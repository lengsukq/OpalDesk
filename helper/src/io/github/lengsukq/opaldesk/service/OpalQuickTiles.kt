package io.github.lengsukq.opaldesk.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.DesktopActivity
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import java.util.concurrent.Executors

/**
 * Shared base class for OpalDesk Android Quick Settings Tiles (`TileService`), handling:
 * - Cold-process daemon Binder refresh on `onStartListening()`
 * - Unified state/subtitle rendering via [QuickEntryPolicy]
 * - API 34+ `startActivityAndCollapse(PendingIntent)` & legacy fallback
 */
abstract class BaseOpalTileService : TileService() {
    companion object {
        private val tileIoExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "OpalQuickTileIo").apply { isDaemon = true }
        }
        private val mainHandler = Handler(Looper.getMainLooper())

        fun requestAllTilesUpdate(ctx: Context?) {
            val appCtx = ctx?.applicationContext ?: return
            val tileClasses = listOf(
                EnterDexTileService::class.java,
                HostScreenOffTileService::class.java,
                WifiShareTileService::class.java,
            )
            for (cls in tileClasses) {
                runCatching {
                    requestListeningState(appCtx, ComponentName(appCtx, cls))
                }
            }
        }
    }

    protected abstract fun computeTileSpec(): QuickEntryPolicy.QuickTileSpec

    override fun onStartListening() {
        super.onStartListening()
        BridgeClient.bindAppContext(applicationContext)
        renderFromFlows()
        if (BridgeService.sUserStopping) return
        tileIoExec.execute {
            if (BridgeClient.isBinderAliveFast()) {
                BridgeClient.refreshStatus()
                BridgeClient.refreshSceneSnapshot()
                BridgeClient.refreshRemoteStatus()
            } else {
                BridgeClient.connectAndRefreshStatusSync(applicationContext, timeoutMs = 700L)
            }
            mainHandler.post { renderFromFlows() }
        }
    }

    protected fun renderFromFlows() {
        val tile = qsTile ?: return
        val spec = computeTileSpec()
        tile.state = when (spec.state) {
            QuickEntryPolicy.QuickTileVisualState.ACTIVE -> Tile.STATE_ACTIVE
            QuickEntryPolicy.QuickTileVisualState.INACTIVE -> Tile.STATE_INACTIVE
            QuickEntryPolicy.QuickTileVisualState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        tile.subtitle = spec.subtitle
        runCatching { tile.updateTile() }
    }

    protected fun runOnIo(block: () -> Unit) {
        tileIoExec.execute(block)
    }

    protected fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    protected fun showToast(message: String) {
        runOnMain {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    protected fun launchAndCollapse(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                (intent.action ?: intent.component?.className ?: "opal_tile").hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    protected fun openActivationConsole() {
        launchAndCollapse(
            MainActivity.createLaunchIntent(
                ctx = this,
                bypassAutoEnterDex = true,
                openActivation = true,
            ),
        )
    }
}

/** Quick Settings Tile: Enter OpalDesk local DeX workstation (or jump to activation if inactive). */
class EnterDexTileService : BaseOpalTileService() {
    override fun computeTileSpec(): QuickEntryPolicy.QuickTileSpec =
        QuickEntryPolicy.resolveEnterDexTileState(
            status = BridgeClient.statusFlow.value,
            scene = BridgeClient.sceneFlow.value,
            remote = BridgeClient.remoteStatusFlow.value,
        )

    override fun onClick() {
        super.onClick()
        BridgeService.sUserStopping = false
        BridgeClient.bindAppContext(applicationContext)
        BridgeService.ensureStarted(applicationContext)

        val status = BridgeClient.statusFlow.value
        val remote = BridgeClient.remoteStatusFlow.value
        if (BridgeClient.isBinderAliveFast() && status.connected) {
            if (remote.connected) {
                showToast("投屏进行中，无法进入本机桌面")
                launchAndCollapse(
                    MainActivity.createLaunchIntent(
                        ctx = this,
                        bypassAutoEnterDex = true,
                    ),
                )
            } else {
                OpalLogStore.i("Tile", "EnterDexTileService -> launching DesktopActivity")
                launchAndCollapse(
                    Intent(this, DesktopActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                )
            }
            return
        }

        OpalLogStore.i("Tile", "EnterDexTileService -> routing via QuickActionActivity")
        launchAndCollapse(QuickActionActivity.createIntent(this, QuickActionActivity.ACTION_ENTER_DEX))
    }
}

/** Quick Settings Tile: Toggle physical Display 0 screen power off/on while casting or running DeX. */
class HostScreenOffTileService : BaseOpalTileService() {
    override fun computeTileSpec(): QuickEntryPolicy.QuickTileSpec =
        QuickEntryPolicy.resolveHostScreenTileState(BridgeClient.statusFlow.value)

    override fun onClick() {
        super.onClick()
        BridgeService.sUserStopping = false
        BridgeClient.bindAppContext(applicationContext)
        val curStatus = BridgeClient.statusFlow.value
        if (!curStatus.connected || !BridgeClient.isBinderAliveFast()) {
            showToast("多窗引擎尚未激活，请先激活")
            openActivationConsole()
            return
        }
        val nextScreenOn = curStatus.hostScreenOff
        OpalLogStore.i("Tile", "HostScreenOffTileService -> setHostScreenPower(on=$nextScreenOn)")
        BridgeClient.setHostScreenPowerAsync(nextScreenOn) { ok ->
            renderFromFlows()
            requestAllTilesUpdate(applicationContext)
            val msg = when {
                !ok -> "当前设备暂不支持物理息屏控制"
                nextScreenOn -> "已点亮本机屏幕"
                else -> "已物理息屏（按电源键或音量键可随时唤醒）"
            }
            showToast(msg)
        }
    }
}

/** Quick Settings Tile: Toggle LAN TLS Wireless Share service (port 19878). */
class WifiShareTileService : BaseOpalTileService() {
    override fun computeTileSpec(): QuickEntryPolicy.QuickTileSpec =
        QuickEntryPolicy.resolveWifiShareTileState(
            status = BridgeClient.statusFlow.value,
            remote = BridgeClient.remoteStatusFlow.value,
            isReceivingPeerCast = OpalPeerCastMutex.roleFlow.value.isReceiving,
        )

    override fun onClick() {
        super.onClick()
        BridgeService.sUserStopping = false
        BridgeClient.bindAppContext(applicationContext)
        val curStatus = BridgeClient.statusFlow.value
        if (!curStatus.connected || !BridgeClient.isBinderAliveFast()) {
            showToast("多窗引擎尚未激活，请先激活")
            openActivationConsole()
            return
        }

        val toggleAction = Runnable {
            val role = OpalPeerCastMutex.roleFlow.value
            val enable = !BridgeClient.remoteStatusFlow.value.wifiEnabled
            if (enable) {
                val check = OpalPeerCastMutex.validateEnableSending(role.isReceiving, role.peerName)
                if (!check.allowed) {
                    showToast(check.reason)
                    renderFromFlows()
                    return@Runnable
                }
            }
            OpalLogStore.i("Tile", "WifiShareTileService -> setWifiStreamEnabled($enable)")
            BridgeClient.setWifiStreamEnabledAsync(enable, 19878) {
                val latest = BridgeClient.remoteStatusFlow.value
                OpalPeerDiscovery.syncAdvertise(
                    applicationContext,
                    latest.wifiEnabled,
                    latest.wifiPort,
                    latest.shortCertFingerprint,
                )
                renderFromFlows()
                requestAllTilesUpdate(applicationContext)
                val msg = if (latest.wifiEnabled) {
                    val port = if (latest.wifiPort > 0) latest.wifiPort else 19878
                    if (latest.lanIp.isNotEmpty()) "无线共享已开启 (${latest.lanIp}:$port)" else "无线共享已开启"
                } else {
                    "已关闭无线共享"
                }
                showToast(msg)
            }
        }

        if (isLocked) {
            unlockAndRun(toggleAction)
        } else {
            toggleAction.run()
        }
    }
}
