package io.github.lengsukq.opaldesk.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updater backed by GitHub Releases API:
 * - Silent startup check (throttled every 6 hours, honors user preference & ignored tag)
 * - Manual check from Settings or header badge
 * - HTTPS allowlist-verified streaming APK download with live progress & cancel support
 * - Fail-closed SHA-256 integrity verification (when GitHub release digest is present) +
 *   packageName validation before handing off to Android's system PackageInstaller via FileProvider.
 */
object AppUpdateManager {
    private const val TAG = "OpalAppUpdate"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 18_000
    private const val MAX_REDIRECTS = 5
    private const val SILENT_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val MIN_VALID_APK_BYTES = 512L * 1024L

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data class UpToDate(val currentVersion: String, val checkedAtMs: Long) : UpdateState()
        data class UpdateAvailable(val release: AppUpdateLogic.ReleaseInfo) : UpdateState()
        data class Downloading(
            val release: AppUpdateLogic.ReleaseInfo,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : UpdateState()
        data class ReadyToInstall(
            val release: AppUpdateLogic.ReleaseInfo,
            val apkFilePath: String,
        ) : UpdateState()
        data class Error(
            val message: String,
            val release: AppUpdateLogic.ReleaseInfo? = null,
        ) : UpdateState()
    }

    private val _stateFlow = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val stateFlow: StateFlow<UpdateState> = _stateFlow.asStateFlow()

    private val checkInFlight = AtomicBoolean(false)
    private val downloadInFlight = AtomicBoolean(false)
    private val downloadCancelFlag = AtomicBoolean(false)
    @Volatile
    private var startupCheckScheduled = false

