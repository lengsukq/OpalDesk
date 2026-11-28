import AppKit
import Foundation
import OpalDeskCore
import os

public enum PopoutDeviceTarget: Sendable, Equatable {
    case activeDesktop
    case adb(AdbDevice)
    case bonjour(DiscoveredBonjourDevice)
    case usbTether(DiscoveredUsbTetherDevice)
    case trusted(TrustedDevice)
}

public struct RemoteAppPickerContext: Identifiable, Sendable {
    public let id = UUID()
    public let target: PopoutDeviceTarget
    public var deviceKey: String
    public var deviceName: String
    public var allowLaunchInDesktop: Bool
    public var apps: [RemoteAppInfo] = []
    public var isLoading: Bool = false
    public var statusMessage: String = ""
    public var errorMessage: String?
    public var resolvedEndpoint: OpalCastEndpoint?
    public var resolvedTransportHint: OpalCastTransportHint = .wifiTls
    public var resolvedAuthToken: String = ""
    public var resolvedFingerprint: String = ""
    public var resolvedAdbSerial: String?
    public var resolvedHost: String?
    public var resolvedPort: UInt16?
    public var resolvedBonjourServiceName: String?
    public var resolvedBonjourDomain: String?
    public var resolvedTransportKind: String = "wifi"
}

/// Lightweight quick-launch item for a user-pinned favorite Android app.
public struct FavoritePopoutAppItem: Identifiable, Sendable, Equatable {
    public var id: String { "\(deviceKey)::\(appKey)" }
    public let pkg: String
    public let cls: String
    public let label: String
    public let userId: Int
    public let profileType: String
    public let deviceKey: String
    public let deviceName: String

    public var appKey: String {
        RemoteAppInfo.formatAppKey(pkg: pkg, userId: userId)
    }

    public var profileBadgeText: String? {
        RemoteAppInfo.profileBadgeText(userId: userId, profileType: profileType)
    }

    public init(
        pkg: String,
        cls: String = "",
        label: String,
        userId: Int = 0,
        profileType: String = "",
        deviceKey: String,
        deviceName: String = ""
    ) {
        let normalizedUid = max(0, userId)
        self.pkg = pkg
        self.cls = cls
        self.label = label.isEmpty ? pkg : label
        self.userId = normalizedUid
        self.profileType = RemoteAppInfo.normalizeProfileType(profileType, userId: normalizedUid)
        self.deviceKey = deviceKey
        self.deviceName = deviceName
    }
}

/// Secondary confirmation prompt shown whenever the user clicks an app whose floating window is already open.
public struct PendingPopoutReloadPrompt: Identifiable {
    public let id = UUID()
    public let appPackage: String
    public let appLabel: String
    public let appUserId: Int
    public let existingWindowId: UUID
    public let onFocusExisting: @MainActor () -> Void
    public let onReloadWindowAndApp: @MainActor () -> Void

    public init(
        appPackage: String,
        appLabel: String,
        appUserId: Int = 0,
        existingWindowId: UUID,
        onFocusExisting: @escaping @MainActor () -> Void,
        onReloadWindowAndApp: @escaping @MainActor () -> Void
    ) {
        self.appPackage = appPackage
        self.appLabel = appLabel
        self.appUserId = max(0, appUserId)
        self.existingWindowId = existingWindowId
        self.onFocusExisting = onFocusExisting
        self.onReloadWindowAndApp = onReloadWindowAndApp
    }
}

// MARK: - Standalone Floating Windows (Up to 3 Concurrent + Pin + History + Reload Confirmation)

extension AppModel {
    enum PopoutTuning {
        static let reloadGapNs: UInt64 = 240_000_000
        static let maxTargetFPS: Int = 60
        static let maxBitrateKbps: Int = 10_000
    }

    func makePopoutConnectionContext(
        endpoint: OpalCastEndpoint,
        transportHint: OpalCastTransportHint,
        authToken: String,
        useEffectiveBitrate: Bool = true
    ) -> PopoutConnectionContext {
        let rawBitrate = useEffectiveBitrate ? effectiveBitrateKbps() : preferences.bitrateKbps
        return PopoutConnectionContext(
            endpoint: endpoint,
            transportHint: transportHint,
            clientId: discovery.trustStore.clientId,
            authToken: authToken,
            preferredCodec: preferences.preferredCodec,
            targetFPS: min(preferences.targetFPS, PopoutTuning.maxTargetFPS),
            bitrateKbps: min(rawBitrate, PopoutTuning.maxBitrateKbps),
            mapCommandToCtrl: preferences.mapCommandToCtrl,
            audioEnabled: preferences.audioEnabled,
            enableEdgeNavigationGestures: preferences.enableEdgeNavigationGestures
        )
    }

    public var activePopoutCount: Int {
        popoutManager.controllers.count
    }

    public func isPopoutActive(pkg: String, userId: Int = 0) -> Bool {
        popoutManager.existingController(for: pkg, userId: userId) != nil
    }

