package io.github.lengsukq.opaldesk.compositor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.BridgeClient
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
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalAmbientBackdrop
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassCard
import io.github.lengsukq.opaldesk.ui.OpalGlyphButton
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.PairingConsentModal
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import io.github.lengsukq.opaldesk.ui.onTapOrContextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Single-source-of-truth multi-window tiling dispatcher shared by Top Menu Bar,
 * Control Center, Home Console Bento Grid, and physical keyboard shortcuts.
 */
fun applyWindowTilingLayout(
    layout: WindowTilingLayout,
    scene: CompositorScene,
    canvasW: Int,
    canvasH: Int,
    density: Float,
    dockInsetDp: Float = CompositorGeometry.DOCK_INSET_DP,
) {
    val candidates = scene.visibleWindows.sortedByDescending { it.zOrder }
        .ifEmpty { scene.windows.sortedByDescending { it.zOrder } }
    if (candidates.isEmpty()) return
    val frames = CompositorGeometry.computeTilingFrames(
        layout = layout,
        windowCount = candidates.size,
        canvasW = canvasW,
        canvasH = canvasH,
        density = density,
        dockInsetDp = dockInsetDp,
    )
    frames.forEachIndexed { idx, rect ->
        val win = candidates[idx]
        if (win.isMinimized) {
            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.FOCUS)
        }
        BridgeClient.updateWindowFrameAsync(
            windowId = win.windowId,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            presentation = BridgeProtocol.Presentation.FLOATING,
        )
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
    virtualCursorPx: Offset? = null,
    imeActive: Boolean = false,
    onToggleSoftKeyboard: (() -> Unit)? = null,
    onPickCustomWallpaper: () -> Unit = {},
    onOpenConsole: () -> Unit,
    onExitDesktop: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val remote by BridgeClient.remoteStatusFlow.collectAsState()
    val pendingPairing by BridgeClient.pendingPairingFlow.collectAsState()
    val extSession by ExternalTrackpadBridge.sessionFlow.collectAsState()
    val phoneTrackpadVisible by ExternalTrackpadBridge.phoneTrackpadVisibleFlow.collectAsState()
    val autoShowPhoneTrackpad by AppPrefs.autoShowPhoneTrackpadFlow.collectAsState()

    val canControlPhoneTrackpad = ExternalTrackpadPolicy.canControlPhoneTrackpad(
        isExternalMonitor = isExternalMonitor,
        remote = remote,
        session = extSession,
    )

    fun togglePhoneTrackpadFromExternal() {
        if (phoneTrackpadVisible) {
            val closed = ExternalTrackpadBridge.requestClosePhoneTrackpad()
            if (!closed) {
                onOpenConsole()
            }
            Toast.makeText(ctx, "已关闭手机触控板", Toast.LENGTH_SHORT).show()
        } else {
            val launched = ExternalTrackpadLauncher.openPhoneTrackpad(ctx)
            Toast.makeText(
                ctx,
                if (launched) "已在手机屏幕显示触控板" else "当前未检测到可用的外接屏触控目标",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    var allApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    val desktopPkgs by AppPrefs.desktopShortcutsFlow.collectAsState()
    val dockPinnedPkgs by AppPrefs.dockPinnedFlow.collectAsState()
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val mouseTargetMode by AppPrefs.mouseTargetFlow.collectAsState()
    val externalUiScale by AppPrefs.externalUiScaleFlow.collectAsState()
    val dockVisible by AppPrefs.dockVisibleFlow.collectAsState()
    val dockOpacity by AppPrefs.dockOpacityFlow.collectAsState()
    val dockScale by AppPrefs.dockScaleFlow.collectAsState()

    val profileState by io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.stateFlow.collectAsState()
    var showAppDrawer by remember { mutableStateOf(false) }
    var showExposeOverview by remember { mutableStateOf(false) }
    var showControlCenter by remember { mutableStateOf(false) }
    var controlCenterInitialTab by remember { mutableStateOf(0) }
    var showDpiPopover by remember { mutableStateOf(false) }
    var showNotificationCenter by remember { mutableStateOf(false) }
    val notifications by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.notificationsFlow.collectAsState()
    val liveNotificationBanner by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.bannerFlow.collectAsState()
    val notifAccessGranted by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.accessGrantedFlow.collectAsState()
    val doNotDisturb by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.doNotDisturbFlow.collectAsState()
    val pinnedWindowIds by io.github.lengsukq.opaldesk.protocol.OpalWindowPinStore.pinnedIdsFlow.collectAsState()
    var showExitSlotModal by remember { mutableStateOf(false) }
    var savingOnExit by remember { mutableStateOf(false) }
    var editingAppDpiTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var autoRestoredOnce by remember { mutableStateOf(false) }
    var contextMenuTarget by remember { mutableStateOf<Triple<String, CompositorWindow?, Offset>?>(null) }
    var wallpaperMenuPos by remember { mutableStateOf<Offset?>(null) }
    var snapPreviewRect by remember { mutableStateOf<WindowRect?>(null) }
    var trayInfo by remember { mutableStateOf(SystemTrayInfo()) }
    val dockLastTapMsByPkg = remember { mutableMapOf<String, Long>() }

    LaunchedEffect(Unit) {
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.hydrate(ctx)
        BridgeClient.bindAppContext(ctx)
    }

    LaunchedEffect(scene.windows) {
        io.github.lengsukq.opaldesk.protocol.OpalWindowPinStore.prune(scene.windows.map { it.windowId }.toSet())
    }

    fun dismissAllPopovers() {
        showAppDrawer = false
        showExposeOverview = false
        showControlCenter = false
        showDpiPopover = false
        showNotificationCenter = false
        contextMenuTarget = null
        wallpaperMenuPos = null
    }

    fun openNotificationApp(pkg: String) {
        dismissAllPopovers()
        val existing = scene.windows.firstOrNull { it.pkg == pkg }
        if (existing != null) {
            BridgeClient.sendWindowActionAsync(existing.windowId, BridgeProtocol.WinAction.FOCUS)
            return
        }
        val entry = allApps.firstOrNull { it.pkg == pkg }
        val cls = entry?.activityClass.orEmpty()
        AppPrefs.recordAppLaunch(ctx, pkg)
        scope.launch(Dispatchers.IO) {
            BridgeClient.openWindow(pkg, cls, BridgeProtocol.Profile.AUTO)
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("M/d E", Locale.getDefault()) }
    fun refreshTrayStatusNow() {
        val now = Date()
        trayInfo = querySystemTrayStatus(ctx, timeFmt.format(now), dateFmt.format(now))
    }

    LaunchedEffect(Unit) {
        allApps = AppCatalogRepository.loadLaunchableApps(ctx)
        scope.launch(Dispatchers.IO) { AppCatalogRepository.prewarmIcons(ctx, allApps) }
    }

    // Refresh clock/battery/Wi-Fi immediately without waiting for app catalog loading.
    LaunchedEffect(ctx) {
        while (true) {
            refreshTrayStatusNow()
            delay(15_000L)
        }
    }

    // React instantaneously to Wi-Fi radio toggle, network loss/rebind, and battery updates.
    DisposableEffect(ctx) {
        val appCtx = ctx.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            private fun scheduleRefresh(recheckDelayMs: Long = 0L) {
                scope.launch {
                    refreshTrayStatusNow()
                    if (recheckDelayMs > 0L) {
                        delay(recheckDelayMs)
                        refreshTrayStatusNow()
                    }
                }
            }

            override fun onAvailable(network: Network) = scheduleRefresh()
            override fun onLost(network: Network) = scheduleRefresh(recheckDelayMs = 250L)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                scheduleRefresh()
        }
        val netRegistered = runCatching {
            cm?.registerDefaultNetworkCallback(netCallback)
            cm != null
        }.getOrDefault(false)

        val trayReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scope.launch {
                    refreshTrayStatusNow()
                    if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                        delay(250L)
                        refreshTrayStatusNow()
                    }
                }
            }
        }
        val trayFilter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val rxRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(trayReceiver, trayFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appCtx.registerReceiver(trayReceiver, trayFilter)
            }
            true
        }.getOrDefault(false)

        onDispose {
            if (netRegistered) {
                runCatching { cm?.unregisterNetworkCallback(netCallback) }
            }
            if (rxRegistered) {
                runCatching { appCtx.unregisterReceiver(trayReceiver) }
            }
        }
    }

    // Keep hostScreenOff status indicator synced promptly if the user double-taps to wake the phone
    LaunchedEffect(status.connected, status.hostScreenOff) {
        if (status.connected && status.hostScreenOff) {
            while (true) {
                delay(2_500L)
                withContext(Dispatchers.IO) {
                    BridgeClient.refreshStatus()
                }
            }
        }
    }

    val hasFullscreenWindow = scene.visibleWindows.any { it.isFullscreen }
    OpalAmbientBackdrop(
        modifier = Modifier.fillMaxSize(),
        wallpaperPreset = wallpaperPreset,
        suspendBackdrop = hasFullscreenWindow,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
            val displaySummary = when {
                remote.connected -> "${remote.channelLabel} · ${remote.peerName.ifBlank { "投屏端" }} · ${remote.width}×${remote.height} @ ${remote.fpsX100 / 100}Hz"
                externalMonitorSummary.isNotBlank() -> externalMonitorSummary
                else -> "桌面画布 · ${canvasW}×${canvasH}"
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
                            Toast.makeText(ctx, "已自动载入槽位 ${matched.id + 1}「${matched.name}」", Toast.LENGTH_SHORT).show()
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
                    if (!autoRestoredOnce && activeSlot.autoRestoreLayout && scene.windows.isEmpty() && activeSlot.savedLayout.isNotEmpty()) {
                        autoRestoredOnce = true
                        BridgeClient.restoreSavedLayoutAsync(activeSlot, canvasW, canvasH)
                    }
                }
            }

            fun applyTiling(layout: WindowTilingLayout) {
                dismissAllPopovers()
                applyWindowTilingLayout(layout, scene, canvasW, canvasH, densityVal, effectiveDockInsetDp)
            }

            fun minimizeAllWindows() {
                scene.visibleWindows.forEach { win ->
                    LiveWindowPreviewRegistry.captureLivePreview(win.windowId)
                    BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.MINIMIZE)
                }
            }

            fun openSystemSettingsInDex(action: String) {
                dismissAllPopovers()
                fun openFallbackDirect() {
                    try {
                        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Throwable) {
                        try {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (!status.connected) {
                    openFallbackDirect()
                    return
                }
                val pm = ctx.packageManager
                val resolved = listOf(action, android.provider.Settings.ACTION_SETTINGS)
                    .firstNotNullOfOrNull { candidateAction ->
                        runCatching {
                            val ri = pm.resolveActivity(
                                Intent(candidateAction),
                                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                            )
                            val ai = ri?.activityInfo
                            if (ai != null && !ai.packageName.isNullOrBlank() && !ai.name.isNullOrBlank()) {
                                ai.packageName to ai.name
                            } else {
                                null
                            }
                        }.getOrNull()
                    }
                if (resolved == null) {
                    openFallbackDirect()
                    return
                }
                val (pkg, cls) = resolved
                scope.launch(Dispatchers.IO) {
                    val winId = BridgeClient.openWindow(pkg, cls, BridgeProtocol.Profile.AUTO)
                    if (winId <= 0) {
                        withContext(Dispatchers.Main) { openFallbackDirect() }
                    }
                }
            }

            // Desktop wallpaper: single tap dismisses popovers; right-click or touchscreen long-press opens desktop/wallpaper menu
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onTapOrContextAction(
                        showAppDrawer, showExposeOverview, showControlCenter, showDpiPopover,
                        contextMenuTarget, wallpaperMenuPos,
                        onPrimaryTap = { dismissAllPopovers() },
                        onContextAction = { pos ->
                            dismissAllPopovers()
                            wallpaperMenuPos = pos
                        },
                    ),
            )

            // Samsung DeX-style Left-Column Desktop App Shortcuts (hidden during true app fullscreen)
            if (status.connected && allApps.isNotEmpty() && fullscreenWin == null) {
                DesktopShortcutColumn(
                    allApps = allApps,
                    desktopPkgs = desktopPkgs,
                    rowsPerColumn = shortcutRowsPerCol,
                    onLaunch = { entry ->
                        dismissAllPopovers()
                        AppPrefs.recordAppLaunch(ctx, entry.pkg)
                        scope.launch(Dispatchers.IO) {
                            BridgeClient.openWindow(entry.pkg, entry.activityClass, BridgeProtocol.Profile.AUTO)
                        }
                    },
                    onContextApp = { pkg, rootPos ->
                        dismissAllPopovers()
                        val running = scene.windows.firstOrNull { it.pkg == pkg }
                        contextMenuTarget = Triple(pkg, running, rootPos)
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
            snapPreviewRect?.let { snapRect ->
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
                        onSnapPreviewChanged = { snapPreviewRect = it },
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
                    isCompactWidth = isCompactWidth,
                    showControlCenter = showControlCenter,
                    showDpiPopover = showDpiPopover,
                    notificationCount = notifications.size,
                    showNotificationCenter = showNotificationCenter,
                    doNotDisturb = doNotDisturb,
                    showPhoneTrackpadButton = canControlPhoneTrackpad,
                    phoneTrackpadVisible = phoneTrackpadVisible,
                    showDockFallbackActions = !dockVisible,
                    showAppDrawer = showAppDrawer,
                    showExpose = showExposeOverview,
                    onToggleAppDrawer = {
                        val next = !showAppDrawer
                        dismissAllPopovers()
                        showAppDrawer = next
                    },
                    onToggleExpose = {
                        val next = !showExposeOverview
                        dismissAllPopovers()
                        showExposeOverview = next
                    },
                    onTogglePhoneTrackpad = {
                        dismissAllPopovers()
                        togglePhoneTrackpadFromExternal()
                    },
                    onToggleDpiPopover = {
                        val next = !showDpiPopover
                        dismissAllPopovers()
                        showDpiPopover = next
                    },
                    onToggleNotificationCenter = {
                        val next = !showNotificationCenter
                        dismissAllPopovers()
                        showNotificationCenter = next
                        if (next) {
                            io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.refreshAccessState(ctx)
                            io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.requestSync()
                        }
                    },
                    onToggleFullscreen = {
                        val focused = scene.focusedWindow ?: return@MacDesktopMenuBar
                        dismissAllPopovers()
                        BridgeClient.sendWindowActionAsync(
                            focused.windowId,
                            BridgeProtocol.WinAction.TOGGLE_FULLSCREEN,
                        )
                    },
                    onToggleControlCenter = {
                        val next = !showControlCenter
                        dismissAllPopovers()
                        controlCenterInitialTab = 0
                        showControlCenter = next
                    },
                    onOpenConsole = onOpenConsole,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(10_000f),
                )
            } else {
                // Unobtrusive top-center pull-down handle to exit fullscreen or return to desktop
                FullscreenExitOverlay(
                    windowTitle = fullscreenWin.title,
                    onExitFullscreen = {
                        dismissAllPopovers()
                        BridgeClient.sendWindowActionAsync(
                            fullscreenWin.windowId,
                            BridgeProtocol.WinAction.TOGGLE_FULLSCREEN,
                        )
                    },
                    onReturnToDesktop = {
                        dismissAllPopovers()
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
                        val next = !showControlCenter
                        dismissAllPopovers()
                        controlCenterInitialTab = 0
                        showControlCenter = next
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 12.dp)
                        .zIndex(10_040f),
                )
            }

            // Bottom Centered Floating Liquid Frosted Glass Dock (hidden during true app fullscreen or when disabled)
            if (fullscreenWin == null && dockVisible) {
                DexMacTaskbar(
                    allApps = allApps,
                    dockPinnedPkgs = dockPinnedPkgs,
                    windows = scene.windows,
                    showAppDrawer = showAppDrawer,
                    showExpose = showExposeOverview,
                    isCompactWidth = isCompactWidth,
                    dockOpacity = dockOpacity,
                    dockScale = dockScale,
                    imeActive = imeActive,
                    onToggleSoftKeyboard = onToggleSoftKeyboard,
                    onToggleAppDrawer = {
                        val next = !showAppDrawer
                        dismissAllPopovers()
                        showAppDrawer = next
                    },
                    onOpenSearch = {
                        dismissAllPopovers()
                        showAppDrawer = true
                    },
                    onToggleExpose = {
                        val next = !showExposeOverview
                        dismissAllPopovers()
                        showExposeOverview = next
                    },
                    onSendBackToFocused = {
                        val focused = scene.focusedWindow
                        if (focused != null && !focused.isMinimized) {
                            BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.SEND_BACK)
                        }
                    },
                    onShowDesktopToggle = {
                        dismissAllPopovers()
                        if (scene.visibleWindows.isNotEmpty()) {
                            minimizeAllWindows()
                        } else {
                            val lastMin = scene.windows.maxByOrNull { it.zOrder }
                            if (lastMin != null) {
                                BridgeClient.sendWindowActionAsync(lastMin.windowId, BridgeProtocol.WinAction.FOCUS)
                            }
                        }
                    },
                    onLaunchOrToggleApp = { pkg, cls ->
                        val nowMs = android.os.SystemClock.uptimeMillis()
                        val prevTapMs = dockLastTapMsByPkg[pkg] ?: 0L
                        if (nowMs - prevTapMs < 280L) return@DexMacTaskbar
                        dockLastTapMsByPkg[pkg] = nowMs
                        dismissAllPopovers()
                        val existing = scene.windows.firstOrNull { it.pkg == pkg }
                        if (existing != null && existing.focused && !existing.isMinimized) {
                            LiveWindowPreviewRegistry.captureLivePreview(existing.windowId)
                            BridgeClient.sendWindowActionAsync(existing.windowId, BridgeProtocol.WinAction.MINIMIZE)
                        } else if (existing != null) {
                            BridgeClient.sendWindowActionAsync(existing.windowId, BridgeProtocol.WinAction.FOCUS)
                        } else {
                            AppPrefs.recordAppLaunch(ctx, pkg)
                            scope.launch(Dispatchers.IO) {
                                BridgeClient.openWindow(pkg, cls, BridgeProtocol.Profile.AUTO)
                            }
                        }
                    },
                    onContextApp = { pkg, win, rootPos ->
                        dismissAllPopovers()
                        contextMenuTarget = Triple(pkg, win, rootPos)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(10_010f),
                )
            }

            // Floating Rounded Glass App Launcher Popover above macOS Dock
            if (showAppDrawer) {
                val drawerBottomPadDp = if (dockVisible) effectiveDockInsetDp + 8f else 16f
                val drawerMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + drawerBottomPadDp + 28f).dp)
                    .coerceIn(220.dp, 480.dp)
                DexAppDrawerPopover(
                    apps = allApps,
                    desktopPkgs = desktopPkgs,
                    dockPinnedPkgs = dockPinnedPkgs,
                    maxHeight = drawerMaxH,
                    onLaunch = { entry, profileCode ->
                        showAppDrawer = false
                        AppPrefs.recordAppLaunch(ctx, entry.pkg)
                        scope.launch(Dispatchers.IO) {
                            BridgeClient.openWindow(entry.pkg, entry.activityClass, profileCode)
                        }
                    },
                    onContextApp = { entry, rootPos ->
                        val running = scene.windows.firstOrNull { it.pkg == entry.pkg }
                        contextMenuTarget = Triple(entry.pkg, running, rootPos)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = drawerBottomPadDp.dp)
                        .zIndex(10_020f),
                )
            }

            // macOS-style Notification Center Dropdown Popover
            if (showNotificationCenter && fullscreenWin == null) {
                val ncMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + 32f).dp).coerceAtLeast(240.dp)
                DesktopNotificationCenterPopover(
                    notifications = notifications,
                    accessGranted = notifAccessGranted,
                    doNotDisturb = doNotDisturb,
                    maxHeight = ncMaxH,
                    onOpenApp = ::openNotificationApp,
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
                        showNotificationCenter = false
                        io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.openNotificationAccessSettings(ctx)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = (CompositorGeometry.TOP_BAR_DP + 8f).dp, end = 12.dp)
                        .zIndex(10_028f),
                )
            }

            // Live Heads-Up Glass Notification Banner (suppressed during true app fullscreen)
            val activeBanner = liveNotificationBanner
            if (activeBanner != null && !doNotDisturb && !showNotificationCenter && fullscreenWin == null) {
                DesktopNotificationBanner(
                    item = activeBanner,
                    onOpenApp = ::openNotificationApp,
                    onDismiss = { io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.dismissBanner(activeBanner.key) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = (CompositorGeometry.TOP_BAR_DP + 10f).dp, end = 12.dp)
                        .zIndex(10_032f),
                )
            }

            // Unified Top-Right macOS Control Center Popover
            if (showControlCenter) {
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
                    windowDpiScalePct = status.windowDpiScalePct,
                    externalUiScale = externalUiScale,
                    themeMode = themeMode,
                    wallpaperPreset = wallpaperPreset,
                    mouseTargetMode = mouseTargetMode,
                    isExternalMonitor = isExternalMonitor,
                    hostScreenOff = status.hostScreenOff,
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
                    maxHeight = ccMaxH,
                    initialTab = controlCenterInitialTab,
                    onToggleDockVisible = { enabled ->
                        AppPrefs.setDockVisible(ctx, enabled)
                    },
                    onChangeDockOpacity = { opacity ->
                        AppPrefs.setDockOpacity(ctx, opacity)
                    },
                    onChangeDockScale = { scale ->
                        AppPrefs.setDockScale(ctx, scale)
                    },
                    onResetDockStyle = {
                        AppPrefs.resetDockAppearance(ctx)
                        Toast.makeText(ctx, "已恢复默认 Dock 栏外观", Toast.LENGTH_SHORT).show()
                    },
                    onOpenSystemSettings = ::openSystemSettingsInDex,
                    onTogglePhoneTrackpad = {
                        showControlCenter = false
                        togglePhoneTrackpadFromExternal()
                    },
                    onToggleAutoPhoneTrackpad = { enabled ->
                        AppPrefs.setAutoShowPhoneTrackpadEnabled(ctx, enabled)
                        Toast.makeText(
                            ctx,
                            if (enabled) "接入外接屏时将自动在手机显示触控板" else "已关闭自动显示，仍可随时在此手动打开",
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
                    onSelectUiScale = { scale ->
                        AppPrefs.setExternalUiScale(ctx, scale)
                    },
                    onToggleHostScreenPower = { screenOn ->
                        BridgeClient.setHostScreenPowerAsync(screenOn) { ok ->
                            val msg = when {
                                !ok -> "当前设备暂不支持物理息屏控制"
                                screenOn -> "已点亮原手机屏幕"
                                else -> "已熄灭原手机屏幕（双击手机屏幕随时亮屏，断开也会自动亮屏）"
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSelectThemeMode = { mode ->
                        AppPrefs.setThemeMode(ctx, mode)
                    },
                    onSelectWallpaper = { idx ->
                        WallpaperStorage.clearCustomWallpaper(ctx)
                        AppPrefs.setWallpaperPreset(ctx, idx)
                    },
                    onPickCustomWallpaper = {
                        showControlCenter = false
                        onPickCustomWallpaper()
                    },
                    onSelectMouseTarget = { mode ->
                        AppPrefs.setMouseTarget(ctx, mode)
                        val extId = ExternalTrackpadBridge.sessionFlow.value.displayId
                        BridgeClient.setMouseTargetAsync(mode, if (extId > 0) extId else -1)
                    },
                    onOpenDpiSettings = {
                        showControlCenter = false
                        showDpiPopover = true
                    },
                    onApplyTilingLayout = ::applyTiling,
                    onMinimizeAll = {
                        showControlCenter = false
                        minimizeAllWindows()
                    },
                    onCloseAll = {
                        showControlCenter = false
                        BridgeClient.closeAllWindowsAsync(removeTasks = true)
                    },
                    onDismissPopover = { showControlCenter = false },
                    onOpenConsole = {
                        showControlCenter = false
                        onOpenConsole()
                    },
                    onExitDesktop = {
                        showControlCenter = false
                        showExitSlotModal = true
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = (CompositorGeometry.TOP_BAR_DP + 8f).dp, start = 12.dp, end = 12.dp)
                        .zIndex(10_020f),
                )
            }

            // In-DeX Live DPI Settings Floating Panel (global + per-app DPI with real-time preview)
            if (showDpiPopover) {
                val dpiMaxH = (maxHeight - (CompositorGeometry.TOP_BAR_DP + 24f).dp).coerceAtLeast(240.dp)
                io.github.lengsukq.opaldesk.ui.DexDpiSettingsPopover(
                    openWindows = scene.windows,
                    allApps = allApps,
                    maxHeight = dpiMaxH,
                    onDismiss = { showDpiPopover = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = (CompositorGeometry.TOP_BAR_DP + 8f).dp, start = 12.dp, end = 12.dp)
                        .zIndex(10_022f),
                )
            }

            // Desktop Wallpaper Right-Click / Long-Press Context Menu (anchored right at cursor)
            wallpaperMenuPos?.let { pos ->
                val isCurrentlyDark = OpalPalette.isDark
                val menuW = with(density) { 252.dp.toPx() }.roundToInt()
                val baseMenuDp = (if (customWallpaperPath.isNotBlank()) 358f else 322f) +
                    (if (canControlPhoneTrackpad) 38f else 0f)
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
                    onTogglePhoneTrackpad = ::togglePhoneTrackpadFromExternal,
                    onSelectPreset = { idx ->
                        WallpaperStorage.clearCustomWallpaper(ctx)
                        AppPrefs.setWallpaperPreset(ctx, idx)
                    },
                    onPickCustomWallpaper = onPickCustomWallpaper,
                    onClearCustomWallpaper = {
                        WallpaperStorage.clearCustomWallpaper(ctx)
                        Toast.makeText(ctx, "已恢复默认预设壁纸", Toast.LENGTH_SHORT).show()
                    },
                    onOpenTilingMenu = {
                        controlCenterInitialTab = 1
                        showControlCenter = true
                    },
                    onOpenDockSettings = {
                        controlCenterInitialTab = 2
                        showControlCenter = true
                    },
                    onOpenAppDrawer = { showAppDrawer = true },
                    onToggleLightDark = {
                        val next = if (isCurrentlyDark) AppPrefs.THEME_LIGHT else AppPrefs.THEME_DARK
                        AppPrefs.setThemeMode(ctx, next)
                    },
                    onOpenConsole = onOpenConsole,
                    onDismiss = { wallpaperMenuPos = null },
                    modifier = Modifier
                        .offset { IntOffset(xPx, yPx) }
                        .zIndex(10_025f),
                )
            }

            // Unified App Context Menu anchored right at cursor / finger position (auto-flips near edges!)
            contextMenuTarget?.let { (pkg, win, anchorPos) ->
                val appEntry = allApps.firstOrNull { it.pkg == pkg }
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
                    pkg = pkg,
                    label = win?.title ?: appEntry?.label ?: pkg,
                    window = win,
                    isOnDesktop = desktopPkgs.contains(pkg),
                    isPinnedToDock = dockPinnedPkgs.contains(pkg),
                    dockPinnedCount = dockPinnedPkgs.size,
                    onFocusOrLaunch = {
                        showAppDrawer = false
                        if (win != null) {
                            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.FOCUS)
                        } else {
                            AppPrefs.recordAppLaunch(ctx, pkg)
                            scope.launch(Dispatchers.IO) {
                                BridgeClient.openWindow(pkg, appEntry?.activityClass.orEmpty(), BridgeProtocol.Profile.AUTO)
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
                        editingAppDpiTarget = pkg to (win?.title ?: appEntry?.label ?: pkg)
                    },
                    onToggleDesktopShortcut = {
                        val added = AppPrefs.toggleDesktopShortcut(ctx, pkg)
                        Toast.makeText(
                            ctx,
                            if (added) "已添加到桌面" else "已从桌面移除",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onToggleDockPin = {
                        when (AppPrefs.toggleDockPin(ctx, pkg)) {
                            AppPrefs.DockPinResult.ADDED ->
                                Toast.makeText(ctx, "已添加到底部程序坞", Toast.LENGTH_SHORT).show()
                            AppPrefs.DockPinResult.REMOVED ->
                                Toast.makeText(ctx, "已从底部程序坞移除", Toast.LENGTH_SHORT).show()
                            AppPrefs.DockPinResult.LIMIT_REACHED ->
                                Toast.makeText(
                                    ctx,
                                    "程序坞最多固定 ${AppPrefs.MAX_DOCK_PINNED} 个应用，请先移除一个后再添加",
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    },
                    onCloseWindow = {
                        if (win != null) {
                            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.CLOSE)
                        }
                    },
                    onDismiss = { contextMenuTarget = null },
                    modifier = Modifier
                        .offset { IntOffset(xPx, yPx) }
                        .zIndex(10_026f),
                )
            }

            // Mission Control / Exposé Multi-Window Overview Overlay
            if (showExposeOverview) {
                Box(modifier = Modifier.fillMaxSize().zIndex(10_030f)) {
                    ExposeWindowSwitcherOverlay(
                        windows = scene.windows,
                        isCompactWidth = isCompactWidth,
                        onSelectWindow = { win ->
                            showExposeOverview = false
                            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.FOCUS)
                        },
                        onCloseWindow = { win ->
                            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.CLOSE)
                        },
                        onMinimizeAll = {
                            showExposeOverview = false
                            minimizeAllWindows()
                        },
                        onCloseAll = {
                            showExposeOverview = false
                            BridgeClient.closeAllWindowsAsync(removeTasks = true)
                        },
                        onDismiss = { showExposeOverview = false },
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

            // Crisp macOS Arrow Cursor Overlay (for External Monitor Presentation Trackpad / Mouse mode)
            virtualCursorPx?.let { pos ->
                Box(modifier = Modifier.fillMaxSize().zIndex(10_050f)) {
                    MacVirtualPointerCursor(positionPx = pos)
                }
            }

            // Per-app custom DPI editor modal (live preview; saved when user commits to a slot on exit)
            editingAppDpiTarget?.let { (pkg, appLabel) ->
                val working = profileState.workingConfig
                io.github.lengsukq.opaldesk.ui.PerAppDpiEditorModal(
                    pkg = pkg,
                    appLabel = appLabel,
                    currentCustomDpi = working.appCustomDpis[pkg] ?: 0,
                    globalCustomDpi = working.globalCustomDpi,
                    onApplyDpi = { nextDpi ->
                        BridgeClient.setAppCustomDpiAsync(ctx, pkg, nextDpi)
                        val tip = if (nextDpi > 0) "已为「$appLabel」应用 $nextDpi DPI（退出时可选择槽位保存）" else "已恢复「$appLabel」跟随全局 DPI"
                        Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { editingAppDpiTarget = null },
                    modifier = Modifier.zIndex(10_055f),
                )
            }

            // Exit DeX modal: lets user pick which of the 5 slots to save current config & layout into
            if (showExitSlotModal) {
                io.github.lengsukq.opaldesk.ui.ExitSaveSlotModal(
                    title = "退出 DeX 桌面",
                    subtitle = "可选择将本次调整的 DPI、应用窗口大小/位置与当前桌面布局保存到 1–5 号任意槽位，或直接退出。",
                    confirmLabel = "保存并退出",
                    openWindowCount = scene.windows.size,
                    busy = savingOnExit,
                    onDismiss = { if (!savingOnExit) showExitSlotModal = false },
                    onExitWithoutSaving = {
                        if (!savingOnExit) {
                            showExitSlotModal = false
                            onExitDesktop()
                        }
                    },
                    onSaveToSlotAndExit = { slotId, saveLayout ->
                        if (!savingOnExit) {
                            savingOnExit = true
                            scope.launch(Dispatchers.IO) {
                                BridgeClient.pullDaemonWindowBoundsSync()
                                val saved = io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.saveWorkingToSlot(
                                    ctx = ctx,
                                    targetSlotId = slotId,
                                    scene = scene,
                                    saveCurrentLayout = saveLayout,
                                    bindTargetKey = currentTarget.key,
                                )
                                withContext(Dispatchers.Main) {
                                    savingOnExit = false
                                    showExitSlotModal = false
                                    Toast.makeText(ctx, "已保存到槽位 ${slotId + 1}「${saved.name}」", Toast.LENGTH_SHORT).show()
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
    }
}

@Composable
private fun DesktopShortcutColumn(
    allApps: List<InstalledAppEntry>,
    desktopPkgs: List<String>,
    rowsPerColumn: Int = 5,
    onLaunch: (InstalledAppEntry) -> Unit,
    onContextApp: (String, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val shortcuts = remember(allApps, desktopPkgs) {
        val byPkg = allApps.associateBy { it.pkg }
        desktopPkgs.mapNotNull { byPkg[it] }.take(15)
    }

    // Wrap into adaptive vertical columns based on portrait/landscape canvas height
    val columns = shortcuts.chunked(rowsPerColumn.coerceIn(3, 8))
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        columns.forEach { colApps ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                colApps.forEach { app ->
                    val icon = remember(app.pkg) { AppCatalogRepository.getAppIcon(ctx, app.pkg) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(74.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .onTapOrContextAction(
                                app.pkg,
                                onPrimaryTap = { onLaunch(app) },
                                onContextAction = { rootPos -> onContextApp(app.pkg, rootPos) },
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .shadow(8.dp, RoundedCornerShape(13.dp))
                                .clip(RoundedCornerShape(13.dp))
                                .background(OpalPalette.GlassSurface),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = app.label,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                OpalText(text = app.label.take(1), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(OpalPalette.GlassSurface.copy(alpha = if (OpalPalette.isDark) 0.52f else 0.72f))
                                .padding(horizontal = 6.dp, vertical = 1.5.dp),
                        ) {
                            OpalText(
                                text = app.label,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpalPalette.TextPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExposeWindowSwitcherOverlay(
    windows: List<CompositorWindow>,
    isCompactWidth: Boolean = false,
    onSelectWindow: (CompositorWindow) -> Unit,
    onCloseWindow: (CompositorWindow) -> Unit,
    onMinimizeAll: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(onClick = onDismiss)
            .padding(
                horizontal = if (isCompactWidth) 16.dp else 32.dp,
                vertical = if (isCompactWidth) 28.dp else 44.dp,
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "调度中心", fontSize = if (isCompactWidth) 17.sp else 20.sp, fontWeight = FontWeight.Bold)
                    OpalText(
                        text = "共 ${windows.size} 个应用窗口 · 点按卡片立即切换或唤醒",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
                if (windows.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OpalButton(
                            label = "全部最小化",
                            filled = false,
                            compact = true,
                            onClick = onMinimizeAll,
                        )
                        OpalButton(
                            label = "全部关闭",
                            accent = OpalPalette.AccentDanger,
                            filled = false,
                            compact = true,
                            onClick = onCloseAll,
                        )
                    }
                }
            }
            Spacer(Modifier.height(if (isCompactWidth) 14.dp else 20.dp))
            if (windows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OpalText(text = "当前没有打开的窗口", color = OpalPalette.TextSecondary, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (isCompactWidth) 160.dp else 250.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(windows, key = { it.windowId }) { win ->
                        val icon = remember(win.pkg) { AppCatalogRepository.getAppIcon(ctx, win.pkg) }
                        var previewBmp by remember(win.windowId, win.snapshotVersion) {
                            mutableStateOf<Bitmap?>(LiveWindowPreviewRegistry.captureLivePreview(win.windowId))
                        }
                        LaunchedEffect(win.windowId, win.snapshotVersion) {
                            val liveSnap = LiveWindowPreviewRegistry.captureLivePreview(win.windowId)
                            if (liveSnap != null && !liveSnap.isRecycled) {
                                previewBmp = liveSnap
                            } else {
                                previewBmp = withContext(Dispatchers.IO) {
                                    BridgeClient.fetchWindowSnapshotBitmap(win.windowId, win.snapshotVersion)
                                } ?: LiveWindowPreviewRegistry.getCachedPreview(win.windowId)
                            }
                        }

                        OpalGlassCard(
                            modifier = Modifier.clickable { onSelectWindow(win) },
                            cornerRadius = 16.dp,
                            fillColor = OpalPalette.GlassPopover,
                            borderColor = if (win.focused) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = win.title,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(text = win.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    val stateLabel = when {
                                        win.isMinimized -> "已最小化"
                                        win.isHibernated -> "快照休眠"
                                        win.focused -> "当前活动"
                                        else -> "实时运行"
                                    }
                                    OpalText(
                                        text = "$stateLabel · ${win.contentWidth}×${win.contentHeight}",
                                        fontSize = 11.sp,
                                        color = OpalPalette.TextSecondary,
                                        maxLines = 1,
                                    )
                                }
                                OpalGlyphButton(glyph = ChromeGlyph.CLOSE, size = 24.dp, dangerHover = true) {
                                    onCloseWindow(win)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(124.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(OpalPalette.WindowCanvasBg)
                                    .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                val bmp = previewBmp
                                if (bmp != null && !bmp.isRecycled) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = win.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    OpalText(
                                        text = if (win.isLive) "实时虚拟屏 #${win.displayId}" else "点击恢复窗口",
                                        fontSize = 11.5.sp,
                                        color = OpalPalette.TextMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineDaemonPromptCard(
    onOpenConsole: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpalGlassCard(
        modifier = modifier.widthIn(max = 420.dp).fillMaxWidth(),
        cornerRadius = 20.dp,
        fillColor = OpalPalette.GlassPopover,
        contentPadding = PaddingValues(24.dp),
    ) {
        OpalStatusBadge(label = "守护进程尚未连接", color = OpalPalette.AccentAmber)
        Spacer(Modifier.height(12.dp))
        OpalText(text = "请先激活免 Root 后台守护进程", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        OpalText(
            text = "支持本机无线调试（免电脑）、Shizuku 一键秒激活、电脑 ADB 或可选 Root 激活。",
            fontSize = 13.sp,
            color = OpalPalette.TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OpalButton(label = "前往控制台激活", onClick = onOpenConsole, modifier = Modifier.weight(1f))
            OpalButton(label = "返回", filled = false, onClick = onExit)
        }
    }
}

/**
 * Crisp macOS-style black-filled, white-outlined arrow pointer rendered at `(positionPx.x, positionPx.y)`
 * on top of the external monitor Presentation canvas.
 */
@Composable
private fun MacVirtualPointerCursor(positionPx: Offset) {
    Canvas(
        modifier = Modifier
            .offset { IntOffset(positionPx.x.roundToInt(), positionPx.y.roundToInt()) }
            .size(26.dp),
    ) {
        val w = size.width
        val h = size.height
        val arrow = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, h * 0.84f)
            lineTo(w * 0.24f, h * 0.64f)
            lineTo(w * 0.42f, h * 0.96f)
            lineTo(w * 0.56f, h * 0.89f)
            lineTo(w * 0.38f, h * 0.58f)
            lineTo(w * 0.68f, h * 0.58f)
            close()
        }
        drawPath(path = arrow, color = Color(0xFF0E1015))
        drawPath(
            path = arrow,
            color = Color.White,
            style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}
