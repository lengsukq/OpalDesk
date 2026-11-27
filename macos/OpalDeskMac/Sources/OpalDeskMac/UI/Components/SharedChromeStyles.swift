import SwiftUI

extension ActiveTransportKind {
    var badgeColor: Color {
        switch self {
        case .wiredUSB: return .accentColor
        case .wirelessADB: return .orange
        case .wirelessWiFi: return .green
        }
    }
}

/// Compact capsule status pill shared across Device Hub, Display Control Bar, Quick Launcher, and Pairing sheets.
struct StatusBadgeView: View {
    let text: String
    let color: Color
    var font: Font = .caption2.weight(.semibold)
    var horizontalPadding: CGFloat = 7
    var verticalPadding: CGFloat = 2
    var backgroundOpacity: Double = 0.14

    var body: some View {
        Text(text)
            .font(font)
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, verticalPadding)
            .background(color.opacity(backgroundOpacity), in: Capsule())
            .foregroundStyle(color)
    }
}

/// Translucent floating notification pill displayed over the display canvas or hub.
struct ToastPillView: View {
    let message: String
    var actionTitle: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "sparkles")
                .font(.caption)
                .foregroundStyle(Color.accentColor)
            Text(message)
                .font(.system(size: 12, weight: .medium))
                .lineLimit(2)

            if let actionTitle, let onAction {
                Button(actionTitle, action: onAction)
                    .buttonStyle(.bordered)
                    .controlSize(.mini)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .floatingCapsuleChrome(shadowRadius: 10, shadowY: 4, shadowOpacity: 0.28)
    }
}

extension View {
    /// iOS / macOS Liquid Glass frosted continuous-squircle card container with 45° specular rim.
    func opalDeskCardContainer(cornerRadius: CGFloat = 22, borderOpacity: Double = 0.22) -> some View {
        self
            .background(
                .regularMaterial,
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [
                                Color.white.opacity(borderOpacity * 1.8),
                                Color.white.opacity(borderOpacity * 0.45),
                                Color.white.opacity(borderOpacity * 1.1),
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 1
                    )
            )
            .shadow(color: Color.black.opacity(0.08), radius: 14, y: 5)
    }

    /// Translucent Liquid Glass floating capsule chrome shared by control bars and toast pills.
    func floatingCapsuleChrome(
        strokeColor: Color = Color.white.opacity(0.28),
        lineWidth: CGFloat = 0.95,
        shadowRadius: CGFloat = 16,
        shadowY: CGFloat = 6,
        shadowOpacity: Double = 0.35
    ) -> some View {
        self
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(
                Capsule()
                    .strokeBorder(
                        LinearGradient(
                            colors: [strokeColor, strokeColor.opacity(0.32), strokeColor.opacity(0.75)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: lineWidth
                    )
            )
            .shadow(color: Color.black.opacity(shadowOpacity), radius: shadowRadius, y: shadowY)
    }
}
