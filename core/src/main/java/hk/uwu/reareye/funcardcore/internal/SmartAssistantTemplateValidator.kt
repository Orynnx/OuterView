package hk.uwu.reareye.funcardcore.internal

import com.google.gson.Gson
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

object SmartAssistantTemplateValidator {
    const val MaxCompressedBytes = 16L * 1024L * 1024L
    const val MaxExpandedBytes = 64L * 1024L * 1024L
    const val MaxEntries = 1024
    private val gson = Gson()

    fun inspect(file: File): TemplateInspection {
        require(file.isFile && file.length() in 1..MaxCompressedBytes) {
            "ZIP 必须大于 0 且不超过 16 MB"
        }
        var expanded = 0L
        var count = 0
        var manifestBytes: ByteArray? = null
        var metadata: CardPackageMetadata? = null
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                require(count <= MaxEntries) { "ZIP 条目数超过 $MaxEntries" }
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                require(normalized.isNotBlank() && !normalized.startsWith("../") && "/../" !in normalized) {
                    "ZIP 包含不安全路径：${entry.name}"
                }
                require(!entry.name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(entry.name)) {
                    "ZIP 包含绝对路径：${entry.name}"
                }
                val size = entry.size
                require(size >= -1L) { "ZIP 条目大小无效：${entry.name}" }
                if (size > 0) {
                    expanded += size
                    require(expanded <= MaxExpandedBytes) { "ZIP 解压大小超过 64 MB" }
                }
                when (normalized) {
                    "manifest.xml" -> manifestBytes = zip.getInputStream(entry).use { it.readBytes() }
                    "reareye-card.json" -> metadata = zip.getInputStream(entry).bufferedReader().use {
                        gson.fromJson(it, CardPackageMetadata::class.java)
                    }
                }
            }
        }
        val manifest = manifestBytes ?: error("ZIP 顶层缺少 manifest.xml")
        require(manifest.size <= 2 * 1024 * 1024) { "manifest.xml 过大" }
        val document = SecureManifestXml.parse(manifest)
        val root = document.documentElement
        require(root.tagName == "Widget") { "只支持根节点为 <Widget> 的 Smart Assistant 模板" }
        require(root.getAttribute("version") == "2") { "只支持 Widget version=2" }

        val findings = mutableListOf<TemplateSecurityFinding>()
        collectElements(root, "IntentCommand").forEach { element ->
            val target = listOf("package", "action", "class").mapNotNull { key ->
                element.getAttribute(key).takeIf { it.isNotBlank() }?.let { "$key=$it" }
            }.joinToString(", ")
            findings += TemplateSecurityFinding("IntentCommand", target.ifBlank { "未声明目标" })
        }
        collectElements(root, "ExternCommand").forEach { element ->
            findings += TemplateSecurityFinding(
                "ExternCommand",
                element.getAttribute("command").ifBlank { "未声明命令" },
            )
        }
        return TemplateInspection(
            sha256 = sha256(file),
            compressedBytes = file.length(),
            expandedBytes = expanded,
            entryCount = count,
            metadata = metadata,
            securityFindings = findings.distinct(),
        )
    }

    private fun collectElements(root: Element, tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
