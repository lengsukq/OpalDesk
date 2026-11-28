package io.github.lengsukq.opaldesk.compositor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.notifications.OpalNotificationStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import kotlin.math.roundToInt

/**
 * High-frequency quick tiles + volume/brightness sliders for the slimmed Control Center.
 */
@Composable
internal fun ControlCenterQuickPanel(
    isZh: Boolean,
    mediaVolume: Int,
    maxVolume: Int,
    lastNonZeroVolume: Int,
    screenBrightness: Float,
    brightnessAuto: Boolean,
    orientationMode: Int,
    isExternalMonitor: Boolean,
    hostScreenOff: Boolean,
    showHostScreenToggle: Boolean,
    showPhoneTrackpadSection: Boolean,
    phoneTrackpadVisible: Boolean,
    isWidgetEditMode: Boolean,
    infoCardShape: RoundedCornerShape,
    onChangeOrientationMode: (Int) -> Unit,
    onToggleHostScreenPower: (Boolean) -> Unit,
    onTogglePhoneTrackpad: () -> Unit,
    onOpenWidgetStore: () -> Unit,
    onToggleWidgetEditMode: () -> Unit,
    onApplyMediaVolume: (Int) -> Unit,
    onSetBrightnessAuto: () -> Unit,
    onChangeBrightness: (Float) -> Unit,
) {
    val ctx = LocalContext.current
    val dndEnabled by OpalNotificationStore.doNotDisturbFlow.collectAsState()
    val forcePhoneSpeaker by AppPrefs.forcePhoneSpeakerFlow.collectAsState()
    val isMuted = mediaVolume == 0

    // Row 1: Host Screen Off / Orientation + Virtual Trackpad / Do Not Disturb
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showHostScreenToggle || hostScreenOff) {
            ControlBentoTile(
                title = stringResource(R.string.taskbar_cc_host_off),
                subtitle = stringResource(
                    if (hostScreenOff) R.string.taskbar_cc_host_off_sub_on else R.string.taskbar_cc_host_off_sub_off,
                ),
                active = hostScreenOff,
                accent = OpalPalette.AccentEmerald,
                iconContent = { tint ->
                    OpalGlyphIcon(
                        glyph = ChromeGlyph.SCREEN_OFF,
                        tint = tint,
                        size = 14.dp,
                    )
                },
                onClick = { onToggleHostScreenPower(hostScreenOff) },
                modifier = Modifier.weight(1f),
            )
        } else {
            val orientLabel = when (orientationMode) {
                1 -> stringResource(R.string.taskbar_cc_orient_land)
                2 -> stringResource(R.string.taskbar_cc_orient_port)
                else -> stringResource(R.string.taskbar_cc_orient_auto)
            }
            ControlBentoTile(
                title = stringResource(R.string.taskbar_cc_orientation),
                subtitle = orientLabel,
                active = orientationMode != 0,
                accent = OpalPalette.AccentCyan,
                iconContent = { tint ->
                    OpalGlyphIcon(
                        glyph = ChromeGlyph.ORIENTATION,
                        tint = tint,
                        size = 14.dp,
                    )
                },
                onClick = { onChangeOrientationMode((orientationMode + 1) % 3) },
                modifier = Modifier.weight(1f),
            )
        }

        if (showPhoneTrackpadSection) {
            ControlBentoTile(
                title = stringResource(R.string.taskbar_cc_trackpad),
                subtitle = stringResource(
                    if (phoneTrackpadVisible) R.string.taskbar_cc_trackpad_on else R.string.taskbar_cc_trackpad_off,
                ),
                active = phoneTrackpadVisible,
                accent = OpalPalette.AccentBlue,
                iconContent = { tint ->
                    OpalGlyphIcon(
                        glyph = ChromeGlyph.EXTERNAL_MONITOR,
                        tint = tint,
                        size = 14.dp,
                    )
                },
                onClick = onTogglePhoneTrackpad,
                modifier = Modifier.weight(1f),
            )
        } else {
            ControlBentoTile(
                title = stringResource(R.string.taskbar_cc_dnd),
                subtitle = stringResource(if (dndEnabled) R.string.common_enabled else R.string.common_disabled),
                active = dndEnabled,
                accent = OpalPalette.AccentAmber,
                iconContent = { tint ->
                    OpalText(
                        text = "☾",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = tint,
                    )
                },
                onClick = { OpalNotificationStore.toggleDoNotDisturb() },
                modifier = Modifier.weight(1f),
            )
        }
    }

    // Row 2: Mute + Force Phone Speaker
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlBentoTile(
            title = stringResource(R.string.taskbar_cc_mute),
            subtitle = stringResource(if (isMuted) R.string.taskbar_cc_muted else R.string.common_disabled),
            active = isMuted,
            accent = OpalPalette.AccentAmber,
            iconContent = { tint ->
                OpalText(
                    text = if (isMuted) "✕" else "♪",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            },
            onClick = {
                if (isMuted) onApplyMediaVolume(lastNonZeroVolume) else onApplyMediaVolume(0)
            },
            modifier = Modifier.weight(1f),
        )
        ControlBentoTile(
            title = if (isZh) "本机播放" else "Phone Speaker",
            subtitle = stringResource(if (forcePhoneSpeaker) R.string.common_enabled else R.string.common_disabled),
            active = forcePhoneSpeaker,
            accent = OpalPalette.AccentCyan,
            iconContent = { tint ->
                OpalGlyphIcon(
                    glyph = ChromeGlyph.MIC,
                    tint = tint,
                    size = 14.dp,
                )
            },
            onClick = {
                val next = !forcePhoneSpeaker
                BridgeClient.setForcePhoneSpeakerAsync(ctx, next) {
                    val msg = ctx.getString(
                        if (next) R.string.settings_force_phone_speaker_toast_on else R.string.settings_force_phone_speaker_toast_off,
                    )
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.weight(1f),
        )
    }

    // Row 3: Widget Store + Edit Desktop
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlBentoTile(
            title = if (isZh) "组件商店" else "Widget Store",
            subtitle = if (isZh) "添加桌面组件" else "Add widgets",
            active = false,
            accent = OpalPalette.AccentBlue,
            iconContent = { tint ->
                OpalGlyphIcon(
                    glyph = ChromeGlyph.APPS_GRID,
                    tint = tint,
                    size = 14.dp,
                )
            },
            onClick = onOpenWidgetStore,
            modifier = Modifier.weight(1f),
        )
        ControlBentoTile(
            title = if (isZh) "编辑桌面" else "Edit Desktop",
            subtitle = if (isWidgetEditMode) {
                if (isZh) "编辑中" else "Editing"
            } else {
                if (isZh) "排列组件" else "Arrange"
            },
            active = isWidgetEditMode,
            accent = OpalPalette.AccentEmerald,
            iconContent = { tint ->
                OpalGlyphIcon(
                    glyph = ChromeGlyph.HOME_DESKTOP,
                    tint = tint,
                    size = 14.dp,
                )
            },
            onClick = onToggleWidgetEditMode,
            modifier = Modifier.weight(1f),
        )
    }

    // Sliders Card: Volume & Brightness
    val volPct = ((mediaVolume * 100f) / maxVolume.toFloat()).roundToInt().coerceIn(0, 100)
    val brightPct = (screenBrightness * 100f).roundToInt().coerceIn(5, 100)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(infoCardShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, infoCardShape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ControlCompactSliderRow(
            label = stringResource(R.string.taskbar_cc_volume),
            valueText = if (isMuted) stringResource(R.string.taskbar_cc_muted) else "$volPct%",
            actionLabel = stringResource(if (isMuted) R.string.taskbar_cc_unmute else R.string.taskbar_cc_mute),
            actionActive = isMuted,
            actionAccent = OpalPalette.AccentAmber,
            onActionClick = {
                if (isMuted) onApplyMediaVolume(lastNonZeroVolume) else onApplyMediaVolume(0)
            },
            value = mediaVolume.toFloat(),
            valueRange = 0f..maxVolume.toFloat(),
            onValueChange = { v -> onApplyMediaVolume(v.roundToInt()) },
        )

        if (!isExternalMonitor) {
            Box(modifier = Modifier.fillMaxWidth().height(0.75.dp).background(OpalPalette.DividerColor))
            ControlCompactSliderRow(
                label = stringResource(R.string.taskbar_cc_brightness),
                valueText = if (brightnessAuto) stringResource(R.string.common_auto) else "$brightPct%",
                actionLabel = stringResource(if (brightnessAuto) R.string.common_auto_checked else R.string.common_auto),
                actionActive = brightnessAuto,
                actionAccent = OpalPalette.AccentBlue,
                onActionClick = onSetBrightnessAuto,
                value = screenBrightness,
                valueRange = 0.05f..1.0f,
                onValueChange = onChangeBrightness,
            )
        }
    }
}
