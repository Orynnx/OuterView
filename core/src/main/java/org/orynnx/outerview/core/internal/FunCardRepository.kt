package org.orynnx.outerview.core.internal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.gson.Gson
import org.orynnx.outerview.core.hostapi.FunCardHostClient
import org.orynnx.outerview.core.hostapi.FunCardHostContract
import org.orynnx.outerview.core.hostapi.HostActionResult
import org.orynnx.outerview.core.hostapi.HostCapabilities
import org.orynnx.outerview.core.hostapi.HostCardDiagnostics
import org.orynnx.outerview.core.hostapi.SystemTemplateInfo
import org.orynnx.outerview.core.RearCardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class FunCardRepositoryRefresh(
    val cards: List<CustomCardRecord>,
    val capabilities: HostCapabilities,
)

object FunCardRepository {
    private const val Tag = "FunCardManager"
    private const val RegistryName = "custom_cards_registry_v2.json"
    private const val LegacyRegistryName = "fun_cards_registry.json"
    private const val CardsDirName = "custom_cards_v2"
    private const val ReplacementJournalName = ".source-replace.json"
    private const val ReplacementBackupName = ".source-replace-backup.zip"
    private const val MaxReplacementJournalBytes = 4L * 1024L
    private const val StagingDirName = "fun_card_import"
    private const val StagingMaxAgeMs = 24L * 60L * 60L * 1000L
    private const val MaxRegistryBytes = 2L * 1024L * 1024L
    private const val MaxPayloadBytes = 128 * 1024
    private const val MaxStagingBytes = 64L * 1024L * 1024L
    private const val MaxStagingFiles = 4
    private const val ImportCopyTimeoutSeconds = 15L
    private val gson = Gson()
    private val registryLock = Any()
    private val stagingLock = Any()
    private val stagingReservations = mutableSetOf<String>()

    private class RefreshHostSession(context: Context) : AutoCloseable {
        val client = FunCardHostClient()
        private val connection = SingleConnectSession(
            connectBlock = { client.connect(context.applicationContext) },
            disconnectBlock = client::disconnect,
        )

        fun connect(): Result<HostCapabilities> = connection.connect()

        override fun close() = connection.close()
    }

    suspend fun loadCards(context: Context): List<CustomCardRecord> = withContext(Dispatchers.IO) {
        val hostSession = RefreshHostSession(context)
        try {
            loadCards(context, hostSession)
        } finally {
            hostSession.close()
        }
    }

    internal suspend fun refresh(context: Context): FunCardRepositoryRefresh = withContext(Dispatchers.IO) {
        val hostSession = RefreshHostSession(context)
        try {
            FunCardRepositoryRefresh(
                cards = loadCards(context, hostSession),
                capabilities = hostSession.connect().getOrThrow(),
            )
        } finally {
            hostSession.close()
        }
    }

    private suspend fun loadCards(
        context: Context,
        hostSession: RefreshHostSession,
    ): List<CustomCardRecord> {
        cleanupStaleImports(context)
        recoverTemplateReplacements(context)
        processPendingCleanup(context, hostSession)
        processPendingInstalls(context, hostSession)
        synchronizeHostCards(hostSession)
        return reconcile(context, loadAll(context), hostSession).filterNot { it.deleted }
    }

    private suspend fun processPendingInstalls(
        context: Context,
        hostSession: RefreshHostSession,
    ) {
        val pending = loadAll(context).filter(PendingInstallPolicy::shouldReplay)
        if (pending.isEmpty()) return

        val client = hostSession.client
        val caps = hostSession.connect().getOrNull()
        if (caps?.compatible != true) return
        // A prior install may have committed in the Host just before this
        // process died. If enumeration itself fails, retain every outbox entry;
        // later refresh phases may still use this same connected session.
        val hostCards = runCatching { client.listHostCards() }.getOrNull() ?: return
        pending.forEach { candidate ->
            val record = loadAll(context).firstOrNull { it.cardId == candidate.cardId }
                ?: return@forEach
            if (!PendingInstallPolicy.shouldReplay(record)) return@forEach
            val confirmed = PendingInstallPolicy.exactHostMatch(record, hostCards)
            if (confirmed != null) {
                val next = PendingInstallPolicy.hostConfirmed(
                    record = record,
                    hostCard = confirmed,
                    now = System.currentTimeMillis(),
                )
                update(context, next)
                log("install-confirmed", next, true, next.lastMessage.orEmpty())
            } else {
                installCard(context, record, client)
            }
        }
    }

    private fun synchronizeHostCards(hostSession: RefreshHostSession) {
        val caps = hostSession.connect().getOrNull()
        if (caps?.compatible == true && caps.managerCaptured) hostSession.client.synchronizeCards()
    }

    fun hasLegacyRegistry(context: Context): Boolean =
        File(context.filesDir, LegacyRegistryName).isFile

    fun discardPendingImport(context: Context, pending: PendingCardImport) {
        discardStagingFile(context, pending.stagedFile)
    }

