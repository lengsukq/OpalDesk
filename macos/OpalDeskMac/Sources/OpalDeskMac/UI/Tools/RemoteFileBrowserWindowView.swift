import AppKit
import OpalDeskCore
import SwiftUI
import UniformTypeIdentifiers
import os

// MARK: - View Modes & Injected Action Callbacks

public enum RemoteFileBrowserTab: String, CaseIterable, Identifiable, Sendable {
    case files = "文件浏览"
    case storage = "存储分析"

    public var id: String { rawValue }
}

public enum RemoteFileViewMode: String, CaseIterable, Identifiable, Sendable {
    case list = "列表"
    case grid = "网格"

    public var id: String { rawValue }

    var sfSymbolName: String {
        switch self {
        case .list: return "list.bullet"
        case .grid: return "square.grid.2x2"
        }
    }
}

/// Decoupled action callbacks allowing `RemoteFileBrowserWindowView` to compile and preview
/// independently of `AppModel` and `RemoteFileBrowserClient`.
public struct RemoteFileBrowserActions {
    public var onNavigateToPath: @MainActor (String) -> Void
    public var onRefreshDirectory: @MainActor (String) -> Void
    public var onCreateFolder: @MainActor (_ parentPath: String, _ name: String) -> Void
    public var onUploadFiles: @MainActor (_ localURLs: [URL], _ remoteDirectory: String) -> Void
    public var onDownloadItems: @MainActor ([RemoteFileItem]) -> Void
    public var onRenameItem: @MainActor (_ item: RemoteFileItem, _ newName: String) -> Void
    public var onDeleteItems: @MainActor ([RemoteFileItem]) -> Void
    public var onInstallApk: @MainActor (RemoteFileItem) -> Void
    public var onRequestThumbnail: (@MainActor (RemoteFileItem) -> Void)?
    public var onScanStorage: @MainActor () -> Void
    public var onCancelTransfer: @MainActor (String) -> Void
    public var onDismissTransfer: @MainActor (String) -> Void
    public var onClearCompletedTransfers: @MainActor () -> Void

    public init(
        onNavigateToPath: @escaping @MainActor (String) -> Void = { _ in },
        onRefreshDirectory: @escaping @MainActor (String) -> Void = { _ in },
        onCreateFolder: @escaping @MainActor (_ parentPath: String, _ name: String) -> Void = { _, _ in },
        onUploadFiles: @escaping @MainActor (_ localURLs: [URL], _ remoteDirectory: String) -> Void = { _, _ in },
        onDownloadItems: @escaping @MainActor ([RemoteFileItem]) -> Void = { _ in },
        onRenameItem: @escaping @MainActor (_ item: RemoteFileItem, _ newName: String) -> Void = { _, _ in },
        onDeleteItems: @escaping @MainActor ([RemoteFileItem]) -> Void = { _ in },
        onInstallApk: @escaping @MainActor (RemoteFileItem) -> Void = { _ in },
        onRequestThumbnail: (@MainActor (RemoteFileItem) -> Void)? = nil,
        onScanStorage: @escaping @MainActor () -> Void = {},
        onCancelTransfer: @escaping @MainActor (String) -> Void = { _ in },
        onDismissTransfer: @escaping @MainActor (String) -> Void = { _ in },
        onClearCompletedTransfers: @escaping @MainActor () -> Void = {}
    ) {
        self.onNavigateToPath = onNavigateToPath
        self.onRefreshDirectory = onRefreshDirectory
        self.onCreateFolder = onCreateFolder
        self.onUploadFiles = onUploadFiles
        self.onDownloadItems = onDownloadItems
        self.onRenameItem = onRenameItem
        self.onDeleteItems = onDeleteItems
        self.onInstallApk = onInstallApk
        self.onRequestThumbnail = onRequestThumbnail
        self.onScanStorage = onScanStorage
        self.onCancelTransfer = onCancelTransfer
        self.onDismissTransfer = onDismissTransfer
        self.onClearCompletedTransfers = onClearCompletedTransfers
    }
}

// MARK: - Main Window View

/// Native macOS dual-tab remote file manager & storage analyzer for paired Android devices.
public struct RemoteFileBrowserWindowView: View {
    public let deviceName: String
    public let isConnected: Bool
    public let currentPath: String
    public let items: [RemoteFileItem]
    public let isLoadingFiles: Bool
    public let filesError: String?
    public let storageReport: RemoteStorageReport?
    public let isScanningStorage: Bool
    public let storageError: String?
    public let transfers: [RemoteFileTransferItem]
    public let quickLocations: [RemoteQuickLocation]
    public let actions: RemoteFileBrowserActions

