package io.github.lengsukq.opaldesk

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import io.github.lengsukq.opaldesk.external.DexTrackpadScreen
import io.github.lengsukq.opaldesk.external.ExternalDesktopSession
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalMonitorPresentation
import io.github.lengsukq.opaldesk.external.ExternalTrackpadPolicy
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.DexImeRuntimeState
import io.github.lengsukq.opaldesk.ui.NativeImeHostController
import io.github.lengsukq.opaldesk.ui.OpalAmbientBackdrop
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlin.math.roundToInt

internal fun dispatchExternalDesktopKeyEvent(
    event: KeyEvent,
    runningInPresentationMode: Boolean,
    directImeVisible: Boolean,
    presentation: ExternalMonitorPresentation?,
    directImeController: NativeImeHostController?,
    superDispatch: (KeyEvent) -> Boolean,
): Boolean {
    if (BridgeClient.tryWakeHostScreenFromKeyEvent(event)) return true
    if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
    if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true
    if (WorkstationKeyDispatcher.handleActiveSpotlightKeyEvent(event)) return true

    val hostSoftImeVisible = directImeVisible || (presentation?.isImeActive == true)
    val isPhysicalKey = WorkstationKeyDispatcher.isPhysicalKeyboardEvent(event)
    val forwardImeSwitch = WorkstationKeyDispatcher.shouldForwardImeSwitchToWindow(
        localComposeTextFieldFocused = OpalImeFocusTracker.localComposeTextFieldFocused,
        hostSoftImeVisible = hostSoftImeVisible,
        isPhysicalKeyEvent = isPhysicalKey,
    )

    if (WorkstationKeyDispatcher.isImeSwitchShortcut(event.keyCode, event.metaState)) {
        if (forwardImeSwitch && WorkstationKeyDispatcher.forwardToFocusedWindow(event, forwardImeSwitch = true)) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !OpalImeFocusTracker.localComposeTextFieldFocused && !isPhysicalKey) {
            val focused = BridgeClient.sceneFlow.value.focusedWindow
            if (focused != null && !focused.isMinimized) {
                if (runningInPresentationMode) {
                    presentation?.ensureImeHostActive()
                } else if (!directImeVisible) {
                    directImeController?.showKeyboard()
                }
            }
        }
        if (superDispatch(event)) return true
        if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
        return false
    }

    if (OpalImeFocusTracker.localComposeTextFieldFocused) {
        if (superDispatch(event)) return true
        if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && WorkstationKeyDispatcher.hasActiveLocalOverlay()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                WorkstationKeyDispatcher.dismissActiveLocalOverlay()
            }
            return true
        }
        return false
    }

    if (hostSoftImeVisible) {
        if (superDispatch(event)) return true
        if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
    }

    if (event.keyCode == KeyEvent.KEYCODE_BACK && WorkstationKeyDispatcher.hasActiveLocalOverlay()) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            WorkstationKeyDispatcher.dismissActiveLocalOverlay()
        }
        return true
    }

    if (WorkstationKeyDispatcher.forwardToFocusedWindow(event, forwardImeSwitch = forwardImeSwitch)) {
        return true
    }

    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            WorkstationKeyDispatcher.handleDesktopBack(
                hostSoftImeVisible = hostSoftImeVisible,
                onHideHostSoftIme = { directImeController?.hideKeyboard() },
            )
        }
        return true
    }

    if (superDispatch(event)) return true
    if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
    return false
}

internal fun sendBackToFocusedExternalWindow() {
    val focused = BridgeClient.sceneFlow.value.focusedWindow
    if (focused != null && !focused.isMinimized) {
        BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.SEND_BACK)
    }
}

internal fun sendKeyTapToFocusedExternalWindow(keyCode: Int, metaState: Int) {
    val focused = BridgeClient.sceneFlow.value.focusedWindow ?: return
    if (focused.isMinimized) return
    BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_DOWN, keyCode, metaState)
    BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_UP, keyCode, metaState)
}

internal fun commitTextToFocusedExternalWindow(ctx: Context, text: String) {
    WorkstationKeyDispatcher.commitTextToFocusedWindow(ctx, text)
}

