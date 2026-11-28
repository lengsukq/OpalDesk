package io.github.lengsukq.opaldesk.compositor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.notifications.DesktopNotificationItem
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DesktopBellIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val bellPath = Path().apply {
            moveTo(w * 0.22f, h * 0.68f)
            lineTo(w * 0.28f, h * 0.42f)
            cubicTo(w * 0.28f, h * 0.20f, w * 0.72f, h * 0.20f, w * 0.72f, h * 0.42f)
            lineTo(w * 0.78f, h * 0.68f)
            close()
        }
        drawPath(bellPath, color = tint, style = stroke)
        drawLine(tint, Offset(w * 0.16f, h * 0.68f), Offset(w * 0.84f, h * 0.68f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.39f, h * 0.70f),
            size = Size(w * 0.22f, h * 0.18f),
            style = stroke,
        )
    }
}

@Composable
private fun SwipeableNotificationContainer(
    itemKey: String,
    swipeEnabled: Boolean,
    onTap: () -> Unit,
    onSwipeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 72.dp.toPx() }
    val flyOutPx = with(density) { 360.dp.toPx() }
    val offsetX = remember(itemKey) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var isDragging by remember(itemKey) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer {
                val progress = (abs(offsetX.value) / (dismissThresholdPx * 1.8f)).coerceIn(0f, 1f)
                alpha = 1f - progress * 0.78f
            }
            .pointerInput(itemKey, swipeEnabled) {
                detectTapGestures(
                    onTap = {
                        if (!isDragging && abs(offsetX.value) < 6f) {
                            onTap()
                        }
                    },
                )
            }
            .then(
                if (swipeEnabled) {
                    Modifier.pointerInput(itemKey) {
                        detectHorizontalDragGestures(
                            onDragStart = { isDragging = true },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    offsetX.snapTo(offsetX.value + dragAmount)
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                scope.launch { offsetX.animateTo(0f, tween(180)) }
                            },
                            onDragEnd = {
                                isDragging = false
                                val current = offsetX.value
                                if (abs(current) >= dismissThresholdPx) {
                                    val target = if (current >= 0f) flyOutPx else -flyOutPx
                                    scope.launch {
                                        offsetX.animateTo(target, tween(160))
                                        onSwipeDismiss()
                                    }
                                } else {
                                    scope.launch { offsetX.animateTo(0f, tween(180)) }
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

/**
 * macOS-style translucent Liquid Glass heads-up notification toast anchored in the top-right corner
 * of the DeX desktop. Auto-dismisses after 4.5s and opens/focuses the app inside DeX when tapped.
 */
@Composable
fun DesktopNotificationBanner(
    item: DesktopNotificationItem,
    onOpenApp: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(item.key, item.postTimeMs) {
        delay(4500L)
        onDismiss()
    }

    val shape = RoundedCornerShape(18.dp)
    SwipeableNotificationContainer(
        itemKey = "${item.key}:${item.postTimeMs}",
        swipeEnabled = true,
        onTap = {
            onDismiss()
            onOpenApp(item.packageName)
        },
        onSwipeDismiss = onDismiss,
        modifier = modifier.widthIn(min = 240.dp, max = 340.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassSurface(
                    shape = shape,
                    fill = OpalPalette.LiquidPopoverFill,
                    rim = OpalPalette.LiquidGlassRim,
                    elevation = 20.dp,
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.24f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            OpalText(
                text = item.appLabel.take(1).uppercase(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = OpalPalette.AccentBlue,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = item.appLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentBlue,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                OpalText(
                    text = item.formattedTime(),
                    fontSize = 10.sp,
                    color = OpalPalette.TextMuted,
                    maxLines = 1,
                )
            }
            OpalText(
                text = item.title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
                maxLines = 1,
            )
            if (item.text.isNotBlank()) {
                OpalText(
                    text = item.text,
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    maxLines = 2,
                    lineHeight = 15.5.sp,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(OpalPalette.ChipFill)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            OpalText(text = "×", fontSize = 12.sp, color = OpalPalette.TextMuted)
        }
        }
    }
}

/**
 * macOS-style Liquid Glass Notification Center dropdown anchored at the top-right of the DeX bar.
 */
@Composable
fun DesktopNotificationCenterPopover(
    notifications: List<DesktopNotificationItem>,
    accessGranted: Boolean,
    doNotDisturb: Boolean = false,
    maxHeight: Dp,
    onOpenApp: (String) -> Unit,
    onDismissNotification: (String) -> Unit,
    onClearAll: () -> Unit,
    onToggleDoNotDisturb: () -> Unit = {},
    onRequestAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .widthIn(min = 290.dp, max = 360.dp)
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 24.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OpalText(text = stringResource(R.string.notif_center_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (notifications.isNotEmpty()) {
                    OpalStatusBadge(label = "${notifications.size}", color = OpalPalette.AccentBlue)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (doNotDisturb) OpalPalette.AccentAmber.copy(alpha = 0.20f) else OpalPalette.ChipFill,
                        )
                        .border(
                            width = if (doNotDisturb) 0.75.dp else 0.dp,
                            color = if (doNotDisturb) OpalPalette.AccentAmber.copy(alpha = 0.50f) else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable(onClick = onToggleDoNotDisturb)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    OpalText(
                        text = stringResource(if (doNotDisturb) R.string.notif_dnd_on else R.string.taskbar_cc_dnd),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (doNotDisturb) OpalPalette.AccentAmber else OpalPalette.TextSecondary,
                    )
                }
                if (notifications.any { it.clearable }) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.ChipFill)
                            .clickable(onClick = onClearAll)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        OpalText(
                            text = stringResource(R.string.notif_clear_all),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = OpalPalette.TextSecondary,
                        )
                    }
                }
            }
        }

        if (!accessGranted) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(OpalPalette.ChipFill)
                    .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpalText(
                    text = stringResource(R.string.notif_enable_title),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                OpalText(
                    text = stringResource(R.string.notif_enable_desc),
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    lineHeight = 16.sp,
                )
                OpalButton(
                    label = stringResource(R.string.notif_enable_btn),
                    compact = true,
                    onClick = onRequestAccess,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(OpalPalette.ChipFill)
                    .padding(vertical = 24.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OpalText(text = stringResource(R.string.notif_empty_title), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OpalPalette.TextSecondary)
                    OpalText(text = stringResource(R.string.notif_empty_sub), fontSize = 11.sp, color = OpalPalette.TextMuted)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notifications.forEach { item ->
                    val itemShape = RoundedCornerShape(15.dp)
                    SwipeableNotificationContainer(
                        itemKey = item.key,
                        swipeEnabled = item.clearable,
                        onTap = { onOpenApp(item.packageName) },
                        onSwipeDismiss = { onDismissNotification(item.key) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(itemShape)
                                .background(OpalPalette.ChipFill)
                                .border(0.7.dp, OpalPalette.BorderSubtle, itemShape)
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.13f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            OpalText(
                                text = item.appLabel.take(1).uppercase(),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = OpalPalette.AccentBlue,
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                OpalText(
                                    text = item.appLabel,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpalPalette.AccentBlue,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(6.dp))
                                OpalText(
                                    text = item.formattedTime(),
                                    fontSize = 10.sp,
                                    color = OpalPalette.TextMuted,
                                    maxLines = 1,
                                )
                            }
                            OpalText(
                                text = item.title,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            if (item.text.isNotBlank()) {
                                OpalText(
                                    text = item.text,
                                    fontSize = 11.5.sp,
                                    color = OpalPalette.TextSecondary,
                                    maxLines = 3,
                                    lineHeight = 15.5.sp,
                                )
                            }
                        }
                        if (item.clearable) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable { onDismissNotification(item.key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                OpalText(text = "×", fontSize = 13.sp, color = OpalPalette.TextMuted)
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}
