package io.github.lengsukq.opaldesk

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lengsukq.opaldesk.compositor.OpalCompositorWorkspace
import io.github.lengsukq.opaldesk.external.DexTrackpadScreen
import io.github.lengsukq.opaldesk.external.DirectExternalTrackpadSink
import io.github.lengsukq.opaldesk.external.ExternalDesktopMode
import io.github.lengsukq.opaldesk.external.ExternalDesktopSession
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalMonitorPresentation
import io.github.lengsukq.opaldesk.external.ExternalPointerDispatcher
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.ExternalTrackpadLauncher
import io.github.lengsukq.opaldesk.external.ExternalTrackpadPolicy
import io.github.lengsukq.opaldesk.external.HardwareMouseDetectingFrameLayout
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.NativeImeBridgeHost
import io.github.lengsukq.opaldesk.ui.NativeImeHostController
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import io.github.lengsukq.opaldesk.ui.rememberNativeImeHostController
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Universal External Monitor Workstation Host (USB-C DisplayPort Alt-Mode / HDMI / Mac Remote Display).
 *
 * Automatically supports BOTH external display modes across Android 11–17:
 * 1. **Direct Extended Display Mode** (`currentDisplayId > 0`, e.g. Pixel / AOSP Desktop / OpalCast Remote VD):
 *    Renders [OpalCompositorWorkspace] natively directly on the external display while registering an
 *    in-process [DirectExternalTrackpadSink] so phone Display 0 can serve as a zero-lag precision trackpad.
 * 2. **Full-Bleed Presentation + Display 0 DeX Trackpad Mode** (`currentDisplayId == 0`, e.g. HyperOS / mirror ROMs):
 *    Opens a fullscreen [ExternalMonitorPresentation] on the physical external display while Display 0
 *    serves as a Samsung DeX-style precision multi-touch trackpad & monitor control deck.
 */
class ExternalDesktopActivity : ComponentActivity() {
    companion object {
        private const val TAG = "OpalExtDesktop"

        val isPresentationActive: Boolean
            get() = ExternalTrackpadBridge.sessionFlow.value.isPresentation

        val activeExternalDisplayId: Int
            get() = ExternalTrackpadBridge.sessionFlow.value.displayId
    }

    private val handler = Handler(Looper.getMainLooper())
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread({
            val saved = WallpaperStorage.importWallpaperFromUri(this, uri)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (saved != null) "已更换桌面壁纸" else "无法读取所选图片",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, "OpalWallpaperImport").start()
    }
    private var displayManager: DisplayManager? = null
    private var presentation: ExternalMonitorPresentation? = null
    private var directComposeView: ComposeView? = null
    private var directPointerDispatcher: ExternalPointerDispatcher? = null
    private var directTrackpadSink: DirectExternalTrackpadSink? = null
    private var targetDisplayId: Int = -1
    private var presentationSummary by mutableStateOf("")
    private var directDisplaySummary by mutableStateOf("")
    private var runningInPresentationMode by mutableStateOf(false)
    private var directImeVisible by mutableStateOf(false)
    private var directComposingText by mutableStateOf("")
    private var directImeController: NativeImeHostController? = null

