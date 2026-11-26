package io.github.lengsukq.opaldesk.prefs

import android.content.Context
import io.github.lengsukq.opaldesk.compositor.DockAppearancePolicy
import io.github.lengsukq.opaldesk.diagnostics.FeedbackQuotaStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * SharedPreferences store + live reactive flows for OpalDesk preferences:
 * appearance theme (Light Frosted Glass / Follow System / Dark), wallpapers, pinned apps,
 * external monitor UI scaling, stepless trackpad pointer speed, and activation preferences.
 */
object AppPrefs {
    const val THEME_LIGHT = 0
    const val THEME_SYSTEM = 1
    const val THEME_DARK = 2

    const val MOUSE_TARGET_LOCAL = 0
    const val MOUSE_TARGET_EXTERNAL = 1

    const val UPDATE_ROUTE_DIRECT = 0
    const val UPDATE_ROUTE_PROXY_1 = 1
    const val UPDATE_ROUTE_PROXY_2 = 2

    const val MIN_TRACKPAD_SENSITIVITY = 0.50f
    const val MAX_TRACKPAD_SENSITIVITY = 3.00f
    const val DEFAULT_TRACKPAD_SENSITIVITY = 1.45f
    const val MAX_DOCK_PINNED = 8

    const val PROJECTION_MODE_AUTO = 0
    const val PROJECTION_MODE_PRESENTATION = 1

    const val MIN_OVERSCAN_PCT = 0f
    const val MAX_OVERSCAN_PCT = 6f
    const val DEFAULT_OVERSCAN_PCT = 0f

    private const val PREFS_NAME = "opaldesk_v2_prefs"
    private const val KEY_AUTO_ROOT = "auto_root"
    private const val KEY_AUTO_SHIZUKU = "auto_shizuku"
    private const val KEY_ROOT_ENHANCE = "root_enhance_optional"
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
    private const val KEY_SORT_APPS_BY_USAGE = "sort_apps_by_usage"
    private const val KEY_APP_LAUNCH_COUNTS = "app_launch_counts_v1"
    private const val KEY_AUTO_CHECK_UPDATE = "auto_check_app_update"
    private const val KEY_LAST_UPDATE_CHECK_MS = "last_update_check_ms"
    private const val KEY_IGNORED_UPDATE_TAG = "ignored_update_tag"
    private const val KEY_UPDATE_DOWNLOAD_ROUTE = "update_download_route"
    private const val KEY_INSTALLATION_ID = "anonymous_installation_id_v1"
    private const val KEY_FEEDBACK_REPORT_DATE = "feedback_report_date_v1"
    private const val KEY_FEEDBACK_REPORT_COUNT = "feedback_report_count_v1"
    private const val KEY_FEEDBACK_USERNAME = "feedback_reporter_username_v1"
    private const val KEY_REMOTE_MIC_ENABLED = "remote_mic_uplink_enabled_v1"
    private const val KEY_REMOTE_CAMERA_ENABLED = "remote_camera_mapping_enabled_v1"
    private const val KEY_AUDIO_ROUTE_MODE = "audio_route_mode_v2"
    private const val KEY_DOCK_VISIBLE = "dock_visible_v1"
    private const val KEY_DOCK_OPACITY = "dock_opacity_v1"
    private const val KEY_DOCK_SCALE = "dock_scale_v1"
    private const val KEY_GLOBAL_SHORTCUTS_ENABLED = "global_shortcuts_enabled_v1"
    private const val KEY_DISABLED_SHORTCUT_IDS = "disabled_shortcut_ids_v1"
    private const val KEY_SHOW_DESKTOP_GUIDE_ICON = "show_desktop_guide_icon_v1"

    private val DEFAULT_PINNED = listOf(
        "com.android.chrome",
        "com.android.vending",
        "com.android.settings",
        "com.google.android.youtube",
        "com.tencent.mm",
        "tv.danmaku.bili",
    )

    @Volatile
    private var flowsHydrated = false

    private val _themeModeFlow = MutableStateFlow(THEME_LIGHT)
    val themeModeFlow: StateFlow<Int> = _themeModeFlow.asStateFlow()

