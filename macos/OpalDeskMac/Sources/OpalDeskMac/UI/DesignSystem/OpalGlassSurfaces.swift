import SwiftUI

// MARK: - Surface Tiers

/// Strict two-layer Apple HIG + LightIsle surface architecture:
/// - **Content Layer** (`.panel`, `.card`, `.inset`): Crisp top-lit luminous solid surfaces
///   (never muddy frosted gray boxes!) with continuous squircle geometry, delicate specular
///   highlight rim, and airy ambient shadow.
/// - **Functional Floating Layer** (`.floatingBar`, `.stageOverlay`): True Liquid Glass
///   (`.ultraThinMaterial` / `.regularMaterial` + translucent tint wash + specular rim) reserved
///   strictly for floating sidebars, floating status capsules, toasts, and video stage controls.
enum OpalGlassSurfaceTier {
    case panel
    case card
    case inset
    case floatingBar
    case stageOverlay
}

// MARK: - Adaptive Two-Layer Surface Modifier

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
                .background {
                    capsuleBackground
                        .shadow(color: shadowColor, radius: shadowRadius, y: shadowY)
                }
                .overlay(capsuleSpecularRim)
        } else {
            let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            content
                .background {
                    rectBackground(shape: shape)
                        .shadow(color: shadowColor, radius: shadowRadius, y: shadowY)
                }
                .overlay(rectSpecularRim(shape: shape))
        }
    }

    // MARK: - Backgrounds

    @ViewBuilder
    private var capsuleBackground: some View {
        ZStack {
            if reduceTransparency || isHighContrast || isSolidContentTier {
                Capsule().fill(solidGradient)
            } else {
                Capsule().fill(floatingMaterial)
                Capsule().fill(floatingWashGradient)
            }
            if let tint {
                Capsule().fill(tint.opacity(tintFillOpacity))
            }
        }
    }

    @ViewBuilder
    private func rectBackground(shape: RoundedRectangle) -> some View {
        ZStack {
            if reduceTransparency || isHighContrast || isSolidContentTier {
                shape.fill(solidGradient)
            } else {
                shape.fill(floatingMaterial)
                shape.fill(floatingWashGradient)
            }
            if let tint {
                shape.fill(tint.opacity(tintFillOpacity))
            }
        }
    }

    private var isSolidContentTier: Bool {
        switch tier {
        case .panel, .card, .inset: return true
        case .floatingBar, .stageOverlay: return false
        }
    }

    private var solidGradient: LinearGradient {
        switch tier {
        case .panel:
            return LinearGradient(
                colors: [OpalFluidPalette.cardTop, OpalFluidPalette.cardBottom],
                startPoint: .top,
                endPoint: .bottom
            )
        case .card:
            return LinearGradient(
                colors: [OpalFluidPalette.cardItemTop, OpalFluidPalette.cardItemBottom],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        case .inset:
            let fill = isDark
                ? Color.white.opacity(0.048)
                : OpalFluidPalette.canvasTop.opacity(0.85)
            return LinearGradient(colors: [fill, fill], startPoint: .top, endPoint: .bottom)
        case .floatingBar, .stageOverlay:
            return LinearGradient(
                colors: [OpalFluidPalette.cardTop, OpalFluidPalette.cardBottom],
                startPoint: .top,
                endPoint: .bottom
            )
        }
    }

    private var floatingMaterial: Material {
        tier == .floatingBar ? .ultraThinMaterial : .regularMaterial
    }

    private var floatingWashGradient: LinearGradient {
        switch tier {
        case .floatingBar:
            return LinearGradient(
                colors: isDark
                    ? [OpalFluidPalette.cardTop.opacity(0.56), OpalFluidPalette.cardBottom.opacity(0.36)]
                    : [OpalFluidPalette.cardTop.opacity(0.68), OpalFluidPalette.cardBottom.opacity(0.46)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        case .stageOverlay:
            return LinearGradient(
                colors: isDark
                    ? [Color.black.opacity(0.34), OpalFluidPalette.cardBottom.opacity(0.48)]
                    : [OpalFluidPalette.cardTop.opacity(0.76), OpalFluidPalette.cardBottom.opacity(0.58)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        default:
            return solidGradient
        }
    }

    // MARK: - Specular Rim

    private var capsuleSpecularRim: some View {
        Capsule()
            .strokeBorder(specularGradient, lineWidth: rimLineWidth)
            .allowsHitTesting(false)
    }

    private func rectSpecularRim(shape: RoundedRectangle) -> some View {
        shape
            .strokeBorder(specularGradient, lineWidth: rimLineWidth)
            .allowsHitTesting(false)
    }

    private var tintFillOpacity: Double {
        if isHighContrast { return isDark ? 0.18 : 0.12 }
        switch tier {
        case .inset: return isDark ? 0.11 : 0.068
        case .card: return isDark ? 0.09 : 0.055
        case .panel, .floatingBar, .stageOverlay: return isDark ? 0.10 : 0.055
        }
    }

    private var specularGradient: LinearGradient {
        if let strokeOverride {
            return LinearGradient(
                colors: [strokeOverride, strokeOverride.opacity(0.45), strokeOverride.opacity(0.80)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
        if isHighContrast {
            return LinearGradient(
                colors: [Color.primary.opacity(0.48), Color.primary.opacity(0.42)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
        if let tint {
            let lead = isDark ? 0.36 : 0.28
            return LinearGradient(
                colors: [tint.opacity(lead), tint.opacity(lead * 0.45), OpalFluidPalette.rim.opacity(0.45)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
        switch tier {
        case .floatingBar, .stageOverlay:
            return LinearGradient(
                colors: [
                    Color.white.opacity(isDark ? 0.32 : 0.65),
                    OpalFluidPalette.rim.opacity(0.58),
                    Color.primary.opacity(0.08),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        case .panel, .card:
            return LinearGradient(
                colors: [
                    Color.white.opacity(isDark ? 0.22 : 0.72),
                    OpalFluidPalette.rim.opacity(isDark ? 0.56 : 0.68),
                    Color.primary.opacity(0.06),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        case .inset:
            return LinearGradient(
                colors: [
                    OpalFluidPalette.rim.opacity(isDark ? 0.44 : 0.55),
                    OpalFluidPalette.rim.opacity(isDark ? 0.28 : 0.35),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
    }

    private var rimLineWidth: CGFloat {
        isHighContrast ? 1.15 : 0.9
    }

    private var shadowColor: Color {
        switch tier {
        case .inset:
            return .clear
        case .card:
            return Color.black.opacity(isDark ? 0.20 : 0.048)
        case .panel:
            return Color.black.opacity(isDark ? 0.28 : OpalFluidPalette.cardShadowOpacity)
        case .floatingBar:
            return Color.black.opacity(isDark ? 0.36 : OpalFluidPalette.cardShadowOpacity * 1.25)
        case .stageOverlay:
            return Color.black.opacity(isDark ? 0.44 : 0.14)
        }
    }

    private var shadowRadius: CGFloat {
        switch tier {
        case .inset: return 0
        case .card: return 11
        case .panel: return OpalFluidPalette.cardShadowRadius
        case .floatingBar: return OpalFluidPalette.cardShadowRadius + 4
        case .stageOverlay: return 22
        }
    }

    private var shadowY: CGFloat {
        switch tier {
        case .inset: return 0
        case .card: return 4
        case .panel: return OpalFluidPalette.cardShadowY
        case .floatingBar: return OpalFluidPalette.cardShadowY + 2
        case .stageOverlay: return 9
        }
    }
}

// MARK: - Ambient Fluid Canvas Backdrop (LightIsle Style)

/// Continuous cool-alabaster / deep-slate fluid backdrop with restrained static luminescence.
/// Uses `.drawingGroup(opaque: true)` so scroll frames stay locked at 120fps.
struct OpalAmbientBackdrop: View {
    var intensity: Double = 1.0
    var accent: Color? = nil

    @Environment(\.opalAccent) private var envAccent
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var resolvedAccent: Color { accent ?? envAccent }

    var body: some View {
        ZStack {
            OpalFluidPalette.canvasTop

            if !reduceTransparency && colorSchemeContrast != .increased {
                LinearGradient(
                    colors: [OpalFluidPalette.canvasTop, OpalFluidPalette.canvasBottom],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )

                RadialGradient(
                    colors: [resolvedAccent.opacity(0.095 * intensity), .clear],
                    center: .topLeading,
                    startRadius: 10,
                    endRadius: 560
                )

                RadialGradient(
                    colors: [OpalFluidPalette.brandCyan.opacity(0.075 * intensity), .clear],
                    center: .topTrailing,
                    startRadius: 0,
                    endRadius: 620
                )

                RadialGradient(
                    colors: [OpalFluidPalette.brandViolet.opacity(0.06 * intensity), .clear],
                    center: .bottomLeading,
                    startRadius: 0,
                    endRadius: 540
                )
            }
        }
        .drawingGroup(opaque: true)
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

// MARK: - Subtle Pointer Hover Lift & Glow (LightIsle Style)

private struct OpalInteractiveHoverModifier: ViewModifier {
    let cornerRadius: CGFloat
    let activeTint: Color?
    let liftOnHover: Bool
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
            .offset(y: (isHovered && liftOnHover && !reduceMotion) ? -1.5 : 0)
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
            return activeTint.opacity(colorScheme == .dark ? 0.08 : 0.045)
        }
        return OpalFluidPalette.brandBlue.opacity(colorScheme == .dark ? 0.06 : 0.035)
    }

    private var hoverStroke: Color {
        guard isHovered else { return .clear }
        if let activeTint {
            return activeTint.opacity(0.36)
        }
        return OpalFluidPalette.rim.opacity(colorScheme == .dark ? 0.85 : 0.95)
    }
}

// MARK: - Unified Drag & Drop Overlay

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
                OpalFluidPalette.brandBlue.opacity(0.88),
                style: StrokeStyle(lineWidth: isCompact ? 2.2 : 2.6, dash: [10, 6])
            )
            .background(OpalFluidPalette.brandBlue.opacity(0.10), in: shape)
            .overlay {
                if !isCompact {
                    VStack(spacing: OpalSpacing.sm) {
                        Image(systemName: icon)
                            .font(.system(size: 36, weight: .semibold))
                            .foregroundStyle(OpalFluidPalette.brandBlue)
                        Text(title)
                            .font(.title3.weight(.semibold))
                        if let subtitle, !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.callout)
                                .foregroundStyle(OpalFluidPalette.secondaryText)
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
    /// Applies the unified OpalDesk two-layer surface treatment for a given semantic tier.
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

    /// Applies the unified Liquid Glass floating capsule chrome (status pills, toasts, stage pills).
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

    /// Applies LightIsle-style smooth pointer hover lift & luminous highlight on continuous-squircle tiles.
    func opalInteractiveHover(
        cornerRadius: CGFloat = OpalRadius.tile,
        tint: Color? = nil,
        lift: Bool = true
    ) -> some View {
        modifier(OpalInteractiveHoverModifier(
            cornerRadius: cornerRadius,
            activeTint: tint,
            liftOnHover: lift
        ))
    }

    /// Applies the standard OpalDesk modal sheet fluid backdrop and framing.
    func opalModalSheetChrome(width: CGFloat? = nil, height: CGFloat? = nil) -> some View {
        self
            .frame(width: width, height: height)
            .background(OpalAmbientBackdrop(intensity: 0.72))
    }
}
