package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.protocol.AppProfileKey
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Pure Kotlin OpalCast v1.1 TLS Peer Receiver Client (100% wire-compatible with
 * `OpalCastProtocol.java` & `RemoteStreamServer.java`):
 * - TLS 1.2/1.3 + PIN-free host interactive consent / permanent authToken authentication
 * - Supports `desktop`, `standalone_app`, and `app_catalog` session roles
 * - Hardware-accelerated HEVC/H.264 + AAC-LC decoding delegated to [OpalCastPeerMediaDecoder]
 * - Full multi-touch, mouse (hover/buttons/scroll), key, IME text, resize, and system-action back-channel
 */
class OpalCastPeerClient(
    private val host: String,
    private val port: Int,
    private val savedAuthToken: String,
    private val clientId: String,
    private val reqWidth: Int,
    private val reqHeight: Int,
    private val reqDpi: Int,
    private val reqFps: Int,
    private val sessionRole: String = SESSION_ROLE_DESKTOP,
    private val appPackage: String = "",
    private val appActivity: String = "",
    private val appUserId: Int = 0,
    private val audioEnabled: Boolean = (sessionRole == SESSION_ROLE_DESKTOP),
    private val initialDisplayMode: Int = DISPLAY_MODE_DEX,
    private val targetNetwork: Network? = null,
    private val isUsbTether: Boolean = (targetNetwork != null),
    private val listener: Listener,
) {
    data class RemoteAppEntry(
        val pkg: String,
        val cls: String,
        val label: String,
        val openCount: Int = 0,
        val userId: Int = 0,
        val profileType: String = "",
    ) {
        val appKey: String
            get() = AppProfileKey.format(pkg, userId)
    }

    interface Listener {
        fun onWaitingForApproval() {}
        fun onAuthenticated(peerName: String, authToken: String, permanent: Boolean, certFingerprint: String)
        fun onAppListReceived(apps: List<RemoteAppEntry>) {}
        fun onRemoteImeRequest(show: Boolean, inputType: Int) {}
        fun onRemoteMicState(active: Boolean, supported: Boolean, message: String) {}
        fun onRemoteCameraState(
            active: Boolean,
            supported: Boolean,
            rootRequired: Boolean,
            message: String,
            facing: String = "",
        ) {}
        fun onRemoteBlackoutChanged(blackout: Boolean) {}
        fun onRemoteFullscreenChanged(windowTitle: String) {}
        fun onDisplayModeChanged(displayMode: Int) {}
        fun onVideoSizeChanged(width: Int, height: Int, fps: Int)
        fun onError(message: String)
        fun onDisconnected(reason: String)
    }

    companion object {
        private const val TAG = "OpalPeerClient"

        const val SESSION_ROLE_DESKTOP = "desktop"
        const val SESSION_ROLE_STANDALONE_APP = "standalone_app"
        const val SESSION_ROLE_APP_CATALOG = "app_catalog"

        private const val TYPE_VIDEO_CONFIG = 0x01
        private const val TYPE_VIDEO_FRAME = 0x02
        private const val TYPE_AUDIO_CONFIG = 0x03
        private const val TYPE_AUDIO_FRAME = 0x04
        private const val TYPE_DEVICE_STATE = 0x10
        private const val TYPE_AUTH_RESULT = 0x12
        private const val TYPE_APP_LIST = 0x15
        private const val TYPE_IME_REQUEST = 0x16
        private const val TYPE_MIC_STATE = 0x17
        private const val TYPE_CAMERA_STATE = 0x18
        private const val TYPE_PONG = 0x1F
        private const val MAX_TX_BATCH = 32
        private const val IDR_THROTTLE_MS = 500L

        private const val TYPE_HELLO = 0x20
        private const val TYPE_RESIZE_CONFIG = 0x21
        private const val TYPE_REQUEST_IDR = 0x22
        private const val TYPE_INPUT_MOUSE = 0x23
        private const val TYPE_INPUT_TOUCH = 0x24
        private const val TYPE_INPUT_KEY = 0x25
        private const val TYPE_INPUT_TEXT = 0x26
        private const val TYPE_SYSTEM_ACTION = 0x27
        private const val TYPE_LIST_APPS = 0x2A
        private const val TYPE_MIC_CONFIG = 0x2C
        private const val TYPE_MIC_FRAME = 0x2D
        private const val TYPE_CAMERA_CONFIG = 0x2E
        private const val TYPE_PING = 0x2F
        private const val TYPE_CAMERA_FRAME = 0x30

        const val DISPLAY_MODE_DEX = 0
        const val DISPLAY_MODE_MIRROR = 1

        const val SYS_HOME = 1
        const val SYS_BACK = 2
        const val SYS_RECENTS = 3
        const val SYS_TOGGLE_DISPLAY_MODE = 4
        const val SYS_TOGGLE_BLACKOUT = 5
        const val SYS_DISCONNECT = 8

        private const val SOCKET_CONNECT_TIMEOUT_MS = 6_000
        private const val SOCKET_RECV_BUF_BYTES = 512 * 1024
        private const val USB_TETHER_SOCKET_RECV_BUF_BYTES = 1024 * 1024
        private const val SOCKET_SEND_BUF_BYTES = 256 * 1024
        private const val REALTIME_MEDIA_TRAFFIC_CLASS = 0xB8
        internal const val AUDIO_PREFILL_FRAMES = 1
        internal const val AUDIO_IDLE_PAUSE_MS = 260L
        internal const val AUDIO_QUEUE_CAPACITY = 6
        internal const val AUDIO_BACKLOG_TRIM_THRESHOLD = 4
        internal const val AUDIO_BACKLOG_KEEP_FRAMES = 2

        const val TRANSPORT_HINT_WIFI_TLS = "wifi_tls"
        const val TRANSPORT_HINT_USB_TETHER = "usb_tether"
        const val CLIENT_PLATFORM_ANDROID = "android"
        const val MIN_BITRATE_KBPS = 2_500
        const val MAX_WIFI_BITRATE_KBPS = 32_000
        const val MAX_USB_TETHER_BITRATE_KBPS = 50_000

        internal fun resolveTransportHint(isUsbTether: Boolean): String =
            if (isUsbTether) TRANSPORT_HINT_USB_TETHER else TRANSPORT_HINT_WIFI_TLS

        /**
         * Computes a low-latency AudioTrack ring buffer (~64 ms of PCM16 audio, honoring platform
         * minBufferSize without 4x inflation) so audio stays tightly synchronized with low-latency video.
         */
        internal fun computeAudioTrackBufferBytes(
            minBufBytes: Int,
            sampleRate: Int,
            channels: Int,
        ): Int {
            val safeMin = ((minBufBytes.coerceAtLeast(4096) + 4095) / 4096) * 4096
            val bytesPerSecond = sampleRate.coerceIn(16_000, 96_000) * channels.coerceIn(1, 2) * 2
            val target64Ms = (bytesPerSecond * 64L / 1000L).toInt()
            val frameAligned = ((target64Ms + 4095) / 4096) * 4096
            return maxOf(safeMin, frameAligned, 12_288)
        }

        /**
         * Computes resolution-, FPS-, and transport-aware HEVC bitrate so 2.5K/4K @ 90-120Hz
         * tablets stay crisp without inducing Wi-Fi congestion bursts, while USB Tethering
         * unlocks wired throughput headroom.
         */
        internal fun computeTargetBitrateKbps(
            width: Int,
            height: Int,
            fps: Int,
            isStandalone: Boolean,
            isUsbTether: Boolean,
        ): Int {
            val safeW = width.coerceAtLeast(320)
            val safeH = height.coerceAtLeast(320)
            val safeFps = fps.coerceIn(30, 120)
            val pixels = safeW.toLong() * safeH.toLong()
            val highFps = safeFps >= 85
            val maxCap = if (isUsbTether) MAX_USB_TETHER_BITRATE_KBPS else MAX_WIFI_BITRATE_KBPS

            val baseKbps = if (isStandalone) {
                when {
                    pixels >= 2_200_000L && highFps -> 15_000
                    pixels >= 2_200_000L -> 12_000
                    pixels >= 1_200_000L -> 10_000
                    else -> 9_000
                }
            } else {
                when {
                    pixels >= 5_500_000L && highFps -> 26_000
                    pixels >= 5_500_000L -> 22_000
                    pixels >= 3_200_000L && highFps -> 22_000
                    pixels >= 3_200_000L -> 18_000
                    pixels >= 2_000_000L && highFps -> 18_000
                    pixels >= 2_000_000L -> 15_000
                    highFps -> 14_000
                    else -> 12_000
                }
            }
            val scaled = if (isUsbTether) (baseKbps * 3) / 2 else baseKbps
            return scaled.coerceIn(MIN_BITRATE_KBPS, maxCap)
        }

        internal fun openConnectedTlsSocket(
            sslSocketFactory: SSLSocketFactory,
            targetHost: String,
            targetPort: Int,
            boundSocketFactory: SocketFactory? = null,
            connectTimeoutMs: Int = SOCKET_CONNECT_TIMEOUT_MS,
        ): SSLSocket {
            return if (boundSocketFactory != null) {
                val raw = boundSocketFactory.createSocket()
                try {
                    configureTransportSocket(raw, highThroughput = true)
                    raw.connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
                    sslSocketFactory.createSocket(raw, targetHost, targetPort, true) as SSLSocket
                } catch (t: Throwable) {
                    try { raw.close() } catch (_: Throwable) {}
                    throw t
                }
            } else {
                val ssl = sslSocketFactory.createSocket() as SSLSocket
                try {
                    configureTransportSocket(ssl, highThroughput = false)
                    ssl.connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
                    ssl
                } catch (t: Throwable) {
                    try { ssl.close() } catch (_: Throwable) {}
                    throw t
                }
            }
        }

        private fun configureTransportSocket(sock: Socket, highThroughput: Boolean) {
            sock.tcpNoDelay = true
            try { sock.trafficClass = REALTIME_MEDIA_TRAFFIC_CLASS } catch (_: Throwable) {}
            val recvBuf = if (highThroughput) USB_TETHER_SOCKET_RECV_BUF_BYTES else SOCKET_RECV_BUF_BYTES
            try { sock.receiveBufferSize = recvBuf } catch (_: Throwable) {}
            try { sock.sendBufferSize = SOCKET_SEND_BUF_BYTES } catch (_: Throwable) {}
        }
    }

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val authCompleted = AtomicBoolean(false)
    private val outQueue = LinkedBlockingQueue<OpalPeerUplinkFrame>(256)
    private val textSeq = AtomicLong(1L)
    private val lastIdrRequestMs = AtomicLong(0L)
    private val sideNavTouchFilter = CompositorGeometry.SideNavigationTouchFilter()
    private val frameCodec = OpalCastPeerFrameCodec(TAG)
    private val lastRxActivityMs = AtomicLong(0L)

    @Volatile
    private var socket: SSLSocket? = null

    @Volatile
    private var audioDecodeThread: Thread? = null

    private val mediaDecoder = OpalCastPeerMediaDecoder(
        initialWidth = reqWidth,
        initialHeight = reqHeight,
        initialFps = reqFps,
        isActive = { running.get() && !closed.get() },
        onDisplayModeChanged = { listener.onDisplayModeChanged(it) },
        onVideoSizeChanged = { w, h, fps -> listener.onVideoSizeChanged(w, h, fps) },
        onRequestKeyFrame = ::requestKeyFrame,
        onRequestKeyFrameThrottled = ::requestKeyFrameThrottled,
    )

    val remoteWidth: Int get() = mediaDecoder.remoteWidth
    val remoteHeight: Int get() = mediaDecoder.remoteHeight
    val remoteFps: Int get() = mediaDecoder.remoteFps

    fun attachSurface(surface: Surface?) {
        mediaDecoder.attachSurface(surface)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread(::ioLoop, "OpalPeerClientRx").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        try {
            enqueue(TYPE_SYSTEM_ACTION, 0, frameCodec.encodeSystemAction(SYS_DISCONNECT, 0))
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        mediaDecoder.releaseAll()
    }

    fun requestKeyFrame() {
        enqueue(TYPE_REQUEST_IDR, 0, ByteArray(0))
    }

    fun requestAppList() {
        enqueue(TYPE_LIST_APPS, 0, ByteArray(0))
    }

    fun sendMicConfig(
        enabled: Boolean,
        sampleRate: Int = PeerMicCapturer.DEFAULT_SAMPLE_RATE,
        channels: Int = PeerMicCapturer.DEFAULT_CHANNELS,
    ) {
        val json = JSONObject().apply {
            put("enabled", enabled)
            put("codec", "pcm16")
            put("sampleRate", sampleRate)
            put("channels", channels)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        enqueue(TYPE_MIC_CONFIG, 0, json)
    }

    fun sendMicFrame(pcm: ByteArray) {
        if (closed.get() || pcm.isEmpty()) return
        if (outQueue.remainingCapacity() <= 32) return
        outQueue.offer(OpalPeerUplinkFrame(TYPE_MIC_FRAME, 0, SystemClock.elapsedRealtimeNanos() / 1000L, pcm))
    }

    fun sendCameraConfig(
        enabled: Boolean,
        width: Int = PeerCameraCapturer.DEFAULT_WIDTH,
        height: Int = PeerCameraCapturer.DEFAULT_HEIGHT,
        fps: Int = PeerCameraCapturer.DEFAULT_FPS,
        facing: String = "front",
    ) {
        val json = JSONObject().apply {
            put("enabled", enabled)
            put("codec", "mjpeg")
            put("width", width)
            put("height", height)
            put("fps", fps)
            put("facing", facing)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        enqueue(TYPE_CAMERA_CONFIG, 0, json)
    }

    fun sendCameraFrame(jpeg: ByteArray) {
        if (closed.get() || jpeg.isEmpty()) return
        if (outQueue.remainingCapacity() <= 32) return
        outQueue.offer(OpalPeerUplinkFrame(TYPE_CAMERA_FRAME, 0, SystemClock.elapsedRealtimeNanos() / 1000L, jpeg))
    }

    fun sendResizeConfig(width: Int, height: Int, dpi: Int, fps: Int = reqFps, bitrateKbps: Int = 0) {
        val alignedW = (width.coerceIn(320, 3840) and 7.inv()).coerceAtLeast(320)
        val alignedH = (height.coerceIn(320, 3840) and 7.inv()).coerceAtLeast(320)
        val wireDpi = if (dpi <= 0) 0 else dpi.coerceIn(120, 560)
        val maxCap = if (isUsbTether) MAX_USB_TETHER_BITRATE_KBPS else MAX_WIFI_BITRATE_KBPS
        val resolvedBitrate = if (bitrateKbps > 0) {
            bitrateKbps.coerceIn(MIN_BITRATE_KBPS, maxCap)
        } else {
            computeTargetBitrateKbps(
                width = alignedW,
                height = alignedH,
                fps = fps,
                isStandalone = (sessionRole == SESSION_ROLE_STANDALONE_APP),
                isUsbTether = isUsbTether,
            )
        }
        val bb = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(alignedW.toShort())
        bb.putShort(alignedH.toShort())
        bb.putShort(wireDpi.toShort())
        bb.putShort((fps.coerceIn(30, 120) * 100).toShort())
        bb.putInt(resolvedBitrate)
        bb.put(0xFF.toByte())
        bb.put(0xFF.toByte())
        enqueue(TYPE_RESIZE_CONFIG, 0, bb.array())
    }

    fun sendSystemAction(action: Int, arg: Int = 0) {
        enqueue(TYPE_SYSTEM_ACTION, 0, frameCodec.encodeSystemAction(action, arg))
    }

    fun sendKeyEvent(action: Int, keyCode: Int, metaState: Int, repeat: Int = 0) {
        val bb = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
        bb.put((action and 0xFF).toByte())
        bb.putInt(keyCode)
        bb.putInt(metaState)
        bb.putInt(repeat)
        enqueue(TYPE_INPUT_KEY, 0, bb.array())
    }

    fun sendTextEvent(text: String, commitPaste: Boolean = true) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val bb = ByteBuffer.allocate(9 + bytes.size).order(ByteOrder.BIG_ENDIAN)
        bb.put(if (commitPaste) 0.toByte() else 1.toByte())
        bb.putLong(textSeq.incrementAndGet())
        bb.put(bytes)
        enqueue(TYPE_INPUT_TEXT, 0, bb.array())
    }

    fun sendTouchEvent(ev: MotionEvent, viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        val count = ev.pointerCount.coerceIn(1, 10)
        val bb = ByteBuffer.allocate(3 + count * 16).order(ByteOrder.BIG_ENDIAN)
        bb.put((ev.actionMasked and 0xFF).toByte())
        bb.put((ev.actionIndex and 0xFF).toByte())
        bb.put((count and 0xFF).toByte())
        val rw = remoteWidth.coerceAtLeast(2).toFloat()
        val rh = remoteHeight.coerceAtLeast(2).toFloat()
        for (i in 0 until count) {
            val rx = (ev.getX(i) * (rw / viewW)).coerceIn(0f, rw - 1f)
            val ry = (ev.getY(i) * (rh / viewH)).coerceIn(0f, rh - 1f)
            bb.putInt(ev.getPointerId(i))
            bb.putFloat(rx)
            bb.putFloat(ry)
            bb.putFloat(ev.getPressure(i).coerceIn(0f, 1f))
        }
        enqueue(TYPE_INPUT_TOUCH, 0, bb.array())
    }

    fun handleGenericMouseMotion(ev: MotionEvent?, viewW: Int, viewH: Int): Boolean {
        if (ev == null || !isMouseMotion(ev)) return false
        return when (ev.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (ev.actionButton == MotionEvent.BUTTON_BACK) {
                    if (WorkstationKeyDispatcher.tryClaimMouseSideBack()) {
                        sendSystemAction(SYS_BACK)
                    }
                }
                true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> true
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            MotionEvent.ACTION_SCROLL,
            -> {
                sendMouseEvent(ev, viewW, viewH)
                true
            }
            else -> false
        }
    }

    fun sendMouseEvent(ev: MotionEvent, viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        val masked = ev.actionMasked
        if (sideNavTouchFilter.shouldSuppress(masked, isMouseMotion(ev), ev.buttonState)) {
            return
        }
        val rw = remoteWidth.coerceAtLeast(2).toFloat()
        val rh = remoteHeight.coerceAtLeast(2).toFloat()
        val rx = (ev.x * (rw / viewW)).coerceIn(0f, rw - 1f)
        val ry = (ev.y * (rh / viewH)).coerceIn(0f, rh - 1f)
        val mouseAction = when (masked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> 2
            MotionEvent.ACTION_SCROLL -> 3
            MotionEvent.ACTION_HOVER_EXIT -> 4
            else -> 0
        }
        val normButtons = if (
            (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_MOVE) &&
            ev.buttonState == 0
        ) {
            MotionEvent.BUTTON_PRIMARY
        } else {
            ev.buttonState
        }
        val actBtn = if (masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE) {
            ev.actionButton
        } else if (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_UP) {
            if (ev.buttonState != 0) ev.buttonState else MotionEvent.BUTTON_PRIMARY
        } else {
            0
        }
        val hScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_HSCROLL) else 0f
        val vScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_VSCROLL) else 0f

        val bb = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
        bb.put((mouseAction and 0xFF).toByte())
        bb.putFloat(rx)
        bb.putFloat(ry)
        bb.putInt(normButtons)
        bb.putInt(actBtn)
        bb.putFloat(hScroll)
        bb.putFloat(vScroll)
        enqueue(TYPE_INPUT_MOUSE, 0, bb.array())
    }

    fun isMouseMotion(ev: MotionEvent): Boolean =
        (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (ev.pointerCount > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

    private fun enqueue(type: Int, flags: Int, payload: ByteArray) {
        if (closed.get()) return
        outQueue.offer(OpalPeerUplinkFrame(type, flags, SystemClock.elapsedRealtimeNanos() / 1000L, payload))
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun openTlsSocket(): Pair<SSLSocket, String> {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            },
        )
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        val ssl = openConnectedTlsSocket(
            sslSocketFactory = ctx.socketFactory,
            targetHost = host,
            targetPort = port,
            boundSocketFactory = targetNetwork?.socketFactory,
        )
        try {
            ssl.soTimeout = 10_000
            ssl.startHandshake()
            val peerCert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
            val fp = if (peerCert != null) frameCodec.sha256Hex(peerCert.encoded) else ""
            ssl.soTimeout = 70_000
            return ssl to fp
        } catch (t: Throwable) {
            try { ssl.close() } catch (_: Throwable) {}
            throw t
        }
    }

    private fun ioLoop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        var disconnectReason = "已断开连接"
        try {
            OpalLogStore.i(
                TAG,
                "Connecting role=$sessionRole mode=$initialDisplayMode port=$port ${reqWidth}x${reqHeight}@${reqFps}fps " +
                    "audio=$audioEnabled pkg=${appPackage.ifBlank { "-" }}",
            )
            val (ssl, peerFp) = openTlsSocket()
            socket = ssl
            val input = BufferedInputStream(ssl.inputStream, 131_072)
            val output = BufferedOutputStream(ssl.outputStream, 65_536)

            val initialBitrateKbps = computeTargetBitrateKbps(
                width = reqWidth,
                height = reqHeight,
                fps = reqFps,
                isStandalone = (sessionRole == SESSION_ROLE_STANDALONE_APP),
                isUsbTether = isUsbTether,
            )
            val helloJson = JSONObject().apply {
                put("protocolVersion", 1)
                put("clientName", Build.MODEL ?: "Android")
                put("clientId", clientId)
                put("preferredCodec", "hevc")
                put("width", reqWidth)
                put("height", reqHeight)
                put("dpi", reqDpi)
                put("fps", reqFps)
                put("bitrateKbps", initialBitrateKbps)
                put("displayMode", initialDisplayMode)
                put("audioEnabled", audioEnabled)
                put("authToken", savedAuthToken)
                put("pinCode", "")
                put("transportHint", resolveTransportHint(isUsbTether))
                put("clientPlatform", CLIENT_PLATFORM_ANDROID)
                put("sessionRole", sessionRole)
                if (appPackage.isNotEmpty()) put("appPackage", appPackage)
                if (appActivity.isNotEmpty()) put("appActivity", appActivity)
                if (appUserId > 0) put("appUserId", appUserId)
            }.toString().toByteArray(StandardCharsets.UTF_8)
            frameCodec.writeFrame(output, TYPE_HELLO, 0, SystemClock.elapsedRealtimeNanos() / 1000L, helloJson)
            output.flush()

            if (savedAuthToken.isBlank()) {
                listener.onWaitingForApproval()
            } else {
                Thread({
                    SystemClock.sleep(320L)
                    if (!authCompleted.get() && running.get() && !closed.get()) {
                        listener.onWaitingForApproval()
                    }
                }, "OpalPeerApprovalHint").apply {
                    isDaemon = true
                    start()
                }
            }

            val txThread = Thread({ sendLoop(output) }, "OpalPeerClientTx").apply {
                isDaemon = true
                start()
            }
            val audioThread = if (sessionRole != SESSION_ROLE_APP_CATALOG && audioEnabled) {
                mediaDecoder.startAudioThread()
            } else {
                null
            }
            audioDecodeThread = audioThread

            lastRxActivityMs.set(SystemClock.uptimeMillis())
            while (running.get() && !closed.get()) {
                val frame = frameCodec.readFrame(input) ?: break
                lastRxActivityMs.set(SystemClock.uptimeMillis())
                handleDownlinkFrame(frame, peerFp, ssl)
            }
            txThread.interrupt()
            audioThread?.interrupt()
        } catch (t: Throwable) {
            if (!closed.get()) {
                val msg = t.message ?: t.javaClass.simpleName
                OpalLogStore.w(TAG, "Peer cast session error (role=$sessionRole): $msg")
                disconnectReason = msg
                listener.onError(msg)
            }
        } finally {
            running.set(false)
            closed.set(true)
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            mediaDecoder.releaseAll()
            listener.onDisconnected(disconnectReason)
        }
    }

    private fun sendLoop(out: OutputStream) {
        var lastPingMs = SystemClock.uptimeMillis()
        var pingSeq = 1L
        val rawBatch = ArrayList<OpalPeerUplinkFrame>(MAX_TX_BATCH)
        val coalescedBatch = ArrayList<OpalPeerUplinkFrame>(MAX_TX_BATCH)
        val pingPayload = ByteArray(8)
        try {
            while (running.get() && !closed.get()) {
                val first = outQueue.poll(1000L, TimeUnit.MILLISECONDS)
                var wroteAny = false
                if (first != null) {
                    rawBatch.clear()
                    rawBatch.add(first)
                    outQueue.drainTo(rawBatch, MAX_TX_BATCH - 1)
                    coalescedBatch.clear()
                    OpalPeerUplinkCoalescer.coalesceBatch(rawBatch, coalescedBatch)
                    for (i in 0 until coalescedBatch.size) {
                        val pkt = coalescedBatch[i]
                        frameCodec.writeFrame(out, pkt.type, pkt.flags, pkt.tsUs, pkt.payload)
                    }
                    wroteAny = true
                }
                val now = SystemClock.uptimeMillis()
                val idleMs = now - lastRxActivityMs.get()
                val pingIntervalMs = if (idleMs >= 3_500L) 4_000L else 10_000L
                if (now - lastPingMs >= pingIntervalMs) {
                    lastPingMs = now
                    val id = pingSeq++
                    pingPayload[0] = ((id ushr 56) and 0xFF).toByte()
                    pingPayload[1] = ((id ushr 48) and 0xFF).toByte()
                    pingPayload[2] = ((id ushr 40) and 0xFF).toByte()
                    pingPayload[3] = ((id ushr 32) and 0xFF).toByte()
                    pingPayload[4] = ((id ushr 24) and 0xFF).toByte()
                    pingPayload[5] = ((id ushr 16) and 0xFF).toByte()
                    pingPayload[6] = ((id ushr 8) and 0xFF).toByte()
                    pingPayload[7] = (id and 0xFF).toByte()
                    frameCodec.writeFrame(out, TYPE_PING, 0, now * 1000L, pingPayload)
                    wroteAny = true
                }
                if (wroteAny) {
                    out.flush()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleDownlinkFrame(frame: OpalCastRxFrame, peerFp: String, ssl: SSLSocket) {
        when (frame.type) {
            TYPE_AUTH_RESULT -> {
                authCompleted.set(true)
                try { ssl.soTimeout = 0 } catch (_: Throwable) {}
                frameCodec.dispatchAuthResult(frame.payload, host, sessionRole, peerFp, listener) { stop() }
            }
            TYPE_DEVICE_STATE -> frameCodec.dispatchDeviceState(frame.payload, listener)
            TYPE_APP_LIST -> frameCodec.dispatchAppList(frame.payload, listener)
            TYPE_IME_REQUEST -> frameCodec.dispatchImeRequest(frame.payload, listener)
            TYPE_MIC_STATE -> frameCodec.dispatchMicState(frame.payload, listener)
            TYPE_CAMERA_STATE -> frameCodec.dispatchCameraState(frame.payload, listener)
            TYPE_VIDEO_CONFIG -> mediaDecoder.handleVideoConfig(frame.payload)
            TYPE_VIDEO_FRAME -> mediaDecoder.handleVideoFrame(frame.payload, frame.length, frame.flags, frame.timestampUs)
            TYPE_AUDIO_CONFIG -> mediaDecoder.handleAudioConfig(frame.payload)
            TYPE_AUDIO_FRAME -> mediaDecoder.enqueueAudioFrame(frame.payload, frame.timestampUs)
            TYPE_PONG -> Unit
        }
    }

    private fun requestKeyFrameThrottled() {
        val nowMs = SystemClock.uptimeMillis()
        val prev = lastIdrRequestMs.get()
        if (prev == 0L || nowMs - prev >= IDR_THROTTLE_MS) {
            if (lastIdrRequestMs.compareAndSet(prev, nowMs)) {
                requestKeyFrame()
            }
        }
    }
}
