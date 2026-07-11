@file:Suppress("UNCHECKED_CAST")

package org.orynnx.outerview.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import hk.uwu.reareye.funcardcore.hostapi.FunCardHostContract
import hk.uwu.reareye.funcardcore.hostapi.IFunCardHostConnection
import hk.uwu.reareye.funcardcore.hostapi.IFunCardHostService
import hk.uwu.reareye.funcardcore.internal.ManagedHostPaths
import hk.uwu.reareye.funcardcore.internal.SecureManifestXml
import hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveDexKitFieldValue
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

@OptIn(DexKitExperimentalApi::class)
class CustomRearCardHook : YukiBaseHooker() {
    private data class HostCard(
        val cardId: String,
        val business: String,
        val displayName: String,
        val templatePath: String,
        val sha256: String,
        val notificationId: Int,
        val updatedAt: Long,
        val enabled: Boolean = false,
        val rearParam: String = "{}",
        val focusParam: String = "{}",
    )

    private data class RuntimeEvidence(
        val notificationSeen: Boolean = false,
        val liveWidgetContains: Boolean = false,
        val loadAttempted: Boolean = false,
        val loadSucceeded: Boolean = false,
        val actualTemplatePath: String? = null,
        val lastCommandId: String? = null,
        val lastEventAt: Long = 0L,
        val lastError: String? = null,
        val runtimeActivated: Boolean = false,
    )

    companion object {
        private const val TAG = "FunCardManager-Hook"
        private const val TESTER_PACKAGE = FunCardHostContract.PROVIDER_PACKAGE
        private const val HOST_PACKAGE = "com.xiaomi.subscreencenter"
        private const val DIRECT_RUNTIME_MARKER = "__outerview_host_direct__"
        private const val MAX_TEMPLATE_BYTES = 16L * 1024L * 1024L
        private val SAFE_CARD_ID = Regex("[a-f0-9]{32}")
        private val SYSTEM_TEMPLATES = linkedMapOf(
            "alarm" to ("闹钟" to "alarm"),
            "carHailing" to ("打车" to "car_hailing"),
            "xiaomiev" to ("汽车" to "ev"),
            "foodDelivery" to ("外卖" to "food_delivery"),
            "mihomeCamera" to ("米家摄像头" to "miHomeCamera"),
            "music" to ("音乐" to "music"),
            "incall" to ("通话" to "phone"),
            "privacy" to ("隐身模式" to "privacy"),
            "sports_schedule" to ("赛程" to "sports_schedule"),
            "stock" to ("股票" to "stock"),
            "countdown" to ("倒计时" to "timer"),
        )
    }

    private val providerInstanceId = UUID.randomUUID().toString()
    private val cards = ConcurrentHashMap<String, HostCard>()
    private val evidence = ConcurrentHashMap<String, RuntimeEvidence>()

    @Volatile private var hostContext: Context? = null
    @Volatile private var manager: Any? = null
    @Volatile private var receiverRegistered = false
    private var dexKitBridge: DexKitCacheBridge.RecyclableBridge? = null

