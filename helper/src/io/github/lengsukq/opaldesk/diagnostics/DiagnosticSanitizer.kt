package io.github.lengsukq.opaldesk.diagnostics

import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/**
 * Pure JVM-testable diagnostic log sanitizer and GZIP compressor.
 * Redacts local filesystem user directories, auth tokens, Sentry/GitHub secrets,
 * ADB pairing codes, and private LAN IP octets before uploading logs.
 */
object DiagnosticSanitizer {
    const val DEFAULT_MAX_HELPER_ENTRIES = 200
    const val DEFAULT_MAX_DAEMON_LINES = 200
    const val DEFAULT_MAX_GZIP_BYTES = 512 * 1024

    private val USER_HOME_PATH_REGEX = Regex("""(/(?:Users|home)/)([^/\s"'`]+)""")
    private val ANDROID_STORAGE_PATH_REGEX = Regex("""(/storage/emulated/)\d+""")
    private val ANDROID_DATA_USER_REGEX = Regex("""(/data/user/)\d+""")
    private val BEARER_TOKEN_REGEX = Regex("""(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]{6,}""")
    private val SENTRY_TOKEN_REGEX = Regex("""\bsntry[a-zA-Z0-9_-]{8,}\b""")
    private val GITHUB_TOKEN_REGEX = Regex("""\b(?:gh[pousr]_[A-Za-z0-9_]{10,}|github_pat_[A-Za-z0-9_]{10,})\b""")
    private val KEY_VALUE_SECRET_REGEX = Regex(
        """(?i)\b(token|auth_?token|access_?token|secret|password|passwd|api_?key|authorization|pairing_?code)\b(\s*[:=]\s*)([^\s,;"'&}\]]+)""",
    )
    private val ADB_PAIR_COMMAND_REGEX = Regex("""(?i)\b(adb\s+pair\s+\S+\s+)(\d{6})\b""")
    private val LAN_IP_192_REGEX = Regex("""\b(192\.168)\.\d{1,3}\.\d{1,3}\b""")
    private val LAN_IP_10_REGEX = Regex("""\b(10)\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")
    private val LAN_IP_172_REGEX = Regex("""\b(172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b""")

    fun sanitizeText(raw: String): String {
        if (raw.isEmpty()) return raw
        var out = raw
        out = USER_HOME_PATH_REGEX.replace(out, "$1<redacted>")
        out = ANDROID_STORAGE_PATH_REGEX.replace(out, "$1<user>")
        out = ANDROID_DATA_USER_REGEX.replace(out, "$1<uid>")
        out = BEARER_TOKEN_REGEX.replace(out, "$1<redacted>")
        out = SENTRY_TOKEN_REGEX.replace(out, "<redacted_sentry_token>")
        out = GITHUB_TOKEN_REGEX.replace(out, "<redacted_gh_token>")
        out = ADB_PAIR_COMMAND_REGEX.replace(out, "$1<redacted_pin>")
        out = KEY_VALUE_SECRET_REGEX.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}<redacted>"
        }
        out = LAN_IP_192_REGEX.replace(out, "$1.*.*")
        out = LAN_IP_10_REGEX.replace(out, "$1.*.*.*")
        out = LAN_IP_172_REGEX.replace(out, "$1.*.*")
        return out
    }

    fun buildSanitizedReportText(
        appVersion: String,
        deviceSummary: String,
        reporterName: String,
        problemDescription: String,
        status: DaemonStatus,
        scene: CompositorScene,
        remote: RemoteStreamStatus,
        helperEntries: List<OpalLogEntry>,
        daemonLogText: String,
        generatedAtText: String,
        maxHelperEntries: Int = DEFAULT_MAX_HELPER_ENTRIES,
        maxDaemonLines: Int = DEFAULT_MAX_DAEMON_LINES,
        env: DiagnosticEnvironmentSnapshot = DiagnosticEnvironmentSnapshot(),
    ): String {
        val recentHelper = if (helperEntries.size > maxHelperEntries) {
            helperEntries.takeLast(maxHelperEntries)
        } else {
            helperEntries
        }
        val daemonLines = daemonLogText.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        val recentDaemon = selectDaemonLinesPreservingStartup(daemonLines, maxDaemonLines)
        val baseReport = OpalLogFilter.buildDiagnosticReport(
            appVersion = appVersion,
            deviceSummary = deviceSummary,
            status = status,
            scene = scene,
            remote = remote,
            helperEntries = recentHelper,
            daemonLogText = recentDaemon.joinToString("\n"),
            generatedAtText = generatedAtText,
            env = env,
        )
        val header = buildString {
            appendLine("=== 用户反馈信息 ===")
            appendLine("用户名: ${reporterName.trim().ifEmpty { "(未填写)" }}")
            appendLine("问题描述: ${problemDescription.trim().ifEmpty { "(未填写)" }}")
            appendLine()
        }
        return sanitizeText(header + baseReport)
    }

    internal fun selectDaemonLinesPreservingStartup(
        daemonLines: List<String>,
        maxDaemonLines: Int,
    ): List<String> {
        if (maxDaemonLines <= 0) return emptyList()
        if (daemonLines.size <= maxDaemonLines) return daemonLines
        val sticky = daemonLines.filter { isStickyStartupLine(it) }.take(minOf(4, maxDaemonLines / 4))
        if (sticky.isEmpty()) return daemonLines.takeLast(maxDaemonLines)
        val tailBudget = (maxDaemonLines - sticky.size).coerceAtLeast(0)
        val tail = if (tailBudget > 0) daemonLines.takeLast(tailBudget) else emptyList()
        val tailSet = tail.toHashSet()
        val missingSticky = sticky.filterNot { it in tailSet }
        if (missingSticky.isEmpty()) return daemonLines.takeLast(maxDaemonLines)
        val trimmedTail = daemonLines.takeLast((maxDaemonLines - missingSticky.size).coerceAtLeast(0))
        return missingSticky + trimmedTail
    }

    private fun isStickyStartupLine(line: String): Boolean =
        line.contains("[STARTUP]") || line.contains("OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED")

    fun gzipCompress(
        text: String,
        maxCompressedBytes: Int = DEFAULT_MAX_GZIP_BYTES,
    ): ByteArray {
        val limit = maxCompressedBytes.coerceAtLeast(1024)
        var candidate = text
        var compressed = gzipRaw(candidate.toByteArray(StandardCharsets.UTF_8))
        var attempts = 0
        while (compressed.size > limit && candidate.length > 512 && attempts < 6) {
            val keepChars = (candidate.length * 0.65).toInt().coerceAtLeast(512)
            candidate = "[... truncated to fit upload size limit ...]\n" + candidate.takeLast(keepChars)
            compressed = gzipRaw(candidate.toByteArray(StandardCharsets.UTF_8))
            attempts++
        }
        return compressed
    }

    private fun gzipRaw(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream((bytes.size / 3).coerceAtLeast(256))
        GZIPOutputStream(out).use { gz ->
            gz.write(bytes)
        }
        return out.toByteArray()
    }
}
