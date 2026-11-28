package io.github.lengsukq.opalcast.net

import android.annotation.SuppressLint
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
 * Pure-Kotlin OpalCast v1.1 TLS Binary Receiver Client (100% wire-compatible with OpalDesk
 * `RemoteStreamServer` & `OpalCastProtocol`), built for Android 5.0–17 (API 21–37).
 * Hardware video/audio decoding is delegated to [OpalCastMediaDecoder].
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
    private val appUserId: Int = 0,
    private val audioEnabled: Boolean = (sessionRole == SESSION_ROLE_DESKTOP),
    private val initialDisplayMode: Int = DISPLAY_MODE_DEX,
    private val targetNetwork: Network? = null,
    private val isUsbTether: Boolean = (targetNetwork != null),
    private val preferredCodec: String = "",
    private val defaultDisconnectReason: String = "Disconnected",
    private val defaultRejectedMessage: String = "Connection rejected by host",
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
        val isSecondaryProfile: Boolean get() = userId > 0
        val appKey: String get() = ReceiverPolicy.formatAppKey(pkg, userId)
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
        private const val TAG = "OpalCastClient"

        const val SESSION_ROLE_DESKTOP = "desktop"
        const val SESSION_ROLE_STANDALONE_APP = "standalone_app"
        const val SESSION_ROLE_APP_CATALOG = "app_catalog"

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
        private const val SOCKET_CONNECT_TIMEOUT_MS = 6_000
        private const val SOCKET_RECV_BUF_BYTES = 512 * 1024
        private const val USB_TETHER_SOCKET_RECV_BUF_BYTES = 1024 * 1024
        private const val SOCKET_SEND_BUF_BYTES = 256 * 1024
        private const val REALTIME_MEDIA_TRAFFIC_CLASS = 0xB8
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
    private val textSeq = AtomicLong(1L)
    private val lastIdrRequestMs = AtomicLong(0L)
    private val lastRxActivityMs = AtomicLong(0L)
    private val sideNavTouchFilter = InputGuardPolicy.SideNavigationTouchFilter()
    private val wireCodec = OpalCastWireCodec()

    @Volatile
    private var socket: SSLSocket? = null

    private val mediaDecoder = OpalCastMediaDecoder(
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
        Thread(::ioLoop, "OpalCastRx").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        try { enqueue(OpalCastWireCodec.TYPE_SYSTEM_ACTION, 0, OpalCastWireCodec.encodeSystemAction(SYS_DISCONNECT, 0)) } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        mediaDecoder.releaseAll()
    }

    fun requestKeyFrame() {
        enqueue(OpalCastWireCodec.TYPE_REQUEST_IDR, 0, ByteArray(0))
    }

    fun requestAppList() {
        enqueue(OpalCastWireCodec.TYPE_LIST_APPS, 0, ByteArray(0))
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
        enqueue(OpalCastWireCodec.TYPE_MIC_CONFIG, 0, json)
    }

    fun sendMicFrame(pcm: ByteArray) {
        if (closed.get() || pcm.isEmpty() || outQueue.remainingCapacity() <= 32) return
        outQueue.offer(UplinkFrame(OpalCastWireCodec.TYPE_MIC_FRAME, 0, SystemClock.elapsedRealtimeNanos() / 1000L, pcm))
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
        enqueue(OpalCastWireCodec.TYPE_CAMERA_CONFIG, 0, json)
    }

    fun sendCameraFrame(jpeg: ByteArray) {
        if (closed.get() || jpeg.isEmpty() || outQueue.remainingCapacity() <= 32) return
        outQueue.offer(UplinkFrame(OpalCastWireCodec.TYPE_CAMERA_FRAME, 0, SystemClock.elapsedRealtimeNanos() / 1000L, jpeg))
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
        enqueue(
            OpalCastWireCodec.TYPE_RESIZE_CONFIG,
            0,
            OpalCastWireCodec.encodeResizeConfig(alignedW, alignedH, wireDpi, fps, resolvedBitrate),
        )
    }

    fun sendSystemAction(action: Int, arg: Int = 0) {
        enqueue(OpalCastWireCodec.TYPE_SYSTEM_ACTION, 0, OpalCastWireCodec.encodeSystemAction(action, arg))
    }

    fun sendKeyEvent(action: Int, keyCode: Int, metaState: Int, repeat: Int = 0) {
        enqueue(OpalCastWireCodec.TYPE_INPUT_KEY, 0, OpalCastWireCodec.encodeKeyEvent(action, keyCode, metaState, repeat))
    }

    fun sendTextEvent(text: String, commitPaste: Boolean = true) {
        if (text.isEmpty()) return
        enqueue(OpalCastWireCodec.TYPE_INPUT_TEXT, 0, OpalCastWireCodec.encodeTextEvent(text, textSeq.incrementAndGet(), commitPaste))
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
        enqueue(OpalCastWireCodec.TYPE_INPUT_TOUCH, 0, bb.array())
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

        enqueue(
            OpalCastWireCodec.TYPE_INPUT_MOUSE,
            0,
            OpalCastWireCodec.encodeMouseEvent(mouseAction, rx, ry, normButtons, actBtn, hScroll, vScroll),
        )
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
            val fp = if (peerCert != null) OpalCastWireCodec.sha256Hex(peerCert.encoded) else ""
            ssl.soTimeout = 70_000
            return ssl to fp
        } catch (t: Throwable) {
            try { ssl.close() } catch (_: Throwable) {}
            throw t
        }
    }

    private fun ioLoop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        var disconnectReason = defaultDisconnectReason
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
                if (appUserId > 0) put("appUserId", appUserId)
            }.toString().toByteArray(StandardCharsets.UTF_8)
            wireCodec.writeFrame(output, OpalCastWireCodec.TYPE_HELLO, 0, SystemClock.elapsedRealtimeNanos() / 1000L, helloJson)
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
                mediaDecoder.startAudioThread()
            } else {
                null
            }

            lastRxActivityMs.set(SystemClock.uptimeMillis())
            while (running.get() && !closed.get()) {
                val frame = wireCodec.readFrame(input) ?: break
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
            mediaDecoder.releaseAll()
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
                        wireCodec.writeFrame(out, pkt.type, pkt.flags, pkt.tsUs, pkt.payload)
                    }
                    wroteAny = true
                }
                val now = SystemClock.uptimeMillis()
                val idleMs = now - lastRxActivityMs.get()
                val pingIntervalMs = if (idleMs >= 3_500L) 4_000L else 10_000L
                if (now - lastPingMs >= pingIntervalMs) {
                    lastPingMs = now
                    val id = pingSeq++
                    OpalCastWireCodec.writePingId(pingPayload, id)
                    wireCodec.writeFrame(out, OpalCastWireCodec.TYPE_PING, 0, now * 1000L, pingPayload)
                    wroteAny = true
                }
                if (wroteAny) out.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleDownlinkFrame(frame: OpalCastRxFrame, peerFp: String, ssl: SSLSocket) {
        when (frame.type) {
            OpalCastWireCodec.TYPE_AUTH_RESULT -> {
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
                    val err = obj.optString("message", defaultRejectedMessage)
                    listener.onError(err)
                    stop()
                }
            }
            OpalCastWireCodec.TYPE_DEVICE_STATE -> OpalCastWireCodec.routeDeviceState(frame.payload, listener)
            OpalCastWireCodec.TYPE_APP_LIST -> OpalCastWireCodec.routeAppList(frame.payload, listener)
            OpalCastWireCodec.TYPE_IME_REQUEST -> OpalCastWireCodec.routeImeRequest(frame.payload, listener)
            OpalCastWireCodec.TYPE_MIC_STATE -> OpalCastWireCodec.routeMicState(frame.payload, listener)
            OpalCastWireCodec.TYPE_CAMERA_STATE -> OpalCastWireCodec.routeCameraState(frame.payload, listener)
            OpalCastWireCodec.TYPE_VIDEO_CONFIG -> mediaDecoder.handleVideoConfig(frame.payload)
            OpalCastWireCodec.TYPE_VIDEO_FRAME -> mediaDecoder.handleVideoFrame(frame.payload, frame.length, frame.flags, frame.timestampUs)
            OpalCastWireCodec.TYPE_AUDIO_CONFIG -> mediaDecoder.handleAudioConfig(frame.payload)
            OpalCastWireCodec.TYPE_AUDIO_FRAME -> mediaDecoder.enqueueAudioFrame(frame.payload, frame.timestampUs)
            OpalCastWireCodec.TYPE_PONG -> Unit
        }
    }

    private fun requestKeyFrameThrottled() {
        val nowMs = SystemClock.uptimeMillis()
        val prev = lastIdrRequestMs.get()
        if (prev == 0L || nowMs - prev >= IDR_THROTTLE_MS) {
            if (lastIdrRequestMs.compareAndSet(prev, nowMs)) requestKeyFrame()
        }
    }
}
