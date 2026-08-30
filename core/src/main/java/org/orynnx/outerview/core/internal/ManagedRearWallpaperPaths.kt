package org.orynnx.outerview.core.internal

import java.io.File
import java.nio.file.Files

object ManagedRearWallpaperPaths {
    const val ResourcePrefix = "outerview_wallpaper_"
    const val PackageFileName = "wallpaper.mrc"
    const val MetadataFileName = "metadata.mrm"
    private val idPattern = Regex("^[a-z0-9_]{1,96}$")

    fun resourceDirectory(runtimeRoot: File, resId: String, applyId: String): File {
        require(resId.startsWith(ResourcePrefix) && idPattern.matches(resId)) { "invalid managed resId" }
        require(idPattern.matches(applyId)) { "invalid applyId" }
        return File(runtimeRoot, "${resId}_${applyId}")
    }

    fun isManagedResource(runtimeRoot: File, record: RearWallpaperRuntimeRecord): Boolean {
        val directory = managedResourceDirectory(runtimeRoot, record) ?: return false
        val packagePath = record.resLocalPath ?: return false
        val metadataPath = record.metaPath ?: return false
        return exactPath(packagePath, File(directory, PackageFileName)) &&
            exactPath(metadataPath, File(directory, MetadataFileName))
    }

    fun managedResourceDirectory(runtimeRoot: File, record: RearWallpaperRuntimeRecord): File? {
        if (!record.resId.startsWith(ResourcePrefix)) return null
        if (Files.isSymbolicLink(runtimeRoot.toPath())) return null
        val root = runCatching { runtimeRoot.canonicalFile }.getOrNull() ?: return null
        val unresolvedDirectory = runCatching {
            resourceDirectory(runtimeRoot, record.resId, record.applyId)
        }.getOrNull() ?: return null
        if (Files.isSymbolicLink(unresolvedDirectory.toPath())) return null
        val directory = runCatching { unresolvedDirectory.canonicalFile }.getOrNull() ?: return null
        if (directory.parentFile != root) return null
        return directory
    }

    private fun exactPath(candidatePath: String, expectedFile: File): Boolean {
        val candidate = runCatching { File(candidatePath) }.getOrNull() ?: return false
        if (Files.isSymbolicLink(candidate.toPath())) return false
        return runCatching { candidate.canonicalFile == expectedFile.canonicalFile }.getOrDefault(false)
    }
}