@Composable
internal fun DirectExtendedImeAndFocusEffects(
    activity: ComponentActivity,
    runningInPresentationMode: Boolean,
    directLocalOverlayNeedsFocus: Boolean,
    localTextFieldFocused: Boolean,
    directImeVisible: Boolean,
    status: DaemonStatus,
    imeState: DexImeRuntimeState,
    scene: CompositorScene,
    imeRequest: BridgeClient.ImeBridgeRequest?,
    overscanPct: Float,
    curExtSession: ExternalDesktopSession,
    defaultW: Int,
    defaultH: Int,
    getDirectComposeView: () -> ComposeView?,
    imeController: NativeImeHostController,
) {
    val inputBindings by BridgeClient.inputBindingsFlow.collectAsState()
    val directImePendingShow by imeController.pendingShowFlow.collectAsState()
    val focusedLiveDisplayId = scene.focusedWindow
        ?.takeIf { !it.isMinimized && it.isLive }
        ?.displayId ?: -1
    val directNotFocusable = !runningInPresentationMode &&
        WorkstationKeyDispatcher.shouldSetCompositorWindowNotFocusable(
            focusedWindowDisplayId = focusedLiveDisplayId,
            keyboardRoutedDisplayId = scene.keyboardRoutedDisplayId,
            bridgeImeActive = imeState.bridgeImeActive ||
                imeState.effectiveWindowImePolicy == BridgeProtocol.DisplayImePolicy.LOCAL,
            localOverlayNeedsFocus = directLocalOverlayNeedsFocus,
            localTextFieldFocused = localTextFieldFocused,
            hostSoftImeVisible = directImeVisible || directImePendingShow,
            hasEnabledKeyBindings = io.github.lengsukq.opaldesk.input.hasEnabledKeyBinding(inputBindings),
            isForegroundActivityWindow = true,
        )
    LaunchedEffect(directNotFocusable) {
        setDirectWindowNotFocusable(activity.window, directNotFocusable)
    }
    LaunchedEffect(status.targetFps) {
        applyPreferredPeakDisplayMode(activity.window, activity.display, status.targetFps)
    }
    LaunchedEffect(overscanPct, curExtSession.isPhysicalDirectExtended, curExtSession.widthPx, curExtSession.heightPx) {
        val targetView = getDirectComposeView() ?: return@LaunchedEffect
        val lp = targetView.layoutParams as? FrameLayout.LayoutParams ?: return@LaunchedEffect
        val effPct = if (curExtSession.isPhysicalDirectExtended) overscanPct else 0f
        val baseW = curExtSession.widthPx.takeIf { it > 0 } ?: defaultW
        val baseH = curExtSession.heightPx.takeIf { it > 0 } ?: defaultH
        val (hPx, vPx) = ExternalDisplayPolicy.computeOverscanInsetsPx(baseW, baseH, effPct)
        if (lp.leftMargin != hPx || lp.topMargin != vPx || lp.rightMargin != hPx || lp.bottomMargin != vPx) {
            lp.setMargins(hPx, vPx, hPx, vPx)
            targetView.layoutParams = lp
        }
    }
    var lastHandledImeSeq by remember {
        mutableLongStateOf(BridgeClient.imeBridgeRequestFlow.value?.seq ?: 0L)
    }
    LaunchedEffect(imeRequest?.seq) {
        val req = imeRequest ?: return@LaunchedEffect
        if (req.seq <= lastHandledImeSeq) return@LaunchedEffect
        lastHandledImeSeq = req.seq
        if (io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.isPluginSessionConnected) {
            if (!req.show) {
                io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.hideSoftKeyboard()
                if (directImeVisible) imeController.hideKeyboard()
            }
            return@LaunchedEffect
        }
        if (req.show) {
            val activeHere = !activity.isFinishing && !activity.isDestroyed && !runningInPresentationMode &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            val focused = BridgeClient.sceneFlow.value.focusedWindow
            if (activeHere &&
                focused != null && !focused.isMinimized &&
                (req.windowId <= 0 || req.windowId == focused.windowId) &&
                !OpalImeFocusTracker.localComposeTextFieldFocused
            ) {
                setDirectWindowNotFocusable(activity.window, false)
                imeController.showKeyboard(req.inputType)
            }
        } else if (directImeVisible) {
            imeController.hideKeyboard()
        }
    }
    LaunchedEffect(scene.focusedWindow?.windowId, scene.focusedWindow?.isMinimized) {
        val focused = scene.focusedWindow
        if (focused == null || focused.isMinimized) {
            io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.hideSoftKeyboard()
            if (directImeVisible && !OpalImeFocusTracker.localComposeTextFieldFocused) {
                imeController.hideKeyboard()
            }
        }
    }
}

@Composable
internal fun DirectComposingTextPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isEmpty()) return
    Box(
        modifier = modifier
            .imePadding()
            .padding(bottom = 12.dp)
            .liquidGlassSurface(
                shape = CircleShape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 16.dp,
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        OpalText(text = text, fontSize = 14.sp, color = OpalPalette.TextPrimary)
    }
}

