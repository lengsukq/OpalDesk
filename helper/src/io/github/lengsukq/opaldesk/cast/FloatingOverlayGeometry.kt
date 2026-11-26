package io.github.lengsukq.opaldesk.cast

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure JVM-testable floating window geometry & safe screen-bound clamping for
 * [PeerFloatingAppService].
 *
 * Guarantees every width, height, drag coordinate, resize range, and collapsed bubble coordinate
 * stays valid (never passing `maximum < minimum` to `coerceIn`) across portrait/landscape phones,
 * foldables, tablets, split-screen, and high-DPI displays.
 */
object FloatingOverlayGeometry {

    data class Bounds(val width: Int, val height: Int)
    data class Point(val x: Int, val y: Int)

    private const val TABLET_MIN_SCREEN_DP = 580f

    fun dpToPx(dp: Int, density: Float): Int = (dp * density.coerceAtLeast(0.5f)).roundToInt()

    /**
     * Safely clamps [value] into `[minVal, maxVal]` even when narrow/short screens cause
     * `maxVal < minVal` (in which case [maxVal] takes precedence as the hard screen bound).
     */
    fun safeCoerceIn(value: Int, minVal: Int, maxVal: Int): Int {
        val safeMax = max(1, maxVal)
        val safeMin = min(minVal, safeMax)
        return value.coerceIn(safeMin, safeMax)
    }

    /**
     * Safely clamps [value] into `[minVal, maxVal]` where [minVal] is preserved as the lower bound
     * when `maxVal < minVal` (used for coordinate ranges like `[margin, screenEdge - size]`).
     */
    fun safeCoerceRangeFloor(value: Int, minVal: Int, maxVal: Int): Int {
        val safeMax = max(minVal, maxVal)
        return value.coerceIn(minVal, safeMax)
    }

    fun computeWindowSize(
        screenW: Int,
        screenH: Int,
        density: Float,
        isLandscape: Boolean,
    ): Bounds {
        val safeScreenW = max(1, screenW)
        val safeScreenH = max(1, screenH)
        val safeDensity = density.coerceAtLeast(0.5f)
        val minScreen = min(safeScreenW, safeScreenH)
        val minScreenDp = minScreen / safeDensity

        val maxAllowedW = max(1, (safeScreenW * 0.92f).roundToInt())
        val maxAllowedH: Int
        val width: Int
        val height: Int

        if (isLandscape) {
            val targetMaxW = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                min(dpToPx(620, safeDensity), (safeScreenW * 0.72f).roundToInt())
            } else {
                min(dpToPx(580, safeDensity), (safeScreenW * 0.88f).roundToInt())
            }
            width = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                safeCoerceIn(dpToPx(620, safeDensity), dpToPx(240, safeDensity), targetMaxW)
            } else {
                safeCoerceIn((safeScreenW * 0.84f).roundToInt(), dpToPx(320, safeDensity), targetMaxW)
            }.coerceAtMost(maxAllowedW)

