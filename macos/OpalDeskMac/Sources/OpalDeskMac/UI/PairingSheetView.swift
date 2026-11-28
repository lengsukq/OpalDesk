import OpalDeskCore
import SwiftUI

/// Secure TOFU certificate verification and interactive host-consent pairing dialog.
public struct PairingSheetView: View {
    public let context: PairingPromptContext
    public let onRequestPairing: () -> Void
    public let onCancel: () -> Void

    public init(
        context: PairingPromptContext,
        onRequestPairing: @escaping () -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.context = context
        self.onRequestPairing = onRequestPairing
        self.onCancel = onCancel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.lg) {
            OpalSheetHeader(
                icon: "lock.shield.fill",
                iconTint: .accentColor,
                title: OpalL10n.text("pairing.title"),
                subtitle: "\(context.displayName)\(context.model.isEmpty ? "" : " · \(context.model)")"
            )

            VStack(alignment: .leading, spacing: OpalSpacing.sm) {
                Label(OpalL10n.text("pairing.verify_label"), systemImage: "checkmark.seal")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                HStack {
                    Text(context.shortFingerprint)
                        .font(.system(size: 16, weight: .semibold, design: .monospaced))
                        .textSelection(.enabled)
                    Spacer()
                    StatusBadgeView(text: "SHA-256", color: .accentColor)
                }
                .padding(.horizontal, OpalSpacing.md)
                .padding(.vertical, 11)
                .opalGlassSurface(.card, cornerRadius: OpalRadius.card)

                Text(OpalL10n.text("pairing.instructions"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if context.isSubmitting {
                OpalNoticeBanner(
                    tone: .loading,
                    title: OpalL10n.text("pairing.submitting_title"),
                    message: OpalL10n.text("pairing.submitting_msg")
                )
            }

            if let errorMessage = context.errorMessage {
                OpalNoticeBanner(tone: .error, message: errorMessage)
            }

            HStack {
                Button(context.isSubmitting ? OpalL10n.text("pairing.cancel_request") : OpalL10n.text("common.cancel"), role: .cancel, action: onCancel)
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .regular, isPill: true))
                    .keyboardShortcut(.cancelAction)

                Spacer()

                Button(primaryActionTitle, action: onRequestPairing)
                    .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
                    .disabled(context.isSubmitting)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(OpalSpacing.xxl)
        .opalModalSheetChrome(width: 440)
    }

    private var primaryActionTitle: String {
        if context.isSubmitting { return OpalL10n.text("pairing.waiting") }
        return context.errorMessage == nil ? OpalL10n.text("pairing.trust_and_pair") : OpalL10n.text("pairing.resend")
    }
}
