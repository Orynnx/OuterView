package hk.uwu.reareye.funcardcore.hostapi

import android.os.Bundle

data class HostCapabilities(
    val connected: Boolean = false,
    val apiVersion: Int = 0,
    val providerPackage: String = "",
    val providerInstanceId: String = "",
    val hostVersion: String = "",
    val hookReady: Boolean = false,
    val managerCaptured: Boolean = false,
    val error: String? = null,
) {
    val compatible: Boolean
        get() = connected && apiVersion == FunCardHostContract.API_VERSION &&
            providerPackage == FunCardHostContract.PROVIDER_PACKAGE
}

data class HostActionResult(
    val success: Boolean,
    val message: String,
    val errorCode: String? = null,
    val templatePath: String? = null,
    val diagnostics: HostCardDiagnostics? = null,
    val cleanupPending: Boolean = false,
) {
    companion object {
        fun fromBundle(bundle: Bundle?): HostActionResult {
            if (bundle == null) return HostActionResult(false, "Hook 返回了空结果", "EMPTY_RESULT")
            return HostActionResult(
                success = bundle.getBoolean(FunCardHostContract.Keys.SUCCESS, false),
                message = bundle.getString(FunCardHostContract.Keys.MESSAGE).orEmpty()
                    .ifBlank { "操作失败" },
                errorCode = bundle.getString(FunCardHostContract.Keys.ERROR_CODE),
                templatePath = bundle.getString(FunCardHostContract.Keys.TEMPLATE_PATH),
                diagnostics = HostCardDiagnostics.fromBundle(bundle),
                cleanupPending = bundle.getBoolean(FunCardHostContract.Keys.CLEANUP_PENDING),
            )
        }
    }
}

data class HostCardDiagnostics(
    val cardId: String = "",
    val business: String = "",
    val hookReady: Boolean = false,
    val managerCaptured: Boolean = false,
    val templateReadable: Boolean = false,
    val hostRegistryContains: Boolean = false,
    val notificationSeen: Boolean = false,
    val runtimeActivated: Boolean = false,
    val managerListContains: Boolean = false,
    val liveWidgetContains: Boolean = false,
    val loadAttempted: Boolean = false,
    val loadSucceeded: Boolean = false,
    val systemPersistenceContains: Boolean = false,
    val actualTemplatePath: String? = null,
    val lastCommandId: String? = null,
    val lastEventAt: Long = 0L,
    val lastError: String? = null,
    val legacyConflicts: List<String> = emptyList(),
) {
    companion object {
        fun fromBundle(bundle: Bundle?): HostCardDiagnostics? {
            bundle ?: return null
            return HostCardDiagnostics(
                cardId = bundle.getString(FunCardHostContract.Keys.CARD_ID).orEmpty(),
                business = bundle.getString(FunCardHostContract.Keys.BUSINESS).orEmpty(),
                hookReady = bundle.getBoolean(FunCardHostContract.Keys.HOOK_READY),
                managerCaptured = bundle.getBoolean(FunCardHostContract.Keys.MANAGER_CAPTURED),
                templateReadable = bundle.getBoolean(FunCardHostContract.Keys.TEMPLATE_READABLE),
                hostRegistryContains = bundle.getBoolean(FunCardHostContract.Keys.HOST_REGISTRY_CONTAINS),
                notificationSeen = bundle.getBoolean(FunCardHostContract.Keys.NOTIFICATION_SEEN),
                runtimeActivated = bundle.getBoolean(FunCardHostContract.Keys.RUNTIME_ACTIVATED),
                managerListContains = bundle.getBoolean(FunCardHostContract.Keys.MANAGER_LIST_CONTAINS),
                liveWidgetContains = bundle.getBoolean(FunCardHostContract.Keys.LIVE_WIDGET_CONTAINS),
                loadAttempted = bundle.getBoolean(FunCardHostContract.Keys.LOAD_ATTEMPTED),
                loadSucceeded = bundle.getBoolean(FunCardHostContract.Keys.LOAD_SUCCEEDED),
                systemPersistenceContains = bundle.getBoolean(FunCardHostContract.Keys.SYSTEM_PERSISTENCE_CONTAINS),
                actualTemplatePath = bundle.getString(FunCardHostContract.Keys.TEMPLATE_PATH),
                lastCommandId = bundle.getString(FunCardHostContract.Keys.LAST_COMMAND_ID),
                lastEventAt = bundle.getLong(FunCardHostContract.Keys.LAST_EVENT_AT),
                lastError = bundle.getString(FunCardHostContract.Keys.LAST_ERROR),
                legacyConflicts = bundle.getStringArrayList(FunCardHostContract.Keys.LEGACY_CONFLICTS).orEmpty(),
            )
        }
    }
}

data class SystemTemplateInfo(
    val business: String,
    val displayName: String,
    val pathName: String,
    val active: Boolean,
    val readable: Boolean,
    val sha256: String = "",
)

data class HostCardInfo(
    val cardId: String,
    val business: String,
    val displayName: String,
    val templatePath: String,
    val sha256: String,
)
