package io.github.lengsukq.opaldesk.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.lengsukq.opaldesk.AdbBootstrap
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.adb.AdbProcessRunner
import io.github.lengsukq.opaldesk.adb.DaemonBootstrapScripts
import io.github.lengsukq.opaldesk.adb.ShizukuBootstrap
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure JVM-testable parser and privacy filter for read-only shell post-mortem diagnostics
 * (`opaldesk.log`, `opaldesk.prev.log`, and `logcat -b crash` filtered strictly to OpalDesk
 * daemon/app crashes so unrelated third-party app crashes are never collected).
 */
object PostmortemLogParser {
    const val MARKER_ALIVE = "===OPAL_DAEMON_ALIVE==="
    const val MARKER_CRASH_LOGCAT = "===OPAL_CRASH_LOGCAT==="
    const val MARKER_CUR_LOG = "===OPAL_CUR_LOG==="
    const val MARKER_PREV_LOG = "===OPAL_PREV_LOG==="
    const val CLEAN_SHUTDOWN_MARKER = "OPALDESK_DAEMON_SHUTDOWN_CLEAN"
    const val POSTMORTEM_PREFIX = "[POSTMORTEM]"

    private const val TOMBSTONE_BANNER = "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***"

    data class ParsedSections(
        val aliveFlag: String = "",
        val crashLogcatLines: List<String> = emptyList(),
        val curLogLines: List<String> = emptyList(),
        val prevLogLines: List<String> = emptyList(),
    )

    fun parseSections(rawOutput: String): ParsedSections {
        if (rawOutput.isBlank()) return ParsedSections()
        var currentSection = ""
        val aliveLines = ArrayList<String>()
        val crashLines = ArrayList<String>()
        val curLines = ArrayList<String>()
        val prevLines = ArrayList<String>()

        rawOutput.lineSequence().forEach { raw ->
            val trimmed = raw.trimEnd()
            when (trimmed.trim()) {
                MARKER_ALIVE -> currentSection = MARKER_ALIVE
                MARKER_CRASH_LOGCAT -> currentSection = MARKER_CRASH_LOGCAT
                MARKER_CUR_LOG -> currentSection = MARKER_CUR_LOG
                MARKER_PREV_LOG -> currentSection = MARKER_PREV_LOG
                else -> if (trimmed.isNotBlank()) {
                    when (currentSection) {
                        MARKER_ALIVE -> aliveLines.add(trimmed.trim())
                        MARKER_CRASH_LOGCAT -> crashLines.add(trimmed)
                        MARKER_CUR_LOG -> curLines.add(trimmed)
                        MARKER_PREV_LOG -> prevLines.add(trimmed)
                    }
                }
            }
        }
        return ParsedSections(
            aliveFlag = aliveLines.firstOrNull().orEmpty(),
            crashLogcatLines = crashLines,
            curLogLines = curLines,
            prevLogLines = prevLines,
        )
    }

    /**
     * Extracts only crash stanzas relevant to OpalDesk / its shell `app_process` daemon so
     * unrelated third-party app crash entries from the circular crash buffer are omitted.
     */
    fun filterRelevantCrashLogcat(lines: List<String>, maxLines: Int = 55): List<String> {
        if (lines.isEmpty() || maxLines <= 0) return emptyList()
        val stanzas = ArrayList<MutableList<String>>()
        var current = ArrayList<String>()

        fun flushStanza() {
            if (current.isNotEmpty()) {
                stanzas.add(current)
                current = ArrayList()
            }
        }

        for (line in lines) {
            val clean = line.trimEnd()
            if (clean.isBlank()) continue
            val startsNewStanza = clean.contains("--------- beginning of crash") ||
                clean.contains("FATAL EXCEPTION") ||
                clean.contains(TOMBSTONE_BANNER) ||
                clean.contains("Fatal signal ")
            if (startsNewStanza && current.isNotEmpty()) {
                // Keep "Fatal signal" grouped inside its enclosing DEBUG tombstone if banner just opened it.
                val insideNewTombstoneHeader = current.size <= 6 &&
                    current.any { it.contains(TOMBSTONE_BANNER) } &&
                    clean.contains("Fatal signal ")
                if (!insideNewTombstoneHeader) {
                    flushStanza()
                }
            }
            if (!clean.contains("--------- beginning of crash")) {
                current.add(clean)
            }
        }
        flushStanza()

        val selected = ArrayList<String>()
        for (stanza in stanzas) {
            if (isRelevantCrashStanza(stanza)) {
                for (ln in stanza) {
                    selected.add(ln)
                }
            }
        }
        if (selected.size <= maxLines) return selected
        return selected.takeLast(maxLines)
    }

