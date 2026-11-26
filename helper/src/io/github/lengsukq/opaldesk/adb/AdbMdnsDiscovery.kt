package io.github.lengsukq.opaldesk.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.lengsukq.opaldesk.AdbBootstrap
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import java.net.Inet4Address
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Serialized NsdManager mDNS discovery for Android Wireless Debugging pairing & connect services,
 * holding a non-reference-counted MulticastLock only while actively scanning.
 */
internal object AdbMdnsDiscovery {
    private const val TAG = "ExtAdbMdns"
    private const val SERVICE_PAIRING = "_adb-tls-pairing._tcp."
    private const val SERVICE_CONNECT = "_adb-tls-connect._tcp."

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { cmd -> mainHandler.post(cmd) }
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val activeInfoCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
    private val state = AdbBootstrap.DiscoveryState()

    private var nsdManager: NsdManager? = null
    private var pairDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: AdbBootstrap.DiscoveryListener? = null
    private var activePairSvcName = ""
    private var activeConnSvcName = ""
    private var resolving = false

    val isActive: Boolean
        @Synchronized get() = nsdManager != null

    @Synchronized
    fun getState(): AdbBootstrap.DiscoveryState = AdbBootstrap.DiscoveryState().apply {
        pairingHost = state.pairingHost
        pairingPort = state.pairingPort
        connectHost = state.connectHost
        connectPort = state.connectPort
    }

