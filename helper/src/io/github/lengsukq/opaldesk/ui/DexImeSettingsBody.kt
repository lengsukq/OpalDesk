package io.github.lengsukq.opaldesk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.ime.ImeEditorSnapshot
import io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel
import io.github.lengsukq.opaldesk.ime.PluginCompositionSnapshot
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.DexImeRuntimeState

@Composable
internal fun DexImeSettingsBody(
    connected: Boolean,
    imeState: DexImeRuntimeState,
    bridgeEditor: ImeEditorSnapshot,
    options: List<DexImeOption>,
    displayMode: Int,
    lockedImeId: String,
    muteHostOnCast: Boolean,
    hardKbdMode: Int,
    autoBridgeOnCast: Boolean,
    compact: Boolean,
    showDisplayModeSection: Boolean = true,
    onOpenSystemImeSettings: () -> Unit,
    onApplyConfig: (
        displayMode: Int,
        lockedImeId: String,
        muteHostOnCast: Boolean,
        hardKbdMode: Int,
        autoBridgeOnCast: Boolean,
        toastMsg: String,
    ) -> Unit,
) {
    val ctx = LocalContext.current
    val pluginState by OpalDeskImeChannel.pluginStateFlow.collectAsState()
    val yuyanEnabledForDex = DexImeCatalog.isYuyanPluginEnabledForDex(
        pluginInstalled = imeState.pluginImeInstalled,
        lockedImeId = lockedImeId,
        autoBridgeOnCast = autoBridgeOnCast,
        displayMode = displayMode,
    )
    val cardShape = RoundedCornerShape(13.dp)
    val sectionGap = if (compact) 8.dp else 10.dp

    Column(verticalArrangement = Arrangement.spacedBy(sectionGap)) {
        YuyanPluginStatusCard(
            imeState = imeState,
            pluginState = pluginState,
            yuyanEnabledForDex = yuyanEnabledForDex,
            compact = compact,
            cardShape = cardShape,
            onTogglePlugin = { enablePlugin ->
                if (enablePlugin) {
                    onApplyConfig(
                        displayMode,
                        BridgeProtocol.OPALDESK_IME_PLUGIN_ID,
                        muteHostOnCast,
                        hardKbdMode,
                        true,
                        "已在 DeX 桌面（含本机桌面）启用内置语燕输入法",
                    )
                } else {
                    val nextLocked = if (DexImeCatalog.isYuyanPluginIme(lockedImeId)) "" else lockedImeId
                    onApplyConfig(
                        displayMode,
                        nextLocked,
                        muteHostOnCast,
                        hardKbdMode,
                        false,
                        "已关闭内置语燕输入法，恢复使用原系统输入法",
                    )
                }
            },
            onOpenReleases = { openYuyanPluginReleasePage(ctx) },
            onOpenSystemImeSettings = onOpenSystemImeSettings,
        )

        if (connected && imeState.synced) {
            LiveDaemonImeStatusCard(
                imeState = imeState,
                bridgeEditor = bridgeEditor,
                pluginState = pluginState,
                options = options,
                lockedImeId = lockedImeId,
                compact = compact,
                cardShape = cardShape,
            )
        }

        if (showDisplayModeSection) {
            DisplayImePositionCard(
                displayMode = displayMode,
                compact = compact,
                cardShape = cardShape,
                onSelectDisplayMode = { nextMode ->
                    onApplyConfig(
                        nextMode,
                        lockedImeId,
                        muteHostOnCast,
                        hardKbdMode,
                        autoBridgeOnCast,
                        "已切换输入法显示位置：${DexImeCatalog.displayModeTitle(nextMode)}",
                    )
                },
            )
        }

        LockedImeSelectorCard(
            imeState = imeState,
            options = options,
            lockedImeId = lockedImeId,
            autoBridgeOnCast = autoBridgeOnCast,
            compact = compact,
            cardShape = cardShape,
            onOpenSystemImeSettings = onOpenSystemImeSettings,
            onSelectLockedIme = { nextLockedId, toastMsg ->
                onApplyConfig(
                    displayMode,
                    nextLockedId,
                    muteHostOnCast,
                    hardKbdMode,
                    autoBridgeOnCast,
                    toastMsg,
                )
            },
        )

        CastAndHardwareKeyboardCard(
            autoBridgeOnCast = autoBridgeOnCast,
            muteHostOnCast = muteHostOnCast,
            hardKbdMode = hardKbdMode,
            compact = compact,
            cardShape = cardShape,
            onToggleAutoBridge = { enabled ->
                onApplyConfig(
                    displayMode,
                    lockedImeId,
                    muteHostOnCast,
                    hardKbdMode,
                    enabled,
                    if (enabled) "已开启自动接管直输通道" else "已关闭自动接管，仅保持系统当前输入法",
                )
            },
            onToggleMuteHostOnCast = { enabled ->
                onApplyConfig(
                    displayMode,
                    lockedImeId,
                    enabled,
                    hardKbdMode,
                    autoBridgeOnCast,
                    if (enabled) "已开启：投屏时屏蔽手机本机弹键盘" else "已关闭投屏本机键盘屏蔽",
                )
            },
            onSelectHardKbdMode = { nextHard, label ->
                onApplyConfig(
                    displayMode,
                    lockedImeId,
                    muteHostOnCast,
                    nextHard,
                    autoBridgeOnCast,
                    "已设置实体键盘软键盘行为：$label",
                )
            },
        )
    }
}

