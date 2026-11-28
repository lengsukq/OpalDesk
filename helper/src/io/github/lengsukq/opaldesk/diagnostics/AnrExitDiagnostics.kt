package io.github.lengsukq.opaldesk.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AnrExitSnapshot(
    val category: String,
    val importanceLabel: String,
    val importanceCode: Int,
    val description: String,
    val timestampMs: Long,
    val pssKb: Long,
    val rssKb: Long,
    val historySummary: String,
)

/**
 * Pure-JVM classifier & Android API 30+ reader for native `ApplicationExitInfo` ANR metadata.
 *
 * Enriches Sentry `AppExitInfo` ANR events (whose sampled main thread is often already idle in
 * `__epoll_pwait`) with the exact OS-reported ANR cause (`description`, `importance`, `category`)
 * so Input-dispatching, Foreground-Service, Broadcast, and Service ANRs are immediately distinct.
 */
object AnrExitDiagnostics {
    private const val MAX_DESCRIPTION_CHARS = 360
    private const val MAX_HISTORY_ITEMS = 5
    private const val RECENT_LOG_WINDOW_MS = 24L * 60L * 60L * 1000L

    const val CAT_FGS_TIMEOUT = "fgs_timeout"
    const val CAT_INPUT_NO_FOCUS = "input_timeout_no_focus"
    const val CAT_INPUT_UNRESPONSIVE = "input_timeout_unresponsive"
    const val CAT_SERVICE_TIMEOUT = "service_timeout"
    const val CAT_BROADCAST_TIMEOUT = "broadcast_timeout"
    const val CAT_PROVIDER_TIMEOUT = "content_provider_timeout"
    const val CAT_OTHER = "other"
    const val CAT_UNKNOWN = "unknown"

    fun isAnrEvent(exceptionType: String?, exceptionValue: String?, mechanismType: String?): Boolean {
        val type = exceptionType.orEmpty()
        val value = exceptionValue.orEmpty()
        val mech = mechanismType.orEmpty()
        return type.contains("ApplicationNotResponding", ignoreCase = true) ||
            mech.equals("AppExitInfo", ignoreCase = true) ||
            value.equals("ANR", ignoreCase = true) ||
            value.contains("Background ANR", ignoreCase = true)
    }

    fun classifyAnrDescription(rawDescription: String?): String {
        val desc = rawDescription?.trim().orEmpty()
        if (desc.isEmpty()) return CAT_UNKNOWN
        val lower = desc.lowercase(Locale.US)
        return when {
            lower.contains("startforegroundservice") ||
                lower.contains("did not then call service.startforeground") ||
                lower.contains("foregroundservicedidnotstartintime") -> CAT_FGS_TIMEOUT
            lower.contains("input dispatching timed out") &&
                (lower.contains("no focused window") || lower.contains("does not have a focused window")) -> CAT_INPUT_NO_FOCUS
            lower.contains("input dispatching timed out") -> CAT_INPUT_UNRESPONSIVE
            lower.contains("executing service") || lower.contains("service timeout") -> CAT_SERVICE_TIMEOUT
            lower.contains("broadcast of intent") || lower.contains("broadcast timeout") -> CAT_BROADCAST_TIMEOUT
            lower.contains("contentprovider not responding") || lower.contains("publishing content providers") -> CAT_PROVIDER_TIMEOUT
            else -> CAT_OTHER
        }
    }

    fun importanceLabel(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground_service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "top_sleeping"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        else -> "imp_$importance"
    }

    fun sanitizeDescription(rawDescription: String?): String {
        val cleaned = DiagnosticSanitizer.sanitizeText(rawDescription.orEmpty())
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length <= MAX_DESCRIPTION_CHARS) return cleaned
        return cleaned.take(MAX_DESCRIPTION_CHARS - 1).trimEnd() + "…"
    }

    fun isWithinRecentLogWindow(timestampMs: Long, nowMs: Long): Boolean =
        timestampMs > 0L && nowMs >= timestampMs && (nowMs - timestampMs) <= RECENT_LOG_WINDOW_MS

    fun formatStartupLogLine(snapshot: AnrExitSnapshot): String {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(snapshot.timestampMs))
        return "Previous ANR AppExitInfo: cat=${snapshot.category} imp=${snapshot.importanceLabel} " +
            "at=$ts pss=${snapshot.pssKb}KB desc=${snapshot.description.ifEmpty { "<empty>" }}"
    }

    fun captureLatestAnrExit(ctx: Context): AnrExitSnapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = ctx.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, MAX_HISTORY_ITEMS)
            if (exits.isEmpty()) return null
            val anrExits = exits.filter { it.reason == ApplicationExitInfo.REASON_ANR }
            val latest = anrExits.firstOrNull() ?: return null
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            val history = anrExits.take(3).joinToString(" | ") { info ->
                val desc = sanitizeDescription(info.description).take(120)
                val cat = classifyAnrDescription(info.description)
                "${fmt.format(Date(info.timestamp))} [$cat/${importanceLabel(info.importance)}] $desc"
            }
            val cleanDesc = sanitizeDescription(latest.description)
            AnrExitSnapshot(
                category = classifyAnrDescription(latest.description),
                importanceLabel = importanceLabel(latest.importance),
                importanceCode = latest.importance,
                description = cleanDesc,
                timestampMs = latest.timestamp,
                pssKb = latest.pss,
                rssKb = latest.rss,
                historySummary = history,
            )
        }.getOrNull()
    }
}
