package org.orynnx.outerview.core.internal

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class SecureZipEntryRead(
    val expandedBytes: Long,
    val capturedBytes: ByteArray?,
    val captureTruncated: Boolean = false,
)

internal object SecureZipValidation {
    private const val BufferSize = 16 * 1024
    private val drivePath = Regex("^[A-Za-z]:")

    fun normalizedPath(
        entry: ZipEntry,
        unsafePathMessage: String,
        absolutePathMessage: String,
    ): String {
        val slashPath = entry.name.replace('\\', '/')
        require(!slashPath.startsWith('/') && !drivePath.containsMatchIn(slashPath)) {
            absolutePathMessage
        }
        require(slashPath.none(Character::isISOControl)) { unsafePathMessage }

        val normalized = slashPath.removeSuffix("/")
        require(normalized.isNotBlank()) { unsafePathMessage }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            unsafePathMessage
        }
        return normalized
    }

    fun readEntry(
        zip: ZipFile,
        entry: ZipEntry,
        expandedBytes: Long,
        maxExpandedBytes: Long,
        expandedLimitMessage: String,
        captureLimitBytes: Int? = null,
        captureLimitMessage: String = expandedLimitMessage,
        failOnCaptureLimit: Boolean = true,
    ): SecureZipEntryRead {
        var total = expandedBytes
        var captureTruncated = false
        val captured = captureLimitBytes?.let {
            ByteArrayOutputStream(minOf(it, BufferSize))
        }
        val buffer = ByteArray(BufferSize)
        zip.getInputStream(entry).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue

                require(total <= maxExpandedBytes - read.toLong()) {
                    expandedLimitMessage
                }
                total += read
                captured?.let { output ->
                    val remaining = captureLimitBytes - output.size()
                    if (read > remaining) {
                        require(!failOnCaptureLimit) { captureLimitMessage }
                        if (remaining > 0) output.write(buffer, 0, remaining)
                        captureTruncated = true
                    } else if (!captureTruncated) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        return SecureZipEntryRead(total, captured?.toByteArray(), captureTruncated)
    }
}