    private val hostBinder = object : IFunCardHostService.Stub() {
        override fun getCapabilities(): Bundle {
            enforceCaller()
            val context = hostContext
            val hostVersion = runCatching {
                context?.packageManager?.getPackageInfo(HOST_PACKAGE, 0)?.versionName
            }.getOrNull().orEmpty()
            return Bundle().apply {
                putInt(FunCardHostContract.Keys.API_VERSION, FunCardHostContract.API_VERSION)
                putString(FunCardHostContract.Keys.PROVIDER_PACKAGE, FunCardHostContract.PROVIDER_PACKAGE)
                putString(FunCardHostContract.Keys.PROVIDER_INSTANCE_ID, providerInstanceId)
                putString(FunCardHostContract.Keys.HOST_VERSION, hostVersion)
                putBoolean(FunCardHostContract.Keys.HOOK_READY, context != null)
                putBoolean(FunCardHostContract.Keys.MANAGER_CAPTURED, manager != null)
            }
        }

        override fun listSystemTemplates(): Bundle {
            enforceCaller()
            val activeBusinesses = managerBusinesses()
            val persistence = persistentBusinesses()
            val items = ArrayList<Bundle>()
            SYSTEM_TEMPLATES.forEach { (business, pair) ->
                val file = File(templateBase(), pair.second)
                if (!file.isFile) return@forEach
                items += Bundle().apply {
                    putString(FunCardHostContract.Keys.BUSINESS, business)
                    putString(FunCardHostContract.Keys.DISPLAY_NAME, pair.first)
                    putString(FunCardHostContract.Keys.SYSTEM_PATH_NAME, pair.second)
                    putBoolean(FunCardHostContract.Keys.ACTIVE, business in activeBusinesses || business in persistence)
                    putBoolean(FunCardHostContract.Keys.TEMPLATE_READABLE, file.canRead())
                    putString(FunCardHostContract.Keys.TEMPLATE_SHA256, sha256(file))
                }
            }
            return Bundle().apply { putParcelableArrayList(FunCardHostContract.Keys.ITEMS, items) }
        }

        override fun listHostCards(): Bundle {
            enforceCaller()
            val items = ArrayList(cards.values.sortedBy { it.cardId }.map(::hostCardBundle))
            return Bundle().apply { putParcelableArrayList(FunCardHostContract.Keys.ITEMS, items) }
        }

        override fun synchronizeCards(): Bundle {
            enforceCaller()
            return runCatching {
                val restored = restoreEnabledCards()
                Bundle().apply {
                    putBoolean(FunCardHostContract.Keys.SUCCESS, true)
                    putString(FunCardHostContract.Keys.MESSAGE, "卡片状态已同步")
                    putInt("restoredCount", restored)
                }
            }.getOrElse {
                Bundle().apply {
                    putBoolean(FunCardHostContract.Keys.SUCCESS, false)
                    putString(FunCardHostContract.Keys.MESSAGE, it.message ?: "同步卡片状态失败")
                    putString(FunCardHostContract.Keys.ERROR_CODE, "SYNCHRONIZE_FAILED")
                }
            }
        }

        override fun installCard(request: Bundle?, zipFd: ParcelFileDescriptor?): Bundle {
            enforceCaller()
            val command = parseRequest(request)
            val fd = zipFd ?: return failure("MISSING_FD", "没有收到模板文件", command)
            return runCatching {
                val target = managedTemplateFile(command.cardId)
                val temp = File(target.parentFile, ".${target.name}.${Process.myPid()}.tmp")
                var total = 0L
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_TEMPLATE_BYTES) { "模板超过 16 MB" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                validateTemplate(temp)
                if (target.exists()) check(target.delete()) { "无法替换旧模板" }
                check(temp.renameTo(target) || runCatching {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                    true
                }.getOrDefault(false)) { "模板部署失败" }
                target.setReadable(true, false)
                val card = HostCard(
                    cardId = command.cardId,
                    business = command.business,
                    displayName = command.displayName,
                    templatePath = target.absolutePath,
                    sha256 = command.sha256,
                    notificationId = command.notificationId,
                    updatedAt = System.currentTimeMillis(),
                )
                cards[card.cardId] = card
                writeRegistry()
                evidence[card.business] = RuntimeEvidence(
                    actualTemplatePath = card.templatePath,
                    lastCommandId = command.commandId,
                    lastEventAt = System.currentTimeMillis(),
                )
                log("install", command, true, "deployed=${card.templatePath}")
                success("模板已部署到宿主", command, card.templatePath)
            }.getOrElse {
                runCatching { fd.close() }
                rememberError(command.business, command.commandId, it.message ?: "安装失败")
                log("install", command, false, it.message.orEmpty())
                failure("INSTALL_FAILED", it.message ?: "安装失败", command)
            }
        }

        override fun uninstallCard(request: Bundle?): Bundle {
            enforceCaller()
            val command = parseRequest(request)
            return runCatching {
                removeBusinessAndAwait(command.business)
                val card = cards[command.cardId]
                val target = card?.templatePath?.let(::File) ?: managedTemplateFile(command.cardId)
                require(isManagedTemplate(target)) { "拒绝删除非托管路径" }
                if (target.exists()) check(target.delete()) { "删除宿主模板失败" }
                cards.remove(command.cardId)
                writeRegistry()
                evidence.remove(command.business)
                log("uninstall", command, true, "removed=${target.absolutePath}")
                success("宿主模板已卸载", command)
            }.getOrElse {
                rememberError(command.business, command.commandId, it.message ?: "卸载失败")
                failure("UNINSTALL_FAILED", it.message ?: "卸载失败", command)
            }
        }

        override fun deleteAllCards(request: Bundle?): Bundle {
            enforceCaller()
            val commandId = request?.getString(FunCardHostContract.Keys.COMMAND_ID).orEmpty()
                .ifBlank { "delete_all_${System.currentTimeMillis()}" }
            return runCatching {
                val snapshot = cards.values.toList()
                val runtimeBusinesses = managerOuterViewBusinesses()
                val businesses = (snapshot.map { it.business } + runtimeBusinesses).toSet()
                businesses.forEach(::removeBusinessAndAwait)
                File(templateBase()).listFiles().orEmpty().forEach { target ->
                    if (isManagedTemplate(target) && target.exists()) {
                        check(target.delete()) { "删除宿主模板失败：${target.name}" }
                    }
                }
                businesses.forEach(evidence::remove)
                cards.clear()
                writeRegistry()
                Bundle().apply {
                    putBoolean(FunCardHostContract.Keys.SUCCESS, true)
                    putString(FunCardHostContract.Keys.MESSAGE, "已清理 ${businesses.size} 张背屏卡片")
                    putString(FunCardHostContract.Keys.COMMAND_ID, commandId)
                }
            }.getOrElse {
                Bundle().apply {
                    putBoolean(FunCardHostContract.Keys.SUCCESS, false)
                    putString(FunCardHostContract.Keys.MESSAGE, it.message ?: "全部删除失败")
                    putString(FunCardHostContract.Keys.ERROR_CODE, "DELETE_ALL_FAILED")
                    putString(FunCardHostContract.Keys.COMMAND_ID, commandId)
                }
            }
        }

        override fun activateCard(request: Bundle?): Bundle {
            enforceCaller()
            val command = parseRequest(request)
            return runCatching {
                activateCardInHost(command)
                success("卡片已通过宿主原生管线激活", command, businessPath(command.business))
            }.getOrElse {
                rememberError(command.business, command.commandId, it.message ?: "显示失败")
                failure("ACTIVATE_FAILED", it.message ?: "显示失败", command)
            }
        }

        override fun deactivateCard(request: Bundle?): Bundle {
            enforceCaller()
            val command = parseRequest(request)
            return runCatching {
                deactivateCardInHost(command)
                success("卡片已从宿主 runtime 移除", command, businessPath(command.business))
            }.getOrElse {
                rememberError(command.business, command.commandId, it.message ?: "隐藏失败")
                failure("DEACTIVATE_FAILED", it.message ?: "隐藏失败", command)
            }
        }

        override fun getCardDiagnostics(cardId: String?, business: String?, notificationId: Int): Bundle {
            enforceCaller()
            val normalizedBusiness = business?.trim().orEmpty()
            val card = cards[cardId]
            val state = evidence[normalizedBusiness] ?: RuntimeEvidence()
            val path = card?.templatePath ?: state.actualTemplatePath
            val managerContains = managerContains(TESTER_PACKAGE, normalizedBusiness)
            return Bundle().apply {
                putString(FunCardHostContract.Keys.CARD_ID, cardId.orEmpty())
                putString(FunCardHostContract.Keys.BUSINESS, normalizedBusiness)
                putBoolean(FunCardHostContract.Keys.HOOK_READY, hostContext != null)
                putBoolean(FunCardHostContract.Keys.MANAGER_CAPTURED, manager != null)
                putBoolean(FunCardHostContract.Keys.TEMPLATE_READABLE, path?.let { File(it).isFile && File(it).canRead() } == true)
                putBoolean(FunCardHostContract.Keys.HOST_REGISTRY_CONTAINS, card != null)
                putBoolean(FunCardHostContract.Keys.NOTIFICATION_SEEN, state.notificationSeen)
                putBoolean(FunCardHostContract.Keys.RUNTIME_ACTIVATED, state.runtimeActivated)
                putBoolean(FunCardHostContract.Keys.MANAGER_LIST_CONTAINS, managerContains)
                putBoolean(FunCardHostContract.Keys.LIVE_WIDGET_CONTAINS, state.liveWidgetContains && managerContains)
                putBoolean(FunCardHostContract.Keys.LOAD_ATTEMPTED, state.loadAttempted)
                putBoolean(FunCardHostContract.Keys.LOAD_SUCCEEDED, state.loadSucceeded)
                putBoolean(FunCardHostContract.Keys.SYSTEM_PERSISTENCE_CONTAINS, normalizedBusiness in persistentBusinesses())
                path?.let { putString(FunCardHostContract.Keys.TEMPLATE_PATH, it) }
                putString(FunCardHostContract.Keys.LAST_COMMAND_ID, state.lastCommandId)
                putLong(FunCardHostContract.Keys.LAST_EVENT_AT, state.lastEventAt)
                putString(FunCardHostContract.Keys.LAST_ERROR, state.lastError)
                putStringArrayList(FunCardHostContract.Keys.LEGACY_CONFLICTS, ArrayList(legacyConflicts()))
            }
        }
    }

