package io.github.lengsukq.opaldesk.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText

@Composable
internal fun WidgetBadgeIcon(type: DesktopWidgetType, accent: Color) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.92f), accent.copy(alpha = 0.62f)),
                ),
            )
            .border(0.8.dp, Color.White.copy(alpha = 0.36f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val sw = 1.7.dp.toPx()
            when (type) {
                DesktopWidgetType.DESKTOP_HUB -> {
                    drawRoundRect(
                        Color.White,
                        Offset(w * 0.10f, h * 0.16f),
                        Size(w * 0.80f, h * 0.48f),
                        CornerRadius(3.dp.toPx()),
                        Stroke(sw),
                    )
                    drawCircle(Color.White, radius = w * 0.075f, center = Offset(w * 0.32f, h * 0.82f))
                    drawCircle(Color.White.copy(alpha = 0.65f), radius = w * 0.06f, center = Offset(w * 0.50f, h * 0.82f))
                    drawCircle(Color.White.copy(alpha = 0.65f), radius = w * 0.06f, center = Offset(w * 0.68f, h * 0.82f))
                }
                DesktopWidgetType.CLOCK_TIMER -> {
                    drawCircle(Color.White, radius = w * 0.42f, style = Stroke(sw))
                    drawLine(Color.White, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.24f), sw, StrokeCap.Round)
                    drawLine(Color.White, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.72f, h * 0.56f), sw, StrokeCap.Round)
                }
                DesktopWidgetType.MEDIA_PLAYER -> {
                    drawCircle(Color.White, radius = w * 0.16f, center = Offset(w * 0.34f, h * 0.72f))
                    drawLine(Color.White, Offset(w * 0.48f, h * 0.72f), Offset(w * 0.48f, h * 0.20f), sw, StrokeCap.Round)
                    drawLine(Color.White, Offset(w * 0.48f, h * 0.20f), Offset(w * 0.80f, h * 0.30f), sw, StrokeCap.Round)
                }
                DesktopWidgetType.WORKSTATION_STATUS -> {
                    drawRoundRect(Color.White, Offset(w * 0.10f, h * 0.26f), Size(w * 0.70f, h * 0.48f), CornerRadius(2.5.dp.toPx()), Stroke(sw))
                    drawRoundRect(Color.White, Offset(w * 0.20f, h * 0.36f), Size(w * 0.42f, h * 0.28f), CornerRadius(1.5.dp.toPx()))
                    drawLine(Color.White, Offset(w * 0.88f, h * 0.40f), Offset(w * 0.88f, h * 0.60f), sw, StrokeCap.Round)
                }
                DesktopWidgetType.NOTIFICATION_OTP -> {
                    drawRoundRect(Color.White, Offset(w * 0.12f, h * 0.22f), Size(w * 0.76f, h * 0.56f), CornerRadius(3.dp.toPx()), Stroke(sw))
                    drawLine(Color.White, Offset(w * 0.28f, h * 0.50f), Offset(w * 0.72f, h * 0.50f), sw, StrokeCap.Round)
                }
                DesktopWidgetType.QUICK_NOTES -> {
                    drawRoundRect(Color.White, Offset(w * 0.16f, h * 0.12f), Size(w * 0.68f, h * 0.76f), CornerRadius(2.5.dp.toPx()), Stroke(sw))
                    drawLine(Color.White, Offset(w * 0.30f, h * 0.38f), Offset(w * 0.70f, h * 0.38f), sw * 0.85f, StrokeCap.Round)
                    drawLine(Color.White, Offset(w * 0.30f, h * 0.60f), Offset(w * 0.58f, h * 0.60f), sw * 0.85f, StrokeCap.Round)
                }
                DesktopWidgetType.APP_GROUP_LAUNCHER -> {
                    val r = CornerRadius(1.8.dp.toPx())
                    drawRoundRect(Color.White, Offset(w * 0.14f, h * 0.14f), Size(w * 0.30f, h * 0.30f), r)
                    drawRoundRect(Color.White, Offset(w * 0.56f, h * 0.14f), Size(w * 0.30f, h * 0.30f), r)
                    drawRoundRect(Color.White, Offset(w * 0.14f, h * 0.56f), Size(w * 0.30f, h * 0.30f), r)
                    drawRoundRect(Color.White.copy(alpha = 0.65f), Offset(w * 0.56f, h * 0.56f), Size(w * 0.30f, h * 0.30f), r)
                }
                DesktopWidgetType.TODO_CHECKLIST -> {
                    drawCircle(Color.White, radius = w * 0.11f, center = Offset(w * 0.24f, h * 0.32f))
                    drawLine(Color.White, Offset(w * 0.44f, h * 0.32f), Offset(w * 0.84f, h * 0.32f), sw, StrokeCap.Round)
                    drawCircle(Color.White, radius = w * 0.11f, center = Offset(w * 0.24f, h * 0.68f), style = Stroke(sw * 0.85f))
                    drawLine(Color.White, Offset(w * 0.44f, h * 0.68f), Offset(w * 0.74f, h * 0.68f), sw, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
internal fun WidgetMiniPreview(type: DesktopWidgetType, accent: Color) {
    val colors = LocalOpalColors.current
    val previewShape = RoundedCornerShape(14.dp)
    val bg = if (colors.isDark) Color(0x550D111B) else Color(0x88FFFFFF)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(previewShape)
            .background(bg)
            .border(0.7.dp, OpalPalette.BorderHairline, previewShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when (type) {
            DesktopWidgetType.DESKTOP_HUB -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OpalText("09:41", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OpalPalette.TextPrimary)
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(OpalPalette.AccentEmerald.copy(alpha = 0.22f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            OpalText("96%", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.AccentEmerald)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { idx ->
                            Box(
                                modifier = Modifier
                                    .size(width = 18.dp, height = 12.dp)
                                    .clip(CircleShape)
                                    .background(if (idx == 0) accent.copy(alpha = 0.30f) else OpalPalette.ChipFill),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(24.dp).height(5.dp).clip(CircleShape).background(accent))
                    repeat(3) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(OpalPalette.TextMuted.copy(alpha = 0.45f)))
                    }
                }
            }
            DesktopWidgetType.CLOCK_TIMER -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    OpalText("09:41", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OpalPalette.TextPrimary)
                    OpalText("25:00 Focus", fontSize = 10.sp, color = accent, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f))
                        .border(2.dp, accent, CircleShape),
                )
            }
            DesktopWidgetType.MEDIA_PLAYER -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(accent, OpalPalette.AccentCyan))),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth(0.75f).height(7.dp).clip(CircleShape).background(OpalPalette.TextPrimary.copy(alpha = 0.65f)))
                    Box(Modifier.fillMaxWidth(0.48f).height(5.dp).clip(CircleShape).background(OpalPalette.TextMuted.copy(alpha = 0.45f)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { idx ->
                        Box(
                            modifier = Modifier
                                .size(if (idx == 1) 20.dp else 14.dp)
                                .clip(CircleShape)
                                .background(if (idx == 1) accent else OpalPalette.ChipFill),
                        )
                    }
                }
            }
            DesktopWidgetType.WORKSTATION_STATUS -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpalText("88%", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent)
                repeat(3) { idx ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (idx == 0) accent.copy(alpha = 0.22f) else OpalPalette.ChipFill),
                    )
                }
            }
            DesktopWidgetType.NOTIFICATION_OTP -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(92.dp).height(6.dp).clip(CircleShape).background(OpalPalette.TextPrimary.copy(alpha = 0.55f)))
                    Box(Modifier.width(128.dp).height(5.dp).clip(CircleShape).background(OpalPalette.TextMuted.copy(alpha = 0.40f)))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(accent.copy(alpha = 0.20f))
                        .border(0.8.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    OpalText("482 910", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
                }
            }
            DesktopWidgetType.QUICK_NOTES -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.fillMaxWidth(0.82f).height(6.dp).clip(CircleShape).background(OpalPalette.TextPrimary.copy(alpha = 0.50f)))
                Box(Modifier.fillMaxWidth(0.60f).height(5.dp).clip(CircleShape).background(OpalPalette.TextMuted.copy(alpha = 0.40f)))
                Box(Modifier.width(56.dp).height(12.dp).clip(CircleShape).background(accent.copy(alpha = 0.22f)))
            }
            DesktopWidgetType.APP_GROUP_LAUNCHER -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(accent, OpalPalette.AccentBlue, OpalPalette.AccentEmerald, OpalPalette.AccentAmber).forEach { col ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(col.copy(alpha = 0.28f))
                            .border(0.8.dp, col.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.20f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    OpalText("Split", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = accent)
                }
            }
            DesktopWidgetType.TODO_CHECKLIST -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(true, false).forEach { done ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(if (done) accent else Color.Transparent)
                                .border(1.2.dp, if (done) accent else OpalPalette.TextMuted, CircleShape),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (done) 0.58f else 0.74f)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(OpalPalette.TextPrimary.copy(alpha = if (done) 0.30f else 0.60f)),
                        )
                    }
                }
            }
        }
    }
}