@Composable
private fun YuyanPluginStatusCard(
    imeState: DexImeRuntimeState,
    pluginState: PluginCompositionSnapshot,
    yuyanEnabledForDex: Boolean,
    compact: Boolean,
    cardShape: RoundedCornerShape,
    onTogglePlugin: (Boolean) -> Unit,
    onOpenReleases: () -> Unit,
    onOpenSystemImeSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(
                if (imeState.pluginImeInstalled) {
                    OpalPalette.AccentEmerald.copy(alpha = if (OpalPalette.isDark) 0.14f else 0.08f)
                } else {
                    OpalPalette.ChipFill
                },
            )
            .border(
                0.75.dp,
                if (imeState.pluginImeInstalled) OpalPalette.AccentEmerald.copy(alpha = 0.30f) else OpalPalette.BorderHairline,
                cardShape,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = "OpalDesk 语燕输入法插件（可选增强）",
                fontSize = if (compact) 11.5.sp else 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            OpalStatusBadge(
                label = when {
                    imeState.pluginImeActive -> "已接管 DeX"
                    imeState.pluginImeInstalled && yuyanEnabledForDex -> "已启用"
                    imeState.pluginImeInstalled -> "已安装 · 未启用"
                    else -> "未安装 · 零膨胀"
                },
                color = if (imeState.pluginImeInstalled && yuyanEnabledForDex) OpalPalette.AccentEmerald else OpalPalette.TextSecondary,
            )
        }
        OpalText(
            text = if (imeState.pluginImeInstalled) {
                "已通过同签名 SHA-256 校验：可在下方自由选择是否在本机 DeX 桌面与外接屏启用；关闭时保持系统原输入法。"
            } else {
                "主包默认不内置词库，保持原输入法习惯；可按需前往 GitHub Releases 下载安装同签名语燕插件 APK，支持本机 DeX 与外接屏自由开关。"
            },
            fontSize = if (compact) 9.8.sp else 10.8.sp,
            color = OpalPalette.TextSecondary,
        )
        if (imeState.pluginImeInstalled) {
            OpalSwitchRow(
                title = "在 DeX 桌面使用内置语燕输入法（含本机桌面）",
                subtitle = "开启后本机 DeX 与外接屏自动启用跨屏候选栏与软键盘；关闭则使用原系统输入法",
                checked = yuyanEnabledForDex,
                onCheckedChange = onTogglePlugin,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpalButton(
                label = if (imeState.pluginImeInstalled) "Releases 下载更新" else "前往 Releases 下载安装",
                compact = true,
                filled = !imeState.pluginImeInstalled,
                accent = OpalPalette.AccentBlue,
                onClick = onOpenReleases,
            )
            OpalButton(
                label = "系统输入法设置",
                compact = true,
                filled = false,
                accent = OpalPalette.AccentCyan,
                onClick = onOpenSystemImeSettings,
            )
        }
        OpalText(
            text = "官方下载地址：${DexImeCatalog.YUYAN_PLUGIN_RELEASE_URL.removePrefix("https://")}",
            fontSize = if (compact) 9.2.sp else 10.sp,
            fontFamily = FontFamily.Monospace,
            color = OpalPalette.TextMuted,
            maxLines = 1,
        )
        if (pluginState.pluginConnected) {
            YuyanSchemaChipRow(
                schemas = DexImeCatalog.YUYAN_PLUGIN_SCHEMAS.take(4),
                activeSchemaId = pluginState.schemaId,
            )
            YuyanSchemaChipRow(
                schemas = DexImeCatalog.YUYAN_PLUGIN_SCHEMAS.drop(4),
                activeSchemaId = pluginState.schemaId,
            )
        }
    }
}

