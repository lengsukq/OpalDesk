package io.github.lengsukq.opaldesk.compositor

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassSlider
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSegmentedRow
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalSwitchRow
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTextInput
import io.github.lengsukq.opaldesk.ui.TilingLayoutPreviewIcon
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import io.github.lengsukq.opaldesk.ui.onTapOrContextAction
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class SystemTrayInfo(
    val clockText: String = "",
    val dateText: String = "",
    val batteryPct: Int = 100,
    val charging: Boolean = false,
    val wifiConnected: Boolean = true,
)

fun querySystemTrayStatus(ctx: Context, clockText: String, dateText: String): SystemTrayInfo {
    var pct = 100
    var charging = false
    try {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level >= 0) pct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    } catch (_: Throwable) {
    }

    var wifi = false
    try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    } catch (_: Throwable) {
    }

    return SystemTrayInfo(
        clockText = clockText,
        dateText = dateText,
        batteryPct = pct,
        charging = charging,
        wifiConnected = wifi,
    )
}

/**
 * Refined 28dp macOS translucent Menu Bar at the top edge of the workspace.
 *
 * Right side hosts the unified System Status & Control Center trigger (Wi-Fi, Battery,
 * Control Center icon, Date/Clock, and Host-Screen-Off indicator).
 */
@Composable
fun MacDesktopMenuBar(
    focusedWindow: CompositorWindow?,
    tray: SystemTrayInfo,
    isExternalMonitor: Boolean,
    hostScreenOff: Boolean,
    isCompactWidth: Boolean,
    showControlCenter: Boolean,
    showDpiPopover: Boolean = false,
    notificationCount: Int = 0,
    showNotificationCenter: Boolean = false,
    doNotDisturb: Boolean = false,
    showPhoneTrackpadButton: Boolean = false,
    phoneTrackpadVisible: Boolean = false,
    onTogglePhoneTrackpad: () -> Unit = {},
    onToggleDpiPopover: () -> Unit = {},
    onToggleNotificationCenter: () -> Unit = {},
    onToggleFullscreen: () -> Unit,
    onToggleControlCenter: () -> Unit,
    onOpenConsole: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CompositorGeometry.TOP_BAR_DP.dp)
            .background(OpalPalette.MenuBarFill)
            .padding(horizontal = if (isCompactWidth) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenConsole)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.5.dp)
                    .clip(CircleShape)
                    .background(OpalPalette.AccentBlue),
            )
            Spacer(Modifier.width(6.dp))
            OpalText(
                text = "OpalDesk",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = OpalPalette.TextPrimary,
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(if (isCompactWidth) 6.dp else 12.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (focusedWindow != null) {
                OpalText(
                    text = focusedWindow.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(if (isCompactWidth) 6.dp else 9.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(OpalPalette.ChipFill)
                        .clickable(onClick = onToggleFullscreen)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OpalGlyphIcon(
                        glyph = ChromeGlyph.MAXIMIZE,
                        tint = OpalPalette.AccentBlue,
                        size = 10.5.dp,
                    )
                    OpalText(
                        text = "全屏",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentBlue,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(if (isCompactWidth) 5.dp else 8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (showDpiPopover) OpalPalette.AccentBlue.copy(alpha = 0.20f) else OpalPalette.ChipFill)
                        .clickable(onClick = onToggleDpiPopover)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    OpalText(
                        text = if (isCompactWidth) "${focusedWindow.contentDpi}dpi" else "${focusedWindow.contentWidth}×${focusedWindow.contentHeight} · ${focusedWindow.contentDpi}dpi",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (showDpiPopover) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
            } else if (!isCompactWidth) {
                OpalText(
                    text = if (isExternalMonitor) "外接桌面工作站" else "桌面工作站",
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        if (showPhoneTrackpadButton) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (phoneTrackpadVisible) OpalPalette.AccentBlue.copy(alpha = 0.20f) else OpalPalette.ChipFill)
                    .clickable(onClick = onTogglePhoneTrackpad)
                    .padding(horizontal = if (isCompactWidth) 6.dp else 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (phoneTrackpadVisible) OpalPalette.AccentBlue else OpalPalette.TextMuted),
                )
                OpalText(
                    text = if (isCompactWidth) "触控板" else "手机触控板",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (phoneTrackpadVisible) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(6.dp))
        }

        // Top-Right Notification Center Bell Trigger
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(if (showNotificationCenter) OpalPalette.ChipActiveFill else Color.Transparent)
                .clickable(onClick = onToggleNotificationCenter)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DesktopBellIcon(
                tint = when {
                    doNotDisturb -> OpalPalette.AccentAmber
                    showNotificationCenter || notificationCount > 0 -> OpalPalette.AccentBlue
                    else -> OpalPalette.TextPrimary
                },
                modifier = Modifier.size(13.dp),
            )
            if (notificationCount > 0 && !doNotDisturb) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.AccentBlue)
                        .padding(horizontal = 4.5.dp, vertical = 0.5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OpalText(
                        text = if (notificationCount > 99) "99+" else "$notificationCount",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            } else if (doNotDisturb) {
                OpalText(
                    text = "勿扰",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentAmber,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(4.dp))

        // Unified Top-Right Status Bar & Control Center Trigger
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(if (showControlCenter) OpalPalette.ChipActiveFill else Color.Transparent)
                .clickable(onClick = onToggleControlCenter)
                .padding(horizontal = if (isCompactWidth) 6.dp else 9.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCompactWidth) 6.dp else 9.dp),
        ) {
            if (hostScreenOff) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.AccentEmerald.copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    OpalText(
                        text = "息屏省电",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentEmerald,
                        maxLines = 1,
                    )
                }
            }
            OpalGlyphIcon(
                glyph = ChromeGlyph.WIFI,
                tint = if (tray.wifiConnected) OpalPalette.TextPrimary else OpalPalette.TextMuted,
                size = 13.dp,
            )
            OpalText(
                text = if (tray.charging) "⚡${tray.batteryPct}%" else "${tray.batteryPct}%",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    tray.charging -> OpalPalette.AccentEmerald
                    tray.batteryPct <= 15 -> OpalPalette.AccentDanger
                    else -> OpalPalette.TextPrimary
                },
                maxLines = 1,
            )
            OpalGlyphIcon(
                glyph = ChromeGlyph.CONTROL_CENTER,
                tint = if (showControlCenter) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                size = 13.5.dp,
            )
            val timeLabel = if (!isCompactWidth && tray.dateText.isNotEmpty()) {
                "${tray.dateText}  ${tray.clockText}"
            } else {
                tray.clockText
            }
            if (timeLabel.isNotEmpty()) {
                OpalText(
                    text = timeLabel,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Centered Floating macOS Liquid Glass Dock (adaptive for both wide tablet/monitor and compact
 * portrait phone screens), preserving Samsung DeX's intuitive Launchpad + Running Apps + Navigation.
 */
@Composable
fun DexMacTaskbar(
    allApps: List<InstalledAppEntry>,
    dockPinnedPkgs: List<String>,
    windows: List<CompositorWindow>,
    showAppDrawer: Boolean,
    showExpose: Boolean,
    isCompactWidth: Boolean = false,
    imeActive: Boolean = false,
    onToggleSoftKeyboard: (() -> Unit)? = null,
    onToggleAppDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleExpose: () -> Unit,
    onSendBackToFocused: () -> Unit,
    onShowDesktopToggle: () -> Unit,
    onLaunchOrToggleApp: (String, String) -> Unit,
    onContextApp: (String, CompositorWindow?, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val taskbarEntries = remember(dockPinnedPkgs, windows, allApps) {
        val installedByPkg = allApps.associateBy { it.pkg }
        val combined = LinkedHashSet<String>()
        dockPinnedPkgs
            .filter { installedByPkg.containsKey(it) }
            .take(AppPrefs.MAX_DOCK_PINNED)
            .forEach { combined.add(it) }
        windows.forEach { combined.add(it.pkg) }
        if (combined.isEmpty()) {
            allApps.take(5).forEach { combined.add(it.pkg) }
        }
        combined.take(AppPrefs.MAX_DOCK_PINNED + 6).map { pkg ->
            val info = installedByPkg[pkg]
            Triple(pkg, info?.activityClass.orEmpty(), info?.label ?: pkg)
        }
    }

    val dockShape = RoundedCornerShape(if (isCompactWidth) 19.dp else 22.dp)
    val leadBtnSize = if (isCompactWidth) 34.dp else 40.dp
    val navBtnSize = if (isCompactWidth) 30.dp else 34.dp
    val appTileSize = if (isCompactWidth) 40.dp else 46.dp
    val appIconSize = if (isCompactWidth) 28.dp else 32.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isCompactWidth) 8.dp else 14.dp,
                end = if (isCompactWidth) 8.dp else 14.dp,
                bottom = if (isCompactWidth) 8.dp else 10.dp,
            ),
    ) {
        // Center Iconic Floating macOS Liquid Frosted Glass Dock
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 780.dp)
                .liquidGlassSurface(
                    shape = dockShape,
                    fill = OpalPalette.LiquidDockFill,
                    rim = OpalPalette.LiquidGlassRim,
                    elevation = 22.dp,
                )
                .padding(
                    horizontal = if (isCompactWidth) 7.dp else 10.dp,
                    vertical = if (isCompactWidth) 5.dp else 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCompactWidth) 3.5.dp else 5.dp),
        ) {
            // Left: Launchpad / App Drawer (+ Spotlight Search on wide screens)
            GlassDockButton(
                glyph = ChromeGlyph.APPS_GRID,
                active = showAppDrawer,
                size = leadBtnSize,
                onClick = onToggleAppDrawer,
            )
            if (!isCompactWidth) {
                GlassDockButton(
                    glyph = ChromeGlyph.SEARCH,
                    active = false,
                    size = 38.dp,
                    onClick = onOpenSearch,
                )
            }

            GlassDockDivider()

            // Center: Pinned + Running Apps Strip (DeX click-to-toggle + long-press menu + macOS running dot)
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isCompactWidth) 3.5.dp else 5.dp),
            ) {
                taskbarEntries.forEach { (pkg, cls, label) ->
                    val icon = remember(pkg) { AppCatalogRepository.getAppIcon(ctx, pkg) }
                    val runningWin = windows.firstOrNull { it.pkg == pkg }
                    val isFocused = runningWin != null && runningWin.focused && !runningWin.isMinimized

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .size(width = appTileSize, height = appTileSize)
                            .clip(RoundedCornerShape(if (isCompactWidth) 11.dp else 14.dp))
                            .background(
                                when {
                                    isFocused -> OpalPalette.ChipActiveFill
                                    runningWin != null -> OpalPalette.ChipFill
                                    else -> Color.Transparent
                                },
                            )
                            .border(
                                width = if (isFocused) 0.85.dp else 0.dp,
                                color = if (isFocused) OpalPalette.BorderFocused else Color.Transparent,
                                shape = RoundedCornerShape(if (isCompactWidth) 11.dp else 14.dp),
                            )
                            .onTapOrContextAction(
                                pkg, runningWin?.windowId, runningWin?.state, runningWin?.focused,
                                onPrimaryTap = { onLaunchOrToggleApp(pkg, cls) },
                                onContextAction = { rootPos -> onContextApp(pkg, runningWin, rootPos) },
                            ),
                    ) {
                        Box(
                            modifier = Modifier.size(appIconSize + 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = label,
                                    alpha = if (runningWin?.isMinimized == true) 0.56f else 1f,
                                    modifier = Modifier
                                        .size(appIconSize)
                                        .clip(RoundedCornerShape(if (isCompactWidth) 8.dp else 9.dp)),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(appIconSize)
                                        .clip(RoundedCornerShape(if (isCompactWidth) 8.dp else 9.dp))
                                        .background(OpalPalette.BgElevated),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    OpalText(text = label.take(1), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .size(width = if (isFocused) 11.dp else 4.dp, height = 2.8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        runningWin == null -> Color.Transparent
                                        runningWin.isMinimized -> OpalPalette.TextMuted
                                        runningWin.isHibernated -> OpalPalette.AccentAmber
                                        isFocused -> OpalPalette.AccentBlue
                                        else -> OpalPalette.AccentCyan
                                    },
                                ),
                        )
                    }
                }
            }

            GlassDockDivider()

            // Right of Dock: Optional Native IME button + Compact DeX Navigation (Back / Desktop / Mission Control)
            if (onToggleSoftKeyboard != null) {
                GlassDockButton(
                    glyph = ChromeGlyph.KEYBOARD,
                    active = imeActive,
                    size = navBtnSize,
                    onClick = onToggleSoftKeyboard,
                )
            }
            GlassDockButton(
                glyph = ChromeGlyph.BACK,
                active = false,
                size = navBtnSize,
                onClick = onSendBackToFocused,
            )
            GlassDockButton(
                glyph = ChromeGlyph.HOME_DESKTOP,
                active = false,
                size = navBtnSize,
                onClick = onShowDesktopToggle,
            )
            GlassDockButton(
                glyph = ChromeGlyph.EXPOSE,
                active = showExpose,
                size = navBtnSize,
                onClick = onToggleExpose,
            )
        }
    }
}

