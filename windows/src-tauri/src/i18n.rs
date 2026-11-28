//! Deterministic locale resolution and localized system tray labels for OpalDesk Windows.
//! Follows `docs/I18N_SPEC.md`:
//! - Supported locales: English (`en`, canonical default/fallback) and Simplified Chinese (`zh-CN`).
//! - Default preference: `"system"` (any OS tag starting with `zh`, case-insensitive -> `zh-CN`; all others -> `en`).

pub const LANG_SYSTEM: &str = "system";
pub const LANG_ZH_CN: &str = "zh-CN";
pub const LANG_EN: &str = "en";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Locale {
    En,
    ZhCn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrayMenuLabels {
    pub show_main: &'static str,
    pub connect_companion: &'static str,
    pub open_files: &'static str,
    pub open_storage: &'static str,
    pub open_dialer: &'static str,
    pub quit_app: &'static str,
    pub idle_tooltip: &'static str,
}

const TRAY_LABELS_EN: TrayMenuLabels = TrayMenuLabels {
    show_main: "Show OpalDesk Console",
    connect_companion: "Connect Companion Mode",
    open_files: "Phone Files",
    open_storage: "Storage Analyzer",
    open_dialer: "Quick Dialer",
    quit_app: "Quit OpalDesk",
    idle_tooltip: "OpalDesk · Idle",
};

const TRAY_LABELS_ZH_CN: TrayMenuLabels = TrayMenuLabels {
    show_main: "显示 OpalDesk 控制台",
    connect_companion: "连接后台随行",
    open_files: "手机文件管理",
    open_storage: "存储空间分析",
    open_dialer: "快捷拨号",
    quit_app: "退出 OpalDesk",
    idle_tooltip: "OpalDesk · 待机",
};

/// Sanitizes a persisted or incoming language preference into `"system" | "zh-CN" | "en"`.
pub fn sanitize_language_pref(raw: &str) -> String {
    let cleaned = raw.trim();
    if cleaned.is_empty() {
        return LANG_SYSTEM.to_string();
    }
    let lower = cleaned.to_ascii_lowercase().replace('_', "-");
    if lower == LANG_SYSTEM {
        return LANG_SYSTEM.to_string();
    }
    if lower == "zh" || lower == "zh-cn" || lower == "zh-hans" || lower.starts_with("zh-hans-") {
        return LANG_ZH_CN.to_string();
    }
    if lower == "en" || lower.starts_with("en-") {
        return LANG_EN.to_string();
    }
    LANG_SYSTEM.to_string()
}

/// Resolves the effective [`Locale`] from a preference and OS language tag(s).
pub fn resolve_locale_from_tags(pref: &str, system_tags: &[&str]) -> Locale {
    match sanitize_language_pref(pref).as_str() {
        LANG_ZH_CN => Locale::ZhCn,
        LANG_EN => Locale::En,
        _ => {
            let has_chinese = system_tags.iter().any(|tag| {
                let norm = tag.trim().to_ascii_lowercase().replace('_', "-");
                norm == "zh" || norm.starts_with("zh-")
            });
            if has_chinese {
                Locale::ZhCn
            } else {
                Locale::En
            }
        }
    }
}

/// Detects the host OS language tag without extra third-party crates.
pub fn detect_os_language_tag() -> String {
    #[cfg(target_os = "windows")]
    {
        if let Some(tag) = detect_windows_user_locale() {
            return tag;
        }
    }
    for key in ["LC_ALL", "LC_MESSAGES", "LANG"] {
        if let Ok(val) = std::env::var(key) {
            let cleaned = val.split('.').next().unwrap_or("").trim();
            if !cleaned.is_empty() && cleaned != "C" && cleaned != "POSIX" {
                return cleaned.replace('_', "-");
            }
        }
    }
    String::new()
}

#[cfg(target_os = "windows")]
fn detect_windows_user_locale() -> Option<String> {
    #[link(name = "kernel32")]
    extern "system" {
        fn GetUserDefaultLocaleName(lp_locale_name: *mut u16, cch_locale_name: i32) -> i32;
    }

    const LOCALE_NAME_MAX_LENGTH: usize = 85;
    let mut buf = [0u16; LOCALE_NAME_MAX_LENGTH];
    let len = unsafe { GetUserDefaultLocaleName(buf.as_mut_ptr(), LOCALE_NAME_MAX_LENGTH as i32) };
    if len > 1 {
        let slice = &buf[..(len as usize - 1)];
        let tag = String::from_utf16_lossy(slice).trim().to_string();
        if !tag.is_empty() {
            return Some(tag);
        }
    }
    None
}

pub fn resolve_active_locale(pref: &str) -> Locale {
    let os_tag = detect_os_language_tag();
    if os_tag.is_empty() {
        resolve_locale_from_tags(pref, &[])
    } else {
        resolve_locale_from_tags(pref, &[os_tag.as_str()])
    }
}

pub fn tray_labels(locale: Locale) -> TrayMenuLabels {
    match locale {
        Locale::ZhCn => TRAY_LABELS_ZH_CN,
        Locale::En => TRAY_LABELS_EN,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitizes_language_preferences() {
        assert_eq!(sanitize_language_pref(""), "system");
        assert_eq!(sanitize_language_pref("system"), "system");
        assert_eq!(sanitize_language_pref("fr-FR"), "system");
        assert_eq!(sanitize_language_pref("zh"), "zh-CN");
        assert_eq!(sanitize_language_pref("zh-CN"), "zh-CN");
        assert_eq!(sanitize_language_pref("zh_Hans"), "zh-CN");
        assert_eq!(sanitize_language_pref("en"), "en");
        assert_eq!(sanitize_language_pref("EN-US"), "en");
    }

    #[test]
    fn resolves_locales_per_spec() {
        assert_eq!(resolve_locale_from_tags("zh-CN", &["en-US"]), Locale::ZhCn);
        assert_eq!(resolve_locale_from_tags("en", &["zh-CN"]), Locale::En);
        assert_eq!(resolve_locale_from_tags("system", &["zh-CN"]), Locale::ZhCn);
        assert_eq!(resolve_locale_from_tags("system", &["zh-TW"]), Locale::ZhCn);
        assert_eq!(resolve_locale_from_tags("system", &["zh_HK"]), Locale::ZhCn);
        assert_eq!(resolve_locale_from_tags("system", &["en-US"]), Locale::En);
        assert_eq!(resolve_locale_from_tags("system", &["ja-JP"]), Locale::En);
        assert_eq!(resolve_locale_from_tags("system", &[]), Locale::En);
    }

    #[test]
    fn provides_localized_tray_labels() {
        let en = tray_labels(Locale::En);
        assert_eq!(en.open_files, "Phone Files");
        assert_eq!(en.quit_app, "Quit OpalDesk");

        let zh = tray_labels(Locale::ZhCn);
        assert_eq!(zh.open_files, "手机文件管理");
        assert_eq!(zh.quit_app, "退出 OpalDesk");
    }
}
