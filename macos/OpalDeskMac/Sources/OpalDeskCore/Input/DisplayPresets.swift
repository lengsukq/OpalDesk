import CoreGraphics

/// Single source of truth for display, encoder, layout, pairing, and popout UI presets/bounds.
public enum DisplayPresets {
    public static let dpiOptions: [Int] = [160, 200, 240, 280, 320]
    public static let defaultDPI: Int = 240

    public static let fpsOptions: [Int] = [60, 90, 120]
    public static let defaultFPS: Int = 60

    public static let bitrateKbpsRange: ClosedRange<Int> = 4000...48000
    public static let bitrateStepKbps: Int = 2000
    public static let defaultBitrateKbps: Int = 16000

    public static let singleWidthDpRange: ClosedRange<Int> = 320...1200
    public static let defaultSingleWidthDp: Int = 460
    public static let singleWidthPctRange: ClosedRange<Int> = 28...100

    public static let pairingPinLength: Int = 6
    public static let popoutOpacityRange: ClosedRange<Double> = 0.4...1.0

    public static func clampBitrateKbps(_ value: Int) -> Int {
        min(max(value, bitrateKbpsRange.lowerBound), bitrateKbpsRange.upperBound)
    }

    public static func clampSingleWidthDp(_ value: Int) -> Int {
        min(max(value, singleWidthDpRange.lowerBound), singleWidthDpRange.upperBound)
    }

    public static func clampSingleWidthPct(_ value: Int) -> Int {
        min(max(value, singleWidthPctRange.lowerBound), singleWidthPctRange.upperBound)
    }

    public static func clampPopoutOpacity(_ value: Double) -> Double {
        min(max(value, popoutOpacityRange.lowerBound), popoutOpacityRange.upperBound)
    }
}

/// Display resolution presets offered in the Display control bar and preferences.
public enum DisplayResolutionPreset: String, Sendable, CaseIterable, Identifiable, Codable {
    case matchWindow
    case fhd1080p
    case qhd2K
    case ultrawide21x9
    case portrait

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .matchWindow: return "自适应窗口"
        case .fhd1080p: return "1080p (1920×1080)"
        case .qhd2K: return "2K (2560×1440)"
        case .ultrawide21x9: return "21:9 带鱼屏 (2560×1080)"
        case .portrait: return "竖屏 (1080×1920)"
        }
    }

    public var fixedSize: (width: Int, height: Int)? {
        switch self {
        case .matchWindow: return nil
        case .fhd1080p: return (1920, 1080)
        case .qhd2K: return (2560, 1440)
        case .ultrawide21x9: return (2560, 1080)
        case .portrait: return (1080, 1920)
        }
    }
}

/// Quick window aspect presets for standalone popout Android app windows.
public enum PopoutAspectPreset: String, Sendable, CaseIterable, Identifiable {
    case phone
    case foldable
    case tablet

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .phone: return "手机"
        case .foldable: return "折叠"
        case .tablet: return "平板"
        }
    }

    public var systemImage: String {
        switch self {
        case .phone: return "iphone"
        case .foldable: return "rectangle.split.2x1"
        case .tablet: return "ipad.landscape"
        }
    }

    public var windowSize: CGSize {
        switch self {
        case .phone: return CGSize(width: 420, height: 840)
        case .foldable: return CGSize(width: 640, height: 820)
        case .tablet: return CGSize(width: 920, height: 640)
        }
    }
}

/// Resolved encoder dimensions and adaptive Android DPI for a standalone popout app window.
public struct PopoutStreamGeometry: Sendable, Equatable {
    public let width: Int
    public let height: Int
    public let dpi: Int

    public init(width: Int, height: Int, dpi: Int) {
        self.width = width
        self.height = height
        self.dpi = dpi
    }

    public var smallestWidthDp: Int {
        guard dpi > 0 else { return 0 }
        return Int((Double(min(width, height)) * 160.0 / Double(dpi)).rounded())
    }
}
