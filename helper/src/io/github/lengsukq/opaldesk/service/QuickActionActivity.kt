package io.github.lengsukq.opaldesk.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.AdbBootstrap
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.DesktopActivity
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.adb.ShizukuBootstrap
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.OpalGlassCard
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight translucent trampoline Activity for Android Launcher long-press shortcuts and
 * cold-start Quick Settings Tile entry: verifies live daemon connectivity (or performs configured
 * silent Shizuku/Root bootstrap) before entering [DesktopActivity] or routing back to [MainActivity].
 */
class QuickActionActivity : ComponentActivity() {
    companion object {
        const val ACTION_ENTER_DEX = "io.github.lengsukq.opaldesk.action.ENTER_DEX"
        const val ACTION_ENTER_LANDSCAPE_DEX = "io.github.lengsukq.opaldesk.action.ENTER_LANDSCAPE_DEX"
        const val ACTION_OPEN_ACTIVATION = "io.github.lengsukq.opaldesk.action.OPEN_ACTIVATION"
        const val ACTION_OPEN_CONSOLE = "io.github.lengsukq.opaldesk.action.OPEN_CONSOLE"

        fun createIntent(ctx: Context, actionName: String): Intent =
            Intent(ctx, QuickActionActivity::class.java).apply {
                action = actionName
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
    }

    private val completed = AtomicBoolean(false)
    private var statusMessage by mutableStateOf("正在检查多窗引擎状态…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpalLogStore.init(applicationContext)
        BridgeClient.bindAppContext(applicationContext)

        val act = intent?.action ?: ACTION_ENTER_DEX
        when (act) {
            ACTION_OPEN_CONSOLE -> {
                startActivityAndFinish(
                    MainActivity.createLaunchIntent(this, bypassAutoEnterDex = true),
                )
                return
            }
            ACTION_OPEN_ACTIVATION -> {
                startActivityAndFinish(
                    MainActivity.createLaunchIntent(
                        ctx = this,
                        bypassAutoEnterDex = true,
                        openActivation = true,
                    ),
                )
                return
            }
        }

        val forceLandscape = act == ACTION_ENTER_LANDSCAPE_DEX
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)

        // Instant zero-flicker fast path when Binder is already alive
        if (BridgeClient.isBinderAliveFast() && BridgeClient.statusFlow.value.connected) {
            routeAfterEngineCheck(connected = true, remoteConnected = BridgeClient.remoteStatusFlow.value.connected, forceLandscape = forceLandscape)
            return
        }

        setContent {
            OpalTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OpalGlassCard(
                        modifier = Modifier.widthIn(max = 320.dp),
                        cornerRadius = 20.dp,
                        fillColor = OpalPalette.GlassPopover,
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OpalText(
                                text = "OpalDesk",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            OpalText(
                                text = statusMessage,
                                fontSize = 12.5.sp,
                                color = OpalPalette.TextSecondary,
                            )
                        }
                    }
                }
            }
        }

        Thread({
            var status = BridgeClient.connectAndRefreshStatusSync(applicationContext, timeoutMs = 950L)
            if (!status.connected && !isFinishing && !isDestroyed) {
                val triedSilent = trySilentAutoBootstrapBlocking()
                if (triedSilent) {
                    status = BridgeClient.connectAndRefreshStatusSync(applicationContext, timeoutMs = 900L)
                }
            }
            val remote = if (status.connected) BridgeClient.refreshRemoteStatus() else BridgeClient.remoteStatusFlow.value
            runOnUiThread {
                routeAfterEngineCheck(
                    connected = status.connected,
                    remoteConnected = remote.connected,
                    forceLandscape = forceLandscape,
                )
            }
        }, "OpalQuickAction").start()
    }

    private fun trySilentAutoBootstrapBlocking(): Boolean {
        val useShizuku = AppPrefs.isAutoShizukuEnabled(this) && ShizukuBootstrap.isReadyAndGranted()
        val useRoot = !useShizuku && AppPrefs.isAutoRootEnabled(this) && AdbBootstrap.hasSuBinaryHint()
        if (!useShizuku && !useRoot) return false

        runOnUiThread {
            statusMessage = if (useShizuku) "正在通过 Shizuku 自动激活引擎…" else "正在通过 Root 自动激活引擎…"
        }
        val latch = CountDownLatch(1)
        val listener = object : AdbBootstrap.ProgressListener {
            override fun onStep(message: String?) = Unit
            override fun onResult(success: Boolean, message: String?) {
                latch.countDown()
            }
        }
        if (useShizuku) {
            AdbBootstrap.shizukuBootstrapAsync(this, markAutoShizuku = true, listener = listener)
        } else {
            AdbBootstrap.rootBootstrapAsync(
                ctx = this,
                markAutoRoot = true,
                rootEnhance = AppPrefs.isRootEnhanceEnabled(this),
                listener = listener,
            )
        }
        runCatching { latch.await(4500L, TimeUnit.MILLISECONDS) }
        return true
    }

    private fun routeAfterEngineCheck(
        connected: Boolean,
        remoteConnected: Boolean,
        forceLandscape: Boolean,
    ) {
        if (!completed.compareAndSet(false, true)) return
        if (isFinishing || isDestroyed) return
        BaseOpalTileService.requestAllTilesUpdate(applicationContext)
        when {
            connected && !remoteConnected -> {
                startActivityAndFinish(
                    Intent(this, DesktopActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(DesktopActivity.EXTRA_FORCE_LANDSCAPE, forceLandscape)
                    },
                )
            }
            connected && remoteConnected -> {
                Toast.makeText(this, "投屏进行中，无法进入本机桌面", Toast.LENGTH_SHORT).show()
                startActivityAndFinish(
                    MainActivity.createLaunchIntent(this, bypassAutoEnterDex = true),
                )
            }
            else -> {
                Toast.makeText(this, "多窗引擎尚未激活，请先激活", Toast.LENGTH_SHORT).show()
                startActivityAndFinish(
                    MainActivity.createLaunchIntent(
                        ctx = this,
                        bypassAutoEnterDex = true,
                        openActivation = true,
                    ),
                )
            }
        }
    }

    private fun startActivityAndFinish(target: Intent) {
        startActivity(target)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
