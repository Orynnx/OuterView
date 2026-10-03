package org.orynnx.outerview.core.ai

import org.orynnx.outerview.core.internal.SecureManifestXml
import org.orynnx.outerview.core.internal.SecureZipValidation
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Shared by the picker and privileged host. This validates data; it never executes a script. */
object AiPackageParser {
    const val MAX_PACKAGE_BYTES = 16L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 32L * 1024 * 1024
    const val MAX_ENTRIES = 512
    const val MAX_XML_BYTES = 2 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_RATIO = 200L
    private val payloadNames = setOf("rearscreen", "rearScreen.mrc")
    private const val SCRIPT_WARNING = "此应用包含可执行 JavaScript，将由系统背屏宿主运行；请只导入可信来源。"

    /** Supports a raw JsCanvas ZIP or one outer theme ZIP containing rearscreen/rearScreen.mrc. */
    @JvmStatic
    fun parse(file: File): AiParsedPackage {
        require(file.isFile && file.length() in 1..MAX_PACKAGE_BYTES) { "应用包必须是 1 字节至 16 MB 的 ZIP/MRC 文件" }
        try {
            val budget = Budget()
            val outer = readArchive(file, budget)
            val warnings = mutableListOf(SCRIPT_WARNING)
            val hasManifest = "manifest.xml" in outer
            val payloads = payloadNames.filter { it in outer }
            require(!(hasManifest && payloads.isNotEmpty())) { "应用包同时包含 manifest.xml 和内层资源，无法确定入口" }
            if (hasManifest) {
                rejectNestedArchives(outer)
                validateManifest(outer.getValue("manifest.xml"))
                warnings += "未提供外层名称、图标或预览时，将使用默认信息。"
                return AiParsedPackage("智能应用", file.readBytes(), null, null, warnings)
            }
            require(payloads.size == 1) { "需要根目录 manifest.xml，或唯一的 rearscreen/rearScreen.mrc 资源" }
            val payloadName = payloads.single()
            rejectNestedArchives(outer.filterKeys { it != payloadName })
            val innerBytes = outer.getValue(payloadName)
            require(innerBytes.size.toLong() in 1..MAX_PACKAGE_BYTES && isZip(innerBytes)) { "内层背屏资源不是有效 ZIP" }
            // Never use an archive entry as a path. The temporary filename is generated locally.
            val innerFile = File.createTempFile("ai-validate-", ".zip", file.absoluteFile.parentFile)
            val inner = try {
                innerFile.writeBytes(innerBytes)
                readArchive(innerFile, budget)
            } finally {
                innerFile.delete()
            }
            rejectNestedArchives(inner)
            require(payloadNames.none { it in inner }) { "嵌套层数超限：只支持一层主题外包" }
            validateManifest(inner["manifest.xml"] ?: throw IllegalArgumentException("内层资源缺少 manifest.xml"))
            val name = outer["description.xml"]?.let(::readName) ?: "智能应用"
            val icon = outer["app/app_icon.png"]?.also { validateImage(it, "图标") }
            val previews = outer.keys.filter { it.startsWith("preview/") && it.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp") }
            val preview = previews.sortedWith(compareBy<String> { it.contains("dark", ignoreCase = true) }.thenBy { it })
                .firstOrNull()?.let(outer::get)?.also { validateImage(it, "预览图") }
            if (icon == null) warnings += "应用包没有图标，将使用默认图标。"
            if (preview == null) warnings += "应用包没有预览图，将使用默认预览。"
            return AiParsedPackage(name, innerBytes, icon, preview, warnings)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("无法读取应用 ZIP：${error.message}", error)
        }
    }

    private class Budget(var expanded: Long = 0, var entries: Int = 0)

    private fun readArchive(file: File, budget: Budget): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                require(++budget.entries <= MAX_ENTRIES) { "ZIP 文件项数量超过 $MAX_ENTRIES" }
                val entry = entries.nextElement()
                val path = SecureZipValidation.normalizedPath(entry, "不安全的 ZIP 路径", "禁止 ZIP 绝对路径")
                require(path.length <= 512 && ':' !in path) { "ZIP 路径过长或含不允许的字符" }
                val key = path.lowercase(Locale.ROOT)
                require(seen.add(key)) { "ZIP 含重复或大小写冲突的路径：$path" }
                if (entry.isDirectory) {
                    require(entry.size <= 0) { "目录项不能携带文件内容" }
                    continue
                }
                files += key
                require(entry.size in 0..MAX_PACKAGE_BYTES && entry.compressedSize >= 0) { "ZIP 文件项大小无效或超过 16 MB" }
                if (entry.size > 1024 * 1024) {
                    require(entry.size <= maxOf(1, entry.compressedSize) * MAX_RATIO) { "ZIP 压缩比异常" }
                }
                val isXml = path.endsWith(".xml", ignoreCase = true)
                val read = SecureZipValidation.readEntry(
                    zip, entry, budget.expanded, MAX_EXPANDED_BYTES,
                    "ZIP 解压总量超过 32 MB", if (isXml) MAX_XML_BYTES else MAX_PACKAGE_BYTES.toInt(),
                    "ZIP 文件项超过允许大小",
                )
                budget.expanded = read.expandedBytes
                val bytes = requireNotNull(read.capturedBytes)
                require(bytes.size.toLong() == entry.size) { "ZIP 文件项大小不一致" }
                val crc = CRC32().apply { update(bytes) }.value
                require(entry.crc == crc) { "ZIP 文件项 CRC 校验失败" }
                if (isXml) {
                    // Validate XML syntax/entities, not a blacklist of native MAML features.
                    // ContentProviderBinder and commands are part of the host's application API.
                    SecureManifestXml.parse(bytes)
                }
                result[path] = bytes
            }
        }
        require(result.isNotEmpty()) { "ZIP 为空" }
        for (path in seen) {
            val parts = path.split('/')
            for (count in 1 until parts.size) {
                require(parts.take(count).joinToString("/") !in files) { "ZIP 文件和目录路径冲突" }
            }
        }
        return result
    }

    private fun rejectNestedArchives(entries: Map<String, ByteArray>) {
        require(entries.none { (name, bytes) ->
            isZip(bytes) || name.endsWith(".zip", true) || name.endsWith(".mrc", true) || name in payloadNames
        }) { "嵌套层数超限或包含额外 ZIP/MRC：只支持一层主题外包" }
    }

    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 0x50.toByte() &&
        bytes[1] == 0x4b.toByte() && ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
        (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()) || (bytes[2] == 7.toByte() && bytes[3] == 8.toByte()))

    private fun validateManifest(bytes: ByteArray) {
        val document = SecureManifestXml.parse(bytes)
        require(document.documentElement.tagName == "Widget") { "manifest.xml 根节点必须是 Widget" }
        val canvases = document.getElementsByTagName("JsCanvas")
        require(canvases.length in 1..8) { "应用必须包含 1 至 8 个 JsCanvas" }
        for (index in 0 until canvases.length) {
            val scripts = (canvases.item(index) as Element).getElementsByTagName("Script")
            require((0 until scripts.length).any { scripts.item(it).textContent.isNotBlank() }) { "JsCanvas 缺少内嵌 Script" }
        }
    }

    private fun readName(bytes: ByteArray): String {
        val document = SecureManifestXml.parse(bytes)
        require(document.documentElement.tagName in setOf("theme", "MIUI-Theme")) { "description.xml 根节点必须是 theme 或 MIUI-Theme" }
        val type = document.getElementsByTagName("resourceType").item(0)?.textContent?.trim().orEmpty()
        require(type.isEmpty() || type == "rearscreen") { "这不是背屏应用资源" }
        return listOf("appName", "title").firstNotNullOfOrNull { tag ->
            document.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
        }?.also { require(it.length <= 80 && it.none(Character::isISOControl)) { "应用名称过长或含控制字符" } } ?: "智能应用"
    }

    private fun validateImage(bytes: ByteArray, label: String) {
        require(bytes.size in 8..MAX_IMAGE_BYTES) { "$label 大小无效或超过 8 MB" }
        val png = bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val jpeg = bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte()
        val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        require(png || jpeg || webp) { "$label 不是支持的 PNG/JPEG/WebP" }
    }
}
