package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Opt-in Beta receiver-side detector for Android tablets connected to an Android phone over
 * USB Tethering (USB Host CDC-NCM / RNDIS Ethernet link).
 *
 * Zero background overhead when disabled: only registers [ConnectivityManager.NetworkCallback]
 * and probes candidate Ethernet gateways while the user explicitly enabled the Beta toggle.
 */
object UsbTetherPeerDetector {
    private const val TAG = "OpalUsbTetherDet"
    private const val PROBE_TIMEOUT_MS = 1_500

    private val probeExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalUsbTetherProbe").apply { isDaemon = true }
    }

    private val _peersFlow = MutableStateFlow<List<UsbTetherGatewayPolicy.DiscoveredUsbTetherPeer>>(emptyList())
    val peersFlow: StateFlow<List<UsbTetherGatewayPolicy.DiscoveredUsbTetherPeer>> = _peersFlow.asStateFlow()

    private val _scanningFlow = MutableStateFlow(false)
    val scanningFlow: StateFlow<Boolean> = _scanningFlow.asStateFlow()

    private val networkByHost = ConcurrentHashMap<String, Network>()

    @Volatile
    private var active = false

    @Volatile
    private var appCtx: Context? = null

    private var cmRef: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    fun start(ctx: Context) {
        val context = ctx.applicationContext
        appCtx = context
        synchronized(this) {
            if (active) {
                triggerScanAsync()
                return
            }
            active = true
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cmRef = cm
            if (cm != null) {
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = triggerScanAsync()
                    override fun onLost(network: Network) = triggerScanAsync()
                    override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                        triggerScanAsync()
                }
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
                try {
                    cm.registerNetworkCallback(req, cb)
                    netCallback = cb
                } catch (t: Throwable) {
                    OpalLogStore.w(TAG, "registerNetworkCallback(TRANSPORT_ETHERNET) failed: ${t.message}")
                }
            }
        }
        triggerScanAsync()
    }

    fun stop() {
        synchronized(this) {
            active = false
            val cm = cmRef
            val cb = netCallback
            netCallback = null
            if (cm != null && cb != null) {
                try {
                    cm.unregisterNetworkCallback(cb)
                } catch (_: Throwable) {
                }
            }
        }
        networkByHost.clear()
        _peersFlow.value = emptyList()
        _scanningFlow.value = false
    }

    fun triggerScanAsync() {
        if (!active) return
        probeExec.execute {
            if (!active) return@execute
            scanEthernetGatewaysNow()
        }
    }

    fun resolveBoundNetworkForHost(host: String?): Network? {
        val key = host?.trim().orEmpty()
        if (key.isEmpty()) return null
        return networkByHost[key]
    }

    private fun scanEthernetGatewaysNow() {
        val cm = cmRef ?: (appCtx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: return
        _scanningFlow.value = true
        try {
            val candidates = collectEthernetGatewayCandidates(cm)
            val found = ArrayList<UsbTetherGatewayPolicy.DiscoveredUsbTetherPeer>()
            val freshBindings = HashMap<String, Network>()

            for ((network, candidate) in candidates) {
                if (!active) break
                val peer = probeCandidate(network, candidate)
                if (peer != null) {
                    found.add(peer)
                    freshBindings[peer.host] = network
                }
            }

            if (active) {
                networkByHost.clear()
                networkByHost.putAll(freshBindings)
                _peersFlow.value = found
            }
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "USB tethering gateway scan error: ${t.message}")
        } finally {
            _scanningFlow.value = false
        }
    }

    @Suppress("DEPRECATION")
    private fun collectEthernetGatewayCandidates(
        cm: ConnectivityManager,
    ): List<Pair<Network, UsbTetherGatewayPolicy.UsbTetherCandidate>> {
        val out = ArrayList<Pair<Network, UsbTetherGatewayPolicy.UsbTetherCandidate>>()
        val seenGateways = HashSet<String>()
        val networks = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        for (network in networks) {
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
            val ifaceName = lp.interfaceName.orEmpty()
            val localIp = lp.linkAddresses
                .mapNotNull { it.address as? Inet4Address }
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
                .orEmpty()

            val gatewayIp = lp.routes
                .asSequence()
                .filter { it.hasGateway() }
                .sortedByDescending { it.isDefaultRoute }
                .mapNotNull { it.gateway as? Inet4Address }
                .mapNotNull { it.hostAddress }
                .firstOrNull { UsbTetherGatewayPolicy.isLikelyUsbTetherRoute(ifaceName, it) }
                ?: continue

            if (gatewayIp == localIp || !seenGateways.add(gatewayIp)) continue
            out.add(network to UsbTetherGatewayPolicy.UsbTetherCandidate(ifaceName, gatewayIp, localIp))
        }
        return out
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun probeCandidate(
        network: Network,
        candidate: UsbTetherGatewayPolicy.UsbTetherCandidate,
    ): UsbTetherGatewayPolicy.DiscoveredUsbTetherPeer? {
        val port = UsbTetherGatewayPolicy.DEFAULT_CAST_PORT
        var ssl: SSLSocket? = null
        return try {
            val trustAll = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                },
            )
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, trustAll, SecureRandom())

            val raw = network.socketFactory.createSocket()
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(candidate.gatewayIp, port), PROBE_TIMEOUT_MS)
            raw.soTimeout = PROBE_TIMEOUT_MS

            ssl = ctx.socketFactory.createSocket(raw, candidate.gatewayIp, port, true) as SSLSocket
            ssl.soTimeout = PROBE_TIMEOUT_MS
            ssl.startHandshake()

            val leafCert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
            val fullFp = if (leafCert != null) sha256Hex(leafCert.encoded) else ""

            val req = "GET /health HTTP/1.1\r\nHost: ${candidate.gatewayIp}:$port\r\nConnection: close\r\n\r\n"
            ssl.outputStream.write(req.toByteArray(StandardCharsets.US_ASCII))
            ssl.outputStream.flush()

            val reader = BufferedReader(InputStreamReader(ssl.inputStream, StandardCharsets.UTF_8))
            val statusLine = reader.readLine().orEmpty()
            if (!statusLine.contains("200")) return null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
            }
            val bodyBuilder = StringBuilder()
            while (reader.readLine().also { line = it } != null && bodyBuilder.length < 4096) {
                bodyBuilder.append(line)
            }
            val health = UsbTetherGatewayPolicy.parseCastHealthJson(bodyBuilder.toString()) ?: return null
            val shortFp = UsbTetherGatewayPolicy.formatShortFingerprint(fullFp)
                .ifEmpty { UsbTetherGatewayPolicy.formatShortFingerprint(health.certShortFp) }

            UsbTetherGatewayPolicy.DiscoveredUsbTetherPeer(
                displayName = health.deviceName,
                host = candidate.gatewayIp,
                port = port,
                interfaceName = candidate.interfaceName.ifBlank { "usb" },
                shortFingerprint = shortFp,
                fullFingerprint = fullFp,
            )
        } catch (_: Throwable) {
            null
        } finally {
            try { ssl?.close() } catch (_: Throwable) {}
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
