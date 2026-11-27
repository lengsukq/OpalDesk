package io.github.lengsukq.opaldesk.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class OpalCastPeerClientSocketTest {

    private class RecordingSocket(private val failOnConnect: Boolean = false) : Socket() {
        var connectedEndpoint: InetSocketAddress? = null
        var connectedTimeoutMs: Int = -1
        var tcpNoDelayFlag: Boolean = false
        var trafficClassValue: Int = -1
        var recvBufSize: Int = -1
        var closedFlag: Boolean = false

        override fun setTcpNoDelay(on: Boolean) {
            tcpNoDelayFlag = on
        }

        override fun setTrafficClass(tc: Int) {
            trafficClassValue = tc
        }

        override fun setReceiveBufferSize(size: Int) {
            recvBufSize = size
        }
        override fun setSendBufferSize(size: Int) = Unit

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            connectedEndpoint = endpoint as? InetSocketAddress
            connectedTimeoutMs = timeout
            if (failOnConnect) {
                throw IOException("simulated connect failure")
            }
        }

        override fun close() {
            closedFlag = true
        }
    }

    private class RecordingSslSocket(private val failOnConnect: Boolean = false) : SSLSocket() {
        var connectedEndpoint: InetSocketAddress? = null
        var connectedTimeoutMs: Int = -1
        var tcpNoDelayFlag: Boolean = false
        var trafficClassValue: Int = -1
        var recvBufSize: Int = -1
        var closedFlag: Boolean = false

        override fun setTcpNoDelay(on: Boolean) {
            tcpNoDelayFlag = on
        }

        override fun setTrafficClass(tc: Int) {
            trafficClassValue = tc
        }

        override fun setReceiveBufferSize(size: Int) {
            recvBufSize = size
        }
        override fun setSendBufferSize(size: Int) = Unit

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            connectedEndpoint = endpoint as? InetSocketAddress
            connectedTimeoutMs = timeout
            if (failOnConnect) {
                throw IOException("simulated ssl connect failure")
            }
        }

        override fun close() {
            closedFlag = true
        }

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<out String>?) = Unit
        override fun getSession(): SSLSession = throw UnsupportedOperationException()
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun startHandshake() = Unit
        override fun setUseClientMode(mode: Boolean) = Unit
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) = Unit
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) = Unit
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) = Unit
        override fun getEnableSessionCreation(): Boolean = true
    }

    private class FakeSslSocketFactory(
        private val directSocket: RecordingSslSocket = RecordingSslSocket(),
        private val wrappedSocket: RecordingSslSocket = RecordingSslSocket(),
    ) : SSLSocketFactory() {
        var wrappedRaw: Socket? = null
        var wrappedHost: String? = null
        var wrappedPort: Int = -1
        var wrappedAutoClose: Boolean = false

        override fun getDefaultCipherSuites(): Array<String> = emptyArray()
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()

        override fun createSocket(): Socket = directSocket

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
            wrappedRaw = s
            wrappedHost = host
            wrappedPort = port
            wrappedAutoClose = autoClose
            return wrappedSocket
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    @Test
    fun directLanConnectUsesTargetPortNotSocketGetPortZero() {
        val ssl = RecordingSslSocket()
        val factory = FakeSslSocketFactory(directSocket = ssl)

        val result = OpalCastPeerClient.openConnectedTlsSocket(
            sslSocketFactory = factory,
            targetHost = "10.10.10.145",
            targetPort = 19878,
            boundSocketFactory = null,
            connectTimeoutMs = 6_000,
        )

        assertSame(ssl, result)
        assertTrue(ssl.tcpNoDelayFlag)
        assertEquals(0xB8, ssl.trafficClassValue)
        assertEquals(512 * 1024, ssl.recvBufSize)
        assertNotNull(ssl.connectedEndpoint)
        assertEquals("10.10.10.145", ssl.connectedEndpoint!!.hostString)
        assertEquals(19878, ssl.connectedEndpoint!!.port)
        assertEquals(6_000, ssl.connectedTimeoutMs)
    }

    @Test
    fun boundNetworkConnectUsesTargetPortAndWrapsRawSocket() {
        val raw = RecordingSocket()
        val wrapped = RecordingSslSocket()
        val sslFactory = FakeSslSocketFactory(wrappedSocket = wrapped)
        val boundFactory = object : SocketFactory() {
            override fun createSocket(): Socket = raw
            override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                throw UnsupportedOperationException()
        }

        val result = OpalCastPeerClient.openConnectedTlsSocket(
            sslSocketFactory = sslFactory,
            targetHost = "192.168.42.129",
            targetPort = 19878,
            boundSocketFactory = boundFactory,
            connectTimeoutMs = 4_500,
        )

        assertSame(wrapped, result)
        assertTrue(raw.tcpNoDelayFlag)
        assertEquals(1024 * 1024, raw.recvBufSize)
        assertEquals("192.168.42.129", raw.connectedEndpoint?.hostString)
        assertEquals(19878, raw.connectedEndpoint?.port)
        assertEquals(4_500, raw.connectedTimeoutMs)
        assertSame(raw, sslFactory.wrappedRaw)
        assertEquals("192.168.42.129", sslFactory.wrappedHost)
        assertEquals(19878, sslFactory.wrappedPort)
        assertTrue(sslFactory.wrappedAutoClose)
    }

    @Test
    fun closesSocketWhenDirectConnectFails() {
        val ssl = RecordingSslSocket(failOnConnect = true)
        val factory = FakeSslSocketFactory(directSocket = ssl)

        var threw = false
        try {
            OpalCastPeerClient.openConnectedTlsSocket(
                sslSocketFactory = factory,
                targetHost = "10.10.10.145",
                targetPort = 19878,
            )
        } catch (_: IOException) {
            threw = true
        }

        assertTrue(threw)
        assertTrue(ssl.closedFlag)
    }

    @Test
    fun scalesBitrateAndTransportHintForHighRefreshAndUsbTethering() {
        assertEquals(OpalCastPeerClient.TRANSPORT_HINT_WIFI_TLS, OpalCastPeerClient.resolveTransportHint(false))
        assertEquals(OpalCastPeerClient.TRANSPORT_HINT_USB_TETHER, OpalCastPeerClient.resolveTransportHint(true))

        // 2560x1600 @ 120Hz wireless gets balanced 22 Mbps (crisp HEVC without Wi-Fi burst thrashing).
        val wifi2k120 = OpalCastPeerClient.computeTargetBitrateKbps(
            width = 2560, height = 1600, fps = 120, isStandalone = false, isUsbTether = false,
        )
        assertEquals(22_000, wifi2k120)

        // Low-latency AudioTrack buffer stays tight (~64-85ms) without 4x inflation on high-minBuf OEMs.
        val audioBuf = OpalCastPeerClient.computeAudioTrackBufferBytes(
            minBufBytes = 7680, sampleRate = 48_000, channels = 2,
        )
        assertTrue("Expected low-latency AudioTrack buffer in 12..20 KiB, got $audioBuf", audioBuf in 12_288..20_480)
        assertEquals(0, audioBuf % 4096)

        val oemMinBuf = OpalCastPeerClient.computeAudioTrackBufferBytes(
            minBufBytes = 15_360, sampleRate = 48_000, channels = 2,
        )
        assertEquals(16_384, oemMinBuf)

        // USB Tethering boosts headroom significantly above Wi-Fi.
        val usb2k120 = OpalCastPeerClient.computeTargetBitrateKbps(
            width = 2560, height = 1600, fps = 120, isStandalone = false, isUsbTether = true,
        )
        assertTrue(usb2k120 > wifi2k120)
        assertTrue(usb2k120 <= OpalCastPeerClient.MAX_USB_TETHER_BITRATE_KBPS)

        // Standalone floating windows stay lightweight on Wi-Fi and boost cleanly on USB Tethering.
        val wifiStandalone = OpalCastPeerClient.computeTargetBitrateKbps(
            width = 800, height = 1440, fps = 60, isStandalone = true, isUsbTether = false,
        )
        val usbStandalone = OpalCastPeerClient.computeTargetBitrateKbps(
            width = 800, height = 1440, fps = 60, isStandalone = true, isUsbTether = true,
        )
        assertEquals(9_000, wifiStandalone)
        assertEquals(13_500, usbStandalone)
    }
}
