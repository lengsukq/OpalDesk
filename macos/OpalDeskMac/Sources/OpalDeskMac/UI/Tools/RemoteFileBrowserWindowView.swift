import AppKit
import OpalDeskCore
import SwiftUI
import UniformTypeIdentifiers
import os

// MARK: - View Modes & Injected Action Callbacks

public enum RemoteFileBrowserTab: String, CaseIterable, Identifiable, Sendable {
    case files
    case storage

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .files: return OpalL10n.text("files.tab.files")
        case .storage: return OpalL10n.text("files.tab.storage")
        }
    }
}

public enum RemoteStorageSubTab: String, CaseIterable, Identifiable, Sendable {
    case categories
    case folders
    case apps

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .categories: return OpalL10n.text("files.storage.tab.categories")
        case .folders: return OpalL10n.text("files.storage.tab.folders")
        case .apps: return OpalL10n.text("files.storage.tab.apps")
        }
    }
}

public enum RemoteFileViewMode: String, CaseIterable, Identifiable, Sendable {
    case list
    case grid

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .list: return OpalL10n.text("files.view.list")
        case .grid: return OpalL10n.text("files.view.grid")
        }
    }

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
    public var onOpenItem: @MainActor (RemoteFileItem) -> Void
    public var onOpenAppPopout: (@MainActor (AppStorageEntry) -> Void)?
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
        onOpenItem: @escaping @MainActor (RemoteFileItem) -> Void = { _ in },
        onOpenAppPopout: (@MainActor (AppStorageEntry) -> Void)? = nil,
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
        self.onOpenItem = onOpenItem
        self.onOpenAppPopout = onOpenAppPopout
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
    public let directOpenMaxMB: Int
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
        directOpenMaxMB: Int = FileBrowserPresentation.defaultDirectOpenMaxMB,
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
        self.directOpenMaxMB = directOpenMaxMB
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
            Button(OpalL10n.text("files.delete.confirm"), role: .destructive) {
                let targets = pendingDeleteItems
                pendingDeleteItems = []
                selectedFilePaths.subtract(targets.map(\.path))
                selectedLargeFilePaths.subtract(targets.map(\.path))
                actions.onDeleteItems(targets)
            }
            Button(OpalL10n.text("common.cancel"), role: .cancel) { pendingDeleteItems = [] }
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
                        Text(isConnected ? OpalL10n.text("common.connected") : OpalL10n.text("common.disconnected"))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer(minLength: OpalSpacing.sm)

            OpalSegmentedControl(
                selection: $activeTab,
                options: [
                    OpalSegmentOption(value: RemoteFileBrowserTab.files, label: RemoteFileBrowserTab.files.title, icon: "folder"),
                    OpalSegmentOption(value: RemoteFileBrowserTab.storage, label: RemoteFileBrowserTab.storage.title, icon: "chart.pie"),
                ],
                compact: true
            )

            Spacer(minLength: OpalSpacing.xs)

            OpalSearchField(
                placeholder: activeTab == .files ? OpalL10n.text("files.search.files") : OpalL10n.text("files.search.storage"),
                text: $searchQuery,
                accessibilityClearLabel: OpalL10n.text("files.search.clear_a11y")
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
                helpText: activeTab == .files ? OpalL10n.text("files.refresh_dir_help") : OpalL10n.text("files.rescan_storage_help")
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
                    Label(OpalL10n.text("files.new_folder"), systemImage: "folder.badge.plus")
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                .disabled(!isConnected)
                .keyboardShortcut("n", modifiers: [.command, .shift])
                .help(OpalL10n.text("files.new_folder_help"))

                Button {
                    openUploadFilePanel()
                } label: {
                    Label(OpalL10n.text("files.upload"), systemImage: "arrow.up.doc")
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .small, isPill: true))
                .disabled(!isConnected)
                .help(OpalL10n.text("files.upload_help"))
            }
        }
    }

    @ViewBuilder
    private var finderMountControl: some View {
        switch finderMountState {
        case .mounted:
            Menu {
                Button(OpalL10n.text("files.finder.open"), systemImage: "folder") {
                    actions.onOpenFinderMount()
                }
                Divider()
                Button(OpalL10n.text("files.finder.unmount"), systemImage: "eject.fill", role: .destructive) {
                    actions.onUnmountFinder()
                }
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "externaldrive.fill.badge.checkmark")
                        .font(.system(size: 11.5, weight: .semibold))
                    Text(OpalL10n.text("files.finder.mounted"))
                        .font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(OpalFluidPalette.brandEmerald)
                .padding(.horizontal, 11)
                .frame(height: 28)
                .background(OpalFluidPalette.brandEmerald.opacity(0.12), in: Capsule())
                .overlay(Capsule().strokeBorder(OpalFluidPalette.brandEmerald.opacity(0.28), lineWidth: 0.85))
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .help(OpalL10n.text("files.finder.mounted_help"))

        case .mounting:
            Button {} label: {
                HStack(spacing: 5) {
                    ProgressView().controlSize(.mini)
                    Text(OpalL10n.text("hub.companion.mounting"))
                }
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
            .disabled(true)

        case .unmounted, .failed:
            Button {
                actions.onToggleFinderMount()
            } label: {
                Label(OpalL10n.text("files.finder.mount"), systemImage: "externaldrive.badge.plus")
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
            .disabled(!isConnected)
            .help(OpalL10n.text("files.finder.mount_help"))
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
        panel.title = OpalL10n.format("files.upload_panel_title_fmt", currentPath)
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
                        localNoticeMessage = OpalL10n.text("files.drop.folder_unsupported")
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
            return OpalL10n.format("files.delete.single_title_fmt", first.name)
        }
        return OpalL10n.format("files.delete.multi_title_fmt", pendingDeleteItems.count)
    }

    private var deleteDialogMessage: String {
        let totalBytes = pendingDeleteItems.reduce(Int64(0)) { $0 + max($1.sizeBytes, 0) }
        if totalBytes > 0 {
            return OpalL10n.format("files.delete.msg_bytes_fmt", FileBrowserPresentation.formatByteCount(totalBytes))
        }
        return OpalL10n.text("files.delete.msg_generic")
    }
}