@Composable
private fun GlassDockButton(
    glyph: ChromeGlyph,
    active: Boolean,
    size: Dp = 38.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (active) OpalPalette.AccentBlue else OpalPalette.ChipFill)
            .border(
                width = 0.75.dp,
                color = if (active) OpalPalette.AccentBlue else OpalPalette.BorderHairline,
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(
            glyph = glyph,
            tint = if (active) Color.White else OpalPalette.TextPrimary.copy(alpha = 0.86f),
            size = size * 0.46f,
        )
    }
}

@Composable
private fun GlassDockDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(26.dp)
            .background(OpalPalette.DividerColor),
    )
}

/**
 * Floating Rounded Liquid Glass App Launcher (macOS Launchpad × Samsung DeX Search & Profile Picker).
 * Delegates directly to [io.github.lengsukq.opaldesk.ui.UnifiedAppLauncherPanel] so Local DeX
 * and Remote Standalone Floating Window Picker share one identical search, usage-sorting, and grid component.
 */
@Composable
fun DexAppDrawerPopover(
    apps: List<InstalledAppEntry>,
    desktopPkgs: List<String>,
    dockPinnedPkgs: List<String>,
    onLaunch: (InstalledAppEntry, Int) -> Unit,
    onContextApp: (InstalledAppEntry, Offset) -> Unit,
    maxHeight: Dp = 460.dp,
    modifier: Modifier = Modifier,
) {
    val launchCounts by AppPrefs.appLaunchCountsFlow.collectAsState()
    var selectedProfileIdx by remember { mutableIntStateOf(0) }

    val profileCode = when (selectedProfileIdx) {
        1 -> BridgeProtocol.Profile.DESKTOP
        2 -> BridgeProtocol.Profile.PORTRAIT
        3 -> BridgeProtocol.Profile.TABLET
        else -> BridgeProtocol.Profile.AUTO
    }

    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    val unifiedItems = remember(apps, desktopPkgs, dockPinnedPkgs, launchCounts) {
        val desktopSet = desktopPkgs.toHashSet()
        val dockSet = dockPinnedPkgs.toHashSet()
        apps.map { app ->
            io.github.lengsukq.opaldesk.ui.UnifiedAppItem(
                pkg = app.pkg,
                activityClass = app.activityClass,
                label = app.label,
                openCount = launchCounts[app.pkg] ?: 0,
                isPinnedDesktop = desktopSet.contains(app.pkg),
                isPinnedDock = dockSet.contains(app.pkg),
            )
        }
    }

    io.github.lengsukq.opaldesk.ui.UnifiedAppLauncherPanel(
        items = unifiedItems,
        searchPlaceholder = "搜索应用（点击启动 · 右键 / 长按固定）",
        maxWidth = 580.dp,
        maxHeight = maxHeight,
        onSelectApp = { item ->
            val entry = byPkg[item.pkg] ?: InstalledAppEntry(item.pkg, item.activityClass, item.label)
            onLaunch(entry, profileCode)
        },
        onContextApp = { item, rootPos ->
            val entry = byPkg[item.pkg] ?: InstalledAppEntry(item.pkg, item.activityClass, item.label)
            onContextApp(entry, rootPos)
        },
        topControlsContent = {
            OpalSegmentedRow(
                options = listOf("自动记忆", "桌面横屏", "手机竖屏", "平板 4:3"),
                selectedIndex = selectedProfileIdx,
                onSelect = { selectedProfileIdx = it },
            )
        },
        modifier = modifier,
    )
}

