import OpalDeskCore
import Foundation
import Observation
import os

/// Coordinates USB/wireless ADB device polling, Bonjour (`_opaldesk._tcp`) LAN discovery,
/// and persistent `TrustStore` records with honest error logging and surfacing.
@MainActor
@Observable
public final class DiscoveryCoordinator {
    private static let idlePollIntervalNs: UInt64 = 3_000_000_000
    private static let activeCanvasPollIntervalNs: UInt64 = 10_000_000_000

    public let trustStore: TrustStore
    public let adbManager: AdbManager
    public let bonjourBrowser: BonjourBrowser

    public private(set) var usbDevices: [AdbDevice] = []
    public private(set) var isScanningUSB: Bool = false
    public private(set) var adbBinaryPath: String?
    public private(set) var bonjourDevices: [DiscoveredBonjourDevice] = []
    public private(set) var usbTetherDevices: [DiscoveredUsbTetherDevice] = []
    public private(set) var isScanningUsbTether: Bool = false
    public private(set) var trustedDevices: [TrustedDevice] = []

    @ObservationIgnored
    private var usbPollTask: Task<Void, Never>?

    public init(customAdbPath: String) {
        let store = TrustStore()
        let adb = AdbManager(customAdbPath: customAdbPath)
        self.trustStore = store
        self.adbManager = adb
        self.bonjourBrowser = BonjourBrowser()
        self.trustedDevices = store.allDevices()
        self.adbBinaryPath = adb.locateAdb()
    }

    public func start(
        customAdbPath: @escaping @MainActor () -> String,
        isCanvasActive: @escaping @MainActor () -> Bool,
        isUsbTetherEnabled: @escaping @MainActor () -> Bool = { false }
    ) {
        refreshAdbPath(customPath: customAdbPath())
        bonjourBrowser.start { [weak self] devices in
            Task { @MainActor [weak self] in self?.bonjourDevices = devices }
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
    }

    public func refreshAdbPath(customPath: String) {
        adbManager.setCustomAdbPath(customPath)
        adbBinaryPath = adbManager.locateAdb()
    }

    public func refreshUSBDevices(customPath: String) async {
        refreshAdbPath(customPath: customPath)
        guard adbBinaryPath != nil else {
            usbDevices = []
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
    }

    public func refreshTrustedDevices() {
        trustedDevices = trustStore.allDevices()
    }

    public func refreshUsbTetherDevices(enabled: Bool) async {
        guard enabled else {
            usbTetherDevices = []
            isScanningUsbTether = false
            return
        }
        guard !isScanningUsbTether else { return }
        isScanningUsbTether = true
        defer { isScanningUsbTether = false }
        usbTetherDevices = await UsbTetherScanner.scan()
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
            deviceName: deviceName.isEmpty ? "Android 设备" : deviceName,
            model: model,
            certFingerprint: normalizedFp,
            authToken: authToken,
            lastHost: lastHost,
            lastPort: lastPort
        )
        return mutateTrustStore(context: "persist trusted device \(record.deviceName)", userMessagePrefix: "保存信任设备失败") {
            _ = try trustStore.upsert(record)
        }
    }

    @discardableResult
    public func revokeTrustedDevice(_ device: TrustedDevice) -> String? {
        mutateTrustStore(context: "revoke trusted device \(device.id)", userMessagePrefix: "撤销信任设备失败") {
            try trustStore.revoke(id: device.id)
        }
    }

    @discardableResult
    public func revokeAllTrustedDevices() -> String? {
        mutateTrustStore(context: "revoke all trusted devices", userMessagePrefix: "清除全部信任设备失败") {
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
