package io.github.lengsukq.opaldesk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.ime.ImeEditorSnapshot
import io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.DexImeRuntimeState

/** Display-ready entry for an enabled (or previously locked) Android input method. */
data class DexImeOption(
    val id: String,
    val label: String,
    val packageName: String,
    val enabledInSystem: Boolean = true,
    val isBuiltInBridge: Boolean = false,
    val isYuyanPlugin: Boolean = false,
    val isStockYuyan: Boolean = false,
)

/**
 * Pure JVM-testable helpers for merging system-enumerated IMEs with daemon-reported IME IDs,
 * resolving display labels, and formatting user-facing status badges.
 */
object DexImeCatalog {
    const val BUILT_IN_BRIDGE_LABEL = "OpalDesk 投屏直输通道"
    const val YUYAN_PLUGIN_LABEL = "OpalDesk 语燕输入法插件"
    const val YUYAN_PLUGIN_RELEASE_URL = "https://github.com/lengsukq?tab=repositories"

    val YUYAN_PLUGIN_SCHEMAS: List<Pair<String, String>> = listOf(
        "pinyin" to "全拼",
        "double_pinyin_flypy" to "小鹤双拼",
        "double_pinyin" to "自然码",
        "double_pinyin_sogou" to "搜狗双拼",
        "double_pinyin_mspy" to "微软双拼",
        "t9" to "九宫格",
    )

    fun isBuiltInBridgeIme(imeId: String): Boolean =
        imeId.trim() == BridgeProtocol.OPALDESK_BRIDGE_IME_ID

    fun isYuyanPluginIme(imeId: String): Boolean =
        imeId.trim() == BridgeProtocol.OPALDESK_IME_PLUGIN_ID

    fun isYuyanPluginEnabledForDex(
        pluginInstalled: Boolean,
        lockedImeId: String,
        autoBridgeOnCast: Boolean,
        displayMode: Int,
    ): Boolean {
        if (!pluginInstalled) return false
        val clean = lockedImeId.trim()
        if (isYuyanPluginIme(clean)) return true
        if (clean.isNotEmpty()) return false
        return autoBridgeOnCast && displayMode != BridgeProtocol.DexImeDisplayMode.FALLBACK_HOST
    }

    fun isStockYuyanIme(imeId: String): Boolean {
        val pkg = extractPackageName(imeId)
        return pkg.startsWith("com.yuyan.pinyin")
    }

    fun mergeOptions(
        localOptions: List<DexImeOption>,
        daemonEnabledIds: List<String>,
        lockedImeId: String,
        currentSystemImeId: String,
        pluginImeInstalled: Boolean = false,
    ): List<DexImeOption> {
        val byId = LinkedHashMap<String, DexImeOption>()
        if (pluginImeInstalled ||
            isYuyanPluginIme(lockedImeId) ||
            isYuyanPluginIme(currentSystemImeId) ||
            daemonEnabledIds.any(::isYuyanPluginIme) ||
            localOptions.any { isYuyanPluginIme(it.id) }
        ) {
            byId[BridgeProtocol.OPALDESK_IME_PLUGIN_ID] = DexImeOption(
                id = BridgeProtocol.OPALDESK_IME_PLUGIN_ID,
                label = YUYAN_PLUGIN_LABEL,
                packageName = "跨屏光标跟随候选栏 · 小窗零遮挡 · 切回主屏自动还原",
                enabledInSystem = pluginImeInstalled || daemonEnabledIds.any(::isYuyanPluginIme) ||
                    localOptions.any { isYuyanPluginIme(it.id) },
                isBuiltInBridge = false,
                isYuyanPlugin = true,
            )
        }
        // Surface OpalDesk's built-in 5 KB zero-UI cast channel IME so users can inspect or select it
        byId[BridgeProtocol.OPALDESK_BRIDGE_IME_ID] = DexImeOption(
            id = BridgeProtocol.OPALDESK_BRIDGE_IME_ID,
            label = BUILT_IN_BRIDGE_LABEL,
            packageName = "仅投屏零剪贴板直输 · 主包零词库体积膨胀",
            enabledInSystem = true,
            isBuiltInBridge = true,
        )
        localOptions.forEach { opt ->
            val id = opt.id.trim()
            if (id.isNotEmpty() && !isBuiltInBridgeIme(id) && !isYuyanPluginIme(id)) {
                byId[id] = opt.copy(
                    id = id,
                    enabledInSystem = true,
                    isBuiltInBridge = false,
                    isYuyanPlugin = false,
                    isStockYuyan = isStockYuyanIme(id),
                )
            }
        }
        daemonEnabledIds.forEach { rawId ->
            val id = rawId.trim()
            if (id.isNotEmpty() && !byId.containsKey(id)) {
                byId[id] = DexImeOption(
                    id = id,
                    label = fallbackLabelForImeId(id),
                    packageName = extractPackageName(id),
                    enabledInSystem = true,
                    isYuyanPlugin = isYuyanPluginIme(id),
                    isStockYuyan = isStockYuyanIme(id),
                )
            }
        }
        val cleanCur = currentSystemImeId.trim()
        if (cleanCur.isNotEmpty() && !byId.containsKey(cleanCur)) {
            byId[cleanCur] = DexImeOption(
                id = cleanCur,
                label = fallbackLabelForImeId(cleanCur),
                packageName = extractPackageName(cleanCur),
                enabledInSystem = true,
                isYuyanPlugin = isYuyanPluginIme(cleanCur),
                isStockYuyan = isStockYuyanIme(cleanCur),
            )
        }
        val cleanLocked = lockedImeId.trim()
        if (cleanLocked.isNotEmpty() && !byId.containsKey(cleanLocked)) {
            byId[cleanLocked] = DexImeOption(
                id = cleanLocked,
                label = fallbackLabelForImeId(cleanLocked),
                packageName = extractPackageName(cleanLocked),
                enabledInSystem = false,
                isYuyanPlugin = isYuyanPluginIme(cleanLocked),
                isStockYuyan = isStockYuyanIme(cleanLocked),
            )
        }
        return byId.values.toList()
    }

