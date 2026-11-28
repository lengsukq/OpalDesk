package io.github.lengsukq.opalcast.prefs

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Single Source of Truth (SSOT) for OpalCast (`:receiver`) locale resolution and API 21–37
 * runtime context wrapping.
 *
 * Follows `docs/I18N_SPEC.md`:
 * - Supported locales: English (`en`, canonical default/fallback) and Simplified Chinese (`zh-CN`).
 * - Default preference: `system` (follow OS locale automatically).
 * - Deterministic rule: any `zh*` tag resolves to `zh-CN`; everything else resolves to `en`.
 *
 * Pure functions ([normalizeLanguagePref], [resolveEffectiveLanguageTag], [toLocale]) have zero
 * Android runtime dependencies so they are deterministically JVM-tested in
 * `ReceiverLocalePolicyTest`.
 */
object ReceiverLocalePolicy {
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
            val hasChinese = systemLanguageTags.any { tag ->
                val normalized = tag.trim().lowercase(Locale.ROOT).replace('_', '-')
                normalized == "zh" || normalized.startsWith("zh-")
            }
            if (hasChinese) LANG_ZH_CN else LANG_EN
        }
    }

    fun toLocale(languageTag: String): Locale =
        if (languageTag == LANG_ZH_CN) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH

    @Suppress("DEPRECATION")
    fun readSystemLanguageTags(): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
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
        } else {
            val sysLocale = runCatching { Resources.getSystem().configuration.locale }.getOrNull()
            if (sysLocale != null) {
                val tag = sysLocale.toLanguageTag()
                if (tag.isNotBlank()) return listOf(tag)
            }
        }
        return listOf(Locale.getDefault().toLanguageTag())
    }

    /**
     * Wraps a base [Context] (typically in `attachBaseContext`) with the resolved locale
     * configuration via [Context.createConfigurationContext] so API 21–37 Activities and
     * `FloatingCastService` cleanly apply both system-default and manual language overrides.
     */
    fun wrapContext(base: Context): Context {
        val pref = ReceiverPrefs.getLanguagePref(base)
        val effectiveTag = resolveEffectiveLanguageTag(pref, readSystemLanguageTags())
        val targetLocale = toLocale(effectiveTag)
        Locale.setDefault(targetLocale)
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(targetLocale))
        }
        config.setLocale(targetLocale)
        return base.createConfigurationContext(config)
    }
}
