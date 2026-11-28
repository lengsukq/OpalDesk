import Foundation

// MARK: - Downlink Session Payloads (Android -> Mac)

/// Downlink `0x10 DEVICE_STATE` UTF-8 JSON payload.
public struct DeviceStatePayload: Decodable, Sendable, Equatable {
    public var deviceName: String
    public var sinkType: Int
    public var displayMode: Int
    public var width: Int
    public var height: Int
    public var dpi: Int
    public var fps: Double
    public var rotation: Int
    public var bitrateKbps: Int
    public var batteryPct: Int
    public var blackout: Bool
    public var audioEnabled: Bool
    public var injectStatus: Int
    public var adaptiveSingleEnabled: Bool
    public var defaultSingleWidthDp: Int
    public var activeLayoutMode: String
    public var topAppPackage: String
    public var topAppLabel: String
    public var tilingSlots: [String]
    public var tilingRatios: [Int]
    public var fullscreenWindowTitle: String
    public var hostTopAppPackage: String
    public var hostTopAppLabel: String

    public var layoutMode: OpalCastLayoutMode? {
        OpalCastLayoutMode(rawValue: activeLayoutMode)
    }

    public init(
        deviceName: String = "",
        sinkType: Int = 0,
        displayMode: Int = Int(OpalCastDisplayMode.dex.rawValue),
        width: Int = OpalCastProtocol.defaultWidth,
        height: Int = OpalCastProtocol.defaultHeight,
        dpi: Int = DisplayPresets.defaultDPI,
        fps: Double = Double(DisplayPresets.defaultFPS),
        rotation: Int = 0,
        bitrateKbps: Int = OpalCastProtocol.defaultDeviceStateBitrateKbps,
        batteryPct: Int = -1,
        blackout: Bool = false,
        audioEnabled: Bool = false,
        injectStatus: Int = OpalCastInjectStatus.unknown.rawValue,
        adaptiveSingleEnabled: Bool = true,
        defaultSingleWidthDp: Int = DisplayPresets.defaultSingleWidthDp,
        activeLayoutMode: String = OpalCastLayoutMode.fullscreen.rawValue,
        topAppPackage: String = "",
        topAppLabel: String = "",
        tilingSlots: [String] = [],
        tilingRatios: [Int] = [],
        fullscreenWindowTitle: String = "",
        hostTopAppPackage: String = "",
        hostTopAppLabel: String = ""
    ) {
        self.deviceName = deviceName
        self.sinkType = sinkType
        self.displayMode = displayMode
        self.width = width
        self.height = height
        self.dpi = dpi
        self.fps = fps
        self.rotation = rotation
        self.bitrateKbps = bitrateKbps
        self.batteryPct = batteryPct
        self.blackout = blackout
        self.audioEnabled = audioEnabled
        self.injectStatus = injectStatus
        self.adaptiveSingleEnabled = adaptiveSingleEnabled
        self.defaultSingleWidthDp = DisplayPresets.clampSingleWidthDp(defaultSingleWidthDp)
        self.activeLayoutMode = activeLayoutMode.isEmpty ? OpalCastLayoutMode.fullscreen.rawValue : activeLayoutMode
        self.topAppPackage = topAppPackage
        self.topAppLabel = topAppLabel
        self.tilingSlots = tilingSlots
        self.tilingRatios = tilingRatios
        self.fullscreenWindowTitle = fullscreenWindowTitle
        self.hostTopAppPackage = hostTopAppPackage
        self.hostTopAppLabel = hostTopAppLabel
    }

