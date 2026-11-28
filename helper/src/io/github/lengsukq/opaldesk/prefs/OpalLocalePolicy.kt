package io.github.lengsukq.opaldesk.prefs

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Single Source of Truth (SSOT) for OpalDesk `:helper` locale resolution and runtime switching.
 *
 * Follows `docs/I18N_SPEC.md`:
 * - Supported locales: English (`en`, canonical default/fallback) and Simplified Chinese (`zh-CN`).
 * - Default preference: `system` (follow OS locale automatically).
 * - Deterministic rule: any `zh*` tag resolves to `zh-CN`; everything else resolves to `en`.
 *
 * Pure functions ([normalizeLanguagePref], [resolveEffectiveLanguageTag], [toLocale]) have zero
 * Android runtime dependencies so they are deterministically JVM-tested in `OpalLocalePolicyTest`.
 */
object OpalLocalePolicy {
    const val LANG_SYSTEM = "system"
    const val LANG_ZH_CN = "zh-CN"
    const val LANG_EN = "en"

    /**
     * Normalizes a persisted or user-supplied language preference into one of
     * [LANG_SYSTEM], [LANG_ZH_CN], or [LANG_EN].
     */
    fun normalizeLanguagePref(raw: String?): String {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty()) return LANG_SYSTEM
        val lower = cleaned.lowercase(Locale.ROOT).replace('_', '-')
        return when {
            lower == LANG_SYSTEM -> LANG_SYSTEM
            lower == "zh" || lower == "zh-cn" || lower == "zh-hans" || lower.startsWith("zh-hans-") -> LANG_ZH_CN
            lower == "en" || lower.startsWith("en-") -> LANG_EN
            else -> LANG_SYSTEM
        }
    }

    /**
     * Resolves the effective language tag ([LANG_ZH_CN] or [LANG_EN]) from the user's explicit
     * preference and the OS language tag list.
     *
     * - Explicit `zh` / `zh-CN` / `zh-Hans` -> `zh-CN`
     * - Explicit `en` / `en-*` -> `en`
     * - `system` or unrecognized -> `zh-CN` if any system tag starts with `zh` (case-insensitive),
     *   otherwise `en`.
     */
    fun resolveEffectiveLanguageTag(
        pref: String?,
        systemLanguageTags: List<String>,
    ): String = when (normalizeLanguagePref(pref)) {
        LANG_ZH_CN -> LANG_ZH_CN
        LANG_EN -> LANG_EN
        else -> {
            val primary = systemLanguageTags
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.lowercase(Locale.ROOT)
                ?.replace('_', '-')
                .orEmpty()
            if (primary == "zh" || primary.startsWith("zh-")) LANG_ZH_CN else LANG_EN
        }
    }

    fun toLocale(languageTag: String): Locale =
        if (normalizeLanguagePref(languageTag) == LANG_ZH_CN) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH

    fun readSystemLanguageTags(): List<String> {
        val localeList = runCatching { Resources.getSystem().configuration.locales }.getOrNull()
            ?.takeIf { !it.isEmpty }
            ?: runCatching { LocaleList.getDefault() }.getOrNull()
        if (localeList != null && !localeList.isEmpty) {
            val tags = ArrayList<String>(localeList.size())
            for (i in 0 until localeList.size()) {
                val loc = localeList.get(i) ?: continue
                val tag = loc.toLanguageTag()
                if (tag.isNotBlank()) tags.add(tag)
            }
            if (tags.isNotEmpty()) return tags
        }
        return listOf(Locale.getDefault().toLanguageTag())
    }

    /**
     * Wraps a base [Context] (typically in `attachBaseContext`) with the resolved locale
     * configuration so resources (`R.string.*`) load in English or Simplified Chinese on
     * Android 11–12 and remain aligned with `LocaleManager` on Android 13+.
     */
    fun wrapContext(base: Context): Context {
        val pref = AppPrefs.getLanguagePref(base)
        val effectiveTag = resolveEffectiveLanguageTag(pref, readSystemLanguageTags())
        val targetLocale = toLocale(effectiveTag)
        val currentLocale = runCatching {
            base.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)
        }.getOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            currentLocale?.language.equals(targetLocale.language, ignoreCase = true)
        ) {
            return base
        }
        Locale.setDefault(targetLocale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(targetLocale))
        return base.createConfigurationContext(config)
    }

    /**
     * Persists [pref] and applies the new runtime locale immediately.
     * Uses Android 13+ [LocaleManager] when available; falls back to updating default locale and
     * recreating the current [Activity] on Android 11–12.
     */
    fun applyRuntimeLocale(context: Context, pref: String) {
        val normalized = normalizeLanguagePref(pref)
        AppPrefs.setLanguagePref(context, normalized)
        val effectiveTag = resolveEffectiveLanguageTag(normalized, readSystemLanguageTags())
        Locale.setDefault(toLocale(effectiveTag))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val appliedViaManager = runCatching {
                val manager = context.getSystemService(LocaleManager::class.java) ?: return@runCatching false
                manager.applicationLocales = when (normalized) {
                    LANG_ZH_CN -> LocaleList.forLanguageTags(LANG_ZH_CN)
                    LANG_EN -> LocaleList.forLanguageTags(LANG_EN)
                    else -> LocaleList.getEmptyLocaleList()
                }
                true
            }.getOrDefault(false)
            if (appliedViaManager) return
        }

        findActivity(context)?.recreate()
    }

    private fun findActivity(context: Context): Activity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return cur as? Activity
    }
}
