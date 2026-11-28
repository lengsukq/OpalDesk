package io.github.lengsukq.opaldesk.prefs

import android.content.Context
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Normalized last-closed window bounds for an application (adapts cleanly across display sizes).
 */
data class SavedAppWindowBounds(
    val leftRatio: Float,
    val topRatio: Float,
    val rightRatio: Float,
    val bottomRatio: Float,
    val presentation: String = BridgeProtocol.Presentation.FLOATING,
) {
    fun toDaemonLine(pkg: String): String =
        "$pkg=${String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f,%s", leftRatio, topRatio, rightRatio, bottomRatio, presentation)}"
}

/**
 * Single window entry in a persisted workspace layout snapshot.
 */
data class SavedLayoutWindow(
    val pkg: String,
    val activityClass: String = "",
    val profileCode: Int = BridgeProtocol.Profile.AUTO,
    val leftRatio: Float = 0.1f,
    val topRatio: Float = 0.1f,
    val rightRatio: Float = 0.7f,
    val bottomRatio: Float = 0.8f,
    val presentation: String = BridgeProtocol.Presentation.FLOATING,
    val minimized: Boolean = false,
    val zOrder: Int = 0,
    val userId: Int = 0,
)

/**
 * One of the 5 persistent Android-side workspace configuration slots (targeting different
 * local / external monitor / casting / receiving device setups).
 */
/**
 * Stable identity of the active display target (local DeX, physical USB-C/HDMI monitor,
 * or remote cast receiver) used to auto-select and remember a matching profile slot.
 */
data class DisplayTargetIdentity(
    val key: String,
    val label: String,
    val defaultSlotId: Int,
)

data class WorkspaceProfileConfig(
    val id: Int,
    val name: String,
    val globalCustomDpi: Int = 0, // 0 = auto, 120..560 = fixed global custom DPI
    val windowDpiScalePct: Int = 100, // 70..160 when globalCustomDpi == 0
    val targetFps: Int = 0, // 0 = auto, 60/90/100/120
    // Retained for v1 JSON compatibility; live UI scale is persisted globally in AppPrefs.
    val externalUiScale: Float = 1.25f,
    val maxVdCount: Int = 6,
    val inputBackendMode: Int = BridgeProtocol.InputBackend.AUTO,
    val keepLayoutOnExit: Boolean = true,
    val autoRestoreLayout: Boolean = false,
    val boundTargets: List<String> = emptyList(),
    val appCustomDpis: Map<String, Int> = emptyMap(),
    val appTopCropDps: Map<String, Int> = emptyMap(),
    val appWindowBounds: Map<String, SavedAppWindowBounds> = emptyMap(),
    val savedLayout: List<SavedLayoutWindow> = emptyList(),
) {
    fun formatAppDpisForDaemon(): String =
        appCustomDpis.entries
            .filter { it.key.isNotBlank() && it.value in ProfileConfigStore.MIN_DPI..ProfileConfigStore.MAX_DPI }
            .joinToString("\n") { "${it.key}=${it.value}" }

    fun formatAppBoundsForDaemon(): String =
        appWindowBounds.entries
            .filter { it.key.isNotBlank() }
            .joinToString("\n") { (pkg, b) -> b.toDaemonLine(pkg) }
}

data class ProfileStoreState(
    val activeSlotId: Int = 0,
    val slots: List<WorkspaceProfileConfig> = ProfileConfigStore.defaultSlots(),
    val workingConfig: WorkspaceProfileConfig = ProfileConfigStore.defaultSlots().first(),
    val hasUnsavedChanges: Boolean = false,
    val autoMatchTargetSlot: Boolean = true,
) {
    val activeSavedSlot: WorkspaceProfileConfig
        get() = slots.firstOrNull { it.id == activeSlotId } ?: slots.first()
}

/**
 * Android-side authoritative persistent configuration store supporting 5 switchable slots.
 *
 * Runtime edits mutate [ProfileStoreState.workingConfig] in memory so tweaks take effect
 * immediately without overwriting any saved slot on every tiny adjustment. When the user
 * actively exits (or explicitly chooses a slot to save), [saveWorkingToSlot] writes the
 * current working settings, per-app DPIs, remembered per-app window bounds, and optional
 * desktop layout into the user-selected slot (`0..4`) atomically under app-private storage
 * (`filesDir/opaldesk_profiles_v1.json`) so everything survives device reboots.
 */
