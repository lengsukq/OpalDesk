package io.github.lengsukq.opaldesk.compositor

import android.app.ActivityOptions
import android.content.Context
import android.view.Display
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.ExternalTrackpadLauncher
import io.github.lengsukq.opaldesk.external.ExternalTrackpadPolicy
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.WindowRect
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.search.DexCommandCatalog
import io.github.lengsukq.opaldesk.search.DexCommandIds
import io.github.lengsukq.opaldesk.search.DexSpotlightState
import io.github.lengsukq.opaldesk.search.SpotlightCategory
import io.github.lengsukq.opaldesk.ui.OpalAmbientBackdrop
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSpotlightController
import io.github.lengsukq.opaldesk.ui.OpalSpotlightOverlay
import io.github.lengsukq.opaldesk.ui.PairingConsentModal
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import io.github.lengsukq.opaldesk.ui.onTapOrContextAction
import io.github.lengsukq.opaldesk.ui.rememberOpalSpotlightController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Consolidated Compose state holder for transient desktop workspace overlays and menus.
 * Keeping these mutable states in a single @Stable holder avoids 80-parameter lambda captures
 * (which can exceed the 255-register DEX threshold under R8 full-mode optimization).
 */
@Stable
private class WorkspaceUiState(initialSpotlightPulse: Long) {
    var allApps by mutableStateOf<List<InstalledAppEntry>>(emptyList())
    var lastSpotlightTogglePulse by mutableLongStateOf(initialSpotlightPulse)
    var showSpotlight by mutableStateOf(false)
    var showAppDrawer by mutableStateOf(false)
    var showExposeOverview by mutableStateOf(false)
    var showControlCenter by mutableStateOf(false)
    var controlCenterInitialTab by mutableIntStateOf(0)
    var showDpiPopover by mutableStateOf(false)
    var showResolutionModal by mutableStateOf(false)
    var showImePopover by mutableStateOf(false)
    var showShortcutsPopover by mutableStateOf(false)
    var showFeatureGuide by mutableStateOf(false)
    var showNotificationCenter by mutableStateOf(false)
    var showExitSlotModal by mutableStateOf(false)
    var savingOnExit by mutableStateOf(false)
    var editingAppDpiTarget by mutableStateOf<Pair<String, String>?>(null)
    var autoRestoredOnce by mutableStateOf(false)
    var contextMenuTarget by mutableStateOf<Triple<String, CompositorWindow?, Offset>?>(null)
    var guideIconMenuPos by mutableStateOf<Offset?>(null)
    var wallpaperMenuPos by mutableStateOf<Offset?>(null)
    var snapPreviewRect by mutableStateOf<WindowRect?>(null)
    val dockLastTapMsByPkg = mutableMapOf<String, Long>()

    fun localOverlayNeedsFocus(hasPendingPairing: Boolean): Boolean =
        showSpotlight ||
            showAppDrawer ||
            showControlCenter ||
            showDpiPopover ||
            showResolutionModal ||
            showImePopover ||
            showShortcutsPopover ||
            showFeatureGuide ||
            showNotificationCenter ||
            showExitSlotModal ||
            editingAppDpiTarget != null ||
            hasPendingPairing

    fun dismissAllPopovers() {
        showSpotlight = false
        showAppDrawer = false
        showExposeOverview = false
        showControlCenter = false
        showDpiPopover = false
        showResolutionModal = false
        showImePopover = false
        showShortcutsPopover = false
        showFeatureGuide = false
        showNotificationCenter = false
        contextMenuTarget = null
        guideIconMenuPos = null
        wallpaperMenuPos = null
    }
}

