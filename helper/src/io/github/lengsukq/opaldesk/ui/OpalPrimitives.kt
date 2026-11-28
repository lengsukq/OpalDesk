package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Authentic macOS Liquid Frosted Glass (液态毛玻璃) optical surface modifier:
 * combines exterior-only diffused halo shadow (masked via ClipOp.Difference so zero shadow
 * ever bleeds inside translucent glass), frosted fill, convex top specular sheen, and refractive rim.
 */
@Composable
fun Modifier.liquidGlassSurface(
    shape: Shape,
    fill: Brush = OpalPalette.LiquidDockFill,
    rim: Brush = OpalPalette.LiquidGlassRim,
    elevation: Dp = 18.dp,
    borderWidth: Dp = 1.dp,
    effectAlphaScale: Float = 1f,
): Modifier {
    val c = LocalOpalColors.current
    val effScale = effectAlphaScale.coerceIn(0f, 1f)
    val specularTop = c.glassSpecularTop.copy(alpha = (c.glassSpecularTop.alpha * effScale).coerceIn(0f, 1f))
    val shadowCol = c.glassShadowColor
    val base = this
        .drawBehind {
            if (elevation > 0.dp && effScale > 0.001f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                val innerPath = Path().apply { addOutline(outline) }
                val elevPx = elevation.toPx().coerceAtMost(26.dp.toPx())
                clipPath(innerPath, clipOp = ClipOp.Difference) {
                    val layers = 4
                    for (i in 1..layers) {
                        val t = i.toFloat() / layers.toFloat()
                        val spread = elevPx * 0.24f * t
                        val yOff = elevPx * 0.26f * t
                        val alpha = shadowCol.alpha * effScale * (0.44f * (1f - t * 0.72f))
                        val outerOutline = shape.createOutline(
                            Size(size.width + spread * 2f, size.height + spread * 2f),
                            layoutDirection,
                            this@drawBehind,
                        )
                        translate(left = -spread, top = yOff - spread * 0.65f) {
                            drawOutline(
                                outline = outerOutline,
                                color = shadowCol.copy(alpha = alpha),
                            )
                        }
                    }
                }
            }
        }
        .clip(shape)
        .background(fill)
        .drawWithContent {
            // Subtle liquid glass optical sheen across the upper convex arc
            if (effScale > 0.001f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to specularTop,
                            0.38f to specularTop.copy(alpha = specularTop.alpha * 0.22f),
                            0.65f to Color.Transparent,
                            1.0f to Color.Transparent,
                        ),
                    ),
                )
            }
            drawContent()
        }
    return if (borderWidth > 0.dp && effScale > 0.001f) {
        base.border(borderWidth, rim, shape)
    } else {
        base
    }
}

/**
 * Single-gesture unified desktop context-menu modifier (zero race between right-click and tap!):
 * - Physical Mouse Right-Click (`BUTTON_SECONDARY`) fires [onContextAction] immediately with
 *   exact root-canvas pixel coordinates and drains the gesture until release so [onPrimaryTap] NEVER fires.
 * - Touchscreen Long-Press fires [onContextAction] ("长按等于右键") with exact root-canvas coordinates.
 * - Left-Click / Single Tap fires [onPrimaryTap] cleanly; dragging beyond touchSlop yields to scrolling.
 */
@Composable
fun Modifier.onTapOrContextAction(
    vararg keys: Any?,
    onPrimaryTap: () -> Unit = {},
    onContextAction: (Offset) -> Unit,
): Modifier {
    val rootOriginRef = remember { mutableStateOf(Offset.Zero) }
    val curPrimary by rememberUpdatedState(onPrimaryTap)
    val curContext by rememberUpdatedState(onContextAction)

    return this
        .onGloballyPositioned { coords ->
            rootOriginRef.value = coords.positionInRoot()
        }
        .pointerInput(keys.contentHashCode()) {
            val touchSlop = viewConfiguration.touchSlop
            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downPos = down.position
                val buttons = currentEvent.buttons

                // 1. Mouse right-click: invoke context menu immediately at exact cursor root position
                if (buttons.isSecondaryPressed) {
                    down.consume()
                    curContext(rootOriginRef.value + downPos)
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.all { !it.pressed }) break
                    }
                    return@awaitEachGesture
                }

                // 2. Ignore tertiary / side buttons for primary tap
                if (buttons.isTertiaryPressed || buttons.isBackPressed || buttons.isForwardPressed) {
                    return@awaitEachGesture
                }

                // 3. Primary click / finger touch: wait for release within slop or long-press timeout
                var lastPos = downPos
                val releasedCleanly = withTimeoutOrNull(longPressTimeout) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val change = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        lastPos = change.position
                        val movedDist = (change.position - downPos).getDistance()
                        if (movedDist > touchSlop) {
                            return@withTimeoutOrNull false
                        }
                        if (change.isConsumed && change.position != change.previousPosition) {
                            return@withTimeoutOrNull false
                        }
                        if (!change.pressed) {
                            if (change.isConsumed) return@withTimeoutOrNull false
                            change.consume()
                            return@withTimeoutOrNull true
                        }
                    }
                }

                if (releasedCleanly == true) {
                    curPrimary()
                } else if (releasedCleanly == null) {
                    val held = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (held != null && held.pressed && (lastPos - downPos).getDistance() <= touchSlop) {
                        held.consume()
                        curContext(rootOriginRef.value + lastPos)
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.all { !it.pressed }) break
                        }
                    }
                }
            }
        }
}