            maxAllowedH = max(1, (safeScreenH * 0.64f).roundToInt())
            height = safeCoerceIn((width * 0.62f).roundToInt(), dpToPx(240, safeDensity), maxAllowedH)
        } else if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
            val maxTabletW = max(1, (safeScreenW * 0.44f).roundToInt())
            width = safeCoerceIn(dpToPx(390, safeDensity), dpToPx(230, safeDensity), maxTabletW)
                .coerceAtMost(maxAllowedW)
            maxAllowedH = max(1, (safeScreenH * 0.82f).roundToInt())
            height = safeCoerceIn((width * 1.72f).roundToInt(), dpToPx(440, safeDensity), maxAllowedH)
        } else {
            val maxPhoneW = min(dpToPx(330, safeDensity), maxAllowedW)
            width = safeCoerceIn((minScreen * 0.56f).roundToInt(), dpToPx(230, safeDensity), maxPhoneW)
            maxAllowedH = max(1, (safeScreenH * 0.74f).roundToInt())
            height = safeCoerceIn((width * 1.70f).roundToInt(), dpToPx(340, safeDensity), maxAllowedH)
        }

        return Bounds(width = max(1, width), height = max(1, height))
    }

    fun clampResizeSize(
        targetW: Int,
        targetH: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Bounds {
        val safeDensity = density.coerceAtLeast(0.5f)
        val minW = dpToPx(210, safeDensity)
        val maxW = max(1, (max(1, screenW) * 0.92f).roundToInt())
        val minH = dpToPx(260, safeDensity)
        val maxH = max(1, (max(1, screenH) * 0.86f).roundToInt())
        return Bounds(
            width = safeCoerceIn(targetW, minW, maxW),
            height = safeCoerceIn(targetH, minH, maxH),
        )
    }

    fun clampDragPosition(
        targetX: Int,
        targetY: Int,
        windowW: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Point {
        val safeDensity = density.coerceAtLeast(0.5f)
        val minX = -max(0, windowW) / 2
        val maxX = max(minX, max(1, screenW) - dpToPx(48, safeDensity))
        val minY = dpToPx(16, safeDensity)
        val maxY = max(minY, max(1, screenH) - dpToPx(56, safeDensity))
        return Point(
            x = targetX.coerceIn(minX, maxX),
            y = targetY.coerceIn(minY, maxY),
        )
    }

    fun clampVisiblePosition(
        x: Int,
        y: Int,
        windowW: Int,
        windowH: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Point {
        val safeDensity = density.coerceAtLeast(0.5f)
        val padX = dpToPx(8, safeDensity)
        val padY = dpToPx(24, safeDensity)
        return Point(
            x = safeCoerceRangeFloor(x, padX, max(1, screenW) - windowW - padX),
            y = safeCoerceRangeFloor(y, padY, max(1, screenH) - windowH - padY),
        )
    }

    fun clampBubblePosition(
        targetX: Int,
        targetY: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Point {
        val safeDensity = density.coerceAtLeast(0.5f)
        val minY = dpToPx(24, safeDensity)
        return Point(
            x = safeCoerceRangeFloor(targetX, 0, max(1, screenW) - dpToPx(52, safeDensity)),
            y = safeCoerceRangeFloor(targetY, minY, max(1, screenH) - dpToPx(64, safeDensity)),
        )
    }

    /**
     * Computes a floating overlay window size whose content area (`width x (height - chromeHeightPx)`)
     * matches the mirrored phone stream's exact aspect ratio (`videoW / videoH`) without stretching or
     * clipping on portrait or landscape rotations.
     */
    fun computeAspectMatchedWindowSize(
        videoW: Int,
        videoH: Int,
        chromeHeightPx: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Bounds {
        val safeScreenW = max(1, screenW)
        val safeScreenH = max(1, screenH)
        val safeDensity = density.coerceAtLeast(0.5f)
        val safeChrome = max(0, chromeHeightPx)
        val aspect = (max(2, videoW).toFloat() / max(2, videoH).toFloat()).coerceIn(0.35f, 3.0f)

        val maxW = max(1, (safeScreenW * 0.90f).roundToInt())
        val maxContentH = max(1, (safeScreenH * 0.84f).roundToInt() - safeChrome)
        val minScreenDp = min(safeScreenW, safeScreenH) / safeDensity

        val desiredContentH: Int
        val desiredContentW: Int
        if (aspect < 1f) {
            val capH = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                min(dpToPx(660, safeDensity), maxContentH)
            } else {
                min(dpToPx(540, safeDensity), maxContentH)
            }
            desiredContentH = max(1, capH)
            desiredContentW = max(1, (desiredContentH * aspect).roundToInt())
        } else {
            val capW = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                min(dpToPx(680, safeDensity), maxW)
            } else {
                min(dpToPx(560, safeDensity), maxW)
            }
            desiredContentW = max(1, capW)
            desiredContentH = max(1, (desiredContentW / aspect).roundToInt())
        }

        return fitAspectIntoBounds(desiredContentW, desiredContentH, aspect, safeChrome, maxW, maxContentH)
    }

    /**
     * Aspect-ratio-locked resize clamping for floating Mirror windows so dragging the corner grip
     * scales the mirrored phone screen smoothly with zero distortion.
     */
    fun clampAspectLockedResizeSize(
        targetW: Int,
        targetH: Int,
        videoW: Int,
        videoH: Int,
        chromeHeightPx: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Bounds {
        val safeScreenW = max(1, screenW)
        val safeScreenH = max(1, screenH)
        val safeDensity = density.coerceAtLeast(0.5f)
        val safeChrome = max(0, chromeHeightPx)
        val aspect = (max(2, videoW).toFloat() / max(2, videoH).toFloat()).coerceIn(0.35f, 3.0f)

        val maxW = max(1, (safeScreenW * 0.92f).roundToInt())
        val maxContentH = max(1, (safeScreenH * 0.86f).roundToInt() - safeChrome)
        val minW = min(dpToPx(200, safeDensity), maxW)
        val minContentH = min(dpToPx(200, safeDensity), maxContentH)

        val desiredW = max(minW, targetW)
        val desiredContentH = max(minContentH, targetH - safeChrome)
        val fromW = fitAspectIntoBounds(desiredW, max(1, (desiredW / aspect).roundToInt()), aspect, safeChrome, maxW, maxContentH)
        val fromH = fitAspectIntoBounds(max(1, (desiredContentH * aspect).roundToInt()), desiredContentH, aspect, safeChrome, maxW, maxContentH)

        // Pick whichever projection tracks the user's drag direction more closely while staying within bounds.
        val errW = absDiff(fromW.width, targetW) + absDiff(fromW.height, targetH)
        val errH = absDiff(fromH.width, targetW) + absDiff(fromH.height, targetH)
        val chosen = if (errW <= errH) fromW else fromH
        val clampedW = max(minW, min(maxW, chosen.width))
        val lockedContentH = max(1, (clampedW / aspect).roundToInt()).coerceAtMost(maxContentH)
        val lockedW = max(1, (lockedContentH * aspect).roundToInt()).coerceAtMost(maxW)
        return Bounds(width = lockedW, height = lockedContentH + safeChrome)
    }

    private fun fitAspectIntoBounds(
        rawW: Int,
        rawContentH: Int,
        aspect: Float,
        chromeHeightPx: Int,
        maxW: Int,
        maxContentH: Int,
    ): Bounds {
        var w = max(1, rawW)
        var h = max(1, rawContentH)
        if (w > maxW) {
            w = maxW
            h = max(1, (w / aspect).roundToInt())
        }
        if (h > maxContentH) {
            h = maxContentH
            w = max(1, (h * aspect).roundToInt())
        }
        if (w > maxW) {
            w = maxW
            h = max(1, (w / aspect).roundToInt())
        }
        return Bounds(width = max(1, w), height = max(1, h) + chromeHeightPx)
    }

    private fun absDiff(a: Int, b: Int): Int = if (a >= b) a - b else b - a
}
