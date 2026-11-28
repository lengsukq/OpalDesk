package io.github.lengsukq.opaldesk.prefs

import android.content.Context
import android.content.SharedPreferences
import io.github.lengsukq.opaldesk.compositor.DockAppearancePolicy
import io.github.lengsukq.opaldesk.external.CustomResolutionPolicy
import io.github.lengsukq.opaldesk.external.CustomResolutionSpec
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.WorkstationShortcutCatalog
import io.github.lengsukq.opaldesk.ui.WorkstationShortcutId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Display, appearance, dock, trackpad/mouse input, keyboard shortcuts,
 * custom resolution, DeX IME, and Spotlight recents preference backing store.
 */
internal object DisplayAndInputPrefs {
    private const val KEY_AUTO_ENTER_DEX = "auto_enter_dex_on_launch_v1"
    private const val KEY_AUTO_EXT_DISPLAY = "auto_project_ext_display"
    private const val KEY_AUTO_SHOW_PHONE_TRACKPAD = "auto_show_phone_trackpad_on_external"
    private const val KEY_EXTERNAL_PROJECTION_MODE = "external_projection_mode_v1"
    private const val KEY_EXTERNAL_OVERSCAN_PCT = "external_overscan_pct_v1"
    private const val KEY_PINNED_PKGS = "pinned_dock_pkgs"
    private const val KEY_DESKTOP_SHORTCUTS = "desktop_shortcut_pkgs"
    private const val KEY_DOCK_PINNED = "dock_pinned_pkgs_v2"
    private const val KEY_WALLPAPER_PRESET = "desktop_wallpaper_preset"
    private const val KEY_CUSTOM_WALLPAPER_PATH = "custom_wallpaper_path"
    private const val KEY_EXTERNAL_UI_SCALE = "external_ui_scale"
    private const val KEY_TRACKPAD_SENSITIVITY = "trackpad_sensitivity"
    private const val KEY_THEME_MODE = "appearance_theme_mode"
    private const val KEY_MOUSE_TARGET = "physical_mouse_target"
    private const val KEY_DOCK_VISIBLE = "dock_visible_v1"
    private const val KEY_DOCK_OPACITY = "dock_opacity_v1"
    private const val KEY_DOCK_SCALE = "dock_scale_v1"
    private const val KEY_GLOBAL_SHORTCUTS_ENABLED = "global_shortcuts_enabled_v1"
    private const val KEY_DISABLED_SHORTCUT_IDS = "disabled_shortcut_ids_v1"
    private const val KEY_SHOW_DESKTOP_GUIDE_ICON = "show_desktop_guide_icon_v1"
    private const val KEY_CUSTOM_RES_PRESETS = "custom_resolution_presets_v1"
    private const val KEY_AUTO_CUSTOM_RES_ENABLED = "auto_custom_resolution_enabled_v1"
    private const val KEY_EXTERNAL_NATIVE_1TO1_NO_SCALING = "external_native_1to1_no_scaling_v1"
    private const val KEY_DEFAULT_CUSTOM_RES_W = "default_custom_resolution_w_v1"
    private const val KEY_DEFAULT_CUSTOM_RES_H = "default_custom_resolution_h_v1"
    private const val KEY_DEFAULT_CUSTOM_RES_FPS = "default_custom_resolution_fps_v1"
    private const val KEY_DEX_IME_DISPLAY_MODE = "dex_ime_display_mode_v1"
    private const val KEY_DEX_LOCKED_IME_ID = "dex_locked_ime_id_v1"
    private const val KEY_MUTE_HOST_IME_ON_CAST = "mute_host_ime_on_cast_v1"
    private const val KEY_HARD_KBD_SOFT_IME_MODE = "hard_kbd_soft_ime_mode_v1"
    private const val KEY_AUTO_BRIDGE_IME_ON_CAST = "auto_bridge_ime_on_cast_v1"
    private const val KEY_SPOTLIGHT_RECENTS = "spotlight_recent_ids_v1"

    private val DEFAULT_PINNED = listOf(
        "com.android.chrome",
        "com.android.vending",
        "com.android.settings",
        "com.google.android.youtube",
        "com.tencent.mm",
        "tv.danmaku.bili",
    )

