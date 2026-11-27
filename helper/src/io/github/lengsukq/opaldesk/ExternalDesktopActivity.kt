package io.github.lengsukq.opaldesk

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
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
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.external.DexTrackpadScreen
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
        const val EXTRA_TARGET_DISPLAY_ID = "targetDisplayId"
        const val EXTRA_PREFER_PRESENTATION = "preferPresentation"
        const val EXTRA_IS_WIRELESS_DISPLAY = "isWirelessDisplay"
        const val EXTRA_SHELL_LAUNCHED = "shellLaunched"
        private const val PRESENTATION_RETRY_DELAY_MS = 260L
        private const val SHELL_RELAUNCH_DELAY_MS = 140L

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
    private var directComposingText by mutableStateOf("")
    private var directImeController: NativeImeHostController? = null
    private var pendingRecoveryRunnable: Runnable? = null
    private var fallbackNoticeShown = false

    private val presentationTrackpadCloser: () -> Unit = {
        runOnUiThread { openConsoleOnPrimaryDisplay() }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val actualDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
            if (!runningInPresentationMode
                && actualDisplayId > Display.DEFAULT_DISPLAY
                && displayId == actualDisplayId
            ) {
                applyPreferredPeakDisplayMode(display)
                refreshDirectSessionMetadata(actualDisplayId)
            }
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (targetDisplayId > Display.DEFAULT_DISPLAY && displayId == targetDisplayId) {
                OpalLogStore.i(TAG, "External display removed: displayId=$displayId, closing workstation")
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
                    sendBackToFocusedWindow()
                }
            },
        )

        val currentDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        val requestedId = intent?.getIntExtra(EXTRA_TARGET_DISPLAY_ID, -1) ?: -1
        val requestedWireless = intent?.getBooleanExtra(EXTRA_IS_WIRELESS_DISPLAY, false) == true
        val shellLaunched = intent?.getBooleanExtra(EXTRA_SHELL_LAUNCHED, false) == true

        if (currentDisplayId > Display.DEFAULT_DISPLAY) {
            runningInPresentationMode = false
            targetDisplayId = currentDisplayId
            BridgeClient.setDexImeSessionActive("external_desktop", true)
            val session = publishDirectSession(currentDisplayId, requestedWireless)
            activeIsWirelessDisplay = session.isWirelessDisplay
            syncExternalMouseRouting(currentDisplayId)
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
            applyImmersiveFullscreen()
            return
        }

        // Activity landed on Display 0: inspect target external display and choose Presentation vs Shell relaunch.
        val inspectedExternal = findExternalDisplay(requestedId)
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
            val pres = ExternalMonitorPresentation(
                hostActivity = this,
                targetDisplay = freshDisplay,
                displayLabelOverride = displayLabel,
                onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                onOpenConsole = ::openConsoleOnPrimaryDisplay,
                onExitDesktop = { finishAndRemoveTask() },
            )
            try {
                pres.show()
                presentation = pres
                targetDisplayId = freshDisplay.displayId
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
                ExternalTrackpadBridge.setPhoneTrackpadVisible(true, presentationTrackpadCloser)
                syncExternalMouseRouting(freshDisplay.displayId)
                OpalLogStore.i(
                    TAG,
                    "Started PRESENTATION mode: targetDisplayId=${freshDisplay.displayId} " +
                        "${pres.displayWidthPx}x${pres.displayHeightPx}@${pres.refreshRateHz}Hz " +
                        "wireless=$isWireless retry=$retryAttempt",
                )
                bindPresentationTrackpadUi(pres)
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
        OpalLogStore.i(
            TAG,
            "Escalating external desktop to shell-daemon Direct Extended launch on displayId=$targetId ($reason)",
        )
        runningInPresentationMode = false
        targetDisplayId = Display.DEFAULT_DISPLAY
        ExternalTrackpadBridge.clearSessionIfOwner(this)
        finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({
            BridgeClient.launchExternalDesktopOnDisplayAsync(targetId, isWireless)
        }, SHELL_RELAUNCH_DELAY_MS)
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
        applyImmersiveFullscreen()
    }

    private fun cancelPendingRecovery() {
        pendingRecoveryRunnable?.let { handler.removeCallbacks(it) }
        pendingRecoveryRunnable = null
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
                    badgePrefix = if (activeIsWirelessDisplay) "无线电视" else "独立满屏",
                    showOverscanControl = true,
                    isWirelessDisplay = activeIsWirelessDisplay,
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
                        finishAndRemoveTask()
                    },
                    onOpenConsole = ::openConsoleOnPrimaryDisplay,
                    onExitExternal = { finishAndRemoveTask() },
                )
            }
        }
    }

    private fun applyPreferredPeakDisplayMode(
        targetDisplay: Display?,
        targetFps: Int = BridgeClient.statusFlow.value.targetFps,
    ) {
        val disp = targetDisplay ?: return
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
        ) ?: return
        val lp = window.attributes
        if (lp.preferredDisplayModeId != chosen.modeId || kotlin.math.abs(lp.preferredRefreshRate - chosen.refreshRate) > 0.5f) {
            lp.preferredDisplayModeId = chosen.modeId
            lp.preferredRefreshRate = chosen.refreshRate
            window.attributes = lp
        }
    }

    private fun bindDirectExtendedWorkspaceUi(currentDisplayId: Int) {
        applyPreferredPeakDisplayMode(display)
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
                    val scene by BridgeClient.sceneFlow.collectAsState()
                    val imeRequest by BridgeClient.imeBridgeRequestFlow.collectAsState()
                    val imeController = rememberNativeImeHostController()
                    directImeController = imeController
                    LaunchedEffect(status.targetFps) {
                        applyPreferredPeakDisplayMode(display, status.targetFps)
                    }
                    LaunchedEffect(overscanPct, curExtSession.isPhysicalDirectExtended, curExtSession.widthPx, curExtSession.heightPx) {
                        val targetView = directComposeView ?: return@LaunchedEffect
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
                        if (req.show) {
                            val activeHere = !isFinishing && !isDestroyed && !runningInPresentationMode &&
                                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                            val focused = BridgeClient.sceneFlow.value.focusedWindow
                            if (activeHere &&
                                focused != null && !focused.isMinimized &&
                                (req.windowId <= 0 || req.windowId == focused.windowId) &&
                                !OpalImeFocusTracker.localComposeTextFieldFocused
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
                                imeActive = directImeVisible,
                                onToggleSoftKeyboard = { imeController.toggleKeyboard(directImeVisible) },
                                virtualCursorPx = directCursor ?: remoteCursor,
                                onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                                onOpenConsole = ::openConsoleOnPrimaryDisplay,
                                onExitDesktop = { finishAndRemoveTask() },
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

    @Suppress("DEPRECATION")
    private fun publishDirectSession(currentDisplayId: Int, requestedWireless: Boolean = false): ExternalDesktopSession {
        if (currentDisplayId <= Display.DEFAULT_DISPLAY) {
            return ExternalDesktopSession()
        }
        val curDisplay = displayManager?.getDisplay(currentDisplayId)
            ?: display?.takeIf { it.displayId == currentDisplayId }
        val curMode = curDisplay?.mode
        val realPt = android.graphics.Point()
        if (curDisplay != null) {
            runCatching { curDisplay.getRealSize(realPt) }
        }
        val (widthPx, heightPx) = ExternalDisplayPolicy.resolveEffectiveDisplaySize(
            modeW = curMode?.physicalWidth ?: resources.displayMetrics.widthPixels,
            modeH = curMode?.physicalHeight ?: resources.displayMetrics.heightPixels,
            realW = realPt.x,
            realH = realPt.y,
        )
        val hz = (curMode?.refreshRate ?: 60f).roundToInt().coerceAtLeast(24)
        val dispName = curDisplay?.name ?: "外接屏"
        val isRemoteCast = ExternalDisplayPolicy.isRemoteCastDisplay(
            displayId = currentDisplayId,
            displayName = dispName,
            remote = BridgeClient.remoteStatusFlow.value,
        )
        val inspectedTransport = curDisplay?.let { d ->
            ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = d.displayId,
                isValid = d.isValid,
                name = d.name,
                flags = d.flags,
                state = d.state,
                displayType = WirelessDisplayHelper.queryDisplayTypeSafe(d),
            )
        }
        val isWireless = !isRemoteCast &&
            (requestedWireless || inspectedTransport == ExternalDisplayTransport.WIRELESS_TV)
        val session = ExternalDesktopSession(
            mode = ExternalDesktopMode.DIRECT_EXTENDED,
            displayId = currentDisplayId,
            displayName = dispName,
            widthPx = widthPx,
            heightPx = heightPx,
            refreshRateHz = hz,
            isRemoteCastDisplay = isRemoteCast,
            isWirelessDisplay = isWireless,
        )
        directDisplaySummary = session.summary
        ExternalTrackpadBridge.publishSession(this, session)
        return session
    }

    private fun refreshDirectSessionMetadata(currentDisplayId: Int) {
        if (currentDisplayId <= Display.DEFAULT_DISPLAY) return
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
            return
        }
        if (currentDisplayId == Display.DEFAULT_DISPLAY && presentation == null) {
            val requestedId = intent.getIntExtra(EXTRA_TARGET_DISPLAY_ID, -1)
            val requestedWireless = intent.getBooleanExtra(EXTRA_IS_WIRELESS_DISPLAY, false)
            val shellLaunched = intent.getBooleanExtra(EXTRA_SHELL_LAUNCHED, false)
            val inspectedExternal = findExternalDisplay(requestedId)
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
        if (BridgeClient.statusFlow.value.hostScreenOff) {
            BridgeClient.setHostScreenPowerAsync(true)
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BridgeClient.tryWakeHostScreenFromKeyEvent(event)) return true
        if (WorkstationKeyDispatcher.consumeDuplicateMouseBackKey(event)) return true
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true

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
            if (super.dispatchKeyEvent(event)) return true
            if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
            return false
        }

        if (OpalImeFocusTracker.localComposeTextFieldFocused || hostSoftImeVisible) {
            if (super.dispatchKeyEvent(event)) return true
            if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
        }

        if (WorkstationKeyDispatcher.forwardToFocusedWindow(event, forwardImeSwitch = forwardImeSwitch)) {
            return true
        }

        if (super.dispatchKeyEvent(event)) return true
        if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
        return false
    }

    private fun findExternalDisplay(preferredId: Int): WirelessDisplayHelper.InspectedDisplay? {
        val dm = displayManager ?: return null
        val remoteDesktopId = BridgeClient.remoteStatusFlow.value.desktopDisplayId
        val candidates = WirelessDisplayHelper.inspectExternalDisplays(this, dm, remoteDesktopId)
        if (preferredId > Display.DEFAULT_DISPLAY) {
            candidates.firstOrNull { it.display.displayId == preferredId }?.let { return it }
        }
        return candidates.firstOrNull { ExternalDisplayPolicy.isAutoProjectable(it.transport) }
            ?: candidates.firstOrNull()
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

    private fun syncExternalMouseRouting(extDisplayId: Int) {
        if (extDisplayId <= Display.DEFAULT_DISPLAY) return
        if (ExternalTrackpadBridge.sessionFlow.value.isRemoteCastDisplay) {
            // Isolate host USB/Bluetooth peripherals on Display 0 while casting to Windows/macOS
            // so attached USB mice do not jump onto the virtual cast display or paint a duplicate cursor.
            BridgeClient.releaseExternalMouseRoutingAsync()
            return
        }
        val mode = AppPrefs.getMouseTarget(this)
        BridgeClient.setMouseTargetAsync(mode, extDisplayId)
    }

    private fun dismissPresentation() {
        BridgeClient.releaseExternalMouseRoutingAsync()
        val p = presentation
        presentation = null
        if (p != null) {
            BridgeClient.setDexImeSessionActive("external_desktop", false)
            try {
                p.dismiss()
            } catch (_: Throwable) {
            }
        }
    }

    private fun openConsoleOnPrimaryDisplay() {
        val intent = MainActivity.createLaunchIntent(this, fromDesktop = true)
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
