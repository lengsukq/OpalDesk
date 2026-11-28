package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge

/**
 * Shared Liquid Glass cards, Bento tiles, diagnostic banners, and clipboard/settings helpers
 * reused across [ConsoleDashboardTab], [ConsoleDisplaySettingsTab], [ConsoleEngineSettingsTab],
 * [ConsoleLogsTab], and [FeedbackTrackingSection].
 */
@Composable
fun MacGlassSectionCard(
    modifier: Modifier = Modifier,
    accentBorder: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val rimBrush = if (accentBorder != null) {
        Brush.verticalGradient(listOf(accentBorder.copy(alpha = 0.62f), accentBorder.copy(alpha = 0.18f)))
    } else {
        OpalPalette.LiquidGlassRim
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidDockFill,
                rim = rimBrush,
                elevation = if (OpalPalette.isDark) 12.dp else 9.dp,
            )
            .padding(contentPadding),
    ) {
        content()
    }
}

@Composable
internal fun CopyableInfoLine(ctx: Context, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            OpalText(text = label, fontSize = 11.sp, color = OpalPalette.TextMuted)
            OpalText(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(OpalPalette.ChipFill)
                .clickable { copyTextWithToast(ctx, value, ctx.getString(R.string.common_copied, label)) }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            OpalText(text = stringResource(R.string.common_copy), fontSize = 11.sp, color = OpalPalette.AccentCyan, fontWeight = FontWeight.Medium)
        }
    }
}

internal fun resolveActiveExternalDisplayId(extDisplay: BridgeService.ExternalDisplayInfo?): Int {
    val session = ExternalTrackpadBridge.sessionFlow.value
    if (session.displayId > 0) return session.displayId
    return extDisplay?.displayId ?: -1
}

internal fun copyTextWithToast(ctx: Context, text: String, toastMsg: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk", text))
        Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
    }
}

