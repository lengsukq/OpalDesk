import AppKit
import Foundation
import OpalDeskCore
import os

// MARK: - Popout Launch, Reload & Target Resolution

extension AppModel {
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
}
