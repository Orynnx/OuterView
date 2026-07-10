package hk.uwu.reareye.funcardcore

import android.content.Context
import android.net.Uri
import hk.uwu.reareye.funcardcore.hostapi.HostCapabilities
import hk.uwu.reareye.funcardcore.hostapi.HostCardDiagnostics
import hk.uwu.reareye.funcardcore.internal.CardOperationResult
import hk.uwu.reareye.funcardcore.internal.CustomCardRecord
import hk.uwu.reareye.funcardcore.internal.FunCardNotificationController
import hk.uwu.reareye.funcardcore.internal.FunCardRepository
import hk.uwu.reareye.funcardcore.internal.PendingCardImport
import hk.uwu.reareye.funcardcore.internal.RearCardWorkflow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RearCardManager private constructor(context: Context) : RearCardManagementEndpoints {
    private val appContext = context.applicationContext
    private val pendingImports = ConcurrentHashMap<String, PendingCardImport>()

    companion object {
        const val API_VERSION = 3
        private const val MigrationPrefs = "rear_card_core_migrations"
        private const val LegacyProbeCleanupKey = "legacy_system_probes_cleaned_v2"

        @JvmStatic
        fun create(context: Context): RearCardManager = RearCardManager(context)
    }

    override suspend fun refresh(): RearCardManagerSnapshot = runCatching {
        cleanupLegacySystemProbesOnce()
        val cards = FunCardRepository.loadCards(appContext).map(CustomCardRecord::toPublic)
        val capabilities = FunCardRepository.capabilities(appContext).toPublic()
        RearCardManagerSnapshot(
            capabilities = capabilities,
            cards = cards,
            hasLegacyArtifacts = FunCardRepository.hasLegacyRegistry(appContext),
        )
    }.getOrElse {
        RearCardManagerSnapshot(
            error = it.message ?: "刷新卡片状态失败",
        )
    }

    override suspend fun inspectImport(
        uri: Uri,
        displayNameHint: String?,
    ): EndpointResult<CardImportPreview> {
        return FunCardRepository.inspectImport(appContext, uri, displayNameHint).fold(
            onSuccess = { pending ->
                val token = UUID.randomUUID().toString()
                pendingImports[token] = pending
                EndpointResult(true, pending.toPublic(token), "模板校验通过")
            },
            onFailure = { EndpointResult(false, message = it.message ?: "模板校验失败", errorCode = "IMPORT_INVALID") },
        )
    }

    override fun discardImport(token: String) {
        pendingImports.remove(token)?.stagedFile?.delete()
    }

    override suspend fun importAndInstall(token: String): RearCardActionResult {
        val pending = pendingImports.remove(token)
            ?: return failure("导入会话已失效，请重新选择 ZIP", "IMPORT_TOKEN_EXPIRED")
        val hashes = FunCardRepository.listSystemTemplates(appContext)
            .mapNotNull { it.sha256.takeIf(String::isNotBlank) }
            .toSet()
        return runCatching {
            RearCardWorkflow.importAndInstall(
                commit = { FunCardRepository.commitImport(appContext, pending, hashes) },
                install = { FunCardRepository.installCard(appContext, it) },
            ).toPublic()
        }.getOrElse { failure(it.message ?: "导入安装失败", "IMPORT_INSTALL_FAILED") }
    }

    override suspend fun replaceAndInstall(cardId: String, token: String): RearCardActionResult {
        val pending = pendingImports.remove(token)
            ?: return failure("导入会话已失效，请重新选择 ZIP", "IMPORT_TOKEN_EXPIRED")
        return runCatching {
            val systemHashes = FunCardRepository.listSystemTemplates(appContext)
                .mapNotNull { it.sha256.takeIf(String::isNotBlank) }
                .toSet()
            if (pending.inspection.sha256 in systemHashes) {
                pending.stagedFile.delete()
                return@runCatching failure("系统已经提供相同模板，不重复添加", "SYSTEM_TEMPLATE_DUPLICATE")
            }
            RearCardWorkflow.replaceAndInstall(
                initial = requireCard(cardId),
                hide = { FunCardRepository.hideCard(appContext, it) },
                replace = { FunCardRepository.replaceTemplate(appContext, it, pending) },
                install = { FunCardRepository.installCard(appContext, it) },
            ).toPublic()
        }.getOrElse { failure(it.message ?: "替换安装失败", "REPLACE_INSTALL_FAILED") }
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
        runCatching { operation().toPublic() }.getOrElse {
            failure(it.message ?: "卡片操作失败", "CORE_OPERATION_FAILED")
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
