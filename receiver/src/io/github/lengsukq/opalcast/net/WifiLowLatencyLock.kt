package io.github.lengsukq.opalcast.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Holds Android's low-latency Wi-Fi lock (API 29+ `WIFI_MODE_FULL_LOW_LATENCY`,
 * API 21–28 `WIFI_MODE_FULL_HIGH_PERF`) during active wireless OpalCast sessions.
 */
internal class WifiLowLatencyLock(context: Context, private val tag: String) {
    private val appCtx = context.applicationContext
    private val lock = Any()
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        synchronized(lock) {
            if (wifiLock?.isHeld == true) return
            val wm = appCtx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            try {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                val next = wifiLock ?: wm.createWifiLock(mode, tag).apply {
                    setReferenceCounted(false)
                }
                wifiLock = next
                if (!next.isHeld) next.acquire()
            } catch (_: Throwable) {
            }
        }
    }

    fun release() {
        synchronized(lock) {
            val cur = wifiLock ?: return
            try {
                if (cur.isHeld) cur.release()
            } catch (_: Throwable) {
            }
        }
    }
}