    public func refreshPopoutHistory() {
        popoutHistory = popoutHistoryStore.snapshot()
    }

    public func removePopoutHistoryEntry(_ entry: PopoutHistoryEntry) {
        popoutHistory = popoutHistoryStore.remove(id: entry.id)
    }

    public func clearPopoutHistory() {
        popoutHistoryStore.clearAll()
        popoutHistory = []
    }

    // MARK: - Favorite App Pinning & Per-App Popout Preferences

    /// Preferred device key for resolving favorites in global surfaces (e.g. Menu Bar).
    public var activePreferredDeviceKey: String {
        if let conn = activeConnection {
            switch conn.transport {
            case .wiredUSB(let s, _), .wirelessADB(let s, _):
                return PopoutDeviceKey.adb(s)
            case .wirelessWiFi:
                return PopoutDeviceKey.resolve(fingerprint: conn.fingerprint, fallback: conn.deviceName)
            }
        }
        if let target = companionTarget, !target.certFingerprint.isEmpty {
            return PopoutDeviceKey.fingerprint(target.certFingerprint)
        }
        if let firstUsb = discovery.usbDevices.first(where: \.isReady) {
            return PopoutDeviceKey.adb(firstUsb.serial)
        }
        if let firstTrusted = discovery.trustedDevices.first, !firstTrusted.certFingerprint.isEmpty {
            return PopoutDeviceKey.fingerprint(firstTrusted.certFingerprint)
        }
        return ""
    }

    public func toggleFavoriteApp(pkg: String, userId: Int = 0, deviceKey: String) {
        let appKey = RemoteAppInfo.formatAppKey(pkg: pkg, userId: userId)
        let updated = popoutAppPrefsStore.toggleFavorite(appPackage: appKey, deviceKey: deviceKey)
        refreshPopoutAppPrefs()
        if let ctrl = popoutManager.existingController(for: pkg, userId: userId) {
            ctrl.isFavorite = updated.isFavorite
        }
    }

