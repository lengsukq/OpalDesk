package io.github.lengsukq.opaldesk.diagnostics

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.PeerFloatingAppService
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pure JVM-testable log level, immutable entry model, ring buffer, filtering, and diagnostic
 * report formatter shared by [OpalLogStore] and unit tests.
 */
enum class OpalLogLevel(val code: String, val label: String) {
    INFO("I", "信息"),
    WARN("W", "警告"),
    ERROR("E", "错误"),
}

data class OpalLogEntry(
    val seq: Long,
    val timestampMs: Long,
    val timeText: String,
    val level: OpalLogLevel,
    val tag: String,
    val message: String,
) {
    fun formatLine(): String = "$timeText ${level.code}/$tag: $message"
}

/**
 * Pure JVM-testable snapshot of runtime display, external monitor, network, and overlay state
 * included in diagnostic report headers to speed up root-cause triage.
 */
data class DiagnosticEnvironmentSnapshot(
    val displaySummary: String = "",
    val externalDisplaySummary: String = "",
    val networkSummary: String = "",
    val floatingOverlaySummary: String = "",
)

/**
 * Bounded thread-safe ring buffer for structured client diagnostic entries.
 */
class LogRingStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 600
        private const val MAX_MESSAGE_CHARS = 1200
    }

    private val lock = Any()
    private val ring = ArrayDeque<OpalLogEntry>(maxEntries.coerceAtLeast(16))
    private var nextSeq = 1L
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun append(
        level: OpalLogLevel,
        tag: String,
        message: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): OpalLogEntry {
        val cleanTag = tag.trim().ifEmpty { "App" }.take(24)
        val cleanMsg = sanitizeMessage(message)
        synchronized(lock) {
            val timeText = timeFmt.format(Date(timestampMs))
            val entry = OpalLogEntry(
                seq = nextSeq++,
                timestampMs = timestampMs,
                timeText = timeText,
                level = level,
                tag = cleanTag,
                message = cleanMsg,
            )
            while (ring.size >= maxEntries) {
                ring.removeFirst()
            }
            ring.addLast(entry)
            return entry
        }
    }

    fun appendRawPersistedLine(rawLine: String): OpalLogEntry? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null
        // Expected format: "HH:mm:ss.SSS I/Tag: message"
        val firstSpace = trimmed.indexOf(' ')
        val slash = if (firstSpace > 0) trimmed.indexOf('/', firstSpace + 1) else -1
        val colon = if (slash > 0) trimmed.indexOf(": ", slash + 1) else -1
        if (firstSpace in 5..15 && slash == firstSpace + 2 && colon > slash + 1) {
            val timeText = trimmed.substring(0, firstSpace)
            val level = when (trimmed[firstSpace + 1]) {
                'E' -> OpalLogLevel.ERROR
                'W' -> OpalLogLevel.WARN
                else -> OpalLogLevel.INFO
            }
            val tag = trimmed.substring(slash + 1, colon).take(24)
            val msg = sanitizeMessage(trimmed.substring(colon + 2))
            synchronized(lock) {
                val entry = OpalLogEntry(
                    seq = nextSeq++,
                    timestampMs = 0L,
                    timeText = timeText,
                    level = level,
                    tag = tag,
                    message = msg,
                )
                while (ring.size >= maxEntries) {
                    ring.removeFirst()
                }
                ring.addLast(entry)
                return entry
            }
        }
        return append(OpalLogLevel.INFO, "Saved", trimmed, timestampMs = 0L)
    }

    fun snapshot(): List<OpalLogEntry> = synchronized(lock) { ring.toList() }

    fun clear() = synchronized(lock) { ring.clear() }

    fun size(): Int = synchronized(lock) { ring.size }

    private fun sanitizeMessage(raw: String): String {
        val normalized = raw.replace('\r', ' ').replace('\n', ' ').trim()
        return if (normalized.length > MAX_MESSAGE_CHARS) {
            normalized.substring(0, MAX_MESSAGE_CHARS) + "..."
        } else {
            normalized
        }
    }
}

