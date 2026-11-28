import Foundation
import os

/// User-selectable display language preference persisted in `PreferencesStore`.
public enum AppLanguage: String, CaseIterable, Identifiable, Sendable {
    case system = "system"
    case zhHans = "zh-Hans"
    case en = "en"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .system: return OpalL10n.text("lang.system")
        case .zhHans: return "简体中文"
        case .en: return "English"
        }
    }
}

/// Concrete resolved locale supported by OpalDesk (`en` canonical default or `zh-Hans`).
public enum ResolvedLanguage: String, CaseIterable, Sendable {
    case en = "en"
    case zhHans = "zh-Hans"

    public var lprojName: String { rawValue }

    public var locale: Locale {
        Locale(identifier: rawValue)
    }
}

/// Single Source of Truth for OpalDesk macOS localization (Swift 6 strict-concurrency safe).
public enum OpalL10n {
    public static let resourceBundleName = "OpalDeskMac_OpalDeskCore"

    private static let preferenceLock = OSAllocatedUnfairLock<AppLanguage>(initialState: .system)

    /// Active language preference mirrored from `PreferencesStore` for non-SwiftUI callers and formatters.
    public static var currentPreference: AppLanguage {
        get { preferenceLock.withLock { $0 } }
        set { preferenceLock.withLock { $0 = newValue } }
    }

    /// Pure, deterministic locale resolver following `docs/I18N_SPEC.md`:
    /// - Explicit `.zhHans` -> `.zhHans`
    /// - Explicit `.en` -> `.en`
    /// - `.system` -> inspects primary preferred language tag; any `zh*` (case-insensitive) -> `.zhHans`, else `.en`.
    public static func resolveLanguage(
        preference: AppLanguage,
        preferredLanguages: [String] = Locale.preferredLanguages
    ) -> ResolvedLanguage {
        switch preference {
        case .zhHans:
            return .zhHans
        case .en:
            return .en
        case .system:
            let primary = preferredLanguages
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .first { !$0.isEmpty }
            guard let primary else { return .en }
            let normalized = primary.lowercased()
            if normalized == "zh" || normalized.hasPrefix("zh-") || normalized.hasPrefix("zh_") {
                return .zhHans
            }
            return .en
        }
    }

    /// Safe resource bundle lookup that works both in SwiftPM (`Bundle.module`) and inside a
    /// standalone packaged `OpalDesk.app/Contents/Resources/OpalDeskMac_OpalDeskCore.bundle`.
    public static var resourceBundle: Bundle {
        if let mainResources = Bundle.main.resourceURL {
            let packagedURL = mainResources.appendingPathComponent("\(resourceBundleName).bundle")
            if let packaged = Bundle(url: packagedURL) {
                return packaged
            }
        }
        let adjacentURL = Bundle.main.bundleURL.appendingPathComponent("\(resourceBundleName).bundle")
        if let adjacent = Bundle(url: adjacentURL) {
            return adjacent
        }
        return Bundle.module
    }

    /// Returns the `.lproj` bundle for `language` inside `baseBundle`, falling back to `en` and then `baseBundle`.
    /// Handles both Xcode (`zh-Hans.lproj`) and SwiftPM `.process("Resources")` lowercased (`zh-hans.lproj`) folders.
    public static func localizationBundle(
        for language: ResolvedLanguage,
        in baseBundle: Bundle = resourceBundle
    ) -> Bundle {
        if let matched = resolvedLprojBundle(for: language.lprojName, in: baseBundle) {
            return matched
        }
        if language != .en,
           let enBundle = resolvedLprojBundle(for: ResolvedLanguage.en.lprojName, in: baseBundle)
        {
            return enBundle
        }
        return baseBundle
    }

    private static func resolvedLprojBundle(for lprojName: String, in baseBundle: Bundle) -> Bundle? {
        let targetLower = lprojName.lowercased()
        var candidates: [String] = [lprojName]
        if targetLower != lprojName {
            candidates.append(targetLower)
        }
        for localization in baseBundle.localizations where localization.lowercased() == targetLower {
            if !candidates.contains(localization) {
                candidates.append(localization)
            }
        }
        for candidate in candidates {
            if let path = baseBundle.path(forResource: candidate, ofType: "lproj"),
               let bundle = Bundle(path: path)
            {
                return bundle
            }
        }
        return nil
    }

    /// Looks up a localized string for `key` using the specified `language` override or `currentPreference`.
    public static func text(_ key: String, language: AppLanguage? = nil) -> String {
        let resolved = resolveLanguage(preference: language ?? currentPreference)
        let bundle = localizationBundle(for: resolved)
        let missingSentinel = "__OPAL_L10N_MISSING__"
        let value = bundle.localizedString(forKey: key, value: missingSentinel, table: nil)
        if value != missingSentinel {
            return value
        }
        if resolved != .en {
            let fallbackBundle = localizationBundle(for: .en)
            let fallbackValue = fallbackBundle.localizedString(forKey: key, value: missingSentinel, table: nil)
            if fallbackValue != missingSentinel {
                return fallbackValue
            }
        }
        return key
    }

    /// Looks up a localized format string and formats it with `args` under the active language.
    public static func format(_ key: String, _ args: CVarArg...) -> String {
        formatWithArray(key, language: nil, arguments: args)
    }

    /// Looks up a localized format string and formats it with `args` under an explicit `language`.
    public static func format(_ key: String, language: AppLanguage?, _ args: CVarArg...) -> String {
        formatWithArray(key, language: language, arguments: args)
    }

    private static func formatWithArray(
        _ key: String,
        language: AppLanguage?,
        arguments: [CVarArg]
    ) -> String {
        let resolved = resolveLanguage(preference: language ?? currentPreference)
        let template = text(key, language: language)
        return String(format: template, locale: resolved.locale, arguments: arguments)
    }
}
