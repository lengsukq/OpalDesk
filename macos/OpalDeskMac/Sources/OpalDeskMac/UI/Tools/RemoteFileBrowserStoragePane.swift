import OpalDeskCore
import SwiftUI

// MARK: - Tab 2: Liquid Glass Storage Space Analyzer Workspace

extension RemoteFileBrowserWindowView {
    @ViewBuilder
    var storageWorkspace: some View {
        if !isConnected {
            ContentUnavailableView(
                "尚未连接 Android 设备",
                systemImage: "internaldrive",
                description: Text("连接手机后可分析各文件类别的空间占用，并按体积清理大文件。")
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
        } else if let storageError, storageReport == nil {
            VStack(spacing: OpalSpacing.md) {
                OpalIconBadge(systemName: "exclamationmark.triangle.fill", tint: .orange, size: 46, iconSize: 22, cornerRadius: 14)
                Text(storageError)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Button("重新扫描") { actions.onScanStorage() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
        } else if let report = storageReport {
            ScrollView {
                VStack(alignment: .leading, spacing: OpalSpacing.lg) {
                    if isScanningStorage {
                        OpalNoticeBanner(tone: .loading, message: "正在更新存储空间分析…")
                    }

                    storageSummaryCard(report)
                    categoryBreakdownGrid(report)
                    largeFilesLeaderboardCard
                }
                .padding(.vertical, 2)
            }
        } else if isScanningStorage {
            VStack(spacing: OpalSpacing.md) {
                ProgressView()
                Text("正在扫描手机存储空间与大文件…")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
        } else {
            ContentUnavailableView {
                Label("分析手机存储空间", systemImage: "chart.bar.xaxis")
            } description: {
                Text("统计图片、视频、音频、文档、安装包体积分布，并找出占用空间的大文件。")
            } actions: {
                Button("开始扫描") { actions.onScanStorage() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
        }
    }

    private func storageSummaryCard(_ report: RemoteStorageReport) -> some View {
        let slices = FileBrowserPresentation.categoryBreakdownPercentages(for: report)
        let uncategorizedFraction = report.totalBytes > 0
            ? min(max(Double(report.uncategorizedUsedBytes) / Double(report.totalBytes), 0), 1)
            : 0

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("设备存储概览")
                        .font(.headline)
                    Text("已用 \(FileBrowserPresentation.formatByteCount(report.usedBytes)) / 总计 \(FileBrowserPresentation.formatByteCount(report.totalBytes))")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Spacer()
                StatusBadgeView(
                    text: "可用 \(FileBrowserPresentation.formatByteCount(report.freeBytes))",
                    color: .green,
                    font: .caption.weight(.semibold).monospacedDigit()
                )
            }

            GeometryReader { proxy in
                HStack(spacing: 2) {
                    ForEach(slices) { slice in
                        let width = max(proxy.size.width * slice.fractionOfTotal, slice.category.bytes > 0 ? 4 : 0)
                        if width > 0 {
                            Rectangle()
                                .fill(swiftUIColor(for: slice.category.colorHint))
                                .frame(width: width)
                                .help("\(slice.category.name)：\(FileBrowserPresentation.formatByteCount(slice.category.bytes))（\(slice.percentageText)）")
                                .accessibilityLabel("\(slice.category.name) 占用 \(FileBrowserPresentation.formatByteCount(slice.category.bytes))，占比 \(slice.percentageText)")
                        }
                    }
                    if uncategorizedFraction > 0.005 {
                        Rectangle()
                            .fill(Color.secondary.opacity(0.45))
                            .frame(width: max(proxy.size.width * uncategorizedFraction, 4))
                            .help("系统与应用数据：\(FileBrowserPresentation.formatByteCount(report.uncategorizedUsedBytes))")
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.primary.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: OpalRadius.thumbnail, style: .continuous))
            }
            .frame(height: 24)

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 155), spacing: OpalSpacing.sm)], alignment: .leading, spacing: OpalSpacing.sm) {
                ForEach(slices) { slice in
                    HStack(spacing: 7) {
                        Circle()
                            .fill(swiftUIColor(for: slice.category.colorHint))
                            .frame(width: 9, height: 9)
                        Text(slice.category.name)
                            .font(.caption.weight(.medium))
                        Text("\(slice.percentageText) · \(FileBrowserPresentation.formatByteCount(slice.category.bytes))")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                if report.uncategorizedUsedBytes > 0 {
                    HStack(spacing: 7) {
                        Circle().fill(Color.secondary.opacity(0.45)).frame(width: 9, height: 9)
                        Text("系统及其他").font(.caption.weight(.medium))
                        Text(FileBrowserPresentation.formatByteCount(report.uncategorizedUsedBytes))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
    }

    private func categoryBreakdownGrid(_ report: RemoteStorageReport) -> some View {
        let slices = FileBrowserPresentation.categoryBreakdownPercentages(for: report)
        return LazyVGrid(
            columns: [GridItem(.adaptive(minimum: 190, maximum: 265), spacing: OpalSpacing.md)],
            spacing: OpalSpacing.md
        ) {
            ForEach(slices) { slice in
                let tint = swiftUIColor(for: slice.category.colorHint)
                VStack(alignment: .leading, spacing: OpalSpacing.sm) {
                    HStack {
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 4, style: .continuous)
                                .fill(tint)
                                .frame(width: 10, height: 10)
                            Text(slice.category.name)
                                .font(.subheadline.weight(.semibold))
                        }
                        Spacer()
                        Text(slice.percentageText)
                            .font(.caption.weight(.semibold).monospacedDigit())
                            .foregroundStyle(.secondary)
                    }

                    Text(FileBrowserPresentation.formatByteCount(slice.category.bytes))
                        .font(.title3.weight(.bold).monospacedDigit())

                    ProgressView(value: min(max(slice.fractionOfUsed, 0), 1))
                        .tint(tint)

                    Text("\(slice.category.fileCount) 个文件")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .padding(OpalSpacing.lg)
                .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
            }
        }
    }

    private var largeFilesLeaderboardCard: some View {
        let selectedBytes = selectedLargeFiles.reduce(Int64(0)) { $0 + max($1.sizeBytes, 0) }

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            HStack(spacing: OpalSpacing.sm) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("大文件排行榜 (\(visibleLargeFiles.count))")
                        .font(.headline)
                    if !selectedLargeFiles.isEmpty {
                        Text("已勾选 \(selectedLargeFiles.count) 项 · 可释放 \(FileBrowserPresentation.formatByteCount(selectedBytes))")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.orange)
                    } else {
                        Text("勾选不需要的大文件后可批量备份到 Mac 或删除释放空间")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()

                if !visibleLargeFiles.isEmpty {
                    let allSelected = selectedLargeFiles.count == visibleLargeFiles.count
                    Button(allSelected ? "取消全选" : "全选") {
                        if allSelected {
                            selectedLargeFilePaths.removeAll()
                        } else {
                            selectedLargeFilePaths = Set(visibleLargeFiles.map(\.path))
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }

                if !selectedLargeFiles.isEmpty {
                    Button {
                        actions.onDownloadItems(selectedLargeFiles)
                    } label: {
                        Label("下载备份", systemImage: "arrow.down.circle")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)

                    Button(role: .destructive) {
                        pendingDeleteItems = selectedLargeFiles
                    } label: {
                        Label("批量删除 (\(selectedLargeFiles.count))", systemImage: "trash")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .controlSize(.small)
                }
            }

            if visibleLargeFiles.isEmpty {
                Text("未发现匹配的大文件记录。")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, OpalSpacing.md)
            } else {
                VStack(spacing: OpalSpacing.xs) {
                    ForEach(Array(visibleLargeFiles.enumerated()), id: \.element.id) { index, file in
                        largeFileRow(file, rank: index + 1)
                    }
                }
            }
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
    }

    private func largeFileRow(_ file: RemoteFileItem, rank: Int) -> some View {
        let isChecked = selectedLargeFilePaths.contains(file.path)

        return HStack(spacing: OpalSpacing.sm) {
            Toggle(isOn: Binding(
                get: { isChecked },
                set: { checked in
                    if checked { selectedLargeFilePaths.insert(file.path) }
                    else { selectedLargeFilePaths.remove(file.path) }
                }
            )) { EmptyView() }
                .toggleStyle(.checkbox)
                .labelsHidden()
                .accessibilityLabel("选择 \(file.name)")

            Text("#\(rank)")
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 26, alignment: .leading)

            fileIconView(for: file, size: 30)

            VStack(alignment: .leading, spacing: 2) {
                Text(file.name)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(file.path)
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer(minLength: OpalSpacing.sm)

            StatusBadgeView(
                text: file.formattedSize,
                color: .secondary,
                font: .caption.weight(.semibold).monospacedDigit(),
                backgroundOpacity: 0.10
            )

            OpalGlassIconButton(
                systemName: "arrow.down.circle",
                tint: .secondary,
                size: 26,
                iconSize: 13,
                helpText: "下载备份到 Mac"
            ) { actions.onDownloadItems([file]) }

            OpalGlassIconButton(
                systemName: "trash",
                tint: .red,
                size: 26,
                iconSize: 12.5,
                helpText: "删除此文件"
            ) { pendingDeleteItems = [file] }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 8)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: isChecked ? .accentColor : nil)
    }
}