    fun extractPackageName(imeId: String): String {
        val slash = imeId.indexOf('/')
        return if (slash > 0) imeId.substring(0, slash).trim() else imeId.trim()
    }

    fun fallbackLabelForImeId(imeId: String): String {
        val clean = imeId.trim()
        if (clean.isEmpty()) return "跟随系统"
        if (isYuyanPluginIme(clean)) return YUYAN_PLUGIN_LABEL
        if (isBuiltInBridgeIme(clean)) return BUILT_IN_BRIDGE_LABEL
        if (isStockYuyanIme(clean)) return "语燕输入法 (${extractPackageName(clean)})"
        val pkg = extractPackageName(clean)
        val service = clean.substringAfter('/', "").substringAfterLast('.')
        return if (service.isNotEmpty()) "$service ($pkg)" else pkg
    }

    fun resolveLabel(imeId: String, options: List<DexImeOption>): String {
        val clean = imeId.trim()
        if (clean.isEmpty()) return "跟随系统"
        return options.firstOrNull { it.id == clean }?.label ?: fallbackLabelForImeId(clean)
    }

    fun displayModeTitle(mode: Int): String = when (mode) {
        BridgeProtocol.DexImeDisplayMode.ALWAYS_LOCAL -> "始终在 DeX 内"
        BridgeProtocol.DexImeDisplayMode.FALLBACK_HOST -> "本机屏幕"
        else -> "智能自动"
    }

    fun displayModeDescription(mode: Int): String = when (mode) {
        BridgeProtocol.DexImeDisplayMode.ALWAYS_LOCAL ->
            "强制在 DeX 窗口与外接屏内唤起输入法，避免弹到本机屏；小窗建议开启输入法悬浮模式"
        BridgeProtocol.DexImeDisplayMode.FALLBACK_HOST ->
            "始终回退到手机主屏（Display 0）显示软键盘，适合把手机当作独立键盘使用"
        else ->
            "连接蓝牙 / USB / 磁吸键盘时自动在 DeX 内显示候选词，纯触控时回退主屏"
    }

    fun effectivePolicyBadge(policy: Int): String = when (policy) {
        BridgeProtocol.DisplayImePolicy.LOCAL -> "DeX 屏内生效"
        BridgeProtocol.DisplayImePolicy.HIDE -> "已屏蔽本机弹键盘"
        else -> "回退本机屏"
    }

    fun formatCompactEntrySubtitle(
        displayMode: Int,
        lockedImeId: String,
        options: List<DexImeOption>,
    ): String {
        val modeLabel = displayModeTitle(displayMode)
        val lockLabel = if (lockedImeId.isBlank()) "跟随系统" else "锁定 ${resolveLabel(lockedImeId, options)}"
        return "$modeLabel · $lockLabel"
    }

    fun hardKbdModeToIndex(mode: Int): Int = when (mode) {
        BridgeProtocol.HardKbdSoftIme.HIDE -> 1
        BridgeProtocol.HardKbdSoftIme.SHOW -> 2
        else -> 0
    }

    fun indexToHardKbdMode(index: Int): Int = when (index) {
        1 -> BridgeProtocol.HardKbdSoftIme.HIDE
        2 -> BridgeProtocol.HardKbdSoftIme.SHOW
        else -> BridgeProtocol.HardKbdSoftIme.SYSTEM
    }

