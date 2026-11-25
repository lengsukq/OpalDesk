package io.github.lengsukq.opaldesk.cast

import java.util.Locale

/**
 * Pure-Kotlin orientation and NV21 rotation math for [PeerCameraCapturer].
 *
 * Keeps all remote camera frames transmitted over OpalCast upright (`0°` world orientation,
 * non-mirrored) regardless of how the capturing Android tablet/phone is rotated (`ROTATION_0`,
 * `ROTATION_90`, `ROTATION_180`, `ROTATION_270`) and regardless of front vs back lens.
 */
object PeerCameraGeometry {
    data class RotatedNv21(
        val data: ByteArray,
        val width: Int,
        val height: Int,
    )

    fun normalizeFacing(facing: String?): String {
        return when (facing?.trim()?.lowercase(Locale.US)) {
            "back", "rear", "environment" -> "back"
            else -> "front"
        }
    }

    /**
     * Converts Android `Surface.ROTATION_*` integer (`0..3`) to CCW degrees (`0, 90, 180, 270`).
     */
    fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        1 -> 90   // Surface.ROTATION_90
        2 -> 180  // Surface.ROTATION_180
        3 -> 270  // Surface.ROTATION_270
        else -> 0 // Surface.ROTATION_0
    }

    /**
     * Computes clockwise degrees (`0, 90, 180, 270`) to rotate a raw Camera2 sensor frame
     * so the output JPEG is upright (`0°`) matching the user's current screen orientation.
     *
     * Matches the official AOSP `CaptureRequest.JPEG_ORIENTATION` specification:
     * - Front camera: `(sensorOrientation + displayRotationCCW) % 360`
     * - Back camera:  `(sensorOrientation - displayRotationCCW + 360) % 360`
     */
    fun computeUprightRotationCw(
        sensorOrientation: Int,
        isFront: Boolean,
        displayRotationDeg: Int,
    ): Int {
        val sensor = ((sensorOrientation % 360) + 360) % 360
        val disp = ((displayRotationDeg % 360) + 360) % 360
        return if (isFront) {
            (sensor + disp) % 360
        } else {
            (sensor - disp + 360) % 360
        }
    }

    /**
     * Rotates an NV21 (`Y` plane followed by interleaved `VU` plane) buffer clockwise by
     * `0`, `90`, `180`, or `270` degrees. Requires even `width` and `height`.
     */
    fun rotateNv21(src: ByteArray, width: Int, height: Int, cwDeg: Int): RotatedNv21 {
        val norm = ((cwDeg % 360) + 360) % 360
        if (norm == 0 || width <= 0 || height <= 0 || (width and 1) != 0 || (height and 1) != 0) {
            return RotatedNv21(src, width, height)
        }
        val ySize = width * height
        val expectedLen = ySize + (ySize / 2)
        if (src.size < expectedLen) {
            return RotatedNv21(src, width, height)
        }

        val dst = ByteArray(expectedLen)
        val uvW = width / 2
        val uvH = height / 2

        return when (norm) {
            90 -> {
                val dstW = height
                val dstH = width
                for (y in 0 until height) {
                    val rowBase = y * width
                    val dstX = height - 1 - y
                    for (x in 0 until width) {
                        dst[x * dstW + dstX] = src[rowBase + x]
                    }
                }
                val dstUvW = uvH
                for (uy in 0 until uvH) {
                    val srcRow = ySize + uy * width
                    val dstUx = uvH - 1 - uy
                    for (ux in 0 until uvW) {
                        val sIdx = srcRow + ux * 2
                        val dIdx = ySize + (ux * dstUvW + dstUx) * 2
                        dst[dIdx] = src[sIdx]
                        dst[dIdx + 1] = src[sIdx + 1]
                    }
                }
                RotatedNv21(dst, dstW, dstH)
            }
            180 -> {
                for (y in 0 until height) {
                    val srcRow = y * width
                    val dstRow = (height - 1 - y) * width
                    for (x in 0 until width) {
                        dst[dstRow + (width - 1 - x)] = src[srcRow + x]
                    }
                }
                for (uy in 0 until uvH) {
                    val srcRow = ySize + uy * width
                    val dstRow = ySize + (uvH - 1 - uy) * width
                    for (ux in 0 until uvW) {
                        val sIdx = srcRow + ux * 2
                        val dIdx = dstRow + (uvW - 1 - ux) * 2
                        dst[dIdx] = src[sIdx]
                        dst[dIdx + 1] = src[sIdx + 1]
                    }
                }
                RotatedNv21(dst, width, height)
            }
            270 -> {
                val dstW = height
                val dstH = width
                for (y in 0 until height) {
                    val rowBase = y * width
                    for (x in 0 until width) {
                        val dstY = width - 1 - x
                        dst[dstY * dstW + y] = src[rowBase + x]
                    }
                }
                val dstUvW = uvH
                for (uy in 0 until uvH) {
                    val srcRow = ySize + uy * width
                    for (ux in 0 until uvW) {
                        val dstUy = uvW - 1 - ux
                        val sIdx = srcRow + ux * 2
                        val dIdx = ySize + (dstUy * dstUvW + uy) * 2
                        dst[dIdx] = src[sIdx]
                        dst[dIdx + 1] = src[sIdx + 1]
                    }
                }
                RotatedNv21(dst, dstW, dstH)
            }
            else -> RotatedNv21(src, width, height)
        }
    }
}
