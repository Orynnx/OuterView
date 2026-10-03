package org.orynnx.outerview.core.ai

data class AiCard(
    val id: String,
    val name: String,
    val resourcePath: String,
    val managed: Boolean,
    val registered: Boolean = true,
)
data class AiAppSnapshot(val connected: Boolean, val cards: List<AiCard>, val message: String)
data class AiImportPreview(val token: String, val name: String, val warnings: List<String>)
data class AiActionResult(val success: Boolean, val message: String, val pending: Boolean = false)

/** Validated bytes, without extracting any caller-controlled path into the filesystem. */
data class AiParsedPackage(
    val name: String,
    val mamlZipBytes: ByteArray,
    val appIconBytes: ByteArray?,
    val previewBytes: ByteArray?,
    val warnings: List<String>,
)
