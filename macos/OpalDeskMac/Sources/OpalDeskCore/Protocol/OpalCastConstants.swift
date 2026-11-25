import Foundation

/// OpalCast v1.1 wire-protocol constants and typed enumerations.
/// All multi-byte integers on the wire use Big-Endian (network byte order).
public enum OpalCastProtocol {
    public static let version: Int = 1
    public static let magicBytes: [UInt8] = [0x4F, 0x50, 0x44, 0x31] // "OPD1"
    public static let headerSize: Int = 18
    public static let maxPayloadLength: UInt32 = 16 * 1024 * 1024 // 16 MiB
    public static let maxTouchPointers: Int = 16
    public static let maxFileNameUTF8Bytes: Int = Int(UInt16.max) // 65535 bytes
    public static let bonjourServiceType: String = "_opaldesk._tcp"
    public static let abstractSocketName: String = "opaldesk_stream"
    public static let defaultWifiPort: UInt16 = 19878
    public static let defaultWidth: Int = 1920
    public static let defaultHeight: Int = 1080
    public static let defaultDeviceStateBitrateKbps: Int = 12000
    public static let defaultFileChunkSize: Int = 256 * 1024 // 256 KiB
    public static let maxClipboardImageBytes: Int = 8 * 1024 * 1024 // 8 MiB
    public static let flagClipboardImagePng: UInt8 = 0x01
}

public enum OpalCastFrameType: UInt8, Sendable, CaseIterable {
    // Downlink: Android -> Mac
    case videoConfig = 0x01
    case videoFrame = 0x02
    case audioConfig = 0x03
    case audioFrame = 0x04
    case deviceState = 0x10
    case clipboardPush = 0x11
    case authResult = 0x12
    case fileTransferAck = 0x13
    case wifiUpgradeReady = 0x14
    case appList = 0x15
    case imeRequest = 0x16
    case pong = 0x1F

    // Uplink: Mac -> Android
    case hello = 0x20
    case resizeConfig = 0x21
    case requestIdr = 0x22
    case inputMouse = 0x23
    case inputTouch = 0x24
    case inputKey = 0x25
    case inputText = 0x26
    case systemAction = 0x27
    case fileTransferChunk = 0x28
    case requestWifiUpgrade = 0x29
    case listApps = 0x2A
    case windowAction = 0x2B
    case ping = 0x2F
}

public struct OpalCastVideoFrameFlags: OptionSet, Sendable, Hashable {
    public let rawValue: UInt8
    public init(rawValue: UInt8) { self.rawValue = rawValue }

    public static let keyframe = OpalCastVideoFrameFlags(rawValue: 0x01)
    public static let configInline = OpalCastVideoFrameFlags(rawValue: 0x02)
}

public enum OpalCastVideoCodec: UInt8, Sendable, CaseIterable {
    case h264 = 1
    case hevc = 2

    public var preferredName: String {
        switch self {
        case .h264: return "h264"
        case .hevc: return "hevc"
        }
    }

    public var displayName: String {
        switch self {
        case .h264: return "H.264"
        case .hevc: return "HEVC"
        }
    }
}

public enum OpalCastAudioCodec: UInt8, Sendable, CaseIterable {
    case aacLC = 1
    case opus = 2
    case pcm16 = 3
}

public enum OpalCastDisplayMode: UInt8, Sendable, CaseIterable {
    case dex = 0
    case mirror = 1
    case unchanged = 0xFF

    public var displayName: String {
        switch self {
        case .dex: return "扩展桌面 (DeX)"
        case .mirror: return "屏幕镜像"
        case .unchanged: return "保持当前"
        }
    }
}

public enum OpalCastLayoutMode: String, Codable, Sendable, CaseIterable, Equatable {
    case fullscreen = "FULLSCREEN"
    case adaptiveSingle = "ADAPTIVE_SINGLE"
    case freeform = "FREEFORM"
    case split2H = "SPLIT_2_H"
    case split2V = "SPLIT_2_V"
    case split3Master = "SPLIT_3_MASTER"
    case split3Cols = "SPLIT_3_COLS"
    case grid2x2 = "GRID_2X2"
}

public enum OpalCastTransportHint: String, Codable, Sendable, CaseIterable {
    case usb = "usb"
    case wifiAdb = "wifi_adb"
    case wifiTls = "wifi_tls"
}

public enum OpalCastSessionRole: String, Codable, Sendable, CaseIterable {
    case desktop = "desktop"
    case standaloneApp = "standalone_app"
}

public enum OpalCastInjectStatus: Int, Sendable, Equatable {
    case denied = -1
    case unknown = 0
    case injectEvents = 1
    case uhid = 2

    public init(rawStatus: Int) {
        self = OpalCastInjectStatus(rawValue: rawStatus) ?? .unknown
    }
}

public enum OpalCastInputBackend: String, Codable, Sendable, CaseIterable, Identifiable {
    case auto = "auto"
    case uhid = "uhid"
    case inject = "inject"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .auto: return "自动（推荐）"
        case .uhid: return "UHID 虚拟外设"
        case .inject: return "系统事件注入"
        }
    }
}

