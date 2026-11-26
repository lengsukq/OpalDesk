import OpalDeskCore
import SwiftUI

/// Secure TOFU certificate verification and interactive host-consent pairing dialog.
/// Displays the Android host's SHA-256 certificate fingerprint and sends a pairing request
/// that the user approves directly on the phone ("永久允许" / "仅本次允许").
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
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("请求与手机配对")
                        .font(.title3.weight(.semibold))
                    Text("\(context.displayName)\(context.model.isEmpty ? "" : " · \(context.model)")")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }

            // Fingerprint verification card (TOFU)
            VStack(alignment: .leading, spacing: 8) {
                Label("请核对手机端显示的 TLS 证书短指纹", systemImage: "checkmark.seal")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                HStack {
                    Text(context.shortFingerprint)
                        .font(.system(size: 16, weight: .semibold, design: .monospaced))
                        .textSelection(.enabled)
                    Spacer()
                    StatusBadgeView(
                        text: "SHA-256",
                        color: .accentColor,
                        font: .caption2.weight(.medium),
                        horizontalPadding: 6
                    )
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .opalDeskCardContainer(cornerRadius: 16, borderOpacity: 0.18)

                Text("确认与 Android 端「共享本机」页面上的证书指纹一致后，点击下方按钮向手机发送连接请求，并在手机弹窗中点击「永久允许」或「仅本次允许」。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if context.isSubmitting {
                HStack(alignment: .top, spacing: 10) {
                    ProgressView()
                        .controlSize(.small)
                        .padding(.top, 2)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("已发送配对请求，等待手机端确认…")
                            .font(.callout.weight(.semibold))
                        Text("请在手机通知栏或 OpalDesk 弹窗中点击「永久允许」或「仅本次允许」。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.accentColor.opacity(0.09), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }

            if let errorMessage = context.errorMessage {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                    Text(errorMessage)
                        .font(.callout)
                        .foregroundStyle(.red)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }

            HStack {
                Button(context.isSubmitting ? "取消请求" : "取消", role: .cancel) {
                    onCancel()
                }
                .keyboardShortcut(.cancelAction)

                Spacer()

                Button(context.isSubmitting ? "等待手机确认…" : (context.errorMessage == nil ? "信任指纹并请求配对" : "重新发送配对请求")) {
                    onRequestPairing()
                }
                .buttonStyle(.borderedProminent)
                .disabled(context.isSubmitting)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(22)
        .frame(width: 430)
    }
}