    internal fun isRelevantCrashStanza(stanza: List<String>): Boolean {
        val joined = stanza.joinToString("\n").lowercase(Locale.ROOT)
        return joined.contains("opaldesk") ||
            joined.contains("opaldaemon") ||
            joined.contains("extctlsocket") ||
            joined.contains("/data/local/tmp/opaldesk.jar") ||
            joined.contains("/system/bin/app_process") ||
            joined.contains("app_process64") ||
            joined.contains("app_process32") ||
            joined.contains("uid 2000") ||
            joined.contains("uid=2000")
    }

    fun formatReportBlock(
        rawOutput: String,
        channel: String,
        triggerReason: String,
    ): String {
        val parsed = parseSections(rawOutput)
        val filteredCrash = filterRelevantCrashLogcat(parsed.crashLogcatLines, maxLines = 55)
        val curTail = parsed.curLogLines.takeLast(60)
        val prevIsClean = hasCleanShutdownWithoutTrailingUncaught(parsed.prevLogLines)
        val prevTail = if (prevIsClean) emptyList() else parsed.prevLogLines.takeLast(40)

        if (filteredCrash.isEmpty() && curTail.isEmpty() && prevTail.isEmpty()) {
            return ""
        }

        val out = buildString {
            val aliveDesc = parsed.aliveFlag.ifBlank { "unknown" }
            appendLine("$POSTMORTEM_PREFIX collected trigger=$triggerReason channel=$channel daemonAlive=$aliveDesc")
            if (filteredCrash.isNotEmpty()) {
                appendLine("$POSTMORTEM_PREFIX --- logcat -b crash (OpalDesk/app_process) ---")
                filteredCrash.forEach { appendLine("$POSTMORTEM_PREFIX $it") }
            }
            if (curTail.isNotEmpty()) {
                appendLine("$POSTMORTEM_PREFIX --- /data/local/tmp/opaldesk.log tail ---")
                curTail.forEach { appendLine("$POSTMORTEM_PREFIX $it") }
            }
            if (prevTail.isNotEmpty()) {
                appendLine("$POSTMORTEM_PREFIX --- /data/local/tmp/opaldesk.prev.log tail ---")
                prevTail.forEach { appendLine("$POSTMORTEM_PREFIX $it") }
            }
        }.trimEnd()

        return DiagnosticSanitizer.sanitizeText(out)
    }

    private fun hasCleanShutdownWithoutTrailingUncaught(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        var lastClean = -1
        var lastUncaught = -1
        lines.forEachIndexed { idx, line ->
            if (line.contains(CLEAN_SHUTDOWN_MARKER)) lastClean = idx
            if (line.contains("OPALDESK_UNCAUGHT_EXCEPTION") || line.contains("FATAL")) lastUncaught = idx
        }
        return lastClean >= 0 && lastClean > lastUncaught
    }
}

/**
 * Best-effort read-only collector that harvests disk logs (`opaldesk.log` / `opaldesk.prev.log`)
 * and filtered `logcat -b crash` via an already-authorized local shell channel (Shizuku first,
 * then saved Wireless ADB endpoint, then opted-in Root) when the daemon crashes mid-session.
 */
object DaemonPostmortemCollector {
    private const val TAG = "Postmortem"
    private const val COOLDOWN_MS = 10_000L
    private const val DEATH_FLUSH_DELAY_MS = 180L

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalPostmortem").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)
    private val lastAttemptMs = AtomicLong(0L)

