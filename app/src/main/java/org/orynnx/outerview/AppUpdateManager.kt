package org.orynnx.outerview

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String,
    val notes: String,
)

/** GitHub Release checker and user-confirmed APK downloader. Never performs a silent install. */
object AppUpdateManager {
    private const val LatestReleaseUrl =
        "https://api.github.com/repos/Orynnx/OuterView/releases/latest"

    suspend fun checkLatest(currentVersion: String): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(LatestReleaseUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "OuterView/$currentVersion")
            }
            try {
                require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "更新检查失败（HTTP ${connection.responseCode}）"
                }
                val root = JSONObject(connection.inputStream.bufferedReader().use { reader -> reader.readText() })
                val version = root.getString("tag_name").removePrefix("v")
                if (compareVersions(version, currentVersion) <= 0) return@runCatching null
                val assets = root.getJSONArray("assets")
                val apkUrl = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
                    ?.getString("browser_download_url")
                    ?: error("新版本未附带 APK")
                AppUpdateInfo(
                    version = version,
                    releaseUrl = root.getString("html_url"),
                    apkUrl = apkUrl,
                    notes = root.optString("body").trim(),
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    fun enqueueDownload(context: Context, update: AppUpdateInfo): Long {
        val fileName = "OuterView-${update.version}.apk"
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("正在下载 OuterView ${update.version}")
            .setDescription("下载完成后可在此安装更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName")
        return (context.getSystemService(DownloadManager::class.java)).enqueue(request)
    }

    fun openDownloadedApk(context: Context, version: String): Boolean {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/OuterView-$version.apk")
        if (!file.isFile) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    internal fun compareVersions(left: String, right: String): Int {
        val l = left.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val r = right.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        return (0 until maxOf(l.size, r.size))
            .map { index -> (l.getOrElse(index) { 0 }).compareTo(r.getOrElse(index) { 0 }) }
            .firstOrNull { it != 0 } ?: 0
    }
}