internal fun openDeveloperSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (_: Throwable) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            Toast.makeText(ctx, ctx.getString(R.string.toast_open_dev_settings_manual), Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun openTetherSettings(ctx: Context) {
    val candidates = listOf(
        Intent("android.settings.TETHER_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Throwable) {
        }
    }
    Toast.makeText(ctx, ctx.getString(R.string.toast_open_tether_settings_manual), Toast.LENGTH_SHORT).show()
}

@Composable
internal fun DashboardHeroIconBadge(
    accent: Color,
    active: Boolean,
) {
    val isDark = OpalPalette.isDark
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(accent.copy(alpha = if (isDark) 0.24f else 0.14f))
            .border(0.9.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = w * 0.095f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val r = CornerRadius(w * 0.14f, w * 0.14f)
            drawRoundRect(accent, Offset(w * 0.05f, h * 0.10f), Size(w * 0.90f, h * 0.62f), r, style = stroke)
            if (active) {
                drawRoundRect(
                    accent.copy(alpha = 0.28f),
                    Offset(w * 0.16f, h * 0.22f),
                    Size(w * 0.30f, h * 0.36f),
                    CornerRadius(w * 0.08f, w * 0.08f),
                )
                drawRoundRect(
                    accent.copy(alpha = 0.45f),
                    Offset(w * 0.54f, h * 0.22f),
                    Size(w * 0.30f, h * 0.36f),
                    CornerRadius(w * 0.08f, w * 0.08f),
                )
            }
            drawLine(accent, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.68f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun DashboardBentoTile(
    title: String,
    subtitle: String,
    badgeText: String,
    accent: Color,
    active: Boolean,
    iconKind: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = OpalPalette.isDark
    val shape = RoundedCornerShape(22.dp)
    val fillBrush = if (active) {
        Brush.verticalGradient(
            if (isDark) {
                listOf(accent.copy(alpha = 0.22f), Color(0xE0161C29))
            } else {
                listOf(accent.copy(alpha = 0.14f), Color(0xF0FFFFFF))
            },
        )
    } else {
        OpalPalette.LiquidDockFill
    }
    val rimBrush = if (active) {
        Brush.verticalGradient(listOf(accent.copy(alpha = 0.62f), accent.copy(alpha = 0.18f)))
    } else {
        OpalPalette.LiquidGlassRim
    }
    Column(
        modifier = modifier
            .liquidGlassSurface(
                shape = shape,
                fill = fillBrush,
                rim = rimBrush,
                elevation = if (isDark) 12.dp else 9.dp,
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (isDark) 0.22f else 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(17.dp)) {
                    val w = size.width
                    val h = size.height
                    val stroke = Stroke(width = w * 0.105f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    if (iconKind == 0) {
                        drawRoundRect(
                            accent,
                            Offset(w * 0.06f, h * 0.12f),
                            Size(w * 0.88f, h * 0.58f),
                            CornerRadius(w * 0.14f, w * 0.14f),
                            style = stroke,
                        )
                        drawLine(accent, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.50f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                        drawLine(accent, Offset(w * 0.28f, h * 0.88f), Offset(w * 0.72f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    } else {
                        drawArc(accent, startAngle = 215f, sweepAngle = 110f, useCenter = false, topLeft = Offset(w * 0.08f, h * 0.18f), size = Size(w * 0.84f, h * 0.84f), style = stroke)
                        drawArc(accent, startAngle = 215f, sweepAngle = 110f, useCenter = false, topLeft = Offset(w * 0.26f, h * 0.36f), size = Size(w * 0.48f, h * 0.48f), style = stroke)
                        drawCircle(accent, radius = w * 0.10f, center = Offset(w * 0.50f, h * 0.76f))
                    }
                }
            }
            OpalStatusBadge(
                label = badgeText,
                color = if (active) accent else OpalPalette.TextMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OpalText(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            OpalText(
                text = subtitle,
                fontSize = 11.5.sp,
                color = if (active) accent else OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun UpgradeReactivateTipCard(
    connected: Boolean,
    onOpenActivation: () -> Unit,
) {
    val shape = CircleShape
    val accent = OpalPalette.AccentBlue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OpalPalette.LiquidDockFill)
            .border(0.8.dp, OpalPalette.BorderSubtle, shape)
            .clickable(onClick = onOpenActivation)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpalText(text = "↻", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
        OpalText(
            text = stringResource(R.string.dash_upgrade_tip),
            fontSize = 11.5.sp,
            color = OpalPalette.TextSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        OpalText(
            text = stringResource(if (connected) R.string.dash_upgrade_reactivate else R.string.dash_upgrade_activate),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}

@Composable
internal fun TouchInputTroubleshootingTipCard(
    ctx: Context,
    inputBlocked: Boolean,
) {
    val accent = if (inputBlocked) OpalPalette.AccentAmber else OpalPalette.AccentCyan
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OpalPalette.LiquidDockFill)
            .border(0.8.dp, accent.copy(alpha = if (inputBlocked) 0.55f else 0.24f), shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            OpalText(text = if (inputBlocked) "!" else "i", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        Column(modifier = Modifier.weight(1f)) {
            OpalText(
                text = stringResource(if (inputBlocked) R.string.dash_touch_blocked_title else R.string.dash_touch_troubleshoot_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (inputBlocked) OpalPalette.AccentAmber else OpalPalette.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            OpalText(
                text = if (inputBlocked) {
                    stringResource(R.string.dash_touch_blocked_desc)
                } else {
                    stringResource(R.string.dash_touch_troubleshoot_desc)
                },
                fontSize = 11.5.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 16.sp,
            )
        }
        OpalButton(
            label = stringResource(R.string.dash_touch_open_settings),
            compact = true,
            filled = inputBlocked,
            accent = accent,
            onClick = { openDeveloperSettings(ctx) },
        )
    }
}

internal fun toggleHostScreenPowerWithToast(ctx: Context, screenOn: Boolean) {
    BridgeClient.setHostScreenPowerAsync(screenOn) { ok ->
        val msg = when {
            !ok -> ctx.getString(R.string.toast_host_screen_unsupported)
            screenOn -> ctx.getString(R.string.toast_host_screen_on)
            else -> ctx.getString(R.string.toast_host_screen_off_hint)
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
