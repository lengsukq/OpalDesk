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
                Text("常用目录")
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
                    title: "松开即可上传到当前手机目录",
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
                helpText: "返回上一级目录"
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

            Text("\(visibleItems.count) 项")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)

            OpalGlassIconButton(
                systemName: showHiddenFiles ? "eye.fill" : "eye.slash",
                tint: showHiddenFiles ? .accentColor : .secondary,
                isActive: showHiddenFiles,
                size: 26,
                iconSize: 11.5,
                helpText: showHiddenFiles ? "隐藏以 . 开头的隐藏文件" : "显示隐藏文件"
            ) {
                showHiddenFiles.toggle()
            }

            sortMenu

            Picker("布局", selection: $viewMode) {
                ForEach(RemoteFileViewMode.allCases) { mode in
                    Image(systemName: mode.sfSymbolName)
                        .tag(mode)
                        .accessibilityLabel(mode.rawValue)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 68)
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
        .help("排序方式")
    }

    private var batchSelectionBar: some View {
        HStack(spacing: OpalSpacing.sm) {
            Text("已选中 \(selectedItems.count) 项")
                .font(.caption.weight(.semibold))
            Spacer()
            let downloadable = selectedItems.filter { !$0.isDirectory }
            if !downloadable.isEmpty {
                Button {
                    actions.onDownloadItems(downloadable)
                } label: {
                    Label("下载到 Mac (\(downloadable.count))", systemImage: "arrow.down.circle")
                        .font(.caption.weight(.medium))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            Button(role: .destructive) {
                pendingDeleteItems = selectedItems
            } label: {
                Label("删除所选", systemImage: "trash")
                    .font(.caption.weight(.medium))
            }
            .buttonStyle(.bordered)
            .controlSize(.small)

            Button("取消选择") { selectedFilePaths.removeAll() }
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
                "尚未连接 Android 设备",
                systemImage: "iphone.slash",
                description: Text("连接或唤醒后台伴侣会话后，即可双向浏览手机文件并互传资料。")
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
                Button("重试加载") { actions.onRefreshDirectory(currentPath) }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if isLoadingFiles && items.isEmpty {
            VStack(spacing: OpalSpacing.md) {
                ProgressView()
                Text("正在读取手机目录…")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if visibleItems.isEmpty {
            ContentUnavailableView {
                Label(
                    searchQuery.isEmpty ? "此文件夹为空" : "没有匹配的文件",
                    systemImage: searchQuery.isEmpty ? "folder" : "magnifyingglass"
                )
            } description: {
                Text(searchQuery.isEmpty ? "可直接从 Finder 拖入文件上传到当前目录，或点击右上角「上传文件…」。" : "尝试修改搜索关键字或开启显示隐藏文件。")
            } actions: {
                if !searchQuery.isEmpty {
                    Button("清除搜索") { searchQuery = "" }
                } else {
                    Button("上传文件…") { openUploadFilePanel() }
                        .buttonStyle(.borderedProminent)
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
            sortableColumnHeader("名称", option: .name)
                .frame(maxWidth: .infinity, alignment: .leading)
            sortableColumnHeader("大小", option: .size)
                .frame(width: 92, alignment: .trailing)
            sortableColumnHeader("修改时间", option: .modifiedDate)
                .frame(width: 146, alignment: .trailing)
            Text("操作")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 112, alignment: .trailing)
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

        return HStack(spacing: OpalSpacing.md) {
            fileIconView(for: item, size: 30)

            HStack(spacing: OpalSpacing.xs) {
                Text(item.name)
                    .font(.system(size: 13, weight: item.isDirectory ? .medium : .regular))
                    .lineLimit(1)
                    .truncationMode(.middle)

                if item.isAndroidPackage {
                    StatusBadgeView(text: "安装包", color: .green, horizontalPadding: 6, verticalPadding: 1.5)
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

            HStack(spacing: OpalSpacing.xs) {
                if item.isAndroidPackage {
                    Button {
                        actions.onInstallApk(item)
                    } label: {
                        Text("安装")
                            .font(.caption2.weight(.semibold))
                    }
                    .buttonStyle(.bordered)
                    .tint(.green)
                    .controlSize(.small)
                    .help("解析并安装此 Android 安装包")
                }

                if !item.isDirectory {
                    OpalGlassIconButton(
                        systemName: "arrow.down.circle",
                        tint: .secondary,
                        size: 24,
                        iconSize: 13,
                        helpText: "下载到 Mac"
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
                .accessibilityLabel("更多操作：\(item.name)")
            }
            .frame(width: 112, alignment: .trailing)
        }
        .padding(.horizontal, OpalSpacing.sm)
        .padding(.vertical, 6)
        .background(
            isSelected ? Color.accentColor.opacity(0.15) : Color.clear,
            in: RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous)
        )
        .opalInteractiveHover(cornerRadius: OpalRadius.control)
        .contentShape(RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous))
        .onTapGesture(count: 2) { activateItem(item) }
        .onTapGesture { toggleSelection(for: item) }
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
                }
            }

            VStack(spacing: 2) {
                Text(item.name)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(2)
                    .truncationMode(.middle)
                    .multilineTextAlignment(.center)
                Text(item.isDirectory ? "文件夹" : item.formattedSize)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(OpalSpacing.md)
        .frame(maxWidth: .infinity, minHeight: 132)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: isSelected ? .accentColor : nil)
        .opalInteractiveHover(cornerRadius: OpalRadius.tile, tint: isSelected ? .accentColor : nil)
        .contentShape(RoundedRectangle(cornerRadius: OpalRadius.tile, style: .continuous))
        .onTapGesture(count: 2) { activateItem(item) }
        .onTapGesture { toggleSelection(for: item) }
        .contextMenu { itemContextMenu(for: item) }
    }

    @ViewBuilder
    private func itemContextMenu(for item: RemoteFileItem) -> some View {
        if item.isDirectory {
            Button("打开文件夹", systemImage: "folder") { actions.onNavigateToPath(item.path) }
        } else {
            if item.isAndroidPackage {
                Button("安装到手机…", systemImage: "shippingbox") { actions.onInstallApk(item) }
            }
            Button("下载到 Mac", systemImage: "arrow.down.circle") { actions.onDownloadItems([item]) }
        }

        Divider()

        Button("重命名…", systemImage: "pencil") {
            renameInput = item.name
            renamingItem = item
        }

        Button("复制手机路径", systemImage: "doc.on.doc") {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(item.path, forType: .string)
            localNoticeMessage = "已复制路径：\(item.path)"
        }

        Divider()

        Button("删除…", systemImage: "trash", role: .destructive) {
            pendingDeleteItems = [item]
        }
    }

    // MARK: - Selection & Activation Helpers

    private func activateItem(_ item: RemoteFileItem) {
        if item.isDirectory {
            actions.onNavigateToPath(item.path)
        } else if item.isAndroidPackage {
            actions.onInstallApk(item)
        } else {
            actions.onDownloadItems([item])
        }
    }

    private func toggleSelection(for item: RemoteFileItem) {
        if NSEvent.modifierFlags.contains(.command) {
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
