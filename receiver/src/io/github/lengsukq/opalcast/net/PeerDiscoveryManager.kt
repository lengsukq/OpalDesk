package io.github.lengsukq.opalcast.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class DiscoveredHostPeer(
    val serviceName: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val shortFingerprint: String = "",
)

/**
 * Android 5.0–17 (API 21–37) `_opaldesk._tcp.` mDNS peer browser with `WifiManager.MulticastLock`
 * for older Wi-Fi stacks, `ServiceInfoCallback` on API 34+, and `ACCESS_LOCAL_NETWORK` guard on API 37+.
 */
object PeerDiscoveryManager {
    private const val TAG = "OpalCastNsd"
    const val SERVICE_TYPE = "_opaldesk._tcp."

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { cmd -> mainHandler.post(cmd) }
    private val nsdExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalCastNsd").apply { isDaemon = true }
    }
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val activeInfoCallbacks = HashMap<String, Any>()
    private val peersByKey = ConcurrentHashMap<String, DiscoveredHostPeer>()
    private val listeners = CopyOnWriteArrayList<(List<DiscoveredHostPeer>) -> Unit>()

    private var nsdManager: NsdManager? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolving = false

    @Volatile
    var currentPeers: List<DiscoveredHostPeer> = emptyList()
        private set

    fun addListener(listener: (List<DiscoveredHostPeer>) -> Unit) {
        listeners.addIfAbsent(listener)
        val snapshot = currentPeers
        mainHandler.post { listener(snapshot) }
    }

    fun removeListener(listener: (List<DiscoveredHostPeer>) -> Unit) {
        listeners.remove(listener)
    }

    fun startBrowsing(ctx: Context) {
        val appCtx = ctx.applicationContext
        val hasPerm = AndroidApiCompat.hasLocalNetworkPermission(appCtx)
        nsdExec.execute {
            if (!hasPerm) {
                stopBrowsingOnNsdThread()
                return@execute
            }
            if (discListener != null) return@execute
            acquireMulticastLock(appCtx)
            val mgr = (appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return@execute
            nsdManager = mgr
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "NSD start failed code=$errorCode")
                    nsdExec.execute { discListener = null }
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    nsdExec.execute { discListener = null }
                }
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (!service.serviceType.contains("_opaldesk")) return
                    resolvePeer(mgr, service)
                }
                override fun onServiceLost(service: NsdServiceInfo) {
                    if (removePeerByServiceName(service.serviceName)) publishSorted()
                }
            }
            try {
                mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                discListener = listener
            } catch (t: Throwable) {
                Log.w(TAG, "discoverServices failed", t)
            }
        }
    }

    fun restartBrowsing(ctx: Context) {
        val appCtx = ctx.applicationContext
        nsdExec.execute {
            stopBrowsingOnNsdThread()
            peersByKey.clear()
            publishSorted()
        }
        startBrowsing(appCtx)
    }

    fun stopBrowsing() {
        nsdExec.execute { stopBrowsingOnNsdThread() }
    }

    private fun stopBrowsingOnNsdThread() {
        val mgr = nsdManager
        val listener = discListener
        discListener = null
        if (mgr != null) {
            unregisterAllInfoCallbacks(mgr)
            if (listener != null) {
                try { mgr.stopServiceDiscovery(listener) } catch (_: Throwable) {}
            }
        }
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        releaseMulticastLock()
    }

    private fun acquireMulticastLock(appCtx: Context) {
        try {
            val wm = appCtx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = multicastLock ?: wm.createMulticastLock("OpalCast:mDNS").apply {
                setReferenceCounted(false)
            }
            multicastLock = lock
            if (!lock.isHeld) lock.acquire()
        } catch (_: Throwable) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Throwable) {
        }
    }

    private fun removePeerByServiceName(svcName: String?): Boolean {
        if (svcName.isNullOrEmpty()) return false
        var removed = false
        val iter = peersByKey.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.serviceName == svcName) {
                iter.remove()
                removed = true
            }
        }
        return removed
    }

    private fun resolvePeer(mgr: NsdManager, service: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            registerPeerInfoCallbackApi34(mgr, service)
            return
        }
        synchronized(resolveQueue) {
            resolveQueue.addLast(service)
            pumpResolveQueueLocked(mgr)
        }
    }

    private fun registerPeerInfoCallbackApi34(mgr: NsdManager, service: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        val svcName = service.serviceName.orEmpty()
        val key = "${service.serviceType.orEmpty()}|$svcName"
        synchronized(activeInfoCallbacks) {
            (activeInfoCallbacks.remove(key) as? NsdManager.ServiceInfoCallback)?.let { prev ->
                try { mgr.unregisterServiceInfoCallback(prev) } catch (_: Throwable) {}
            }
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    synchronized(activeInfoCallbacks) { activeInfoCallbacks.remove(key) }
                }
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    handleResolvedPeer(serviceInfo)
                }
                override fun onServiceLost() {
                    if (removePeerByServiceName(svcName)) publishSorted()
                }
                override fun onServiceInfoCallbackUnregistered() {
                    synchronized(activeInfoCallbacks) { activeInfoCallbacks.remove(key) }
                }
            }
            try {
                activeInfoCallbacks[key] = cb
                mgr.registerServiceInfoCallback(service, mainExecutor, cb)
            } catch (_: Throwable) {
                activeInfoCallbacks.remove(key)
            }
        }
    }

    private fun unregisterAllInfoCallbacks(mgr: NsdManager) {
        if (Build.VERSION.SDK_INT < 34) return
        val callbacks = synchronized(activeInfoCallbacks) {
            val copy = activeInfoCallbacks.values.toList()
            activeInfoCallbacks.clear()
            copy
        }
        for (cb in callbacks) {
            if (cb is NsdManager.ServiceInfoCallback) {
                try { mgr.unregisterServiceInfoCallback(cb) } catch (_: Throwable) {}
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pumpResolveQueueLocked(mgr: NsdManager) {
        if (resolving || resolveQueue.isEmpty()) return
        val next = resolveQueue.pollFirst() ?: return
        resolving = true
        try {
            mgr.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(resolveQueue) {
                            resolving = false
                            pumpResolveQueueLocked(mgr)
                        }
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        handleResolvedPeer(resolved)
                        synchronized(resolveQueue) {
                            resolving = false
                            pumpResolveQueueLocked(mgr)
                        }
                    }
                },
            )
        } catch (_: Throwable) {
            resolving = false
            pumpResolveQueueLocked(mgr)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePreferredAddress(resolved: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= 34) {
            val addrs = resolved.hostAddresses
            addrs.firstOrNull { it is Inet4Address }?.let { return it }
            if (addrs.isNotEmpty()) return addrs.first()
        }
        return resolved.host
    }

    private fun handleResolvedPeer(resolved: NsdServiceInfo) {
        val hostAddr = resolvePreferredAddress(resolved)
        val ip = (hostAddr as? Inet4Address)?.hostAddress
            ?: hostAddr?.hostAddress.orEmpty().substringBefore('%')
        val port = resolved.port
        if (ip.isBlank() || port <= 0) return
        val attrs = resolved.attributes
        val devName = attrs?.get("device")?.let { String(it, StandardCharsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
            ?: resolved.serviceName.removePrefix("OpalDesk-").ifBlank { resolved.serviceName }
        val fp = attrs?.get("fp")?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
        peersByKey["$ip:$port"] = DiscoveredHostPeer(
            serviceName = resolved.serviceName,
            displayName = devName,
            host = ip,
            port = port,
            shortFingerprint = fp,
        )
        publishSorted()
    }

    private fun publishSorted() {
        val sorted = peersByKey.values.sortedBy { it.displayName }
        currentPeers = sorted
        mainHandler.post {
            for (l in listeners) l(sorted)
        }
    }
}
