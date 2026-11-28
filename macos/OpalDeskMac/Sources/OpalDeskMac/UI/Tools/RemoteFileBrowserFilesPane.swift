import AppKit
import OpalDeskCore
import SwiftUI
import UniformTypeIdentifiers

// MARK: - Tab 1: Finder-Style Liquid Glass File Browser Workspace

extension RemoteFileBrowserWindowView {
    var filesWorkspace: some View {
        HStack(spacing: OpalSpacing.md) {
            quickLocationsSidebar
                .frame(width: 204)

            filesDetailPane
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var quickLocationsSidebar: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: OpalSpacing.xxs) {
                Text(OpalL10n.text("files.sidebar.title"))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 10)
                    .padding(.top, 6)
                    .padding(.bottom, 4)

                ForEach(quickLocations) { location in
                    let isSelected = FileBrowserPresentation.normalizePath(currentPath)
                        == FileBrowserPresentation.normalizePath(location.path)
                    sidebarLocationRow(location, isSelected: isSelected)
                }
            }
            .padding(OpalSpacing.sm)
        }
        .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
    }

    private func sidebarLocationRow(_ location: RemoteQuickLocation, isSelected: Bool) -> some View {
        Button {
            actions.onNavigateToPath(location.path)
        } label: {
            HStack(spacing: 9) {
                Image(systemName: location.sfSymbolName)
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                    .frame(width: 18)

                VStack(alignment: .leading, spacing: 1) {
                    Text(location.title)
                        .font(.system(size: 12.5, weight: isSelected ? .semibold : .regular))
                        .foregroundStyle(isSelected ? Color.primary : Color.primary.opacity(0.9))
                        .lineLimit(1)
                    if !location.subtitle.isEmpty {
                        Text(location.subtitle)
                            .font(.system(size: 10.5))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                isSelected ? Color.accentColor.opacity(0.16) : Color.clear,
                in: RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous)
                    .strokeBorder(isSelected ? Color.accentColor.opacity(0.32) : .clear, lineWidth: 0.8)
            )
            .opalInteractiveHover(cornerRadius: OpalRadius.control)
        }
        .buttonStyle(.plain)
        .disabled(!isConnected)
        .help(location.path)
    }

    private var filesDetailPane: some View {
        ZStack {
            VStack(spacing: 0) {
                browserSubheaderBar
                Divider().opacity(0.6)

                if !selectedItems.isEmpty {
                    batchSelectionBar
                    Divider().opacity(0.6)
                }

                fileContentArea
            }

            if isDropTargeted {
                OpalDropZoneOverlay(
                    icon: "arrow.up.doc.fill",
                    title: OpalL10n.text("files.drop.title"),
                    subtitle: currentPath,
                    cornerRadius: OpalRadius.panel,
                    insetPadding: OpalSpacing.md
                )
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: OpalRadius.panel, style: .continuous))
        .opalGlassSurface(.panel, cornerRadius: OpalRadius.panel)
        .onDrop(of: [UTType.fileURL], isTargeted: $isDropTargeted) { providers in
            handleDrop(providers: providers)
        }
    }

    // MARK: - Breadcrumbs & View Mode Controls

    private var browserSubheaderBar: some View {
        let parent = FileBrowserPresentation.parentPath(of: currentPath)
        let crumbs = FileBrowserPresentation.pathBreadcrumbs(for: currentPath)

        return HStack(spacing: OpalSpacing.sm) {
            OpalGlassIconButton(
                systemName: "chevron.up",
                size: 26,
                iconSize: 11.5,
                helpText: OpalL10n.text("files.up_help")
            ) {
                if let parent { actions.onNavigateToPath(parent) }
            }
            .disabled(!isConnected || parent == nil)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(Array(crumbs.enumerated()), id: \.element.id) { index, crumb in
                        if index > 0 {
                            Image(systemName: "chevron.right")
                                .font(.system(size: 9.5, weight: .semibold))
                                .foregroundStyle(.tertiary)
                        }
                        let isLast = index == crumbs.count - 1
                        Button {
                            actions.onNavigateToPath(crumb.path)
                        } label: {
                            Text(crumb.title)
                                .font(.system(size: 12, weight: isLast ? .semibold : .regular))
                                .foregroundStyle(isLast ? .primary : .secondary)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3.5)
                                .background(
                                    isLast ? Color.primary.opacity(0.07) : Color.clear,
                                    in: Capsule()
                                )
                        }
                        .buttonStyle(.plain)
                        .disabled(!isConnected || isLast)
                        .help(crumb.path)
                    }
                }
            }

            Spacer(minLength: OpalSpacing.sm)

            Text(OpalL10n.format("files.item_count_fmt", visibleItems.count))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)

            OpalGlassIconButton(
                systemName: showHiddenFiles ? "eye.fill" : "eye.slash",
                tint: showHiddenFiles ? .accentColor : .secondary,
                isActive: showHiddenFiles,
                size: 26,
                iconSize: 11.5,
                helpText: showHiddenFiles ? OpalL10n.text("files.hidden.hide_help") : OpalL10n.text("files.hidden.show_help")
            ) {
                showHiddenFiles.toggle()
            }

            sortMenu

            OpalSegmentedControl(
                selection: $viewMode,
                options: RemoteFileViewMode.allCases.map { mode in
                    OpalSegmentOption(
                        value: mode,
                        label: "",
                        icon: mode.sfSymbolName,
                        accessibilityLabel: mode.title
                    )
                },
                compact: true
            )
        }
        .padding(.horizontal, OpalSpacing.lg)
        .padding(.vertical, 10)
    }

    private var sortMenu: some View {
        Menu {
            ForEach(RemoteFileSortOption.allCases) { option in
                Button {
                    if sortOption == option {
                        sortAscending.toggle()
                    } else {
                        sortOption = option
                        sortAscending = option.defaultAscending
                    }
                } label: {
                    HStack {
                        Text(option.displayName)
                        if sortOption == option {
                            Image(systemName: sortAscending ? "arrow.up" : "arrow.down")
                        }
                    }
                }
            }
        } label: {
            Label(sortOption.displayName, systemImage: sortAscending ? "arrow.up.arrow.down" : "arrow.down.arrow.up")
                .font(.caption.weight(.medium))
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .help(OpalL10n.text("files.sort.help"))
    }

    private var batchSelectionBar: some View {
        HStack(spacing: OpalSpacing.sm) {
            Text(OpalL10n.format("files.selected_count_fmt", selectedItems.count))
                .font(.caption.weight(.semibold))
            Spacer()
            let downloadable = selectedItems.filter { !$0.isDirectory }
            if downloadable.count == 1, let single = downloadable.first {
                Button {
                    actions.onQuickLookItem(single)
                } label: {
                    if quickLookLoadingPath == single.path {
                        HStack(spacing: 4) {
                            ProgressView().controlSize(.mini)
                            Text(OpalL10n.text("files.quicklook.loading"))
                        }
                    } else {
                        Label(OpalL10n.text("files.quicklook"), systemImage: "eye")
                    }
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                .disabled(quickLookLoadingPath == single.path)
            }
            if !downloadable.isEmpty {
                Button {
                    actions.onDownloadItems(downloadable)
                } label: {
                    Label(OpalL10n.format("files.download_count_fmt", downloadable.count), systemImage: "arrow.down.circle")
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
            }
            Button(role: .destructive) {
                pendingDeleteItems = selectedItems
            } label: {
                Label(OpalL10n.text("files.delete_selected"), systemImage: "trash")
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .destructive, size: .small, isPill: true))

            Button(OpalL10n.text("common.deselect")) { selectedFilePaths.removeAll() }
                .buttonStyle(.borderless)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, OpalSpacing.lg)
        .padding(.vertical, 8)
        .background(Color.accentColor.opacity(0.08))
    }

    // MARK: - Content Area (List / Grid / Empty States)

    @ViewBuilder
    private var fileContentArea: some View {
        if !isConnected {
            ContentUnavailableView(
                OpalL10n.text("files.empty.disconnected_title"),
                systemImage: "iphone.slash",
                description: Text(OpalL10n.text("files.empty.disconnected_desc"))
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let filesError, visibleItems.isEmpty {
            VStack(spacing: OpalSpacing.md) {
                OpalIconBadge(systemName: "exclamationmark.triangle.fill", tint: .orange, size: 46, iconSize: 22, cornerRadius: 14)
                Text(filesError)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 420)
                Button(OpalL10n.text("files.retry_load")) { actions.onRefreshDirectory(currentPath) }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if isLoadingFiles && items.isEmpty {
            VStack(spacing: OpalSpacing.md) {
                ProgressView()
                Text(OpalL10n.text("files.loading_dir"))
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if visibleItems.isEmpty {
            ContentUnavailableView {
                Label(
                    searchQuery.isEmpty ? OpalL10n.text("files.empty.folder_title") : OpalL10n.text("files.empty.search_title"),
                    systemImage: searchQuery.isEmpty ? "folder" : "magnifyingglass"
                )
            } description: {
                Text(searchQuery.isEmpty ? OpalL10n.text("files.empty.folder_desc") : OpalL10n.text("files.empty.search_desc"))
            } actions: {
                if !searchQuery.isEmpty {
                    Button(OpalL10n.text("files.clear_search")) { searchQuery = "" }
                        .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .regular, isPill: true))
                } else {
                    Button(OpalL10n.text("files.upload")) { openUploadFilePanel() }
                        .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            switch viewMode {
            case .list: fileListView
            case .grid: fileGridView
            }
        }
    }

    private var fileListView: some View {
        VStack(spacing: 0) {
            listColumnHeaders
            Divider().opacity(0.5)

            ScrollView {
                LazyVStack(spacing: 3) {
                    ForEach(visibleItems) { item in
                        fileListRow(item)
                            .onAppear { requestThumbnailIfNeeded(for: item) }
                    }
                }
                .padding(.horizontal, OpalSpacing.sm)
                .padding(.vertical, OpalSpacing.sm)
            }
        }
    }

    private var listColumnHeaders: some View {
        HStack(spacing: OpalSpacing.md) {
            sortableColumnHeader(OpalL10n.text("files.col.name"), option: .name)
                .frame(maxWidth: .infinity, alignment: .leading)
            sortableColumnHeader(OpalL10n.text("files.col.size"), option: .size)
                .frame(width: 92, alignment: .trailing)
            sortableColumnHeader(OpalL10n.text("files.col.modified"), option: .modifiedDate)
                .frame(width: 146, alignment: .trailing)
            Text(OpalL10n.text("files.col.actions"))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 138, alignment: .trailing)
        }
        .padding(.horizontal, OpalSpacing.lg)
        .padding(.vertical, 7)
    }

    private func sortableColumnHeader(_ title: String, option: RemoteFileSortOption) -> some View {
        let isActive = sortOption == option
        return Button {
            if isActive {
                sortAscending.toggle()
            } else {
                sortOption = option
                sortAscending = option.defaultAscending
            }
        } label: {
            HStack(spacing: 4) {
                Text(title)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(isActive ? .primary : .secondary)
                if isActive {
                    Image(systemName: sortAscending ? "chevron.up" : "chevron.down")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(Color.accentColor)
                }
            }
        }
        .buttonStyle(.plain)
    }

    private func fileListRow(_ item: RemoteFileItem) -> some View {
        let isSelected = selectedFilePaths.contains(item.path)
        let isPreviewLoading = quickLookLoadingPath == item.path

        return HStack(spacing: OpalSpacing.md) {
            HStack(spacing: OpalSpacing.md) {
                fileIconView(for: item, size: 30)

                HStack(spacing: OpalSpacing.xs) {
                    Text(item.name)
                        .font(.system(size: 13, weight: item.isDirectory ? .medium : .regular))
                        .lineLimit(1)
                        .truncationMode(.middle)

                    if item.isAndroidPackage {
                        StatusBadgeView(text: OpalL10n.text("files.badge.apk"), color: .green, horizontalPadding: 6, verticalPadding: 1.5)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Text(item.formattedSize)
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 92, alignment: .trailing)

                Text(item.formattedModifiedDate)
                    .font(.system(size: 11.5).monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 146, alignment: .trailing)
            }
            .contentShape(Rectangle())
            .overlay {
                RemoteFileDragOutOverlay(
                    item: item,
                    isDragEnabled: isConnected && !item.isDirectory,
                    pullFile: actions.onPullFileForDrag,
                    onSelect: { modifiers in toggleSelection(for: item, modifiers: modifiers) },
                    onActivate: { activateItem(item) }
                )
            }

            HStack(spacing: OpalSpacing.xs) {
                if item.isAndroidPackage {
                    Button {
                        actions.onInstallApk(item)
                    } label: {
                        Text(OpalL10n.text("files.action.install"))
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .emerald, size: .mini, isPill: true))
                    .help(OpalL10n.text("files.action.install_help"))
                }

                if !item.isDirectory {
                    if isPreviewLoading {
                        ProgressView()
                            .controlSize(.mini)
                            .frame(width: 24, height: 24)
                    } else {
                        OpalGlassIconButton(
                            systemName: "eye",
                            tint: .secondary,
                            size: 24,
                            iconSize: 12.5,
                            helpText: OpalL10n.text("files.quicklook")
                        ) { actions.onQuickLookItem(item) }
                    }

                    OpalGlassIconButton(
                        systemName: "arrow.down.circle",
                        tint: .secondary,
                        size: 24,
                        iconSize: 13,
                        helpText: OpalL10n.text("files.action.download_help")
                    ) { actions.onDownloadItems([item]) }
                }

                Menu {
                    itemContextMenu(for: item)
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                }
                .menuStyle(.borderlessButton)
                .frame(width: 24)
                .accessibilityLabel(OpalL10n.format("files.action.more_a11y_fmt", item.name))
            }
            .frame(width: 138, alignment: .trailing)
        }
        .padding(.horizontal, OpalSpacing.sm)
        .padding(.vertical, 6)
        .background(
            isSelected ? Color.accentColor.opacity(0.15) : Color.clear,
            in: RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous)
        )
        .opalInteractiveHover(cornerRadius: OpalRadius.control)
        .contentShape(RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous))
        .contextMenu { itemContextMenu(for: item) }
    }

    private var fileGridView: some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 132, maximum: 156), spacing: OpalSpacing.md)],
                spacing: OpalSpacing.md
            ) {
                ForEach(visibleItems) { item in
                    fileGridCard(item)
                        .onAppear { requestThumbnailIfNeeded(for: item) }
                }
            }
            .padding(OpalSpacing.lg)
        }
    }

    private func fileGridCard(_ item: RemoteFileItem) -> some View {
        let isSelected = selectedFilePaths.contains(item.path)

        return VStack(spacing: OpalSpacing.sm) {
            ZStack(alignment: .topTrailing) {
                fileIconView(for: item, size: 58)
                    .frame(width: 72, height: 64)

                if item.isAndroidPackage {
                    StatusBadgeView(text: "APK", color: .green, horizontalPadding: 5, verticalPadding: 1.5)
                } else if quickLookLoadingPath == item.path {
                    ProgressView().controlSize(.mini)
                }
            }

            VStack(spacing: 2) {
                Text(item.name)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(2)
                    .truncationMode(.middle)
                    .multilineTextAlignment(.center)
                Text(item.isDirectory ? OpalL10n.text("files.grid.folder") : item.formattedSize)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(OpalSpacing.md)
        .frame(maxWidth: .infinity, minHeight: 132)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: isSelected ? .accentColor : nil)
        .opalInteractiveHover(cornerRadius: OpalRadius.tile, tint: isSelected ? .accentColor : nil)
        .contentShape(RoundedRectangle(cornerRadius: OpalRadius.tile, style: .continuous))
        .overlay {
            RemoteFileDragOutOverlay(
                item: item,
                isDragEnabled: isConnected && !item.isDirectory,
                pullFile: actions.onPullFileForDrag,
                onSelect: { modifiers in toggleSelection(for: item, modifiers: modifiers) },
                onActivate: { activateItem(item) }
            )
        }
        .contextMenu { itemContextMenu(for: item) }
    }

    @ViewBuilder
    private func itemContextMenu(for item: RemoteFileItem) -> some View {
        if item.isDirectory {
            Button(OpalL10n.text("files.ctx.open_folder"), systemImage: "folder") { actions.onNavigateToPath(item.path) }
        } else {
            Button(OpalL10n.text("files.ctx.direct_open"), systemImage: "arrow.up.forward.app") { actions.onOpenItem(item) }
            Button(OpalL10n.text("files.quicklook"), systemImage: "eye") { actions.onQuickLookItem(item) }
            if item.isAndroidPackage {
                Button(OpalL10n.text("files.ctx.install_ellipsis"), systemImage: "shippingbox") { actions.onInstallApk(item) }
            }
            Button(OpalL10n.text("files.ctx.download"), systemImage: "arrow.down.circle") { actions.onDownloadItems([item]) }
        }

        Divider()

        Button(OpalL10n.text("files.ctx.rename_ellipsis"), systemImage: "pencil") {
            renameInput = item.name
            renamingItem = item
        }

        Button(OpalL10n.text("files.ctx.copy_path"), systemImage: "doc.on.doc") {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(item.path, forType: .string)
            localNoticeMessage = OpalL10n.format("files.copied_path_fmt", item.path)
        }

        Divider()

        Button(OpalL10n.text("files.ctx.delete_ellipsis"), systemImage: "trash", role: .destructive) {
            pendingDeleteItems = [item]
        }
    }

    // MARK: - Selection & Activation Helpers

    private func activateItem(_ item: RemoteFileItem) {
        if item.isDirectory {
            actions.onNavigateToPath(item.path)
        } else if item.isAndroidPackage {
            actions.onInstallApk(item)
        } else if FileBrowserPresentation.shouldDirectOpen(item, maxSizeMB: directOpenMaxMB) {
            actions.onOpenItem(item)
        } else {
            actions.onDownloadItems([item])
        }
    }

    private func toggleSelection(for item: RemoteFileItem, modifiers: NSEvent.ModifierFlags = NSEvent.modifierFlags) {
        if modifiers.contains(.command) {
            if selectedFilePaths.contains(item.path) {
                selectedFilePaths.remove(item.path)
            } else {
                selectedFilePaths.insert(item.path)
            }
        } else {
            selectedFilePaths = [item.path]
        }
    }

    private func requestThumbnailIfNeeded(for item: RemoteFileItem) {
        guard !item.isDirectory,
              item.thumbnailData == nil,
              item.kind == .image || item.kind == .video else {
            return
        }
        actions.onRequestThumbnail?(item)
    }
}