    private enum CodingKeys: String, CodingKey {
        case deviceName, sinkType, displayMode, width, height, dpi, fps, rotation
        case bitrateKbps, batteryPct, blackout, audioEnabled, injectStatus
        case adaptiveSingleEnabled, defaultSingleWidthDp, activeLayoutMode
        case topAppPackage, topAppLabel, tilingSlots, tilingRatios, fullscreenWindowTitle
        case hostTopAppPackage, hostTopAppLabel
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let rawWidthDp = try c.decodeIfPresent(Int.self, forKey: .defaultSingleWidthDp) ?? DisplayPresets.defaultSingleWidthDp
        let rawLayout = try c.decodeIfPresent(String.self, forKey: .activeLayoutMode) ?? ""
        self.init(
            deviceName: try c.decodeIfPresent(String.self, forKey: .deviceName) ?? "",
            sinkType: try c.decodeIfPresent(Int.self, forKey: .sinkType) ?? 0,
            displayMode: try c.decodeIfPresent(Int.self, forKey: .displayMode) ?? Int(OpalCastDisplayMode.dex.rawValue),
            width: try c.decodeIfPresent(Int.self, forKey: .width) ?? OpalCastProtocol.defaultWidth,
            height: try c.decodeIfPresent(Int.self, forKey: .height) ?? OpalCastProtocol.defaultHeight,
            dpi: try c.decodeIfPresent(Int.self, forKey: .dpi) ?? DisplayPresets.defaultDPI,
            fps: try c.decodeIfPresent(Double.self, forKey: .fps) ?? Double(DisplayPresets.defaultFPS),
            rotation: try c.decodeIfPresent(Int.self, forKey: .rotation) ?? 0,
            bitrateKbps: try c.decodeIfPresent(Int.self, forKey: .bitrateKbps) ?? OpalCastProtocol.defaultDeviceStateBitrateKbps,
            batteryPct: try c.decodeIfPresent(Int.self, forKey: .batteryPct) ?? -1,
            blackout: try c.decodeIfPresent(Bool.self, forKey: .blackout) ?? false,
            audioEnabled: try c.decodeIfPresent(Bool.self, forKey: .audioEnabled) ?? false,
            injectStatus: try c.decodeIfPresent(Int.self, forKey: .injectStatus) ?? OpalCastInjectStatus.unknown.rawValue,
            adaptiveSingleEnabled: try c.decodeIfPresent(Bool.self, forKey: .adaptiveSingleEnabled) ?? true,
            defaultSingleWidthDp: rawWidthDp,
            activeLayoutMode: rawLayout,
            topAppPackage: try c.decodeIfPresent(String.self, forKey: .topAppPackage) ?? "",
            topAppLabel: try c.decodeIfPresent(String.self, forKey: .topAppLabel) ?? "",
            tilingSlots: try c.decodeIfPresent([String].self, forKey: .tilingSlots) ?? [],
            tilingRatios: try c.decodeIfPresent([Int].self, forKey: .tilingRatios) ?? [],
            fullscreenWindowTitle: try c.decodeIfPresent(String.self, forKey: .fullscreenWindowTitle) ?? "",
            hostTopAppPackage: try c.decodeIfPresent(String.self, forKey: .hostTopAppPackage) ?? "",
            hostTopAppLabel: try c.decodeIfPresent(String.self, forKey: .hostTopAppLabel) ?? ""
        )
    }

    public static func decode(from data: Data) throws -> DeviceStatePayload {
        try OpalCastJSONCodec.decode(DeviceStatePayload.self, from: data, context: "DEVICE_STATE")
    }
}

/// Downlink `0x12 AUTH_RESULT` payload: 1-byte `ok` prefix followed by UTF-8 JSON metadata.
public struct AuthResultPayload: Decodable, Sendable, Equatable {
    public var ok: Bool
    public var authToken: String
    public var permanent: Bool
    public var message: String
    public var deviceName: String
    public var certFingerprint: String

    public init(
        ok: Bool,
        authToken: String = "",
        permanent: Bool = false,
        message: String = "",
        deviceName: String = "",
        certFingerprint: String = ""
    ) {
        self.ok = ok
        self.authToken = authToken
        self.permanent = permanent
        self.message = message
        self.deviceName = deviceName
        self.certFingerprint = certFingerprint
    }

    private enum CodingKeys: String, CodingKey {
        case ok, authToken, permanent, message, deviceName, certFingerprint
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        authToken = try c.decodeIfPresent(String.self, forKey: .authToken) ?? ""
        permanent = try c.decodeIfPresent(Bool.self, forKey: .permanent) ?? false
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
        deviceName = try c.decodeIfPresent(String.self, forKey: .deviceName) ?? ""
        certFingerprint = try c.decodeIfPresent(String.self, forKey: .certFingerprint) ?? ""
    }

    public static func decode(from data: Data) throws -> AuthResultPayload {
        var reader = BinaryReader(data)
        let okByte = try reader.readUInt8()
        let headerOk = okByte != 0
        let jsonBytes = reader.readRemainingBytes()
        if jsonBytes.isEmpty {
            return AuthResultPayload(ok: headerOk)
        }
        var decoded = try OpalCastJSONCodec.decode(AuthResultPayload.self, from: jsonBytes, context: "AUTH_RESULT")
        decoded.ok = headerOk && decoded.ok
        return decoded
    }
}

/// Downlink `0x14 WIFI_UPGRADE_READY` UTF-8 JSON payload.
public struct WifiUpgradeReadyPayload: Decodable, Sendable, Equatable {
    public var ok: Bool
    public var host: String
    public var port: Int
    public var certFingerprint: String
    public var authToken: String
    public var message: String

    public init(
        ok: Bool,
        host: String = "",
        port: Int = Int(OpalCastProtocol.defaultWifiPort),
        certFingerprint: String = "",
        authToken: String = "",
        message: String = ""
    ) {
        self.ok = ok
        self.host = host
        self.port = port
        self.certFingerprint = certFingerprint
        self.authToken = authToken
        self.message = message
    }

    private enum CodingKeys: String, CodingKey {
        case ok, host, port, certFingerprint, authToken, message
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        host = try c.decodeIfPresent(String.self, forKey: .host) ?? ""
        port = try c.decodeIfPresent(Int.self, forKey: .port) ?? Int(OpalCastProtocol.defaultWifiPort)
        certFingerprint = try c.decodeIfPresent(String.self, forKey: .certFingerprint) ?? ""
        authToken = try c.decodeIfPresent(String.self, forKey: .authToken) ?? ""
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
    }

    public var validPort: UInt16? {
        guard port > 0 && port <= Int(UInt16.max) else { return nil }
        return UInt16(port)
    }

