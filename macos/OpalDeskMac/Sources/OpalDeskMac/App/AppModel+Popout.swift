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
    private enum PopoutTuning {
        static let reloadGapNs: UInt64 = 240_000_000
        static let maxTargetFPS: Int = 60
        static let maxBitrateKbps: Int = 10_000
    }

    private func makePopoutConnectionContext(
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

    private func applyPopoutPreferenceChange(
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

    /// Opens the remote app picker sheet for either the live desktop session or any discovered device in Device Hub.
    public func openRemoteAppPicker(for target: PopoutDeviceTarget) {
        remoteAppCatalogFetcher.cancel()
        var ctx = makeInitialPickerContext(for: target)
        ctx.isLoading = true
        ctx.statusMessage = OpalL10n.format("app.popout.loading_apps_fmt", ctx.deviceName)
        remoteAppPickerContext = ctx
        loadAppsForCurrentPicker()
    }

    public func refreshRemoteAppPicker() {
        guard var ctx = remoteAppPickerContext else { return }
        ctx.isLoading = true
        ctx.errorMessage = nil
        ctx.statusMessage = OpalL10n.text("app.popout.refreshing_apps")
        remoteAppPickerContext = ctx
        loadAppsForCurrentPicker()
    }

    public func closeRemoteAppPicker() {
        remoteAppCatalogFetcher.cancel()
        remoteAppPickerContext = nil
    }

    /// Handles `0x15 APP_LIST` frames received on the main desktop session.
    func handleMainSessionAppList(_ apps: [RemoteAppInfo]) {
        cachedDesktopApps = apps
        if var ctx = remoteAppPickerContext, ctx.target == .activeDesktop {
            ctx.apps = apps
            ctx.isLoading = false
            ctx.statusMessage = ""
            ctx.errorMessage = nil
            remoteAppPickerContext = ctx
        }
    }

    /// Launches a floating app window from the Remote App Picker (prompts reload if already open).
    public func requestPopoutLaunch(
        pkg: String,
        cls: String,
        label: String,
        userId: Int = 0,
        profileType: String = "",
        pickerContext: RemoteAppPickerContext
    ) {
        let cleanPkg = pkg.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPkg.isEmpty else { return }
        let normalizedUid = max(0, userId)

        guard let endpoint = pickerContext.resolvedEndpoint else {
            showToast(OpalL10n.text("app.popout.context_not_ready"))
            return
        }

        let connCtx = makePopoutConnectionContext(
            endpoint: endpoint,
            transportHint: pickerContext.resolvedTransportHint,
            authToken: pickerContext.resolvedAuthToken
        )

        let historyTemplate = PopoutHistoryEntry(
            appPackage: cleanPkg,
            appActivity: cls,
            appLabel: label,
            deviceKey: pickerContext.deviceKey,
            deviceName: pickerContext.deviceName,
            transportKind: pickerContext.resolvedTransportKind,
            adbSerial: pickerContext.resolvedAdbSerial,
            host: pickerContext.resolvedHost,
            port: pickerContext.resolvedPort,
            certFingerprint: pickerContext.resolvedFingerprint.isEmpty ? nil : pickerContext.resolvedFingerprint,
            bonjourServiceName: pickerContext.resolvedBonjourServiceName,
            bonjourDomain: pickerContext.resolvedBonjourDomain,
            pinnedOnTop: popoutHistory.first(where: {
                $0.appPackage == cleanPkg && $0.appUserId == normalizedUid && $0.deviceKey == pickerContext.deviceKey
            })?.pinnedOnTop ?? false,
            appUserId: normalizedUid,
            appProfileType: profileType
        )

        performPopoutOpenOrPromptReload(
            entry: historyTemplate,
            context: connCtx,
            closePickerOnOpen: true
        )
    }

    /// One-click launch from the Device Hub / Picker「历史 APP 小窗」bar without opening the full desktop.
    public func openPopoutFromHistory(_ entry: PopoutHistoryEntry) {
        if let existing = popoutManager.existingController(for: entry.appPackage, userId: entry.appUserId) {
            pendingPopoutReloadPrompt = PendingPopoutReloadPrompt(
                appPackage: entry.appPackage,
                appLabel: entry.displayLabel,
                appUserId: entry.appUserId,
                existingWindowId: existing.id,
                onFocusExisting: { [weak self] in
                    self?.popoutManager.focusWindow(id: existing.id)
                },
                onReloadWindowAndApp: { [weak self] in
                    guard let self else { return }
                    Task { await self.resolveAndOpenHistoryEntry(entry, forceReload: true) }
                }
            )
            return
        }

        if popoutManager.controllers.count >= PopoutWindowManager.maxConcurrentWindows {
            let msg = OpalL10n.format("app.popout.limit_fmt", PopoutWindowManager.maxConcurrentWindows)
            bannerError = msg
            showToast(msg)
            return
        }

        Task { await resolveAndOpenHistoryEntry(entry, forceReload: false) }
    }

    /// Migrates open popouts after Wireless ADB seamlessly upgrades to Wi-Fi TLS.
    func migratePopoutsAfterWifiUpgrade(endpoint: OpalCastEndpoint, authToken: String) {
        guard !popoutManager.controllers.isEmpty else { return }
        let upgraded = makePopoutConnectionContext(
            endpoint: endpoint,
            transportHint: .wifiTls,
            authToken: authToken
        )
        popoutManager.migrateConnectionContext(to: upgraded)
    }

    /// Resolves the best matching online Android host (active main session, active companion, USB/Bonjour/Trusted)
    /// and opens a standalone popout window for `packageName` (used by deep links, notifications, and handoff).
    func resolveAndOpenAppPopout(
        packageName: String,
        activityClass: String = "",
        appLabel: String = "",
        userId: Int = 0,
        profileType: String = "",
        preferredDeviceKey: String? = nil,
        preferredDeviceName: String? = nil,
        waitForDiscoveryIfNeeded: Bool = false
    ) async {
        let cleanPkg = packageName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPkg.isEmpty else { return }
        let normalizedUid = max(0, userId)
        let displayLabel = appLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? cleanPkg : appLabel

        if let existing = popoutManager.existingController(for: cleanPkg, userId: normalizedUid) {
            popoutManager.focusWindow(id: existing.id)
            return
        }

        if popoutManager.controllers.count >= PopoutWindowManager.maxConcurrentWindows {
            let msg = OpalL10n.format("app.popout.limit_fmt", PopoutWindowManager.maxConcurrentWindows)
            bannerError = msg
            showToast(msg)
            return
        }

        statusMessage = OpalL10n.format("app.popout.locating_fmt", displayLabel)
        defer { statusMessage = nil }

        let maxAttempts = waitForDiscoveryIfNeeded ? 10 : 1
        for attempt in 0..<maxAttempts {
            if let resolved = await tryResolveOnlinePopoutTarget(
                packageName: cleanPkg,
                activityClass: activityClass,
                appLabel: displayLabel,
                userId: normalizedUid,
                profileType: profileType,
                preferredDeviceKey: preferredDeviceKey,
                preferredDeviceName: preferredDeviceName
            ) {
                let result = openPopoutImmediate(entry: resolved.entry, context: resolved.context, forceReload: false)
                switch result {
                case .opened:
                    showToast(OpalL10n.format("app.popout.opened_fmt", displayLabel, popoutManager.controllers.count, PopoutWindowManager.maxConcurrentWindows))
                case .alreadyOpen(let existingId, _):
                    popoutManager.focusWindow(id: existingId)
                case .limitReached(let maxCount):
                    let msg = OpalL10n.format("app.popout.limit_fmt", maxCount)
                    bannerError = msg
                    showToast(msg)
                case .invalidPackage:
                    showToast(OpalL10n.text("app.popout.invalid_pkg"))
                }
                return
            }
            if attempt + 1 < maxAttempts {
                guard await sleepCancellable(nanoseconds: 250_000_000) else { return }
            }
        }

        let targetHint = preferredDeviceName ?? OpalL10n.text("common.android_device")
        let msg = OpalL10n.format("app.popout.not_found_fmt", targetHint, displayLabel)
        onRequestOpenMainWindow?()
        NSApp.activate(ignoringOtherApps: true)
        bannerError = msg
        showToast(msg)
    }

    private func tryResolveOnlinePopoutTarget(
        packageName: String,
        activityClass: String,
        appLabel: String,
        userId: Int,
        profileType: String,
        preferredDeviceKey: String?,
        preferredDeviceName: String?
    ) async -> (context: PopoutConnectionContext, entry: PopoutHistoryEntry)? {
        let keyMatches: (String?, String?, String?) -> Bool = { serial, fp, name in
            guard let prefKey = preferredDeviceKey, !prefKey.isEmpty else {
                if let prefName = preferredDeviceName, !prefName.isEmpty, let name, !name.isEmpty {
                    return name == prefName
                }
                return true
            }
            if prefKey.hasPrefix("adb:"), let serial, !serial.isEmpty {
                return String(prefKey.dropFirst(4)) == serial
            }
            if prefKey.hasPrefix("fp:"), let fp, !fp.isEmpty {
                return FingerprintUtil.matches(actualFullHex: fp, expected: String(prefKey.dropFirst(3)))
            }
            if let serial, serial == prefKey { return true }
            if let fp, !fp.isEmpty, FingerprintUtil.matches(actualFullHex: fp, expected: prefKey) { return true }
            if let name, !name.isEmpty, name == prefKey { return true }
            return false
        }

        // 1. Active main desktop session
        if isAuthenticated, let endpoint = activeSessionEndpoint, let conn = activeConnection {
            let serial: String?
            let host: String?
            let port: UInt16?
            let kind: String
            switch conn.transport {
            case .wiredUSB(let s, _), .wirelessADB(let s, _):
                serial = s; host = nil; port = nil; kind = "usb"
            case .wirelessWiFi(_, let h, let p):
                serial = nil; host = h; port = p; kind = "wifi"
            }
            if keyMatches(serial, conn.fingerprint, conn.deviceName) {
                let devKey = PopoutDeviceKey.resolve(
                    adbSerial: serial,
                    fingerprint: conn.fingerprint,
                    fallback: conn.deviceName
                )
                let ctx = makePopoutConnectionContext(
                    endpoint: endpoint,
                    transportHint: conn.transport.transportHint,
                    authToken: pendingAuth.authToken
                )
                let entry = PopoutHistoryEntry(
                    appPackage: packageName,
                    appActivity: activityClass,
                    appLabel: appLabel,
                    deviceKey: devKey,
                    deviceName: conn.deviceName,
                    transportKind: kind,
                    adbSerial: serial,
                    host: host,
                    port: port,
                    certFingerprint: conn.fingerprint.isEmpty ? nil : conn.fingerprint,
                    appUserId: userId,
                    appProfileType: profileType
                )
                return (ctx, entry)
            }
        }

        // 2. Active background companion session
        if companionManager.isAuthenticated, let target = companionManager.activeTarget,
           keyMatches(nil, target.certFingerprint, target.deviceName)
        {
            let ctx = makePopoutConnectionContext(
                endpoint: target.endpoint,
                transportHint: target.endpoint.security == .wiredLocalhostPlaintext ? .usb : .wifiTls,
                authToken: target.authToken,
                useEffectiveBitrate: false
            )
            let devKey = PopoutDeviceKey.resolve(fingerprint: target.certFingerprint, fallback: target.deviceName)
            let entry = PopoutHistoryEntry(
                appPackage: packageName,
                appActivity: activityClass,
                appLabel: appLabel,
                deviceKey: devKey,
                deviceName: target.deviceName,
                transportKind: "bonjour",
                host: target.lastHost,
                port: target.lastPort,
                certFingerprint: target.certFingerprint.isEmpty ? nil : target.certFingerprint,
                appUserId: userId,
                appProfileType: profileType
            )
            return (ctx, entry)
        }

        // 3. Ready USB / wireless ADB device
        if let usbDev = discovery.usbDevices.first(where: { $0.isReady && keyMatches($0.serial, nil, $0.displayName) })
            ?? (preferredDeviceKey == nil ? discovery.usbDevices.first(where: \.isReady) : nil)
        {
            if let forward = try? await ensureAdbForwardForPopout(serial: usbDev.serial) {
                let ep = OpalCastEndpoint(
                    target: .hostPort(host: "127.0.0.1", port: forward.localPort),
                    security: .wiredLocalhostPlaintext,
                    autoReconnect: true
                )
                let ctx = makePopoutConnectionContext(
                    endpoint: ep,
                    transportHint: usbDev.isWirelessAdb ? .wifiAdb : .usb,
                    authToken: "",
                    useEffectiveBitrate: false
                )
                let entry = PopoutHistoryEntry(
                    appPackage: packageName,
                    appActivity: activityClass,
                    appLabel: appLabel,
                    deviceKey: PopoutDeviceKey.adb(usbDev.serial),
                    deviceName: usbDev.displayName,
                    transportKind: "usb",
                    adbSerial: usbDev.serial,
                    appUserId: userId,
                    appProfileType: profileType
                )
                return (ctx, entry)
            }
        }

        // 4. Discovered trusted Bonjour device
        let bonjourMatch = discovery.bonjourDevices.first(where: { dev in
            let trusted = discovery.trustStore.find(byFingerprint: dev.fingerprintPrefix)
            let fp = trusted?.certFingerprint ?? dev.fingerprintPrefix
            let svcMatch = (preferredDeviceKey == "bonjour:\(dev.serviceName)")
            return (svcMatch || keyMatches(nil, fp, dev.displayName)) && (trusted != nil || !dev.fingerprintPrefix.isEmpty)
        }) ?? (preferredDeviceKey == nil ? discovery.bonjourDevices.first(where: { discovery.isTrusted($0) }) : nil)

        if let bonjour = bonjourMatch {
            let trusted = discovery.trustStore.find(byFingerprint: bonjour.fingerprintPrefix)
            let pin = trusted?.certFingerprint ?? bonjour.fingerprintPrefix
            if pin.count >= FingerprintUtil.minimumPrefixHexLength {
                let ep = OpalCastEndpoint(
                    target: .bonjour(serviceName: bonjour.serviceName, domain: bonjour.domain),
                    security: .tlsPinned(expectedFingerprint: pin),
                    autoReconnect: true
                )
                let ctx = makePopoutConnectionContext(
                    endpoint: ep,
                    transportHint: .wifiTls,
                    authToken: trusted?.authToken ?? "",
                    useEffectiveBitrate: false
                )
                let entry = PopoutHistoryEntry(
                    appPackage: packageName,
                    appActivity: activityClass,
                    appLabel: appLabel,
                    deviceKey: PopoutDeviceKey.fingerprint(pin),
                    deviceName: bonjour.displayName,
                    transportKind: "bonjour",
                    certFingerprint: pin,
                    bonjourServiceName: bonjour.serviceName,
                    bonjourDomain: bonjour.domain,
                    appUserId: userId,
                    appProfileType: profileType
                )
                return (ctx, entry)
            }
        }

        // 5. Fallback to active main or companion even when preferredDeviceKey didn't match (e.g. rotated device key)
        if preferredDeviceKey != nil {
            if isAuthenticated, let endpoint = activeSessionEndpoint, let conn = activeConnection {
                let ctx = makePopoutConnectionContext(
                    endpoint: endpoint,
                    transportHint: conn.transport.transportHint,
                    authToken: pendingAuth.authToken
                )
                let entry = PopoutHistoryEntry(
                    appPackage: packageName,
                    appActivity: activityClass,
                    appLabel: appLabel,
                    deviceKey: conn.deviceName,
                    deviceName: conn.deviceName,
                    transportKind: "wifi",
                    certFingerprint: conn.fingerprint.isEmpty ? nil : conn.fingerprint,
                    appUserId: userId,
                    appProfileType: profileType
                )
                return (ctx, entry)
            }
            if companionManager.isAuthenticated, let target = companionManager.activeTarget {
                let ctx = makePopoutConnectionContext(
                    endpoint: target.endpoint,
                    transportHint: .wifiTls,
                    authToken: target.authToken,
                    useEffectiveBitrate: false
                )
                let entry = PopoutHistoryEntry(
                    appPackage: packageName,
                    appActivity: activityClass,
                    appLabel: appLabel,
                    deviceKey: PopoutDeviceKey.fingerprint(target.certFingerprint),
                    deviceName: target.deviceName,
                    transportKind: "bonjour",
                    certFingerprint: target.certFingerprint,
                    appUserId: userId,
                    appProfileType: profileType
                )
                return (ctx, entry)
            }
        }

        return nil
    }

    /// Releases any standalone ADB forwards that are no longer used by open popout windows or the main desktop session.
    func cleanupUnusedPopoutAdbForwards() {
        let unused = popoutAdbForwards.filter { _, forward in
            let usedByMain = activeAdbForward?.localPort == forward.localPort
            let usedByPopout = popoutManager.hasActiveController(usingLocalPort: forward.localPort)
            return !usedByMain && !usedByPopout
        }
        guard !unused.isEmpty else { return }
        for (serial, forward) in unused {
            popoutAdbForwards.removeValue(forKey: serial)
            Task { [adb = discovery.adbManager] in
                await adb.removeForward(forward)
            }
        }
    }

    // MARK: - Private Resolution & Reload Flow

    private func performPopoutOpenOrPromptReload(
        entry: PopoutHistoryEntry,
        context: PopoutConnectionContext,
        closePickerOnOpen: Bool
    ) {
        let result = openPopoutImmediate(entry: entry, context: context, forceReload: false)
        switch result {
        case .opened:
            if closePickerOnOpen { closeRemoteAppPicker() }
            showToast(OpalL10n.format("app.popout.opened_fmt", entry.displayLabel, popoutManager.controllers.count, PopoutWindowManager.maxConcurrentWindows))
        case let .alreadyOpen(existingId, existingLabel):
            pendingPopoutReloadPrompt = PendingPopoutReloadPrompt(
                appPackage: entry.appPackage,
                appLabel: existingLabel,
                appUserId: entry.appUserId,
                existingWindowId: existingId,
                onFocusExisting: { [weak self] in
                    self?.popoutManager.focusWindow(id: existingId)
                    if closePickerOnOpen { self?.closeRemoteAppPicker() }
                },
                onReloadWindowAndApp: { [weak self] in
                    guard let self else { return }
                    if closePickerOnOpen { self.closeRemoteAppPicker() }
                    Task { @MainActor in
                        self.popoutManager.closeWindow(id: existingId, sendDisconnect: true)
                        _ = await sleepCancellable(nanoseconds: PopoutTuning.reloadGapNs)
                        let reopened = self.openPopoutImmediate(entry: entry, context: context, forceReload: true)
                        if case .opened = reopened {
                            self.showToast(OpalL10n.format("app.popout.reloaded_fmt", entry.displayLabel))
                        }
                    }
                }
            )
        case .limitReached(let maxCount):
            let msg = OpalL10n.format("app.popout.limit_fmt", maxCount)
            bannerError = msg
            showToast(msg)
        case .invalidPackage:
            showToast(OpalL10n.text("app.popout.invalid_pkg"))
        }
    }

    @discardableResult
    private func openPopoutImmediate(
        entry: PopoutHistoryEntry,
        context: PopoutConnectionContext,
        forceReload: Bool
    ) -> PopoutOpenResult {
        let prefKey = entry.appKey
        let savedPref = popoutAppPrefsStore.preference(for: prefKey, deviceKey: entry.deviceKey)
        let savedPinned = savedPref?.pinnedOnTop
            ?? popoutHistory.first(where: { $0.id == entry.id })?.pinnedOnTop
            ?? entry.pinnedOnTop
        let res = popoutManager.openPopout(
            appPackage: entry.appPackage,
            appActivity: entry.appActivity,
            appLabel: entry.displayLabel,
            appUserId: entry.appUserId,
            appProfileType: entry.appProfileType,
            deviceKey: entry.deviceKey,
            deviceName: entry.deviceName,
            initialPinnedOnTop: savedPinned,
            initialPreference: savedPref,
            canReturnToDesktop: isAuthenticated,
            forceReloadExisting: forceReload,
            context: context,
            trustStore: discovery.trustStore,
            onPinChanged: { [weak self] pinned in
                guard let self else { return }
                self.popoutHistory = self.popoutHistoryStore.updatePinPreference(
                    appPackage: entry.appPackage,
                    deviceKey: entry.deviceKey,
                    pinnedOnTop: pinned,
                    appUserId: entry.appUserId
                )
            },
            onPreferenceChanged: { [weak self] change in
                self?.applyPopoutPreferenceChange(
                    change,
                    appPackage: prefKey,
                    deviceKey: entry.deviceKey
                )
            },
            onAuthTokenIssued: { [weak self] devName, fp, token in
                guard let self, !fp.isEmpty, !token.isEmpty else { return }
                self.discovery.upsertTrustedDevice(
                    deviceName: devName,
                    model: "",
                    certFingerprint: fp,
                    authToken: token,
                    lastHost: entry.host,
                    lastPort: entry.port
                )
            },
            onFilesDropped: { [weak self] urls in
                self?.handleDroppedFiles(urls)
            }
        )
        if case .opened = res {
            popoutHistory = popoutHistoryStore.recordLaunch(entry)
        }
        return res
    }

    private func resolveAndOpenHistoryEntry(_ entry: PopoutHistoryEntry, forceReload: Bool) async {
        statusMessage = OpalL10n.format("app.popout.launching_fmt", entry.displayLabel)
        defer { statusMessage = nil }

        do {
            let (connCtx, updatedEntry) = try await resolveConnectionContextForHistory(entry)
            if forceReload, let existing = popoutManager.existingController(for: entry.appPackage, userId: entry.appUserId) {
                popoutManager.closeWindow(id: existing.id, sendDisconnect: true)
                _ = await sleepCancellable(nanoseconds: PopoutTuning.reloadGapNs)
            }
            let result = openPopoutImmediate(entry: updatedEntry, context: connCtx, forceReload: forceReload)
            switch result {
            case .opened:
                showToast(
                    forceReload
                        ? OpalL10n.format("app.popout.reloaded_fmt", entry.displayLabel)
                        : OpalL10n.format("app.popout.opened_fmt", entry.displayLabel, popoutManager.controllers.count, PopoutWindowManager.maxConcurrentWindows)
                )
            case .limitReached(let maxCount):
                bannerError = OpalL10n.format("app.popout.limit_fmt", maxCount)
            case .alreadyOpen(let existingId, _):
                popoutManager.focusWindow(id: existingId)
            case .invalidPackage:
                break
            }
        } catch {
            OpalDeskLog.warn("popout", "Failed to launch history popout \(entry.appPackage): \(error.localizedDescription)")
            bannerError = OpalL10n.format("app.popout.launch_failed_fmt", entry.displayLabel, error.localizedDescription)
        }
    }

    private func resolveConnectionContextForHistory(
        _ entry: PopoutHistoryEntry
    ) async throws -> (PopoutConnectionContext, PopoutHistoryEntry) {
        var nextEntry = entry
        nextEntry.lastOpenedAtMs = Int64(Date().timeIntervalSince1970 * 1000)

        // 1. Reuse live main desktop session when targeting the same connected device.
        if isAuthenticated, let endpoint = activeSessionEndpoint, let conn = activeConnection {
            let matchesActive = (entry.adbSerial != nil && matchesAdbSerial(conn.transport, serial: entry.adbSerial!))
                || (!conn.fingerprint.isEmpty && entry.certFingerprint != nil
                    && FingerprintUtil.matches(actualFullHex: conn.fingerprint, expected: entry.certFingerprint!))
                || (entry.deviceName == conn.deviceName)
            if matchesActive {
                let ctx = makePopoutConnectionContext(
                    endpoint: endpoint,
                    transportHint: conn.transport.transportHint,
                    authToken: pendingAuth.authToken
                )
                return (ctx, nextEntry)
            }
        }

        // 2. USB / Wireless ADB device currently attached.
        if let serial = entry.adbSerial, !serial.isEmpty,
           let usbDev = discovery.usbDevices.first(where: { $0.serial == serial && $0.isReady })
        {
            let forward = try await ensureAdbForwardForPopout(serial: usbDev.serial)
            let ep = OpalCastEndpoint(
                target: .hostPort(host: "127.0.0.1", port: forward.localPort),
                security: .wiredLocalhostPlaintext,
                autoReconnect: true
            )
            let ctx = makePopoutConnectionContext(
                endpoint: ep,
                transportHint: usbDev.isWirelessAdb ? .wifiAdb : .usb,
                authToken: "",
                useEffectiveBitrate: false
            )
            return (ctx, nextEntry)
        }

        // 3. Discovered Bonjour device matching fingerprint or service name.
        if let bonjour = discovery.bonjourDevices.first(where: { dev in
            if let fp = entry.certFingerprint, !fp.isEmpty, !dev.fingerprintPrefix.isEmpty {
                if FingerprintUtil.matches(actualFullHex: fp, expected: dev.fingerprintPrefix) { return true }
            }
            if let svc = entry.bonjourServiceName, !svc.isEmpty, dev.serviceName == svc { return true }
            return dev.displayName == entry.deviceName
        }) {
            let trusted = discovery.trustStore.find(byFingerprint: bonjour.fingerprintPrefix)
                ?? (entry.certFingerprint.flatMap { discovery.trustStore.find(byFingerprint: $0) })
            let pin = trusted?.certFingerprint ?? entry.certFingerprint ?? bonjour.fingerprintPrefix
            guard pin.count >= FingerprintUtil.minimumPrefixHexLength else {
                throw NSError(domain: "OpalDeskPopout", code: -10, userInfo: [
                    NSLocalizedDescriptionKey: OpalL10n.text("app.popout.bad_fp")
                ])
            }
            let ep = OpalCastEndpoint(
                target: .bonjour(serviceName: bonjour.serviceName, domain: bonjour.domain),
                security: .tlsPinned(expectedFingerprint: pin),
                autoReconnect: true
            )
            let ctx = makePopoutConnectionContext(
                endpoint: ep,
                transportHint: .wifiTls,
                authToken: trusted?.authToken ?? "",
                useEffectiveBitrate: false
            )
            return (ctx, nextEntry)
        }

        // 4. Saved host:port or trusted device fallback.
        let trustedByFp = entry.certFingerprint.flatMap { discovery.trustStore.find(byFingerprint: $0) }
        let host = entry.host ?? trustedByFp?.lastHost ?? ""
        let port = entry.port ?? trustedByFp?.lastPort ?? OpalCastProtocol.defaultWifiPort
        let fp = trustedByFp?.certFingerprint ?? entry.certFingerprint ?? ""
        if !host.isEmpty && fp.count >= FingerprintUtil.minimumPrefixHexLength {
            let ep = OpalCastEndpoint(
                target: .hostPort(host: host, port: port),
                security: .tlsPinned(expectedFingerprint: fp),
                autoReconnect: true
            )
            let ctx = makePopoutConnectionContext(
                endpoint: ep,
                transportHint: .wifiTls,
                authToken: trustedByFp?.authToken ?? "",
                useEffectiveBitrate: false
            )
            return (ctx, nextEntry)
        }

        throw NSError(domain: "OpalDeskPopout", code: -11, userInfo: [
            NSLocalizedDescriptionKey: OpalL10n.format("app.popout.device_offline_fmt", entry.deviceName)
        ])
    }

    private func makeInitialPickerContext(for target: PopoutDeviceTarget) -> RemoteAppPickerContext {
        switch target {
        case .activeDesktop:
            let conn = activeConnection
            let serial: String?
            let host: String?
            let port: UInt16?
            let kind: String
            switch conn?.transport {
            case .wiredUSB(let s, _), .wirelessADB(let s, _):
                serial = s; host = nil; port = nil; kind = "usb"
            case .wirelessWiFi(_, let h, let p):
                serial = nil; host = h; port = p; kind = "wifi"
            case nil:
                serial = nil; host = nil; port = nil; kind = "wifi"
            }
            let devKey = PopoutDeviceKey.resolve(
                adbSerial: serial,
                fingerprint: conn?.fingerprint,
                fallback: conn?.deviceName ?? "active"
            )
            return RemoteAppPickerContext(
                target: target,
                deviceKey: devKey,
                deviceName: conn?.deviceName ?? OpalL10n.text("common.android_device"),
                allowLaunchInDesktop: true,
                apps: cachedDesktopApps,
                resolvedEndpoint: activeSessionEndpoint,
                resolvedTransportHint: conn?.transport.transportHint ?? .wifiTls,
                resolvedAuthToken: pendingAuth.authToken,
                resolvedFingerprint: conn?.fingerprint ?? "",
                resolvedAdbSerial: serial,
                resolvedHost: host,
                resolvedPort: port,
                resolvedTransportKind: kind
            )
        case .adb(let dev):
            return RemoteAppPickerContext(
                target: target,
                deviceKey: PopoutDeviceKey.adb(dev.serial),
                deviceName: dev.displayName,
                allowLaunchInDesktop: false,
                resolvedTransportHint: dev.isWirelessAdb ? .wifiAdb : .usb,
                resolvedAdbSerial: dev.serial,
                resolvedTransportKind: "usb"
            )
        case .bonjour(let dev):
            let trusted = discovery.trustStore.find(byFingerprint: dev.fingerprintPrefix)
            let fp = trusted?.certFingerprint ?? dev.fingerprintPrefix
            return RemoteAppPickerContext(
                target: target,
                deviceKey: PopoutDeviceKey.resolve(fingerprint: fp, fallback: "bonjour:\(dev.serviceName)"),
                deviceName: dev.displayName,
                allowLaunchInDesktop: false,
                resolvedTransportHint: .wifiTls,
                resolvedAuthToken: trusted?.authToken ?? "",
                resolvedFingerprint: fp,
                resolvedBonjourServiceName: dev.serviceName,
                resolvedBonjourDomain: dev.domain,
                resolvedTransportKind: "bonjour"
            )
        case .usbTether(let dev):
            let trusted = discovery.trustStore.find(byFingerprint: dev.certFingerprint)
                ?? discovery.trustStore.find(byHost: dev.host, port: dev.port)
            let fp = trusted?.certFingerprint ?? dev.certFingerprint
            return RemoteAppPickerContext(
                target: target,
                deviceKey: PopoutDeviceKey.resolve(fingerprint: fp, fallback: "host:\(dev.host):\(dev.port)"),
                deviceName: dev.displayName,
                allowLaunchInDesktop: false,
                resolvedTransportHint: .wifiTls,
                resolvedAuthToken: trusted?.authToken ?? "",
                resolvedFingerprint: fp,
                resolvedHost: dev.host,
                resolvedPort: dev.port,
                resolvedTransportKind: "wifi"
            )
        case .trusted(let dev):
            return RemoteAppPickerContext(
                target: target,
                deviceKey: PopoutDeviceKey.fingerprint(dev.certFingerprint),
                deviceName: dev.deviceName,
                allowLaunchInDesktop: false,
                resolvedTransportHint: .wifiTls,
                resolvedAuthToken: dev.authToken,
                resolvedFingerprint: dev.certFingerprint,
                resolvedHost: dev.lastHost,
                resolvedPort: dev.lastPort ?? OpalCastProtocol.defaultWifiPort,
                resolvedTransportKind: "wifi"
            )
        }
    }

    private func loadAppsForCurrentPicker() {
        guard let currentCtx = remoteAppPickerContext else { return }

        if currentCtx.target == .activeDesktop {
            guard isAuthenticated else {
                var failed = currentCtx
                failed.isLoading = false
                failed.errorMessage = OpalL10n.text("app.popout.main_unauth")
                remoteAppPickerContext = failed
                return
            }
            session.send(.listApps())
            return
        }

        Task { [weak self] in
            guard let self else { return }
            do {
                let (endpoint, hint, token, fp, serial, host, port) = try await self.resolveCatalogEndpoint(for: currentCtx.target)
                guard var ctx = self.remoteAppPickerContext, ctx.id == currentCtx.id else { return }
                ctx.resolvedEndpoint = endpoint
                ctx.resolvedTransportHint = hint
                ctx.resolvedAuthToken = token
                ctx.resolvedFingerprint = fp
                ctx.resolvedAdbSerial = serial
                ctx.resolvedHost = host
                ctx.resolvedPort = port
                self.remoteAppPickerContext = ctx

                let res = try await self.remoteAppCatalogFetcher.fetchApps(
                    endpoint: endpoint,
                    transportHint: hint,
                    clientId: self.discovery.trustStore.clientId,
                    authToken: token,
                    trustStore: self.discovery.trustStore,
                    onStatus: { [weak self] status in
                        guard let self, var c = self.remoteAppPickerContext, c.id == currentCtx.id else { return }
                        c.statusMessage = status
                        self.remoteAppPickerContext = c
                    }
                )
                guard var updated = self.remoteAppPickerContext, updated.id == currentCtx.id else { return }
                updated.apps = res.apps
                if !res.deviceName.isEmpty { updated.deviceName = res.deviceName }
                if !res.authToken.isEmpty { updated.resolvedAuthToken = res.authToken }
                if !res.verifiedFingerprint.isEmpty { updated.resolvedFingerprint = res.verifiedFingerprint }
                if res.isPermanentTrust && !res.authToken.isEmpty && !updated.resolvedFingerprint.isEmpty {
                    self.discovery.upsertTrustedDevice(
                        deviceName: updated.deviceName,
                        model: "",
                        certFingerprint: updated.resolvedFingerprint,
                        authToken: res.authToken,
                        lastHost: updated.resolvedHost,
                        lastPort: updated.resolvedPort
                    )
                }
                updated.isLoading = false
                updated.statusMessage = ""
                updated.errorMessage = nil
                self.remoteAppPickerContext = updated
                OpalDeskLog.info("popout", "Fetched \(res.apps.count) apps from \(updated.deviceName)")
            } catch {
                OpalDeskLog.warn("popout", "Failed to load remote app catalog: \(error.localizedDescription)")
                guard var failed = self.remoteAppPickerContext, failed.id == currentCtx.id else { return }
                failed.isLoading = false
                failed.statusMessage = ""
                failed.errorMessage = error.localizedDescription
                self.remoteAppPickerContext = failed
            }
        }
    }

    private func resolveCatalogEndpoint(
        for target: PopoutDeviceTarget
    ) async throws -> (OpalCastEndpoint, OpalCastTransportHint, String, String, String?, String?, UInt16?) {
        switch target {
        case .activeDesktop:
            guard let ep = activeSessionEndpoint else {
                throw NSError(domain: "OpalDeskCatalog", code: -1, userInfo: [NSLocalizedDescriptionKey: OpalL10n.text("app.popout.no_conn")])
            }
            return (ep, activeConnection?.transport.transportHint ?? .wifiTls, pendingAuth.authToken, activeConnection?.fingerprint ?? "", nil, nil, nil)

        case .adb(let dev):
            let fwd = try await ensureAdbForwardForPopout(serial: dev.serial)
            let ep = OpalCastEndpoint(
                target: .hostPort(host: "127.0.0.1", port: fwd.localPort),
                security: .wiredLocalhostPlaintext,
                autoReconnect: false
            )
            return (ep, dev.isWirelessAdb ? .wifiAdb : .usb, "", "", dev.serial, nil, nil)

        case .bonjour(let dev):
            let trusted = discovery.trustStore.find(byFingerprint: dev.fingerprintPrefix)
            var pin = trusted?.certFingerprint ?? dev.fingerprintPrefix
            if pin.count < FingerprintUtil.minimumPrefixHexLength {
                pin = try await OpalCastSession.probeCertificateFingerprint(nwEndpoint: dev.nwEndpoint)
            }
            let ep = OpalCastEndpoint(
                target: .bonjour(serviceName: dev.serviceName, domain: dev.domain),
                security: .tlsPinned(expectedFingerprint: pin),
                autoReconnect: false
            )
            return (ep, .wifiTls, trusted?.authToken ?? "", pin, nil, nil, nil)

        case .usbTether(let dev):
            let trusted = discovery.trustStore.find(byFingerprint: dev.certFingerprint)
                ?? discovery.trustStore.find(byHost: dev.host, port: dev.port)
            let pin = trusted?.certFingerprint ?? dev.certFingerprint
            let ep = OpalCastEndpoint(
                target: .hostPort(host: dev.host, port: dev.port),
                security: .tlsPinned(expectedFingerprint: pin),
                autoReconnect: false
            )
            return (ep, .wifiTls, trusted?.authToken ?? "", pin, nil, dev.host, dev.port)

        case .trusted(let dev):
            guard let host = dev.lastHost, !host.isEmpty else {
                throw NSError(domain: "OpalDeskCatalog", code: -2, userInfo: [NSLocalizedDescriptionKey: OpalL10n.text("app.popout.no_lan_ip")])
            }
            let port = dev.lastPort ?? OpalCastProtocol.defaultWifiPort
            let ep = OpalCastEndpoint(
                target: .hostPort(host: host, port: port),
                security: .tlsPinned(expectedFingerprint: dev.certFingerprint),
                autoReconnect: false
            )
            return (ep, .wifiTls, dev.authToken, dev.certFingerprint, nil, host, port)
        }
    }

    private func ensureAdbForwardForPopout(serial: String) async throws -> AdbForwardHandle {
        if let active = activeAdbForward, active.serial == serial {
            return active
        }
        if let existing = popoutAdbForwards[serial] {
            return existing
        }
        let fwd = try await discovery.adbManager.bootstrapDaemon(serial: serial)
        popoutAdbForwards[serial] = fwd
        return fwd
    }

    private func matchesAdbSerial(_ transport: ActiveTransportKind, serial: String) -> Bool {
        switch transport {
        case .wiredUSB(let s, _), .wirelessADB(let s, _): return s == serial
        case .wirelessWiFi: return false
        }
    }
}
