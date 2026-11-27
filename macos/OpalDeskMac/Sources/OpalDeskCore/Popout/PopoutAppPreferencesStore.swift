import CoreGraphics
import Foundation
import os

// MARK: - Canonical Device Key Formatting (SSOT)

/// Single source of truth for per-device preference & shortcut keys (`adb:<serial>` / `fp:<16-hex>`).
public enum PopoutDeviceKey {
    public static let adbPrefix = "adb:"
    public static let fingerprintPrefix = "fp:"
    public static let fingerprintPrefixLength = 16

    public static func adb(_ serial: String) -> String {
        "\(adbPrefix)\(serial)"
    }

    public static func fingerprint(_ fullHex: String) -> String {
        "\(fingerprintPrefix)\(fullHex.prefix(fingerprintPrefixLength))"
    }

    public static func resolve(adbSerial: String? = nil, fingerprint: String? = nil, fallback: String = "") -> String {
        if let serial = adbSerial, !serial.isEmpty {
            return adb(serial)
        }
        if let fp = fingerprint, !fp.isEmpty {
            return Self.fingerprint(fp)
        }
        return fallback
    }
}

// MARK: - Per-App Popout Preferences Model

/// Persisted per-app preferences for standalone floating Android app windows (favorites, presets, DPI, compat resolution).
public struct PopoutAppPreference: Codable, Sendable, Equatable, Hashable {
    public var isFavorite: Bool
    public var favoriteOrder: Int
    /// `"phone" | "fold" | "tablet" | "custom"` (or `nil` for default adaptive sizing).
    public var preferredPreset: String?
    public var customWidthPt: Double?
    public var customHeightPt: Double?
    public var pinnedOnTop: Bool
    public var customDpi: Int?
    public var compatibilityResolution: Bool

    public init(
        isFavorite: Bool = false,
        favoriteOrder: Int = 0,
        preferredPreset: String? = nil,
        customWidthPt: Double? = nil,
        customHeightPt: Double? = nil,
        pinnedOnTop: Bool = false,
        customDpi: Int? = nil,
        compatibilityResolution: Bool = false
    ) {
        self.isFavorite = isFavorite
        self.favoriteOrder = favoriteOrder
        self.preferredPreset = preferredPreset
        self.customWidthPt = customWidthPt
        self.customHeightPt = customHeightPt
        self.pinnedOnTop = pinnedOnTop
        self.customDpi = customDpi
        self.compatibilityResolution = compatibilityResolution
    }

    private enum CodingKeys: String, CodingKey {
        case isFavorite
        case favoriteOrder
        case preferredPreset
        case customWidthPt
        case customHeightPt
        case pinnedOnTop
        case customDpi
        case compatibilityResolution
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        isFavorite = try c.decodeIfPresent(Bool.self, forKey: .isFavorite) ?? false
        favoriteOrder = try c.decodeIfPresent(Int.self, forKey: .favoriteOrder) ?? 0
        preferredPreset = try c.decodeIfPresent(String.self, forKey: .preferredPreset)
        customWidthPt = try c.decodeIfPresent(Double.self, forKey: .customWidthPt)
        customHeightPt = try c.decodeIfPresent(Double.self, forKey: .customHeightPt)
        pinnedOnTop = try c.decodeIfPresent(Bool.self, forKey: .pinnedOnTop) ?? false
        customDpi = try c.decodeIfPresent(Int.self, forKey: .customDpi)
        compatibilityResolution = try c.decodeIfPresent(Bool.self, forKey: .compatibilityResolution) ?? false
    }

    /// Returns custom window size in macOS points if both width and height are valid positive values.
    public var customWindowSize: CGSize? {
        guard let w = customWidthPt, let h = customHeightPt, w > 0, h > 0 else { return nil }
        return CGSize(width: w, height: h)
    }

    /// True when every field is at its default value and no preference needs retention.
    public var isDefault: Bool {
        !isFavorite
            && favoriteOrder == 0
            && preferredPreset == nil
            && customWidthPt == nil
            && customHeightPt == nil
            && !pinnedOnTop
            && customDpi == nil
            && !compatibilityResolution
    }
}

// MARK: - Compatibility Resolution Profiles

/// Safe standard Android display geometries for apps that misrender or crash at arbitrary window aspect ratios
/// (e.g., banking apps, games, or fixed-density OEM launchers) when `compatibilityResolution == true`.
public enum PopoutCompatibilityResolution: String, Codable, Sendable, CaseIterable, Equatable {
    case phone = "phone"
    case fold = "fold"
    case tablet = "tablet"

    public static let minCustomDpi: Int = 160
    public static let maxCustomDpi: Int = 480

