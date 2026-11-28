package io.github.lengsukq.opaldesk.external

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlin.math.hypot

@Composable
internal fun TrackpadControlDeck(
    leftButtonHeld: Boolean,
    rightButtonHeld: Boolean,
    dragLockToggled: Boolean,
    hostScreenOff: Boolean,
    onLeftPressChange: (Boolean) -> Unit,
    onRightPressChange: (Boolean) -> Unit,
    onRightClick: () -> Unit,
    onToggleDragLock: () -> Unit,
    onShowDesktop: () -> Unit,
    onToggleHostScreenPower: () -> Unit,
) {
    val isDark = OpalPalette.isDark
    val curOnLeftPress by rememberUpdatedState(onLeftPressChange)
    val curOnRightPress by rememberUpdatedState(onRightPressChange)
    val curOnRightClick by rememberUpdatedState(onRightClick)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 560.dp
        if (compact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .liquidGlassSurface(
                            shape = RoundedCornerShape(16.dp),
                            fill = OpalPalette.LiquidDockFill,
                            rim = OpalPalette.LiquidGlassRim,
                            elevation = 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackpadPrimaryHalf(
                        held = leftButtonHeld,
                        label = stringResource(if (leftButtonHeld) R.string.trackpad_left_dragging else R.string.trackpad_left_drag_hint),
                        onPressChange = { curOnLeftPress(it) },
                        modifier = Modifier.weight(1f),
                    )
                    TrackpadDockDivider()
                    TrackpadSecondaryHalf(
                        held = rightButtonHeld,
                        label = stringResource(R.string.trackpad_right_back),
                        onPressChange = { curOnRightPress(it) },
                        onTrigger = { curOnRightClick() },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .liquidGlassSurface(
                            shape = RoundedCornerShape(14.dp),
                            fill = OpalPalette.LiquidDockFill,
                            rim = OpalPalette.LiquidGlassRim,
                            elevation = 6.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackpadUtilitySegment(
                        glyph = ChromeGlyph.LOCK,
                        label = stringResource(if (dragLockToggled) R.string.trackpad_drag_locked else R.string.trackpad_drag_lock),
                        active = dragLockToggled,
                        activeAccent = OpalPalette.AccentAmber,
                        idleIconTint = OpalPalette.TextSecondary,
                        isDark = isDark,
                        onClick = onToggleDragLock,
                        modifier = Modifier.weight(1f),
                    )
                    TrackpadDockDivider()
                    TrackpadUtilitySegment(
                        glyph = ChromeGlyph.EXPOSE,
                        label = stringResource(R.string.dash_hero_show_desktop),
                        active = false,
                        activeAccent = OpalPalette.AccentBlue,
                        idleIconTint = OpalPalette.TextSecondary,
                        isDark = isDark,
                        onClick = onShowDesktop,
                        modifier = Modifier.weight(1f),
                    )
                    TrackpadDockDivider()
                    TrackpadUtilitySegment(
                        glyph = ChromeGlyph.SCREEN_OFF,
                        label = stringResource(if (hostScreenOff) R.string.dash_hero_wake_screen else R.string.trackpad_screen_off_btn),
                        active = hostScreenOff,
                        activeAccent = OpalPalette.AccentEmerald,
                        idleIconTint = OpalPalette.AccentEmerald,
                        isDark = isDark,
                        onClick = onToggleHostScreenPower,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackpadPrimaryHalf(
                    held = leftButtonHeld,
                    label = stringResource(if (leftButtonHeld) R.string.trackpad_left_held else R.string.trackpad_left_short),
                    onPressChange = { curOnLeftPress(it) },
                    modifier = Modifier.weight(1.2f),
                )
                TrackpadDockDivider()
                TrackpadSecondaryHalf(
                    held = rightButtonHeld,
                    label = stringResource(R.string.trackpad_right_back),
                    onPressChange = { curOnRightPress(it) },
                    onTrigger = { curOnRightClick() },
                    modifier = Modifier.weight(1.0f),
                )
                TrackpadDockDivider()
                TrackpadUtilitySegment(
                    glyph = ChromeGlyph.LOCK,
                    label = stringResource(if (dragLockToggled) R.string.trackpad_drag_locked else R.string.trackpad_drag_lock),
                    active = dragLockToggled,
                    activeAccent = OpalPalette.AccentAmber,
                    idleIconTint = OpalPalette.TextSecondary,
                    isDark = isDark,
                    onClick = onToggleDragLock,
                    modifier = Modifier.weight(0.95f),
                )
                TrackpadDockDivider()
                TrackpadUtilitySegment(
                    glyph = ChromeGlyph.EXPOSE,
                    label = stringResource(R.string.dash_hero_show_desktop),
                    active = false,
                    activeAccent = OpalPalette.AccentBlue,
                    idleIconTint = OpalPalette.TextSecondary,
                    isDark = isDark,
                    onClick = onShowDesktop,
                    modifier = Modifier.weight(0.9f),
                )
                TrackpadDockDivider()
                TrackpadUtilitySegment(
                    glyph = ChromeGlyph.SCREEN_OFF,
                    label = stringResource(if (hostScreenOff) R.string.dash_hero_wake_screen else R.string.trackpad_screen_off_btn),
                    active = hostScreenOff,
                    activeAccent = OpalPalette.AccentEmerald,
                    idleIconTint = OpalPalette.AccentEmerald,
                    isDark = isDark,
                    onClick = onToggleHostScreenPower,
                    modifier = Modifier.weight(0.95f),
                )
            }
        }
    }
}

@Composable
private fun TrackpadPrimaryHalf(
    held: Boolean,
    label: String,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val curPress by rememberUpdatedState(onPressChange)
    val bgBrush = if (held) {
        Brush.verticalGradient(listOf(OpalPalette.AccentBlue, OpalPalette.AccentBlue.copy(alpha = 0.88f)))
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(bgBrush)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    curPress(true)
                    waitForUpOrCancellation()
                    curPress(false)
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (held) Color.White else OpalPalette.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TrackpadSecondaryHalf(
    held: Boolean,
    label: String,
    onPressChange: (Boolean) -> Unit,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val curPress by rememberUpdatedState(onPressChange)
    val curTrigger by rememberUpdatedState(onTrigger)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (held) OpalPalette.ChipActiveFill else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    curPress(true)
                    val up = waitForUpOrCancellation()
                    curPress(false)
                    if (up != null) {
                        curTrigger()
                    }
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TrackpadUtilitySegment(
    glyph: ChromeGlyph,
    label: String,
    active: Boolean,
    activeAccent: Color,
    idleIconTint: Color,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (active) activeAccent.copy(alpha = if (isDark) 0.22f else 0.14f) else Color.Transparent
    val textColor = if (active) activeAccent else OpalPalette.TextPrimary
    val iconTint = if (active) activeAccent else idleIconTint
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpalGlyphIcon(glyph = glyph, tint = iconTint, size = 14.dp)
        Spacer(Modifier.width(5.5.dp))
        OpalText(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun TrackpadDockDivider() {
    Box(
        modifier = Modifier
            .width(0.8.dp)
            .fillMaxHeight()
            .padding(vertical = 10.dp)
            .background(OpalPalette.DividerColor),
    )
}

@SuppressLint("ClickableViewAccessibility")
@Composable
internal fun TrackpadTouchSurface(
    sensitivity: Float,
    onMoveCursor: (Float, Float) -> Unit,
    onSetPrimaryButton: (Boolean) -> Unit,
    onTapPrimary: () -> Unit,
    onScroll: (Float, Float) -> Unit,
    onSecondaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val curSens by rememberUpdatedState(sensitivity)
    val curMove by rememberUpdatedState(onMoveCursor)
    val curPrimary by rememberUpdatedState(onSetPrimaryButton)
    val curTap by rememberUpdatedState(onTapPrimary)
    val curScroll by rememberUpdatedState(onScroll)
    val curSecondary by rememberUpdatedState(onSecondaryAction)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PrecisionTrackpadView(
                ctx = ctx,
                getSensitivity = { curSens },
                onMove = { dx, dy -> curMove(dx, dy) },
                onPrimaryChanged = { down -> curPrimary(down) },
                onTap = { curTap() },
                onScroll = { hx, vy -> curScroll(hx, vy) },
                onSecondary = { curSecondary() },
            )
        },
    )
}

@SuppressLint("ViewConstructor")
private class PrecisionTrackpadView(
    ctx: Context,
    private val getSensitivity: () -> Float,
    private val onMove: (Float, Float) -> Unit,
    private val onPrimaryChanged: (Boolean) -> Unit,
    private val onTap: () -> Unit,
    private val onScroll: (Float, Float) -> Unit,
    private val onSecondary: () -> Unit,
) : View(ctx) {

    private var downTimeMs = 0L
    private var lastTapUpMs = 0L
    private var maxPointers = 0
    private var totalDistance = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastHoverX = Float.NaN
    private var lastHoverY = Float.NaN
    private var dragLockActive = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val isMouse = (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        if (isMouse) return handleMouseTouch(ev)

        val masked = ev.actionMasked
        val count = ev.pointerCount
        when (masked) {
            MotionEvent.ACTION_DOWN -> {
                val now = SystemClock.uptimeMillis()
                downTimeMs = now
                maxPointers = 1
                totalDistance = 0f
                lastX = ev.x
                lastY = ev.y
                if (now - lastTapUpMs <= 260L) {
                    dragLockActive = true
                    onPrimaryChanged(true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (count > maxPointers) maxPointers = count
                if (dragLockActive) {
                    dragLockActive = false
                    onPrimaryChanged(false)
                }
                lastX = avgX(ev)
                lastY = avgY(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                val cx = if (count >= 2) avgX(ev) else ev.x
                val cy = if (count >= 2) avgY(ev) else ev.y
                val dx = cx - lastX
                val dy = cy - lastY
                lastX = cx
                lastY = cy
                totalDistance += hypot(dx, dy)

                if (count >= 2) {
                    val density = resources.displayMetrics.density.coerceAtLeast(1f)
                    val scrollScale = (0.045f / density) * getSensitivity()
                    onScroll(-dx * scrollScale, dy * scrollScale)
                } else {
                    val speed = hypot(dx, dy)
                    val accel = (1f + (speed / 28f).coerceAtMost(1.35f)) * getSensitivity()
                    onMove(dx * accel, dy * accel)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIdx = if (ev.actionIndex == 0 && count > 1) 1 else 0
                lastX = ev.getX(remainingIdx)
                lastY = ev.getY(remainingIdx)
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = SystemClock.uptimeMillis() - downTimeMs
                if (dragLockActive) {
                    dragLockActive = false
                    onPrimaryChanged(false)
                    lastTapUpMs = 0L
                } else if (elapsed <= 240L && totalDistance < 18f) {
                    if (maxPointers >= 2) {
                        onSecondary()
                    } else {
                        onTap()
                        lastTapUpMs = SystemClock.uptimeMillis()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragLockActive) {
                    dragLockActive = false
                    onPrimaryChanged(false)
                }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!lastHoverX.isNaN() && !lastHoverY.isNaN()) {
                    onMove((ev.x - lastHoverX) * getSensitivity(), (ev.y - lastHoverY) * getSensitivity())
                }
                lastHoverX = ev.x
                lastHoverY = ev.y
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                lastHoverX = Float.NaN
                lastHoverY = Float.NaN
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                onScroll(ev.getAxisValue(MotionEvent.AXIS_HSCROLL), ev.getAxisValue(MotionEvent.AXIS_VSCROLL))
                return true
            }
        }
        return super.onGenericMotionEvent(ev)
    }

    private fun handleMouseTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastHoverX = ev.x
                lastHoverY = ev.y
                if ((ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    onSecondary()
                } else {
                    onPrimaryChanged(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!lastHoverX.isNaN() && !lastHoverY.isNaN()) {
                    onMove((ev.x - lastHoverX) * getSensitivity(), (ev.y - lastHoverY) * getSensitivity())
                }
                lastHoverX = ev.x
                lastHoverY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onPrimaryChanged(false)
                lastHoverX = ev.x
                lastHoverY = ev.y
            }
        }
        return true
    }

    private fun avgX(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getX(i)
        return sum / ev.pointerCount.coerceAtLeast(1)
    }

    private fun avgY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount.coerceAtLeast(1)
    }
}
