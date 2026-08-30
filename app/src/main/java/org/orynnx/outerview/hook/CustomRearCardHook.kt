@file:Suppress("UNCHECKED_CAST")

package org.orynnx.outerview.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import org.orynnx.outerview.core.hostapi.FunCardHostContract
import org.orynnx.outerview.core.hostapi.IFunCardHostConnection
import org.orynnx.outerview.core.hostapi.IFunCardHostService
import org.orynnx.outerview.core.internal.HostPendingCleanupPolicy
import org.orynnx.outerview.core.internal.HostTemplateUnlinkOutcome
import org.orynnx.outerview.core.internal.HostInstallJournal
import org.orynnx.outerview.core.internal.HostInstallJournalCodec
import org.orynnx.outerview.core.internal.HostInstallPrecondition
import org.orynnx.outerview.core.internal.HostInstallPreconditionPolicy
import org.orynnx.outerview.core.internal.HostInstallRecovery
import org.orynnx.outerview.core.internal.HostInstallRegistrySnapshot
import org.orynnx.outerview.core.internal.ManagedHostPaths
import org.orynnx.outerview.core.internal.SmartAssistantTemplateValidator
import org.orynnx.outerview.core.internal.VerifiedHostCardOwnership
import org.orynnx.outerview.hook.dex.HostClassQuery
import org.orynnx.outerview.hook.dex.HostDexResolver
import org.orynnx.outerview.hook.dex.HostFieldQuery
import org.orynnx.outerview.hook.dex.HostMethodQuery
import org.orynnx.outerview.hook.dex.HostMethodRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    private data class RegistryStateSnapshot(
        val cards: Map<String, HostCard>,
        val suppressedBusinesses: Set<String>,
        val pendingBulkBusinesses: Set<String>,
        val pendingBulkTemplates: Set<String>,
    )

    private data class ActivationPlan(
        val card: HostCard,
        val epoch: Long,
        val wasSuppressed: Boolean,
        val evidenceBefore: RuntimeEvidence?,
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
        private const val MAX_REGISTRY_BYTES = 2L * 1024L * 1024L
        private const val MAX_REGISTRY_RECORDS = 1024
        private const val MAX_NOTIFICATION_STATE_BYTES = 4L * 1024L * 1024L
        private const val MAX_INSTALL_JOURNAL_BYTES = 256L * 1024L
        private val SAFE_CARD_ID = Regex("[a-f0-9]{32}")
        private val INSTALL_JOURNAL_NAME = Regex("\\.install-([a-f0-9]{32})\\.json")
        private val INSTALL_ORPHAN_NAME = Regex(
            "\\.install-[a-f0-9]{32}\\.(new|old)\\.zip|" +
                "\\.outerview_install_[a-f0-9]{32}_[0-9]+\\.tmp|" +
                "\\.outerview_write_[0-9]+\\.tmp",
        )
        private val INSTALL_TARGET_TEMP_NAME = Regex("\\.outerview_install_write_[0-9]+\\.tmp")
        private val PRIMITIVE_WRAPPERS = mapOf<Class<*>, Class<*>>(
            Int::class.javaPrimitiveType!! to Int::class.javaObjectType,
            Long::class.javaPrimitiveType!! to Long::class.javaObjectType,
            Boolean::class.javaPrimitiveType!! to Boolean::class.javaObjectType,
            Float::class.javaPrimitiveType!! to Float::class.javaObjectType,
            Double::class.javaPrimitiveType!! to Double::class.javaObjectType,
            Short::class.javaPrimitiveType!! to Short::class.javaObjectType,
            Byte::class.javaPrimitiveType!! to Byte::class.javaObjectType,
            Char::class.javaPrimitiveType!! to Char::class.javaObjectType,
        )
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
    @Volatile private var registryWriteBlocked = false
    private val runtimeReconcileScheduled = AtomicBoolean(false)

    @Volatile private var hostContext: Context? = null
    @Volatile private var manager: Any? = null
    @Volatile private var receiverRegistered = false
    private var hostDex: HostDexResolver? = null

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
            val items = ArrayList(
                synchronized(lifecycleLock) { cards.values.toList() }.sortedBy { it.cardId }
                    .filter { card -> card.pendingDelete || isVerifiedHostCard(card) }
                    .map(::hostCardBundle),
            )
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
            val command = runCatching { parseRequest(request) }.getOrElse { error ->
                runCatching { zipFd?.close() }
                return invalidRequest(error)
            }
            val fd = zipFd ?: return failure("MISSING_FD", "没有收到模板文件", command)
            val target = runCatching { managedTemplateFile(command.cardId) }.getOrElse { error ->
                runCatching { fd.close() }
                return failure("UNSAFE_TEMPLATE_PATH", error.message ?: "模板目录不安全", command)
            }
            val temp = runCatching {
                val directory = ensureRegistryDirectory()
                File.createTempFile(".outerview_install_${command.cardId}_", ".tmp", directory)
            }.getOrElse { error ->
                runCatching { fd.close() }
                return failure("TEMP_FILE_FAILED", error.message ?: "无法创建模板临时文件", command)
            }
            val installEpoch = synchronized(lifecycleLock) {
                installingBusinesses.add(command.business)
                nextOperationEpoch(command.business)
            }
            return runCatching {
                var total = 0L
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    FileOutputStream(temp, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_TEMPLATE_BYTES) { "模板超过 16 MB" }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                val actualSha256 = validateTemplate(temp)
                require(command.sha256 == actualSha256) { "模板摘要校验失败" }
                val runtimeBusinesses = buildSet {
                    add(command.business)
                    cards[command.cardId]?.business?.let(::add)
                }
                val runtimePresenceBeforeCommit = runtimeBusinesses.associateWith { business ->
                    runtimePresence(TESTER_PACKAGE, business)
                }
                val card = synchronized(lifecycleLock) {
                    check(currentOperationEpoch(command.business) == installEpoch) {
                        "模板安装已被较新的删除操作取消"
                    }
                    check(!registryWriteBlocked) { "宿主 registry 无法安全读取；已阻止安装" }
                    recoverHostInstallTransactionIfPresent(command.cardId, cards[command.cardId])
                    val stateBefore = registryStateSnapshot()
                    val previousCard = cards[command.cardId]
                    val runtimeAbsenceConfirmed = buildSet {
                        add(command.business)
                        previousCard?.business?.let(::add)
                    }.all { business ->
                        runtimePresenceBeforeCommit[business] == RuntimePresence.ABSENT
                    }
                    when (
                        HostInstallPreconditionPolicy.evaluate(
                            previousPendingDelete = previousCard?.pendingDelete == true,
                            previousEnabled = previousCard?.enabled == true,
                            runtimeAbsenceConfirmed = runtimeAbsenceConfirmed,
                        )
                    ) {
                        HostInstallPrecondition.ALLOW -> Unit
                        HostInstallPrecondition.DELETE_PENDING -> error("卡片删除进行中；已拒绝安装")
                        HostInstallPrecondition.ENABLED -> error("旧卡片仍处于启用状态；请先停用再安装")
                        HostInstallPrecondition.RUNTIME_NOT_CONFIRMED_ABSENT ->
                            error("无法确认旧卡片 Runtime 已移除；已拒绝替换模板")
                    }
                    if (previousCard != null) {
                        require(isVerifiedHostCard(previousCard)) {
                            "宿主 registry 旧模板缺失或摘要不匹配"
                        }
                    }
                    val previousTargetSha256 = existingTransactionFileSha(target)
                    val installed = HostCard(
                        cardId = command.cardId,
                        business = command.business,
                        displayName = command.displayName,
                        templatePath = target.canonicalPath,
                        sha256 = command.sha256,
                        notificationId = command.notificationId,
                        updatedAt = System.currentTimeMillis(),
                    )
                    val journal = HostInstallJournal(
                        cardId = command.cardId,
                        oldRegistryFingerprint = previousCard?.let(::hostCardFingerprint),
                        newRegistryFingerprint = hostCardFingerprint(installed),
                        oldTemplatePath = previousCard?.let { File(it.templatePath).canonicalPath },
                        oldTargetSha256 = previousTargetSha256,
                        newTargetSha256 = command.sha256,
                    )
                    val journalFile = installJournalFile(command.cardId)
                    val staging = installStagingFile(command.cardId)
                    val backup = installBackupFile(command.cardId)
                    require(!journalFile.exists() && !staging.exists() && !backup.exists()) {
                        "已有未完成的 Host 模板安装事务"
                    }
                    var committedAfterException: Throwable? = null
                    try {
                        moveIncomingTemplateToStaging(temp, staging)
                        writeFileAtomically(
                            journalFile,
                            HostInstallJournalCodec.encode(journal),
                            MAX_INSTALL_JOURNAL_BYTES,
                            "Host install journal",
                        )
                        if (previousTargetSha256 != null) {
                            copyTransactionFileAtomically(target, backup, previousTargetSha256)
                        }
                        copyTransactionFileAtomically(staging, target, command.sha256)
                        check(target.setReadable(true, false)) { "无法设置模板读取权限" }
                        cards[installed.cardId] = installed
                        pendingBulkBusinesses.remove(command.business)
                        pendingBulkTemplates.remove(target.canonicalPath)
                        suppressedBusinesses.remove(command.business)
                        writeRegistry()
                    } catch (error: Throwable) {
                        if (!journalFile.isFile) {
                            restoreRegistryState(stateBefore)
                            runCatching { deleteInstallSidecar(staging) }.onFailure(error::addSuppressed)
                            runCatching { deleteInstallSidecar(backup) }.onFailure(error::addSuppressed)
                            throw error
                        }
                        val diskFingerprint = runCatching {
                            readRegistryCardFingerprintFromDisk(command.cardId)
                        }.getOrElse { auditError ->
                            error.addSuppressed(auditError)
                            blockUnsafeInstallRecovery(previousCard, command.business, auditError)
                            throw error
                        }
                        val recovery = runCatching {
                            recoverHostInstallTransaction(journal, diskFingerprint)
                        }.onFailure { recoveryError ->
                            error.addSuppressed(recoveryError)
                            blockUnsafeInstallRecovery(previousCard, command.business, recoveryError)
                        }
                        if (recovery.isFailure) {
                            if (diskFingerprint == journal.oldRegistryFingerprint) {
                                restoreRegistryState(stateBefore)
                            }
                            throw error
                        }
                        if (diskFingerprint == journal.newRegistryFingerprint) {
                            cards[installed.cardId] = installed
                            pendingBulkBusinesses.remove(command.business)
                            pendingBulkTemplates.remove(target.canonicalPath)
                            suppressedBusinesses.remove(command.business)
                            committedAfterException = error
                        } else {
                            restoreRegistryState(stateBefore)
                            throw error
                        }
                    }
                    committedAfterException?.let { committedError ->
                        YLog.warn(
                            "[$TAG] Host install registry was already committed; recovered as success",
                            committedError,
                        )
                    }
                    evidence[installed.business] = RuntimeEvidence(
                        actualTemplatePath = installed.templatePath,
                        lastCommandId = command.commandId,
                        lastEventAt = System.currentTimeMillis(),
                    )
                    runCatching { finishCommittedHostInstallTransaction(journal) }
                        .onFailure { cleanupError ->
                            YLog.warn("[$TAG] Host install committed; cleanup will retry", cleanupError)
                        }
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
            val command = runCatching { parseRequest(request) }
                .getOrElse { error -> return invalidRequest(error) }
            return runCatching {
                val registered = cards[command.cardId]
                if (registered != null) {
                    require(
                        registered.business == command.business &&
                            registered.sha256 == command.sha256 &&
                            registered.notificationId == command.notificationId,
                    ) { "卸载请求与宿主 registry 身份不一致" }
                }
                val target = registered?.templatePath?.let(::File) ?: managedTemplateFile(command.cardId)
                require(isTemplateForCard(target, command.cardId)) { "拒绝删除非托管路径" }
                synchronized(lifecycleLock) {
                    val stateBefore = registryStateSnapshot()
                    try {
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
                    } catch (error: Throwable) {
                        restoreRegistryState(stateBefore)
                        throw error
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
            if (commandId.length !in 1..160 || commandId.any(Char::isISOControl)) {
                return invalidRequest(IllegalArgumentException("命令 ID 无效"))
            }
            // A shape-only bulk request cannot prove ownership of legacy
            // reareye_custom_* records.  The manager now calls uninstallCard for
            // each private-registry identity (cardId/business/SHA/notificationId).
            return Bundle().apply {
                putBoolean(FunCardHostContract.Keys.SUCCESS, false)
                putString(FunCardHostContract.Keys.MESSAGE, "请使用逐卡身份校验删除")
                putString(FunCardHostContract.Keys.ERROR_CODE, "IDENTIFIED_DELETE_REQUIRED")
                putString(FunCardHostContract.Keys.COMMAND_ID, commandId)
            }
        }

        override fun activateCard(request: Bundle?): Bundle {
            enforceCaller()
            val command = runCatching { parseRequest(request) }
                .getOrElse { error -> return invalidRequest(error) }
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
            val command = runCatching { parseRequest(request) }
                .getOrElse { error -> return invalidRequest(error) }
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
            val versionCode = hostPackageVersionCode(systemContext, appInfo.packageName, appInfo.sourceDir)
            hostDex = HostDexResolver.open(appInfo.sourceDir, appInfo.dataDir, versionCode)
            loadRegistry()

            "com.xiaomi.subscreencenter.SubScreenCenterApp".toClass().resolve().firstMethod {
                name = "attachBaseContext"
                parameterCount = 1
            }.hook().after {
                val baseContext = args[0] as? Context
                hostContext = baseContext?.applicationContext ?: baseContext
                loadRegistry()
                registerServiceReceiver()
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
                val extras = readInstanceField(instance, resolveNotificationWidgetExtrasFieldName()) as? Bundle ?: return@after
                if (extras.getString("package_name") != TESTER_PACKAGE && extras.getString("creator_package") != TESTER_PACKAGE) return@after
                val business = extras.getString("business")?.trim().orEmpty()
                if (business.isBlank()) return@after
                val path = readInstanceField(instance, resolveNotificationWidgetTemplatePathFieldName()) as? String
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
                // The user may remove an enabled card from the system rear-screen UI
                // without going through OuterView.  Adopt that removal as authoritative
                // so the next synchronizeCards() does not force it back.
                cards.values.toList().forEach { card ->
                    if (card.pendingDelete || card.business in suppressedBusinesses) return@forEach
                    if (runtimePresence(TESTER_PACKAGE, card.business) != RuntimePresence.ABSENT) return@forEach
                    synchronized(lifecycleLock) {
                        val current = cards[card.cardId] ?: return@synchronized
                        if (current.enabled && current.business !in suppressedBusinesses) {
                            val now = System.currentTimeMillis()
                            val stateBefore = registryStateSnapshot()
                            try {
                                cards[current.cardId] = current.copy(enabled = false, updatedAt = now)
                                writeRegistry()
                            } catch (error: Throwable) {
                                restoreRegistryState(stateBefore)
                                YLog.error("[$TAG] failed to persist external removal", error)
                                return@synchronized
                            }
                            evidence[current.business] = (evidence[current.business] ?: RuntimeEvidence()).copy(
                                liveWidgetContains = false,
                                runtimeActivated = false,
                                lastEventAt = now,
                                lastError = null,
                            )
                            dispatchRuntimeEvent(current.business, "runtime_deactivated")
                            YLog.info("[$TAG] external removal adopted business=${current.business} cardId=${current.cardId}")
                        }
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
        context.registerReceiver(
            receiver,
            IntentFilter(FunCardHostContract.ACTION_REQUEST_SERVICE),
            FunCardHostContract.ACCESS_HOST_API_PERMISSION,
            null,
            Context.RECEIVER_EXPORTED,
        )
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
        val displayName = bundle.getString(FunCardHostContract.Keys.DISPLAY_NAME).orEmpty()
            .ifBlank { business }.trim()
        val sha256 = bundle.getString(FunCardHostContract.Keys.TEMPLATE_SHA256).orEmpty()
        val commandId = bundle.getString(FunCardHostContract.Keys.COMMAND_ID).orEmpty()
        val rearParam = bundle.getString(FunCardHostContract.Keys.REAR_PARAM).orEmpty()
        val focusParam = bundle.getString(FunCardHostContract.Keys.FOCUS_PARAM).orEmpty()
        require(cardId.matches(SAFE_CARD_ID)) { "cardId 无效" }
        require(ManagedHostPaths.matchesBusiness(cardId, business)) { "business 与 cardId 不匹配" }
        require(displayName.codePointCount(0, displayName.length) in 1..80 &&
            displayName.none(::isUnsafeDisplayCharacter)
        ) { "卡片名称无效" }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "模板摘要无效" }
        require(commandId.length in 1..160 && commandId.none(Char::isISOControl)) { "命令 ID 无效" }
        val notificationId = bundle.getInt(FunCardHostContract.Keys.NOTIFICATION_ID)
        require(notificationId in 620_000..719_999) { "notificationId 无效" }
        require(rearParam.toByteArray().size + focusParam.toByteArray().size <= 128 * 1024) {
            "卡片参数过大"
        }
        return CardCommand(
            cardId,
            business,
            displayName,
            sha256,
            notificationId,
            commandId,
            rearParam,
            focusParam,
        )
    }

    private fun activateCardInHost(command: CardCommand, persist: Boolean = true) {
        require(command.rearParam.isNotBlank() && command.focusParam.isNotBlank()) { "卡片 payload 为空" }
        val plan = synchronized(lifecycleLock) {
            val current = cards[command.cardId] ?: error("宿主 registry 中不存在该卡片")
            check(!current.pendingDelete) { "卡片正在删除，不能重新启用" }
            require(current.business == command.business &&
                current.notificationId == command.notificationId &&
                current.sha256 == command.sha256
            ) { "卡片请求与宿主 registry 身份不一致" }
            require(isVerifiedHostCard(current) && File(current.templatePath).canRead()) {
                "宿主模板缺失、不可读或摘要不匹配"
            }
            val wasSuppressed = command.business in suppressedBusinesses
            if (persist) {
                check(!registryWriteBlocked) { "宿主 registry 无法安全读取；已阻止覆盖" }
                val candidate = current.copy(
                    enabled = true,
                    rearParam = command.rearParam,
                    focusParam = command.focusParam,
                    updatedAt = System.currentTimeMillis(),
                )
                encodeRegistry(
                    cardValues = cards.values.filterNot { it.cardId == current.cardId } + candidate,
                    pendingBusinesses = pendingBulkBusinesses,
                    pendingTemplates = pendingBulkTemplates,
                )
                suppressedBusinesses.remove(command.business)
            } else {
                check(current.enabled && command.business !in suppressedBusinesses) {
                    "卡片恢复已被较新的停用操作取消"
                }
            }
            ActivationPlan(
                card = current,
                epoch = nextOperationEpoch(command.business),
                wasSuppressed = wasSuppressed,
                evidenceBefore = evidence[command.business],
            )
        }
        val card = plan.card
        val activationEpoch = plan.epoch
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
        try {
            runOnMainThread {
                val target = manager ?: error("Smart Assistant manager 尚未就绪")
                if (managerContains(TESTER_PACKAGE, command.business)) {
                    removeManagerRecord(target, ManagerRemoval.Business(TESTER_PACKAGE, command.business))
                }
                val runnable = createPostRunnable(
                    manager = target,
                    runtimeId = runtimeId,
                    packageName = TESTER_PACKAGE,
                    compositeKey = compositeKey,
                    extras = extras,
                )
                runnable.run()
            }
        } catch (error: Throwable) {
            if (persist) synchronized(lifecycleLock) {
                if (currentOperationEpoch(command.business) == activationEpoch) {
                    if (plan.wasSuppressed) suppressedBusinesses.add(command.business)
                    else suppressedBusinesses.remove(command.business)
                }
            }
            runCatching { submitRuntimeRemoval(command.business) }
            throw error
        }
        val now = System.currentTimeMillis()
        val accepted = try {
            synchronized(lifecycleLock) {
                val current = cards[card.cardId]
                if (currentOperationEpoch(command.business) != activationEpoch ||
                    command.business in suppressedBusinesses || current?.pendingDelete != false
                ) {
                    false
                } else {
                    try {
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
                        true
                    } catch (error: Throwable) {
                        if (persist) {
                            cards[card.cardId] = plan.card
                            if (plan.wasSuppressed) suppressedBusinesses.add(command.business)
                            else suppressedBusinesses.remove(command.business)
                            if (plan.evidenceBefore == null) evidence.remove(command.business)
                            else evidence[command.business] = plan.evidenceBefore
                        }
                        throw error
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching { submitRuntimeRemoval(command.business) }
            throw error
        }
        if (!accepted) {
            scheduleSuppressedRuntimeEject(command.business, extras)
            error("卡片启用已被较新的停用或删除操作取消")
        }
        dispatchRuntimeEvent(command.business, "runtime_activated")
        log("activate", command, true, "compositeKey=$compositeKey")
    }

    /**
     * Xiaomi changed the first parameter of its notification-post runnable in
     * HyperOS 4: it is now a [MainPanel] rather than SmartAssistantManager.
     * Resolve that owner from the captured manager at runtime so the hook stays
     * compatible with both layouts instead of assuming a particular obfuscated
     * class relationship.
     */
    private fun createPostRunnable(
        manager: Any,
        runtimeId: Int,
        packageName: String,
        compositeKey: String,
        extras: Bundle,
    ): Runnable {
        val runnableClass = resolvePostRunnableClassName().toClass()
        val constructors = runnableClass.declaredConstructors.filter { it.parameterCount == 5 }
        val expectedArguments = arrayOf<Any>(runtimeId, packageName, compositeKey, extras)
        constructors.forEach { constructor ->
            val parameterTypes = constructor.parameterTypes
            val owner = resolvePostRunnableOwner(manager, parameterTypes[0]) ?: return@forEach
            val arguments = arrayOf(owner, *expectedArguments)
            if (!parameterTypes.indices.all { index -> acceptsArgument(parameterTypes[index], arguments[index]) }) {
                return@forEach
            }
            val runnable = runCatching {
                constructor.isAccessible = true
                constructor.newInstance(*arguments) as? Runnable
            }.onFailure {
                YLog.warn("[$TAG] Post Runnable constructor rejected compatible arguments", it)
            }.getOrNull()
            if (runnable != null) {
                YLog.info(
                    "[$TAG] using Post Runnable constructor=" +
                        constructor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name } +
                        " owner=${owner.javaClass.name}",
                )
                return runnable
            }
        }
        val signatures = constructors.joinToString { constructor ->
            constructor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        error("无法为 Smart Assistant Post Runnable 解析宿主对象，候选构造器=$signatures")
    }

    private fun resolvePostRunnableOwner(manager: Any, expectedType: Class<*>): Any? {
        if (expectedType.isInstance(manager)) return manager
        var current: Class<*>? = manager.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(manager)
                }.getOrNull()
                if (value != null && expectedType.isInstance(value)) return value
            }
            current = current.superclass
        }
        return null
    }

    private fun acceptsArgument(expectedType: Class<*>, value: Any): Boolean {
        val acceptedRuntimeType = PRIMITIVE_WRAPPERS[expectedType] ?: expectedType
        return acceptedRuntimeType.isInstance(value)
    }

    private fun deactivateCardInHost(command: CardCommand, persist: Boolean = true) {
        val now = System.currentTimeMillis()
        synchronized(lifecycleLock) {
            val card = cards[command.cardId] ?: error("宿主 registry 中不存在该卡片")
            val stateBefore = registryStateSnapshot()
            try {
                nextOperationEpoch(command.business)
                suppressedBusinesses.add(command.business)
                // Persist this before removal so delayed startup restoration cannot win.
                cards[card.cardId] = card.copy(enabled = false, updatedAt = now)
                if (persist) writeRegistry()
            } catch (error: Throwable) {
                restoreRegistryState(stateBefore)
                throw error
            }
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
        runCatching {
            hostContext?.sendBroadcast(
                Intent(FunCardHostContract.ACTION_CARD_RUNTIME_EVENT)
                    .setPackage(TESTER_PACKAGE)
                    .putExtra(FunCardHostContract.Keys.BUSINESS, business)
                    .putExtra(FunCardHostContract.Keys.RUNTIME_EVENT, event)
                    .putExtra(FunCardHostContract.Keys.PROVIDER_INSTANCE_ID, providerInstanceId),
            )
        }.onFailure { YLog.warn("[$TAG] runtime event delivery failed", it) }
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
                            removeManagerRecord(
                                target,
                                ManagerRemoval.Notification(identity.notificationId, TESTER_PACKAGE),
                            )
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
                        removeManagerRecord(
                            target,
                            ManagerRemoval.Composite(identity.compositeKey, TESTER_PACKAGE),
                        )
                    }.onSuccess {
                        invoked = true
                    }.onFailure { YLog.warn("[$TAG] composite remove failed key=${identity.compositeKey}", it) }
                }
                runCatching {
                    removeManagerRecord(target, ManagerRemoval.Business(TESTER_PACKAGE, business))
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
            require(isTemplateForCard(target, cardId)) {
                "拒绝清理非托管模板"
            }
            val unlinkOutcome = try {
                if (Files.deleteIfExists(target.toPath())) {
                    HostTemplateUnlinkOutcome.REMOVED
                } else {
                    HostTemplateUnlinkOutcome.ABSENT
                }
            } catch (error: Throwable) {
                YLog.warn("[$TAG] pending template unlink failed path=${target.absolutePath}", error)
                HostTemplateUnlinkOutcome.FAILED
            }
            if (!HostPendingCleanupPolicy.canCommitRegistryDeletion(unlinkOutcome)) {
                return@synchronized false
            }
            if (unlinkOutcome == HostTemplateUnlinkOutcome.REMOVED) {
                syncDirectory(requireNotNull(target.parentFile))
            }
            val stateBefore = registryStateSnapshot()
            try {
                pendingBulkTemplates.remove(target.canonicalPath)
                pendingBulkBusinesses.remove(business)
                if (card != null) cards.remove(cardId, card)
                writeRegistry()
            } catch (error: Throwable) {
                restoreRegistryState(stateBefore)
                YLog.warn(
                    "[$TAG] template unlinked but registry cleanup failed; retry remains pending " +
                        "business=$business",
                    error,
                )
                return@synchronized false
            }
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
            val stateBefore = registryStateSnapshot()
            var changed = false
            try {
                pendingBulkBusinesses.toList().forEach { business ->
                    if (states[business] == RuntimePresence.ABSENT) {
                        changed = pendingBulkBusinesses.remove(business) || changed
                    }
                }
                val retained = cards.values.filterNot { it.pendingDelete }
                    .map { File(it.templatePath).canonicalPath }
                    .toSet()
                pendingBulkTemplates.toList().forEach { path ->
                    val target = File(path).canonicalFile
                    val business = target.name
                    val resolved = when {
                        !isManagedTemplate(target) -> true
                        target.path in retained -> true
                        business in installingBusinesses -> false
                        !target.exists() -> true
                        states[business] == RuntimePresence.ABSENT -> target.delete()
                        else -> false
                    }
                    if (resolved) changed = pendingBulkTemplates.remove(target.path) || changed
                }
                if (changed) writeRegistry()
            } catch (error: Throwable) {
                restoreRegistryState(stateBefore)
                throw error
            }
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

    private sealed interface ManagerRemoval {
        val packageName: String

        data class Business(override val packageName: String, val business: String) : ManagerRemoval
        data class Notification(val notificationId: Int, override val packageName: String) : ManagerRemoval
        data class Composite(val compositeKey: String, override val packageName: String) : ManagerRemoval
    }

    /**
     * MIUI keeps three differently shaped removal operations.  Treat them as data so that the
     * method lookup, API-version argument order, and result handling live in one host adapter.
     */
    private fun removeManagerRecord(target: Any, request: ManagerRemoval): Boolean {
        val endpoint = when (request) {
            is ManagerRemoval.Business -> resolveRemoveBusinessMethod()
            is ManagerRemoval.Notification -> resolveRemoveNotificationMethod()
            is ManagerRemoval.Composite -> resolveRemoveCompositeMethod()
        }
        val member = target.asResolver().firstMethod {
            name = endpoint.methodName
            parameterCount = if (request is ManagerRemoval.Business) 2 else 3
        }
        val arguments = when (request) {
            is ManagerRemoval.Business -> arrayOf<Any>(request.packageName, request.business)
            is ManagerRemoval.Notification -> {
                if (member.self.parameterTypes.getOrNull(1) == String::class.java) {
                    arrayOf<Any>(request.notificationId, request.packageName, 1)
                } else {
                    arrayOf<Any>(request.notificationId, 1, request.packageName)
                }
            }
            is ManagerRemoval.Composite -> arrayOf<Any>(1, request.compositeKey, request.packageName)
        }
        return if (request is ManagerRemoval.Composite) {
            member.invoke<Boolean>(*arguments) == true
        } else {
            member.invoke(*arguments)
            true
        }
    }

    private fun scheduleSuppressedRuntimeEject(business: String, extras: Bundle) {
        val target = manager ?: return
        val notificationId = extras.getInt("notification_id", extras.getInt("widget_id", 0))
        val compositeKey = extras.getString("composite_key").orEmpty()
            .ifBlank { "$TESTER_PACKAGE:$business:$notificationId" }
        Handler(Looper.getMainLooper()).post {
            if (notificationId > 0) {
                runCatching {
                    removeManagerRecord(target, ManagerRemoval.Notification(notificationId, TESTER_PACKAGE))
                }
            }
            runCatching { removeManagerRecord(target, ManagerRemoval.Composite(compositeKey, TESTER_PACKAGE)) }
            runCatching { removeManagerRecord(target, ManagerRemoval.Business(TESTER_PACKAGE, business)) }
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

    private fun invalidRequest(error: Throwable): Bundle = Bundle().apply {
        putBoolean(FunCardHostContract.Keys.SUCCESS, false)
        putString(FunCardHostContract.Keys.MESSAGE, error.message ?: "请求参数无效")
        putString(FunCardHostContract.Keys.ERROR_CODE, "INVALID_REQUEST")
    }

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

    private fun isTemplateForCard(file: File, cardId: String): Boolean {
        return ManagedHostPaths.isTemplateForCard(File(templateBase()), file, cardId)
    }

    private fun templateBase(): String =
        "/data/system/theme_magic/users/${Process.myUid() / 100000}/subscreencenter/smart_assistant"

    private fun registryDir(): File = File(
        "/data/system/theme_magic/users/${Process.myUid() / 100000}/subscreencenter/outerview_cards",
    ).absoluteFile.also { directory ->
        require(!Files.isSymbolicLink(directory.toPath())) { "宿主 registry 目录不允许使用符号链接" }
    }

    private fun registryFile(): File {
        val directory = registryDir()
        val file = File(directory, "registry.json").absoluteFile
        require(file.parentFile == directory && !Files.isSymbolicLink(file.toPath())) {
            "宿主 registry 路径不安全"
        }
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "宿主 registry 越出受管目录" }
        return file
    }

    private fun ensureRegistryDirectory(): File = registryDir().also { directory ->
        check(directory.isDirectory || directory.mkdirs()) { "无法创建宿主 registry 目录" }
        require(!Files.isSymbolicLink(directory.toPath())) { "宿主 registry 目录不允许使用符号链接" }
    }

    private fun installJournalFile(cardId: String): File =
        installSidecar(cardId, ".install-$cardId.json")

    private fun installStagingFile(cardId: String): File =
        installSidecar(cardId, ".install-$cardId.new.zip")

    private fun installBackupFile(cardId: String): File =
        installSidecar(cardId, ".install-$cardId.old.zip")

    private fun installSidecar(cardId: String, name: String): File {
        require(cardId.matches(SAFE_CARD_ID)) { "Host install sidecar cardId 无效" }
        val directory = registryDir()
        val file = File(directory, name).absoluteFile
        require(file.parentFile == directory && !Files.isSymbolicLink(file.toPath())) {
            "Host install sidecar 路径不安全"
        }
        require(file.canonicalFile.parentFile == directory.canonicalFile) {
            "Host install sidecar 越出 registry 目录"
        }
        return file
    }

    private fun HostCard.installSnapshot() = HostInstallRegistrySnapshot(
        cardId = cardId,
        business = business,
        displayName = displayName,
        templatePath = File(templatePath).canonicalPath,
        sha256 = sha256,
        notificationId = notificationId,
        updatedAt = updatedAt,
        enabled = enabled,
        pendingDelete = pendingDelete,
        rearParam = rearParam,
        focusParam = focusParam,
    )

    private fun hostCardFingerprint(card: HostCard): String =
        HostInstallJournalCodec.registryFingerprint(card.installSnapshot())

    private fun blockUnsafeInstallRecovery(
        previousCard: HostCard?,
        newBusiness: String,
        error: Throwable,
    ) {
        registryWriteBlocked = true
        suppressedBusinesses.add(newBusiness)
        previousCard?.business?.let(suppressedBusinesses::add)
        YLog.error("[$TAG] Host install recovery is ambiguous; writes and runtime restore blocked", error)
    }

    private fun readRegistryCardFingerprintFromDisk(cardId: String): String? =
        synchronized(registryLock) {
            val file = registryFile()
            if (!file.exists()) return@synchronized null
            require(file.isFile) { "宿主 registry 不是普通文件" }
            val root = JSONObject(readTextBounded(file, MAX_REGISTRY_BYTES, "宿主 registry"))
            decodeRegistryCards(root)[cardId]?.let(::hostCardFingerprint)
        }

    private fun decodeRegistryCards(root: JSONObject): LinkedHashMap<String, HostCard> {
        val schemaVersion = root.optInt("schemaVersion", -1)
        require(schemaVersion == 5) { "不支持的宿主 registry 版本：$schemaVersion" }
        val array = requireNotNull(root.optJSONArray("cards")) { "宿主 registry 缺少 cards" }
        require(array.length() <= MAX_REGISTRY_RECORDS) { "宿主 registry 卡片数量过多" }
        val loadedCards = linkedMapOf<String, HostCard>()
        val loadedTemplatePaths = linkedSetOf<String>()
        val loadedNotificationIds = linkedSetOf<Int>()
        for (index in 0 until array.length()) {
            val item = requireNotNull(array.optJSONObject(index)) { "宿主 registry 卡片记录无效" }
            val cardId = item.optString("cardId")
            val business = item.optString("business")
            val target = File(item.optString("templatePath")).canonicalFile
            require(ManagedHostPaths.matchesBusiness(cardId, business)) { "宿主 registry business 无效" }
            require(isTemplateForCard(target, cardId)) {
                "宿主 registry 模板路径与 cardId 不匹配"
            }
            require(loadedTemplatePaths.add(target.path)) { "宿主 registry 包含重复模板路径" }
            require(cardId !in loadedCards) { "宿主 registry 包含重复 cardId" }
            val displayName = item.optString("displayName", business).trim()
            require(
                displayName.codePointCount(0, displayName.length) in 1..80 &&
                    displayName.none(::isUnsafeDisplayCharacter),
            ) { "宿主 registry 卡片名称无效" }
            val hash = item.optString("sha256")
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "宿主 registry 模板摘要无效" }
            val notificationId = item.optInt("notificationId")
            require(notificationId in 620_000..719_999 && loadedNotificationIds.add(notificationId)) {
                "宿主 registry notificationId 无效或重复"
            }
            val rearParam = item.optString("rearParam", "{}")
            val focusParam = item.optString("focusParam", "{}")
            require(rearParam.toByteArray().size + focusParam.toByteArray().size <= 128 * 1024) {
                "宿主 registry 卡片参数过大"
            }
            JSONObject(rearParam)
            JSONObject(focusParam)
            val updatedAt = item.optLong("updatedAt")
            require(updatedAt >= 0L) { "宿主 registry 更新时间无效" }
            loadedCards[cardId] = HostCard(
                cardId = cardId,
                business = business,
                displayName = displayName,
                templatePath = target.path,
                sha256 = hash,
                notificationId = notificationId,
                updatedAt = updatedAt,
                enabled = item.optBoolean("enabled"),
                pendingDelete = item.optBoolean("pendingDelete"),
                rearParam = rearParam,
                focusParam = focusParam,
            )
        }
        return loadedCards
    }

    private fun recoverHostInstallTransactions(registryCards: Map<String, HostCard>) {
        val directory = registryDir()
        if (!directory.exists()) return
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Host install transaction 目录不安全"
        }
        val children = directory.listFiles() ?: error("无法扫描 Host install transaction")
        val journals = children.mapNotNull { file ->
            val match = INSTALL_JOURNAL_NAME.matchEntire(file.name) ?: return@mapNotNull null
            match.groupValues[1] to file
        }
        require(journals.size <= MAX_REGISTRY_RECORDS) { "Host install transaction 数量过多" }
        journals.forEach { (cardId, file) ->
            val expected = installJournalFile(cardId)
            require(file.canonicalFile == expected.canonicalFile) { "Host install journal 路径不安全" }
            require(file.isFile && file.length() in 1..MAX_INSTALL_JOURNAL_BYTES) {
                "Host install journal 大小无效"
            }
            val journal = HostInstallJournalCodec.decode(
                readTextBounded(file, MAX_INSTALL_JOURNAL_BYTES, "Host install journal"),
            )
            require(journal.cardId == cardId) { "Host install journal 与文件名不匹配" }
            recoverHostInstallTransaction(
                journal,
                registryCards[cardId]?.let(::hostCardFingerprint),
            )
        }
        cleanupOrphanInstallSidecars()
    }

    private fun recoverHostInstallTransactionIfPresent(cardId: String, registryCard: HostCard?) {
        val journalFile = installJournalFile(cardId)
        if (!journalFile.exists()) return
        require(journalFile.isFile && journalFile.length() in 1..MAX_INSTALL_JOURNAL_BYTES) {
            "Host install journal 大小无效"
        }
        val journal = HostInstallJournalCodec.decode(
            readTextBounded(journalFile, MAX_INSTALL_JOURNAL_BYTES, "Host install journal"),
        )
        require(journal.cardId == cardId) { "Host install journal cardId 不匹配" }
        recoverHostInstallTransaction(journal, registryCard?.let(::hostCardFingerprint))
    }

    private fun recoverHostInstallTransaction(
        journal: HostInstallJournal,
        registryFingerprint: String?,
    ) {
        val target = managedTemplateFile(journal.cardId)
        val staging = installStagingFile(journal.cardId)
        val backup = installBackupFile(journal.cardId)
        when (
            HostInstallJournalCodec.recovery(
                journal = journal,
                registryFingerprint = registryFingerprint,
                targetSha256 = existingTransactionFileSha(target),
                stagingSha256 = existingTransactionFileSha(staging),
                backupSha256 = existingTransactionFileSha(backup),
            )
        ) {
            HostInstallRecovery.KEEP_NEW,
            HostInstallRecovery.KEEP_OLD
            -> Unit
            HostInstallRecovery.RESTORE_NEW -> {
                copyTransactionFileAtomically(staging, target, journal.newTargetSha256)
                check(target.setReadable(true, false)) { "无法恢复新 Host 模板读取权限" }
            }
            HostInstallRecovery.RESTORE_OLD -> {
                copyTransactionFileAtomically(backup, target, requireNotNull(journal.oldTargetSha256))
                check(target.setReadable(true, false)) { "无法恢复旧 Host 模板读取权限" }
            }
            HostInstallRecovery.DELETE_NEW_TARGET -> {
                if (Files.deleteIfExists(target.toPath())) syncDirectory(requireNotNull(target.parentFile))
            }
        }
        if (registryFingerprint == journal.newRegistryFingerprint) {
            finishCommittedHostInstallTransaction(journal)
        } else {
            finishHostInstallTransaction(journal.cardId)
        }
        YLog.info("[$TAG] recovered Host install transaction cardId=${journal.cardId}")
    }

    private fun finishCommittedHostInstallTransaction(journal: HostInstallJournal) {
        journal.oldTemplatePath?.let { path ->
            val oldTarget = File(path).canonicalFile
            val currentTarget = managedTemplateFile(journal.cardId).canonicalFile
            require(oldTarget.path == path && isTemplateForCard(oldTarget, journal.cardId)) {
                "Host install journal 旧模板路径不安全"
            }
            require(!Files.isSymbolicLink(oldTarget.toPath())) {
                "拒绝清理符号链接形式的 Host 旧模板"
            }
            if (oldTarget != currentTarget && Files.deleteIfExists(oldTarget.toPath())) {
                syncDirectory(requireNotNull(oldTarget.parentFile))
            }
        }
        finishHostInstallTransaction(journal.cardId)
    }

    private fun finishHostInstallTransaction(cardId: String) {
        deleteInstallSidecar(installBackupFile(cardId))
        deleteInstallSidecar(installStagingFile(cardId))
        // The journal is the recovery authority and must be removed last.
        deleteInstallSidecar(installJournalFile(cardId))
    }

    private fun cleanupOrphanInstallSidecars() {
        val directory = registryDir()
        if (!directory.isDirectory) return
        directory.listFiles().orEmpty()
            .filter { file -> INSTALL_ORPHAN_NAME.matches(file.name) }
            .forEach(::deleteInstallSidecar)
        val templateDirectory = File(templateBase()).canonicalFile
        if (!templateDirectory.isDirectory || Files.isSymbolicLink(templateDirectory.toPath())) return
        templateDirectory.listFiles().orEmpty()
            .filter { file -> INSTALL_TARGET_TEMP_NAME.matches(file.name) }
            .forEach { file ->
                require(file.absoluteFile.parentFile == templateDirectory && !Files.isSymbolicLink(file.toPath())) {
                    "拒绝删除非托管 Host target 临时文件"
                }
                require(file.canonicalFile.parentFile == templateDirectory) {
                    "Host target 临时文件越出受管目录"
                }
                if (Files.deleteIfExists(file.toPath())) syncDirectory(templateDirectory)
            }
    }

    private fun deleteInstallSidecar(file: File) {
        val directory = registryDir()
        require(file.absoluteFile.parentFile == directory && !Files.isSymbolicLink(file.toPath())) {
            "拒绝删除非托管 Host install sidecar"
        }
        require(file.canonicalFile.parentFile == directory.canonicalFile) {
            "Host install sidecar 越出 registry 目录"
        }
        if (Files.deleteIfExists(file.toPath())) syncDirectory(directory)
    }

    private fun moveIncomingTemplateToStaging(source: File, staging: File) {
        val directory = registryDir()
        require(source.isFile && source.absoluteFile.parentFile == directory) {
            "Host install incoming template 路径不安全"
        }
        require(staging.absoluteFile.parentFile == directory && !staging.exists()) {
            "Host install staging 路径不安全"
        }
        Files.move(source.toPath(), staging.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(directory)
    }

    private fun copyTransactionFileAtomically(source: File, target: File, expectedSha256: String) {
        require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
            "Host install transaction 源文件无效"
        }
        require(source.length() in 1..MAX_TEMPLATE_BYTES) { "Host install transaction 源文件大小无效" }
        val parent = requireNotNull(target.absoluteFile.parentFile) { "Host install target 缺少父目录" }
        require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) {
            "Host install target 父目录不安全"
        }
        require(!Files.isSymbolicLink(target.toPath()) && target.canonicalFile.parentFile == parent.canonicalFile) {
            "Host install target 路径不安全"
        }
        val temp = File.createTempFile(".outerview_install_write_", ".tmp", parent)
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(temp, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        require(total <= MAX_TEMPLATE_BYTES) { "Host install template 超过 16 MB" }
                        output.write(buffer, 0, read)
                    }
                    require(total > 0L) { "Host install template 为空" }
                    output.fd.sync()
                }
            }
            require(sha256(temp) == expectedSha256) { "Host install transaction 副本摘要不匹配" }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectoryAfterCommit(parent, "Host install template")
        } finally {
            deleteAtomicTempBestEffort(temp, "Host install template")
        }
    }

    private fun existingTransactionFileSha(file: File): String? {
        if (!file.exists()) return null
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Host install transaction 文件无效"
        }
        require(file.length() in 1..MAX_TEMPLATE_BYTES) { "Host install transaction 文件大小无效" }
        return sha256(file)
    }

    private fun isVerifiedHostCard(card: HostCard): Boolean = runCatching {
        val target = File(card.templatePath).canonicalFile
        require(isTemplateForCard(target, card.cardId)) { "Host card target 路径不安全" }
        existingTransactionFileSha(target) == card.sha256
    }.getOrDefault(false)

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun syncDirectoryAfterCommit(directory: File, label: String) {
        runCatching { syncDirectory(directory) }
            .onFailure { error -> YLog.warn("[$TAG] $label committed; directory fsync failed", error) }
    }

    private fun deleteAtomicTempBestEffort(temp: File, label: String) {
        runCatching { Files.deleteIfExists(temp.toPath()) }
            .onFailure { error -> YLog.warn("[$TAG] $label temp cleanup failed", error) }
    }

    private fun businessPath(business: String): String? =
        cards.values.firstOrNull { it.business == business }?.templatePath

    private fun loadRegistry() {
        synchronized(registryLock) {
            val currentFile = runCatching { registryFile() }.getOrElse { error ->
                registryWriteBlocked = true
                YLog.error("[$TAG] registry path validation failed; writes blocked", error)
                return
            }
            if (!currentFile.isFile) {
                runCatching {
                    recoverHostInstallTransactions(emptyMap())
                    cards.clear()
                    suppressedBusinesses.clear()
                    pendingBulkBusinesses.clear()
                    pendingBulkTemplates.clear()
                }.onSuccess {
                    registryWriteBlocked = false
                }.onFailure { error ->
                    registryWriteBlocked = true
                    YLog.error("[$TAG] install recovery without registry failed; writes blocked", error)
                }
                return
            }
            runCatching {
                val root = JSONObject(readTextBounded(currentFile, MAX_REGISTRY_BYTES, "宿主 registry"))
                val loadedCards = decodeRegistryCards(root)
                val loadedSuppressed = loadedCards.values
                    .filterTo(linkedSetOf()) { card -> !card.enabled || card.pendingDelete }
                    .mapTo(linkedSetOf()) { card -> card.business }
                recoverHostInstallTransactions(loadedCards)
                loadedCards.values.forEach { card ->
                    if (!card.pendingDelete && !isVerifiedHostCard(card)) {
                        loadedSuppressed.add(card.business)
                        YLog.warn(
                            "[$TAG] Host card target missing or digest mismatch; runtime restore blocked " +
                                "cardId=${card.cardId}",
                        )
                    }
                }
                val pendingBusinesses = root.optJSONArray("pendingBulkBusinesses") ?: JSONArray()
                require(pendingBusinesses.length() <= MAX_REGISTRY_RECORDS) {
                    "宿主 registry 待删除 business 数量过多"
                }
                val verifiedOwnership = loadedCards.values.map { card ->
                    VerifiedHostCardOwnership(
                        business = card.business,
                        templatePath = File(card.templatePath).canonicalPath,
                        pendingDelete = card.pendingDelete,
                    )
                }
                val loadedPendingBusinesses = linkedSetOf<String>()
                for (index in 0 until pendingBusinesses.length()) {
                    val business = pendingBusinesses.optString(index)
                    val cardId = business
                        .removePrefix(ManagedHostPaths.BusinessPrefix)
                        .removePrefix(ManagedHostPaths.LegacyBusinessPrefix)
                    require(ManagedHostPaths.matchesBusiness(cardId, business)) {
                        "宿主 registry 待删除 business 无效"
                    }
                    if (!HostPendingCleanupPolicy.canRestoreBusiness(business, verifiedOwnership)) {
                        YLog.warn("[$TAG] dropped unowned legacy pending business=$business")
                        continue
                    }
                    require(loadedPendingBusinesses.add(business)) {
                        "宿主 registry 包含重复待删除 business"
                    }
                    loadedSuppressed.add(business)
                }
                val pendingTemplates = root.optJSONArray("pendingBulkTemplates") ?: JSONArray()
                require(pendingTemplates.length() <= MAX_REGISTRY_RECORDS) {
                    "宿主 registry 待删除模板数量过多"
                }
                val loadedPendingTemplates = linkedSetOf<String>()
                for (index in 0 until pendingTemplates.length()) {
                    val target = File(pendingTemplates.optString(index)).canonicalFile
                    val canRestore = HostPendingCleanupPolicy.canRestoreTemplate(
                        business = target.name,
                        canonicalPath = target.path,
                        currentNamespaceManaged = isManagedTemplate(target),
                        verifiedCards = verifiedOwnership,
                    )
                    if (!canRestore) {
                        YLog.warn("[$TAG] dropped unowned legacy pending template=${target.path}")
                        continue
                    }
                    require(loadedPendingTemplates.add(target.path)) {
                        "宿主 registry 包含重复待删除模板"
                    }
                    loadedPendingBusinesses.add(target.name)
                    loadedSuppressed.add(target.name)
                }

                cards.clear()
                cards.putAll(loadedCards)
                suppressedBusinesses.clear()
                suppressedBusinesses.addAll(loadedSuppressed)
                pendingBulkBusinesses.clear()
                pendingBulkBusinesses.addAll(loadedPendingBusinesses)
                pendingBulkTemplates.clear()
                pendingBulkTemplates.addAll(loadedPendingTemplates)
            }.onSuccess {
                registryWriteBlocked = false
            }.onFailure {
                registryWriteBlocked = true
                YLog.error("[$TAG] registry load failed; writes blocked to preserve the file", it)
            }
        }
    }

    private fun readTextBounded(file: File, maxBytes: Long, label: String): String {
        require(file.isFile && file.length() in 1..maxBytes) { "$label 文件大小无效" }
        val output = ByteArrayOutputStream(file.length().toInt())
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= maxBytes) { "$label 文件过大" }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun isUnsafeDisplayCharacter(character: Char): Boolean =
        character.isISOControl() || character in '\u202a'..'\u202e' ||
            character in '\u2066'..'\u2069' || character == '\u200e' ||
            character == '\u200f' || character == '\u061c'

    private fun registryStateSnapshot() = RegistryStateSnapshot(
        cards = cards.toMap(),
        suppressedBusinesses = suppressedBusinesses.toSet(),
        pendingBulkBusinesses = pendingBulkBusinesses.toSet(),
        pendingBulkTemplates = pendingBulkTemplates.toSet(),
    )

    private fun restoreRegistryState(snapshot: RegistryStateSnapshot) {
        cards.clear()
        cards.putAll(snapshot.cards)
        suppressedBusinesses.clear()
        suppressedBusinesses.addAll(snapshot.suppressedBusinesses)
        pendingBulkBusinesses.clear()
        pendingBulkBusinesses.addAll(snapshot.pendingBulkBusinesses)
        pendingBulkTemplates.clear()
        pendingBulkTemplates.addAll(snapshot.pendingBulkTemplates)
    }

    private fun encodeRegistry(
        cardValues: Collection<HostCard> = cards.values,
        pendingBusinesses: Collection<String> = pendingBulkBusinesses,
        pendingTemplates: Collection<String> = pendingBulkTemplates,
    ): ByteArray {
        require(cardValues.size <= MAX_REGISTRY_RECORDS) { "宿主 registry 卡片数量过多" }
        val normalizedCards = cardValues.map { card ->
            val target = File(card.templatePath).canonicalFile
            require(
                ManagedHostPaths.matchesBusiness(card.cardId, card.business) &&
                    isTemplateForCard(target, card.cardId),
            ) { "宿主 registry 卡片身份或路径无效" }
            card.copy(templatePath = target.path)
        }
        require(normalizedCards.map { it.cardId }.distinct().size == normalizedCards.size) {
            "宿主 registry cardId 重复"
        }
        require(normalizedCards.map { it.templatePath }.distinct().size == normalizedCards.size) {
            "宿主 registry 模板路径重复"
        }
        require(normalizedCards.all { it.notificationId in 620_000..719_999 } &&
            normalizedCards.map { it.notificationId }.distinct().size == normalizedCards.size
        ) {
            "宿主 registry notificationId 重复"
        }
        require(pendingBusinesses.size <= MAX_REGISTRY_RECORDS) {
            "宿主 registry 待删除 business 数量过多"
        }
        require(pendingTemplates.size <= MAX_REGISTRY_RECORDS) {
            "宿主 registry 待删除模板数量过多"
        }
        val registryOwnedTemplatePaths = normalizedCards.mapTo(HashSet()) { it.templatePath }
        val normalizedPendingTemplates = pendingTemplates.map { path ->
            File(path).canonicalFile.also { target ->
                require(
                    isManagedTemplate(target) || target.path in registryOwnedTemplatePaths,
                ) { "宿主 registry 待删除模板路径无效" }
            }.path
        }.toSet()
        require(normalizedPendingTemplates.size == pendingTemplates.size) {
            "宿主 registry 待删除模板路径重复"
        }
        val array = JSONArray()
        normalizedCards.sortedBy { it.cardId }.forEach { card ->
            array.put(JSONObject().put("cardId", card.cardId).put("business", card.business)
                .put("displayName", card.displayName).put("templatePath", card.templatePath)
                .put("sha256", card.sha256).put("notificationId", card.notificationId)
                .put("updatedAt", card.updatedAt).put("enabled", card.enabled)
                .put("pendingDelete", card.pendingDelete)
                .put("rearParam", card.rearParam).put("focusParam", card.focusParam))
        }
        val pendingBusinessArray = JSONArray().apply { pendingBusinesses.sorted().forEach(::put) }
        val pendingTemplateArray = JSONArray().apply { normalizedPendingTemplates.sorted().forEach(::put) }
        return JSONObject()
            .put("schemaVersion", 5)
            .put("cards", array)
            .put("pendingBulkBusinesses", pendingBusinessArray)
            .put("pendingBulkTemplates", pendingTemplateArray)
            .toString()
            .toByteArray(Charsets.UTF_8)
            .also { bytes ->
                require(bytes.size <= MAX_REGISTRY_BYTES) { "宿主 registry 超过 2 MB，已拒绝写入" }
            }
    }

    private fun writeRegistry() = synchronized(registryLock) {
        check(!registryWriteBlocked) { "宿主 registry 无法安全读取；已阻止覆盖" }
        val bytes = encodeRegistry()
        val dir = registryDir()
        check(dir.isDirectory || dir.mkdirs()) { "无法创建宿主 registry 目录" }
        require(!Files.isSymbolicLink(dir.toPath())) { "宿主 registry 目录不允许使用符号链接" }
        val target = registryFile()
        writeFileAtomically(target, bytes, MAX_REGISTRY_BYTES, "宿主 registry")
        target.setReadable(true, true)
    }

    private fun writeFileAtomically(target: File, bytes: ByteArray, maxBytes: Long, label: String) {
        require(bytes.size.toLong() <= maxBytes) { "$label 超过大小限制" }
        val parent = requireNotNull(target.absoluteFile.parentFile) { "$label 缺少父目录" }
        require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) { "$label 父目录不安全" }
        require(!Files.isSymbolicLink(target.toPath())) { "$label 不允许使用符号链接" }
        require(target.canonicalFile.parentFile == parent.canonicalFile) { "$label 越出受管目录" }
        val temp = File.createTempFile(".outerview_write_", ".tmp", parent)
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectoryAfterCommit(parent, label)
        } finally {
            deleteAtomicTempBestEffort(temp, label)
        }
    }

    private fun validateTemplate(file: File): String = SmartAssistantTemplateValidator.inspect(file).sha256

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
        val ownedLegacyBusinesses = cards.values.asSequence()
            .map { it.business }
            .filter(ManagedHostPaths::isLegacyBusiness)
            .toSet()
        return runOnMainThread {
            val target = manager ?: return@runOnMainThread emptySet()
            val list = runCatching {
                target.asResolver().firstField { name = resolveManagerListFieldName() }.get<Any>() as? Iterable<*>
            }.getOrNull() ?: return@runOnMainThread emptySet()
            list.mapNotNull { widget ->
                val extras = managerWidgetBundle(widget) ?: return@mapNotNull null
                val business = extras.getString("business") ?: return@mapNotNull null
                val pkg = widgetPackage(extras, business)
                business.takeIf {
                    pkg == TESTER_PACKAGE &&
                        (ManagedHostPaths.isCurrentBusiness(it) || it in ownedLegacyBusinesses)
                }
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
            val raw = readTextBounded(
                file,
                MAX_NOTIFICATION_STATE_BYTES,
                "宿主 notification state",
            ).removePrefix("\uFEFF")
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

    private fun readInstanceField(instance: Any?, fieldName: String): Any? {
        val receiver = instance ?: return null
        val field = generateSequence(receiver.javaClass) { it.superclass }
            .takeWhile { it != Any::class.java }
            .mapNotNull { type -> runCatching { type.getDeclaredField(fieldName) }.getOrNull() }
            .firstOrNull()
            ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(receiver)
        }.getOrNull()
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
        java.security.DigestInputStream(file.inputStream(), digest).use { stream ->
            val scratch = ByteArray(8 * 1024)
            while (stream.read(scratch) != -1) Unit
        }
        return digest.digest().joinToString(separator = "") { byte ->
            byte.toUByte().toString(radix = 16).padStart(2, '0')
        }
    }

    private fun resolveMethod(key: String, query: HostMethodQuery): HostMethodRef =
        hostDex?.method("FUN_CARD_$key", query)
            ?.takeIf { it.className.isNotBlank() && it.methodName.isNotBlank() }
            ?: error("无法解析方法 $key")

    private fun resolveClass(key: String, query: HostClassQuery): String =
        hostDex?.className("FUN_CARD_$key", query)
            ?.takeIf(String::isNotBlank)
            ?: error("无法解析类 $key")

    private fun resolveField(key: String, query: HostFieldQuery): String =
        hostDex?.fieldName("FUN_CARD_$key", query)
            ?.takeIf(String::isNotBlank)
            ?: error("无法解析字段 $key")

    private fun resolveManagerInitMethod() = resolveMethod(
        "MANAGER_INIT",
        HostMethodQuery(
            parameterTypes = listOf(Context::class.java.name),
            returnType = "void",
            strings = setOf(
                "SmartAssistantManager initialized",
                "SmartAssistant not supported, skip manager initialization",
            ),
        ),
    )

    private fun resolveParseWidgetMethod() = resolveMethod(
        "PARSE_WIDGET",
        HostMethodQuery(
            parameterCount = 2,
            strings = setOf(
                "Found business in rear.paramV1: %s",
                "No business found for %s and not in config",
            ),
        ),
    )

    private fun resolveUtilsClassName() = resolveParseWidgetMethod().className

    private fun resolvePathMethod() = resolveMethod(
        "RESOLVE_PATH",
        HostMethodQuery(
            owner = resolveUtilsClassName(),
            parameterTypes = listOf(String::class.java.name, String::class.java.name),
            returnType = String::class.java.name,
            strings = setOf("unified.music", "music"),
        ),
    )

    private fun resolveAllowAppMethod() = resolveMethod(
        "ALLOW_APP",
        HostMethodQuery(
            owner = resolveUtilsClassName(),
            parameterTypes = listOf("java.lang.String", "java.util.Set", "java.util.Map"),
            returnType = "boolean",
            strings = setOf(
                "Music app %s allowed: %s (music switch: %s)",
                "Multi-business app %s allowed: false (no business enabled)",
            ),
        ),
    )

    private fun resolvePostRunnableClassName() = resolveClass(
        "POST_RUNNABLE",
        HostClassQuery(
            strings = setOf(
                "No valid params: %s",
                "Using compositeKey: %s (business: %s)",
            ),
        ),
    )

    private fun resolveManagerInsertMethod(): HostMethodRef {
        val managerClass = resolveManagerInitMethod().className
        return resolveMethod(
            "MANAGER_INSERT",
            HostMethodQuery(
                owner = managerClass,
                parameterCount = 1,
                returnType = "void",
                strings = setOf("Inserted widget at position %d, type=%s, new display index=%d"),
            ),
        )
    }

    private fun resolveManagerListFieldName(): String {
        val managerClass = resolveManagerInitMethod().className
        return resolveField(
            "MANAGER_LIST",
            HostFieldQuery(
                owner = managerClass,
                type = "java.util.ArrayList",
                readBy = resolveManagerInsertMethod(),
            ),
        )
    }

    private fun resolveRemoveBusinessMethod(): HostMethodRef {
        val managerClass = resolveManagerInitMethod().className
        return resolveMethod(
            "REMOVE_BUSINESS",
            HostMethodQuery(
                owner = managerClass,
                parameterTypes = listOf(String::class.java.name, String::class.java.name),
                returnType = "void",
                strings = setOf("Removing widgets for %s:%s"),
            ),
        )
    }

    private fun resolveRemoveNotificationMethod(): HostMethodRef {
        val managerClass = resolveManagerInitMethod().className
        return resolveMethod(
            "REMOVE_NOTIFICATION",
            HostMethodQuery(
                owner = managerClass,
                parameterCount = 3,
                returnType = "void",
                strings = setOf("Widget not found for multi-business app: %s, ID: %d"),
            ),
        )
    }

    private fun resolveRemoveCompositeMethod(): HostMethodRef {
        val managerClass = resolveManagerInitMethod().className
        return resolveMethod(
            "REMOVE_COMPOSITE",
            HostMethodQuery(
                owner = managerClass,
                parameterTypes = listOf("int", "java.lang.String", "java.lang.String"),
                returnType = "boolean",
                strings = setOf("Found widget for compositeKey: %s, removing"),
            ),
        )
    }

    private fun resolveWidgetExtrasFieldName(): String {
        val insertPoint = resolveManagerInsertMethod()
        val recordClass = insertPoint.className.toClass().resolve().firstMethod {
            name = insertPoint.methodName
            parameterCount = 1
        }.self.parameterTypes.firstOrNull()?.name ?: error("无法解析卡片记录类型")
        return resolveField(
            "WIDGET_EXTRAS",
            HostFieldQuery(owner = recordClass, type = Bundle::class.java.name),
        )
    }

    private fun resolveNotificationWidgetApplyMethod() = resolveMethod(
        "WIDGET_APPLY",
        HostMethodQuery(
            returnType = "void",
            strings = setOf("notification_received", "params_transferred"),
        ),
    )

    private fun resolveNotificationWidgetHostClassName(): String =
        resolveNotificationWidgetApplyMethod().className.toClass().superclass?.name ?: error("无法解析通知卡片类")

    private fun resolveNotificationWidgetTemplatePathFieldName(): String {
        val hostClass = resolveNotificationWidgetHostClassName()
        val reader = resolveMethod(
            "WIDGET_PATH_READER",
            HostMethodQuery(
                owner = hostClass,
                parameterTypes = listOf(Context::class.java.name),
                returnType = "android.view.View",
                strings = setOf("onCreate path ="),
            ),
        )
        return resolveField(
            "WIDGET_PATH",
            HostFieldQuery(owner = hostClass, type = String::class.java.name, readBy = reader),
        )
    }

    private fun resolveNotificationWidgetExtrasFieldName(): String {
        val apply = resolveNotificationWidgetApplyMethod()
        return resolveField(
            "LIVE_WIDGET_EXTRAS",
            HostFieldQuery(type = Bundle::class.java.name, readBy = apply),
        )
    }
}
