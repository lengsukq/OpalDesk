package io.github.lengsukq.opaldesk.prefs

import android.content.Context
import android.content.SharedPreferences
import io.github.lengsukq.opaldesk.diagnostics.FeedbackQuotaStorage
import io.github.lengsukq.opaldesk.external.CustomResolutionSpec
import kotlinx.coroutines.flow.StateFlow

/**
 * SharedPreferences store + live reactive flows for OpalDesk preferences:
 * appearance theme (Light Frosted Glass / Follow System / Dark), wallpapers, pinned apps,
 * external monitor UI scaling, stepless trackpad pointer speed, and activation preferences.
 *
 * Aggregates [DisplayAndInputPrefs] and [CastAndCompanionPrefs] behind a single
 * backward-compatible facade.
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
    const val MAX_SPOTLIGHT_RECENTS = 5

    private const val PREFS_NAME = "opaldesk_v2_prefs"

    @Volatile
    private var flowsHydrated = false

    val themeModeFlow: StateFlow<Int> = DisplayAndInputPrefs.themeModeFlow
    val wallpaperPresetFlow: StateFlow<Int> = DisplayAndInputPrefs.wallpaperPresetFlow
    val trackpadSensitivityFlow: StateFlow<Float> = DisplayAndInputPrefs.trackpadSensitivityFlow
    val externalUiScaleFlow: StateFlow<Float> = DisplayAndInputPrefs.externalUiScaleFlow
    val mouseTargetFlow: StateFlow<Int> = DisplayAndInputPrefs.mouseTargetFlow
    val desktopShortcutsFlow: StateFlow<List<String>> = DisplayAndInputPrefs.desktopShortcutsFlow
    val dockPinnedFlow: StateFlow<List<String>> = DisplayAndInputPrefs.dockPinnedFlow
    val customWallpaperPathFlow: StateFlow<String> = DisplayAndInputPrefs.customWallpaperPathFlow
    val sortAppsByUsageFlow: StateFlow<Boolean> = CastAndCompanionPrefs.sortAppsByUsageFlow
    val appLaunchCountsFlow: StateFlow<Map<String, Int>> = CastAndCompanionPrefs.appLaunchCountsFlow
    val autoCheckUpdateFlow: StateFlow<Boolean> = CastAndCompanionPrefs.autoCheckUpdateFlow
    val autoShowPhoneTrackpadFlow: StateFlow<Boolean> = DisplayAndInputPrefs.autoShowPhoneTrackpadFlow
    val autoBlackoutOnCastFlow: StateFlow<Boolean> = CastAndCompanionPrefs.autoBlackoutOnCastFlow
    val autoEnterDexFlow: StateFlow<Boolean> = DisplayAndInputPrefs.autoEnterDexFlow
    val externalProjectionModeFlow: StateFlow<Int> = DisplayAndInputPrefs.externalProjectionModeFlow
    val externalOverscanPctFlow: StateFlow<Float> = DisplayAndInputPrefs.externalOverscanPctFlow
    val updateDownloadRouteFlow: StateFlow<Int> = CastAndCompanionPrefs.updateDownloadRouteFlow
    val remoteMicEnabledFlow: StateFlow<Boolean> = CastAndCompanionPrefs.remoteMicEnabledFlow
    val remoteCameraEnabledFlow: StateFlow<Boolean> = CastAndCompanionPrefs.remoteCameraEnabledFlow
    val audioRouteModeFlow: StateFlow<Int> = CastAndCompanionPrefs.audioRouteModeFlow
    val dockVisibleFlow: StateFlow<Boolean> = DisplayAndInputPrefs.dockVisibleFlow
    val dockOpacityFlow: StateFlow<Float> = DisplayAndInputPrefs.dockOpacityFlow
    val dockScaleFlow: StateFlow<Float> = DisplayAndInputPrefs.dockScaleFlow
    val globalShortcutsEnabledFlow: StateFlow<Boolean> = DisplayAndInputPrefs.globalShortcutsEnabledFlow
    val disabledShortcutIdsFlow: StateFlow<Set<String>> = DisplayAndInputPrefs.disabledShortcutIdsFlow
    val showDesktopGuideIconFlow: StateFlow<Boolean> = DisplayAndInputPrefs.showDesktopGuideIconFlow
    val customResolutionPresetsFlow: StateFlow<List<CustomResolutionSpec>> = DisplayAndInputPrefs.customResolutionPresetsFlow
    val autoCustomResolutionEnabledFlow: StateFlow<Boolean> = DisplayAndInputPrefs.autoCustomResolutionEnabledFlow
    val externalNative1To1Flow: StateFlow<Boolean> = DisplayAndInputPrefs.externalNative1To1Flow
    val defaultCustomResolutionFlow: StateFlow<CustomResolutionSpec> = DisplayAndInputPrefs.defaultCustomResolutionFlow
    val usbTetherCastEnabledFlow: StateFlow<Boolean> = CastAndCompanionPrefs.usbTetherCastEnabledFlow
    val dexImeDisplayModeFlow: StateFlow<Int> = DisplayAndInputPrefs.dexImeDisplayModeFlow
    val dexLockedImeIdFlow: StateFlow<String> = DisplayAndInputPrefs.dexLockedImeIdFlow
    val muteHostImeOnCastFlow: StateFlow<Boolean> = DisplayAndInputPrefs.muteHostImeOnCastFlow
    val hardKbdSoftImeModeFlow: StateFlow<Int> = DisplayAndInputPrefs.hardKbdSoftImeModeFlow
    val autoBridgeImeOnCastFlow: StateFlow<Boolean> = DisplayAndInputPrefs.autoBridgeImeOnCastFlow
    val spotlightRecentsFlow: StateFlow<List<String>> = DisplayAndInputPrefs.spotlightRecentsFlow
    val callControlSyncEnabledFlow: StateFlow<Boolean> = CastAndCompanionPrefs.callControlSyncEnabledFlow
    val bleNearbyEnabledFlow: StateFlow<Boolean> = CastAndCompanionPrefs.bleNearbyEnabledFlow
    val languagePrefFlow: StateFlow<String> = CastAndCompanionPrefs.languagePrefFlow

    internal fun sharedPrefs(ctx: Context?): SharedPreferences? =
        (ctx?.applicationContext ?: ctx)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hydrateFlows(ctx: Context?) {
        if (flowsHydrated || ctx == null) return
        flowsHydrated = true
        val p = sharedPrefs(ctx) ?: return
        DisplayAndInputPrefs.hydrate(p)
        CastAndCompanionPrefs.hydrate(p)
    }

    fun isAutoRootEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isAutoRootEnabled(ctx)

    fun setAutoRootEnabled(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setAutoRootEnabled(ctx, enabled)

    fun isAutoShizukuEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isAutoShizukuEnabled(ctx)

    fun setAutoShizukuEnabled(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setAutoShizukuEnabled(ctx, enabled)

    fun isRootEnhanceEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isRootEnhanceEnabled(ctx)

    fun setRootEnhanceEnabled(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setRootEnhanceEnabled(ctx, enabled)

    fun isAutoExternalDisplayEnabled(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isAutoExternalDisplayEnabled(ctx)

    fun setAutoExternalDisplayEnabled(ctx: Context?, enabled: Boolean) =
        DisplayAndInputPrefs.setAutoExternalDisplayEnabled(ctx, enabled)

    fun isAutoShowPhoneTrackpadEnabled(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isAutoShowPhoneTrackpadEnabled(ctx)

    fun setAutoShowPhoneTrackpadEnabled(ctx: Context?, enabled: Boolean) =
        DisplayAndInputPrefs.setAutoShowPhoneTrackpadEnabled(ctx, enabled)

    fun isAutoEnterDexEnabled(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isAutoEnterDexEnabled(ctx)

    fun setAutoEnterDexEnabled(ctx: Context?, enabled: Boolean) =
        DisplayAndInputPrefs.setAutoEnterDexEnabled(ctx, enabled)

    fun getExternalProjectionMode(ctx: Context?): Int =
        DisplayAndInputPrefs.getExternalProjectionMode(ctx)

    fun setExternalProjectionMode(ctx: Context?, mode: Int) =
        DisplayAndInputPrefs.setExternalProjectionMode(ctx, mode)

    fun getExternalOverscanPct(ctx: Context?): Float =
        DisplayAndInputPrefs.getExternalOverscanPct(ctx)

    fun setExternalOverscanPct(ctx: Context?, pct: Float) =
        DisplayAndInputPrefs.setExternalOverscanPct(ctx, pct)

    fun isRemoteMicEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isRemoteMicEnabled(ctx)

    fun setRemoteMicEnabled(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setRemoteMicEnabled(ctx, enabled)

    fun isRemoteCameraEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isRemoteCameraEnabled(ctx)

    fun setRemoteCameraEnabled(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setRemoteCameraEnabled(ctx, enabled)

    fun getAudioRouteMode(ctx: Context?): Int =
        CastAndCompanionPrefs.getAudioRouteMode(ctx)

    fun setAudioRouteMode(ctx: Context?, mode: Int) =
        CastAndCompanionPrefs.setAudioRouteMode(ctx, mode)

    fun getDesktopShortcuts(ctx: Context?): List<String> =
        DisplayAndInputPrefs.getDesktopShortcuts(ctx)

    /** Adds or removes [pkg] on the desktop shortcut grid; returns true if now present on desktop. */
    fun toggleDesktopShortcut(ctx: Context?, pkg: String): Boolean =
        DisplayAndInputPrefs.toggleDesktopShortcut(ctx, pkg)

    fun getDockPinnedPackages(ctx: Context?): List<String> =
        DisplayAndInputPrefs.getDockPinnedPackages(ctx)

    fun isDockVisible(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isDockVisible(ctx)

    fun setDockVisible(ctx: Context?, visible: Boolean) =
        DisplayAndInputPrefs.setDockVisible(ctx, visible)

    fun getDockOpacity(ctx: Context?): Float =
        DisplayAndInputPrefs.getDockOpacity(ctx)

    fun setDockOpacity(ctx: Context?, opacity: Float) =
        DisplayAndInputPrefs.setDockOpacity(ctx, opacity)

    fun getDockScale(ctx: Context?): Float =
        DisplayAndInputPrefs.getDockScale(ctx)

    fun setDockScale(ctx: Context?, scale: Float) =
        DisplayAndInputPrefs.setDockScale(ctx, scale)

    fun resetDockAppearance(ctx: Context?) =
        DisplayAndInputPrefs.resetDockAppearance(ctx)

    enum class DockPinResult { ADDED, REMOVED, LIMIT_REACHED }

    /**
     * Toggles [pkg] in the bottom Dock pinned strip, strictly enforcing [MAX_DOCK_PINNED].
     */
    fun toggleDockPin(ctx: Context?, pkg: String): DockPinResult =
        DisplayAndInputPrefs.toggleDockPin(ctx, pkg)

    fun setCustomWallpaperPath(ctx: Context?, path: String) =
        DisplayAndInputPrefs.setCustomWallpaperPath(ctx, path)

    fun isAutoBlackoutOnCast(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isAutoBlackoutOnCast(ctx)

    fun setAutoBlackoutOnCast(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setAutoBlackoutOnCast(ctx, enabled)

    fun clearCustomWallpaper(ctx: Context?) =
        DisplayAndInputPrefs.clearCustomWallpaper(ctx)

    fun getThemeMode(ctx: Context?): Int =
        DisplayAndInputPrefs.getThemeMode(ctx)

    fun setThemeMode(ctx: Context?, mode: Int) =
        DisplayAndInputPrefs.setThemeMode(ctx, mode)

    fun getWallpaperPreset(ctx: Context?): Int =
        DisplayAndInputPrefs.getWallpaperPreset(ctx)

    fun setWallpaperPreset(ctx: Context?, preset: Int) =
        DisplayAndInputPrefs.setWallpaperPreset(ctx, preset)

    fun getExternalUiScale(ctx: Context?): Float =
        DisplayAndInputPrefs.getExternalUiScale(ctx)

    fun setExternalUiScale(ctx: Context?, scale: Float) =
        DisplayAndInputPrefs.setExternalUiScale(ctx, scale)

    fun getTrackpadSensitivity(ctx: Context?): Float =
        DisplayAndInputPrefs.getTrackpadSensitivity(ctx)

    fun setTrackpadSensitivity(ctx: Context?, sensitivity: Float) =
        DisplayAndInputPrefs.setTrackpadSensitivity(ctx, sensitivity)

    fun getMouseTarget(ctx: Context?): Int =
        DisplayAndInputPrefs.getMouseTarget(ctx)

    fun setMouseTarget(ctx: Context?, mode: Int) =
        DisplayAndInputPrefs.setMouseTarget(ctx, mode)

    fun getOrCreatePeerClientId(ctx: Context?): String =
        CastAndCompanionPrefs.getOrCreatePeerClientId(ctx)

    fun getPeerAuthToken(ctx: Context?, host: String): String =
        CastAndCompanionPrefs.getPeerAuthToken(ctx, host)

    fun savePeerAuthToken(ctx: Context?, host: String, token: String) =
        CastAndCompanionPrefs.savePeerAuthToken(ctx, host, token)

    fun clearPeerAuthToken(ctx: Context?, host: String) =
        CastAndCompanionPrefs.clearPeerAuthToken(ctx, host)

    fun isSortAppsByUsageEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isSortAppsByUsageEnabled(ctx)

    fun setSortAppsByUsage(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setSortAppsByUsage(ctx, enabled)

    fun getAppLaunchCounts(ctx: Context?): Map<String, Int> =
        CastAndCompanionPrefs.getAppLaunchCounts(ctx)

    fun recordAppLaunch(ctx: Context?, pkg: String): Int =
        CastAndCompanionPrefs.recordAppLaunch(ctx, pkg)

    fun parseLaunchCounts(raw: String?): Map<String, Int> =
        CastAndCompanionPrefs.parseLaunchCounts(raw)

    fun serializeLaunchCounts(counts: Map<String, Int>): String =
        CastAndCompanionPrefs.serializeLaunchCounts(counts)

    fun isAutoCheckUpdateEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isAutoCheckUpdateEnabled(ctx)

    fun setAutoCheckUpdate(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setAutoCheckUpdate(ctx, enabled)

    fun getLastUpdateCheckMs(ctx: Context?): Long =
        CastAndCompanionPrefs.getLastUpdateCheckMs(ctx)

    fun setLastUpdateCheckMs(ctx: Context?, timestampMs: Long) =
        CastAndCompanionPrefs.setLastUpdateCheckMs(ctx, timestampMs)

    fun getIgnoredUpdateTag(ctx: Context?): String =
        CastAndCompanionPrefs.getIgnoredUpdateTag(ctx)

    fun setIgnoredUpdateTag(ctx: Context?, tagName: String) =
        CastAndCompanionPrefs.setIgnoredUpdateTag(ctx, tagName)

    fun getUpdateDownloadRoute(ctx: Context?): Int =
        CastAndCompanionPrefs.getUpdateDownloadRoute(ctx)

    fun setUpdateDownloadRoute(ctx: Context?, route: Int) =
        CastAndCompanionPrefs.setUpdateDownloadRoute(ctx, route)

    fun getOrCreateInstallationId(ctx: Context?): String =
        CastAndCompanionPrefs.getOrCreateInstallationId(ctx)

    fun getFeedbackUsername(ctx: Context?): String =
        CastAndCompanionPrefs.getFeedbackUsername(ctx)

    fun setFeedbackUsername(ctx: Context?, username: String) =
        CastAndCompanionPrefs.setFeedbackUsername(ctx, username)

    fun isGlobalShortcutsEnabled(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isGlobalShortcutsEnabled(ctx)

    fun setGlobalShortcutsEnabled(ctx: Context?, enabled: Boolean) =
        DisplayAndInputPrefs.setGlobalShortcutsEnabled(ctx, enabled)

    fun getDisabledShortcutIds(ctx: Context?): Set<String> =
        DisplayAndInputPrefs.getDisabledShortcutIds(ctx)

    fun setShortcutEnabled(
        ctx: Context?,
        shortcutId: io.github.lengsukq.opaldesk.ui.WorkstationShortcutId,
        enabled: Boolean,
    ) = DisplayAndInputPrefs.setShortcutEnabled(ctx, shortcutId, enabled)

    fun resetKeyboardShortcuts(ctx: Context?) =
        DisplayAndInputPrefs.resetKeyboardShortcuts(ctx)

    fun isDesktopGuideIconVisible(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isDesktopGuideIconVisible(ctx)

    fun setDesktopGuideIconVisible(ctx: Context?, visible: Boolean) =
        DisplayAndInputPrefs.setDesktopGuideIconVisible(ctx, visible)

    fun getCustomResolutionPresets(ctx: Context?): List<CustomResolutionSpec> =
        DisplayAndInputPrefs.getCustomResolutionPresets(ctx)

    fun addCustomResolutionPreset(ctx: Context?, spec: CustomResolutionSpec) =
        DisplayAndInputPrefs.addCustomResolutionPreset(ctx, spec)

    fun removeCustomResolutionPreset(ctx: Context?, key: String) =
        DisplayAndInputPrefs.removeCustomResolutionPreset(ctx, key)

    fun isAutoCustomResolutionEnabled(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isAutoCustomResolutionEnabled(ctx)

    fun setAutoCustomResolutionEnabled(ctx: Context?, enabled: Boolean) =
        DisplayAndInputPrefs.setAutoCustomResolutionEnabled(ctx, enabled)

    fun isExternalNative1To1Enabled(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isExternalNative1To1Enabled(ctx)

    fun setExternalNative1To1Enabled(ctx: Context?, enabled: Boolean) =
        DisplayAndInputPrefs.setExternalNative1To1Enabled(ctx, enabled)

    fun getDefaultCustomResolution(ctx: Context?): CustomResolutionSpec =
        DisplayAndInputPrefs.getDefaultCustomResolution(ctx)

    fun setDefaultCustomResolution(ctx: Context?, spec: CustomResolutionSpec) =
        DisplayAndInputPrefs.setDefaultCustomResolution(ctx, spec)

    fun isUsbTetherCastEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isUsbTetherCastEnabled(ctx)

    fun setUsbTetherCastEnabled(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setUsbTetherCastEnabled(ctx, enabled)

    fun getDexImeDisplayMode(ctx: Context?): Int =
        DisplayAndInputPrefs.getDexImeDisplayMode(ctx)

    fun setDexImeDisplayMode(ctx: Context?, mode: Int) =
        DisplayAndInputPrefs.setDexImeDisplayMode(ctx, mode)

    fun getDexLockedImeId(ctx: Context?): String =
        DisplayAndInputPrefs.getDexLockedImeId(ctx)

    fun setDexLockedImeId(ctx: Context?, imeId: String) =
        DisplayAndInputPrefs.setDexLockedImeId(ctx, imeId)

    fun isMuteHostImeOnCastEnabled(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isMuteHostImeOnCastEnabled(ctx)

    fun setMuteHostImeOnCastEnabled(ctx: Context?, enabled: Boolean) =
        DisplayAndInputPrefs.setMuteHostImeOnCastEnabled(ctx, enabled)

    fun getHardKbdSoftImeMode(ctx: Context?): Int =
        DisplayAndInputPrefs.getHardKbdSoftImeMode(ctx)

    fun setHardKbdSoftImeMode(ctx: Context?, mode: Int) =
        DisplayAndInputPrefs.setHardKbdSoftImeMode(ctx, mode)

    fun isAutoBridgeImeOnCastEnabled(ctx: Context?): Boolean =
        DisplayAndInputPrefs.isAutoBridgeImeOnCastEnabled(ctx)

    fun setAutoBridgeImeOnCastEnabled(ctx: Context?, enabled: Boolean) =
        DisplayAndInputPrefs.setAutoBridgeImeOnCastEnabled(ctx, enabled)

    fun isCallControlSyncEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isCallControlSyncEnabled(ctx)

    fun setCallControlSyncEnabled(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setCallControlSyncEnabled(ctx, enabled)

    fun isBleNearbyEnabled(ctx: Context?): Boolean =
        CastAndCompanionPrefs.isBleNearbyEnabled(ctx)

    fun setBleNearbyEnabled(ctx: Context?, enabled: Boolean) =
        CastAndCompanionPrefs.setBleNearbyEnabled(ctx, enabled)

    fun getLanguagePref(ctx: Context?): String =
        CastAndCompanionPrefs.getLanguagePref(ctx)

    fun setLanguagePref(ctx: Context?, pref: String) =
        CastAndCompanionPrefs.setLanguagePref(ctx, pref)

    fun getSpotlightRecents(ctx: Context?): List<String> =
        DisplayAndInputPrefs.getSpotlightRecents(ctx)

    fun recordSpotlightRecent(ctx: Context?, itemId: String): List<String> =
        DisplayAndInputPrefs.recordSpotlightRecent(ctx, itemId)

    fun parseSpotlightRecents(raw: String?, maxSize: Int = MAX_SPOTLIGHT_RECENTS): List<String> =
        DisplayAndInputPrefs.parseSpotlightRecents(raw, maxSize)

    fun updateSpotlightRecents(
        existing: List<String>,
        itemId: String,
        maxSize: Int = MAX_SPOTLIGHT_RECENTS,
    ): List<String> = DisplayAndInputPrefs.updateSpotlightRecents(existing, itemId, maxSize)

    fun createFeedbackQuotaStorage(ctx: Context): FeedbackQuotaStorage =
        CastAndCompanionPrefs.createFeedbackQuotaStorage(ctx)
}
