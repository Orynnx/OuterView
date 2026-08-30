package org.orynnx.outerview.core.internal

import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

object SmartAssistantTemplateValidator {
    const val MaxCompressedBytes = 16L * 1024L * 1024L
    const val MaxExpandedBytes = 64L * 1024L * 1024L
    const val MaxEntries = 1024
    const val MaxManifestBytes = 2 * 1024 * 1024
    const val MaxMetadataBytes = 256 * 1024
    private val gson = Gson()
    private data class DigestResult(val sha256: String, val bytesRead: Long)

    fun inspect(file: File): TemplateInspection {
        require(file.isFile && file.length() in 1..MaxCompressedBytes) {
            "ZIP 必须大于 0 且不超过 16 MB"
        }
        var expanded = 0L
        var count = 0
        var manifestBytes: ByteArray? = null
        var metadata: CardPackageMetadata? = null
        val findings = mutableListOf<TemplateSecurityFinding>()
        val normalizedPaths = mutableSetOf<String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                require(count <= MaxEntries) { "ZIP 条目数超过 $MaxEntries" }
                val normalized = SecureZipValidation.normalizedPath(
                    entry = entry,
                    unsafePathMessage = "ZIP 包含不安全路径：${entry.name}",
                    absolutePathMessage = "ZIP 包含绝对路径：${entry.name}",
                )
                require(normalizedPaths.add(normalized)) { "ZIP 包含重复路径：${entry.name}" }
                require(entry.size >= -1L) { "ZIP 条目大小无效：${entry.name}" }

                val isXml = !entry.isDirectory && normalized.endsWith(".xml", ignoreCase = true)
                val isManifest = normalized == "manifest.xml"
                val isMetadata = normalized in setOf("outerview-card.json", "reareye-card.json")
                val captureLimit = when {
                    isXml -> MaxManifestBytes
                    isMetadata -> MaxMetadataBytes
                    else -> null
                }
                val read = SecureZipValidation.readEntry(
                    zip = zip,
                    entry = entry,
                    expandedBytes = expanded,
                    maxExpandedBytes = MaxExpandedBytes,
                    expandedLimitMessage = "ZIP 解压大小超过 64 MB",
                    captureLimitBytes = captureLimit,
                    captureLimitMessage = if (isXml) "卡片 XML 过大" else "卡片元数据过大",
                    failOnCaptureLimit = isManifest,
                )
                expanded = read.expandedBytes
                if (isXml) {
                    if (read.captureTruncated) {
                        findings += TemplateSecurityFinding(
                            "未完整扫描 XML",
                            "$normalized: 超过 ${MaxManifestBytes / 1024 / 1024} MB，仅保留安装兼容性",
                        )
                    } else if (!isManifest) {
                        runCatching {
                            MamlXmlSecurityScanner.inspect(requireNotNull(read.capturedBytes), normalized)
                        }.onSuccess { findings += it.securityFindings }
                            .onFailure {
                                findings += TemplateSecurityFinding(
                                    "无法扫描附属 XML",
                                    "$normalized: 文件不是可安全解析的标准 XML",
                                )
                            }
                    }
                }
                when (normalized) {
                    "manifest.xml" -> manifestBytes = read.capturedBytes
                    "outerview-card.json" -> metadata = parseMetadata(read)
                    "reareye-card.json" -> if (metadata == null) {
                        metadata = parseMetadata(read)
                    }
                }
            }
        }
        val manifest = manifestBytes ?: error("ZIP 顶层缺少 manifest.xml")
        val document = SecureManifestXml.parse(manifest)
        val root = document.documentElement
        require(root.tagName == "Widget") { "只支持根节点为 <Widget> 的 Smart Assistant 模板" }
        findings += MamlXmlSecurityScanner.inspect(manifest, "manifest.xml").securityFindings

        val digest = digest(file)
        return TemplateInspection(
            sha256 = digest.sha256,
            compressedBytes = digest.bytesRead,
            expandedBytes = expanded,
            entryCount = count,
            metadata = metadata,
            securityFindings = findings.distinct(),
        )
    }

    private fun parseMetadata(read: SecureZipEntryRead): CardPackageMetadata? {
        if (read.captureTruncated) return null
        return runCatching {
            gson.fromJson(
                requireNotNull(read.capturedBytes).toString(Charsets.UTF_8),
                CardPackageMetadata::class.java,
            )
        }.getOrNull()
    }

    fun sha256(file: File): String = digest(file).sha256

    private fun digest(file: File): DigestResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= MaxCompressedBytes) { "ZIP 超过 16 MB" }
                digest.update(buffer, 0, read)
            }
        }
        require(total > 0) { "ZIP 不能为空" }
        return DigestResult(
            sha256 = digest.digest()
                .joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') },
            bytesRead = total,
        )
    }
}
