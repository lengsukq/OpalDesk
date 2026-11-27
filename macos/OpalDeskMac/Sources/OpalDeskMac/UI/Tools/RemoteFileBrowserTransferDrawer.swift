import AppKit
import OpalDeskCore
import SwiftUI

// MARK: - Transfer Queue Dock, Modal Dialogs & Icon/Color Helpers

extension RemoteFileBrowserWindowView {
    var transferQueueStrip: some View {
        let activeCount = transfers.filter { !$0.state.isTerminal }.count

        return VStack(spacing: 0) {
            HStack(spacing: OpalSpacing.sm) {
                Button {
                    withAnimation(OpalMotion.quick) {
                        isTransferDrawerExpanded.toggle()
                    }
                } label: {
                    HStack(spacing: OpalSpacing.xs) {
                        Image(systemName: isTransferDrawerExpanded ? "chevron.down" : "chevron.right")
                            .font(.system(size: 10.5, weight: .semibold))
                        Text(activeCount > 0 ? "传输队列（进行中 \(activeCount) / 共 \(transfers.count) 项）" : "传输记录（共 \(transfers.count) 项）")
                            .font(.caption.weight(.semibold))
                    }
                }
                .buttonStyle(.plain)

                Spacer()

                if transfers.contains(where: { $0.state.isTerminal }) {
                    Button("清除已完成") { actions.onClearCompletedTransfers() }
                        .buttonStyle(.borderless)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, OpalSpacing.lg)
            .padding(.vertical, 9)

            if isTransferDrawerExpanded {
                Divider().opacity(0.5)
                ScrollView {
                    VStack(spacing: OpalSpacing.xs) {
                        ForEach(transfers) { transfer in
                            transferQueueRow(transfer)
                        }
                    }
                    .padding(OpalSpacing.md)
                }
                .frame(maxHeight: 140)
            }
        }
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func transferQueueRow(_ transfer: RemoteFileTransferItem) -> some View {
        HStack(spacing: OpalSpacing.sm) {
            Image(systemName: transfer.direction.sfSymbolName)
                .font(.system(size: 14))
                .foregroundStyle(transferStatusColor(for: transfer.state))

            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(transfer.fileName)
                        .font(.caption.weight(.medium))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                    Text(transfer.statusSummary)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(transferStatusColor(for: transfer.state))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                if !transfer.state.isTerminal {
                    ProgressView(value: transfer.fractionCompleted)
                        .progressViewStyle(.linear)
                }
            }

            if transfer.state.isTerminal {
                OpalGlassIconButton(
                    systemName: "xmark",
                    tint: .secondary,
                    size: 22,
                    iconSize: 10,
                    helpText: "移除此记录"
                ) { actions.onDismissTransfer(transfer.id) }
            } else {
                OpalGlassIconButton(
                    systemName: "xmark.circle.fill",
                    tint: .secondary,
                    size: 22,
                    iconSize: 12,
                    helpText: "取消传输"
                ) { actions.onCancelTransfer(transfer.id) }
            }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 7)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.control)
    }

    // MARK: - Modal Sheets

    var newFolderSheet: some View {
        let joinedPath = FileBrowserPresentation.joining(base: currentPath, name: newFolderName)
        let showValidationError = !newFolderName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && joinedPath == nil

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSheetHeader(
                icon: "folder.badge.plus",
                iconTint: .accentColor,
                title: "新建文件夹",
                subtitle: "将在「\(currentPath)」下创建子目录"
            )

            TextField("输入文件夹名称", text: $newFolderName)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    if FileBrowserPresentation.joining(base: currentPath, name: newFolderName) != nil {
                        let clean = newFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
                        isNewFolderPresented = false
                        actions.onCreateFolder(currentPath, clean)
                    }
                }

            if showValidationError {
                OpalNoticeBanner(tone: .error, message: "名称不能为空、不能为 . 或 ..，且不能包含 / 或 \\。")
            }

            HStack {
                Button("取消", role: .cancel) { isNewFolderPresented = false }
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button("创建") {
                    let clean = newFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
                    isNewFolderPresented = false
                    actions.onCreateFolder(currentPath, clean)
                }
                .buttonStyle(.borderedProminent)
                .disabled(joinedPath == nil)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(OpalSpacing.xl)
        .opalModalSheetChrome(width: 390)
    }

    func renameSheet(for item: RemoteFileItem) -> some View {
        let parent = FileBrowserPresentation.parentPath(of: item.path) ?? currentPath
        let joinedPath = FileBrowserPresentation.joining(base: parent, name: renameInput)
        let trimmed = renameInput.trimmingCharacters(in: .whitespacesAndNewlines)
        let isChanged = trimmed != item.name
        let showValidationError = !trimmed.isEmpty && joinedPath == nil

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSheetHeader(
                icon: "pencil",
                iconTint: .accentColor,
                title: "重命名项目",
                subtitle: item.name
            )

            TextField("输入新名称", text: $renameInput)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    if joinedPath != nil && isChanged {
                        renamingItem = nil
                        actions.onRenameItem(item, trimmed)
                    }
                }

            if showValidationError {
                OpalNoticeBanner(tone: .error, message: "名称不能为 . 或 ..，且不能包含 / 或 \\。")
            }

            HStack {
                Button("取消", role: .cancel) { renamingItem = nil }
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button("确定") {
                    renamingItem = nil
                    actions.onRenameItem(item, trimmed)
                }
                .buttonStyle(.borderedProminent)
                .disabled(joinedPath == nil || !isChanged)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(OpalSpacing.xl)
        .opalModalSheetChrome(width: 390)
    }

    // MARK: - Shared Icon & Semantic Color Helpers

    @ViewBuilder
    func fileIconView(for item: RemoteFileItem, size: CGFloat) -> some View {
        let radius = max(size * 0.22, 7)
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)

        if let thumbData = item.thumbnailData, let nsImage = NSImage(data: thumbData) {
            Image(nsImage: nsImage)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: size, height: size)
                .clipShape(shape)
                .overlay(shape.strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.6))
        } else {
            OpalIconBadge(
                systemName: item.sfSymbolName,
                tint: swiftUIColor(for: item.kind.colorHint),
                size: size,
                iconSize: size * 0.46,
                cornerRadius: radius
            )
        }
    }

    func transferStatusColor(for state: RemoteFileTransferState) -> Color {
        switch state {
        case .queued, .inProgress: return .accentColor
        case .completed: return .green
        case .failed: return .red
        case .cancelled: return .secondary
        }
    }

    func swiftUIColor(for hint: RemoteStorageColorHint) -> Color {
        switch hint {
        case .blue: return .blue
        case .purple: return .purple
        case .pink: return .pink
        case .orange: return .orange
        case .green: return .green
        case .teal: return .teal
        case .indigo: return .indigo
        case .gray: return .gray
        }
    }
}
