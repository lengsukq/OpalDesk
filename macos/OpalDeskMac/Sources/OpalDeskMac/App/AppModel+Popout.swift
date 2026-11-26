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

/// Secondary confirmation prompt shown whenever the user clicks an app whose floating window is already open.
public struct PendingPopoutReloadPrompt: Identifiable {
    public let id = UUID()
    public let appPackage: String
    public let appLabel: String
    public let existingWindowId: UUID
    public let onFocusExisting: @MainActor () -> Void
    public let onReloadWindowAndApp: @MainActor () -> Void
}

// MARK: - Standalone Floating Windows (Up to 3 Concurrent + Pin + History + Reload Confirmation)

extension AppModel {
    private static let reloadGapNs: UInt64 = 240_000_000

    public var activePopoutCount: Int {
        popoutManager.controllers.count
    }

    public func isPopoutActive(pkg: String) -> Bool {
        popoutManager.existingController(for: pkg) != nil
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

    /// Opens the remote app picker sheet for either the live desktop session or any discovered device in Device Hub.
    public func openRemoteAppPicker(for target: PopoutDeviceTarget) {
        remoteAppCatalogFetcher.cancel()
        var ctx = makeInitialPickerContext(for: target)
        ctx.isLoading = true
        ctx.statusMessage = "正在读取「\(ctx.deviceName)」的应用列表…"
        remoteAppPickerContext = ctx
        loadAppsForCurrentPicker()
    }

    public func refreshRemoteAppPicker() {
        guard var ctx = remoteAppPickerContext else { return }
        ctx.isLoading = true
        ctx.errorMessage = nil
        ctx.statusMessage = "正在刷新应用列表…"
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
        pickerContext: RemoteAppPickerContext
    ) {
        let cleanPkg = pkg.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPkg.isEmpty else { return }

        guard let endpoint = pickerContext.resolvedEndpoint else {
            showToast("当前设备连接上下文未就绪，请先刷新应用列表")
            return
        }

        let connCtx = PopoutConnectionContext(
            endpoint: endpoint,
            transportHint: pickerContext.resolvedTransportHint,
            clientId: discovery.trustStore.clientId,
            authToken: pickerContext.resolvedAuthToken,
            preferredCodec: preferences.preferredCodec,
            targetFPS: min(preferences.targetFPS, 60),
            bitrateKbps: min(effectiveBitrateKbps(), 10_000),
            mapCommandToCtrl: preferences.mapCommandToCtrl
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
            pinnedOnTop: popoutHistory.first(where: { $0.appPackage == cleanPkg && $0.deviceKey == pickerContext.deviceKey })?.pinnedOnTop ?? false
        )

        performPopoutOpenOrPromptReload(
            entry: historyTemplate,
            context: connCtx,
            closePickerOnOpen: true
        )
    }

    /// One-click launch from the Device Hub / Picker「历史 APP 小窗」bar without opening the full desktop.
    public func openPopoutFromHistory(_ entry: PopoutHistoryEntry) {
        if let existing = popoutManager.existingController(for: entry.appPackage) {
            pendingPopoutReloadPrompt = PendingPopoutReloadPrompt(
                appPackage: entry.appPackage,
                appLabel: entry.displayLabel,
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
            let msg = "最多同时打开 \(PopoutWindowManager.maxConcurrentWindows) 个悬浮小窗，请先关闭一个已有小窗"
            bannerError = msg
            showToast(msg)
            return
        }

        Task { await resolveAndOpenHistoryEntry(entry, forceReload: false) }
    }

    /// Migrates open popouts after Wireless ADB seamlessly upgrades to Wi-Fi TLS.
    func migratePopoutsAfterWifiUpgrade(endpoint: OpalCastEndpoint, authToken: String) {
        guard !popoutManager.controllers.isEmpty else { return }
        let upgraded = PopoutConnectionContext(
            endpoint: endpoint,
            transportHint: .wifiTls,
            clientId: discovery.trustStore.clientId,
            authToken: authToken,
            preferredCodec: preferences.preferredCodec,
            targetFPS: min(preferences.targetFPS, 60),
            bitrateKbps: min(effectiveBitrateKbps(), 10_000),
            mapCommandToCtrl: preferences.mapCommandToCtrl
        )
        popoutManager.migrateConnectionContext(to: upgraded)
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
            showToast("已弹出「\(entry.displayLabel)」悬浮小窗（\(popoutManager.controllers.count)/\(PopoutWindowManager.maxConcurrentWindows)）")
        case let .alreadyOpen(existingId, existingLabel):
            pendingPopoutReloadPrompt = PendingPopoutReloadPrompt(
                appPackage: entry.appPackage,
                appLabel: existingLabel,
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
                        _ = await sleepCancellable(nanoseconds: Self.reloadGapNs)
                        let reopened = self.openPopoutImmediate(entry: entry, context: context, forceReload: true)
                        if case .opened = reopened {
                            self.showToast("已重载「\(entry.displayLabel)」小窗与应用")
                        }
                    }
                }
            )
        case .limitReached(let maxCount):
            let msg = "最多同时打开 \(maxCount) 个悬浮小窗，请先关闭一个已有小窗"
            bannerError = msg
            showToast(msg)
        case .invalidPackage:
            showToast("无效的应用包名")
        }
    }

    @discardableResult
    private func openPopoutImmediate(
        entry: PopoutHistoryEntry,
        context: PopoutConnectionContext,
        forceReload: Bool
    ) -> PopoutOpenResult {
        let savedPinned = popoutHistory.first(where: { $0.id == entry.id })?.pinnedOnTop ?? entry.pinnedOnTop
        let res = popoutManager.openPopout(
            appPackage: entry.appPackage,
            appActivity: entry.appActivity,
            appLabel: entry.displayLabel,
            deviceKey: entry.deviceKey,
            deviceName: entry.deviceName,
            initialPinnedOnTop: savedPinned,
            canReturnToDesktop: isAuthenticated,
            forceReloadExisting: forceReload,
            context: context,
            trustStore: discovery.trustStore,
            onPinChanged: { [weak self] pinned in
                guard let self else { return }
                self.popoutHistory = self.popoutHistoryStore.updatePinPreference(
                    appPackage: entry.appPackage,
                    deviceKey: entry.deviceKey,
                    pinnedOnTop: pinned
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
                self?.sendFiles(urls)
            }
        )
        if case .opened = res {
            popoutHistory = popoutHistoryStore.recordLaunch(entry)
        }
        return res
    }

    private func resolveAndOpenHistoryEntry(_ entry: PopoutHistoryEntry, forceReload: Bool) async {
        statusMessage = "正在拉起「\(entry.displayLabel)」悬浮小窗…"
        defer { statusMessage = nil }

        do {
            let (connCtx, updatedEntry) = try await resolveConnectionContextForHistory(entry)
            if forceReload, let existing = popoutManager.existingController(for: entry.appPackage) {
                popoutManager.closeWindow(id: existing.id, sendDisconnect: true)
                _ = await sleepCancellable(nanoseconds: Self.reloadGapNs)
            }
            let result = openPopoutImmediate(entry: updatedEntry, context: connCtx, forceReload: forceReload)
            switch result {
            case .opened:
                showToast(
                    forceReload
                        ? "已重载「\(entry.displayLabel)」小窗与应用"
                        : "已打开「\(entry.displayLabel)」悬浮小窗（\(popoutManager.controllers.count)/\(PopoutWindowManager.maxConcurrentWindows)）"
                )
            case .limitReached(let maxCount):
                bannerError = "最多同时打开 \(maxCount) 个悬浮小窗，请先关闭一个已有小窗"
            case .alreadyOpen(let existingId, _):
                popoutManager.focusWindow(id: existingId)
            case .invalidPackage:
                break
            }
        } catch {
            OpalDeskLog.warn("popout", "Failed to launch history popout \(entry.appPackage): \(error.localizedDescription)")
            bannerError = "拉起「\(entry.displayLabel)」失败：\(error.localizedDescription)"
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
                let ctx = PopoutConnectionContext(
                    endpoint: endpoint,
                    transportHint: conn.transport.transportHint,
                    clientId: discovery.trustStore.clientId,
                    authToken: pendingAuth.authToken,
                    preferredCodec: preferences.preferredCodec,
                    targetFPS: min(preferences.targetFPS, 60),
                    bitrateKbps: min(effectiveBitrateKbps(), 10_000),
                    mapCommandToCtrl: preferences.mapCommandToCtrl
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
            let ctx = PopoutConnectionContext(
                endpoint: ep,
                transportHint: usbDev.isWirelessAdb ? .wifiAdb : .usb,
                clientId: discovery.trustStore.clientId,
                authToken: "",
                preferredCodec: preferences.preferredCodec,
                targetFPS: min(preferences.targetFPS, 60),
                bitrateKbps: min(preferences.bitrateKbps, 10_000),
                mapCommandToCtrl: preferences.mapCommandToCtrl
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
                    NSLocalizedDescriptionKey: "设备证书指纹不完整，请先从设备列表点击「应用小窗」重新建立信任"
                ])
            }
            let ep = OpalCastEndpoint(
                target: .bonjour(serviceName: bonjour.serviceName, domain: bonjour.domain),
                security: .tlsPinned(expectedFingerprint: pin),
                autoReconnect: true
            )
            let ctx = PopoutConnectionContext(
                endpoint: ep,
                transportHint: .wifiTls,
                clientId: discovery.trustStore.clientId,
                authToken: trusted?.authToken ?? "",
                preferredCodec: preferences.preferredCodec,
                targetFPS: min(preferences.targetFPS, 60),
                bitrateKbps: min(preferences.bitrateKbps, 10_000),
                mapCommandToCtrl: preferences.mapCommandToCtrl
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
            let ctx = PopoutConnectionContext(
                endpoint: ep,
                transportHint: .wifiTls,
                clientId: discovery.trustStore.clientId,
                authToken: trustedByFp?.authToken ?? "",
                preferredCodec: preferences.preferredCodec,
                targetFPS: min(preferences.targetFPS, 60),
                bitrateKbps: min(preferences.bitrateKbps, 10_000),
                mapCommandToCtrl: preferences.mapCommandToCtrl
            )
            return (ctx, nextEntry)
        }

        throw NSError(domain: "OpalDeskPopout", code: -11, userInfo: [
            NSLocalizedDescriptionKey: "未找到在线设备「\(entry.deviceName)」，请确认手机已连接 USB 或开启无线共享服务"
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
            let devKey = serial ?? (conn?.fingerprint.isEmpty == false ? String(conn!.fingerprint.prefix(16)) : (conn?.deviceName ?? "active"))
            return RemoteAppPickerContext(
                target: target,
                deviceKey: devKey,
                deviceName: conn?.deviceName ?? "Android 设备",
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
                deviceKey: "adb:\(dev.serial)",
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
                deviceKey: fp.isEmpty ? "bonjour:\(dev.serviceName)" : "fp:\(fp.prefix(16))",
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
                deviceKey: fp.isEmpty ? "host:\(dev.host):\(dev.port)" : "fp:\(fp.prefix(16))",
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
                deviceKey: "fp:\(dev.certFingerprint.prefix(16))",
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
                failed.errorMessage = "当前主投屏会话未认证完成"
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
                throw NSError(domain: "OpalDeskCatalog", code: -1, userInfo: [NSLocalizedDescriptionKey: "当前无活动连接"])
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
                throw NSError(domain: "OpalDeskCatalog", code: -2, userInfo: [NSLocalizedDescriptionKey: "该已信任设备未记录局域网 IP，请等待局域网自动发现"])
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
