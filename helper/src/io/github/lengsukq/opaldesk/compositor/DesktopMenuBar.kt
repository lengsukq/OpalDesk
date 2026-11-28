package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText

data class TrayNetworkState(
    val wifiEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val ethernetConnected: Boolean = false,
)

/**
 * Pure JVM-testable resolution of Wi-Fi vs Ethernet status bar visibility.
 * When Wi-Fi radio is explicitly OFF (`wifiRadioEnabled == false`), [wifiConnected] is ALWAYS
 * `false` so the menu bar never displays a stale or Ethernet-borrowed Wi-Fi icon.
 */
fun resolveTrayNetworkState(
    wifiRadioEnabled: Boolean?,
    hasWifiTransport: Boolean,
    hasEthernetTransport: Boolean,
): TrayNetworkState {
    val wifiOn = when (wifiRadioEnabled) {
        true -> true
        false -> false
        null -> hasWifiTransport
    }
    val wifiConn = wifiOn && hasWifiTransport
    return TrayNetworkState(
        wifiEnabled = wifiOn,
        wifiConnected = wifiConn,
        ethernetConnected = hasEthernetTransport,
    )
}

data class SystemTrayInfo(
    val clockText: String = "",
    val dateText: String = "",
    val batteryPct: Int = 100,
    val charging: Boolean = false,
    val wifiEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val ethernetConnected: Boolean = false,
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

    val wifiRadioEnabled: Boolean? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.isWifiEnabled
    } catch (_: Throwable) {
        null
    }

    var hasWifiTransport = false
    var hasEthernetTransport = false
    try {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        hasWifiTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        hasEthernetTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    } catch (_: Throwable) {
    }

    val net = resolveTrayNetworkState(
        wifiRadioEnabled = wifiRadioEnabled,
        hasWifiTransport = hasWifiTransport,
        hasEthernetTransport = hasEthernetTransport,
    )

    return SystemTrayInfo(
        clockText = clockText,
        dateText = dateText,
        batteryPct = pct,
        charging = charging,
        wifiEnabled = net.wifiEnabled,
        wifiConnected = net.wifiConnected,
        ethernetConnected = net.ethernetConnected,
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
    showHostScreenToggle: Boolean = false,
    isCompactWidth: Boolean,
    showControlCenter: Boolean,
    showDpiPopover: Boolean = false,
    notificationCount: Int = 0,
    showNotificationCenter: Boolean = false,
    doNotDisturb: Boolean = false,
    showPhoneTrackpadButton: Boolean = false,
    phoneTrackpadVisible: Boolean = false,
    showDockFallbackActions: Boolean = false,
    showAppDrawer: Boolean = false,
    showExpose: Boolean = false,
    showSpotlight: Boolean = false,
    onToggleAppDrawer: () -> Unit = {},
    onToggleExpose: () -> Unit = {},
    onToggleSpotlight: (() -> Unit)? = null,
    onTogglePhoneTrackpad: () -> Unit = {},
    onToggleHostScreenPower: (Boolean) -> Unit = {},
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

        if (showDockFallbackActions) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (showAppDrawer) OpalPalette.AccentBlue.copy(alpha = 0.22f) else OpalPalette.ChipFill)
                        .clickable(onClick = onToggleAppDrawer)
                        .padding(horizontal = if (isCompactWidth) 6.dp else 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OpalGlyphIcon(
                        glyph = ChromeGlyph.APPS_GRID,
                        tint = if (showAppDrawer) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                        size = 11.dp,
                    )
                    if (!isCompactWidth) {
                        OpalText(
                            text = stringResource(R.string.taskbar_apps),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (showAppDrawer) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (showExpose) OpalPalette.AccentBlue.copy(alpha = 0.22f) else OpalPalette.ChipFill)
                        .clickable(onClick = onToggleExpose)
                        .padding(horizontal = if (isCompactWidth) 6.dp else 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OpalGlyphIcon(
                        glyph = ChromeGlyph.EXPOSE,
                        tint = if (showExpose) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                        size = 11.dp,
                    )
                    if (!isCompactWidth) {
                        OpalText(
                            text = stringResource(R.string.taskbar_recents),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (showExpose) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(if (isCompactWidth) 6.dp else 10.dp))
        }

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
                        text = stringResource(R.string.taskbar_fullscreen),
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
                    text = stringResource(if (isExternalMonitor) R.string.taskbar_ext_workstation else R.string.taskbar_workstation),
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        if (onToggleSpotlight != null && (isCompactWidth || showDockFallbackActions)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (showSpotlight) OpalPalette.ChipActiveFill else Color.Transparent)
                    .clickable(onClick = onToggleSpotlight)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                OpalGlyphIcon(
                    glyph = ChromeGlyph.SEARCH,
                    tint = if (showSpotlight) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                    size = 13.dp,
                )
            }
            Spacer(Modifier.width(4.dp))
        }

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
                    text = stringResource(R.string.taskbar_dnd_short),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentAmber,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(4.dp))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(if (showControlCenter) OpalPalette.ChipActiveFill else Color.Transparent)
                .clickable(onClick = onToggleControlCenter)
                .padding(horizontal = if (isCompactWidth) 6.dp else 9.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCompactWidth) 6.dp else 9.dp),
        ) {
            if (tray.wifiConnected) {
                OpalGlyphIcon(
                    glyph = ChromeGlyph.WIFI,
                    tint = OpalPalette.TextPrimary,
                    size = 13.dp,
                )
            }
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