    private val _wallpaperPresetFlow = MutableStateFlow(0)
    val wallpaperPresetFlow: StateFlow<Int> = _wallpaperPresetFlow.asStateFlow()

    private val _trackpadSensitivityFlow = MutableStateFlow(DEFAULT_TRACKPAD_SENSITIVITY)
    val trackpadSensitivityFlow: StateFlow<Float> = _trackpadSensitivityFlow.asStateFlow()

    private val _externalUiScaleFlow = MutableStateFlow(1.25f)
    val externalUiScaleFlow: StateFlow<Float> = _externalUiScaleFlow.asStateFlow()

    private val _mouseTargetFlow = MutableStateFlow(MOUSE_TARGET_EXTERNAL)
    val mouseTargetFlow: StateFlow<Int> = _mouseTargetFlow.asStateFlow()

    private val _desktopShortcutsFlow = MutableStateFlow(DEFAULT_PINNED)
    val desktopShortcutsFlow: StateFlow<List<String>> = _desktopShortcutsFlow.asStateFlow()

    private val _dockPinnedFlow = MutableStateFlow(DEFAULT_PINNED.take(MAX_DOCK_PINNED))
    val dockPinnedFlow: StateFlow<List<String>> = _dockPinnedFlow.asStateFlow()

    private val _customWallpaperPathFlow = MutableStateFlow("")
    val customWallpaperPathFlow: StateFlow<String> = _customWallpaperPathFlow.asStateFlow()

    private val _sortAppsByUsageFlow = MutableStateFlow(true)
    val sortAppsByUsageFlow: StateFlow<Boolean> = _sortAppsByUsageFlow.asStateFlow()

    private val _appLaunchCountsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    val appLaunchCountsFlow: StateFlow<Map<String, Int>> = _appLaunchCountsFlow.asStateFlow()

    private val _autoCheckUpdateFlow = MutableStateFlow(true)
    val autoCheckUpdateFlow: StateFlow<Boolean> = _autoCheckUpdateFlow.asStateFlow()

    private val _autoShowPhoneTrackpadFlow = MutableStateFlow(true)
    val autoShowPhoneTrackpadFlow: StateFlow<Boolean> = _autoShowPhoneTrackpadFlow.asStateFlow()

    private val _autoEnterDexFlow = MutableStateFlow(false)
    val autoEnterDexFlow: StateFlow<Boolean> = _autoEnterDexFlow.asStateFlow()

    private val _externalProjectionModeFlow = MutableStateFlow(PROJECTION_MODE_AUTO)
    val externalProjectionModeFlow: StateFlow<Int> = _externalProjectionModeFlow.asStateFlow()

    private val _externalOverscanPctFlow = MutableStateFlow(DEFAULT_OVERSCAN_PCT)
    val externalOverscanPctFlow: StateFlow<Float> = _externalOverscanPctFlow.asStateFlow()

    private val _updateDownloadRouteFlow = MutableStateFlow(UPDATE_ROUTE_DIRECT)
    val updateDownloadRouteFlow: StateFlow<Int> = _updateDownloadRouteFlow.asStateFlow()

    private val _remoteMicEnabledFlow = MutableStateFlow(false)
    val remoteMicEnabledFlow: StateFlow<Boolean> = _remoteMicEnabledFlow.asStateFlow()

    private val _remoteCameraEnabledFlow = MutableStateFlow(false)
    val remoteCameraEnabledFlow: StateFlow<Boolean> = _remoteCameraEnabledFlow.asStateFlow()

    private val _audioRouteModeFlow = MutableStateFlow(
        io.github.lengsukq.opaldesk.protocol.BridgeProtocol.AudioRoute.SPLIT_BY_APP,
    )
    val audioRouteModeFlow: StateFlow<Int> = _audioRouteModeFlow.asStateFlow()

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