    public static func decode(from data: Data) throws -> WifiUpgradeReadyPayload {
        try OpalCastJSONCodec.decode(WifiUpgradeReadyPayload.self, from: data, context: "WIFI_UPGRADE_READY")
    }
}

// MARK: - Bidirectional Keepalive Payload

/// Bidirectional `0x2F PING` / `0x1F PONG` 8-byte Big-Endian sequence payload.
public struct PingPongPayload: Sendable, Equatable {
    public var pingId: UInt64

    public init(pingId: UInt64) {
        self.pingId = pingId
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 8)
        writer.writeUInt64(pingId)
        return writer.data
    }

    public static func decode(from data: Data) throws -> PingPongPayload {
        var reader = BinaryReader(data)
        return PingPongPayload(pingId: try reader.readUInt64())
    }
}

// MARK: - Uplink Session Payloads (Mac -> Android)

/// Uplink `0x20 HELLO` UTF-8 JSON handshake payload.
public struct HelloPayload: Encodable, Sendable, Equatable {
    public var protocolVersion: Int
    public var clientName: String
    public var clientId: String
    public var preferredCodec: String
    public var width: Int
    public var height: Int
    public var dpi: Int
    public var fps: Int
    public var bitrateKbps: Int
    public var displayMode: Int
    public var audioEnabled: Bool
    public var authToken: String
    public var pinCode: String
    public var transportHint: String
    public var clientPlatform: String
    public var sessionRole: String
    public var appPackage: String
    public var appActivity: String
    public var companionEvents: Bool
    public var appUserId: Int?

    public init(
        protocolVersion: Int = OpalCastProtocol.version,
        clientName: String,
        clientId: String,
        preferredCodec: String = OpalCastVideoCodec.hevc.preferredName,
        width: Int = OpalCastProtocol.defaultWidth,
        height: Int = OpalCastProtocol.defaultHeight,
        dpi: Int = DisplayPresets.defaultDPI,
        fps: Int = DisplayPresets.defaultFPS,
        bitrateKbps: Int = DisplayPresets.defaultBitrateKbps,
        displayMode: Int = Int(OpalCastDisplayMode.dex.rawValue),
        audioEnabled: Bool = true,
        authToken: String = "",
        pinCode: String = "",
        transportHint: String = OpalCastTransportHint.usb.rawValue,
        clientPlatform: String = "macos",
        sessionRole: String = OpalCastSessionRole.desktop.rawValue,
        appPackage: String = "",
        appActivity: String = "",
        companionEvents: Bool = false,
        appUserId: Int? = nil
    ) {
        self.protocolVersion = protocolVersion
        self.clientName = clientName
        self.clientId = clientId
        self.preferredCodec = preferredCodec
        self.width = width
        self.height = height
        self.dpi = dpi
        self.fps = fps
        self.bitrateKbps = bitrateKbps
        self.displayMode = displayMode
        self.audioEnabled = audioEnabled
        self.authToken = authToken
        self.pinCode = pinCode
        self.transportHint = transportHint
        self.clientPlatform = clientPlatform
        self.sessionRole = sessionRole
        self.appPackage = appPackage
        self.appActivity = appActivity
        self.companionEvents = companionEvents
        self.appUserId = (appUserId ?? 0) > 0 ? appUserId : nil
    }

    public func encode() throws -> Data {
        try OpalCastJSONCodec.encode(self, context: "HELLO")
    }
}

/// Uplink `0x21 RESIZE_CONFIG` fixed 14-byte Big-Endian payload.
public struct ResizeConfigPayload: Sendable, Equatable {
    public static let unchangedByte: UInt8 = 0xFF

    public var width: UInt16
    public var height: UInt16
    public var dpi: UInt16
    public var fpsX100: UInt16
    public var bitrateKbps: UInt32
    public var displayMode: UInt8
    public var rotation: UInt8

    public init(
        width: UInt16,
        height: UInt16,
        dpi: UInt16,
        fpsX100: UInt16,
        bitrateKbps: UInt32,
        displayMode: UInt8 = ResizeConfigPayload.unchangedByte,
        rotation: UInt8 = ResizeConfigPayload.unchangedByte
    ) {
        self.width = width
        self.height = height
        self.dpi = dpi
        self.fpsX100 = fpsX100
        self.bitrateKbps = bitrateKbps
        self.displayMode = displayMode
        self.rotation = rotation
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 14)
        writer.writeUInt16(width)
        writer.writeUInt16(height)
        writer.writeUInt16(dpi)
        writer.writeUInt16(fpsX100)
        writer.writeUInt32(bitrateKbps)
        writer.writeUInt8(displayMode)
        writer.writeUInt8(rotation)
        return writer.data
    }
}

/// Uplink `0x27 SYSTEM_ACTION` fixed 5-byte Big-Endian payload.
public struct SystemActionPayload: Sendable, Equatable {
    public var action: OpalCastSystemActionKind
    public var arg: Int32

    public init(action: OpalCastSystemActionKind, arg: Int32 = 0) {
        self.action = action
        self.arg = arg
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 5)
        writer.writeUInt8(action.rawValue)
        writer.writeInt32(arg)
        return writer.data
    }
}
