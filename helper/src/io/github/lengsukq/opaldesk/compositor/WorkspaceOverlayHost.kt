package io.github.lengsukq.opaldesk.compositor

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
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
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.settings.DexSettingsCategory
import io.github.lengsukq.opaldesk.settings.DexSettingsOverlay
import io.github.lengsukq.opaldesk.ui.ConsoleSectionKeys
import io.github.lengsukq.opaldesk.ui.OpalFaqModal
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.PairingConsentModal
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetStore
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetStoreModal
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
    onOpenConsoleSection: ((tabIndex: Int, sectionKey: String) -> Unit)? = null,
    onExitDesktop: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val desktopPkgs by AppPrefs.desktopShortcutsFlow.collectAsState()
    val dockPinnedPkgs by AppPrefs.dockPinnedFlow.collectAsState()
    val dockVisible by AppPrefs.dockVisibleFlow.collectAsState()

    // Transparent outside-click dismiss scrim above live app windows & desktop widgets
    // (below Dock at 10_010f and popovers at 10_020f+) so tapping/clicking anywhere outside
    // the App Drawer or other floating popovers closes them cleanly for both touch and mouse.
    val showPopoverOutsideScrim = DockAppearancePolicy.shouldShowPopoverOutsideDismissScrim(
        showAppDrawer = ui.showAppDrawer,
        showControlCenter = ui.showControlCenter,
        showNotificationCenter = ui.showNotificationCenter,
        hasFullscreenWindow = hasFullscreenWindow,
        showDpiPopover = ui.showDpiPopover,
        showImePopover = ui.showImePopover,
        showShortcutsPopover = ui.showShortcutsPopover,
        hasContextMenu = ui.contextMenuTarget != null ||
            ui.guideIconMenuPos != null ||
            ui.wallpaperMenuPos != null,
    )
    if (showPopoverOutsideScrim) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10_009f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        ui.dismissAllPopovers()
                    }
                },
        )
    }

    // Floating Rounded Glass App Launcher Popover above macOS Dock
    if (ui.showAppDrawer) {
        val drawerBottomPadDp = if (dockVisible && effectiveDockInsetDp > 0f) effectiveDockInsetDp + 8f else 16f
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

    // Unified Liquid Glass Control Center Popover (anchored directly above Dock Setting button)
    val ccBottomPadDp = if (dockVisible && effectiveDockInsetDp > 0f) effectiveDockInsetDp + 10f else 16f
    val canvasWidthDp = if (densityVal > 0f) canvasW / densityVal else 0f
    val ccHorizontalShiftDp = when {
        isCompactWidth || !dockVisible || effectiveDockInsetDp <= 0f -> 0f
        canvasWidthDp >= 900f -> -124f
        else -> -72f
    }
    val ccAnchorModifier = if (hasFullscreenWindow) {
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 48.dp, start = 12.dp, end = 12.dp)
            .zIndex(10_020f)
    } else {
        Modifier
            .align(Alignment.BottomCenter)
            .then(if (ccHorizontalShiftDp != 0f) Modifier.offset(x = ccHorizontalShiftDp.dp) else Modifier)
            .padding(start = 12.dp, end = 12.dp, bottom = ccBottomPadDp.dp)
            .zIndex(10_020f)
    }
    AnimatedVisibility(
        visible = ui.showControlCenter,
        enter = fadeIn(tween(180)) +
            slideInVertically(tween(220, easing = FastOutSlowInEasing)) {
                if (hasFullscreenWindow) -it / 8 else it / 6
            } +
            scaleIn(
                tween(220, easing = FastOutSlowInEasing),
                initialScale = 0.94f,
                transformOrigin = TransformOrigin(0.5f, if (hasFullscreenWindow) 0f else 1f),
            ),
        exit = fadeOut(tween(140)) +
            slideOutVertically(tween(160, easing = FastOutSlowInEasing)) {
                if (hasFullscreenWindow) -it / 10 else it / 8
            } +
            scaleOut(
                tween(160, easing = FastOutSlowInEasing),
                targetScale = 0.95f,
                transformOrigin = TransformOrigin(0.5f, if (hasFullscreenWindow) 0f else 1f),
            ),
        modifier = ccAnchorModifier,
    ) {
        WorkspaceControlCenterHost(
            ui = ui,
            trayInfo = trayInfo,
            maxHeight = maxHeight,
            bottomClearanceDp = ccBottomPadDp,
            displaySummary = displaySummary,
            isExternalMonitor = isExternalMonitor,
            hasFullscreenWindow = hasFullscreenWindow,
            canControlPhoneTrackpad = canControlPhoneTrackpad,
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

    // Desktop Liquid Glass Widget / Plugin Store Modal
    if (ui.showWidgetStore) {
        val widgetInstances by DesktopWidgetStore.instancesFlow.collectAsState()
        val storeMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + effectiveDockInsetDp + 28f).dp)
            .coerceAtLeast(320.dp)
        DesktopWidgetStoreModal(
            instances = widgetInstances,
            maxHeight = storeMaxH,
            onAddWidget = { type -> DesktopWidgetStore.addWidget(ctx, type) },
            onRemoveWidget = { id -> DesktopWidgetStore.removeWidget(ctx, id) },
            onResetDefaults = { DesktopWidgetStore.resetToDefaults(ctx) },
            onEnterEditMode = {
                ui.showWidgetStore = false
                ui.isWidgetEditMode = true
            },
            onDismiss = { ui.showWidgetStore = false },
            modifier = Modifier.zIndex(10_029f),
        )
    }

    // Dedicated Full-Featured DeX Settings App Overlay
    if (ui.showDexSettings) {
        Box(modifier = Modifier.fillMaxSize().zIndex(10_029f)) {
            DexSettingsOverlay(
                bridge = BridgeClient,
                uiState = ui,
                initialCategory = ui.dexSettingsInitialCategory,
                onOpenWidgetStore = {
                    ui.showDexSettings = false
                    ui.showWidgetStore = true
                },
                onToggleWidgetEdit = {
                    ui.showDexSettings = false
                    ui.isWidgetEditMode = !ui.isWidgetEditMode
                },
                onOpenKeyBindings = {
                    ui.showDexSettings = false
                    ui.showShortcutsPopover = true
                },
                onOpenFaq = {
                    ui.showDexSettings = false
                    ui.showFaqModal = true
                },
                onUploadLogs = {
                    ui.showDexSettings = false
                    openConsoleSectionAction(
                        ctx = ctx,
                        isExternalMonitor = isExternalMonitor,
                        onOpenConsoleSection = onOpenConsoleSection,
                        tabIndex = 3,
                        sectionKey = ConsoleSectionKeys.DIAGNOSTICS_LOGS,
                    )
                },
                onDismiss = { ui.showDexSettings = false },
            )
        }
    }

    if (ui.showFaqModal) {
        Box(modifier = Modifier.fillMaxSize().zIndex(10_035f)) {
            OpalFaqModal(onDismiss = { ui.showFaqModal = false })
        }
    }

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
private fun WorkspaceControlCenterHost(
    ui: WorkspaceUiState,
    trayInfo: SystemTrayInfo,
    maxHeight: Dp,
    bottomClearanceDp: Float,
    displaySummary: String,
    isExternalMonitor: Boolean,
    hasFullscreenWindow: Boolean,
    canControlPhoneTrackpad: Boolean,
    onOpenConsole: () -> Unit,
) {
    val ctx = LocalContext.current
    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val remote by BridgeClient.remoteStatusFlow.collectAsState()
    val phoneTrackpadVisible by ExternalTrackpadBridge.phoneTrackpadVisibleFlow.collectAsState()

    val verticalReserveDp = if (hasFullscreenWindow) {
        CompositorGeometry.TOP_BAR_DP + 56f
    } else {
        CompositorGeometry.TOP_BAR_DP + bottomClearanceDp + 18f
    }
    val ccMaxH = (maxHeight - verticalReserveDp.dp).coerceAtLeast(240.dp)
    MacControlCenterPopover(
        tray = trayInfo,
        totalWindows = scene.windows.size,
        displaySummary = displaySummary,
        inputBackendLabel = status.injectBackendLabel,
        isExternalMonitor = isExternalMonitor,
        hostScreenOff = status.hostScreenOff,
        showHostScreenToggle = isExternalMonitor || remote.connected || status.hostScreenOff,
        showPhoneTrackpadSection = canControlPhoneTrackpad,
        phoneTrackpadVisible = phoneTrackpadVisible,
        maxHeight = ccMaxH,
        isWidgetEditMode = ui.isWidgetEditMode,
        onOpenDexSettings = {
            ui.openDexSettings(DexSettingsCategory.APPEARANCE)
        },
        onTogglePhoneTrackpad = {
            ui.showControlCenter = false
            togglePhoneTrackpadAction(ctx, phoneTrackpadVisible, onOpenConsole)
        },
        onToggleHostScreenPower = { screenOn ->
            toggleHostScreenPowerAction(ctx, screenOn)
        },
        onOpenWidgetStore = {
            ui.showControlCenter = false
            ui.showWidgetStore = true
        },
        onToggleWidgetEditMode = {
            ui.showControlCenter = false
            ui.isWidgetEditMode = !ui.isWidgetEditMode
        },
        onOpenConsole = {
            ui.showControlCenter = false
            onOpenConsole()
        },
        onExitDesktop = {
            ui.showControlCenter = false
            ui.showExitSlotModal = true
        },
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