    private val _themeModeFlow = MutableStateFlow(AppPrefs.THEME_LIGHT)
    val themeModeFlow: StateFlow<Int> = _themeModeFlow.asStateFlow()

    private val _wallpaperPresetFlow = MutableStateFlow(0)
    val wallpaperPresetFlow: StateFlow<Int> = _wallpaperPresetFlow.asStateFlow()

    private val _trackpadSensitivityFlow = MutableStateFlow(AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
    val trackpadSensitivityFlow: StateFlow<Float> = _trackpadSensitivityFlow.asStateFlow()

    private val _externalUiScaleFlow = MutableStateFlow(1.25f)
    val externalUiScaleFlow: StateFlow<Float> = _externalUiScaleFlow.asStateFlow()

    private val _mouseTargetFlow = MutableStateFlow(AppPrefs.MOUSE_TARGET_EXTERNAL)
    val mouseTargetFlow: StateFlow<Int> = _mouseTargetFlow.asStateFlow()

    private val _desktopShortcutsFlow = MutableStateFlow(DEFAULT_PINNED)
    val desktopShortcutsFlow: StateFlow<List<String>> = _desktopShortcutsFlow.asStateFlow()

    private val _dockPinnedFlow = MutableStateFlow(DEFAULT_PINNED.take(AppPrefs.MAX_DOCK_PINNED))
    val dockPinnedFlow: StateFlow<List<String>> = _dockPinnedFlow.asStateFlow()

    private val _customWallpaperPathFlow = MutableStateFlow("")
    val customWallpaperPathFlow: StateFlow<String> = _customWallpaperPathFlow.asStateFlow()

    private val _autoShowPhoneTrackpadFlow = MutableStateFlow(true)
    val autoShowPhoneTrackpadFlow: StateFlow<Boolean> = _autoShowPhoneTrackpadFlow.asStateFlow()

    private val _autoEnterDexFlow = MutableStateFlow(false)
    val autoEnterDexFlow: StateFlow<Boolean> = _autoEnterDexFlow.asStateFlow()

    private val _externalProjectionModeFlow = MutableStateFlow(AppPrefs.PROJECTION_MODE_AUTO)
    val externalProjectionModeFlow: StateFlow<Int> = _externalProjectionModeFlow.asStateFlow()

    private val _externalOverscanPctFlow = MutableStateFlow(AppPrefs.DEFAULT_OVERSCAN_PCT)
    val externalOverscanPctFlow: StateFlow<Float> = _externalOverscanPctFlow.asStateFlow()

    private val _dockVisibleFlow = MutableStateFlow(true)
    val dockVisibleFlow: StateFlow<Boolean> = _dockVisibleFlow.asStateFlow()

    private val _dockOpacityFlow = MutableStateFlow(DockAppearancePolicy.DEFAULT_OPACITY)
    val dockOpacityFlow: StateFlow<Float> = _dockOpacityFlow.asStateFlow()

    private val _dockScaleFlow = MutableStateFlow(DockAppearancePolicy.DEFAULT_SCALE)
    val dockScaleFlow: StateFlow<Float> = _dockScaleFlow.asStateFlow()

    private val _globalShortcutsEnabledFlow = MutableStateFlow(true)
    val globalShortcutsEnabledFlow: StateFlow<Boolean> = _globalShortcutsEnabledFlow.asStateFlow()

    private val _disabledShortcutIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    val disabledShortcutIdsFlow: StateFlow<Set<String>> = _disabledShortcutIdsFlow.asStateFlow()

    private val _showDesktopGuideIconFlow = MutableStateFlow(true)
    val showDesktopGuideIconFlow: StateFlow<Boolean> = _showDesktopGuideIconFlow.asStateFlow()

    private val _customResolutionPresetsFlow = MutableStateFlow<List<CustomResolutionSpec>>(emptyList())
    val customResolutionPresetsFlow: StateFlow<List<CustomResolutionSpec>> = _customResolutionPresetsFlow.asStateFlow()

    private val _autoCustomResolutionEnabledFlow = MutableStateFlow(false)
    val autoCustomResolutionEnabledFlow: StateFlow<Boolean> = _autoCustomResolutionEnabledFlow.asStateFlow()

    private val _externalNative1To1Flow = MutableStateFlow(false)
    val externalNative1To1Flow: StateFlow<Boolean> = _externalNative1To1Flow.asStateFlow()

    private val _defaultCustomResolutionFlow = MutableStateFlow(CustomResolutionPolicy.DEFAULT_CUSTOM_SPEC)
    val defaultCustomResolutionFlow: StateFlow<CustomResolutionSpec> = _defaultCustomResolutionFlow.asStateFlow()

    private val _dexImeDisplayModeFlow = MutableStateFlow(BridgeProtocol.DexImeDisplayMode.AUTO)
    val dexImeDisplayModeFlow: StateFlow<Int> = _dexImeDisplayModeFlow.asStateFlow()

    private val _dexLockedImeIdFlow = MutableStateFlow("")
    val dexLockedImeIdFlow: StateFlow<String> = _dexLockedImeIdFlow.asStateFlow()

    private val _muteHostImeOnCastFlow = MutableStateFlow(false)
    val muteHostImeOnCastFlow: StateFlow<Boolean> = _muteHostImeOnCastFlow.asStateFlow()

    private val _hardKbdSoftImeModeFlow = MutableStateFlow(BridgeProtocol.HardKbdSoftIme.SYSTEM)
    val hardKbdSoftImeModeFlow: StateFlow<Int> = _hardKbdSoftImeModeFlow.asStateFlow()

    private val _autoBridgeImeOnCastFlow = MutableStateFlow(true)
    val autoBridgeImeOnCastFlow: StateFlow<Boolean> = _autoBridgeImeOnCastFlow.asStateFlow()

    private val _spotlightRecentsFlow = MutableStateFlow<List<String>>(emptyList())
    val spotlightRecentsFlow: StateFlow<List<String>> = _spotlightRecentsFlow.asStateFlow()

    fun hydrate(p: SharedPreferences) {
        _themeModeFlow.value = p.getInt(KEY_THEME_MODE, AppPrefs.THEME_LIGHT)
            .coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK)
        _wallpaperPresetFlow.value = p.getInt(KEY_WALLPAPER_PRESET, 0).coerceIn(0, 3)
        _customWallpaperPathFlow.value = p.getString(KEY_CUSTOM_WALLPAPER_PATH, "").orEmpty()
        _trackpadSensitivityFlow.value = p.getFloat(KEY_TRACKPAD_SENSITIVITY, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
            .coerceIn(AppPrefs.MIN_TRACKPAD_SENSITIVITY, AppPrefs.MAX_TRACKPAD_SENSITIVITY)
        _externalUiScaleFlow.value = p.getFloat(KEY_EXTERNAL_UI_SCALE, 1.25f).coerceIn(0.85f, 2.0f)
        _mouseTargetFlow.value = p.getInt(KEY_MOUSE_TARGET, AppPrefs.MOUSE_TARGET_EXTERNAL)
            .coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL)
        _desktopShortcutsFlow.value = loadPkgList(p, KEY_DESKTOP_SHORTCUTS, fallbackKey = KEY_PINNED_PKGS)
        _dockPinnedFlow.value = loadPkgList(p, KEY_DOCK_PINNED, fallbackKey = KEY_PINNED_PKGS)
            .take(AppPrefs.MAX_DOCK_PINNED)
        _autoShowPhoneTrackpadFlow.value = p.getBoolean(KEY_AUTO_SHOW_PHONE_TRACKPAD, true)
        _autoEnterDexFlow.value = p.getBoolean(KEY_AUTO_ENTER_DEX, false)
        _externalProjectionModeFlow.value = p.getInt(KEY_EXTERNAL_PROJECTION_MODE, AppPrefs.PROJECTION_MODE_AUTO)
            .coerceIn(AppPrefs.PROJECTION_MODE_AUTO, AppPrefs.PROJECTION_MODE_PRESENTATION)
        _externalOverscanPctFlow.value = p.getFloat(KEY_EXTERNAL_OVERSCAN_PCT, AppPrefs.DEFAULT_OVERSCAN_PCT)
            .coerceIn(AppPrefs.MIN_OVERSCAN_PCT, AppPrefs.MAX_OVERSCAN_PCT)
        _dockVisibleFlow.value = p.getBoolean(KEY_DOCK_VISIBLE, true)
        _dockOpacityFlow.value = DockAppearancePolicy.clampOpacity(
            p.getFloat(KEY_DOCK_OPACITY, DockAppearancePolicy.DEFAULT_OPACITY),
        )
        _dockScaleFlow.value = DockAppearancePolicy.clampScale(
            p.getFloat(KEY_DOCK_SCALE, DockAppearancePolicy.DEFAULT_SCALE),
        )
        _globalShortcutsEnabledFlow.value = p.getBoolean(KEY_GLOBAL_SHORTCUTS_ENABLED, true)
        _disabledShortcutIdsFlow.value = WorkstationShortcutCatalog.parseDisabledIds(
            p.getString(KEY_DISABLED_SHORTCUT_IDS, null),
        )
        _showDesktopGuideIconFlow.value = p.getBoolean(KEY_SHOW_DESKTOP_GUIDE_ICON, true)
        _customResolutionPresetsFlow.value = CustomResolutionPolicy.parsePresets(
            p.getString(KEY_CUSTOM_RES_PRESETS, null),
        )
        _autoCustomResolutionEnabledFlow.value = p.getBoolean(KEY_AUTO_CUSTOM_RES_ENABLED, false)
        _externalNative1To1Flow.value = p.getBoolean(KEY_EXTERNAL_NATIVE_1TO1_NO_SCALING, false)
        val savedW = p.getInt(KEY_DEFAULT_CUSTOM_RES_W, CustomResolutionPolicy.DEFAULT_CUSTOM_SPEC.width)
        val savedH = p.getInt(KEY_DEFAULT_CUSTOM_RES_H, CustomResolutionPolicy.DEFAULT_CUSTOM_SPEC.height)
        val savedFps = p.getInt(KEY_DEFAULT_CUSTOM_RES_FPS, CustomResolutionPolicy.DEFAULT_CUSTOM_SPEC.fps)
        _defaultCustomResolutionFlow.value = CustomResolutionPolicy.sanitize(savedW, savedH, savedFps)
            ?: CustomResolutionPolicy.DEFAULT_CUSTOM_SPEC
        _dexImeDisplayModeFlow.value = p.getInt(
            KEY_DEX_IME_DISPLAY_MODE,
            BridgeProtocol.DexImeDisplayMode.AUTO,
        ).coerceIn(
            BridgeProtocol.DexImeDisplayMode.AUTO,
            BridgeProtocol.DexImeDisplayMode.FALLBACK_HOST,
        )
        _dexLockedImeIdFlow.value = p.getString(KEY_DEX_LOCKED_IME_ID, "").orEmpty().trim()
        _muteHostImeOnCastFlow.value = p.getBoolean(KEY_MUTE_HOST_IME_ON_CAST, false)
        val rawHardKbd = p.getInt(KEY_HARD_KBD_SOFT_IME_MODE, BridgeProtocol.HardKbdSoftIme.SYSTEM)
        _hardKbdSoftImeModeFlow.value = when (rawHardKbd) {
            BridgeProtocol.HardKbdSoftIme.HIDE,
            BridgeProtocol.HardKbdSoftIme.SHOW,
            -> rawHardKbd
            else -> BridgeProtocol.HardKbdSoftIme.SYSTEM
        }
        _autoBridgeImeOnCastFlow.value = p.getBoolean(KEY_AUTO_BRIDGE_IME_ON_CAST, true)
        _spotlightRecentsFlow.value = parseSpotlightRecents(p.getString(KEY_SPOTLIGHT_RECENTS, null))
    }

