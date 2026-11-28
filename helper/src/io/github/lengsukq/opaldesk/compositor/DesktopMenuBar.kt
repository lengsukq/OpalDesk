package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
 * Bottom-right floating Liquid Glass Control Center trigger (Setting Gear Icon) replacing the
 * retired top status bar Control Center button without overlapping the Dock or desktop widgets.
 */
@Composable
internal fun DesktopFloatingSettingButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val accent = colors.accentBlue
    val fill = if (active) {
        Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (colors.isDark) 0.42f else 0.30f),
                accent.copy(alpha = if (colors.isDark) 0.24f else 0.16f),
            ),
        )
    } else {
        OpalPalette.LiquidDockFill
    }
    val rim = if (active) {
        Brush.linearGradient(listOf(accent.copy(alpha = 0.92f), colors.accentCyan.copy(alpha = 0.72f)))
    } else {
        OpalPalette.LiquidGlassRim
    }
    Box(
        modifier = modifier
            .size(44.dp)
            .liquidGlassSurface(
                shape = CircleShape,
                fill = fill,
                rim = rim,
                elevation = if (active) 24.dp else 18.dp,
                borderWidth = if (active) 1.35.dp else 1.dp,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val tint = if (active) accent else colors.textPrimary
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val sw = 1.7.dp.toPx()
            val innerR = size.minDimension * 0.17f
            val bodyR = size.minDimension * 0.33f
            val toothOut = size.minDimension * 0.46f
            drawCircle(color = tint, radius = innerR, center = Offset(cx, cy), style = Stroke(sw))
            drawCircle(color = tint, radius = bodyR, center = Offset(cx, cy), style = Stroke(sw))
            for (i in 0 until 8) {
                val rad = (i * 45.0) * PI / 180.0
                val sx = cx + (bodyR * cos(rad)).toFloat()
                val sy = cy + (bodyR * sin(rad)).toFloat()
                val ex = cx + (toothOut * cos(rad)).toFloat()
                val ey = cy + (toothOut * sin(rad)).toFloat()
                drawLine(color = tint, start = Offset(sx, sy), end = Offset(ex, ey), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}