    private data class CardCommand(
        val cardId: String,
        val business: String,
        val displayName: String,
        val sha256: String,
        val notificationId: Int,
        val commandId: String,
        val rearParam: String,
        val focusParam: String,
    )

    override fun onHook() {
        loadApp(HOST_PACKAGE) {
            YLog.info("[$TAG] hook process=$processName")
            val versionCode = resolveHookPackageVersionCode(systemContext, appInfo.packageName, appInfo.sourceDir)
            dexKitBridge = createDexKitCacheBridge(appInfo.packageName, versionCode, appInfo.sourceDir, appInfo.dataDir)
            loadRegistry()

            "com.xiaomi.subscreencenter.SubScreenCenterApp".toClass().resolve().firstMethod {
                name = "attachBaseContext"
                parameterCount = 1
            }.hook().after {
                val baseContext = args[0] as? Context
                hostContext = baseContext?.applicationContext ?: baseContext
                registerServiceReceiver()
                loadRegistry()
                YLog.info(
                    "[$TAG] host attached cards=${cards.size} context=${hostContext != null} receiver=$receiverRegistered"
                )
            }

            installSmartAssistantHooks()
        }
    }

    private fun installSmartAssistantHooks() {
        runCatching {
            val point = resolveManagerInitMethod()
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 1
            }.hook().after {
                manager = instance
                YLog.info("[$TAG] manager captured cards=${cards.size}")
                scheduleEnabledCardRestore()
            }
        }.onFailure { YLog.error("[$TAG] manager hook failed", it) }

