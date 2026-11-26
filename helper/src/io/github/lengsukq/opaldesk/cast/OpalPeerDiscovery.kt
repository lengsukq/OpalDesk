package io.github.lengsukq.opaldesk.cast

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class DiscoveredOpalPeer(
    val serviceName: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val shortFingerprint: String = "",
)

/**
 * Standard AOSP [NsdManager] mDNS advertiser & browser for `_opaldesk._tcp.` enabling
 * automatic zero-config discovery between Android <-> Android and Android <-> Mac OpalDesk nodes.
 */
object OpalPeerDiscovery {
    private const val TAG = "OpalPeerNsd"
    const val SERVICE_TYPE = "_opaldesk._tcp."
    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * On Android 17 (API 37+), calling NsdManager.discoverServices without ACCESS_LOCAL_NETWORK
     * launches the system NsdPickerActivity modal on every call. Guard all NSD calls behind this check.
     */
    fun hasLocalNetworkPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        return try {
            ctx.checkSelfPermission(PERMISSION_ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { cmd -> mainHandler.post(cmd) }
    private val nsdExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalPeerNsd").apply { isDaemon = true }
    }
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val activeInfoCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
    private val peersByKey = ConcurrentHashMap<String, DiscoveredOpalPeer>()
    private val _discoveredPeersFlow = MutableStateFlow<List<DiscoveredOpalPeer>>(emptyList())
    val discoveredPeersFlow: StateFlow<List<DiscoveredOpalPeer>> = _discoveredPeersFlow.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var resolving = false

    @Volatile
    private var registeredPort: Int = -1

    @Volatile
    private var registeredFp: String = ""

    @Volatile
    private var localLanIp: String = ""

    fun syncAdvertise(ctx: Context, enabled: Boolean, port: Int, shortFingerprint: String) {
        val appCtx = ctx.applicationContext
        val shouldAdvertise = enabled && port > 0 && !OpalPeerCastMutex.isReceivingActive && hasLocalNetworkPermission(appCtx)
        nsdExec.execute {
            if (!shouldAdvertise) {
                stopAdvertiseOnNsdThread()
                return@execute
            }
            if (regListener != null && registeredPort == port && registeredFp == shortFingerprint) {
                return@execute
            }
            stopAdvertiseOnNsdThread()
            val mgr = (appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return@execute
            nsdManager = mgr
            val info = NsdServiceInfo().apply {
                serviceName = "OpalDesk-${Build.MODEL}".take(48)
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("device", Build.MODEL.orEmpty())
                if (shortFingerprint.isNotBlank()) setAttribute("fp", shortFingerprint)
                setAttribute("v", "1")
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Advertised ${serviceInfo.serviceName} on :$port")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD advertise failed code=$errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
            try {
                mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                regListener = listener
                registeredPort = port
                registeredFp = shortFingerprint
            } catch (t: Throwable) {
                Log.w(TAG, "registerService failed: ${t.message}")
            }
        }
    }

    fun stopAdvertise() {
        nsdExec.execute { stopAdvertiseOnNsdThread() }
    }

    private fun stopAdvertiseOnNsdThread() {
        val mgr = nsdManager
        val listener = regListener
        regListener = null
        registeredPort = -1
        registeredFp = ""
        if (mgr != null && listener != null) {
            try {
                mgr.unregisterService(listener)
            } catch (_: Throwable) {
            }
        }
    }

    fun startBrowsing(ctx: Context, selfLanIp: String) {
        val appCtx = ctx.applicationContext
        val cleanIp = selfLanIp.trim()
        val hasPerm = hasLocalNetworkPermission(appCtx)
        nsdExec.execute {
            localLanIp = cleanIp
            if (!hasPerm) {
                stopBrowsingOnNsdThread()
                return@execute
            }
            if (discListener != null) return@execute
            val mgr = (appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return@execute
            nsdManager = mgr
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
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
                    val removed = peersByKey.entries.removeIf { it.value.serviceName == service.serviceName }
                    if (removed) publishSorted()
                }
            }
            try {
                mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                discListener = listener
            } catch (t: Throwable) {
                Log.w(TAG, "discoverServices failed: ${t.message}")
            }
        }
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
                try {
                    mgr.stopServiceDiscovery(listener)
                } catch (_: Throwable) {
                }
            }
        }
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
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
            activeInfoCallbacks.remove(key)?.let { prev ->
                try { mgr.unregisterServiceInfoCallback(prev) } catch (_: Throwable) {}
            }
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(TAG, "registerServiceInfoCallback failed key=$key code=$errorCode")
                    synchronized(activeInfoCallbacks) { activeInfoCallbacks.remove(key) }
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    handleResolvedPeer(serviceInfo)
                }

                override fun onServiceLost() {
                    val removed = peersByKey.entries.removeIf { it.value.serviceName == svcName }
                    if (removed) publishSorted()
                }

                override fun onServiceInfoCallbackUnregistered() {
                    synchronized(activeInfoCallbacks) { activeInfoCallbacks.remove(key) }
                }
            }
            try {
                activeInfoCallbacks[key] = cb
                mgr.registerServiceInfoCallback(service, mainExecutor, cb)
            } catch (t: Throwable) {
                activeInfoCallbacks.remove(key)
                Log.w(TAG, "registerServiceInfoCallback threw for $key: ${t.message}")
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
            try { mgr.unregisterServiceInfoCallback(cb) } catch (_: Throwable) {}
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
    private fun resolvePreferredPeerAddress(resolved: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= 34) {
            val addrs = resolved.hostAddresses
            val v4 = addrs.firstOrNull { it is Inet4Address }
            if (v4 != null) return v4
            if (addrs.isNotEmpty()) return addrs.first()
        }
        return resolved.host
    }

    private fun handleResolvedPeer(resolved: NsdServiceInfo) {
        val hostAddr = resolvePreferredPeerAddress(resolved)
        val ip = (hostAddr as? Inet4Address)?.hostAddress
            ?: hostAddr?.hostAddress.orEmpty().substringBefore('%')
        val port = resolved.port
        if (ip.isBlank() || port <= 0) return
        if (OpalPeerCastMutex.isSelfAddress(ip, localLanIp)) return
        val attrs = resolved.attributes
        val devName = attrs?.get("device")?.let { String(it, StandardCharsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
            ?: resolved.serviceName.removePrefix("OpalDesk-").ifBlank { resolved.serviceName }
        val fp = attrs?.get("fp")?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
        peersByKey["$ip:$port"] = DiscoveredOpalPeer(
            serviceName = resolved.serviceName,
            displayName = devName,
            host = ip,
            port = port,
            shortFingerprint = fp,
        )
        publishSorted()
    }

    private fun publishSorted() {
        _discoveredPeersFlow.value = peersByKey.values.sortedBy { it.displayName }
    }
}
