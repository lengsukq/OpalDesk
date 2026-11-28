package io.github.lengsukq.opaldesk.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DockSizePreset {
    COMPACT,
    STANDARD,
    LARGE,
}

enum class DockGlassStyle {
    CLEAR,
    BALANCED,
    FROSTED,
}

data class DockCustomizationConfig(
    val sizePreset: DockSizePreset = DockSizePreset.STANDARD,
    val floatingStyle: Boolean = true,
    val autoHide: Boolean = false,
    val showSearchButton: Boolean = true,
    val showSettingsButton: Boolean = true,
    val showRunningApps: Boolean = true,
    val showRunningDots: Boolean = true,
    val glassStyle: DockGlassStyle = DockGlassStyle.BALANCED,
    val orbMode: Boolean = false,
    val orbSnapToEdge: Boolean = true,
    val orbHoverExpand: Boolean = true,
    val orbIdleDim: Boolean = true,
    val orbDockedRight: Boolean = true,
    val orbVerticalFraction: Float = 0.72f,
    val orbOpacity: Float = 0.86f,
) {
    fun toDockScale(): Float = when (sizePreset) {
        DockSizePreset.COMPACT -> 0.84f
        DockSizePreset.STANDARD -> 1.00f
        DockSizePreset.LARGE -> 1.20f
    }

    fun toDockOpacity(): Float = when (glassStyle) {
        DockGlassStyle.CLEAR -> 0.35f
        DockGlassStyle.BALANCED -> 0.65f
        DockGlassStyle.FROSTED -> 0.92f
    }

    fun clampedOrbOpacity(): Float =
        if (orbOpacity.isFinite()) orbOpacity.coerceIn(0.25f, 1.0f) else 0.86f
}

/**
 * SharedPreferences-backed reactive store for DeX bottom Dock customization.
 * Emits live updates through [observe] so the taskbar and preview bar update immediately.
 */
object DockCustomizationStore {
    private const val PREFS_NAME = "opaldesk_dock_customization_prefs"
    private const val KEY_SIZE_PRESET = "dock_size_preset"
    private const val KEY_FLOATING_STYLE = "dock_floating_style"
    private const val KEY_AUTO_HIDE = "dock_auto_hide"
    private const val KEY_SHOW_SEARCH = "dock_show_search_button"
    private const val KEY_SHOW_SETTINGS = "dock_show_settings_button"
    private const val KEY_SHOW_RUNNING_APPS = "dock_show_running_apps"
    private const val KEY_SHOW_RUNNING_DOTS = "dock_show_running_dots"
    private const val KEY_GLASS_STYLE = "dock_glass_style"
    private const val KEY_ORB_MODE = "dock_orb_mode"
    private const val KEY_ORB_SNAP_TO_EDGE = "dock_orb_snap_to_edge"
    private const val KEY_ORB_HOVER_EXPAND = "dock_orb_hover_expand"
    private const val KEY_ORB_IDLE_DIM = "dock_orb_idle_dim"
    private const val KEY_ORB_DOCKED_RIGHT = "dock_orb_docked_right"
    private const val KEY_ORB_VERTICAL_FRACTION = "dock_orb_vertical_fraction"
    private const val KEY_ORB_OPACITY = "dock_orb_opacity"

    private val lock = Any()

    @Volatile
    private var hydrated = false

    private val _stateFlow = MutableStateFlow(DockCustomizationConfig())

