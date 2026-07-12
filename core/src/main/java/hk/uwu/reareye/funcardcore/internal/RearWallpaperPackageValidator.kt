package hk.uwu.reareye.funcardcore.internal

import java.io.File
import java.util.zip.ZipFile

object RearWallpaperPackageValidator {
    const val MaxCompressedBytes = 32L * 1024L * 1024L
    const val MaxExpandedBytes = 128L * 1024L * 1024L
    const val MaxEntries = 2048
    // Real rear-screen MRC packages use MAML Widget version="1". This must not be
    // confused with Smart Assistant's Widget version="2" card contract.
    private val allowedRoots = setOf("Widget", "MiWallpaper", "Wallpaper", "Root")

    fun inspect(file: File): RearWallpaperPackageInspection {
        require(file.isFile && file.length() in 1..MaxCompressedBytes) {
            "wallpaper package must be between 1 byte and 32 MB"
        }
        var expandedBytes = 0L
        var entryCount = 0
        var descriptorName: String? = null
        var descriptorBytes: ByteArray? = null
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount++
                require(entryCount <= MaxEntries) { "too many ZIP entries" }
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                require(normalized.isNotBlank() && !normalized.startsWith("../") && "/../" !in normalized) {
                    "unsafe ZIP path: ${entry.name}"
                }
                require(!entry.name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(entry.name)) {
                    "absolute ZIP path: ${entry.name}"
                }
                require(entry.size >= -1L) { "invalid ZIP entry size" }
                if (entry.size > 0) {
                    expandedBytes += entry.size
                    require(expandedBytes <= MaxExpandedBytes) { "expanded package exceeds 128 MB" }
                }
                if (!entry.isDirectory && normalized in setOf("manifest.xml", "config.xml")) {
                    if (descriptorName == null || normalized == "manifest.xml") {
                        descriptorName = normalized
                        descriptorBytes = zip.getInputStream(entry).use { it.readBytes() }
                    }
                }
            }
        }
        val name = descriptorName ?: error("top-level manifest.xml or config.xml is required")
        val bytes = descriptorBytes ?: error("wallpaper descriptor is empty")
        require(bytes.size <= 2 * 1024 * 1024) { "wallpaper descriptor exceeds 2 MB" }
        val root = SecureManifestXml.parse(bytes).documentElement.tagName
        require(root in allowedRoots || name == "config.xml") { "unsupported wallpaper root element: $root" }
        return RearWallpaperPackageInspection(file.length(), expandedBytes, entryCount, name, root)
    }
}
