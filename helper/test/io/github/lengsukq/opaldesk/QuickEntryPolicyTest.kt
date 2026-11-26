package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.protocol.WindowRect
import io.github.lengsukq.opaldesk.service.QuickEntryPolicy
import io.github.lengsukq.opaldesk.service.QuickEntryPolicy.AutoEnterDecision
import io.github.lengsukq.opaldesk.service.QuickEntryPolicy.AutoEnterDexInput
import io.github.lengsukq.opaldesk.service.QuickEntryPolicy.QuickTileVisualState
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickEntryPolicyTest {

    private fun baseAutoEnterInput() = AutoEnterDexInput(
        autoEnterEnabled = true,
        isColdLaunch = true,
        launchedFromDesktop = false,
        bypassAutoEnter = false,
        userManuallyCancelled = false,
        userStopping = false,
        engineConnected = true,
        remoteCastConnected = false,
        physicalExternalActive = false,
        hasPendingPairing = false,
    )

    @Test
    fun evaluateAutoEnterDexEntersOnlyOnCleanColdLaunchWithActiveEngine() {
        assertEquals(
            AutoEnterDecision.ENTER_DEX,
            QuickEntryPolicy.evaluateAutoEnterDex(baseAutoEnterInput()),
        )
        assertEquals(
            AutoEnterDecision.WAIT_FOR_ENGINE,
            QuickEntryPolicy.evaluateAutoEnterDex(baseAutoEnterInput().copy(engineConnected = false)),
        )
    }

    @Test
    fun evaluateAutoEnterDexStaysOnConsoleWhenDisabledOrReturnedFromDexOrBusy() {
        val base = baseAutoEnterInput()
        assertEquals(AutoEnterDecision.STAY_ON_CONSOLE, QuickEntryPolicy.evaluateAutoEnterDex(base.copy(autoEnterEnabled = false)))
        assertEquals(AutoEnterDecision.STAY_ON_CONSOLE, QuickEntryPolicy.evaluateAutoEnterDex(base.copy(isColdLaunch = false)))
        assertEquals(AutoEnterDecision.STAY_ON_CONSOLE, QuickEntryPolicy.evaluateAutoEnterDex(base.copy(launchedFromDesktop = true)))
        assertEquals(AutoEnterDecision.STAY_ON_CONSOLE, QuickEntryPolicy.evaluateAutoEnterDex(base.copy(bypassAutoEnter = true)))
        assertEquals(AutoEnterDecision.STAY_ON_CONSOLE, QuickEntryPolicy.evaluateAutoEnterDex(base.copy(userManuallyCancelled = true)))
        assertEquals(AutoEnterDecision.STAY_ON_CONSOLE, QuickEntryPolicy.evaluateAutoEnterDex(base.copy(userStopping = true)))
        assertEquals(AutoEnterDecision.STAY_ON_CONSOLE, QuickEntryPolicy.evaluateAutoEnterDex(base.copy(remoteCastConnected = true)))
        assertEquals(AutoEnterDecision.STAY_ON_CONSOLE, QuickEntryPolicy.evaluateAutoEnterDex(base.copy(physicalExternalActive = true)))
        assertEquals(AutoEnterDecision.STAY_ON_CONSOLE, QuickEntryPolicy.evaluateAutoEnterDex(base.copy(hasPendingPairing = true)))
    }

    @Test
    fun startupWaitBudgetExpandsWhenSilentBootstrapIsPossible() {
        assertEquals(
            QuickEntryPolicy.EXISTING_DAEMON_WAIT_BUDGET_MS,
            QuickEntryPolicy.computeStartupWaitBudgetMs(autoShizukuCandidate = false, autoRootCandidate = false),
        )
        assertEquals(
            QuickEntryPolicy.SILENT_BOOTSTRAP_WAIT_BUDGET_MS,
            QuickEntryPolicy.computeStartupWaitBudgetMs(autoShizukuCandidate = true, autoRootCandidate = false),
        )
        assertEquals(
            QuickEntryPolicy.SILENT_BOOTSTRAP_WAIT_BUDGET_MS,
            QuickEntryPolicy.computeStartupWaitBudgetMs(autoShizukuCandidate = false, autoRootCandidate = true),
        )
    }

    @Test
    fun enterDexTileStateReflectsConnectionRemoteLockAndWindowCount() {
        val emptyScene = CompositorScene()
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.INACTIVE, "未激活 · 点按激活"),
            QuickEntryPolicy.resolveEnterDexTileState(DaemonStatus(connected = false), emptyScene, RemoteStreamStatus()),
        )
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.UNAVAILABLE, "投屏锁定中"),
            QuickEntryPolicy.resolveEnterDexTileState(
                DaemonStatus(connected = true),
                emptyScene,
                RemoteStreamStatus(connected = true),
            ),
        )
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.ACTIVE, "引擎已就绪"),
            QuickEntryPolicy.resolveEnterDexTileState(DaemonStatus(connected = true), emptyScene, RemoteStreamStatus()),
        )
        val oneWinScene = CompositorScene(
            windows = listOf(
                CompositorWindow(
                    windowId = 1,
                    pkg = "com.example.app",
                    activityClass = "",
                    title = "App",
                    profileName = "DESKTOP",
                    profileCode = 1,
                    state = "LIVE",
                    presentation = "FLOATING",
                    frame = WindowRect(0, 0, 800, 600),
                    contentWidth = 800,
                    contentHeight = 560,
                    contentDpi = 200,
                    displayId = 2,
                    taskId = 10,
                    zOrder = 1,
                    focused = true,
                    snapshotVersion = 0L,
                    hasSnapshot = false,
                ),
            ),
        )
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.ACTIVE, "1 个窗口"),
            QuickEntryPolicy.resolveEnterDexTileState(DaemonStatus(connected = true), oneWinScene, RemoteStreamStatus()),
        )
    }

    @Test
    fun hostScreenAndWifiShareTileStatesFollowDaemonAndPeerStatus() {
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.INACTIVE, "引擎未激活"),
            QuickEntryPolicy.resolveHostScreenTileState(DaemonStatus(connected = false)),
        )
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.INACTIVE, "亮屏运行中"),
            QuickEntryPolicy.resolveHostScreenTileState(DaemonStatus(connected = true, hostScreenOff = false)),
        )
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.ACTIVE, "已物理息屏"),
            QuickEntryPolicy.resolveHostScreenTileState(DaemonStatus(connected = true, hostScreenOff = true)),
        )

        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.INACTIVE, "引擎未激活"),
            QuickEntryPolicy.resolveWifiShareTileState(DaemonStatus(connected = false), RemoteStreamStatus(), false),
        )
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.UNAVAILABLE, "接收投屏中"),
            QuickEntryPolicy.resolveWifiShareTileState(DaemonStatus(connected = true), RemoteStreamStatus(), true),
        )
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.INACTIVE, "未开启"),
            QuickEntryPolicy.resolveWifiShareTileState(
                DaemonStatus(connected = true),
                RemoteStreamStatus(wifiEnabled = false),
                false,
            ),
        )
        assertEquals(
            QuickEntryPolicy.QuickTileSpec(QuickTileVisualState.ACTIVE, "192.168.1.25:19878"),
            QuickEntryPolicy.resolveWifiShareTileState(
                DaemonStatus(connected = true),
                RemoteStreamStatus(wifiEnabled = true, lanIp = "192.168.1.25", wifiPort = 19878),
                false,
            ),
        )
    }
}
