package io.github.lengsukq.opaldesk.external

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.core.view.WindowCompat
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.DesktopActivity
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import kotlin.math.roundToInt

/**
 * Display 0 Precision Multi-Touch Trackpad & Control Deck supporting BOTH:
 * 1. Physical Direct Extended external monitors (USB-C / HDMI where [io.github.lengsukq.opaldesk.ExternalDesktopActivity]
 *    runs directly on Display N > 0), dispatching zero-lag in-process cursor/touch/scroll via [ExternalTrackpadBridge].
 * 2. Active Wireless / USB OpalCast desktop sessions, injecting 120Hz `FLAG_ONEWAY` events onto `OpalDesk-Remote`.
 *
 * Lives on its own Display 0 task (`taskAffinity=".trackpad"`) so it never collides with
 * [io.github.lengsukq.opaldesk.ExternalDesktopActivity] on the external or remote display.
 */
class RemoteTrackpadActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(OpalLocalePolicy.wrapContext(newBase))
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val remoteCursorPosState = mutableStateOf(Offset(960f, 540f))
    private var isRemotePrimaryPressed = false

    private val trackpadCloser: () -> Unit = {
        runOnUiThread { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val initialTarget = ExternalTrackpadPolicy.resolveTrackpadTarget(
            remote = BridgeClient.remoteStatusFlow.value,
            session = ExternalTrackpadBridge.sessionFlow.value,
        )
        when (initialTarget) {
            TrackpadTargetMode.NONE -> {
                Toast.makeText(this, getString(R.string.toast_not_in_external_or_cast), Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            TrackpadTargetMode.PHYSICAL_PRESENTATION -> {
                ExternalTrackpadLauncher.openPhoneTrackpad(this)
                finish()
                return
            }
            TrackpadTargetMode.REMOTE_CAST -> {
                val initialRemote = BridgeClient.remoteStatusFlow.value
                val initW = initialRemote.width.coerceAtLeast(640)
                val initH = initialRemote.height.coerceAtLeast(480)
                val center = Offset(initW * 0.5f, initH * 0.5f)
                remoteCursorPosState.value = center
                BridgeClient.updateRemoteTrackpadCursor(center)
            }
            TrackpadTargetMode.PHYSICAL_DIRECT_EXTENDED -> Unit
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    sendBackToFocusedWindow()
                }
            },
        )

        setContent {
            OpalTheme {
                val isDark = OpalPalette.isDark
                val remote by BridgeClient.remoteStatusFlow.collectAsState()
                val extSession by ExternalTrackpadBridge.sessionFlow.collectAsState()
                val directCursorPair by ExternalTrackpadBridge.directCursorFlow.collectAsState()
                val targetMode = ExternalTrackpadPolicy.resolveTrackpadTarget(remote, extSession)

                LaunchedEffect(isDark) {
                    val ctrl = WindowCompat.getInsetsController(window, window.decorView)
                    ctrl.isAppearanceLightStatusBars = !isDark
                    ctrl.isAppearanceLightNavigationBars = !isDark
                }

                LaunchedEffect(targetMode) {
                    if (targetMode == TrackpadTargetMode.NONE) {
                        finish()
                    } else if (targetMode == TrackpadTargetMode.PHYSICAL_PRESENTATION) {
                        ExternalTrackpadLauncher.openPhoneTrackpad(this@RemoteTrackpadActivity)
                        finish()
                    }
                }

                if (targetMode == TrackpadTargetMode.PHYSICAL_DIRECT_EXTENDED) {
                    val summary = extSession.summary.ifBlank { "外接扩展屏" }
                    DexTrackpadScreen(
                        monitorSummary = summary,
                        externalDisplayId = extSession.displayId,
                        cursorX = directCursorPair.first.roundToInt(),
                        cursorY = directCursorPair.second.roundToInt(),
                        badgePrefix = if (extSession.isWirelessDisplay) "无线电视" else "外接扩展屏",
                        allowSwitchToLocalDesktop = true,
                        exitButtonLabel = "退出投射",
                        showOverscanControl = true,
                        isWirelessDisplay = extSession.isWirelessDisplay,
                        onMoveCursor = { dx, dy -> ExternalTrackpadBridge.getDirectSink()?.moveCursorBy(dx, dy) },
                        onSetPrimaryButton = { down -> ExternalTrackpadBridge.getDirectSink()?.setPrimaryButton(down) },
                        onTapPrimary = { ExternalTrackpadBridge.getDirectSink()?.tapPrimary() },
                        onScroll = { h, v -> ExternalTrackpadBridge.getDirectSink()?.scrollAtCursor(h, v) },
                        onSecondaryAction = ::sendBackToFocusedWindow,
                        onSendKeyTap = ::sendKeyTapToFocusedWindow,
                        onCommitTextToFocusedWindow = ::commitTextToFocusedWindow,
                        onSwitchToLocalDesktop = {
                            ExternalTrackpadBridge.getDirectSink()?.closeExternalDesktop()
                            startActivity(
                                Intent(this, DesktopActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                },
                            )
                            finish()
                        },
                        onOpenConsole = ::openConsole,
                        onExitExternal = {
                            ExternalTrackpadBridge.getDirectSink()?.closeExternalDesktop()
                            finish()
                        },
                    )
                } else {
                    val cursor = remoteCursorPosState.value
                    val targetW = remote.width.coerceAtLeast(640)
                    val targetH = remote.height.coerceAtLeast(480)
                    val fps = (remote.fpsX100 / 100).coerceAtLeast(30)
                    val summary = "${remote.peerName.ifBlank { "投屏端" }} · ${targetW}×${targetH} @ ${fps}Hz"

                    DexTrackpadScreen(
                        monitorSummary = summary,
                        externalDisplayId = remote.desktopDisplayId,
                        cursorX = cursor.x.roundToInt(),
                        cursorY = cursor.y.roundToInt(),
                        badgePrefix = remote.channelLabel,
                        allowSwitchToLocalDesktop = false,
                        exitButtonLabel = "断开投屏",
                        onMoveCursor = { dx, dy -> moveRemoteCursorBy(dx, dy, targetW, targetH) },
                        onSetPrimaryButton = { down -> setRemotePrimaryButton(down) },
                        onTapPrimary = { tapRemotePrimary() },
                        onScroll = { h, v -> scrollRemoteAtCursor(h, v) },
                        onSecondaryAction = ::sendBackToFocusedWindow,
                        onSendKeyTap = ::sendKeyTapToFocusedWindow,
                        onCommitTextToFocusedWindow = ::commitTextToFocusedWindow,
                        onSwitchToLocalDesktop = { /* Locked while casting */ },
                        onOpenConsole = ::openConsole,
                        onExitExternal = {
                            BridgeClient.disconnectRemoteStreamAsync()
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ExternalTrackpadBridge.setPhoneTrackpadVisible(true, trackpadCloser)
        BridgeClient.requestBinder(this)
        Thread({
            val latestRemote = BridgeClient.refreshRemoteStatus()
            val target = ExternalTrackpadPolicy.resolveTrackpadTarget(
                remote = latestRemote,
                session = ExternalTrackpadBridge.sessionFlow.value,
            )
            if (target == TrackpadTargetMode.NONE) {
                runOnUiThread { finish() }
            }
        }, "OpalRemoteTrackpadSync").start()
    }

    override fun onPause() {
        ExternalTrackpadBridge.setPhoneTrackpadVisible(false, trackpadCloser)
        super.onPause()
    }

    override fun onDestroy() {
        ExternalTrackpadBridge.setPhoneTrackpadVisible(false, trackpadCloser)
        BridgeClient.updateRemoteTrackpadCursor(null)
        ExternalTrackpadBridge.getDirectSink()?.hideCursor()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BridgeClient.tryWakeHostScreenFromKeyEvent(event)) return true
        if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true
        if (OpalImeFocusTracker.localComposeTextFieldFocused && super.dispatchKeyEvent(event)) return true
        val forwardImeSwitch = WorkstationKeyDispatcher.shouldForwardImeSwitchToWindow(
            localComposeTextFieldFocused = OpalImeFocusTracker.localComposeTextFieldFocused,
            hostSoftImeVisible = false,
            isPhysicalKeyEvent = WorkstationKeyDispatcher.isPhysicalKeyboardEvent(event),
        )
        if (WorkstationKeyDispatcher.forwardToFocusedWindow(event, forwardImeSwitch = forwardImeSwitch)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun moveRemoteCursorBy(dx: Float, dy: Float, dispW: Int, dispH: Int) {
        val cur = remoteCursorPosState.value
        val (nx, ny) = ExternalTrackpadPolicy.clampCursorPosition(cur.x + dx, cur.y + dy, dispW, dispH)
        val next = Offset(nx, ny)
        remoteCursorPosState.value = next
        BridgeClient.updateRemoteTrackpadCursor(next)
        if (isRemotePrimaryPressed) {
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_MOVE,
                x = nx,
                y = ny,
                buttonState = MotionEvent.BUTTON_PRIMARY,
            )
        } else {
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_HOVER_MOVE,
                x = nx,
                y = ny,
                buttonState = 0,
            )
        }
    }

    private fun setRemotePrimaryButton(pressed: Boolean) {
        val pos = remoteCursorPosState.value
        if (pressed && !isRemotePrimaryPressed) {
            isRemotePrimaryPressed = true
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_DOWN,
                x = pos.x,
                y = pos.y,
                buttonState = MotionEvent.BUTTON_PRIMARY,
            )
        } else if (!pressed && isRemotePrimaryPressed) {
            isRemotePrimaryPressed = false
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_UP,
                x = pos.x,
                y = pos.y,
                buttonState = 0,
            )
        }
    }

    private fun tapRemotePrimary() {
        val pos = remoteCursorPosState.value
        isRemotePrimaryPressed = false
        BridgeClient.injectRemoteTrackpadOneway(
            action = MotionEvent.ACTION_DOWN,
            x = pos.x,
            y = pos.y,
            buttonState = MotionEvent.BUTTON_PRIMARY,
        )
        mainHandler.postDelayed({
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_UP,
                x = pos.x,
                y = pos.y,
                buttonState = 0,
            )
        }, 20L)
    }

    private fun scrollRemoteAtCursor(hScroll: Float, vScroll: Float) {
        val pos = remoteCursorPosState.value
        BridgeClient.injectRemoteTrackpadOneway(
            action = MotionEvent.ACTION_SCROLL,
            x = pos.x,
            y = pos.y,
            hScroll = hScroll,
            vScroll = vScroll,
            buttonState = 0,
        )
    }

    private fun sendBackToFocusedWindow() {
        val focused = BridgeClient.sceneFlow.value.focusedWindow
        if (focused != null && !focused.isMinimized) {
            BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.SEND_BACK)
        }
    }

    private fun sendKeyTapToFocusedWindow(keyCode: Int, metaState: Int) {
        val focused = BridgeClient.sceneFlow.value.focusedWindow ?: return
        if (focused.isMinimized) return
        BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_DOWN, keyCode, metaState)
        BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_UP, keyCode, metaState)
    }

    private fun commitTextToFocusedWindow(text: String) {
        WorkstationKeyDispatcher.commitTextToFocusedWindow(this, text)
    }

    private fun openConsole() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }
}
