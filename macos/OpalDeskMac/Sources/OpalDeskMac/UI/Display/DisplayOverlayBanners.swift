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
                Text("手机系统阻止了默认输入注入（如安全限制 / 未开模拟点击）")
                    .font(.system(size: 12, weight: .semibold))
                Text("切换至 UHID 虚拟键鼠外设可原生绕过限制，获得低延迟完整控屏体验。")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }

            Button(action: onSwitchToUHID) {
                Label("一键切换 UHID", systemImage: "keyboard.badge.ellipsis")
                    .font(.system(size: 11, weight: .semibold))
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)

            OpalGlassIconButton(
                systemName: "xmark",
                tint: .secondary,
                size: 24,
                iconSize: 10.5,
                helpText: "暂时隐藏此提示",
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
                    helpText: "取消传输"
                ) { onCancel(transfer.transferId) }
            } else {
                OpalGlassIconButton(
                    systemName: "xmark",
                    tint: .secondary,
                    size: 24,
                    iconSize: 10.5,
                    helpText: "关闭提示"
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
            Text("等待手机端落盘确认…")
                .font(.caption)
                .foregroundStyle(.secondary)
        case .completed(let remotePath):
            Text(remotePath.isEmpty ? "已发送到手机" : "已保存：\(remotePath)")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        case .failed(let reason):
            Text("失败：\(reason)")
                .font(.caption)
                .foregroundStyle(.red)
                .lineLimit(2)
        case .cancelled:
            Text("已取消")
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
