package io.github.lengsukq.opaldesk.compositor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.util.Base64
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSpotlightController
import io.github.lengsukq.opaldesk.ui.onTapOrContextAction
import io.github.lengsukq.opaldesk.widgets.DesktopGridShortcutItem
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetCallbacks
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetHostLayer
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetStore
import io.github.lengsukq.opaldesk.widgets.WidgetAppShortcut
import io.github.lengsukq.opaldesk.widgets.WidgetMediaSnapshot
import io.github.lengsukq.opaldesk.widgets.WidgetNotificationItem
import io.github.lengsukq.opaldesk.widgets.WidgetWorkstationSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.WorkspaceWindowLayer(
    ui: WorkspaceUiState,
    trayInfo: SystemTrayInfo,
    spotlightController: OpalSpotlightController,
    canvasW: Int,
    canvasH: Int,
    isCompactWidth: Boolean,
    effectiveDockInsetDp: Float,
    shortcutRowsPerCol: Int,
    fullscreenWin: CompositorWindow?,
    isExternalMonitor: Boolean,
    suppressFullscreenExitOverlay: Boolean,
    canControlPhoneTrackpad: Boolean,
    imeActive: Boolean,
    onToggleSoftKeyboard: (() -> Unit)?,
    onOpenConsole: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val remote by BridgeClient.remoteStatusFlow.collectAsState()
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
    val widgetInstances by DesktopWidgetStore.instancesFlow.collectAsState()
    val mediaSnap by io.github.lengsukq.opaldesk.notifications.OpalMediaSessionMonitor.snapshotFlow.collectAsState()
    val audioMgr = remember(ctx) { ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var isMuted by remember(audioMgr) {
        mutableStateOf(runCatching { audioMgr?.isStreamMute(AudioManager.STREAM_MUSIC) == true }.getOrDefault(false))
    }
    LaunchedEffect(Unit) {
        DesktopWidgetStore.hydrate(ctx)
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

    // Unified Desktop Liquid Glass Widget & App Shortcut Grid Layer (above wallpaper, below live app windows)
    if (fullscreenWin == null) {
        val desktopGridShortcuts = remember(status.connected, showDesktopGuideIcon, ui.allApps, desktopPkgs) {
            if (!status.connected || (!showDesktopGuideIcon && ui.allApps.isEmpty())) {
                emptyList()
            } else {
                val byKey = ui.allApps.associateBy { it.appKey }
                buildList<DesktopGridShortcutItem>(16) {
                    if (showDesktopGuideIcon) add(DesktopGridShortcutItem.BuiltinGuide)
                    val maxApps = if (showDesktopGuideIcon) 14 else 15
                    desktopPkgs.mapNotNull { byKey[it] }.take(maxApps).forEach { entry ->
                        add(DesktopGridShortcutItem.App(entry))
                    }
                }
            }
        }
        val mediaArtwork = remember(mediaSnap.artworkBase64) {
            if (mediaSnap.artworkBase64.isBlank()) {
                null
            } else {
                runCatching {
                    val bytes = Base64.decode(mediaSnap.artworkBase64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        }
        val widgetMediaSnapshot = remember(mediaSnap, mediaArtwork) {
            WidgetMediaSnapshot(
                title = mediaSnap.title,
                artist = mediaSnap.artist,
                appName = mediaSnap.appLabel,
                isPlaying = mediaSnap.playing,
                hasSession = mediaSnap.active,
                artworkBitmap = mediaArtwork,
            )
        }
        val widgetWorkstationSnapshot = remember(trayInfo, status, remote, scene.visibleWindows.size, isMuted, isExternalMonitor) {
            val connLabel = when {
                remote.connected -> remote.peerName.ifBlank { remote.channelLabel }
                isExternalMonitor -> "External"
                status.connected -> "Connected"
                else -> "Offline"
            }
            WidgetWorkstationSnapshot(
                batteryPercent = trayInfo.batteryPct,
                isCharging = trayInfo.charging,
                isConnected = status.connected,
                connectionLabel = connLabel,
                activeWindowCount = scene.visibleWindows.size,
                isPhoneScreenOff = status.hostScreenOff,
                isMuted = isMuted,
            )
        }
        val widgetNotifications = remember(notifications) {
            notifications.take(12).map { item ->
                WidgetNotificationItem(
                    key = item.key,
                    appName = item.appLabel.ifBlank { item.packageName },
                    title = item.title,
                    text = item.text,
                    timestampMs = item.postTimeMs,
                )
            }
        }
        val widgetAppShortcuts = remember(ui.allApps) {
            ui.allApps.take(24).map { entry ->
                val bmp = runCatching { AppCatalogRepository.getCachedIcon(entry.pkg, entry.userId)?.asAndroidBitmap() }.getOrNull()
                WidgetAppShortcut(packageName = entry.pkg, label = entry.label, iconBitmap = bmp)
            }
        }
        val widgetCallbacks = remember(ctx, scope, ui, audioMgr, status.hostScreenOff, phoneTrackpadVisible, canvasW, canvasH, effectiveDockInsetDp) {
            DesktopWidgetCallbacks(
                onMediaPrev = {
                    if (!io.github.lengsukq.opaldesk.notifications.OpalMediaSessionMonitor.handleRemoteAction("previous", 0L)) {
                        ControlCenterActions.dispatchMediaKey(audioMgr, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    }
                },
                onMediaPlayPause = {
                    if (!io.github.lengsukq.opaldesk.notifications.OpalMediaSessionMonitor.handleRemoteAction("toggle", 0L)) {
                        ControlCenterActions.dispatchMediaKey(audioMgr, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                },
                onMediaNext = {
                    if (!io.github.lengsukq.opaldesk.notifications.OpalMediaSessionMonitor.handleRemoteAction("next", 0L)) {
                        ControlCenterActions.dispatchMediaKey(audioMgr, KeyEvent.KEYCODE_MEDIA_NEXT)
                    }
                },
                onTogglePhoneScreenOff = { toggleHostScreenPowerAction(ctx, screenOn = status.hostScreenOff) },
                onOpenTrackpad = { togglePhoneTrackpadAction(ctx, phoneTrackpadVisible, onOpenConsole) },
                onToggleMute = {
                    runCatching {
                        audioMgr?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
                        isMuted = audioMgr?.isStreamMute(AudioManager.STREAM_MUSIC) == true
                    }
                },
                onOpenControlCenter = {
                    ui.dismissAllPopovers()
                    ui.controlCenterInitialTab = 0
                    ui.showControlCenter = true
                },
                onCopyText = { text ->
                    runCatching {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk", text))
                        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
                    }
                },
                onReadClipboard = {
                    runCatching {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    }.getOrNull()
                },
                onLaunchApp = { pkg ->
                    val entry = ui.allApps.firstOrNull { it.pkg == pkg }
                    AppPrefs.recordAppLaunch(ctx, entry?.appKey ?: pkg)
                    scope.launch(Dispatchers.IO) {
                        BridgeClient.openWindow(pkg, entry?.activityClass.orEmpty(), BridgeProtocol.Profile.AUTO, userId = entry?.userId ?: 0)
                    }
                },
                onSplitLaunchApps = { pkg1, pkg2 ->
                    val e1 = ui.allApps.firstOrNull { it.pkg == pkg1 }
                    val e2 = ui.allApps.firstOrNull { it.pkg == pkg2 }
                    scope.launch(Dispatchers.IO) {
                        BridgeClient.openWindow(pkg1, e1?.activityClass.orEmpty(), BridgeProtocol.Profile.AUTO, userId = e1?.userId ?: 0)
                        delay(180L)
                        BridgeClient.openWindow(pkg2, e2?.activityClass.orEmpty(), BridgeProtocol.Profile.AUTO, userId = e2?.userId ?: 0)
                    }
                },
                onUpdateWidgetConfig = { id, json -> DesktopWidgetStore.updateWidgetConfig(ctx, id, json) },
                onOpenSpotlight = {
                    ui.dismissAllPopovers()
                    spotlightController.resetSelection()
                    ui.showSpotlight = true
                },
                onOpenWidgetStore = {
                    ui.dismissAllPopovers()
                    ui.showWidgetStore = true
                },
                onToggleWidgetEditMode = {
                    ui.dismissAllPopovers()
                    ui.isWidgetEditMode = !ui.isWidgetEditMode
                },
                onOpenNotifications = {
                    ui.dismissAllPopovers()
                    ui.showNotificationCenter = true
                    io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.refreshAccessState(ctx)
                    io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.requestSync()
                },
                onOpenSettings = onOpenConsole,
            )
        }
        DesktopWidgetHostLayer(
            instances = widgetInstances,
            isEditMode = ui.isWidgetEditMode,
            mediaSnapshot = widgetMediaSnapshot,
            workstationSnapshot = widgetWorkstationSnapshot,
            notifications = widgetNotifications,
            appShortcuts = widgetAppShortcuts,
            callbacks = widgetCallbacks,
            desktopShortcuts = desktopGridShortcuts,
            onOpenGuide = {
                ui.dismissAllPopovers()
                ui.showFeatureGuide = true
            },
            onContextGuide = { rootPos ->
                ui.dismissAllPopovers()
                ui.guideIconMenuPos = rootPos
            },
            onLaunchDesktopApp = { entry ->
                ui.dismissAllPopovers()
                AppPrefs.recordAppLaunch(ctx, entry.appKey)
                scope.launch(Dispatchers.IO) {
                    BridgeClient.openWindow(
                        entry.pkg, entry.activityClass, BridgeProtocol.Profile.AUTO, userId = entry.userId,
                    )
                }
            },
            onContextDesktopApp = { appKey, rootPos ->
                ui.dismissAllPopovers()
                val running = scene.windows.firstOrNull { it.appKey == appKey }
                ui.contextMenuTarget = Triple(appKey, running, rootPos)
            },
            onEnterEditMode = { ui.isWidgetEditMode = true },
            onExitEditMode = { ui.isWidgetEditMode = false },
            onOpenWidgetStore = {
                ui.dismissAllPopovers()
                ui.showWidgetStore = true
            },
            onMoveWidget = { id, col, row -> DesktopWidgetStore.moveWidget(ctx, id, col, row) },
            onResizeWidget = { id, colSpan, rowSpan -> DesktopWidgetStore.resizeWidget(ctx, id, colSpan, rowSpan) },
            onRemoveWidget = { id -> DesktopWidgetStore.removeWidget(ctx, id) },
            onResetDefaults = { DesktopWidgetStore.resetToDefaults(ctx) },
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = (CompositorGeometry.TOP_BAR_DP + 10f).dp,
                    bottom = (effectiveDockInsetDp + 10f).dp,
                    start = 10.dp,
                    end = 10.dp,
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

    if (fullscreenWin != null && !suppressFullscreenExitOverlay) {
        // Unobtrusive top-center pull-down handle to exit fullscreen or return to desktop
        // (suppressed on remote cast displays where the outer receiver already owns the single top-center pill).
        FullscreenExitOverlay(
            windowTitle = fullscreenWin.title,
            imeActive = imeActive,
            onToggleSoftKeyboard = onToggleSoftKeyboard,
            onCloseWindow = {
                ui.dismissAllPopovers()
                BridgeClient.sendWindowActionAsync(
                    fullscreenWin.windowId,
                    BridgeProtocol.WinAction.CLOSE,
                )
            },
            onMinimizeWindow = {
                ui.dismissAllPopovers()
                BridgeClient.sendWindowActionAsync(
                    fullscreenWin.windowId,
                    BridgeProtocol.WinAction.MINIMIZE,
                )
            },
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
                .fillMaxWidth()
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
            showControlCenter = ui.showControlCenter,
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
            onToggleControlCenter = {
                val next = !ui.showControlCenter
                ui.dismissAllPopovers()
                ui.controlCenterInitialTab = 0
                ui.showControlCenter = next
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
                launchOrToggleDockAppAction(ctx, scope, ui, scene, pkg, cls, userId)
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
}

@Composable
internal fun WorkspaceContextMenusHost(
    ui: WorkspaceUiState,
    spotlightController: OpalSpotlightController,
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
        val baseMenuDp = ((if (customWallpaperPath.isNotBlank()) 472f else 436f) +
            152f +
            (if (canControlPhoneTrackpad) 38f else 0f) +
            (if (!showDesktopGuideIcon) 38f else 0f)).coerceAtMost(560f)
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
            isWidgetEditMode = ui.isWidgetEditMode,
            onOpenWidgetStore = { ui.showWidgetStore = true },
            onToggleWidgetEditMode = { ui.isWidgetEditMode = !ui.isWidgetEditMode },
            onOpenSpotlight = {
                ui.dismissAllPopovers()
                spotlightController.resetSelection()
                ui.showSpotlight = true
            },
            onOpenControlCenter = {
                ui.dismissAllPopovers()
                ui.controlCenterInitialTab = 0
                ui.showControlCenter = true
            },
            onOpenNotifications = {
                ui.dismissAllPopovers()
                ui.showNotificationCenter = true
                io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.refreshAccessState(ctx)
                io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.requestSync()
            },
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
            onExitDesktop = {
                ui.dismissAllPopovers()
                ui.showExitSlotModal = true
            },
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
