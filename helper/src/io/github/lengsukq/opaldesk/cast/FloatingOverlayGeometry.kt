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
}