object OpalLogFilter {
    fun filterHelperEntries(
        entries: List<OpalLogEntry>,
        warningsAndErrorsOnly: Boolean,
        query: String,
    ): List<OpalLogEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        return entries.filter { entry ->
            if (warningsAndErrorsOnly && entry.level == OpalLogLevel.INFO) return@filter false
            if (q.isEmpty()) return@filter true
            entry.tag.lowercase(Locale.ROOT).contains(q) ||
                entry.message.lowercase(Locale.ROOT).contains(q) ||
                entry.timeText.contains(q)
        }
    }

    fun filterDaemonLines(
        daemonLogText: String,
        warningsAndErrorsOnly: Boolean,
        query: String,
    ): List<String> {
        if (daemonLogText.isBlank()) return emptyList()
        val q = query.trim().lowercase(Locale.ROOT)
        return daemonLogText.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .filter { line ->
                if (warningsAndErrorsOnly && !isDaemonWarningOrError(line)) return@filter false
                if (q.isEmpty()) return@filter true
                line.lowercase(Locale.ROOT).contains(q)
            }
            .toList()
    }

    fun isDaemonWarningOrError(line: String): Boolean {
        if (line.contains(" E/Daemon:")) return true
        val upper = line.uppercase(Locale.ROOT)
        return upper.contains("WARN") ||
            upper.contains("ERR") ||
            upper.contains("FAIL") ||
            upper.contains("EXCEPTION") ||
            upper.contains("FATAL") ||
            upper.contains("SIGSEGV") ||
            upper.contains("SIGABRT") ||
            upper.contains("UNCAUGHT") ||
            upper.contains("DIED") ||
            upper.contains("REJECT")
    }

    /**
     * Merges cached/live daemon lines with a newly captured [postmortemBlock] while replacing any
     * stale `[POSTMORTEM]` section from an earlier offline collection pass and keeping total lines
     * bounded with `[STARTUP]` / critical `[PREV_CRASH]` headers preserved.
     */
    fun mergeDaemonAndPostmortemLogs(
        existingDaemonText: String,
        postmortemBlock: String,
        maxLines: Int = 350,
    ): String {
        val cleanPost = postmortemBlock.trimEnd()
        if (cleanPost.isBlank()) return existingDaemonText.trimEnd()
        val postLines = cleanPost.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        val preLines = existingDaemonText.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith(PostmortemLogParser.POSTMORTEM_PREFIX) }
            .toList()
        if (preLines.isEmpty()) {
            return DiagnosticSanitizer.selectDaemonLinesPreservingStartup(postLines, maxLines).joinToString("\n")
        }
        val combined = preLines + postLines
        return DiagnosticSanitizer.selectDaemonLinesPreservingStartup(combined, maxLines).joinToString("\n")
    }

    fun inputBackendModeLabel(mode: Int): String = when (mode) {
        BridgeProtocol.InputBackend.AUTO -> "AUTO"
        BridgeProtocol.InputBackend.SYSTEM -> "SYSTEM"
        BridgeProtocol.InputBackend.UHID -> "UHID"
        else -> "UNKNOWN($mode)"
    }

    fun buildDiagnosticReport(
        appVersion: String,
        deviceSummary: String,
        status: DaemonStatus,
        scene: CompositorScene,
        remote: RemoteStreamStatus,
        helperEntries: List<OpalLogEntry>,
        daemonLogText: String,
        generatedAtText: String,
        env: DiagnosticEnvironmentSnapshot = DiagnosticEnvironmentSnapshot(),
    ): String = buildString(8192) {
        appendLine("=== OpalDesk 诊断日志报告 ===")
        appendLine("生成时间: $generatedAtText")
        appendLine("应用版本: v$appVersion")
        appendLine("设备信息: $deviceSummary")
        appendLine(
            "引擎状态: ${if (status.connected) "已连接" else "未连接"} | " +
                "uid=${status.daemonUid} | rootEnhanced=${status.rootEnhanced} | " +
                "VD=${status.activeVdCount}/${status.maxVdCount} | windows=${scene.windows.size}",
        )
        appendLine(
            "画布与输入: ${status.canvasWidth}x${status.canvasHeight}@${status.canvasDpi}dpi | " +
                "targetFps=${status.targetFps} | dpiScale=${status.windowDpiScalePct}% | " +
                "inputMode=${inputBackendModeLabel(status.inputBackendMode)} | " +
                "inject=${status.injectBackendLabel} (status=${status.injectStatus})",
        )
        appendLine(
            "投屏状态: connected=${remote.connected} | channel=${remote.channelLabel} | " +
                "wifi=${remote.wifiEnabled}:${remote.wifiPort} | lanIp=${remote.lanIp.ifBlank { "-" }}",
        )
        if (env.displaySummary.isNotBlank() || env.externalDisplaySummary.isNotBlank()) {
            appendLine(
                "主屏与外屏: ${env.displaySummary.ifBlank { "-" }} | " +
                    "ext=${env.externalDisplaySummary.ifBlank { "none" }}",
            )
        }
        if (env.networkSummary.isNotBlank() || env.floatingOverlaySummary.isNotBlank()) {
            appendLine(
                "网络与悬浮: net=${env.networkSummary.ifBlank { "-" }} | " +
                    "floating=${env.floatingOverlaySummary.ifBlank { "none" }}",
            )
        }
        if (scene.windows.isNotEmpty()) {
            appendLine("--- 当前窗口列表 (${scene.windows.size}) ---")
            scene.windows.forEach { w ->
                appendLine(
                    "wid=${w.windowId} pkg=${w.pkg} state=${w.state} displayId=${w.displayId} " +
                        "taskId=${w.taskId} content=${w.contentWidth}x${w.contentHeight}@${w.contentDpi}dpi " +
                        "pres=${w.presentation} focused=${w.focused}",
                )
            }
        }
        appendLine()
        appendLine("=== 客户端日志 (${helperEntries.size} 条) ===")
        if (helperEntries.isEmpty()) {
            appendLine("(无记录)")
        } else {
            helperEntries.forEach { appendLine(it.formatLine()) }
        }
        appendLine()
        val daemonLines = daemonLogText.lineSequence().filter { it.isNotBlank() }.toList()
        appendLine("=== 守护进程日志 (${daemonLines.size} 条) ===")
        if (daemonLines.isEmpty()) {
            appendLine("(守护进程未连接或暂无输出)")
        } else {
            daemonLines.forEach { appendLine(it) }
        }
    }
}