public enum OpalCastLaunchMode: String, Codable, Sendable, CaseIterable {
    case adaptive = "adaptive"
    case fullscreen = "fullscreen"
    case freeform = "freeform"
    case split = "split"
}

public enum OpalCastTilingTopology: String, Codable, Sendable, CaseIterable, Identifiable {
    case split2H = "SPLIT_2_H"
    case split2V = "SPLIT_2_V"
    case split3Master = "SPLIT_3_MASTER"
    case split3Cols = "SPLIT_3_COLS"
    case grid2x2 = "GRID_2X2"
    case exit = "EXIT"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .split2H: return "左右 2 分屏"
        case .split2V: return "上下 2 分屏"
        case .split3Master: return "1+2 主从三分屏"
        case .split3Cols: return "三列分屏"
        case .grid2x2: return "2×2 四宫格"
        case .exit: return "退出分屏"
        }
    }

    public var defaultRatios: [Int] {
        switch self {
        case .split2H, .split2V: return [50, 50]
        case .split3Master: return [55, 45]
        case .split3Cols: return [33, 34, 33]
        case .grid2x2: return [50, 50]
        case .exit: return []
        }
    }
}

public enum OpalCastMouseAction: UInt8, Sendable, CaseIterable {
    case moveAbs = 0
    case buttonDown = 1
    case buttonUp = 2
    case scroll = 3
    case hoverExit = 4
}

public struct OpalCastMouseButtons: OptionSet, Sendable, Hashable {
    public let rawValue: UInt32
    public init(rawValue: UInt32) { self.rawValue = rawValue }

    public static let primary = OpalCastMouseButtons(rawValue: 1)
    public static let secondary = OpalCastMouseButtons(rawValue: 2)
    public static let tertiary = OpalCastMouseButtons(rawValue: 4)
    public static let back = OpalCastMouseButtons(rawValue: 8)
    public static let forward = OpalCastMouseButtons(rawValue: 16)
}

public enum OpalCastTouchAction: UInt8, Sendable, CaseIterable {
    case down = 0
    case up = 1
    case move = 2
    case cancel = 3
    case pointerDown = 5
    case pointerUp = 6
}

public enum OpalCastKeyAction: UInt8, Sendable, CaseIterable {
    case down = 0
    case up = 1
}

public enum OpalCastInputTextMode: UInt8, Sendable, CaseIterable {
    case commitTextPaste = 0
    case setClipboardOnly = 1

    public static let setClipboardImageOnly: OpalCastInputTextMode = .setClipboardOnly
}

public enum OpalCastSystemActionKind: UInt8, Sendable, CaseIterable {
    case home = 1
    case back = 2
    case recents = 3
    case toggleDisplayMode = 4
    case toggleBlackout = 5
    case rotateCW = 6
    case setAudioEnabled = 7
    case disconnect = 8
}

public struct OpalCastFileChunkFlags: OptionSet, Sendable, Hashable {
    public let rawValue: UInt8
    public init(rawValue: UInt8) { self.rawValue = rawValue }

    public static let start = OpalCastFileChunkFlags(rawValue: 0x01)
    public static let data = OpalCastFileChunkFlags(rawValue: 0x02)
    public static let end = OpalCastFileChunkFlags(rawValue: 0x04)
    public static let abort = OpalCastFileChunkFlags(rawValue: 0x08)
}

public enum OpalCastFileAckStatus: UInt8, Sendable {
    case ok = 0
    case error = 1
}

public enum OpalCastCodecError: Error, Sendable, Equatable, LocalizedError {
    case invalidMagic([UInt8])
    case unknownFrameType(UInt8)
    case payloadTooLarge(UInt32)
    case fieldTooLong(field: String, length: Int, maxLength: Int)
    case truncatedPayload(expected: Int, actual: Int)
    case invalidEnumValue(field: String, rawValue: UInt32)
    case invalidUTF8(field: String)
    case invalidJSON(String)

    public var errorDescription: String? {
        switch self {
        case .invalidMagic(let bytes):
            let hex = bytes.map { String(format: "%02X", $0) }.joined(separator: " ")
            return "Invalid OpalCast frame magic: [\(hex)]"
        case .unknownFrameType(let raw):
            return String(format: "Unknown OpalCast frame type: 0x%02X", raw)
        case .payloadTooLarge(let length):
            return "OpalCast payload length \(length) exceeds maximum \(OpalCastProtocol.maxPayloadLength)"
        case let .fieldTooLong(field, length, maxLength):
            return "OpalCast field '\(field)' length \(length) exceeds maximum \(maxLength)"
        case .truncatedPayload(let expected, let actual):
            return "Truncated OpalCast payload: expected at least \(expected) bytes, got \(actual)"
        case .invalidEnumValue(let field, let raw):
            return "Invalid value \(raw) for OpalCast field '\(field)'"
        case .invalidUTF8(let field):
            return "Invalid UTF-8 encoding in OpalCast field '\(field)'"
        case .invalidJSON(let detail):
            return "Failed to parse OpalCast JSON payload: \(detail)"
        }
    }
}
