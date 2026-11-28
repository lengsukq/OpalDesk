package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol

/**
 * Floating Liquid Glass popover opened from the DeX Control Center for configuring
 * DeX IME display routing (`DISPLAY_IME_POLICY_LOCAL` / `AUTO` / `HIDE`) and locking a
 * dedicated system keyboard while inside DeX.
 */
@Composable
fun DexImeSettingsPopover(
    maxHeight: Dp,
    onOpenSystemImeSettings: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val status by BridgeClient.statusFlow.collectAsState()
    val imeState by BridgeClient.imeStateFlow.collectAsState()
    val bridgeEditor by OpalDeskImeChannel.editorStateFlow.collectAsState()
    val displayMode by AppPrefs.dexImeDisplayModeFlow.collectAsState()
    val lockedImeId by AppPrefs.dexLockedImeIdFlow.collectAsState()
    val muteHostOnCast by AppPrefs.muteHostImeOnCastFlow.collectAsState()
    val hardKbdMode by AppPrefs.hardKbdSoftImeModeFlow.collectAsState()
    val autoBridgeOnCast by AppPrefs.autoBridgeImeOnCastFlow.collectAsState()
    var localImes by remember { mutableStateOf<List<DexImeOption>>(emptyList()) }

    fun refreshAll() {
        localImes = DexImeCatalog.loadLocalEnabledImes(ctx)
        if (status.connected) {
            BridgeClient.refreshDexImeStateAsync()
        }
    }

    LaunchedEffect(Unit) {
        AppPrefs.hydrateFlows(ctx)
        refreshAll()
    }

    val options = remember(localImes, imeState.enabledImeIds, lockedImeId, imeState.currentImeId, imeState.pluginImeInstalled) {
        DexImeCatalog.mergeOptions(
            localOptions = localImes,
            daemonEnabledIds = imeState.enabledImeIds,
            lockedImeId = lockedImeId,
            currentSystemImeId = imeState.currentImeId,
            pluginImeInstalled = imeState.pluginImeInstalled,
        )
    }

    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .widthIn(max = 368.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.SolidPopoverFill,
                rim = OpalPalette.SolidPopoverRim,
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                OpalText(text = stringResource(R.string.ime_popover_title), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                OpalText(
                    text = DexImeCatalog.formatCompactEntrySubtitle(displayMode, lockedImeId, options),
                    fontSize = 10.5.sp,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalButton(
                    label = stringResource(R.string.common_refresh),
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentCyan,
                    onClick = ::refreshAll,
                )
                OpalButton(
                    label = stringResource(R.string.common_close),
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentBlue,
                    onClick = onDismiss,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            DexImeSettingsBody(
                connected = status.connected,
                imeState = imeState,
                bridgeEditor = bridgeEditor,
                options = options,
                displayMode = displayMode,
                lockedImeId = lockedImeId,
                muteHostOnCast = muteHostOnCast,
                hardKbdMode = hardKbdMode,
                autoBridgeOnCast = autoBridgeOnCast,
                compact = true,
                onOpenSystemImeSettings = onOpenSystemImeSettings ?: { openSystemImeSettingsFallback(ctx) },
                onApplyConfig = { nextMode, nextLocked, nextMute, nextHardKbd, nextAutoBridge, toastMsg ->
                    applyDexImeConfig(
                        ctx = ctx,
                        connected = status.connected,
                        displayMode = nextMode,
                        lockedImeId = nextLocked,
                        muteHostOnCast = nextMute,
                        hardKbdMode = nextHardKbd,
                        autoBridgeOnCast = nextAutoBridge,
                        successMessage = toastMsg,
                    )
                },
            )
        }
    }
}

/**
 * Flat inner content for [OpalCollapsibleSectionCard] in Tab 1 ("显示与外设") so the IME card
 * opens directly with display-position switcher, Yuyan plugin controls, and locked keyboard list.
 */
@Composable
fun ConsoleDexImeSectionContent() {
    val ctx = LocalContext.current
    val status by BridgeClient.statusFlow.collectAsState()
    val imeState by BridgeClient.imeStateFlow.collectAsState()
    val bridgeEditor by OpalDeskImeChannel.editorStateFlow.collectAsState()
    val displayMode by AppPrefs.dexImeDisplayModeFlow.collectAsState()
    val lockedImeId by AppPrefs.dexLockedImeIdFlow.collectAsState()
    val muteHostOnCast by AppPrefs.muteHostImeOnCastFlow.collectAsState()
    val hardKbdMode by AppPrefs.hardKbdSoftImeModeFlow.collectAsState()
    val autoBridgeOnCast by AppPrefs.autoBridgeImeOnCastFlow.collectAsState()
    var localImes by remember { mutableStateOf<List<DexImeOption>>(emptyList()) }

    LaunchedEffect(status.connected) {
        AppPrefs.hydrateFlows(ctx)
        localImes = DexImeCatalog.loadLocalEnabledImes(ctx)
        if (status.connected) {
            BridgeClient.refreshDexImeStateAsync()
        }
    }

    val options = remember(localImes, imeState.enabledImeIds, lockedImeId, imeState.currentImeId, imeState.pluginImeInstalled) {
        DexImeCatalog.mergeOptions(
            localOptions = localImes,
            daemonEnabledIds = imeState.enabledImeIds,
            lockedImeId = lockedImeId,
            currentSystemImeId = imeState.currentImeId,
            pluginImeInstalled = imeState.pluginImeInstalled,
        )
    }

    DexImeSettingsBody(
        connected = status.connected,
        imeState = imeState,
        bridgeEditor = bridgeEditor,
        options = options,
        displayMode = displayMode,
        lockedImeId = lockedImeId,
        muteHostOnCast = muteHostOnCast,
        hardKbdMode = hardKbdMode,
        autoBridgeOnCast = autoBridgeOnCast,
        compact = false,
        showDisplayModeSection = true,
        onOpenSystemImeSettings = { openSystemImeSettingsFallback(ctx) },
        onApplyConfig = { nextMode, nextLocked, nextMute, nextHardKbd, nextAutoBridge, toastMsg ->
            applyDexImeConfig(
                ctx = ctx,
                connected = status.connected,
                displayMode = nextMode,
                lockedImeId = nextLocked,
                muteHostOnCast = nextMute,
                hardKbdMode = nextHardKbd,
                autoBridgeOnCast = nextAutoBridge,
                successMessage = toastMsg,
            )
        },
    )
}

/**
 * Console Settings card under "显示与外设 → 键鼠与壁纸" so users can configure DeX IME display
 * routing and locked DeX keyboard before or during a workstation session.
 */
@Composable
fun ConsoleDexImeCard(
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val status by BridgeClient.statusFlow.collectAsState()
    val imeState by BridgeClient.imeStateFlow.collectAsState()
    val bridgeEditor by OpalDeskImeChannel.editorStateFlow.collectAsState()
    val displayMode by AppPrefs.dexImeDisplayModeFlow.collectAsState()
    val lockedImeId by AppPrefs.dexLockedImeIdFlow.collectAsState()
    val muteHostOnCast by AppPrefs.muteHostImeOnCastFlow.collectAsState()
    val hardKbdMode by AppPrefs.hardKbdSoftImeModeFlow.collectAsState()
    val autoBridgeOnCast by AppPrefs.autoBridgeImeOnCastFlow.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var localImes by remember { mutableStateOf<List<DexImeOption>>(emptyList()) }

    fun refreshAll() {
        localImes = DexImeCatalog.loadLocalEnabledImes(ctx)
        if (status.connected) {
            BridgeClient.refreshDexImeStateAsync()
        }
    }

    LaunchedEffect(status.connected) {
        AppPrefs.hydrateFlows(ctx)
        refreshAll()
    }

    val options = remember(localImes, imeState.enabledImeIds, lockedImeId, imeState.currentImeId, imeState.pluginImeInstalled) {
        DexImeCatalog.mergeOptions(
            localOptions = localImes,
            daemonEnabledIds = imeState.enabledImeIds,
            lockedImeId = lockedImeId,
            currentSystemImeId = imeState.currentImeId,
            pluginImeInstalled = imeState.pluginImeInstalled,
        )
    }

    MacGlassSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OpalText(text = "DeX 输入法与键盘锁定", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                OpalText(
                    text = DexImeCatalog.formatCompactEntrySubtitle(displayMode, lockedImeId, options),
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            OpalStatusBadge(
                label = when {
                    imeState.pluginImeActive -> "语燕插件接管"
                    imeState.pluginImeInstalled -> "语燕插件已装"
                    displayMode == BridgeProtocol.DexImeDisplayMode.ALWAYS_LOCAL || lockedImeId.isNotBlank() -> "已自定义"
                    else -> "智能自动"
                },
                color = when {
                    imeState.pluginImeActive || imeState.pluginImeInstalled -> OpalPalette.AccentEmerald
                    displayMode == BridgeProtocol.DexImeDisplayMode.ALWAYS_LOCAL || lockedImeId.isNotBlank() -> OpalPalette.AccentEmerald
                    else -> OpalPalette.AccentBlue
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        OpalText(text = "输入法显示位置（解决输入法弹在本机屏）", fontSize = 12.sp, color = OpalPalette.TextSecondary)
        Spacer(Modifier.height(6.dp))
        OpalSegmentedRow(
            options = listOf("智能自动", "始终在 DeX 内", "本机屏幕"),
            selectedIndex = displayMode.coerceIn(
                BridgeProtocol.DexImeDisplayMode.AUTO,
                BridgeProtocol.DexImeDisplayMode.FALLBACK_HOST,
            ),
            onSelect = { nextMode ->
                applyDexImeConfig(
                    ctx = ctx,
                    connected = status.connected,
                    displayMode = nextMode,
                    lockedImeId = lockedImeId,
                    muteHostOnCast = muteHostOnCast,
                    hardKbdMode = hardKbdMode,
                    autoBridgeOnCast = autoBridgeOnCast,
                    successMessage = "已切换输入法显示位置：${DexImeCatalog.displayModeTitle(nextMode)}",
                )
            },
        )
        Spacer(Modifier.height(5.dp))
        OpalText(
            text = DexImeCatalog.displayModeDescription(displayMode),
            fontSize = 11.sp,
            color = OpalPalette.TextMuted,
        )

        Spacer(Modifier.height(8.dp))
        val previewShape = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(previewShape)
                .background(OpalPalette.ChipFill)
                .border(0.75.dp, OpalPalette.BorderHairline, previewShape)
                .clickable {
                    val next = !expanded
                    expanded = next
                    if (next) refreshAll()
                }
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = if (expanded) "收起输入法锁定与语燕插件选项" else "查看语燕插件状态、锁定输入法与投屏选项",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.AccentBlue,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OpalText(
                text = if (expanded) "收起 ▴" else "展开 ▾",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = OpalPalette.TextSecondary,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            DexImeSettingsBody(
                connected = status.connected,
                imeState = imeState,
                bridgeEditor = bridgeEditor,
                options = options,
                displayMode = displayMode,
                lockedImeId = lockedImeId,
                muteHostOnCast = muteHostOnCast,
                hardKbdMode = hardKbdMode,
                autoBridgeOnCast = autoBridgeOnCast,
                compact = false,
                showDisplayModeSection = false,
                onOpenSystemImeSettings = { openSystemImeSettingsFallback(ctx) },
                onApplyConfig = { nextMode, nextLocked, nextMute, nextHardKbd, nextAutoBridge, toastMsg ->
                    applyDexImeConfig(
                        ctx = ctx,
                        connected = status.connected,
                        displayMode = nextMode,
                        lockedImeId = nextLocked,
                        muteHostOnCast = nextMute,
                        hardKbdMode = nextHardKbd,
                        autoBridgeOnCast = nextAutoBridge,
                        successMessage = toastMsg,
                    )
                },
            )
        }
    }
}
