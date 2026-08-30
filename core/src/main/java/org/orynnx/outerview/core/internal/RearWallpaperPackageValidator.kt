package org.orynnx.outerview.core.internal

import java.io.File
import java.util.zip.ZipFile

object RearWallpaperPackageValidator {
    const val MaxCompressedBytes = 32L * 1024L * 1024L
    const val MaxExpandedBytes = 128L * 1024L * 1024L
    const val MaxEntries = 2048
    const val MaxDescriptorBytes = 2 * 1024 * 1024
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
        val normalizedPaths = mutableSetOf<String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount++
                require(entryCount <= MaxEntries) { "too many ZIP entries" }
                val normalized = SecureZipValidation.normalizedPath(
                    entry = entry,
                    unsafePathMessage = "unsafe ZIP path: ${entry.name}",
                    absolutePathMessage = "absolute ZIP path: ${entry.name}",
                )
                require(normalizedPaths.add(normalized)) { "duplicate ZIP path: ${entry.name}" }
                require(entry.size >= -1L) { "invalid ZIP entry size" }
                val isXml = !entry.isDirectory && normalized.endsWith(".xml", ignoreCase = true)
                val isDescriptor = !entry.isDirectory && normalized in setOf("manifest.xml", "config.xml")
                val read = SecureZipValidation.readEntry(
                    zip = zip,
                    entry = entry,
                    expandedBytes = expandedBytes,
                    maxExpandedBytes = MaxExpandedBytes,
                    expandedLimitMessage = "expanded package exceeds 128 MB",
                    captureLimitBytes = MaxDescriptorBytes.takeIf { isXml },
                    captureLimitMessage = "wallpaper XML exceeds 2 MB",
                )
                expandedBytes = read.expandedBytes
                if (isXml) {
                    val scan = MamlXmlSecurityScanner.inspect(requireNotNull(read.capturedBytes), normalized)
                    require(scan.securityFindings.isEmpty()) {
                        val finding = scan.securityFindings.first()
                        "wallpaper XML contains blocked ${finding.type}: ${finding.detail}"
                    }
                }
                if (isDescriptor && (descriptorName == null || normalized == "manifest.xml")) {
                    descriptorName = normalized
                    descriptorBytes = read.capturedBytes
                }
            }
        }
        val name = descriptorName ?: error("top-level manifest.xml or config.xml is required")
        val bytes = descriptorBytes ?: error("wallpaper descriptor is empty")
        val root = SecureManifestXml.parse(bytes).documentElement.tagName
        require(root in allowedRoots || name == "config.xml") { "unsupported wallpaper root element: $root" }
        return RearWallpaperPackageInspection(file.length(), expandedBytes, entryCount, name, root)
    }
}