@Composable
private fun YuyanSchemaChipRow(
    schemas: List<Pair<String, String>>,
    activeSchemaId: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        schemas.forEach { (schemaId, label) ->
            val active = activeSchemaId == schemaId
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) OpalPalette.AccentBlue else OpalPalette.ChipFill)
                    .clickable { OpalDeskImeChannel.selectSchema(schemaId) }
                    .padding(vertical = 5.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                OpalText(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) Color.White else OpalPalette.TextPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LiveDaemonImeStatusCard(
    imeState: DexImeRuntimeState,
    bridgeEditor: ImeEditorSnapshot,
    pluginState: PluginCompositionSnapshot,
    options: List<DexImeOption>,
    lockedImeId: String,
    compact: Boolean,
    cardShape: RoundedCornerShape,
) {
    val activeLabel = DexImeCatalog.resolveLabel(imeState.currentImeId, options)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.14f else 0.08f))
            .border(0.75.dp, OpalPalette.AccentBlue.copy(alpha = 0.28f), cardShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = "当前输入法：$activeLabel",
                fontSize = if (compact) 11.5.sp else 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            OpalStatusBadge(
                label = DexImeCatalog.effectivePolicyBadge(imeState.effectiveWindowImePolicy),
                color = when (imeState.effectiveWindowImePolicy) {
                    BridgeProtocol.DisplayImePolicy.LOCAL -> OpalPalette.AccentEmerald
                    BridgeProtocol.DisplayImePolicy.HIDE -> OpalPalette.AccentAmber
                    else -> OpalPalette.TextSecondary
                },
            )
        }
        val lockSummary = when {
            imeState.yieldedToDisplay0 ->
                "当前在手机主屏输入，已临时让回原手机输入法（点回 DeX 窗口自动接管）"
            imeState.pluginImeActive && bridgeEditor.inputActive && bridgeEditor.acceptingText ->
                "语燕插件跨屏组词中 (${pluginState.schemaLabel} · ${bridgeEditor.packageName.ifEmpty { "窗口" }})"
            imeState.pluginImeActive ->
                "语燕输入法插件已接管 · 跨屏悬浮候选栏就绪（退出自动恢复原输入法）"
            imeState.bridgeImeActive && bridgeEditor.inputActive && bridgeEditor.acceptingText ->
                "内置投屏直输通道运行中 · 已绑定输入框 (${bridgeEditor.packageName.ifEmpty { "窗口" }})"
            imeState.bridgeImeActive ->
                "内置投屏直输通道已生效 · 本机零弹窗，直输不污染剪贴板（退出自动恢复）"
            imeState.imeLockApplied ->
                "DeX 输入法锁定已生效 · 退出 DeX 或停止服务自动恢复原输入法"
            lockedImeId.isNotBlank() && !imeState.dexSessionActive ->
                "已预约锁定 · 进入 DeX 或投屏后自动切换"
            lockedImeId.isNotBlank() && imeState.dexSessionActive ->
                "锁定未生效（请确认所选输入法已在系统启用）"
            else -> "当前保持用户系统默认输入法（底层跨屏焦点防抢占已生效）"
        }
        val kbdNote = if (imeState.physicalKeyboardDetected) " · 已检测到实体/磁吸键盘" else ""
        OpalText(
            text = "$lockSummary$kbdNote",
            fontSize = if (compact) 10.sp else 11.sp,
            color = OpalPalette.TextSecondary,
        )
    }
}

