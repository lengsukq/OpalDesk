package io.github.lengsukq.opaldesk.compositor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.ExternalTrackpadPolicy
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Observes clock, battery, and active Wi-Fi/Ethernet network changes reactively for the top
 * Menu Bar and Control Center without polling tightly or leaking system receivers.
 */
@Composable
internal fun rememberWorkspaceSystemTrayInfo(ctx: Context): SystemTrayInfo {
    val scope = rememberCoroutineScope()
    var trayInfo by remember { mutableStateOf(SystemTrayInfo()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("M/d E", Locale.getDefault()) }

    fun refreshNow() {
        val now = Date()
        trayInfo = querySystemTrayStatus(ctx, timeFmt.format(now), dateFmt.format(now))
    }

    LaunchedEffect(ctx) {
        while (true) {
            refreshNow()
            delay(15_000L)
        }
    }

    DisposableEffect(ctx) {
        val appCtx = ctx.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            private fun scheduleRefresh(recheckDelayMs: Long = 0L) {
                scope.launch {
                    refreshNow()
                    if (recheckDelayMs > 0L) {
                        delay(recheckDelayMs)
                        refreshNow()
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
                    refreshNow()
                    if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                        delay(250L)
                        refreshNow()
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

    return trayInfo
}

/**
 * Thermal-saving auto-blackout effect: turns off the phone's OLED panel 3s after entering
 * external monitor or remote cast DeX mode, firing once per session and restoring power on exit.
 */
@Composable
internal fun WorkspaceAutoBlackoutEffect(
    ctx: Context,
    isExternalMonitor: Boolean,
    autoBlackoutOnCast: Boolean,
    daemonConnected: Boolean,
    hostScreenOff: Boolean,
    phoneTrackpadVisible: Boolean,
    isPresentationMode: Boolean,
) {
    var triggeredAutoBlackout by remember { mutableStateOf(false) }
    var autoBlackoutAttemptedForSession by remember(isExternalMonitor, autoBlackoutOnCast) { mutableStateOf(false) }

    LaunchedEffect(
        isExternalMonitor,
        autoBlackoutOnCast,
        daemonConnected,
        phoneTrackpadVisible,
        isPresentationMode,
    ) {
        if (ExternalTrackpadPolicy.shouldTriggerAutoBlackout(
                isExternalMonitor = isExternalMonitor,
                autoBlackoutEnabled = autoBlackoutOnCast,
                daemonConnected = daemonConnected,
                hostScreenOff = hostScreenOff,
                phoneTrackpadVisible = phoneTrackpadVisible,
                isPresentationMode = isPresentationMode,
                alreadyTriggeredForSession = autoBlackoutAttemptedForSession,
            )
        ) {
            delay(3_000L)
            if (!BridgeClient.statusFlow.value.hostScreenOff) {
                autoBlackoutAttemptedForSession = true
                triggeredAutoBlackout = true
                BridgeClient.setHostScreenPowerAsync(false) { ok ->
                    if (ok) {
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.workspace_auto_blackout_toast),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (triggeredAutoBlackout) {
                BridgeClient.setHostScreenPowerAsync(true)
            }
        }
    }
}

/**
 * Resolves a standard Android Settings intent action and launches it inside a DeX compositor
 * window when connected, falling back to a direct host launch otherwise.
 */
internal fun launchSystemSettingsInDex(
    ctx: Context,
    scope: CoroutineScope,
    daemonConnected: Boolean,
    action: String,
) {
    fun openFallbackDirect() {
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Throwable) {
            }
        }
    }
    if (!daemonConnected) {
        openFallbackDirect()
        return
    }
    val pm = ctx.packageManager
    val resolved = listOf(action, Settings.ACTION_SETTINGS)
        .firstNotNullOfOrNull { candidateAction ->
            runCatching {
                val ri = pm.resolveActivity(Intent(candidateAction), PackageManager.MATCH_DEFAULT_ONLY)
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