object ProfileConfigStore {
    const val SLOT_COUNT = 5
    const val MIN_DPI = 120
    const val MAX_DPI = 560
    const val DEFAULT_TOP_CROP_DP = 28
    const val MIN_TOP_CROP_DP = 0
    const val MAX_TOP_CROP_DP = 48
    private const val STORE_FILE_NAME = "opaldesk_profiles_v1.json"

    private val lock = Any()

    @Volatile
    private var hydrated = false

    private val _stateFlow = MutableStateFlow(ProfileStoreState())
    val stateFlow: StateFlow<ProfileStoreState> = _stateFlow.asStateFlow()

    val workingConfig: WorkspaceProfileConfig
        get() = _stateFlow.value.workingConfig

    fun defaultSlots(): List<WorkspaceProfileConfig> = listOf(
        WorkspaceProfileConfig(id = 0, name = "本机桌面"),
        WorkspaceProfileConfig(id = 1, name = "外接显示器"),
        WorkspaceProfileConfig(id = 2, name = "投屏电脑 / Mac"),
        WorkspaceProfileConfig(id = 3, name = "投屏平板 / 手机"),
        WorkspaceProfileConfig(id = 4, name = "自定义方案 5"),
    )

    fun clampDpiOrAuto(dpi: Int): Int =
        if (dpi <= 0) 0 else dpi.coerceIn(MIN_DPI, MAX_DPI)

    /**
     * Builds a deterministic target key from the active output channel (Local DeX,
     * physical USB-C/HDMI external monitor, or remote cast receiver).
     */
    fun resolveDisplayTarget(
        isExternalMonitor: Boolean,
        remoteConnected: Boolean,
        remotePeerName: String = "",
        externalDisplayName: String = "",
        externalWidth: Int = 0,
        externalHeight: Int = 0,
    ): DisplayTargetIdentity {
        if (remoteConnected) {
            val cleanPeer = remotePeerName.trim()
            val lower = cleanPeer.lowercase(Locale.ROOT)
            val isDesktopPeer = lower.contains("mac") ||
                lower.contains("pc") ||
                lower.contains("win") ||
                lower.contains("linux") ||
                lower.contains("desktop") ||
                lower.contains("chrome") ||
                lower.contains("edge") ||
                lower.contains("safari") ||
                lower.contains("firefox") ||
                lower.contains("web") ||
                lower.contains("browser")
            val key = "cast:${lower.ifEmpty { "remote" }}"
            val label = cleanPeer.ifEmpty { if (isDesktopPeer) "投屏电脑" else "投屏设备" }
            return DisplayTargetIdentity(
                key = key,
                label = label,
                defaultSlotId = if (isDesktopPeer) 2 else 3,
            )
        }
        if (isExternalMonitor || externalDisplayName.isNotBlank()) {
            val cleanName = externalDisplayName.trim().ifEmpty { "external" }
            val sizeTag = if (externalWidth > 0 && externalHeight > 0) "@${externalWidth}x${externalHeight}" else ""
            val key = "ext:${cleanName.lowercase(Locale.ROOT)}$sizeTag"
            return DisplayTargetIdentity(
                key = key,
                label = if (sizeTag.isNotEmpty()) "$cleanName ($sizeTag)" else cleanName,
                defaultSlotId = 1,
            )
        }
        return DisplayTargetIdentity(key = "local", label = "本机桌面", defaultSlotId = 0)
    }

    /**
     * Resolves which slot (`0..4`) matches [target]: prefers any slot explicitly bound to
     * [DisplayTargetIdentity.key], falling back to [DisplayTargetIdentity.defaultSlotId].
     */
    fun resolveSlotIdForTarget(target: DisplayTargetIdentity, state: ProfileStoreState = _stateFlow.value): Int {
        val bound = state.slots.firstOrNull { slot -> slot.boundTargets.contains(target.key) }
        return (bound?.id ?: target.defaultSlotId).coerceIn(0, SLOT_COUNT - 1)
    }