/**
 * Floating Liquid Frosted Glass Window Tiling Preset Popover anchored below the top Menu Bar.
 */
@Composable
fun WindowTilingPopover(
    windowCount: Int,
    onApplyLayout: (WindowTilingLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val layouts = listOf(
        WindowTilingLayout.SPLIT_2_COLS,
        WindowTilingLayout.SPLIT_2_MAIN_SIDE,
        WindowTilingLayout.SPLIT_3_COLS,
        WindowTilingLayout.SPLIT_3_MAIN_STACK,
        WindowTilingLayout.SPLIT_4_COLS,
        WindowTilingLayout.SPLIT_4_GRID,
        WindowTilingLayout.SPLIT_5_COLS,
        WindowTilingLayout.SPLIT_6_GRID,
        WindowTilingLayout.SPLIT_8_GRID,
        WindowTilingLayout.CASCADE,
    )
    Column(
        modifier = modifier
            .width(344.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 26.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = "快速分屏布局", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            OpalText(
                text = if (windowCount > 0) "当前 $windowCount 窗可排列" else "打开应用后一键排列",
                fontSize = 11.sp,
                color = if (windowCount > 0) OpalPalette.AccentCyan else OpalPalette.TextMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            layouts.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { layout ->
                        TilingPresetTile(
                            layout = layout,
                            enabled = windowCount > 0,
                            onClick = { onApplyLayout(layout) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TilingPresetTile(
    layout: WindowTilingLayout,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(tileShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, tileShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TilingLayoutPreviewIcon(
            layout = layout,
            tint = if (enabled) OpalPalette.AccentBlue else OpalPalette.TextMuted,
            size = 22.dp,
        )
        OpalText(
            text = layout.shortLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) OpalPalette.TextPrimary else OpalPalette.TextMuted,
            maxLines = 1,
        )
    }
}

/**
 * Unified Top-Right macOS Control Center Popover consolidating former top-bar indicators
 * (virtual display pool, active output summary, all tiling layouts), host phone screen-off power
 * control, external keyboard & mouse target routing, appearance, and wallpapers.
 */
@Composable
fun MacControlCenterPopover(
    tray: SystemTrayInfo,
    totalWindows: Int,
    focusedWindowId: Int = -1,
    canvasW: Int = 1920,
    canvasH: Int = 1200,
    displaySummary: String,
    inputBackendLabel: String,
    targetFps: Int,
    windowDpiScalePct: Int,
    externalUiScale: Float,
    themeMode: Int,
    wallpaperPreset: Int,
    mouseTargetMode: Int,
    isExternalMonitor: Boolean,
    hostScreenOff: Boolean,
    rootEnhanced: Boolean = false,
    showHostScreenToggle: Boolean,
    showRemoteMediaSection: Boolean = false,
    showPhoneTrackpadSection: Boolean = false,
    phoneTrackpadVisible: Boolean = false,
    showAutoTrackpadSwitch: Boolean = false,
    autoShowPhoneTrackpad: Boolean = true,
    maxHeight: Dp = 640.dp,
    initialTab: Int = 0,
    onTogglePhoneTrackpad: () -> Unit = {},
    onToggleAutoPhoneTrackpad: (Boolean) -> Unit = {},
    onSelectTargetFps: (Int) -> Unit,
    onSelectWindowDpiPct: (Int) -> Unit,
    onSelectUiScale: (Float) -> Unit,
    onToggleHostScreenPower: (Boolean) -> Unit,
    onSelectThemeMode: (Int) -> Unit,
    onSelectWallpaper: (Int) -> Unit,
    onPickCustomWallpaper: () -> Unit,
    onSelectMouseTarget: (Int) -> Unit,
    onOpenDpiSettings: () -> Unit = {},
    onApplyTilingLayout: (WindowTilingLayout) -> Unit,
    onMinimizeAll: () -> Unit,
    onCloseAll: () -> Unit,
    onDismissPopover: () -> Unit = {},
    onOpenConsole: () -> Unit,
    onExitDesktop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = OpalPalette.isDark
    val wallpaperColors = if (isDark) {
        listOf(
            Pair("红杉深海", listOf(Color(0xFF142842), Color(0xFF2A9D8F))),
            Pair("索诺玛暮色", listOf(Color(0xFF2B2752), Color(0xFFE07A5F))),
            Pair("极地冰川", listOf(Color(0xFF0E293D), Color(0xFF38BDF8))),
            Pair("曜石暗夜", listOf(Color(0xFF1C1F26), Color(0xFF4B5563))),
        )
    } else {
        listOf(
            Pair("晴空云雾", listOf(Color(0xFFC8DEFB), Color(0xFF60A5FA))),
            Pair("晨曦薄暮", listOf(Color(0xFFFBDCE8), Color(0xFFF472B6))),
            Pair("冰川清露", listOf(Color(0xFFBFE7F4), Color(0xFF38BDF8))),
            Pair("素白霜华", listOf(Color(0xFFE9EEF6), Color(0xFFCBD5E1))),
        )
    }

    val ctx = LocalContext.current
    val audioMgr = remember(ctx) { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val maxVolume = remember(audioMgr) {
        (try { audioMgr?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { null } ?: 15).coerceAtLeast(1)
    }
    var mediaVolume by remember(audioMgr) {
        mutableIntStateOf(
            (try { audioMgr?.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { null } ?: (maxVolume / 2))
                .coerceIn(0, maxVolume),
        )
    }
    var lastNonZeroVolume by remember { mutableIntStateOf(mediaVolume.coerceAtLeast(1)) }
    var screenBrightness by remember(ctx) { mutableStateOf(ControlCenterActions.readInitialBrightness(ctx)) }
    var brightnessAuto by remember { mutableStateOf(true) }
    var orientationMode by remember(ctx) { mutableIntStateOf(ControlCenterActions.readOrientationMode(ctx)) }
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) } // 0=快捷, 1=窗口, 2=显示
    var showMoreTiling by remember { mutableStateOf(false) }

    fun applyMediaVolume(target: Int) {
        val clamped = target.coerceIn(0, maxVolume)
        mediaVolume = clamped
        if (clamped > 0) lastNonZeroVolume = clamped
        try {
            audioMgr?.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        } catch (_: Throwable) {
        }
    }

    val shape = RoundedCornerShape(22.dp)
    val infoCardShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .widthIn(max = 352.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 28.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(12.dp),
    ) {
        // Compact Single-Line Status Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OpalText(text = "控制中心", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.ChipFill)
                        .padding(horizontal = 7.dp, vertical = 2.5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (totalWindows > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted),
                    )
                    OpalText(
                        text = if (totalWindows > 0) "$totalWindows 窗口" else "就绪",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentCyan,
                        maxLines = 1,
                    )
                }
            }
            val batLabel = if (tray.charging) "⚡${tray.batteryPct}%" else "${tray.batteryPct}%"
            val summaryText = if (displaySummary.isNotBlank()) "$displaySummary · $batLabel" else "$inputBackendLabel · $batLabel"
            OpalText(
                text = summaryText,
                fontSize = 10.5.sp,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }

        // Top Segmented Tab Bar (3 balanced tabs)
        Spacer(Modifier.height(8.dp))
        OpalSegmentedRow(
            options = listOf("快捷", "窗口", "显示"),
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
        )

        Spacer(Modifier.height(9.dp))
        // Scrollable Tab Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (selectedTab) {
                0 -> {
                    // Tab 0: Quick Controls (2x2 Bento Tiles + Unified Sound/Brightness/Media Card + Screenshots)
                    val btEnabled = remember(ctx, selectedTab) {
                        try {
                            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                            bm?.adapter?.isEnabled
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    fun openSystemSettings(action: String) {
                        try {
                            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Throwable) {
                            try {
                                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Throwable) {
                            }
                        }
                    }

                    val dndEnabled by io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.doNotDisturbFlow.collectAsState()
                    val btOn = btEnabled == true

                    // 2x2 Bento Quick-Toggle Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ControlBentoTile(
                            title = "Wi-Fi",
                            subtitle = if (tray.wifiConnected) "已连接" else "未连接",
                            active = tray.wifiConnected,
                            accent = OpalPalette.AccentBlue,
                            iconContent = { tint ->
                                OpalGlyphIcon(
                                    glyph = ChromeGlyph.WIFI,
                                    tint = tint,
                                    size = 14.dp,
                                )
                            },
                            onClick = { openSystemSettings(Settings.ACTION_WIFI_SETTINGS) },
                            modifier = Modifier.weight(1f),
                        )
                        ControlBentoTile(
                            title = "蓝牙",
                            subtitle = when (btEnabled) {
                                true -> "已开启"
                                false -> "已关闭"
                                null -> "设置"
                            },
                            active = btOn,
                            accent = OpalPalette.AccentBlue,
                            iconContent = { tint ->
                                OpalText(
                                    text = "B",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tint,
                                )
                            },
                            onClick = { openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ControlBentoTile(
                            title = "免打扰",
                            subtitle = if (dndEnabled) "已开启" else "已关闭",
                            active = dndEnabled,
                            accent = OpalPalette.AccentAmber,
                            iconContent = { tint ->
                                OpalText(
                                    text = "☾",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tint,
                                )
                            },
                            onClick = {
                                io.github.lengsukq.opaldesk.notifications.OpalNotificationStore.toggleDoNotDisturb()
                            },
                            modifier = Modifier.weight(1f),
                        )

                        if (!isExternalMonitor) {
                            val orientLabel = when (orientationMode) {
                                1 -> "锁定横屏"
                                2 -> "锁定竖屏"
                                else -> "自动旋转"
                            }
                            ControlBentoTile(
                                title = "屏幕方向",
                                subtitle = orientLabel,
                                active = orientationMode != 0,
                                accent = OpalPalette.AccentCyan,
                                iconContent = { tint ->
                                    OpalGlyphIcon(
                                        glyph = ChromeGlyph.ORIENTATION,
                                        tint = tint,
                                        size = 14.dp,
                                    )
                                },
                                onClick = {
                                    val next = (orientationMode + 1) % 3
                                    orientationMode = next
                                    ControlCenterActions.applyOrientationMode(ctx, next)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        } else if (showHostScreenToggle || hostScreenOff) {
                            ControlBentoTile(
                                title = "本机熄屏",
                                subtitle = if (hostScreenOff) "已熄屏省电" else "点击熄屏",
                                active = hostScreenOff,
                                accent = OpalPalette.AccentEmerald,
                                iconContent = { tint ->
                                    OpalGlyphIcon(
                                        glyph = ChromeGlyph.SCREEN_OFF,
                                        tint = tint,
                                        size = 14.dp,
                                    )
                                },
                                onClick = { onToggleHostScreenPower(hostScreenOff) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }

                    // Unified Sound, Brightness & Media Transport Card
                    val volPct = ((mediaVolume * 100f) / maxVolume.toFloat()).roundToInt().coerceIn(0, 100)
                    val brightPct = (screenBrightness * 100f).roundToInt().coerceIn(5, 100)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(infoCardShape)
                            .background(OpalPalette.ChipFill)
                            .border(0.75.dp, OpalPalette.BorderHairline, infoCardShape)
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        // Volume Slider Row
                        ControlCompactSliderRow(
                            label = "声音",
                            valueText = if (mediaVolume == 0) "已静音" else "$volPct%",
                            actionLabel = if (mediaVolume == 0) "恢复" else "静音",
                            actionActive = mediaVolume == 0,
                            actionAccent = OpalPalette.AccentAmber,
                            onActionClick = {
                                if (mediaVolume == 0) applyMediaVolume(lastNonZeroVolume) else applyMediaVolume(0)
                            },
                            value = mediaVolume.toFloat(),
                            valueRange = 0f..maxVolume.toFloat(),
                            onValueChange = { v -> applyMediaVolume(v.roundToInt()) },
                        )

                        // Brightness Slider Row (Local DeX only)
                        if (!isExternalMonitor) {
                            Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
                            ControlCompactSliderRow(
                                label = "亮度",
                                valueText = if (brightnessAuto) "自动" else "$brightPct%",
                                actionLabel = if (brightnessAuto) "自动 ✓" else "自动",
                                actionActive = brightnessAuto,
                                actionAccent = OpalPalette.AccentBlue,
                                onActionClick = {
                                    brightnessAuto = true
                                    ControlCenterActions.applyWindowBrightness(ctx, -1f)
                                },
                                value = screenBrightness,
                                valueRange = 0.05f..1.0f,
                                onValueChange = { v ->
                                    brightnessAuto = false
                                    screenBrightness = v
                                    ControlCenterActions.applyWindowBrightness(ctx, v)
                                },
                            )
                        }

                        // Compact Media Transport Row
                        Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OpalButton(
                                label = "⏮",
                                compact = true,
                                filled = false,
                                onClick = { ControlCenterActions.dispatchMediaKey(audioMgr, KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
                                modifier = Modifier.weight(0.85f),
                            )
                            OpalButton(
                                label = "⏯ 播放 / 暂停",
                                compact = true,
                                filled = true,
                                accent = OpalPalette.AccentBlue,
                                onClick = { ControlCenterActions.dispatchMediaKey(audioMgr, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) },
                                modifier = Modifier.weight(1.5f),
                            )
                            OpalButton(
                                label = "⏭",
                                compact = true,
                                filled = false,
                                onClick = { ControlCenterActions.dispatchMediaKey(audioMgr, KeyEvent.KEYCODE_MEDIA_NEXT) },
                                modifier = Modifier.weight(0.85f),
                            )
                        }
                    }

                    // One-Tap Screenshots (Focused Window / Full Desktop)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OpalButton(
                            label = "截当前窗口",
                            compact = true,
                            filled = false,
                            enabled = focusedWindowId > 0,
                            accent = OpalPalette.AccentCyan,
                            onClick = {
                                onDismissPopover()
                                ControlCenterActions.captureFocusedWindowScreenshot(ctx, focusedWindowId)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        OpalButton(
                            label = "截桌面整屏",
                            compact = true,
                            filled = false,
                            accent = OpalPalette.AccentBlue,
                            onClick = {
                                onDismissPopover()
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    ControlCenterActions.captureDesktopScreenshot(ctx)
                                }, 140L)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Phone Trackpad On-Screen Control (External Display only)
                    if (showPhoneTrackpadSection) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(infoCardShape)
                                .background(
                                    if (phoneTrackpadVisible) OpalPalette.AccentBlue.copy(alpha = 0.12f) else OpalPalette.ChipFill,
                                )
                                .border(
                                    width = 0.8.dp,
                                    color = if (phoneTrackpadVisible) OpalPalette.AccentBlue.copy(alpha = 0.40f) else OpalPalette.BorderHairline,
                                    shape = infoCardShape,
                                )
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(
                                        text = if (phoneTrackpadVisible) "手机触控板运行中" else "手机屏幕触控板",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (phoneTrackpadVisible) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                OpalButton(
                                    label = if (phoneTrackpadVisible) "关闭" else "开启",
                                    compact = true,
                                    filled = !phoneTrackpadVisible,
                                    accent = if (phoneTrackpadVisible) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                                    onClick = onTogglePhoneTrackpad,
                                )
                            }
                            if (showAutoTrackpadSwitch) {
                                Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
                                OpalSwitchRow(
                                    title = "外接屏自动显示触控板",
                                    subtitle = "关闭后仅在此手动打开",
                                    checked = autoShowPhoneTrackpad,
                                    onCheckedChange = onToggleAutoPhoneTrackpad,
                                )
                            }
                        }
                    }

                    // Audio Split Mode + Remote Mic & Root Camera Mapping Controls (remote OpalCast sessions only)
                    if (showRemoteMediaSection) {
                        val remoteMicEnabled by AppPrefs.remoteMicEnabledFlow.collectAsState()
                        val remoteCameraEnabled by AppPrefs.remoteCameraEnabledFlow.collectAsState()
                        val audioRouteMode by AppPrefs.audioRouteModeFlow.collectAsState()
                        val splitAudioOn = audioRouteMode == BridgeProtocol.AudioRoute.SPLIT_BY_APP
                        LaunchedEffect(Unit) {
                            AppPrefs.hydrateFlows(ctx)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(infoCardShape)
                                .background(OpalPalette.ChipFill)
                                .border(0.75.dp, OpalPalette.BorderHairline, infoCardShape)
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val effectiveCameraEnabled = rootEnhanced && remoteCameraEnabled
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                OpalText(
                                    text = "跨端音频与媒体",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpalPalette.TextPrimary,
                                )
                                OpalText(
                                    text = when {
                                        splitAudioOn -> "独立分流"
                                        remoteMicEnabled || effectiveCameraEnabled -> "已启用"
                                        else -> "默认"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (splitAudioOn || remoteMicEnabled || effectiveCameraEnabled) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
                                )
                            }
                            OpalSegmentedRow(
                                options = listOf("整机传 DeX", "应用独立分流"),
                                selectedIndex = audioRouteMode.coerceIn(
                                    BridgeProtocol.AudioRoute.ALL_TO_DEX,
                                    BridgeProtocol.AudioRoute.SPLIT_BY_APP,
                                ),
                                onSelect = { mode ->
                                    io.github.lengsukq.opaldesk.BridgeClient.setAudioRouteModeAsync(ctx, mode) {
                                        val msg = if (mode == BridgeProtocol.AudioRoute.SPLIT_BY_APP) {
                                            "已开启应用独立音频分流：手机与 DeX 各自发声"
                                        } else {
                                            "已切换为整机声音传至 DeX 投屏端"
                                        }
                                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                            OpalText(
                                text = if (splitAudioOn) {
                                    "手机主屏应用由手机发声，DeX 窗口应用由投屏端发声"
                                } else {
                                    "全部声音传至 DeX 投屏端；切至「应用独立分流」可各自发声"
                                },
                                fontSize = 10.sp,
                                color = OpalPalette.TextMuted,
                            )
                            Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
                            OpalSwitchRow(
                                title = "远端麦克风回传",
                                subtitle = if (remoteMicEnabled) "已开启 · 允许投屏端注入" else "已关闭 · Android 13+ 免 Root",
                                checked = remoteMicEnabled,
                                onCheckedChange = { enabled ->
                                    io.github.lengsukq.opaldesk.BridgeClient.setRemoteMediaConfigAsync(ctx, enabled, effectiveCameraEnabled) {
                                        android.widget.Toast.makeText(
                                            ctx,
                                            if (enabled) "已开启远端麦克风回传" else "已关闭远端麦克风回传",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                            Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
                            OpalSwitchRow(
                                title = "Root 摄像头映射",
                                subtitle = when {
                                    !rootEnhanced -> "需 Root 权限 · 未启用 Root 增强不可开启"
                                    effectiveCameraEnabled -> "已开启 · Android 15+ 系统虚拟摄像头就绪"
                                    else -> "已关闭 · Root 模式可用（Android 15+ 原生注入）"
                                },
                                checked = effectiveCameraEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && !rootEnhanced) {
                                        if (remoteCameraEnabled) {
                                            AppPrefs.setRemoteCameraEnabled(ctx, false)
                                        }
                                        android.widget.Toast.makeText(
                                            ctx,
                                            "未获得 Root 增强权限，无法开启摄像头映射（请先用 Root 模式激活引擎）",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                        return@OpalSwitchRow
                                    }
                                    io.github.lengsukq.opaldesk.BridgeClient.setRemoteMediaConfigAsync(ctx, remoteMicEnabled, enabled) { ok ->
                                        val msg = when {
                                            !enabled -> "已关闭 Root 摄像头映射"
                                            ok -> "已开启 Root 摄像头映射"
                                            else -> "未获得 Root 增强权限，无法开启摄像头映射"
                                        }
                                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }
                    }
                }

                1 -> {
                    // Tab 1: Workspace Slots, Tiling Layouts & Batch Window Actions
                    // Quick 1–5 Configuration Slot Switcher & Layout Restore
                    io.github.lengsukq.opaldesk.ui.QuickSlotSwitcherStrip(
                        compact = true,
                        showRestoreButton = true,
                        canvasW = canvasW,
                        canvasH = canvasH,
                    )

                    // Multi-Window Tiling Layouts (top 4 common presets + collapsible extra 6)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OpalText(text = "分屏布局", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
                            OpalText(
                                text = if (showMoreTiling) "收起 ▴" else "更多 (6) ▾",
                                fontSize = 11.sp,
                                color = OpalPalette.TextSecondary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showMoreTiling = !showMoreTiling }
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                        val primaryTiles = listOf(
                            WindowTilingLayout.SPLIT_2_COLS,
                            WindowTilingLayout.SPLIT_2_MAIN_SIDE,
                            WindowTilingLayout.SPLIT_3_COLS,
                            WindowTilingLayout.SPLIT_4_GRID,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            primaryTiles.forEach { layout ->
                                TilingPresetTile(
                                    layout = layout,
                                    enabled = totalWindows > 0,
                                    onClick = { onApplyTilingLayout(layout) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (showMoreTiling) {
                            val extraTiles = listOf(
                                WindowTilingLayout.SPLIT_3_MAIN_STACK,
                                WindowTilingLayout.SPLIT_4_COLS,
                                WindowTilingLayout.SPLIT_5_COLS,
                                WindowTilingLayout.SPLIT_6_GRID,
                                WindowTilingLayout.SPLIT_8_GRID,
                                WindowTilingLayout.CASCADE,
                            )
                            extraTiles.chunked(3).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    row.forEach { layout ->
                                        TilingPresetTile(
                                            layout = layout,
                                            enabled = totalWindows > 0,
                                            onClick = { onApplyTilingLayout(layout) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(3 - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    // Batch Window Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OpalButton(
                            label = "全部最小化",
                            compact = true,
                            filled = false,
                            enabled = totalWindows > 0,
                            accent = OpalPalette.AccentBlue,
                            onClick = onMinimizeAll,
                            modifier = Modifier.weight(1f),
                        )
                        OpalButton(
                            label = "关闭全部窗口",
                            compact = true,
                            filled = false,
                            enabled = totalWindows > 0,
                            accent = OpalPalette.AccentAmber,
                            onClick = onCloseAll,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                else -> {
                    // Tab 2: Display, FPS, DPI, External Scaling & Appearance
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        OpalText(text = "刷新率", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
                        val fpsOpts = listOf(0, 60, 90, 120)
                        val fpsIdx = fpsOpts.indexOf(targetFps).let { if (it >= 0) it else 0 }
                        OpalSegmentedRow(
                            options = listOf("自动", "60Hz", "90Hz", "120Hz"),
                            selectedIndex = fpsIdx,
                            onSelect = { onSelectTargetFps(fpsOpts[it]) },
                        )
                    }

                    // Compact Single-Row Real-time Visual DPI Entry
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(infoCardShape)
                            .background(OpalPalette.ChipFill)
                            .border(0.75.dp, OpalPalette.BorderHairline, infoCardShape)
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            OpalText(text = "实时窗口 DPI", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            OpalText(
                                text = "全局与单应用实时滑块预览",
                                fontSize = 10.sp,
                                color = OpalPalette.TextSecondary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OpalButton(
                            label = "调节",
                            compact = true,
                            filled = true,
                            onClick = onOpenDpiSettings,
                        )
                    }

                    if (isExternalMonitor) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            OpalText(text = "界面缩放", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
                            val scaleOpts = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                            val scaleIdx = scaleOpts.indexOfFirst { abs(it - externalUiScale) < 0.08f }.let { if (it >= 0) it else 1 }
                            OpalSegmentedRow(
                                options = listOf("100%", "125%", "150%", "175%", "200%"),
                                selectedIndex = scaleIdx,
                                onSelect = { onSelectUiScale(scaleOpts[it]) },
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            OpalText(text = "外接键鼠目标", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
                            OpalSegmentedRow(
                                options = listOf("跟随本机", "优先投屏端"),
                                selectedIndex = mouseTargetMode.coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL),
                                onSelect = onSelectMouseTarget,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        OpalText(text = "外观主题", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
                        OpalSegmentedRow(
                            options = listOf("浅色", "跟随系统", "深色"),
                            selectedIndex = themeMode.coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK),
                            onSelect = onSelectThemeMode,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OpalText(text = "桌面壁纸", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
                            OpalText(
                                text = "相册自定义…",
                                fontSize = 11.sp,
                                color = OpalPalette.AccentBlue,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onPickCustomWallpaper)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                            wallpaperColors.forEachIndexed { idx, (name, grad) ->
                                val selected = idx == wallpaperPreset
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onSelectWallpaper(idx) }
                                        .padding(vertical = 2.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(Brush.linearGradient(grad))
                                            .border(
                                                width = if (selected) 1.6.dp else 0.7.dp,
                                                color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                                                shape = RoundedCornerShape(9.dp),
                                            ),
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    OpalText(
                                        text = name,
                                        fontSize = 10.sp,
                                        color = if (selected) OpalPalette.TextPrimary else OpalPalette.TextMuted,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pinned Bottom Action Row (2 global actions, zero duplicates)
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OpalButton(
                label = "主控制台",
                compact = true,
                filled = false,
                onClick = onOpenConsole,
                modifier = Modifier.weight(1f),
            )
            OpalButton(
                label = "退出 DeX",
                compact = true,
                filled = false,
                accent = OpalPalette.AccentDanger,
                onClick = onExitDesktop,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ControlBentoTile(
    title: String,
    subtitle: String,
    active: Boolean,
    accent: Color,
    iconContent: @Composable (tint: Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(tileShape)
            .background(if (active) accent.copy(alpha = 0.15f) else OpalPalette.ChipFill)
            .border(
                width = 0.8.dp,
                color = if (active) accent.copy(alpha = 0.44f) else OpalPalette.BorderHairline,
                shape = tileShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (active) accent else OpalPalette.ChipActiveFill),
            contentAlignment = Alignment.Center,
        ) {
            iconContent(if (active) Color.White else OpalPalette.TextSecondary)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            OpalText(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
                maxLines = 1,
            )
            OpalText(
                text = subtitle,
                fontSize = 10.sp,
                color = if (active) accent else OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ControlCompactSliderRow(
    label: String,
    valueText: String,
    actionLabel: String,
    actionActive: Boolean,
    actionAccent: Color,
    onActionClick: () -> Unit,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalText(text = label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                OpalText(text = valueText, fontSize = 10.5.sp, color = OpalPalette.TextSecondary)
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (actionActive) actionAccent.copy(alpha = 0.16f) else OpalPalette.ChipActiveFill)
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                OpalText(
                    text = actionLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (actionActive) actionAccent else OpalPalette.TextSecondary,
                )
            }
        }
        OpalGlassSlider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Floating Rounded Liquid Frosted Glass Context Menu when right-clicking or long-pressing
 * any app in the App Drawer, Desktop Shortcuts, or Bottom Dock.
 */
@Composable
fun TaskbarContextMenuPopover(
    pkg: String,
    label: String,
    window: CompositorWindow?,
    isOnDesktop: Boolean,
    isPinnedToDock: Boolean,
    dockPinnedCount: Int,
    onFocusOrLaunch: () -> Unit,
    onSnapLeft: () -> Unit,
    onSnapRight: () -> Unit,
    onMaximize: () -> Unit,
    onEditAppDpi: () -> Unit = {},
    onToggleDesktopShortcut: () -> Unit,
    onToggleDockPin: () -> Unit,
    onCloseWindow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val profileState by io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.stateFlow.collectAsState()
    val cropDp = profileState.workingConfig.appTopCropDps[pkg] ?: 0
    val isCropTop = cropDp > 0
    val shape = RoundedCornerShape(18.dp)
    val dockFull = !isPinnedToDock && dockPinnedCount >= AppPrefs.MAX_DOCK_PINNED
    Column(
        modifier = modifier
            .width(244.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 24.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(7.dp),
    ) {
        OpalText(
            text = label.ifBlank { pkg },
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
        ContextMenuRow(if (window != null) "聚焦 / 恢复窗口" else "启动应用") {
            onFocusOrLaunch()
            onDismiss()
        }
        ContextMenuRow("自定义此应用 DPI…", textColor = OpalPalette.AccentBlue) {
            onDismiss()
            onEditAppDpi()
        }
        ContextMenuRow(
            title = if (isCropTop) "裁切顶部留白 (${cropDp}dp) ✓" else "裁切顶部留白",
            textColor = if (isCropTop) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
        ) {
            val enabled = io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.toggleWorkingAppTopCrop(pkg)
            android.widget.Toast.makeText(
                ctx,
                if (enabled) "已为「${label.ifBlank { pkg }}」开启顶部裁切（可在实时 DPI 面板微调高度）" else "已恢复「${label.ifBlank { pkg }}」原始顶部",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
        if (window != null) {
            ContextMenuRow("贴靠左半屏") {
                onSnapLeft()
                onDismiss()
            }
            ContextMenuRow("贴靠右半屏") {
                onSnapRight()
                onDismiss()
            }
            ContextMenuRow(if (window.isMaximized) "还原窗口大小" else "最大化窗口") {
                onMaximize()
                onDismiss()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .height(0.8.dp)
                .background(OpalPalette.DividerColor),
        )
        ContextMenuRow(if (isOnDesktop) "从桌面移除图标" else "添加到桌面") {
            onToggleDesktopShortcut()
            onDismiss()
        }
        ContextMenuRow(
            title = when {
                isPinnedToDock -> "从程序坞移除 ($dockPinnedCount/${AppPrefs.MAX_DOCK_PINNED})"
                dockFull -> "程序坞已满 (${AppPrefs.MAX_DOCK_PINNED}/${AppPrefs.MAX_DOCK_PINNED})"
                else -> "添加到底部程序坞 ($dockPinnedCount/${AppPrefs.MAX_DOCK_PINNED})"
            },
            textColor = if (dockFull) OpalPalette.TextMuted else OpalPalette.TextPrimary,
        ) {
            onToggleDockPin()
            onDismiss()
        }
        if (window != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 8.dp)
                    .height(0.8.dp)
                    .background(OpalPalette.DividerColor),
            )
            ContextMenuRow("关闭窗口", textColor = OpalPalette.AccentDanger) {
                onCloseWindow()
                onDismiss()
            }
        }
    }
}

/**
 * macOS-style Desktop Wallpaper Right-Click / Long-Press Context Menu allowing free custom
 * gallery wallpaper selection, preset switching, quick tiling, and appearance toggling.
 */
@Composable
fun DesktopWallpaperContextMenu(
    wallpaperPreset: Int,
    hasCustomWallpaper: Boolean,
    showPhoneTrackpadAction: Boolean = false,
    phoneTrackpadVisible: Boolean = false,
    onTogglePhoneTrackpad: () -> Unit = {},
    onSelectPreset: (Int) -> Unit,
    onPickCustomWallpaper: () -> Unit,
    onClearCustomWallpaper: () -> Unit,
    onOpenTilingMenu: () -> Unit,
    onOpenAppDrawer: () -> Unit,
    onToggleLightDark: () -> Unit,
    onOpenConsole: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = OpalPalette.isDark
    val presets = if (isDark) {
        listOf(
            Color(0xFF142842) to Color(0xFF2A9D8F),
            Color(0xFF2B2752) to Color(0xFFE07A5F),
            Color(0xFF0E293D) to Color(0xFF38BDF8),
            Color(0xFF1C1F26) to Color(0xFF4B5563),
        )
    } else {
        listOf(
            Color(0xFFC8DEFB) to Color(0xFF60A5FA),
            Color(0xFFFBDCE8) to Color(0xFFF472B6),
            Color(0xFFBFE7F4) to Color(0xFF38BDF8),
            Color(0xFFE9EEF6) to Color(0xFFCBD5E1),
        )
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .width(248.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 24.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(8.dp),
    ) {
        OpalText(
            text = "桌面与壁纸",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            presets.forEachIndexed { idx, (c1, c2) ->
                val selected = !hasCustomWallpaper && idx == wallpaperPreset
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(c1, c2)))
                        .border(
                            width = if (selected) 1.8.dp else 0.7.dp,
                            color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            onSelectPreset(idx)
                            onDismiss()
                        },
                )
            }
        }
        ContextMenuRow("自由更换桌面壁纸（从相册选择）…", textColor = OpalPalette.AccentBlue) {
            onDismiss()
            onPickCustomWallpaper()
        }
        if (hasCustomWallpaper) {
            ContextMenuRow("恢复默认预设壁纸") {
                onClearCustomWallpaper()
                onDismiss()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .height(0.8.dp)
                .background(OpalPalette.DividerColor),
        )
        ContextMenuRow("快速分屏布局（二 / 三 / 四列…）") {
            onDismiss()
            onOpenTilingMenu()
        }
        ContextMenuRow("打开应用抽屉") {
            onDismiss()
            onOpenAppDrawer()
        }
        ContextMenuRow(if (isDark) "切换为浅色液态毛玻璃" else "切换为深色液态毛玻璃") {
            onToggleLightDark()
            onDismiss()
        }
        if (showPhoneTrackpadAction) {
            ContextMenuRow(
                title = if (phoneTrackpadVisible) "关闭手机屏幕触控板" else "在手机上显示触控板",
                textColor = OpalPalette.AccentBlue,
            ) {
                onDismiss()
                onTogglePhoneTrackpad()
            }
        }
        ContextMenuRow("打开 OpalDesk 主控制台") {
            onDismiss()
            onOpenConsole()
        }
    }
}

@Composable
private fun ContextMenuRow(
    title: String,
    textColor: Color = OpalPalette.TextPrimary,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        OpalText(text = title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = textColor)
    }
}

/**
 * Unobtrusive Top-Center Pull-Down / Tap Handle shown exclusively during True App Fullscreen mode
 * (watching video / gaming), letting the user exit fullscreen, return to desktop, or open Control Center.
 */
@Composable
fun FullscreenExitOverlay(
    windowTitle: String,
    onExitFullscreen: () -> Unit,
    onReturnToDesktop: () -> Unit,
    onOpenControlCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(expanded) {
        if (expanded) {
            delay(4_500L)
            expanded = false
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopEnd,
    ) {
        if (!expanded) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.28f))
                    .border(0.6.dp, Color.White.copy(alpha = 0.34f), CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures { expanded = true }
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 6f) expanded = true
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                OpalText(
                    text = "退出全屏",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.76f),
                    maxLines = 1,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .padding(top = 10.dp, start = 12.dp, end = 12.dp)
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidPopoverFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 22.dp,
                    )
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpalText(
                    text = windowTitle,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                    maxLines = 1,
                )
                OpalButton(
                    label = "退出全屏",
                    compact = true,
                    onClick = {
                        expanded = false
                        onExitFullscreen()
                    },
                )
                OpalButton(
                    label = "显示桌面",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentCyan,
                    onClick = {
                        expanded = false
                        onReturnToDesktop()
                    },
                )
                OpalButton(
                    label = "控制中心",
                    compact = true,
                    filled = false,
                    onClick = {
                        expanded = false
                        onOpenControlCenter()
                    },
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.ChipFill)
                        .clickable { expanded = false }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    OpalText(text = "隐藏", fontSize = 11.sp, color = OpalPalette.TextSecondary)
                }
            }
        }
    }
}
