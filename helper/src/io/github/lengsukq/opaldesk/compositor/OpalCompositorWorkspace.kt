package io.github.lengsukq.opaldesk.compositor

import android.widget.Toast
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.ExternalTrackpadPolicy
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.search.DexCommandCatalog
import io.github.lengsukq.opaldesk.search.DexSpotlightState
import io.github.lengsukq.opaldesk.search.SpotlightCategory
import io.github.lengsukq.opaldesk.ui.OpalAmbientBackdrop
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSpotlightController
import io.github.lengsukq.opaldesk.ui.OpalSpotlightOverlay
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import io.github.lengsukq.opaldesk.ui.rememberOpalSpotlightController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    val dockVisible by AppPrefs.dockVisibleFlow.collectAsState()
    val dockScale by AppPrefs.dockScaleFlow.collectAsState()
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

    WorkspaceWindowLayer(
        ui = ui,
        trayInfo = trayInfo,
        spotlightController = spotlightController,
        canvasW = canvasW,
        canvasH = canvasH,
        isCompactWidth = isCompactWidth,
        effectiveDockInsetDp = effectiveDockInsetDp,
        shortcutRowsPerCol = shortcutRowsPerCol,
        fullscreenWin = fullscreenWin,
        isExternalMonitor = isExternalMonitor,
        suppressFullscreenExitOverlay = suppressFullscreenExitOverlay,
        canControlPhoneTrackpad = canControlPhoneTrackpad,
        imeActive = imeActive,
        onToggleSoftKeyboard = onToggleSoftKeyboard,
        onOpenConsole = onOpenConsole,
    )

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

    WorkspaceOverlayHost(
        ui = ui,
        trayInfo = trayInfo,
        canvasW = canvasW,
        canvasH = canvasH,
        maxHeight = maxHeight,
        isCompactWidth = isCompactWidth,
        densityVal = densityVal,
        effectiveDockInsetDp = effectiveDockInsetDp,
        displaySummary = displaySummary,
        actualRefreshHz = activeDisplayMetrics.first,
        maxSupportedRefreshHz = activeDisplayMetrics.second,
        currentTargetKey = currentTarget.key,
        isExternalMonitor = isExternalMonitor,
        hasFullscreenWindow = fullscreenWin != null,
        canControlPhoneTrackpad = canControlPhoneTrackpad,
        virtualCursorPx = virtualCursorPx,
        onPickCustomWallpaper = onPickCustomWallpaper,
        onOpenConsole = onOpenConsole,
        onExitDesktop = onExitDesktop,
    )

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
            handleWorkspaceSpotlightSelection(
                ctx = ctx,
                scope = scope,
                ui = ui,
                status = status,
                scene = scene,
                remote = remote,
                curExtDisplay = curExtDisplay,
                appsByKey = appsByKey,
                chosen = chosen,
                canvasW = canvasW,
                canvasH = canvasH,
                densityVal = densityVal,
                effectiveDockInsetDp = effectiveDockInsetDp,
                isExternalMonitor = isExternalMonitor,
                isCurrentlyDark = isCurrentlyDark,
                dockVisible = dockVisible,
                phoneTrackpadVisible = phoneTrackpadVisible,
                onPickCustomWallpaper = onPickCustomWallpaper,
                onOpenConsole = onOpenConsole,
                onOpenConsoleSection = onOpenConsoleSection,
            )
        },
        onDismiss = { ui.showSpotlight = false },
        modifier = Modifier.zIndex(10_048f),
    )
}