    private fun prefs(ctx: Context?) =
        ctx?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hydrateFlows(ctx: Context?) {
        if (flowsHydrated || ctx == null) return
        flowsHydrated = true
        val p = prefs(ctx) ?: return
        _themeModeFlow.value = p.getInt(KEY_THEME_MODE, THEME_LIGHT).coerceIn(THEME_LIGHT, THEME_DARK)
        _wallpaperPresetFlow.value = p.getInt(KEY_WALLPAPER_PRESET, 0).coerceIn(0, 3)
        _customWallpaperPathFlow.value = p.getString(KEY_CUSTOM_WALLPAPER_PATH, "").orEmpty()
        _trackpadSensitivityFlow.value = p.getFloat(KEY_TRACKPAD_SENSITIVITY, DEFAULT_TRACKPAD_SENSITIVITY)
            .coerceIn(MIN_TRACKPAD_SENSITIVITY, MAX_TRACKPAD_SENSITIVITY)
        _externalUiScaleFlow.value = p.getFloat(KEY_EXTERNAL_UI_SCALE, 1.25f).coerceIn(0.85f, 2.0f)
        _mouseTargetFlow.value = p.getInt(KEY_MOUSE_TARGET, MOUSE_TARGET_EXTERNAL)
            .coerceIn(MOUSE_TARGET_LOCAL, MOUSE_TARGET_EXTERNAL)
        _desktopShortcutsFlow.value = loadPkgList(p, KEY_DESKTOP_SHORTCUTS, fallbackKey = KEY_PINNED_PKGS)
        _dockPinnedFlow.value = loadPkgList(p, KEY_DOCK_PINNED, fallbackKey = KEY_PINNED_PKGS).take(MAX_DOCK_PINNED)
        _sortAppsByUsageFlow.value = p.getBoolean(KEY_SORT_APPS_BY_USAGE, true)
        _appLaunchCountsFlow.value = parseLaunchCounts(p.getString(KEY_APP_LAUNCH_COUNTS, null))
        _autoCheckUpdateFlow.value = p.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
        _autoShowPhoneTrackpadFlow.value = p.getBoolean(KEY_AUTO_SHOW_PHONE_TRACKPAD, true)
        _autoEnterDexFlow.value = p.getBoolean(KEY_AUTO_ENTER_DEX, false)
        _externalProjectionModeFlow.value = p.getInt(KEY_EXTERNAL_PROJECTION_MODE, PROJECTION_MODE_AUTO)
            .coerceIn(PROJECTION_MODE_AUTO, PROJECTION_MODE_PRESENTATION)
        _externalOverscanPctFlow.value = p.getFloat(KEY_EXTERNAL_OVERSCAN_PCT, DEFAULT_OVERSCAN_PCT)
            .coerceIn(MIN_OVERSCAN_PCT, MAX_OVERSCAN_PCT)
        _updateDownloadRouteFlow.value = p.getInt(KEY_UPDATE_DOWNLOAD_ROUTE, UPDATE_ROUTE_DIRECT)
            .coerceIn(UPDATE_ROUTE_DIRECT, UPDATE_ROUTE_PROXY_2)
        _remoteMicEnabledFlow.value = p.getBoolean(KEY_REMOTE_MIC_ENABLED, false)
        _remoteCameraEnabledFlow.value = p.getBoolean(KEY_REMOTE_CAMERA_ENABLED, false)
        _audioRouteModeFlow.value = p.getInt(
            KEY_AUDIO_ROUTE_MODE,
            io.github.lengsukq.opaldesk.protocol.BridgeProtocol.AudioRoute.SPLIT_BY_APP,
        ).coerceIn(
            io.github.lengsukq.opaldesk.protocol.BridgeProtocol.AudioRoute.ALL_TO_DEX,
            io.github.lengsukq.opaldesk.protocol.BridgeProtocol.AudioRoute.SPLIT_BY_APP,
        )
        _dockVisibleFlow.value = p.getBoolean(KEY_DOCK_VISIBLE, true)
        _dockOpacityFlow.value = DockAppearancePolicy.clampOpacity(
            p.getFloat(KEY_DOCK_OPACITY, DockAppearancePolicy.DEFAULT_OPACITY),
        )
        _dockScaleFlow.value = DockAppearancePolicy.clampScale(
            p.getFloat(KEY_DOCK_SCALE, DockAppearancePolicy.DEFAULT_SCALE),
        )
        _globalShortcutsEnabledFlow.value = p.getBoolean(KEY_GLOBAL_SHORTCUTS_ENABLED, true)
        _disabledShortcutIdsFlow.value = io.github.lengsukq.opaldesk.ui.WorkstationShortcutCatalog.parseDisabledIds(
            p.getString(KEY_DISABLED_SHORTCUT_IDS, null),
        )
        _showDesktopGuideIconFlow.value = p.getBoolean(KEY_SHOW_DESKTOP_GUIDE_ICON, true)
    }

