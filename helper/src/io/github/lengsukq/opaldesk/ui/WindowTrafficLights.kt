package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol

enum class TrafficLightType { CLOSE, MINIMIZE, FULLSCREEN }

enum class FullscreenTrafficLightGlyphMode { ENTER_FULLSCREEN, EXIT_FULLSCREEN }

/**
 * Pure JVM-testable policy for the macOS-style window traffic light controls
 * (strict order: Close -> Minimize -> Fullscreen / Exit Fullscreen) and fullscreen hover reveal.
 */
object WindowTrafficLightPolicy {
    val ORDERED_TYPES: List<TrafficLightType> = listOf(
        TrafficLightType.CLOSE,
        TrafficLightType.MINIMIZE,
        TrafficLightType.FULLSCREEN,
    )

    val RIGHT_ORDERED_TYPES: List<TrafficLightType> = listOf(
        TrafficLightType.MINIMIZE,
        TrafficLightType.FULLSCREEN,
        TrafficLightType.CLOSE,
    )

    fun orderedTypes(rightAligned: Boolean): List<TrafficLightType> =
        if (rightAligned) RIGHT_ORDERED_TYPES else ORDERED_TYPES

    fun winActionFor(type: TrafficLightType): Int = when (type) {
        TrafficLightType.CLOSE -> BridgeProtocol.WinAction.CLOSE
        TrafficLightType.MINIMIZE -> BridgeProtocol.WinAction.MINIMIZE
        TrafficLightType.FULLSCREEN -> BridgeProtocol.WinAction.TOGGLE_FULLSCREEN
    }

    fun greenGlyphMode(isFullscreen: Boolean): FullscreenTrafficLightGlyphMode = if (isFullscreen) {
        FullscreenTrafficLightGlyphMode.EXIT_FULLSCREEN
    } else {
        FullscreenTrafficLightGlyphMode.ENTER_FULLSCREEN
    }

    fun shouldArmShrinkOnFullscreenToggle(isFullscreen: Boolean): Boolean = isFullscreen

    fun shouldRevealFullscreenBarOnMouseHover(
        elapsedSinceManualCollapseMs: Long,
        guardMs: Long = 650L,
    ): Boolean = elapsedSinceManualCollapseMs >= guardMs
}

@Composable
fun WindowTrafficLightCluster(
    focused: Boolean,
    isFullscreen: Boolean,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onToggleFullscreen: () -> Unit,
    alwaysShowSymbols: Boolean = false,
    rightAligned: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var isClusterHovered by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.any { it.type == PointerType.Mouse }) {
                        when (ev.type) {
                            PointerEventType.Enter, PointerEventType.Move -> isClusterHovered = true
                            PointerEventType.Exit -> isClusterHovered = false
                        }
                    }
                }
            }
        },
    ) {
        WindowTrafficLightPolicy.orderedTypes(rightAligned).forEach { type ->
            val onClick = when (type) {
                TrafficLightType.CLOSE -> onClose
                TrafficLightType.MINIMIZE -> onMinimize
                TrafficLightType.FULLSCREEN -> onToggleFullscreen
            }
            MacTrafficLightButton(
                type = type,
                focused = focused,
                isFullscreen = isFullscreen,
                hovered = isClusterHovered,
                alwaysShowSymbol = alwaysShowSymbols,
                onClick = onClick,
            )
        }
    }
}

@Composable
fun MacTrafficLightButton(
    type: TrafficLightType,
    focused: Boolean,
    isFullscreen: Boolean = false,
    hovered: Boolean = false,
    alwaysShowSymbol: Boolean = false,
    onClick: () -> Unit,
) {
    val activeVisual = focused || hovered || alwaysShowSymbol
    val fill = when {
        !activeVisual -> OpalPalette.TrafficInactive
        type == TrafficLightType.CLOSE -> OpalPalette.TrafficRed
        type == TrafficLightType.MINIMIZE -> OpalPalette.TrafficYellow
        else -> OpalPalette.TrafficGreen
    }
    val stroke = when {
        !activeVisual -> OpalPalette.BorderHairline
        type == TrafficLightType.CLOSE -> OpalPalette.TrafficRedBorder
        type == TrafficLightType.MINIMIZE -> OpalPalette.TrafficYellowBorder
        else -> OpalPalette.TrafficGreenBorder
    }
    val symbolAlpha = when {
        hovered -> 0.88f
        alwaysShowSymbol -> 0.68f
        focused -> 0.56f
        else -> 0f
    }
    val symbolTint = when (type) {
        TrafficLightType.CLOSE -> Color(0xFF4D0000)
        TrafficLightType.MINIMIZE -> Color(0xFF5C3B00)
        TrafficLightType.FULLSCREEN -> Color(0xFF004D12)
    }.copy(alpha = symbolAlpha)
    val labelText = when (type) {
        TrafficLightType.CLOSE -> stringResource(R.string.window_action_close)
        TrafficLightType.MINIMIZE -> stringResource(R.string.window_action_minimize)
        TrafficLightType.FULLSCREEN -> stringResource(
            if (isFullscreen) R.string.taskbar_fs_exit else R.string.taskbar_fullscreen,
        )
    }

    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = labelText
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.5.dp)
                .clip(CircleShape)
                .background(fill)
                .border(0.75.dp, stroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (symbolAlpha > 0f) {
                Canvas(modifier = Modifier.size(7.5.dp)) {
                    val w = size.width
                    val h = size.height
                    val sw = 1.25.dp.toPx()
                    when (type) {
                        TrafficLightType.CLOSE -> {
                            drawLine(symbolTint, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), sw, StrokeCap.Round)
                            drawLine(symbolTint, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), sw, StrokeCap.Round)
                        }
                        TrafficLightType.MINIMIZE -> {
                            drawLine(symbolTint, Offset(w * 0.16f, h * 0.50f), Offset(w * 0.84f, h * 0.50f), sw, StrokeCap.Round)
                        }
                        TrafficLightType.FULLSCREEN -> {
                            if (WindowTrafficLightPolicy.greenGlyphMode(isFullscreen) == FullscreenTrafficLightGlyphMode.ENTER_FULLSCREEN) {
                                val tr = Path().apply {
                                    moveTo(w * 0.40f, h * 0.14f)
                                    lineTo(w * 0.86f, h * 0.14f)
                                    lineTo(w * 0.86f, h * 0.60f)
                                    close()
                                }
                                val bl = Path().apply {
                                    moveTo(w * 0.14f, h * 0.40f)
                                    lineTo(w * 0.14f, h * 0.86f)
                                    lineTo(w * 0.60f, h * 0.86f)
                                    close()
                                }
                                drawPath(tr, symbolTint)
                                drawPath(bl, symbolTint)
                            } else {
                                val trIn = Path().apply {
                                    moveTo(w * 0.52f, h * 0.48f)
                                    lineTo(w * 0.52f, h * 0.14f)
                                    lineTo(w * 0.86f, h * 0.48f)
                                    close()
                                }
                                val blIn = Path().apply {
                                    moveTo(w * 0.48f, h * 0.52f)
                                    lineTo(w * 0.48f, h * 0.86f)
                                    lineTo(w * 0.14f, h * 0.52f)
                                    close()
                                }
                                drawPath(trIn, symbolTint)
                                drawPath(blIn, symbolTint)
                            }
                        }
                    }
                }
            }
        }
    }
}
