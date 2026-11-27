import SwiftUI

// MARK: - Surface Tiers

/// Two-layer Apple HIG surface hierarchy:
/// - Content layer surfaces (`.panel`, `.card`, `.inset`) use refined macOS materials + subtle dual-edge depth.
/// - Functional floating layers (`.floatingBar`, `.stageOverlay`) use Liquid Glass vibrancy + luminous 45° specular rims.
enum OpalGlassSurfaceTier {
    case panel
    case card
    case inset
    case floatingBar
    case stageOverlay
}

// MARK: - Adaptive Liquid Glass Surface Modifier

/// Unified Liquid Glass continuous-squircle surface with 45° specular rim, depth hairline,
/// and automatic Light/Dark, Reduce Transparency, and Increase Contrast adaptation.
private struct OpalGlassSurfaceModifier: ViewModifier {
    let tier: OpalGlassSurfaceTier
    let cornerRadius: CGFloat
    let tint: Color?
    let strokeOverride: Color?
    let isCapsule: Bool

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var isDark: Bool { colorScheme == .dark }
    private var isHighContrast: Bool { colorSchemeContrast == .increased }

    func body(content: Content) -> some View {
        if isCapsule {
            content
                .background(capsuleBackground)
                .overlay(capsuleSpecularRim)
                .shadow(color: shadowColor, radius: shadowRadius, y: shadowY)
        } else {
            let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            content
                .background(rectBackground(shape: shape))
                .overlay(rectSpecularRim(shape: shape))
                .shadow(color: shadowColor, radius: shadowRadius, y: shadowY)
        }
    }

    // MARK: - Backgrounds

    @ViewBuilder
    private var capsuleBackground: some View {
        ZStack {
            if reduceTransparency {
                Capsule().fill(opaqueFillColor)
            } else {
                Capsule().fill(surfaceMaterial)
                Capsule().fill(glassWashColor)
            }
            if let tint {
                Capsule().fill(tint.opacity(tintFillOpacity))
            }
        }
    }

    @ViewBuilder
    private func rectBackground(shape: RoundedRectangle) -> some View {
        ZStack {
            if reduceTransparency {
                shape.fill(opaqueFillColor)
            } else {
                shape.fill(surfaceMaterial)
                shape.fill(glassWashColor)
            }
            if let tint {
                shape.fill(tint.opacity(tintFillOpacity))
            }
        }
    }

    // MARK: - Specular Rim

    private var capsuleSpecularRim: some View {
        ZStack {
            Capsule()
                .strokeBorder(specularGradient, lineWidth: rimLineWidth)
            if !isDark || isHighContrast {
                Capsule()
                    .strokeBorder(outerHairlineColor, lineWidth: 0.5)
            }
        }
        .allowsHitTesting(false)
    }

    private func rectSpecularRim(shape: RoundedRectangle) -> some View {
        ZStack {
            shape
                .strokeBorder(specularGradient, lineWidth: rimLineWidth)
            if !isDark || isHighContrast {
                shape
                    .strokeBorder(outerHairlineColor, lineWidth: 0.5)
            }
        }
        .allowsHitTesting(false)
    }

    // MARK: - Adaptive Token Calculations

    private var surfaceMaterial: Material {
        switch tier {
        case .panel: return isDark ? .regularMaterial : .thickMaterial
        case .card: return isDark ? .thinMaterial : .regularMaterial
        case .inset: return .ultraThinMaterial
        case .floatingBar: return .ultraThinMaterial
        case .stageOverlay: return .regularMaterial
        }
    }

    private var opaqueFillColor: Color {
        switch tier {
        case .panel, .card:
            return Color(nsColor: .controlBackgroundColor)
        case .inset:
            return Color(nsColor: .underPageBackgroundColor)
        case .floatingBar, .stageOverlay:
            return isDark
                ? Color(nsColor: .windowBackgroundColor)
                : Color(nsColor: .controlBackgroundColor)
        }
    }

    private var glassWashColor: Color {
        switch tier {
        case .panel:
            return isDark ? Color.white.opacity(0.035) : Color.white.opacity(0.46)
        case .card:
            return isDark ? Color.white.opacity(0.03) : Color.white.opacity(0.36)
        case .inset:
            return isDark ? Color.white.opacity(0.035) : Color.black.opacity(0.025)
        case .floatingBar:
            return isDark ? Color.black.opacity(0.28) : Color.white.opacity(0.42)
        case .stageOverlay:
            return isDark ? Color.black.opacity(0.32) : Color.white.opacity(0.55)
        }
    }

