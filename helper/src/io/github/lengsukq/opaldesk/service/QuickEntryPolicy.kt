package io.github.lengsukq.opaldesk.service

import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus

/**
 * Pure JVM-testable policy rules for cold-launch auto-entering DeX mode and
 * resolving Android Quick Settings Tile states/subtitles without Android framework dependencies.
 */
object QuickEntryPolicy {
    const val EXISTING_DAEMON_WAIT_BUDGET_MS = 1500L
    const val SILENT_BOOTSTRAP_WAIT_BUDGET_MS = 5000L

    data class AutoEnterDexInput(
        val autoEnterEnabled: Boolean,
        val isColdLaunch: Boolean,
        val launchedFromDesktop: Boolean,
        val bypassAutoEnter: Boolean,
        val userManuallyCancelled: Boolean,
        val userStopping: Boolean,
        val engineConnected: Boolean,
        val remoteCastConnected: Boolean,
        val physicalExternalActive: Boolean,
        val hasPendingPairing: Boolean,
    )

    enum class AutoEnterDecision {
        ENTER_DEX,
        WAIT_FOR_ENGINE,
        STAY_ON_CONSOLE,
    }

    enum class QuickTileVisualState {
        ACTIVE,
        INACTIVE,
        UNAVAILABLE,
    }

    data class QuickTileSpec(
        val state: QuickTileVisualState,
        val subtitle: String,
    )

    fun evaluateAutoEnterDex(input: AutoEnterDexInput): AutoEnterDecision {
        if (!input.autoEnterEnabled ||
            !input.isColdLaunch ||
            input.launchedFromDesktop ||
            input.bypassAutoEnter ||
            input.userManuallyCancelled ||
            input.userStopping ||
            input.remoteCastConnected ||
            input.physicalExternalActive ||
            input.hasPendingPairing
        ) {
            return AutoEnterDecision.STAY_ON_CONSOLE
        }
        return if (input.engineConnected) {
            AutoEnterDecision.ENTER_DEX
        } else {
            AutoEnterDecision.WAIT_FOR_ENGINE
        }
    }

    fun computeStartupWaitBudgetMs(
        autoShizukuCandidate: Boolean,
        autoRootCandidate: Boolean,
    ): Long = if (autoShizukuCandidate || autoRootCandidate) {
        SILENT_BOOTSTRAP_WAIT_BUDGET_MS
    } else {
        EXISTING_DAEMON_WAIT_BUDGET_MS
    }

    fun resolveEnterDexTileState(
        status: DaemonStatus,
        scene: CompositorScene,
        remote: RemoteStreamStatus,
    ): QuickTileSpec {
        if (!status.connected) {
            return QuickTileSpec(QuickTileVisualState.INACTIVE, "未激活 · 点按激活")
        }
        if (remote.connected) {
            return QuickTileSpec(QuickTileVisualState.UNAVAILABLE, "投屏锁定中")
        }
        val winCount = scene.windows.size
        val subtitle = if (winCount > 0) "$winCount 个窗口" else "引擎已就绪"
        return QuickTileSpec(QuickTileVisualState.ACTIVE, subtitle)
    }

    fun resolveHostScreenTileState(status: DaemonStatus): QuickTileSpec {
        if (!status.connected) {
            return QuickTileSpec(QuickTileVisualState.INACTIVE, "引擎未激活")
        }
        return if (status.hostScreenOff) {
            QuickTileSpec(QuickTileVisualState.ACTIVE, "已物理息屏")
        } else {
            QuickTileSpec(QuickTileVisualState.INACTIVE, "亮屏运行中")
        }
    }

    fun resolveWifiShareTileState(
        status: DaemonStatus,
        remote: RemoteStreamStatus,
        isReceivingPeerCast: Boolean,
    ): QuickTileSpec {
        if (!status.connected) {
            return QuickTileSpec(QuickTileVisualState.INACTIVE, "引擎未激活")
        }
        if (isReceivingPeerCast) {
            return QuickTileSpec(QuickTileVisualState.UNAVAILABLE, "接收投屏中")
        }
        if (!remote.wifiEnabled) {
            return QuickTileSpec(QuickTileVisualState.INACTIVE, "未开启")
        }
        val port = if (remote.wifiPort > 0) remote.wifiPort else 19878
        val subtitle = if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:$port" else "服务运行中"
        return QuickTileSpec(QuickTileVisualState.ACTIVE, subtitle)
    }
}