    @Volatile
    private var collectedForDisconnectEpisode = false

    fun onDaemonBinderConnected() {
        collectedForDisconnectEpisode = false
    }

    fun onUnexpectedDaemonDeathAsync(ctx: Context?) {
        val appCtx = ctx?.applicationContext ?: return
        if (BridgeService.sUserStopping) return
        collectAsyncInternal(
            appCtx = appCtx,
            triggerReason = "link_to_death",
            delayMs = DEATH_FLUSH_DELAY_MS,
            force = true,
            onDone = null,
        )
    }

    fun collectIfDisconnectedAsync(
        ctx: Context?,
        triggerReason: String,
        force: Boolean = false,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val appCtx = ctx?.applicationContext ?: run {
            onDone?.invoke(false)
            return
        }
        if (BridgeClient.getStatus().connected) {
            onDone?.invoke(false)
            return
        }
        if (!force && collectedForDisconnectEpisode) {
            onDone?.invoke(false)
            return
        }
        collectAsyncInternal(
            appCtx = appCtx,
            triggerReason = triggerReason,
            delayMs = 0L,
            force = force,
            onDone = onDone,
        )
    }

    private fun collectAsyncInternal(
        appCtx: Context,
        triggerReason: String,
        delayMs: Long,
        force: Boolean,
        onDone: ((Boolean) -> Unit)?,
    ) {
        val now = System.currentTimeMillis()
        val prevAttempt = lastAttemptMs.get()
        if (!force && now - prevAttempt < COOLDOWN_MS) {
            onDone?.invoke(false)
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            onDone?.invoke(false)
            return
        }
        lastAttemptMs.set(now)
        exec.execute {
            var captured = false
            try {
                if (delayMs > 0L) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                }
                if (BridgeClient.getStatus().connected) {
                    return@execute
                }
                val cmd = DaemonBootstrapScripts.buildPostmortemDiagCommand()
                val rawResult = runBestEffortShell(appCtx, cmd)
                if (rawResult != null) {
                    val (channel, rawOut) = rawResult
                    val formatted = PostmortemLogParser.formatReportBlock(
                        rawOutput = rawOut,
                        channel = channel,
                        triggerReason = triggerReason,
                    )
                    if (formatted.isNotBlank()) {
                        OpalLogStore.mergePostmortemDaemonLogs(formatted)
                        collectedForDisconnectEpisode = true
                        captured = true
                        OpalLogStore.i(TAG, "Captured post-mortem daemon diagnostics via $channel ($triggerReason)")
                    }
                }
            } catch (t: Throwable) {
                OpalLogStore.w(TAG, "Post-mortem collection skipped", t)
            } finally {
                inFlight.set(false)
                dispatchCallback(onDone, captured)
            }
        }
    }

    private fun dispatchCallback(onDone: ((Boolean) -> Unit)?, captured: Boolean) {
        if (onDone == null) return
        try {
            Handler(Looper.getMainLooper()).post { onDone(captured) }
        } catch (_: Throwable) {
            onDone(captured)
        }
    }

    private fun runBestEffortShell(appCtx: Context, cmd: String): Pair<String, String>? {
        if (ShizukuBootstrap.isReadyAndGranted()) {
            val res = ShizukuBootstrap.execShell(3500L, null, cmd)
            if (res.output.isNotBlank()) {
                return "shizuku" to res.output
            }
        }
        val wirelessOut = AdbBootstrap.tryRunPostmortemOverWirelessSync(appCtx, cmd)
        if (!wirelessOut.isNullOrBlank()) {
            return "wireless_adb" to wirelessOut
        }
        val allowRootProbe = AdbBootstrap.wasRootActivatedRecently() || AppPrefs.isAutoRootEnabled(appCtx)
        if (allowRootProbe && AdbProcessRunner.hasSuBinaryHint()) {
            val suRes = AdbProcessRunner.runSu(3500L, cmd)
            if (suRes.output.isNotBlank()) {
                return "root" to suRes.output
            }
        }
        return null
    }
}