    private var tintFillOpacity: Double {
        if isHighContrast { return isDark ? 0.22 : 0.16 }
        switch tier {
        case .inset, .card: return isDark ? 0.13 : 0.085
        case .panel, .floatingBar, .stageOverlay: return isDark ? 0.14 : 0.10
        }
    }

    private var specularGradient: LinearGradient {
        if let strokeOverride {
            return LinearGradient(
                colors: [
                    strokeOverride,
                    strokeOverride.opacity(0.36),
                    strokeOverride.opacity(0.78),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }

        if let tint {
            let lead = isHighContrast ? 0.65 : (isDark ? 0.42 : 0.34)
            return LinearGradient(
                colors: [
                    tint.opacity(lead),
                    tint.opacity(lead * 0.32),
                    tint.opacity(lead * 0.65),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }

        if isDark {
            let topLead = isHighContrast ? 0.42 : (tier == .floatingBar ? 0.34 : 0.24)
            return LinearGradient(
                colors: [
                    Color.white.opacity(topLead),
                    Color.white.opacity(topLead * 0.22),
                    Color.white.opacity(topLead * 0.48),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        } else {
            let topLead = isHighContrast ? 0.85 : 0.92
            return LinearGradient(
                colors: [
                    Color.white.opacity(topLead),
                    Color.white.opacity(0.28),
                    Color.black.opacity(isHighContrast ? 0.22 : 0.07),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
    }

    private var outerHairlineColor: Color {
        if isHighContrast {
            return Color.primary.opacity(0.38)
        }
        return isDark ? Color.white.opacity(0.08) : Color.black.opacity(0.08)
    }

    private var rimLineWidth: CGFloat {
        isHighContrast ? 1.25 : (tier == .floatingBar ? 1.0 : 0.9)
    }

    private var shadowColor: Color {
        switch tier {
        case .inset:
            return .clear
        case .card:
            return Color.black.opacity(isDark ? 0.18 : 0.05)
        case .panel:
            return Color.black.opacity(isDark ? 0.26 : 0.07)
        case .floatingBar:
            return Color.black.opacity(isDark ? 0.42 : 0.16)
        case .stageOverlay:
            return Color.black.opacity(isDark ? 0.48 : 0.18)
        }
    }

    private var shadowRadius: CGFloat {
        switch tier {
        case .inset: return 0
        case .card: return 10
        case .panel: return 18
        case .floatingBar: return 16
        case .stageOverlay: return 24
        }
    }

    private var shadowY: CGFloat {
        switch tier {
        case .inset: return 0
        case .card: return 4
        case .panel: return 7
        case .floatingBar: return 6
        case .stageOverlay: return 10
        }
    }
}

// MARK: - Ambient Opalescent Window Backdrop

/// Subtle macOS Light/Dark adaptive opalescent backdrop that gives frosted Liquid Glass surfaces
/// natural luminance and depth without competing with foreground content.
struct OpalAmbientBackdrop: View {
    var intensity: Double = 1.0

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)

            if !reduceTransparency {
                RadialGradient(
                    colors: [
                        Color.accentColor.opacity((colorScheme == .dark ? 0.14 : 0.09) * intensity),
                        Color.clear,
                    ],
                    center: .topLeading,
                    startRadius: 24,
                    endRadius: 560
                )

                RadialGradient(
                    colors: [
                        Color.cyan.opacity((colorScheme == .dark ? 0.09 : 0.065) * intensity),
                        Color.clear,
                    ],
                    center: .topTrailing,
                    startRadius: 40,
                    endRadius: 480
                )

                RadialGradient(
                    colors: [
                        Color.indigo.opacity((colorScheme == .dark ? 0.11 : 0.06) * intensity),
                        Color.clear,
                    ],
                    center: .bottomTrailing,
                    startRadius: 30,
                    endRadius: 540
                )
            }
        }
        .ignoresSafeArea()
    }
}

// MARK: - Subtle Pointer Hover Highlight

/// macOS-native pointer hover feedback for interactive tiles, cards, and rows.
private struct OpalInteractiveHoverModifier: ViewModifier {
    let cornerRadius: CGFloat
    let activeTint: Color?
    @State private var isHovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        content
            .overlay(
                shape
                    .fill(hoverFill)
                    .allowsHitTesting(false)
            )
            .overlay(
                shape
                    .strokeBorder(hoverStroke, lineWidth: 1)
                    .allowsHitTesting(false)
            )
            .onHover { hovering in
                if reduceMotion {
                    isHovered = hovering
                } else {
                    withAnimation(OpalMotion.hover) {
                        isHovered = hovering
                    }
                }
            }
    }

    private var hoverFill: Color {
        guard isHovered else { return .clear }
        if let activeTint {
            return activeTint.opacity(colorScheme == .dark ? 0.09 : 0.06)
        }
        return Color.primary.opacity(colorScheme == .dark ? 0.045 : 0.03)
    }

    private var hoverStroke: Color {
        guard isHovered else { return .clear }
        if let activeTint {
            return activeTint.opacity(0.38)
        }
        return Color.accentColor.opacity(colorScheme == .dark ? 0.34 : 0.28)
    }
}

// MARK: - Unified Drag & Drop Overlay

/// Shared Liquid Glass file drop overlay for Display Canvas, Popout Window, and Remote File Browser.
struct OpalDropZoneOverlay: View {
    let icon: String
    let title: String
    let subtitle: String?
    var cornerRadius: CGFloat = OpalRadius.stageOverlay
    var insetPadding: CGFloat = OpalSpacing.xl
    var isCompact: Bool = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        shape
            .strokeBorder(
                Color.accentColor.opacity(0.9),
                style: StrokeStyle(lineWidth: isCompact ? 2.2 : 2.6, dash: [10, 6])
            )
            .background(Color.accentColor.opacity(0.11), in: shape)
            .overlay {
                if !isCompact {
                    VStack(spacing: OpalSpacing.sm) {
                        Image(systemName: icon)
                            .font(.system(size: 36, weight: .semibold))
                            .foregroundStyle(Color.accentColor)
                        Text(title)
                            .font(.title3.weight(.semibold))
                        if let subtitle, !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.callout)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                                .multilineTextAlignment(.center)
                        }
                    }
                    .padding(.horizontal, OpalSpacing.xxl)
                    .padding(.vertical, OpalSpacing.xl)
                    .opalGlassSurface(.stageOverlay, cornerRadius: OpalRadius.panel)
                }
            }
            .padding(insetPadding)
            .allowsHitTesting(false)
    }
}

// MARK: - View Extensions

extension View {
    /// Applies the unified OpalDesk Liquid Glass surface treatment for a given semantic tier.
    func opalGlassSurface(
        _ tier: OpalGlassSurfaceTier,
        cornerRadius: CGFloat? = nil,
        tint: Color? = nil,
        strokeOverride: Color? = nil
    ) -> some View {
        let resolvedRadius: CGFloat = cornerRadius ?? {
            switch tier {
            case .panel: return OpalRadius.panel
            case .card: return OpalRadius.card
            case .inset: return OpalRadius.tile
            case .floatingBar: return OpalRadius.panel
            case .stageOverlay: return OpalRadius.stageOverlay
            }
        }()
        return modifier(OpalGlassSurfaceModifier(
            tier: tier,
            cornerRadius: resolvedRadius,
            tint: tint,
            strokeOverride: strokeOverride,
            isCapsule: false
        ))
    }

    /// Applies the unified Liquid Glass floating capsule chrome (control bars, HUD pills, recording badges, toasts).
    func opalFloatingGlassCapsule(
        tint: Color? = nil,
        strokeColor: Color? = nil
    ) -> some View {
        modifier(OpalGlassSurfaceModifier(
            tier: .floatingBar,
            cornerRadius: 0,
            tint: tint,
            strokeOverride: strokeColor,
            isCapsule: true
        ))
    }

    /// Applies macOS pointer hover highlight feedback on interactive continuous-squircle containers.
    func opalInteractiveHover(cornerRadius: CGFloat = OpalRadius.tile, tint: Color? = nil) -> some View {
        modifier(OpalInteractiveHoverModifier(cornerRadius: cornerRadius, activeTint: tint))
    }

    /// Applies the standard OpalDesk modal sheet backdrop and width/height framing.
    func opalModalSheetChrome(width: CGFloat? = nil, height: CGFloat? = nil) -> some View {
        self
            .frame(width: width, height: height)
            .background(OpalAmbientBackdrop(intensity: 0.7))
    }
}
