import OpalDeskCore
import Foundation

/// Active transport backing the main OpalCast session.
public enum ActiveTransportKind: Sendable, Equatable {
    case wiredUSB(serial: String, localPort: UInt16)
    case wirelessADB(serial: String, localPort: UInt16)
    case wirelessWiFi(displayTarget: String, host: String?, port: UInt16?)

    public var shortBadge: String {
        switch self {
        case .wiredUSB: return "USB 有线"
        case .wirelessADB: return "无线 ADB"
        case .wirelessWiFi: return "Wi-Fi 直连"
        }
    }

    public var systemImage: String {
        switch self {
        case .wiredUSB: return "cable.connector"
        case .wirelessADB, .wirelessWiFi: return "wifi"
        }
    }

    public var transportHint: OpalCastTransportHint {
        switch self {
        case .wiredUSB: return .usb
        case .wirelessADB: return .wifiAdb
        case .wirelessWiFi: return .wifiTls
        }
    }
}

/// Metadata describing the currently connected Android host.
public struct ActiveConnectionInfo: Sendable, Equatable {
    public var deviceName: String
    public var model: String
    public var transport: ActiveTransportKind
    public var fingerprint: String
    public var connectedAt: Date
}

/// Context driving the TOFU certificate fingerprint verification and host-consent pairing request sheet.
public struct PairingPromptContext: Identifiable, Sendable, Equatable {
    public enum Source: Sendable, Equatable {
        case bonjour(DiscoveredBonjourDevice)
        case manualHostPort(host: String, port: UInt16)
    }

    public let id = UUID()
    public let source: Source
    public let displayName: String
    public let model: String
    public let expectedFingerprint: String
    public var errorMessage: String?
    public var isSubmitting: Bool = false

    public var shortFingerprint: String {
        FingerprintUtil.formatShort(expectedFingerprint)
    }
}

/// Snapshot of the active authenticated connection shared by standalone popout app windows.
public struct PopoutConnectionContext: Sendable, Equatable {
    public let endpoint: OpalCastEndpoint
    public let transportHint: OpalCastTransportHint
    public let clientId: String
    public let authToken: String
    public let preferredCodec: OpalCastVideoCodec
    public let targetFPS: Int
    public let bitrateKbps: Int
    public let mapCommandToCtrl: Bool
    public let audioEnabled: Bool
    public let enableEdgeNavigationGestures: Bool

    public init(
        endpoint: OpalCastEndpoint,
        transportHint: OpalCastTransportHint,
        clientId: String,
        authToken: String,
        preferredCodec: OpalCastVideoCodec,
        targetFPS: Int,
        bitrateKbps: Int,
        mapCommandToCtrl: Bool,
        audioEnabled: Bool = true,
        enableEdgeNavigationGestures: Bool = true
    ) {
        self.endpoint = endpoint
        self.transportHint = transportHint
        self.clientId = clientId
        self.authToken = authToken
        self.preferredCodec = preferredCodec
        self.targetFPS = targetFPS
        self.bitrateKbps = bitrateKbps
        self.mapCommandToCtrl = mapCommandToCtrl
        self.audioEnabled = audioEnabled
        self.enableEdgeNavigationGestures = enableEdgeNavigationGestures
    }
}

/// Presentation context for `ApkInstallerSheet` supporting both local macOS packages (upload + install)
/// and packages already residing on Android storage (direct install via `0x34`).
public struct ApkInstallerContext: Identifiable, Sendable, Equatable {
    public enum PresentationTarget: Sendable, Equatable {
        case main
        case fileBrowser
    }

    public let id = UUID()
    public let packageURL: URL
    public let preloadedInfo: AndroidPackageInfo?
    public let remoteFilePath: String?
    public let targetWindow: PresentationTarget

    public init(
        packageURL: URL,
        preloadedInfo: AndroidPackageInfo? = nil,
        remoteFilePath: String? = nil,
        targetWindow: PresentationTarget = .main
    ) {
        self.packageURL = packageURL
        self.preloadedInfo = preloadedInfo
        self.remoteFilePath = remoteFilePath
        self.targetWindow = targetWindow
    }
}

/// Mutually exclusive DeX floating panels anchored above the taskbar.
public enum DeXOverlayPanel: Equatable, Sendable {
    case none
    case appDrawer
    case quickSettings
    case layoutPicker
}

/// High-level DeX desktop window layout modes presented in the Snap Layout picker.
public enum DeXWindowMode: Equatable, Sendable {
    case maximized
    case centeredPhone
    case tiling(OpalCastTilingTopology)
}

extension OpalCastTilingTopology {
    public var slotCount: Int {
        switch self {
        case .split2H, .split2V: return 2
        case .split3Master, .split3Cols: return 3
        case .grid2x2: return 4
        case .exit: return 0
        }
    }

    public var systemImage: String {
        switch self {
        case .split2H: return "rectangle.split.2x1"
        case .split2V: return "rectangle.split.1x2"
        case .split3Master: return "rectangle.split.2x2"
        case .split3Cols: return "rectangle.split.3x1"
        case .grid2x2: return "square.grid.2x2"
        case .exit: return "rectangle"
        }
    }

    public func slotDisplayName(for index: Int) -> String {
        switch self {
        case .split2H:
            return index == 0 ? "左侧分屏" : "右侧分屏"
        case .split2V:
            return index == 0 ? "上方分屏" : "下方分屏"
        case .split3Master:
            switch index {
            case 0: return "左侧主屏"
            case 1: return "右上分屏"
            default: return "右下分屏"
            }
        case .split3Cols:
            switch index {
            case 0: return "左列分屏"
            case 1: return "中列分屏"
            default: return "右列分屏"
            }
        case .grid2x2:
            switch index {
            case 0: return "左上格"
            case 1: return "右上格"
            case 2: return "左下格"
            default: return "右下格"
            }
        case .exit:
            return "窗口 \(index + 1)"
        }
    }
}