    public static func clampCustomDpi(_ dpi: Int) -> Int {
        min(max(dpi, minCustomDpi), maxCustomDpi)
    }

    /// Standard encoder-safe Android virtual display dimensions and DPI for this compatibility profile.
    public var streamGeometry: PopoutStreamGeometry {
        switch self {
        case .phone:
            return PopoutStreamGeometry(width: 1080, height: 2340, dpi: 420)
        case .fold:
            return PopoutStreamGeometry(width: 1440, height: 1600, dpi: 360)
        case .tablet:
            return PopoutStreamGeometry(width: 1600, height: 1000, dpi: 240)
        }
    }

    /// Selects the best compatibility profile from a preset string (`"phone"`, `"fold"`/`"foldable"`, `"tablet"`)
    /// or falls back to inferring orientation/aspect ratio from `windowSize`.
    public static func profile(
        forPreset preset: String?,
        windowSize: CGSize? = nil
    ) -> PopoutCompatibilityResolution {
        if let raw = preset?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            switch raw {
            case "phone":
                return .phone
            case "fold", "foldable":
                return .fold
            case "tablet":
                return .tablet
            default:
                break
            }
        }

        if let size = windowSize, size.width > 0, size.height > 0 {
            let aspect = Double(size.width) / Double(size.height)
            if aspect >= 1.2 {
                return .tablet
            }
            if aspect >= 0.72 {
                return .fold
            }
            return .phone
        }

        return .phone
    }

    /// Computes final Android stream geometry for a popout window, honoring `compatibilityResolution`
    /// and optional `customDpi` overrides on top of adaptive `CoordinateMapper.popoutStreamGeometry`.
    public static func resolveStreamGeometry(
        preference: PopoutAppPreference?,
        viewSize: CGSize,
        backingScale: CGFloat = 2.0,
        maxLongEdge: Int = CoordinateMapper.popoutDefaultMaxLongEdge
    ) -> PopoutStreamGeometry {
        let base: PopoutStreamGeometry
        if preference?.compatibilityResolution == true {
            base = profile(forPreset: preference?.preferredPreset, windowSize: viewSize).streamGeometry
        } else {
            base = CoordinateMapper.popoutStreamGeometry(
                viewSize: viewSize,
                backingScale: backingScale,
                maxLongEdge: maxLongEdge
            )
        }

        if let customDpi = preference?.customDpi {
            return PopoutStreamGeometry(
                width: base.width,
                height: base.height,
                dpi: clampCustomDpi(customDpi)
            )
        }
        return base
    }
}

// MARK: - Persistent Store

private struct PopoutAppPreferencesDisk: Codable, Sendable {
    var version: Int = 1
    var preferences: [String: PopoutAppPreference]
}