@Composable
fun OpalText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = OpalPalette.TextPrimary,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    fontFamily: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            lineHeight = lineHeight,
            textAlign = textAlign,
        ),
    )
}

@Composable
fun OpalGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    fillColor: Color = OpalPalette.GlassSurface,
    borderColor: Color = OpalPalette.BorderSubtle,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(fillColor)
            .border(0.9.dp, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun OpalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = OpalPalette.AccentBlue,
    filled: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(if (compact) 10.dp else 13.dp)
    val bg = when {
        !enabled -> OpalPalette.ChipFill
        filled -> accent
        else -> accent.copy(alpha = if (OpalPalette.isDark) 0.16f else 0.12f)
    }
    val borderCol = when {
        !enabled -> OpalPalette.BorderHairline
        filled -> Color.White.copy(alpha = 0.24f)
        else -> accent.copy(alpha = if (OpalPalette.isDark) 0.36f else 0.28f)
    }
    val textCol = when {
        !enabled -> OpalPalette.TextMuted
        filled -> Color.White
        else -> accent
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(0.8.dp, borderCol, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 7.dp else 10.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            color = textCol,
            fontSize = if (compact) 12.sp else 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun OpalTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    val colors = LocalOpalColors.current
    val hostView = LocalView.current
    val shape = RoundedCornerShape(11.dp)
    var fieldFocused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (fieldFocused) {
                val dispId = hostView.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
                OpalImeFocusTracker.setLocalTextFieldFocused(false, dispId)
            }
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        modifier = Modifier.onFocusChanged { st ->
            fieldFocused = st.isFocused
            val dispId = hostView.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
            OpalImeFocusTracker.setLocalTextFieldFocused(st.isFocused, dispId)
        },
        textStyle = TextStyle(
            color = colors.textPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
        ),
        cursorBrush = SolidColor(colors.accentBlue),
        decorationBox = { inner ->
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(colors.inputBg)
                    .border(0.8.dp, colors.borderSubtle, shape)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    OpalText(text = placeholder, color = colors.textMuted, fontSize = 13.sp, maxLines = 1)
                }
                inner()
            }
        },
    )
}