    /// Returns a compact summary of non-default remembered window preferences (preset/DPI/compat),
    /// or `nil` when the app uses all default adaptive settings.
    public func popoutPreferenceSummary(pkg: String, userId: Int = 0, deviceKey: String) -> String? {
        let appKey = RemoteAppInfo.formatAppKey(pkg: pkg, userId: userId)
        guard !appKey.isEmpty else { return nil }
        let snapshot = popoutAppPrefs
        let exactKey = PopoutAppPreferencesStore.compositeKey(deviceKey: deviceKey, appPackage: appKey)
        let globalKey = PopoutAppPreferencesStore.compositeKey(deviceKey: "", appPackage: appKey)
        guard let pref = snapshot[exactKey] ?? snapshot[globalKey] else { return nil }

        var parts: [String] = []
        switch pref.preferredPreset?.lowercased() {
        case "fold", "foldable":
            parts.append(OpalL10n.text("app.popout.pref.foldable"))
        case "tablet":
            parts.append(OpalL10n.text("app.popout.pref.tablet"))
        case "custom":
            if pref.customWindowSize != nil { parts.append(OpalL10n.text("app.popout.pref.custom_size")) }
        default:
            if pref.customWindowSize != nil { parts.append(OpalL10n.text("app.popout.pref.custom_size")) }
        }
        if let dpi = pref.customDpi {
            parts.append("\(dpi) DPI")
        }
        if pref.compatibilityResolution {
            parts.append(OpalL10n.text("app.popout.pref.compat"))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    public func isFavoriteApp(pkg: String, userId: Int = 0, deviceKey: String) -> Bool {
        let appKey = RemoteAppInfo.formatAppKey(pkg: pkg, userId: userId)
        guard !appKey.isEmpty else { return false }
        let snapshot = popoutAppPrefs
        let exactKey = PopoutAppPreferencesStore.compositeKey(deviceKey: deviceKey, appPackage: appKey)
        if let exact = snapshot[exactKey] {
            return exact.isFavorite
        }
        let globalKey = PopoutAppPreferencesStore.compositeKey(deviceKey: "", appPackage: appKey)
        return snapshot[globalKey]?.isFavorite ?? false
    }

    public func favoriteAppPackages(for deviceKey: String) -> [String] {
        // Read `popoutAppPrefs` so SwiftUI Observation tracks mutations live.
        _ = popoutAppPrefs
        return popoutAppPrefsStore.favoritePackages(forDeviceKey: deviceKey)
    }

    /// Returns ordered pinned favorite apps enriched with cached labels & activities for 1-click launch.
    public func favoriteAppItems(for deviceKey: String? = nil) -> [FavoritePopoutAppItem] {
        let snapshot = popoutAppPrefs
        let targetDeviceKey = deviceKey ?? activePreferredDeviceKey

        var orderedEntries: [(pkg: String, deviceKey: String, order: Int)] = []
        if !targetDeviceKey.isEmpty {
            let pkgs = popoutAppPrefsStore.favoritePackages(forDeviceKey: targetDeviceKey)
            for (idx, pkg) in pkgs.enumerated() {
                orderedEntries.append((pkg, targetDeviceKey, idx + 1))
            }
        }

        // If no favorites on the active device key (or no device key), surface favorites across all devices.
        if orderedEntries.isEmpty {
            var bestByPkg: [String: (deviceKey: String, order: Int)] = [:]
            for (composite, pref) in snapshot where pref.isFavorite {
                guard let sep = composite.range(of: "::") else { continue }
                let devKey = String(composite[..<sep.lowerBound])
                let pkg = String(composite[sep.upperBound...])
                guard !pkg.isEmpty else { continue }
                if let existing = bestByPkg[pkg] {
                    if pref.favoriteOrder < existing.order {
                        bestByPkg[pkg] = (devKey, pref.favoriteOrder)
                    }
                } else {
                    bestByPkg[pkg] = (devKey, pref.favoriteOrder)
                }
            }
            orderedEntries = bestByPkg
                .sorted { lhs, rhs in
                    if lhs.value.order != rhs.value.order {
                        return lhs.value.order < rhs.value.order
                    }
                    return lhs.key < rhs.key
                }
                .map { ($0.key, $0.value.deviceKey, $0.value.order) }
        }

        return orderedEntries.map { item in
            let parsed = RemoteAppInfo.parseAppKey(item.pkg)
            let catalogMatch = cachedDesktopApps.first(where: { $0.pkg == parsed.pkg && $0.userId == parsed.userId })
                ?? cachedCompanionApps.first(where: { $0.pkg == parsed.pkg && $0.userId == parsed.userId })
                ?? cachedDesktopApps.first(where: { $0.pkg == parsed.pkg })
                ?? cachedCompanionApps.first(where: { $0.pkg == parsed.pkg })
            let historyMatch = popoutHistory.first(where: { $0.appPackage == parsed.pkg && $0.appUserId == parsed.userId && $0.deviceKey == item.deviceKey })
                ?? popoutHistory.first(where: { $0.appPackage == parsed.pkg && $0.appUserId == parsed.userId })
            let label = catalogMatch?.displayLabel ?? historyMatch?.displayLabel ?? parsed.pkg
            let cls = catalogMatch?.cls ?? historyMatch?.appActivity ?? ""
            let profileType = catalogMatch?.profileType ?? historyMatch?.appProfileType ?? ""
            let devName = historyMatch?.deviceName ?? (isAnyChannelConnected ? activeChannelDeviceName : "")
            return FavoritePopoutAppItem(
                pkg: parsed.pkg,
                cls: cls,
                label: label,
                userId: parsed.userId,
                profileType: profileType,
                deviceKey: item.deviceKey,
                deviceName: devName
            )
        }
    }

    private func refreshPopoutAppPrefs() {
        popoutAppPrefs = popoutAppPrefsStore.snapshot()
    }

    func applyPopoutPreferenceChange(
        _ change: PopoutAppPreferenceChange,
        appPackage: String,
        deviceKey: String
    ) {
        switch change {
        case .favorite(let isFav):
            let currentFav = popoutAppPrefsStore.exactPreference(for: appPackage, deviceKey: deviceKey)?.isFavorite ?? false
            if currentFav != isFav {
                _ = popoutAppPrefsStore.toggleFavorite(appPackage: appPackage, deviceKey: deviceKey)
            }
        case .preset(let presetKey):
            _ = popoutAppPrefsStore.setPreset(presetKey, appPackage: appPackage, deviceKey: deviceKey)
            if presetKey != "custom" {
                _ = popoutAppPrefsStore.setCustomWindowSize(
                    widthPt: nil,
                    heightPt: nil,
                    appPackage: appPackage,
                    deviceKey: deviceKey
                )
                if let presetKey {
                    _ = popoutAppPrefsStore.setPreset(presetKey, appPackage: appPackage, deviceKey: deviceKey)
                }
            }
        case let .customWindowSize(widthPt, heightPt):
            _ = popoutAppPrefsStore.setCustomWindowSize(
                widthPt: widthPt,
                heightPt: heightPt,
                appPackage: appPackage,
                deviceKey: deviceKey
            )
        case .pinnedOnTop(let pinned):
            _ = popoutAppPrefsStore.setPinnedOnTop(pinned, appPackage: appPackage, deviceKey: deviceKey)
        case .customDpi(let dpi):
            _ = popoutAppPrefsStore.setCustomDpi(dpi, appPackage: appPackage, deviceKey: deviceKey)
        case .compatibilityResolution(let enabled):
            _ = popoutAppPrefsStore.setCompatibilityResolution(enabled, appPackage: appPackage, deviceKey: deviceKey)
        }
        refreshPopoutAppPrefs()
    }
}