private fun togglePhoneTrackpadAction(
    ctx: Context,
    phoneTrackpadVisible: Boolean,
    onOpenConsole: () -> Unit,
) {
    if (phoneTrackpadVisible) {
        val closed = ExternalTrackpadBridge.requestClosePhoneTrackpad()
        if (!closed) {
            onOpenConsole()
        }
        Toast.makeText(ctx, ctx.getString(R.string.workspace_trackpad_closed), Toast.LENGTH_SHORT).show()
    } else {
        val launched = ExternalTrackpadLauncher.openPhoneTrackpad(ctx)
        Toast.makeText(
            ctx,
            ctx.getString(
                if (launched) R.string.workspace_trackpad_opened else R.string.workspace_trackpad_no_target,
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun openConsoleSectionAction(
    ctx: Context,
    isExternalMonitor: Boolean,
    onOpenConsoleSection: ((tabIndex: Int, sectionKey: String) -> Unit)?,
    tabIndex: Int,
    sectionKey: String,
) {
    if (onOpenConsoleSection != null) {
        onOpenConsoleSection(tabIndex, sectionKey)
        return
    }
    val intent = MainActivity.createLaunchIntent(
        ctx = ctx,
        fromDesktop = true,
        focusTab = tabIndex,
        focusSectionKey = sectionKey,
    )
    if (isExternalMonitor) {
        try {
            val opts = ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                .toBundle()
            ctx.startActivity(intent, opts)
            return
        } catch (_: Throwable) {
        }
    }
    runCatching { ctx.startActivity(intent) }
}

private fun openNotificationAppAction(
    ctx: Context,
    scope: CoroutineScope,
    ui: WorkspaceUiState,
    scene: CompositorScene,
    pkg: String,
) {
    ui.dismissAllPopovers()
    val existing = scene.windows.firstOrNull { it.pkg == pkg }
    if (existing != null) {
        BridgeClient.sendWindowActionAsync(existing.windowId, BridgeProtocol.WinAction.FOCUS)
        return
    }
    val entry = ui.allApps.firstOrNull { it.pkg == pkg }
    val cls = entry?.activityClass.orEmpty()
    AppPrefs.recordAppLaunch(ctx, pkg)
    scope.launch(Dispatchers.IO) {
        BridgeClient.openWindow(pkg, cls, BridgeProtocol.Profile.AUTO)
    }
}

private fun minimizeAllVisibleWindows(scene: CompositorScene) {
    scene.visibleWindows.forEach { win ->
        LiveWindowPreviewRegistry.captureLivePreview(win.windowId)
        BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.MINIMIZE)
    }
}

/**
 * Shared macOS-Styled + Samsung-DeX-Layout Multi-Window Compositor Workspace.
 *
 * Runs seamlessly inside [io.github.lengsukq.opaldesk.DesktopActivity] (on-device tablet/phone),
 * [io.github.lengsukq.opaldesk.ExternalDesktopActivity] (extended external display), and
 * [io.github.lengsukq.opaldesk.external.ExternalMonitorPresentation] (full-bleed native external monitor).
 */
@Composable
fun OpalCompositorWorkspace(
    isExternalMonitor: Boolean,
    externalMonitorSummary: String = "",
    suppressFullscreenExitOverlay: Boolean = false,
    virtualCursorPx: Offset? = null,
    imeActive: Boolean = false,
    onToggleSoftKeyboard: (() -> Unit)? = null,
    onLocalOverlayNeedsFocusChanged: (Boolean) -> Unit = {},
    onPickCustomWallpaper: () -> Unit = {},
    onOpenConsole: () -> Unit,
    onOpenConsoleSection: ((tabIndex: Int, sectionKey: String) -> Unit)? = null,
    onExitDesktop: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val pendingPairing by BridgeClient.pendingPairingFlow.collectAsState()
    val extSession by ExternalTrackpadBridge.sessionFlow.collectAsState()
    val phoneTrackpadVisible by ExternalTrackpadBridge.phoneTrackpadVisibleFlow.collectAsState()
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val autoBlackoutOnCast by AppPrefs.autoBlackoutOnCastFlow.collectAsState()
    val spotlightTogglePulse by WorkstationKeyDispatcher.spotlightTogglePulseFlow.collectAsState()

    val ui = remember { WorkspaceUiState(WorkstationKeyDispatcher.spotlightTogglePulseFlow.value) }
    val spotlightController = rememberOpalSpotlightController()
    val trayInfo = rememberWorkspaceSystemTrayInfo(ctx)

    LaunchedEffect(Unit) {
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.hydrate(ctx)
        BridgeClient.bindAppContext(ctx)
    }

    LaunchedEffect(scene.windows) {
        io.github.lengsukq.opaldesk.protocol.OpalWindowPinStore.prune(scene.windows.map { it.windowId }.toSet())
    }

    val localOverlayNeedsFocus = ui.localOverlayNeedsFocus(hasPendingPairing = pendingPairing != null)
    LaunchedEffect(localOverlayNeedsFocus) {
        onLocalOverlayNeedsFocusChanged(localOverlayNeedsFocus)
    }

    LaunchedEffect(spotlightTogglePulse) {
        if (spotlightTogglePulse != ui.lastSpotlightTogglePulse) {
            ui.lastSpotlightTogglePulse = spotlightTogglePulse
            val next = !ui.showSpotlight
            ui.dismissAllPopovers()
            if (next) {
                spotlightController.resetSelection()
                ui.showSpotlight = true
            }
        }
    }

    DisposableEffect(ui.showSpotlight, spotlightController) {
        if (ui.showSpotlight) {
            val keyHandler: (android.view.KeyEvent) -> Boolean = { ev -> spotlightController.handleKeyEvent(ev) }
            WorkstationKeyDispatcher.setActiveSpotlightKeyHandler(keyHandler)
            onDispose {
                WorkstationKeyDispatcher.clearActiveSpotlightKeyHandler(keyHandler)
            }
        } else {
            onDispose {}
        }
    }

    LaunchedEffect(status.connected) {
        ui.allApps = AppCatalogRepository.loadLaunchableApps(ctx, forceReload = status.connected)
        scope.launch(Dispatchers.IO) { AppCatalogRepository.prewarmIcons(ctx, ui.allApps) }
    }

    // Keep hostScreenOff status indicator synced if the user wakes Display 0 via power button
    // (relaxed 5s interval halves idle Binder wakeups).
    LaunchedEffect(status.connected, status.hostScreenOff) {
        if (status.connected && status.hostScreenOff) {
            while (true) {
                delay(5_000L)
                withContext(Dispatchers.IO) {
                    BridgeClient.refreshStatus()
                }
            }
        }
    }

    // Optional thermal-saving auto-blackout: turns off the phone's OLED panel 3s after entering
    // external monitor or remote cast DeX mode, firing once per session so manual wake-ups stay on.
    WorkspaceAutoBlackoutEffect(
        ctx = ctx,
        isExternalMonitor = isExternalMonitor,
        autoBlackoutOnCast = autoBlackoutOnCast,
        daemonConnected = status.connected,
        hostScreenOff = status.hostScreenOff,
        phoneTrackpadVisible = phoneTrackpadVisible,
        isPresentationMode = extSession.isPresentation,
    )

    val hasFullscreenWindow = scene.visibleWindows.any { it.isFullscreen }
    OpalAmbientBackdrop(
        modifier = Modifier.fillMaxSize(),
        wallpaperPreset = wallpaperPreset,
        suspendBackdrop = hasFullscreenWindow,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            WorkspaceCanvasContent(
                ui = ui,
                trayInfo = trayInfo,
                spotlightController = spotlightController,
                isExternalMonitor = isExternalMonitor,
                externalMonitorSummary = externalMonitorSummary,
                suppressFullscreenExitOverlay = suppressFullscreenExitOverlay,
                virtualCursorPx = virtualCursorPx,
                imeActive = imeActive,
                onToggleSoftKeyboard = onToggleSoftKeyboard,
                onPickCustomWallpaper = onPickCustomWallpaper,
                onOpenConsole = onOpenConsole,
                onOpenConsoleSection = onOpenConsoleSection,
                onExitDesktop = onExitDesktop,
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.WorkspaceCanvasContent(
    ui: WorkspaceUiState,
    trayInfo: SystemTrayInfo,
    spotlightController: OpalSpotlightController,
    isExternalMonitor: Boolean,
    externalMonitorSummary: String,
    suppressFullscreenExitOverlay: Boolean,
    virtualCursorPx: Offset?,
    imeActive: Boolean,
    onToggleSoftKeyboard: (() -> Unit)?,
    onPickCustomWallpaper: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenConsoleSection: ((tabIndex: Int, sectionKey: String) -> Unit)?,
    onExitDesktop: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val remote by BridgeClient.remoteStatusFlow.collectAsState()
    val extDisplay by io.github.lengsukq.opaldesk.BridgeService.externalDisplayFlow.collectAsState()
    val extSession by ExternalTrackpadBridge.sessionFlow.collectAsState()
    val phoneTrackpadVisible by ExternalTrackpadBridge.phoneTrackpadVisibleFlow.collectAsState()
    val desktopPkgs by AppPrefs.desktopShortcutsFlow.collectAsState()
    val dockPinnedPkgs by AppPrefs.dockPinnedFlow.collectAsState()
    val dockVisible by AppPrefs.dockVisibleFlow.collectAsState()
    val dockOpacity by AppPrefs.dockOpacityFlow.collectAsState()
    val dockScale by AppPrefs.dockScaleFlow.collectAsState()
    val showDesktopGuideIcon by AppPrefs.showDesktopGuideIconFlow.collectAsState()
    val notifications by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.notificationsFlow.collectAsState()
    val doNotDisturb by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.doNotDisturbFlow.collectAsState()
    val pinnedWindowIds by io.github.lengsukq.opaldesk.protocol.OpalWindowPinStore.pinnedIdsFlow.collectAsState()
    val profileState by io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.stateFlow.collectAsState()

    val canControlPhoneTrackpad = ExternalTrackpadPolicy.canControlPhoneTrackpad(
        isExternalMonitor = isExternalMonitor,
        remote = remote,
        session = extSession,
    )

    val canvasW = constraints.maxWidth
    val canvasH = constraints.maxHeight
    val isCompactWidth = maxWidth < 600.dp
    val densityVal = density.density
    val dpiInt = (densityVal * 160f).roundToInt().coerceIn(120, 560)
    val effectiveDockInsetDp = DockAppearancePolicy.computeEffectiveDockInsetDp(
        visible = dockVisible,
        scale = dockScale,
        isCompactWidth = isCompactWidth,
    )
    val usableHeightDp = (maxHeight - (CompositorGeometry.TOP_BAR_DP + effectiveDockInsetDp + 24f).dp)
        .coerceAtLeast(180.dp)
    val shortcutRowsPerCol = (usableHeightDp.value / 88f).toInt().coerceIn(3, 8)
    val fullscreenWin = scene.visibleWindows.firstOrNull { it.isFullscreen }
    val activeDisplayMetrics = remember(isExternalMonitor, remote.connected, remote.fpsX100, extSession.refreshRateHz, extDisplay, status.targetFps, canvasW, canvasH) {
        val hostDisp = runCatching { ctx.display }.getOrNull()
        val actualHz = when {
            remote.connected && remote.fpsX100 > 0 -> (remote.fpsX100 / 100f).roundToInt().coerceAtLeast(24)
            isExternalMonitor && extSession.refreshRateHz > 0 -> extSession.refreshRateHz
            else -> (hostDisp?.mode?.refreshRate ?: hostDisp?.refreshRate ?: 60f).roundToInt().coerceAtLeast(24)
        }
        val maxHz = when {
            remote.connected -> 120
            isExternalMonitor && extDisplay != null && extDisplay!!.supportedModes.isNotEmpty() ->
                extDisplay!!.supportedModes.maxOf { it.refreshRateHz }.coerceAtLeast(actualHz)
            hostDisp?.supportedModes?.isNotEmpty() == true ->
                hostDisp.supportedModes.maxOf { it.refreshRate.roundToInt() }.coerceAtLeast(actualHz)
            else -> actualHz
        }
        actualHz to maxHz
    }
    val displaySummary = when {
        remote.connected -> "${remote.channelLabel} · ${remote.peerName.ifBlank { ctx.getString(R.string.common_peer_device) }} · ${remote.width}×${remote.height} @ ${remote.fpsX100 / 100}Hz"
        externalMonitorSummary.isNotBlank() -> externalMonitorSummary
        else -> ctx.getString(R.string.workspace_canvas_label, canvasW, canvasH, activeDisplayMetrics.first)
    }

    val currentTarget = remember(isExternalMonitor, remote.connected, remote.peerName, externalMonitorSummary, canvasW, canvasH) {
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.resolveDisplayTarget(
            isExternalMonitor = isExternalMonitor,
            remoteConnected = remote.connected,
            remotePeerName = remote.peerName,
            externalDisplayName = if (isExternalMonitor && !remote.connected) externalMonitorSummary.substringBefore("·").trim() else "",
            externalWidth = if (isExternalMonitor && !remote.connected) canvasW else 0,
            externalHeight = if (isExternalMonitor && !remote.connected) canvasH else 0,
        )
    }

    // Automatically select & sync the matching profile slot for the active display / cast receiver
    LaunchedEffect(status.connected, currentTarget.key, profileState.autoMatchTargetSlot) {
        if (status.connected && profileState.autoMatchTargetSlot) {
            val (matched, changed) = io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.autoSelectSlotForTarget(ctx, currentTarget)
            if (changed) {
                BridgeClient.syncProfileToDaemonAsync(matched) {
                    Toast.makeText(ctx, ctx.getString(R.string.workspace_auto_slot_toast, matched.id + 1, matched.name), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Sync physical canvas dimensions & effective DPI with daemon compositor
    LaunchedEffect(status.connected, canvasW, canvasH, dpiInt) {
        if (status.connected && canvasW >= 360 && canvasH >= 360) {
            BridgeClient.updateCompositorConfigAsync(
                canvasW = canvasW,
                canvasH = canvasH,
                canvasDpi = dpiInt,
            )
            val activeSlot = io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.stateFlow.value.activeSavedSlot
            if (!ui.autoRestoredOnce && activeSlot.autoRestoreLayout && scene.windows.isEmpty() && activeSlot.savedLayout.isNotEmpty()) {
                ui.autoRestoredOnce = true
                BridgeClient.restoreSavedLayoutAsync(activeSlot, canvasW, canvasH)
            }
        }
    }

    // Desktop wallpaper: single tap dismisses popovers; right-click or touchscreen long-press opens desktop/wallpaper menu
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onTapOrContextAction(
                ui.showSpotlight, ui.showAppDrawer, ui.showExposeOverview, ui.showControlCenter,
                ui.showDpiPopover, ui.showResolutionModal, ui.showShortcutsPopover, ui.showFeatureGuide,
                ui.contextMenuTarget, ui.guideIconMenuPos, ui.wallpaperMenuPos,
                onPrimaryTap = { ui.dismissAllPopovers() },
                onContextAction = { pos ->
                    ui.dismissAllPopovers()
                    ui.wallpaperMenuPos = pos
                },
            ),
    )

    // Samsung DeX-style Left-Column Desktop App Shortcuts (hidden during true app fullscreen)
    if (status.connected && (showDesktopGuideIcon || ui.allApps.isNotEmpty()) && fullscreenWin == null) {
        DesktopShortcutColumn(
            allApps = ui.allApps,
            desktopPkgs = desktopPkgs,
            showGuideIcon = showDesktopGuideIcon,
            rowsPerColumn = shortcutRowsPerCol,
            onOpenGuide = {
                ui.dismissAllPopovers()
                ui.showFeatureGuide = true
            },
            onContextGuide = { rootPos ->
                ui.dismissAllPopovers()
                ui.guideIconMenuPos = rootPos
            },
            onLaunch = { entry ->
                ui.dismissAllPopovers()
                AppPrefs.recordAppLaunch(ctx, entry.appKey)
                scope.launch(Dispatchers.IO) {
                    BridgeClient.openWindow(
                        entry.pkg, entry.activityClass, BridgeProtocol.Profile.AUTO, userId = entry.userId,
                    )
                }
            },
            onContextApp = { appKey, rootPos ->
                ui.dismissAllPopovers()
                val running = scene.windows.firstOrNull { it.appKey == appKey }
                ui.contextMenuTarget = Triple(appKey, running, rootPos)
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    top = (CompositorGeometry.TOP_BAR_DP + 14f).dp,
                    start = if (isCompactWidth) 10.dp else 16.dp,
                    bottom = (effectiveDockInsetDp + 14f).dp,
                ),
        )
    }

    // Edge/corner snap frosted-glass preview frame
    ui.snapPreviewRect?.let { snapRect ->
        Box(
            modifier = Modifier
                .offset { IntOffset(snapRect.left, snapRect.top) }
                .size(
                    width = with(density) { snapRect.width.toDp() },
                    height = with(density) { snapRect.height.toDp() },
                )
                .clip(RoundedCornerShape(14.dp))
                .background(OpalPalette.AccentBlue.copy(alpha = 0.20f))
                .border(1.5.dp, OpalPalette.AccentBlue.copy(alpha = 0.78f), RoundedCornerShape(14.dp)),
        )
    }

    // Live Z-ordered macOS Compositor Windows:
    // Keep composition order STABLE by windowId so AndroidView(TextureView) never detaches
    // or desyncs AndroidViewsHandler hit-testing on focus changes; drive draw & touch order via zIndex!
    val stableWindows = remember(scene.visibleWindows) {
        scene.visibleWindows.sortedBy { it.windowId }
    }
    for (win in stableWindows) {
        key(win.windowId) {
            CompositorWindowFrameNode(
                window = win,
                canvasW = canvasW,
                canvasH = canvasH,
                dockInsetDp = effectiveDockInsetDp,
                occludedByFullscreen = (fullscreenWin != null && win.windowId != fullscreenWin.windowId),
                onSnapPreviewChanged = { ui.snapPreviewRect = it },
                modifier = Modifier.zIndex(CompositorGeometry.effectiveZOrder(win, pinnedWindowIds).toFloat()),
            )
        }
    }

    // Top 28dp Translucent macOS Liquid Glass Menu Bar (hidden during true app fullscreen)
    if (fullscreenWin == null) {
        MacDesktopMenuBar(
            focusedWindow = scene.focusedWindow,
            tray = trayInfo,
            isExternalMonitor = isExternalMonitor,
            hostScreenOff = status.hostScreenOff,
            showHostScreenToggle = isExternalMonitor || remote.connected || status.hostScreenOff,
            isCompactWidth = isCompactWidth,
            showControlCenter = ui.showControlCenter,
            showDpiPopover = ui.showDpiPopover,
            notificationCount = notifications.size,
            showNotificationCenter = ui.showNotificationCenter,
            doNotDisturb = doNotDisturb,
            showPhoneTrackpadButton = canControlPhoneTrackpad,
            phoneTrackpadVisible = phoneTrackpadVisible,
            showDockFallbackActions = !dockVisible,
            showAppDrawer = ui.showAppDrawer,
            showExpose = ui.showExposeOverview,
            showSpotlight = ui.showSpotlight,
            onToggleAppDrawer = {
                val next = !ui.showAppDrawer
                ui.dismissAllPopovers()
                ui.showAppDrawer = next
            },
            onToggleExpose = {
                val next = !ui.showExposeOverview
                ui.dismissAllPopovers()
                ui.showExposeOverview = next
            },
            onToggleSpotlight = {
                val next = !ui.showSpotlight
                ui.dismissAllPopovers()
                if (next) {
                    spotlightController.resetSelection()
                    ui.showSpotlight = true
                }
            },
            onTogglePhoneTrackpad = {
                ui.dismissAllPopovers()
                togglePhoneTrackpadAction(ctx, phoneTrackpadVisible, onOpenConsole)
            },
            onToggleHostScreenPower = { screenOn ->
                BridgeClient.setHostScreenPowerAsync(screenOn) { ok ->
                    val msg = when {
                        !ok -> ctx.getString(R.string.toast_host_screen_unsupported)
                        screenOn -> ctx.getString(R.string.toast_host_screen_on)
                        else -> ctx.getString(R.string.toast_host_screen_off_hint)
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onToggleDpiPopover = {
                val next = !ui.showDpiPopover
                ui.dismissAllPopovers()
                ui.showDpiPopover = next
            },
            onToggleNotificationCenter = {
                val next = !ui.showNotificationCenter
                ui.dismissAllPopovers()
                ui.showNotificationCenter = next
                if (next) {
                    io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.refreshAccessState(ctx)
                    io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.requestSync()
                }
            },
            onToggleFullscreen = {
                val focused = scene.focusedWindow ?: return@MacDesktopMenuBar
                ui.dismissAllPopovers()
                BridgeClient.sendWindowActionAsync(
                    focused.windowId,
                    BridgeProtocol.WinAction.TOGGLE_FULLSCREEN,
                )
            },
            onToggleControlCenter = {
                val next = !ui.showControlCenter
                ui.dismissAllPopovers()
                ui.controlCenterInitialTab = 0
                ui.showControlCenter = next
            },
            onOpenConsole = onOpenConsole,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(10_000f),
        )
    } else if (!suppressFullscreenExitOverlay) {
        // Unobtrusive top-center pull-down handle to exit fullscreen or return to desktop
        // (suppressed on remote cast displays where the outer receiver already owns the single top-center pill).
        FullscreenExitOverlay(
            windowTitle = fullscreenWin.title,
            imeActive = imeActive,
            onToggleSoftKeyboard = onToggleSoftKeyboard,
            onExitFullscreen = {
                ui.dismissAllPopovers()
                BridgeClient.sendWindowActionAsync(
                    fullscreenWin.windowId,
                    BridgeProtocol.WinAction.TOGGLE_FULLSCREEN,
                )
            },
            onToggleOrientation = {
                ui.dismissAllPopovers()
                BridgeClient.sendWindowActionAsync(
                    fullscreenWin.windowId,
                    BridgeProtocol.WinAction.TOGGLE_ORIENTATION,
                )
            },
            onReturnToDesktop = {
                ui.dismissAllPopovers()
                BridgeClient.sendWindowActionAsync(
                    fullscreenWin.windowId,
                    BridgeProtocol.WinAction.TOGGLE_FULLSCREEN,
                )
                BridgeClient.sendWindowActionAsync(
                    fullscreenWin.windowId,
                    BridgeProtocol.WinAction.MINIMIZE,
                )
            },
            onOpenControlCenter = {
                val next = !ui.showControlCenter
                ui.dismissAllPopovers()
                ui.controlCenterInitialTab = 0
                ui.showControlCenter = next
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(10_040f),
        )
    }

    // Bottom Centered Floating Liquid Frosted Glass Dock (hidden during true app fullscreen or when disabled)
    if (fullscreenWin == null && dockVisible) {
        DexMacTaskbar(
            allApps = ui.allApps,
            dockPinnedPkgs = dockPinnedPkgs,
            windows = scene.windows,
            showAppDrawer = ui.showAppDrawer,
            showExpose = ui.showExposeOverview,
            showSpotlight = ui.showSpotlight,
            isCompactWidth = isCompactWidth,
            dockOpacity = dockOpacity,
            dockScale = dockScale,
            imeActive = imeActive,
            onToggleSoftKeyboard = onToggleSoftKeyboard,
            onToggleAppDrawer = {
                val next = !ui.showAppDrawer
                ui.dismissAllPopovers()
                ui.showAppDrawer = next
            },
            onOpenSearch = {
                val next = !ui.showSpotlight
                ui.dismissAllPopovers()
                if (next) {
                    spotlightController.resetSelection()
                    ui.showSpotlight = true
                }
            },
            onToggleExpose = {
                val next = !ui.showExposeOverview
                ui.dismissAllPopovers()
                ui.showExposeOverview = next
            },
            onSendBackToFocused = {
                val focused = scene.focusedWindow
                if (focused != null && !focused.isMinimized) {
                    BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.SEND_BACK)
                }
            },
            onShowDesktopToggle = {
                ui.dismissAllPopovers()
                if (scene.visibleWindows.isNotEmpty()) {
                    minimizeAllVisibleWindows(scene)
                } else {
                    val lastMin = scene.windows.maxByOrNull { it.zOrder }
                    if (lastMin != null) {
                        BridgeClient.sendWindowActionAsync(lastMin.windowId, BridgeProtocol.WinAction.FOCUS)
                    }
                }
            },
            onLaunchOrToggleApp = { pkg, cls, userId ->
                val appKey = io.github.lengsukq.opaldesk.protocol.AppProfileKey.format(pkg, userId)
                val nowMs = android.os.SystemClock.uptimeMillis()
                val prevTapMs = ui.dockLastTapMsByPkg[appKey] ?: 0L
                if (nowMs - prevTapMs < 280L) return@DexMacTaskbar
                ui.dockLastTapMsByPkg[appKey] = nowMs
                ui.dismissAllPopovers()
                val existing = scene.windows.firstOrNull { it.pkg == pkg && it.userId == userId }
                if (existing != null && existing.focused && !existing.isMinimized) {
                    LiveWindowPreviewRegistry.captureLivePreview(existing.windowId)
                    BridgeClient.sendWindowActionAsync(existing.windowId, BridgeProtocol.WinAction.MINIMIZE)
                } else if (existing != null) {
                    BridgeClient.sendWindowActionAsync(existing.windowId, BridgeProtocol.WinAction.FOCUS)
                } else {
                    AppPrefs.recordAppLaunch(ctx, appKey)
                    scope.launch(Dispatchers.IO) {
                        BridgeClient.openWindow(pkg, cls, BridgeProtocol.Profile.AUTO, userId = userId)
                    }
                }
            },
            onContextApp = { appKey, win, rootPos ->
                ui.dismissAllPopovers()
                ui.contextMenuTarget = Triple(appKey, win, rootPos)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(10_010f),
        )
    }

    // Floating Rounded Glass App Launcher Popover above macOS Dock
    if (ui.showAppDrawer) {
        val drawerBottomPadDp = if (dockVisible) effectiveDockInsetDp + 8f else 16f
        val drawerMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + drawerBottomPadDp + 28f).dp)
            .coerceIn(220.dp, 480.dp)
        DexAppDrawerPopover(
            apps = ui.allApps,
            desktopPkgs = desktopPkgs,
            dockPinnedPkgs = dockPinnedPkgs,
            maxHeight = drawerMaxH,
            onLaunch = { entry, profileCode ->
                ui.showAppDrawer = false
                AppPrefs.recordAppLaunch(ctx, entry.appKey)
                scope.launch(Dispatchers.IO) {
                    BridgeClient.openWindow(
                        entry.pkg, entry.activityClass, profileCode, userId = entry.userId,
                    )
                }
            },
            onContextApp = { entry, rootPos ->
                val running = scene.windows.firstOrNull { it.pkg == entry.pkg && it.userId == entry.userId }
                ui.contextMenuTarget = Triple(entry.appKey, running, rootPos)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = drawerBottomPadDp.dp)
                .zIndex(10_020f),
        )
    }

    // Notification Center & Heads-Up Banner
    WorkspaceNotificationsHost(
        ui = ui,
        maxHeight = maxHeight,
        hasFullscreenWindow = fullscreenWin != null,
    )

    // Unified Top-Right macOS Control Center Popover
    if (ui.showControlCenter) {
        WorkspaceControlCenterHost(
            ui = ui,
            trayInfo = trayInfo,
            canvasW = canvasW,
            canvasH = canvasH,
            maxHeight = maxHeight,
            densityVal = densityVal,
            effectiveDockInsetDp = effectiveDockInsetDp,
            displaySummary = displaySummary,
            actualRefreshHz = activeDisplayMetrics.first,
            maxSupportedRefreshHz = activeDisplayMetrics.second,
            isExternalMonitor = isExternalMonitor,
            hasFullscreenWindow = fullscreenWin != null,
            canControlPhoneTrackpad = canControlPhoneTrackpad,
            onPickCustomWallpaper = onPickCustomWallpaper,
            onOpenConsole = onOpenConsole,
        )
    }

    // In-DeX Utility Popovers, Feature Guide, Per-App DPI Editor, Exit Save Slot & Pairing Modal
    WorkspaceUtilityModalsHost(
        ui = ui,
        maxHeight = maxHeight,
        effectiveDockInsetDp = effectiveDockInsetDp,
        currentTargetKey = currentTarget.key,
        onOpenConsole = onOpenConsole,
        onExitDesktop = onExitDesktop,
    )

    // Context Menus (Desktop Guide Icon, Wallpaper, App Shortcut / Dock / Drawer)
    WorkspaceContextMenusHost(
        ui = ui,
        canvasW = canvasW,
        canvasH = canvasH,
        densityVal = densityVal,
        effectiveDockInsetDp = effectiveDockInsetDp,
        canControlPhoneTrackpad = canControlPhoneTrackpad,
        onPickCustomWallpaper = onPickCustomWallpaper,
        onOpenConsole = onOpenConsole,
    )

    // Mission Control / Exposé Multi-Window Overview Overlay
    if (ui.showExposeOverview) {
        Box(modifier = Modifier.fillMaxSize().zIndex(10_030f)) {
            ExposeWindowSwitcherOverlay(
                windows = scene.windows,
                isCompactWidth = isCompactWidth,
                onSelectWindow = { win ->
                    ui.showExposeOverview = false
                    BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.FOCUS)
                },
                onCloseWindow = { win ->
                    BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.CLOSE)
                },
                onMinimizeAll = {
                    ui.showExposeOverview = false
                    minimizeAllVisibleWindows(scene)
                },
                onCloseAll = {
                    ui.showExposeOverview = false
                    BridgeClient.closeAllWindowsAsync(removeTasks = true)
                },
                onDismiss = { ui.showExposeOverview = false },
            )
        }
    }

    // Offline guard if daemon disconnected
    if (!status.connected) {
        OfflineDaemonPromptCard(
            onOpenConsole = onOpenConsole,
            onExit = onExitDesktop,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp)
                .zIndex(10_040f),
        )
    }

    // Cross-display Yuyan companion plugin Floating Candidate Bar + Desktop Soft Keyboard
    io.github.lengsukq.opaldesk.ime.OpalCompositorImeOverlay(
        focusedWindow = scene.focusedWindow,
        canvasW = canvasW,
        canvasH = canvasH,
        dockInsetDp = if (fullscreenWin == null && dockVisible) effectiveDockInsetDp else 0f,
        modifier = Modifier.zIndex(10_045f),
    )

    // Unified DeX Global Spotlight Overlay (Open Windows + Apps + DeX Commands + Host Console Settings)
    if (ui.showSpotlight) {
        WorkspaceSpotlightHost(
            ui = ui,
            spotlightController = spotlightController,
            canvasW = canvasW,
            canvasH = canvasH,
            densityVal = densityVal,
            effectiveDockInsetDp = effectiveDockInsetDp,
            isExternalMonitor = isExternalMonitor,
            canControlPhoneTrackpad = canControlPhoneTrackpad,
            onPickCustomWallpaper = onPickCustomWallpaper,
            onOpenConsole = onOpenConsole,
            onOpenConsoleSection = onOpenConsoleSection,
        )
    }

    // Crisp macOS Arrow Cursor Overlay (for External Monitor Presentation Trackpad / Mouse mode)
    virtualCursorPx?.let { pos ->
        Box(modifier = Modifier.fillMaxSize().zIndex(10_050f)) {
            MacVirtualPointerCursor(positionPx = pos)
        }
    }
}

@Composable
private fun BoxScope.WorkspaceNotificationsHost(
    ui: WorkspaceUiState,
    maxHeight: Dp,
    hasFullscreenWindow: Boolean,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val notifications by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.notificationsFlow.collectAsState()
    val liveNotificationBanner by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.bannerFlow.collectAsState()
    val notifAccessGranted by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.accessGrantedFlow.collectAsState()
    val doNotDisturb by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.doNotDisturbFlow.collectAsState()

    if (ui.showNotificationCenter && !hasFullscreenWindow) {
        val ncMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + 32f).dp).coerceAtLeast(240.dp)
        DesktopNotificationCenterPopover(
            notifications = notifications,
            accessGranted = notifAccessGranted,
            doNotDisturb = doNotDisturb,
            maxHeight = ncMaxH,
            onOpenApp = { pkg -> openNotificationAppAction(ctx, scope, ui, scene, pkg) },
            onDismissNotification = { key ->
                io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.cancelNotification(key)
            },
            onClearAll = {
                io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.clearAllNotifications()
            },
            onToggleDoNotDisturb = {
                io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.toggleDoNotDisturb()
            },
            onRequestAccess = {
                ui.showNotificationCenter = false
                io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.openNotificationAccessSettings(ctx)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = (CompositorGeometry.TOP_BAR_DP + 8f).dp, end = 12.dp)
                .zIndex(10_028f),
        )
    }

    val activeBanner = liveNotificationBanner
    if (activeBanner != null && !doNotDisturb && !ui.showNotificationCenter && !hasFullscreenWindow) {
        DesktopNotificationBanner(
            item = activeBanner,
            onOpenApp = { pkg -> openNotificationAppAction(ctx, scope, ui, scene, pkg) },
            onDismiss = { io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.dismissBanner(activeBanner.key) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = (CompositorGeometry.TOP_BAR_DP + 10f).dp, end = 12.dp)
                .zIndex(10_032f),
        )
    }
}

@Composable
private fun BoxScope.WorkspaceControlCenterHost(
    ui: WorkspaceUiState,
    trayInfo: SystemTrayInfo,
    canvasW: Int,
    canvasH: Int,
    maxHeight: Dp,
    densityVal: Float,
    effectiveDockInsetDp: Float,
    displaySummary: String,
    actualRefreshHz: Int,
    maxSupportedRefreshHz: Int,
    isExternalMonitor: Boolean,
    hasFullscreenWindow: Boolean,
    canControlPhoneTrackpad: Boolean,
    onPickCustomWallpaper: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val remote by BridgeClient.remoteStatusFlow.collectAsState()
    val extDisplay by io.github.lengsukq.opaldesk.BridgeService.externalDisplayFlow.collectAsState()
    val phoneTrackpadVisible by ExternalTrackpadBridge.phoneTrackpadVisibleFlow.collectAsState()

    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val mouseTargetMode by AppPrefs.mouseTargetFlow.collectAsState()
    val externalUiScale by AppPrefs.externalUiScaleFlow.collectAsState()
    val autoBlackoutOnCast by AppPrefs.autoBlackoutOnCastFlow.collectAsState()
    val autoShowPhoneTrackpad by AppPrefs.autoShowPhoneTrackpadFlow.collectAsState()
    val dockVisible by AppPrefs.dockVisibleFlow.collectAsState()
    val dockOpacity by AppPrefs.dockOpacityFlow.collectAsState()
    val dockScale by AppPrefs.dockScaleFlow.collectAsState()
    val globalShortcutsEnabled by AppPrefs.globalShortcutsEnabledFlow.collectAsState()
    val disabledShortcutIds by AppPrefs.disabledShortcutIdsFlow.collectAsState()
    val dexImeDisplayMode by AppPrefs.dexImeDisplayModeFlow.collectAsState()
    val dexLockedImeId by AppPrefs.dexLockedImeIdFlow.collectAsState()
    val showDesktopGuideIcon by AppPrefs.showDesktopGuideIconFlow.collectAsState()

    val ccMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + 24f).dp).coerceAtLeast(240.dp)
    MacControlCenterPopover(
        tray = trayInfo,
        totalWindows = scene.windows.size,
        focusedWindowId = scene.focusedWindow?.windowId ?: -1,
        canvasW = canvasW,
        canvasH = canvasH,
        displaySummary = displaySummary,
        inputBackendLabel = status.injectBackendLabel,
        targetFps = status.targetFps,
        actualRefreshHz = actualRefreshHz,
        maxSupportedRefreshHz = maxSupportedRefreshHz,
        isRemoteCast = remote.connected,
        windowDpiScalePct = status.windowDpiScalePct,
        externalUiScale = externalUiScale,
        themeMode = themeMode,
        wallpaperPreset = wallpaperPreset,
        mouseTargetMode = mouseTargetMode,
        isExternalMonitor = isExternalMonitor,
        hostScreenOff = status.hostScreenOff,
        autoBlackoutOnCast = autoBlackoutOnCast,
        rootEnhanced = status.rootEnhanced,
        showHostScreenToggle = isExternalMonitor || remote.connected || status.hostScreenOff,
        showRemoteMediaSection = isExternalMonitor && (remote.connected || remote.hasActiveDesktopCast),
        showPhoneTrackpadSection = canControlPhoneTrackpad,
        phoneTrackpadVisible = phoneTrackpadVisible,
        showAutoTrackpadSwitch = canControlPhoneTrackpad && !remote.hasActiveDesktopCast,
        autoShowPhoneTrackpad = autoShowPhoneTrackpad,
        dockVisible = dockVisible,
        dockOpacity = dockOpacity,
        dockScale = dockScale,
        globalShortcutsEnabled = globalShortcutsEnabled,
        disabledShortcutIds = disabledShortcutIds,
        dexImeDisplayMode = dexImeDisplayMode,
        dexLockedImeId = dexLockedImeId,
        showDesktopGuideIcon = showDesktopGuideIcon,
        maxHeight = ccMaxH,
        initialTab = ui.controlCenterInitialTab,
        onToggleDockVisible = { enabled -> AppPrefs.setDockVisible(ctx, enabled) },
        onChangeDockOpacity = { opacity -> AppPrefs.setDockOpacity(ctx, opacity) },
        onChangeDockScale = { scale -> AppPrefs.setDockScale(ctx, scale) },
        onResetDockStyle = {
            AppPrefs.resetDockAppearance(ctx)
            Toast.makeText(ctx, ctx.getString(R.string.workspace_dock_reset_toast), Toast.LENGTH_SHORT).show()
        },
        onOpenSystemSettings = { action ->
            ui.dismissAllPopovers()
            launchSystemSettingsInDex(ctx, scope, status.connected, action)
        },
        onTogglePhoneTrackpad = {
            ui.showControlCenter = false
            togglePhoneTrackpadAction(ctx, phoneTrackpadVisible, onOpenConsole)
        },
        onToggleAutoPhoneTrackpad = { enabled ->
            AppPrefs.setAutoShowPhoneTrackpadEnabled(ctx, enabled)
            Toast.makeText(
                ctx,
                ctx.getString(
                    if (enabled) R.string.display_auto_trackpad_sub else R.string.taskbar_cc_auto_trackpad_sub,
                ),
                Toast.LENGTH_SHORT,
            ).show()
        },
        onSelectTargetFps = { fps ->
            BridgeClient.updateDisplayPrefsAsync(
                targetFps = fps,
                windowDpiScalePct = status.windowDpiScalePct,
            )
        },
        onSelectWindowDpiPct = { pct ->
            BridgeClient.updateDisplayPrefsAsync(
                targetFps = status.targetFps,
                windowDpiScalePct = pct,
            )
        },
        onSelectUiScale = { scale -> AppPrefs.setExternalUiScale(ctx, scale) },
        onToggleHostScreenPower = { screenOn ->
            BridgeClient.setHostScreenPowerAsync(screenOn) { ok ->
                val msg = when {
                    !ok -> ctx.getString(R.string.toast_host_screen_unsupported)
                    screenOn -> ctx.getString(R.string.toast_host_screen_on)
                    else -> ctx.getString(R.string.toast_host_screen_off_hint)
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        },
        onToggleAutoBlackout = { enabled ->
            AppPrefs.setAutoBlackoutOnCast(ctx, enabled)
            Toast.makeText(
                ctx,
                ctx.getString(
                    if (enabled) R.string.display_auto_blackout_sub else R.string.common_disabled,
                ),
                Toast.LENGTH_SHORT,
            ).show()
        },
        onSelectThemeMode = { mode -> AppPrefs.setThemeMode(ctx, mode) },
        onSelectWallpaper = { idx ->
            WallpaperStorage.clearCustomWallpaper(ctx)
            AppPrefs.setWallpaperPreset(ctx, idx)
        },
        onPickCustomWallpaper = {
            ui.showControlCenter = false
            onPickCustomWallpaper()
        },
        onSelectMouseTarget = { mode ->
            AppPrefs.setMouseTarget(ctx, mode)
            val extId = ExternalTrackpadBridge.sessionFlow.value.displayId
            BridgeClient.setMouseTargetAsync(mode, if (extId > 0) extId else -1)
        },
        onOpenDpiSettings = {
            ui.showControlCenter = false
            ui.showDpiPopover = true
        },
        onOpenResolutionSettings = if (isExternalMonitor || extDisplay != null || remote.connected) {
            {
                ui.showControlCenter = false
                ui.showResolutionModal = true
            }
        } else {
            null
        },
        onToggleExternalImeSync = { syncToExternal ->
            val nextMode = if (syncToExternal) {
                BridgeProtocol.DexImeDisplayMode.ALWAYS_LOCAL
            } else {
                BridgeProtocol.DexImeDisplayMode.AUTO
            }
            AppPrefs.setDexImeDisplayMode(ctx, nextMode)
            BridgeClient.setDexImeConfigAsync(
                ctx = ctx,
                displayMode = nextMode,
                lockedImeId = dexLockedImeId,
                muteHostOnCast = AppPrefs.isMuteHostImeOnCastEnabled(ctx),
                showWithHardKbd = AppPrefs.getHardKbdSoftImeMode(ctx),
                autoBridgeImeOnCast = AppPrefs.isAutoBridgeImeOnCastEnabled(ctx),
            )
            Toast.makeText(
                ctx,
                ctx.getString(
                    if (syncToExternal) R.string.taskbar_cc_ext_ime_local else R.string.taskbar_cc_ext_ime_auto,
                ),
                Toast.LENGTH_SHORT,
            ).show()
        },
        onOpenImeSettings = {
            ui.showControlCenter = false
            ui.showImePopover = true
        },
        onOpenKeyboardShortcuts = {
            ui.showControlCenter = false
            ui.showShortcutsPopover = true
        },
        onOpenFeatureGuide = {
            ui.showControlCenter = false
            ui.showFeatureGuide = true
        },
        onApplyTilingLayout = { layout ->
            ui.dismissAllPopovers()
            applyWindowTilingLayout(layout, scene, canvasW, canvasH, densityVal, effectiveDockInsetDp)
        },
        onMinimizeAll = {
            ui.showControlCenter = false
            minimizeAllVisibleWindows(scene)
        },
        onCloseAll = {
            ui.showControlCenter = false
            BridgeClient.closeAllWindowsAsync(removeTasks = true)
        },
        onDismissPopover = { ui.showControlCenter = false },
        onOpenConsole = {
            ui.showControlCenter = false
            onOpenConsole()
        },
        onExitDesktop = {
            ui.showControlCenter = false
            ui.showExitSlotModal = true
        },
        modifier = Modifier
            .align(if (hasFullscreenWindow) Alignment.TopCenter else Alignment.TopEnd)
            .padding(
                top = if (hasFullscreenWindow) 48.dp else (CompositorGeometry.TOP_BAR_DP + 8f).dp,
                start = 12.dp,
                end = 12.dp,
            )
            .zIndex(10_020f),
    )
}

@Composable
private fun BoxScope.WorkspaceUtilityModalsHost(
    ui: WorkspaceUiState,
    maxHeight: Dp,
    effectiveDockInsetDp: Float,
    currentTargetKey: String,
    onOpenConsole: () -> Unit,
    onExitDesktop: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val remote by BridgeClient.remoteStatusFlow.collectAsState()
    val extDisplay by io.github.lengsukq.opaldesk.BridgeService.externalDisplayFlow.collectAsState()
    val pendingPairing by BridgeClient.pendingPairingFlow.collectAsState()

    // In-DeX Live DPI Settings Floating Panel (global + per-app DPI with real-time preview)
    if (ui.showDpiPopover) {
        val dpiMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + 24f).dp).coerceAtLeast(240.dp)
        io.github.lengsukq.opaldesk.ui.DexDpiSettingsPopover(
            openWindows = scene.windows,
            allApps = ui.allApps,
            maxHeight = dpiMaxH,
            onDismiss = { ui.showDpiPopover = false },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = (CompositorGeometry.TOP_BAR_DP + 8f).dp, start = 12.dp, end = 12.dp)
                .zIndex(10_022f),
        )
    }

    // In-DeX Custom Resolution & Refresh Rate Modal
    if (ui.showResolutionModal) {
        val resMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + effectiveDockInsetDp + 28f).dp)
            .coerceAtLeast(300.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10_027f)
                .background(OpalPalette.ScrimColor.copy(alpha = 0.28f))
                .pointerInput(Unit) { detectTapGestures { ui.showResolutionModal = false } }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            io.github.lengsukq.opaldesk.ui.DexCustomResolutionWindow(
                extDisplay = extDisplay,
                remote = remote,
                maxHeight = resMaxH,
                onDismiss = { ui.showResolutionModal = false },
            )
        }
    }

    // In-DeX IME Display Routing & Locked Keyboard Popover
    if (ui.showImePopover) {
        val imeMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + 24f).dp).coerceAtLeast(240.dp)
        io.github.lengsukq.opaldesk.ui.DexImeSettingsPopover(
            maxHeight = imeMaxH,
            onOpenSystemImeSettings = {
                ui.showImePopover = false
                ui.dismissAllPopovers()
                launchSystemSettingsInDex(ctx, scope, status.connected, android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
            },
            onDismiss = { ui.showImePopover = false },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = (CompositorGeometry.TOP_BAR_DP + 8f).dp, start = 12.dp, end = 12.dp)
                .zIndex(10_023f),
        )
    }

    // In-DeX Keyboard Shortcuts Cheatsheet & Toggle Popover
    if (ui.showShortcutsPopover) {
        val shortcutsMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + 24f).dp).coerceAtLeast(240.dp)
        io.github.lengsukq.opaldesk.ui.DexKeyboardShortcutsPopover(
            maxHeight = shortcutsMaxH,
            onDismiss = { ui.showShortcutsPopover = false },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = (CompositorGeometry.TOP_BAR_DP + 8f).dp, start = 12.dp, end = 12.dp)
                .zIndex(10_023f),
        )
    }

    // Built-in Liquid Glass Feature Guide Window (hot-updateable, zero VirtualDisplay quota)
    if (ui.showFeatureGuide) {
        val guideMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + effectiveDockInsetDp + 28f).dp)
            .coerceAtLeast(300.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10_028f)
                .background(OpalPalette.ScrimColor.copy(alpha = 0.28f))
                .pointerInput(Unit) { detectTapGestures { ui.showFeatureGuide = false } }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            io.github.lengsukq.opaldesk.ui.DexFeatureGuideWindow(
                maxHeight = guideMaxH,
                onAction = { action, url ->
                    when (action) {
                        io.github.lengsukq.opaldesk.guide.OpalGuidePolicy.ACTION_OPEN_SHORTCUTS -> {
                            ui.showFeatureGuide = false
                            ui.showShortcutsPopover = true
                        }
                        io.github.lengsukq.opaldesk.guide.OpalGuidePolicy.ACTION_OPEN_DPI -> {
                            ui.showFeatureGuide = false
                            ui.showDpiPopover = true
                        }
                        io.github.lengsukq.opaldesk.guide.OpalGuidePolicy.ACTION_OPEN_TILING -> {
                            ui.showFeatureGuide = false
                            ui.controlCenterInitialTab = 1
                            ui.showControlCenter = true
                        }
                        io.github.lengsukq.opaldesk.guide.OpalGuidePolicy.ACTION_OPEN_DOCK_SETTINGS -> {
                            ui.showFeatureGuide = false
                            ui.controlCenterInitialTab = 2
                            ui.showControlCenter = true
                        }
                        io.github.lengsukq.opaldesk.guide.OpalGuidePolicy.ACTION_OPEN_CONTROL_CENTER -> {
                            ui.showFeatureGuide = false
                            ui.controlCenterInitialTab = 0
                            ui.showControlCenter = true
                        }
                        io.github.lengsukq.opaldesk.guide.OpalGuidePolicy.ACTION_OPEN_CONSOLE -> {
                            ui.showFeatureGuide = false
                            onOpenConsole()
                        }
                        io.github.lengsukq.opaldesk.guide.OpalGuidePolicy.ACTION_JOIN_QQ -> {
                            io.github.lengsukq.opaldesk.ui.joinOpalDeskQqGroup(ctx)
                        }
                        io.github.lengsukq.opaldesk.guide.OpalGuidePolicy.ACTION_OPEN_URL -> {
                            io.github.lengsukq.opaldesk.ui.openAllowedGuideUrl(ctx, url)
                        }
                    }
                },
                onDismiss = { ui.showFeatureGuide = false },
            )
        }
    }

    // Per-app custom DPI editor modal (live preview; saved when user commits to a slot on exit)
    ui.editingAppDpiTarget?.let { (pkg, appLabel) ->
        val profileState by io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.stateFlow.collectAsState()
        val working = profileState.workingConfig
        io.github.lengsukq.opaldesk.ui.PerAppDpiEditorModal(
            pkg = pkg,
            appLabel = appLabel,
            currentCustomDpi = working.appCustomDpis[pkg] ?: 0,
            globalCustomDpi = working.globalCustomDpi,
            onApplyDpi = { nextDpi ->
                BridgeClient.setAppCustomDpiAsync(ctx, pkg, nextDpi)
                val tip = if (nextDpi > 0) {
                    ctx.getString(R.string.workspace_app_dpi_applied, nextDpi, appLabel)
                } else {
                    ctx.getString(R.string.display_dpi_reset_toast, appLabel)
                }
                Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { ui.editingAppDpiTarget = null },
            modifier = Modifier.zIndex(10_055f),
        )
    }

    // Exit DeX modal: lets user pick which of the 5 slots to save current config & layout into
    if (ui.showExitSlotModal) {
        io.github.lengsukq.opaldesk.ui.ExitSaveSlotModal(
            title = stringResource(R.string.workspace_exit_dex_title),
            subtitle = stringResource(R.string.workspace_exit_dex_sub),
            confirmLabel = stringResource(R.string.exit_app_save_and_exit),
            openWindowCount = scene.windows.size,
            busy = ui.savingOnExit,
            onDismiss = { if (!ui.savingOnExit) ui.showExitSlotModal = false },
            onExitWithoutSaving = {
                if (!ui.savingOnExit) {
                    ui.showExitSlotModal = false
                    onExitDesktop()
                }
            },
            onSaveToSlotAndExit = { slotId, saveLayout ->
                if (!ui.savingOnExit) {
                    ui.savingOnExit = true
                    scope.launch(Dispatchers.IO) {
                        BridgeClient.pullDaemonWindowBoundsSync()
                        val saved = io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.saveWorkingToSlot(
                            ctx = ctx,
                            targetSlotId = slotId,
                            scene = scene,
                            saveCurrentLayout = saveLayout,
                            bindTargetKey = currentTargetKey,
                        )
                        withContext(Dispatchers.Main) {
                            ui.savingOnExit = false
                            ui.showExitSlotModal = false
                            Toast.makeText(ctx, ctx.getString(R.string.slot_saved_toast, slotId + 1, saved.name), Toast.LENGTH_SHORT).show()
                            onExitDesktop()
                        }
                    }
                }
            },
            modifier = Modifier.zIndex(10_058f),
        )
    }

    // Host-side interactive wireless pairing consent prompt (also active in Desktop mode)
    pendingPairing?.let { req ->
        PairingConsentModal(
            request = req,
            modifier = Modifier.zIndex(10_060f),
        )
    }
}

