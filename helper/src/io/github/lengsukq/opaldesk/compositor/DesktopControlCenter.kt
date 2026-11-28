package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassSlider
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streamlined High-Frequency Liquid Glass Control Center Popover:
 * - Top status & battery header + prominent "DeX Settings ↗" glass pill
 * - High-frequency quick tiles (host blackout, trackpad, mute, force phone speaker, widgets, edit)
 * - Volume & brightness sliders
 * Deep configuration lives in the dedicated DeX Settings app.
 */
@Composable
fun MacControlCenterPopover(
    tray: SystemTrayInfo,
    totalWindows: Int,
    displaySummary: String,
    inputBackendLabel: String,
    isExternalMonitor: Boolean,
    hostScreenOff: Boolean,
    showHostScreenToggle: Boolean,
    showPhoneTrackpadSection: Boolean = false,
    phoneTrackpadVisible: Boolean = false,
    isWidgetEditMode: Boolean = false,
    maxHeight: Dp = 520.dp,
    onOpenDexSettings: () -> Unit = {},
    onTogglePhoneTrackpad: () -> Unit = {},
    onToggleHostScreenPower: (Boolean) -> Unit,
    onOpenWidgetStore: () -> Unit = {},
    onToggleWidgetEditMode: () -> Unit = {},
    onOpenConsole: () -> Unit,
    onExitDesktop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val isZh = remember(ctx) {
        val lang = runCatching {
            ctx.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language
        }.getOrNull() ?: Locale.getDefault().language
        lang.equals("zh", ignoreCase = true)
    }
    val audioMgr = remember(ctx) { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val maxVolume = remember(audioMgr) {
        (try { audioMgr?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { null } ?: 15).coerceAtLeast(1)
    }
    var mediaVolume by remember(audioMgr) {
        mutableIntStateOf(
            (try { audioMgr?.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { null } ?: (maxVolume / 2))
                .coerceIn(0, maxVolume),
        )
    }
    var lastNonZeroVolume by remember { mutableIntStateOf(mediaVolume.coerceAtLeast(1)) }
    var screenBrightness by remember(ctx) { mutableStateOf(ControlCenterActions.readInitialBrightness(ctx)) }
    var brightnessAuto by remember { mutableStateOf(true) }
    var orientationMode by remember(ctx) { mutableIntStateOf(ControlCenterActions.readOrientationMode(ctx)) }

    fun applyMediaVolume(target: Int) {
        val clamped = target.coerceIn(0, maxVolume)
        mediaVolume = clamped
        if (clamped > 0) lastNonZeroVolume = clamped
        try {
            audioMgr?.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        } catch (_: Throwable) {
        }
    }

    val shape = RoundedCornerShape(22.dp)
    val infoCardShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight.coerceAtMost(520.dp))
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 28.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OpalText(text = stringResource(R.string.taskbar_cc_title), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.ChipFill)
                        .padding(horizontal = 7.dp, vertical = 2.5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (totalWindows > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted),
                    )
                    OpalText(
                        text = if (totalWindows > 0) {
                            stringResource(R.string.dash_hero_window_count, totalWindows)
                        } else {
                            stringResource(R.string.common_ready)
                        },
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentCyan,
                        maxLines = 1,
                    )
                }
            }
            val batLabel = if (tray.charging) "⚡${tray.batteryPct}%" else "${tray.batteryPct}%"
            val summaryText = if (displaySummary.isNotBlank()) "$displaySummary · $batLabel" else "$inputBackendLabel · $batLabel"
            OpalText(
                text = summaryText,
                fontSize = 10.5.sp,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(8.dp))
        DexSettingsCapsuleButton(
            label = if (isZh) "DeX 设置 ↗" else "DeX Settings ↗",
            subtitle = if (isZh) "外观 · Dock · 显示 · 音频 · 输入" else "Appearance · Dock · Display · Audio",
            onClick = onOpenDexSettings,
        )

        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ControlCenterQuickPanel(
                isZh = isZh,
                mediaVolume = mediaVolume,
                maxVolume = maxVolume,
                lastNonZeroVolume = lastNonZeroVolume,
                screenBrightness = screenBrightness,
                brightnessAuto = brightnessAuto,
                orientationMode = orientationMode,
                isExternalMonitor = isExternalMonitor,
                hostScreenOff = hostScreenOff,
                showHostScreenToggle = showHostScreenToggle,
                showPhoneTrackpadSection = showPhoneTrackpadSection,
                phoneTrackpadVisible = phoneTrackpadVisible,
                isWidgetEditMode = isWidgetEditMode,
                infoCardShape = infoCardShape,
                onChangeOrientationMode = { next ->
                    orientationMode = next
                    ControlCenterActions.applyOrientationMode(ctx, next)
                },
                onToggleHostScreenPower = onToggleHostScreenPower,
                onTogglePhoneTrackpad = onTogglePhoneTrackpad,
                onOpenWidgetStore = onOpenWidgetStore,
                onToggleWidgetEditMode = onToggleWidgetEditMode,
                onApplyMediaVolume = ::applyMediaVolume,
                onSetBrightnessAuto = {
                    brightnessAuto = true
                    ControlCenterActions.applyWindowBrightness(ctx, -1f)
                },
                onChangeBrightness = { v ->
                    brightnessAuto = false
                    screenBrightness = v
                    ControlCenterActions.applyWindowBrightness(ctx, v)
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OpalButton(
                label = stringResource(R.string.taskbar_cc_console),
                compact = true,
                filled = false,
                onClick = onOpenConsole,
                modifier = Modifier.weight(1f),
            )
            OpalButton(
                label = stringResource(R.string.taskbar_cc_exit_dex),
                compact = true,
                filled = false,
                accent = OpalPalette.AccentDanger,
                onClick = onExitDesktop,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DexSettingsCapsuleButton(
    label: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = LocalOpalColors.current
    val pillShape = RoundedCornerShape(15.dp)
    val bgBrush = Brush.horizontalGradient(
        listOf(
            colors.accentBlue.copy(alpha = if (colors.isDark) 0.28f else 0.16f),
            colors.accentCyan.copy(alpha = if (colors.isDark) 0.20f else 0.12f),
        ),
    )
    val borderBrush = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (colors.isDark) 0.36f else 0.86f),
            colors.accentBlue.copy(alpha = 0.56f),
        ),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(pillShape)
            .background(bgBrush)
            .border(1.dp, borderBrush, pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(colors.accentBlue, colors.accentCyan)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(16.dp)) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val sw = 1.55.dp.toPx()
                val innerR = size.minDimension * 0.17f
                val bodyR = size.minDimension * 0.33f
                val toothOut = size.minDimension * 0.47f
                drawCircle(color = Color.White, radius = innerR, center = Offset(cx, cy), style = Stroke(sw))
                drawCircle(color = Color.White, radius = bodyR, center = Offset(cx, cy), style = Stroke(sw))
                for (i in 0 until 8) {
                    val rad = (i * 45.0) * PI / 180.0
                    drawLine(
                        color = Color.White,
                        start = Offset(cx + (bodyR * cos(rad)).toFloat(), cy + (bodyR * sin(rad)).toFloat()),
                        end = Offset(cx + (toothOut * cos(rad)).toFloat(), cy + (toothOut * sin(rad)).toFloat()),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            OpalText(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
            )
            OpalText(
                text = subtitle,
                fontSize = 10.sp,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ControlBentoTile(
    title: String,
    subtitle: String,
    active: Boolean,
    accent: Color,
    iconContent: @Composable (tint: Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(tileShape)
            .background(if (active) accent.copy(alpha = 0.15f) else OpalPalette.ChipFill)
            .border(
                width = 0.8.dp,
                color = if (active) accent.copy(alpha = 0.44f) else OpalPalette.BorderHairline,
                shape = tileShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (active) accent else OpalPalette.ChipActiveFill),
            contentAlignment = Alignment.Center,
        ) {
            iconContent(if (active) Color.White else OpalPalette.TextSecondary)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            OpalText(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
                maxLines = 1,
            )
            OpalText(
                text = subtitle,
                fontSize = 10.sp,
                color = if (active) accent else OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ControlCompactSliderRow(
    label: String,
    valueText: String,
    actionLabel: String,
    actionActive: Boolean,
    actionAccent: Color,
    onActionClick: () -> Unit,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalText(text = label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                OpalText(text = valueText, fontSize = 10.5.sp, color = OpalPalette.TextSecondary)
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (actionActive) actionAccent.copy(alpha = 0.16f) else OpalPalette.ChipActiveFill)
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                OpalText(
                    text = actionLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (actionActive) actionAccent else OpalPalette.TextSecondary,
                )
            }
        }
        OpalGlassSlider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
