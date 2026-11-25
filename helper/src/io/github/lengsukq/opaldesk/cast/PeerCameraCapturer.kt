package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-overhead Camera2 MJPEG capturer for Android Tablet/Phone receivers (Root-gated camera uplink).
 * Captures YUV_420_888 frames from the front camera (falling back to any available camera),
 * rate-limits to `targetFps`, and encodes compact JPEG frames for `TYPE_CAMERA_FRAME (0x30)`.
 */
internal class PeerCameraCapturer(
    private val context: Context,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val targetFps: Int = DEFAULT_FPS,
    private val jpegQuality: Int = 72,
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "OpalPeerCam"
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 480
        const val DEFAULT_FPS = 15
    }

    private val running = AtomicBoolean(false)
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    @Volatile
    private var lastFrameMs: Long = 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            running.set(false)
            onError("无法获取系统 CameraManager 服务")
            return false
        }

        val cameraId = selectCameraId(manager)
        if (cameraId == null) {
            running.set(false)
            onError("未检测到可用的本机摄像头")
            return false
        }
        val (captureW, captureH) = selectCaptureSize(manager, cameraId, width, height)

        return try {
            val thread = HandlerThread("OpalPeerCamTx").apply { start() }
            val handler = Handler(thread.looper)
            bgThread = thread
            bgHandler = handler

            val reader = ImageReader.newInstance(captureW, captureH, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
                try {
                    if (!running.get()) return@setOnImageAvailableListener
                    val now = SystemClock.uptimeMillis()
                    val minInterval = (1000L / targetFps.coerceIn(5, 30)).coerceAtLeast(33L)
                    if (now - lastFrameMs < minInterval) return@setOnImageAvailableListener
                    lastFrameMs = now
                    val jpeg = yuv420ToJpeg(img, jpegQuality)
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        onJpegFrame(jpeg)
                    }
                } finally {
                    try { img.close() } catch (_: Throwable) {}
                }
            }, handler)
            imageReader = reader

            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!running.get()) {
                            try { camera.close() } catch (_: Throwable) {}
                            return
                        }
                        cameraDevice = camera
                        createSession(camera, reader, handler)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        stop()
                        onError("本机摄像头连接已断开")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        stop()
                        onError("打开本机摄像头失败 (code=$error)")
                    }
                },
                handler,
            )
            true
        } catch (t: Throwable) {
            stop()
            onError("启动本机摄像头失败：${t.message ?: t.javaClass.simpleName}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, reader: ImageReader, handler: Handler) {
        try {
            val surf = reader.surface
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surf)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            camera.createCaptureSession(
                listOf(surf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running.get()) {
                            try { session.close() } catch (_: Throwable) {}
                            return
                        }
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, handler)
                        } catch (t: Throwable) {
                            Log.w(TAG, "setRepeatingRequest failed: ${t.message}")
                            stop()
                            onError("摄像头预览流启动失败")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        stop()
                        onError("摄像头会话配置失败")
                    }
                },
                handler,
            )
        } catch (t: Throwable) {
            stop()
            onError("创建摄像头会话失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        val t = bgThread
        bgThread = null
        bgHandler = null
        if (t != null) {
            try { t.quitSafely() } catch (_: Throwable) {}
        }
    }

    private fun selectCameraId(manager: CameraManager): String? {
        return try {
            val ids = manager.cameraIdList
            var fallback: String? = null
            for (id in ids) {
                if (fallback == null) fallback = id
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    return id
                }
            }
            fallback
        } catch (_: Throwable) {
            null
        }
    }

    private fun selectCaptureSize(manager: CameraManager, cameraId: String, reqW: Int, reqH: Int): Pair<Int, Int> {
        return try {
            val map = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            if (sizes == null || sizes.isEmpty()) return reqW to reqH
            val best = sizes.minByOrNull { s ->
                kotlin.math.abs(s.width - reqW) + kotlin.math.abs(s.height - reqH)
            } ?: return reqW to reqH
            best.width to best.height
        } catch (_: Throwable) {
            reqW to reqH
        }
    }

    private fun yuv420ToJpeg(image: Image, quality: Int): ByteArray? {
        val w = image.width
        val h = image.height
        val planes = image.planes
        if (planes.size < 3 || w <= 0 || h <= 0) return null

        val nv21 = ByteArray(w * h * 3 / 2)
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yLimit = yBuf.limit()
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()
        val yRowStride = planes[0].rowStride
        val yPixStride = planes[0].pixelStride
        var outPos = 0
        for (row in 0 until h) {
            val rowStart = row * yRowStride
            if (yPixStride == 1 && rowStart + w <= yLimit) {
                yBuf.position(rowStart)
                yBuf.get(nv21, outPos, w)
                outPos += w
            } else {
                for (col in 0 until w) {
                    val idx = rowStart + col * yPixStride
                    nv21[outPos++] = if (idx in 0 until yLimit) yBuf.get(idx) else 0
                }
            }
        }

        val uvRowStride = planes[2].rowStride
        val uvPixStride = planes[2].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixStride = planes[1].pixelStride
        val halfH = h / 2
        val halfW = w / 2
        for (row in 0 until halfH) {
            for (col in 0 until halfW) {
                val vIdx = row * uvRowStride + col * uvPixStride
                val uIdx = row * uRowStride + col * uPixStride
                nv21[outPos++] = if (vIdx in 0 until vLimit) vBuf.get(vIdx) else 128.toByte()
                nv21[outPos++] = if (uIdx in 0 until uLimit) uBuf.get(uIdx) else 128.toByte()
            }
        }

        val baos = ByteArrayOutputStream(24 * 1024)
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        return if (yuv.compressToJpeg(Rect(0, 0, w, h), quality, baos)) baos.toByteArray() else null
    }
}