    private fun loadPkgList(p: android.content.SharedPreferences, key: String, fallbackKey: String): List<String> {
        val raw = p.getString(key, null) ?: p.getString(fallbackKey, null)
        if (raw == null) return DEFAULT_PINNED
        if (raw.isBlank()) return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun isAutoRootEnabled(ctx: Context?): Boolean =
        prefs(ctx)?.getBoolean(KEY_AUTO_ROOT, false) ?: false

    fun setAutoRootEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_ROOT, enabled)?.apply()
    }

    fun isAutoShizukuEnabled(ctx: Context?): Boolean =
        prefs(ctx)?.getBoolean(KEY_AUTO_SHIZUKU, true) ?: true

    fun setAutoShizukuEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_SHIZUKU, enabled)?.apply()
    }

    fun isRootEnhanceEnabled(ctx: Context?): Boolean =
        prefs(ctx)?.getBoolean(KEY_ROOT_ENHANCE, false) ?: false

    fun setRootEnhanceEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_ROOT_ENHANCE, enabled)?.apply()
    }

    fun isAutoExternalDisplayEnabled(ctx: Context?): Boolean =
        prefs(ctx)?.getBoolean(KEY_AUTO_EXT_DISPLAY, true) ?: true

    fun setAutoExternalDisplayEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_EXT_DISPLAY, enabled)?.apply()
    }

    fun isAutoShowPhoneTrackpadEnabled(ctx: Context?): Boolean {
        hydrateFlows(ctx)
        return _autoShowPhoneTrackpadFlow.value
    }

    fun setAutoShowPhoneTrackpadEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_SHOW_PHONE_TRACKPAD, enabled)?.apply()
        _autoShowPhoneTrackpadFlow.value = enabled
    }

    fun isAutoEnterDexEnabled(ctx: Context?): Boolean {
        hydrateFlows(ctx)
        return _autoEnterDexFlow.value
    }

    fun setAutoEnterDexEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_ENTER_DEX, enabled)?.apply()
        _autoEnterDexFlow.value = enabled
    }

    fun getExternalProjectionMode(ctx: Context?): Int {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getInt(KEY_EXTERNAL_PROJECTION_MODE, PROJECTION_MODE_AUTO) ?: PROJECTION_MODE_AUTO)
            .coerceIn(PROJECTION_MODE_AUTO, PROJECTION_MODE_PRESENTATION)
    }

    fun setExternalProjectionMode(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(PROJECTION_MODE_AUTO, PROJECTION_MODE_PRESENTATION)
        prefs(ctx)?.edit()?.putInt(KEY_EXTERNAL_PROJECTION_MODE, clamped)?.apply()
        _externalProjectionModeFlow.value = clamped
    }

    fun getExternalOverscanPct(ctx: Context?): Float {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getFloat(KEY_EXTERNAL_OVERSCAN_PCT, DEFAULT_OVERSCAN_PCT) ?: DEFAULT_OVERSCAN_PCT)
            .coerceIn(MIN_OVERSCAN_PCT, MAX_OVERSCAN_PCT)
    }

    fun setExternalOverscanPct(ctx: Context?, pct: Float) {
        val clamped = pct.coerceIn(MIN_OVERSCAN_PCT, MAX_OVERSCAN_PCT)
        prefs(ctx)?.edit()?.putFloat(KEY_EXTERNAL_OVERSCAN_PCT, clamped)?.apply()
        _externalOverscanPctFlow.value = clamped
    }

    fun isRemoteMicEnabled(ctx: Context?): Boolean {
        hydrateFlows(ctx)
        return _remoteMicEnabledFlow.value
    }

    fun setRemoteMicEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_REMOTE_MIC_ENABLED, enabled)?.apply()
        _remoteMicEnabledFlow.value = enabled
    }

    fun isRemoteCameraEnabled(ctx: Context?): Boolean {
        hydrateFlows(ctx)
        return _remoteCameraEnabledFlow.value
    }

    fun setRemoteCameraEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_REMOTE_CAMERA_ENABLED, enabled)?.apply()
        _remoteCameraEnabledFlow.value = enabled
    }

    fun getAudioRouteMode(ctx: Context?): Int {
        hydrateFlows(ctx)
        return _audioRouteModeFlow.value
    }

    fun setAudioRouteMode(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(
            io.github.lengsukq.opaldesk.protocol.BridgeProtocol.AudioRoute.ALL_TO_DEX,
            io.github.lengsukq.opaldesk.protocol.BridgeProtocol.AudioRoute.SPLIT_BY_APP,
        )
        prefs(ctx)?.edit()?.putInt(KEY_AUDIO_ROUTE_MODE, clamped)?.apply()
        _audioRouteModeFlow.value = clamped
    }

    fun getDesktopShortcuts(ctx: Context?): List<String> {
        hydrateFlows(ctx)
        return _desktopShortcutsFlow.value
    }

    /** Adds or removes [pkg] on the desktop shortcut grid; returns true if now present on desktop. */
    fun toggleDesktopShortcut(ctx: Context?, pkg: String): Boolean {
        hydrateFlows(ctx)
        val current = _desktopShortcutsFlow.value.toMutableList()
        val added = if (current.contains(pkg)) {
            current.remove(pkg)
            false
        } else {
            current.add(pkg)
            true
        }
        prefs(ctx)?.edit()?.putString(KEY_DESKTOP_SHORTCUTS, current.joinToString(","))?.apply()
        _desktopShortcutsFlow.value = current
        return added
    }

    fun getDockPinnedPackages(ctx: Context?): List<String> {
        hydrateFlows(ctx)
        return _dockPinnedFlow.value
    }

    fun isDockVisible(ctx: Context?): Boolean {
        hydrateFlows(ctx)
        return _dockVisibleFlow.value
    }

    fun setDockVisible(ctx: Context?, visible: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_DOCK_VISIBLE, visible)?.apply()
        _dockVisibleFlow.value = visible
    }

    fun getDockOpacity(ctx: Context?): Float {
        hydrateFlows(ctx)
        return _dockOpacityFlow.value
    }

    fun setDockOpacity(ctx: Context?, opacity: Float) {
        val clamped = DockAppearancePolicy.clampOpacity(opacity)
        prefs(ctx)?.edit()?.putFloat(KEY_DOCK_OPACITY, clamped)?.apply()
        _dockOpacityFlow.value = clamped
    }

    fun getDockScale(ctx: Context?): Float {
        hydrateFlows(ctx)
        return _dockScaleFlow.value
    }

    fun setDockScale(ctx: Context?, scale: Float) {
        val clamped = DockAppearancePolicy.clampScale(scale)
        prefs(ctx)?.edit()?.putFloat(KEY_DOCK_SCALE, clamped)?.apply()
        _dockScaleFlow.value = clamped
    }

    fun resetDockAppearance(ctx: Context?) {
        prefs(ctx)?.edit()
            ?.putBoolean(KEY_DOCK_VISIBLE, true)
            ?.putFloat(KEY_DOCK_OPACITY, DockAppearancePolicy.DEFAULT_OPACITY)
            ?.putFloat(KEY_DOCK_SCALE, DockAppearancePolicy.DEFAULT_SCALE)
            ?.apply()
        _dockVisibleFlow.value = true
        _dockOpacityFlow.value = DockAppearancePolicy.DEFAULT_OPACITY
        _dockScaleFlow.value = DockAppearancePolicy.DEFAULT_SCALE
    }

    enum class DockPinResult { ADDED, REMOVED, LIMIT_REACHED }

    /**
     * Toggles [pkg] in the bottom Dock pinned strip, strictly enforcing [MAX_DOCK_PINNED].
     */
    fun toggleDockPin(ctx: Context?, pkg: String): DockPinResult {
        hydrateFlows(ctx)
        val current = _dockPinnedFlow.value.toMutableList()
        if (current.contains(pkg)) {
            current.remove(pkg)
            prefs(ctx)?.edit()?.putString(KEY_DOCK_PINNED, current.joinToString(","))?.apply()
            _dockPinnedFlow.value = current
            return DockPinResult.REMOVED
        }
        if (current.size >= MAX_DOCK_PINNED) {
            return DockPinResult.LIMIT_REACHED
        }
        current.add(pkg)
        prefs(ctx)?.edit()?.putString(KEY_DOCK_PINNED, current.joinToString(","))?.apply()
        _dockPinnedFlow.value = current
        return DockPinResult.ADDED
    }

    fun setCustomWallpaperPath(ctx: Context?, path: String) {
        val clean = path.trim()
        prefs(ctx)?.edit()?.putString(KEY_CUSTOM_WALLPAPER_PATH, clean)?.apply()
        _customWallpaperPathFlow.value = clean
    }

    fun clearCustomWallpaper(ctx: Context?) {
        setCustomWallpaperPath(ctx, "")
    }

    fun getThemeMode(ctx: Context?): Int {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getInt(KEY_THEME_MODE, THEME_LIGHT) ?: THEME_LIGHT).coerceIn(THEME_LIGHT, THEME_DARK)
    }

    fun setThemeMode(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(THEME_LIGHT, THEME_DARK)
        prefs(ctx)?.edit()?.putInt(KEY_THEME_MODE, clamped)?.apply()
        _themeModeFlow.value = clamped
    }

    fun getWallpaperPreset(ctx: Context?): Int {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getInt(KEY_WALLPAPER_PRESET, 0) ?: 0).coerceIn(0, 3)
    }

    fun setWallpaperPreset(ctx: Context?, preset: Int) {
        val clamped = preset.coerceIn(0, 3)
        prefs(ctx)?.edit()?.putInt(KEY_WALLPAPER_PRESET, clamped)?.apply()
        _wallpaperPresetFlow.value = clamped
    }

    fun getExternalUiScale(ctx: Context?): Float {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getFloat(KEY_EXTERNAL_UI_SCALE, 1.25f) ?: 1.25f).coerceIn(0.85f, 2.0f)
    }

    fun setExternalUiScale(ctx: Context?, scale: Float) {
        val clamped = scale.coerceIn(0.85f, 2.0f)
        prefs(ctx)?.edit()?.putFloat(KEY_EXTERNAL_UI_SCALE, clamped)?.apply()
        _externalUiScaleFlow.value = clamped
    }

    fun getTrackpadSensitivity(ctx: Context?): Float {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getFloat(KEY_TRACKPAD_SENSITIVITY, DEFAULT_TRACKPAD_SENSITIVITY)
            ?: DEFAULT_TRACKPAD_SENSITIVITY).coerceIn(MIN_TRACKPAD_SENSITIVITY, MAX_TRACKPAD_SENSITIVITY)
    }

    fun setTrackpadSensitivity(ctx: Context?, sensitivity: Float) {
        val clamped = sensitivity.coerceIn(MIN_TRACKPAD_SENSITIVITY, MAX_TRACKPAD_SENSITIVITY)
        prefs(ctx)?.edit()?.putFloat(KEY_TRACKPAD_SENSITIVITY, clamped)?.apply()
        _trackpadSensitivityFlow.value = clamped
    }

    fun getMouseTarget(ctx: Context?): Int {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getInt(KEY_MOUSE_TARGET, MOUSE_TARGET_EXTERNAL) ?: MOUSE_TARGET_EXTERNAL)
            .coerceIn(MOUSE_TARGET_LOCAL, MOUSE_TARGET_EXTERNAL)
    }

    fun setMouseTarget(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(MOUSE_TARGET_LOCAL, MOUSE_TARGET_EXTERNAL)
        prefs(ctx)?.edit()?.putInt(KEY_MOUSE_TARGET, clamped)?.apply()
        _mouseTargetFlow.value = clamped
    }

    fun getOrCreatePeerClientId(ctx: Context?): String {
        val p = prefs(ctx) ?: return "opal-android"
        val existing = p.getString("peer_client_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android-${java.util.UUID.randomUUID().toString().take(12)}"
        p.edit().putString("peer_client_id", created).apply()
        return created
    }

    fun getPeerAuthToken(ctx: Context?, host: String): String =
        prefs(ctx)?.getString("peer_tok_${host.trim()}", "").orEmpty()

    fun savePeerAuthToken(ctx: Context?, host: String, token: String) {
        if (host.isBlank() || token.isBlank()) return
        prefs(ctx)?.edit()?.putString("peer_tok_${host.trim()}", token)?.apply()
    }

    fun clearPeerAuthToken(ctx: Context?, host: String) {
        if (host.isBlank()) return
        prefs(ctx)?.edit()?.remove("peer_tok_${host.trim()}")?.apply()
    }

    fun isSortAppsByUsageEnabled(ctx: Context?): Boolean {
        hydrateFlows(ctx)
        return _sortAppsByUsageFlow.value
    }

    fun setSortAppsByUsage(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_SORT_APPS_BY_USAGE, enabled)?.apply()
        _sortAppsByUsageFlow.value = enabled
    }

    fun getAppLaunchCounts(ctx: Context?): Map<String, Int> {
        hydrateFlows(ctx)
        return _appLaunchCountsFlow.value
    }

    fun recordAppLaunch(ctx: Context?, pkg: String): Int {
        val clean = pkg.trim()
        if (clean.isEmpty()) return 0
        hydrateFlows(ctx)
        val next = LinkedHashMap(_appLaunchCountsFlow.value)
        val updated = (next[clean] ?: 0) + 1
        next[clean] = updated
        _appLaunchCountsFlow.value = next
        prefs(ctx)?.edit()?.putString(KEY_APP_LAUNCH_COUNTS, serializeLaunchCounts(next))?.apply()
        return updated
    }

    fun parseLaunchCounts(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        raw.lineSequence().forEach { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim().toIntOrNull() ?: return@forEach
            if (k.isNotEmpty() && v > 0) out[k] = v
        }
        return out
    }

    fun serializeLaunchCounts(counts: Map<String, Int>): String =
        counts.entries
            .filter { it.key.isNotBlank() && it.value > 0 }
            .joinToString("\n") { "${it.key.trim()}=${it.value}" }

    fun isAutoCheckUpdateEnabled(ctx: Context?): Boolean {
        hydrateFlows(ctx)
        return _autoCheckUpdateFlow.value
    }

    fun setAutoCheckUpdate(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_CHECK_UPDATE, enabled)?.apply()
        _autoCheckUpdateFlow.value = enabled
    }

    fun getLastUpdateCheckMs(ctx: Context?): Long =
        prefs(ctx)?.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L) ?: 0L

    fun setLastUpdateCheckMs(ctx: Context?, timestampMs: Long) {
        prefs(ctx)?.edit()?.putLong(KEY_LAST_UPDATE_CHECK_MS, timestampMs)?.apply()
    }

    fun getIgnoredUpdateTag(ctx: Context?): String =
        prefs(ctx)?.getString(KEY_IGNORED_UPDATE_TAG, "").orEmpty()

    fun setIgnoredUpdateTag(ctx: Context?, tagName: String) {
        prefs(ctx)?.edit()?.putString(KEY_IGNORED_UPDATE_TAG, tagName.trim())?.apply()
    }

    fun getUpdateDownloadRoute(ctx: Context?): Int {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getInt(KEY_UPDATE_DOWNLOAD_ROUTE, UPDATE_ROUTE_DIRECT) ?: UPDATE_ROUTE_DIRECT)
            .coerceIn(UPDATE_ROUTE_DIRECT, UPDATE_ROUTE_PROXY_2)
    }

    fun setUpdateDownloadRoute(ctx: Context?, route: Int) {
        val clamped = route.coerceIn(UPDATE_ROUTE_DIRECT, UPDATE_ROUTE_PROXY_2)
        prefs(ctx)?.edit()?.putInt(KEY_UPDATE_DOWNLOAD_ROUTE, clamped)?.apply()
        _updateDownloadRouteFlow.value = clamped
    }

    fun getOrCreateInstallationId(ctx: Context?): String {
        val p = prefs(ctx) ?: return UUID.randomUUID().toString()
        val existing = p.getString(KEY_INSTALLATION_ID, null)?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val created = UUID.randomUUID().toString()
        p.edit().putString(KEY_INSTALLATION_ID, created).apply()
        return created
    }

    fun getFeedbackUsername(ctx: Context?): String =
        prefs(ctx)?.getString(KEY_FEEDBACK_USERNAME, "").orEmpty()

    fun setFeedbackUsername(ctx: Context?, username: String) {
        prefs(ctx)?.edit()?.putString(KEY_FEEDBACK_USERNAME, username.trim().take(64))?.apply()
    }

    fun isGlobalShortcutsEnabled(ctx: Context?): Boolean {
        hydrateFlows(ctx)
        return _globalShortcutsEnabledFlow.value
    }

    fun setGlobalShortcutsEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_GLOBAL_SHORTCUTS_ENABLED, enabled)?.apply()
        _globalShortcutsEnabledFlow.value = enabled
    }

    fun getDisabledShortcutIds(ctx: Context?): Set<String> {
        hydrateFlows(ctx)
        return _disabledShortcutIdsFlow.value
    }

    fun setShortcutEnabled(
        ctx: Context?,
        shortcutId: io.github.lengsukq.opaldesk.ui.WorkstationShortcutId,
        enabled: Boolean,
    ) {
        hydrateFlows(ctx)
        val next = _disabledShortcutIdsFlow.value.toMutableSet()
        if (enabled) {
            next.remove(shortcutId.prefKey)
        } else {
            next.add(shortcutId.prefKey)
        }
        val serialized = io.github.lengsukq.opaldesk.ui.WorkstationShortcutCatalog.serializeDisabledIds(next)
        prefs(ctx)?.edit()?.putString(KEY_DISABLED_SHORTCUT_IDS, serialized)?.apply()
        _disabledShortcutIdsFlow.value = next
    }

    fun resetKeyboardShortcuts(ctx: Context?) {
        prefs(ctx)?.edit()
            ?.putBoolean(KEY_GLOBAL_SHORTCUTS_ENABLED, true)
            ?.remove(KEY_DISABLED_SHORTCUT_IDS)
            ?.apply()
        _globalShortcutsEnabledFlow.value = true
        _disabledShortcutIdsFlow.value = emptySet()
    }

    fun isDesktopGuideIconVisible(ctx: Context?): Boolean {
        hydrateFlows(ctx)
        return _showDesktopGuideIconFlow.value
    }

    fun setDesktopGuideIconVisible(ctx: Context?, visible: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_SHOW_DESKTOP_GUIDE_ICON, visible)?.apply()
        _showDesktopGuideIconFlow.value = visible
    }

    fun createFeedbackQuotaStorage(ctx: Context): FeedbackQuotaStorage {
        val appCtx = ctx.applicationContext
        return object : FeedbackQuotaStorage {
            override fun getOrCreateInstallationId(): String =
                this@AppPrefs.getOrCreateInstallationId(appCtx)

            override fun getLastReportDate(): String =
                prefs(appCtx)?.getString(KEY_FEEDBACK_REPORT_DATE, "").orEmpty()

            override fun getDailyReportCount(): Int =
                prefs(appCtx)?.getInt(KEY_FEEDBACK_REPORT_COUNT, 0) ?: 0

            override fun saveDailyReportState(dateYyyyMmDd: String, count: Int) {
                prefs(appCtx)?.edit()
                    ?.putString(KEY_FEEDBACK_REPORT_DATE, dateYyyyMmDd)
                    ?.putInt(KEY_FEEDBACK_REPORT_COUNT, count)
                    ?.apply()
            }
        }
    }
}