    suspend fun inspectImport(
        context: Context,
        uri: Uri,
        displayNameHint: String?,
    ): Result<PendingCardImport> = withContext(Dispatchers.IO) {
        runCatching {
            val staged = reserveStagingFile(context)
            try {
                val timeoutNanos = TimeUnit.SECONDS.toNanos(ImportCopyTimeoutSeconds)
                val cancellationSignal = CancellationSignal()
                val abortRequested = AtomicBoolean(false)
                val descriptorRef = AtomicReference<ParcelFileDescriptor?>()
                FileOutputStream(staged, false).use { output ->
                    val copied = try {
                        BoundedDeadlineCopy.runWithSupervisor(
                            timeoutNanos = timeoutNanos,
                            closeSource = {
                                abortRequested.set(true)
                                val closeFailure = runCatching {
                                    descriptorRef.getAndSet(null)?.close()
                                }.exceptionOrNull()
                                runCatching { cancellationSignal.cancel() }
                                    .onFailure { cancelFailure ->
                                        if (closeFailure == null) throw cancelFailure
                                        closeFailure.addSuppressed(cancelFailure)
                                    }
                                if (closeFailure != null) throw closeFailure
                            },
                        ) {
                            val descriptor = requireNotNull(
                                context.contentResolver.openFileDescriptor(uri, "r", cancellationSignal),
                            ) { "无法读取所选文件" }
                            descriptorRef.set(descriptor)
                            if (abortRequested.get()) {
                                descriptorRef.compareAndSet(descriptor, null)
                                runCatching { descriptor.close() }
                                throw InterruptedIOException("card import was cancelled")
                            }
                            try {
                                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                                    BoundedDeadlineCopy.copy(
                                        input = input,
                                        output = output,
                                        maxBytes = SmartAssistantTemplateValidator.MaxCompressedBytes,
                                        timeoutNanos = timeoutNanos,
                                    )
                                }
                            } finally {
                                descriptorRef.compareAndSet(descriptor, null)
                                runCatching { descriptor.close() }
                            }
                        }
                    } catch (error: BoundedDeadlineCopy.DeadlineExceededException) {
                        throw IOException("读取 ZIP 超时（${ImportCopyTimeoutSeconds} 秒）", error)
                    } catch (error: BoundedDeadlineCopy.LimitExceededException) {
                        throw IOException("ZIP 超过 16 MB", error)
                    }
                    require(copied > 0L) { "ZIP 不能为空" }
                    output.fd.sync()
                }
                val inspection = SmartAssistantTemplateValidator.inspect(staged)
                val metadataName = safeDisplayText(inspection.metadata?.name, 80)
                PendingCardImport(
                    stagedFile = staged,
                    suggestedName = metadataName
                        ?: safeDisplayText(displayNameHint?.substringBeforeLast('.'), 80)
                        ?: "自定义背屏卡片",
                    inspection = inspection,
                )
            } catch (error: Throwable) {
                discardStagingFile(context, staged)
                throw error
            } finally {
                synchronized(stagingLock) { stagingReservations.remove(staged.absolutePath) }
            }
        }
    }

    suspend fun commitImport(
        context: Context,
        pending: PendingCardImport,
        systemTemplateHashes: Set<String> = emptySet(),
    ): CardOperationResult = withContext(Dispatchers.IO) {
        synchronized(stagingLock) {
        val verifiedInspection = validatePendingImport(context, pending)
        val records = loadAll(context)
        records.firstOrNull { !it.deleted && it.sha256 == verifiedInspection.sha256 }?.let {
            discardStagingFile(context, pending.stagedFile)
            return@withContext CardOperationResult(
                false,
                "该模板已经导入：${it.displayName}",
                it.stateEnum,
                it,
            )
        }
        if (verifiedInspection.sha256 in systemTemplateHashes) {
            discardStagingFile(context, pending.stagedFile)
            return@withContext CardOperationResult(false, "系统已经提供相同模板，不重复添加", RearCardState.NOT_INSTALLED)
        }

        val cardId = UUID.randomUUID().toString().replace("-", "").lowercase()
        val cardDir = cardDir(context, cardId).apply {
            check(isDirectory || mkdirs()) { "无法创建卡片目录" }
        }
        val target = managedLocalFile(context, cardId)
        check(!target.exists()) { "卡片目标文件已存在" }
        val notificationId = allocateNotificationId(cardId, records)
        val record = try {
            val stableInspection = writeTemplateAtomically(
                pending.stagedFile,
                target,
                verifiedInspection.sha256,
            )
            val metadata = stableInspection.metadata
            CustomCardRecord(
                cardId = cardId,
                business = ManagedHostPaths.business(cardId),
                displayName = safeDisplayText(pending.suggestedName, 80) ?: "自定义背屏卡片",
                author = safeDisplayText(metadata?.author, 80),
                templateVersion = safeDisplayText(metadata?.version, 32),
                localZipPath = target.absolutePath,
                sha256 = stableInspection.sha256,
                notificationId = notificationId,
                mamlConfigJson = gson.toJson(metadata?.defaultMamlConfig ?: emptyMap<String, Any?>()),
                state = RearCardState.NOT_INSTALLED.value,
                pendingInstall = true,
                lastMessage = "已导入，等待安装",
                updatedAt = System.currentTimeMillis(),
            ).also { candidate ->
                validateRuntimePayload(candidate)
                saveAll(context, records + candidate)
            }
        } catch (error: Throwable) {
            runCatching { deleteManagedCardFiles(context, cardId) }
            throw error
        }
        discardStagingFile(context, pending.stagedFile)
        log("import", record, true, record.lastMessage.orEmpty())
        CardOperationResult(true, "模板导入成功", RearCardState.NOT_INSTALLED, record)
        }
    }

    suspend fun replaceTemplate(
        context: Context,
        record: CustomCardRecord,
        pending: PendingCardImport,
    ): CardOperationResult = withContext(Dispatchers.IO) {
        synchronized(stagingLock) {
        val verifiedInspection = validatePendingImport(context, pending)
        if (record.stateEnum == RearCardState.INSTALLED_ENABLED) {
            return@withContext CardOperationResult(false, "请先关闭显示到背屏", record.stateEnum, record)
        }
        recoverTemplateReplacements(context)
        val target = managedLocalFile(context, record.cardId)
        val hadOriginal = target.isFile
        if (hadOriginal) {
            val oldInspection = SmartAssistantTemplateValidator.inspect(target)
            require(oldInspection.sha256 == record.sha256) { "本地模板与 registry 的 SHA-256 不一致" }
        }
        if (verifiedInspection.sha256 == record.sha256) {
            val installIntent = PendingInstallPolicy.markPending(record, System.currentTimeMillis())
            update(context, installIntent)
            writeTemplateAtomically(pending.stagedFile, target, record.sha256)
            discardStagingFile(context, pending.stagedFile)
            return@withContext CardOperationResult(
                true,
                "模板内容未变化",
                installIntent.stateEnum,
                installIntent,
            )
        }
        val next = synchronized(registryLock) {
            val journalFile = replacementJournalFile(context, record.cardId)
            val backupFile = replacementBackupFile(context, record.cardId)
            require(!journalFile.exists() && !backupFile.exists()) { "已有未完成的卡片替换事务" }
            val journal = TemplateReplacementJournal(
                cardId = record.cardId,
                oldSha256 = record.sha256,
                newSha256 = verifiedInspection.sha256,
                hadOriginal = hadOriginal,
            )
            // Persist intent before creating the backup so every later crash
            // state has an unambiguous recovery record.
            writeAtomically(journalFile, TemplateReplacementJournalCodec.encode(journal))
            if (hadOriginal) {
                copyBounded(target, backupFile, SmartAssistantTemplateValidator.MaxCompressedBytes)
                require(SmartAssistantTemplateValidator.inspect(backupFile).sha256 == record.sha256) {
                    "旧模板备份校验失败"
                }
            }
            try {
                val stableInspection = writeTemplateAtomically(
                    pending.stagedFile,
                    target,
                    verifiedInspection.sha256,
                )
                record.copy(
                    displayName = safeDisplayText(stableInspection.metadata?.name, 80) ?: record.displayName,
                    author = safeDisplayText(stableInspection.metadata?.author, 80) ?: record.author,
                    templateVersion = safeDisplayText(stableInspection.metadata?.version, 32) ?: record.templateVersion,
                    sha256 = stableInspection.sha256,
                    state = RearCardState.NOT_INSTALLED.value,
                    pendingInstall = true,
                    hostTemplatePath = null,
                    lastMessage = "模板已替换，请重新安装",
                    updatedAt = System.currentTimeMillis(),
                ).also { candidate ->
                    validateRuntimePayload(candidate)
                    update(context, candidate)
                }
            } catch (error: Throwable) {
                // An exception is not proof that the atomic registry move did not
                // commit. Re-read the durable registry before choosing rollback or
                // commit so a post-move failure cannot restore the old ZIP under a
                // registry that already points at the new SHA-256.
                runCatching { recoverTemplateReplacementFromRegistry(context, journal) }
                    .onFailure(error::addSuppressed)
                throw error
            }.also {
                runCatching { finishTemplateReplacement(context, record.cardId) }
                    .onFailure { cleanupError ->
                        Log.w(Tag, "替换已提交；事务清理将在下次启动重试", cleanupError)
                    }
            }
        }
        discardStagingFile(context, pending.stagedFile)
        CardOperationResult(true, next.lastMessage.orEmpty(), next.stateEnum, next)
        }
    }

    suspend fun installCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            installCard(context, record) { installIntent, commandId, localFile ->
                withHost(context) { client ->
                    ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        client.installCard(request(installIntent, commandId), fd)
                    }
                }
            }
        }

    private fun installCard(
        context: Context,
        record: CustomCardRecord,
        client: FunCardHostClient,
    ): CardOperationResult = installCard(context, record) { installIntent, commandId, localFile ->
        ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            client.installCard(request(installIntent, commandId), fd)
        }
    }

    private fun installCard(
        context: Context,
        record: CustomCardRecord,
        install: (CustomCardRecord, String, File) -> HostActionResult,
    ): CardOperationResult {
        val commandId = commandId("install", record.cardId)
        val installIntent = PendingInstallPolicy.markPending(record, System.currentTimeMillis())
        // The outbox must be durable before opening the Binder crash window.
        update(context, installIntent)
        val localFile = managedLocalFile(context, installIntent.cardId)
        val result = if (!localFile.isFile) {
            HostActionResult(false, "本地 ZIP 不存在", "LOCAL_ZIP_MISSING")
        } else {
            runCatching { install(installIntent, commandId, localFile) }
                .getOrElse { error ->
                    HostActionResult(false, error.message ?: "安装失败", "INSTALL_ERROR")
                }
        }
        val next = PendingInstallPolicy.installationFinished(
            record = installIntent,
            success = result.success,
            message = result.message,
            templatePath = result.templatePath,
            commandId = commandId,
            now = System.currentTimeMillis(),
        )
        update(context, next)
        log("install", next, result.success, result.message)
        return CardOperationResult(result.success, result.message, next.stateEnum, next)
    }

    suspend fun showCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            if (record.stateEnum == RearCardState.NOT_INSTALLED) {
                return@withContext CardOperationResult(false, "请先安装模板", record.stateEnum, record)
            }
            val commandId = commandId("show", record.cardId)
            val client = FunCardHostClient()
            val capabilities = client.connect(context)
            if (!capabilities.compatible) {
                return@withContext failAndPersist(
                    context,
                    record,
                    commandId,
                    capabilities.error ?: "Hook 未连接",
                    desiredEnabled = false,
                )
            }
            val beforeActivate = client.diagnostics(record.cardId, record.business, record.notificationId)
            if (!beforeActivate.templateReadable) {
                val localFile = managedLocalFile(context, record.cardId)
                if (!localFile.isFile) {
                    client.disconnect()
                    return@withContext failAndPersist(
                        context,
                        record,
                        commandId,
                        "宿主模板丢失，且本地 ZIP 不存在",
                        desiredEnabled = false,
                    )
                }
                val redeployed = ParcelFileDescriptor.open(
                    localFile,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { fd -> client.installCard(request(record, commandId), fd) }
                if (!redeployed.success) {
                    client.disconnect()
                    return@withContext failAndPersist(
                        context,
                        record,
                        commandId,
                        "自动重新部署失败：${redeployed.message}",
                        desiredEnabled = false,
                    )
                }
            }
            FunCardNotificationController.cancel(context, record.notificationId)
            val activated = client.activateCard(request(record, commandId, includePayload = true))
            if (!activated.success) {
                client.disconnect()
                return@withContext failAndPersist(
                    context,
                    record,
                    commandId,
                    activated.message,
                    desiredEnabled = false,
                )
            }
            val diagnostics = awaitDiagnostics(client, record, visible = true)
            client.disconnect()
            // Runtime insertion is the host's enable callback.  The notification widget
            // observer may arrive much later (or never show a popup), so it must not
            // block the card from becoming operable.
            val verified = diagnostics.managerListContains &&
                diagnostics.runtimeActivated && diagnostics.templateReadable
            val nextState = if (verified) RearCardState.INSTALLED_ENABLED else RearCardState.ERROR
            val message = if (verified) "卡片已启用，正在由背屏加载" else
                diagnostics.lastError ?: "5 秒内未收到宿主启用回调"
            val next = record.copy(
                state = nextState.value,
                desiredEnabled = verified,
                lastCommandId = commandId,
                lastMessage = message,
                updatedAt = System.currentTimeMillis(),
            )
            update(context, next)
            log("show", next, verified, message)
            CardOperationResult(verified, message, nextState, next)
        }

    suspend fun hideCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            val commandId = commandId("hide", record.cardId)
            FunCardNotificationController.cancel(context, record.notificationId)
            val client = FunCardHostClient()
            val capabilities = client.connect(context)
            if (!capabilities.compatible) {
                return@withContext failAndPersist(
                    context,
                    record,
                    commandId,
                    capabilities.error ?: "Hook 未连接",
                    desiredEnabled = false,
                )
            }
            val deactivated = client.deactivateCard(request(record, commandId))
            if (!deactivated.success) {
                client.disconnect()
                return@withContext failAndPersist(
                    context,
                    record,
                    commandId,
                    deactivated.message,
                    desiredEnabled = false,
                )
            }
            client.disconnect()
            // Host removal is asynchronous. A successful native dispatch is the command
            // acknowledgement; final disappearance is reconciled by runtime callbacks.
            val verified = true
            val nextState = RearCardState.INSTALLED_DISABLED
            val message = "隐藏请求已提交到背屏"
            val next = record.copy(
                state = nextState.value,
                desiredEnabled = false,
                lastCommandId = commandId,
                lastMessage = message,
                updatedAt = System.currentTimeMillis(),
            )
            update(context, next)
            log("hide", next, verified, message)
            CardOperationResult(verified, message, nextState, next)
        }

    suspend fun uninstallCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            if (RearCardWorkflow.shouldHide(record, notificationActive = false)) {
                return@withContext CardOperationResult(false, "请先关闭显示到背屏", record.stateEnum, record)
            }
            val canceledIntent = if (record.pendingInstall) {
                record.copy(
                    pendingInstall = false,
                    updatedAt = System.currentTimeMillis(),
                ).also { update(context, it) }
            } else {
                record
            }
            val commandId = commandId("uninstall", record.cardId)
            val result = withHost(context) { it.uninstallCard(request(canceledIntent, commandId)) }
            val nextState = when {
                !result.success -> RearCardState.ERROR
                result.cleanupPending -> RearCardState.INSTALLED_DISABLED
                else -> RearCardState.NOT_INSTALLED
            }
            val next = canceledIntent.copy(
                state = nextState.value,
                pendingInstall = false,
                hostTemplatePath = if (result.success && !result.cleanupPending) null else canceledIntent.hostTemplatePath,
                cleanupPending = result.cleanupPending,
                lastCommandId = commandId,
                lastMessage = result.message,
                updatedAt = System.currentTimeMillis(),
            )
            update(context, next)
            log("uninstall", next, result.success, result.message)
            CardOperationResult(result.success, result.message, nextState, next)
        }

    suspend fun deleteCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            // Persist the user's delete intent before notification, runtime, Host,
            // local-file, or registry mutations. Every later step is replayable.
            val tombstone = RearCardWorkflow.deletionTombstone(
                record,
                "卡片已从列表移除，正在清理关联资源",
            )
            update(context, tombstone)
            FunCardNotificationController.cancel(context, tombstone.notificationId)
            if (!tombstone.cleanupPending) {
                return@withContext finishLocalDeletion(context, tombstone)
            }
            val commandId = commandId("delete", tombstone.cardId)
            val result = withHost(context) { client ->
                client.uninstallCard(request(tombstone, commandId))
            }
            applyHostDeletionResult(context, tombstone, commandId, result)
        }

    suspend fun deleteAllCards(context: Context): CardOperationResult = withContext(Dispatchers.IO) {
        val records = loadAll(context)
        if (records.isEmpty()) {
            return@withContext CardOperationResult(true, "没有需要删除的卡片", RearCardState.NOT_INSTALLED)
        }
        val failures = mutableListOf<String>()
        records.forEach { record ->
            runCatching { deleteCard(context, record) }
                .onSuccess { result ->
                    if (!result.success) failures += "${record.displayName}：${result.message}"
                }
                .onFailure { error ->
                    failures += "${record.displayName}：${error.message ?: "删除失败"}"
                }
        }
        val remaining = loadAll(context).filterNot { it.deleted }
        when {
            remaining.isNotEmpty() || failures.isNotEmpty() -> CardOperationResult(
                false,
                failures.firstOrNull() ?: "仍有 ${remaining.size} 张卡片未能删除",
                RearCardState.ERROR,
            )
            loadAll(context).any { it.deleted } -> CardOperationResult(
                true,
                "卡片已从列表移除，后台将继续清理关联资源",
                RearCardState.ERROR,
            )
            else -> CardOperationResult(
                true,
                "已永久删除 ${records.size} 张卡片",
                RearCardState.NOT_INSTALLED,
            )
        }
    }

    suspend fun savePayload(
        context: Context,
        record: CustomCardRecord,
        advanced: Boolean,
        mamlConfig: String,
        rearParam: String,
        focusParam: String,
    ): CardOperationResult = withContext(Dispatchers.IO) {
        val normalizedMamlConfig = mamlConfig.ifBlank { "{}" }
        val normalizedRearParam = rearParam.ifBlank { "{}" }
        val normalizedFocusParam = focusParam.ifBlank { "{}" }
        val next = record.copy(
            advancedPayload = advanced,
            mamlConfigJson = if (advanced) normalizeInactiveMamlConfig(normalizedMamlConfig) else normalizedMamlConfig,
            advancedRearParamJson = normalizedRearParam.takeIf { advanced },
            advancedFocusParamJson = normalizedFocusParam.takeIf { advanced },
            updatedAt = System.currentTimeMillis(),
            lastMessage = "Payload 已保存",
        )
        runCatching { validateRuntimePayload(next) }.getOrElse {
            return@withContext CardOperationResult(false, "Payload 无效：${it.message}", record.stateEnum, record)
        }
        update(context, next)
        CardOperationResult(true, "Payload 已保存", next.stateEnum, next)
    }

    suspend fun capabilities(context: Context): HostCapabilities = withContext(Dispatchers.IO) {
        val client = FunCardHostClient()
        try { client.connect(context) } finally { client.disconnect() }
    }

    suspend fun diagnostics(context: Context, record: CustomCardRecord): HostCardDiagnostics =
        withContext(Dispatchers.IO) {
            val client = FunCardHostClient()
            val caps = client.connect(context)
            if (!caps.compatible) {
                client.disconnect()
                return@withContext HostCardDiagnostics(record.cardId, record.business, lastError = caps.error)
            }
            try { client.diagnostics(record.cardId, record.business, record.notificationId) }
            finally { client.disconnect() }
        }

    suspend fun listSystemTemplates(context: Context): List<SystemTemplateInfo> = withContext(Dispatchers.IO) {
        val client = FunCardHostClient()
        val caps = client.connect(context)
        if (!caps.compatible) {
            client.disconnect()
            return@withContext emptyList()
        }
        try { client.listSystemTemplates() } finally { client.disconnect() }
    }

    private fun reconcile(
        context: Context,
        records: List<CustomCardRecord>,
        hostSession: RefreshHostSession,
    ): List<CustomCardRecord> {
        if (records.none { !it.deleted }) return records
        val client = hostSession.client
        val caps = hostSession.connect().getOrElse { return records }
        if (!caps.compatible || !caps.managerCaptured) {
            return records
        }
        val next = records.map { record ->
            if (record.deleted || record.pendingInstall) return@map record
            val legacyNotificationActive = FunCardNotificationController.isActive(context, record.notificationId)
            if (legacyNotificationActive) {
                if (record.desiredEnabled) {
                    val migrated = client.activateCard(
                        request(record, commandId("migrate", record.cardId), includePayload = true),
                    )
                    if (migrated.success) FunCardNotificationController.cancel(context, record.notificationId)
                } else {
                    FunCardNotificationController.cancel(context, record.notificationId)
                }
            }
            val diagnostics = client.diagnostics(record.cardId, record.business, record.notificationId)
            val state = when {
                !diagnostics.hostRegistryContains -> RearCardState.NOT_INSTALLED
                !record.desiredEnabled && diagnostics.managerListContains -> RearCardState.ERROR
                diagnostics.managerListContains &&
                    diagnostics.runtimeActivated && diagnostics.templateReadable -> RearCardState.INSTALLED_ENABLED
                !diagnostics.managerListContains -> RearCardState.INSTALLED_DISABLED
                else -> RearCardState.ERROR
            }
            record.copy(
                state = state.value,
                // Host observations describe actual state; they must never overwrite
                // the user's desired on/off state during a transient removal race.
                desiredEnabled = record.desiredEnabled,
                hostTemplatePath = diagnostics.actualTemplatePath ?: record.hostTemplatePath,
                lastMessage = if (state == RearCardState.ERROR) {
                    diagnostics.lastError ?: "宿主 runtime 状态不一致"
                } else {
                    record.lastMessage
                },
            )
        }
        if (next != records) saveAll(context, next)
        return next
    }

    private suspend fun awaitDiagnostics(
        client: FunCardHostClient,
        record: CustomCardRecord,
        visible: Boolean,
    ): HostCardDiagnostics {
        var latest = HostCardDiagnostics(record.cardId, record.business)
        repeat(25) {
            latest = client.diagnostics(record.cardId, record.business, record.notificationId)
            val reached = if (visible) {
                latest.managerListContains && latest.runtimeActivated && latest.templateReadable
            } else {
                !latest.managerListContains && !latest.liveWidgetContains
            }
            if (reached) return latest
            delay(200)
        }
        return latest
    }

    private fun processPendingCleanup(
        context: Context,
        hostSession: RefreshHostSession,
    ) {
        // Older builds could die after persisting cleanupPending but before
        // marking the record deleted. That state was only produced by deleteCard.
        loadAll(context)
            .filter { it.cleanupPending && !it.deleted }
            .forEach { record ->
                runCatching {
                    update(
                        context,
                        RearCardWorkflow.cleanupTombstone(
                            record,
                            "卡片已从列表移除，继续清理关联资源",
                        ),
                    )
                }.onFailure { error ->
                    Log.w(Tag, "无法恢复旧版删除 tombstone：${record.cardId}", error)
                }
            }

        loadAll(context)
            .filter { it.deleted && !it.cleanupPending }
            .forEach { record ->
                runCatching {
                    FunCardNotificationController.cancel(context, record.notificationId)
                    finishLocalDeletion(context, record)
                }.onFailure { error ->
                    Log.w(Tag, "本地删除清理将在下次启动重试：${record.cardId}", error)
                }
            }

        val pendingHostCleanup = loadAll(context).filter { it.deleted && it.cleanupPending }
        if (pendingHostCleanup.isEmpty()) return
        val client = hostSession.client
        val caps = hostSession.connect().getOrNull()
        if (caps?.compatible != true) return
        pendingHostCleanup.forEach { candidate ->
            runCatching {
                val record = loadAll(context).firstOrNull { it.cardId == candidate.cardId }
                    ?: return@runCatching
                if (!record.deleted || !record.cleanupPending) return@runCatching
                FunCardNotificationController.cancel(context, record.notificationId)
                val commandId = commandId("cleanup", record.cardId)
                val result = client.uninstallCard(request(record, commandId))
                applyHostDeletionResult(context, record, commandId, result)
            }.onFailure { error ->
                Log.w(Tag, "Host 删除清理将在下次启动重试：${candidate.cardId}", error)
            }
        }
    }

    private fun applyHostDeletionResult(
        context: Context,
        record: CustomCardRecord,
        commandId: String,
        result: HostActionResult,
    ): CardOperationResult {
        val message = when {
            !result.success -> "卡片已从列表移除；等待 Hook 恢复后继续清理：${result.message}"
            result.cleanupPending -> "卡片已从列表移除，宿主正在安全清理 Runtime"
            else -> result.message
        }
        val next = RearCardWorkflow.hostCleanupResult(
            record = record,
            success = result.success,
            cleanupStillPending = result.cleanupPending,
            message = message,
            commandId = commandId,
        )
        update(context, next)
        log("delete-host", next, result.success, message)
        return if (result.success && !result.cleanupPending) {
            finishLocalDeletion(context, next)
        } else {
            CardOperationResult(true, message, next.stateEnum, next)
        }
    }

    private fun finishLocalDeletion(
        context: Context,
        record: CustomCardRecord,
    ): CardOperationResult = try {
        deleteManagedCardFiles(context, record.cardId)
        saveAll(context, loadAll(context).filterNot { it.cardId == record.cardId })
        log("delete-local", record, true, "卡片已永久删除")
        CardOperationResult(true, "卡片已永久删除", RearCardState.NOT_INSTALLED)
    } catch (error: Throwable) {
        val message = "卡片已从列表移除；本地清理将在下次启动重试：${error.message ?: "清理失败"}"
        val pending = RearCardWorkflow.localCleanupFailed(record, message)
        runCatching { update(context, pending) }.onFailure(error::addSuppressed)
        log("delete-local", pending, false, message)
        CardOperationResult(true, message, RearCardState.ERROR, pending)
    }

    private fun withHost(context: Context, block: (FunCardHostClient) -> HostActionResult): HostActionResult {
        val client = FunCardHostClient()
        val caps = runCatching { client.connect(context) }.getOrElse {
            return HostActionResult(false, it.message ?: "Hook 连接失败", "CONNECT_ERROR")
        }
        if (!caps.compatible) {
            client.disconnect()
            return HostActionResult(false, caps.error ?: "Host API 不兼容", "INCOMPATIBLE_API")
        }
        return try { block(client) } finally { client.disconnect() }
    }

    private fun failAndPersist(
        context: Context,
        record: CustomCardRecord,
        commandId: String,
        message: String,
        desiredEnabled: Boolean = record.desiredEnabled,
    ): CardOperationResult {
        val next = record.copy(
            state = RearCardState.ERROR.value,
            desiredEnabled = desiredEnabled,
            lastCommandId = commandId,
            lastMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
        update(context, next)
        return CardOperationResult(false, message, RearCardState.ERROR, next)
    }

    private fun request(
        record: CustomCardRecord,
        commandId: String,
        includePayload: Boolean = false,
    ): Bundle = Bundle().apply {
        putString(FunCardHostContract.Keys.CARD_ID, record.cardId)
        putString(FunCardHostContract.Keys.BUSINESS, record.business)
        putString(FunCardHostContract.Keys.DISPLAY_NAME, record.displayName)
        putString(FunCardHostContract.Keys.TEMPLATE_SHA256, record.sha256)
        putInt(FunCardHostContract.Keys.NOTIFICATION_ID, record.notificationId)
        putString(FunCardHostContract.Keys.COMMAND_ID, commandId)
        if (includePayload) {
            val payload = FunCardNotificationController.buildRuntimePayload(record)
            putString(FunCardHostContract.Keys.REAR_PARAM, payload.rearParam)
            putString(FunCardHostContract.Keys.FOCUS_PARAM, payload.focusParam)
        }
    }

    private fun loadAll(context: Context): List<CustomCardRecord> = synchronized(registryLock) {
        val file = registryFile(context)
        if (!file.isFile) return@synchronized emptyList()
        FunCardRegistryCodec.decodeStrict(readRegistryText(file)).onEach { record ->
            validateManagedRecord(context, record)
            validateRuntimePayload(record)
        }
    }

    private fun update(context: Context, record: CustomCardRecord) = synchronized(registryLock) {
        saveAll(context, loadAll(context).filterNot { it.cardId == record.cardId } + record)
    }

    private fun saveAll(context: Context, records: List<CustomCardRecord>) = synchronized(registryLock) {
        records.forEach { record ->
            validateManagedRecord(context, record)
            validateRuntimePayload(record)
        }
        val bytes = FunCardRegistryCodec.encode(records).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxRegistryBytes) { "卡片 registry 超过 2 MB，已拒绝写入" }
        writeAtomically(registryFile(context), bytes)
    }

    private fun registryFile(context: Context): File {
        val filesDir = context.filesDir.absoluteFile
        val file = File(filesDir, RegistryName).absoluteFile
        require(file.parentFile == filesDir) { "卡片 registry 路径无效" }
        require(!Files.isSymbolicLink(file.toPath())) { "卡片 registry 不允许使用符号链接" }
        require(file.canonicalFile.parentFile == filesDir.canonicalFile) { "卡片 registry 越出应用目录" }
        return file
    }

    private fun readRegistryText(file: File): String {
        require(file.length() in 1..MaxRegistryBytes) { "卡片 registry 文件大小无效" }
        val output = ByteArrayOutputStream(file.length().toInt())
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= MaxRegistryBytes) { "卡片 registry 超过 2 MB，已拒绝读取" }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun cleanupStaleImports(context: Context) = synchronized(stagingLock) {
        val cutoff = System.currentTimeMillis() - StagingMaxAgeMs
        val stagingDir = managedStagingDir(context, create = false)
        if (!stagingDir.isDirectory) return@synchronized
        stagingDir.listFiles().orEmpty().filter(::isManagedStagingEntry)
            .filter {
                it.absolutePath !in stagingReservations &&
                    !Files.isSymbolicLink(it.toPath()) &&
                    it.isFile &&
                    it.lastModified() < cutoff
            }
            .forEach { stale -> check(stale.delete() || !stale.exists()) { "无法清理过期导入缓存" } }
    }

    private fun reserveStagingFile(context: Context): File = synchronized(stagingLock) {
        val stagingDir = managedStagingDir(context, create = true)
        cleanupStaleImports(context)
        reserveStagingCapacity(stagingDir)
        File.createTempFile("pending_", ".zip", stagingDir).also { staged ->
            check(stagingReservations.add(staged.absolutePath)) { "导入缓存 reservation 冲突" }
        }
    }

    private fun reserveStagingCapacity(stagingDir: File) {
        stagingDir.listFiles().orEmpty()
            .filter(::isManagedStagingEntry)
            .filter { it.absolutePath !in stagingReservations }
            .filter { Files.isSymbolicLink(it.toPath()) }
            .forEach { link -> Files.deleteIfExists(link.toPath()) }
        val files = stagingDir.listFiles().orEmpty()
            .filter(::isManagedStagingEntry)
            .filter { it.absolutePath !in stagingReservations }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .sortedBy(File::lastModified)
            .toMutableList()
        var totalBytes = files.sumOf(File::length)
        val reservedSlots = stagingReservations.size + 1
        while (
            files.size + reservedSlots > MaxStagingFiles ||
            totalBytes + reservedSlots * SmartAssistantTemplateValidator.MaxCompressedBytes > MaxStagingBytes
        ) {
            val oldest = files.removeFirstOrNull() ?: break
            val bytes = oldest.length()
            check(oldest.delete() || !oldest.exists()) { "无法释放旧的导入缓存" }
            totalBytes = (totalBytes - bytes).coerceAtLeast(0L)
        }
        require(files.size + reservedSlots <= MaxStagingFiles) { "待确认导入文件过多" }
        require(
            totalBytes + reservedSlots * SmartAssistantTemplateValidator.MaxCompressedBytes <= MaxStagingBytes,
        ) {
            "导入缓存总量超过 64 MB"
        }
    }

    private fun isManagedStagingEntry(file: File): Boolean =
        file.name.startsWith("pending_") && file.name.endsWith(".zip")

    private fun discardStagingFile(context: Context, file: File) = synchronized(stagingLock) {
        val stagingDir = managedStagingDir(context, create = false)
        val target = file.absoluteFile
        require(target.parentFile == stagingDir && isManagedStagingEntry(target)) {
            "拒绝删除非受管导入缓存"
        }
        stagingReservations.remove(target.absolutePath)
        if (Files.isSymbolicLink(target.toPath())) {
            Files.deleteIfExists(target.toPath())
        } else {
            require(target.canonicalFile.parentFile == stagingDir.canonicalFile) { "导入缓存越出受管目录" }
            check(target.delete() || !target.exists()) { "无法删除导入缓存" }
        }
    }

    private fun safeDisplayText(value: String?, maxCodePoints: Int): String? {
        val normalized = value.orEmpty()
            .filterNot { character ->
                character.isISOControl() || character in BidiControlCharacters
            }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return null
        val end = normalized.offsetByCodePoints(0, normalized.codePointCount(0, normalized.length).coerceAtMost(maxCodePoints))
        return normalized.substring(0, end)
    }

    private fun validatePayloadJson(value: Any?, depth: Int = 0, budget: IntArray = intArrayOf(0)) {
        require(depth <= 32) { "JSON 嵌套超过 32 层" }
        budget[0]++
        require(budget[0] <= 4096) { "JSON 节点过多" }
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                require(key.length <= 256) { "JSON 键过长" }
                validatePayloadJson(value.get(key), depth + 1, budget)
            }
            is JSONArray -> for (index in 0 until value.length()) {
                validatePayloadJson(value.get(index), depth + 1, budget)
            }
            is String -> require(value.length <= 16_384) { "JSON 字符串过长" }
        }
    }

    private fun validateRuntimePayload(record: CustomCardRecord) {
        val payload = FunCardNotificationController.buildRuntimePayload(record)
        val totalBytes = payload.rearParam.toByteArray(Charsets.UTF_8).size +
            payload.focusParam.toByteArray(Charsets.UTF_8).size
        require(totalBytes <= MaxPayloadBytes) { "最终 Payload 超过 128 KB" }
        validatePayloadJson(JSONObject(payload.rearParam))
        validatePayloadJson(JSONObject(payload.focusParam))
    }

    private fun normalizeInactiveMamlConfig(value: String): String = runCatching {
        require(value.toByteArray(Charsets.UTF_8).size <= MaxPayloadBytes) { "MAML Payload 超过 128 KB" }
        val parsed = JSONObject(value)
        validatePayloadJson(parsed)
        value
    }.getOrDefault("{}")

    private val BidiControlCharacters = setOf(
        '\u061c', '\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )

    private fun cardsRoot(context: Context): File {
        val filesDir = context.filesDir.absoluteFile
        val cardsRoot = File(filesDir, CardsDirName).absoluteFile
        require(cardsRoot.parentFile == filesDir) { "卡片根目录路径无效" }
        require(!Files.isSymbolicLink(cardsRoot.toPath())) { "卡片根目录不允许使用符号链接" }
        require(cardsRoot.canonicalFile.parentFile == filesDir.canonicalFile) { "卡片根目录越出应用目录" }
        return cardsRoot
    }

    private fun cardDir(context: Context, cardId: String): File {
        require(cardId.matches(Regex("[a-f0-9]{32}"))) { "cardId 不安全" }
        val cardsRoot = cardsRoot(context)
        val directory = File(cardsRoot, cardId).absoluteFile
        require(directory.parentFile == cardsRoot) { "卡片目录路径无效" }
        require(!Files.isSymbolicLink(directory.toPath())) { "卡片目录不允许使用符号链接" }
        require(directory.canonicalFile.parentFile == cardsRoot.canonicalFile) { "卡片目录越出受管根目录" }
        return directory
    }

    private fun managedLocalFile(context: Context, cardId: String): File {
        val directory = cardDir(context, cardId)
        val file = File(directory, "source.zip").absoluteFile
        require(file.parentFile == directory) { "本地模板路径无效" }
        require(!Files.isSymbolicLink(file.toPath())) { "本地模板不允许使用符号链接" }
        require(
            file.canonicalFile.parentFile == directory.canonicalFile &&
                file.canonicalFile.name == "source.zip",
        ) { "本地模板越出受管卡片目录" }
        return file
    }

    private fun replacementJournalFile(context: Context, cardId: String): File =
        managedCardSidecar(context, cardId, ReplacementJournalName)

    private fun replacementBackupFile(context: Context, cardId: String): File =
        managedCardSidecar(context, cardId, ReplacementBackupName)

    private fun managedCardSidecar(context: Context, cardId: String, name: String): File {
        val directory = cardDir(context, cardId)
        val file = File(directory, name).absoluteFile
        require(file.parentFile == directory && file.name == name) { "卡片事务文件路径无效" }
        require(!Files.isSymbolicLink(file.toPath())) { "卡片事务文件不允许使用符号链接" }
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "卡片事务文件越出受管目录" }
        return file
    }

    private fun validateManagedRecord(context: Context, record: CustomCardRecord) {
        val expected = managedLocalFile(context, record.cardId)
        require(record.localFile.absoluteFile == expected) { "卡片 registry 的本地模板路径不受信任" }
    }

    private fun managedStagingDir(context: Context, create: Boolean): File {
        val cacheDir = context.cacheDir.absoluteFile
        val directory = File(cacheDir, StagingDirName).absoluteFile
        require(directory.parentFile == cacheDir) { "导入缓存路径无效" }
        require(!Files.isSymbolicLink(directory.toPath())) { "导入缓存目录不允许使用符号链接" }
        if (create) check(directory.isDirectory || directory.mkdirs()) { "无法创建导入缓存目录" }
        require(directory.canonicalFile.parentFile == cacheDir.canonicalFile) { "导入缓存越出应用目录" }
        return directory
    }

    private fun validatePendingImport(context: Context, pending: PendingCardImport): TemplateInspection {
        val stagingDir = managedStagingDir(context, create = false)
        val staged = pending.stagedFile.absoluteFile
        require(staged.parentFile == stagingDir) { "导入缓存文件不受信任" }
        require(staged.name.startsWith("pending_") && staged.name.endsWith(".zip")) {
            "导入缓存文件名无效"
        }
        require(!Files.isSymbolicLink(staged.toPath())) { "导入缓存文件不允许使用符号链接" }
        require(staged.canonicalFile.parentFile == stagingDir.canonicalFile) { "导入缓存文件越出受管目录" }
        val inspection = SmartAssistantTemplateValidator.inspect(staged)
        require(inspection.sha256 == pending.inspection.sha256) { "导入缓存已被修改，请重新选择 ZIP" }
        return inspection
    }

    private fun recoverTemplateReplacements(context: Context) = synchronized(registryLock) {
        val root = cardsRoot(context)
        if (!root.isDirectory) return@synchronized
        val recordsById = registryFile(context).takeIf(File::isFile)?.let { file ->
            FunCardRegistryCodec.decodeStrict(readRegistryText(file)).associateBy { it.cardId }
        }.orEmpty()
        root.listFiles().orEmpty().forEach { directory ->
            if (!directory.name.matches(Regex("[a-f0-9]{32}"))) return@forEach
            require(directory.absoluteFile.parentFile == root && directory.isDirectory) {
                "卡片替换事务目录无效"
            }
            require(!Files.isSymbolicLink(directory.toPath())) { "卡片替换事务目录不允许使用符号链接" }
            val journalFile = replacementJournalFile(context, directory.name)
            if (!journalFile.exists()) return@forEach
            require(journalFile.isFile && journalFile.length() in 1..MaxReplacementJournalBytes) {
                "卡片替换事务文件大小无效"
            }
            val journal = TemplateReplacementJournalCodec.decode(readRegistryText(journalFile))
            require(journal.cardId == directory.name) { "卡片替换事务与目录不匹配" }
            val registryRecord = recordsById[journal.cardId]
                ?: error("卡片替换事务找不到 registry 记录")
            // A durable delete tombstone owns this directory now. Recovering a
            // partially unlinked replacement would fight deletion replay.
            if (registryRecord.deleted) return@forEach
            recoverTemplateReplacement(context, journal, registryRecord.sha256)
        }
    }

    private fun recoverTemplateReplacement(
        context: Context,
        journal: TemplateReplacementJournal,
        registrySha256: String,
    ) {
        val target = managedLocalFile(context, journal.cardId)
        val backup = replacementBackupFile(context, journal.cardId)
        val targetSha256 = inspectTemplateShaOrNull(target)
        val backupSha256 = inspectTemplateShaOrNull(backup)
        when (
            TemplateReplacementJournalCodec.recovery(
                journal,
                registrySha256,
                targetSha256,
                backupSha256,
            )
        ) {
            TemplateReplacementRecovery.COMMIT,
            TemplateReplacementRecovery.KEEP_ORIGINAL
            -> Unit
            TemplateReplacementRecovery.RESTORE_BACKUP -> {
                writeTemplateAtomically(backup, target, journal.oldSha256)
            }
            TemplateReplacementRecovery.DELETE_REPLACEMENT -> {
                check(!target.exists() || target.delete()) { "无法回滚新建的模板文件" }
            }
        }
        finishTemplateReplacement(context, journal.cardId)
        Log.i(Tag, "recovered template replacement cardId=${journal.cardId}")
    }

    private fun recoverTemplateReplacementFromRegistry(
        context: Context,
        journal: TemplateReplacementJournal,
    ) {
        val registrySha256 = persistedReplacementSha256(journal, loadAll(context))
        recoverTemplateReplacement(context, journal, registrySha256)
    }

    private fun finishTemplateReplacement(context: Context, cardId: String) {
        val backup = replacementBackupFile(context, cardId)
        val journal = replacementJournalFile(context, cardId)
        check(!backup.exists() || backup.delete()) { "无法清理卡片替换备份" }
        check(!journal.exists() || journal.delete()) { "无法清理卡片替换事务" }
    }

    private fun inspectTemplateShaOrNull(file: File): String? =
        file.takeIf(File::isFile)?.let { candidate ->
            runCatching { SmartAssistantTemplateValidator.inspect(candidate).sha256 }.getOrNull()
        }

    private fun copyBounded(source: File, target: File, maxBytes: Long) {
        require(!Files.isSymbolicLink(source.toPath())) { "源文件不允许使用符号链接" }
        require(!Files.isSymbolicLink(target.toPath())) { "目标文件不允许使用符号链接" }
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        require(total <= maxBytes) { "ZIP 超过 16 MB" }
                        output.write(buffer, 0, read)
                    }
                    require(total > 0) { "ZIP 不能为空" }
                    output.fd.sync()
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun writeTemplateAtomically(
        source: File,
        target: File,
        expectedSha256: String,
    ): TemplateInspection {
        require(target.parentFile?.isDirectory == true) { "卡片目录不存在" }
        require(!Files.isSymbolicLink(target.toPath())) { "本地模板不允许使用符号链接" }
        val temp = File.createTempFile(".outerview_template_", ".tmp", target.parentFile)
        return withNonThrowingCleanup(
            cleanup = { Files.deleteIfExists(temp.toPath()) },
            onCleanupFailure = { error ->
                Log.w(Tag, "原子模板临时文件清理失败：${temp.name}", error)
            },
        ) {
            copyBounded(source, temp, SmartAssistantTemplateValidator.MaxCompressedBytes)
            val inspection = SmartAssistantTemplateValidator.inspect(temp)
            require(inspection.sha256 == expectedSha256) { "复制后的 ZIP 校验失败，请重新选择文件" }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            inspection
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val parent = requireNotNull(file.absoluteFile.parentFile) { "原子写入缺少父目录" }
        check(parent.isDirectory || parent.mkdirs()) { "无法创建原子写入目录" }
        require(!Files.isSymbolicLink(parent.toPath())) { "原子写入目录不允许使用符号链接" }
        require(!Files.isSymbolicLink(file.toPath())) { "原子写入目标不允许使用符号链接" }
        require(file.canonicalFile.parentFile == parent.canonicalFile) { "原子写入目标越出受管目录" }
        val temp = File.createTempFile(".outerview_write_", ".tmp", parent)
        withNonThrowingCleanup(
            cleanup = { Files.deleteIfExists(temp.toPath()) },
            onCleanupFailure = { error ->
                Log.w(Tag, "原子文件临时文件清理失败：${temp.name}", error)
            },
        ) {
            FileOutputStream(temp, false).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun deleteManagedCardFiles(context: Context, cardId: String) {
        val directory = cardDir(context, cardId)
        if (!directory.exists()) return
        require(directory.isDirectory) { "受管卡片路径不是目录" }
        val children = requireNotNull(directory.listFiles()) { "无法读取受管卡片目录" }
        children.forEach { child ->
            require(child.absoluteFile.parentFile == directory) { "卡片目录包含越界文件" }
            if (Files.isSymbolicLink(child.toPath())) {
                Files.deleteIfExists(child.toPath())
            } else {
                require(child.isFile) { "卡片目录包含非文件项，已拒绝递归删除" }
                check(child.delete()) { "无法删除受管卡片文件" }
            }
        }
        check(directory.delete()) { "无法删除受管卡片目录" }
    }

    private fun allocateNotificationId(cardId: String, records: List<CustomCardRecord>): Int {
        val used = records.map { it.notificationId }.toSet()
        var candidate = 620_000 + (cardId.take(8).toLong(16) % 100_000).toInt()
        repeat(100_000) {
            if (candidate !in used) return candidate
            candidate = if (candidate == 719_999) 620_000 else candidate + 1
        }
        error("没有可用的通知 ID")
    }

    private fun commandId(operation: String, key: String) =
        "${operation}_${System.currentTimeMillis()}_${Integer.toHexString(key.hashCode())}"

    private fun log(operation: String, record: CustomCardRecord, success: Boolean, message: String) {
        Log.i(
            Tag,
            "operation=$operation commandId=${record.lastCommandId.orEmpty()} cardId=${record.cardId} " +
                "business=${record.business} notificationId=${record.notificationId} state=${record.state} " +
                "result=$success template=${record.hostTemplatePath.orEmpty()} message=$message",
        )
    }
}

internal fun persistedReplacementSha256(
    journal: TemplateReplacementJournal,
    records: List<CustomCardRecord>,
): String = records
    .firstOrNull { it.cardId == journal.cardId }
    ?.sha256
    ?: error("卡片替换事务找不到 registry 记录")

internal fun <T> withNonThrowingCleanup(
    cleanup: () -> Unit,
    onCleanupFailure: (Throwable) -> Unit = {},
    block: () -> T,
): T = try {
    block()
} finally {
    runCatching(cleanup).onFailure { error ->
        runCatching { onCleanupFailure(error) }
    }
}
