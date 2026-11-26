package io.github.lengsukq.opaldesk.diagnostics

import java.time.LocalDate

/**
 * Persistence abstraction for anonymous installation identity and per-day upload counters.
 * Kept Android-free so [FeedbackRateLimiter] is 100% deterministic under pure JVM JUnit tests.
 */
interface FeedbackQuotaStorage {
    fun getOrCreateInstallationId(): String
    fun getLastReportDate(): String
    fun getDailyReportCount(): Int
    fun saveDailyReportState(dateYyyyMmDd: String, count: Int)
}

data class FeedbackQuotaSnapshot(
    val dateKey: String,
    val usedToday: Int,
    val maxDaily: Int,
) {
    val remaining: Int get() = (maxDaily - usedToday).coerceAtLeast(0)
    val isExhausted: Boolean get() = remaining <= 0

    fun remainingLabel(): String = "今日剩余 $remaining/$maxDaily 次"
}

/**
 * Local natural-day (YYYY-MM-DD) rate limiter for manual diagnostic log uploads.
 * Enforces at most [maxDailyReports] confirmed uploads per local calendar day.
 * Failed uploads (network timeout, 429, 5xx) MUST NOT invoke [recordConfirmedUpload].
 */
class FeedbackRateLimiter(
    private val storage: FeedbackQuotaStorage,
    private val maxDailyReports: Int = MAX_DAILY_REPORTS,
    private val dateProvider: () -> String = { LocalDate.now().toString() },
) {
    companion object {
        const val MAX_DAILY_REPORTS = 5
        const val LIMIT_REACHED_MESSAGE = "今日日志上传次数已达上限（5/5），请明天再试"
    }

    fun installationId(): String = storage.getOrCreateInstallationId()

    @Synchronized
    fun currentQuota(): FeedbackQuotaSnapshot {
        val today = dateProvider()
        val storedDate = storage.getLastReportDate().trim()
        val used = if (storedDate == today) {
            storage.getDailyReportCount().coerceIn(0, maxDailyReports)
        } else {
            0
        }
        return FeedbackQuotaSnapshot(
            dateKey = today,
            usedToday = used,
            maxDaily = maxDailyReports,
        )
    }

    @Synchronized
    fun canUpload(): Boolean = !currentQuota().isExhausted

    /**
     * Increments today's counter by 1 ONLY after Sentry / Tunnel confirms HTTP 2xx receipt.
     */
    @Synchronized
    fun recordConfirmedUpload(): FeedbackQuotaSnapshot {
        val snapshot = currentQuota()
        val nextUsed = (snapshot.usedToday + 1).coerceAtMost(maxDailyReports)
        storage.saveDailyReportState(snapshot.dateKey, nextUsed)
        return FeedbackQuotaSnapshot(
            dateKey = snapshot.dateKey,
            usedToday = nextUsed,
            maxDaily = maxDailyReports,
        )
    }

    fun limitReachedMessage(): String =
        if (maxDailyReports == MAX_DAILY_REPORTS) {
            LIMIT_REACHED_MESSAGE
        } else {
            "今日日志上传次数已达上限（$maxDailyReports/$maxDailyReports），请明天再试"
        }
}

/**
 * Pure JVM-testable guard preventing accidental duplicate manual report submissions
 * right after a confirmed upload while the modal remains open displaying the feedback code.
 */
object ManualReportSubmitGuard {
    fun isSubmitAllowed(
        submitting: Boolean,
        quotaExhausted: Boolean,
        sentryConfigured: Boolean,
        lastUploadSucceeded: Boolean,
        currentDescription: String,
        lastSubmittedDescription: String?,
    ): Boolean {
        if (submitting || quotaExhausted || !sentryConfigured) return false
        if (lastUploadSucceeded &&
            lastSubmittedDescription != null &&
            currentDescription.trim() == lastSubmittedDescription
        ) {
            return false
        }
        return true
    }
}

enum class ReportVersionDecision {
    /** Update check is currently in flight; briefly wait before proceeding or prompting. */
    WAIT_FOR_CHECK,
    /** A newer client version exists and the user has not yet chosen to upload anyway. */
    PROMPT_UPDATE_OPTIONAL,
    /** Already latest, check failed/timed out, or user explicitly confirmed uploading anyway. */
    PROCEED_UPLOAD,
}

/**
 * Pure JVM-testable policy deciding when to run a pre-upload version check and whether to
 * surface a non-blocking update suggestion before sending a manual diagnostic report.
 */
object ReportVersionCheckPolicy {
    const val PRE_CHECK_FRESH_MS = 5L * 60L * 1000L
    const val SUBMIT_WAIT_TIMEOUT_MS = 3_500L

    fun shouldTriggerPreCheck(
        hasKnownUpdate: Boolean,
        isChecking: Boolean,
        upToDateCheckedAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (hasKnownUpdate || isChecking) return false
        if (upToDateCheckedAtMs != null) {
            val ageMs = nowMs - upToDateCheckedAtMs
            if (ageMs in 0L until PRE_CHECK_FRESH_MS) return false
        }
        return true
    }

    fun decideOnSubmit(
        hasKnownUpdate: Boolean,
        isChecking: Boolean,
        confirmedOutdatedUpload: Boolean,
    ): ReportVersionDecision = when {
        hasKnownUpdate && !confirmedOutdatedUpload -> ReportVersionDecision.PROMPT_UPDATE_OPTIONAL
        isChecking && !confirmedOutdatedUpload -> ReportVersionDecision.WAIT_FOR_CHECK
        else -> ReportVersionDecision.PROCEED_UPLOAD
    }
}