    private val presentationTrackpadCloser: () -> Unit = {
        runOnUiThread { openConsoleOnPrimaryDisplay() }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (!runningInPresentationMode && targetDisplayId > Display.DEFAULT_DISPLAY && displayId == targetDisplayId) {
                refreshDirectSessionMetadata(targetDisplayId)
            }
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (targetDisplayId > Display.DEFAULT_DISPLAY && displayId == targetDisplayId) {
                dismissPresentation()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        BridgeClient.requestBinder(this)

        displayManager = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.also { dm ->
            dm.registerDisplayListener(displayListener, handler)
        }

        val currentDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        val requestedId = intent?.getIntExtra("targetDisplayId", -1) ?: -1
        val physicalExternal = findPhysicalExternalDisplay(requestedId)

        val usePresentation = (currentDisplayId == Display.DEFAULT_DISPLAY) && (physicalExternal != null)
        runningInPresentationMode = usePresentation

        if (currentDisplayId == Display.DEFAULT_DISPLAY && physicalExternal != null) {
            targetDisplayId = physicalExternal.displayId
            val pres = ExternalMonitorPresentation(
                hostActivity = this,
                targetDisplay = physicalExternal,
                onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                onOpenConsole = ::openConsoleOnPrimaryDisplay,
                onExitDesktop = { finish() },
            )
            try {
                pres.show()
                presentation = pres
                presentationSummary = pres.monitorSummary
                ExternalTrackpadBridge.publishSession(
                    owner = this,
                    session = ExternalDesktopSession(
                        mode = ExternalDesktopMode.PRESENTATION,
                        displayId = physicalExternal.displayId,
                        displayName = physicalExternal.name ?: "外接屏",
                        widthPx = pres.displayWidthPx,
                        heightPx = pres.displayHeightPx,
                        refreshRateHz = pres.refreshRateHz,
                        isRemoteCastDisplay = false,
                    ),
                )
                syncExternalMouseRouting(physicalExternal.displayId)
            } catch (t: Throwable) {
                Log.w(TAG, "Presentation.show failed, falling back to direct render: ${t.message}")
                runningInPresentationMode = false
                ExternalTrackpadBridge.clearSessionIfOwner(this)
            }
        } else if (currentDisplayId > Display.DEFAULT_DISPLAY) {
            targetDisplayId = currentDisplayId
            val session = publishDirectSession(currentDisplayId)
            syncExternalMouseRouting(currentDisplayId)
            if (ExternalTrackpadPolicy.shouldAutoLaunchPhoneTrackpad(
                    session = session,
                    autoShowEnabled = AppPrefs.isAutoShowPhoneTrackpadEnabled(this),
                    alreadyVisible = ExternalTrackpadBridge.phoneTrackpadVisibleFlow.value,
                )
            ) {
                handler.post {
                    ExternalTrackpadLauncher.openPhoneTrackpad(this)
                }
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    sendBackToFocusedWindow()
                }
            },
        )

        if (runningInPresentationMode && presentation != null) {
            bindPresentationTrackpadUi(presentation!!)
        } else {
            bindDirectExtendedWorkspaceUi(currentDisplayId)
            applyImmersiveFullscreen()
        }
    }

    private fun bindPresentationTrackpadUi(pres: ExternalMonitorPresentation) {
        setContent {
            OpalTheme {
                val isDark = OpalPalette.isDark
                LaunchedEffect(isDark) {
                    val ctrl = WindowCompat.getInsetsController(window, window.decorView)
                    ctrl.isAppearanceLightStatusBars = !isDark
                    ctrl.isAppearanceLightNavigationBars = !isDark
                }
                val cursor = pres.cursorPosState.value
                DexTrackpadScreen(
                    monitorSummary = presentationSummary,
                    externalDisplayId = targetDisplayId,
                    cursorX = cursor.x.roundToInt(),
                    cursorY = cursor.y.roundToInt(),
                    onMoveCursor = { dx, dy -> pres.moveCursorBy(dx, dy) },
                    onSetPrimaryButton = { down -> pres.setPrimaryButton(down) },
                    onTapPrimary = { pres.tapPrimary() },
                    onScroll = { h, v -> pres.scrollAtCursor(h, v) },
                    onSecondaryAction = ::sendBackToFocusedWindow,
                    onSendKeyTap = ::sendKeyTapToFocusedWindow,
                    onCommitTextToFocusedWindow = ::commitTextToFocusedWindow,
                    onSwitchToLocalDesktop = {
                        dismissPresentation()
                        startActivity(
                            Intent(this, DesktopActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            },
                        )
                        finish()
                    },
                    onOpenConsole = ::openConsoleOnPrimaryDisplay,
                    onExitExternal = { finish() },
                )
            }
        }
    }

