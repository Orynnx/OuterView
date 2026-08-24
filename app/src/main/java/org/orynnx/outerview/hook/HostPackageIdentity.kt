package org.orynnx.outerview.hook

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

internal fun hostPackageVersionCode(
    context: Context,
    packageName: String,
    sourceDir: String,
): Long {
    val packageManager = context.packageManager
    val installed = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
    }.getOrNull()
    if (installed != null) return installed

    val archived = runCatching {
        packageManager.getPackageArchiveInfo(sourceDir, 0)?.longVersionCode
    }.getOrNull()
    if (archived != null) return archived

    return File(sourceDir).lastModified().takeIf { it > 0L }
        ?: error("Unable to identify host package $packageName")
}
