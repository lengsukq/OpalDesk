package io.github.lengsukq.opaldesk.compositor

import android.app.ActivityOptions
import android.content.Context
import android.os.SystemClock
import android.view.Display
import android.widget.Toast
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.ExternalTrackpadLauncher
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.AppProfileKey
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.search.DexCommandIds
import io.github.lengsukq.opaldesk.search.SpotlightCategory
import io.github.lengsukq.opaldesk.search.SpotlightItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun togglePhoneTrackpadAction(
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

internal fun openConsoleSectionAction(
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

internal fun openNotificationAppAction(
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

internal fun minimizeAllVisibleWindows(scene: CompositorScene) {
    scene.visibleWindows.forEach { win ->
        LiveWindowPreviewRegistry.captureLivePreview(win.windowId)
        BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.MINIMIZE)
    }
}

internal fun toggleHostScreenPowerAction(ctx: Context, screenOn: Boolean) {
    BridgeClient.setHostScreenPowerAsync(screenOn) { ok ->
        val msg = when {
            !ok -> ctx.getString(R.string.toast_host_screen_unsupported)
            screenOn -> ctx.getString(R.string.toast_host_screen_on)
            else -> ctx.getString(R.string.toast_host_screen_off_hint)
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}

internal fun launchOrToggleDockAppAction(
    ctx: Context,
    scope: CoroutineScope,
    ui: WorkspaceUiState,
    scene: CompositorScene,
    pkg: String,
    cls: String,
    userId: Int,
) {
    val appKey = AppProfileKey.format(pkg, userId)
    val nowMs = SystemClock.uptimeMillis()
    val prevTapMs = ui.dockLastTapMsByPkg[appKey] ?: 0L
    if (nowMs - prevTapMs < 280L) return
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
}

internal fun handleFeatureGuideAction(
    ctx: Context,
    ui: WorkspaceUiState,
    action: String,
    url: String,
    onOpenConsole: () -> Unit,
) {
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
}

internal fun handleWorkspaceSpotlightSelection(
    ctx: Context,
    scope: CoroutineScope,
    ui: WorkspaceUiState,
    status: DaemonStatus,
    scene: CompositorScene,
    remote: RemoteStreamStatus,
    curExtDisplay: BridgeService.ExternalDisplayInfo?,
    appsByKey: Map<String, InstalledAppEntry>,
    chosen: SpotlightItem,
    canvasW: Int,
    canvasH: Int,
    densityVal: Float,
    effectiveDockInsetDp: Float,
    isExternalMonitor: Boolean,
    isCurrentlyDark: Boolean,
    dockVisible: Boolean,
    phoneTrackpadVisible: Boolean,
    onPickCustomWallpaper: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenConsoleSection: ((tabIndex: Int, sectionKey: String) -> Unit)?,
) {
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
            val (pkg, userId) = AppProfileKey.parse(appKey)
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
                DexCommandIds.TOGGLE_BLACKOUT -> toggleHostScreenPowerAction(ctx, status.hostScreenOff)
                DexCommandIds.TOGGLE_TRACKPAD -> togglePhoneTrackpadAction(ctx, phoneTrackpadVisible, onOpenConsole)
                DexCommandIds.OPEN_DPI -> ui.showDpiPopover = true
                DexCommandIds.OPEN_RESOLUTION -> {
                    if (isExternalMonitor || curExtDisplay != null || remote.connected) {
                        ui.showResolutionModal = true
                    } else {
                        openConsoleSectionAction(
                            ctx,
                            isExternalMonitor,
                            onOpenConsoleSection,
                            1,
                            io.github.lengsukq.opaldesk.ui.ConsoleSectionKeys.CUSTOM_RESOLUTION,
                        )
                    }
                }
                DexCommandIds.OPEN_IME -> ui.showImePopover = true
                DexCommandIds.OPEN_SHORTCUTS -> ui.showShortcutsPopover = true
                DexCommandIds.PICK_WALLPAPER -> onPickCustomWallpaper()
                DexCommandIds.TOGGLE_THEME -> {
                    val next = if (isCurrentlyDark) AppPrefs.THEME_LIGHT else AppPrefs.THEME_DARK
                    AppPrefs.setThemeMode(ctx, next)
                }
                DexCommandIds.TOGGLE_DOCK -> AppPrefs.setDockVisible(ctx, !dockVisible)
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
}
