@preconcurrency import CoreBluetooth
import Foundation
import os

// MARK: - BLE Nearby Models & Errors

/// Safe authorization/power snapshot surfaced by `BleNearbyScanner` without crashing when BT is unavailable.
public enum BleAuthorizationState: String, Sendable, Equatable, CaseIterable {
    case unknown
    case poweredOff
    case unauthorized
    case ready
    case unsupported
}

/// A trusted nearby Android OpalDesk host discovered via BLE advertising.
public struct DiscoveredBleDevice: Sendable, Equatable, Identifiable {
    /// CoreBluetooth peripheral identifier UUID string.
    public let id: String
    public let trustedDevice: TrustedDevice
    /// Leading 16 lowercase hex chars of the device's SHA-256 certificate fingerprint.
    public let fingerprintPrefix: String
    public let wifiPort: UInt16
    public let isLanShareActive: Bool
    public let isHotspotActive: Bool
    public let supportsHotspotTrigger: Bool
    /// Battery percentage `0...100`, or `nil` when wire byte is `0xFF`.
    public let batteryPercent: Int?
    public let rssi: Int
    public let lastSeenAt: Date

    public init(
        id: String,
        trustedDevice: TrustedDevice,
        fingerprintPrefix: String,
        wifiPort: UInt16,
        isLanShareActive: Bool,
        isHotspotActive: Bool,
        supportsHotspotTrigger: Bool,
        batteryPercent: Int?,
        rssi: Int,
        lastSeenAt: Date
    ) {
        self.id = id
        self.trustedDevice = trustedDevice
        self.fingerprintPrefix = fingerprintPrefix
        self.wifiPort = wifiPort
        self.isLanShareActive = isLanShareActive
        self.isHotspotActive = isHotspotActive
        self.supportsHotspotTrigger = supportsHotspotTrigger
        self.batteryPercent = batteryPercent
        self.rssi = rssi
        self.lastSeenAt = lastSeenAt
    }
}

public enum BleNearbyError: Error, Sendable, Equatable, LocalizedError {
    case bluetoothUnavailable(BleAuthorizationState)
    case deviceNotFound(String)
    case missingAuthToken
    case connectionFailed(String)
    case gattDiscoveryFailed(String)
    case invalidChallengeNonce
    case responseDecodeFailed(String)
    case timedOut
    case cancelled

    public var errorDescription: String? {
        switch self {
        case .bluetoothUnavailable(let state):
            return "蓝牙当前不可用（状态：\(state.rawValue)）"
        case .deviceNotFound(let id):
            return "未在附近找到目标蓝牙设备（\(id)）"
        case .missingAuthToken:
            return "该设备缺少永久信任凭证，请先完成一次配对"
        case .connectionFailed(let detail):
            return "蓝牙连接失败：\(detail)"
        case .gattDiscoveryFailed(let detail):
            return "蓝牙 GATT 服务发现失败：\(detail)"
        case .invalidChallengeNonce:
            return "设备返回的挑战随机数无效"
        case .responseDecodeFailed(let detail):
            return "无法解析热点触发响应：\(detail)"
        case .timedOut:
            return "蓝牙触发个人热点超时（15 秒）"
        case .cancelled:
            return "蓝牙热点触发操作已取消"
        }
    }
}

// MARK: - BleNearbyScanner