@Composable
private fun DisplayImePositionCard(
    displayMode: Int,
    compact: Boolean,
    cardShape: RoundedCornerShape,
    onSelectDisplayMode: (Int) -> Unit,
) {
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
            text = "输入法显示位置",
            fontSize = if (compact) 11.5.sp else 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextPrimary,
        )
        OpalSegmentedRow(
            options = listOf("智能自动", "始终在 DeX 内", "本机屏幕"),
            selectedIndex = displayMode.coerceIn(
                BridgeProtocol.DexImeDisplayMode.AUTO,
                BridgeProtocol.DexImeDisplayMode.FALLBACK_HOST,
            ),
            onSelect = onSelectDisplayMode,
        )
        OpalText(
            text = DexImeCatalog.displayModeDescription(displayMode),
            fontSize = if (compact) 10.sp else 11.sp,
            color = OpalPalette.TextMuted,
        )
    }
}

@Composable
private fun LockedImeSelectorCard(
    imeState: DexImeRuntimeState,
    options: List<DexImeOption>,
    lockedImeId: String,
    autoBridgeOnCast: Boolean,
    compact: Boolean,
    cardShape: RoundedCornerShape,
    onOpenSystemImeSettings: () -> Unit,
    onSelectLockedIme: (lockedImeId: String, toastMsg: String) -> Unit,
) {
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                OpalText(
                    text = "DeX 锁定输入法",
                    fontSize = if (compact) 11.5.sp else 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                )
                OpalText(
                    text = "未安装插件时跟随原系统输入法；安装语燕插件后默认自动接管 DeX",
                    fontSize = if (compact) 9.8.sp else 10.8.sp,
                    color = OpalPalette.TextMuted,
                )
            }
            Spacer(Modifier.width(6.dp))
            OpalButton(
                label = "系统键盘",
                compact = true,
                filled = false,
                accent = OpalPalette.AccentBlue,
                onClick = onOpenSystemImeSettings,
            )
        }

        ImeRadioOptionRow(
            title = if (imeState.pluginImeInstalled && autoBridgeOnCast) "智能默认（优先语燕插件）" else "跟随系统默认",
            subtitle = if (imeState.pluginImeInstalled && autoBridgeOnCast) "DeX 内自动启用语燕插件，切出自动还原原输入法" else "保持原系统输入法，不自动切换",
            selected = lockedImeId.isBlank(),
            isSystemCurrent = false,
            isBuiltInBridge = false,
            isYuyanPlugin = false,
            isStockYuyan = false,
            disabledWarning = false,
            compact = compact,
            onClick = { onSelectLockedIme("", "已恢复默认输入法策略") },
        )

        if (options.isEmpty()) {
            OpalText(
                text = "暂未读取到已启用的系统输入法，可点击右上角「系统键盘」检查或刷新",
                fontSize = 10.5.sp,
                color = OpalPalette.TextMuted,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            options.forEach { opt ->
                Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).background(OpalPalette.DividerColor))
                ImeRadioOptionRow(
                    title = opt.label,
                    subtitle = opt.packageName,
                    selected = lockedImeId == opt.id,
                    isSystemCurrent = imeState.synced && imeState.currentImeId == opt.id,
                    isBuiltInBridge = opt.isBuiltInBridge,
                    isYuyanPlugin = opt.isYuyanPlugin,
                    isStockYuyan = opt.isStockYuyan,
                    disabledWarning = !opt.enabledInSystem,
                    compact = compact,
                    onClick = {
                        onSelectLockedIme(opt.id, "已锁定 DeX 输入法：${opt.label}（退出自动恢复）")
                    },
                )
            }
        }
    }
}

