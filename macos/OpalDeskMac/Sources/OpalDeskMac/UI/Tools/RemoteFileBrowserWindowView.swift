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

public enum RemoteStorageSubTab: String, CaseIterable, Identifiable, Sendable {
    case categories = "分类概览"
    case folders = "文件夹占用"
    case apps = "应用空间排行"

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

/// Decoupled action callbacks allowing `RemoteFileBrowserWindowView` to remain independent of `AppModel`.
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
    public var onToggleFinderMount: @MainActor () -> Void
    public var onOpenFinderMount: @MainActor () -> Void
    public var onUnmountFinder: @MainActor () -> Void
    public var onQuickLookItem: @MainActor (RemoteFileItem) -> Void
    public var onPullFileForDrag: RemoteFileDragPullHandler?

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
        onClearCompletedTransfers: @escaping @MainActor () -> Void = {},
        onToggleFinderMount: @escaping @MainActor () -> Void = {},
        onOpenFinderMount: @escaping @MainActor () -> Void = {},
        onUnmountFinder: @escaping @MainActor () -> Void = {},
        onQuickLookItem: @escaping @MainActor (RemoteFileItem) -> Void = { _ in },
        onPullFileForDrag: RemoteFileDragPullHandler? = nil
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
        self.onToggleFinderMount = onToggleFinderMount
        self.onOpenFinderMount = onOpenFinderMount
        self.onUnmountFinder = onUnmountFinder
        self.onQuickLookItem = onQuickLookItem
        self.onPullFileForDrag = onPullFileForDrag
    }
}

// MARK: - Main Liquid Glass Window View

