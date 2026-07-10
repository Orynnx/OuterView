package hk.uwu.reareye.funcardcore

import android.net.Uri

interface RearCardManagementEndpoints {
    suspend fun refresh(): RearCardManagerSnapshot
    suspend fun inspectImport(uri: Uri, displayNameHint: String? = null): EndpointResult<CardImportPreview>
    fun discardImport(token: String)
    suspend fun importAndInstall(token: String): RearCardActionResult
    suspend fun replaceAndInstall(cardId: String, token: String): RearCardActionResult
    suspend fun retryInstall(cardId: String): RearCardActionResult
    suspend fun setVisible(cardId: String, visible: Boolean): RearCardActionResult
    suspend fun deleteCard(cardId: String): RearCardActionResult
    suspend fun deleteAllCards(): RearCardActionResult
    suspend fun updatePayload(
        cardId: String,
        advanced: Boolean,
        mamlConfigJson: String = "{}",
        rearParamJson: String = "{}",
        focusParamJson: String = "{}",
    ): RearCardActionResult
    suspend fun diagnostics(cardId: String): EndpointResult<ManagedCardDiagnostics>
}