    @Synchronized
    fun clearListener() {
        listener = null
    }

    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (nsdManager == null) start(ctx, listener)
    }

    @Synchronized
    fun start(ctx: Context, nextListener: AdbBootstrap.DiscoveryListener?) {
        listener = nextListener
        val appCtx = ctx.applicationContext
        if (!OpalPeerDiscovery.hasLocalNetworkPermission(appCtx)) {
            OpalLogStore.i(TAG, "Skipping NSD wireless-ADB scan until ACCESS_LOCAL_NETWORK is granted")
            return
        }
        if (nsdManager != null) {
            notifyListenerLocked()
            return
        }
        try {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && multicastLock == null) {
                multicastLock = wm.createMulticastLock("opaldesk_mdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "MulticastLock unavailable", t)
        }

        try {
            val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = nsd
            val pairDisc = createDiscoveryListener(isPairing = true)
            val connDisc = createDiscoveryListener(isPairing = false)
            pairDiscoveryListener = pairDisc
            connDiscoveryListener = connDisc
            nsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairDisc)
            nsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connDisc)
        } catch (t: Throwable) {
            OpalLogStore.e(TAG, "startDiscovery failed", t)
        }
    }

    @Synchronized
    fun stop() {
        listener = null
        val nsd = nsdManager
        if (nsd != null) {
            unregisterAllServiceInfoCallbacks(nsd)
            pairDiscoveryListener?.let { stopDiscoverySafely(nsd, it) }
            connDiscoveryListener?.let { stopDiscoverySafely(nsd, it) }
        }
        pairDiscoveryListener = null
        connDiscoveryListener = null
        nsdManager = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        multicastLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (t: Throwable) {
                Log.d(TAG, "Failed to release MulticastLock: ${t.message}")
            }
            multicastLock = null
        }
    }

    private fun stopDiscoverySafely(nsd: NsdManager, disc: NsdManager.DiscoveryListener) {
        try {
            nsd.stopServiceDiscovery(disc)
        } catch (t: Throwable) {
            Log.d(TAG, "stopServiceDiscovery ignored: ${t.message}")
        }
    }

    private fun createDiscoveryListener(isPairing: Boolean): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                OpalLogStore.w(TAG, "onStartDiscoveryFailed type=$serviceType code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo != null) enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                val lostName = serviceInfo?.serviceName.orEmpty()
                synchronized(this@AdbMdnsDiscovery) {
                    if (isPairing) {
                        if (lostName.isEmpty() || activePairSvcName.isEmpty() || lostName == activePairSvcName) {
                            state.pairingPort = 0
                            activePairSvcName = ""
                            notifyListenerLocked()
                        }
                    } else if (lostName.isEmpty() || activeConnSvcName.isEmpty() || lostName == activeConnSvcName) {
                        state.connectPort = 0
                        activeConnSvcName = ""
                        notifyListenerLocked()
                    }
                }
            }
        }

    private fun enqueueResolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            registerServiceInfoCallbackApi34(info)
            return
        }
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            pumpResolveLocked()
        }
    }

    private fun registerServiceInfoCallbackApi34(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        val nsd = nsdManager ?: return
        val key = "${info.serviceType.orEmpty()}|${info.serviceName.orEmpty()}"
        synchronized(activeInfoCallbacks) {
            activeInfoCallbacks.remove(key)?.let { prev ->
                try { nsd.unregisterServiceInfoCallback(prev) } catch (_: Throwable) {}
            }
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    OpalLogStore.w(TAG, "registerServiceInfoCallback failed key=$key code=$errorCode")
                    synchronized(activeInfoCallbacks) { activeInfoCallbacks.remove(key) }
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    handleResolved(serviceInfo)
                }

                override fun onServiceLost() {
                    val lostType = info.serviceType.orEmpty()
                    val lostName = info.serviceName.orEmpty()
                    synchronized(this@AdbMdnsDiscovery) {
                        if (lostType.contains("pairing") && (activePairSvcName.isEmpty() || lostName == activePairSvcName)) {
                            state.pairingPort = 0
                            activePairSvcName = ""
                            notifyListenerLocked()
                        } else if (lostType.contains("connect") && (activeConnSvcName.isEmpty() || lostName == activeConnSvcName)) {
                            state.connectPort = 0
                            activeConnSvcName = ""
                            notifyListenerLocked()
                        }
                    }
                }

                override fun onServiceInfoCallbackUnregistered() {
                    synchronized(activeInfoCallbacks) { activeInfoCallbacks.remove(key) }
                }
            }
            try {
                activeInfoCallbacks[key] = cb
                nsd.registerServiceInfoCallback(info, mainExecutor, cb)
            } catch (t: Throwable) {
                activeInfoCallbacks.remove(key)
                OpalLogStore.w(TAG, "registerServiceInfoCallback threw for $key", t)
            }
        }
    }

    private fun unregisterAllServiceInfoCallbacks(nsd: NsdManager) {
        if (Build.VERSION.SDK_INT < 34) return
        val callbacks = synchronized(activeInfoCallbacks) {
            val copy = activeInfoCallbacks.values.toList()
            activeInfoCallbacks.clear()
            copy
        }
        for (cb in callbacks) {
            try { nsd.unregisterServiceInfoCallback(cb) } catch (_: Throwable) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun pumpResolveLocked() {
        val nsd = nsdManager
        if (resolving || resolveQueue.isEmpty() || nsd == null) return
        val next = resolveQueue.pollFirst() ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    synchronized(resolveQueue) {
                        resolving = false
                        pumpResolveLocked()
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                    if (resolved != null) handleResolved(resolved)
                    synchronized(resolveQueue) {
                        resolving = false
                        pumpResolveLocked()
                    }
                }
            })
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "resolveService failed", t)
            resolving = false
            pumpResolveLocked()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePreferredLocalAddress(resolved: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= 34) {
            val addrs = resolved.hostAddresses
            val localV4 = addrs.firstOrNull { it is Inet4Address && AdbProcessRunner.isLocalDeviceAddress(it) }
            if (localV4 != null) return localV4
            val anyLocal = addrs.firstOrNull { AdbProcessRunner.isLocalDeviceAddress(it) }
            if (anyLocal != null) return anyLocal
        }
        return resolved.host
    }

    private fun handleResolved(resolved: NsdServiceInfo) {
        val type = resolved.serviceType.orEmpty()
        val addr = resolvePreferredLocalAddress(resolved)
        if (!AdbProcessRunner.isLocalDeviceAddress(addr)) return
        var host = addr?.hostAddress.orEmpty().substringBefore('%')
        if (host.contains(':') && !host.startsWith("[")) {
            // Prefer local IPv4 for adb connect/pair when link-local IPv6 scope was stripped
            host = AdbProcessRunner.getLocalIpv4()
        }
        val port = resolved.port
        if (port <= 0) return
        val svcName = resolved.serviceName.orEmpty()

        synchronized(this) {
            when {
                type.contains("pairing") -> {
                    state.pairingHost = host
                    state.pairingPort = port
                    activePairSvcName = svcName
                }
                type.contains("connect") -> {
                    state.connectHost = host
                    state.connectPort = port
                    activeConnSvcName = svcName
                }
            }
            notifyListenerLocked()
        }
    }

    private fun notifyListenerLocked() {
        val target = listener ?: return
        val snap = getState()
        mainHandler.post { target.onDiscoveryUpdated(snap) }
    }
}
