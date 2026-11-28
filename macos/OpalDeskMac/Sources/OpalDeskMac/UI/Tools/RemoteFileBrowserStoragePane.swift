import OpalDeskCore
import SwiftUI

// MARK: - Tab 2: Liquid Glass Storage Space Analyzer Workspace

extension RemoteFileBrowserWindowView {
    @ViewBuilder
    var storageWorkspace: some View {
        if !isConnected {
            ContentUnavailableView(
                OpalL10n.text("files.empty.disconnected_title"),
                systemImage: "internaldrive",
                description: Text(OpalL10n.text("files.storage.disconnected_desc"))
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
        } else if let storageError, storageReport == nil {
            VStack(spacing: OpalSpacing.md) {
                OpalIconBadge(systemName: "exclamationmark.triangle.fill", tint: .orange, size: 46, iconSize: 22, cornerRadius: 14)
                Text(storageError)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Button(OpalL10n.text("files.storage.rescan")) { actions.onScanStorage() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
        } else if let report = storageReport {
            ScrollView {
                VStack(alignment: .leading, spacing: OpalSpacing.lg) {
                    if isScanningStorage {
                        OpalNoticeBanner(tone: .loading, message: OpalL10n.text("files.storage.updating"))
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
                Text(OpalL10n.text("files.storage.scanning"))
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
        } else {
            ContentUnavailableView {
                Label(OpalL10n.text("files.storage.empty_title"), systemImage: "chart.bar.xaxis")
            } description: {
                Text(OpalL10n.text("files.storage.empty_desc"))
            } actions: {
                Button(OpalL10n.text("files.storage.start_scan")) { actions.onScanStorage() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
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
                    Text(OpalL10n.text("files.storage.overview_title"))
                        .font(.headline)
                    Text(OpalL10n.format("files.storage.used_total_fmt", FileBrowserPresentation.formatByteCount(report.usedBytes), FileBrowserPresentation.formatByteCount(report.totalBytes)))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Spacer()
                StatusBadgeView(
                    text: OpalL10n.format("files.storage.free_fmt", FileBrowserPresentation.formatByteCount(report.freeBytes)),
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
                                .help(OpalL10n.format("files.storage.slice_help_fmt", slice.category.name, FileBrowserPresentation.formatByteCount(slice.category.bytes), slice.percentageText))
                                .accessibilityLabel(OpalL10n.format("files.storage.slice_a11y_fmt", slice.category.name, FileBrowserPresentation.formatByteCount(slice.category.bytes), slice.percentageText))
                        }
                    }
                    if uncategorizedFraction > 0.005 {
                        Rectangle()
                            .fill(Color.secondary.opacity(0.45))
                            .frame(width: max(proxy.size.width * uncategorizedFraction, 4))
                            .help(OpalL10n.format("files.storage.system_help_fmt", FileBrowserPresentation.formatByteCount(report.uncategorizedUsedBytes)))
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
                        Text(OpalL10n.text("files.storage.system_other")).font(.caption.weight(.medium))
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

                    Text(OpalL10n.format("files.storage.file_count_fmt", slice.category.fileCount))
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
            OpalSegmentedControl(
                selection: $activeStorageSubTab,
                options: RemoteStorageSubTab.allCases.map { tab in
                    OpalSegmentOption(value: tab, label: storageSubTabTitle(tab, report: report))
                },
                compact: true
            )

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
            return tab.title
        case .folders:
            return report.folders.isEmpty ? tab.title : "\(tab.title) (\(report.folders.count))"
        case .apps:
            return report.apps.isEmpty ? tab.title : "\(tab.title) (\(report.apps.count))"
        }
    }

    private var storageSubTabHint: String {
        switch activeStorageSubTab {
        case .categories: return OpalL10n.text("files.storage.hint.categories")
        case .folders: return OpalL10n.text("files.storage.hint.folders")
        case .apps: return OpalL10n.text("files.storage.hint.apps")
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
                title: OpalL10n.text("files.storage.folders_title"),
                systemImage: "folder.fill",
                badgeText: OpalL10n.format("files.storage.folder_count_fmt", folders.count),
                badgeColor: .blue
            )

            if folders.isEmpty {
                OpalEmptyStateRow(
                    icon: "folder.badge.questionmark",
                    title: searchQuery.isEmpty ? OpalL10n.text("files.storage.folders_empty_title") : OpalL10n.text("files.storage.folders_search_empty"),
                    subtitle: searchQuery.isEmpty
                        ? OpalL10n.text("files.storage.folders_empty_sub")
                        : OpalL10n.text("files.storage.search_hint")
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
                        Text("\(OpalL10n.format("files.storage.file_count_fmt", folder.fileCount)) · \(shareText)")
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
        .help(OpalL10n.format("files.storage.folder_open_help_fmt", folder.path))
    }

    private func appStorageLeaderboardCard(_ report: RemoteStorageReport) -> some View {
        let apps = filteredApps(in: report)
        let maxTotal = max(apps.first?.totalBytes ?? 1, 1)

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(
                title: OpalL10n.text("files.storage.apps_title"),
                systemImage: "square.stack.3d.up.fill",
                badgeText: OpalL10n.format("files.storage.app_count_fmt", apps.count),
                badgeColor: .purple
            )

            if apps.isEmpty {
                OpalEmptyStateRow(
                    icon: "app.dashed",
                    tint: .orange,
                    title: searchQuery.isEmpty ? OpalL10n.text("files.storage.apps_empty_title") : OpalL10n.text("files.storage.apps_search_empty"),
                    subtitle: searchQuery.isEmpty
                        ? OpalL10n.text("files.storage.apps_empty_sub")
                        : OpalL10n.text("files.storage.apps_search_sub")
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
        let overallFraction = min(max(Double(app.totalBytes) / Double(maxTotalBytes), 0.03), 1.0)

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

                appStorageBreakdownBadges(app)

                Text(FileBrowserPresentation.formatByteCount(app.totalBytes))
                    .font(.callout.weight(.bold).monospacedDigit())
                    .frame(minWidth: 74, alignment: .trailing)

                if let onOpenAppPopout = actions.onOpenAppPopout {
                    OpalGlassIconButton(
                        systemName: "macwindow.on.rectangle",
                        tint: .accentColor,
                        size: 26,
                        iconSize: 12,
                        helpText: OpalL10n.format("files.storage.app_popout_help_fmt", app.displayLabel)
                    ) {
                        onOpenAppPopout(app)
                    }
                }
            }

            appStorageStackedBar(app, overallFraction: overallFraction)
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 9)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
        .opalInteractiveHover(cornerRadius: OpalRadius.tile)
    }

    private func appStorageBreakdownBadges(_ app: AppStorageEntry) -> some View {
        HStack(spacing: 5) {
            StatusBadgeView(
                text: OpalL10n.format("files.storage.badge_app_fmt", FileBrowserPresentation.formatByteCount(app.apkBytes)),
                color: .blue,
                horizontalPadding: 6,
                verticalPadding: 2
            )
            StatusBadgeView(
                text: OpalL10n.format("files.storage.badge_data_fmt", FileBrowserPresentation.formatByteCount(app.dataBytes)),
                color: .purple,
                horizontalPadding: 6,
                verticalPadding: 2
            )
            StatusBadgeView(
                text: OpalL10n.format("files.storage.badge_cache_fmt", FileBrowserPresentation.formatByteCount(app.cacheBytes)),
                color: .orange,
                horizontalPadding: 6,
                verticalPadding: 2
            )
        }
    }

    private func appStorageStackedBar(_ app: AppStorageEntry, overallFraction: Double) -> some View {
        let totalDouble = Double(max(app.totalBytes, 1))
        let apkRatio = Double(app.apkBytes) / totalDouble
        let dataRatio = Double(app.dataBytes) / totalDouble
        let cacheRatio = Double(app.cacheBytes) / totalDouble

        return GeometryReader { proxy in
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

    private var largeFilesLeaderboardCard: some View {
        let selectedBytes = selectedLargeFiles.reduce(Int64(0)) { $0 + max($1.sizeBytes, 0) }

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            HStack(spacing: OpalSpacing.sm) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(OpalL10n.format("files.storage.large_title_fmt", visibleLargeFiles.count))
                        .font(.headline)
                    if !selectedLargeFiles.isEmpty {
                        Text(OpalL10n.format("files.storage.large_selected_fmt", selectedLargeFiles.count, FileBrowserPresentation.formatByteCount(selectedBytes)))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.orange)
                    } else {
                        Text(OpalL10n.text("files.storage.large_hint"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()

                if !visibleLargeFiles.isEmpty {
                    let allSelected = selectedLargeFiles.count == visibleLargeFiles.count
                    Button(allSelected ? OpalL10n.text("files.storage.deselect_all") : OpalL10n.text("common.select_all")) {
                        if allSelected {
                            selectedLargeFilePaths.removeAll()
                        } else {
                            selectedLargeFilePaths = Set(visibleLargeFiles.map(\.path))
                        }
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                }

                if !selectedLargeFiles.isEmpty {
                    Button {
                        actions.onDownloadItems(selectedLargeFiles)
                    } label: {
                        Label(OpalL10n.text("files.storage.download_backup"), systemImage: "arrow.down.circle")
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))

                    Button(role: .destructive) {
                        pendingDeleteItems = selectedLargeFiles
                    } label: {
                        Label(OpalL10n.format("files.storage.batch_delete_fmt", selectedLargeFiles.count), systemImage: "trash")
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .destructive, size: .small, isPill: true))
                }
            }

            if visibleLargeFiles.isEmpty {
                Text(OpalL10n.text("files.storage.large_empty"))
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
                .accessibilityLabel(OpalL10n.format("files.storage.select_a11y_fmt", file.name))

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
                helpText: OpalL10n.text("files.storage.download_backup_help")
            ) { actions.onDownloadItems([file]) }

            OpalGlassIconButton(
                systemName: "trash",
                tint: .red,
                size: 26,
                iconSize: 12.5,
                helpText: OpalL10n.text("files.storage.delete_file_help")
            ) { pendingDeleteItems = [file] }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 8)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: isChecked ? .accentColor : nil)
    }
}