@Composable
private fun CastAndHardwareKeyboardCard(
    autoBridgeOnCast: Boolean,
    muteHostOnCast: Boolean,
    hardKbdMode: Int,
    compact: Boolean,
    cardShape: RoundedCornerShape,
    onToggleAutoBridge: (Boolean) -> Unit,
    onToggleMuteHostOnCast: (Boolean) -> Unit,
    onSelectHardKbdMode: (mode: Int, label: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        OpalSwitchRow(
            title = "投屏与插件自动接管直输通道",
            subtitle = "安装语燕插件后在 DeX 内自动启用；未装插件时仅在无线投屏电脑/平板时启用轻量直输通道",
            checked = autoBridgeOnCast,
            onCheckedChange = onToggleAutoBridge,
        )

        Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).background(OpalPalette.DividerColor))

        OpalSwitchRow(
            title = "投屏时屏蔽手机本机弹键盘",
            subtitle = "额外启用 DISPLAY_IME_POLICY_HIDE 兜底屏蔽本机键盘；投平板需自动唤起平板键盘时建议关闭",
            checked = muteHostOnCast,
            onCheckedChange = onToggleMuteHostOnCast,
        )

        Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).background(OpalPalette.DividerColor))

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            OpalText(
                text = "接实体键盘时软键盘行为（仅 DeX 内生效）",
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextSecondary,
            )
            OpalSegmentedRow(
                options = listOf("跟随系统", "隐藏软键盘", "保留软键盘"),
                selectedIndex = DexImeCatalog.hardKbdModeToIndex(hardKbdMode),
                onSelect = { idx ->
                    val nextHard = DexImeCatalog.indexToHardKbdMode(idx)
                    val label = when (idx) {
                        1 -> "隐藏软键盘"
                        2 -> "保留软键盘"
                        else -> "跟随系统"
                    }
                    onSelectHardKbdMode(nextHard, label)
                },
            )
        }
    }
}

@Composable
private fun ImeRadioOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    isSystemCurrent: Boolean,
    isBuiltInBridge: Boolean,
    isYuyanPlugin: Boolean = false,
    isStockYuyan: Boolean = false,
    disabledWarning: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val rowShape = RoundedCornerShape(9.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(
                if (selected) {
                    OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.18f else 0.10f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                OpalText(
                    text = title,
                    fontSize = if (compact) 12.sp else 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                    maxLines = 1,
                )
                if (isYuyanPlugin) {
                    ImeTagChip("语燕插件", OpalPalette.AccentEmerald)
                } else if (isStockYuyan) {
                    ImeTagChip("语燕", OpalPalette.AccentCyan)
                }
                if (isBuiltInBridge) {
                    ImeTagChip("投屏通道", OpalPalette.AccentCyan)
                }
                if (isSystemCurrent) {
                    ImeTagChip("当前", OpalPalette.AccentEmerald, bgAlpha = 0.14f)
                }
                if (disabledWarning) {
                    ImeTagChip("未启用", OpalPalette.AccentAmber)
                }
            }
            OpalText(
                text = subtitle,
                fontSize = if (compact) 9.8.sp else 10.8.sp,
                fontFamily = if (isBuiltInBridge || isYuyanPlugin) FontFamily.Default else FontFamily.Monospace,
                color = OpalPalette.TextMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) OpalPalette.AccentBlue else Color.Transparent)
                .border(
                    width = if (selected) 0.dp else 1.3.dp,
                    color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                OpalText(
                    text = "✓",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ImeTagChip(label: String, color: Color, bgAlpha: Float = 0.16f) {
    OpalText(
        text = label,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = bgAlpha))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

internal fun applyDexImeConfig(
    ctx: Context,
    connected: Boolean,
    displayMode: Int,
    lockedImeId: String,
    muteHostOnCast: Boolean,
    hardKbdMode: Int,
    autoBridgeOnCast: Boolean,
    successMessage: String,
) {
    BridgeClient.setDexImeConfigAsync(
        ctx = ctx,
        displayMode = displayMode,
        lockedImeId = lockedImeId,
        muteHostOnCast = muteHostOnCast,
        showWithHardKbd = hardKbdMode,
        autoBridgeImeOnCast = autoBridgeOnCast,
    ) { ok, _ ->
        val msg = when {
            !connected -> "已保存配置，激活守护服务后自动生效"
            ok -> successMessage
            else -> "配置已保存，但同步守护服务失败，请稍后重试"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}

internal fun openSystemImeSettingsFallback(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Throwable) {
        Toast.makeText(ctx, "无法打开系统输入法设置", Toast.LENGTH_SHORT).show()
    }
}

private fun openYuyanPluginReleasePage(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(DexImeCatalog.YUYAN_PLUGIN_RELEASE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Throwable) {
        Toast.makeText(ctx, "无法打开浏览器，请访问 ${DexImeCatalog.YUYAN_PLUGIN_RELEASE_URL}", Toast.LENGTH_LONG).show()
    }
}
