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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.input.InputBindingAction
import io.github.lengsukq.opaldesk.input.InputBindingRule
import io.github.lengsukq.opaldesk.input.LocalInputBindingExecutor
import io.github.lengsukq.opaldesk.input.formatTriggerBadge
import io.github.lengsukq.opaldesk.input.upsertInputBindingRule
import java.util.Locale

/**
 * Unified custom key & mouse interception rules section embedded inside the Keyboard Shortcuts
 * panel/popover. Built purely with `androidx.compose.foundation` + `OpalPrimitives.kt`.
 */
@Composable
internal fun InputBindingsSection(
    compact: Boolean,
) {
    val rules by BridgeClient.inputBindingsFlow.collectAsState()
    val recentEventSummary by LocalInputBindingExecutor.recentEventSummary.collectAsState()
    var showRecorderModal by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<InputBindingRule?>(null) }

    LaunchedEffect(Unit) {
        BridgeClient.fetchInputBindingsAsync()
    }

    val cardShape = RoundedCornerShape(13.dp)
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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                OpalText(
                    text = "自定义键鼠劫持（USB/无线投屏与本机通用）",
                    fontSize = if (compact) 11.5.sp else 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                )
                OpalText(
                    text = "点击条目即可编辑，支持录制任意鼠标侧键或键盘组合键，跨投屏端与本机统一生效",
                    fontSize = if (compact) 10.sp else 11.sp,
                    color = OpalPalette.TextMuted,
                )
            }
        }

        // Real-time recent capture / hit status bar
        val statusShape = RoundedCornerShape(8.dp)
        val hasRecent = !recentEventSummary.isNullOrBlank()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(statusShape)
                .background(
                    if (hasRecent) {
                        OpalPalette.AccentEmerald.copy(alpha = if (OpalPalette.isDark) 0.16f else 0.10f)
                    } else {
                        OpalPalette.ChipActiveFill.copy(alpha = 0.45f)
                    },
                )
                .border(
                    width = 0.7.dp,
                    color = if (hasRecent) OpalPalette.AccentEmerald.copy(alpha = 0.42f) else OpalPalette.BorderHairline,
                    shape = statusShape,
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (hasRecent) OpalPalette.AccentEmerald else OpalPalette.TextMuted),
            )
            OpalText(
                text = recentEventSummary ?: "实时状态：等待按键命中或录制（按下鼠标侧键将实时反馈）",
                fontSize = if (compact) 10.sp else 10.5.sp,
                fontFamily = if (hasRecent) FontFamily.Monospace else FontFamily.Default,
                color = if (hasRecent) OpalPalette.TextPrimary else OpalPalette.TextMuted,
                maxLines = 1,
            )
        }

        if (rules.isEmpty()) {
            OpalText(
                text = "当前暂无劫持规则，所有键鼠按键均原样透传给应用",
                fontSize = if (compact) 10.5.sp else 11.5.sp,
                color = OpalPalette.TextMuted,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            rules.forEachIndexed { idx, rule ->
                if (idx > 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).background(OpalPalette.DividerColor))
                }
                InputBindingRuleRow(
                    rule = rule,
                    compact = compact,
                    onEdit = {
                        editingRule = rule
                        showRecorderModal = true
                    },
                    onToggle = { enabled ->
                        val updated = rules.map {
                            if (it.id == rule.id && it.code == rule.code && it.metaMask == rule.metaMask) {
                                it.copy(enabled = enabled)
                            } else {
                                it
                            }
                        }
                        BridgeClient.saveInputBindingsAsync(updated)
                    },
                    onDelete = {
                        val updated = rules.filterNot {
                            it.id == rule.id && it.code == rule.code && it.metaMask == rule.metaMask
                        }
                        BridgeClient.saveInputBindingsAsync(updated)
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpalButton(
                label = "+ 添加劫持映射",
                compact = true,
                filled = true,
                accent = OpalPalette.AccentBlue,
                onClick = {
                    editingRule = null
                    showRecorderModal = true
                },
                modifier = Modifier.weight(1.3f),
            )
            OpalButton(
                label = "恢复默认",
                compact = true,
                filled = false,
                accent = OpalPalette.TextSecondary,
                onClick = { BridgeClient.resetInputBindingsAsync() },
                modifier = Modifier.weight(0.9f),
            )
        }
    }

    if (showRecorderModal) {
        InputBindingEditorModal(
            existingRules = rules,
            editingRule = editingRule,
            onDismiss = {
                showRecorderModal = false
                editingRule = null
            },
            onSave = { savedRule ->
                val updated = upsertInputBindingRule(
                    rules = rules,
                    newRule = savedRule,
                    editingRuleId = editingRule?.id,
                )
                BridgeClient.saveInputBindingsAsync(updated)
                showRecorderModal = false
                editingRule = null
            },
        )
    }
}

@Composable
private fun InputBindingRuleRow(
    rule: InputBindingRule,
    compact: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val badge = formatTriggerBadge(rule.triggerType, rule.code, rule.metaMask)
    val actionLabel = InputBindingAction.fromId(rule.actionId)?.labelZh ?: rule.actionId
    val hexCode = "0x${rule.code.toString(16).uppercase(Locale.US)}"
    val capShape = RoundedCornerShape(6.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onEdit)
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(capShape)
                        .background(
                            if (rule.enabled) {
                                OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.12f)
                            } else {
                                OpalPalette.ChipActiveFill
                            },
                        )
                        .border(
                            width = 0.75.dp,
                            color = if (rule.enabled) OpalPalette.AccentBlue.copy(alpha = 0.45f) else OpalPalette.BorderHairline,
                            shape = capShape,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    OpalText(
                        text = badge,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (rule.enabled) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
                OpalText(
                    text = "→ $actionLabel",
                    fontSize = if (compact) 11.5.sp else 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (rule.enabled) OpalPalette.TextPrimary else OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
            OpalText(
                text = "${rule.triggerType} · $hexCode · 点击编辑",
                fontSize = if (compact) 9.5.sp else 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = OpalPalette.TextMuted,
            )
        }
        Spacer(Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val colors = LocalOpalColors.current
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(21.dp)
                    .clip(CircleShape)
                    .background(if (rule.enabled) colors.accentEmerald else colors.switchTrackOff)
                    .clickable { onToggle(!rule.enabled) }
                    .padding(2.dp),
                contentAlignment = if (rule.enabled) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(OpalPalette.AccentDanger.copy(alpha = if (OpalPalette.isDark) 0.18f else 0.11f))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                OpalText(
                    text = "删除",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentDanger,
                )
            }
        }
    }
}
