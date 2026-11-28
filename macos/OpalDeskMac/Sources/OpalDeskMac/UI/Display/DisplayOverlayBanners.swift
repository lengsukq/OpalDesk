import OpalDeskCore
import SwiftUI

/// Smart warning banner shown when Android blocks default `INJECT_EVENTS`, offering one-click UHID switch.
struct InjectDeniedSmartBanner: View {
    let onSwitchToUHID: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(
                systemName: "exclamationmark.shield.fill",
                tint: .orange,
                size: 32,
                iconSize: 15,
                cornerRadius: 10
            )

            VStack(alignment: .leading, spacing: 2) {
                Text(OpalL10n.text("display.inject.title"))
                    .font(.system(size: 12, weight: .semibold))
                Text(OpalL10n.text("display.inject.subtitle"))
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }

            Button(action: onSwitchToUHID) {
                Label(OpalL10n.text("display.inject.switch_uhid"), systemImage: "keyboard.badge.ellipsis")
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .small, isPill: true))

            OpalGlassIconButton(
                systemName: "xmark",
                tint: .secondary,
                size: 24,
                iconSize: 10.5,
                helpText: OpalL10n.text("display.inject.dismiss_help"),
                action: onDismiss
            )
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 10)
        .opalGlassSurface(.stageOverlay, cornerRadius: OpalRadius.card, tint: .orange)
    }
}

/// Bottom-right stack of active and recently completed file transfer progress cards.
struct FileTransferToastStack: View {
    let transfers: [FileTransferProgress]
    let onCancel: (UInt32) -> Void
    let onDismiss: (UInt32) -> Void

    var body: some View {
        VStack(alignment: .trailing, spacing: OpalSpacing.sm) {
            ForEach(transfers) { transfer in
                transferRow(transfer)
            }
        }
    }

    private func transferRow(_ transfer: FileTransferProgress) -> some View {
        HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(
                systemName: transferIcon(for: transfer.status),
                tint: transferColor(for: transfer.status),
                size: 30,
                iconSize: 14,
                cornerRadius: 9
            )

            VStack(alignment: .leading, spacing: 3) {
                Text(transfer.fileName)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.middle)

                transferStatusDetail(transfer)
            }

            if transfer.status == .sending {
                OpalGlassIconButton(
                    systemName: "xmark",
                    tint: .secondary,
                    size: 24,
                    iconSize: 10.5,
                    helpText: OpalL10n.text("display.transfer.cancel_help")
                ) { onCancel(transfer.transferId) }
            } else {
                OpalGlassIconButton(
                    systemName: "xmark",
                    tint: .secondary,
                    size: 24,
                    iconSize: 10.5,
                    helpText: OpalL10n.text("common.dismiss_tip")
                ) { onDismiss(transfer.transferId) }
            }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 9)
        .opalGlassSurface(.stageOverlay, cornerRadius: OpalRadius.tile)
    }

    @ViewBuilder
    private func transferStatusDetail(_ transfer: FileTransferProgress) -> some View {
        switch transfer.status {
        case .sending:
            ProgressView(value: transfer.fractionCompleted)
                .frame(width: 170)
        case .awaitingAck:
            Text(OpalL10n.text("display.transfer.awaiting_ack"))
                .font(.caption)
                .foregroundStyle(.secondary)
        case .completed(let remotePath):
            Text(remotePath.isEmpty ? OpalL10n.text("display.transfer.sent") : OpalL10n.format("display.transfer.saved_fmt", remotePath))
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        case .failed(let reason):
            Text(OpalL10n.format("display.transfer.failed_fmt", reason))
                .font(.caption)
                .foregroundStyle(.red)
                .lineLimit(2)
        case .cancelled:
            Text(OpalL10n.text("display.transfer.cancelled"))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func transferIcon(for status: FileTransferStatus) -> String {
        switch status {
        case .sending, .awaitingAck: return "arrow.up.circle.fill"
        case .completed: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        case .cancelled: return "slash.circle"
        }
    }

    private func transferColor(for status: FileTransferStatus) -> Color {
        switch status {
        case .sending, .awaitingAck: return .accentColor
        case .completed: return .green
        case .failed: return .red
        case .cancelled: return .secondary
        }
    }
}