internal class PresentationTrackpadHostState {
    var visible by mutableStateOf(false)
        private set
    private var manuallyOpened = false
    private var manuallyClosed = false

    fun syncForEntryOrResume(autoShowEnabled: Boolean): Boolean {
        val shouldShow = ExternalTrackpadPolicy.shouldShowPresentationTrackpad(
            autoShowEnabled = autoShowEnabled,
            userManuallyOpened = manuallyOpened,
            userManuallyClosed = manuallyClosed,
        )
        visible = shouldShow
        return shouldShow
    }

    fun setManualVisibility(show: Boolean): Boolean {
        val changed = visible != show
        manuallyOpened = show
        manuallyClosed = !show
        visible = show
        return changed
    }
}

internal fun bindPresentationTrackpadContent(
    activity: ComponentActivity,
    pres: ExternalMonitorPresentation,
    trackpadState: PresentationTrackpadHostState,
    getPresentationSummary: () -> String,
    getTargetDisplayId: () -> Int,
    getActiveIsWirelessDisplay: () -> Boolean,
    onOpenTrackpad: () -> Unit,
    onDismissPresentation: () -> Unit,
) {
    activity.setContent {
        OpalTheme {
            val isDark = OpalPalette.isDark
            LaunchedEffect(isDark) {
                val ctrl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                ctrl.isAppearanceLightStatusBars = !isDark
                ctrl.isAppearanceLightNavigationBars = !isDark
            }
            val isWireless = getActiveIsWirelessDisplay()
            if (trackpadState.visible) {
                val cursor = pres.cursorPosState.value
                DexTrackpadScreen(
                    monitorSummary = getPresentationSummary(),
                    externalDisplayId = getTargetDisplayId(),
                    cursorX = cursor.x.roundToInt(),
                    cursorY = cursor.y.roundToInt(),
                    badgePrefix = if (isWireless) "无线电视" else "独立满屏",
                    showOverscanControl = true,
                    isWirelessDisplay = isWireless,
                    onMoveCursor = { dx, dy -> pres.moveCursorBy(dx, dy) },
                    onSetPrimaryButton = { down -> pres.setPrimaryButton(down) },
                    onTapPrimary = { pres.tapPrimary() },
                    onScroll = { h, v -> pres.scrollAtCursor(h, v) },
                    onSecondaryAction = ::sendBackToFocusedExternalWindow,
                    onSendKeyTap = ::sendKeyTapToFocusedExternalWindow,
                    onCommitTextToFocusedWindow = { commitTextToFocusedExternalWindow(activity, it) },
                    onSwitchToLocalDesktop = {
                        onDismissPresentation()
                        activity.startActivity(
                            Intent(activity, DesktopActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            },
                        )
                        activity.finishAndRemoveTask()
                    },
                    onOpenConsole = { openConsoleOnPrimaryDisplay(activity) },
                    onExitExternal = { activity.finishAndRemoveTask() },
                )
            } else {
                PresentationStandbyScreen(
                    monitorSummary = getPresentationSummary(),
                    badgePrefix = if (isWireless) "无线电视" else "独立满屏",
                    onOpenTrackpad = onOpenTrackpad,
                    onOpenConsole = { openConsoleOnPrimaryDisplay(activity) },
                    onExitExternal = { activity.finishAndRemoveTask() },
                )
            }
        }
    }
}

@Composable
private fun PresentationStandbyScreen(
    monitorSummary: String,
    badgePrefix: String,
    onOpenTrackpad: () -> Unit,
    onOpenConsole: () -> Unit,
    onExitExternal: () -> Unit,
) {
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    OpalAmbientBackdrop(
        modifier = Modifier.fillMaxSize(),
        wallpaperPreset = wallpaperPreset,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(24.dp),
                        fill = OpalPalette.LiquidPopoverFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 16.dp,
                    )
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OpalStatusBadge(
                    label = "$badgePrefix · ${monitorSummary.ifBlank { "外接屏运行中" }}",
                    color = OpalPalette.AccentEmerald,
                )
                OpalText(
                    text = "外接屏桌面运行中",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                )
                OpalText(
                    text = "已关闭自动打开手机触控板，可直接使用外接键鼠操作，或随时点击下方按钮手动开启触控板。",
                    fontSize = 13.sp,
                    color = OpalPalette.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OpalButton(label = "打开触控板", filled = true, onClick = onOpenTrackpad)
                    OpalButton(label = "返回控制台", filled = false, onClick = onOpenConsole)
                    OpalButton(label = "退出投屏", filled = false, accent = OpalPalette.AccentDanger, onClick = onExitExternal)
                }
            }
        }
    }
}