    @State private var activeTab: RemoteFileBrowserTab
    @State private var searchQuery: String = ""
    @State private var sortOption: RemoteFileSortOption = .name
    @State private var sortAscending: Bool = true
    @State private var showHiddenFiles: Bool = false
    @State private var viewMode: RemoteFileViewMode = .list
    @State private var selectedFilePaths: Set<String> = []
    @State private var selectedLargeFilePaths: Set<String> = []
    @State private var isDropTargeted: Bool = false
    @State private var isTransferDrawerExpanded: Bool = true
    @State private var isNewFolderPresented: Bool = false
    @State private var newFolderName: String = ""
    @State private var renamingItem: RemoteFileItem?
    @State private var renameInput: String = ""
    @State private var pendingDeleteItems: [RemoteFileItem] = []
    @State private var localNoticeMessage: String?

    public init(
        deviceName: String,
        isConnected: Bool,
        currentPath: String,
        items: [RemoteFileItem],
        isLoadingFiles: Bool = false,
        filesError: String? = nil,
        storageReport: RemoteStorageReport? = nil,
        isScanningStorage: Bool = false,
        storageError: String? = nil,
        transfers: [RemoteFileTransferItem] = [],
        quickLocations: [RemoteQuickLocation] = RemoteQuickLocation.defaultLocations,
        initialTab: RemoteFileBrowserTab = .files,
        actions: RemoteFileBrowserActions = RemoteFileBrowserActions()
    ) {
        self.deviceName = deviceName
        self.isConnected = isConnected
        self.currentPath = currentPath
        self.items = items
        self.isLoadingFiles = isLoadingFiles
        self.filesError = filesError
        self.storageReport = storageReport
        self.isScanningStorage = isScanningStorage
        self.storageError = storageError
        self.transfers = transfers
        self.quickLocations = quickLocations
        self.actions = actions
        _activeTab = State(initialValue: initialTab)
    }

    private var visibleItems: [RemoteFileItem] {
        FileBrowserPresentation.filteredAndSorted(
            items,
            query: searchQuery,
            sortBy: sortOption,
            ascending: sortAscending,
            showHidden: showHiddenFiles
        )
    }

    private var selectedItems: [RemoteFileItem] {
        visibleItems.filter { selectedFilePaths.contains($0.path) }
    }

