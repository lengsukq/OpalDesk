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
                title: "请求与手机配对",
                subtitle: "\(context.displayName)\(context.model.isEmpty ? "" : " · \(context.model)")"
            )

            VStack(alignment: .leading, spacing: OpalSpacing.sm) {
                Label("请核对手机端显示的 TLS 证书短指纹", systemImage: "checkmark.seal")
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

                Text("确认与 Android 端「共享本机」页面上的证书指纹一致后，点击下方按钮向手机发送连接请求，并在手机弹窗中点击「永久允许」或「仅本次允许」。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if context.isSubmitting {
                OpalNoticeBanner(
                    tone: .loading,
                    title: "已发送配对请求，等待手机端确认…",
                    message: "请在手机通知栏或 OpalDesk 弹窗中点击「永久允许」或「仅本次允许」。"
                )
            }

            if let errorMessage = context.errorMessage {
                OpalNoticeBanner(tone: .error, message: errorMessage)
            }

            HStack {
                Button(context.isSubmitting ? "取消请求" : "取消", role: .cancel, action: onCancel)
                    .keyboardShortcut(.cancelAction)

                Spacer()

                Button(primaryActionTitle, action: onRequestPairing)
                    .buttonStyle(.borderedProminent)
                    .disabled(context.isSubmitting)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(OpalSpacing.xxl)
        .opalModalSheetChrome(width: 440)
    }

    private var primaryActionTitle: String {
        if context.isSubmitting { return "等待手机确认…" }
        return context.errorMessage == nil ? "信任指纹并请求配对" : "重新发送配对请求"
    }
}
