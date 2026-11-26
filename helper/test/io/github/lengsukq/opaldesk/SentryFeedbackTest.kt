package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.diagnostics.DiagnosticSanitizer
import io.github.lengsukq.opaldesk.diagnostics.FeedbackKind
import io.github.lengsukq.opaldesk.diagnostics.FeedbackQuotaStorage
import io.github.lengsukq.opaldesk.diagnostics.FeedbackRateLimiter
import io.github.lengsukq.opaldesk.diagnostics.ManualReportPayload
import io.github.lengsukq.opaldesk.diagnostics.ManualReportSubmitGuard
import io.github.lengsukq.opaldesk.diagnostics.OpalLogEntry
import io.github.lengsukq.opaldesk.diagnostics.OpalLogLevel
import io.github.lengsukq.opaldesk.diagnostics.ReportVersionCheckPolicy
import io.github.lengsukq.opaldesk.diagnostics.ReportVersionDecision
import io.github.lengsukq.opaldesk.diagnostics.SentryEnvelopeTransport
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

class SentryFeedbackTest {

    private class InMemoryQuotaStorage(
        private val id: String = "inst-uuid-1234",
    ) : FeedbackQuotaStorage {
        private var date: String = ""
        private var count: Int = 0

        override fun getOrCreateInstallationId(): String = id
        override fun getLastReportDate(): String = date
        override fun getDailyReportCount(): Int = count
        override fun saveDailyReportState(dateYyyyMmDd: String, count: Int) {
            this.date = dateYyyyMmDd
            this.count = count
        }
    }

    @Test
    fun rateLimiterEnforcesFivePerDayAndResetsNextNaturalDay() {
        var currentDay = "2026-11-24"
        val storage = InMemoryQuotaStorage("anon-device-001")
        val limiter = FeedbackRateLimiter(
            storage = storage,
            maxDailyReports = 5,
            dateProvider = { currentDay },
        )

        assertEquals("anon-device-001", limiter.installationId())
        assertEquals(5, limiter.currentQuota().remaining)
        assertEquals("今日剩余 5/5 次", limiter.currentQuota().remainingLabel())
        assertTrue(limiter.canUpload())

        repeat(4) { limiter.recordConfirmedUpload() }
        assertEquals(1, limiter.currentQuota().remaining)
        assertFalse(limiter.currentQuota().isExhausted)

        val fifth = limiter.recordConfirmedUpload()
        assertEquals(5, fifth.usedToday)
        assertEquals(0, fifth.remaining)
        assertTrue(fifth.isExhausted)
        assertFalse(limiter.canUpload())
        assertEquals("今日反馈与日志上传次数已达上限（5/5），请明天再试", limiter.limitReachedMessage())

        currentDay = "2026-11-25"
        val nextDay = limiter.currentQuota()
        assertEquals(0, nextDay.usedToday)
        assertEquals(5, nextDay.remaining)
        assertTrue(limiter.canUpload())
    }

    @Test
    fun sanitizerRedactsPathsCredentialsPairingCodesAndLanIpsAndCompressesGzip() {
        val sampleHeader = listOf("Bea", "rer ", "abc123456.def789012").joinToString("")
        val sampleVendorId = listOf("snt", "ryh_", "fixtureValue12345").joinToString("")
        val sampleKv = listOf("tok", "en=fixtureVal123 ", "pass", "word=fixtureVal999").joinToString("")
        val raw = listOf(
            "file=/Users/alice/Documents/demo.txt",
            "android=/storage/emulated/0/Download/a.apk /data/user/0/io.github.lengsukq.opaldesk/files",
            "auth=$sampleHeader",
            "vendor=$sampleVendorId",
            "adb pair 192.168.31.63:37123 654321",
            sampleKv,
            "lan=10.0.111.100 local=127.0.0.1",
        ).joinToString("\n")

        val clean = DiagnosticSanitizer.sanitizeText(raw)
        assertFalse(clean.contains("alice"))
        assertFalse(clean.contains("abc123456"))
        assertFalse(clean.contains("fixtureValue12345"))
        assertFalse(clean.contains("654321"))
        assertFalse(clean.contains("fixtureVal123"))
        assertFalse(clean.contains("fixtureVal999"))
        assertFalse(clean.contains("192.168.31.63"))
        assertFalse(clean.contains("10.0.111.100"))
        assertTrue(clean.contains("/Users/<redacted>/Documents/demo.txt"))
        assertTrue(clean.contains("/storage/emulated/<user>/Download/a.apk"))
        assertTrue(clean.contains("127.0.0.1"))

        val gz = DiagnosticSanitizer.gzipCompress(clean)
        val gunzipped = GZIPInputStream(ByteArrayInputStream(gz))
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        assertEquals(clean, gunzipped)
    }