@Composable
private fun WorkspaceContextMenusHost(
    ui: WorkspaceUiState,
    canvasW: Int,
    canvasH: Int,
    densityVal: Float,
    effectiveDockInsetDp: Float,
    canControlPhoneTrackpad: Boolean,
    onPickCustomWallpaper: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Built-in Desktop "使用指南" Icon Right-Click / Long-Press Context Menu
    ui.guideIconMenuPos?.let { pos ->
        val menuW = with(density) { 214.dp.toPx() }.roundToInt()
        val menuH = with(density) { 112.dp.toPx() }.roundToInt()
        val (xPx, yPx) = CompositorGeometry.computeAnchoredMenuPosition(
            anchorX = pos.x.roundToInt(),
            anchorY = pos.y.roundToInt(),
            menuW = menuW,
            menuH = menuH,
            canvasW = canvasW,
            canvasH = canvasH,
            density = densityVal,
        )
        io.github.lengsukq.opaldesk.ui.GuideIconContextMenu(
            onOpenGuide = { ui.showFeatureGuide = true },
            onHideFromDesktop = {
                AppPrefs.setDesktopGuideIconVisible(ctx, false)
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.workspace_guide_hidden_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onDismiss = { ui.guideIconMenuPos = null },
            modifier = Modifier
                .offset { IntOffset(xPx, yPx) }
                .zIndex(10_026f),
        )
    }

    // Desktop Wallpaper Right-Click / Long-Press Context Menu (anchored right at cursor)
    ui.wallpaperMenuPos?.let { pos ->
        val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
        val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
        val showDesktopGuideIcon by AppPrefs.showDesktopGuideIconFlow.collectAsState()
        val phoneTrackpadVisible by ExternalTrackpadBridge.phoneTrackpadVisibleFlow.collectAsState()

        val isCurrentlyDark = OpalPalette.isDark
        val menuW = with(density) { 252.dp.toPx() }.roundToInt()
        val baseMenuDp = (if (customWallpaperPath.isNotBlank()) 396f else 360f) +
            (if (canControlPhoneTrackpad) 38f else 0f) +
            (if (!showDesktopGuideIcon) 38f else 0f)
        val menuH = with(density) { baseMenuDp.dp.toPx() }.roundToInt()
        val (xPx, yPx) = CompositorGeometry.computeAnchoredMenuPosition(
            anchorX = pos.x.roundToInt(),
            anchorY = pos.y.roundToInt(),
            menuW = menuW,
            menuH = menuH,
            canvasW = canvasW,
            canvasH = canvasH,
            density = densityVal,
        )
        DesktopWallpaperContextMenu(
            wallpaperPreset = wallpaperPreset,
            hasCustomWallpaper = customWallpaperPath.isNotBlank(),
            showPhoneTrackpadAction = canControlPhoneTrackpad,
            phoneTrackpadVisible = phoneTrackpadVisible,
            showDesktopGuideIcon = showDesktopGuideIcon,
            onTogglePhoneTrackpad = { togglePhoneTrackpadAction(ctx, phoneTrackpadVisible, onOpenConsole) },
            onSelectPreset = { idx ->
                WallpaperStorage.clearCustomWallpaper(ctx)
                AppPrefs.setWallpaperPreset(ctx, idx)
            },
            onPickCustomWallpaper = onPickCustomWallpaper,
            onClearCustomWallpaper = {
                WallpaperStorage.clearCustomWallpaper(ctx)
                Toast.makeText(ctx, ctx.getString(R.string.display_wallpaper_restored_toast), Toast.LENGTH_SHORT).show()
            },
            onOpenTilingMenu = {
                ui.controlCenterInitialTab = 1
                ui.showControlCenter = true
            },
            onOpenDockSettings = {
                ui.controlCenterInitialTab = 2
                ui.showControlCenter = true
            },
            onOpenAppDrawer = { ui.showAppDrawer = true },
            onOpenFeatureGuide = { ui.showFeatureGuide = true },
            onRestoreDesktopGuideIcon = {
                AppPrefs.setDesktopGuideIconVisible(ctx, true)
                Toast.makeText(ctx, ctx.getString(R.string.workspace_guide_restored_toast), Toast.LENGTH_SHORT).show()
            },
            onToggleLightDark = {
                val next = if (isCurrentlyDark) AppPrefs.THEME_LIGHT else AppPrefs.THEME_DARK
                AppPrefs.setThemeMode(ctx, next)
            },
            onOpenConsole = onOpenConsole,
            onDismiss = { ui.wallpaperMenuPos = null },
            modifier = Modifier
                .offset { IntOffset(xPx, yPx) }
                .zIndex(10_025f),
        )
    }

    // Unified App Context Menu anchored right at cursor / finger position (auto-flips near edges!)
    ui.contextMenuTarget?.let { (appKey, win, anchorPos) ->
        val desktopPkgs by AppPrefs.desktopShortcutsFlow.collectAsState()
        val dockPinnedPkgs by AppPrefs.dockPinnedFlow.collectAsState()

        val (parsedPkg, parsedUid) = io.github.lengsukq.opaldesk.protocol.AppProfileKey.parse(appKey)
        val appEntry = ui.allApps.firstOrNull { it.appKey == appKey }
        val targetPkg = appEntry?.pkg ?: win?.pkg ?: parsedPkg
        val targetUserId = appEntry?.userId ?: win?.userId ?: parsedUid
        val targetProfileType = appEntry?.profileType?.ifEmpty { win?.profileType.orEmpty() }
            ?: win?.profileType.orEmpty()
        val baseLabel = win?.title ?: appEntry?.label ?: targetPkg
        val profileSuffix = if (targetUserId > 0) {
            " · ${AppCatalogRepository.formatProfileBadge(ctx, targetProfileType, targetUserId)}"
        } else {
            ""
        }
        val menuW = with(density) { 248.dp.toPx() }.roundToInt()
        val menuH = with(density) { (if (win != null) 316.dp else 174.dp).toPx() }.roundToInt()
        val (xPx, yPx) = CompositorGeometry.computeAnchoredMenuPosition(
            anchorX = anchorPos.x.roundToInt(),
            anchorY = anchorPos.y.roundToInt(),
            menuW = menuW,
            menuH = menuH,
            canvasW = canvasW,
            canvasH = canvasH,
            density = densityVal,
        )
        TaskbarContextMenuPopover(
            pkg = targetPkg,
            label = baseLabel + profileSuffix,
            window = win,
            isOnDesktop = desktopPkgs.contains(appKey),
            isPinnedToDock = dockPinnedPkgs.contains(appKey),
            dockPinnedCount = dockPinnedPkgs.size,
            onFocusOrLaunch = {
                ui.showAppDrawer = false
                if (win != null) {
                    BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.FOCUS)
                } else {
                    AppPrefs.recordAppLaunch(ctx, appKey)
                    scope.launch(Dispatchers.IO) {
                        BridgeClient.openWindow(
                            targetPkg,
                            appEntry?.activityClass.orEmpty(),
                            BridgeProtocol.Profile.AUTO,
                            userId = targetUserId,
                        )
                    }
                }
            },
            onSnapLeft = {
                if (win != null) {
                    val r = CompositorGeometry.computeSnapPreviewRect(
                        BridgeProtocol.Presentation.SNAP_LEFT, canvasW, canvasH, densityVal, effectiveDockInsetDp,
                    )
                    BridgeClient.updateWindowFrameAsync(
                        win.windowId, r.left, r.top, r.right, r.bottom,
                        BridgeProtocol.Presentation.SNAP_LEFT,
                    )
                }
            },
            onSnapRight = {
                if (win != null) {
                    val r = CompositorGeometry.computeSnapPreviewRect(
                        BridgeProtocol.Presentation.SNAP_RIGHT, canvasW, canvasH, densityVal, effectiveDockInsetDp,
                    )
                    BridgeClient.updateWindowFrameAsync(
                        win.windowId, r.left, r.top, r.right, r.bottom,
                        BridgeProtocol.Presentation.SNAP_RIGHT,
                    )
                }
            },
            onMaximize = {
                if (win != null) {
                    BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.TOGGLE_MAXIMIZE)
                }
            },
            onEditAppDpi = {
                ui.editingAppDpiTarget = targetPkg to baseLabel
            },
            onToggleDesktopShortcut = {
                val added = AppPrefs.toggleDesktopShortcut(ctx, appKey)
                Toast.makeText(
                    ctx,
                    ctx.getString(
                        if (added) R.string.workspace_desktop_pinned else R.string.workspace_desktop_unpinned,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onToggleDockPin = {
                when (AppPrefs.toggleDockPin(ctx, appKey)) {
                    AppPrefs.DockPinResult.ADDED ->
                        Toast.makeText(ctx, ctx.getString(R.string.workspace_dock_pinned), Toast.LENGTH_SHORT).show()
                    AppPrefs.DockPinResult.REMOVED ->
                        Toast.makeText(ctx, ctx.getString(R.string.workspace_dock_unpinned), Toast.LENGTH_SHORT).show()
                    AppPrefs.DockPinResult.LIMIT_REACHED ->
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.workspace_dock_limit, AppPrefs.MAX_DOCK_PINNED),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            },
            onCloseWindow = {
                if (win != null) {
                    BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.CLOSE)
                }
            },
            onDismiss = { ui.contextMenuTarget = null },
            modifier = Modifier
                .offset { IntOffset(xPx, yPx) }
                .zIndex(10_026f),
        )
    }
}

@Composable
private fun WorkspaceSpotlightHost(
    ui: WorkspaceUiState,
    spotlightController: OpalSpotlightController,
    canvasW: Int,
    canvasH: Int,
    densityVal: Float,
    effectiveDockInsetDp: Float,
    isExternalMonitor: Boolean,
    canControlPhoneTrackpad: Boolean,
    onPickCustomWallpaper: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenConsoleSection: ((tabIndex: Int, sectionKey: String) -> Unit)?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val remote by BridgeClient.remoteStatusFlow.collectAsState()
    val extDisplay by io.github.lengsukq.opaldesk.BridgeService.externalDisplayFlow.collectAsState()
    val phoneTrackpadVisible by ExternalTrackpadBridge.phoneTrackpadVisibleFlow.collectAsState()

    val profileState by io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.stateFlow.collectAsState()
    val imeState by BridgeClient.imeStateFlow.collectAsState()
    val trustedPeers by BridgeClient.trustedPeersFlow.collectAsState()
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val externalUiScale by AppPrefs.externalUiScaleFlow.collectAsState()
    val dockVisible by AppPrefs.dockVisibleFlow.collectAsState()
    val globalShortcutsEnabled by AppPrefs.globalShortcutsEnabledFlow.collectAsState()
    val disabledShortcutIds by AppPrefs.disabledShortcutIdsFlow.collectAsState()
    val dexImeDisplayMode by AppPrefs.dexImeDisplayModeFlow.collectAsState()
    val dexLockedImeId by AppPrefs.dexLockedImeIdFlow.collectAsState()
    val autoBridgeImeOnCast by AppPrefs.autoBridgeImeOnCastFlow.collectAsState()
    val autoCustomResEnabled by AppPrefs.autoCustomResolutionEnabledFlow.collectAsState()
    val defaultCustomRes by AppPrefs.defaultCustomResolutionFlow.collectAsState()
    val audioRouteMode by AppPrefs.audioRouteModeFlow.collectAsState()
    val usbTetherEnabled by AppPrefs.usbTetherCastEnabledFlow.collectAsState()
    val trackpadSens by AppPrefs.trackpadSensitivityFlow.collectAsState()
    val launchCounts by AppPrefs.appLaunchCountsFlow.collectAsState()
    val spotlightRecents by AppPrefs.spotlightRecentsFlow.collectAsState()
    val notifications by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.notificationsFlow.collectAsState()
    val notifAccessGranted by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.accessGrantedFlow.collectAsState()

    val isCurrentlyDark = OpalPalette.isDark
    val curExtDisplay = extDisplay
    val consoleSpotlightState = buildWorkspaceConsoleSpotlightState(
        ctx = ctx,
        status = status,
        scene = scene,
        remote = remote,
        extDisplay = curExtDisplay,
        profileState = profileState,
        imeState = imeState,
        themeMode = themeMode,
        externalUiScale = externalUiScale,
        autoCustomResEnabled = autoCustomResEnabled,
        defaultCustomRes = defaultCustomRes,
        trackpadSens = trackpadSens,
        dexImeDisplayMode = dexImeDisplayMode,
        dexLockedImeId = dexLockedImeId,
        autoBridgeImeOnCast = autoBridgeImeOnCast,
        globalShortcutsEnabled = globalShortcutsEnabled,
        disabledShortcutIds = disabledShortcutIds,
        notifAccessGranted = notifAccessGranted,
        audioRouteMode = audioRouteMode,
        usbTetherEnabled = usbTetherEnabled,
        trustedPeerCount = trustedPeers.size,
    )
    val dexSpotlightState = DexSpotlightState(
        openWindowCount = scene.windows.size,
        hasFocusedWindow = scene.focusedWindow != null,
        isExternalMonitor = isExternalMonitor,
        isDarkTheme = isCurrentlyDark,
        dockVisible = dockVisible,
        hostScreenOff = status.hostScreenOff,
        canControlPhoneTrackpad = canControlPhoneTrackpad,
        phoneTrackpadVisible = phoneTrackpadVisible,
        resolutionEditable = isExternalMonitor || curExtDisplay != null || remote.connected,
        unreadNotificationCount = notifications.size,
    )
    val aggregatedSpotlightItems = remember(
        scene.windows,
        ui.allApps,
        launchCounts,
        dexSpotlightState,
        consoleSpotlightState,
    ) {
        buildWorkspaceSpotlightItems(
            ctx = ctx,
            scene = scene,
            allApps = ui.allApps,
            launchCounts = launchCounts,
            dexSpotlightState = dexSpotlightState,
            consoleSpotlightState = consoleSpotlightState,
        )
    }
    val appsByKey = remember(ui.allApps) { ui.allApps.associateBy { it.appKey } }

    OpalSpotlightOverlay(
        items = aggregatedSpotlightItems,
        recentIds = spotlightRecents,
        placeholder = stringResource(R.string.workspace_spotlight_placeholder),
        quickChips = DexCommandCatalog.QUICK_CHIPS,
        controller = spotlightController,
        iconProvider = { item ->
            val appKey = item.payload
            if ((item.category == SpotlightCategory.RUNNING_WINDOW || item.category == SpotlightCategory.APP) &&
                appKey.isNotBlank()
            ) {
                val (p, u) = io.github.lengsukq.opaldesk.protocol.AppProfileKey.parse(appKey)
                val pType = appsByKey[appKey]?.profileType.orEmpty()
                remember(appKey) { AppCatalogRepository.getAppIcon(ctx, p, u, pType) }
            } else {
                null
            }
        },
        onSelectItem = { chosen ->
            AppPrefs.recordSpotlightRecent(ctx, chosen.id)
            ui.showSpotlight = false
            when (chosen.category) {
                SpotlightCategory.RUNNING_WINDOW -> {
                    val winId = chosen.id.removePrefix("win:").toIntOrNull()
                    if (winId != null) {
                        BridgeClient.sendWindowActionAsync(winId, BridgeProtocol.WinAction.FOCUS)
                    }
                }
                SpotlightCategory.APP -> {
                    val appKey = chosen.payload.ifBlank { chosen.id.removePrefix("app:") }
                    val (pkg, userId) = io.github.lengsukq.opaldesk.protocol.AppProfileKey.parse(appKey)
                    val existing = scene.windows.firstOrNull { it.pkg == pkg && it.userId == userId }
                    if (existing != null) {
                        BridgeClient.sendWindowActionAsync(existing.windowId, BridgeProtocol.WinAction.FOCUS)
                    } else {
                        val entry = appsByKey[appKey]
                        AppPrefs.recordAppLaunch(ctx, appKey)
                        scope.launch(Dispatchers.IO) {
                            BridgeClient.openWindow(
                                pkg,
                                entry?.activityClass.orEmpty(),
                                BridgeProtocol.Profile.AUTO,
                                userId = userId,
                            )
                        }
                    }
                }
                SpotlightCategory.DEX_ACTION -> {
                    when (chosen.id) {
                        DexCommandIds.SPLIT_2 -> {
                            ui.dismissAllPopovers()
                            applyWindowTilingLayout(WindowTilingLayout.SPLIT_2_COLS, scene, canvasW, canvasH, densityVal, effectiveDockInsetDp)
                        }
                        DexCommandIds.SPLIT_2_MAIN_SIDE -> {
                            ui.dismissAllPopovers()
                            applyWindowTilingLayout(WindowTilingLayout.SPLIT_2_MAIN_SIDE, scene, canvasW, canvasH, densityVal, effectiveDockInsetDp)
                        }
                        DexCommandIds.SPLIT_3 -> {
                            ui.dismissAllPopovers()
                            applyWindowTilingLayout(WindowTilingLayout.SPLIT_3_COLS, scene, canvasW, canvasH, densityVal, effectiveDockInsetDp)
                        }
                        DexCommandIds.SPLIT_4_COLS -> {
                            ui.dismissAllPopovers()
                            applyWindowTilingLayout(WindowTilingLayout.SPLIT_4_COLS, scene, canvasW, canvasH, densityVal, effectiveDockInsetDp)
                        }
                        DexCommandIds.SPLIT_4_GRID -> {
                            ui.dismissAllPopovers()
                            applyWindowTilingLayout(WindowTilingLayout.SPLIT_4_GRID, scene, canvasW, canvasH, densityVal, effectiveDockInsetDp)
                        }
                        DexCommandIds.SPLIT_6_GRID -> {
                            ui.dismissAllPopovers()
                            applyWindowTilingLayout(WindowTilingLayout.SPLIT_6_GRID, scene, canvasW, canvasH, densityVal, effectiveDockInsetDp)
                        }
                        DexCommandIds.CASCADE -> {
                            ui.dismissAllPopovers()
                            applyWindowTilingLayout(WindowTilingLayout.CASCADE, scene, canvasW, canvasH, densityVal, effectiveDockInsetDp)
                        }
                        DexCommandIds.EXPOSE -> ui.showExposeOverview = true
                        DexCommandIds.MINIMIZE_ALL -> minimizeAllVisibleWindows(scene)
                        DexCommandIds.CLOSE_ALL -> BridgeClient.closeAllWindowsAsync(removeTasks = true)
                        DexCommandIds.SCREENSHOT_WINDOW -> {
                            ControlCenterActions.captureFocusedWindowScreenshot(
                                ctx,
                                scene.focusedWindow?.windowId ?: -1,
                            )
                        }
                        DexCommandIds.SCREENSHOT_DESKTOP -> {
                            scope.launch {
                                delay(120L)
                                ControlCenterActions.captureDesktopScreenshot(ctx)
                            }
                        }
                        DexCommandIds.TOGGLE_BLACKOUT -> {
                            val nextScreenOn = status.hostScreenOff
                            BridgeClient.setHostScreenPowerAsync(nextScreenOn) { ok ->
                                val msg = when {
                                    !ok -> ctx.getString(R.string.toast_host_screen_unsupported)
                                    nextScreenOn -> ctx.getString(R.string.toast_host_screen_on)
                                    else -> ctx.getString(R.string.toast_host_screen_off_hint)
                                }
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        DexCommandIds.TOGGLE_TRACKPAD -> togglePhoneTrackpadAction(ctx, phoneTrackpadVisible, onOpenConsole)
                        DexCommandIds.OPEN_DPI -> ui.showDpiPopover = true
                        DexCommandIds.OPEN_RESOLUTION -> {
                            if (isExternalMonitor || curExtDisplay != null || remote.connected) {
                                ui.showResolutionModal = true
                            } else {
                                openConsoleSectionAction(ctx, isExternalMonitor, onOpenConsoleSection, 1, io.github.lengsukq.opaldesk.ui.ConsoleSectionKeys.CUSTOM_RESOLUTION)
                            }
                        }
                        DexCommandIds.OPEN_IME -> ui.showImePopover = true
                        DexCommandIds.OPEN_SHORTCUTS -> ui.showShortcutsPopover = true
                        DexCommandIds.PICK_WALLPAPER -> onPickCustomWallpaper()
                        DexCommandIds.TOGGLE_THEME -> {
                            val next = if (isCurrentlyDark) AppPrefs.THEME_LIGHT else AppPrefs.THEME_DARK
                            AppPrefs.setThemeMode(ctx, next)
                        }
                        DexCommandIds.TOGGLE_DOCK -> {
                            AppPrefs.setDockVisible(ctx, !dockVisible)
                        }
                        DexCommandIds.OPEN_GUIDE -> ui.showFeatureGuide = true
                        DexCommandIds.OPEN_NOTIFICATIONS -> {
                            ui.showNotificationCenter = true
                            io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.refreshAccessState(ctx)
                            io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.requestSync()
                        }
                        DexCommandIds.OPEN_CONTROL_CENTER -> {
                            ui.controlCenterInitialTab = 0
                            ui.showControlCenter = true
                        }
                        DexCommandIds.OPEN_CONSOLE -> onOpenConsole()
                        DexCommandIds.EXIT_DEX -> ui.showExitSlotModal = true
                    }
                }
                SpotlightCategory.CONSOLE_SECTION -> {
                    val tab = chosen.tabIndex ?: 0
                    val section = chosen.sectionKey.orEmpty()
                    openConsoleSectionAction(ctx, isExternalMonitor, onOpenConsoleSection, tab, section)
                }
                SpotlightCategory.CONSOLE_ACTION -> onOpenConsole()
            }
        },
        onDismiss = { ui.showSpotlight = false },
        modifier = Modifier.zIndex(10_048f),
    )
}