    fun setAutoMatchTargetSlot(ctx: Context?, enabled: Boolean) {
        hydrate(ctx)
        synchronized(lock) {
            val next = _stateFlow.value.copy(autoMatchTargetSlot = enabled)
            persistSlotsLocked(ctx, next)
        }
    }

    /**
     * Automatically loads the best matching slot for [target] if [ProfileStoreState.autoMatchTargetSlot]
     * is enabled. Returns the active config and whether the active slot changed.
     */
    fun autoSelectSlotForTarget(ctx: Context?, target: DisplayTargetIdentity): Pair<WorkspaceProfileConfig, Boolean> {
        hydrate(ctx)
        val cur = _stateFlow.value
        if (!cur.autoMatchTargetSlot) return cur.workingConfig to false
        val targetSlotId = resolveSlotIdForTarget(target, cur)
        if (targetSlotId == cur.activeSlotId && !cur.hasUnsavedChanges) {
            return cur.workingConfig to false
        }
        val loaded = loadSlot(ctx, targetSlotId)
        return loaded to true
    }

    fun hydrate(ctx: Context?) {
        if (hydrated || ctx == null) return
        synchronized(lock) {
            if (hydrated) return
            val file = File(ctx.applicationContext.filesDir, STORE_FILE_NAME)
            val loaded = if (file.isFile) {
                try {
                    fromJson(file.readText(StandardCharsets.UTF_8))
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            if (loaded != null) {
                _stateFlow.value = loaded
            }
            hydrated = true
        }
    }

    private fun persistSlotsLocked(ctx: Context?, next: ProfileStoreState) {
        _stateFlow.value = next
        if (ctx == null) return
        try {
            val dir = ctx.applicationContext.filesDir
            val target = File(dir, STORE_FILE_NAME)
            val tmp = File(dir, "$STORE_FILE_NAME.tmp")
            tmp.writeText(toJson(next), StandardCharsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(toJson(next), StandardCharsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Loads a saved slot (`0..4`) into [workingConfig] and remembers it as the active slot.
     * Note: [AppPrefs.externalUiScaleFlow] is intentionally NOT overwritten here so desktop UI scale
     * persists across exits, slot switches, and display target changes.
     */
    fun loadSlot(ctx: Context?, slotId: Int): WorkspaceProfileConfig {
        hydrate(ctx)
        val clamped = slotId.coerceIn(0, SLOT_COUNT - 1)
        return synchronized(lock) {
            val cur = _stateFlow.value
            val target = cur.slots.firstOrNull { it.id == clamped } ?: cur.slots.first()
            val next = cur.copy(
                activeSlotId = clamped,
                workingConfig = target,
                hasUnsavedChanges = false,
            )
            persistSlotsLocked(ctx, next)
            target
        }
    }

    /**
     * Mutates only the live in-memory [workingConfig] without overwriting any persisted slot.
     */
    fun updateWorkingConfig(
        transform: (WorkspaceProfileConfig) -> WorkspaceProfileConfig,
    ): WorkspaceProfileConfig {
        return synchronized(lock) {
            val cur = _stateFlow.value
            val updated = transform(cur.workingConfig).copy(id = cur.activeSlotId)
            val next = cur.copy(workingConfig = updated, hasUnsavedChanges = true)
            _stateFlow.value = next
            updated
        }
    }

    fun renameSlot(ctx: Context?, slotId: Int, newName: String): WorkspaceProfileConfig {
        hydrate(ctx)
        val clamped = slotId.coerceIn(0, SLOT_COUNT - 1)
        val clean = newName.trim().take(24).ifEmpty { defaultSlots()[clamped].name }
        return synchronized(lock) {
            val cur = _stateFlow.value
            val nextSlots = cur.slots.map { p ->
                if (p.id == clamped) p.copy(name = clean) else p
            }
            val nextWorking = if (cur.activeSlotId == clamped) cur.workingConfig.copy(name = clean) else cur.workingConfig
            val next = cur.copy(slots = nextSlots, workingConfig = nextWorking)
            persistSlotsLocked(ctx, next)
            next.slots[clamped]
        }
    }

    fun setWorkingGlobalCustomDpi(dpi: Int): WorkspaceProfileConfig =
        updateWorkingConfig { it.copy(globalCustomDpi = clampDpiOrAuto(dpi)) }

    fun setWorkingWindowDpiScalePct(pct: Int): WorkspaceProfileConfig =
        updateWorkingConfig { it.copy(windowDpiScalePct = pct.coerceIn(70, 160)) }

    fun setWorkingTargetFps(fps: Int): WorkspaceProfileConfig =
        updateWorkingConfig {
            val valid = if (fps == 0 || fps in 30..165) fps else 0
            it.copy(targetFps = valid)
        }

    /**
     * Immediately persists display FPS / DPI scale preferences into both [workingConfig] and the
     * active saved slot so Helper restarts or Binder reconnects never overwrite the user's chosen
     * refresh rate back to 0 (Auto).
     */
    fun persistWorkingDisplayPrefs(
        ctx: Context?,
        targetFps: Int,
        windowDpiScalePct: Int,
    ): WorkspaceProfileConfig {
        hydrate(ctx)
        val validFps = if (targetFps == 0 || targetFps in 30..165) targetFps else 0
        val validDpiPct = windowDpiScalePct.coerceIn(70, 160)
        return synchronized(lock) {
            val cur = _stateFlow.value
            val updatedWorking = cur.workingConfig.copy(
                id = cur.activeSlotId,
                targetFps = validFps,
                windowDpiScalePct = validDpiPct,
            )
            val nextSlots = cur.slots.map { slot ->
                if (slot.id == cur.activeSlotId) {
                    slot.copy(targetFps = validFps, windowDpiScalePct = validDpiPct)
                } else {
                    slot
                }
            }
            val next = cur.copy(slots = nextSlots, workingConfig = updatedWorking)
            persistSlotsLocked(ctx, next)
            updatedWorking
        }
    }

    fun setWorkingMaxVdCount(maxVd: Int): WorkspaceProfileConfig =
        updateWorkingConfig { it.copy(maxVdCount = maxVd.coerceIn(2, 12)) }

    fun setWorkingInputBackendMode(mode: Int): WorkspaceProfileConfig =
        updateWorkingConfig {
            it.copy(inputBackendMode = mode.coerceIn(BridgeProtocol.InputBackend.AUTO, BridgeProtocol.InputBackend.UHID))
        }

    fun setWorkingKeepLayoutOnExit(enabled: Boolean): WorkspaceProfileConfig =
        updateWorkingConfig { it.copy(keepLayoutOnExit = enabled) }

    fun setWorkingAutoRestoreLayout(enabled: Boolean): WorkspaceProfileConfig =
        updateWorkingConfig { it.copy(autoRestoreLayout = enabled) }

    fun setWorkingAppCustomDpi(pkg: String, dpi: Int): WorkspaceProfileConfig {
        val cleanPkg = pkg.trim()
        if (cleanPkg.isEmpty()) return workingConfig
        val cleanDpi = clampDpiOrAuto(dpi)
        return updateWorkingConfig { prof ->
            val nextMap = LinkedHashMap(prof.appCustomDpis)
            if (cleanDpi <= 0) nextMap.remove(cleanPkg) else nextMap[cleanPkg] = cleanDpi
            prof.copy(appCustomDpis = nextMap)
        }
    }

    fun setWorkingAppTopCropDp(pkg: String, dp: Int): WorkspaceProfileConfig {
        val cleanPkg = pkg.trim()
        if (cleanPkg.isEmpty()) return workingConfig
        val cleanDp = dp.coerceIn(MIN_TOP_CROP_DP, MAX_TOP_CROP_DP)
        return updateWorkingConfig { prof ->
            val nextMap = LinkedHashMap(prof.appTopCropDps)
            if (cleanDp <= 0) nextMap.remove(cleanPkg) else nextMap[cleanPkg] = cleanDp
            prof.copy(appTopCropDps = nextMap)
        }
    }

    fun toggleWorkingAppTopCrop(pkg: String): Boolean {
        val cur = workingConfig.appTopCropDps[pkg.trim()] ?: 0
        val enable = cur <= 0
        setWorkingAppTopCropDp(pkg, if (enable) DEFAULT_TOP_CROP_DP else 0)
        return enable
    }

    fun mergeDaemonBoundsIntoWorking(advancedStateJson: String?): WorkspaceProfileConfig {
        if (advancedStateJson.isNullOrBlank()) return workingConfig
        return try {
            val root = MiniJson.parseObject(advancedStateJson) ?: return workingConfig
            val boundsObj = root.obj("appWindowBounds") ?: return workingConfig
            val parsed = LinkedHashMap<String, SavedAppWindowBounds>()
            for ((pkg, rawVal) in boundsObj) {
                val o = rawVal as? Map<*, *> ?: continue
                parsed[pkg] = SavedAppWindowBounds(
                    leftRatio = o.int("l", 1000) / 10000f,
                    topRatio = o.int("t", 1000) / 10000f,
                    rightRatio = o.int("r", 7000) / 10000f,
                    bottomRatio = o.int("b", 8000) / 10000f,
                    presentation = o.str("p", BridgeProtocol.Presentation.FLOATING),
                )
            }
            if (parsed.isEmpty()) return workingConfig
            synchronized(lock) {
                val cur = _stateFlow.value
                val merged = LinkedHashMap(cur.workingConfig.appWindowBounds)
                merged.putAll(parsed)
                val updated = cur.workingConfig.copy(appWindowBounds = merged)
                _stateFlow.value = cur.copy(workingConfig = updated)
                updated
            }
        } catch (_: Throwable) {
            workingConfig
        }
    }

    /**
     * Explicitly commits the live [workingConfig] (including per-app custom DPIs, remembered
     * per-app window bounds, and optionally the current desktop window layout) into the chosen
     * slot (`0..4`) and persists atomically to disk.
     */
    fun saveWorkingToSlot(
        ctx: Context?,
        targetSlotId: Int,
        scene: CompositorScene? = null,
        saveCurrentLayout: Boolean = true,
        customSlotName: String? = null,
        bindTargetKey: String? = null,
    ): WorkspaceProfileConfig {
        hydrate(ctx)
        val clamped = targetSlotId.coerceIn(0, SLOT_COUNT - 1)
        val cleanBindKey = bindTargetKey?.trim().orEmpty()
        return synchronized(lock) {
            val cur = _stateFlow.value
            val existingTarget = cur.slots.firstOrNull { it.id == clamped } ?: defaultSlots()[clamped]
            val resolvedName = customSlotName?.trim()?.take(24)?.takeIf { it.isNotEmpty() } ?: existingTarget.name

            val mergedBounds = LinkedHashMap(cur.workingConfig.appWindowBounds)
            val nextLayout: List<SavedLayoutWindow> = if (saveCurrentLayout && scene != null && scene.windows.isNotEmpty()) {
                val cw = scene.canvasWidth.coerceAtLeast(360).toFloat()
                val ch = scene.canvasHeight.coerceAtLeast(360).toFloat()
                scene.windows.sortedBy { it.zOrder }.map { w ->
                    val entry = SavedLayoutWindow(
                        pkg = w.pkg,
                        activityClass = w.activityClass,
                        profileCode = w.profileCode,
                        leftRatio = (w.frame.left / cw).coerceIn(-0.5f, 0.95f),
                        topRatio = (w.frame.top / ch).coerceIn(0f, 0.95f),
                        rightRatio = (w.frame.right / cw).coerceIn(0.1f, 1.5f),
                        bottomRatio = (w.frame.bottom / ch).coerceIn(0.1f, 1.2f),
                        presentation = if (w.isFullscreen) BridgeProtocol.Presentation.FLOATING else w.presentation,
                        minimized = w.isMinimized,
                        zOrder = w.zOrder,
                        userId = w.userId,
                    )
                    mergedBounds[w.pkg] = SavedAppWindowBounds(
                        leftRatio = entry.leftRatio,
                        topRatio = entry.topRatio,
                        rightRatio = entry.rightRatio,
                        bottomRatio = entry.bottomRatio,
                        presentation = entry.presentation,
                    )
                    entry
                }
            } else if (!saveCurrentLayout) {
                existingTarget.savedLayout
            } else {
                cur.workingConfig.savedLayout
            }

            val nextTargetBindings = LinkedHashSet(existingTarget.boundTargets)
            if (cleanBindKey.isNotEmpty()) {
                nextTargetBindings.add(cleanBindKey)
            }

            val committed = cur.workingConfig.copy(
                id = clamped,
                name = resolvedName,
                boundTargets = nextTargetBindings.toList(),
                appWindowBounds = mergedBounds,
                savedLayout = nextLayout,
            )
            val nextSlots = cur.slots.map { p ->
                when {
                    p.id == clamped -> committed
                    cleanBindKey.isNotEmpty() && p.boundTargets.contains(cleanBindKey) ->
                        p.copy(boundTargets = p.boundTargets.filterNot { it == cleanBindKey })
                    else -> p
                }
            }
            val next = cur.copy(
                activeSlotId = clamped,
                slots = nextSlots,
                workingConfig = committed,
                hasUnsavedChanges = false,
            )
            persistSlotsLocked(ctx, next)
            committed
        }
    }

    fun toJson(state: ProfileStoreState): String = buildString(1024) {
        append("{\"version\":1")
        append(",\"activeProfileId\":").append(state.activeSlotId.coerceIn(0, SLOT_COUNT - 1))
        append(",\"autoMatchTargetSlot\":").append(state.autoMatchTargetSlot)
        append(",\"profiles\":[")
        state.slots.forEachIndexed { idx, p ->
            if (idx > 0) append(',')
            append("{\"id\":").append(p.id)
            append(",\"name\":").append(MiniJson.quote(p.name))
            append(",\"globalCustomDpi\":").append(p.globalCustomDpi)
            append(",\"windowDpiScalePct\":").append(p.windowDpiScalePct)
            append(",\"targetFps\":").append(p.targetFps)
            append(",\"externalUiScale\":").append(String.format(Locale.US, "%.4f", p.externalUiScale.toDouble()))
            append(",\"maxVdCount\":").append(p.maxVdCount)
            append(",\"inputBackendMode\":").append(p.inputBackendMode)
            append(",\"keepLayoutOnExit\":").append(p.keepLayoutOnExit)
            append(",\"autoRestoreLayout\":").append(p.autoRestoreLayout)

            append(",\"boundTargets\":[")
            var firstT = true
            p.boundTargets.forEach { t ->
                if (t.isNotBlank()) {
                    if (!firstT) append(',')
                    firstT = false
                    append(MiniJson.quote(t))
                }
            }
            append(']')

            append(",\"appCustomDpis\":{")
            var firstD = true
            p.appCustomDpis.forEach { (k, v) ->
                if (!firstD) append(',')
                firstD = false
                append(MiniJson.quote(k)).append(':').append(v)
            }
            append('}')

            append(",\"appTopCropDps\":{")
            var firstC = true
            p.appTopCropDps.forEach { (k, v) ->
                if (!firstC) append(',')
                firstC = false
                append(MiniJson.quote(k)).append(':').append(v)
            }
            append('}')

            append(",\"appWindowBounds\":{")
            var firstB = true
            p.appWindowBounds.forEach { (k, b) ->
                if (!firstB) append(',')
                firstB = false
                append(MiniJson.quote(k)).append(":{")
                append("\"l\":").append((b.leftRatio * 10000f).roundToInt())
                append(",\"t\":").append((b.topRatio * 10000f).roundToInt())
                append(",\"r\":").append((b.rightRatio * 10000f).roundToInt())
                append(",\"b\":").append((b.bottomRatio * 10000f).roundToInt())
                append(",\"p\":").append(MiniJson.quote(b.presentation))
                append('}')
            }
            append('}')

            append(",\"savedLayout\":[")
            p.savedLayout.forEachIndexed { lIdx, w ->
                if (lIdx > 0) append(',')
                append("{\"pkg\":").append(MiniJson.quote(w.pkg))
                append(",\"cls\":").append(MiniJson.quote(w.activityClass))
                append(",\"profileCode\":").append(w.profileCode)
                append(",\"l\":").append((w.leftRatio * 10000f).roundToInt())
                append(",\"t\":").append((w.topRatio * 10000f).roundToInt())
                append(",\"r\":").append((w.rightRatio * 10000f).roundToInt())
                append(",\"b\":").append((w.bottomRatio * 10000f).roundToInt())
                append(",\"p\":").append(MiniJson.quote(w.presentation))
                append(",\"min\":").append(w.minimized)
                append(",\"z\":").append(w.zOrder)
                if (w.userId > 0) {
                    append(",\"userId\":").append(w.userId)
                }
                append('}')
            }
            append("]}")
        }
        append("]}")
    }

    fun fromJson(raw: String?): ProfileStoreState? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = MiniJson.parseObject(raw) ?: return null
            val activeId = root.int("activeProfileId", 0).coerceIn(0, SLOT_COUNT - 1)
            val autoMatch = root.bool("autoMatchTargetSlot", true)
            val defaults = defaultSlots()
            val byId = LinkedHashMap<Int, WorkspaceProfileConfig>()
            defaults.forEach { byId[it.id] = it }

            val arr = root.arr("profiles")
            if (arr != null) {
                for (i in arr.indices) {
                    val po = arr[i] as? Map<*, *> ?: continue
                    val id = po.int("id", i).coerceIn(0, SLOT_COUNT - 1)
                    val base = defaults[id]

                    val dpisMap = LinkedHashMap<String, Int>()
                    val dpisObj = po.obj("appCustomDpis")
                    if (dpisObj != null) {
                        for ((k, rawV) in dpisObj) {
                            val v = clampDpiOrAuto((rawV as? Number)?.toInt() ?: 0)
                            if (k.isNotBlank() && v > 0) dpisMap[k] = v
                        }
                    }

                    val topCropMap = LinkedHashMap<String, Int>()
                    val topCropObj = po.obj("appTopCropDps")
                    if (topCropObj != null) {
                        for ((k, rawV) in topCropObj) {
                            val v = ((rawV as? Number)?.toInt() ?: 0).coerceIn(MIN_TOP_CROP_DP, MAX_TOP_CROP_DP)
                            if (k.isNotBlank() && v > 0) topCropMap[k] = v
                        }
                    }

                    val boundsMap = LinkedHashMap<String, SavedAppWindowBounds>()
                    val boundsObj = po.obj("appWindowBounds")
                    if (boundsObj != null) {
                        for ((k, rawBo) in boundsObj) {
                            val bo = rawBo as? Map<*, *> ?: continue
                            if (k.isBlank()) continue
                            boundsMap[k] = SavedAppWindowBounds(
                                leftRatio = bo.int("l", 1000) / 10000f,
                                topRatio = bo.int("t", 1000) / 10000f,
                                rightRatio = bo.int("r", 7000) / 10000f,
                                bottomRatio = bo.int("b", 8000) / 10000f,
                                presentation = bo.str("p", BridgeProtocol.Presentation.FLOATING),
                            )
                        }
                    }

                    val layoutList = ArrayList<SavedLayoutWindow>()
                    val layoutArr = po.arr("savedLayout")
                    if (layoutArr != null) {
                        for (j in layoutArr.indices) {
                            val wo = layoutArr[j] as? Map<*, *> ?: continue
                            val pkg = wo.str("pkg", "").trim()
                            if (pkg.isEmpty()) continue
                            layoutList.add(
                                SavedLayoutWindow(
                                    pkg = pkg,
                                    activityClass = wo.str("cls", ""),
                                    profileCode = wo.int("profileCode", BridgeProtocol.Profile.AUTO),
                                    leftRatio = wo.int("l", 1000) / 10000f,
                                    topRatio = wo.int("t", 1000) / 10000f,
                                    rightRatio = wo.int("r", 7000) / 10000f,
                                    bottomRatio = wo.int("b", 8000) / 10000f,
                                    presentation = wo.str("p", BridgeProtocol.Presentation.FLOATING),
                                    minimized = wo.bool("min", false),
                                    zOrder = wo.int("z", j),
                                    userId = wo.int("userId", 0).coerceAtLeast(0),
                                ),
                            )
                        }
                    }

                    val boundTargetsList = ArrayList<String>()
                    val targetsArr = po.arr("boundTargets")
                    if (targetsArr != null) {
                        for (item in targetsArr) {
                            val t = (item as? String)?.trim().orEmpty()
                            if (t.isNotEmpty() && !boundTargetsList.contains(t)) boundTargetsList.add(t)
                        }
                    }

                    byId[id] = WorkspaceProfileConfig(
                        id = id,
                        name = po.str("name", base.name).trim().ifEmpty { base.name },
                        globalCustomDpi = clampDpiOrAuto(po.int("globalCustomDpi", 0)),
                        windowDpiScalePct = po.int("windowDpiScalePct", 100).coerceIn(70, 160),
                        targetFps = po.int("targetFps", 0).let { if (it == 0 || it in 30..165) it else 0 },
                        externalUiScale = po.double("externalUiScale", 1.25).toFloat().coerceIn(0.85f, 2.0f),
                        maxVdCount = po.int("maxVdCount", 6).coerceIn(2, 12),
                        inputBackendMode = po.int("inputBackendMode", BridgeProtocol.InputBackend.AUTO)
                            .coerceIn(BridgeProtocol.InputBackend.AUTO, BridgeProtocol.InputBackend.UHID),
                        keepLayoutOnExit = po.bool("keepLayoutOnExit", true),
                        autoRestoreLayout = po.bool("autoRestoreLayout", false),
                        boundTargets = boundTargetsList,
                        appCustomDpis = dpisMap,
                        appTopCropDps = topCropMap,
                        appWindowBounds = boundsMap,
                        savedLayout = layoutList,
                    )
                }
            }
            val list = (0 until SLOT_COUNT).map { byId[it] ?: defaults[it] }
            ProfileStoreState(
                activeSlotId = activeId,
                slots = list,
                workingConfig = list[activeId],
                hasUnsavedChanges = false,
                autoMatchTargetSlot = autoMatch,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun Map<*, *>.str(key: String, fallback: String): String = (this[key] as? String) ?: fallback
    private fun Map<*, *>.int(key: String, fallback: Int): Int = (this[key] as? Number)?.toInt() ?: fallback
    private fun Map<*, *>.double(key: String, fallback: Double): Double = (this[key] as? Number)?.toDouble() ?: fallback
    private fun Map<*, *>.bool(key: String, fallback: Boolean): Boolean = (this[key] as? Boolean) ?: fallback
    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
    private fun Map<*, *>.arr(key: String): List<Any?>? = this[key] as? List<Any?>

    private object MiniJson {
        fun quote(s: String): String = buildString(s.length + 2) {
            append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) {
                        append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
            append('"')
        }

        @Suppress("UNCHECKED_CAST")
        fun parseObject(src: String): Map<String, Any?>? = Parser(src).parseValue() as? Map<String, Any?>

        private class Parser(private val s: String) {
            private var i = 0

            private fun skipWs() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun parseValue(): Any? {
                skipWs()
                if (i >= s.length) return null
                return when (s[i]) {
                    '{' -> parseObj()
                    '[' -> parseArr()
                    '"' -> parseStr()
                    't' -> if (s.startsWith("true", i)) { i += 4; true } else null
                    'f' -> if (s.startsWith("false", i)) { i += 5; false } else null
                    'n' -> if (s.startsWith("null", i)) { i += 4; null } else null
                    else -> parseNum()
                }
            }

            private fun parseObj(): Map<String, Any?> {
                val map = LinkedHashMap<String, Any?>()
                i++ // '{'
                skipWs()
                if (i < s.length && s[i] == '}') { i++; return map }
                while (i < s.length) {
                    skipWs()
                    if (i >= s.length || s[i] != '"') break
                    val key = parseStr()
                    skipWs()
                    if (i < s.length && s[i] == ':') i++
                    val v = parseValue()
                    map[key] = v
                    skipWs()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == '}') { i++; break }
                    break
                }
                return map
            }

            private fun parseArr(): List<Any?> {
                val list = ArrayList<Any?>()
                i++ // '['
                skipWs()
                if (i < s.length && s[i] == ']') { i++; return list }
                while (i < s.length) {
                    list.add(parseValue())
                    skipWs()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == ']') { i++; break }
                    break
                }
                return list
            }

            private fun parseStr(): String {
                i++ // opening '"'
                val sb = StringBuilder()
                while (i < s.length) {
                    val ch = s[i++]
                    if (ch == '"') break
                    if (ch == '\\' && i < s.length) {
                        when (val esc = s[i++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> if (i + 4 <= s.length) {
                                val code = s.substring(i, i + 4).toIntOrNull(16)
                                if (code != null) { sb.append(code.toChar()); i += 4 }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                return sb.toString()
            }

            private fun parseNum(): Number? {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
                val tok = s.substring(start, i)
                return if (tok.contains('.') || tok.contains('e', true)) tok.toDoubleOrNull() else tok.toLongOrNull()
            }
        }
    }
}
