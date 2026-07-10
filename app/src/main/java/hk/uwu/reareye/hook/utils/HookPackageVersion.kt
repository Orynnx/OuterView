package hk.uwu.reareye.hook.utils

import android.content.Context
import android.os.Build
import java.io.File

internal fun resolveHookPackageVersionCode(
    context: Context,
    packageName: String,
    sourceDir: String,
): Long {
    return runCatching {
        val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }.getOrElse {
        File(sourceDir).lastModified().takeIf { lastModified -> lastModified > 0L } ?: 1L
    }
}
