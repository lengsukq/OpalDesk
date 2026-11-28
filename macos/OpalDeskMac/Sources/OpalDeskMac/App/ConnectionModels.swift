import OpalDeskCore
import Foundation

/// Active transport backing the main OpalCast session.
public enum ActiveTransportKind: Sendable, Equatable {
    case wiredUSB(serial: String, localPort: UInt16)
    case wirelessADB(serial: String, localPort: UInt16)
    case wirelessWiFi(displayTarget: String, host: String?, port: UInt16?)

    public var shortBadge: String {
        switch self {
        case .wiredUSB: return OpalL10n.text("transport.usb")
        case .wirelessADB: return OpalL10n.text("transport.wireless_adb")
        case .wirelessWiFi: return OpalL10n.text("transport.wifi_direct")
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
            return index == 0 ? OpalL10n.text("layout.slot.left") : OpalL10n.text("layout.slot.right")
        case .split2V:
            return index == 0 ? OpalL10n.text("layout.slot.top") : OpalL10n.text("layout.slot.bottom")
        case .split3Master:
            switch index {
            case 0: return OpalL10n.text("layout.slot.left_main")
            case 1: return OpalL10n.text("layout.slot.top_right")
            default: return OpalL10n.text("layout.slot.bottom_right")
            }
        case .split3Cols:
            switch index {
            case 0: return OpalL10n.text("layout.slot.col_left")
            case 1: return OpalL10n.text("layout.slot.col_mid")
            default: return OpalL10n.text("layout.slot.col_right")
            }
        case .grid2x2:
            switch index {
            case 0: return OpalL10n.text("layout.slot.top_left")
            case 1: return OpalL10n.text("layout.slot.top_right")
            case 2: return OpalL10n.text("layout.slot.bottom_left")
            default: return OpalL10n.text("layout.slot.bottom_right")
            }
        case .exit:
            return OpalL10n.format("layout.slot.window_fmt", index + 1)
        }
    }
}
