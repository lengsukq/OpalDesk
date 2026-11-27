package io.github.lengsukq.opalcast.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.policy.UsbTetherPolicy
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Opt-in Beta USB Tethering (CDC-NCM / RNDIS Ethernet) gateway detector, API 21–37 safe.
 */
object UsbTetherDiscovery {
    private const val PROBE_TIMEOUT_MS = 1_500

    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalCastUsbProbe").apply { isDaemon = true }
    }
    private val networkByHost = ConcurrentHashMap<String, Network>()
    private val listeners = CopyOnWriteArrayList<(List<UsbTetherPolicy.DiscoveredUsbTetherPeer>, Boolean) -> Unit>()

    @Volatile
    private var active = false
    @Volatile
    var currentPeers: List<UsbTetherPolicy.DiscoveredUsbTetherPeer> = emptyList()
        private set
    @Volatile
    var isScanning: Boolean = false
        private set

    private var cmRef: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    fun addListener(listener: (List<UsbTetherPolicy.DiscoveredUsbTetherPeer>, Boolean) -> Unit) {
        listeners.addIfAbsent(listener)
        val p = currentPeers
        val s = isScanning
        mainHandler.post { listener(p, s) }
    }

    fun removeListener(listener: (List<UsbTetherPolicy.DiscoveredUsbTetherPeer>, Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun start(ctx: Context) {
        val context = ctx.applicationContext
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
                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = triggerScanAsync()
                }
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
                try {
                    cm.registerNetworkCallback(req, cb)
                    netCallback = cb
                } catch (_: Throwable) {
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
                try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
            }
        }
        networkByHost.clear()
        publish(emptyList(), false)
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
        return if (key.isEmpty()) null else networkByHost[key]
    }

    private fun scanEthernetGatewaysNow() {
        val cm = cmRef ?: return
        publish(currentPeers, true)
        try {
            val candidates = collectEthernetGatewayCandidates(cm)
            val found = ArrayList<UsbTetherPolicy.DiscoveredUsbTetherPeer>()
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
                publish(found, false)
            }
        } catch (_: Throwable) {
            publish(currentPeers, false)
        }
    }

    @Suppress("DEPRECATION")
    private fun collectEthernetGatewayCandidates(
        cm: ConnectivityManager,
    ): List<Pair<Network, UsbTetherPolicy.UsbTetherCandidate>> {
        val out = ArrayList<Pair<Network, UsbTetherPolicy.UsbTetherCandidate>>()
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
                ?.hostAddress.orEmpty()
            val gatewayIp = lp.routes
                .asSequence()
                .filter { it.gateway != null }
                .sortedByDescending { it.isDefaultRoute }
                .mapNotNull { it.gateway as? Inet4Address }
                .mapNotNull { it.hostAddress }
                .firstOrNull { UsbTetherPolicy.isLikelyUsbTetherRoute(ifaceName, it) }
                ?: continue
            if (gatewayIp == localIp || !seenGateways.add(gatewayIp)) continue
            out.add(network to UsbTetherPolicy.UsbTetherCandidate(ifaceName, gatewayIp, localIp))
        }
        return out
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun probeCandidate(
        network: Network,
        candidate: UsbTetherPolicy.UsbTetherCandidate,
    ): UsbTetherPolicy.DiscoveredUsbTetherPeer? {
        val port = UsbTetherPolicy.DEFAULT_CAST_PORT
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
            val secureProtocols = ReceiverPolicy.filterSecureTlsProtocols(ssl.supportedProtocols)
            if (secureProtocols.isNotEmpty()) ssl.enabledProtocols = secureProtocols
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
            val body = StringBuilder()
            while (reader.readLine().also { line = it } != null && body.length < 4096) {
                body.append(line)
            }
            val health = UsbTetherPolicy.parseCastHealthJson(body.toString()) ?: return null
            val shortFp = UsbTetherPolicy.formatShortFingerprint(fullFp)
                .ifEmpty { UsbTetherPolicy.formatShortFingerprint(health.certShortFp) }

            UsbTetherPolicy.DiscoveredUsbTetherPeer(
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

    private fun publish(peers: List<UsbTetherPolicy.DiscoveredUsbTetherPeer>, scanning: Boolean) {
        currentPeers = peers
        isScanning = scanning
        mainHandler.post {
            for (l in listeners) l(peers, scanning)
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