    private fun bindDirectExtendedWorkspaceUi(currentDisplayId: Int) {
        val curMode = display?.mode
        val defaultW = curMode?.physicalWidth?.coerceAtLeast(640) ?: resources.displayMetrics.widthPixels.coerceAtLeast(640)
        val defaultH = curMode?.physicalHeight?.coerceAtLeast(480) ?: resources.displayMetrics.heightPixels.coerceAtLeast(480)
        val dispatcher = ExternalPointerDispatcher(
            defaultWidthPx = defaultW,
            defaultHeightPx = defaultH,
            targetViewProvider = { directComposeView },
            onCursorChanged = { x, y -> ExternalTrackpadBridge.updateDirectCursor(x, y) },
        )
        directPointerDispatcher = dispatcher

        val session = ExternalTrackpadBridge.sessionFlow.value
        if (currentDisplayId > Display.DEFAULT_DISPLAY && session.isPhysicalDirectExtended) {
            val sink = createDirectTrackpadSink(currentDisplayId, dispatcher)
            directTrackpadSink = sink
            ExternalTrackpadBridge.registerDirectSink(sink)
        }

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setContent {
                OpalTheme {
                    val uiScale by AppPrefs.externalUiScaleFlow.collectAsState()
                    val remoteCursor by BridgeClient.remoteTrackpadCursorFlow.collectAsState()
                    val scene by BridgeClient.sceneFlow.collectAsState()
                    val imeRequest by BridgeClient.imeBridgeRequestFlow.collectAsState()
                    val imeController = rememberNativeImeHostController()
                    directImeController = imeController
                    var lastHandledImeSeq by remember {
                        mutableLongStateOf(BridgeClient.imeBridgeRequestFlow.value?.seq ?: 0L)
                    }
                    LaunchedEffect(imeRequest?.seq) {
                        val req = imeRequest ?: return@LaunchedEffect
                        if (req.seq <= lastHandledImeSeq) return@LaunchedEffect
                        lastHandledImeSeq = req.seq
                        if (req.show) {
                            val focused = BridgeClient.sceneFlow.value.focusedWindow
                            if (focused != null && !focused.isMinimized
                                && (req.windowId <= 0 || req.windowId == focused.windowId)
                                && !OpalImeFocusTracker.localComposeTextFieldFocused
                            ) {
                                imeController.showKeyboard(req.inputType)
                            }
                        } else if (directImeVisible) {
                            imeController.hideKeyboard()
                        }
                    }
                    LaunchedEffect(scene.focusedWindow?.windowId, scene.focusedWindow?.isMinimized) {
                        val focused = scene.focusedWindow
                        if (directImeVisible && !OpalImeFocusTracker.localComposeTextFieldFocused && (focused == null || focused.isMinimized)) {
                            imeController.hideKeyboard()
                        }
                    }
                    val baseDensity = LocalDensity.current
                    val effDensity = Density(
                        density = (baseDensity.density * uiScale).coerceIn(0.85f, 3.5f),
                        fontScale = baseDensity.fontScale,
                    )
                    val directCursor = if (dispatcher.showVirtualCursorState.value) {
                        dispatcher.cursorPosState.value
                    } else {
                        null
                    }
                    CompositionLocalProvider(LocalDensity provides effDensity) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NativeImeBridgeHost(
                                controller = imeController,
                                onSendKey = { action, keyCode, meta ->
                                    val focused = BridgeClient.sceneFlow.value.focusedWindow
                                    if (focused != null && !focused.isMinimized) {
                                        BridgeClient.injectWindowKeyAsync(focused.windowId, action, keyCode, meta)
                                    }
                                },
                                onCommitText = { txt ->
                                    WorkstationKeyDispatcher.commitTextToFocusedWindow(this@ExternalDesktopActivity, txt)
                                },
                                onComposingChanged = { directComposingText = it },
                                onImeVisibilityChanged = { vis ->
                                    directImeVisible = vis
                                    if (!vis) directComposingText = ""
                                },
                                modifier = Modifier.align(Alignment.BottomStart),
                            )
                            OpalCompositorWorkspace(
                                isExternalMonitor = true,
                                externalMonitorSummary = directDisplaySummary,
                                imeActive = directImeVisible,
                                onToggleSoftKeyboard = { imeController.toggleKeyboard(directImeVisible) },
                                virtualCursorPx = directCursor ?: remoteCursor,
                                onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                                onOpenConsole = ::openConsoleOnPrimaryDisplay,
                                onExitDesktop = { finish() },
                            )
                            if (directComposingText.isNotEmpty()) {
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
                                    OpalText(text = directComposingText, fontSize = 14.sp, color = OpalPalette.TextPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
        directComposeView = view

        val rootContainer = HardwareMouseDetectingFrameLayout(this) {
            dispatcher.hideCursor()
            if (BridgeClient.remoteTrackpadCursorFlow.value != null) {
                BridgeClient.updateRemoteTrackpadCursor(null)
            }
        }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(view)
        }
        setContentView(rootContainer)
    }

    private fun createDirectTrackpadSink(
        extDisplayId: Int,
        dispatcher: ExternalPointerDispatcher,
    ): DirectExternalTrackpadSink = object : DirectExternalTrackpadSink {
        override val displayId: Int = extDisplayId
        override fun currentSurfaceWidthPx(): Int = dispatcher.currentSurfaceWidthPx()
        override fun currentSurfaceHeightPx(): Int = dispatcher.currentSurfaceHeightPx()
        override fun currentCursorX(): Float = dispatcher.cursorPosState.value.x
        override fun currentCursorY(): Float = dispatcher.cursorPosState.value.y
        override fun moveCursorBy(dxPx: Float, dyPx: Float) = runOnUiThread { dispatcher.moveCursorBy(dxPx, dyPx) }
        override fun setPrimaryButton(pressed: Boolean) = runOnUiThread { dispatcher.setPrimaryButton(pressed) }
        override fun tapPrimary() = runOnUiThread { dispatcher.tapPrimary() }
        override fun scrollAtCursor(hScroll: Float, vScroll: Float) = runOnUiThread { dispatcher.scrollAtCursor(hScroll, vScroll) }
        override fun hideCursor() = runOnUiThread { dispatcher.hideCursor() }
        override fun closeExternalDesktop() = runOnUiThread { finish() }
    }

    private fun publishDirectSession(currentDisplayId: Int): ExternalDesktopSession {
        val curDisplay = display ?: displayManager?.getDisplay(currentDisplayId)
        val curMode = curDisplay?.mode
        val widthPx = curMode?.physicalWidth?.coerceAtLeast(640) ?: resources.displayMetrics.widthPixels.coerceAtLeast(640)
        val heightPx = curMode?.physicalHeight?.coerceAtLeast(480) ?: resources.displayMetrics.heightPixels.coerceAtLeast(480)
        val hz = (curMode?.refreshRate ?: 60f).toInt().coerceAtLeast(30)
        val dispName = curDisplay?.name ?: "外接屏"
        val isRemoteCast = ExternalDisplayPolicy.isRemoteCastDisplay(
            displayId = currentDisplayId,
            displayName = dispName,
            remote = BridgeClient.remoteStatusFlow.value,
        )
        val session = ExternalDesktopSession(
            mode = ExternalDesktopMode.DIRECT_EXTENDED,
            displayId = currentDisplayId,
            displayName = dispName,
            widthPx = widthPx,
            heightPx = heightPx,
            refreshRateHz = hz,
            isRemoteCastDisplay = isRemoteCast,
        )
        directDisplaySummary = session.summary
        ExternalTrackpadBridge.publishSession(this, session)
        return session
    }

    private fun refreshDirectSessionMetadata(currentDisplayId: Int) {
        val session = publishDirectSession(currentDisplayId)
        if (!session.isPhysicalDirectExtended) {
            directTrackpadSink?.let { ExternalTrackpadBridge.unregisterDirectSink(it) }
            directTrackpadSink = null
        } else if (directTrackpadSink == null && directPointerDispatcher != null) {
            val sink = createDirectTrackpadSink(currentDisplayId, directPointerDispatcher!!)
            directTrackpadSink = sink
            ExternalTrackpadBridge.registerDirectSink(sink)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val currentDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        if (!runningInPresentationMode && currentDisplayId > Display.DEFAULT_DISPLAY) {
            targetDisplayId = currentDisplayId
            refreshDirectSessionMetadata(currentDisplayId)
            syncExternalMouseRouting(currentDisplayId)
        }
    }

    override fun onResume() {
        super.onResume()
        if (runningInPresentationMode) {
            ExternalTrackpadBridge.setPhoneTrackpadVisible(true, presentationTrackpadCloser)
        } else {
            applyImmersiveFullscreen()
        }
        BridgeClient.requestBinder(this)
        val extId = ExternalTrackpadBridge.sessionFlow.value.displayId
        if (extId > Display.DEFAULT_DISPLAY) {
            syncExternalMouseRouting(extId)
        }
        Thread({
            BridgeClient.refreshStatus()
            BridgeClient.refreshSceneSnapshot()
        }, "OpalExtDesktopSync").start()
    }

    override fun onPause() {
        if (runningInPresentationMode) {
            ExternalTrackpadBridge.setPhoneTrackpadVisible(false, presentationTrackpadCloser)
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !runningInPresentationMode) applyImmersiveFullscreen()
    }

    override fun onDestroy() {
        if (runningInPresentationMode) {
            ExternalTrackpadBridge.setPhoneTrackpadVisible(false, presentationTrackpadCloser)
        }
        directTrackpadSink?.let { ExternalTrackpadBridge.unregisterDirectSink(it) }
        directTrackpadSink = null
        directPointerDispatcher?.hideCursor()
        directPointerDispatcher = null
        directComposeView = null

        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        dismissPresentation()
        ExternalTrackpadBridge.clearSessionIfOwner(this)
        if (BridgeClient.statusFlow.value.hostScreenOff) {
            BridgeClient.setHostScreenPowerAsync(true)
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BridgeClient.tryWakeHostScreenFromKeyEvent(event)) return true
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true

        if (WorkstationKeyDispatcher.isImeSwitchShortcut(event.keyCode, event.metaState)) {
            if (event.action == KeyEvent.ACTION_DOWN && !OpalImeFocusTracker.localComposeTextFieldFocused) {
                val focused = BridgeClient.sceneFlow.value.focusedWindow
                if (focused != null && !focused.isMinimized) {
                    if (runningInPresentationMode) {
                        presentation?.ensureImeHostActive()
                    } else if (!directImeVisible) {
                        directImeController?.showKeyboard()
                    }
                }
            }
            if (super.dispatchKeyEvent(event)) return true
            if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
            return false
        }

        if (OpalImeFocusTracker.localComposeTextFieldFocused || directImeVisible || (presentation?.isImeActive == true)) {
            if (super.dispatchKeyEvent(event)) return true
            if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
        }

        if (WorkstationKeyDispatcher.forwardToFocusedWindow(event)) {
            return true
        }

        if (super.dispatchKeyEvent(event)) return true
        if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
        return false
    }

    private fun findPhysicalExternalDisplay(preferredId: Int): Display? {
        val dm = displayManager ?: return null
        if (preferredId > Display.DEFAULT_DISPLAY) {
            dm.getDisplay(preferredId)?.takeIf { isPhysicalExternal(it) }?.let { return it }
        }
        return dm.displays.firstOrNull { isPhysicalExternal(it) }
    }

    private fun isPhysicalExternal(d: Display): Boolean =
        ExternalDisplayPolicy.isPhysicalExternalDisplay(
            displayId = d.displayId,
            isValid = d.isValid,
            name = d.name,
            flags = d.flags,
            state = d.state,
        )

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

    private fun syncExternalMouseRouting(extDisplayId: Int) {
        if (extDisplayId <= Display.DEFAULT_DISPLAY) return
        val mode = AppPrefs.getMouseTarget(this)
        BridgeClient.setMouseTargetAsync(mode, extDisplayId)
    }

    private fun dismissPresentation() {
        BridgeClient.releaseExternalMouseRoutingAsync()
        val p = presentation
        presentation = null
        if (p != null) {
            try {
                p.dismiss()
            } catch (_: Throwable) {
            }
        }
    }

    private fun openConsoleOnPrimaryDisplay() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            startActivity(intent, opts.toBundle())
        } catch (_: Throwable) {
            startActivity(intent)
        }
    }

    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val decor = window.decorView
        WindowCompat.getInsetsController(window, decor).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