/**
 * Process-wide diagnostic log store combining an in-memory ring buffer, bounded rolling file
 * persistence (`filesDir/logs/opaldesk_helper.log`), and reactive UI flows for Helper + Daemon logs.
 */
object OpalLogStore {
    private const val LOGCAT_TAG_PREFIX = "OpalDesk"
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "opaldesk_helper.log"
    private const val DAEMON_LOG_FILE_NAME = "opaldesk_daemon_last.log"
    private const val MAX_FILE_BYTES = 192 * 1024L
    private const val MAX_DAEMON_FILE_BYTES = 128 * 1024L
    private const val KEEP_FILE_TAIL_LINES = 350
    private const val KEEP_DAEMON_FILE_TAIL_LINES = 350

    private val ring = LogRingStore(LogRingStore.DEFAULT_MAX_ENTRIES)
    private val daemonLock = Any()
    private val fileExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalLogDisk").apply { isDaemon = true }
    }

    private val _entriesFlow = MutableStateFlow<List<OpalLogEntry>>(emptyList())
    val entriesFlow: StateFlow<List<OpalLogEntry>> = _entriesFlow.asStateFlow()

    private val _daemonLogsFlow = MutableStateFlow("")
    val daemonLogsFlow: StateFlow<String> = _daemonLogsFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var lastUploadedDaemonTail: List<String> = emptyList()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var daemonLogFile: File? = null

    @Volatile
    var entryListener: ((OpalLogEntry) -> Unit)? = null

    fun recentTailLines(maxLines: Int = 45): List<String> =
        ring.snapshot().takeLast(maxLines.coerceAtLeast(1)).map { it.formatLine() }

    fun captureEnvironmentSnapshot(ctx: Context): DiagnosticEnvironmentSnapshot {
        val appCtx = ctx.applicationContext
        val displaySummary = runCatching {
            val dm = appCtx.resources.displayMetrics
            val orient = if (appCtx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                "LANDSCAPE"
            } else {
                "PORTRAIT"
            }
            "${dm.widthPixels}x${dm.heightPixels}@${String.format(Locale.US, "%.2f", dm.density)}x($orient)"
        }.getOrDefault("")

        val externalSummary = runCatching {
            val ext = ExternalTrackpadBridge.sessionFlow.value
            if (ext.isActive) {
                "${ext.mode}(d=${ext.displayId} ${ext.widthPx}x${ext.heightPx}@${ext.refreshRateHz}Hz " +
                    "wireless=${ext.isWirelessDisplay} cast=${ext.isRemoteCastDisplay})"
            } else {
                "none"
            }
        }.getOrDefault("none")

        val networkSummary = runCatching {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiEnabled = runCatching { wm?.isWifiEnabled == true }.getOrDefault(false)
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val wifiConn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val ethConn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            val localNetPerm = OpalPeerDiscovery.hasLocalNetworkPermission(appCtx)
            "wifiEnabled=$wifiEnabled wifiConn=$wifiConn eth=$ethConn localNetPerm=$localNetPerm"
        }.getOrDefault("")

        val floatingSummary = runCatching {
            val active = PeerFloatingAppService.activeFlow.value
            if (active != null) {
                "active(pkg=${active.appPackage} peer=${active.peerName})"
            } else {
                "none"
            }
        }.getOrDefault("none")

        return DiagnosticEnvironmentSnapshot(
            displaySummary = displaySummary,
            externalDisplaySummary = externalSummary,
            networkSummary = networkSummary,
            floatingOverlaySummary = floatingSummary,
        )
    }

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val dir = File(ctx.applicationContext.filesDir, LOG_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }
            val target = File(dir, LOG_FILE_NAME)
            val daemonTarget = File(dir, DAEMON_LOG_FILE_NAME)
            logFile = target
            daemonLogFile = daemonTarget
            fileExec.execute {
                loadTailFromDisk(target)
                loadDaemonTailFromDisk(daemonTarget)
            }
        }
    }

    fun i(tag: String, message: String) {
        safeLogcat(OpalLogLevel.INFO, tag, message, null)
        record(OpalLogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        safeLogcat(OpalLogLevel.WARN, tag, message, throwable)
        record(OpalLogLevel.WARN, tag, formatWithThrowable(message, throwable))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        safeLogcat(OpalLogLevel.ERROR, tag, message, throwable)
        record(OpalLogLevel.ERROR, tag, formatWithThrowable(message, throwable))
    }

    fun updateDaemonLogs(rawText: String) {
        val trimmed = rawText.trimEnd()
        if (trimmed.isBlank()) return
        val updated = synchronized(daemonLock) {
            val filtered = stripUploadedDaemonPrefix(trimmed, lastUploadedDaemonTail)
            if (filtered == _daemonLogsFlow.value) return@synchronized null
            _daemonLogsFlow.value = filtered
            filtered
        } ?: return
        persistDaemonTextAsync(updated)
    }

    fun mergePostmortemDaemonLogs(postmortemBlock: String) {
        val cleanPost = postmortemBlock.trimEnd()
        if (cleanPost.isBlank()) return
        val updated = synchronized(daemonLock) {
            val merged = OpalLogFilter.mergeDaemonAndPostmortemLogs(
                existingDaemonText = _daemonLogsFlow.value,
                postmortemBlock = cleanPost,
                maxLines = KEEP_DAEMON_FILE_TAIL_LINES,
            )
            val filtered = stripUploadedDaemonPrefix(merged, lastUploadedDaemonTail)
            if (filtered == _daemonLogsFlow.value) return@synchronized null
            _daemonLogsFlow.value = filtered
            filtered
        } ?: return
        persistDaemonTextAsync(updated)
    }

    /**
     * Clears local helper & cached daemon logs and records a daemon-log watermark after a confirmed
     * upload so reporting a second issue right afterwards only includes newly generated logs.
     */
    fun clearAfterConfirmedUpload(uploadedDaemonText: String) {
        val tailLines = uploadedDaemonText
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(5)
        synchronized(daemonLock) {
            if (tailLines.isNotEmpty()) {
                lastUploadedDaemonTail = tailLines
            }
        }
        clearDaemonLogs(resetWatermark = false)
        clearHelperLogs()
    }

    fun clearDaemonLogs(resetWatermark: Boolean = false) {
        synchronized(daemonLock) {
            if (resetWatermark) {
                lastUploadedDaemonTail = emptyList()
            }
            _daemonLogsFlow.value = ""
        }
        daemonLogFile?.let { file ->
            fileExec.execute {
                runCatching {
                    if (file.exists()) {
                        FileOutputStream(file, false).use { }
                    }
                }
            }
        }
    }

    internal fun stripUploadedDaemonPrefix(rawText: String, uploadedTail: List<String>): String {
        if (rawText.isBlank() || uploadedTail.isEmpty()) return rawText
        val lines = rawText.lines()
        val tailSize = uploadedTail.size
        for (idx in lines.size - tailSize downTo 0) {
            var matched = true
            for (j in 0 until tailSize) {
                if (lines[idx + j].trimEnd() != uploadedTail[j]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                val remaining = lines.subList(idx + tailSize, lines.size)
                return remaining.joinToString("\n").trimStart('\n').trimEnd()
            }
        }
        return rawText
    }

    fun clearHelperLogs() {
        ring.clear()
        _entriesFlow.value = emptyList()
        val file = logFile ?: return
        fileExec.execute {
            runCatching {
                if (file.exists()) {
                    FileOutputStream(file, false).use { }
                }
            }
        }
    }

    fun flushBlocking(timeoutMs: Long = 250L) {
        val latch = CountDownLatch(1)
        try {
            fileExec.execute { latch.countDown() }
            latch.await(timeoutMs.coerceAtLeast(10L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
        }
    }

    fun buildReport(
        appVersion: String,
        status: DaemonStatus,
        scene: CompositorScene,
        remote: RemoteStreamStatus,
        helperEntries: List<OpalLogEntry> = _entriesFlow.value,
        daemonLogText: String = _daemonLogsFlow.value,
        env: DiagnosticEnvironmentSnapshot = DiagnosticEnvironmentSnapshot(),
    ): String {
        val deviceSummary = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return OpalLogFilter.buildDiagnosticReport(
            appVersion = appVersion,
            deviceSummary = deviceSummary,
            status = status,
            scene = scene,
            remote = remote,
            helperEntries = helperEntries,
            daemonLogText = daemonLogText,
            generatedAtText = generatedAt,
            env = env,
        )
    }

    fun shareDiagnosticReport(ctx: Context, reportText: String): Boolean {
        return try {
            val outDir = File(ctx.cacheDir, LOG_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }
            // Keep cache tidy by pruning older exported reports
            outDir.listFiles()?.filter { it.name.startsWith("opaldesk_diag_") }?.forEach { old ->
                runCatching { old.delete() }
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(outDir, "opaldesk_diag_$stamp.txt")
            outFile.writeText(reportText, StandardCharsets.UTF_8)

            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", outFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "OpalDesk 诊断日志 ($stamp)")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "导出 OpalDesk 诊断日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
            true
        } catch (t: Throwable) {
            w("Logs", "Share diagnostic report failed", t)
            false
        }
    }

    private fun record(level: OpalLogLevel, tag: String, message: String) {
        val entry = ring.append(level, tag, message)
        _entriesFlow.value = ring.snapshot()
        entryListener?.let { listener ->
            runCatching { listener(entry) }
        }
        val file = logFile ?: return
        val line = entry.formatLine() + "\n"
        fileExec.execute {
            appendLineToDisk(file, line)
        }
    }

    private fun loadTailFromDisk(file: File) {
        if (!file.isFile || file.length() <= 0L) return
        runCatching {
            val lines = file.readLines(StandardCharsets.UTF_8)
            val tail = if (lines.size > KEEP_FILE_TAIL_LINES) {
                lines.subList(lines.size - KEEP_FILE_TAIL_LINES, lines.size)
            } else {
                lines
            }
            if (tail.isNotEmpty()) {
                val restored = LogRingStore(LogRingStore.DEFAULT_MAX_ENTRIES)
                tail.forEach { restored.appendRawPersistedLine(it) }
                val live = ring.snapshot()
                ring.clear()
                restored.snapshot().forEach { entry ->
                    ring.appendRawPersistedLine(entry.formatLine())
                }
                live.forEach { entry ->
                    ring.append(entry.level, entry.tag, entry.message, entry.timestampMs)
                }
                _entriesFlow.value = ring.snapshot()
            }
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText(tail.joinToString("\n") + "\n", StandardCharsets.UTF_8)
            }
        }
    }

    private fun loadDaemonTailFromDisk(file: File) {
        if (!file.isFile || file.length() <= 0L) return
        runCatching {
            val rawLines = file.readLines(StandardCharsets.UTF_8)
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }
            if (rawLines.isEmpty()) return
            val kept = DiagnosticSanitizer.selectDaemonLinesPreservingStartup(rawLines, KEEP_DAEMON_FILE_TAIL_LINES)
            val text = kept.joinToString("\n")
            synchronized(daemonLock) {
                if (_daemonLogsFlow.value.isBlank() && text.isNotBlank()) {
                    _daemonLogsFlow.value = stripUploadedDaemonPrefix(text, lastUploadedDaemonTail)
                }
            }
            if (file.length() > MAX_DAEMON_FILE_BYTES) {
                file.writeText(if (text.isNotEmpty()) "$text\n" else "", StandardCharsets.UTF_8)
            }
        }
    }

    private fun persistDaemonTextAsync(text: String) {
        val file = daemonLogFile ?: return
        fileExec.execute {
            runCatching {
                val lines = text.lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                    .toList()
                val kept = DiagnosticSanitizer.selectDaemonLinesPreservingStartup(lines, KEEP_DAEMON_FILE_TAIL_LINES)
                val out = kept.joinToString("\n")
                file.writeText(if (out.isNotEmpty()) "$out\n" else "", StandardCharsets.UTF_8)
            }
        }
    }

    private fun appendLineToDisk(file: File, line: String) {
        runCatching {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val lines = file.readLines(StandardCharsets.UTF_8)
                val kept = if (lines.size > KEEP_FILE_TAIL_LINES) {
                    lines.subList(lines.size - KEEP_FILE_TAIL_LINES, lines.size)
                } else {
                    lines
                }
                file.writeText(kept.joinToString("\n") + "\n", StandardCharsets.UTF_8)
            }
            FileOutputStream(file, true).use { fos ->
                fos.write(line.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    private fun formatWithThrowable(message: String, t: Throwable?): String {
        if (t == null) return message
        val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return "$message (${t.javaClass.simpleName}: $detail)"
    }

    private fun safeLogcat(level: OpalLogLevel, tag: String, message: String, t: Throwable?) {
        val fullTag = "$LOGCAT_TAG_PREFIX/$tag"
        try {
            when (level) {
                OpalLogLevel.INFO -> Log.i(fullTag, message)
                OpalLogLevel.WARN -> if (t != null) Log.w(fullTag, message, t) else Log.w(fullTag, message)
                OpalLogLevel.ERROR -> if (t != null) Log.e(fullTag, message, t) else Log.e(fullTag, message)
            }
        } catch (_: Throwable) {
            // Safe no-op under plain JVM unit tests
        }
    }
}
