import AppKit
import SwiftUI

// MARK: - Liquid Button Style (LightIsle / iOS-Crafted Tactile Controls)

enum OpalButtonVariant {
    case prominent
    case secondary
    case emerald
    case destructive
    case borderless
}

enum OpalButtonSize {
    case mini
    case small
    case regular
    case large
}

/// Custom continuous-squircle / pill button replacing dated macOS Aqua `.bordered` controls.
struct OpalLiquidButtonStyle: ButtonStyle {
    var variant: OpalButtonVariant = .secondary
    var size: OpalButtonSize = .regular
    var isPill: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        OpalLiquidButtonSurface(
            label: configuration.label,
            pressed: configuration.isPressed,
            variant: variant,
            size: size,
            isPill: isPill
        )
    }
}

private struct OpalLiquidButtonSurface<Label: View>: View {
    let label: Label
    let pressed: Bool
    let variant: OpalButtonVariant
    let size: OpalButtonSize
    let isPill: Bool

    @Environment(\.opalAccent) private var accent
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var hovered = false

    private var cornerRadius: CGFloat {
        if isPill { return 999 }
        switch size {
        case .mini: return 8
        case .small: return 10
        case .regular: return OpalRadius.control
        case .large: return 13
        }
    }

    private var fontSize: CGFloat {
        switch size {
        case .mini: return 11
        case .small: return 12
        case .regular: return 12.5
        case .large: return 13.5
        }
    }

    private var horizontalPadding: CGFloat {
        switch size {
        case .mini: return 9
        case .small: return 11
        case .regular: return 14
        case .large: return 16
        }
    }

    private var minHeight: CGFloat {
        switch size {
        case .mini: return 24
        case .small: return 28
        case .regular: return 32
        case .large: return 38
        }
    }

    private var foreground: Color {
        switch variant {
        case .prominent, .emerald: return OpalFluidPalette.onAccent
        case .destructive: return OpalFluidPalette.brandRed
        case .borderless: return accent
        case .secondary: return .primary
        }
    }

    private var fillTint: Color {
        variant == .emerald ? OpalFluidPalette.brandEmerald : accent
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        label
            .font(.system(size: fontSize, weight: (variant == .prominent || variant == .emerald) ? .semibold : .medium))
            .padding(.horizontal, horizontalPadding)
            .frame(minHeight: minHeight)
            .foregroundStyle(foreground)
            .background {
                Group {
                    switch variant {
                    case .prominent, .emerald:
                        shape.fill(
                            LinearGradient(
                                colors: [fillTint.opacity(hovered ? 1.0 : 0.92), fillTint],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                    case .borderless:
                        shape.fill(hovered ? accent.opacity(0.10) : .clear)
                    case .destructive:
                        shape.fill(hovered ? OpalFluidPalette.brandRed.opacity(0.14) : OpalFluidPalette.brandRed.opacity(0.07))
                    case .secondary:
                        shape.fill(
                            LinearGradient(
                                colors: hovered
                                    ? [OpalFluidPalette.cardHover, OpalFluidPalette.cardTop]
                                    : [OpalFluidPalette.cardTop, OpalFluidPalette.cardBottom],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                    }
                }
                .shadow(
                    color: (variant == .prominent || variant == .emerald)
                        ? fillTint.opacity(enabled ? (hovered ? 0.30 : 0.18) : 0)
                        : .black.opacity(variant == .borderless ? 0 : 0.04),
                    radius: (variant == .prominent || variant == .emerald) ? 10 : 4,
                    y: (variant == .prominent || variant == .emerald) ? 4 : 1.5
                )
            }
            .overlay {
                if variant == .prominent || variant == .emerald {
                    shape
                        .strokeBorder(
                            LinearGradient(
                                colors: [Color.white.opacity(0.36), Color.white.opacity(0.08)],
                                startPoint: .top,
                                endPoint: .bottom
                            ),
                            lineWidth: 0.9
                        )
                        .allowsHitTesting(false)
                } else if variant != .borderless {
                    shape
                        .strokeBorder(
                            contrast == .increased
                                ? Color.primary.opacity(0.5)
                                : (variant == .destructive
                                    ? OpalFluidPalette.brandRed.opacity(0.28)
                                    : OpalFluidPalette.rim.opacity(0.8)),
                            lineWidth: 0.9
                        )
                        .allowsHitTesting(false)
                }
            }
            .contentShape(shape)
            .scaleEffect(enabled && !reduceMotion && pressed ? 0.97 : 1)
            .brightness(enabled && pressed ? -0.03 : 0)
            .opacity(enabled ? 1 : 0.46)
            .animation(OpalMotion.animation(OpalMotion.quick, reduceMotion: reduceMotion), value: hovered)
            .animation(OpalMotion.animation(OpalMotion.fluid, reduceMotion: reduceMotion), value: pressed)
            .onHover { hovered = enabled && $0 }
    }
}

// MARK: - Press Tactile Style & Circular iOS Action Button

struct OpalPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.975 : 1)
            .animation(OpalMotion.animation(OpalMotion.fluid, reduceMotion: reduceMotion), value: configuration.isPressed)
    }
}

/// Iconic iOS circular action button (Call Answer / Decline / Mute / Speaker / Media Play).
struct OpalCircularActionButton: View {
    let systemName: String
    var fill: Color? = nil
    var foreground: Color = .white
    var size: CGFloat = 38
    var iconSize: CGFloat = 15
    var isActive: Bool = false
    var helpText: String
    let action: () -> Void

