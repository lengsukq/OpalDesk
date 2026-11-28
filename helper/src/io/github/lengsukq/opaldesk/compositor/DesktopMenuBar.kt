package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager

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