/// Native macOS Finder-inspired dual-tab remote file manager & storage analyzer for paired Android devices.
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
    public let finderMountState: FinderMountState
    public let quickLookLoadingPath: String?
    public let quickLocations: [RemoteQuickLocation]
    public let actions: RemoteFileBrowserActions

    @State var activeTab: RemoteFileBrowserTab
    @State var activeStorageSubTab: RemoteStorageSubTab = .categories
    @State var searchQuery: String = ""
    @State var sortOption: RemoteFileSortOption = .name
    @State var sortAscending: Bool = true
    @State var showHiddenFiles: Bool = false
    @State var viewMode: RemoteFileViewMode = .list
    @State var selectedFilePaths: Set<String> = []
    @State var selectedLargeFilePaths: Set<String> = []
    @State var isDropTargeted: Bool = false
    @State var isTransferDrawerExpanded: Bool = true
    @State var isNewFolderPresented: Bool = false
    @State var newFolderName: String = ""
    @State var renamingItem: RemoteFileItem?
    @State var renameInput: String = ""
    @State var pendingDeleteItems: [RemoteFileItem] = []
    @State var localNoticeMessage: String?

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
        finderMountState: FinderMountState = .unmounted,
        quickLookLoadingPath: String? = nil,
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
        self.finderMountState = finderMountState
        self.quickLookLoadingPath = quickLookLoadingPath
        self.quickLocations = quickLocations
        self.actions = actions
        _activeTab = State(initialValue: initialTab)
    }

    var visibleItems: [RemoteFileItem] {
        FileBrowserPresentation.filteredAndSorted(
            items,
            query: searchQuery,
            sortBy: sortOption,
            ascending: sortAscending,
            showHidden: showHiddenFiles
        )
    }

    var selectedItems: [RemoteFileItem] {
        visibleItems.filter { selectedFilePaths.contains($0.path) }
    }

    var visibleLargeFiles: [RemoteFileItem] {
        guard let report = storageReport else { return [] }
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return report.largeFiles }
        return report.largeFiles.filter {
            $0.name.localizedCaseInsensitiveContains(trimmed)
                || $0.path.localizedCaseInsensitiveContains(trimmed)
        }
    }

    var selectedLargeFiles: [RemoteFileItem] {
        visibleLargeFiles.filter { selectedLargeFilePaths.contains($0.path) }
    }

    public var body: some View {
        VStack(spacing: OpalSpacing.md) {
            topToolbarCard

            if let notice = localNoticeMessage {
                OpalNoticeBanner(
                    tone: .info,
                    message: notice,
                    onDismiss: { localNoticeMessage = nil }
                )
            }

            Group {
                switch activeTab {
                case .files: filesWorkspace
                case .storage: storageWorkspace
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if !transfers.isEmpty {
                transferQueueStrip
            }
        }
        .padding(.horizontal, OpalSpacing.lg)
        .padding(.top, OpalSpacing.sm)
        .padding(.bottom, OpalSpacing.lg)
        .background(OpalAmbientBackdrop(intensity: 0.85))
        .background(
            RemoteFileBrowserSpacebarMonitor(
                isEnabled: activeTab == .files && isConnected && !isNewFolderPresented && renamingItem == nil
            ) {
                triggerQuickLookForCurrentSelection()
            }
        )
        .frame(minWidth: 920, minHeight: 560)
        .onChange(of: currentPath) { _, _ in
            selectedFilePaths.removeAll()
        }
        .onChange(of: activeTab) { _, nextTab in
            if nextTab == .storage && storageReport == nil && !isScanningStorage && isConnected {
                actions.onScanStorage()
            }
        }
        .sheet(isPresented: $isNewFolderPresented) { newFolderSheet }
        .sheet(item: $renamingItem) { item in renameSheet(for: item) }
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
            Button("取消", role: .cancel) { pendingDeleteItems = [] }
        } message: {
            Text(deleteDialogMessage)
        }
    }

    // MARK: - Top Floating Liquid Glass Toolbar

    private var topToolbarCard: some View {
        HStack(spacing: OpalSpacing.md) {
            HStack(spacing: OpalSpacing.sm) {
                OpalIconBadge(
                    systemName: "iphone.gen3",
                    tint: isConnected ? .accentColor : .secondary,
                    size: 34,
                    iconSize: 15,
                    cornerRadius: 10
                )

                VStack(alignment: .leading, spacing: 1) {
                    Text(deviceName)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    HStack(spacing: 5) {
                        Circle()
                            .fill(isConnected ? Color.green : Color.orange)
                            .frame(width: 6, height: 6)
                        Text(isConnected ? "已连接" : "未连接")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer(minLength: OpalSpacing.sm)

            Picker("视图模式", selection: $activeTab) {
                ForEach(RemoteFileBrowserTab.allCases) { tab in
                    Text(tab.rawValue).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 176)

            Spacer(minLength: OpalSpacing.xs)

            OpalSearchField(
                placeholder: activeTab == .files ? "筛选当前目录…" : "筛选文件夹 / 应用 / 大文件…",
                text: $searchQuery,
                accessibilityClearLabel: "清除搜索关键字"
            )
            .frame(minWidth: 150, idealWidth: 185, maxWidth: 210)

            toolbarActionButtons
        }
        .padding(.horizontal, OpalSpacing.lg)
        .padding(.vertical, 10)
        .opalGlassSurface(.panel, cornerRadius: OpalRadius.card)
    }

    private var toolbarActionButtons: some View {
        HStack(spacing: OpalSpacing.sm) {
            finderMountControl

            OpalGlassIconButton(
                systemName: "arrow.clockwise",
                size: 28,
                iconSize: 12.5,
                helpText: activeTab == .files ? "刷新目录 (⌘R)" : "重新扫描存储空间 (⌘R)"
            ) {
                if activeTab == .files {
                    actions.onRefreshDirectory(currentPath)
                } else {
                    actions.onScanStorage()
                }
            }
            .disabled(!isConnected || (activeTab == .files ? isLoadingFiles : isScanningStorage))
            .keyboardShortcut("r", modifiers: [.command])

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

    @ViewBuilder
    private var finderMountControl: some View {
        switch finderMountState {
        case .mounted:
            Menu {
                Button("在 Finder 中打开", systemImage: "folder") {
                    actions.onOpenFinderMount()
                }
                Divider()
                Button("卸载 Finder 卷", systemImage: "eject.fill", role: .destructive) {
                    actions.onUnmountFinder()
                }
            } label: {
                Label("已挂载 Finder", systemImage: "externaldrive.fill.badge.checkmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.green)
            }
            .menuStyle(.borderedButton)
            .tint(.green)
            .fixedSize()
            .help("手机存储已通过本地 WebDAV 挂载到 macOS 访达，点击可打开或卸载")

        case .mounting:
            Button {} label: {
                HStack(spacing: 5) {
                    ProgressView().controlSize(.mini)
                    Text("挂载中…")
                        .font(.system(size: 12, weight: .medium))
                }
            }
            .buttonStyle(.bordered)
            .disabled(true)

        case .unmounted, .failed:
            Button {
                actions.onToggleFinderMount()
            } label: {
                Label("在 Finder 中挂载", systemImage: "externaldrive.badge.plus")
                    .font(.system(size: 12, weight: .medium))
            }
            .buttonStyle(.bordered)
            .disabled(!isConnected)
            .help("通过本地加密回环 WebDAV 将手机存储直接挂载到 macOS 访达")
        }
    }

    func triggerQuickLookForCurrentSelection() {
        let previewable = selectedItems.filter { !$0.isDirectory }
        guard previewable.count == 1, let target = previewable.first else { return }
        actions.onQuickLookItem(target)
    }

    // MARK: - Shared Actions

    func openUploadFilePanel() {
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

    func handleDrop(providers: [NSItemProvider]) -> Bool {
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
}
