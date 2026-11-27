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
                    storageSubTabBar(report)

                    switch activeStorageSubTab {
                    case .categories:
                        categoryBreakdownGrid(report)
                    case .folders:
                        folderBreakdownCard(report)
                    case .apps:
                        appStorageLeaderboardCard(report)
                    }

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

    private func storageSubTabBar(_ report: RemoteStorageReport) -> some View {
        HStack(spacing: OpalSpacing.md) {
            Picker("存储维度", selection: $activeStorageSubTab) {
                ForEach(RemoteStorageSubTab.allCases) { tab in
                    Text(storageSubTabTitle(tab, report: report)).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 440)

            Spacer()

            Text(storageSubTabHint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    private func storageSubTabTitle(_ tab: RemoteStorageSubTab, report: RemoteStorageReport) -> String {
        switch tab {
        case .categories:
            return tab.rawValue
        case .folders:
            return report.folders.isEmpty ? tab.rawValue : "\(tab.rawValue) (\(report.folders.count))"
        case .apps:
            return report.apps.isEmpty ? tab.rawValue : "\(tab.rawValue) (\(report.apps.count))"
        }
    }

    private var storageSubTabHint: String {
        switch activeStorageSubTab {
        case .categories: return "按媒体与文档类型统计手机内部存储分布"
        case .folders: return "点击任意文件夹可直接跳转至文件浏览定位"
        case .apps: return "按应用安装包、用户数据与缓存体积排序"
        }
    }

    private func filteredFolders(in report: RemoteStorageReport) -> [FolderStorageEntry] {
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let sorted = report.folders.sorted { $0.bytes > $1.bytes }
        guard !trimmed.isEmpty else { return sorted }
        return sorted.filter {
            $0.name.localizedCaseInsensitiveContains(trimmed)
                || $0.path.localizedCaseInsensitiveContains(trimmed)
        }
    }

    private func filteredApps(in report: RemoteStorageReport) -> [AppStorageEntry] {
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let sorted = report.apps.sorted { $0.totalBytes > $1.totalBytes }
        guard !trimmed.isEmpty else { return sorted }
        return sorted.filter {
            $0.displayLabel.localizedCaseInsensitiveContains(trimmed)
                || $0.pkg.localizedCaseInsensitiveContains(trimmed)
        }
    }

    private func folderBreakdownCard(_ report: RemoteStorageReport) -> some View {
        let folders = filteredFolders(in: report)
        let maxBytes = max(folders.first?.bytes ?? 1, 1)

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(
                title: "文件夹空间占用",
                systemImage: "folder.fill",
                badgeText: "\(folders.count) 个目录",
                badgeColor: .blue
            )

            if folders.isEmpty {
                OpalEmptyStateRow(
                    icon: "folder.badge.questionmark",
                    title: searchQuery.isEmpty ? "暂无文件夹占用统计" : "没有匹配的文件夹",
                    subtitle: searchQuery.isEmpty
                        ? "请点击右上角刷新按钮重新扫描手机目录空间占用。"
                        : "请尝试调整顶部筛选关键字。"
                )
            } else {
                VStack(spacing: OpalSpacing.xs) {
                    ForEach(folders) { folder in
                        folderUsageRow(folder, maxBytes: maxBytes, totalUsedBytes: max(report.usedBytes, 1))
                    }
                }
            }
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
    }

    private func folderUsageRow(
        _ folder: FolderStorageEntry,
        maxBytes: Int64,
        totalUsedBytes: Int64
    ) -> some View {
        let barFraction = min(max(Double(folder.bytes) / Double(maxBytes), 0.02), 1.0)
        let shareText = FileBrowserPresentation.formatPercentage(Double(folder.bytes) / Double(totalUsedBytes))

        return Button {
            activeTab = .files
            actions.onNavigateToPath(folder.path)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: OpalSpacing.sm) {
                    OpalIconBadge(systemName: "folder.fill", tint: .blue, size: 30, iconSize: 14, cornerRadius: 9)

                    VStack(alignment: .leading, spacing: 1) {
                        Text(folder.name.isEmpty ? folder.path : folder.name)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Text(folder.path)
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }

                    Spacer(minLength: OpalSpacing.sm)

                    VStack(alignment: .trailing, spacing: 1) {
                        Text(FileBrowserPresentation.formatByteCount(folder.bytes))
                            .font(.callout.weight(.semibold).monospacedDigit())
                            .foregroundStyle(.primary)
                        Text("\(folder.fileCount) 个文件 · \(shareText)")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }

                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }

                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.primary.opacity(0.08))
                        Capsule()
                            .fill(
                                LinearGradient(
                                    colors: [Color.accentColor, Color.blue.opacity(0.75)],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .frame(width: max(proxy.size.width * barFraction, 6))
                    }
                }
                .frame(height: 6)
            }
            .padding(.horizontal, OpalSpacing.md)
            .padding(.vertical, 9)
            .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
            .opalInteractiveHover(cornerRadius: OpalRadius.tile)
        }
        .buttonStyle(.plain)
        .help("点击打开目录 \(folder.path)")
    }

    private func appStorageLeaderboardCard(_ report: RemoteStorageReport) -> some View {
        let apps = filteredApps(in: report)
        let maxTotal = max(apps.first?.totalBytes ?? 1, 1)

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(
                title: "应用空间排行",
                systemImage: "square.stack.3d.up.fill",
                badgeText: "\(apps.count) 个应用",
                badgeColor: .purple
            )

            if apps.isEmpty {
                OpalEmptyStateRow(
                    icon: "app.dashed",
                    tint: .orange,
                    title: searchQuery.isEmpty ? "当前设备未返回应用空间统计" : "没有匹配的应用",
                    subtitle: searchQuery.isEmpty
                        ? "部分 Android ROM 限制或裁剪了 dumpsys diskstats 输出，因此仅展示目录与文件分类统计。"
                        : "请尝试搜索其他应用名称或包名。"
                )
            } else {
                VStack(spacing: OpalSpacing.xs) {
                    ForEach(Array(apps.enumerated()), id: \.element.id) { index, app in
                        appStorageRow(app, rank: index + 1, maxTotalBytes: maxTotal)
                    }
                }
            }
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
    }

    private func appStorageRow(_ app: AppStorageEntry, rank: Int, maxTotalBytes: Int64) -> some View {
        let totalDouble = Double(max(app.totalBytes, 1))
        let overallFraction = min(max(Double(app.totalBytes) / Double(maxTotalBytes), 0.03), 1.0)
        let apkRatio = Double(app.apkBytes) / totalDouble
        let dataRatio = Double(app.dataBytes) / totalDouble
        let cacheRatio = Double(app.cacheBytes) / totalDouble

        return VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: OpalSpacing.sm) {
                Text("#\(rank)")
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 26, alignment: .leading)

                OpalAppMonogram(label: app.displayLabel, packageKey: app.pkg, size: 32, cornerRadius: 9)

                VStack(alignment: .leading, spacing: 2) {
                    Text(app.displayLabel)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(app.pkg)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer(minLength: OpalSpacing.sm)

                HStack(spacing: 5) {
                    StatusBadgeView(
                        text: "应用 \(FileBrowserPresentation.formatByteCount(app.apkBytes))",
                        color: .blue,
                        horizontalPadding: 6,
                        verticalPadding: 2
                    )
                    StatusBadgeView(
                        text: "数据 \(FileBrowserPresentation.formatByteCount(app.dataBytes))",
                        color: .purple,
                        horizontalPadding: 6,
                        verticalPadding: 2
                    )
                    StatusBadgeView(
                        text: "缓存 \(FileBrowserPresentation.formatByteCount(app.cacheBytes))",
                        color: .orange,
                        horizontalPadding: 6,
                        verticalPadding: 2
                    )
                }

                Text(FileBrowserPresentation.formatByteCount(app.totalBytes))
                    .font(.callout.weight(.bold).monospacedDigit())
                    .frame(minWidth: 74, alignment: .trailing)
            }

            GeometryReader { proxy in
                let barWidth = max(proxy.size.width * overallFraction, 8)
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.08))
                    HStack(spacing: 1) {
                        if app.apkBytes > 0 {
                            Rectangle().fill(Color.blue).frame(width: max(barWidth * apkRatio, 2))
                        }
                        if app.dataBytes > 0 {
                            Rectangle().fill(Color.purple).frame(width: max(barWidth * dataRatio, 2))
                        }
                        if app.cacheBytes > 0 {
                            Rectangle().fill(Color.orange).frame(width: max(barWidth * cacheRatio, 2))
                        }
                    }
                    .frame(width: barWidth, alignment: .leading)
                    .clipShape(Capsule())
                }
            }
            .frame(height: 6)
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 9)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
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
