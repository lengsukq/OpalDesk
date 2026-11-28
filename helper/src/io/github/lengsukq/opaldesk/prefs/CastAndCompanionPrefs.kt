package io.github.lengsukq.opaldesk.prefs

import android.content.Context
import android.content.SharedPreferences
import io.github.lengsukq.opaldesk.diagnostics.FeedbackQuotaStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Activation/boot, casting/audio peripherals, companion peer authentication,
 * app usage sorting, update routing, feedback quota, and language preference store.
 */
internal object CastAndCompanionPrefs {
    private const val KEY_AUTO_ROOT = "auto_root"
    private const val KEY_AUTO_SHIZUKU = "auto_shizuku"
    private const val KEY_ROOT_ENHANCE = "root_enhance_optional"
    private const val KEY_AUTO_BLACKOUT_ON_CAST = "auto_blackout_on_cast_v1"
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
    private const val KEY_FORCE_PHONE_SPEAKER = "force_phone_speaker_v1"
    private const val KEY_USB_TETHER_CAST_ENABLED = "usb_tether_cast_enabled_v1"
    private const val KEY_CALL_CONTROL_SYNC_ENABLED = "call_control_sync_enabled_v1"
    private const val KEY_BLE_NEARBY_ENABLED = "ble_nearby_enabled_v1"
    private const val KEY_LANGUAGE_PREF = "app_language_pref_v1"
    private const val KEY_CAST_BITRATE_KBPS = "cast_bitrate_kbps_v1"

    val CAST_BITRATE_PRESETS_KBPS: IntArray = intArrayOf(0, 8_000, 16_000, 24_000, 36_000, 50_000, 80_000, 100_000)

    private val _sortAppsByUsageFlow = MutableStateFlow(true)
    val sortAppsByUsageFlow: StateFlow<Boolean> = _sortAppsByUsageFlow.asStateFlow()

    private val _appLaunchCountsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    val appLaunchCountsFlow: StateFlow<Map<String, Int>> = _appLaunchCountsFlow.asStateFlow()

    private val _autoCheckUpdateFlow = MutableStateFlow(true)
    val autoCheckUpdateFlow: StateFlow<Boolean> = _autoCheckUpdateFlow.asStateFlow()

    private val _autoBlackoutOnCastFlow = MutableStateFlow(false)
    val autoBlackoutOnCastFlow: StateFlow<Boolean> = _autoBlackoutOnCastFlow.asStateFlow()

    private val _updateDownloadRouteFlow = MutableStateFlow(AppPrefs.UPDATE_ROUTE_DIRECT)
    val updateDownloadRouteFlow: StateFlow<Int> = _updateDownloadRouteFlow.asStateFlow()

    private val _remoteMicEnabledFlow = MutableStateFlow(false)
    val remoteMicEnabledFlow: StateFlow<Boolean> = _remoteMicEnabledFlow.asStateFlow()

    private val _remoteCameraEnabledFlow = MutableStateFlow(false)
    val remoteCameraEnabledFlow: StateFlow<Boolean> = _remoteCameraEnabledFlow.asStateFlow()

    private val _audioRouteModeFlow = MutableStateFlow(BridgeProtocol.AudioRoute.SPLIT_BY_APP)
    val audioRouteModeFlow: StateFlow<Int> = _audioRouteModeFlow.asStateFlow()

    private val _forcePhoneSpeakerFlow = MutableStateFlow(false)
    val forcePhoneSpeakerFlow: StateFlow<Boolean> = _forcePhoneSpeakerFlow.asStateFlow()

    private val _usbTetherCastEnabledFlow = MutableStateFlow(false)
    val usbTetherCastEnabledFlow: StateFlow<Boolean> = _usbTetherCastEnabledFlow.asStateFlow()

    private val _callControlSyncEnabledFlow = MutableStateFlow(false)
    val callControlSyncEnabledFlow: StateFlow<Boolean> = _callControlSyncEnabledFlow.asStateFlow()

    private val _bleNearbyEnabledFlow = MutableStateFlow(false)
    val bleNearbyEnabledFlow: StateFlow<Boolean> = _bleNearbyEnabledFlow.asStateFlow()

    private val _languagePrefFlow = MutableStateFlow(OpalLocalePolicy.LANG_SYSTEM)
    val languagePrefFlow: StateFlow<String> = _languagePrefFlow.asStateFlow()

