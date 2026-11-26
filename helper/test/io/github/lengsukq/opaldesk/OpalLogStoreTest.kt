package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.diagnostics.DiagnosticEnvironmentSnapshot
import io.github.lengsukq.opaldesk.diagnostics.LogRingStore
import io.github.lengsukq.opaldesk.diagnostics.OpalLogEntry
import io.github.lengsukq.opaldesk.diagnostics.OpalLogFilter
import io.github.lengsukq.opaldesk.diagnostics.OpalLogLevel
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.diagnostics.PostmortemLogParser
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.protocol.WindowRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpalLogStoreTest {

    @Test
    fun ringStoreCapsEntriesSanitizesNewlinesAndRestoresPersistedLines() {
        val store = LogRingStore(maxEntries = 16)
        for (i in 1..25) {
            store.append(OpalLogLevel.INFO, "ADB", "step $i\nwith newline", timestampMs = 1_700_000_000_000L + i)
        }

        val snap = store.snapshot()
        assertEquals(16, snap.size)
        assertEquals("step 10 with newline", snap.first().message)
        assertEquals("step 25 with newline", snap.last().message)

        val restored = LogRingStore(maxEntries = 16)
        restored.appendRawPersistedLine("22:30:11.123 E/Surface: attachWindowSurface FAILED wid=1")
        restored.appendRawPersistedLine("22:30:12.456 W/ADB: pairing timeout")
        restored.appendRawPersistedLine("plain unformatted line")

        val restoredSnap = restored.snapshot()
        assertEquals(3, restoredSnap.size)
        assertEquals(OpalLogLevel.ERROR, restoredSnap[0].level)
        assertEquals("Surface", restoredSnap[0].tag)
        assertEquals("attachWindowSurface FAILED wid=1", restoredSnap[0].message)
        assertEquals(OpalLogLevel.WARN, restoredSnap[1].level)
        assertEquals("ADB", restoredSnap[1].tag)
        assertEquals(OpalLogLevel.INFO, restoredSnap[2].level)
        assertEquals("Saved", restoredSnap[2].tag)
    }

    @Test
    fun filterHelperAndDaemonLogsRespectsLevelAndQuery() {
        val store = LogRingStore(maxEntries = 32)
        store.append(OpalLogLevel.INFO, "ADB", "Wireless shell returned LAUNCH_DONE")
        store.append(OpalLogLevel.WARN, "Surface", "TextureView waiting for frame")
        store.append(OpalLogLevel.ERROR, "Surface", "attachWindowSurface FAILED wid=3")

        val all = store.snapshot()
        assertEquals(2, OpalLogFilter.filterHelperEntries(all, warningsAndErrorsOnly = true, query = "").size)
        assertEquals(1, OpalLogFilter.filterHelperEntries(all, warningsAndErrorsOnly = true, query = "wid=3").size)
        assertEquals(1, OpalLogFilter.filterHelperEntries(all, warningsAndErrorsOnly = false, query = "launch_done").size)

        val daemonRaw = """
            22:31:01.001 I/Daemon: OPALDESK_VD_CREATED name=OpalWin-1 displayId=5
            22:31:02.002 E/Daemon: OPALDESK_VD_CREATE_FAIL name=OpalWin-2
            22:31:03.003 I/Daemon: OPALDESK_TICK_WARN: transient error
        """.trimIndent()
        val daemonAll = OpalLogFilter.filterDaemonLines(daemonRaw, warningsAndErrorsOnly = false, query = "")
        val daemonWarnErr = OpalLogFilter.filterDaemonLines(daemonRaw, warningsAndErrorsOnly = true, query = "")
        assertEquals(3, daemonAll.size)
        assertEquals(2, daemonWarnErr.size)
        assertFalse(daemonWarnErr.any { it.contains("VD_CREATED") })
    }

    @Test
    fun buildDiagnosticReportIncludesDeviceStatusWindowsAndDualLogs() {
        val store = LogRingStore(maxEntries = 16)
        store.append(OpalLogLevel.ERROR, "Surface", "attachWindowSurface FAILED wid=2")

        val status = DaemonStatus(
            connected = true,
            daemonUid = 2000,
            activeVdCount = 1,
            maxVdCount = 6,
            windowCount = 1,
            canvasWidth = 2400,
            canvasHeight = 1080,
            canvasDpi = 420,
        )
        val scene = CompositorScene(
            windows = listOf(
                CompositorWindow(
                    windowId = 2,
                    pkg = "com.android.settings",
                    activityClass = "",
                    title = "Settings",
                    profileName = "DESKTOP",
                    profileCode = 1,
                    state = "LIVE",
                    presentation = "FLOATING",
                    frame = WindowRect(0, 0, 800, 600),
                    contentWidth = 800,
                    contentHeight = 560,
                    contentDpi = 220,
                    displayId = -1,
                    taskId = -1,
                    zOrder = 1,
                    focused = true,
                    snapshotVersion = 0L,
                    hasSnapshot = false,
                ),
            ),
        )
        val report = OpalLogFilter.buildDiagnosticReport(
            appVersion = "1.0.5",
            deviceSummary = "Xiaomi Test · Android 16 (API 36)",
            status = status,
            scene = scene,
            remote = RemoteStreamStatus(lanIp = "192.168.31.63"),
            helperEntries = store.snapshot(),
            daemonLogText = "22:31:02.002 E/Daemon: OPALDESK_VD_CREATE_FAIL name=OpalWin-2",
            generatedAtText = "2026-11-23 22:45:00",
        )

        assertTrue(report.contains("v1.0.5"))
        assertTrue(report.contains("uid=2000"))
        assertTrue(report.contains("inputMode=AUTO"))
        assertTrue(report.contains("wid=2 pkg=com.android.settings"))
        assertTrue(report.contains("attachWindowSurface FAILED wid=2"))
        assertTrue(report.contains("OPALDESK_VD_CREATE_FAIL name=OpalWin-2"))
    }

    @Test
    fun buildDiagnosticReportIncludesEnvironmentSnapshotAndInputModeDetails() {
        val status = DaemonStatus(
            connected = true,
            daemonUid = 2000,
            inputBackendMode = BridgeProtocol.InputBackend.UHID,
            injectStatus = BridgeProtocol.InputBackend.STATUS_UHID,
        )
        val env = DiagnosticEnvironmentSnapshot(
            displaySummary = "2400x1080@2.75x(LANDSCAPE)",
            externalDisplaySummary = "PRESENTATION(d=2 1920x1080@60Hz wireless=false cast=false)",
            networkSummary = "wifiEnabled=false wifiConn=false eth=false localNetPerm=true",
            floatingOverlaySummary = "active(pkg=com.tencent.mm peer=XiaoMi15)",
        )
        val report = OpalLogFilter.buildDiagnosticReport(
            appVersion = "1.0.16",
            deviceSummary = "TestDevice · Android 17 (API 37)",
            status = status,
            scene = CompositorScene(),
            remote = RemoteStreamStatus(),
            helperEntries = emptyList(),
            daemonLogText = "",
            generatedAtText = "2026-11-26 09:20:00",
            env = env,
        )

        assertTrue(report.contains("inputMode=UHID"))
        assertTrue(report.contains("status=${BridgeProtocol.InputBackend.STATUS_UHID}"))
        assertTrue(report.contains("2400x1080@2.75x(LANDSCAPE)"))
        assertTrue(report.contains("PRESENTATION(d=2 1920x1080@60Hz"))
        assertTrue(report.contains("wifiEnabled=false wifiConn=false"))
        assertTrue(report.contains("active(pkg=com.tencent.mm"))
    }

    @Test
    fun entryListenerReceivesAppendedEntriesForBreadcrumbBridging() {
        val captured = ArrayList<OpalLogEntry>()
        val prevListener = OpalLogStore.entryListener
        try {
            OpalLogStore.clearHelperLogs()
            OpalLogStore.entryListener = { entry -> captured.add(entry) }
            OpalLogStore.i("PeerCast", "Connected desktop session")
            OpalLogStore.w("ExtDesktop", "Presentation fallback triggered")
            assertEquals(2, captured.size)
            assertEquals("PeerCast", captured[0].tag)
            assertEquals(OpalLogLevel.WARN, captured[1].level)
            val tail = OpalLogStore.recentTailLines(1)
            assertEquals(1, tail.size)
            assertTrue(tail[0].contains("Presentation fallback triggered"))
        } finally {
            OpalLogStore.entryListener = prevListener
            OpalLogStore.clearHelperLogs()
        }
    }

    @Test
    fun postmortemLogParserFiltersThirdPartyCrashesAndPreservesDaemonAndAppProcessCrashes() {
        val raw = """
            ===OPAL_DAEMON_ALIVE===
            0
            ===OPAL_CRASH_LOGCAT===
            --------- beginning of crash
            11-26 17:00:00.100 E AndroidRuntime: FATAL EXCEPTION: main
            11-26 17:00:00.100 E AndroidRuntime: Process: com.example.unrelatedapp, PID: 9999
            11-26 17:00:00.100 E AndroidRuntime: java.lang.NullPointerException: ignore me
            --------- beginning of crash
            11-26 17:05:11.200 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            11-26 17:05:11.201 F DEBUG   : Cmdline: /system/bin/app_process /data/local/tmp io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon
            11-26 17:05:11.202 F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
            11-26 17:05:11.203 F DEBUG   :     #00 pc 0000000000012345  /system/lib64/libandroid_runtime.so
            ===OPAL_CUR_LOG===
            OPALDESK_VD_CREATED name=OpalWin-1 displayId=2
            192.168.31.88 bearer abcdef123456
            ===OPAL_PREV_LOG===
            OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED uid=2000 api=31
            OPALDESK_DAEMON_SHUTDOWN_CLEAN reason=ctl_stop
        """.trimIndent()

        val formatted = PostmortemLogParser.formatReportBlock(
            rawOutput = raw,
            channel = "shizuku",
            triggerReason = "link_to_death",
        )
        assertTrue(formatted.contains("[POSTMORTEM] collected trigger=link_to_death channel=shizuku daemonAlive=0"))
        assertTrue(formatted.contains("signal 11 (SIGSEGV)"))
        assertTrue(formatted.contains("OpalDeskDaemon"))
        assertTrue(formatted.contains("OPALDESK_VD_CREATED name=OpalWin-1"))
        // Unrelated third-party app crashes and clean previous restart logs must be excluded; PII redacted.
        assertFalse(formatted.contains("com.example.unrelatedapp"))
        assertFalse(formatted.contains("OPALDESK_DAEMON_SHUTDOWN_CLEAN"))
        assertFalse(formatted.contains("192.168.31.88"))
        assertFalse(formatted.contains("abcdef123456"))
    }

    @Test
    fun mergeDaemonLogsPreservesCachedLinesAcrossEmptyFetchAndReplacesOldPostmortemSection() {
        try {
            OpalLogStore.clearDaemonLogs(resetWatermark = true)
            OpalLogStore.updateDaemonLogs("17:00:00.000 I/Daemon: [STARTUP] OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED\n17:00:01.000 I/Daemon: live-1")
            // Simulated failed/empty Binder IPC fetch must NOT erase cached live daemon logs
            OpalLogStore.updateDaemonLogs("")
            assertTrue(OpalLogStore.daemonLogsFlow.value.contains("live-1"))

            OpalLogStore.mergePostmortemDaemonLogs("[POSTMORTEM] old-capture")
            assertTrue(OpalLogStore.daemonLogsFlow.value.contains("live-1"))
            assertTrue(OpalLogStore.daemonLogsFlow.value.contains("old-capture"))

            // Subsequent post-mortem collection replaces stale [POSTMORTEM] block while keeping cached live lines
            OpalLogStore.mergePostmortemDaemonLogs("[POSTMORTEM] fresh-capture\n[POSTMORTEM] Fatal signal 11")
            val merged = OpalLogStore.daemonLogsFlow.value
            assertTrue(merged.contains("[STARTUP] OPALDESK_UNIVERSAL_COMPOSITOR_DAEMON_STARTED"))
            assertTrue(merged.contains("live-1"))
            assertFalse(merged.contains("old-capture"))
            assertTrue(merged.contains("fresh-capture"))
            assertTrue(OpalLogFilter.isDaemonWarningOrError("[POSTMORTEM] Fatal signal 11"))
        } finally {
            OpalLogStore.clearDaemonLogs(resetWatermark = true)
        }
    }
}
