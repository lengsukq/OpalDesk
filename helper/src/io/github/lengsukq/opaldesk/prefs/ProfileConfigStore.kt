package io.github.lengsukq.opaldesk.prefs

import android.content.Context
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.widgets.DesktopWidgetStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

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
            DesktopWidgetStore.switchSlot(ctx, _stateFlow.value.activeSlotId)
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
            if (ctx != null) DesktopWidgetStore.switchSlot(ctx, clamped)
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
            val parsed = ProfileConfigCodec.parseDaemonBounds(advancedStateJson)
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
            if (ctx != null) DesktopWidgetStore.saveToSlot(ctx, clamped)
            committed
        }
    }

    fun toJson(state: ProfileStoreState): String = ProfileConfigCodec.toJson(state)

    fun fromJson(raw: String?): ProfileStoreState? = ProfileConfigCodec.fromJson(raw)
}
