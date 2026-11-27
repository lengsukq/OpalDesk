import AppKit
import SwiftUI

// MARK: - Spatial & Geometric Tokens (Single Source of Truth)

/// Continuous squircle corner radius scale shared across all macOS OpalDesk windows, cards, and controls.
enum OpalRadius {
    /// Immersive overlay dialogs floating over video stages (connection / loading / drop stage).
    static let stageOverlay: CGFloat = 28
    /// Primary Liquid Glass surface panels (Device Hub sections, Storage summary, Diagnostics hero).
    static let panel: CGFloat = 24
    /// Floating standalone popout window outer corner radius.
    static let popoutWindow: CGFloat = 22
    /// Nested glass cards, sheet hero blocks, disclosure containers.
    static let card: CGFloat = 18
    /// Interactive tiles, history cards, file grid tiles, inline callout banners.
    static let tile: CGFloat = 14
    /// Controls, search inputs, text editors, sidebar selection pills, medium icon badges.
    static let control: CGFloat = 11
    /// Compact thumbnails, artwork icons, small inner indicators.
    static let thumbnail: CGFloat = 8
}

/// Consistent spacing rhythm derived from a 4pt macOS layout grid.
enum OpalSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 6
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 20
    static let xxl: CGFloat = 24
}

/// Standard hit-target and icon dimensions respecting macOS HIG minimum targets (>= 24–28pt).
enum OpalControlSize {
    static let iconButtonCompact: CGFloat = 26
    static let iconButtonRegular: CGFloat = 30
    static let badgeIconSmall: CGFloat = 32
    static let badgeIconMedium: CGFloat = 38
    static let badgeIconHero: CGFloat = 48
    static let appIconLarge: CGFloat = 56
}

/// Centralized motion curves; helpers automatically collapse animations under Reduce Motion.
enum OpalMotion {
    static let quick: Animation = .snappy(duration: 0.18, extraBounce: 0.0)
    static let smooth: Animation = .smooth(duration: 0.24)
    static let hover: Animation = .easeOut(duration: 0.14)

    static func animation(_ preferred: Animation, reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : preferred
    }
}

/// Semantic color and deterministic identity palette tokens (Light/Dark + High Contrast adaptive).
enum OpalPalette {
    static let appMonogramColors: [Color] = [
        .blue, .teal, .indigo, .orange, .cyan, .pink, .purple, .mint,
    ]

    static func monogramColor(for key: String) -> Color {
        let stableHash = key.unicodeScalars.reduce(UInt64(5381)) { acc, scalar in
            ((acc << 5) &+ acc) &+ UInt64(scalar.value)
        }
        return appMonogramColors[Int(stableHash % UInt64(appMonogramColors.count))]
    }
}

extension ActiveTransportKind {
    var badgeColor: Color {
        switch self {
        case .wiredUSB: return .accentColor
        case .wirelessADB: return .orange
        case .wirelessWiFi: return .green
        }
    }
}

/// Unified native macOS `NSOpenPanel` helper for selecting an `adb` binary without duplicating dialog setup.
@MainActor
enum OpalFilePanels {
    static func pickAdbExecutable() -> URL? {
        let panel = NSOpenPanel()
        panel.title = "选择 adb 可执行文件"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.showsHiddenFiles = true
        return panel.runModal() == .OK ? panel.url : nil
    }
}
