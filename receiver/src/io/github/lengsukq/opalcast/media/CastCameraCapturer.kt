package io.github.lengsukq.opalcast.media

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
import android.view.Surface
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.policy.CameraGeometry
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * API 21–37 compatible Camera2 MJPEG capturer with live front/back lens switching and upright rotation.
 */
internal class CastCameraCapturer(
    private val context: Context,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val targetFps: Int = DEFAULT_FPS,
    private val jpegQuality: Int = 72,
    initialFacing: String = "front",
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "OpalCastCam"
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 480
        const val DEFAULT_FPS = 15
    }

    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val openGen = AtomicInteger(0)

    @Volatile
    private var currentFacing: String = CameraGeometry.normalizeFacing(initialFacing)
    @Volatile
    private var activeSensorOrientation: Int = 0
    @Volatile
    private var activeLensIsFront: Boolean = true
    @Volatile
    private var lastFrameMs: Long = 0L

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        synchronized(lifecycleLock) {
            if (!running.compareAndSet(false, true)) return true
            val thread = HandlerThread("OpalCastCamTx").apply { start() }
            bgThread = thread
            bgHandler = Handler(thread.looper)
            return openCurrentLensLocked()
        }
    }

    @SuppressLint("MissingPermission")
    fun switchFacing(targetFacing: String): Boolean {
        val norm = CameraGeometry.normalizeFacing(targetFacing)
        synchronized(lifecycleLock) {
            if (!running.get()) {
                currentFacing = norm
                return false
            }
            if (norm == currentFacing && cameraDevice != null) return true
            currentFacing = norm
            closeCameraResourcesLocked()
            return openCurrentLensLocked()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCurrentLensLocked(): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            stopLocked()
            onError(context.getString(R.string.cam_err_service))
            return false
        }
        val cameraId = selectCameraId(manager, currentFacing)
        if (cameraId == null) {
            stopLocked()
            onError(context.getString(R.string.cam_err_no_camera))
            return false
        }
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
        activeSensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val lensFacing = chars?.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_FRONT
        activeLensIsFront = (lensFacing == CameraCharacteristics.LENS_FACING_FRONT)

        val (captureW, captureH) = selectCaptureSize(manager, cameraId, width, height)
        val handler = bgHandler ?: run {
            stopLocked()
            return false
        }
        val gen = openGen.incrementAndGet()

        return try {
            val reader = ImageReader.newInstance(captureW, captureH, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (!running.get() || openGen.get() != gen) return@setOnImageAvailableListener
                    val now = SystemClock.uptimeMillis()
                    val minInterval = (1000L / targetFps.coerceIn(5, 30)).coerceAtLeast(33L)
                    if (now - lastFrameMs < minInterval) return@setOnImageAvailableListener
                    lastFrameMs = now
                    val rot = AndroidApiCompat.resolveContextDisplay(context)?.rotation ?: Surface.ROTATION_0
                    val cwDeg = CameraGeometry.computeUprightRotationCw(
                        sensorOrientation = activeSensorOrientation,
                        isFront = activeLensIsFront,
                        displayRotationDeg = CameraGeometry.surfaceRotationToDegrees(rot),
                    )
                    val jpeg = yuv420ToUprightJpeg(img, cwDeg, jpegQuality)
                    if (jpeg != null && jpeg.isNotEmpty()) onJpegFrame(jpeg)
                } finally {
                    try { img.close() } catch (_: Throwable) {}
                }
            }, handler)
            imageReader = reader

            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        synchronized(lifecycleLock) {
                            if (!running.get() || openGen.get() != gen) {
                                try { camera.close() } catch (_: Throwable) {}
                                return
                            }
                            cameraDevice = camera
                            createSessionLocked(camera, reader, handler, gen)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        if (openGen.get() != gen) {
                            try { camera.close() } catch (_: Throwable) {}
                            return
                        }
                        stop()
                        onError(context.getString(R.string.cam_err_disconnected))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (openGen.get() != gen) {
                            try { camera.close() } catch (_: Throwable) {}
                            return
                        }
                        stop()
                        onError(context.getString(R.string.cam_err_open_code_fmt, error))
                    }
                },
                handler,
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "openCamera failed", t)
            stopLocked()
            onError(context.getString(R.string.cam_err_start_fmt, t.message ?: t.javaClass.simpleName))
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun createSessionLocked(camera: CameraDevice, reader: ImageReader, handler: Handler, gen: Int) {
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
                        synchronized(lifecycleLock) {
                            if (!running.get() || openGen.get() != gen) {
                                try { session.close() } catch (_: Throwable) {}
                                return
                            }
                            captureSession = session
                            try {
                                session.setRepeatingRequest(builder.build(), null, handler)
                            } catch (_: Throwable) {
                                stopLocked()
                                onError(context.getString(R.string.cam_err_preview_start))
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (openGen.get() != gen) return
                        stop()
                        onError(context.getString(R.string.cam_err_session_config))
                    }
                },
                handler,
            )
        } catch (t: Throwable) {
            if (openGen.get() != gen) return
            stopLocked()
            onError(context.getString(R.string.cam_err_session_create_fmt, t.message ?: t.javaClass.simpleName))
        }
    }

    fun stop() {
        synchronized(lifecycleLock) { stopLocked() }
    }

    private fun stopLocked() {
        val wasRunning = running.getAndSet(false)
        openGen.incrementAndGet()
        closeCameraResourcesLocked()
        val t = bgThread
        bgThread = null
        bgHandler = null
        if (t != null && wasRunning) {
            try { t.quitSafely() } catch (_: Throwable) {}
        }
    }

    private fun closeCameraResourcesLocked() {
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
    }

    private fun selectCameraId(manager: CameraManager, facing: String): String? {
        val desired = if (facing == "back") CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        return try {
            var fallback: String? = null
            for (id in manager.cameraIdList) {
                if (fallback == null) fallback = id
                if (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == desired) return id
            }
            fallback
        } catch (_: Throwable) {
            null
        }
    }

    private fun selectCaptureSize(manager: CameraManager, cameraId: String, reqW: Int, reqH: Int): Pair<Int, Int> {
        return try {
            val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return reqW to reqH
            val best = sizes.minByOrNull { abs(it.width - reqW) + abs(it.height - reqH) } ?: return reqW to reqH
            best.width to best.height
        } catch (_: Throwable) {
            reqW to reqH
        }
    }

    private fun yuv420ToUprightJpeg(image: Image, cwDeg: Int, quality: Int): ByteArray? {
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
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val vIdx = row * uvRowStride + col * uvPixStride
                val uIdx = row * uRowStride + col * uPixStride
                nv21[outPos++] = if (vIdx in 0 until vLimit) vBuf.get(vIdx) else 128.toByte()
                nv21[outPos++] = if (uIdx in 0 until uLimit) uBuf.get(uIdx) else 128.toByte()
            }
        }
        val upright = CameraGeometry.rotateNv21(nv21, w, h, cwDeg)
        val baos = ByteArrayOutputStream(24 * 1024)
        val yuv = YuvImage(upright.data, ImageFormat.NV21, upright.width, upright.height, null)
        return if (yuv.compressToJpeg(Rect(0, 0, upright.width, upright.height), quality, baos)) {
            baos.toByteArray()
        } else {
            null
        }
    }
}
