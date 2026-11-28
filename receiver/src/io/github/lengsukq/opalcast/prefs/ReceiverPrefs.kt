package io.github.lengsukq.opalcast.prefs

import android.content.Context
import android.content.SharedPreferences
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import java.util.UUID

/**
 * Lightweight SharedPreferences persistence for OpalCast receiver settings, per-host
 * permanent auth tokens, recent endpoints, and remote app usage sorting.
 */
object ReceiverPrefs {
    private const val PREF_NAME = "opalcast_receiver_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_LANGUAGE_PREF = "language_pref"
    private const val KEY_VIDEO_CODEC = "video_codec_pref"
    private const val KEY_USB_TETHER_BETA = "usb_tether_beta"
    private const val KEY_CLIENT_ID = "receiver_client_id"
    private const val KEY_RECENT_PEERS = "recent_peers"
    private const val PREFIX_TOKEN = "auth_token_"
    private const val PREFIX_APP_COUNT = "app_launch_cnt_"
    private const val MAX_RECENT_PEERS = 6

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    data class RecentPeer(
        val host: String,
        val port: Int,
        val label: String,
    )

    private fun prefs(ctx: Context): SharedPreferences =
        (ctx.applicationContext ?: ctx).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_THEME_MODE, THEME_SYSTEM).coerceIn(THEME_SYSTEM, THEME_DARK)

    fun setThemeMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_THEME_MODE, mode.coerceIn(THEME_SYSTEM, THEME_DARK)).apply()
    }

    fun getLanguagePref(ctx: Context): String =
        ReceiverLocalePolicy.normalizeLanguagePref(
            prefs(ctx).getString(KEY_LANGUAGE_PREF, ReceiverLocalePolicy.LANG_SYSTEM),
        )

    fun setLanguagePref(ctx: Context, pref: String) {
        prefs(ctx).edit().putString(KEY_LANGUAGE_PREF, ReceiverLocalePolicy.normalizeLanguagePref(pref)).apply()
    }

    fun getVideoCodecPref(ctx: Context): Int =
        ReceiverPolicy.normalizeCodecPreference(prefs(ctx).getInt(KEY_VIDEO_CODEC, ReceiverPolicy.CODEC_PREF_AUTO))

    fun setVideoCodecPref(ctx: Context, pref: Int) {
        prefs(ctx).edit().putInt(KEY_VIDEO_CODEC, ReceiverPolicy.normalizeCodecPreference(pref)).apply()
    }

    fun isUsbTetherEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_USB_TETHER_BETA, false)

    fun setUsbTetherEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_USB_TETHER_BETA, enabled).apply()
    }

    fun getOrCreateClientId(ctx: Context): String {
        val sp = prefs(ctx)
        val existing = sp.getString(KEY_CLIENT_ID, "").orEmpty()
        if (existing.isNotBlank()) return existing
        val created = "opalcast-" + UUID.randomUUID().toString().replace("-", "").take(16)
        sp.edit().putString(KEY_CLIENT_ID, created).apply()
        return created
    }

    fun getPeerAuthToken(ctx: Context, host: String): String {
        val clean = host.trim()
        if (clean.isEmpty()) return ""
        return prefs(ctx).getString(PREFIX_TOKEN + clean, "").orEmpty()
    }

    fun savePeerAuthToken(ctx: Context, host: String, token: String) {
        val clean = host.trim()
        if (clean.isEmpty() || token.isBlank()) return
        prefs(ctx).edit().putString(PREFIX_TOKEN + clean, token.trim()).apply()
    }

    fun clearPeerAuthToken(ctx: Context, host: String) {
        val clean = host.trim()
        if (clean.isEmpty()) return
        prefs(ctx).edit().remove(PREFIX_TOKEN + clean).apply()
    }

    fun clearAllPeerAuthTokens(ctx: Context): Int {
        val sp = prefs(ctx)
        val editor = sp.edit()
        var count = 0
        for (k in sp.all.keys) {
            if (k.startsWith(PREFIX_TOKEN)) {
                editor.remove(k)
                count++
            }
        }
        editor.apply()
        return count
    }

    fun getRecentPeers(ctx: Context): List<RecentPeer> {
        val raw = prefs(ctx).getString(KEY_RECENT_PEERS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<RecentPeer>()
        for (line in raw.lineSequence()) {
            val parts = line.split('|')
            if (parts.size < 2) continue
            val host = parts[0].trim()
            val port = parts[1].trim().toIntOrNull() ?: ReceiverPolicy.DEFAULT_CAST_PORT
            val label = parts.getOrNull(2)?.trim().orEmpty().ifBlank { host }
            if (host.isNotEmpty() && port in 1..65535) {
                out.add(RecentPeer(host, port, label))
            }
        }
        return out
    }

    fun recordRecentPeer(ctx: Context, host: String, port: Int, label: String) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return
        val safeLabel = label.trim().replace("|", "").replace("\n", "").ifBlank { cleanHost }
        val existing = getRecentPeers(ctx).filterNot { it.host == cleanHost && it.port == port }
        val merged = (listOf(RecentPeer(cleanHost, port.coerceIn(1, 65535), safeLabel)) + existing).take(MAX_RECENT_PEERS)
        val serialized = merged.joinToString("\n") { "${it.host}|${it.port}|${it.label}" }
        prefs(ctx).edit().putString(KEY_RECENT_PEERS, serialized).apply()
    }

    fun recordAppLaunch(ctx: Context, pkg: String) {
        val clean = pkg.trim()
        if (clean.isEmpty()) return
        val sp = prefs(ctx)
        val next = sp.getInt(PREFIX_APP_COUNT + clean, 0) + 1
        sp.edit().putInt(PREFIX_APP_COUNT + clean, next).apply()
    }

    fun getAppLaunchCount(ctx: Context, pkg: String): Int =
        prefs(ctx).getInt(PREFIX_APP_COUNT + pkg.trim(), 0)
}
