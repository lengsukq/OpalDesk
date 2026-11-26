package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Pure Kotlin OpalCast v1.1 TLS Peer Receiver Client (100% wire-compatible with
 * `OpalCastProtocol.java` & `RemoteStreamServer.java`):
 * - TLS 1.2/1.3 + PIN-free host interactive consent / permanent authToken authentication
 * - Supports `desktop`, `standalone_app`, and `app_catalog` session roles
 * - Hardware-accelerated HEVC / H.264 video decoding directly onto a GPU [Surface]
 * - Hardware AAC-LC audio decoding to low-latency [AudioTrack]
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
    private val audioEnabled: Boolean = (sessionRole == SESSION_ROLE_DESKTOP),
    private val targetNetwork: Network? = null,
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
        fun onVideoSizeChanged(width: Int, height: Int, fps: Int)
        fun onError(message: String)
        fun onDisconnected(reason: String)
    }

    companion object {
        private const val TAG = "OpalPeerClient"
        private const val HEADER_SIZE = 18
        private const val MAX_PAYLOAD_LEN = 16 * 1024 * 1024

        const val SESSION_ROLE_DESKTOP = "desktop"
        const val SESSION_ROLE_STANDALONE_APP = "standalone_app"
        const val SESSION_ROLE_APP_CATALOG = "app_catalog"

        private const val TYPE_VIDEO_CONFIG = 0x01
        private const val TYPE_VIDEO_FRAME = 0x02
        private const val TYPE_AUDIO_CONFIG = 0x03
        private const val TYPE_AUDIO_FRAME = 0x04
        private const val TYPE_AUTH_RESULT = 0x12
        private const val TYPE_APP_LIST = 0x15
        private const val TYPE_IME_REQUEST = 0x16
        private const val TYPE_MIC_STATE = 0x17
        private const val TYPE_CAMERA_STATE = 0x18

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

        private const val CODEC_H264 = 1
        private const val CODEC_HEVC = 2
        private const val FLAG_VIDEO_KEYFRAME = 0x01

        const val SYS_HOME = 1
        const val SYS_BACK = 2
        const val SYS_RECENTS = 3
        const val SYS_TOGGLE_DISPLAY_MODE = 4
        const val SYS_DISCONNECT = 8
    }

    private data class OutPacket(val type: Int, val flags: Int, val tsUs: Long, val payload: ByteArray)

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val authCompleted = AtomicBoolean(false)
    private val outQueue = LinkedBlockingQueue<OutPacket>(256)
    private val audioQueue = LinkedBlockingQueue<Pair<ByteArray, Long>>(8)
    private val textSeq = AtomicLong(1L)
    private val sideNavTouchFilter = CompositorGeometry.SideNavigationTouchFilter()

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
    private var configuredVideoW: Int = 0
    private var configuredVideoH: Int = 0
    private var videoConfigured = false
    @Volatile
    private var videoDrainThread: Thread? = null

    private val audioLock = Any()
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var audioDecodeThread: Thread? = null

    fun attachSurface(surface: Surface?) {
        synchronized(videoLock) {
            val previous = outputSurface
            outputSurface = surface
            if (surface == null || !surface.isValid) {
                releaseVideoDecoderLocked()
                return
            }
            if (previous != surface && videoConfigured) {
                releaseVideoDecoderLocked()
            }
            if (pendingCsd != null && !videoConfigured) {
                configureVideoDecoderLocked(videoMime, remoteWidth, remoteHeight, pendingCsd!!)
            }
        }
        requestKeyFrame()
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
            enqueue(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_DISCONNECT, 0))
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
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
        // Reserve headroom in outQueue for control/input frames; drop media frames under backpressure.
        if (outQueue.remainingCapacity() <= 32) return
        outQueue.offer(OutPacket(TYPE_MIC_FRAME, 0, SystemClock.elapsedRealtimeNanos() / 1000L, pcm))
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
        // Reserve headroom in outQueue for control/input frames; drop stale video frames under backpressure.
        if (outQueue.remainingCapacity() <= 32) return
        outQueue.offer(OutPacket(TYPE_CAMERA_FRAME, 0, SystemClock.elapsedRealtimeNanos() / 1000L, jpeg))
    }

    fun sendResizeConfig(width: Int, height: Int, dpi: Int, fps: Int = reqFps, bitrateKbps: Int = 9_000) {
        val alignedW = (width.coerceIn(320, 2560) and 7.inv()).coerceAtLeast(320)
        val alignedH = (height.coerceIn(320, 2560) and 7.inv()).coerceAtLeast(320)
        val wireDpi = if (dpi <= 0) 0 else dpi.coerceIn(120, 560)
        val bb = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(alignedW.toShort())
        bb.putShort(alignedH.toShort())
        bb.putShort(wireDpi.toShort())
        bb.putShort((fps.coerceIn(30, 120) * 100).toShort())
        bb.putInt(bitrateKbps.coerceIn(2500, 24_000))
        bb.put(0xFF.toByte()) // DISPLAY_MODE_UNCHANGED
        bb.put(0xFF.toByte()) // rotation unchanged
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

    /**
     * Sends UTF-8 text (Chinese Pinyin, emoji, pasted phrases) via OpalCast `TYPE_INPUT_TEXT`
     * matching `OpalCastProtocol.decodeTextEvent` (mode 0 = commit + paste, mode 1 = clipboard only).
     */
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
                    sendSystemAction(SYS_BACK)
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
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> 1 // MOUSE_BUTTON_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> 2 // MOUSE_BUTTON_UP
            MotionEvent.ACTION_SCROLL -> 3 // MOUSE_SCROLL
            MotionEvent.ACTION_HOVER_EXIT -> 4 // MOUSE_HOVER_EXIT
            else -> 0 // MOUSE_MOVE_ABS
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
        outQueue.offer(OutPacket(type, flags, SystemClock.elapsedRealtimeNanos() / 1000L, payload))
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
        val ssl: SSLSocket = if (targetNetwork != null) {
            // Bind via explicit Network.socketFactory so Android netd fwmark routes via USB Ethernet
            // even when Wi-Fi is simultaneously active as the default network.
            val raw = targetNetwork.socketFactory.createSocket()
            raw.tcpNoDelay = true
            try { raw.receiveBufferSize = 512 * 1024 } catch (_: Throwable) {}
            try { raw.sendBufferSize = 256 * 1024 } catch (_: Throwable) {}
            raw.connect(InetSocketAddress(host, port), 6_000)
            ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        } else {
            (ctx.socketFactory.createSocket() as SSLSocket).apply {
                tcpNoDelay = true
                try { receiveBufferSize = 512 * 1024 } catch (_: Throwable) {}
                try { sendBufferSize = 256 * 1024 } catch (_: Throwable) {}
                connect(InetSocketAddress(host, port), 6_000)
            }
        }
        ssl.soTimeout = 10_000
        ssl.startHandshake()
        val peerCert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
        val fp = if (peerCert != null) sha256Hex(peerCert.encoded) else ""
        // Allow up to 70s for interactive host-side approval before clearing socket timeout on AUTH_RESULT.
        ssl.soTimeout = 70_000
        return ssl to fp
    }

    private val lastVideoDecodeErrLogMs = AtomicLong(0L)

    private fun ioLoop() {
        var disconnectReason = "已断开连接"
        try {
            OpalLogStore.i(
                TAG,
                "Connecting role=$sessionRole port=$port ${reqWidth}x${reqHeight}@${reqFps}fps " +
                    "audio=$audioEnabled pkg=${appPackage.ifBlank { "-" }}",
            )
            val (ssl, peerFp) = openTlsSocket()
            socket = ssl
            val input = BufferedInputStream(ssl.inputStream, 131_072)
            val output = BufferedOutputStream(ssl.outputStream, 65_536)

            // 1. Send OpalCast v1.1 HELLO (PIN-free: uses permanent authToken or triggers host consent prompt)
            val helloJson = JSONObject().apply {
                put("protocolVersion", 1)
                put("clientName", Build.MODEL ?: "Android")
                put("clientId", clientId)
                put("preferredCodec", "hevc")
                put("width", reqWidth)
                put("height", reqHeight)
                put("dpi", reqDpi)
                put("fps", reqFps)
                put("bitrateKbps", if (sessionRole == SESSION_ROLE_STANDALONE_APP) 9_000 else 16_000)
                put("displayMode", 0)
                put("audioEnabled", audioEnabled)
                put("authToken", savedAuthToken)
                put("pinCode", "")
                put("transportHint", "wifi_tls")
                put("sessionRole", sessionRole)
                if (appPackage.isNotEmpty()) put("appPackage", appPackage)
                if (appActivity.isNotEmpty()) put("appActivity", appActivity)
            }.toString().toByteArray(StandardCharsets.UTF_8)
            writeFrame(output, TYPE_HELLO, 0, SystemClock.elapsedRealtimeNanos() / 1000L, helloJson)
            output.flush()

            // Notify UI promptly if the host is waiting for manual user approval (including revoked token fallback)
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

            // 2. Start dedicated sender + async audio decode threads so neither blocks video RX
            val txThread = Thread({ sendLoop(output) }, "OpalPeerClientTx").apply {
                isDaemon = true
                start()
            }
            val audioThread = if (sessionRole != SESSION_ROLE_APP_CATALOG && audioEnabled) {
                Thread({ audioDecodeLoop() }, "OpalPeerAudioDec").apply {
                    isDaemon = true
                    start()
                }
            } else {
                null
            }
            audioDecodeThread = audioThread

            // 3. Receive loop
            while (running.get() && !closed.get()) {
                val frame = readFrame(input) ?: break
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
            releaseDecoders()
            listener.onDisconnected(disconnectReason)
        }
    }

    private fun sendLoop(out: OutputStream) {
        var lastPingMs = SystemClock.uptimeMillis()
        var pingSeq = 1L
        try {
            while (running.get() && !closed.get()) {
                val pkt = outQueue.poll(1000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (pkt != null) {
                    writeFrame(out, pkt.type, pkt.flags, pkt.tsUs, pkt.payload)
                    out.flush()
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastPingMs >= 4_000L) {
                    lastPingMs = now
                    val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(pingSeq++)
                    writeFrame(out, TYPE_PING, 0, now * 1000L, bb.array())
                    out.flush()
                }
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
                    OpalLogStore.i(TAG, "Authenticated role=$sessionRole peer=$peerName permanent=$permanent")
                    listener.onAuthenticated(peerName, token, permanent, fp)
                } else {
                    val err = obj.optString("message", "对方拒绝了连接请求")
                    OpalLogStore.w(TAG, "Authentication rejected role=$sessionRole: $err")
                    listener.onError(err)
                    stop()
                }
            }
            TYPE_APP_LIST -> handleAppList(frame.payload)
            TYPE_IME_REQUEST -> handleImeRequest(frame.payload)
            TYPE_MIC_STATE -> handleMicState(frame.payload)
            TYPE_CAMERA_STATE -> handleCameraState(frame.payload)
            TYPE_VIDEO_CONFIG -> handleVideoConfig(frame.payload)
            TYPE_VIDEO_FRAME -> handleVideoFrame(frame.payload, frame.flags, frame.timestampUs)
            TYPE_AUDIO_CONFIG -> handleAudioConfig(frame.payload)
            TYPE_AUDIO_FRAME -> enqueueAudioFrame(frame.payload, frame.timestampUs)
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
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "Failed to parse APP_LIST", t)
        }
    }

    private fun handleImeRequest(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            val show = obj.optBoolean("show", true)
            val inputType = obj.optInt("inputType", 0)
            OpalLogStore.i(TAG, "RX IME_REQUEST show=$show inputType=0x${inputType.toString(16)}")
            listener.onRemoteImeRequest(show, inputType)
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "Failed to parse IME_REQUEST", t)
        }
    }

    private fun handleMicState(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            val active = obj.optBoolean("active", false)
            val supported = obj.optBoolean("supported", false)
            val message = obj.optString("message", "")
            OpalLogStore.i(TAG, "RX MIC_STATE active=$active supported=$supported msg=$message")
            listener.onRemoteMicState(active, supported, message)
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "Failed to parse MIC_STATE", t)
        }
    }

    private fun handleCameraState(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            val active = obj.optBoolean("active", false)
            val supported = obj.optBoolean("supported", false)
            val rootRequired = obj.optBoolean("rootRequired", true)
            val message = obj.optString("message", "")
            val facing = obj.optString("facing", "")
            OpalLogStore.i(TAG, "RX CAMERA_STATE active=$active supported=$supported rootReq=$rootRequired facing=$facing msg=$message")
            listener.onRemoteCameraState(active, supported, rootRequired, message, facing)
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "Failed to parse CAMERA_STATE", t)
        }
    }

    private fun enqueueAudioFrame(payload: ByteArray, tsUs: Long) {
        if (payload.isEmpty()) return
        if (!audioQueue.offer(payload to tsUs)) {
            audioQueue.poll()
            audioQueue.offer(payload to tsUs)
        }
    }

    private fun audioDecodeLoop() {
        while (running.get() && !closed.get()) {
            try {
                val item = audioQueue.poll(250L, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                handleAudioFrame(item.first, item.second)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleVideoConfig(payload: ByteArray) {
        if (payload.size < 11) return
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val codecId = bb.get().toInt() and 0xFF
        val w = bb.short.toInt() and 0xFFFF
        val h = bb.short.toInt() and 0xFFFF
        bb.short // dpi
        bb.get() // rotation
        val fpsX100 = bb.short.toInt() and 0xFFFF
        bb.get() // displayMode
        val csd = if (payload.size > 11) payload.copyOfRange(11, payload.size) else ByteArray(0)
        val mime = if (codecId == CODEC_HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val fps = (fpsX100 / 100).coerceIn(30, 165)
        remoteWidth = w.coerceAtLeast(2)
        remoteHeight = h.coerceAtLeast(2)
        remoteFps = fps
        listener.onVideoSizeChanged(remoteWidth, remoteHeight, fps)

        synchronized(videoLock) {
            if (videoConfigured
                && videoMime == mime
                && configuredVideoW == remoteWidth
                && configuredVideoH == remoteHeight
                && pendingCsd?.contentEquals(csd) == true
            ) {
                return
            }
            videoMime = mime
            pendingCsd = csd
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
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                surf.setFrameRate(remoteFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (_: Throwable) {
            }
        }
        // Prefer a hardware low-latency decoder (AOSP FEATURE_LowLatency, e.g. c2.*.decoder.low_latency)
        // before falling back to the platform default decoder.
        val preferredName = selectPreferredDecoderName(mime)
        val codecCandidates: List<String?> = if (preferredName != null) listOf(preferredName, null) else listOf(null)
        val opRates = intArrayOf(Short.MAX_VALUE.toInt(), maxOf(120, remoteFps), 0)
        var lastErr: Throwable? = null
        for (codecName in codecCandidates) {
            for (opRate in opRates) {
                var codec: MediaCodec? = null
                try {
                    codec = if (codecName != null) {
                        MediaCodec.createByCodecName(codecName)
                    } else {
                        MediaCodec.createDecoderByType(mime)
                    }
                    val fmt = MediaFormat.createVideoFormat(mime, width, height)
                    fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= 30) {
                        fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    if (opRate > 0) {
                        fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, opRate)
                    }
                    if (csd.isNotEmpty()) {
                        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                    }
                    codec.configure(fmt, surf, null, 0)
                    codec.start()
                    videoCodec = codec
                    configuredVideoW = width
                    configuredVideoH = height
                    videoConfigured = true
                    startVideoDrainThreadLocked(codec)
                    return
                } catch (t: Throwable) {
                    lastErr = t
                    try { codec?.release() } catch (_: Throwable) {}
                }
            }
        }
        OpalLogStore.w(TAG, "Video decoder configure failed ($mime ${width}x${height})", lastErr)
        releaseVideoDecoderLocked()
    }

    private fun selectPreferredDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var hwLowLatency: String? = null
            var hwFirst: String? = null
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                val isSw = try {
                    info.isSoftwareOnly
                } catch (_: Throwable) {
                    val lower = info.name.lowercase()
                    lower.startsWith("omx.google.") || lower.startsWith("c2.android.")
                }
                if (isSw) continue
                val matchedType = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: continue
                if (hwFirst == null) hwFirst = info.name
                val caps = try {
                    info.getCapabilitiesForType(matchedType)
                } catch (_: Throwable) {
                    null
                }
                if (caps != null && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                    hwLowLatency = info.name
                    break
                }
            }
            hwLowLatency ?: hwFirst
        } catch (_: Throwable) {
            null
        }
    }

    private fun startVideoDrainThreadLocked(codec: MediaCodec) {
        val t = Thread({
            val info = MediaCodec.BufferInfo()
            while (running.get() && !closed.get() && videoCodec === codec) {
                try {
                    val outIdx = codec.dequeueOutputBuffer(info, 12_000L)
                    if (outIdx >= 0) {
                        codec.releaseOutputBuffer(outIdx, true)
                    }
                } catch (_: IllegalStateException) {
                    break
                } catch (_: Throwable) {
                    break
                }
            }
        }, "OpalPeerVideoOut").apply {
            isDaemon = true
            start()
        }
        videoDrainThread = t
    }

    private fun handleVideoFrame(payload: ByteArray, flags: Int, tsUs: Long) {
        if (payload.isEmpty()) return
        val codec = synchronized(videoLock) {
            if (!videoConfigured && pendingCsd != null) {
                configureVideoDecoderLocked(videoMime, remoteWidth, remoteHeight, pendingCsd!!)
            }
            videoCodec
        } ?: return

        try {
            val inIdx = codec.dequeueInputBuffer(12_000L)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)
                if (buf != null && buf.capacity() >= payload.size) {
                    buf.clear()
                    buf.put(payload)
                    val mcFlags = if ((flags and FLAG_VIDEO_KEYFRAME) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    codec.queueInputBuffer(inIdx, 0, payload.size, tsUs, mcFlags)
                } else {
                    codec.queueInputBuffer(inIdx, 0, 0, tsUs, 0)
                }
            }
        } catch (t: Throwable) {
            val nowMs = SystemClock.uptimeMillis()
            val prevMs = lastVideoDecodeErrLogMs.get()
            if (prevMs == 0L || nowMs - prevMs >= 5_000L) {
                if (lastVideoDecodeErrLogMs.compareAndSet(prevMs, nowMs)) {
                    OpalLogStore.w(TAG, "Video decode frame error", t)
                }
            }
            synchronized(videoLock) {
                if (videoCodec === codec) {
                    releaseVideoDecoderLocked()
                }
            }
            requestKeyFrame()
        }
    }

    private fun handleAudioConfig(payload: ByteArray) {
        if (payload.size < 6) return
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        bb.get() // codec (AAC-LC)
        val sampleRate = bb.int.coerceIn(16_000, 96_000)
        val channels = (bb.get().toInt() and 0xFF).coerceIn(1, 2)
        val asc = if (payload.size > 6) payload.copyOfRange(6, payload.size) else ByteArray(0)

        synchronized(audioLock) {
            releaseAudioDecoderLocked()
            try {
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
                fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                if (asc.isNotEmpty()) {
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                }
                codec.configure(fmt, null, null, 0)
                codec.start()

                val chMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
                    .coerceAtLeast(4096)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(chMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                audioCodec = codec
                audioTrack = track
            } catch (t: Throwable) {
                OpalLogStore.w(TAG, "Audio decoder init failed", t)
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
                val inIdx = codec.dequeueInputBuffer(4_000L)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    if (buf != null && buf.capacity() >= payload.size) {
                        buf.clear()
                        buf.put(payload)
                        codec.queueInputBuffer(inIdx, 0, payload.size, tsUs, 0)
                    }
                }
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            track.write(outBuf, info.size, AudioTrack.WRITE_NON_BLOCKING)
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

    private data class RxFrame(val type: Int, val flags: Int, val timestampUs: Long, val payload: ByteArray)

    private fun writeFrame(out: OutputStream, type: Int, flags: Int, tsUs: Long, payload: ByteArray) {
        val hdr = ByteArray(HEADER_SIZE)
        hdr[0] = 0x4F // 'O'
        hdr[1] = 0x50 // 'P'
        hdr[2] = 0x44 // 'D'
        hdr[3] = 0x31 // '1'
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
        val hdr = ByteArray(HEADER_SIZE)
        val first = input.read()
        if (first < 0) return null
        hdr[0] = first.toByte()
        readFully(input, hdr, 1, HEADER_SIZE - 1)
        if (hdr[0] != 0x4F.toByte() || hdr[1] != 0x50.toByte()
            || hdr[2] != 0x44.toByte() || hdr[3] != 0x31.toByte()
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
        val payload = if (len == 0) ByteArray(0) else ByteArray(len).also { readFully(input, it, 0, len) }
        return RxFrame(type, flags, tsUs, payload)
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
        for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }
}
