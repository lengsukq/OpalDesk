import AppKit
import OpalDeskCore
import SwiftUI

// MARK: - Fluid Palette (LightIsle / iOS 26-Inspired Cool Alabaster & Deep Twilight Slate)

/// Single-source adaptive color palette bridging LightIsle's crisp luminous content cards
/// with Apple Liquid Glass floating navigation and chrome.
enum OpalFluidPalette {
    private static func adaptive(
        light: (Double, Double, Double),
        dark: (Double, Double, Double)
    ) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            let c = isDark ? dark : light
            return NSColor(
                srgbRed: c.0 / 255.0,
                green: c.1 / 255.0,
                blue: c.2 / 255.0,
                alpha: 1.0
            )
        })
    }

    static let canvasTop = adaptive(light: (242, 245, 251), dark: (20, 23, 34))
    static let canvasBottom = adaptive(light: (234, 239, 249), dark: (27, 31, 46))

    static let cardTop = adaptive(light: (255, 255, 255), dark: (36, 41, 60))
    static let cardBottom = adaptive(light: (248, 250, 254), dark: (30, 34, 51))
    static let cardHover = adaptive(light: (238, 243, 255), dark: (46, 53, 78))

    static let cardItemTop = adaptive(light: (255, 255, 255), dark: (41, 46, 67))
    static let cardItemBottom = adaptive(light: (246, 249, 254), dark: (34, 39, 58))

    static let rim = adaptive(light: (218, 226, 243), dark: (72, 84, 118))
    static let divider = adaptive(light: (224, 230, 242), dark: (58, 67, 94))

    static let brandBlue = adaptive(light: (37, 99, 235), dark: (116, 168, 255))
    static let brandCyan = adaptive(light: (8, 128, 168), dark: (90, 204, 238))
    static let brandViolet = adaptive(light: (124, 66, 206), dark: (190, 152, 248))
    static let brandIndigo = adaptive(light: (87, 80, 200), dark: (162, 158, 255))
    static let brandEmerald = adaptive(light: (22, 156, 88), dark: (72, 212, 138))
    static let brandAmber = adaptive(light: (212, 118, 12), dark: (246, 194, 96))
    static let brandRed = adaptive(light: (220, 48, 52), dark: (255, 108, 114))

    /// Saturated iOS phone-banner green & red for circular call controls.
    static let iosCallGreen = Color(red: 52.0 / 255.0, green: 199.0 / 255.0, blue: 89.0 / 255.0)
    static let iosCallRed = Color(red: 255.0 / 255.0, green: 59.0 / 255.0, blue: 48.0 / 255.0)

    static let secondaryText = adaptive(light: (91, 100, 116), dark: (176, 184, 204))
    static let tertiaryText = adaptive(light: (132, 141, 158), dark: (134, 143, 166))

    static let onAccent = Color.white

    static let cardShadowOpacity: Double = 0.072
    static let cardShadowRadius: CGFloat = 20
    static let cardShadowY: CGFloat = 7
}

// MARK: - Section Accent Environment Key

private struct OpalSectionAccentKey: EnvironmentKey {
    static let defaultValue: Color = OpalFluidPalette.brandBlue
}

extension EnvironmentValues {
    var opalAccent: Color {
        get { self[OpalSectionAccentKey.self] }
        set { self[OpalSectionAccentKey.self] = newValue }
    }
}

// MARK: - Spatial & Geometric Tokens (Single Source of Truth)

/// Continuous squircle corner radius scale shared across all macOS OpalDesk windows, cards, and controls.
enum OpalRadius {
    /// Immersive overlay dialogs & floating call HUD banner.
    static let stageOverlay: CGFloat = 28
    /// Primary elevated content panels & floating sidebar rail.
    static let panel: CGFloat = 22
    /// Floating standalone popout window outer corner radius.
    static let popoutWindow: CGFloat = 22
    /// Nested luminous cards, sheet hero blocks, grouped settings containers.
    static let card: CGFloat = 18
    /// Interactive tiles, history cards, file grid tiles, inline callout banners.
    static let tile: CGFloat = 14
    /// Controls, buttons, search inputs, text editors, sidebar selection pills.
    static let control: CGFloat = 11
    /// Compact thumbnails, artwork icons, small inner indicators.
    static let thumbnail: CGFloat = 8
}

/// Consistent spacing rhythm derived from a 4pt layout grid.
enum OpalSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 6
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 20
    static let xxl: CGFloat = 24
}

/// Standard hit-target and icon dimensions respecting Apple HIG minimum targets.
enum OpalControlSize {
    static let iconButtonCompact: CGFloat = 28
    static let iconButtonRegular: CGFloat = 32
    static let badgeIconSmall: CGFloat = 32
    static let badgeIconMedium: CGFloat = 38
    static let badgeIconHero: CGFloat = 46
    static let appIconLarge: CGFloat = 56
}

/// Fluid spring motion curves aligned with LightIsle; helpers collapse cleanly under Reduce Motion.
enum OpalMotion {
    static let quick: Animation = .easeOut(duration: 0.16)
    static let hover: Animation = .easeOut(duration: 0.15)
    static let smooth: Animation = .spring(response: 0.30, dampingFraction: 0.86)
    static let fluid: Animation = .spring(response: 0.28, dampingFraction: 0.86)
    static let navigation: Animation = .spring(response: 0.36, dampingFraction: 0.88)

    static func animation(_ preferred: Animation, reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : preferred
    }
}

/// Semantic color and deterministic identity palette tokens (Light/Dark + High Contrast adaptive).
enum OpalPalette {
    static let appMonogramColors: [Color] = [
        OpalFluidPalette.brandBlue,
        OpalFluidPalette.brandCyan,
        OpalFluidPalette.brandIndigo,
        OpalFluidPalette.brandAmber,
        OpalFluidPalette.brandViolet,
        OpalFluidPalette.brandEmerald,
        .pink,
        .teal,
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
        case .wiredUSB: return OpalFluidPalette.brandBlue
        case .wirelessADB: return OpalFluidPalette.brandAmber
        case .wirelessWiFi: return OpalFluidPalette.brandEmerald
        }
    }
}

/// Unified native macOS `NSOpenPanel` helper for selecting an `adb` binary without duplicating dialog setup.
@MainActor
enum OpalFilePanels {
    static func pickAdbExecutable() -> URL? {
        let panel = NSOpenPanel()
        panel.title = OpalL10n.text("app.adb.panel_title")
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.showsHiddenFiles = true
        return panel.runModal() == .OK ? panel.url : nil
    }
}