    private var visibleLargeFiles: [RemoteFileItem] {
        guard let report = storageReport else { return [] }
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return report.largeFiles }
        return report.largeFiles.filter {
            $0.name.localizedCaseInsensitiveContains(trimmed)
                || $0.path.localizedCaseInsensitiveContains(trimmed)
        }
    }

    private var selectedLargeFiles: [RemoteFileItem] {
        visibleLargeFiles.filter { selectedLargeFilePaths.contains($0.path) }
    }

    public var body: some View {
        VStack(spacing: 0) {
            topToolbar
            Divider()

            if let notice = localNoticeMessage {
                inlineNoticeBanner(notice)
                Divider()
            }

            Group {
                switch activeTab {
                case .files:
                    filesWorkspace
                case .storage:
                    storageWorkspace
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if !transfers.isEmpty {
                Divider()
                transferQueueStrip
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .frame(minWidth: 820, minHeight: 540)
        .onChange(of: currentPath) { _, _ in
            selectedFilePaths.removeAll()
        }
        .onChange(of: activeTab) { _, nextTab in
            if nextTab == .storage && storageReport == nil && !isScanningStorage && isConnected {
                actions.onScanStorage()
            }
        }
        .sheet(isPresented: $isNewFolderPresented) {
            newFolderSheet
        }
        .sheet(item: $renamingItem) { item in
            renameSheet(for: item)
        }
        .confirmationDialog(
            deleteDialogTitle,
            isPresented: Binding(
                get: { !pendingDeleteItems.isEmpty },
                set: { if !$0 { pendingDeleteItems = [] } }
            ),
            titleVisibility: .visible
        ) {
            Button("永久删除", role: .destructive) {
                let targets = pendingDeleteItems
                pendingDeleteItems = []
                selectedFilePaths.subtract(targets.map(\.path))
                selectedLargeFilePaths.subtract(targets.map(\.path))
                actions.onDeleteItems(targets)
            }
            Button("取消", role: .cancel) {
                pendingDeleteItems = []
            }
        } message: {
            Text(deleteDialogMessage)
        }
    }

    // MARK: - Top Toolbar

    private var topToolbar: some View {
        HStack(spacing: 12) {
            HStack(spacing: 9) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Color.accentColor.opacity(0.14))
                        .frame(width: 30, height: 30)
                    Image(systemName: "iphone.gen3")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                }

                VStack(alignment: .leading, spacing: 1) {
                    Text(deviceName)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                    HStack(spacing: 5) {
                        Circle()
                            .fill(isConnected ? Color.green : Color.orange)
                            .frame(width: 6, height: 6)
                        Text(isConnected ? "已连接" : "未连接")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer(minLength: 8)

            Picker("视图模式", selection: $activeTab) {
                ForEach(RemoteFileBrowserTab.allCases) { tab in
                    Text(tab.rawValue).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 196)

            Spacer(minLength: 8)

            searchField

            toolbarActionButtons
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.regularMaterial)
    }

    private var searchField: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            TextField(activeTab == .files ? "筛选当前目录…" : "筛选大文件…", text: $searchQuery)
                .textFieldStyle(.plain)
                .font(.system(size: 12.5))
            if !searchQuery.isEmpty {
                Button {
                    searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清除搜索关键字")
            }
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .frame(width: 210)
        .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.8)
        )
    }

    private var toolbarActionButtons: some View {
        HStack(spacing: 8) {
            Button {
                if activeTab == .files {
                    actions.onRefreshDirectory(currentPath)
                } else {
                    actions.onScanStorage()
                }
            } label: {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 12.5, weight: .medium))
                    .frame(width: 24, height: 20)
            }
            .buttonStyle(.bordered)
            .controlSize(.regular)
            .disabled(!isConnected || (activeTab == .files ? isLoadingFiles : isScanningStorage))
            .keyboardShortcut("r", modifiers: [.command])
            .help(activeTab == .files ? "刷新目录 (⌘R)" : "重新扫描存储空间 (⌘R)")
            .accessibilityLabel(activeTab == .files ? "刷新当前目录" : "重新扫描存储空间")

            if activeTab == .files {
                Button {
                    newFolderName = ""
                    isNewFolderPresented = true
                } label: {
                    Label("新建文件夹", systemImage: "folder.badge.plus")
                        .font(.system(size: 12, weight: .medium))
                }
                .buttonStyle(.bordered)
                .disabled(!isConnected)
                .keyboardShortcut("n", modifiers: [.command, .shift])
                .help("在当前目录新建文件夹 (⌘⇧N)")

                Button {
                    openUploadFilePanel()
                } label: {
                    Label("上传文件…", systemImage: "arrow.up.doc")
                        .font(.system(size: 12, weight: .semibold))
                }
                .buttonStyle(.borderedProminent)
                .disabled(!isConnected)
                .help("选择 Mac 本地文件上传到当前目录")
            }
        }
    }

    // MARK: - Tab 1: File Browser Workspace

    private var filesWorkspace: some View {
        HSplitView {
            quickLocationsSidebar
                .frame(minWidth: 176, idealWidth: 196, maxWidth: 230)

            filesDetailPane
                .frame(minWidth: 540, maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var quickLocationsSidebar: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                Text("常用目录")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 10)
                    .padding(.top, 10)
                    .padding(.bottom, 4)

                ForEach(quickLocations) { location in
                    let isSelected = FileBrowserPresentation.normalizePath(currentPath) == FileBrowserPresentation.normalizePath(location.path)
                    Button {
                        actions.onNavigateToPath(location.path)
                    } label: {
                        HStack(spacing: 9) {
                            Image(systemName: location.sfSymbolName)
                                .font(.system(size: 13.5, weight: .medium))
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
                        .padding(.vertical, 7)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            isSelected ? Color.accentColor.opacity(0.14) : Color.clear,
                            in: RoundedRectangle(cornerRadius: 7, style: .continuous)
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(!isConnected)
                    .help(location.path)
                }
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 12)
        }
        .background(.ultraThinMaterial)
    }

    private var filesDetailPane: some View {
        ZStack {
            VStack(spacing: 0) {
                browserSubheaderBar
                Divider()

                if !selectedItems.isEmpty {
                    batchSelectionBar
                    Divider()
                }

                fileContentArea
            }

            if isDropTargeted {
                dropTargetOverlay
            }
        }
        .onDrop(of: [UTType.fileURL], isTargeted: $isDropTargeted) { providers in
            handleDrop(providers: providers)
        }
    }

    private var browserSubheaderBar: some View {
        let parent = FileBrowserPresentation.parentPath(of: currentPath)
        let crumbs = FileBrowserPresentation.pathBreadcrumbs(for: currentPath)

        return HStack(spacing: 8) {
            Button {
                if let parent {
                    actions.onNavigateToPath(parent)
                }
            } label: {
                Image(systemName: "chevron.up")
                    .font(.system(size: 11.5, weight: .semibold))
                    .frame(width: 22, height: 22)
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .disabled(!isConnected || parent == nil)
            .help("返回上一级目录")
            .accessibilityLabel("返回上一级目录")

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
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(
                                    isLast ? Color.primary.opacity(0.06) : Color.clear,
                                    in: RoundedRectangle(cornerRadius: 5, style: .continuous)
                                )
                        }
                        .buttonStyle(.plain)
                        .disabled(!isConnected || isLast)
                        .help(crumb.path)
                    }
                }
            }

            Spacer(minLength: 8)

            Text("\(visibleItems.count) 项")
                .font(.system(size: 11).monospacedDigit())
                .foregroundStyle(.secondary)

            Button {
                showHiddenFiles.toggle()
            } label: {
                Image(systemName: showHiddenFiles ? "eye.fill" : "eye.slash")
                    .font(.system(size: 11.5))
                    .frame(width: 22, height: 20)
            }
            .buttonStyle(.bordered)
            .tint(showHiddenFiles ? .accentColor : nil)
            .controlSize(.small)
            .help(showHiddenFiles ? "隐藏以 . 开头的隐藏文件" : "显示隐藏文件")
            .accessibilityLabel(showHiddenFiles ? "隐藏以点开头的隐藏文件" : "显示隐藏文件")

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
            .frame(width: 66)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
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
                .font(.system(size: 11.5))
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .help("排序方式")
    }

    private var batchSelectionBar: some View {
        HStack(spacing: 10) {
            Text("已选中 \(selectedItems.count) 项")
                .font(.system(size: 12, weight: .semibold))
            Spacer()
            let downloadable = selectedItems.filter { !$0.isDirectory }
            if !downloadable.isEmpty {
                Button {
                    actions.onDownloadItems(downloadable)
                } label: {
                    Label("下载到 Mac (\(downloadable.count))", systemImage: "arrow.down.circle")
                        .font(.system(size: 11.5, weight: .medium))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            Button(role: .destructive) {
                pendingDeleteItems = selectedItems
            } label: {
                Label("删除所选", systemImage: "trash")
                    .font(.system(size: 11.5, weight: .medium))
            }
            .buttonStyle(.bordered)
            .controlSize(.small)

            Button("取消选择") {
                selectedFilePaths.removeAll()
            }
            .buttonStyle(.borderless)
            .font(.system(size: 11.5))
            .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(Color.accentColor.opacity(0.08))
    }

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
            VStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(.orange)
                Text(filesError)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 420)
                Button("重试加载") {
                    actions.onRefreshDirectory(currentPath)
                }
                .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if isLoadingFiles && items.isEmpty {
            VStack(spacing: 10) {
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
            case .list:
                fileListView
            case .grid:
                fileGridView
            }
        }
    }

    private var fileListView: some View {
        VStack(spacing: 0) {
            listColumnHeaders
            Divider()

            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(visibleItems.enumerated()), id: \.element.id) { index, item in
                        fileListRow(item, isAlternate: index % 2 == 1)
                            .onAppear {
                                requestThumbnailIfNeeded(for: item)
                            }
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private var listColumnHeaders: some View {
        HStack(spacing: 12) {
            sortableColumnHeader("名称", option: .name)
                .frame(maxWidth: .infinity, alignment: .leading)
            sortableColumnHeader("大小", option: .size)
                .frame(width: 92, alignment: .trailing)
            sortableColumnHeader("修改时间", option: .modifiedDate)
                .frame(width: 146, alignment: .trailing)
            Text("操作")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 112, alignment: .trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(Color(nsColor: .controlBackgroundColor).opacity(0.5))
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
                    .font(.system(size: 11, weight: .semibold))
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

    private func fileListRow(_ item: RemoteFileItem, isAlternate: Bool) -> some View {
        let isSelected = selectedFilePaths.contains(item.path)

        return HStack(spacing: 12) {
            fileIconView(for: item, size: 28)

            HStack(spacing: 6) {
                Text(item.name)
                    .font(.system(size: 13, weight: item.isDirectory ? .medium : .regular))
                    .lineLimit(1)
                    .truncationMode(.middle)

                if item.isAndroidPackage {
                    Text("安装包")
                        .font(.system(size: 10, weight: .semibold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.green.opacity(0.15), in: Capsule())
                        .foregroundStyle(.green)
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

            HStack(spacing: 6) {
                if item.isAndroidPackage {
                    Button {
                        actions.onInstallApk(item)
                    } label: {
                        Text("安装")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .buttonStyle(.bordered)
                    .tint(.green)
                    .controlSize(.small)
                    .help("解析并安装此 Android 安装包")
                }

                if !item.isDirectory {
                    Button {
                        actions.onDownloadItems([item])
                    } label: {
                        Image(systemName: "arrow.down.circle")
                            .font(.system(size: 13))
                    }
                    .buttonStyle(.borderless)
                    .help("下载到 Mac")
                    .accessibilityLabel("下载 \(item.name) 到 Mac")
                }

                Menu {
                    itemContextMenu(for: item)
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 13))
                }
                .menuStyle(.borderlessButton)
                .frame(width: 24)
                .accessibilityLabel("更多操作：\(item.name)")
            }
            .frame(width: 112, alignment: .trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(
            isSelected
                ? Color.accentColor.opacity(0.16)
                : (isAlternate ? Color.primary.opacity(0.025) : Color.clear)
        )
        .contentShape(Rectangle())
        .onTapGesture(count: 2) {
            activateItem(item)
        }
        .onTapGesture {
            toggleSelection(for: item)
        }
        .contextMenu {
            itemContextMenu(for: item)
        }
    }

    private var fileGridView: some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 124, maximum: 148), spacing: 12)],
                spacing: 12
            ) {
                ForEach(visibleItems) { item in
                    fileGridCard(item)
                        .onAppear {
                            requestThumbnailIfNeeded(for: item)
                        }
                }
            }
            .padding(14)
        }
    }

    private func fileGridCard(_ item: RemoteFileItem) -> some View {
        let isSelected = selectedFilePaths.contains(item.path)

        return VStack(spacing: 8) {
            ZStack(alignment: .topTrailing) {
                fileIconView(for: item, size: 58)
                    .frame(width: 72, height: 64)

                if item.isAndroidPackage {
                    Text("APK")
                        .font(.system(size: 9.5, weight: .bold))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(Color.green.opacity(0.2), in: Capsule())
                        .foregroundStyle(.green)
                }
            }

            VStack(spacing: 2) {
                Text(item.name)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(2)
                    .truncationMode(.middle)
                    .multilineTextAlignment(.center)
                Text(item.isDirectory ? "文件夹" : item.formattedSize)
                    .font(.system(size: 10.5).monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, minHeight: 126)
        .background(
            isSelected ? Color.accentColor.opacity(0.15) : Color.primary.opacity(0.035),
            in: RoundedRectangle(cornerRadius: 11, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .strokeBorder(isSelected ? Color.accentColor : Color.primary.opacity(0.08), lineWidth: isSelected ? 1.4 : 0.8)
        )
        .contentShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        .onTapGesture(count: 2) {
            activateItem(item)
        }
        .onTapGesture {
            toggleSelection(for: item)
        }
        .contextMenu {
            itemContextMenu(for: item)
        }
    }

    @ViewBuilder
    private func fileIconView(for item: RemoteFileItem, size: CGFloat) -> some View {
        if let thumbData = item.thumbnailData, let nsImage = NSImage(data: thumbData) {
            Image(nsImage: nsImage)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: max(size * 0.18, 5), style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: max(size * 0.18, 5), style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.6)
                )
        } else {
            let tint = swiftUIColor(for: item.kind.colorHint)
            ZStack {
                RoundedRectangle(cornerRadius: max(size * 0.22, 6), style: .continuous)
                    .fill(tint.opacity(0.14))
                    .frame(width: size, height: size)
                Image(systemName: item.sfSymbolName)
                    .font(.system(size: size * 0.48, weight: .medium))
                    .foregroundStyle(tint)
            }
        }
    }

    @ViewBuilder
    private func itemContextMenu(for item: RemoteFileItem) -> some View {
        if item.isDirectory {
            Button("打开文件夹", systemImage: "folder") {
                actions.onNavigateToPath(item.path)
            }
        } else {
            if item.isAndroidPackage {
                Button("安装到手机…", systemImage: "shippingbox") {
                    actions.onInstallApk(item)
                }
            }
            Button("下载到 Mac", systemImage: "arrow.down.circle") {
                actions.onDownloadItems([item])
            }
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

    private var dropTargetOverlay: some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, dash: [9, 6]))
            .background(Color.accentColor.opacity(0.10), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                VStack(spacing: 8) {
                    Image(systemName: "arrow.up.doc.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(Color.accentColor)
                    Text("松开即可上传到当前手机目录")
                        .font(.headline)
                    Text(currentPath)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
                .padding(20)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(16)
            .allowsHitTesting(false)
    }

    // MARK: - Tab 2: Storage Analysis Workspace

    @ViewBuilder
    private var storageWorkspace: some View {
        if !isConnected {
            ContentUnavailableView(
                "尚未连接 Android 设备",
                systemImage: "internaldrive",
                description: Text("连接手机后可分析各文件类别的空间占用，并按体积清理大文件。")
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let storageError, storageReport == nil {
            VStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(.orange)
                Text(storageError)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Button("重新扫描") { actions.onScanStorage() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let report = storageReport {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if isScanningStorage {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small)
                            Text("正在更新存储空间分析…")
                                .font(.callout)
                                .foregroundStyle(.secondary)
                        }
                    }

                    storageSummaryCard(report)
                    categoryBreakdownGrid(report)
                    largeFilesLeaderboardCard
                }
                .padding(20)
            }
        } else if isScanningStorage {
            VStack(spacing: 12) {
                ProgressView()
                Text("正在扫描手机存储空间与大文件…")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ContentUnavailableView {
                Label("分析手机存储空间", systemImage: "chart.bar.xaxis")
            } description: {
                Text("统计图片、视频、音频、文档、安装包体积分布，并找出占用空间的大文件。")
            } actions: {
                Button("开始扫描") {
                    actions.onScanStorage()
                }
                .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func storageSummaryCard(_ report: RemoteStorageReport) -> some View {
        let slices = FileBrowserPresentation.categoryBreakdownPercentages(for: report)
        let uncategorizedFraction = report.totalBytes > 0
            ? min(max(Double(report.uncategorizedUsedBytes) / Double(report.totalBytes), 0), 1)
            : 0

        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("设备存储概览")
                        .font(.headline)
                    Text("已用 \(FileBrowserPresentation.formatByteCount(report.usedBytes)) / 总计 \(FileBrowserPresentation.formatByteCount(report.totalBytes))")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("可用 \(FileBrowserPresentation.formatByteCount(report.freeBytes))")
                    .font(.system(size: 13, weight: .semibold).monospacedDigit())
                    .foregroundStyle(.green)
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
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            }
            .frame(height: 22)

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], alignment: .leading, spacing: 8) {
                ForEach(slices) { slice in
                    HStack(spacing: 7) {
                        Circle()
                            .fill(swiftUIColor(for: slice.category.colorHint))
                            .frame(width: 9, height: 9)
                        Text(slice.category.name)
                            .font(.system(size: 12, weight: .medium))
                        Text("\(slice.percentageText) · \(FileBrowserPresentation.formatByteCount(slice.category.bytes))")
                            .font(.system(size: 11.5).monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                if report.uncategorizedUsedBytes > 0 {
                    HStack(spacing: 7) {
                        Circle().fill(Color.secondary.opacity(0.45)).frame(width: 9, height: 9)
                        Text("系统及其他")
                            .font(.system(size: 12, weight: .medium))
                        Text(FileBrowserPresentation.formatByteCount(report.uncategorizedUsedBytes))
                            .font(.system(size: 11.5).monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(16)
        .opalDeskCardContainer(cornerRadius: 16, borderOpacity: 0.18)
    }

    private func categoryBreakdownGrid(_ report: RemoteStorageReport) -> some View {
        let slices = FileBrowserPresentation.categoryBreakdownPercentages(for: report)
        return LazyVGrid(
            columns: [GridItem(.adaptive(minimum: 185, maximum: 260), spacing: 12)],
            spacing: 12
        ) {
            ForEach(slices) { slice in
                let tint = swiftUIColor(for: slice.category.colorHint)
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 4, style: .continuous)
                                .fill(tint)
                                .frame(width: 10, height: 10)
                            Text(slice.category.name)
                                .font(.system(size: 13, weight: .semibold))
                        }
                        Spacer()
                        Text(slice.percentageText)
                            .font(.system(size: 11.5, weight: .semibold).monospacedDigit())
                            .foregroundStyle(.secondary)
                    }

                    Text(FileBrowserPresentation.formatByteCount(slice.category.bytes))
                        .font(.system(size: 17, weight: .bold).monospacedDigit())

                    ProgressView(value: slice.fractionOfUsed)
                        .tint(tint)

                    Text("\(slice.category.fileCount) 个文件")
                        .font(.system(size: 11).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .padding(14)
                .opalDeskCardContainer(cornerRadius: 14, borderOpacity: 0.14)
            }
        }
    }

    private var largeFilesLeaderboardCard: some View {
        let selectedBytes = selectedLargeFiles.reduce(Int64(0)) { $0 + max($1.sizeBytes, 0) }

        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
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
                    .padding(.vertical, 12)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(visibleLargeFiles.enumerated()), id: \.element.id) { index, file in
                        largeFileRow(file, rank: index + 1)
                        if index < visibleLargeFiles.count - 1 {
                            Divider().padding(.leading, 44)
                        }
                    }
                }
            }
        }
        .padding(16)
        .opalDeskCardContainer(cornerRadius: 16, borderOpacity: 0.18)
    }

    private func largeFileRow(_ file: RemoteFileItem, rank: Int) -> some View {
        let isChecked = selectedLargeFilePaths.contains(file.path)

        return HStack(spacing: 10) {
            Toggle(isOn: Binding(
                get: { isChecked },
                set: { checked in
                    if checked {
                        selectedLargeFilePaths.insert(file.path)
                    } else {
                        selectedLargeFilePaths.remove(file.path)
                    }
                }
            )) {
                EmptyView()
            }
            .toggleStyle(.checkbox)
            .labelsHidden()
            .accessibilityLabel("选择 \(file.name)")

            Text("#\(rank)")
                .font(.system(size: 11, weight: .semibold).monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 24, alignment: .leading)

            fileIconView(for: file, size: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(file.name)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(file.path)
                    .font(.system(size: 11).monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer(minLength: 8)

            Text(file.formattedSize)
                .font(.system(size: 12.5, weight: .semibold).monospacedDigit())
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Color.primary.opacity(0.06), in: Capsule())

            Button {
                actions.onDownloadItems([file])
            } label: {
                Image(systemName: "arrow.down.circle")
            }
            .buttonStyle(.borderless)
            .help("下载备份到 Mac")
            .accessibilityLabel("下载备份 \(file.name)")

            Button(role: .destructive) {
                pendingDeleteItems = [file]
            } label: {
                Image(systemName: "trash")
                    .foregroundStyle(.red)
            }
            .buttonStyle(.borderless)
            .help("删除此文件")
            .accessibilityLabel("删除 \(file.name)")
        }
        .padding(.vertical, 8)
    }

    // MARK: - Bottom Collapsible Transfer Queue Strip

    private var transferQueueStrip: some View {
        let activeCount = transfers.filter { !$0.state.isTerminal }.count

        return VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button {
                    withAnimation(.easeInOut(duration: 0.16)) {
                        isTransferDrawerExpanded.toggle()
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: isTransferDrawerExpanded ? "chevron.down" : "chevron.right")
                            .font(.system(size: 10.5, weight: .semibold))
                        Text(activeCount > 0 ? "传输队列（进行中 \(activeCount) / 共 \(transfers.count) 项）" : "传输记录（共 \(transfers.count) 项）")
                            .font(.system(size: 12, weight: .semibold))
                    }
                }
                .buttonStyle(.plain)

                Spacer()

                if transfers.contains(where: { $0.state.isTerminal }) {
                    Button("清除已完成") {
                        actions.onClearCompletedTransfers()
                    }
                    .buttonStyle(.borderless)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
            .background(Color(nsColor: .controlBackgroundColor).opacity(0.65))

            if isTransferDrawerExpanded {
                ScrollView {
                    VStack(spacing: 6) {
                        ForEach(transfers) { transfer in
                            transferQueueRow(transfer)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
                .frame(maxHeight: 132)
            }
        }
    }

    private func transferQueueRow(_ transfer: RemoteFileTransferItem) -> some View {
        HStack(spacing: 10) {
            Image(systemName: transfer.direction.sfSymbolName)
                .font(.system(size: 14))
                .foregroundStyle(transferStatusColor(for: transfer.state))

            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(transfer.fileName)
                        .font(.system(size: 12, weight: .medium))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                    Text(transfer.statusSummary)
                        .font(.system(size: 11).monospacedDigit())
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
                Button {
                    actions.onDismissTransfer(transfer.id)
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 10.5))
                }
                .buttonStyle(.borderless)
                .help("移除此记录")
                .accessibilityLabel("移除 \(transfer.fileName) 传输记录")
            } else {
                Button {
                    actions.onCancelTransfer(transfer.id)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .help("取消传输")
                .accessibilityLabel("取消传输 \(transfer.fileName)")
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    // MARK: - Sheets & Helpers

    private var newFolderSheet: some View {
        let joinedPath = FileBrowserPresentation.joining(base: currentPath, name: newFolderName)
        let showValidationError = !newFolderName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && joinedPath == nil

        return VStack(alignment: .leading, spacing: 14) {
            Text("新建文件夹")
                .font(.title3.weight(.semibold))
            Text("将在「\(currentPath)」下创建子目录")
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField("输入文件夹名称", text: $newFolderName)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    if let valid = FileBrowserPresentation.joining(base: currentPath, name: newFolderName) {
                        _ = valid
                        let clean = newFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
                        isNewFolderPresented = false
                        actions.onCreateFolder(currentPath, clean)
                    }
                }
            if showValidationError {
                Text("名称不能为空、不能为 . 或 ..，且不能包含 / 或 \\。")
                    .font(.caption)
                    .foregroundStyle(.red)
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
        .padding(20)
        .frame(width: 360)
    }

    private func renameSheet(for item: RemoteFileItem) -> some View {
        let parent = FileBrowserPresentation.parentPath(of: item.path) ?? currentPath
        let joinedPath = FileBrowserPresentation.joining(base: parent, name: renameInput)
        let trimmed = renameInput.trimmingCharacters(in: .whitespacesAndNewlines)
        let isChanged = trimmed != item.name
        let showValidationError = !trimmed.isEmpty && joinedPath == nil

        return VStack(alignment: .leading, spacing: 14) {
            Text("重命名「\(item.name)」")
                .font(.title3.weight(.semibold))
                .lineLimit(1)
                .truncationMode(.middle)
            TextField("输入新名称", text: $renameInput)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    if joinedPath != nil && isChanged {
                        renamingItem = nil
                        actions.onRenameItem(item, trimmed)
                    }
                }
            if showValidationError {
                Text("名称不能为 . 或 ..，且不能包含 / 或 \\。")
                    .font(.caption)
                    .foregroundStyle(.red)
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
        .padding(20)
        .frame(width: 360)
    }

    private func inlineNoticeBanner(_ message: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "info.circle.fill")
                .foregroundStyle(Color.accentColor)
            Text(message)
                .font(.system(size: 12))
            Spacer()
            Button {
                localNoticeMessage = nil
            } label: {
                Image(systemName: "xmark")
                    .font(.caption)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("关闭提示")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 7)
        .background(Color.accentColor.opacity(0.08))
    }

    private var deleteDialogTitle: String {
        if pendingDeleteItems.count == 1, let first = pendingDeleteItems.first {
            return "确定永久删除「\(first.name)」吗？"
        }
        return "确定永久删除选中的 \(pendingDeleteItems.count) 个项目吗？"
    }

    private var deleteDialogMessage: String {
        let totalBytes = pendingDeleteItems.reduce(Int64(0)) { $0 + max($1.sizeBytes, 0) }
        if totalBytes > 0 {
            return "此操作不可恢复，将从 Android 设备删除约 \(FileBrowserPresentation.formatByteCount(totalBytes)) 数据。"
        }
        return "此操作不可恢复，将从 Android 设备永久删除所选文件或文件夹。"
    }

    // MARK: - Actions & Helpers

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

    private func openUploadFilePanel() {
        let panel = NSOpenPanel()
        panel.title = "选择要上传到「\(currentPath)」的文件"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        if panel.runModal() == .OK {
            let validFiles = FileBrowserPresentation.filterUploadableLocalFiles(panel.urls)
            if !validFiles.isEmpty {
                actions.onUploadFiles(validFiles, currentPath)
            }
        }
    }

    private func handleDrop(providers: [NSItemProvider]) -> Bool {
        guard isConnected else { return false }
        let fileProviders = providers.filter {
            $0.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
        }
        guard !fileProviders.isEmpty else { return false }

        let collected = OSAllocatedUnfairLock<[URL]>(initialState: [])
        let group = DispatchGroup()

        for provider in fileProviders {
            group.enter()
            _ = provider.loadDataRepresentation(forTypeIdentifier: UTType.fileURL.identifier) { data, _ in
                defer { group.leave() }
                guard let data,
                      let url = URL(dataRepresentation: data, relativeTo: nil),
                      url.isFileURL else {
                    return
                }
                collected.withLock { $0.append(url) }
            }
        }

        let targetDir = currentPath
        group.notify(queue: .main) {
            Task { @MainActor in
                let rawURLs = collected.withLock { $0 }
                let validFiles = FileBrowserPresentation.filterUploadableLocalFiles(rawURLs)
                guard !validFiles.isEmpty else {
                    if !rawURLs.isEmpty {
                        localNoticeMessage = "暂不支持直接拖入文件夹，请拖入具体文件上传。"
                    }
                    return
                }
                actions.onUploadFiles(validFiles, targetDir)
            }
        }
        return true
    }

    private func transferStatusColor(for state: RemoteFileTransferState) -> Color {
        switch state {
        case .queued, .inProgress: return .accentColor
        case .completed: return .green
        case .failed: return .red
        case .cancelled: return .secondary
        }
    }

    private func swiftUIColor(for hint: RemoteStorageColorHint) -> Color {
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