    /** Queries enabled system IMEs with localized labels directly via Android [InputMethodManager]. */
    fun loadLocalEnabledImes(ctx: Context): List<DexImeOption> {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return emptyList()
        val pm = ctx.packageManager
        val list = try {
            imm.enabledInputMethodList.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        return list.mapNotNull { info ->
            val id = info.id?.trim().orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            val label = try {
                info.loadLabel(pm)?.toString()?.trim().orEmpty()
            } catch (_: Throwable) {
                ""
            }.ifEmpty { fallbackLabelForImeId(id) }
            DexImeOption(
                id = id,
                label = label,
                packageName = info.packageName?.trim().orEmpty().ifEmpty { extractPackageName(id) },
                enabledInSystem = true,
            )
        }
    }
}

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
                OpalText(text = "DeX 输入法与显示位置", fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                    label = "刷新",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentCyan,
                    onClick = ::refreshAll,
                )
                OpalButton(
                    label = "关闭",
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

@Composable
private fun DexImeSettingsBody(
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
        // Optional Companion YuyanIme Plugin Status & Quick Schema Bar
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
                    onCheckedChange = { enablePlugin ->
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
                    onClick = { openYuyanPluginReleasePage(ctx) },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DexImeCatalog.YUYAN_PLUGIN_SCHEMAS.take(4).forEach { (schemaId, label) ->
                        val active = pluginState.schemaId == schemaId
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DexImeCatalog.YUYAN_PLUGIN_SCHEMAS.drop(4).forEach { (schemaId, label) ->
                        val active = pluginState.schemaId == schemaId
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
        }

        // Live Daemon IME Status Summary
        if (connected && imeState.synced) {
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

        // Section 1: Display IME Position Policy
        if (showDisplayModeSection) {
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
                    onSelect = { nextMode ->
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
                OpalText(
                    text = DexImeCatalog.displayModeDescription(displayMode),
                    fontSize = if (compact) 10.sp else 11.sp,
                    color = OpalPalette.TextMuted,
                )
            }
        }

        // Section 2: Dedicated DeX Locked IME Selector (mirrors system keyboard picker)
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

            // Follow system / auto plugin option
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
                onClick = {
                    onApplyConfig(
                        displayMode,
                        "",
                        muteHostOnCast,
                        hardKbdMode,
                        autoBridgeOnCast,
                        "已恢复默认输入法策略",
                    )
                },
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
                            onApplyConfig(
                                displayMode,
                                opt.id,
                                muteHostOnCast,
                                hardKbdMode,
                                autoBridgeOnCast,
                                "已锁定 DeX 输入法：${opt.label}（退出自动恢复）",
                            )
                        },
                    )
                }
            }
        }

        // Section 3: Built-in Cast Channel IME + Remote Cast Mute + Physical Keyboard Soft-IME Override
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
                onCheckedChange = { enabled ->
                    onApplyConfig(
                        displayMode,
                        lockedImeId,
                        muteHostOnCast,
                        hardKbdMode,
                        enabled,
                        if (enabled) "已开启自动接管直输通道" else "已关闭自动接管，仅保持系统当前输入法",
                    )
                },
            )

            Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).background(OpalPalette.DividerColor))

            OpalSwitchRow(
                title = "投屏时屏蔽手机本机弹键盘",
                subtitle = "额外启用 DISPLAY_IME_POLICY_HIDE 兜底屏蔽本机键盘；投平板需自动唤起平板键盘时建议关闭",
                checked = muteHostOnCast,
                onCheckedChange = { enabled ->
                    onApplyConfig(
                        displayMode,
                        lockedImeId,
                        enabled,
                        hardKbdMode,
                        autoBridgeOnCast,
                        if (enabled) "已开启：投屏时屏蔽手机本机弹键盘" else "已关闭投屏本机键盘屏蔽",
                    )
                },
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
                    OpalText(
                        text = "语燕插件",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentEmerald,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.AccentEmerald.copy(alpha = 0.16f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                } else if (isStockYuyan) {
                    OpalText(
                        text = "语燕",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentCyan,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.AccentCyan.copy(alpha = 0.16f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (isBuiltInBridge) {
                    OpalText(
                        text = "投屏通道",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentCyan,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.AccentCyan.copy(alpha = 0.16f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (isSystemCurrent) {
                    OpalText(
                        text = "当前",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentEmerald,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.AccentEmerald.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (disabledWarning) {
                    OpalText(
                        text = "未启用",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.AccentAmber,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.AccentAmber.copy(alpha = 0.16f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
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

private fun applyDexImeConfig(
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

private fun openSystemImeSettingsFallback(ctx: Context) {
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