    private val _castBitrateKbpsFlow = MutableStateFlow(0)
    val castBitrateKbpsFlow: StateFlow<Int> = _castBitrateKbpsFlow.asStateFlow()

    fun hydrate(p: SharedPreferences) {
        _sortAppsByUsageFlow.value = p.getBoolean(KEY_SORT_APPS_BY_USAGE, true)
        _appLaunchCountsFlow.value = parseLaunchCounts(p.getString(KEY_APP_LAUNCH_COUNTS, null))
        _autoCheckUpdateFlow.value = p.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
        _autoBlackoutOnCastFlow.value = p.getBoolean(KEY_AUTO_BLACKOUT_ON_CAST, false)
        _updateDownloadRouteFlow.value = p.getInt(KEY_UPDATE_DOWNLOAD_ROUTE, AppPrefs.UPDATE_ROUTE_DIRECT)
            .coerceIn(AppPrefs.UPDATE_ROUTE_DIRECT, AppPrefs.UPDATE_ROUTE_PROXY_2)
        _remoteMicEnabledFlow.value = p.getBoolean(KEY_REMOTE_MIC_ENABLED, false)
        _remoteCameraEnabledFlow.value = p.getBoolean(KEY_REMOTE_CAMERA_ENABLED, false)
        _audioRouteModeFlow.value = p.getInt(
            KEY_AUDIO_ROUTE_MODE,
            BridgeProtocol.AudioRoute.SPLIT_BY_APP,
        ).coerceIn(
            BridgeProtocol.AudioRoute.ALL_TO_DEX,
            BridgeProtocol.AudioRoute.SPLIT_BY_APP,
        )
        _forcePhoneSpeakerFlow.value = p.getBoolean(KEY_FORCE_PHONE_SPEAKER, false)
        _usbTetherCastEnabledFlow.value = p.getBoolean(KEY_USB_TETHER_CAST_ENABLED, false)
        _callControlSyncEnabledFlow.value = p.getBoolean(KEY_CALL_CONTROL_SYNC_ENABLED, false)
        _bleNearbyEnabledFlow.value = p.getBoolean(KEY_BLE_NEARBY_ENABLED, false)
        _languagePrefFlow.value = OpalLocalePolicy.normalizeLanguagePref(
            p.getString(KEY_LANGUAGE_PREF, OpalLocalePolicy.LANG_SYSTEM),
        )
        _castBitrateKbpsFlow.value = normalizeCastBitrateKbps(p.getInt(KEY_CAST_BITRATE_KBPS, 0))
    }

    fun normalizeCastBitrateKbps(raw: Int): Int =
        if (raw <= 0) 0 else CAST_BITRATE_PRESETS_KBPS.firstOrNull { it == raw } ?: raw.coerceIn(2_500, 100_000)

    fun formatBitrateLabel(kbps: Int): String =
        if (kbps <= 0) "自动" else "${kbps / 1000} Mbps"

    fun getCastBitrateKbps(ctx: Context?): Int {
        AppPrefs.hydrateFlows(ctx)
        return _castBitrateKbpsFlow.value
    }

    fun setCastBitrateKbps(ctx: Context?, kbps: Int) {
        val normalized = normalizeCastBitrateKbps(kbps)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putInt(KEY_CAST_BITRATE_KBPS, normalized)?.apply()
        _castBitrateKbpsFlow.value = normalized
    }

    fun isAutoRootEnabled(ctx: Context?): Boolean =
        AppPrefs.sharedPrefs(ctx)?.getBoolean(KEY_AUTO_ROOT, false) ?: false

