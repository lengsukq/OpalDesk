package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun KeyboardShortcutsBody(
    globalEnabled: Boolean,
    disabledIds: Set<String>,
    compact: Boolean,
    showMasterSwitch: Boolean = true,
    onToggleGlobal: (Boolean) -> Unit,
    onToggleItem: (WorkstationShortcutId, Boolean) -> Unit,
) {
    val cardShape = RoundedCornerShape(13.dp)
    val sectionGap = if (compact) 8.dp else 10.dp

    Column(verticalArrangement = Arrangement.spacedBy(sectionGap)) {
        if (showMasterSwitch) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(OpalPalette.ChipFill)
                    .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                OpalSwitchRow(
                    title = "启用桌面全局快捷键",
                    subtitle = if (globalEnabled) {
                        "下方各项可按需单独关闭，关闭后将直接透传给应用"
                    } else {
                        "已全部关闭 · 所有组合键原样透传给当前应用"
                    },
                    checked = globalEnabled,
                    onCheckedChange = onToggleGlobal,
                )
            }
        }

        // Section 1: Window Control Shortcuts
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(OpalPalette.ChipFill)
                .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpalText(
                text = "窗口控制",
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextSecondary,
            )
            WorkstationShortcutCatalog.windowShortcuts.forEachIndexed { idx, item ->
                if (idx > 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).background(OpalPalette.DividerColor))
                }
                val toggleId = item.toggleId
                val itemEnabled = toggleId == null || toggleId.prefKey !in disabledIds
                val effectiveOn = globalEnabled && itemEnabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = globalEnabled && toggleId != null) {
                            if (toggleId != null) onToggleItem(toggleId, !itemEnabled)
                        }
                        .padding(vertical = 3.dp)
                        .alpha(if (globalEnabled) 1f else 0.48f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ShortcutKeyCapRow(keys = item.keys, active = effectiveOn)
                            OpalText(
                                text = item.title,
                                fontSize = if (compact) 12.sp else 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (effectiveOn) OpalPalette.TextPrimary else OpalPalette.TextSecondary,
                                maxLines = 1,
                            )
                        }
                        OpalText(
                            text = item.subtitle,
                            fontSize = if (compact) 10.sp else 11.sp,
                            color = OpalPalette.TextMuted,
                        )
                    }
                    if (toggleId != null) {
                        Spacer(Modifier.width(8.dp))
                        CompactShortcutToggle(checked = effectiveOn)
                    }
                }
            }
        }

        // Section 2: Tiling Layout Shortcuts (grouped under 1 toggle + crisp 2-col keycap grid)
        val tilingEnabled = WorkstationShortcutId.WINDOW_TILING.prefKey !in disabledIds
        val tilingEffectiveOn = globalEnabled && tilingEnabled
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(OpalPalette.ChipFill)
                .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = globalEnabled) {
                        onToggleItem(WorkstationShortcutId.WINDOW_TILING, !tilingEnabled)
                    }
                    .alpha(if (globalEnabled) 1f else 0.48f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    OpalText(
                        text = "一键分屏排版",
                        fontSize = if (compact) 11.5.sp else 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.TextPrimary,
                    )
                    OpalText(
                        text = "自动按列或网格排列当前可见窗口",
                        fontSize = if (compact) 10.sp else 11.sp,
                        color = OpalPalette.TextMuted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                CompactShortcutToggle(checked = tilingEffectiveOn)
            }

            Column(
                modifier = Modifier.alpha(if (tilingEffectiveOn) 1f else 0.48f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                WorkstationShortcutCatalog.tilingShortcuts.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pair.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(OpalPalette.GlassSurface.copy(alpha = if (OpalPalette.isDark) 0.45f else 0.65f))
                                    .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 7.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                ShortcutKeyCapRow(keys = item.keys, active = tilingEffectiveOn)
                                Spacer(Modifier.width(4.dp))
                                OpalText(
                                    text = item.title,
                                    fontSize = if (compact) 10.5.sp else 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = OpalPalette.TextPrimary,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Section 3: IME & App Pass-through Reference
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(OpalPalette.ChipFill)
                .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "输入法与常用直通",
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                OpalText(
                    text = "始终直通应用",
                    fontSize = 10.sp,
                    color = OpalPalette.TextMuted,
                )
            }
            WorkstationShortcutCatalog.passthroughShortcuts.forEachIndexed { idx, item ->
                if (idx > 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).background(OpalPalette.DividerColor))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ShortcutKeyCapRow(keys = item.keys, active = true)
                        OpalText(
                            text = item.title,
                            fontSize = if (compact) 11.5.sp else 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = OpalPalette.TextPrimary,
                            maxLines = 1,
                        )
                    }
                    OpalText(
                        text = item.subtitle,
                        fontSize = if (compact) 10.sp else 11.sp,
                        color = OpalPalette.TextMuted,
                    )
                }
            }
        }

        // Section 4: Unified Custom Key/Mouse Input Interception Rules
        InputBindingsSection(compact = compact)
    }
}

@Composable
private fun ShortcutKeyCapRow(
    keys: List<String>,
    active: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        keys.forEachIndexed { idx, keyLabel ->
            if (idx > 0) {
                OpalText(
                    text = "+",
                    fontSize = 10.sp,
                    color = OpalPalette.TextMuted,
                )
            }
            val capShape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .clip(capShape)
                    .background(
                        if (active) {
                            OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.12f)
                        } else {
                            OpalPalette.ChipActiveFill
                        },
                    )
                    .border(
                        width = 0.75.dp,
                        color = if (active) OpalPalette.AccentBlue.copy(alpha = 0.45f) else OpalPalette.BorderHairline,
                        shape = capShape,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                OpalText(
                    text = keyLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (active) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CompactShortcutToggle(
    checked: Boolean,
) {
    val colors = LocalOpalColors.current
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(21.dp)
            .clip(CircleShape)
            .background(if (checked) colors.accentEmerald else colors.switchTrackOff)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
