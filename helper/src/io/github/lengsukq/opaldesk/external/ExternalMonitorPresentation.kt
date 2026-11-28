package io.github.lengsukq.opaldesk.external

import android.app.Presentation
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.compositor.OpalCompositorWorkspace
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.NativeImeBridgeHost
import io.github.lengsukq.opaldesk.ui.NativeImeHostController
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import io.github.lengsukq.opaldesk.ui.rememberNativeImeHostController

/**
 * Full-bleed native external monitor compositor host built on standard public AOSP [Presentation].
 *
 * Uses its own independent [PresentationLifecycleOwner] so the external display's Compose tree stays
 * 100% live and interactive at peak refresh rate (e.g. 2560×1440 @ 100Hz) even when Display 0
 * switches to Home, OpalDesk Console, or other Android apps.
 *
 * Supports BOTH:
 * - Direct hardware mouse routed natively to the external display (auto-hides virtual cursor)
 * - Display 0 Precision Trackpad synthetic cursor/touch/scroll injection via [ExternalPointerDispatcher]
 */
class ExternalMonitorPresentation(
    hostActivity: ComponentActivity,
    private val targetDisplay: Display,
    private val displayLabelOverride: String? = null,
    private val primaryWidthPx: Int = 0,
    private val primaryHeightPx: Int = 0,
    private val onPickCustomWallpaper: () -> Unit,
    private val onOpenConsole: () -> Unit,
    private val onExitDesktop: () -> Unit,
) : Presentation(hostActivity, targetDisplay, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {

    private var activeDisplay: Display = targetDisplay
    private val initialBoundsPx: Pair<Int, Int> = resolveLogicalDisplaySizePx(targetDisplay, primaryWidthPx, primaryHeightPx)
    private var displayWidthPxState by mutableIntStateOf(initialBoundsPx.first)
    private var displayHeightPxState by mutableIntStateOf(initialBoundsPx.second)
    private var refreshRateHzState by mutableIntStateOf(resolveDisplayRefreshHz(targetDisplay))

    val displayWidthPx: Int get() = displayWidthPxState
    val displayHeightPx: Int get() = displayHeightPxState
    val refreshRateHz: Int get() = refreshRateHzState

    private val ownLifecycle = PresentationLifecycleOwner()
    private var rootContainer: HardwareMouseDetectingFrameLayout? = null
    private var composeView: ComposeView? = null
    private var imeVisible by mutableStateOf(false)
    private var localOverlayNeedsFocus by mutableStateOf(false)
    private var composingText by mutableStateOf("")
    private var imeController: NativeImeHostController? = null
    val isImeActive: Boolean get() = imeVisible
    private val pointerDispatcher = ExternalPointerDispatcher(
        defaultWidthPx = initialBoundsPx.first,
        defaultHeightPx = initialBoundsPx.second,
        targetViewProvider = { composeView },
    )

    val cursorPosState get() = pointerDispatcher.cursorPosState

    val monitorSummary: String
        get() {
            val label = displayLabelOverride?.takeIf { it.isNotBlank() }
                ?: activeDisplay.name?.takeIf { it.isNotBlank() }
                ?: "外接屏"
            return "$label · ${displayWidthPx}×${displayHeightPx} @ ${refreshRateHz}Hz"
        }

    fun refreshDisplayGeometry(
        freshDisplay: Display,
        primaryW: Int = primaryWidthPx,
        primaryH: Int = primaryHeightPx,
    ) {
        activeDisplay = freshDisplay
        val (w, h) = resolveLogicalDisplaySizePx(freshDisplay, primaryW, primaryH)
        val hz = resolveDisplayRefreshHz(freshDisplay)
        displayWidthPxState = w
        displayHeightPxState = h
        refreshRateHzState = hz
        pointerDispatcher.updateDefaultBounds(w, h)
        applyPreferredRefreshRate(BridgeClient.statusFlow.value.targetFps)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownLifecycle.onCreate()

        window?.let { win ->
            win.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            applyPreferredRefreshRate(BridgeClient.statusFlow.value.targetFps)

            WindowCompat.setDecorFitsSystemWindows(win, false)
            val decor = win.decorView
            decor.setViewTreeLifecycleOwner(ownLifecycle)
            decor.setViewTreeViewModelStoreOwner(ownLifecycle)
            decor.setViewTreeSavedStateRegistryOwner(ownLifecycle)
        }

        val initialOverscan = AppPrefs.getExternalOverscanPct(context)
        val (initInsetH, initInsetV) = ExternalDisplayPolicy.computeOverscanInsetsPx(
            widthPx = displayWidthPx,
            heightPx = displayHeightPx,
            overscanPct = initialOverscan,
        )
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ).apply {
                setMargins(initInsetH, initInsetV, initInsetH, initInsetV)
            }
            setContent {
                OpalTheme {
                    val uiScale by AppPrefs.externalUiScaleFlow.collectAsState()
                    val overscanPct by AppPrefs.externalOverscanPctFlow.collectAsState()
                    val status by BridgeClient.statusFlow.collectAsState()
                    val imeState by BridgeClient.imeStateFlow.collectAsState()
                    val scene by BridgeClient.sceneFlow.collectAsState()
                    val imeRequest by BridgeClient.imeBridgeRequestFlow.collectAsState()
                    val localTextFieldFocused by OpalImeFocusTracker.localComposeTextFieldFocusedFlow.collectAsState()
                    val pluginState by io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.pluginStateFlow.collectAsState()
                    val pluginSoftKbdVisible by io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.softKeyboardVisibleFlow.collectAsState()
                    val inputBindings by BridgeClient.inputBindingsFlow.collectAsState()
                    val controller = rememberNativeImeHostController()
                    imeController = controller
                    val focusedLiveDisplayId = scene.focusedWindow
                        ?.takeIf { !it.isMinimized && it.isLive }
                        ?.displayId ?: -1
                    val avoidFocusSteal = WorkstationKeyDispatcher.shouldSetCompositorWindowNotFocusable(
                        focusedWindowDisplayId = focusedLiveDisplayId,
                        keyboardRoutedDisplayId = scene.keyboardRoutedDisplayId,
                        bridgeImeActive = imeState.bridgeImeActive ||
                            imeState.effectiveWindowImePolicy == io.github.lengsukq.opaldesk.protocol.BridgeProtocol.DisplayImePolicy.LOCAL,
                        localOverlayNeedsFocus = localOverlayNeedsFocus,
                        localTextFieldFocused = localTextFieldFocused,
                        hostSoftImeVisible = imeVisible,
                        hasEnabledKeyBindings = io.github.lengsukq.opaldesk.input.hasEnabledKeyBinding(inputBindings),
                    )
                    val showVirtualCursor = pointerDispatcher.showVirtualCursorState.value
                    LaunchedEffect(showVirtualCursor) {
                        rootContainer?.syncSystemPointerIcon()
                    }
                    LaunchedEffect(avoidFocusSteal) {
                        setPresentationNotFocusable(avoidFocusSteal)
                    }
                    LaunchedEffect(status.targetFps) {
                        applyPreferredRefreshRate(status.targetFps)
                    }
                    LaunchedEffect(overscanPct, displayWidthPx, displayHeightPx) {
                        val targetView = composeView ?: return@LaunchedEffect
                        val lp = targetView.layoutParams as? FrameLayout.LayoutParams ?: return@LaunchedEffect
                        val (hPx, vPx) = ExternalDisplayPolicy.computeOverscanInsetsPx(
                            widthPx = displayWidthPx,
                            heightPx = displayHeightPx,
                            overscanPct = overscanPct,
                        )
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
                                if (imeVisible) controller.hideKeyboard()
                            }
                            return@LaunchedEffect
                        }
                        if (req.show) {
                            val activeHere = isShowing &&
                                ownLifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                            val focused = BridgeClient.sceneFlow.value.focusedWindow
                            if (activeHere &&
                                focused != null && !focused.isMinimized &&
                                (req.windowId <= 0 || req.windowId == focused.windowId) &&
                                !OpalImeFocusTracker.localComposeTextFieldFocused
                            ) {
                                setPresentationNotFocusable(false)
                                controller.showKeyboard(req.inputType)
                            }
                        } else if (imeVisible) {
                            controller.hideKeyboard()
                        }
                    }
                    LaunchedEffect(scene.focusedWindow?.windowId, scene.focusedWindow?.isMinimized) {
                        val focused = scene.focusedWindow
                        if (focused == null || focused.isMinimized) {
                            io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.hideSoftKeyboard()
                            if (imeVisible && !OpalImeFocusTracker.localComposeTextFieldFocused) {
                                controller.hideKeyboard()
                            }
                        }
                    }
                    val baseDensity = LocalDensity.current
                    val effDensity = Density(
                        density = (baseDensity.density * uiScale).coerceIn(0.85f, 3.5f),
                        fontScale = baseDensity.fontScale,
                    )
                    CompositionLocalProvider(LocalDensity provides effDensity) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NativeImeBridgeHost(
                                controller = controller,
                                hostWindow = window,
                                onSendKey = { action, keyCode, meta ->
                                    val focused = BridgeClient.sceneFlow.value.focusedWindow
                                    if (focused != null && !focused.isMinimized) {
                                        BridgeClient.injectWindowKeyAsync(focused.windowId, action, keyCode, meta)
                                    }
                                },
                                onCommitText = { txt ->
                                    WorkstationKeyDispatcher.commitTextToFocusedWindow(context, txt)
                                },
                                onComposingChanged = { composingText = it },
                                onImeVisibilityChanged = { vis ->
                                    imeVisible = vis
                                    if (!vis) composingText = ""
                                },
                                modifier = Modifier.align(Alignment.BottomStart),
                            )
                            OpalCompositorWorkspace(
                                isExternalMonitor = true,
                                externalMonitorSummary = monitorSummary,
                                imeActive = imeVisible || (pluginState.pluginConnected && pluginSoftKbdVisible),
                                onToggleSoftKeyboard = {
                                    if (io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.isPluginSessionConnected) {
                                        io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.toggleSoftKeyboard()
                                    } else {
                                        if (!imeVisible) setPresentationNotFocusable(false)
                                        controller.toggleKeyboard(imeVisible)
                                    }
                                },
                                onLocalOverlayNeedsFocusChanged = { localOverlayNeedsFocus = it },
                                virtualCursorPx = if (pointerDispatcher.showVirtualCursorState.value) {
                                    pointerDispatcher.cursorPosState.value
                                } else {
                                    null
                                },
                                onPickCustomWallpaper = onPickCustomWallpaper,
                                onOpenConsole = onOpenConsole,
                                onExitDesktop = onExitDesktop,
                            )
                            if (composingText.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
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
                                    OpalText(text = composingText, fontSize = 14.sp, color = OpalPalette.TextPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
        composeView = view

        val container = HardwareMouseDetectingFrameLayout(
            ctx = context,
            shouldSuppressSystemPointerIcon = { pointerDispatcher.showVirtualCursorState.value },
            onHardwareMouseSeen = { pointerDispatcher.hideCursor() },
        ).apply {
            setBackgroundColor(AndroidColor.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(view)
            syncSystemPointerIcon()
        }
        rootContainer = container
        setContentView(container)

        window?.let { win ->
            WindowCompat.getInsetsController(win, win.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ownLifecycle.onResume()
    }

    override fun onStop() {
        ownLifecycle.onStop()
        super.onStop()
    }

    override fun dismiss() {
        rootContainer = null
        composeView = null
        ownLifecycle.onDestroy()
        super.dismiss()
    }

    fun moveCursorBy(dxPx: Float, dyPx: Float) = pointerDispatcher.moveCursorBy(dxPx, dyPx)

    fun setPrimaryButton(pressed: Boolean) = pointerDispatcher.setPrimaryButton(pressed)

    fun tapPrimary() = pointerDispatcher.tapPrimary()

    fun scrollAtCursor(hScroll: Float, vScroll: Float) = pointerDispatcher.scrollAtCursor(hScroll, vScroll)

    fun hideCursor() = pointerDispatcher.hideCursor()

    fun ensureImeHostActive() {
        setPresentationNotFocusable(false)
        if (!imeVisible) {
            imeController?.showKeyboard()
        }
    }

    private fun setPresentationNotFocusable(notFocusable: Boolean) {
        val win = window ?: return
        val hasFlag = (win.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
        if (notFocusable == hasFlag) return
        if (notFocusable) {
            win.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Shield pure desktop state so clicking mouse side-back or KEYCODE_BACK never dismisses Presentation.
        WorkstationKeyDispatcher.handleDesktopBack(
            hostSoftImeVisible = imeVisible,
            onHideHostSoftIme = { imeController?.hideKeyboard() },
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true
        val isPhysicalKey = WorkstationKeyDispatcher.isPhysicalKeyboardEvent(event)
        val forwardImeSwitch = WorkstationKeyDispatcher.shouldForwardImeSwitchToWindow(
            localComposeTextFieldFocused = OpalImeFocusTracker.localComposeTextFieldFocused,
            hostSoftImeVisible = imeVisible,
            isPhysicalKeyEvent = isPhysicalKey,
        )
        if (WorkstationKeyDispatcher.isImeSwitchShortcut(event.keyCode, event.metaState)) {
            if (forwardImeSwitch && WorkstationKeyDispatcher.forwardToFocusedWindow(event, forwardImeSwitch = true)) {
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && !OpalImeFocusTracker.localComposeTextFieldFocused && !isPhysicalKey) {
                val focused = BridgeClient.sceneFlow.value.focusedWindow
                if (focused != null && !focused.isMinimized) ensureImeHostActive()
            }
            return super.dispatchKeyEvent(event)
        }
        if ((OpalImeFocusTracker.localComposeTextFieldFocused || imeVisible) && super.dispatchKeyEvent(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && WorkstationKeyDispatcher.hasActiveLocalOverlay()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                WorkstationKeyDispatcher.dismissActiveLocalOverlay()
            }
            return true
        }
        if (WorkstationKeyDispatcher.forwardToFocusedWindow(event, forwardImeSwitch = forwardImeSwitch)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                WorkstationKeyDispatcher.handleDesktopBack(
                    hostSoftImeVisible = imeVisible,
                    onHideHostSoftIme = { imeController?.hideKeyboard() },
                )
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun dispatchKeyEventToPresentation(event: KeyEvent): Boolean {
        val target = composeView ?: return false
        return target.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun resolveLogicalDisplaySizePx(
        display: Display,
        primaryW: Int = 0,
        primaryH: Int = 0,
    ): Pair<Int, Int> {
        val realPt = android.graphics.Point()
        runCatching { display.getRealSize(realPt) }
        val mode = display.mode
        val physW = mode?.physicalWidth ?: 0
        val physH = mode?.physicalHeight ?: 0
        val rotated = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        val modeW = if (rotated && physW >= 320 && physH >= 320) physH else physW
        val modeH = if (rotated && physW >= 320 && physH >= 320) physW else physH
        return ExternalDisplayPolicy.resolveEffectiveDisplaySize(
            modeW = modeW,
            modeH = modeH,
            realW = realPt.x,
            realH = realPt.y,
            primaryW = primaryW,
            primaryH = primaryH,
        )
    }

    private fun resolveDisplayRefreshHz(display: Display): Int =
        (resolvePeakRefreshRateMode(display)?.refreshRate
            ?: display.mode?.refreshRate
            ?: 60f).roundToInt().coerceAtLeast(24)

    private fun applyPreferredRefreshRate(targetFps: Int) {
        val win = window ?: return
        val disp = activeDisplay
        val cur = disp.mode ?: return
        val supported = disp.supportedModes ?: return
        val candidates = supported.map {
            ExternalDisplayPolicy.DisplayModeCandidate(
                modeId = it.modeId,
                width = it.physicalWidth,
                height = it.physicalHeight,
                refreshRate = it.refreshRate,
            )
        }
        val chosen = ExternalDisplayPolicy.selectDisplayModeForTargetFps(
            modes = candidates,
            currentWidth = cur.physicalWidth,
            currentHeight = cur.physicalHeight,
            targetFps = targetFps,
        )
        val lp = win.attributes
        if (chosen == null) {
            if (lp.preferredDisplayModeId != 0 || lp.preferredRefreshRate != 0f) {
                lp.preferredDisplayModeId = 0
                lp.preferredRefreshRate = 0f
                win.attributes = lp
            }
            return
        }
        if (lp.preferredDisplayModeId != chosen.modeId || kotlin.math.abs(lp.preferredRefreshRate - chosen.refreshRate) > 0.5f) {
            lp.preferredDisplayModeId = chosen.modeId
            lp.preferredRefreshRate = chosen.refreshRate
            win.attributes = lp
        }
    }

    private fun resolvePeakRefreshRateMode(display: Display): Display.Mode? {
        val cur = display.mode ?: return null
        val supported = display.supportedModes ?: return cur
        val candidates = supported.map {
            ExternalDisplayPolicy.DisplayModeCandidate(
                modeId = it.modeId,
                width = it.physicalWidth,
                height = it.physicalHeight,
                refreshRate = it.refreshRate,
            )
        }
        val chosen = ExternalDisplayPolicy.selectPreferredDisplayMode(
            modes = candidates,
            currentWidth = cur.physicalWidth,
            currentHeight = cur.physicalHeight,
            preferHighestResolution = false,
        ) ?: return cur
        return supported.firstOrNull { it.modeId == chosen.modeId } ?: cur
    }
}

private class PresentationLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    fun onStop() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    fun onDestroy() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED && lifecycleRegistry.currentState != Lifecycle.State.INITIALIZED) {
            onStop()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
    }
}
