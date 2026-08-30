package org.orynnx.outerview.core

import android.content.Context
import android.net.Uri
import org.orynnx.outerview.core.hostapi.HostCapabilities
import org.orynnx.outerview.core.hostapi.HostCardDiagnostics
import org.orynnx.outerview.core.internal.CardOperationResult
import org.orynnx.outerview.core.internal.CustomCardRecord
import org.orynnx.outerview.core.internal.FunCardNotificationController
import org.orynnx.outerview.core.internal.FunCardRepository
import org.orynnx.outerview.core.internal.PendingCardImport
import org.orynnx.outerview.core.internal.RearCardWorkflow
import org.orynnx.outerview.core.internal.SmartAssistantTemplateValidator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RearCardManager private constructor(context: Context) : RearCardManagementEndpoints {
    private val appContext = context.applicationContext
    private val pendingImports = ConcurrentHashMap<String, PendingCardImport>()

    companion object {
        const val API_VERSION = 5
        private const val MigrationPrefs = "rear_card_core_migrations"
        private const val LegacyProbeCleanupKey = "legacy_system_probes_cleaned_v2"
        private const val MaxPendingImports = 4
        private const val MaxPendingImportBytes = 64L * 1024L * 1024L
        private const val PendingImportSessionMs = 30L * 60L * 1000L
        private val operationMutex = Mutex()

        @JvmStatic
        fun create(context: Context): RearCardManager = RearCardManager(context)
    }

    override suspend fun refresh(): RearCardManagerSnapshot = operationMutex.withLock {
        runCatching {
            cleanupLegacySystemProbesOnce()
            val repositoryRefresh = FunCardRepository.refresh(appContext)
            RearCardManagerSnapshot(
                capabilities = repositoryRefresh.capabilities.toPublic(),
                cards = repositoryRefresh.cards.map(CustomCardRecord::toPublic),
                hasLegacyArtifacts = FunCardRepository.hasLegacyRegistry(appContext),
            )
        }.getOrElse {
            RearCardManagerSnapshot(
                error = it.message ?: "刷新卡片状态失败",
            )
        }
    }

    override suspend fun inspectImport(
        uri: Uri,
        displayNameHint: String?,
    ): EndpointResult<CardImportPreview> {
        return FunCardRepository.inspectImport(appContext, uri, displayNameHint).fold(
            onSuccess = { pending ->
                runCatching {
                    val token = storePendingImport(pending)
                    EndpointResult(true, pending.toPublic(token), "模板校验通过")
                }.getOrElse { error ->
                    runCatching { discardPendingFile(pending) }
                    EndpointResult(false, message = error.message ?: "无法保存导入会话", errorCode = "IMPORT_SESSION_FAILED")
                }
            },
            onFailure = { EndpointResult(false, message = it.message ?: "模板校验失败", errorCode = "IMPORT_INVALID") },
        )
    }

    override fun discardImport(token: String) {
        synchronized(pendingImports) { pendingImports.remove(token) }
            ?.let { pending -> runCatching { discardPendingFile(pending) } }
    }

    override suspend fun importAndInstall(token: String): RearCardActionResult = operationMutex.withLock {
        val pending = takePendingImport(token)
            ?: return@withLock failure("导入会话已失效，请重新选择 ZIP", "IMPORT_TOKEN_EXPIRED")
        val hashes = FunCardRepository.listSystemTemplates(appContext)
            .mapNotNull { it.sha256.takeIf(String::isNotBlank) }
            .toSet()
        try {
            runCatching {
                RearCardWorkflow.importAndInstall(
                    commit = { FunCardRepository.commitImport(appContext, pending, hashes) },
                    install = { FunCardRepository.installCard(appContext, it) },
                ).toPublic()
            }.getOrElse { failure(it.message ?: "导入安装失败", "IMPORT_INSTALL_FAILED") }
        } finally {
            runCatching { discardPendingFile(pending) }
        }
    }

    override suspend fun replaceAndInstall(cardId: String, token: String): RearCardActionResult = operationMutex.withLock {
        val pending = takePendingImport(token)
            ?: return@withLock failure("导入会话已失效，请重新选择 ZIP", "IMPORT_TOKEN_EXPIRED")
        try {
            runCatching {
                val systemHashes = FunCardRepository.listSystemTemplates(appContext)
                    .mapNotNull { it.sha256.takeIf(String::isNotBlank) }
                    .toSet()
                if (pending.inspection.sha256 in systemHashes) {
                    return@runCatching failure("系统已经提供相同模板，不重复添加", "SYSTEM_TEMPLATE_DUPLICATE")
                }
                RearCardWorkflow.replaceAndInstall(
                    initial = requireCard(cardId),
                    hide = { FunCardRepository.hideCard(appContext, it) },
                    replace = { FunCardRepository.replaceTemplate(appContext, it, pending) },
                    install = { FunCardRepository.installCard(appContext, it) },
                ).toPublic()
            }.getOrElse { failure(it.message ?: "替换安装失败", "REPLACE_INSTALL_FAILED") }
        } finally {
            runCatching { discardPendingFile(pending) }
        }
    }

    override suspend fun retryInstall(cardId: String): RearCardActionResult =
        withCard(cardId) { FunCardRepository.installCard(appContext, it) }

    override suspend fun setVisible(cardId: String, visible: Boolean): RearCardActionResult =
        withCard(cardId) {
            if (visible) FunCardRepository.showCard(appContext, it)
            else FunCardRepository.hideCard(appContext, it)
        }

    override suspend fun deleteCard(cardId: String): RearCardActionResult =
        withCard(cardId) { FunCardRepository.deleteCard(appContext, it) }

    override suspend fun deleteAllCards(): RearCardActionResult =
        action { FunCardRepository.deleteAllCards(appContext) }

    override suspend fun updatePayload(
        cardId: String,
        advanced: Boolean,
        mamlConfigJson: String,
        rearParamJson: String,
        focusParamJson: String,
    ): RearCardActionResult = withCard(cardId) {
        FunCardRepository.savePayload(
            appContext,
            it,
            advanced,
            mamlConfigJson,
            rearParamJson,
            focusParamJson,
        )
    }

    override suspend fun diagnostics(cardId: String): EndpointResult<ManagedCardDiagnostics> = runCatching {
        val card = requireCard(cardId)
        EndpointResult(true, FunCardRepository.diagnostics(appContext, card).toPublic(), "诊断已刷新")
    }.getOrElse {
        EndpointResult(false, message = it.message ?: "诊断失败", errorCode = "DIAGNOSTICS_FAILED")
    }

    private fun storePendingImport(pending: PendingCardImport): String = synchronized(pendingImports) {
        val now = System.currentTimeMillis()
        prunePendingImportsLocked(now)
        val stagedBytes = pending.stagedFile.length()
        require(
            pending.stagedFile.isFile &&
                stagedBytes in 1..SmartAssistantTemplateValidator.MaxCompressedBytes,
        ) { "导入缓存文件大小无效" }
        while (
            pendingImports.size >= MaxPendingImports ||
            pendingImports.values.sumOf { it.stagedFile.length() } + stagedBytes > MaxPendingImportBytes
        ) {
            val oldest = pendingImports.entries.minByOrNull { it.value.stagedFile.lastModified() }
                ?: break
            pendingImports.remove(oldest.key)?.let { evicted -> runCatching { discardPendingFile(evicted) } }
        }
        require(pendingImports.size < MaxPendingImports) { "待确认导入过多，请先完成或取消已有导入" }
        require(pendingImports.values.sumOf { it.stagedFile.length() } + stagedBytes <= MaxPendingImportBytes) {
            "导入缓存总量超过 64 MB"
        }
        UUID.randomUUID().toString().also { token -> pendingImports[token] = pending }
    }

    private fun takePendingImport(token: String): PendingCardImport? {
        val pending = synchronized(pendingImports) { pendingImports.remove(token) } ?: return null
        val age = System.currentTimeMillis() - pending.stagedFile.lastModified()
        val valid = pending.stagedFile.isFile &&
            pending.stagedFile.length() in 1..SmartAssistantTemplateValidator.MaxCompressedBytes &&
            age in 0..PendingImportSessionMs
        if (!valid) {
            runCatching { discardPendingFile(pending) }
            return null
        }
        return pending
    }

    private fun prunePendingImportsLocked(now: Long) {
        pendingImports.entries.toList().forEach { (token, pending) ->
            val age = now - pending.stagedFile.lastModified()
            if (
                !pending.stagedFile.isFile ||
                pending.stagedFile.length() !in 1..SmartAssistantTemplateValidator.MaxCompressedBytes ||
                age !in 0..PendingImportSessionMs
            ) {
                pendingImports.remove(token)?.let { stale -> runCatching { discardPendingFile(stale) } }
            }
        }
    }

    private fun discardPendingFile(pending: PendingCardImport) {
        FunCardRepository.discardPendingImport(appContext, pending)
    }

    private fun cleanupLegacySystemProbesOnce() {
        val prefs = appContext.getSharedPreferences(MigrationPrefs, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LegacyProbeCleanupKey, false)) return
        FunCardNotificationController.cancelLegacySystemProbes(appContext)
        prefs.edit().putBoolean(LegacyProbeCleanupKey, true).apply()
    }

    private suspend fun withCard(
        cardId: String,
        operation: suspend (CustomCardRecord) -> CardOperationResult,
    ): RearCardActionResult = action { operation(requireCard(cardId)) }

    private suspend fun requireCard(cardId: String): CustomCardRecord =
        FunCardRepository.loadCards(appContext).firstOrNull { it.cardId == cardId }
            ?: error("卡片不存在或已删除")

    private suspend fun action(operation: suspend () -> CardOperationResult): RearCardActionResult =
        operationMutex.withLock {
            runCatching { operation().toPublic() }.getOrElse {
                failure(it.message ?: "卡片操作失败", "CORE_OPERATION_FAILED")
            }
        }

    private fun failure(message: String, code: String) = RearCardActionResult(
        success = false,
        message = message,
        state = RearCardState.ERROR,
        errorCode = code,
    )
}

