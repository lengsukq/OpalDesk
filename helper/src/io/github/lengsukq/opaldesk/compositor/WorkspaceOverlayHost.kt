package io.github.lengsukq.opaldesk.compositor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.PairingConsentModal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BoxScope.WorkspaceOverlayHost(
    ui: WorkspaceUiState,
    trayInfo: SystemTrayInfo,
    canvasW: Int,
    canvasH: Int,
    maxHeight: Dp,
    isCompactWidth: Boolean,
    densityVal: Float,
    effectiveDockInsetDp: Float,
    displaySummary: String,
    actualRefreshHz: Int,
    maxSupportedRefreshHz: Int,
    currentTargetKey: String,
    isExternalMonitor: Boolean,
    hasFullscreenWindow: Boolean,
    canControlPhoneTrackpad: Boolean,
    virtualCursorPx: Offset?,
    onPickCustomWallpaper: () -> Unit,
    onOpenConsole: () -> Unit,
    onExitDesktop: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val desktopPkgs by AppPrefs.desktopShortcutsFlow.collectAsState()
    val dockPinnedPkgs by AppPrefs.dockPinnedFlow.collectAsState()
    val dockVisible by AppPrefs.dockVisibleFlow.collectAsState()

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
        hasFullscreenWindow = hasFullscreenWindow,
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
            actualRefreshHz = actualRefreshHz,
            maxSupportedRefreshHz = maxSupportedRefreshHz,
            isExternalMonitor = isExternalMonitor,
            hasFullscreenWindow = hasFullscreenWindow,
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
        currentTargetKey = currentTargetKey,
        onOpenConsole = onOpenConsole,
        onExitDesktop = onExitDesktop,
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
        dockInsetDp = if (!hasFullscreenWindow && dockVisible) effectiveDockInsetDp else 0f,
        modifier = Modifier.zIndex(10_045f),
    )

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
            toggleHostScreenPowerAction(ctx, screenOn)
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
                    handleFeatureGuideAction(ctx, ui, action, url, onOpenConsole)
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
