import SwiftUI

// MARK: - Backwards-Compatible View Modifier Forwarding
// Canonical tokens, Liquid Glass surfaces, and reusable components live in `UI/DesignSystem/`.

extension View {
    /// Unified macOS Liquid Glass continuous-squircle card container with adaptive 45° specular rim.
    func opalDeskCardContainer(cornerRadius: CGFloat = OpalRadius.panel, borderOpacity: Double = 0.22) -> some View {
        let tier: OpalGlassSurfaceTier = cornerRadius >= OpalRadius.panel ? .panel : .card
        _ = borderOpacity
        return opalGlassSurface(tier, cornerRadius: cornerRadius)
    }

    /// Unified translucent Liquid Glass floating capsule chrome shared by control bars, HUDs, and toast pills.
    func floatingCapsuleChrome(
        strokeColor: Color? = nil,
        lineWidth: CGFloat = 0.95,
        shadowRadius: CGFloat = 16,
        shadowY: CGFloat = 6,
        shadowOpacity: Double = 0.35
    ) -> some View {
        _ = (lineWidth, shadowRadius, shadowY, shadowOpacity)
        return opalFloatingGlassCapsule(strokeColor: strokeColor)
    }
}