private fun CustomCardRecord.toPublic() = ManagedRearCard(
    cardId = cardId,
    business = business,
    displayName = displayName,
    author = author,
    templateVersion = templateVersion,
    sha256 = sha256,
    state = stateEnum,
    desiredEnabled = desiredEnabled,
    cleanupPending = cleanupPending,
    advancedPayload = advancedPayload,
    mamlConfigJson = mamlConfigJson,
    advancedRearParamJson = advancedRearParamJson,
    advancedFocusParamJson = advancedFocusParamJson,
    hostTemplatePath = hostTemplatePath,
    lastCommandId = lastCommandId,
    lastMessage = lastMessage,
    updatedAt = updatedAt,
)

private fun HostCapabilities.toPublic() = RearCardManagerCapabilities(
    connected = connected,
    compatible = compatible,
    apiVersion = apiVersion,
    providerPackage = providerPackage,
    providerInstanceId = providerInstanceId,
    hostVersion = hostVersion,
    hookReady = hookReady,
    managerCaptured = managerCaptured,
    error = error,
)

private fun PendingCardImport.toPublic(token: String) = CardImportPreview(
    token = token,
    suggestedName = suggestedName,
    sha256 = inspection.sha256,
    compressedBytes = inspection.compressedBytes,
    expandedBytes = inspection.expandedBytes,
    entryCount = inspection.entryCount,
    author = inspection.metadata?.author,
    templateVersion = inspection.metadata?.version,
    findings = inspection.securityFindings.map { TemplateCommandFinding(it.type, it.detail) },
)

private fun HostCardDiagnostics.toPublic() = ManagedCardDiagnostics(
    cardId = cardId,
    business = business,
    hookReady = hookReady,
    managerCaptured = managerCaptured,
    templateReadable = templateReadable,
    hostRegistryContains = hostRegistryContains,
    notificationSeen = notificationSeen,
    runtimeActivated = runtimeActivated,
    managerListContains = managerListContains,
    liveWidgetContains = liveWidgetContains,
    loadAttempted = loadAttempted,
    loadSucceeded = loadSucceeded,
    systemPersistenceContains = systemPersistenceContains,
    actualTemplatePath = actualTemplatePath,
    lastCommandId = lastCommandId,
    lastEventAt = lastEventAt,
    lastError = lastError,
    legacyConflicts = legacyConflicts,
)

private fun CardOperationResult.toPublic(
    successMessage: String? = null,
    failurePrefix: String? = null,
) = RearCardActionResult(
    success = success,
    message = when {
        success && successMessage != null -> successMessage
        !success && failurePrefix != null -> failurePrefix + message
        else -> message
    },
    state = state,
    card = record?.toPublic(),
)