    fun setAutoRootEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_AUTO_ROOT, enabled)?.apply()
    }

    fun isAutoShizukuEnabled(ctx: Context?): Boolean =
        AppPrefs.sharedPrefs(ctx)?.getBoolean(KEY_AUTO_SHIZUKU, true) ?: true

    fun setAutoShizukuEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_AUTO_SHIZUKU, enabled)?.apply()
    }

    fun isRootEnhanceEnabled(ctx: Context?): Boolean =
        AppPrefs.sharedPrefs(ctx)?.getBoolean(KEY_ROOT_ENHANCE, false) ?: false

    fun setRootEnhanceEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_ROOT_ENHANCE, enabled)?.apply()
    }

    fun isRemoteMicEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _remoteMicEnabledFlow.value
    }

    fun setRemoteMicEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_REMOTE_MIC_ENABLED, enabled)?.apply()
        _remoteMicEnabledFlow.value = enabled
    }

    fun isRemoteCameraEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _remoteCameraEnabledFlow.value
    }

    fun setRemoteCameraEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_REMOTE_CAMERA_ENABLED, enabled)?.apply()
        _remoteCameraEnabledFlow.value = enabled
    }

    fun getAudioRouteMode(ctx: Context?): Int {
        AppPrefs.hydrateFlows(ctx)
        return _audioRouteModeFlow.value
    }

    fun setAudioRouteMode(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(
            BridgeProtocol.AudioRoute.ALL_TO_DEX,
            BridgeProtocol.AudioRoute.SPLIT_BY_APP,
        )
        AppPrefs.sharedPrefs(ctx)?.edit()?.putInt(KEY_AUDIO_ROUTE_MODE, clamped)?.apply()
        _audioRouteModeFlow.value = clamped
    }

    fun isForcePhoneSpeakerEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _forcePhoneSpeakerFlow.value
    }

    fun setForcePhoneSpeakerEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_FORCE_PHONE_SPEAKER, enabled)?.apply()
        _forcePhoneSpeakerFlow.value = enabled
    }

    fun isAutoBlackoutOnCast(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _autoBlackoutOnCastFlow.value
    }

    fun setAutoBlackoutOnCast(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_AUTO_BLACKOUT_ON_CAST, enabled)?.apply()
        _autoBlackoutOnCastFlow.value = enabled
    }

    fun getOrCreatePeerClientId(ctx: Context?): String {
        val p = AppPrefs.sharedPrefs(ctx) ?: return "opal-android"
        val existing = p.getString("peer_client_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android-${UUID.randomUUID().toString().take(12)}"
        p.edit().putString("peer_client_id", created).apply()
        return created
    }

    fun getPeerAuthToken(ctx: Context?, host: String): String =
        AppPrefs.sharedPrefs(ctx)?.getString("peer_tok_${host.trim()}", "").orEmpty()

    fun savePeerAuthToken(ctx: Context?, host: String, token: String) {
        if (host.isBlank() || token.isBlank()) return
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString("peer_tok_${host.trim()}", token)?.apply()
    }

    fun clearPeerAuthToken(ctx: Context?, host: String) {
        if (host.isBlank()) return
        AppPrefs.sharedPrefs(ctx)?.edit()?.remove("peer_tok_${host.trim()}")?.apply()
    }

    fun isSortAppsByUsageEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _sortAppsByUsageFlow.value
    }

    fun setSortAppsByUsage(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_SORT_APPS_BY_USAGE, enabled)?.apply()
        _sortAppsByUsageFlow.value = enabled
    }

    fun getAppLaunchCounts(ctx: Context?): Map<String, Int> {
        AppPrefs.hydrateFlows(ctx)
        return _appLaunchCountsFlow.value
    }

    fun recordAppLaunch(ctx: Context?, pkg: String): Int {
        val clean = pkg.trim()
        if (clean.isEmpty()) return 0
        AppPrefs.hydrateFlows(ctx)
        val next = LinkedHashMap(_appLaunchCountsFlow.value)
        val updated = (next[clean] ?: 0) + 1
        next[clean] = updated
        _appLaunchCountsFlow.value = next
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_APP_LAUNCH_COUNTS, serializeLaunchCounts(next))?.apply()
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
        AppPrefs.hydrateFlows(ctx)
        return _autoCheckUpdateFlow.value
    }

    fun setAutoCheckUpdate(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_AUTO_CHECK_UPDATE, enabled)?.apply()
        _autoCheckUpdateFlow.value = enabled
    }

    fun getLastUpdateCheckMs(ctx: Context?): Long =
        AppPrefs.sharedPrefs(ctx)?.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L) ?: 0L

    fun setLastUpdateCheckMs(ctx: Context?, timestampMs: Long) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putLong(KEY_LAST_UPDATE_CHECK_MS, timestampMs)?.apply()
    }

    fun getIgnoredUpdateTag(ctx: Context?): String =
        AppPrefs.sharedPrefs(ctx)?.getString(KEY_IGNORED_UPDATE_TAG, "").orEmpty()

    fun setIgnoredUpdateTag(ctx: Context?, tagName: String) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_IGNORED_UPDATE_TAG, tagName.trim())?.apply()
    }

    fun getUpdateDownloadRoute(ctx: Context?): Int {
        AppPrefs.hydrateFlows(ctx)
        return (AppPrefs.sharedPrefs(ctx)?.getInt(KEY_UPDATE_DOWNLOAD_ROUTE, AppPrefs.UPDATE_ROUTE_DIRECT)
            ?: AppPrefs.UPDATE_ROUTE_DIRECT)
            .coerceIn(AppPrefs.UPDATE_ROUTE_DIRECT, AppPrefs.UPDATE_ROUTE_PROXY_2)
    }

    fun setUpdateDownloadRoute(ctx: Context?, route: Int) {
        val clamped = route.coerceIn(AppPrefs.UPDATE_ROUTE_DIRECT, AppPrefs.UPDATE_ROUTE_PROXY_2)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putInt(KEY_UPDATE_DOWNLOAD_ROUTE, clamped)?.apply()
        _updateDownloadRouteFlow.value = clamped
    }

    fun getOrCreateInstallationId(ctx: Context?): String {
        val p = AppPrefs.sharedPrefs(ctx) ?: return UUID.randomUUID().toString()
        val existing = p.getString(KEY_INSTALLATION_ID, null)?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val created = UUID.randomUUID().toString()
        p.edit().putString(KEY_INSTALLATION_ID, created).apply()
        return created
    }

    fun getFeedbackUsername(ctx: Context?): String =
        AppPrefs.sharedPrefs(ctx)?.getString(KEY_FEEDBACK_USERNAME, "").orEmpty()

    fun setFeedbackUsername(ctx: Context?, username: String) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_FEEDBACK_USERNAME, username.trim().take(64))?.apply()
    }

    fun isUsbTetherCastEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _usbTetherCastEnabledFlow.value
    }

    fun setUsbTetherCastEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_USB_TETHER_CAST_ENABLED, enabled)?.apply()
        _usbTetherCastEnabledFlow.value = enabled
    }

    fun isCallControlSyncEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _callControlSyncEnabledFlow.value
    }

    fun setCallControlSyncEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_CALL_CONTROL_SYNC_ENABLED, enabled)?.apply()
        _callControlSyncEnabledFlow.value = enabled
    }

    fun isBleNearbyEnabled(ctx: Context?): Boolean {
        AppPrefs.hydrateFlows(ctx)
        return _bleNearbyEnabledFlow.value
    }

    fun setBleNearbyEnabled(ctx: Context?, enabled: Boolean) {
        AppPrefs.sharedPrefs(ctx)?.edit()?.putBoolean(KEY_BLE_NEARBY_ENABLED, enabled)?.apply()
        _bleNearbyEnabledFlow.value = enabled
    }

    fun getLanguagePref(ctx: Context?): String {
        val raw = AppPrefs.sharedPrefs(ctx)?.getString(KEY_LANGUAGE_PREF, OpalLocalePolicy.LANG_SYSTEM)
        val normalized = OpalLocalePolicy.normalizeLanguagePref(raw)
        _languagePrefFlow.value = normalized
        return normalized
    }

    fun setLanguagePref(ctx: Context?, pref: String) {
        val normalized = OpalLocalePolicy.normalizeLanguagePref(pref)
        AppPrefs.sharedPrefs(ctx)?.edit()?.putString(KEY_LANGUAGE_PREF, normalized)?.apply()
        _languagePrefFlow.value = normalized
    }

    fun createFeedbackQuotaStorage(ctx: Context): FeedbackQuotaStorage {
        val appCtx = ctx.applicationContext
        return object : FeedbackQuotaStorage {
            override fun getOrCreateInstallationId(): String =
                this@CastAndCompanionPrefs.getOrCreateInstallationId(appCtx)

            override fun getLastReportDate(): String =
                AppPrefs.sharedPrefs(appCtx)?.getString(KEY_FEEDBACK_REPORT_DATE, "").orEmpty()

            override fun getDailyReportCount(): Int =
                AppPrefs.sharedPrefs(appCtx)?.getInt(KEY_FEEDBACK_REPORT_COUNT, 0) ?: 0

            override fun saveDailyReportState(dateYyyyMmDd: String, count: Int) {
                AppPrefs.sharedPrefs(appCtx)?.edit()
                    ?.putString(KEY_FEEDBACK_REPORT_DATE, dateYyyyMmDd)
                    ?.putInt(KEY_FEEDBACK_REPORT_COUNT, count)
                    ?.apply()
            }
        }
    }
}