    fun getCurrentVersionName(ctx: Context): String {
        return try {
            val pm = ctx.packageManager
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, 0)
            }
            pi.versionName?.trim().takeUnless { it.isNullOrEmpty() } ?: "1.0.3"
        } catch (_: Throwable) {
            "1.0.3"
        }
    }

    /**
     * Runs at most once per process launch when auto-check is enabled and the throttle window has elapsed.
     * Never surfaces network errors to the user on silent startup checks.
     */
    fun maybeSilentCheckOnStartup(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (startupCheckScheduled) return
        if (!AppPrefs.isAutoCheckUpdateEnabled(appCtx)) return
        val now = System.currentTimeMillis()
        val last = AppPrefs.getLastUpdateCheckMs(appCtx)
        if (now - last < SILENT_CHECK_INTERVAL_MS && _stateFlow.value !is UpdateState.UpdateAvailable) {
            return
        }
        startupCheckScheduled = true
        checkForUpdates(appCtx, userInitiated = false)
    }

    fun checkForUpdates(
        ctx: Context,
        userInitiated: Boolean,
        bypassIgnoredTag: Boolean = false,
    ) {
        val appCtx = ctx.applicationContext
        if (downloadInFlight.get()) return
        if (!checkInFlight.compareAndSet(false, true)) return
        val prevState = _stateFlow.value
        if (userInitiated || bypassIgnoredTag || prevState is UpdateState.Idle || prevState is UpdateState.Error) {
            _stateFlow.value = UpdateState.Checking
        }

        Thread({
            try {
                cleanupStaleCachedApks(appCtx)
                val currentVersion = getCurrentVersionName(appCtx)
                val latest = fetchLatestRelease(currentVersion)
                val now = System.currentTimeMillis()
                AppPrefs.setLastUpdateCheckMs(appCtx, now)

                if (latest == null) {
                    _stateFlow.value = if (userInitiated) {
                        UpdateState.Error("未找到可用的正式版发布信息，请稍后再试")
                    } else {
                        prevState
                    }
                    return@Thread
                }

                if (AppUpdateLogic.isNewerVersion(currentVersion, latest.versionName)) {
                    val ignoredTag = AppPrefs.getIgnoredUpdateTag(appCtx)
                    if (!userInitiated && !bypassIgnoredTag && ignoredTag.equals(latest.tagName, ignoreCase = true)) {
                        _stateFlow.value = UpdateState.Idle
                        return@Thread
                    }
                    // Reuse an already downloaded & verified APK if present in cache
                    val cachedFile = resolveCachedApkFile(appCtx, latest)
                    if (cachedFile != null && verifyApkFile(appCtx, cachedFile, latest) == null) {
                        _stateFlow.value = UpdateState.ReadyToInstall(latest, cachedFile.absolutePath)
                    } else {
                        _stateFlow.value = UpdateState.UpdateAvailable(latest)
                    }
                } else {
                    _stateFlow.value = UpdateState.UpToDate(currentVersion, now)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Update check failed: ${t.message}")
                _stateFlow.value = if (userInitiated) {
                    UpdateState.Error("检查更新失败：${friendlyNetworkError(t)}")
                } else {
                    prevState
                }
            } finally {
                checkInFlight.set(false)
            }
        }, "OpalUpdateCheck").start()
    }

    fun startDownload(ctx: Context, release: AppUpdateLogic.ReleaseInfo) {
        val appCtx = ctx.applicationContext
        if (release.apkDownloadUrl.isBlank() || !AppUpdateLogic.isAllowedReleaseUrl(release.apkDownloadUrl)) {
            _stateFlow.value = UpdateState.Error("该版本未提供有效的安装包下载链接，请前往 GitHub 页面下载", release)
            return
        }
        val route = AppUpdateLogic.DownloadRoute.fromIndex(AppPrefs.getUpdateDownloadRoute(appCtx))
        val effectiveUrl = AppUpdateLogic.buildDownloadUrl(release.apkDownloadUrl, route)
        if (!downloadInFlight.compareAndSet(false, true)) return
        downloadCancelFlag.set(false)
        _stateFlow.value = UpdateState.Downloading(release, 0L, release.apkSizeBytes)

        Thread({
            val updatesDir = File(appCtx.cacheDir, "updates").apply { mkdirs() }
            val safeName = release.apkAssetName.ifBlank { "OpalDesk_${release.tagName}.apk" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val finalFile = File(updatesDir, safeName)
            val partFile = File(updatesDir, "$safeName.part")

            var conn: HttpURLConnection? = null
            try {
                if (partFile.exists()) partFile.delete()
                conn = openValidatedHttpsGet(
                    urlStr = effectiveUrl,
                    accept = "application/octet-stream",
                    userAgent = "OpalDesk/${getCurrentVersionName(appCtx)}",
                    allowProxyRoute = route != AppUpdateLogic.DownloadRoute.DIRECT,
                )
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                val contentLength = conn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastEmitMs = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            if (downloadCancelFlag.get()) {
                                throw InterruptedException("cancelled")
                            }
                            val read = input.read(buf)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buf, 0, read)
                            digest.update(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 120L || (contentLength > 0L && downloaded >= contentLength)) {
                                lastEmitMs = now
                                _stateFlow.value = UpdateState.Downloading(release, downloaded, contentLength)
                            }
                        }
                        output.flush()
                    }
                }

                // Verify SHA-256 if GitHub API supplied one
                if (!release.apkSha256.isNullOrBlank()) {
                    val actualHex = digest.digest().joinToString("") { b -> "%02x".format(b) }
                    if (!actualHex.equals(release.apkSha256, ignoreCase = true)) {
                        partFile.delete()
                        _stateFlow.value = UpdateState.Error("安装包 SHA-256 校验未通过，请重新下载", release)
                        return@Thread
                    }
                }

                // Verify minimum size & packageName integrity
                val validationError = verifyApkFile(appCtx, partFile, release)
                if (validationError != null) {
                    partFile.delete()
                    _stateFlow.value = UpdateState.Error(validationError, release)
                    return@Thread
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                _stateFlow.value = UpdateState.ReadyToInstall(release, finalFile.absolutePath)
            } catch (_: InterruptedException) {
                partFile.delete()
                _stateFlow.value = UpdateState.UpdateAvailable(release)
            } catch (t: Throwable) {
                partFile.delete()
                Log.w(TAG, "APK download failed: ${t.message}")
                _stateFlow.value = UpdateState.Error("下载失败：${friendlyNetworkError(t)}", release)
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Throwable) {
                }
                downloadInFlight.set(false)
            }
        }, "OpalUpdateDownload").start()
    }

    fun cancelDownload() {
        downloadCancelFlag.set(true)
    }

    fun ignoreRelease(ctx: Context, tagName: String) {
        AppPrefs.setIgnoredUpdateTag(ctx.applicationContext, tagName)
        _stateFlow.value = UpdateState.Idle
    }

    /**
     * Launches Android's system PackageInstaller for the downloaded APK, requesting
     * "Install unknown apps" permission first when not yet granted.
     */
    fun installDownloadedApk(ctx: Context, apkPath: String): Boolean {
        val file = File(apkPath)
        if (!file.isFile || file.length() < MIN_VALID_APK_BYTES) {
            Toast.makeText(ctx, "安装包文件不存在或已失效，请重新下载", Toast.LENGTH_SHORT).show()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                ctx,
                "请先开启「允许安装来自此来源的应用」，返回后再点「立即安装」",
                Toast.LENGTH_LONG,
            ).show()
            try {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(permIntent)
            } catch (_: Throwable) {
            }
            return false
        }
        return try {
            val contentUri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                file,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(installIntent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch PackageInstaller", t)
            Toast.makeText(ctx, "无法调起系统安装器：${t.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun openReleaseInBrowser(ctx: Context, url: String = AppUpdateLogic.ALL_RELEASES_WEB_URL) {
        val safeUrl = if (AppUpdateLogic.isAllowedReleaseUrl(url)) url else AppUpdateLogic.ALL_RELEASES_WEB_URL
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
            Toast.makeText(ctx, "未找到可用浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchLatestRelease(currentVersion: String): AppUpdateLogic.ReleaseInfo? {
        val userAgent = "OpalDesk/$currentVersion"
        var conn: HttpURLConnection? = null
        try {
            conn = openValidatedHttpsGet(
                urlStr = AppUpdateLogic.LATEST_RELEASE_API_URL,
                accept = "application/vnd.github+json",
                userAgent = userAgent,
            )
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return AppUpdateLogic.parseLatestReleaseJson(body)
            }
            if (code == 403 || code == 429) {
                return fetchLatestReleaseViaWebRedirect(userAgent)
            }
            if (code == 404) return null
            throw IllegalStateException("GitHub API HTTP $code")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun fetchLatestReleaseViaWebRedirect(userAgent: String): AppUpdateLogic.ReleaseInfo? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(AppUpdateLogic.LATEST_RELEASE_WEB_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location").orEmpty()
                return AppUpdateLogic.buildFallbackReleaseFromTagRedirect(loc)
            }
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun openValidatedHttpsGet(
        urlStr: String,
        accept: String,
        userAgent: String,
        allowProxyRoute: Boolean = false,
    ): HttpURLConnection {
        var current = urlStr
        for (hop in 0..MAX_REDIRECTS) {
            val allowed = if (allowProxyRoute) {
                current.startsWith("https://", ignoreCase = true)
            } else {
                AppUpdateLogic.isAllowedReleaseUrl(current)
            }
            if (!allowed) {
                throw SecurityException("Blocked non-HTTPS or untrusted host")
            }
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", userAgent)
            }
            val code = conn.responseCode
            if (code in 301..308 && code != 304 && code != 305) {
                val location = conn.getHeaderField("Location").orEmpty()
                conn.disconnect()
                if (location.isBlank()) throw IllegalStateException("Empty redirect location")
                current = URL(URL(current), location).toString()
                continue
            }
            return conn
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun resolveCachedApkFile(ctx: Context, release: AppUpdateLogic.ReleaseInfo): File? {
        val updatesDir = File(ctx.cacheDir, "updates")
        if (!updatesDir.isDirectory) return null
        val safeName = release.apkAssetName.ifBlank { "OpalDesk_${release.tagName}.apk" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(updatesDir, safeName)
        return if (file.isFile) file else null
    }

    private fun verifyApkFile(
        ctx: Context,
        file: File,
        release: AppUpdateLogic.ReleaseInfo,
    ): String? {
        if (!file.isFile || file.length() < MIN_VALID_APK_BYTES) {
            return "安装包下载不完整，请重试"
        }
        if (release.apkSizeBytes > 0L && file.length() != release.apkSizeBytes) {
            return "安装包大小校验异常，请重试"
        }
        val info = try {
            val pm = ctx.packageManager
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, 0)
            }
        } catch (_: Throwable) {
            null
        } ?: return "安装包解析失败，文件可能已损坏"
        if (info.packageName != ctx.packageName) {
            return "安装包包名不匹配（${info.packageName}），已阻止安装"
        }
        return null
    }

    private fun cleanupStaleCachedApks(ctx: Context) {
        try {
            val updatesDir = File(ctx.cacheDir, "updates")
            if (!updatesDir.isDirectory) return
            val cur = getCurrentVersionName(ctx)
            updatesDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".part")) {
                    f.delete()
                } else if (f.name.endsWith(".apk")) {
                    val m = Regex("v(\\d+\\.\\d+(?:\\.\\d+)?)").find(f.name)
                    val ver = m?.groupValues?.getOrNull(1)
                    if (ver != null && !AppUpdateLogic.isNewerVersion(cur, ver)) {
                        f.delete()
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun friendlyNetworkError(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            t is java.net.UnknownHostException -> "无法解析服务器域名，可切换下方代理线路后重试"
            t is java.net.SocketTimeoutException -> "连接超时，可切换下方代理一 / 代理二或用浏览器下载"
            t is SecurityException -> "下载链接安全校验未通过"
            raw.isNotBlank() -> raw.take(80)
            else -> "网络连接异常"
        }
    }
}
