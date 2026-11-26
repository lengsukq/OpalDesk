package io.github.lengsukq.opaldesk.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Holds Android's low-latency Wi-Fi lock ([WifiManager.WIFI_MODE_FULL_LOW_LATENCY] on API 29+,
 * [WifiManager.WIFI_MODE_FULL_HIGH_PERF] on older versions) during active wireless OpalCast
 * sessions so the Wi-Fi radio does not enter 100–200 ms DTIM beacon sleep between frames.
 */
internal class WifiLowLatencyLock(context: Context, private val tag: String) {
    private val appCtx = context.applicationContext
    private val lock = Any()
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        synchronized(lock) {
            if (wifiLock?.isHeld == true) return
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
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
                if (!next.isHeld) {
                    next.acquire()
                }
            } catch (_: Throwable) {
                // Gracefully degrade if a restricted ROM denies WifiLock.
            }
        }
    }

    fun release() {
        synchronized(lock) {
            val cur = wifiLock
            if (cur != null) {
                try {
                    if (cur.isHeld) cur.release()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
