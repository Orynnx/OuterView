package hk.uwu.reareye.funcardcore.internal

import hk.uwu.reareye.funcardcore.RearCardState
import java.io.File

const val TesterPackageName = "org.orynnx.outerview"

data class CustomCardRecord(
    val cardId: String = "",
    val business: String = "",
    val displayName: String = "",
    val author: String? = null,
    val templateVersion: String? = null,
    val localZipPath: String = "",
    val sha256: String = "",
    val state: String = RearCardState.NOT_INSTALLED.value,
    val notificationId: Int = 0,
    val mamlConfigJson: String = "{}",
    val advancedRearParamJson: String? = null,
    val advancedFocusParamJson: String? = null,
    val advancedPayload: Boolean = false,
    val desiredEnabled: Boolean = false,
    val cleanupPending: Boolean = false,
    val deleted: Boolean = false,
    val hostTemplatePath: String? = null,
    val lastCommandId: String? = null,
    val lastMessage: String? = null,
    val updatedAt: Long = 0L,
) {
    val stateEnum: RearCardState get() = RearCardState.fromValue(state)
    val localFile: File get() = File(localZipPath)
}

data class CardPackageMetadata(
    val schemaVersion: Int = 1,
    val name: String? = null,
    val author: String? = null,
    val version: String? = null,
    val defaultMamlConfig: Map<String, Any?>? = null,
)

data class TemplateSecurityFinding(
    val type: String,
    val detail: String,
)

data class TemplateInspection(
    val sha256: String,
    val compressedBytes: Long,
    val expandedBytes: Long,
    val entryCount: Int,
    val metadata: CardPackageMetadata?,
    val securityFindings: List<TemplateSecurityFinding>,
)

data class PendingCardImport(
    val stagedFile: File,
    val suggestedName: String,
    val inspection: TemplateInspection,
)

data class CardOperationResult(
    val success: Boolean,
    val message: String,
    val state: RearCardState,
    val record: CustomCardRecord? = null,
)