    private fun loadPkgList(p: SharedPreferences, key: String, fallbackKey: String): List<String> {
        val raw = p.getString(key, null) ?: p.getString(fallbackKey, null)
        if (raw == null) return DEFAULT_PINNED
        if (raw.isBlank()) return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun isAutoExternalDisplayEnabled(ctx: Context?): Boolean =
        AppPrefs.sharedPrefs(ctx)?.getBoolean(KEY_AUTO_EXT_DISPLAY, true) ?: true

    fun setAutoExternalDisplayEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_AUTO_EXT_DISPLAY, enabled)?.apply()
    }

    fun isAutoShowPhoneTrackpadEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _autoShowPhoneTrackpadFlow.value
    }

    fun setAutoShowPhoneTrackpadEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_AUTO_SHOW_PHONE_TRACKPAD, enabled)?.apply()
        _autoShowPhoneTrackpadFlow.value = enabled
    }

    fun isAutoEnterDexEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _autoEnterDexFlow.value
    }

    fun setAutoEnterDexEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_AUTO_ENTER_DEX, enabled)?.apply()
        _autoEnterDexFlow.value = enabled
    }

    fun getExternalProjectionMode(ctx: Context?): Int {
        AppPrefs.hydrateFlows(ctx)
        return (AppPrefs.sharedPrefs(ctx)?.getInt(KEY_EXTERNAL_PROJECTION_MODE, AppPrefs.PROJECTION_MODE_AUTO)
            ?: AppPrefs.PROJECTION_MODE_AUTO)
            .coerceIn(AppPrefs.PROJECTION_MODE_AUTO, AppPrefs.PROJECTION_MODE_PRESENTATION)
    }

    fun setExternalProjectionMode(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(AppPrefs.PROJECTION_MODE_AUTO, AppPrefs.PROJECTION_MODE_PRESENTATION)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putInt(KEY_EXTERNAL_PROJECTION_MODE, clamped)?.apply()
        _externalProjectionModeFlow.value = clamped
    }

    fun getExternalOverscanPct(ctx: Context?): Float {
        AppPrefs.hydrateFlows(ctx)
        return (AppPrefs.sharedPrefs(ctx)?.getFloat(KEY_EXTERNAL_OVERSCAN_PCT, AppPrefs.DEFAULT_OVERSCAN_PCT)
            ?: AppPrefs.DEFAULT_OVERSCAN_PCT)
            .coerceIn(AppPrefs.MIN_OVERSCAN_PCT, AppPrefs.MAX_OVERSCAN_PCT)
    }

    fun setExternalOverscanPct(ctx: Context?, pct: Float) {
        val clamped = pct.coerceIn(AppPrefs.MIN_OVERSCAN_PCT, AppPrefs.MAX_OVERSCAN_PCT)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putFloat(KEY_EXTERNAL_OVERSCAN_PCT, clamped)?.apply()
        _externalOverscanPctFlow.value = clamped
    }

    fun getDesktopShortcuts(ctx: Context?): List<String> {
        AppPrefs.hydrateFlows(ctx)
        return _desktopShortcutsFlow.value
    }

    fun toggleDesktopShortcut(ctx: Context?, pkg: String): Boolean {
        AppPrefs.hydrateFlows(ctx)
        val current = _desktopShortcutsFlow.value.toMutableList()
        val added = if (current.contains(pkg)) {
            current.remove(pkg)
            false
        } else {
            current.add(pkg)
            true
        }
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_DESKTOP_SHORTCUTS, current.joinToString(","))?.apply()
        _desktopShortcutsFlow.value = current
        return added
    }

    fun getDockPinnedPackages(ctx: Context?): List<String> {
        AppPrefs.hydrateFlows(ctx)
        return _dockPinnedFlow.value
    }

    fun isDockVisible(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _dockVisibleFlow.value
    }

    fun setDockVisible(ctx: Context?, visible: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_DOCK_VISIBLE, visible)?.apply()
        _dockVisibleFlow.value = visible
    }

    fun getDockOpacity(ctx: Context?): Float {
        AppPrefs.hydrateFlows(ctx)
        return _dockOpacityFlow.value
    }

    fun setDockOpacity(ctx: Context?, opacity: Float) {
        val clamped = DockAppearancePolicy.clampOpacity(opacity)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putFloat(KEY_DOCK_OPACITY, clamped)?.apply()
        _dockOpacityFlow.value = clamped
    }

    fun getDockScale(ctx: Context?): Float {
        AppPrefs.hydrateFlows(ctx)
        return _dockScaleFlow.value
    }

    fun setDockScale(ctx: Context?, scale: Float) {
        val clamped = DockAppearancePolicy.clampScale(scale)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putFloat(KEY_DOCK_SCALE, clamped)?.apply()
        _dockScaleFlow.value = clamped
    }

    fun resetDockAppearance(ctx: Context?) {
        AppPrefs.sharedPrefs(ctx)?.edit()
            ?.putBoolean(KEY_DOCK_VISIBLE, true)
            ?.putFloat(KEY_DOCK_OPACITY, DockAppearancePolicy.DEFAULT_OPACITY)
            ?.putFloat(KEY_DOCK_SCALE, DockAppearancePolicy.DEFAULT_SCALE)
            ?.apply()
        _dockVisibleFlow.value = true
        _dockOpacityFlow.value = DockAppearancePolicy.DEFAULT_OPACITY
        _dockScaleFlow.value = DockAppearancePolicy.DEFAULT_SCALE
    }

    fun toggleDockPin(ctx: Context?, pkg: String): AppPrefs.DockPinResult {
        AppPrefs.hydrateFlows(ctx)
        val current = _dockPinnedFlow.value.toMutableList()
        if (current.contains(pkg)) {
            current.remove(pkg)
            AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_DOCK_PINNED, current.joinToString(","))?.apply()
            _dockPinnedFlow.value = current
            return AppPrefs.DockPinResult.REMOVED
        }
        if (current.size >= AppPrefs.MAX_DOCK_PINNED) {
            return AppPrefs.DockPinResult.LIMIT_REACHED
        }
        current.add(pkg)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_DOCK_PINNED, current.joinToString(","))?.apply()
        _dockPinnedFlow.value = current
        return AppPrefs.DockPinResult.ADDED
    }

    fun setCustomWallpaperPath(ctx: Context?, path: String) {
        val clean = path.trim()
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_CUSTOM_WALLPAPER_PATH, clean)?.apply()
        _customWallpaperPathFlow.value = clean
    }

    fun clearCustomWallpaper(ctx: Context?) {
        setCustomWallpaperPath(ctx, "")
    }

    fun getThemeMode(ctx: Context?): Int {
        AppPrefs.hydrateFlows(ctx)
        return (AppPrefs.sharedPrefs(ctx)?.getInt(KEY_THEME_MODE, AppPrefs.THEME_LIGHT) ?: AppPrefs.THEME_LIGHT)
            .coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK)
    }

    fun setThemeMode(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putInt(KEY_THEME_MODE, clamped)?.apply()
        _themeModeFlow.value = clamped
    }

    fun getWallpaperPreset(ctx: Context?): Int {
        AppPrefs.hydrateFlows(ctx)
        return (AppPrefs.sharedPrefs(ctx)?.getInt(KEY_WALLPAPER_PRESET, 0) ?: 0).coerceIn(0, 3)
    }

    fun setWallpaperPreset(ctx: Context?, preset: Int) {
        val clamped = preset.coerceIn(0, 3)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putInt(KEY_WALLPAPER_PRESET, clamped)?.apply()
        _wallpaperPresetFlow.value = clamped
    }

    fun getExternalUiScale(ctx: Context?): Float {
        AppPrefs.hydrateFlows(ctx)
        return (AppPrefs.sharedPrefs(ctx)?.getFloat(KEY_EXTERNAL_UI_SCALE, 1.25f) ?: 1.25f).coerceIn(0.85f, 2.0f)
    }

    fun setExternalUiScale(ctx: Context?, scale: Float) {
        val clamped = scale.coerceIn(0.85f, 2.0f)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putFloat(KEY_EXTERNAL_UI_SCALE, clamped)?.apply()
        _externalUiScaleFlow.value = clamped
    }

    fun getTrackpadSensitivity(ctx: Context?): Float {
        AppPrefs.hydrateFlows(ctx)
        return (AppPrefs.sharedPrefs(ctx)?.getFloat(KEY_TRACKPAD_SENSITIVITY, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
            ?: AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
            .coerceIn(AppPrefs.MIN_TRACKPAD_SENSITIVITY, AppPrefs.MAX_TRACKPAD_SENSITIVITY)
    }

    fun setTrackpadSensitivity(ctx: Context?, sensitivity: Float) {
        val clamped = sensitivity.coerceIn(AppPrefs.MIN_TRACKPAD_SENSITIVITY, AppPrefs.MAX_TRACKPAD_SENSITIVITY)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putFloat(KEY_TRACKPAD_SENSITIVITY, clamped)?.apply()
        _trackpadSensitivityFlow.value = clamped
    }

    fun getMouseTarget(ctx: Context?): Int {
        AppPrefs.hydrateFlows(ctx)
        return (AppPrefs.sharedPrefs(ctx)?.getInt(KEY_MOUSE_TARGET, AppPrefs.MOUSE_TARGET_EXTERNAL)
            ?: AppPrefs.MOUSE_TARGET_EXTERNAL)
            .coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL)
    }

    fun setMouseTarget(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putInt(KEY_MOUSE_TARGET, clamped)?.apply()
        _mouseTargetFlow.value = clamped
    }

    fun isGlobalShortcutsEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _globalShortcutsEnabledFlow.value
    }

    fun setGlobalShortcutsEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_GLOBAL_SHORTCUTS_ENABLED, enabled)?.apply()
        _globalShortcutsEnabledFlow.value = enabled
    }

    fun getDisabledShortcutIds(ctx: Context?): Set<String> {
        AppPrefs.hydrateFlows(ctx)
        return _disabledShortcutIdsFlow.value
    }

    fun setShortcutEnabled(ctx: Context?, shortcutId: WorkstationShortcutId, enabled: Boolean) {
        AppPrefs.hydrateFlows(ctx)
        val next = _disabledShortcutIdsFlow.value.toMutableSet()
        if (enabled) {
            next.remove(shortcutId.prefKey)
        } else {
            next.add(shortcutId.prefKey)
        }
        val serialized = WorkstationShortcutCatalog.serializeDisabledIds(next)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_DISABLED_SHORTCUT_IDS, serialized)?.apply()
        _disabledShortcutIdsFlow.value = next
    }

    fun resetKeyboardShortcuts(ctx: Context?) {
        AppPrefs.sharedPrefs(ctx)?.edit()
            ?.putBoolean(KEY_GLOBAL_SHORTCUTS_ENABLED, true)
            ?.remove(KEY_DISABLED_SHORTCUT_IDS)
            ?.apply()
        _globalShortcutsEnabledFlow.value = true
        _disabledShortcutIdsFlow.value = emptySet()
    }

    fun isDesktopGuideIconVisible(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _showDesktopGuideIconFlow.value
    }

    fun setDesktopGuideIconVisible(ctx: Context?, visible: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_SHOW_DESKTOP_GUIDE_ICON, visible)?.apply()
        _showDesktopGuideIconFlow.value = visible
    }

    fun getCustomResolutionPresets(ctx: Context?): List<CustomResolutionSpec> {
        AppPrefs.hydrateFlows(ctx)
        return _customResolutionPresetsFlow.value
    }

    fun addCustomResolutionPreset(ctx: Context?, spec: CustomResolutionSpec) {
        AppPrefs.hydrateFlows(ctx)
        val updated = CustomResolutionPolicy.upsertPreset(_customResolutionPresetsFlow.value, spec)
        AppPrefs.sharedPrefs(ctx)?.edit()
            ?.putString(KEY_CUSTOM_RES_PRESETS, CustomResolutionPolicy.serializePresets(updated))
            ?.apply()
        _customResolutionPresetsFlow.value = updated
    }

    fun removeCustomResolutionPreset(ctx: Context?, key: String) {
        AppPrefs.hydrateFlows(ctx)
        val updated = CustomResolutionPolicy.removePreset(_customResolutionPresetsFlow.value, key)
        AppPrefs.sharedPrefs(ctx)?.edit()
            ?.putString(KEY_CUSTOM_RES_PRESETS, CustomResolutionPolicy.serializePresets(updated))
            ?.apply()
        _customResolutionPresetsFlow.value = updated
    }

    fun isAutoCustomResolutionEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _autoCustomResolutionEnabledFlow.value
    }

    fun setAutoCustomResolutionEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_AUTO_CUSTOM_RES_ENABLED, enabled)?.apply()
        _autoCustomResolutionEnabledFlow.value = enabled
    }

    fun isExternalNative1To1Enabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _externalNative1To1Flow.value
    }

    fun setExternalNative1To1Enabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_EXTERNAL_NATIVE_1TO1_NO_SCALING, enabled)?.apply()
        _externalNative1To1Flow.value = enabled
    }

    fun getDefaultCustomResolution(ctx: Context?): CustomResolutionSpec {
        AppPrefs.hydrateFlows(ctx)
        return _defaultCustomResolutionFlow.value
    }

    fun setDefaultCustomResolution(ctx: Context?, spec: CustomResolutionSpec) {
        val clean = CustomResolutionPolicy.sanitize(spec.width, spec.height, spec.fps, spec.label) ?: return
        AppPrefs.sharedPrefs(ctx)?.edit()
            ?.putInt(KEY_DEFAULT_CUSTOM_RES_W, clean.width)
            ?.putInt(KEY_DEFAULT_CUSTOM_RES_H, clean.height)
            ?.putInt(KEY_DEFAULT_CUSTOM_RES_FPS, clean.fps)
            ?.apply()
        _defaultCustomResolutionFlow.value = clean
    }

    fun getDexImeDisplayMode(ctx: Context?): Int {
        AppPrefs.hydrateFlows(ctx)
        return _dexImeDisplayModeFlow.value
    }

    fun setDexImeDisplayMode(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(
            BridgeProtocol.DexImeDisplayMode.AUTO,
            BridgeProtocol.DexImeDisplayMode.FALLBACK_HOST,
        )
        AppPrefs.sharedPrefs(ctx)?.edit()?.putInt(KEY_DEX_IME_DISPLAY_MODE, clamped)?.apply()
        _dexImeDisplayModeFlow.value = clamped
    }

    fun getDexLockedImeId(ctx: Context?): String {
        AppPrefs.hydrateFlows(ctx)
        return _dexLockedImeIdFlow.value
    }

    fun setDexLockedImeId(ctx: Context?, imeId: String) {
        val clean = imeId.trim()
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_DEX_LOCKED_IME_ID, clean)?.apply()
        _dexLockedImeIdFlow.value = clean
    }

    fun isMuteHostImeOnCastEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _muteHostImeOnCastFlow.value
    }

    fun setMuteHostImeOnCastEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_MUTE_HOST_IME_ON_CAST, enabled)?.apply()
        _muteHostImeOnCastFlow.value = enabled
    }

    fun getHardKbdSoftImeMode(ctx: Context?): Int {
        AppPrefs.hydrateFlows(ctx)
        return _hardKbdSoftImeModeFlow.value
    }

    fun setHardKbdSoftImeMode(ctx: Context?, mode: Int) {
        val clamped = when (mode) {
            BridgeProtocol.HardKbdSoftIme.HIDE,
            BridgeProtocol.HardKbdSoftIme.SHOW,
            -> mode
            else -> BridgeProtocol.HardKbdSoftIme.SYSTEM
        }
        AppPrefs.sharedPrefs(ctx)?.edit()?.putInt(KEY_HARD_KBD_SOFT_IME_MODE, clamped)?.apply()
        _hardKbdSoftImeModeFlow.value = clamped
    }

    fun isAutoBridgeImeOnCastEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _autoBridgeImeOnCastFlow.value
    }

    fun setAutoBridgeImeOnCastEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_AUTO_BRIDGE_IME_ON_CAST, enabled)?.apply()
        _autoBridgeImeOnCastFlow.value = enabled
    }

    fun getSpotlightRecents(ctx: Context?): List<String> {
        AppPrefs.hydrateFlows(ctx)
        return _spotlightRecentsFlow.value
    }

    fun recordSpotlightRecent(ctx: Context?, itemId: String): List<String> {
        AppPrefs.hydrateFlows(ctx)
        val updated = updateSpotlightRecents(_spotlightRecentsFlow.value, itemId, AppPrefs.MAX_SPOTLIGHT_RECENTS)
        _spotlightRecentsFlow.value = updated
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_SPOTLIGHT_RECENTS, updated.joinToString(","))?.apply()
        return updated
    }

    fun parseSpotlightRecents(raw: String?, maxSize: Int = AppPrefs.MAX_SPOTLIGHT_RECENTS): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(maxSize.coerceAtLeast(1))
            .toList()
    }

    fun updateSpotlightRecents(
        existing: List<String>,
        itemId: String,
        maxSize: Int = AppPrefs.MAX_SPOTLIGHT_RECENTS,
    ): List<String> {
        val clean = itemId.trim()
        if (clean.isEmpty()) return existing.take(maxSize.coerceAtLeast(1))
        val out = ArrayList<String>(maxSize.coerceAtLeast(1))
        out.add(clean)
        for (id in existing) {
            val t = id.trim()
            if (t.isNotEmpty() && t != clean && out.size < maxSize) {
                out.add(t)
            }
        }
        return out
    }
}
