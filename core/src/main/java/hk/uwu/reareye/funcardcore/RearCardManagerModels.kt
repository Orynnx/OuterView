package hk.uwu.reareye.funcardcore

enum class RearCardState(val value: String) {
    NOT_INSTALLED("NOT_INSTALLED"),
    INSTALLED_DISABLED("INSTALLED_DISABLED"),
    INSTALLED_ENABLED("INSTALLED_ENABLED"),
    ERROR("ERROR");

    companion object {
        fun fromValue(value: String?): RearCardState {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { it.value.equals(normalized, ignoreCase = true) }
                ?: NOT_INSTALLED
        }
    }
}

data class RearCardManagerCapabilities(
    val connected: Boolean = false,
    val compatible: Boolean = false,
    val apiVersion: Int = 0,
    val providerPackage: String = "",
    val providerInstanceId: String = "",
    val hostVersion: String = "",
    val hookReady: Boolean = false,
    val managerCaptured: Boolean = false,
    val error: String? = null,
)

data class ManagedRearCard(
    val cardId: String,
    val business: String,
    val displayName: String,
    val author: String? = null,
    val templateVersion: String? = null,
    val sha256: String,
    val state: RearCardState,
    val desiredEnabled: Boolean,
    val cleanupPending: Boolean,
    val advancedPayload: Boolean,
    val mamlConfigJson: String,
    val advancedRearParamJson: String? = null,
    val advancedFocusParamJson: String? = null,
    val hostTemplatePath: String? = null,
    val lastCommandId: String? = null,
    val lastMessage: String? = null,
    val updatedAt: Long = 0L,
) {
    val stateEnum: RearCardState get() = state
}

data class RearCardManagerSnapshot(
    val capabilities: RearCardManagerCapabilities = RearCardManagerCapabilities(),
    val cards: List<ManagedRearCard> = emptyList(),
    val hasLegacyArtifacts: Boolean = false,
    val error: String? = null,
)

data class TemplateCommandFinding(
    val type: String,
    val detail: String,
)

data class CardImportPreview(
    val token: String,
    val suggestedName: String,
    val sha256: String,
    val compressedBytes: Long,
    val expandedBytes: Long,
    val entryCount: Int,
    val author: String? = null,
    val templateVersion: String? = null,
    val findings: List<TemplateCommandFinding> = emptyList(),
)

data class ManagedCardDiagnostics(
    val cardId: String,
    val business: String,
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
)

data class RearCardActionResult(
    val success: Boolean,
    val message: String,
    val state: RearCardState,
    val card: ManagedRearCard? = null,
    val errorCode: String? = null,
)

data class EndpointResult<T>(
    val success: Boolean,
    val value: T? = null,
    val message: String = "",
    val errorCode: String? = null,
)