        runCatching {
            val point = resolveAllowAppMethod()
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 3
            }.hook().before {
                if ((args[0] as? String) == TESTER_PACKAGE) result = true
            }
        }.onFailure { YLog.error("[$TAG] allow hook failed", it) }

        runCatching {
            val point = resolvePathMethod()
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 2
            }.hook().after {
                val business = args[1] as? String ?: return@after
                val path = businessPath(business) ?: return@after
                result = path
            }
        }.onFailure { YLog.error("[$TAG] path hook failed", it) }

        runCatching {
            val point = resolveParseWidgetMethod()
            val method = point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 2
            }
            val specClass = method.self.returnType
            method.hook().after {
                if (result != null || (args[0] as? String) != TESTER_PACKAGE) return@after
                val business = extractKnownBusiness(args.getOrNull(1)) ?: return@after
                result = specClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .newInstance(business, 0, 500)
                YLog.info("[$TAG] parse fallback business=$business")
            }
        }.onFailure { YLog.error("[$TAG] parse hook failed", it) }

        runCatching {
            val className = resolvePostRunnableClassName()
            className.toClass().resolve().firstConstructor { parameterCount = 5 }.hook().after {
                val packageName = args.getOrNull(2) as? String ?: return@after
                if (packageName != TESTER_PACKAGE) return@after
                val extras = args.getOrNull(4) as? Bundle ?: return@after
                val business = parseBusiness(extras) ?: return@after
                val old = evidence[business] ?: RuntimeEvidence()
                val directRuntime = extras.getBoolean(DIRECT_RUNTIME_MARKER)
                evidence[business] = old.copy(
                    notificationSeen = !directRuntime,
                    runtimeActivated = directRuntime || old.runtimeActivated,
                    lastEventAt = System.currentTimeMillis(),
                )
                cards.values.firstOrNull { it.business == business }?.let { card ->
                    cards[card.cardId] = card.copy(
                        enabled = true,
                        rearParam = extras.getString("miui.rear.param").orEmpty(),
                        focusParam = extras.getString("miui.focus.param").orEmpty(),
                        updatedAt = System.currentTimeMillis(),
                    )
                    runCatching(::writeRegistry)
                }
                YLog.info("[$TAG] notification seen business=$business id=${args.getOrNull(1)}")
            }
        }.onFailure { YLog.error("[$TAG] notification observer failed", it) }

        runCatching {
            val point = resolveNotificationWidgetApplyMethod()
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 1
            }.hook().after {
                val extras = extractField(instance, resolveNotificationWidgetExtrasFieldName()) as? Bundle ?: return@after
                if (extras.getString("package_name") != TESTER_PACKAGE && extras.getString("creator_package") != TESTER_PACKAGE) return@after
                val business = extras.getString("business")?.trim().orEmpty()
                if (business.isBlank()) return@after
                val path = extractField(instance, resolveNotificationWidgetTemplatePathFieldName()) as? String
                    ?: businessPath(business)
                val readable = path?.let { File(it).isFile && File(it).canRead() } == true
                val old = evidence[business] ?: RuntimeEvidence()
                evidence[business] = old.copy(
                    liveWidgetContains = true,
                    loadAttempted = true,
                    loadSucceeded = readable,
                    runtimeActivated = true,
                    actualTemplatePath = path,
                    lastEventAt = System.currentTimeMillis(),
                    lastError = if (readable) null else "MAML 模板路径不可读",
                )
                YLog.info("[$TAG] widget applied business=$business path=$path readable=$readable")
            }
        }.onFailure { YLog.error("[$TAG] widget observer failed", it) }
    }

    private fun registerServiceReceiver() {
        if (receiverRegistered) return
        val context = hostContext ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != FunCardHostContract.ACTION_REQUEST_SERVICE) return
                YLog.info("[$TAG] host service request received")
                val binder = intent.getBundleExtra(FunCardHostContract.EXTRA_BUNDLE)
                    ?.getBinder(FunCardHostContract.EXTRA_CALLBACK) ?: run {
                    YLog.error("[$TAG] host service request missing callback binder")
                    return
                }
                val callback = IFunCardHostConnection.Stub.asInterface(binder) ?: return
                runCatching {
                    callback.onServiceConnected(hostBinder)
                    YLog.info("[$TAG] host service callback delivered")
                }
                    .onFailure { YLog.error("[$TAG] service callback failed", it) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(FunCardHostContract.ACTION_REQUEST_SERVICE), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(FunCardHostContract.ACTION_REQUEST_SERVICE))
        }
        receiverRegistered = true
    }

    private fun enforceCaller() {
        val context = hostContext ?: error("Host 尚未初始化")
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        check(TESTER_PACKAGE in packages) { "拒绝未授权调用者" }
    }

    private fun parseRequest(request: Bundle?): CardCommand {
        val bundle = request ?: Bundle.EMPTY
        val cardId = bundle.getString(FunCardHostContract.Keys.CARD_ID)?.trim().orEmpty()
        val business = bundle.getString(FunCardHostContract.Keys.BUSINESS)?.trim().orEmpty()
        require(cardId.matches(SAFE_CARD_ID)) { "cardId 无效" }
        require(business == "reareye_custom_$cardId") { "business 与 cardId 不匹配" }
        return CardCommand(
            cardId,
            business,
            bundle.getString(FunCardHostContract.Keys.DISPLAY_NAME).orEmpty().ifBlank { business },
            bundle.getString(FunCardHostContract.Keys.TEMPLATE_SHA256).orEmpty(),
            bundle.getInt(FunCardHostContract.Keys.NOTIFICATION_ID),
            bundle.getString(FunCardHostContract.Keys.COMMAND_ID).orEmpty(),
            bundle.getString(FunCardHostContract.Keys.REAR_PARAM).orEmpty(),
            bundle.getString(FunCardHostContract.Keys.FOCUS_PARAM).orEmpty(),
        )
    }

    private fun activateCardInHost(command: CardCommand, persist: Boolean = true) {
        val card = cards[command.cardId] ?: error("宿主 registry 中不存在该卡片")
        require(File(card.templatePath).isFile && File(card.templatePath).canRead()) { "宿主模板不可读" }
        require(command.rearParam.isNotBlank() && command.focusParam.isNotBlank()) { "卡片 payload 为空" }
        val runtimeId = syntheticRuntimeId(command.notificationId)
        val compositeKey = "$TESTER_PACKAGE:${command.business}:$runtimeId"
        val extras = Bundle().apply {
            putString("package_name", TESTER_PACKAGE)
            putString("creator_package", TESTER_PACKAGE)
            putString("business", command.business)
            putInt("index", 0)
            putInt("priority", 500)
            putInt("notification_id", runtimeId)
            putInt("widget_id", runtimeId)
            putString("composite_key", compositeKey)
            putLong("timestamp", System.currentTimeMillis())
            putBoolean("disable_popup", true)
            putBoolean("show_time_tip", true)
            putString("miui.rear.param", command.rearParam)
            putString("miui.focus.param", command.focusParam)
            putString("__fun_card_id__", command.cardId)
            putBoolean(DIRECT_RUNTIME_MARKER, true)
        }
        runOnMainThread {
            val target = manager ?: error("Smart Assistant manager 尚未就绪")
            if (managerContains(TESTER_PACKAGE, command.business)) {
                invokeManagerRemoveBusiness(target, TESTER_PACKAGE, command.business)
            }
            val runnableClass = resolvePostRunnableClassName().toClass()
            val constructor = runnableClass.declaredConstructors.firstOrNull { it.parameterCount == 5 }
                ?: error("无法解析 Smart Assistant Post Runnable 构造器")
            constructor.isAccessible = true
            val runnable = constructor.newInstance(
                target,
                runtimeId,
                TESTER_PACKAGE,
                compositeKey,
                extras,
            ) as? Runnable ?: error("宿主 Post Runnable 类型不匹配")
            runnable.run()
        }
        val now = System.currentTimeMillis()
        cards[card.cardId] = card.copy(
            enabled = true,
            rearParam = command.rearParam,
            focusParam = command.focusParam,
            updatedAt = now,
        )
        evidence[command.business] = (evidence[command.business] ?: RuntimeEvidence()).copy(
            notificationSeen = false,
            runtimeActivated = true,
            lastCommandId = command.commandId,
            lastEventAt = now,
            lastError = null,
        )
        if (persist) writeRegistry()
        log("activate", command, true, "compositeKey=$compositeKey")
    }

    private fun deactivateCardInHost(command: CardCommand, persist: Boolean = true) {
        val card = cards[command.cardId] ?: error("宿主 registry 中不存在该卡片")
        removeBusinessAndAwait(command.business)
        val now = System.currentTimeMillis()
        cards[card.cardId] = card.copy(enabled = false, updatedAt = now)
        evidence[command.business] = (evidence[command.business] ?: RuntimeEvidence()).copy(
            liveWidgetContains = false,
            runtimeActivated = false,
            lastCommandId = command.commandId,
            lastEventAt = now,
            lastError = null,
        )
        if (persist) writeRegistry()
        log("deactivate", command, true, "removed business=${command.business}")
    }

    private fun removeBusinessAndAwait(business: String, timeoutMs: Long = 5_000L) {
        val target = manager ?: error("Smart Assistant manager 尚未就绪")
        runOnMainThread {
            if (managerContains(TESTER_PACKAGE, business)) {
                invokeManagerRemoveBusiness(target, TESTER_PACKAGE, business)
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (managerContains(TESTER_PACKAGE, business) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L)
        }
        check(!managerContains(TESTER_PACKAGE, business)) { "背屏 runtime 移除超时：$business" }
    }

    private fun syntheticRuntimeId(notificationId: Int): Int = 100_000_000 + notificationId

    private fun runOnMainThread(timeoutMs: Long = 5_000L, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching(action).onFailure(error::set)
            latch.countDown()
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "等待宿主主线程执行超时" }
        error.get()?.let { throw it }
    }

    private fun invokeManagerRemoveBusiness(target: Any, packageName: String, business: String) {
        val point = resolveRemoveBusinessMethod()
        target.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 2
        }.invoke(packageName, business)
    }

    private fun scheduleEnabledCardRestore() {
        val handler = Handler(Looper.getMainLooper())
        listOf(800L, 2_000L).forEach { delay ->
            handler.postDelayed({
                restoreEnabledCards()
            }, delay)
        }
    }

    private fun restoreEnabledCards(): Int {
        check(manager != null) { "Smart Assistant manager 尚未就绪" }
        var restored = 0
        cards.values.filter { it.enabled }.forEach { card ->
            if (managerContains(TESTER_PACKAGE, card.business)) return@forEach
            val command = CardCommand(
                card.cardId,
                card.business,
                card.displayName,
                card.sha256,
                card.notificationId,
                "restore_${System.currentTimeMillis()}",
                card.rearParam,
                card.focusParam,
            )
            runCatching { activateCardInHost(command, persist = false) }
                .onSuccess { restored++ }
                .onFailure { rememberError(card.business, command.commandId, it.message ?: "恢复失败") }
        }
        return restored
    }

    private fun success(message: String, command: CardCommand, path: String? = null): Bundle =
        diagnosticBundle(command, true, message, null, path)

    private fun failure(code: String, message: String, command: CardCommand): Bundle =
        diagnosticBundle(command, false, message, code, businessPath(command.business))

    private fun diagnosticBundle(
        command: CardCommand,
        success: Boolean,
        message: String,
        errorCode: String?,
        path: String?,
    ): Bundle = hostBinder.getCardDiagnostics(command.cardId, command.business, command.notificationId).apply {
        putBoolean(FunCardHostContract.Keys.SUCCESS, success)
        putString(FunCardHostContract.Keys.MESSAGE, message)
        errorCode?.let { putString(FunCardHostContract.Keys.ERROR_CODE, it) }
        path?.let { putString(FunCardHostContract.Keys.TEMPLATE_PATH, it) }
        putString(FunCardHostContract.Keys.LAST_COMMAND_ID, command.commandId)
    }

    private fun hostCardBundle(card: HostCard) = Bundle().apply {
        putString(FunCardHostContract.Keys.CARD_ID, card.cardId)
        putString(FunCardHostContract.Keys.BUSINESS, card.business)
        putString(FunCardHostContract.Keys.DISPLAY_NAME, card.displayName)
        putString(FunCardHostContract.Keys.TEMPLATE_PATH, card.templatePath)
        putString(FunCardHostContract.Keys.TEMPLATE_SHA256, card.sha256)
        putInt(FunCardHostContract.Keys.NOTIFICATION_ID, card.notificationId)
    }

    private fun managedTemplateFile(cardId: String): File {
        return ManagedHostPaths.templateFile(File(templateBase()), cardId)
    }

    private fun isManagedTemplate(file: File): Boolean {
        return ManagedHostPaths.isManagedTemplate(File(templateBase()), file)
    }

    private fun templateBase(): String =
        "/data/system/theme_magic/users/${Process.myUid() / 100000}/subscreencenter/smart_assistant"

    private fun registryDir(): File = File(
        "/data/system/theme_magic/users/${Process.myUid() / 100000}/subscreencenter/reareye_custom_cards"
    )

    private fun registryFile(): File = File(registryDir(), "registry.json")

    private fun businessPath(business: String): String? =
        cards.values.firstOrNull { it.business == business }?.templatePath

    private fun loadRegistry() {
        val file = registryFile()
        if (!file.isFile) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("cards") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val cardId = item.optString("cardId")
                val business = item.optString("business")
                val path = item.optString("templatePath")
                val target = File(path)
                if (!cardId.matches(SAFE_CARD_ID) || business != "reareye_custom_$cardId" ||
                    !isManagedTemplate(target) || !target.isFile
                ) continue
                cards[cardId] = HostCard(
                    cardId, business, item.optString("displayName", business), path,
                    item.optString("sha256"), item.optInt("notificationId"), item.optLong("updatedAt"),
                    item.optBoolean("enabled"), item.optString("rearParam", "{}"),
                    item.optString("focusParam", "{}"),
                )
            }
        }.onFailure { YLog.error("[$TAG] registry load failed", it) }
    }

    private fun writeRegistry() {
        val dir = registryDir()
        if (!dir.exists()) check(dir.mkdirs()) { "无法创建宿主 registry 目录" }
        val array = JSONArray()
        cards.values.sortedBy { it.cardId }.forEach { card ->
            array.put(JSONObject().put("cardId", card.cardId).put("business", card.business)
                .put("displayName", card.displayName).put("templatePath", card.templatePath)
                .put("sha256", card.sha256).put("notificationId", card.notificationId)
                .put("updatedAt", card.updatedAt).put("enabled", card.enabled)
                .put("rearParam", card.rearParam).put("focusParam", card.focusParam))
        }
        val target = registryFile()
        val temp = File(dir, ".registry.${Process.myPid()}.tmp")
        temp.writeText(JSONObject().put("schemaVersion", 3).put("cards", array).toString())
        if (target.exists()) check(target.delete()) { "无法替换宿主 registry" }
        check(temp.renameTo(target)) { "宿主 registry 原子替换失败" }
        target.setReadable(true, true)
    }

    private fun validateTemplate(file: File) {
        require(file.isFile && file.length() in 1..MAX_TEMPLATE_BYTES) { "模板文件无效" }
        ZipFile(file).use { zip ->
            require(zip.size() <= 1024) { "模板条目过多" }
            val entry = zip.getEntry("manifest.xml") ?: error("顶层缺少 manifest.xml")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            require(bytes.size <= 2 * 1024 * 1024) { "manifest.xml 过大" }
            val document = SecureManifestXml.parse(bytes)
            require(document.documentElement.tagName == "Widget") { "根节点必须是 Widget" }
            require(document.documentElement.getAttribute("version") == "2") { "只支持 Widget version=2" }
        }
    }

    private fun parseBusiness(extras: Bundle): String? {
        extras.getString("business")?.trim()?.takeIf { businessPath(it) != null }?.let { return it }
        listOf("miui.rear.param", "miui.focus.param").forEach { key ->
            val root = runCatching { JSONObject(extras.getString(key).orEmpty()) }.getOrNull() ?: return@forEach
            val candidates = listOf(
                root.optString("business"),
                root.optJSONObject("rear_param_v1")?.optString("business"),
                root.optJSONObject("param_v2")?.optString("business"),
            )
            candidates.filterNotNull().firstOrNull { businessPath(it) != null }?.let { return it }
        }
        return null
    }

    private fun extractKnownBusiness(value: Any?): String? {
        val known = cards.values.map { it.business }.toSet()
        if (known.isEmpty() || value == null) return null
        if (value is String) return known.firstOrNull { value.contains(it) }
        if (value is Bundle) return parseBusiness(value)
        val text = runCatching { value.toString() }.getOrNull().orEmpty()
        known.firstOrNull { text.contains(it) }?.let { return it }
        var current: Class<*>? = value.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                val fieldValue = runCatching { field.isAccessible = true; field.get(value) }.getOrNull()
                if (fieldValue is String) known.firstOrNull { fieldValue.contains(it) }?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun managerBusinesses(): Set<String> {
        val target = manager ?: return emptySet()
        val list = runCatching {
            target.asResolver().firstField { name = resolveManagerListFieldName() }.get<Any>() as? Iterable<*>
        }.getOrNull() ?: return emptySet()
        return list.mapNotNull { widget -> managerWidgetBundle(widget)?.getString("business") }.toSet()
    }

    private fun managerOuterViewBusinesses(): Set<String> {
        val target = manager ?: return emptySet()
        val list = runCatching {
            target.asResolver().firstField { name = resolveManagerListFieldName() }.get<Any>() as? Iterable<*>
        }.getOrNull() ?: return emptySet()
        return list.mapNotNull { widget ->
            val extras = managerWidgetBundle(widget) ?: return@mapNotNull null
            val pkg = extras.getString("package_name") ?: extras.getString("creator_package")
            val business = extras.getString("business")
            business?.takeIf { pkg == TESTER_PACKAGE && it.startsWith("reareye_custom_") }
        }.toSet()
    }

    private fun managerContains(packageName: String, business: String): Boolean {
        val target = manager ?: return false
        val list = runCatching {
            target.asResolver().firstField { name = resolveManagerListFieldName() }.get<Any>() as? Iterable<*>
        }.getOrNull() ?: return false
        return list.any { widget ->
            val extras = managerWidgetBundle(widget) ?: return@any false
            val pkg = extras.getString("package_name") ?: extras.getString("creator_package")
            extras.getString("business") == business && pkg == packageName
        }
    }

    private fun managerWidgetBundle(widget: Any?): Bundle? {
        widget ?: return null
        return runCatching {
            widget.asResolver().firstField { name = resolveWidgetExtrasFieldName() }.get<Bundle?>()
        }.getOrNull()
    }

    private fun persistentBusinesses(): Set<String> {
        val file = File(
            "/data/system/theme_magic/users/${Process.myUid() / 100000}/subscreencenter/notification/notification_widget.json"
        )
        return runCatching {
            val raw = file.readText().removePrefix("\uFEFF")
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull {
                array.optJSONObject(it)?.optJSONObject("extra")?.optString("business")?.takeIf(String::isNotBlank)
            }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun legacyConflicts(): List<String> {
        val conflicts = linkedSetOf<String>()
        manager?.let { target ->
            val list = runCatching {
                target.asResolver().firstField { name = resolveManagerListFieldName() }.get<Any>() as? Iterable<*>
            }.getOrNull()
            list?.forEach { widget ->
                val extras = managerWidgetBundle(widget) ?: return@forEach
                val pkg = extras.getString("package_name").orEmpty()
                val business = extras.getString("business").orEmpty()
                val path = extractManagedPath(extras)
                if (pkg == "com.example.codexpanel" || business.startsWith("codex_") ||
                    business.startsWith("reareye_fun_") || path.contains("/re_codex_")
                ) conflicts += "$pkg/$business"
            }
        }
        return conflicts.toList()
    }

    private fun extractManagedPath(extras: Bundle): String =
        extras.getString("path") ?: extras.getString("template_path") ?: ""

    private fun extractField(owner: Any?, fieldName: String): Any? {
        var current: Class<*>? = owner?.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                return runCatching { field.isAccessible = true; field.get(owner) }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun rememberError(business: String, commandId: String, message: String) {
        val old = evidence[business] ?: RuntimeEvidence()
        evidence[business] = old.copy(lastCommandId = commandId, lastEventAt = System.currentTimeMillis(), lastError = message)
    }

    private fun log(operation: String, command: CardCommand, success: Boolean, message: String) {
        YLog.info(
            "[$TAG] operation=$operation commandId=${command.commandId} cardId=${command.cardId} " +
                "business=${command.business} notificationId=${command.notificationId} result=$success message=$message"
        )
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun resolveCachedMethod(
        key: String,
        crossinline finder: DexKitBridge.() -> MethodData?,
    ): DexKitMethodInjectionPoint {
        val bridge = dexKitBridge ?: error("DexKit 未初始化")
        return resolveDexKitMethodInjectionPoint(bridge, "FUN_CARD_$key") { finder() }
            ?.takeIf { it.className.isNotBlank() && it.methodName.isNotBlank() }
            ?: error("无法解析方法 $key")
    }

    private inline fun resolveCachedClass(
        key: String,
        crossinline finder: DexKitBridge.() -> ClassData?,
    ): String {
        val bridge = dexKitBridge ?: error("DexKit 未初始化")
        return resolveDexKitClassValue(bridge, "FUN_CARD_$key") { finder() }
            ?.takeIf(String::isNotBlank) ?: error("无法解析类 $key")
    }

    private inline fun resolveCachedField(
        key: String,
        crossinline finder: DexKitBridge.() -> FieldData?,
    ): String {
        val bridge = dexKitBridge ?: error("DexKit 未初始化")
        return resolveDexKitFieldValue(bridge, "FUN_CARD_$key") { finder() }
            ?.takeIf(String::isNotBlank) ?: error("无法解析字段 $key")
    }

    private fun resolveManagerInitMethod() = resolveCachedMethod("MANAGER_INIT") {
        findMethod { matcher {
            paramTypes(Context::class.java); returnType = "void"
            usingStrings("SmartAssistantManager initialized", "SmartAssistant not supported, skip manager initialization")
        } }.singleOrNull()
    }

    private fun resolveParseWidgetMethod() = resolveCachedMethod("PARSE_WIDGET") {
        findMethod { matcher {
            paramCount(2); usingStrings("Found business in rear.paramV1: %s", "No business found for %s and not in config")
        } }.singleOrNull()
    }

    private fun resolveUtilsClassName() = resolveParseWidgetMethod().className

    private fun resolvePathMethod() = resolveCachedMethod("RESOLVE_PATH") {
        findMethod { matcher {
            declaredClass = resolveUtilsClassName(); paramTypes(String::class.java, String::class.java)
            returnType = "java.lang.String"; usingStrings("unified.music", "music")
        } }.singleOrNull()
    }

    private fun resolveAllowAppMethod() = resolveCachedMethod("ALLOW_APP") {
        findMethod { matcher {
            declaredClass = resolveUtilsClassName()
            paramTypes("java.lang.String", "java.util.Set", "java.util.Map")
            returnType = "boolean"
            usingStrings("Music app %s allowed: %s (music switch: %s)", "Multi-business app %s allowed: false (no business enabled)")
        } }.singleOrNull()
    }

    private fun resolvePostRunnableClassName() = resolveCachedClass("POST_RUNNABLE") {
        findClass { matcher {
            usingStrings("No valid params: %s", "Using compositeKey: %s (business: %s)")
        } }.singleOrNull()
    }

    private fun resolveManagerListFieldName() = resolveCachedField("MANAGER_LIST") {
        val managerClass = resolveManagerInitMethod().className
        findField { searchPackages(managerClass.substringBeforeLast('.')); matcher {
            declaredClass = managerClass; type = "java.util.ArrayList"
            readMethods { add { usingStrings("Inserted widget at position %d, type=%s, new display index=%d") } }
        } }.singleOrNull()
    }

    private fun resolveRemoveBusinessMethod() = resolveCachedMethod("REMOVE_BUSINESS") {
        val managerClass = resolveManagerInitMethod().className
        findMethod { matcher {
            declaredClass = managerClass
            paramTypes(String::class.java, String::class.java)
            returnType = "void"
            usingStrings("Removing widgets for %s:%s")
        } }.singleOrNull()
    }

    private fun resolveWidgetExtrasFieldName() = resolveCachedField("WIDGET_EXTRAS") {
        val managerClass = resolveManagerInitMethod().className
        val insertData = findMethod { matcher {
            declaredClass = managerClass; paramCount(1); returnType = "void"
            usingStrings("Inserted widget at position %d, type=%s, new display index=%d")
        } }.singleOrNull() ?: return@resolveCachedField null
        val insertPoint = DexKitMethodInjectionPoint(insertData.className, insertData.methodName)
        val recordClass = insertPoint.className.toClass().resolve().firstMethod {
            name = insertPoint.methodName
            parameterCount = 1
        }.self.parameterTypes.firstOrNull()?.name ?: return@resolveCachedField null
        findField { searchPackages(recordClass.substringBeforeLast('.')); matcher {
            declaredClass = recordClass; type = "android.os.Bundle"
        } }.singleOrNull()
    }

    private fun resolveNotificationWidgetApplyMethod() = resolveCachedMethod("WIDGET_APPLY") {
        findMethod { matcher { returnType = "void"; usingStrings("notification_received", "params_transferred") } }
            .singleOrNull()
    }

    private fun resolveNotificationWidgetHostClassName(): String =
        resolveNotificationWidgetApplyMethod().className.toClass().superclass?.name ?: error("无法解析通知卡片类")

    private fun resolveNotificationWidgetTemplatePathFieldName() = resolveCachedField("WIDGET_PATH") {
        val hostClass = resolveNotificationWidgetHostClassName()
        findField { searchPackages(hostClass.substringBeforeLast('.')); matcher {
            declaredClass = hostClass; type = "java.lang.String"
            readMethods { add { declaredClass = hostClass; paramTypes(Context::class.java); returnType = "android.view.View"; usingStrings("onCreate path =") } }
        } }.singleOrNull()
    }

    private fun resolveNotificationWidgetExtrasFieldName() = resolveCachedField("LIVE_WIDGET_EXTRAS") {
        val baseClass = resolveNotificationWidgetHostClassName().toClass().superclass?.name ?: return@resolveCachedField null
        val apply = resolveNotificationWidgetApplyMethod()
        findField { searchPackages(baseClass.substringBeforeLast('.')); matcher {
            declaredClass = baseClass; type = "android.os.Bundle"
            readMethods { add { declaredClass = apply.className; name = apply.methodName; paramCount(1); returnType = "void" } }
        } }.singleOrNull()
    }
}
