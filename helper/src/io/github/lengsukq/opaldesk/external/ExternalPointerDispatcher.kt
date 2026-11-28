package io.github.lengsukq.opaldesk.external

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.ExternalDesktopActivity
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper

/**
 * Shared synthetic pointer/touch/scroll dispatcher for in-process external monitor Compose hosts
 * ([ExternalMonitorPresentation] and Direct Extended [ExternalDesktopActivity]).
 */
class ExternalPointerDispatcher(
    defaultWidthPx: Int,
    defaultHeightPx: Int,
    private val targetViewProvider: () -> View?,
    private val onCursorChanged: ((Float, Float) -> Unit)? = null,
) {
    private var fallbackWidthPx: Int = defaultWidthPx.coerceAtLeast(640)
    private var fallbackHeightPx: Int = defaultHeightPx.coerceAtLeast(480)

    val cursorPosState = mutableStateOf(
        Offset(fallbackWidthPx * 0.5f, fallbackHeightPx * 0.5f),
    )
    val showVirtualCursorState = mutableStateOf(false)

    private var primaryDownTimeMs: Long = 0L
    private var isPrimaryPressed: Boolean = false

    fun updateDefaultBounds(widthPx: Int, heightPx: Int) {
        if (widthPx >= 320 && heightPx >= 320) {
            fallbackWidthPx = widthPx
            fallbackHeightPx = heightPx
            val cur = cursorPosState.value
            val (cx, cy) = ExternalTrackpadPolicy.clampCursorPosition(
                x = cur.x,
                y = cur.y,
                widthPx = currentSurfaceWidthPx(),
                heightPx = currentSurfaceHeightPx(),
            )
            cursorPosState.value = Offset(cx, cy)
        }
    }

    fun currentSurfaceWidthPx(): Int =
        targetViewProvider()?.width?.takeIf { it > 1 } ?: fallbackWidthPx

    fun currentSurfaceHeightPx(): Int =
        targetViewProvider()?.height?.takeIf { it > 1 } ?: fallbackHeightPx

    fun hideCursor() {
        if (isPrimaryPressed) {
            setPrimaryButton(false)
        }
        if (showVirtualCursorState.value) {
            showVirtualCursorState.value = false
        }
    }

    fun moveCursorBy(dxPx: Float, dyPx: Float) {
        if (!showVirtualCursorState.value) showVirtualCursorState.value = true
        val cur = cursorPosState.value
        val (nx, ny) = ExternalTrackpadPolicy.clampCursorPosition(
            x = cur.x + dxPx,
            y = cur.y + dyPx,
            widthPx = currentSurfaceWidthPx(),
            heightPx = currentSurfaceHeightPx(),
        )
        val next = Offset(nx, ny)
        cursorPosState.value = next
        onCursorChanged?.invoke(nx, ny)
        if (isPrimaryPressed) {
            dispatchPointerTouch(MotionEvent.ACTION_MOVE, nx, ny)
        } else {
            dispatchPointerHover(nx, ny)
        }
    }

    fun setPrimaryButton(pressed: Boolean) {
        if (!showVirtualCursorState.value && pressed) showVirtualCursorState.value = true
        val pos = cursorPosState.value
        if (pressed && !isPrimaryPressed) {
            isPrimaryPressed = true
            primaryDownTimeMs = SystemClock.uptimeMillis()
            dispatchPointerTouch(MotionEvent.ACTION_DOWN, pos.x, pos.y)
        } else if (!pressed && isPrimaryPressed) {
            isPrimaryPressed = false
            dispatchPointerTouch(MotionEvent.ACTION_UP, pos.x, pos.y)
        }
    }

    fun tapPrimary() {
        if (!showVirtualCursorState.value) showVirtualCursorState.value = true
        val pos = cursorPosState.value
        val now = SystemClock.uptimeMillis()
        isPrimaryPressed = false
        primaryDownTimeMs = now
        dispatchPointerTouch(MotionEvent.ACTION_DOWN, pos.x, pos.y, now)
        dispatchPointerTouch(MotionEvent.ACTION_UP, pos.x, pos.y, now + 18L)
    }

    fun scrollAtCursor(hScroll: Float, vScroll: Float) {
        val target = targetViewProvider() ?: return
        if (!showVirtualCursorState.value) showVirtualCursorState.value = true
        val pos = cursorPosState.value
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = pos.x
                y = pos.y
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            },
        )
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        try {
            target.dispatchGenericMotionEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun dispatchPointerTouch(
        action: Int,
        x: Float,
        y: Float,
        eventTimeMs: Long = SystemClock.uptimeMillis(),
    ) {
        val target = targetViewProvider() ?: return
        val downTime = if (primaryDownTimeMs > 0L) primaryDownTimeMs else eventTimeMs
        val ev = MotionEvent.obtain(downTime, eventTimeMs, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            target.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun dispatchPointerHover(x: Float, y: Float) {
        val target = targetViewProvider() ?: return
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        try {
            target.dispatchGenericMotionEvent(ev)
        } finally {
            ev.recycle()
        }
    }
}

/**
 * Container detecting native hardware Bluetooth/USB mouse events (`deviceId > 0`) on an external
 * display so the synthetic trackpad cursor hides immediately with zero double-cursor artifacts,
 * and suppressing Android's outer system pointer icon whenever OpalDesk's virtual trackpad cursor
 * or a remote PC cursor owns pointer rendering.
 */
class HardwareMouseDetectingFrameLayout(
    ctx: Context,
    private val shouldSuppressSystemPointerIcon: () -> Boolean = { false },
    private val onHardwareMouseSeen: () -> Unit,
) : FrameLayout(ctx) {
    private val nullPointerIcon: PointerIcon by lazy {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    }

    fun syncSystemPointerIcon() {
        val targetIcon = if (shouldSuppressSystemPointerIcon()) nullPointerIcon else null
        if (pointerIcon != targetIcon) {
            pointerIcon = targetIcon
        }
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        if (shouldSuppressSystemPointerIcon()) {
            return nullPointerIcon
        }
        return super.onResolvePointerIcon(event, pointerIndex)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (isHardwareMouseEvent(ev)) {
            onHardwareMouseSeen()
            syncSystemPointerIcon()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isHardwareMouseEvent(ev)) {
            onHardwareMouseSeen()
            syncSystemPointerIcon()
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        fun isHardwareMouseEvent(ev: MotionEvent): Boolean {
            if (ev.deviceId <= 0) return false
            val src = ev.source
            return (src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (src and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE ||
                (ev.pointerCount > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)
        }
    }
}

/**
 * Single launcher coordinating Display 0 phone trackpad activation across Presentation mode,
 * Direct Extended Display mode, and Wireless/USB OpalCast sessions.
 */
object ExternalTrackpadLauncher {
    fun openPhoneTrackpad(ctx: Context): Boolean {
        if (BridgeClient.statusFlow.value.hostScreenOff) {
            BridgeClient.setHostScreenPowerAsync(true)
        }
        val target = ExternalTrackpadPolicy.resolveTrackpadTarget(
            remote = BridgeClient.remoteStatusFlow.value,
            session = ExternalTrackpadBridge.sessionFlow.value,
        )
        return when (target) {
            TrackpadTargetMode.PHYSICAL_PRESENTATION -> {
                val intent = Intent(ctx, ExternalDesktopActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                    putExtra("targetDisplayId", ExternalTrackpadBridge.sessionFlow.value.displayId)
                }
                DisplayLaunchHelper.launchOnDisplay0(ctx, intent)
            }
            TrackpadTargetMode.PHYSICAL_DIRECT_EXTENDED,
            TrackpadTargetMode.REMOTE_CAST,
            -> {
                val intent = Intent(ctx, RemoteTrackpadActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                }
                DisplayLaunchHelper.launchOnDisplay0(ctx, intent)
            }
            TrackpadTargetMode.NONE -> false
        }
    }
}
