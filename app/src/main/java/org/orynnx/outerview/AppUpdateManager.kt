package org.orynnx.outerview

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String,
    val notes: String,
)

enum class AppUpdateDownloadStatus {
    NONE,
    PENDING,
    RUNNING,
    PAUSED,
    SUCCESSFUL,
    FAILED,
}

data class AppUpdateDownloadState(
    val id: Long? = null,
    val status: AppUpdateDownloadStatus = AppUpdateDownloadStatus.NONE,
    val reason: Int? = null,
) {
    val inProgress: Boolean
        get() = status == AppUpdateDownloadStatus.PENDING ||
            status == AppUpdateDownloadStatus.RUNNING ||
            status == AppUpdateDownloadStatus.PAUSED
}

enum class AppUpdateError {
    INVALID_VERSION,
    UPDATE_CHECK_FAILED,
    HTTP_ERROR,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
    UNTRUSTED_URL,
    DOWNLOAD_UNAVAILABLE,
    FILE_IO,
    APK_NOT_FOUND,
    APK_INVALID,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    SIGNATURE_MISMATCH,
    NO_INSTALLER,
    INSTALL_LAUNCH_FAILED,
}

class AppUpdateException(
    val code: AppUpdateError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** GitHub Release checker and user-confirmed APK downloader. Never performs a silent install. */
object AppUpdateManager {
    private const val LatestReleaseUrl =
        "https://api.github.com/repos/Orynnx/OuterView/releases/latest"
    private const val GitHubHost = "github.com"
    private const val RepositoryPath = "/Orynnx/OuterView"
    private const val MaxReleaseResponseBytes = 1024 * 1024
    private const val MaxReleaseNotesChars = 64 * 1024
    private const val DownloadPrefs = "app_update_download"
    private const val DownloadIdKey = "download_id"
    private const val DownloadVersionKey = "download_version"
    private val UpdateRetentionMillis = TimeUnit.DAYS.toMillis(14)
    private val StableVersionRegex = Regex(
        """v?(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})""",
    )
    private val ManagedUpdateNameRegex = Regex(
        """OuterView-(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.apk""",
    )

    suspend fun checkLatest(currentVersion: String): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        val normalizedCurrent = normalizeStableVersion(currentVersion)
            ?: return@withContext updateFailure(
                AppUpdateError.INVALID_VERSION,
                "当前应用版本格式无效",
            )

        appResult(AppUpdateError.UPDATE_CHECK_FAILED, "检查更新失败") {
            val connection = (URL(LatestReleaseUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "OuterView/$normalizedCurrent")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw AppUpdateException(
                        AppUpdateError.HTTP_ERROR,
                        "更新检查失败（HTTP ${connection.responseCode}）",
                    )
                }
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MaxReleaseResponseBytes) {
                    throw AppUpdateException(
                        AppUpdateError.RESPONSE_TOO_LARGE,
                        "更新信息响应过大",
                    )
                }
                connection.inputStream.use { input ->
                    parseReleaseResponse(
                        currentVersion = normalizedCurrent,
                        response = readUtf8Limited(input, MaxReleaseResponseBytes),
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Enqueues a trusted release APK download and returns a structured failure to new callers.
     * [enqueueDownload] remains as a throwing compatibility facade for the existing UI.
     */
    @Synchronized
    fun enqueueDownloadResult(context: Context, update: AppUpdateInfo): Result<Long> {
        var destination: File? = null
        val result = appResult(AppUpdateError.DOWNLOAD_UNAVAILABLE, "无法开始下载") {
            val version = normalizeStableVersion(update.version)
                ?: throw AppUpdateException(AppUpdateError.INVALID_VERSION, "更新版本格式无效")
            if (!isExpectedReleaseUrl(update.releaseUrl, version) ||
                !isExpectedApkUrl(update.apkUrl, version)
            ) {
                throw AppUpdateException(AppUpdateError.UNTRUSTED_URL, "更新链接不受信任")
            }
            val existing = downloadState(context, version)
            if ((existing.inProgress || existing.status == AppUpdateDownloadStatus.SUCCESSFUL) &&
                existing.id != null
            ) {
                return@appResult existing.id
            }

            val downloadsRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw AppUpdateException(AppUpdateError.FILE_IO, "更新存储目录不可用")
            val updateDirectory = File(downloadsRoot, "updates")
            if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
                throw AppUpdateException(AppUpdateError.FILE_IO, "无法创建更新存储目录")
            }
            if (!updateDirectory.isDirectory) {
                throw AppUpdateException(AppUpdateError.FILE_IO, "更新存储路径不是目录")
            }

            val fileName = expectedAssetName(version)
            destination = File(updateDirectory, fileName)
            cleanManagedUpdateFiles(updateDirectory, fileName)

            val manager = context.getSystemService(DownloadManager::class.java)
                ?: throw AppUpdateException(AppUpdateError.DOWNLOAD_UNAVAILABLE, "系统下载服务不可用")
            val request = DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle("正在下载 OuterView $version")
                .setDescription("下载完成后可在此安装更新")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "updates/$fileName",
                )
            manager.enqueue(request).also { id -> rememberDownload(context, version, id) }
        }
        if (result.isFailure) {
            destination?.takeIf(File::exists)?.delete()
        }
        return result
    }

    fun enqueueDownload(context: Context, update: AppUpdateInfo): Long =
        enqueueDownloadResult(context, update).getOrThrow()

    /**
     * Validates the downloaded archive before handing it to a system package installer.
     * This checks the package identity, declared version, and forward signing lineage;
     * a merely present or partially downloaded file never succeeds.
     */
    fun openDownloadedApkResult(context: Context, version: String): Result<Unit> =
        appResult(AppUpdateError.INSTALL_LAUNCH_FAILED, "无法打开安装程序") {
            val normalizedVersion = normalizeStableVersion(version)
                ?: throw AppUpdateException(AppUpdateError.INVALID_VERSION, "更新版本格式无效")
            val downloadsRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw AppUpdateException(AppUpdateError.FILE_IO, "更新存储目录不可用")
            val file = File(
                File(downloadsRoot, "updates"),
                expectedAssetName(normalizedVersion),
            )
            if (!file.isFile || file.length() <= 0L) {
                forgetDownload(context, normalizedVersion)
                throw AppUpdateException(AppUpdateError.APK_NOT_FOUND, "更新 APK 尚未下载完成")
            }

            try {
                validateArchiveIdentity(context, file, normalizedVersion)
            } catch (error: AppUpdateException) {
                if (error.code in setOf(
                        AppUpdateError.APK_INVALID,
                        AppUpdateError.PACKAGE_MISMATCH,
                        AppUpdateError.VERSION_MISMATCH,
                        AppUpdateError.SIGNATURE_MISMATCH,
                    )
                ) {
                    if (!file.delete() && file.exists()) {
                        throw AppUpdateException(
                            AppUpdateError.FILE_IO,
                            "更新 APK 校验失败且无法清理，请在系统下载中删除后重试",
                            error,
                        )
                    }
                    forgetDownload(context, normalizedVersion)
                }
                throw error
            }

            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            } catch (error: IllegalArgumentException) {
                throw AppUpdateException(AppUpdateError.FILE_IO, "无法安全共享更新 APK", error)
            } catch (error: SecurityException) {
                throw AppUpdateException(AppUpdateError.FILE_IO, "无权共享更新 APK", error)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                throw AppUpdateException(AppUpdateError.NO_INSTALLER, "系统中没有可用的 APK 安装程序")
            }
            try {
                context.startActivity(intent)
            } catch (error: ActivityNotFoundException) {
                throw AppUpdateException(AppUpdateError.NO_INSTALLER, "系统中没有可用的 APK 安装程序", error)
            } catch (error: SecurityException) {
                throw AppUpdateException(AppUpdateError.INSTALL_LAUNCH_FAILED, "无权打开 APK 安装程序", error)
            }
        }

    /** Compatibility facade for the current UI. Prefer [openDownloadedApkResult] for error details. */
    fun openDownloadedApk(context: Context, version: String): Boolean =
        openDownloadedApkResult(context, version).isSuccess

    fun downloadState(context: Context, version: String): AppUpdateDownloadState {
        val normalizedVersion = normalizeStableVersion(version) ?: return AppUpdateDownloadState()
        val preferences = context.getSharedPreferences(DownloadPrefs, Context.MODE_PRIVATE)
        if (preferences.getString(DownloadVersionKey, null) != normalizedVersion) {
            return AppUpdateDownloadState()
        }
        val id = preferences.getLong(DownloadIdKey, -1L).takeIf { it >= 0L }
            ?: return AppUpdateDownloadState()
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: return AppUpdateDownloadState(id, AppUpdateDownloadStatus.FAILED)
        val queried = runCatching {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use AppUpdateDownloadState(id)
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                AppUpdateDownloadState(
                    id = id,
                    status = when (status) {
                        DownloadManager.STATUS_PENDING -> AppUpdateDownloadStatus.PENDING
                        DownloadManager.STATUS_RUNNING -> AppUpdateDownloadStatus.RUNNING
                        DownloadManager.STATUS_PAUSED -> AppUpdateDownloadStatus.PAUSED
                        DownloadManager.STATUS_SUCCESSFUL -> AppUpdateDownloadStatus.SUCCESSFUL
                        DownloadManager.STATUS_FAILED -> AppUpdateDownloadStatus.FAILED
                        else -> AppUpdateDownloadStatus.NONE
                    },
                    reason = reason.takeIf { status == DownloadManager.STATUS_FAILED },
                )
            }
        }.getOrNull() ?: AppUpdateDownloadState(id, AppUpdateDownloadStatus.FAILED)
        if (queried.status == AppUpdateDownloadStatus.SUCCESSFUL) {
            val downloadsRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val file = downloadsRoot?.let { File(File(it, "updates"), expectedAssetName(normalizedVersion)) }
            if (file?.isFile != true || file.length() <= 0L) {
                return AppUpdateDownloadState(id, AppUpdateDownloadStatus.FAILED)
            }
        }
        return queried
    }

    fun rememberedDownloadVersion(context: Context): String? =
        context.getSharedPreferences(DownloadPrefs, Context.MODE_PRIVATE)
            .getString(DownloadVersionKey, null)
            ?.let(::normalizeStableVersion)

    internal fun normalizeStableVersion(raw: String): String? {
        val match = StableVersionRegex.matchEntire(raw) ?: return null
        return (1..3).joinToString(".") { match.groupValues[it] }
    }

    internal fun compareVersions(left: String, right: String): Int {
        val l = parseStableVersion(left)
            ?: throw IllegalArgumentException("Invalid stable version: $left")
        val r = parseStableVersion(right)
            ?: throw IllegalArgumentException("Invalid stable version: $right")
        return compareValuesBy(l, r, VersionParts::major, VersionParts::minor, VersionParts::patch)
    }

    internal fun expectedAssetName(version: String): String {
        val normalized = normalizeStableVersion(version)
            ?: throw IllegalArgumentException("Invalid stable version: $version")
        return "OuterView-$normalized.apk"
    }

    internal fun selectExpectedAssetUrl(
        version: String,
        assets: List<Pair<String, String>>,
    ): String? {
        val expectedName = runCatching { expectedAssetName(version) }.getOrNull() ?: return null
        val matches = assets.filter { (name, _) -> name == expectedName }
        return matches.singleOrNull()?.second
    }

    internal fun isExpectedReleaseUrl(rawUrl: String, version: String): Boolean {
        val normalized = normalizeStableVersion(version) ?: return false
        val uri = trustedHttpsUri(rawUrl, GitHubHost) ?: return false
        return uri.rawPath == "$RepositoryPath/releases/tag/$normalized" ||
            uri.rawPath == "$RepositoryPath/releases/tag/v$normalized"
    }

    internal fun isExpectedApkUrl(rawUrl: String, version: String): Boolean {
        val normalized = normalizeStableVersion(version) ?: return false
        val uri = trustedHttpsUri(rawUrl, GitHubHost) ?: return false
        val fileName = expectedAssetName(normalized)
        return uri.rawPath == "$RepositoryPath/releases/download/$normalized/$fileName" ||
            uri.rawPath == "$RepositoryPath/releases/download/v$normalized/$fileName"
    }

    internal fun isManagedUpdateApkName(name: String): Boolean =
        ManagedUpdateNameRegex.matches(name)

    internal fun readUtf8Limited(input: InputStream, maxBytes: Int): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw AppUpdateException(AppUpdateError.RESPONSE_TOO_LARGE, "更新信息响应过大")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    internal fun signingLineageAllowsUpdate(
        installedCurrent: Set<String>,
        archiveCurrent: Set<String>,
        archiveHistory: Set<String>,
        installedHasMultipleSigners: Boolean,
        archiveHasMultipleSigners: Boolean,
    ): Boolean {
        if (installedCurrent.isEmpty() || archiveCurrent.isEmpty()) return false
        if (installedHasMultipleSigners || archiveHasMultipleSigners) {
            return installedHasMultipleSigners && archiveHasMultipleSigners &&
                installedCurrent == archiveCurrent
        }
        val installedSigner = installedCurrent.singleOrNull() ?: return false
        return installedSigner in (archiveHistory + archiveCurrent)
    }

    private fun parseReleaseResponse(currentVersion: String, response: String): AppUpdateInfo? {
        val root = try {
            JSONObject(response)
        } catch (error: Exception) {
            throw AppUpdateException(AppUpdateError.INVALID_RESPONSE, "更新信息不是有效的 JSON", error)
        }
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
            throw AppUpdateException(AppUpdateError.INVALID_RESPONSE, "更新信息不是稳定版本")
        }
        val rawVersion = root.optString("tag_name")
        val version = normalizeStableVersion(rawVersion)
            ?: throw AppUpdateException(AppUpdateError.INVALID_VERSION, "远程版本格式无效")
        if (compareVersions(version, currentVersion) <= 0) return null

        val releaseUrl = root.optString("html_url")
        if (!isExpectedReleaseUrl(releaseUrl, version)) {
            throw AppUpdateException(AppUpdateError.UNTRUSTED_URL, "发布页链接不受信任")
        }
        val assetsArray = root.optJSONArray("assets")
            ?: throw AppUpdateException(AppUpdateError.INVALID_RESPONSE, "更新信息缺少资产列表")
        val assets = buildList {
            for (index in 0 until assetsArray.length()) {
                val asset = assetsArray.optJSONObject(index) ?: continue
                add(asset.optString("name") to asset.optString("browser_download_url"))
            }
        }
        val apkUrl = selectExpectedAssetUrl(version, assets)
            ?: throw AppUpdateException(
                AppUpdateError.INVALID_RESPONSE,
                "新版本未附带唯一且精确命名的 ${expectedAssetName(version)}",
            )
        if (!isExpectedApkUrl(apkUrl, version)) {
            throw AppUpdateException(AppUpdateError.UNTRUSTED_URL, "APK 下载链接不受信任")
        }
        return AppUpdateInfo(
            version = version,
            releaseUrl = releaseUrl,
            apkUrl = apkUrl,
            notes = root.optString("body").trim().take(MaxReleaseNotesChars),
        )
    }

    private fun cleanManagedUpdateFiles(directory: File, expectedFileName: String) {
        val now = System.currentTimeMillis()
        val candidates = directory.listFiles()
            ?: throw AppUpdateException(AppUpdateError.FILE_IO, "无法读取更新存储目录")
        candidates.forEach { candidate ->
            val expired = candidate.lastModified() <= 0L ||
                (now >= candidate.lastModified() && now - candidate.lastModified() >= UpdateRetentionMillis)
            if (candidate.name == expectedFileName ||
                (isManagedUpdateApkName(candidate.name) && expired)
            ) {
                if (!candidate.delete() && candidate.exists()) {
                    throw AppUpdateException(
                        AppUpdateError.FILE_IO,
                        "无法清理旧的更新文件：${candidate.name}",
                    )
                }
            }
        }
    }

    private fun rememberDownload(context: Context, version: String, id: Long) {
        context.getSharedPreferences(DownloadPrefs, Context.MODE_PRIVATE)
            .edit()
            .putString(DownloadVersionKey, version)
            .putLong(DownloadIdKey, id)
            .apply()
    }

    private fun forgetDownload(context: Context, version: String) {
        val preferences = context.getSharedPreferences(DownloadPrefs, Context.MODE_PRIVATE)
        if (preferences.getString(DownloadVersionKey, null) != version) return
        val id = preferences.getLong(DownloadIdKey, -1L).takeIf { it >= 0L }
        if (id != null) {
            runCatching { context.getSystemService(DownloadManager::class.java)?.remove(id) }
        }
        preferences.edit()
            .remove(DownloadVersionKey)
            .remove(DownloadIdKey)
            .commit()
    }

    private fun validateArchiveIdentity(context: Context, file: File, expectedVersion: String) {
        val packageManager = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(
            PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
        )
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw AppUpdateException(AppUpdateError.APK_INVALID, "下载文件不是有效的 APK")
        if (archive.packageName != context.packageName) {
            throw AppUpdateException(AppUpdateError.PACKAGE_MISMATCH, "下载 APK 的应用包名不匹配")
        }
        val archiveVersion = archive.versionName?.let(::normalizeStableVersion)
        if (archiveVersion != expectedVersion) {
            throw AppUpdateException(AppUpdateError.VERSION_MISMATCH, "下载 APK 的版本不匹配")
        }

        val installed = try {
            packageManager.getPackageInfo(context.packageName, flags)
        } catch (error: PackageManager.NameNotFoundException) {
            throw AppUpdateException(AppUpdateError.APK_INVALID, "无法读取当前应用身份", error)
        }
        val installedSigning = installed.signingInfo
            ?: throw AppUpdateException(AppUpdateError.APK_INVALID, "当前应用缺少签名证书")
        val archiveSigning = archive.signingInfo
            ?: throw AppUpdateException(AppUpdateError.APK_INVALID, "下载 APK 缺少签名证书")
        val installedCurrent = signatureDigests(installedSigning.apkContentsSigners.orEmpty())
        val archiveCurrent = signatureDigests(archiveSigning.apkContentsSigners.orEmpty())
        val archiveHistory = if (archiveSigning.hasMultipleSigners()) {
            emptySet()
        } else {
            signatureDigests(archiveSigning.signingCertificateHistory.orEmpty())
        }
        if (!signingLineageAllowsUpdate(
                installedCurrent = installedCurrent,
                archiveCurrent = archiveCurrent,
                archiveHistory = archiveHistory,
                installedHasMultipleSigners = installedSigning.hasMultipleSigners(),
                archiveHasMultipleSigners = archiveSigning.hasMultipleSigners(),
            )
        ) {
            throw AppUpdateException(AppUpdateError.SIGNATURE_MISMATCH, "下载 APK 的签名证书不匹配")
        }
    }

    private fun signatureDigests(signatures: Array<out android.content.pm.Signature>): Set<String> =
        signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }

    private fun parseStableVersion(raw: String): VersionParts? {
        val normalized = normalizeStableVersion(raw) ?: return null
        val parts = normalized.split('.').map(String::toInt)
        return VersionParts(parts[0], parts[1], parts[2])
    }

    private fun trustedHttpsUri(rawUrl: String, expectedHost: String): URI? {
        if (rawUrl.any(Char::isISOControl)) return null
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        return uri.takeIf {
            it.scheme == "https" &&
                it.host.equals(expectedHost, ignoreCase = true) &&
                it.port == -1 &&
                it.rawUserInfo == null &&
                it.rawQuery == null &&
                it.rawFragment == null &&
                it.isAbsolute
        }
    }

    private inline fun <T> appResult(
        fallbackCode: AppUpdateError,
        fallbackMessage: String,
        block: () -> T,
    ): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: AppUpdateException) {
        Result.failure(error)
    } catch (error: Exception) {
        Result.failure(AppUpdateException(fallbackCode, fallbackMessage, error))
    }

    private fun <T> updateFailure(code: AppUpdateError, message: String): Result<T> =
        Result.failure(AppUpdateException(code, message))

    private data class VersionParts(
        val major: Int,
        val minor: Int,
        val patch: Int,
    )
}