@Composable
fun OpalSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            OpalText(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                OpalText(text = subtitle, fontSize = 12.sp, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(25.dp)
                .clip(CircleShape)
                .background(if (checked) colors.accentEmerald else colors.switchTrackOff)
                .padding(2.5.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
fun OpalSegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.segmentedTrack)
            .border(0.8.dp, colors.borderHairline, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val pillShape = RoundedCornerShape(9.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(pillShape)
                    .background(if (selected) colors.segmentedSelected else Color.Transparent)
                    .border(
                        width = if (selected) 0.75.dp else 0.dp,
                        color = if (selected) colors.segmentedSelectedBorder else Color.Transparent,
                        shape = pillShape,
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                OpalText(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Smooth macOS-style stepless continuous glass slider supporting both tap-to-jump and fluid dragging.
 */
@Composable
fun OpalGlassSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpalColors.current
    val density = LocalDensity.current
    val curOnChange by rememberUpdatedState(onValueChange)
    val curOnFinished by rememberUpdatedState(onValueChangeFinished)
    var trackWidthPx by remember { mutableIntStateOf(1) }

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val thumbSizeDp = 20.dp
    val thumbSizePx = with(density) { thumbSizeDp.toPx() }
    val usablePx = (trackWidthPx - thumbSizePx).coerceAtLeast(1f)

    fun updateFromPx(xPx: Float) {
        val norm = ((xPx - thumbSizePx * 0.5f) / usablePx).coerceIn(0f, 1f)
        curOnChange(valueRange.start + norm * span)
    }

    Box(
        modifier = modifier
            .height(30.dp)
            .onSizeChanged { trackWidthPx = it.width.coerceAtLeast(1) }
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    updateFromPx(offset.x)
                    curOnFinished?.invoke()
                }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures(
                    onDragEnd = { curOnFinished?.invoke() },
                    onDragCancel = { curOnFinished?.invoke() },
                ) { change, _ ->
                    change.consume()
                    updateFromPx(change.position.x)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(colors.segmentedTrack)
                .border(0.6.dp, colors.borderHairline, CircleShape),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(colors.accentBlue),
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((fraction * usablePx).roundToInt(), 0) }
                .size(thumbSizeDp)
                .shadow(5.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .border(0.8.dp, Color(0x300F172A), CircleShape),
        )
    }
}

@Composable
fun OpalStatusBadge(label: String, color: Color) {
    val isDark = OpalPalette.isDark
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (isDark) 0.15f else 0.12f))
            .border(0.8.dp, color.copy(alpha = if (isDark) 0.36f else 0.28f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.5.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.5.dp))
        OpalText(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

enum class TrafficLightType { CLOSE, MINIMIZE, ZOOM }

@Composable
fun MacTrafficLightButton(
    type: TrafficLightType,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val inactive = OpalPalette.TrafficInactive
    val fill = when {
        !focused -> inactive
        type == TrafficLightType.CLOSE -> OpalPalette.TrafficRed
        type == TrafficLightType.MINIMIZE -> OpalPalette.TrafficYellow
        else -> OpalPalette.TrafficGreen
    }
    val stroke = when {
        !focused -> OpalPalette.BorderHairline
        type == TrafficLightType.CLOSE -> OpalPalette.TrafficRedBorder
        type == TrafficLightType.MINIMIZE -> OpalPalette.TrafficYellowBorder
        else -> OpalPalette.TrafficGreenBorder
    }

    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.5.dp)
                .clip(CircleShape)
                .background(fill)
                .border(0.75.dp, stroke, CircleShape),
        )
    }
}

/**
 * Compact capsule icon button shared by top-center floating control pills (Fullscreen overlay & PeerCast bar).
 */
@Composable
fun OpalTopPillIconButton(
    glyph: ChromeGlyph,
    contentDescription: String,
    tint: Color = OpalPalette.TextPrimary,
    active: Boolean = false,
    activeColor: Color = OpalPalette.AccentBlue,
    onClick: () -> Unit,
) {
    val effectiveTint = if (active) activeColor else tint
    val bg = if (active) {
        activeColor.copy(alpha = if (OpalPalette.isDark) 0.24f else 0.16f)
    } else {
        OpalPalette.ChipFill
    }
    val borderColor = if (active) {
        activeColor.copy(alpha = if (OpalPalette.isDark) 0.48f else 0.36f)
    } else {
        Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 26.dp)
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, borderColor, CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(glyph = glyph, tint = effectiveTint, size = 13.5.dp)
    }
}

/**
 * Matching-height capsule text (or optional leading icon + text) chip for domain-specific top-pill actions
 * whose semantics are clearer with concise wording than an icon alone.
 */
@Composable
fun OpalTopPillChip(
    label: String,
    glyph: ChromeGlyph? = null,
    tint: Color = OpalPalette.TextPrimary,
    active: Boolean = false,
    activeColor: Color = OpalPalette.AccentBlue,
    filled: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = if (danger) OpalPalette.AccentDanger else activeColor
    val bg = when {
        filled -> accent
        danger -> OpalPalette.AccentDanger.copy(alpha = if (OpalPalette.isDark) 0.20f else 0.12f)
        active -> accent.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.14f)
        else -> OpalPalette.ChipFill
    }
    val borderColor = when {
        filled -> Color.White.copy(alpha = 0.22f)
        danger -> OpalPalette.AccentDanger.copy(alpha = if (OpalPalette.isDark) 0.42f else 0.30f)
        active -> accent.copy(alpha = if (OpalPalette.isDark) 0.42f else 0.30f)
        else -> Color.Transparent
    }
    val contentTint = when {
        filled -> Color.White
        danger -> OpalPalette.AccentDanger
        else -> tint
    }
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (glyph != null) 8.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (glyph != null) {
            OpalGlyphIcon(glyph = glyph, tint = contentTint, size = 12.dp)
        }
        OpalText(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentTint,
            maxLines = 1,
        )
    }
}

@Composable
fun OpalTopPillDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(14.dp)
            .background(OpalPalette.DividerColor),
    )
}
