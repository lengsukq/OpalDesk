import OpalDeskCore
import Foundation
import Observation
import os

/// Coordinates USB/wireless ADB device polling, Bonjour (`_opaldesk._tcp`) LAN discovery,
/// BLE nearby discovery for trusted hosts, and persistent `TrustStore` records with honest error surfacing.
@MainActor
@Observable
public final class DiscoveryCoordinator {
    private static let idlePollIntervalNs: UInt64 = 3_000_000_000
    private static let activeCanvasPollIntervalNs: UInt64 = 10_000_000_000

    public let trustStore: TrustStore
    public let adbManager: AdbManager
    public let bonjourBrowser: BonjourBrowser
    public let bleScanner: BleNearbyScanner

    public private(set) var usbDevices: [AdbDevice] = []
    public private(set) var isScanningUSB: Bool = false
    public private(set) var adbBinaryPath: String?
    public private(set) var bonjourDevices: [DiscoveredBonjourDevice] = []
    public private(set) var usbTetherDevices: [DiscoveredUsbTetherDevice] = []
    public private(set) var isScanningUsbTether: Bool = false
    public private(set) var trustedDevices: [TrustedDevice] = []
    /// Trusted BLE-nearby devices filtered to exclude hosts already reachable on LAN Bonjour, USB ADB, or USB Tether.
    public private(set) var bleNearbyDevices: [DiscoveredBleDevice] = []
    public private(set) var bleAuthState: BleAuthorizationState = .unknown

    @ObservationIgnored
    private var usbPollTask: Task<Void, Never>?

    @ObservationIgnored
    private var rawBleDevices: [DiscoveredBleDevice] = []

    @ObservationIgnored
    public var onBonjourDevicesChanged: (@MainActor ([DiscoveredBonjourDevice]) -> Void)?

    @ObservationIgnored
    public var onTrustedDevicesChanged: (@MainActor ([TrustedDevice]) -> Void)?

    public init(customAdbPath: String) {
        let store = TrustStore()
        let adb = AdbManager(customAdbPath: customAdbPath)
        let scanner = BleNearbyScanner(trustStore: store)
        self.trustStore = store
        self.adbManager = adb
        self.bonjourBrowser = BonjourBrowser()
        self.bleScanner = scanner
        self.trustedDevices = store.allDevices()
        self.adbBinaryPath = adb.locateAdb()
        self.bleAuthState = scanner.authorizationState
    }

