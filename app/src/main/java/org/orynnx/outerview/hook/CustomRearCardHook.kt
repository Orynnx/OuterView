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
import android.util.AtomicFile
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
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        val pendingDelete: Boolean = false,
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

    private data class RuntimeWidgetIdentity(
        val notificationId: Int,
        val compositeKey: String,
    )

    private data class PostRunnableObservation(
        val packageName: String,
        val notificationId: Int,
        val extras: Bundle,
    )

    private enum class RuntimePresence { PRESENT, ABSENT, UNKNOWN }

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
    private val suppressedBusinesses = ConcurrentHashMap.newKeySet<String>()
    private val installingBusinesses = ConcurrentHashMap.newKeySet<String>()
    private val pendingBulkBusinesses = ConcurrentHashMap.newKeySet<String>()
    private val pendingBulkTemplates = ConcurrentHashMap.newKeySet<String>()
    private val operationEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val pendingPostRunnables = Collections.synchronizedMap(
        WeakHashMap<Any, PostRunnableObservation>(),
    )
    private val lifecycleLock = Any()
    private val registryLock = Any()
    private val runtimeReconcileScheduled = AtomicBoolean(false)

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
            val target = managedTemplateFile(command.cardId)
            val temp = File(target.parentFile, ".${target.name}.${Process.myPid()}.tmp")
            val installEpoch = synchronized(lifecycleLock) {
                installingBusinesses.add(command.business)
                nextOperationEpoch(command.business)
            }
            return runCatching {
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
                val card = synchronized(lifecycleLock) {
                    check(currentOperationEpoch(command.business) == installEpoch) {
                        "模板安装已被较新的删除操作取消"
                    }
                    if (target.exists()) check(target.delete()) { "无法替换旧模板" }
                    check(temp.renameTo(target) || runCatching {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                        true
                    }.getOrDefault(false)) { "模板部署失败" }
                    target.setReadable(true, false)
                    val installed = HostCard(
                        cardId = command.cardId,
                        business = command.business,
                        displayName = command.displayName,
                        templatePath = target.absolutePath,
                        sha256 = command.sha256,
                        notificationId = command.notificationId,
                        updatedAt = System.currentTimeMillis(),
                    )
                    pendingBulkBusinesses.remove(command.business)
                    pendingBulkTemplates.remove(target.absolutePath)
                    suppressedBusinesses.remove(command.business)
                    cards[installed.cardId] = installed
                    writeRegistry()
                    evidence[installed.business] = RuntimeEvidence(
                        actualTemplatePath = installed.templatePath,
                        lastCommandId = command.commandId,
                        lastEventAt = System.currentTimeMillis(),
                    )
                    installed
                }
                log("install", command, true, "deployed=${card.templatePath}")
                success("模板已部署到宿主", command, card.templatePath)
            }.getOrElse {
                runCatching { fd.close() }
                runCatching { temp.delete() }
                rememberError(command.business, command.commandId, it.message ?: "安装失败")
                log("install", command, false, it.message.orEmpty())
                failure("INSTALL_FAILED", it.message ?: "安装失败", command)
            }.also {
                installingBusinesses.remove(command.business)
            }
        }

        override fun uninstallCard(request: Bundle?): Bundle {
            enforceCaller()
            val command = parseRequest(request)
            return runCatching {
                val target = cards[command.cardId]?.templatePath?.let(::File)
                    ?: managedTemplateFile(command.cardId)
                require(isManagedTemplate(target)) { "拒绝删除非托管路径" }
                synchronized(lifecycleLock) {
                    nextOperationEpoch(command.business)
                    suppressedBusinesses.add(command.business)
                    cards[command.cardId]?.let { card ->
                        cards[command.cardId] = card.copy(
                            enabled = false,
                            pendingDelete = true,
                            updatedAt = System.currentTimeMillis(),
                        )
                        writeRegistry()
                    }
                }
                submitRuntimeRemoval(command.business)
                val completed = finalizePendingDeletion(command.cardId, command.business)
                if (!completed) schedulePendingDeletionCleanup(command.cardId, command.business)
                log("uninstall", command, true, "pending=${!completed} target=${target.absolutePath}")
                success(
                    if (completed) "宿主模板已安全删除" else "删除请求已提交；Runtime 退出后将安全清理宿主模板",
                    command,
                ).apply {
                    putBoolean(FunCardHostContract.Keys.CLEANUP_PENDING, !completed)
                }
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
                val runtimeBusinesses = managerOuterViewBusinesses()
                val (snapshot, businesses) = synchronized(lifecycleLock) {
                    val currentCards = cards.values.toList()
                    val cleanupTargets = File(templateBase()).listFiles().orEmpty()
                        .filter(::isManagedTemplate)
                        .map { it.absolutePath }
                        .toSet()
                    val ownedBusinesses = (
                        currentCards.map { it.business } + runtimeBusinesses +
                            cleanupTargets.map { File(it).name }
                        ).toSet()
                    currentCards.forEach { card ->
                        nextOperationEpoch(card.business)
                        suppressedBusinesses.add(card.business)
                        cards[card.cardId] = card.copy(
                            enabled = false,
                            pendingDelete = true,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                    suppressedBusinesses.addAll(ownedBusinesses)
                    pendingBulkBusinesses.addAll(ownedBusinesses)
                    pendingBulkTemplates.addAll(cleanupTargets)
                    // Disable first so scheduled restoration cannot race cleanup.
                    writeRegistry()
                    currentCards to ownedBusinesses
                }
                businesses.forEach(::submitRuntimeRemoval)
                snapshot.forEach { card ->
                    if (!finalizePendingDeletion(card.cardId, card.business)) {
                        schedulePendingDeletionCleanup(card.cardId, card.business)
                    }
                }
                val bulkComplete = cleanupPendingBulk()
                if (!bulkComplete) scheduleDeleteAllCleanup()
                val pending = !bulkComplete || cards.values.any { it.pendingDelete }
                Bundle().apply {
                    putBoolean(FunCardHostContract.Keys.SUCCESS, true)
                    putString(
                        FunCardHostContract.Keys.MESSAGE,
                        if (pending) "已提交 ${businesses.size} 张背屏卡片的安全清理"
                        else "已安全删除 ${businesses.size} 张背屏卡片",
                    )
                    putString(FunCardHostContract.Keys.COMMAND_ID, commandId)
                    putBoolean(FunCardHostContract.Keys.CLEANUP_PENDING, pending)
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
                cards.values.filter { it.pendingDelete }.forEach { card ->
                    runCatching { submitRuntimeRemoval(card.business) }
                        .onFailure { YLog.warn("[$TAG] startup pending delete failed business=${card.business}", it) }
                    schedulePendingDeletionCleanup(card.cardId, card.business)
                }
                if (pendingBulkBusinesses.isNotEmpty() || pendingBulkTemplates.isNotEmpty()) {
                    pendingBulkBusinesses.forEach { business ->
                        suppressedBusinesses.add(business)
                        runCatching { submitRuntimeRemoval(business) }
                            .onFailure { YLog.warn("[$TAG] startup bulk cleanup failed business=$business", it) }
                    }
                    scheduleDeleteAllCleanup()
                }
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
                pendingPostRunnables[instance] = PostRunnableObservation(
                    packageName = packageName,
                    notificationId = (args.getOrNull(1) as? Int) ?: extras.getInt("notification_id", 0),
                    extras = Bundle(extras),
                )
            }
            className.toClass().resolve().firstMethod {
                name = "run"
                parameterCount = 0
            }.hook().after {
                val observation = pendingPostRunnables.remove(instance) ?: return@after
                handlePostRunnableCompleted(observation)
            }
        }.onFailure { YLog.error("[$TAG] notification runnable observer failed", it) }

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
                val accepted = synchronized(lifecycleLock) {
                    if (business in suppressedBusinesses ||
                        cards.values.any { it.business == business && it.pendingDelete }
                    ) {
                        false
                    } else {
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
                        dispatchRuntimeEvent(business, "widget_applied")
                        true
                    }
                }
                if (!accepted) {
                    scheduleSuppressedRuntimeEject(business, extras)
                    YLog.info("[$TAG] ignored late widget callback business=$business")
                    return@after
                }
                YLog.info("[$TAG] widget applied business=$business path=$path readable=$readable")
            }
        }.onFailure { YLog.error("[$TAG] widget observer failed", it) }

        installRuntimeRemovalObservers()
    }

    private fun handlePostRunnableCompleted(observation: PostRunnableObservation) {
        if (observation.packageName != TESTER_PACKAGE) return
        val extras = observation.extras
        val business = parseBusiness(extras) ?: return
        val directRuntime = extras.getBoolean(DIRECT_RUNTIME_MARKER)
        val accepted = synchronized(lifecycleLock) {
            if (business in suppressedBusinesses ||
                cards.values.any { it.business == business && it.pendingDelete }
            ) {
                false
            } else {
                val old = evidence[business] ?: RuntimeEvidence()
                evidence[business] = old.copy(
                    notificationSeen = !directRuntime,
                    runtimeActivated = directRuntime || old.runtimeActivated,
                    lastEventAt = System.currentTimeMillis(),
                )
                // Direct activation is not committed until activateCardInHost persists
                // enabled=true.  That method emits the single authoritative event.
                if (!directRuntime) dispatchRuntimeEvent(business, "notification_observed")
                true
            }
        }
        if (!accepted) {
            scheduleSuppressedRuntimeEject(business, extras)
            YLog.info("[$TAG] ejected late completed runnable business=$business")
            return
        }
        YLog.info("[$TAG] notification runnable completed business=$business id=${observation.notificationId}")
    }

    private fun installRuntimeRemovalObservers() {
        runCatching {
            val point = resolveRemoveNotificationMethod()
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 3
            }.hook().after { scheduleRuntimeRemovalReconcile() }
        }.onFailure { YLog.error("[$TAG] notification removal observer failed", it) }

        runCatching {
            val point = resolveRemoveCompositeMethod()
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 3
            }.hook().after { scheduleRuntimeRemovalReconcile() }
        }.onFailure { YLog.error("[$TAG] composite removal observer failed", it) }

        runCatching {
            val point = resolveRemoveBusinessMethod()
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 2
            }.hook().after { scheduleRuntimeRemovalReconcile() }
        }.onFailure { YLog.error("[$TAG] business removal observer failed", it) }
    }

    private fun scheduleRuntimeRemovalReconcile() {
        if (!runtimeReconcileScheduled.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            try {
                suppressedBusinesses.toList().forEach { business ->
                    if (runtimePresence(TESTER_PACKAGE, business) == RuntimePresence.ABSENT) {
                        val old = evidence[business] ?: RuntimeEvidence()
                        val changed = old.liveWidgetContains || old.runtimeActivated
                        evidence[business] = old.copy(
                            liveWidgetContains = false,
                            runtimeActivated = false,
                            lastEventAt = System.currentTimeMillis(),
                            lastError = null,
                        )
                        if (changed) dispatchRuntimeEvent(business, "runtime_deactivated")
                        cards.values.filter { it.business == business && it.pendingDelete }
                            .forEach { finalizePendingDeletion(it.cardId, business) }
                    }
                }
            } finally {
                runtimeReconcileScheduled.set(false)
            }
        }
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
        val (card, activationEpoch) = synchronized(lifecycleLock) {
            val current = cards[command.cardId] ?: error("宿主 registry 中不存在该卡片")
            check(!current.pendingDelete) { "卡片正在删除，不能重新启用" }
            if (persist) {
                suppressedBusinesses.remove(command.business)
            } else {
                check(current.enabled && command.business !in suppressedBusinesses) {
                    "卡片恢复已被较新的停用操作取消"
                }
            }
            current to nextOperationEpoch(command.business)
        }
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
        val accepted = synchronized(lifecycleLock) {
            val current = cards[card.cardId]
            if (currentOperationEpoch(command.business) != activationEpoch ||
                command.business in suppressedBusinesses || current?.pendingDelete != false
            ) {
                false
            } else {
                cards[card.cardId] = current.copy(
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
                dispatchRuntimeEvent(command.business, "runtime_activated")
                true
            }
        }
        if (!accepted) {
            scheduleSuppressedRuntimeEject(command.business, extras)
            error("卡片启用已被较新的停用或删除操作取消")
        }
        log("activate", command, true, "compositeKey=$compositeKey")
    }

    private fun deactivateCardInHost(command: CardCommand, persist: Boolean = true) {
        val now = System.currentTimeMillis()
        synchronized(lifecycleLock) {
            val card = cards[command.cardId] ?: error("宿主 registry 中不存在该卡片")
            nextOperationEpoch(command.business)
            suppressedBusinesses.add(command.business)
            // Persist this before removal so delayed startup restoration cannot win.
            cards[card.cardId] = card.copy(enabled = false, updatedAt = now)
            if (persist) writeRegistry()
            evidence[command.business] = (evidence[command.business] ?: RuntimeEvidence()).copy(
                liveWidgetContains = false,
                runtimeActivated = false,
                lastCommandId = command.commandId,
                lastEventAt = now,
                lastError = null,
            )
            dispatchRuntimeEvent(command.business, "runtime_deactivated")
        }
        submitRuntimeRemoval(command.business)
        log("deactivate", command, true, "removed business=${command.business}")
    }

    /** Delivers host lifecycle callbacks to the manager app without using notifications. */
    private fun dispatchRuntimeEvent(business: String, event: String) {
        hostContext?.sendBroadcast(
            Intent(FunCardHostContract.ACTION_CARD_RUNTIME_EVENT)
                .setPackage(TESTER_PACKAGE)
                .putExtra(FunCardHostContract.Keys.BUSINESS, business)
                .putExtra(FunCardHostContract.Keys.RUNTIME_EVENT, event),
        )
    }

    private fun submitRuntimeRemoval(business: String) {
        val target = manager ?: error("Smart Assistant manager 尚未就绪")
        val beforePresence = runtimePresence(TESTER_PACKAGE, business)
        val before = managerWidgetCount(TESTER_PACKAGE, business)
        fun dispatchRemovalPass(): Boolean = runOnMainThread {
                var invoked = false
                val current = managerWidgetIdentities(TESTER_PACKAGE, business)
                current.forEach { identity ->
                    if (identity.notificationId > 0) {
                        runCatching {
                            invokeManagerRemoveNotification(target, identity.notificationId, TESTER_PACKAGE)
                        }.onSuccess {
                            invoked = true
                        }.onFailure {
                            YLog.warn("[$TAG] notification remove failed id=${identity.notificationId}", it)
                        }
                    }
                }
                // Notification removal is canonical.  Composite cleanup only handles
                // records which remain after that exact lifecycle call.
                managerWidgetIdentities(TESTER_PACKAGE, business).forEach { identity ->
                    runCatching {
                        invokeManagerRemoveComposite(target, identity.compositeKey, TESTER_PACKAGE)
                    }.onSuccess {
                        invoked = true
                    }.onFailure { YLog.warn("[$TAG] composite remove failed key=${identity.compositeKey}", it) }
                }
                runCatching {
                    invokeManagerRemoveBusiness(target, TESTER_PACKAGE, business)
                }.onSuccess {
                    invoked = true
                }.onFailure { YLog.warn("[$TAG] business remove failed business=$business", it) }
                invoked
            }
        val accepted = dispatchRemovalPass()
        check(beforePresence == RuntimePresence.ABSENT || accepted) {
            "背屏 Runtime 移除接口不可用；已保留可恢复数据"
        }
        listOf(250L, 750L, 2_000L, 5_000L).forEach { delay ->
            Handler(Looper.getMainLooper()).postDelayed({
                if (business in suppressedBusinesses && managerContains(TESTER_PACKAGE, business)) {
                    runCatching { dispatchRemovalPass() }
                        .onFailure { YLog.warn("[$TAG] runtime removal retry failed business=$business", it) }
                }
            }, delay)
        }
        YLog.info("[$TAG] runtime removal submitted business=$business before=$before")
    }

    private fun schedulePendingDeletionCleanup(cardId: String, business: String) {
        val handler = Handler(Looper.getMainLooper())
        listOf(100L, 1_000L, 6_000L, 15_000L).forEach { delay ->
            handler.postDelayed({
                if (business !in suppressedBusinesses) return@postDelayed
                when (runtimePresence(TESTER_PACKAGE, business)) {
                    RuntimePresence.ABSENT -> finalizePendingDeletion(cardId, business)
                    RuntimePresence.PRESENT, RuntimePresence.UNKNOWN -> if (delay >= 6_000L) {
                        runCatching { submitRuntimeRemoval(business) }
                            .onFailure { YLog.warn("[$TAG] pending delete retry failed business=$business", it) }
                    }
                }
            }, delay)
        }
    }

    private fun finalizePendingDeletion(cardId: String, business: String): Boolean = runOnMainThread {
        if (runtimePresence(TESTER_PACKAGE, business) != RuntimePresence.ABSENT) return@runOnMainThread false
        synchronized(lifecycleLock) {
            if (business in installingBusinesses) return@synchronized false
            val card = cards[cardId]
            if (card != null && (card.business != business || !card.pendingDelete)) return@synchronized false
            val target = card?.templatePath?.let(::File) ?: managedTemplateFile(cardId)
            require(isManagedTemplate(target)) { "拒绝清理非托管模板" }
            if (target.exists()) check(target.delete()) { "删除宿主模板失败" }
            pendingBulkTemplates.remove(target.absolutePath)
            pendingBulkBusinesses.remove(business)
            if (card != null) cards.remove(cardId, card)
            writeRegistry()
            evidence.remove(business)
            dispatchRuntimeEvent(business, "runtime_deleted")
            YLog.info("[$TAG] pending delete finalized business=$business path=${target.absolutePath}")
            true
        }
    }

    private fun scheduleDeleteAllCleanup() {
        val handler = Handler(Looper.getMainLooper())
        listOf(250L, 1_500L, 6_500L, 16_000L).forEach { delay ->
            handler.postDelayed({
                val remaining = pendingBulkBusinesses.filter {
                    runtimePresence(TESTER_PACKAGE, it) != RuntimePresence.ABSENT
                }
                if (delay >= 6_500L) remaining.forEach { business ->
                    runCatching { submitRuntimeRemoval(business) }
                }
                cards.values.filter { it.pendingDelete }.toList().forEach { card ->
                    finalizePendingDeletion(card.cardId, card.business)
                }
                runCatching { cleanupPendingBulk() }
                    .onFailure { YLog.warn("[$TAG] bulk cleanup retry failed", it) }
            }, delay)
        }
    }

    private fun cleanupPendingBulk(): Boolean = runOnMainThread {
        val targetBusinesses = pendingBulkTemplates.map { File(it).name }
        val states = (pendingBulkBusinesses + targetBusinesses).associateWith { business ->
            runtimePresence(TESTER_PACKAGE, business)
        }
        synchronized(lifecycleLock) {
            var changed = false
            pendingBulkBusinesses.toList().forEach { business ->
                if (states[business] == RuntimePresence.ABSENT) {
                    changed = pendingBulkBusinesses.remove(business) || changed
                }
            }
            val retained = cards.values.filterNot { it.pendingDelete }
                .map { File(it.templatePath).absolutePath }
                .toSet()
            pendingBulkTemplates.toList().forEach { path ->
                val target = File(path)
                val business = target.name
                val resolved = when {
                    !isManagedTemplate(target) -> true
                    path in retained -> true
                    business in installingBusinesses -> false
                    !target.exists() -> true
                    states[business] == RuntimePresence.ABSENT -> target.delete()
                    else -> false
                }
                if (resolved) changed = pendingBulkTemplates.remove(path) || changed
            }
            if (changed) writeRegistry()
            pendingBulkBusinesses.isEmpty() && pendingBulkTemplates.isEmpty()
        }
    }

    private fun nextOperationEpoch(business: String): Long =
        operationEpochs.computeIfAbsent(business) { AtomicLong() }.incrementAndGet()

    private fun currentOperationEpoch(business: String): Long =
        operationEpochs[business]?.get() ?: 0L

    private fun notificationWidgetFile() = File(
        "/data/system/theme_magic/users/${Process.myUid() / 100000}/subscreencenter/notification/notification_widget.json",
    )

    private fun managerWidgetCount(packageName: String, business: String): Int {
        if (manager == null) return 0
        return runOnMainThread {
            val target = manager ?: return@runOnMainThread 0
            val list = runCatching {
                target.asResolver().firstField { name = resolveManagerListFieldName() }.get<Any>() as? Iterable<*>
            }.getOrNull() ?: return@runOnMainThread 0
            list.count { widget ->
                val extras = managerWidgetBundle(widget) ?: return@count false
                val pkg = widgetPackage(extras, business)
                pkg == packageName && extras.getString("business") == business
            }
        }
    }

    private fun managerWidgetIdentities(packageName: String, business: String): List<RuntimeWidgetIdentity> {
        if (manager == null) return emptyList()
        return runOnMainThread {
            val target = manager ?: return@runOnMainThread emptyList()
            val list = runCatching {
                target.asResolver().firstField { name = resolveManagerListFieldName() }.get<Any>() as? Iterable<*>
            }.getOrNull() ?: return@runOnMainThread emptyList()
            val fallbackId = cards.values.firstOrNull { it.business == business }
                ?.notificationId?.let(::syntheticRuntimeId) ?: 0
            list.mapNotNull { widget ->
                val extras = managerWidgetBundle(widget) ?: return@mapNotNull null
                val pkg = widgetPackage(extras, business)
                if (pkg != packageName || extras.getString("business") != business) return@mapNotNull null
                val notificationId = extras.getInt("notification_id", extras.getInt("widget_id", 0))
                    .takeIf { it > 0 } ?: fallbackId
                val compositeKey = extras.getString("composite_key").orEmpty()
                    .ifBlank { "$packageName:$business:$notificationId" }
                RuntimeWidgetIdentity(notificationId, compositeKey)
            }.toList()
        }
    }

    private fun syntheticRuntimeId(notificationId: Int): Int = 100_000_000 + notificationId

    private fun <T> runOnMainThread(timeoutMs: Long = 5_000L, action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action()
        }
        val value = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                value.set(action())
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "等待宿主主线程执行超时" }
        error.get()?.let { throw it }
        return value.get()
    }

    private fun invokeManagerRemoveBusiness(target: Any, packageName: String, business: String) {
        val point = resolveRemoveBusinessMethod()
        target.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 2
        }.invoke(packageName, business)
    }

    private fun invokeManagerRemoveNotification(target: Any, notificationId: Int, packageName: String) {
        val point = resolveRemoveNotificationMethod()
        target.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 3
        }.also { method ->
            if (method.self.parameterTypes[1] == String::class.java) {
                method.invoke(notificationId, packageName, 1)
            } else {
                method.invoke(notificationId, 1, packageName)
            }
        }
    }

    private fun invokeManagerRemoveComposite(target: Any, compositeKey: String, packageName: String): Boolean {
        val point = resolveRemoveCompositeMethod()
        return target.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 3
        }.invoke<Boolean>(1, compositeKey, packageName) == true
    }

    private fun scheduleSuppressedRuntimeEject(business: String, extras: Bundle) {
        val target = manager ?: return
        val notificationId = extras.getInt("notification_id", extras.getInt("widget_id", 0))
        val compositeKey = extras.getString("composite_key").orEmpty()
            .ifBlank { "$TESTER_PACKAGE:$business:$notificationId" }
        Handler(Looper.getMainLooper()).post {
            if (notificationId > 0) {
                runCatching { invokeManagerRemoveNotification(target, notificationId, TESTER_PACKAGE) }
            }
            runCatching { invokeManagerRemoveComposite(target, compositeKey, TESTER_PACKAGE) }
            runCatching { invokeManagerRemoveBusiness(target, TESTER_PACKAGE, business) }
        }
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
        cards.values.map { it.cardId }.forEach { cardId ->
            val card = cards[cardId] ?: return@forEach
            if (!card.enabled || card.pendingDelete || card.business in suppressedBusinesses) return@forEach
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
        synchronized(registryLock) {
            val file = registryFile()
            if (!file.isFile) return
            runCatching {
                val root = JSONObject(file.readText())
                val array = root.optJSONArray("cards") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val cardId = item.optString("cardId")
                    val business = item.optString("business")
                    val path = item.optString("templatePath")
                    val target = File(path)
                    if (!cardId.matches(SAFE_CARD_ID) || business != "reareye_custom_$cardId" ||
                        !isManagedTemplate(target) || !target.isFile
                    ) continue
                    val enabled = item.optBoolean("enabled")
                    val pendingDelete = item.optBoolean("pendingDelete")
                    cards[cardId] = HostCard(
                        cardId = cardId,
                        business = business,
                        displayName = item.optString("displayName", business),
                        templatePath = path,
                        sha256 = item.optString("sha256"),
                        notificationId = item.optInt("notificationId"),
                        updatedAt = item.optLong("updatedAt"),
                        enabled = enabled,
                        pendingDelete = pendingDelete,
                        rearParam = item.optString("rearParam", "{}"),
                        focusParam = item.optString("focusParam", "{}"),
                    )
                    if (!enabled || pendingDelete) suppressedBusinesses.add(business)
                }
                val pendingBusinesses = root.optJSONArray("pendingBulkBusinesses") ?: JSONArray()
                for (index in 0 until pendingBusinesses.length()) {
                    val business = pendingBusinesses.optString(index)
                    val cardId = business.removePrefix("reareye_custom_")
                    if (business == "reareye_custom_$cardId" && cardId.matches(SAFE_CARD_ID)) {
                        pendingBulkBusinesses.add(business)
                        suppressedBusinesses.add(business)
                    }
                }
                val pendingTemplates = root.optJSONArray("pendingBulkTemplates") ?: JSONArray()
                for (index in 0 until pendingTemplates.length()) {
                    val target = File(pendingTemplates.optString(index))
                    if (isManagedTemplate(target)) {
                        pendingBulkTemplates.add(target.absolutePath)
                        pendingBulkBusinesses.add(target.name)
                        suppressedBusinesses.add(target.name)
                    }
                }
            }.onFailure { YLog.error("[$TAG] registry load failed", it) }
        }
    }

    private fun writeRegistry() = synchronized(registryLock) {
        val dir = registryDir()
        if (!dir.exists()) check(dir.mkdirs()) { "无法创建宿主 registry 目录" }
        val array = JSONArray()
        cards.values.sortedBy { it.cardId }.forEach { card ->
            array.put(JSONObject().put("cardId", card.cardId).put("business", card.business)
                .put("displayName", card.displayName).put("templatePath", card.templatePath)
                .put("sha256", card.sha256).put("notificationId", card.notificationId)
                .put("updatedAt", card.updatedAt).put("enabled", card.enabled)
                .put("pendingDelete", card.pendingDelete)
                .put("rearParam", card.rearParam).put("focusParam", card.focusParam))
        }
        val target = registryFile()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            val pendingBusinesses = JSONArray().apply {
                pendingBulkBusinesses.sorted().forEach(::put)
            }
            val pendingTemplates = JSONArray().apply {
                pendingBulkTemplates.sorted().forEach(::put)
            }
            output.write(
                JSONObject()
                    .put("schemaVersion", 5)
                    .put("cards", array)
                    .put("pendingBulkBusinesses", pendingBusinesses)
                    .put("pendingBulkTemplates", pendingTemplates)
                    .toString()
                    .toByteArray(),
            )
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
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
        val known = cards.values.map { it.business }.toSet() + suppressedBusinesses
        extras.getString("business")?.trim()?.takeIf { it in known }?.let { return it }
        listOf("miui.rear.param", "miui.focus.param").forEach { key ->
            val root = runCatching { JSONObject(extras.getString(key).orEmpty()) }.getOrNull() ?: return@forEach
            val candidates = listOf(
                root.optString("business"),
                root.optJSONObject("rear_param_v1")?.optString("business"),
                root.optJSONObject("param_v2")?.optString("business"),
            )
            candidates.filterNotNull().firstOrNull { it in known }?.let { return it }
        }
        return null
    }

    private fun extractKnownBusiness(value: Any?): String? {
        val known = cards.values.map { it.business }.toSet() + suppressedBusinesses
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
        if (manager == null) return emptySet()
        return runOnMainThread {
            val target = manager ?: return@runOnMainThread emptySet()
            val list = runCatching {
                target.asResolver().firstField { name = resolveManagerListFieldName() }.get<Any>() as? Iterable<*>
            }.getOrNull() ?: return@runOnMainThread emptySet()
            list.mapNotNull { widget -> managerWidgetBundle(widget)?.getString("business") }.toSet()
        }
    }

    private fun managerOuterViewBusinesses(): Set<String> {
        if (manager == null) return emptySet()
        return runOnMainThread {
            val target = manager ?: return@runOnMainThread emptySet()
            val list = runCatching {
                target.asResolver().firstField { name = resolveManagerListFieldName() }.get<Any>() as? Iterable<*>
            }.getOrNull() ?: return@runOnMainThread emptySet()
            list.mapNotNull { widget ->
                val extras = managerWidgetBundle(widget) ?: return@mapNotNull null
                val business = extras.getString("business") ?: return@mapNotNull null
                val pkg = widgetPackage(extras, business)
                business.takeIf { pkg == TESTER_PACKAGE && it.startsWith("reareye_custom_") }
            }.toSet()
        }
    }

    private fun managerContains(packageName: String, business: String): Boolean {
        return runtimePresence(packageName, business) == RuntimePresence.PRESENT
    }

    private fun runtimePresence(packageName: String, business: String): RuntimePresence {
        if (manager == null) return RuntimePresence.UNKNOWN
        return runOnMainThread {
            val target = manager ?: return@runOnMainThread RuntimePresence.UNKNOWN
            runCatching {
                val list = target.asResolver()
                    .firstField { name = resolveManagerListFieldName() }
                    .get<Any>() as? Iterable<*> ?: error("manager list unavailable")
                var unreadableRecord = false
                val present = list.any { widget ->
                        val extras = managerWidgetBundle(widget) ?: run {
                            unreadableRecord = true
                            return@any false
                        }
                        val pkg = widgetPackage(extras, business)
                        extras.getString("business") == business && pkg == packageName
                    }
                when {
                    present -> RuntimePresence.PRESENT
                    unreadableRecord -> RuntimePresence.UNKNOWN
                    else -> RuntimePresence.ABSENT
                }
            }.getOrElse { RuntimePresence.UNKNOWN }
        }
    }

    private fun widgetPackage(extras: Bundle, business: String): String? =
        extras.getString("package_name")
            ?: extras.getString("creator_package")
            ?: TESTER_PACKAGE.takeIf {
                business in suppressedBusinesses || cards.values.any { card -> card.business == business }
            }

    private fun managerWidgetBundle(widget: Any?): Bundle? {
        widget ?: return null
        return runCatching {
            widget.asResolver().firstField { name = resolveWidgetExtrasFieldName() }.get<Bundle?>()
        }.getOrNull()
    }

    private fun persistentBusinesses(): Set<String> {
        val file = notificationWidgetFile()
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

    private fun resolveRemoveNotificationMethod() = resolveCachedMethod("REMOVE_NOTIFICATION") {
        val managerClass = resolveManagerInitMethod().className
        findMethod { matcher {
            declaredClass = managerClass
            paramCount(3)
            returnType = "void"
            usingStrings("Widget not found for multi-business app: %s, ID: %d")
        } }.singleOrNull()
    }

    private fun resolveRemoveCompositeMethod() = resolveCachedMethod("REMOVE_COMPOSITE") {
        val managerClass = resolveManagerInitMethod().className
        findMethod { matcher {
            declaredClass = managerClass
            paramTypes("int", "java.lang.String", "java.lang.String")
            returnType = "boolean"
            usingStrings("Found widget for compositeKey: %s, removing")
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
