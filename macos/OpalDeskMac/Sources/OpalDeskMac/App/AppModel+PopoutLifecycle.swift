import AppKit
import Foundation
import OpalDeskCore
import os

// MARK: - Remote App Picker, Catalog Fetching & Popout Transport Lifecycle

extension AppModel {
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

    // MARK: - Picker Context & Catalog Endpoint Resolution

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

    func ensureAdbForwardForPopout(serial: String) async throws -> AdbForwardHandle {
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

    func matchesAdbSerial(_ transport: ActiveTransportKind, serial: String) -> Bool {
        switch transport {
        case .wiredUSB(let s, _), .wirelessADB(let s, _): return s == serial
        case .wirelessWiFi: return false
        }
    }
}
