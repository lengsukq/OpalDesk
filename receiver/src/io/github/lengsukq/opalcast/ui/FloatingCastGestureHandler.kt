package io.github.lengsukq.opalcast.ui

import android.annotation.SuppressLint
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.lengsukq.opalcast.policy.FloatingGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Drag, resize, bubble-pill touch gesture handlers and aspect-ratio window sizing math
 * for [FloatingCastService].
 */
internal object FloatingCastGestureHandler {
    @SuppressLint("ClickableViewAccessibility")
    fun installDragMoveListener(
        handle: View,
        getLayoutParams: () -> WindowManager.LayoutParams?,
        getRootView: () -> View?,
        getDisplayMetrics: () -> DisplayMetrics,
        onPositionChanged: (Int, Int) -> Unit,
        onUpdateLayout: (View, WindowManager.LayoutParams) -> Unit,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, ev ->
            val lp = getLayoutParams() ?: return@setOnTouchListener false
            val rv = getRootView() ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = getDisplayMetrics()
                    val clamped = FloatingGeometry.clampDragPosition(
                        startX + (ev.rawX - downRawX).roundToInt(),
                        startY + (ev.rawY - downRawY).roundToInt(),
                        lp.width,
                        dm.widthPixels,
                        dm.heightPixels,
                        dm.density,
                    )
                    lp.x = clamped.x
                    lp.y = clamped.y
                    onPositionChanged(lp.x, lp.y)
                    onUpdateLayout(rv, lp)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun installResizeListener(
        grip: View,
        getLayoutParams: () -> WindowManager.LayoutParams?,
        getRootView: () -> View?,
        getDisplayMetrics: () -> DisplayMetrics,
        isMirrorMode: () -> Boolean,
        getMirrorVideoSize: () -> Pair<Int, Int>,
        getChromeHeightPx: () -> Int,
        onSizeChanged: (Int, Int) -> Unit,
        onUpdateLayout: (View, WindowManager.LayoutParams) -> Unit,
        onCommitRemoteResize: () -> Unit,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startW = 0
        var startH = 0
        grip.setOnTouchListener { _, ev ->
            val lp = getLayoutParams() ?: return@setOnTouchListener false
            val rv = getRootView() ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startW = lp.width
                    startH = lp.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = getDisplayMetrics()
                    val clamped = if (isMirrorMode()) {
                        val (vw, vh) = getMirrorVideoSize()
                        FloatingGeometry.clampAspectLockedResizeSize(
                            startW + (ev.rawX - downRawX).roundToInt(),
                            startH + (ev.rawY - downRawY).roundToInt(),
                            vw,
                            vh,
                            getChromeHeightPx(),
                            dm.widthPixels,
                            dm.heightPixels,
                            dm.density,
                        )
                    } else {
                        FloatingGeometry.clampResizeSize(
                            startW + (ev.rawX - downRawX).roundToInt(),
                            startH + (ev.rawY - downRawY).roundToInt(),
                            dm.widthPixels,
                            dm.heightPixels,
                            dm.density,
                        )
                    }
                    lp.width = clamped.width
                    lp.height = clamped.height
                    onSizeChanged(lp.width, lp.height)
                    onUpdateLayout(rv, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isMirrorMode()) onCommitRemoteResize()
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun installBubbleTouchListener(
        pill: View,
        bLp: WindowManager.LayoutParams,
        slop: Int,
        getDisplayMetrics: () -> DisplayMetrics,
        onUpdateLayout: (View, WindowManager.LayoutParams) -> Unit,
        onExpandFromBubble: () -> Unit,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        pill.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = bLp.x
                    startY = bLp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).roundToInt()
                    val dy = (ev.rawY - downY).roundToInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragged = true
                    if (dragged) {
                        val dm = getDisplayMetrics()
                        val clamped = FloatingGeometry.clampBubblePosition(
                            startX + dx,
                            startY + dy,
                            dm.widthPixels,
                            dm.heightPixels,
                            dm.density,
                        )
                        bLp.x = clamped.x
                        bLp.y = clamped.y
                        onUpdateLayout(pill, bLp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onExpandFromBubble()
                    true
                }
                else -> false
            }
        }
    }

    fun computeStreamSpecForView(viewW: Int, viewH: Int, density: Float): Triple<Int, Int, Int> {
        val safeDensity = density.coerceAtLeast(1f)
        val dp180 = (180 * safeDensity).roundToInt()
        val dp41 = (41 * safeDensity).roundToInt()
        val contentH = max(dp180, viewH - dp41)
        val ratio = viewW.toFloat() / contentH.toFloat()
        val targetLong = 1440
        val rawW = if (ratio >= 1f) targetLong else (targetLong * ratio).roundToInt().coerceIn(544, targetLong)
        val rawH = if (ratio >= 1f) (targetLong / ratio).roundToInt().coerceIn(544, targetLong) else targetLong
        val streamW = (rawW and 7.inv()).coerceAtLeast(544)
        val streamH = (rawH and 7.inv()).coerceAtLeast(544)
        val viewWdp = viewW / safeDensity
        val viewHdp = contentH / safeDensity
        val reqDpi = if (streamH >= streamW) {
            (streamW * 160f / viewWdp.coerceIn(330f, 430f)).roundToInt().coerceIn(240, 480)
        } else {
            (streamH * 160f / viewHdp.coerceIn(340f, 520f)).roundToInt().coerceIn(240, 480)
        }
        return Triple(streamW, streamH, reqDpi)
    }
}