    public func start(
        customAdbPath: @escaping @MainActor () -> String,
        isCanvasActive: @escaping @MainActor () -> Bool,
        isUsbTetherEnabled: @escaping @MainActor () -> Bool = { false },
        isBleEnabled: @escaping @MainActor () -> Bool = { true }
    ) {
        refreshAdbPath(customPath: customAdbPath())
        bonjourBrowser.start { [weak self] devices in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.bonjourDevices = devices
                self.recomputeFilteredBleDevices()
                self.onBonjourDevicesChanged?(devices)
            }
        }
        bleScanner.setOnDevicesChanged { [weak self] devices in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.rawBleDevices = devices
                self.recomputeFilteredBleDevices()
            }
        }
        bleScanner.setOnAuthorizationChanged { [weak self] authState in
            Task { @MainActor [weak self] in
                self?.bleAuthState = authState
            }
        }
        if isBleEnabled() {
            bleScanner.startScanning()
        }

        usbPollTask?.cancel()
        usbPollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                let canvasActive = isCanvasActive()
                if !canvasActive {
                    await self.refreshUSBDevices(customPath: customAdbPath())
                    let tetherOn = isUsbTetherEnabled()
                    if tetherOn {
                        await self.refreshUsbTetherDevices(enabled: true)
                    } else if !self.usbTetherDevices.isEmpty {
                        self.usbTetherDevices = []
                        self.recomputeFilteredBleDevices()
                    }
                }
                let sleepNs = canvasActive ? Self.activeCanvasPollIntervalNs : Self.idlePollIntervalNs
                guard await sleepCancellable(nanoseconds: sleepNs) else { break }
            }
        }
    }

    public func stop() {
        usbPollTask?.cancel()
        usbPollTask = nil
        bonjourBrowser.stop()
        bleScanner.stopScanning()
    }

    public func setBleScanningEnabled(_ enabled: Bool) {
        if enabled {
            bleScanner.startScanning()
        } else {
            bleScanner.stopScanning()
            rawBleDevices = []
            bleNearbyDevices = []
        }
    }

    public func refreshAdbPath(customPath: String) {
        adbManager.setCustomAdbPath(customPath)
        adbBinaryPath = adbManager.locateAdb()
    }

    public func refreshUSBDevices(customPath: String) async {
        refreshAdbPath(customPath: customPath)
        guard adbBinaryPath != nil else {
            usbDevices = []
            recomputeFilteredBleDevices()
            return
        }
        isScanningUSB = true
        defer { isScanningUSB = false }
        do {
            usbDevices = try await adbManager.listDevices()
        } catch {
            OpalDeskLog.adb.warning("Failed to scan ADB devices: \(error.localizedDescription, privacy: .public)")
            usbDevices = []
        }
        recomputeFilteredBleDevices()
    }

    public func refreshTrustedDevices() {
        trustedDevices = trustStore.allDevices()
        recomputeFilteredBleDevices()
        onTrustedDevicesChanged?(trustedDevices)
    }

    public func refreshUsbTetherDevices(enabled: Bool) async {
        guard enabled else {
            usbTetherDevices = []
            isScanningUsbTether = false
            recomputeFilteredBleDevices()
            return
        }
        guard !isScanningUsbTether else { return }
        isScanningUsbTether = true
        defer { isScanningUsbTether = false }
        usbTetherDevices = await UsbTetherScanner.scan()
        recomputeFilteredBleDevices()
    }

    private func recomputeFilteredBleDevices() {
        bleNearbyDevices = rawBleDevices.filter { bleDevice in
            let fp = bleDevice.trustedDevice.certFingerprint
            let stillTrusted = trustedDevices.contains { trusted in
                !trusted.authToken.isEmpty && FingerprintUtil.matches(actualFullHex: trusted.certFingerprint, expected: fp)
            }
            guard stillTrusted else { return false }

            let visibleOnBonjour = bonjourDevices.contains { bonjour in
                if !bonjour.fingerprintPrefix.isEmpty,
                   FingerprintUtil.matches(actualFullHex: fp, expected: bonjour.fingerprintPrefix)
                {
                    return true
                }
                return !bonjour.displayName.isEmpty
                    && bonjour.displayName.caseInsensitiveCompare(bleDevice.trustedDevice.deviceName) == .orderedSame
            }
            if visibleOnBonjour { return false }

            let visibleOnUsb = usbDevices.contains { usb in
                guard usb.isReady else { return false }
                if !usb.displayName.isEmpty,
                   usb.displayName.caseInsensitiveCompare(bleDevice.trustedDevice.deviceName) == .orderedSame
                {
                    return true
                }
                if !usb.model.isEmpty && !bleDevice.trustedDevice.model.isEmpty,
                   usb.model.caseInsensitiveCompare(bleDevice.trustedDevice.model) == .orderedSame
                {
                    return true
                }
                return false
            }
            if visibleOnUsb { return false }

            let visibleOnTether = usbTetherDevices.contains { tether in
                !tether.certFingerprint.isEmpty
                    && FingerprintUtil.matches(actualFullHex: fp, expected: tether.certFingerprint)
            }
            return !visibleOnTether
        }
    }

    public func isTrusted(_ device: DiscoveredUsbTetherDevice) -> Bool {
        let normalized = FingerprintUtil.normalize(device.certFingerprint)
        guard normalized.count >= FingerprintUtil.minimumPrefixHexLength else { return false }
        return trustedDevices.contains { trusted in
            !trusted.authToken.isEmpty
                && FingerprintUtil.matches(actualFullHex: trusted.certFingerprint, expected: normalized)
        }
    }

    /// Observable trust check for Bonjour devices (automatically tracks `trustedDevices` in SwiftUI).
    public func isTrusted(_ device: DiscoveredBonjourDevice) -> Bool {
        let normalizedPrefix = FingerprintUtil.normalize(device.fingerprintPrefix)
        guard normalizedPrefix.count >= FingerprintUtil.minimumPrefixHexLength else { return false }
        return trustedDevices.contains { trusted in
            !trusted.authToken.isEmpty
                && FingerprintUtil.matches(actualFullHex: trusted.certFingerprint, expected: normalizedPrefix)
        }
    }

    /// Upserts a paired Android host record in `TrustStore` and refreshes observable state.
    @discardableResult
    public func upsertTrustedDevice(
        deviceName: String,
        model: String,
        certFingerprint: String,
        authToken: String,
        lastHost: String?,
        lastPort: UInt16?
    ) -> String? {
        let normalizedFp = FingerprintUtil.normalize(certFingerprint)
        guard !normalizedFp.isEmpty, !authToken.isEmpty else { return nil }
        let record = TrustedDevice(
            id: String(normalizedFp.prefix(FingerprintUtil.minimumPrefixHexLength)),
            deviceName: deviceName.isEmpty ? OpalL10n.text("common.android_device") : deviceName,
            model: model,
            certFingerprint: normalizedFp,
            authToken: authToken,
            lastHost: lastHost,
            lastPort: lastPort
        )
        return mutateTrustStore(context: "persist trusted device \(record.deviceName)", userMessagePrefix: OpalL10n.text("app.trust.save_failed")) {
            _ = try trustStore.upsert(record)
        }
    }

    @discardableResult
    public func revokeTrustedDevice(_ device: TrustedDevice) -> String? {
        mutateTrustStore(context: "revoke trusted device \(device.id)", userMessagePrefix: OpalL10n.text("app.trust.revoke_failed")) {
            try trustStore.revoke(id: device.id)
        }
    }

    @discardableResult
    public func revokeAllTrustedDevices() -> String? {
        mutateTrustStore(context: "revoke all trusted devices", userMessagePrefix: OpalL10n.text("app.trust.clear_failed")) {
            try trustStore.revokeAll()
        }
    }

    private func mutateTrustStore(
        context: String,
        userMessagePrefix: String,
        _ operation: () throws -> Void
    ) -> String? {
        do {
            try operation()
            refreshTrustedDevices()
            return nil
        } catch {
            OpalDeskLog.trust.error("Failed to \(context, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return "\(userMessagePrefix)：\(error.localizedDescription)"
        }
    }
}
