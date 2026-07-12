package hk.uwu.reareye.funcardcore.internal

data class RearWallpaperRuntimeRecord(
    val resId: String,
    val applyId: String,
    val resLocalPath: String?,
    val metaPath: String?,
    val previewPath: String?,
    val position: Int,
    val displayName: String? = null,
) {
    val wallpaperId: Int get() = (resId + applyId).hashCode()
}

data class RearWallpaperPackageInspection(
    val compressedBytes: Long,
    val expandedBytes: Long,
    val entryCount: Int,
    val descriptorName: String,
    val rootElement: String?,
)
