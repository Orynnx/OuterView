package hk.uwu.reareye.funcardcore.internal

import java.io.File

object ManagedRearWallpaperPaths {
    const val ResourcePrefix = "outerview_wallpaper_"
    private val idPattern = Regex("^[a-z0-9_]{1,96}$")

    fun resourceDirectory(runtimeRoot: File, resId: String, applyId: String): File {
        require(resId.startsWith(ResourcePrefix) && idPattern.matches(resId)) { "invalid managed resId" }
        require(idPattern.matches(applyId)) { "invalid applyId" }
        return File(runtimeRoot, "${resId}_${applyId}")
    }

    fun isManagedResource(runtimeRoot: File, record: RearWallpaperRuntimeRecord): Boolean {
        if (!record.resId.startsWith(ResourcePrefix)) return false
        val expected = runCatching {
            resourceDirectory(runtimeRoot, record.resId, record.applyId).canonicalFile
        }.getOrNull() ?: return false
        return listOfNotNull(record.resLocalPath, record.metaPath).all { path ->
            val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return@all false
            candidate == expected || candidate.toPath().startsWith(expected.toPath())
        } && listOfNotNull(record.resLocalPath, record.metaPath).isNotEmpty()
    }
}