/// Passively scans for nearby paired Android OpalDesk devices advertising BLE service
/// `7A4F0001-6F70-616C-6465-736B-000000000001`, filters strictly against `TrustStore`,
/// expires unseen devices after 20 seconds, and performs authenticated GATT hotspot triggers.
public final class BleNearbyScanner: Sendable {
    private static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "ble")
    public static let defaultStaleIntervalSeconds: TimeInterval = 20.0
    public static let defaultTriggerTimeoutSeconds: TimeInterval = 15.0

    private static let serviceCBUUID = CBUUID(string: BleNearbyProtocol.serviceUUIDString)
    private static let challengeCBUUID = CBUUID(string: BleNearbyProtocol.challengeCharacteristicUUIDString)
    private static let commandCBUUID = CBUUID(string: BleNearbyProtocol.commandCharacteristicUUIDString)

    private struct PendingHotspotTrigger {
        let peripheralId: String
        let peripheral: CBPeripheral
        let clientId: String
        let authToken: String
        var challengeCharacteristic: CBCharacteristic?
        var commandCharacteristic: CBCharacteristic?
        var commandWritten: Bool = false
        var continuation: CheckedContinuation<HotspotCommandResponse, Error>?
        var timeoutTask: Task<Void, Never>?
    }

    private struct MutableState {
        var authorizationState: BleAuthorizationState = .unknown
        var isScanningRequested: Bool = false
        var centralManager: CBCentralManager?
        var delegateProxy: CoreBluetoothProxy?
        var devicesById: [String: DiscoveredBleDevice] = [:]
        var peripheralsById: [String: CBPeripheral] = [:]
        var pendingTrigger: PendingHotspotTrigger?
        var pruneTask: Task<Void, Never>?
        var onDevicesChanged: (@Sendable ([DiscoveredBleDevice]) -> Void)?
        var onAuthorizationChanged: (@Sendable (BleAuthorizationState) -> Void)?
    }

    private let trustStore: TrustStore
    private let staleIntervalSeconds: TimeInterval
    private let triggerTimeoutSeconds: TimeInterval
    private let nowProvider: @Sendable () -> Date
    private let bleQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.ble", qos: .utility)
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    public init(
        trustStore: TrustStore,
        staleIntervalSeconds: TimeInterval = BleNearbyScanner.defaultStaleIntervalSeconds,
        triggerTimeoutSeconds: TimeInterval = BleNearbyScanner.defaultTriggerTimeoutSeconds,
        nowProvider: @escaping @Sendable () -> Date = { Date() },
        onDevicesChanged: (@Sendable ([DiscoveredBleDevice]) -> Void)? = nil,
        onAuthorizationChanged: (@Sendable (BleAuthorizationState) -> Void)? = nil
    ) {
        self.trustStore = trustStore
        self.staleIntervalSeconds = max(1.0, staleIntervalSeconds)
        self.triggerTimeoutSeconds = max(1.0, triggerTimeoutSeconds)
        self.nowProvider = nowProvider
        self.state.withLock { s in
            s.onDevicesChanged = onDevicesChanged
            s.onAuthorizationChanged = onAuthorizationChanged
        }
    }

    deinit {
        stopScanning()
    }

    // MARK: - Observable State

    public var authorizationState: BleAuthorizationState {
        state.withLock { $0.authorizationState }
    }

    public var discoveredDevices: [DiscoveredBleDevice] {
        state.withLock { Self.sortedDevices(from: $0.devicesById) }
    }

    public func setOnDevicesChanged(_ handler: (@Sendable ([DiscoveredBleDevice]) -> Void)?) {
        state.withLock { $0.onDevicesChanged = handler }
    }

    public func setOnAuthorizationChanged(_ handler: (@Sendable (BleAuthorizationState) -> Void)?) {
        state.withLock { $0.onAuthorizationChanged = handler }
    }

    // MARK: - Scanning Lifecycle

    /// Begins passive BLE scanning for OpalDesk nearby beacons. Safe to call even if Bluetooth
    /// is unauthorized, powered off, or unsupported on the current Mac.
    public func startScanning() {
        let shouldCreateCentral: Bool = state.withLock { s in
            s.isScanningRequested = true
            return s.centralManager == nil
        }

        startPruneLoopIfNeeded()

        bleQueue.async { [weak self] in
            guard let self else { return }
            if shouldCreateCentral {
                let proxy = CoreBluetoothProxy(owner: self)
                let central = CBCentralManager(
                    delegate: proxy,
                    queue: self.bleQueue,
                    options: [CBCentralManagerOptionShowPowerAlertKey: false]
                )
                self.state.withLock { s in
                    s.delegateProxy = proxy
                    s.centralManager = central
                }
            } else {
                let central = self.state.withLock { $0.centralManager }
                if let central, central.state == .poweredOn {
                    central.scanForPeripherals(
                        withServices: [Self.serviceCBUUID],
                        options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
                    )
                }
            }
        }
    }

    /// Stops passive BLE scanning, cancels any pending GATT trigger, and stops stale-entry pruning.
    public func stopScanning() {
        let (central, pruneTask): (CBCentralManager?, Task<Void, Never>?) = state.withLock { s in
            s.isScanningRequested = false
            let t = s.pruneTask
            s.pruneTask = nil
            return (s.centralManager, t)
        }
        pruneTask?.cancel()
        failPendingTrigger(with: .cancelled)

        if let central {
            bleQueue.async {
                if central.isScanning {
                    central.stopScan()
                }
            }
        }
    }

    // MARK: - Pure Advertisement Ingestion & Stale Expiration (Testable Without Hardware)

    /// Ingests raw manufacturer data from a BLE advertisement, matching against `TrustStore`
    /// and ignoring untrusted devices. Returns the matched `DiscoveredBleDevice` if trusted.
    @discardableResult
    public func ingestAdvertisement(
        peripheralId: String,
        manufacturerData: Data,
        rssi: Int
    ) -> DiscoveredBleDevice? {
        guard !peripheralId.isEmpty,
              let payload = BleNearbyProtocol.decodeManufacturerData(manufacturerData),
              let trusted = trustStore.find(byFingerprint: payload.fingerprintPrefix)
        else {
            return nil
        }

        let now = nowProvider()
        let device = DiscoveredBleDevice(
            id: peripheralId,
            trustedDevice: trusted,
            fingerprintPrefix: payload.fingerprintPrefix,
            wifiPort: payload.wifiPort,
            isLanShareActive: payload.isLanShareActive,
            isHotspotActive: payload.isHotspotActive,
            supportsHotspotTrigger: payload.supportsHotspotTrigger,
            batteryPercent: payload.batteryPercent,
            rssi: rssi,
            lastSeenAt: now
        )

        let (snapshot, callback): ([DiscoveredBleDevice], (@Sendable ([DiscoveredBleDevice]) -> Void)?) = state.withLock { s in
            s.devicesById[peripheralId] = device
            return (Self.sortedDevices(from: s.devicesById), s.onDevicesChanged)
        }
        callback?(snapshot)
        return device
    }

    /// Removes discovered BLE devices that have not been seen within `staleIntervalSeconds` (default 20s).
    @discardableResult
    public func pruneStaleDevices() -> Int {
        let now = nowProvider()
        let (removedCount, snapshot, callback): (Int, [DiscoveredBleDevice], (@Sendable ([DiscoveredBleDevice]) -> Void)?) = state.withLock { s in
            let staleIds = s.devicesById.compactMap { id, dev -> String? in
                now.timeIntervalSince(dev.lastSeenAt) > staleIntervalSeconds ? id : nil
            }
            guard !staleIds.isEmpty else {
                return (0, [], nil)
            }
            for id in staleIds {
                s.devicesById.removeValue(forKey: id)
                if s.pendingTrigger?.peripheralId != id {
                    s.peripheralsById.removeValue(forKey: id)
                }
            }
            return (staleIds.count, Self.sortedDevices(from: s.devicesById), s.onDevicesChanged)
        }
        if removedCount > 0 {
            callback?(snapshot)
        }
        return removedCount
    }

    // MARK: - Authenticated GATT Hotspot Trigger

    /// Connects to a nearby trusted Android peripheral over BLE GATT, reads its 16-byte challenge nonce,
    /// signs `OPAL_HOTSPOT_V1:<clientId>:<nonceHex>` with `HMAC-SHA256(sha256HexLower(authToken))`,
    /// writes the JSON command, and awaits the `HotspotCommandResponse` with a 15-second timeout.
    public func triggerHotspot(
        for device: DiscoveredBleDevice,
        clientId: String
    ) async throws -> HotspotCommandResponse {
        guard !device.trustedDevice.authToken.isEmpty else {
            throw BleNearbyError.missingAuthToken
        }

        let (authState, central, peripheral): (BleAuthorizationState, CBCentralManager?, CBPeripheral?) = state.withLock { s in
            (s.authorizationState, s.centralManager, s.peripheralsById[device.id])
        }

        guard authState == .ready, let central else {
            throw BleNearbyError.bluetoothUnavailable(authState)
        }
        guard let peripheral else {
            throw BleNearbyError.deviceNotFound(device.id)
        }

        // Cancel any previous in-flight trigger before starting a new one.
        failPendingTrigger(with: .cancelled)

        let timeoutNs = UInt64(triggerTimeoutSeconds * 1_000_000_000)

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<HotspotCommandResponse, Error>) in
                let timeoutTask = Task.detached(priority: .utility) { [weak self] in
                    do {
                        try await Task.sleep(nanoseconds: timeoutNs)
                    } catch {
                        return
                    }
                    self?.failPendingTrigger(with: .timedOut)
                }

                let registered: Bool = state.withLock { s in
                    if Task.isCancelled { return false }
                    s.pendingTrigger = PendingHotspotTrigger(
                        peripheralId: device.id,
                        peripheral: peripheral,
                        clientId: clientId,
                        authToken: device.trustedDevice.authToken,
                        continuation: continuation,
                        timeoutTask: timeoutTask
                    )
                    return true
                }

                guard registered else {
                    timeoutTask.cancel()
                    continuation.resume(throwing: BleNearbyError.cancelled)
                    return
                }

                bleQueue.async { [weak self] in
                    guard let self else { return }
                    let proxy = self.state.withLock { $0.delegateProxy }
                    peripheral.delegate = proxy
                    central.connect(peripheral, options: nil)
                }
            }
        } onCancel: { [weak self] in
            self?.failPendingTrigger(with: .cancelled)
        }
    }

    // MARK: - CoreBluetooth Delegate Callbacks (on `bleQueue`)

    fileprivate func handleCentralStateUpdate(_ central: CBCentralManager) {
        let mapped = Self.mapAuthorizationState(central.state)
        let (shouldScan, callback): (Bool, (@Sendable (BleAuthorizationState) -> Void)?) = state.withLock { s in
            s.authorizationState = mapped
            return (s.isScanningRequested, s.onAuthorizationChanged)
        }

        callback?(mapped)

        if mapped == .ready && shouldScan {
            central.scanForPeripherals(
                withServices: [Self.serviceCBUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
        } else if mapped != .ready {
            failPendingTrigger(with: .bluetoothUnavailable(mapped))
        }
    }

    fileprivate func handleDiscoveredPeripheral(
        _ peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi: NSNumber
    ) {
        guard let mfgData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data else {
            return
        }
        let id = peripheral.identifier.uuidString
        let rssiInt = rssi.intValue
        if ingestAdvertisement(peripheralId: id, manufacturerData: mfgData, rssi: rssiInt) != nil {
            state.withLock { $0.peripheralsById[id] = peripheral }
        }
    }

    fileprivate func handleDidConnect(_ peripheral: CBPeripheral) {
        let isPending: Bool = state.withLock { $0.pendingTrigger?.peripheral.identifier == peripheral.identifier }
        guard isPending else { return }
        peripheral.discoverServices([Self.serviceCBUUID])
    }

    fileprivate func handleDidFailToConnect(_ peripheral: CBPeripheral, error: Error?) {
        let isPending: Bool = state.withLock { $0.pendingTrigger?.peripheral.identifier == peripheral.identifier }
        guard isPending else { return }
        failPendingTrigger(with: .connectionFailed(error?.localizedDescription ?? "无法建立蓝牙连接"))
    }

    fileprivate func handleDidDisconnect(_ peripheral: CBPeripheral, error: Error?) {
        let isPending: Bool = state.withLock { $0.pendingTrigger?.peripheral.identifier == peripheral.identifier }
        guard isPending else { return }
        let detail = error?.localizedDescription ?? "外设提前断开连接"
        failPendingTrigger(with: .connectionFailed(detail))
    }

    fileprivate func handleDidDiscoverServices(_ peripheral: CBPeripheral, error: Error?) {
        let isPending: Bool = state.withLock { $0.pendingTrigger?.peripheral.identifier == peripheral.identifier }
        guard isPending else { return }
        if let error {
            failPendingTrigger(with: .gattDiscoveryFailed(error.localizedDescription))
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceCBUUID }) else {
            failPendingTrigger(with: .gattDiscoveryFailed("未找到 OpalDesk BLE 服务"))
            return
        }
        peripheral.discoverCharacteristics([Self.challengeCBUUID, Self.commandCBUUID], for: service)
    }

    fileprivate func handleDidDiscoverCharacteristics(_ peripheral: CBPeripheral, service: CBService, error: Error?) {
        let isPending: Bool = state.withLock { $0.pendingTrigger?.peripheral.identifier == peripheral.identifier }
        guard isPending else { return }
        if let error {
            failPendingTrigger(with: .gattDiscoveryFailed(error.localizedDescription))
            return
        }

        let characteristics = service.characteristics ?? []
        guard let challengeChar = characteristics.first(where: { $0.uuid == Self.challengeCBUUID }),
              let commandChar = characteristics.first(where: { $0.uuid == Self.commandCBUUID })
        else {
            failPendingTrigger(with: .gattDiscoveryFailed("缺少 Challenge 或 Command GATT 特征"))
            return
        }

        state.withLock { s in
            s.pendingTrigger?.challengeCharacteristic = challengeChar
            s.pendingTrigger?.commandCharacteristic = commandChar
        }

        if commandChar.properties.contains(.notify) || commandChar.properties.contains(.indicate) {
            peripheral.setNotifyValue(true, for: commandChar)
        }
        peripheral.readValue(for: challengeChar)
    }

    fileprivate func handleDidUpdateValue(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
        let pendingInfo: (clientId: String, authToken: String, commandChar: CBCharacteristic?, commandWritten: Bool)? = state.withLock { s in
            guard let t = s.pendingTrigger, t.peripheral.identifier == peripheral.identifier else { return nil }
            return (t.clientId, t.authToken, t.commandCharacteristic, t.commandWritten)
        }
        guard let pendingInfo else { return }

        if let error {
            failPendingTrigger(with: .gattDiscoveryFailed(error.localizedDescription))
            return
        }

        guard let value = characteristic.value, !value.isEmpty else {
            if characteristic.uuid == Self.challengeCBUUID {
                failPendingTrigger(with: .invalidChallengeNonce)
            }
            return
        }

        if characteristic.uuid == Self.challengeCBUUID {
            let nonceHex: String
            if value.count == 16 {
                nonceHex = BleNearbyProtocol.hexString(from: value)
            } else if value.count == 32,
                      let ascii = String(data: value, encoding: .utf8),
                      FingerprintUtil.normalize(ascii).count == 32
            {
                nonceHex = FingerprintUtil.normalize(ascii)
            } else if value.count >= 8 {
                nonceHex = BleNearbyProtocol.hexString(from: value)
            } else {
                failPendingTrigger(with: .invalidChallengeNonce)
                return
            }

            guard let commandChar = pendingInfo.commandChar else {
                failPendingTrigger(with: .gattDiscoveryFailed("Command GATT 特征未就绪"))
                return
            }

            let request = HotspotCommandRequest.triggerHotspot(
                clientId: pendingInfo.clientId,
                nonceHex: nonceHex,
                authToken: pendingInfo.authToken
            )
            let payloadData: Data
            do {
                payloadData = try request.encode()
            } catch {
                failPendingTrigger(with: .responseDecodeFailed(error.localizedDescription))
                return
            }

            state.withLock { $0.pendingTrigger?.commandWritten = true }
            let writeType: CBCharacteristicWriteType = commandChar.properties.contains(.write) ? .withResponse : .withoutResponse
            peripheral.writeValue(payloadData, for: commandChar, type: writeType)
            if writeType == .withoutResponse && !(commandChar.properties.contains(.notify) || commandChar.properties.contains(.indicate)) {
                peripheral.readValue(for: commandChar)
            }
            return
        }

        if characteristic.uuid == Self.commandCBUUID && pendingInfo.commandWritten {
            do {
                let response = try HotspotCommandResponse.decode(from: value)
                completePendingTrigger(with: .success(response))
            } catch {
                failPendingTrigger(with: .responseDecodeFailed(error.localizedDescription))
            }
        }
    }

    fileprivate func handleDidWriteValue(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
        let isPending: Bool = state.withLock { $0.pendingTrigger?.peripheral.identifier == peripheral.identifier }
        guard isPending, characteristic.uuid == Self.commandCBUUID else { return }

        if let error {
            failPendingTrigger(with: .gattDiscoveryFailed("写入热点命令失败：\(error.localizedDescription)"))
            return
        }

        // If peripheral doesn't notify on commandCharacteristic, explicitly read response back.
        if !(characteristic.properties.contains(.notify) || characteristic.properties.contains(.indicate)) {
            peripheral.readValue(for: characteristic)
        }
    }

    // MARK: - Private Lifecycle Helpers

    private func startPruneLoopIfNeeded() {
        let shouldStart: Bool = state.withLock { s in
            guard s.pruneTask == nil else { return false }
            let intervalNs = UInt64(min(max(staleIntervalSeconds / 4.0, 1.0), 5.0) * 1_000_000_000)
            s.pruneTask = Task.detached(priority: .utility) { [weak self] in
                while !Task.isCancelled {
                    do {
                        try await Task.sleep(nanoseconds: intervalNs)
                    } catch {
                        return
                    }
                    guard let self, !Task.isCancelled else { return }
                    _ = self.pruneStaleDevices()
                }
            }
            return true
        }
        _ = shouldStart
    }

    private func failPendingTrigger(with error: BleNearbyError) {
        completePendingTrigger(with: .failure(error))
    }

    private func completePendingTrigger(with result: Result<HotspotCommandResponse, Error>) {
        let (pending, central): (PendingHotspotTrigger?, CBCentralManager?) = state.withLock { s in
            let p = s.pendingTrigger
            s.pendingTrigger = nil
            return (p, s.centralManager)
        }
        guard let pending else { return }
        pending.timeoutTask?.cancel()
        pending.continuation?.resume(with: result)

        if let central {
            let peripheral = pending.peripheral
            bleQueue.async {
                peripheral.delegate = nil
                central.cancelPeripheralConnection(peripheral)
            }
        }
    }

    private static func mapAuthorizationState(_ cbState: CBManagerState) -> BleAuthorizationState {
        switch cbState {
        case .poweredOn: return .ready
        case .poweredOff: return .poweredOff
        case .unauthorized: return .unauthorized
        case .unsupported: return .unsupported
        case .unknown, .resetting: return .unknown
        @unknown default: return .unknown
        }
    }

    private static func sortedDevices(from map: [String: DiscoveredBleDevice]) -> [DiscoveredBleDevice] {
        map.values.sorted { lhs, rhs in
            if lhs.rssi != rhs.rssi {
                return lhs.rssi > rhs.rssi
            }
            return lhs.id < rhs.id
        }
    }
}

// MARK: - CoreBluetooth Objective-C Delegate Proxy

private final class CoreBluetoothProxy: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    private weak var owner: BleNearbyScanner?

    init(owner: BleNearbyScanner) {
        self.owner = owner
        super.init()
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        owner?.handleCentralStateUpdate(central)
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        owner?.handleDiscoveredPeripheral(peripheral, advertisementData: advertisementData, rssi: RSSI)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        owner?.handleDidConnect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        owner?.handleDidFailToConnect(peripheral, error: error)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        owner?.handleDidDisconnect(peripheral, error: error)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        owner?.handleDidDiscoverServices(peripheral, error: error)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        owner?.handleDidDiscoverCharacteristics(peripheral, service: service, error: error)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        owner?.handleDidUpdateValue(peripheral, characteristic: characteristic, error: error)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        owner?.handleDidWriteValue(peripheral, characteristic: characteristic, error: error)
    }
}
