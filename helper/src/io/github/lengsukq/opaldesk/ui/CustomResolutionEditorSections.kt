package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.external.CustomResolutionPolicy
import io.github.lengsukq.opaldesk.external.CustomResolutionSpec
import kotlin.math.abs

@Composable
internal fun EdidModesSection(
    extDisplay: BridgeService.ExternalDisplayInfo,
    onApplySpec: (CustomResolutionSpec, Boolean) -> Unit,
) {
    if (extDisplay.hasAnisotropyDistortion) {
        val infoShape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(infoShape)
                .background(OpalPalette.AccentCyan.copy(alpha = if (OpalPalette.isDark) 0.14f else 0.09f))
                .border(0.8.dp, OpalPalette.AccentCyan.copy(alpha = 0.45f), infoShape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            OpalText(
                text = stringResource(
                    R.string.res_anisotropy_banner,
                    extDisplay.nativeWidth,
                    extDisplay.nativeHeight,
                ),
                fontSize = 11.sp,
                color = OpalPalette.TextPrimary,
                lineHeight = 15.5.sp,
            )
        }
    }
    if (extDisplay.supportedModes.isNotEmpty()) {
        val nativeBadge = stringResource(R.string.res_mode_badge_native)
        val anisoBadge = stringResource(R.string.res_mode_badge_anisotropy)
        OpalText(
            text = stringResource(R.string.res_edid_modes_title, extDisplay.supportedModes.size),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            extDisplay.supportedModes.chunked(2).forEach { pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    pair.forEach { m ->
                        val isCurrent = !extDisplay.isCustomSizeOverride &&
                            m.width == extDisplay.width &&
                            m.height == extDisplay.height &&
                            abs(m.refreshRateHz - extDisplay.refreshRateHz) <= 2
                        val suffix = when {
                            m.isNativePhysical && extDisplay.hasAnisotropyDistortion -> " · $nativeBadge"
                            m.isAnisotropySynthetic -> " · $anisoBadge"
                            else -> ""
                        }
                        OpalButton(
                            label = "${m.width}×${m.height} · ${m.refreshRateHz}Hz$suffix",
                            compact = true,
                            filled = isCurrent,
                            accent = when {
                                isCurrent -> OpalPalette.AccentBlue
                                m.isNativePhysical && extDisplay.hasAnisotropyDistortion -> OpalPalette.AccentEmerald
                                m.isAnisotropySynthetic -> OpalPalette.TextMuted
                                else -> OpalPalette.TextSecondary
                            },
                            onClick = {
                                onApplySpec(
                                    CustomResolutionSpec(m.width, m.height, m.refreshRateHz),
                                    true,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        OpalText(
            text = stringResource(R.string.res_no_edid_hint),
            fontSize = 11.5.sp,
            color = OpalPalette.AccentAmber,
            lineHeight = 16.sp,
        )
    }
}

@Composable
internal fun SavedResolutionPresetsSection(
    savedPresets: List<CustomResolutionSpec>,
    isConnected: Boolean,
    curActiveW: Int,
    curActiveH: Int,
    onApplySpec: (CustomResolutionSpec) -> Unit,
    onRemovePreset: (String) -> Unit,
) {
    OpalText(
        text = stringResource(R.string.res_saved_presets_title),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = OpalPalette.TextSecondary,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        savedPresets.forEach { preset ->
            val active = isConnected && curActiveW == preset.width && curActiveH == preset.height
            val chipShape = CircleShape
            Row(
                modifier = Modifier
                    .clip(chipShape)
                    .background(if (active) OpalPalette.ChipActiveFill else OpalPalette.ChipFill)
                    .border(
                        0.8.dp,
                        if (active) OpalPalette.AccentBlue.copy(alpha = 0.7f) else OpalPalette.BorderHairline,
                        chipShape,
                    )
                    .clickable { onApplySpec(preset) }
                    .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val title = if (preset.label.isNotBlank()) {
                    "${preset.label} (${preset.shortResolutionText})"
                } else {
                    preset.displayText
                }
                OpalText(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                    maxLines = 1,
                )
                OpalText(
                    text = "×",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = OpalPalette.TextMuted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onRemovePreset(preset.key) }
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun BuiltinResolutionPresetsSection(
    hasExternalDisplay: Boolean,
    isConnected: Boolean,
    curActiveW: Int,
    curActiveH: Int,
    curActiveFps: Int,
    editorExpanded: Boolean,
    onToggleEditor: () -> Unit,
    onApplySpec: (CustomResolutionSpec) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OpalText(
            text = stringResource(if (hasExternalDisplay) R.string.res_builtin_ext_title else R.string.res_builtin_cast_title),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(OpalPalette.ChipActiveFill)
                .clickable(onClick = onToggleEditor)
                .padding(horizontal = 8.dp, vertical = 2.5.dp),
        ) {
            OpalText(
                text = stringResource(if (editorExpanded) R.string.res_hide_editor else R.string.res_custom_wh_btn),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.AccentBlue,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CustomResolutionPolicy.BUILTIN_PRESETS.forEach { preset ->
            val isCurrent = isConnected &&
                curActiveW == preset.width &&
                curActiveH == preset.height &&
                (preset.fps <= 0 || abs(curActiveFps - preset.fps) <= 3)
            val chipShape = RoundedCornerShape(11.dp)
            Column(
                modifier = Modifier
                    .clip(chipShape)
                    .background(if (isCurrent) OpalPalette.ChipActiveFill else OpalPalette.ChipFill)
                    .border(
                        0.8.dp,
                        if (isCurrent) OpalPalette.AccentBlue.copy(alpha = 0.75f) else OpalPalette.BorderHairline,
                        chipShape,
                    )
                    .clickable { onApplySpec(preset) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                OpalText(
                    text = preset.label.ifBlank { preset.shortResolutionText },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                    maxLines = 1,
                )
                OpalText(
                    text = preset.displayText,
                    fontSize = 9.5.sp,
                    color = if (isCurrent) OpalPalette.AccentBlue.copy(alpha = 0.85f) else OpalPalette.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun ManualResolutionEditorCard(
    widthInput: String,
    heightInput: String,
    fpsInput: String,
    labelInput: String,
    inputError: String,
    isConnected: Boolean,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onFpsChange: (String) -> Unit,
    onSwapOrientation: () -> Unit,
    onApplyClick: () -> Unit,
    onSavePresetClick: () -> Unit,
) {
    val editorShape = RoundedCornerShape(14.dp)
    val parsedPreview = remember(widthInput, heightInput, fpsInput, labelInput) {
        CustomResolutionPolicy.parseUserInput(widthInput, heightInput, fpsInput, labelInput)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(editorShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, editorShape)
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = stringResource(R.string.res_manual_title),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val previewSpec = parsedPreview.spec
            if (previewSpec != null) {
                val ratioTag = CustomResolutionPolicy.formatAspectRatioLabel(
                    previewSpec.width,
                    previewSpec.height,
                )
                OpalStatusBadge(
                    label = "$ratioTag · ${previewSpec.displayText}",
                    color = OpalPalette.AccentBlue,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                OpalText(text = stringResource(R.string.res_width_label), fontSize = 10.sp, color = OpalPalette.TextSecondary)
                OpalTextInput(
                    value = widthInput,
                    onValueChange = { raw -> onWidthChange(raw.filter { it.isDigit() }.take(4)) },
                    placeholder = stringResource(R.string.res_width_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clip(CircleShape)
                    .background(OpalPalette.ChipActiveFill)
                    .clickable(onClick = onSwapOrientation)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                OpalText(
                    text = "⇄",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = OpalPalette.AccentBlue,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                OpalText(text = stringResource(R.string.res_height_label), fontSize = 10.sp, color = OpalPalette.TextSecondary)
                OpalTextInput(
                    value = heightInput,
                    onValueChange = { raw -> onHeightChange(raw.filter { it.isDigit() }.take(4)) },
                    placeholder = stringResource(R.string.res_height_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(modifier = Modifier.weight(0.75f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                OpalText(text = stringResource(R.string.res_fps_label), fontSize = 10.sp, color = OpalPalette.TextSecondary)
                OpalTextInput(
                    value = fpsInput,
                    onValueChange = { raw -> onFpsChange(raw.filter { it.isDigit() }.take(3)) },
                    placeholder = stringResource(R.string.common_auto),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpalText(text = stringResource(R.string.res_quick_fps), fontSize = 10.5.sp, color = OpalPalette.TextMuted)
            listOf("" to stringResource(R.string.common_auto), "60" to "60Hz", "90" to "90Hz", "120" to "120Hz").forEach { (valStr, lbl) ->
                val sel = fpsInput.trim() == valStr
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (sel) OpalPalette.ChipActiveFill else LocalOpalColors.current.inputBg)
                        .border(
                            0.7.dp,
                            if (sel) OpalPalette.AccentBlue.copy(alpha = 0.7f) else OpalPalette.BorderHairline,
                            CircleShape,
                        )
                        .clickable { onFpsChange(valStr) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    OpalText(
                        text = lbl,
                        fontSize = 10.5.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                    )
                }
            }
        }

        if (inputError.isNotBlank()) {
            OpalText(text = inputError, fontSize = 11.sp, color = OpalPalette.AccentDanger)
        } else if (parsedPreview.adjustedNote.isNotBlank()) {
            OpalText(text = parsedPreview.adjustedNote, fontSize = 10.5.sp, color = OpalPalette.AccentCyan)
        } else {
            OpalText(
                text = stringResource(R.string.res_range_hint),
                fontSize = 10.5.sp,
                color = OpalPalette.TextMuted,
                lineHeight = 14.5.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpalButton(
                label = stringResource(if (isConnected) R.string.res_apply_now else R.string.res_set_default),
                compact = true,
                filled = true,
                accent = OpalPalette.AccentBlue,
                onClick = onApplyClick,
                modifier = Modifier.weight(1f),
            )
            OpalButton(
                label = stringResource(R.string.res_save_preset),
                compact = true,
                filled = false,
                accent = OpalPalette.AccentEmerald,
                onClick = onSavePresetClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