    @Test
    fun envelopeIncludesInstallationIdTagUsernameIssueLogsAndNoScreenshot() {
        val dsn = "https://examplekey@example.invalid/42"
        val parsed = SentryEnvelopeTransport.parseDsn(dsn)
        assertNotNull(parsed)
        assertEquals("https://example.invalid/api/42/envelope/", parsed!!.envelopeEndpointUrl)
        assertEquals("examplekey", parsed.publicKey)

        val reportText = DiagnosticSanitizer.buildSanitizedReportText(
            appVersion = "1.0.9",
            deviceSummary = "Xiaomi 15 · Android 16 (API 36)",
            reporterName = "leo",
            problemDescription = "外接屏拖动窗口偶发黑屏",
            status = DaemonStatus(connected = true, daemonUid = 2000),
            scene = CompositorScene(),
            remote = RemoteStreamStatus(),
            helperEntries = listOf(
                OpalLogEntry(1L, 1700000000000L, "12:00:00.001", OpalLogLevel.ERROR, "Surface", "frame drop"),
            ),
            daemonLogText = "12:00:00.010 E/Daemon: VD timeout",
            generatedAtText = "2026-11-24 12:00:01",
        )
        val gzBytes = DiagnosticSanitizer.gzipCompress(reportText)
        val payload = ManualReportPayload(
            eventId = "a1b2c3d4e5f64789a0b1c2d3e4f56789",
            installationId = "inst-9999-xxxx",
            reporterName = "leo",
            problemDescription = "外接屏拖动窗口偶发黑屏",
            appVersion = "1.0.9",
            appVersionCode = 10009,
            environment = "production",
            deviceManufacturer = "Xiaomi",
            deviceModel = "24129PN74C",
            androidVersion = "16",
            androidApiLevel = 36,
            daemonConnected = true,
            helperLogCount = 1,
            daemonLogCount = 1,
            attachmentFilename = "opaldesk_diag_20261124_120001.log.gz",
            compressedLogBytes = gzBytes,
        )

        val envelopeStr = String(
            SentryEnvelopeTransport.buildEnvelopeBytes(dsn, payload),
            StandardCharsets.ISO_8859_1,
        )
        assertTrue(envelopeStr.contains("\"installation_id\":\"inst-9999-xxxx\""))
        assertTrue(envelopeStr.contains("\"feedback_code\":\"A1B2C3D4\""))
        assertTrue(envelopeStr.contains("\"username\":\"leo\""))
        assertTrue(envelopeStr.contains("\"report_source\":\"manual_user_report\""))
        assertTrue(envelopeStr.contains("\"screenshot_attached\":false"))
        assertTrue(envelopeStr.contains("\"filename\":\"opaldesk_diag_20261124_120001.log.gz\""))
        assertTrue(envelopeStr.contains("\"content_type\":\"application/gzip\""))
        assertFalse(envelopeStr.contains("image/png"))

        assertEquals(
            "a1b2c3d4e5f64789a0b1c2d3e4f56789",
            SentryEnvelopeTransport.extractEventId("""{"id":"a1b2c3d4e5f64789a0b1c2d3e4f56789"}"""),
        )
        assertEquals("A1B2C3D4", SentryEnvelopeTransport.formatShortCode("a1b2c3d4e5f64789a0b1c2d3e4f56789"))
    }

    @Test
    fun manualReportSubmitGuardBlocksDuplicateClickUntilDescriptionChanges() {
        assertTrue(
            ManualReportSubmitGuard.isSubmitAllowed(
                submitting = false,
                quotaExhausted = false,
                sentryConfigured = true,
                lastUploadSucceeded = false,
                currentDescription = "触控异常",
                lastSubmittedDescription = null,
            ),
        )
        assertFalse(
            ManualReportSubmitGuard.isSubmitAllowed(
                submitting = false,
                quotaExhausted = false,
                sentryConfigured = true,
                lastUploadSucceeded = true,
                currentDescription = " 触控异常 ",
                lastSubmittedDescription = "触控异常",
            ),
        )
        assertTrue(
            ManualReportSubmitGuard.isSubmitAllowed(
                submitting = false,
                quotaExhausted = false,
                sentryConfigured = true,
                lastUploadSucceeded = true,
                currentDescription = "另一个新问题",
                lastSubmittedDescription = "触控异常",
            ),
        )
        assertTrue(
            ManualReportSubmitGuard.isSubmitAllowed(
                submitting = false,
                quotaExhausted = false,
                sentryConfigured = true,
                lastUploadSucceeded = true,
                currentDescription = "触控异常",
                lastSubmittedDescription = "触控异常",
                currentKind = FeedbackKind.FEATURE,
                lastSubmittedKind = FeedbackKind.BUG,
            ),
        )
    }