    @State private var isHovered = false
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(resolvedFill)
                Circle()
                    .strokeBorder(resolvedBorder, lineWidth: 0.85)
                Image(systemName: systemName)
                    .font(.system(size: iconSize, weight: .semibold))
                    .foregroundStyle(resolvedForeground)
            }
            .frame(width: size, height: size)
            .shadow(
                color: fill.map { $0.opacity(isHovered ? 0.42 : 0.28) } ?? .clear,
                radius: fill != nil ? 9 : 0,
                y: fill != nil ? 3 : 0
            )
            .scaleEffect(!reduceMotion && isHovered ? 1.06 : 1.0)
            .contentShape(Circle())
        }
        .buttonStyle(OpalPressStyle())
        .help(helpText)
        .accessibilityLabel(helpText)
        .onHover { hovering in
            if reduceMotion {
                isHovered = hovering
            } else {
                withAnimation(OpalMotion.quick) { isHovered = hovering }
            }
        }
    }

    private var resolvedFill: AnyShapeStyle {
        if let fill {
            return AnyShapeStyle(
                LinearGradient(
                    colors: [fill.opacity(isHovered ? 1.0 : 0.92), fill],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
        if isActive {
            return AnyShapeStyle(Color.primary.opacity(colorScheme == .dark ? 0.24 : 0.14))
        }
        return AnyShapeStyle(
            Color.primary.opacity(
                colorScheme == .dark
                    ? (isHovered ? 0.18 : 0.12)
                    : (isHovered ? 0.11 : 0.065)
            )
        )
    }

    private var resolvedBorder: Color {
        if fill != nil {
            return Color.white.opacity(0.28)
        }
        return Color.primary.opacity(colorScheme == .dark ? 0.16 : 0.10)
    }

    private var resolvedForeground: Color {
        if fill != nil { return foreground }
        return isActive ? foreground : .primary
    }
}

// MARK: - iOS Sliding Pill Segmented Control (LightIsle Style)

struct OpalSegmentOption<Value: Hashable>: Identifiable {
    let value: Value
    let label: String
    var icon: String? = nil
    var accessibilityLabel: String? = nil
    var id: Value { value }
}

/// Custom animated sliding-pill segmented picker replacing stock macOS NSSegmentedControl.
struct OpalSegmentedControl<Value: Hashable>: View {
    @Binding var selection: Value
    let options: [OpalSegmentOption<Value>]
    var compact: Bool = false

    @Namespace private var thumbNamespace
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 3) {
            ForEach(options) { option in
                let isSelected = selection == option.value
                let hasLabel = !option.label.isEmpty
                Button {
                    withAnimation(OpalMotion.animation(OpalMotion.navigation, reduceMotion: reduceMotion)) {
                        selection = option.value
                    }
                } label: {
                    HStack(spacing: hasLabel ? 5 : 0) {
                        if let icon = option.icon {
                            Image(systemName: icon)
                                .font(.system(size: compact ? 11 : 11.5, weight: .semibold))
                        }
                        if hasLabel {
                            Text(option.label)
                                .lineLimit(1)
                        }
                    }
                    .font(.system(size: compact ? 11.5 : 12.5, weight: isSelected ? .semibold : .medium))
                    .foregroundStyle(isSelected ? Color.primary : OpalFluidPalette.secondaryText)
                    .padding(.horizontal, hasLabel ? (compact ? 10 : 13) : (compact ? 8.5 : 10))
                    .frame(minHeight: compact ? 26 : 30)
                    .background {
                        if isSelected {
                            RoundedRectangle(cornerRadius: compact ? 7.5 : 9, style: .continuous)
                                .fill(
                                    LinearGradient(
                                        colors: [OpalFluidPalette.cardTop, OpalFluidPalette.cardBottom],
                                        startPoint: .top,
                                        endPoint: .bottom
                                    )
                                )
                                .overlay {
                                    RoundedRectangle(cornerRadius: compact ? 7.5 : 9, style: .continuous)
                                        .strokeBorder(OpalFluidPalette.rim.opacity(0.68), lineWidth: 0.8)
                                }
                                .shadow(color: .black.opacity(0.08), radius: 4, y: 1.5)
                                .matchedGeometryEffect(id: "opal-segment-thumb", in: thumbNamespace)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(OpalPressStyle())
                .accessibilityLabel(option.accessibilityLabel ?? option.label)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .padding(3)
        .background(Color.primary.opacity(0.055), in: RoundedRectangle(cornerRadius: compact ? 10 : 12, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: compact ? 10 : 12, style: .continuous)
                .strokeBorder(OpalFluidPalette.rim.opacity(0.45), lineWidth: 0.8)
                .allowsHitTesting(false)
        }
        .fixedSize(horizontal: true, vertical: false)
    }
}

// MARK: - Liquid Input Field Modifier

private struct OpalLiquidInputModifier: ViewModifier {
    var cornerRadius: CGFloat = OpalRadius.control
    @FocusState private var isFocused: Bool
    @Environment(\.opalAccent) private var accent
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        content
            .textFieldStyle(.plain)
            .focused($isFocused)
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .frame(minHeight: 32)
            .background(
                LinearGradient(
                    colors: [OpalFluidPalette.cardItemTop, OpalFluidPalette.cardTop],
                    startPoint: .top,
                    endPoint: .bottom
                ),
                in: shape
            )
            .overlay {
                shape
                    .strokeBorder(
                        isFocused
                            ? accent
                            : (contrast == .increased ? Color.primary.opacity(0.5) : OpalFluidPalette.rim.opacity(0.8)),
                        lineWidth: isFocused ? 1.6 : 0.9
                    )
                    .allowsHitTesting(false)
            }
            .shadow(color: isFocused ? accent.opacity(0.14) : .clear, radius: 6, y: 2)
            .animation(OpalMotion.animation(OpalMotion.quick, reduceMotion: reduceMotion), value: isFocused)
    }
}

extension View {
    func opalLiquidInput(cornerRadius: CGFloat = OpalRadius.control) -> some View {
        modifier(OpalLiquidInputModifier(cornerRadius: cornerRadius))
    }
}

// MARK: - iOS Inset-Grouped Settings Primitives (LightIsle Style)

struct OpalSettingsGroup<Content: View>: View {
    let title: String?
    let footer: String?
    @ViewBuilder let content: () -> Content

    init(
        title: String? = nil,
        footer: String? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.footer = footer
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            if let title, !title.isEmpty {
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.primary.opacity(0.86))
                    .padding(.horizontal, 4)
            }

            VStack(spacing: 0) {
                content()
            }
            .clipShape(RoundedRectangle(cornerRadius: OpalRadius.panel, style: .continuous))
            .opalGlassSurface(.panel)

            if let footer, !footer.isEmpty {
                Text(footer)
                    .font(.system(size: 11.5))
                    .foregroundStyle(OpalFluidPalette.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 6)
            }
        }
    }
}

struct OpalSettingsRow<RightContent: View>: View {
    let title: String
    var subtitle: String? = nil
    var icon: String? = nil
    var iconTint: Color? = nil
    var showsDivider: Bool = true
    @ViewBuilder let rightContent: () -> RightContent

    @Environment(\.opalAccent) private var accent

    init(
        title: String,
        subtitle: String? = nil,
        icon: String? = nil,
        iconTint: Color? = nil,
        showsDivider: Bool = true,
        @ViewBuilder rightContent: @escaping () -> RightContent
    ) {
        self.title = title
        self.subtitle = subtitle
        self.icon = icon
        self.iconTint = iconTint
        self.showsDivider = showsDivider
        self.rightContent = rightContent
    }

    private var resolvedTint: Color { iconTint ?? accent }

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            HStack(alignment: .center, spacing: 11) {
                if let icon {
                    OpalIconBadge(
                        systemName: icon,
                        tint: resolvedTint,
                        size: 30,
                        iconSize: 13.5,
                        cornerRadius: 8.5
                    )
                    .accessibilityHidden(true)
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 13, weight: .medium))
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.system(size: 11.5))
                            .foregroundStyle(OpalFluidPalette.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            rightContent()
                .fixedSize(horizontal: true, vertical: false)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .overlay(alignment: .bottom) {
            if showsDivider {
                Rectangle()
                    .fill(OpalFluidPalette.divider.opacity(0.62))
                    .frame(height: 0.75)
                    .padding(.leading, icon != nil ? 57 : 16)
                    .allowsHitTesting(false)
            }
        }
    }
}
