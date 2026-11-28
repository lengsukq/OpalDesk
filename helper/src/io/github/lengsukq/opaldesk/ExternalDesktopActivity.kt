package io.github.lengsukq.opaldesk

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import io.github.lengsukq.opaldesk.compositor.OpalCompositorWorkspace
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.external.DirectExternalTrackpadSink
import io.github.lengsukq.opaldesk.external.ExternalDesktopMode
import io.github.lengsukq.opaldesk.external.ExternalDesktopSession
import io.github.lengsukq.opaldesk.external.ExternalDisplayPolicy
import io.github.lengsukq.opaldesk.external.ExternalDisplayTransport
import io.github.lengsukq.opaldesk.external.ExternalMonitorPresentation
import io.github.lengsukq.opaldesk.external.ExternalPointerDispatcher
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.ExternalTrackpadLauncher
import io.github.lengsukq.opaldesk.external.ExternalTrackpadPolicy
import io.github.lengsukq.opaldesk.external.HardwareMouseDetectingFrameLayout
import io.github.lengsukq.opaldesk.external.WirelessDisplayHelper
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.ui.NativeImeBridgeHost
import io.github.lengsukq.opaldesk.ui.NativeImeHostController
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import io.github.lengsukq.opaldesk.ui.rememberNativeImeHostController
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
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(OpalLocalePolicy.wrapContext(newBase))
    }

    companion object {
        private const val TAG = "OpalExtDesktop"
        const val EXTRA_TARGET_DISPLAY_ID = "targetDisplayId"
        const val EXTRA_PREFER_PRESENTATION = "preferPresentation"
        const val EXTRA_IS_WIRELESS_DISPLAY = "isWirelessDisplay"
        const val EXTRA_SHELL_LAUNCHED = "shellLaunched"
        const val EXTRA_OPEN_TRACKPAD = "openTrackpad"
        private const val PRESENTATION_RETRY_DELAY_MS = 260L

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
                    getString(if (saved != null) R.string.toast_wallpaper_updated else R.string.toast_wallpaper_read_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, "OpalWallpaperImport").start()
    }
    private var displayManager: DisplayManager? = null
    private var presentation: ExternalMonitorPresentation? = null
    private var directRootContainer: HardwareMouseDetectingFrameLayout? = null
    private var directComposeView: ComposeView? = null
    private var directPointerDispatcher: ExternalPointerDispatcher? = null
    private var directTrackpadSink: DirectExternalTrackpadSink? = null
    private var targetDisplayId: Int = -1
    private var presentationSummary by mutableStateOf("")
    private var directDisplaySummary by mutableStateOf("")
    private var runningInPresentationMode by mutableStateOf(false)
    private var activeIsWirelessDisplay by mutableStateOf(false)
    private var directImeVisible by mutableStateOf(false)
    private var directLocalOverlayNeedsFocus by mutableStateOf(false)
    private var directComposingText by mutableStateOf("")
    private var directImeController: NativeImeHostController? = null
    private var pendingRecoveryRunnable: Runnable? = null
    private var fallbackNoticeShown = false
    private var lastAutoNormalizedDisplayId: Int = -1
    private var activePresentationLabel: String = ""
    private val presentationTrackpadState = PresentationTrackpadHostState()

    private val presentationTrackpadCloser: () -> Unit = {
        runOnUiThread {
            presentationTrackpadState.setManualVisibility(false)
            ExternalTrackpadBridge.setPhoneTrackpadVisible(false, presentationTrackpadCloser)
            presentation?.hideCursor()
            syncExternalMouseRouting(this, targetDisplayId)
            openConsoleOnPrimaryDisplay(this)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (runningInPresentationMode
                && targetDisplayId > Display.DEFAULT_DISPLAY
                && displayId == targetDisplayId
            ) {
                val pres = presentation
                val freshDisp = displayManager?.getDisplay(displayId)
                if (pres != null && freshDisp != null && freshDisp.isValid) {
                    val (primaryW, primaryH) = WirelessDisplayHelper.queryPrimaryDisplaySizePx(displayManager)
                    pres.refreshDisplayGeometry(freshDisp, primaryW, primaryH)
                    presentationSummary = pres.monitorSummary
                    val label = activePresentationLabel.ifBlank { freshDisp.name ?: "外接屏" }
                    ExternalTrackpadBridge.publishSession(
                        owner = this@ExternalDesktopActivity,
                        session = ExternalDesktopSession(
                            mode = ExternalDesktopMode.PRESENTATION,
                            displayId = freshDisp.displayId,
                            displayName = label,
                            widthPx = pres.displayWidthPx,
                            heightPx = pres.displayHeightPx,
                            refreshRateHz = pres.refreshRateHz,
                            isRemoteCastDisplay = false,
                            isWirelessDisplay = activeIsWirelessDisplay,
                        ),
                    )
                }
                return
            }
            val activeDisp = this@ExternalDesktopActivity.display
            val actualDisplayId = activeDisp?.displayId ?: Display.DEFAULT_DISPLAY
            if (!runningInPresentationMode
                && actualDisplayId > Display.DEFAULT_DISPLAY
                && displayId == actualDisplayId
            ) {
                applyPreferredPeakDisplayMode(window, activeDisp)
                refreshDirectSessionMetadata(actualDisplayId)
            }
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (targetDisplayId > Display.DEFAULT_DISPLAY && displayId == targetDisplayId) {
                OpalLogStore.i(TAG, "External display removed: displayId=$displayId, closing workstation")
                lastAutoNormalizedDisplayId = -1
                dismissPresentation()
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        AppPrefs.hydrateFlows(applicationContext)
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        BridgeClient.requestBinder(this)

        displayManager = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.also { dm ->
            dm.registerDisplayListener(displayListener, handler)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    WorkstationKeyDispatcher.handleDesktopBack(
                        hostSoftImeVisible = directImeVisible || (presentation?.isImeActive == true),
                        onHideHostSoftIme = { directImeController?.hideKeyboard() },
                    )
                }
            },
        )

        val currentDisplayId = display?.displayId
            ?: Display.DEFAULT_DISPLAY
        val requestedId = intent?.getIntExtra(EXTRA_TARGET_DISPLAY_ID, -1) ?: -1
        val requestedWireless = intent?.getBooleanExtra(EXTRA_IS_WIRELESS_DISPLAY, false) == true
        val shellLaunched = intent?.getBooleanExtra(EXTRA_SHELL_LAUNCHED, false) == true

        if (currentDisplayId > Display.DEFAULT_DISPLAY) {
            runningInPresentationMode = false
            targetDisplayId = currentDisplayId
            BridgeClient.setDexImeSessionActive("external_desktop", true)
            val session = publishDirectSession(currentDisplayId, requestedWireless)
            activeIsWirelessDisplay = session.isWirelessDisplay
            syncExternalMouseRouting(this, currentDisplayId)
            OpalLogStore.i(
                TAG,
                "Started DIRECT_EXTENDED mode: displayId=$currentDisplayId " +
                    "${session.widthPx}x${session.heightPx}@${session.refreshRateHz}Hz " +
                    "wireless=${session.isWirelessDisplay} cast=${session.isRemoteCastDisplay}",
            )
            if (ExternalTrackpadPolicy.shouldAutoLaunchPhoneTrackpad(
                    session = session,
                    autoShowEnabled = AppPrefs.isAutoShowPhoneTrackpadEnabled(this),
                    alreadyVisible = ExternalTrackpadBridge.phoneTrackpadVisibleFlow.value,
                    autoBlackoutEnabled = AppPrefs.isAutoBlackoutOnCast(this),
                )
            ) {
                handler.post {
                    ExternalTrackpadLauncher.openPhoneTrackpad(this)
                }
            }
            bindDirectExtendedWorkspaceUi(currentDisplayId)
            applyImmersiveFullscreen(window)
            return
        }

        // Activity landed on Display 0: inspect target external display and choose Presentation vs Shell relaunch.
        val inspectedExternal = findExternalDisplay(this, displayManager, requestedId)
        handleDisplay0Launch(
            inspectedExternal = inspectedExternal,
            requestedWireless = requestedWireless,
            shellLaunched = shellLaunched,
        )
    }

    private fun handleDisplay0Launch(
        inspectedExternal: WirelessDisplayHelper.InspectedDisplay?,
        requestedWireless: Boolean,
        shellLaunched: Boolean,
    ) {
        cancelPendingRecovery()
        val targetExternal = inspectedExternal?.display
        val isPublicPres = targetExternal != null &&
            targetExternal.isValid &&
            ExternalDisplayPolicy.isPublicPresentationDisplay(targetExternal.flags)
        val isWireless = requestedWireless ||
            inspectedExternal?.transport == ExternalDisplayTransport.WIRELESS_TV
        activeIsWirelessDisplay = isWireless

        if (targetExternal != null) {
            val mode = targetExternal.mode
            val modeDesc = if (mode != null) {
                "${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate.roundToInt()}Hz"
            } else {
                "unknown"
            }
            OpalLogStore.i(
                TAG,
                "Display0 host evaluating external displayId=${targetExternal.displayId} " +
                    "name=${inspectedExternal.displayName} flags=0x${Integer.toHexString(targetExternal.flags)} " +
                    "type=${inspectedExternal.displayType} state=${targetExternal.state} " +
                    "valid=${targetExternal.isValid} publicPres=$isPublicPres mode=$modeDesc " +
                    "shellLaunched=$shellLaunched",
            )
        }

        val action = ExternalDisplayPolicy.resolveDisplay0ExternalAction(
            hasTargetDisplay = targetExternal != null,
            isPublicPresentation = isPublicPres,
            shellLaunchAlreadyTried = shellLaunched,
            daemonConnected = BridgeClient.statusFlow.value.connected,
        )
        when (action) {
            ExternalDisplayPolicy.Display0ExternalAction.USE_PRESENTATION -> {
                attemptPresentationWithRecovery(
                    targetId = targetExternal!!.displayId,
                    displayLabel = inspectedExternal.displayName,
                    isWireless = isWireless,
                    shellLaunched = shellLaunched,
                    retryAttempt = 0,
                )
            }
            ExternalDisplayPolicy.Display0ExternalAction.RELAUNCH_DIRECT_VIA_SHELL -> {
                escalateToShellDirectLaunch(
                    targetId = targetExternal!!.displayId,
                    isWireless = isWireless,
                    reason = "target lacks FLAG_PRESENTATION (flags=0x${Integer.toHexString(targetExternal.flags)})",
                )
            }
            ExternalDisplayPolicy.Display0ExternalAction.FALLBACK_LOCAL_ONLY -> {
                enterDisplay0LocalFallback(
                    notifyExternalUnavailable = targetExternal != null,
                )
            }
        }
    }

    private fun attemptPresentationWithRecovery(
        targetId: Int,
        displayLabel: String,
        isWireless: Boolean,
        shellLaunched: Boolean,
        retryAttempt: Int,
    ) {
        if (isFinishing || isDestroyed) return
        val freshDisplay = displayManager?.getDisplay(targetId)
        val freshPublicPres = freshDisplay != null &&
            freshDisplay.isValid &&
            ExternalDisplayPolicy.isPublicPresentationDisplay(freshDisplay.flags)

        if (freshDisplay != null && freshPublicPres) {
            val (primaryW, primaryH) = WirelessDisplayHelper.queryPrimaryDisplaySizePx(displayManager)
            lastAutoNormalizedDisplayId = maybeAutoNormalizeExternalDisplay(
                this, freshDisplay, primaryW, primaryH, lastAutoNormalizedDisplayId,
            )
            val pres = ExternalMonitorPresentation(
                hostActivity = this,
                targetDisplay = freshDisplay,
                displayLabelOverride = displayLabel,
                primaryWidthPx = primaryW,
                primaryHeightPx = primaryH,
                onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                onOpenConsole = { openConsoleOnPrimaryDisplay(this) },
                onExitDesktop = { finishAndRemoveTask() },
            )
            try {
                pres.show()
                presentation = pres
                targetDisplayId = freshDisplay.displayId
                activePresentationLabel = displayLabel
                runningInPresentationMode = true
                presentationSummary = pres.monitorSummary
                BridgeClient.setDexImeSessionActive("external_desktop", true)
                ExternalTrackpadBridge.publishSession(
                    owner = this,
                    session = ExternalDesktopSession(
                        mode = ExternalDesktopMode.PRESENTATION,
                        displayId = freshDisplay.displayId,
                        displayName = displayLabel,
                        widthPx = pres.displayWidthPx,
                        heightPx = pres.displayHeightPx,
                        refreshRateHz = pres.refreshRateHz,
                        isRemoteCastDisplay = false,
                        isWirelessDisplay = isWireless,
                    ),
                )
                val showTrackpad = presentationTrackpadState.syncForEntryOrResume(
                    AppPrefs.isAutoShowPhoneTrackpadEnabled(this),
                )
                ExternalTrackpadBridge.setPhoneTrackpadVisible(showTrackpad, presentationTrackpadCloser)
                syncExternalMouseRouting(this, freshDisplay.displayId)
                OpalLogStore.i(
                    TAG,
                    "Started PRESENTATION mode: targetDisplayId=${freshDisplay.displayId} " +
                        "${pres.displayWidthPx}x${pres.displayHeightPx}@${pres.refreshRateHz}Hz " +
                        "wireless=$isWireless retry=$retryAttempt",
                )
                bindPresentationTrackpadContent(
                    activity = this,
                    pres = pres,
                    trackpadState = presentationTrackpadState,
                    getPresentationSummary = { presentationSummary },
                    getTargetDisplayId = { targetDisplayId },
                    getActiveIsWirelessDisplay = { activeIsWirelessDisplay },
                    onOpenTrackpad = {
                        presentationTrackpadState.setManualVisibility(true)
                        ExternalTrackpadBridge.setPhoneTrackpadVisible(true, presentationTrackpadCloser)
                    },
                    onDismissPresentation = ::dismissPresentation,
                )
                return
            } catch (t: Throwable) {
                runCatching { pres.dismiss() }
                OpalLogStore.w(
                    TAG,
                    "Presentation.show failed (displayId=$targetId flags=0x${Integer.toHexString(freshDisplay.flags)} " +
                        "state=${freshDisplay.state} retry=$retryAttempt): ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        } else {
            OpalLogStore.w(
                TAG,
                "Presentation skipped: displayId=$targetId freshNull=${freshDisplay == null} " +
                    "valid=${freshDisplay?.isValid} flags=0x${Integer.toHexString(freshDisplay?.flags ?: 0)}",
            )
        }

        runningInPresentationMode = false
        ExternalTrackpadBridge.clearSessionIfOwner(this)

        val recovery = ExternalDisplayPolicy.resolvePresentationFailureAction(
            retryAttempt = retryAttempt,
            isPublicPresentation = freshPublicPres,
            shellLaunchAlreadyTried = shellLaunched,
            daemonConnected = BridgeClient.statusFlow.value.connected,
        )
        when (recovery) {
            ExternalDisplayPolicy.PresentationFailureAction.RETRY_PRESENTATION -> {
                val r = Runnable {
                    pendingRecoveryRunnable = null
                    attemptPresentationWithRecovery(
                        targetId = targetId,
                        displayLabel = displayLabel,
                        isWireless = isWireless,
                        shellLaunched = shellLaunched,
                        retryAttempt = retryAttempt + 1,
                    )
                }
                pendingRecoveryRunnable = r
                handler.postDelayed(r, PRESENTATION_RETRY_DELAY_MS)
            }
            ExternalDisplayPolicy.PresentationFailureAction.ESCALATE_DIRECT_VIA_SHELL -> {
                escalateToShellDirectLaunch(
                    targetId = targetId,
                    isWireless = isWireless,
                    reason = "Presentation.show rejected on displayId=$targetId",
                )
            }
            ExternalDisplayPolicy.PresentationFailureAction.FALLBACK_LOCAL_ONLY -> {
                enterDisplay0LocalFallback(notifyExternalUnavailable = true)
            }
        }
    }

    private fun escalateToShellDirectLaunch(
        targetId: Int,
        isWireless: Boolean,
        reason: String,
    ) {
        val appCtx = applicationContext
        runningInPresentationMode = false
        targetDisplayId = Display.DEFAULT_DISPLAY
        ExternalTrackpadBridge.clearSessionIfOwner(this)
        finishAndRemoveTask()
        scheduleShellDirectLaunchFallback(appCtx, targetId, isWireless, reason)
    }

    private fun enterDisplay0LocalFallback(notifyExternalUnavailable: Boolean) {
        runningInPresentationMode = false
        targetDisplayId = Display.DEFAULT_DISPLAY
        ExternalTrackpadBridge.clearSessionIfOwner(this)
        BridgeClient.setDexImeSessionActive("external_desktop", true)
        if (notifyExternalUnavailable && !fallbackNoticeShown) {
            fallbackNoticeShown = true
            Toast.makeText(
                this,
                "外接屏暂不支持独立副屏投射（可能处于系统镜像模式），当前在主屏渲染",
                Toast.LENGTH_LONG,
            ).show()
        }
        bindDirectExtendedWorkspaceUi(Display.DEFAULT_DISPLAY)
        applyImmersiveFullscreen(window)
    }

    private fun cancelPendingRecovery() {
        pendingRecoveryRunnable?.let { handler.removeCallbacks(it) }
        pendingRecoveryRunnable = null
    }

    private fun bindDirectExtendedWorkspaceUi(currentDisplayId: Int) {
        val activeDisp = display
        applyPreferredPeakDisplayMode(window, activeDisp)
        val curMode = activeDisp?.mode
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
            val sink = createDirectTrackpadSink(this, currentDisplayId, dispatcher)
            directTrackpadSink = sink
            ExternalTrackpadBridge.registerDirectSink(sink)
        }

        val initialOverscan = if (session.isPhysicalDirectExtended) AppPrefs.getExternalOverscanPct(this) else 0f
        val (initInsetH, initInsetV) = ExternalDisplayPolicy.computeOverscanInsetsPx(defaultW, defaultH, initialOverscan)
        val view = ComposeView(this).apply {
            if (session.isRemoteCastDisplay) {
                pointerIcon = android.view.PointerIcon.getSystemIcon(this@ExternalDesktopActivity, android.view.PointerIcon.TYPE_NULL)
            }
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
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
                    val curExtSession by ExternalTrackpadBridge.sessionFlow.collectAsState()
                    val remoteCursor by BridgeClient.remoteTrackpadCursorFlow.collectAsState()
                    val status by BridgeClient.statusFlow.collectAsState()
                    val imeState by BridgeClient.imeStateFlow.collectAsState()
                    val scene by BridgeClient.sceneFlow.collectAsState()
                    val imeRequest by BridgeClient.imeBridgeRequestFlow.collectAsState()
                    val localTextFieldFocused by OpalImeFocusTracker.localComposeTextFieldFocusedFlow.collectAsState()
                    val pluginState by io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.pluginStateFlow.collectAsState()
                    val pluginSoftKbdVisible by io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.softKeyboardVisibleFlow.collectAsState()
                    val imeController = rememberNativeImeHostController()
                    directImeController = imeController
                    DirectExtendedImeAndFocusEffects(
                        activity = this@ExternalDesktopActivity,
                        runningInPresentationMode = runningInPresentationMode,
                        directLocalOverlayNeedsFocus = directLocalOverlayNeedsFocus,
                        localTextFieldFocused = localTextFieldFocused,
                        directImeVisible = directImeVisible,
                        status = status,
                        imeState = imeState,
                        scene = scene,
                        imeRequest = imeRequest,
                        overscanPct = overscanPct,
                        curExtSession = curExtSession,
                        defaultW = defaultW,
                        defaultH = defaultH,
                        getDirectComposeView = { directComposeView },
                        imeController = imeController,
                    )
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
                    val suppressSystemCursor = curExtSession.isRemoteCastDisplay ||
                        directCursor != null ||
                        remoteCursor != null
                    LaunchedEffect(suppressSystemCursor) {
                        directRootContainer?.syncSystemPointerIcon()
                    }
                    CompositionLocalProvider(LocalDensity provides effDensity) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NativeImeBridgeHost(
                                controller = imeController,
                                hostWindow = window,
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
                                suppressFullscreenExitOverlay = curExtSession.isRemoteCastDisplay,
                                imeActive = directImeVisible || (pluginState.pluginConnected && pluginSoftKbdVisible),
                                onToggleSoftKeyboard = {
                                    if (io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.isPluginSessionConnected) {
                                        io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel.toggleSoftKeyboard()
                                    } else {
                                        if (!directImeVisible) setDirectWindowNotFocusable(window, false)
                                        imeController.toggleKeyboard(directImeVisible)
                                    }
                                },
                                onLocalOverlayNeedsFocusChanged = { directLocalOverlayNeedsFocus = it },
                                virtualCursorPx = directCursor ?: remoteCursor,
                                onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                                onOpenConsole = { openConsoleOnPrimaryDisplay(this@ExternalDesktopActivity) },
                                onOpenConsoleSection = { tab, section ->
                                    openConsoleSectionOnPrimaryDisplay(this@ExternalDesktopActivity, tab, section)
                                },
                                onExitDesktop = { finishAndRemoveTask() },
                            )
                            DirectComposingTextPill(
                                text = directComposingText,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
        }
        directComposeView = view

        val rootContainer = HardwareMouseDetectingFrameLayout(
            ctx = this,
            shouldSuppressSystemPointerIcon = {
                ExternalTrackpadBridge.sessionFlow.value.isRemoteCastDisplay ||
                    dispatcher.showVirtualCursorState.value ||
                    BridgeClient.remoteTrackpadCursorFlow.value != null
            },
            onHardwareMouseSeen = {
                dispatcher.hideCursor()
                if (BridgeClient.remoteTrackpadCursorFlow.value != null) {
                    BridgeClient.updateRemoteTrackpadCursor(null)
                }
            },
        ).apply {
            setBackgroundColor(AndroidColor.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(view)
            syncSystemPointerIcon()
        }
        directRootContainer = rootContainer
        setContentView(rootContainer)
    }

    private fun publishDirectSession(currentDisplayId: Int, requestedWireless: Boolean = false): ExternalDesktopSession {
        return buildAndPublishDirectSession(
            ctx = this,
            displayManager = displayManager,
            activityDisplay = display,
            currentDisplayId = currentDisplayId,
            requestedWireless = requestedWireless,
            owner = this,
        ).also { session ->
            if (session.isActive) {
                directDisplaySummary = session.summary
            }
        }
    }

    private fun refreshDirectSessionMetadata(currentDisplayId: Int) {
        if (currentDisplayId <= Display.DEFAULT_DISPLAY) return
        val session = publishDirectSession(currentDisplayId)
        if (!session.isPhysicalDirectExtended) {
            directTrackpadSink?.let { ExternalTrackpadBridge.unregisterDirectSink(it) }
            directTrackpadSink = null
        } else if (directTrackpadSink == null && directPointerDispatcher != null) {
            val sink = createDirectTrackpadSink(this, currentDisplayId, directPointerDispatcher!!)
            directTrackpadSink = sink
            ExternalTrackpadBridge.registerDirectSink(sink)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val currentDisplayId = display?.displayId
            ?: Display.DEFAULT_DISPLAY
        if (runningInPresentationMode && intent.getBooleanExtra(EXTRA_OPEN_TRACKPAD, false)) {
            presentationTrackpadState.setManualVisibility(true)
            ExternalTrackpadBridge.setPhoneTrackpadVisible(true, presentationTrackpadCloser)
            return
        }
        if (!runningInPresentationMode && currentDisplayId > Display.DEFAULT_DISPLAY) {
            targetDisplayId = currentDisplayId
            refreshDirectSessionMetadata(currentDisplayId)
            syncExternalMouseRouting(this, currentDisplayId)
            return
        }
        if (currentDisplayId == Display.DEFAULT_DISPLAY && presentation == null) {
            val requestedId = intent.getIntExtra(EXTRA_TARGET_DISPLAY_ID, -1)
            val requestedWireless = intent.getBooleanExtra(EXTRA_IS_WIRELESS_DISPLAY, false)
            val shellLaunched = intent.getBooleanExtra(EXTRA_SHELL_LAUNCHED, false)
            val inspectedExternal = findExternalDisplay(this, displayManager, requestedId)
            if (inspectedExternal != null) {
                handleDisplay0Launch(
                    inspectedExternal = inspectedExternal,
                    requestedWireless = requestedWireless,
                    shellLaunched = shellLaunched,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (runningInPresentationMode) {
            val showTrackpad = presentationTrackpadState.syncForEntryOrResume(
                AppPrefs.isAutoShowPhoneTrackpadEnabled(this),
            )
            ExternalTrackpadBridge.setPhoneTrackpadVisible(showTrackpad, presentationTrackpadCloser)
        } else {
            applyImmersiveFullscreen(window)
        }
        BridgeClient.requestBinder(this)
        val extId = ExternalTrackpadBridge.sessionFlow.value.displayId
        if (extId > Display.DEFAULT_DISPLAY) {
            syncExternalMouseRouting(this, extId)
            syncExternalDesktopAudioRoute(this, entering = true)
        }
        Thread({
            BridgeClient.refreshStatus()
            BridgeClient.refreshSceneSnapshot()
            BridgeClient.fetchInputBindingsAsync()
        }, "OpalExtDesktopSync").start()
    }

    override fun onPause() {
        if (runningInPresentationMode) {
            ExternalTrackpadBridge.setPhoneTrackpadVisible(false, presentationTrackpadCloser)
            presentation?.hideCursor()
            syncExternalMouseRouting(this, targetDisplayId)
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !runningInPresentationMode) applyImmersiveFullscreen(window)
    }

    override fun onDestroy() {
        cancelPendingRecovery()
        if (runningInPresentationMode) {
            ExternalTrackpadBridge.setPhoneTrackpadVisible(false, presentationTrackpadCloser)
        }
        directTrackpadSink?.let { ExternalTrackpadBridge.unregisterDirectSink(it) }
        directTrackpadSink = null
        directPointerDispatcher?.hideCursor()
        directPointerDispatcher = null
        directRootContainer = null
        directComposeView = null

        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        dismissPresentation()
        BridgeClient.setDexImeSessionActive("external_desktop", false)
        ExternalTrackpadBridge.clearSessionIfOwner(this)
        syncExternalDesktopAudioRoute(this, entering = false)
        if (BridgeClient.statusFlow.value.hostScreenOff) {
            BridgeClient.setHostScreenPowerAsync(true)
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return dispatchExternalDesktopKeyEvent(
            event = event,
            runningInPresentationMode = runningInPresentationMode,
            directImeVisible = directImeVisible,
            presentation = presentation,
            directImeController = directImeController,
            superDispatch = { super.dispatchKeyEvent(it) },
        )
    }

    private fun dismissPresentation() {
        BridgeClient.releaseExternalMouseRoutingAsync()
        syncExternalDesktopAudioRoute(this, entering = false)
        val p = presentation
        presentation = null
        if (p != null) {
            BridgeClient.setDexImeSessionActive("external_desktop", false)
            try {
                p.dismiss()
            } catch (_: Throwable) {
            }
        }
        if (BridgeClient.statusFlow.value.hostScreenOff) {
            BridgeClient.setHostScreenPowerAsync(true)
        }
    }
}