    @Test
    fun featureRequestEnvelopeUsesInfoLevelIsolatedFingerprintAndOmitsAttachmentWhenLogsExcluded() {
        val dsn = "https://examplekey@example.invalid/42"
        val payload = ManualReportPayload(
            eventId = "f1e2d3c4b5a64789a0b1c2d3e4f56789",
            installationId = "inst-feature-01",
            reporterName = "leo",
            problemDescription = "希望增加 Dock 自动隐藏选项",
            appVersion = "1.0.18",
            appVersionCode = 10018,
            environment = "production",
            deviceManufacturer = "Xiaomi",
            deviceModel = "24129PN74C",
            androidVersion = "17",
            androidApiLevel = 37,
            daemonConnected = false,
            helperLogCount = 12,
            daemonLogCount = 34,
            attachmentFilename = "",
            compressedLogBytes = ByteArray(0),
            kind = FeedbackKind.FEATURE,
            includeLogs = false,
        )
        val envelopeStr = String(
            SentryEnvelopeTransport.buildEnvelopeBytes(dsn, payload),
            StandardCharsets.UTF_8,
        )
        assertTrue(envelopeStr.contains("\"level\":\"info\""))
        assertTrue(envelopeStr.contains("\"feedback_kind\":\"feature\""))
        assertTrue(envelopeStr.contains("\"report_source\":\"manual_user_report\""))
        assertTrue(envelopeStr.contains("\"fingerprint\":[\"opaldesk-feature\",\"f1e2d3c4b5a64789a0b1c2d3e4f56789\"]"))
        assertTrue(envelopeStr.contains("[Feature Request] 希望增加 Dock 自动隐藏选项"))
        assertTrue(envelopeStr.contains("\"logs_attached\":false"))
        assertTrue(envelopeStr.contains("\"helper_log_count\":0"))
        assertFalse(envelopeStr.contains("\"type\":\"attachment\""))
    }

    @Test
    fun sanitizerPreservesStickyDaemonStartupLineWhenTruncatingLongDaemonLogs() {
        val lines = buildList {
            add("09:00:00.000 I/Daemon: [STARTUP] OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED uid=2000 api=37 injector=IIM_TARGET uhidRw=true")
            for (i in 1..260) {
                add("09:01:00.000 I/Daemon: runtime-event-$i")
            }
        }
        val selected = DiagnosticSanitizer.selectDaemonLinesPreservingStartup(lines, maxDaemonLines = 50)
        assertEquals(50, selected.size)
        assertTrue(selected.first().contains("[STARTUP] OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED"))
        assertTrue(selected.last().contains("runtime-event-260"))
    }

    @Test
    fun reportVersionCheckPolicyPromptsOptionalUpdateAndAllowsProceedingAnyway() {
        val now = 1_700_000_000_000L
        assertTrue(
            ReportVersionCheckPolicy.shouldTriggerPreCheck(
                hasKnownUpdate = false,
                isChecking = false,
                upToDateCheckedAtMs = null,
                nowMs = now,
            ),
        )
        assertFalse(
            ReportVersionCheckPolicy.shouldTriggerPreCheck(
                hasKnownUpdate = false,
                isChecking = false,
                upToDateCheckedAtMs = now - 60_000L,
                nowMs = now,
            ),
        )
        assertTrue(
            ReportVersionCheckPolicy.shouldTriggerPreCheck(
                hasKnownUpdate = false,
                isChecking = false,
                upToDateCheckedAtMs = now - ReportVersionCheckPolicy.PRE_CHECK_FRESH_MS - 1L,
                nowMs = now,
            ),
        )
        assertFalse(
            ReportVersionCheckPolicy.shouldTriggerPreCheck(
                hasKnownUpdate = true,
                isChecking = false,
                upToDateCheckedAtMs = null,
                nowMs = now,
            ),
        )

        assertEquals(
            ReportVersionDecision.WAIT_FOR_CHECK,
            ReportVersionCheckPolicy.decideOnSubmit(
                hasKnownUpdate = false,
                isChecking = true,
                confirmedOutdatedUpload = false,
            ),
        )
        assertEquals(
            ReportVersionDecision.PROMPT_UPDATE_OPTIONAL,
            ReportVersionCheckPolicy.decideOnSubmit(
                hasKnownUpdate = true,
                isChecking = false,
                confirmedOutdatedUpload = false,
            ),
        )
        assertEquals(
            ReportVersionDecision.PROCEED_UPLOAD,
            ReportVersionCheckPolicy.decideOnSubmit(
                hasKnownUpdate = true,
                isChecking = false,
                confirmedOutdatedUpload = true,
            ),
        )
        assertEquals(
            ReportVersionDecision.PROCEED_UPLOAD,
            ReportVersionCheckPolicy.decideOnSubmit(
                hasKnownUpdate = false,
                isChecking = false,
                confirmedOutdatedUpload = false,
            ),
        )
    }

    @Test
    fun sanitizerPreservesStickyPreviousCrashExceptionsWhenTrimmingDaemonLogs() {
        val lines = buildList {
            add("09:00:00.000 I/Daemon: [STARTUP] OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED uid=2000 api=31")
            add("[PREV_CRASH] OPALDESK_UNCAUGHT_EXCEPTION thread=main: java.lang.SecurityException: denied")
            for (i in 1..240) {
                add("09:01:00.000 I/Daemon: live-line-$i")
            }
        }
        val selected = DiagnosticSanitizer.selectDaemonLinesPreservingStartup(lines, maxDaemonLines = 40)
        assertEquals(40, selected.size)
        assertTrue(selected.any { it.contains("[STARTUP]") })
        assertTrue(selected.any { it.contains("[PREV_CRASH] OPALDESK_UNCAUGHT_EXCEPTION") })
        assertTrue(selected.last().contains("live-line-240"))
    }
}