    private fun prefs(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun ensureHydrated(context: Context) {
        if (hydrated) return
        synchronized(lock) {
            if (hydrated) return
            _stateFlow.value = readFromPrefs(prefs(context))
            hydrated = true
        }
    }

    fun observe(context: Context): StateFlow<DockCustomizationConfig> {
        ensureHydrated(context)
        return _stateFlow.asStateFlow()
    }

    fun update(
        context: Context,
        transform: (DockCustomizationConfig) -> DockCustomizationConfig,
    ) {
        ensureHydrated(context)
        synchronized(lock) {
            val next = transform(_stateFlow.value)
            if (next == _stateFlow.value) return
            _stateFlow.value = next
            writeToPrefs(prefs(context), next)
        }
    }

    fun updateOrbPosition(
        context: Context,
        dockedRight: Boolean,
        fractionY: Float,
    ) {
        val clampedY = if (fractionY.isFinite()) fractionY.coerceIn(0.10f, 0.90f) else 0.72f
        update(context) { cur ->
            cur.copy(
                orbDockedRight = dockedRight,
                orbVerticalFraction = clampedY,
            )
        }
    }

    fun reset(context: Context) {
        ensureHydrated(context)
        val defaults = DockCustomizationConfig()
        synchronized(lock) {
            _stateFlow.value = defaults
            writeToPrefs(prefs(context), defaults)
        }
    }

    private fun readFromPrefs(p: SharedPreferences): DockCustomizationConfig {
        val sizeRaw = p.getString(KEY_SIZE_PRESET, DockSizePreset.STANDARD.name)
        val glassRaw = p.getString(KEY_GLASS_STYLE, DockGlassStyle.BALANCED.name)
        val sizePreset = DockSizePreset.entries.firstOrNull { it.name == sizeRaw }
            ?: DockSizePreset.STANDARD
        val glassStyle = DockGlassStyle.entries.firstOrNull { it.name == glassRaw }
            ?: DockGlassStyle.BALANCED
        val rawFraction = p.getFloat(KEY_ORB_VERTICAL_FRACTION, 0.72f)
        val safeFraction = if (rawFraction.isFinite()) rawFraction.coerceIn(0.10f, 0.90f) else 0.72f
        val rawOrbOpacity = p.getFloat(KEY_ORB_OPACITY, 0.86f)
        val safeOrbOpacity = if (rawOrbOpacity.isFinite()) rawOrbOpacity.coerceIn(0.25f, 1.0f) else 0.86f
        return DockCustomizationConfig(
            sizePreset = sizePreset,
            floatingStyle = p.getBoolean(KEY_FLOATING_STYLE, true),
            autoHide = p.getBoolean(KEY_AUTO_HIDE, false),
            showSearchButton = p.getBoolean(KEY_SHOW_SEARCH, true),
            showSettingsButton = p.getBoolean(KEY_SHOW_SETTINGS, true),
            showRunningApps = p.getBoolean(KEY_SHOW_RUNNING_APPS, true),
            showRunningDots = p.getBoolean(KEY_SHOW_RUNNING_DOTS, true),
            glassStyle = glassStyle,
            orbMode = p.getBoolean(KEY_ORB_MODE, false),
            orbSnapToEdge = p.getBoolean(KEY_ORB_SNAP_TO_EDGE, true),
            orbHoverExpand = p.getBoolean(KEY_ORB_HOVER_EXPAND, true),
            orbIdleDim = p.getBoolean(KEY_ORB_IDLE_DIM, true),
            orbDockedRight = p.getBoolean(KEY_ORB_DOCKED_RIGHT, true),
            orbVerticalFraction = safeFraction,
            orbOpacity = safeOrbOpacity,
        )
    }

    private fun writeToPrefs(p: SharedPreferences, config: DockCustomizationConfig) {
        p.edit()
            .putString(KEY_SIZE_PRESET, config.sizePreset.name)
            .putBoolean(KEY_FLOATING_STYLE, config.floatingStyle)
            .putBoolean(KEY_AUTO_HIDE, config.autoHide)
            .putBoolean(KEY_SHOW_SEARCH, config.showSearchButton)
            .putBoolean(KEY_SHOW_SETTINGS, config.showSettingsButton)
            .putBoolean(KEY_SHOW_RUNNING_APPS, config.showRunningApps)
            .putBoolean(KEY_SHOW_RUNNING_DOTS, config.showRunningDots)
            .putString(KEY_GLASS_STYLE, config.glassStyle.name)
            .putBoolean(KEY_ORB_MODE, config.orbMode)
            .putBoolean(KEY_ORB_SNAP_TO_EDGE, config.orbSnapToEdge)
            .putBoolean(KEY_ORB_HOVER_EXPAND, config.orbHoverExpand)
            .putBoolean(KEY_ORB_IDLE_DIM, config.orbIdleDim)
            .putBoolean(KEY_ORB_DOCKED_RIGHT, config.orbDockedRight)
            .putFloat(KEY_ORB_VERTICAL_FRACTION, config.orbVerticalFraction.coerceIn(0.10f, 0.90f))
            .putFloat(KEY_ORB_OPACITY, config.clampedOrbOpacity())
            .apply()
    }
}