/// Thread-safe persistent store for per-app standalone floating window preferences (`popout_app_prefs_v1.json`).
/// Entries are keyed by `"\(deviceKey)::\(appPackage)"` with package-level fallback lookup.
public final class PopoutAppPreferencesStore: Sendable {
    private static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "popout.prefs")

    private let storageURL: URL
    private let state: OSAllocatedUnfairLock<[String: PopoutAppPreference]>
    private let writeLock = NSLock()

    public static func defaultStorageURL() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
        return appSupport
            .appendingPathComponent("OpalDesk", isDirectory: true)
            .appendingPathComponent("popout_app_prefs_v1.json")
    }

    public static func compositeKey(deviceKey: String, appPackage: String) -> String {
        let cleanDevice = deviceKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        return "\(cleanDevice)::\(cleanPkg)"
    }

    public init(storageURL: URL = PopoutAppPreferencesStore.defaultStorageURL()) {
        self.storageURL = storageURL
        let loaded = Self.loadFromDisk(url: storageURL)
        self.state = OSAllocatedUnfairLock(initialState: loaded)
    }

    /// Snapshot of all stored preference entries keyed by `deviceKey::appPackage`.
    public func snapshot() -> [String: PopoutAppPreference] {
        state.withLock { $0 }
    }

    /// Looks up the exact entry for `(deviceKey, appPackage)` without falling back to other devices.
    public func exactPreference(for appPackage: String, deviceKey: String) -> PopoutAppPreference? {
        let cleanPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPkg.isEmpty else { return nil }
        let key = Self.compositeKey(deviceKey: deviceKey, appPackage: cleanPkg)
        return state.withLock { $0[key] }
    }

    /// Looks up preferences for `appPackage` on `deviceKey`, falling back to:
    /// 1. package-global entry (`"::\(appPackage)"`), then
    /// 2. any stored entry for `appPackage` from another device (deterministic sorted-key tie-break).
    public func preference(for appPackage: String, deviceKey: String? = nil) -> PopoutAppPreference? {
        let cleanPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPkg.isEmpty else { return nil }
        let cleanDevice = deviceKey?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let suffix = "::\(cleanPkg)"

        return state.withLock { map in
            if !cleanDevice.isEmpty, let exact = map["\(cleanDevice)\(suffix)"] {
                return exact
            }
            if let global = map[suffix] {
                return global
            }
            // Package-level fallback across stored devices (deterministic order).
            let matchingKeys = map.keys
                .filter { $0.hasSuffix(suffix) }
                .sorted()
            if let firstKey = matchingKeys.first {
                return map[firstKey]
            }
            return nil
        }
    }

    /// Convenience accessor returning the resolved preference or a default `PopoutAppPreference()`.
    public func resolvedPreference(for appPackage: String, deviceKey: String? = nil) -> PopoutAppPreference {
        preference(for: appPackage, deviceKey: deviceKey) ?? PopoutAppPreference()
    }

    // MARK: - Mutations

    @discardableResult
    public func toggleFavorite(appPackage: String, deviceKey: String) -> PopoutAppPreference {
        mutatePreference(appPackage: appPackage, deviceKey: deviceKey) { pref, all, cleanDevice in
            if pref.isFavorite {
                pref.isFavorite = false
                pref.favoriteOrder = 0
            } else {
                pref.isFavorite = true
                let prefix = "\(cleanDevice)::"
                let maxOrder = all
                    .filter { $0.key.hasPrefix(prefix) && $0.value.isFavorite }
                    .map(\.value.favoriteOrder)
                    .max() ?? 0
                pref.favoriteOrder = maxOrder + 1
            }
        }
    }

    @discardableResult
    public func setFavorite(
        _ isFavorite: Bool,
        appPackage: String,
        deviceKey: String,
        order: Int? = nil
    ) -> PopoutAppPreference {
        mutatePreference(appPackage: appPackage, deviceKey: deviceKey) { pref, all, cleanDevice in
            pref.isFavorite = isFavorite
            if isFavorite {
                if let order {
                    pref.favoriteOrder = max(0, order)
                } else if pref.favoriteOrder <= 0 {
                    let prefix = "\(cleanDevice)::"
                    let maxOrder = all
                        .filter { $0.key.hasPrefix(prefix) && $0.value.isFavorite }
                        .map(\.value.favoriteOrder)
                        .max() ?? 0
                    pref.favoriteOrder = maxOrder + 1
                }
            } else {
                pref.favoriteOrder = 0
            }
        }
    }

    /// Updates `favoriteOrder` for an ordered list of favorite package names on `deviceKey`.
    public func reorderFavorites(_ orderedPackages: [String], forDeviceKey deviceKey: String) {
        let cleanDevice = deviceKey.trimmingCharacters(in: .whitespacesAndNewlines)
        writeLock.withLock {
            var next = state.withLock { $0 }
            for (index, rawPkg) in orderedPackages.enumerated() {
                let cleanPkg = rawPkg.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !cleanPkg.isEmpty else { continue }
                let key = Self.compositeKey(deviceKey: cleanDevice, appPackage: cleanPkg)
                var pref = next[key] ?? PopoutAppPreference()
                pref.isFavorite = true
                pref.favoriteOrder = index + 1
                next[key] = pref
            }
            persist(next)
            let committed = next
            state.withLock { $0 = committed }
        }
    }

    @discardableResult
    public func setPreset(
        _ preset: String?,
        appPackage: String,
        deviceKey: String
    ) -> PopoutAppPreference {
        let normalizedPreset: String? = {
            guard let raw = preset?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
                  !raw.isEmpty
            else { return nil }
            switch raw {
            case "phone", "fold", "tablet", "custom":
                return raw
            case "foldable":
                return "fold"
            default:
                return raw
            }
        }()
        return mutatePreference(appPackage: appPackage, deviceKey: deviceKey) { pref, _, _ in
            pref.preferredPreset = normalizedPreset
        }
    }

    @discardableResult
    public func setCustomWindowSize(
        widthPt: Double?,
        heightPt: Double?,
        appPackage: String,
        deviceKey: String
    ) -> PopoutAppPreference {
        mutatePreference(appPackage: appPackage, deviceKey: deviceKey) { pref, _, _ in
            if let w = widthPt, let h = heightPt, w.isFinite, h.isFinite, w > 0, h > 0 {
                pref.customWidthPt = w
                pref.customHeightPt = h
                pref.preferredPreset = "custom"
            } else {
                pref.customWidthPt = nil
                pref.customHeightPt = nil
                if pref.preferredPreset == "custom" {
                    pref.preferredPreset = nil
                }
            }
        }
    }

    @discardableResult
    public func setPinnedOnTop(
        _ pinned: Bool,
        appPackage: String,
        deviceKey: String
    ) -> PopoutAppPreference {
        mutatePreference(appPackage: appPackage, deviceKey: deviceKey) { pref, _, _ in
            pref.pinnedOnTop = pinned
        }
    }

    @discardableResult
    public func setCustomDpi(
        _ dpi: Int?,
        appPackage: String,
        deviceKey: String
    ) -> PopoutAppPreference {
        mutatePreference(appPackage: appPackage, deviceKey: deviceKey) { pref, _, _ in
            pref.customDpi = dpi.map(PopoutCompatibilityResolution.clampCustomDpi)
        }
    }

    @discardableResult
    public func setCompatibilityResolution(
        _ enabled: Bool,
        appPackage: String,
        deviceKey: String
    ) -> PopoutAppPreference {
        mutatePreference(appPackage: appPackage, deviceKey: deviceKey) { pref, _, _ in
            pref.compatibilityResolution = enabled
        }
    }

    /// Returns favorite package names for `deviceKey` ordered by `(favoriteOrder, packageName)`.
    /// Also includes global package-level favorites (`"::<pkg>"`) unless explicitly overridden for `deviceKey`.
    public func favoritePackages(forDeviceKey deviceKey: String) -> [String] {
        let cleanDevice = deviceKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let devicePrefix = "\(cleanDevice)::"
        let globalPrefix = "::"

        return state.withLock { map in
            var byPkg: [String: PopoutAppPreference] = [:]

            // Seed with global package-level entries first.
            for (key, pref) in map where key.hasPrefix(globalPrefix) {
                let pkg = String(key.dropFirst(globalPrefix.count))
                guard !pkg.isEmpty else { continue }
                byPkg[pkg] = pref
            }

            // Override with device-specific entries.
            if !cleanDevice.isEmpty {
                for (key, pref) in map where key.hasPrefix(devicePrefix) {
                    let pkg = String(key.dropFirst(devicePrefix.count))
                    guard !pkg.isEmpty else { continue }
                    byPkg[pkg] = pref
                }
            }

            return byPkg
                .filter { $0.value.isFavorite }
                .sorted { lhs, rhs in
                    if lhs.value.favoriteOrder != rhs.value.favoriteOrder {
                        return lhs.value.favoriteOrder < rhs.value.favoriteOrder
                    }
                    return lhs.key < rhs.key
                }
                .map(\.key)
        }
    }

    public func remove(appPackage: String, deviceKey: String) {
        let cleanPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPkg.isEmpty else { return }
        let key = Self.compositeKey(deviceKey: deviceKey, appPackage: cleanPkg)
        writeLock.withLock {
            var next = state.withLock { $0 }
            guard next.removeValue(forKey: key) != nil else { return }
            persist(next)
            let committed = next
            state.withLock { $0 = committed }
        }
    }

    public func clearAll() {
        writeLock.withLock {
            persist([:])
            state.withLock { $0.removeAll() }
        }
    }

    // MARK: - Persistence Internals

    private func mutatePreference(
        appPackage: String,
        deviceKey: String,
        mutate: (inout PopoutAppPreference, [String: PopoutAppPreference], String) -> Void
    ) -> PopoutAppPreference {
        let cleanPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDevice = deviceKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPkg.isEmpty else { return PopoutAppPreference() }
        let key = Self.compositeKey(deviceKey: cleanDevice, appPackage: cleanPkg)

        return writeLock.withLock {
            var next = state.withLock { $0 }
            var pref = next[key] ?? PopoutAppPreference()
            mutate(&pref, next, cleanDevice)
            if pref.isDefault {
                next.removeValue(forKey: key)
            } else {
                next[key] = pref
            }
            persist(next)
            let committed = next
            state.withLock { $0 = committed }
            return pref
        }
    }

    private static func loadFromDisk(url: URL) -> [String: PopoutAppPreference] {
        guard FileManager.default.fileExists(atPath: url.path) else {
            return [:]
        }
        do {
            let data = try Data(contentsOf: url)
            let decoded = try JSONDecoder().decode(PopoutAppPreferencesDisk.self, from: data)
            return decoded.preferences
        } catch {
            logger.warning("Failed to load popout app preferences from \(url.path, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return [:]
        }
    }

    private func persist(_ preferences: [String: PopoutAppPreference]) {
        let dir = storageURL.deletingLastPathComponent()
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(PopoutAppPreferencesDisk(version: 1, preferences: preferences))
            try data.write(to: storageURL, options: [.atomic])
        } catch {
            Self.logger.error("Failed to persist popout app preferences to \(self.storageURL.path, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }
}
