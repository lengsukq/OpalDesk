package io.github.lengsukq.opalcast.net

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.media.CastCameraCapturer
import io.github.lengsukq.opalcast.media.CastMicCapturer
import io.github.lengsukq.opalcast.policy.InputGuardPolicy
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.policy.UplinkCoalescer
import io.github.lengsukq.opalcast.policy.UplinkFrame
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
 * Pure-Kotlin OpalCast v1.1 TLS Binary Receiver Client (100% wire-compatible with OpalDesk
 * `RemoteStreamServer` & `OpalCastProtocol`), built for Android 5.0–17 (API 21–37).
 */
class OpalCastClient(
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
    private val audioEnabled: Boolean = (sessionRole == SESSION_ROLE_DESKTOP),
    private val initialDisplayMode: Int = DISPLAY_MODE_DEX,
    private val targetNetwork: Network? = null,
    private val isUsbTether: Boolean = (targetNetwork != null),
    private val preferredCodec: String = "",
    private val listener: Listener,
) {
    data class RemoteAppEntry(
        val pkg: String,
        val cls: String,
        val label: String,
        val openCount: Int = 0,
    )

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
        private const val TAG = "OpalCastClient"
        private const val HEADER_SIZE = 18
        private const val MAX_PAYLOAD_LEN = 16 * 1024 * 1024

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

        private const val CODEC_HEVC = 2
        private const val FLAG_VIDEO_KEYFRAME = 0x01

        const val DISPLAY_MODE_DEX = 0
        const val DISPLAY_MODE_MIRROR = 1

        const val SYS_HOME = 1
        const val SYS_BACK = 2
        const val SYS_RECENTS = 3
        const val SYS_TOGGLE_DISPLAY_MODE = 4
        const val SYS_TOGGLE_BLACKOUT = 5
        const val SYS_DISCONNECT = 8

        private const val MAX_TX_BATCH = 32
        private const val IDR_THROTTLE_MS = 500L
        private const val DECODER_RETRY_COOLDOWN_MS = 1_000L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 6_000
        private const val SOCKET_RECV_BUF_BYTES = 512 * 1024
        private const val USB_TETHER_SOCKET_RECV_BUF_BYTES = 1024 * 1024
        private const val SOCKET_SEND_BUF_BYTES = 256 * 1024
        private const val REALTIME_MEDIA_TRAFFIC_CLASS = 0xB8
        private const val AUDIO_PREFILL_FRAMES = 1
        private const val AUDIO_IDLE_PAUSE_MS = 260L
        private const val CLIENT_PLATFORM_ANDROID = "android"

        val sharedSideBackDedup = InputGuardPolicy.SideBackKeyDeduplicator()

        private fun openConnectedTlsSocket(
            sslSocketFactory: SSLSocketFactory,
            targetHost: String,
            targetPort: Int,
            boundSocketFactory: SocketFactory? = null,
        ): SSLSocket {
            return if (boundSocketFactory != null) {
                val raw = boundSocketFactory.createSocket()
                try {
                    configureTransportSocket(raw, highThroughput = true)
                    raw.connect(InetSocketAddress(targetHost, targetPort), SOCKET_CONNECT_TIMEOUT_MS)
                    sslSocketFactory.createSocket(raw, targetHost, targetPort, true) as SSLSocket
                } catch (t: Throwable) {
                    try { raw.close() } catch (_: Throwable) {}
                    throw t
                }
            } else {
                val ssl = sslSocketFactory.createSocket() as SSLSocket
                try {
                    configureTransportSocket(ssl, highThroughput = false)
                    ssl.connect(InetSocketAddress(targetHost, targetPort), SOCKET_CONNECT_TIMEOUT_MS)
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
    private val outQueue = LinkedBlockingQueue<UplinkFrame>(256)
    private val audioQueue = LinkedBlockingQueue<Pair<ByteArray, Long>>(6)
    private val textSeq = AtomicLong(1L)
    private val lastIdrRequestMs = AtomicLong(0L)
    private val lastRxActivityMs = AtomicLong(0L)
    private val lastVideoDecodeErrLogMs = AtomicLong(0L)
    private val sideNavTouchFilter = InputGuardPolicy.SideNavigationTouchFilter()
    private val txHeaderBuf = ByteArray(HEADER_SIZE)
    private val rxHeaderBuf = ByteArray(HEADER_SIZE)
    private var rxVideoScratch = ByteArray(64 * 1024)
    private val audioOutBufInfo = MediaCodec.BufferInfo()

    @Volatile
    private var outputSurface: Surface? = null
    @Volatile
    private var socket: SSLSocket? = null
    @Volatile
    var remoteWidth: Int = reqWidth
        private set
    @Volatile
    var remoteHeight: Int = reqHeight
        private set
    @Volatile
    var remoteFps: Int = reqFps
        private set

    private val videoLock = Any()
    @Volatile
    private var videoCodec: MediaCodec? = null
    private var videoMime: String = MediaFormat.MIMETYPE_VIDEO_HEVC
    private var pendingCsd: ByteArray? = null
    private var configuredVideoW = 0
    private var configuredVideoH = 0
    private var videoConfigured = false
    @Volatile
    private var awaitingInitialKeyFrame = true
    private var lastDecoderConfigureFailMs = 0L
    @Volatile
    private var videoDrainThread: Thread? = null

    private val audioLock = Any()
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var audioTrackPlaying = false
    private var audioPrefillFrames = 0

    fun attachSurface(surface: Surface?) {
        var needsIdr = false
        synchronized(videoLock) {
            val surfaceChanged = outputSurface !== surface
            outputSurface = surface
            lastDecoderConfigureFailMs = 0L
            if (surface == null || !surface.isValid) {
                releaseVideoDecoderLocked()
                return
            }
            if (surfaceChanged && videoConfigured) {
                releaseVideoDecoderLocked()
            }
            val csd = pendingCsd
            if (csd != null && !videoConfigured) {
                configureVideoDecoderLocked(videoMime, remoteWidth, remoteHeight, csd)
                needsIdr = true
            } else if (surfaceChanged || !videoConfigured) {
                needsIdr = true
            }
        }
        if (needsIdr) requestKeyFrame()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread(::ioLoop, "OpalCastRx").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        try { enqueue(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_DISCONNECT, 0)) } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        releaseDecoders()
    }

    fun requestKeyFrame() {
        enqueue(TYPE_REQUEST_IDR, 0, ByteArray(0))
    }

    fun requestAppList() {
        enqueue(TYPE_LIST_APPS, 0, ByteArray(0))
    }

    fun sendMicConfig(
        enabled: Boolean,
        sampleRate: Int = CastMicCapturer.DEFAULT_SAMPLE_RATE,
        channels: Int = CastMicCapturer.DEFAULT_CHANNELS,
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
        if (closed.get() || pcm.isEmpty() || outQueue.remainingCapacity() <= 32) return
        outQueue.offer(UplinkFrame(TYPE_MIC_FRAME, 0, SystemClock.elapsedRealtimeNanos() / 1000L, pcm))
    }

    fun sendCameraConfig(
        enabled: Boolean,
        width: Int = CastCameraCapturer.DEFAULT_WIDTH,
        height: Int = CastCameraCapturer.DEFAULT_HEIGHT,
        fps: Int = CastCameraCapturer.DEFAULT_FPS,
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
        if (closed.get() || jpeg.isEmpty() || outQueue.remainingCapacity() <= 32) return
        outQueue.offer(UplinkFrame(TYPE_CAMERA_FRAME, 0, SystemClock.elapsedRealtimeNanos() / 1000L, jpeg))
    }

    fun sendResizeConfig(width: Int, height: Int, dpi: Int, fps: Int = reqFps, bitrateKbps: Int = 0) {
        val alignedW = (width.coerceIn(320, 3840) and 7.inv()).coerceAtLeast(320)
        val alignedH = (height.coerceIn(320, 3840) and 7.inv()).coerceAtLeast(320)
        val wireDpi = if (dpi <= 0) 0 else dpi.coerceIn(120, 560)
        val maxCap = if (isUsbTether) ReceiverPolicy.MAX_USB_TETHER_BITRATE_KBPS else ReceiverPolicy.MAX_WIFI_BITRATE_KBPS
        val resolvedBitrate = if (bitrateKbps > 0) {
            bitrateKbps.coerceIn(ReceiverPolicy.MIN_BITRATE_KBPS, maxCap)
        } else {
            ReceiverPolicy.computeTargetBitrateKbps(
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
        enqueue(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(action, arg))
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
        if (AndroidApiCompat.isMouseButtonPress(ev)) {
            if (AndroidApiCompat.extractActionButton(ev) == MotionEvent.BUTTON_BACK) {
                if (sharedSideBackDedup.tryClaimSideButtonBack(SystemClock.uptimeMillis())) {
                    sendSystemAction(SYS_BACK)
                }
            }
            return true
        }
        if (AndroidApiCompat.isMouseButtonRelease(ev)) return true
        return when (ev.actionMasked) {
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
        if (sideNavTouchFilter.shouldSuppress(masked, isMouseMotion(ev), ev.buttonState)) return
        val rw = remoteWidth.coerceAtLeast(2).toFloat()
        val rh = remoteHeight.coerceAtLeast(2).toFloat()
        val rx = (ev.x * (rw / viewW)).coerceIn(0f, rw - 1f)
        val ry = (ev.y * (rh / viewH)).coerceIn(0f, rh - 1f)
        val isBtnPress = AndroidApiCompat.isMouseButtonPress(ev)
        val isBtnRelease = AndroidApiCompat.isMouseButtonRelease(ev)
        val mouseAction = when {
            masked == MotionEvent.ACTION_DOWN || isBtnPress -> 1
            masked == MotionEvent.ACTION_UP || isBtnRelease -> 2
            masked == MotionEvent.ACTION_SCROLL -> 3
            masked == MotionEvent.ACTION_HOVER_EXIT -> 4
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
        val actBtn = when {
            isBtnPress || isBtnRelease -> AndroidApiCompat.extractActionButton(ev)
            masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_UP ->
                if (ev.buttonState != 0) ev.buttonState else MotionEvent.BUTTON_PRIMARY
            else -> 0
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
        outQueue.offer(UplinkFrame(type, flags, SystemClock.elapsedRealtimeNanos() / 1000L, payload))
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
            val secureProtocols = ReceiverPolicy.filterSecureTlsProtocols(ssl.supportedProtocols)
            if (secureProtocols.isNotEmpty()) ssl.enabledProtocols = secureProtocols
            ssl.soTimeout = 10_000
            ssl.startHandshake()
            val peerCert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
            val fp = if (peerCert != null) sha256Hex(peerCert.encoded) else ""
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
            val effectiveCodec = when {
                preferredCodec.equals(ReceiverPolicy.WIRE_CODEC_H264, ignoreCase = true) -> ReceiverPolicy.WIRE_CODEC_H264
                preferredCodec.equals(ReceiverPolicy.WIRE_CODEC_HEVC, ignoreCase = true) -> ReceiverPolicy.WIRE_CODEC_HEVC
                else -> AndroidApiCompat.resolvePreferredCastCodec(ReceiverPolicy.CODEC_PREF_AUTO)
            }
            val (ssl, peerFp) = openTlsSocket()
            socket = ssl
            val input = BufferedInputStream(ssl.inputStream, 131_072)
            val output = BufferedOutputStream(ssl.outputStream, 65_536)

            val initialBitrateKbps = ReceiverPolicy.computeTargetBitrateKbps(
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
                put("preferredCodec", effectiveCodec)
                put("width", reqWidth)
                put("height", reqHeight)
                put("dpi", reqDpi)
                put("fps", reqFps)
                put("bitrateKbps", initialBitrateKbps)
                put("displayMode", initialDisplayMode)
                put("audioEnabled", audioEnabled)
                put("authToken", savedAuthToken)
                put("pinCode", "")
                put("transportHint", ReceiverPolicy.resolveTransportHint(isUsbTether))
                put("clientPlatform", CLIENT_PLATFORM_ANDROID)
                put("sessionRole", sessionRole)
                if (appPackage.isNotEmpty()) put("appPackage", appPackage)
                if (appActivity.isNotEmpty()) put("appActivity", appActivity)
            }.toString().toByteArray(StandardCharsets.UTF_8)
            writeFrame(output, TYPE_HELLO, 0, SystemClock.elapsedRealtimeNanos() / 1000L, helloJson)
            output.flush()

            if (savedAuthToken.isBlank()) {
                listener.onWaitingForApproval()
            } else {
                Thread({
                    SystemClock.sleep(320L)
                    if (!authCompleted.get() && running.get() && !closed.get()) {
                        listener.onWaitingForApproval()
                    }
                }, "OpalCastApprovalHint").apply {
                    isDaemon = true
                    start()
                }
            }

            val txThread = Thread({ sendLoop(output) }, "OpalCastTx").apply {
                isDaemon = true
                start()
            }
            val audioThread = if (sessionRole != SESSION_ROLE_APP_CATALOG && audioEnabled) {
                Thread({ audioDecodeLoop() }, "OpalCastAudioDec").apply {
                    isDaemon = true
                    start()
                }
            } else {
                null
            }

            lastRxActivityMs.set(SystemClock.uptimeMillis())
            while (running.get() && !closed.get()) {
                val frame = readFrame(input) ?: break
                lastRxActivityMs.set(SystemClock.uptimeMillis())
                handleDownlinkFrame(frame, peerFp, ssl)
            }
            txThread.interrupt()
            audioThread?.interrupt()
        } catch (t: Throwable) {
            if (!closed.get()) {
                val msg = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Cast session error (role=$sessionRole): $msg")
                disconnectReason = msg
                listener.onError(msg)
            }
        } finally {
            running.set(false)
            closed.set(true)
            try { socket?.close() } catch (_: Throwable) {}
            releaseDecoders()
            listener.onDisconnected(disconnectReason)
        }
    }

    private fun sendLoop(out: OutputStream) {
        var lastPingMs = SystemClock.uptimeMillis()
        var pingSeq = 1L
        val rawBatch = ArrayList<UplinkFrame>(MAX_TX_BATCH)
        val coalescedBatch = ArrayList<UplinkFrame>(MAX_TX_BATCH)
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
                    UplinkCoalescer.coalesceBatch(rawBatch, coalescedBatch)
                    for (i in 0 until coalescedBatch.size) {
                        val pkt = coalescedBatch[i]
                        writeFrame(out, pkt.type, pkt.flags, pkt.tsUs, pkt.payload)
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
                    writeFrame(out, TYPE_PING, 0, now * 1000L, pingPayload)
                    wroteAny = true
                }
                if (wroteAny) out.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleDownlinkFrame(frame: RxFrame, peerFp: String, ssl: SSLSocket) {
        when (frame.type) {
            TYPE_AUTH_RESULT -> {
                authCompleted.set(true)
                try { ssl.soTimeout = 0 } catch (_: Throwable) {}
                if (frame.payload.isEmpty()) return
                val ok = frame.payload[0].toInt() != 0
                val jsonText = if (frame.payload.size > 1) {
                    String(frame.payload, 1, frame.payload.size - 1, StandardCharsets.UTF_8)
                } else {
                    "{}"
                }
                val obj = try { JSONObject(jsonText) } catch (_: Throwable) { JSONObject() }
                if (ok) {
                    val peerName = obj.optString("deviceName", host)
                    val token = obj.optString("authToken", "")
                    val permanent = obj.optBoolean("permanent", token.isNotEmpty())
                    val fp = obj.optString("certFingerprint", peerFp)
                    listener.onAuthenticated(peerName, token, permanent, fp)
                } else {
                    val err = obj.optString("message", "对方拒绝了连接请求")
                    listener.onError(err)
                    stop()
                }
            }
            TYPE_DEVICE_STATE -> handleDeviceState(frame.payload)
            TYPE_APP_LIST -> handleAppList(frame.payload)
            TYPE_IME_REQUEST -> handleImeRequest(frame.payload)
            TYPE_MIC_STATE -> handleMicState(frame.payload)
            TYPE_CAMERA_STATE -> handleCameraState(frame.payload)
            TYPE_VIDEO_CONFIG -> handleVideoConfig(frame.payload)
            TYPE_VIDEO_FRAME -> handleVideoFrame(frame.payload, frame.length, frame.flags, frame.timestampUs)
            TYPE_AUDIO_CONFIG -> handleAudioConfig(frame.payload)
            TYPE_AUDIO_FRAME -> enqueueAudioFrame(frame.payload, frame.timestampUs)
            TYPE_PONG -> Unit
        }
    }

    private fun handleDeviceState(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            if (obj.has("blackout")) listener.onRemoteBlackoutChanged(obj.optBoolean("blackout", false))
            if (obj.has("fullscreenWindowTitle")) {
                listener.onRemoteFullscreenChanged(obj.optString("fullscreenWindowTitle", "").trim())
            }
            if (obj.has("displayMode")) {
                val mode = obj.optInt("displayMode", DISPLAY_MODE_DEX)
                if (mode == DISPLAY_MODE_DEX || mode == DISPLAY_MODE_MIRROR) {
                    listener.onDisplayModeChanged(mode)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleAppList(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val root = JSONObject(String(payload, StandardCharsets.UTF_8))
            val arr = root.optJSONArray("apps")
            val out = ArrayList<RemoteAppEntry>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val pkg = o.optString("pkg", "").trim()
                    if (pkg.isEmpty()) continue
                    out.add(
                        RemoteAppEntry(
                            pkg = pkg,
                            cls = o.optString("cls", ""),
                            label = o.optString("label", pkg).ifBlank { pkg },
                            openCount = o.optInt("openCount", 0).coerceAtLeast(0),
                        ),
                    )
                }
            }
            listener.onAppListReceived(out)
        } catch (_: Throwable) {
        }
    }

    private fun handleImeRequest(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            listener.onRemoteImeRequest(obj.optBoolean("show", true), obj.optInt("inputType", 0))
        } catch (_: Throwable) {
        }
    }

    private fun handleMicState(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            listener.onRemoteMicState(
                obj.optBoolean("active", false),
                obj.optBoolean("supported", false),
                obj.optString("message", ""),
            )
        } catch (_: Throwable) {
        }
    }

    private fun handleCameraState(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            listener.onRemoteCameraState(
                obj.optBoolean("active", false),
                obj.optBoolean("supported", false),
                obj.optBoolean("rootRequired", true),
                obj.optString("message", ""),
                obj.optString("facing", ""),
            )
        } catch (_: Throwable) {
        }
    }

    private fun enqueueAudioFrame(payload: ByteArray, tsUs: Long) {
        if (payload.isEmpty()) return
        if (audioQueue.size >= 4) {
            while (audioQueue.size > 2) {
                if (audioQueue.poll() == null) break
            }
        }
        if (!audioQueue.offer(payload to tsUs)) {
            audioQueue.poll()
            audioQueue.offer(payload to tsUs)
        }
    }

    private fun audioDecodeLoop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        while (running.get() && !closed.get()) {
            try {
                val item = audioQueue.poll(AUDIO_IDLE_PAUSE_MS, TimeUnit.MILLISECONDS)
                if (item == null) {
                    pauseAudioTrackOnIdle()
                    continue
                }
                handleAudioFrame(item.first, item.second)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
            }
        }
    }

    private fun pauseAudioTrackOnIdle() {
        synchronized(audioLock) {
            val track = audioTrack ?: return
            if (audioTrackPlaying) {
                try {
                    track.pause()
                    track.flush()
                } catch (_: Throwable) {
                }
                audioTrackPlaying = false
                audioPrefillFrames = 0
            }
        }
    }

    private fun handleVideoConfig(payload: ByteArray) {
        if (payload.size < 11) return
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val codecId = bb.get().toInt() and 0xFF
        val w = bb.short.toInt() and 0xFFFF
        val h = bb.short.toInt() and 0xFFFF
        bb.short
        bb.get()
        val fpsX100 = bb.short.toInt() and 0xFFFF
        val displayMode = bb.get().toInt() and 0xFF
        val csd = if (payload.size > 11) payload.copyOfRange(11, payload.size) else ByteArray(0)
        val mime = if (codecId == CODEC_HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val fps = (fpsX100 / 100).coerceIn(30, 165)
        remoteWidth = w.coerceAtLeast(2)
        remoteHeight = h.coerceAtLeast(2)
        remoteFps = fps
        if (displayMode == DISPLAY_MODE_DEX || displayMode == DISPLAY_MODE_MIRROR) {
            listener.onDisplayModeChanged(displayMode)
        }
        listener.onVideoSizeChanged(remoteWidth, remoteHeight, fps)

        synchronized(videoLock) {
            if (videoConfigured && videoMime == mime &&
                configuredVideoW == remoteWidth && configuredVideoH == remoteHeight &&
                pendingCsd?.contentEquals(csd) == true
            ) {
                return
            }
            videoMime = mime
            pendingCsd = csd
            lastDecoderConfigureFailMs = 0L
            configureVideoDecoderLocked(mime, remoteWidth, remoteHeight, csd)
        }
    }

    private fun configureVideoDecoderLocked(mime: String, width: Int, height: Int, csd: ByteArray) {
        val surf = outputSurface
        if (surf == null || !surf.isValid) {
            videoConfigured = false
            return
        }
        releaseVideoDecoderLocked()
        AndroidApiCompat.configureSurfaceFrameRate(surf, remoteFps)

        val preferred = AndroidApiCompat.selectPreferredDecoder(mime)
        val candidates = ArrayList<Pair<String?, Boolean>>(3).apply {
            if (preferred.hwName != null) add(preferred.hwName to preferred.supportsLowLatency)
            add(null to false)
            if (preferred.swName != null && preferred.swName != preferred.hwName) {
                add(preferred.swName to false)
            }
        }
        var lastErr: Throwable? = null
        for ((codecName, supportsLowLatency) in candidates) {
            val plans = UplinkCoalescer.buildDecoderConfigPlans(remoteFps, supportsLowLatency, Build.VERSION.SDK_INT)
            for (plan in plans) {
                var codec: MediaCodec? = null
                try {
                    codec = if (codecName != null) {
                        MediaCodec.createByCodecName(codecName)
                    } else {
                        MediaCodec.createDecoderByType(mime)
                    }
                    val fmt = MediaFormat.createVideoFormat(mime, width, height)
                    AndroidApiCompat.applyVideoDecoderPlan(fmt, plan)
                    if (csd.isNotEmpty()) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                    codec.configure(fmt, surf, null, 0)
                    codec.start()
                    videoCodec = codec
                    configuredVideoW = width
                    configuredVideoH = height
                    videoConfigured = true
                    awaitingInitialKeyFrame = true
                    lastDecoderConfigureFailMs = 0L
                    startVideoDrainThreadLocked(codec)
                    return
                } catch (t: Throwable) {
                    lastErr = t
                    try { codec?.release() } catch (_: Throwable) {}
                }
            }
        }
        lastDecoderConfigureFailMs = SystemClock.uptimeMillis()
        Log.w(TAG, "Video decoder configure failed ($mime ${width}x${height})", lastErr)
        releaseVideoDecoderLocked()
    }

    private fun startVideoDrainThreadLocked(codec: MediaCodec) {
        videoDrainThread = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
            val info = MediaCodec.BufferInfo()
            while (running.get() && !closed.get() && videoCodec === codec) {
                try {
                    var latestIdx = codec.dequeueOutputBuffer(info, 12_000L)
                    if (latestIdx >= 0) {
                        while (true) {
                            val nextIdx = codec.dequeueOutputBuffer(info, 0L)
                            if (nextIdx >= 0) {
                                codec.releaseOutputBuffer(latestIdx, false)
                                latestIdx = nextIdx
                            } else {
                                break
                            }
                        }
                        codec.releaseOutputBuffer(latestIdx, true)
                    }
                } catch (_: Throwable) {
                    break
                }
            }
        }, "OpalCastVideoOut").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleVideoFrame(payload: ByteArray, length: Int, flags: Int, tsUs: Long) {
        if (length <= 0 || length > payload.size) return
        val codec = synchronized(videoLock) {
            if (!videoConfigured && pendingCsd != null) {
                val now = SystemClock.uptimeMillis()
                if (lastDecoderConfigureFailMs == 0L || now - lastDecoderConfigureFailMs >= DECODER_RETRY_COOLDOWN_MS) {
                    configureVideoDecoderLocked(videoMime, remoteWidth, remoteHeight, pendingCsd!!)
                }
            }
            videoCodec
        } ?: return

        val isKey = (flags and FLAG_VIDEO_KEYFRAME) != 0
        if (awaitingInitialKeyFrame) {
            if (!isKey) {
                requestKeyFrameThrottled()
                return
            }
            awaitingInitialKeyFrame = false
        }

        try {
            val inIdx = codec.dequeueInputBuffer(12_000L)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)
                if (buf != null && buf.capacity() >= length) {
                    buf.clear()
                    buf.put(payload, 0, length)
                    val mcFlags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    codec.queueInputBuffer(inIdx, 0, length, tsUs, mcFlags)
                } else {
                    codec.queueInputBuffer(inIdx, 0, 0, tsUs, 0)
                }
            } else {
                requestKeyFrameThrottled()
            }
        } catch (t: Throwable) {
            val nowMs = SystemClock.uptimeMillis()
            val prevMs = lastVideoDecodeErrLogMs.get()
            if (prevMs == 0L || nowMs - prevMs >= 5_000L) {
                if (lastVideoDecodeErrLogMs.compareAndSet(prevMs, nowMs)) {
                    Log.w(TAG, "Video decode frame error", t)
                }
            }
            synchronized(videoLock) {
                if (videoCodec === codec) releaseVideoDecoderLocked()
            }
            requestKeyFrame()
        }
    }

    private fun handleAudioConfig(payload: ByteArray) {
        if (payload.size < 6) return
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        bb.get()
        val sampleRate = bb.int.coerceIn(16_000, 96_000)
        val channels = (bb.get().toInt() and 0xFF).coerceIn(1, 2)
        val asc = if (payload.size > 6) payload.copyOfRange(6, payload.size) else ByteArray(0)

        synchronized(audioLock) {
            releaseAudioDecoderLocked()
            try {
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
                fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                if (asc.isNotEmpty()) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                codec.configure(fmt, null, null, 0)
                codec.start()

                val chMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
                    .coerceAtLeast(4096)
                val trackBufBytes = ReceiverPolicy.computeAudioTrackBufferBytes(minBuf, sampleRate, channels)
                val track = AndroidApiCompat.createAudioTrack(sampleRate, channels, trackBufBytes)
                audioCodec = codec
                audioTrack = track
                audioTrackPlaying = false
                audioPrefillFrames = 0
            } catch (t: Throwable) {
                Log.w(TAG, "Audio decoder init failed", t)
                releaseAudioDecoderLocked()
            }
        }
    }

    private fun handleAudioFrame(payload: ByteArray, tsUs: Long) {
        if (payload.isEmpty()) return
        synchronized(audioLock) {
            val codec = audioCodec ?: return
            val track = audioTrack ?: return
            try {
                val inIdx = codec.dequeueInputBuffer(8_000L)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    if (buf != null && buf.capacity() >= payload.size) {
                        buf.clear()
                        buf.put(payload)
                        codec.queueInputBuffer(inIdx, 0, payload.size, tsUs, 0)
                    }
                }
                val info = audioOutBufInfo
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            if (!audioTrackPlaying) {
                                writePcmBlocking(track, outBuf, info.size)
                                audioPrefillFrames++
                                if (audioPrefillFrames >= AUDIO_PREFILL_FRAMES) {
                                    track.play()
                                    audioTrackPlaying = true
                                }
                            } else {
                                writePcmBlocking(track, outBuf, info.size)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    } else {
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun writePcmBlocking(track: AudioTrack, src: ByteBuffer, totalBytes: Int) {
        var remaining = totalBytes
        while (remaining > 0 && running.get() && !closed.get()) {
            val written = track.write(src, remaining, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            remaining -= written
        }
    }

    private fun releaseDecoders() {
        synchronized(videoLock) { releaseVideoDecoderLocked() }
        synchronized(audioLock) { releaseAudioDecoderLocked() }
    }

    private fun releaseVideoDecoderLocked() {
        videoConfigured = false
        val c = videoCodec
        videoCodec = null
        val t = videoDrainThread
        videoDrainThread = null
        t?.interrupt()
        if (c != null) {
            try { c.stop() } catch (_: Throwable) {}
            try { c.release() } catch (_: Throwable) {}
        }
    }

    private fun releaseAudioDecoderLocked() {
        audioQueue.clear()
        audioTrackPlaying = false
        audioPrefillFrames = 0
        val t = audioTrack
        audioTrack = null
        if (t != null) {
            try { t.stop() } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
        }
        val c = audioCodec
        audioCodec = null
        if (c != null) {
            try { c.stop() } catch (_: Throwable) {}
            try { c.release() } catch (_: Throwable) {}
        }
    }

    private data class RxFrame(
        val type: Int,
        val flags: Int,
        val timestampUs: Long,
        val payload: ByteArray,
        val length: Int = payload.size,
    )

    private fun requestKeyFrameThrottled() {
        val nowMs = SystemClock.uptimeMillis()
        val prev = lastIdrRequestMs.get()
        if (prev == 0L || nowMs - prev >= IDR_THROTTLE_MS) {
            if (lastIdrRequestMs.compareAndSet(prev, nowMs)) requestKeyFrame()
        }
    }

    private fun writeFrame(out: OutputStream, type: Int, flags: Int, tsUs: Long, payload: ByteArray) {
        val hdr = txHeaderBuf
        hdr[0] = 0x4F
        hdr[1] = 0x50
        hdr[2] = 0x44
        hdr[3] = 0x31
        hdr[4] = (type and 0xFF).toByte()
        hdr[5] = (flags and 0xFF).toByte()
        val len = payload.size
        hdr[6] = ((len ushr 24) and 0xFF).toByte()
        hdr[7] = ((len ushr 16) and 0xFF).toByte()
        hdr[8] = ((len ushr 8) and 0xFF).toByte()
        hdr[9] = (len and 0xFF).toByte()
        hdr[10] = ((tsUs ushr 56) and 0xFF).toByte()
        hdr[11] = ((tsUs ushr 48) and 0xFF).toByte()
        hdr[12] = ((tsUs ushr 40) and 0xFF).toByte()
        hdr[13] = ((tsUs ushr 32) and 0xFF).toByte()
        hdr[14] = ((tsUs ushr 24) and 0xFF).toByte()
        hdr[15] = ((tsUs ushr 16) and 0xFF).toByte()
        hdr[16] = ((tsUs ushr 8) and 0xFF).toByte()
        hdr[17] = (tsUs and 0xFF).toByte()
        out.write(hdr)
        if (len > 0) out.write(payload)
    }

    private fun readFrame(input: InputStream): RxFrame? {
        val hdr = rxHeaderBuf
        val first = input.read()
        if (first < 0) return null
        hdr[0] = first.toByte()
        readFully(input, hdr, 1, HEADER_SIZE - 1)
        if (hdr[0] != 0x4F.toByte() || hdr[1] != 0x50.toByte() ||
            hdr[2] != 0x44.toByte() || hdr[3] != 0x31.toByte()
        ) {
            throw IOException("Invalid OpalCast frame magic")
        }
        val type = hdr[4].toInt() and 0xFF
        val flags = hdr[5].toInt() and 0xFF
        val len = ((hdr[6].toInt() and 0xFF) shl 24) or
            ((hdr[7].toInt() and 0xFF) shl 16) or
            ((hdr[8].toInt() and 0xFF) shl 8) or
            (hdr[9].toInt() and 0xFF)
        if (len < 0 || len > MAX_PAYLOAD_LEN) {
            throw IOException("Invalid OpalCast payload length: $len")
        }
        val tsUs = ((hdr[10].toLong() and 0xFF) shl 56) or
            ((hdr[11].toLong() and 0xFF) shl 48) or
            ((hdr[12].toLong() and 0xFF) shl 40) or
            ((hdr[13].toLong() and 0xFF) shl 32) or
            ((hdr[14].toLong() and 0xFF) shl 24) or
            ((hdr[15].toLong() and 0xFF) shl 16) or
            ((hdr[16].toLong() and 0xFF) shl 8) or
            (hdr[17].toLong() and 0xFF)
        if (len == 0) return RxFrame(type, flags, tsUs, ByteArray(0), 0)
        if (type == TYPE_VIDEO_FRAME) {
            if (rxVideoScratch.size < len) {
                rxVideoScratch = ByteArray(maxOf(len, rxVideoScratch.size * 2).coerceAtMost(MAX_PAYLOAD_LEN))
            }
            readFully(input, rxVideoScratch, 0, len)
            return RxFrame(type, flags, tsUs, rxVideoScratch, len)
        }
        val payload = ByteArray(len)
        readFully(input, payload, 0, len)
        return RxFrame(type, flags, tsUs, payload, len)
    }

    private fun readFully(input: InputStream, dst: ByteArray, off: Int, len: Int) {
        var pos = off
        var rem = len
        while (rem > 0) {
            val n = input.read(dst, pos, rem)
            if (n < 0) throw EOFException("Unexpected EOF in OpalCast stream")
            pos += n
            rem -= n
        }
    }

    private fun encodeSystemAction(action: Int, arg: Int): ByteArray =
        ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put((action and 0xFF).toByte())
            .putInt(arg)
            .array()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
