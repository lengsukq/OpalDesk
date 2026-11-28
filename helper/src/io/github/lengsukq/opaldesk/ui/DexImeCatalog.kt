package io.github.lengsukq.opaldesk.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol

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
