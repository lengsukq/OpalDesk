package io.github.lengsukq.opaldesk

import android.app.ActivityManager
import io.github.lengsukq.opaldesk.diagnostics.AnrExitDiagnostics
import io.github.lengsukq.opaldesk.diagnostics.AnrExitSnapshot
import io.github.lengsukq.opaldesk.service.BridgeServiceLifecyclePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeServiceAndAnrDiagnosticsTest {

    @Test
    fun `BridgeServiceLifecyclePolicy blocks redundant and background FGS starts`() {
        // Explicit foreground UI launch starts FGS when not running
        assertTrue(
            BridgeServiceLifecyclePolicy.shouldStartForegroundService(
                userStopping = false,
                alreadyForegroundActive = false,
                requireForegroundProcess = false,
                processImportance = BridgeServiceLifecyclePolicy.IMPORTANCE_GONE,
            ),
        )
        // Already running in foreground -> never re-arm startForegroundService()
        assertFalse(
            BridgeServiceLifecyclePolicy.shouldStartForegroundService(
                userStopping = false,
                alreadyForegroundActive = true,
                requireForegroundProcess = false,
                processImportance = BridgeServiceLifecyclePolicy.IMPORTANCE_FOREGROUND,
            ),
        )
        assertTrue(
            BridgeServiceLifecyclePolicy.shouldRefreshRunningServiceOnly(
                userStopping = false,
                alreadyForegroundActive = true,
            ),
        )
        // Background BridgeProvider Binder handshake must NOT start FGS when app is backgrounded
        assertFalse(
            BridgeServiceLifecyclePolicy.shouldStartForegroundService(
                userStopping = false,
                alreadyForegroundActive = false,
                requireForegroundProcess = true,
                processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
            ),
        )
        assertFalse(
            BridgeServiceLifecyclePolicy.shouldStartForegroundService(
                userStopping = false,
                alreadyForegroundActive = false,
                requireForegroundProcess = true,
                processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            ),
        )
        // Foreground BridgeProvider Binder handshake IS allowed to start FGS
        assertTrue(
            BridgeServiceLifecyclePolicy.shouldStartForegroundService(
                userStopping = false,
                alreadyForegroundActive = false,
                requireForegroundProcess = true,
                processImportance = BridgeServiceLifecyclePolicy.IMPORTANCE_FOREGROUND,
            ),
        )
        // User-stopping blocks both start and refresh
        assertFalse(
            BridgeServiceLifecyclePolicy.shouldStartForegroundService(
                userStopping = true,
                alreadyForegroundActive = false,
                requireForegroundProcess = false,
                processImportance = BridgeServiceLifecyclePolicy.IMPORTANCE_FOREGROUND,
            ),
        )
        assertFalse(
            BridgeServiceLifecyclePolicy.shouldRefreshRunningServiceOnly(
                userStopping = true,
                alreadyForegroundActive = true,
            ),
        )
    }

    @Test
    fun `BridgeServiceLifecyclePolicy stops idle FGS on Recents swipe unless external or cast active`() {
        assertTrue(
            BridgeServiceLifecyclePolicy.shouldStopSelfOnTaskRemoved(
                hasPhysicalExternal = false,
                hasRemoteCast = false,
            ),
        )
        assertFalse(
            BridgeServiceLifecyclePolicy.shouldStopSelfOnTaskRemoved(
                hasPhysicalExternal = true,
                hasRemoteCast = false,
            ),
        )
        assertFalse(
            BridgeServiceLifecyclePolicy.shouldStopSelfOnTaskRemoved(
                hasPhysicalExternal = false,
                hasRemoteCast = true,
            ),
        )
    }

    @Test
    fun `AnrExitDiagnostics detects ANR events and classifies native AppExitInfo descriptions`() {
        assertTrue(AnrExitDiagnostics.isAnrEvent("ApplicationNotResponding", "ANR", "AppExitInfo"))
        assertTrue(AnrExitDiagnostics.isAnrEvent("ApplicationNotResponding", "Background ANR", null))
        assertTrue(AnrExitDiagnostics.isAnrEvent(null, null, "AppExitInfo"))
        assertFalse(AnrExitDiagnostics.isAnrEvent("NullPointerException", "null", "UncaughtExceptionHandler"))

        assertEquals(
            AnrExitDiagnostics.CAT_FGS_TIMEOUT,
            AnrExitDiagnostics.classifyAnrDescription(
                "Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{BridgeService}",
            ),
        )
        assertEquals(
            AnrExitDiagnostics.CAT_INPUT_NO_FOCUS,
            AnrExitDiagnostics.classifyAnrDescription(
                "Input dispatching timed out (Application does not have a focused window)",
            ),
        )
        assertEquals(
            AnrExitDiagnostics.CAT_INPUT_UNRESPONSIVE,
            AnrExitDiagnostics.classifyAnrDescription(
                "Input dispatching timed out (Waiting to send non-key event because the touched window has not finished processing)",
            ),
        )
        assertEquals(
            AnrExitDiagnostics.CAT_SERVICE_TIMEOUT,
            AnrExitDiagnostics.classifyAnrDescription("executing service io.github.lengsukq.opaldesk/.BridgeService"),
        )
        assertEquals(
            AnrExitDiagnostics.CAT_BROADCAST_TIMEOUT,
            AnrExitDiagnostics.classifyAnrDescription("Broadcast of Intent { act=android.intent.action.SCREEN_ON }"),
        )
        assertEquals(
            AnrExitDiagnostics.CAT_PROVIDER_TIMEOUT,
            AnrExitDiagnostics.classifyAnrDescription("ContentProvider not responding"),
        )
        assertEquals(AnrExitDiagnostics.CAT_UNKNOWN, AnrExitDiagnostics.classifyAnrDescription("   "))
        assertEquals(AnrExitDiagnostics.CAT_OTHER, AnrExitDiagnostics.classifyAnrDescription("OEM watchdog freeze"))
    }

    @Test
    fun `AnrExitDiagnostics sanitizes descriptions and formats startup diagnostic lines`() {
        val raw = "Input dispatching timed out from 192.168.31.40\nwith multiline   whitespace"
        val clean = AnrExitDiagnostics.sanitizeDescription(raw)
        assertFalse(clean.contains("192.168.31.40"))
        assertFalse(clean.contains("\n"))
        assertTrue(clean.contains("Input dispatching timed out"))

        val longClean = AnrExitDiagnostics.sanitizeDescription("x".repeat(500))
        assertTrue(longClean.length <= 360)
        assertTrue(longClean.endsWith("…"))

        val now = 1_764_250_000_000L
        assertTrue(AnrExitDiagnostics.isWithinRecentLogWindow(now - 60_000L, now))
        assertFalse(AnrExitDiagnostics.isWithinRecentLogWindow(now - 25L * 3_600_000L, now))
        assertFalse(AnrExitDiagnostics.isWithinRecentLogWindow(0L, now))

        val snap = AnrExitSnapshot(
            category = AnrExitDiagnostics.CAT_INPUT_NO_FOCUS,
            importanceLabel = "foreground",
            importanceCode = 100,
            description = clean,
            timestampMs = now - 5_000L,
            pssKb = 42_000L,
            rssKb = 95_000L,
            historySummary = "summary",
        )
        val line = AnrExitDiagnostics.formatStartupLogLine(snap)
        assertTrue(line.contains("cat=input_timeout_no_focus"))
        assertTrue(line.contains("imp=foreground"))
        assertTrue(line.contains("pss=42000KB"))
    }
}
