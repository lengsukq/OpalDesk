import AppKit
import CryptoKit
import Foundation
import OpalDeskCore
import os

// MARK: - Bidirectional Remote File Browser, Storage Analyzer, Finder WebDAV Mount & Quick Look Cache

extension AppModel {
    private enum FileBrowserTuning {
        static let maxThumbnailCacheEntries: Int = 240
        static let maxQuickLookCacheBytes: Int64 = 256 * 1024 * 1024
        static let maxQuickLookCacheEntries: Int = 64
    }

    public func openFileBrowserWindow() {
        onRequestOpenFileBrowserWindow?()
        NSApp.activate(ignoringOtherApps: true)
    }

    public func ensureFileBrowserInitialLoad() {
        guard isAnyChannelConnected else { return }
        if fileBrowserItems.isEmpty && !isLoadingFileBrowser {
            navigateFileBrowser(to: fileBrowserCurrentPath)
        }
    }

    public func makeFileBrowserActions() -> RemoteFileBrowserActions {
        RemoteFileBrowserActions(
            onNavigateToPath: { [weak self] path in
                self?.navigateFileBrowser(to: path)
            },
            onRefreshDirectory: { [weak self] path in
                self?.refreshFileBrowser(path: path)
            },
            onCreateFolder: { [weak self] parentPath, name in
                self?.createRemoteFolder(parentPath: parentPath, name: name)
            },
            onUploadFiles: { [weak self] localURLs, remoteDirectory in
                self?.uploadFilesToRemoteDirectory(localURLs, remoteDirectory: remoteDirectory)
            },
            onDownloadItems: { [weak self] items in
                self?.downloadRemoteItems(items)
            },
            onRenameItem: { [weak self] item, newName in
                self?.renameRemoteItem(item, newName: newName)
            },
            onDeleteItems: { [weak self] items in
                self?.deleteRemoteItems(items)
            },
            onInstallApk: { [weak self] item in
                self?.presentApkInstaller(forRemoteItem: item)
            },
            onRequestThumbnail: { [weak self] item in
                self?.requestRemoteThumbnail(for: item)
            },
            onScanStorage: { [weak self] in
                self?.scanRemoteStorage()
            },
            onCancelTransfer: { [weak self] transferItemId in
                self?.cancelFileBrowserTransfer(id: transferItemId)
            },
            onDismissTransfer: { [weak self] transferItemId in
                self?.dismissFileBrowserTransfer(id: transferItemId)
            },
            onClearCompletedTransfers: { [weak self] in
                self?.clearCompletedFileBrowserTransfers()
            },
            onToggleFinderMount: { [weak self] in
                self?.toggleFinderMount()
            },
            onOpenFinderMount: { [weak self] in
                self?.openMountedFinderVolume()
            },
            onUnmountFinder: { [weak self] in
                guard let self else { return }
                Task { await self.unmountFinderIfNeeded(showToastOnSuccess: true) }
            },
            onQuickLookItem: { [weak self] item in
                self?.previewRemoteItemWithQuickLook(item)
            },
            onOpenItem: { [weak self] item in
                self?.openRemoteItemDirectly(item)
            },
            onOpenAppPopout: { [weak self] app in
                self?.openAppPopoutForRemotePackage(
                    packageName: app.pkg,
                    appLabel: app.displayLabel
                )
            },
            onPullFileForDrag: { [fileBrowserClient] remotePath, destinationURL in
                try await fileBrowserClient.pullFile(remotePath: remotePath, toLocalURL: destinationURL)
            }
        )
    }

    // MARK: - Native Finder WebDAV Volume Mount

    public func toggleFinderMount() {
        if finderMountState.isMounted {
            Task { await unmountFinderIfNeeded(showToastOnSuccess: true) }
            return
        }
        guard finderMountState != .mounting else { return }
        guard isAnyChannelConnected else {
            let msg = OpalL10n.text("app.files.mount_disconnected")
            fileBrowserError = msg
            showToast(msg)
            return
        }

        let targetDeviceName = activeChannelDeviceName
        let browserClient = fileBrowserClient
        let backend = CompanionRemoteFileSystemBackend(browserClient: browserClient) { [weak self] localURL, toRemoteDirectory, remoteFileName in
            guard let self else { throw RemoteFileSystemError.ioError(OpalL10n.text("app.files.session_closed")) }
            guard let remotePath = FileBrowserPresentation.joining(base: toRemoteDirectory, name: remoteFileName) else {
                throw RemoteFileSystemError.invalidPath("\(toRemoteDirectory)/\(remoteFileName)")
            }
            _ = try await self.uploadFileAwaitingAck(
                url: localURL,
                remoteFileNameOverride: remotePath,
                trackAsApkUpload: false,
                onProgress: { _ in }
            )
        }

        finderMountTask?.cancel()
        finderMountTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let mountPoint = try await self.finderMountManager.mount(
                    deviceName: targetDeviceName,
                    backend: backend,
                    rootPath: "/sdcard"
                )
                guard !Task.isCancelled else { return }
                self.finderMountState = self.finderMountManager.currentState
                NSWorkspace.shared.selectFile(nil, inFileViewerRootedAtPath: mountPoint.path)
                self.showToast(
                    OpalL10n.format("app.files.mounted_fmt", targetDeviceName),
                    durationSeconds: 5.0,
                    actionTitle: OpalL10n.text("common.open_finder"),
                    revealURL: mountPoint
                )
            } catch is CancellationError {
                self.finderMountState = self.finderMountManager.currentState
            } catch {
                OpalDeskLog.file.error("Finder WebDAV mount failed: \(error.localizedDescription, privacy: .public)")
                self.finderMountState = self.finderMountManager.currentState
                self.fileBrowserError = error.localizedDescription
                self.showToast(error.localizedDescription)
            }
        }
    }

    public func openMountedFinderVolume() {
        guard let mountPoint = finderMountState.mountPoint ?? finderMountManager.currentState.mountPoint else { return }
        NSWorkspace.shared.selectFile(nil, inFileViewerRootedAtPath: mountPoint.path)
    }

    public func unmountFinderIfNeeded(showToastOnSuccess: Bool = false) async {
        finderMountTask?.cancel()
        finderMountTask = nil
        let hadMount = finderMountState.isMounted
            || finderMountState == .mounting
            || finderMountManager.currentState.isMounted
        guard hadMount else {
            finderMountState = .unmounted
            return
        }
        await finderMountManager.unmount()
        finderMountState = .unmounted
        if showToastOnSuccess {
            showToast(OpalL10n.text("app.files.unmounted"))
        }
    }

    /// Non-blocking unmount invoked during `cleanupOnTerminate()` so `@MainActor` never stalls on exit.
    func detachFinderUnmountIfNeeded() {
        guard let mountPoint = finderMountState.mountPoint ?? finderMountManager.currentState.mountPoint else {
            return
        }
        finderMountState = .unmounted
        let mountPath = mountPoint.path
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/diskutil")
        process.arguments = ["unmount", "force", mountPath]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
        } catch {
            OpalDeskLog.file.warning("Failed to detach Finder unmount for \(mountPath, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }

    // MARK: - Quick Look Bounded Temp Cache & Preview

    /// Downloads a remote non-directory file into a bounded temporary cache (`OpalDeskQuickLook`) keyed by
    /// `path + sizeBytes + modifiedAtMs` so repeated Spacebar Quick Look previews open instantaneously.
    public func downloadForQuickLook(_ item: RemoteFileItem) async throws -> URL {
        guard !item.isDirectory else {
            throw RemoteFileBrowserError.localFileError(OpalL10n.text("app.files.quicklook_folder"))
        }
        guard isAnyChannelConnected else {
            throw RemoteFileBrowserError.notConnected
        }

        let cacheRoot = Self.quickLookCacheRootURL()
        let keyHash = Self.quickLookCacheKey(for: item)
        let entryDir = cacheRoot.appendingPathComponent(keyHash, isDirectory: true)
        let safeName = RemoteFilePromiseDelegate.sanitizedFileName(item.name)
        let destinationURL = entryDir.appendingPathComponent(safeName)

        let fm = FileManager.default
        if fm.fileExists(atPath: destinationURL.path) {
            let existingSize = Int64((try? destinationURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? -1)
            if item.sizeBytes <= 0 || existingSize == item.sizeBytes {
                try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: entryDir.path)
                return destinationURL
            }
        }

        try fm.createDirectory(at: entryDir, withIntermediateDirectories: true)
        let savedURL = try await fileBrowserClient.pullFile(
            remotePath: item.path,
            toLocalURL: destinationURL
        )
        try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: entryDir.path)
        Self.pruneQuickLookCacheIfNeeded(cacheRoot: cacheRoot, keepingDirName: keyHash)
        return savedURL
    }

    public func previewRemoteItemWithQuickLook(_ item: RemoteFileItem) {
        guard !item.isDirectory else { return }
        quickLookDownloadTask?.cancel()
        quickLookLoadingPath = item.path

        quickLookDownloadTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.quickLookLoadingPath == item.path {
                    self.quickLookLoadingPath = nil
                }
            }
            do {
                let localURL = try await self.downloadForQuickLook(item)
                guard !Task.isCancelled else { return }
                self.quickLookCoordinator.togglePreview(url: localURL)
            } catch is CancellationError {
                return
            } catch {
                OpalDeskLog.file.warning("Quick Look download failed for \(item.path, privacy: .public): \(error.localizedDescription, privacy: .public)")
                self.showToast(OpalL10n.format("app.files.quicklook_failed_fmt", error.localizedDescription))
            }
        }
    }

    /// Streams a remote file into the bounded LRU temp cache (instant hit on repeat opens) and launches
    /// it directly with macOS's default application without cluttering the user's Downloads folder.
    public func openRemoteItemDirectly(_ item: RemoteFileItem) {
        guard !item.isDirectory else { return }
        quickLookDownloadTask?.cancel()
        quickLookLoadingPath = item.path

        quickLookDownloadTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.quickLookLoadingPath == item.path {
                    self.quickLookLoadingPath = nil
                }
            }
            do {
                let localURL = try await self.downloadForQuickLook(item)
                guard !Task.isCancelled else { return }
                let opened = NSWorkspace.shared.open(localURL)
                if !opened {
                    self.quickLookCoordinator.togglePreview(url: localURL)
                }
                self.showToast(OpalL10n.format("app.files.direct_opened_fmt", item.name), revealURL: localURL)
            } catch is CancellationError {
                return
            } catch {
                OpalDeskLog.file.warning("Direct open failed for \(item.path, privacy: .public): \(error.localizedDescription, privacy: .public)")
                self.showToast(OpalL10n.format("app.files.direct_open_failed_fmt", error.localizedDescription))
            }
        }
    }

    private static func quickLookCacheRootURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("OpalDeskQuickLook", isDirectory: true)
    }

    private static func quickLookCacheKey(for item: RemoteFileItem) -> String {
        let raw = "\(item.path)|\(item.sizeBytes)|\(item.modifiedAtMs)"
        let digest = SHA256.hash(data: Data(raw.utf8))
        return digest.prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    private static func pruneQuickLookCacheIfNeeded(cacheRoot: URL, keepingDirName: String) {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: cacheRoot,
            includingPropertiesForKeys: [.isDirectoryKey, .contentModificationDateKey],
            options: []
        ) else { return }

        struct EntryStat {
            let url: URL
            let modifiedAt: Date
            let bytes: Int64
        }

        var stats: [EntryStat] = []
        var totalBytes: Int64 = 0
        for entry in entries {
            let modDate = (try? entry.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            var dirBytes: Int64 = 0
            if let children = try? fm.contentsOfDirectory(at: entry, includingPropertiesForKeys: [.fileSizeKey], options: []) {
                for child in children {
                    dirBytes += Int64((try? child.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
                }
            }
            totalBytes += dirBytes
            stats.append(EntryStat(url: entry, modifiedAt: modDate, bytes: dirBytes))
        }

        guard stats.count > FileBrowserTuning.maxQuickLookCacheEntries
            || totalBytes > FileBrowserTuning.maxQuickLookCacheBytes
        else {
            return
        }

        let sortedOldestFirst = stats.sorted { $0.modifiedAt < $1.modifiedAt }
        var remainingCount = stats.count
        var remainingBytes = totalBytes
        for stat in sortedOldestFirst {
            if stat.url.lastPathComponent == keepingDirName { continue }
            if remainingCount <= FileBrowserTuning.maxQuickLookCacheEntries
                && remainingBytes <= FileBrowserTuning.maxQuickLookCacheBytes
            {
                break
            }
            try? fm.removeItem(at: stat.url)
            remainingCount -= 1
            remainingBytes -= stat.bytes
        }
    }

    // MARK: - Directory Navigation, CRUD & File Transfers

    public func navigateFileBrowser(to path: String) {
        let normalized = FileBrowserPresentation.normalizePath(path)
        fileBrowserCurrentPath = normalized
        refreshFileBrowser(path: normalized)
    }

    public func refreshFileBrowser(path: String) {
        let normalized = FileBrowserPresentation.normalizePath(path)
        guard isAnyChannelConnected else {
            fileBrowserError = OpalL10n.text("app.files.disconnected")
            isLoadingFileBrowser = false
            return
        }

        fileBrowserDirectoryTask?.cancel()
        isLoadingFileBrowser = true
        fileBrowserError = nil

        fileBrowserDirectoryTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let entries = try await self.fileBrowserClient.listDirectory(path: normalized)
                guard !Task.isCancelled else { return }
                let mapped = entries.map { entry in
                    RemoteFileItem(
                        name: entry.name,
                        path: entry.path,
                        isDirectory: entry.isDir,
                        sizeBytes: entry.size,
                        modifiedAtMs: entry.modifiedMs,
                        mimeType: entry.mime,
                        thumbnailData: self.fileBrowserThumbnailCache[entry.path]
                    )
                }
                self.fileBrowserItems = mapped
                self.isLoadingFileBrowser = false
                self.fileBrowserError = nil
            } catch is CancellationError {
                self.isLoadingFileBrowser = false
            } catch {
                OpalDeskLog.file.warning("listDirectory(\(normalized, privacy: .public)) failed: \(error.localizedDescription, privacy: .public)")
                self.isLoadingFileBrowser = false
                self.fileBrowserError = error.localizedDescription
            }
        }
    }

    public func createRemoteFolder(parentPath: String, name: String) {
        guard let targetPath = FileBrowserPresentation.joining(base: parentPath, name: name) else {
            fileBrowserError = OpalL10n.format("app.files.invalid_folder_fmt", name)
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.fileBrowserClient.makeDirectory(path: targetPath)
                self.refreshFileBrowser(path: parentPath)
                self.showToast(OpalL10n.format("app.files.created_folder_fmt", name))
            } catch {
                self.fileBrowserError = error.localizedDescription
            }
        }
    }

    public func renameRemoteItem(_ item: RemoteFileItem, newName: String) {
        guard let parent = FileBrowserPresentation.parentPath(of: item.path),
              let newPath = FileBrowserPresentation.joining(base: parent, name: newName)
        else {
            fileBrowserError = OpalL10n.format("app.files.invalid_rename_fmt", newName)
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.fileBrowserClient.rename(from: item.path, to: newPath)
                self.refreshFileBrowser(path: self.fileBrowserCurrentPath)
                self.showToast(OpalL10n.format("app.files.renamed_fmt", newName))
            } catch {
                self.fileBrowserError = error.localizedDescription
            }
        }
    }

    public func deleteRemoteItems(_ items: [RemoteFileItem]) {
        guard !items.isEmpty else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            var deletedCount = 0
            var lastError: String?
            for item in items {
                do {
                    try await self.fileBrowserClient.delete(path: item.path)
                    deletedCount += 1
                    self.fileBrowserThumbnailCache.removeValue(forKey: item.path)
                } catch {
                    lastError = error.localizedDescription
                }
            }
            self.refreshFileBrowser(path: self.fileBrowserCurrentPath)
            if self.fileBrowserStorageReport != nil && deletedCount > 0 {
                self.scanRemoteStorage()
            }
            if let lastError {
                self.fileBrowserError = lastError
            } else if deletedCount > 0 {
                self.showToast(OpalL10n.format("app.files.deleted_count_fmt", deletedCount))
            }
        }
    }

    public func uploadFilesToRemoteDirectory(_ localURLs: [URL], remoteDirectory: String) {
        guard isAnyChannelConnected else {
            fileBrowserError = OpalL10n.text("app.files.disconnected")
            return
        }
        let validFiles = FileBrowserPresentation.filterUploadableLocalFiles(localURLs)
        guard !validFiles.isEmpty else { return }

        let targetDir = FileBrowserPresentation.normalizePath(remoteDirectory)
        for url in validFiles {
            guard let remotePath = FileBrowserPresentation.joining(base: targetDir, name: url.lastPathComponent) else {
                continue
            }
            let fileSize = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            do {
                let transferId = try fileSender.sendFile(
                    at: url,
                    remoteFileNameOverride: remotePath,
                    sendFrame: { [weak self] frame in
                        guard let self else { return }
                        _ = await self.sendPacedFileFrameOnActiveChannel(frame)
                    },
                    onProgress: { [weak self] progress in
                        Task { @MainActor [weak self] in self?.handleFileSenderProgress(progress) }
                    }
                )
                let transferItemId = "up-\(transferId)"
                fileBrowserUploadTransferMap[transferId] = (transferItemId, targetDir)
                let item = RemoteFileTransferItem(
                    id: transferItemId,
                    direction: .upload,
                    fileName: url.lastPathComponent,
                    localURL: url,
                    remotePath: remotePath,
                    totalBytes: fileSize,
                    transferredBytes: 0,
                    state: .inProgress
                )
                upsertFileBrowserTransfer(item)
            } catch {
                OpalDeskLog.file.error("File browser upload failed for \(url.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)")
                fileBrowserError = error.localizedDescription
            }
        }
    }

    public func downloadRemoteItems(_ items: [RemoteFileItem]) {
        let files = items.filter { !$0.isDirectory }
        guard !files.isEmpty, isAnyChannelConnected else { return }

        let downloadsDir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads", isDirectory: true)

        var targets: [(item: RemoteFileItem, localURL: URL)] = []
        if files.count == 1, let single = files.first {
            let panel = NSSavePanel()
            panel.title = OpalL10n.text("app.files.save_single_title")
            panel.nameFieldStringValue = single.name
            panel.directoryURL = downloadsDir
            panel.canCreateDirectories = true
            guard panel.runModal() == .OK, let destination = panel.url else { return }
            targets.append((single, destination))
        } else {
            let panel = NSOpenPanel()
            panel.title = OpalL10n.format("app.files.save_multi_title_fmt", files.count)
            panel.prompt = OpalL10n.text("app.files.save_prompt")
            panel.canChooseFiles = false
            panel.canChooseDirectories = true
            panel.canCreateDirectories = true
            panel.allowsMultipleSelection = false
            panel.directoryURL = downloadsDir
            guard panel.runModal() == .OK, let folder = panel.url else { return }
            for file in files {
                targets.append((file, uniqueLocalURL(in: folder, fileName: file.name)))
            }
        }

        for pair in targets {
            startSingleDownload(remoteItem: pair.item, destinationURL: pair.localURL)
        }
    }

    private func startSingleDownload(remoteItem: RemoteFileItem, destinationURL: URL) {
        let transferId = "down-\(UUID().uuidString)"
        let initial = RemoteFileTransferItem(
            id: transferId,
            direction: .download,
            fileName: remoteItem.name,
            localURL: destinationURL,
            remotePath: remoteItem.path,
            totalBytes: max(remoteItem.sizeBytes, 0),
            transferredBytes: 0,
            state: .inProgress
        )
        upsertFileBrowserTransfer(initial)

        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.fileBrowserDownloadTasks.removeValue(forKey: transferId) }
            do {
                let savedURL = try await self.fileBrowserClient.pullFile(
                    remotePath: remoteItem.path,
                    toLocalURL: destinationURL,
                    onProgress: { [weak self] progress in
                        Task { @MainActor [weak self] in
                            self?.updateFileBrowserTransfer(id: transferId) { entry in
                                if progress.totalBytes > 0 {
                                    entry.totalBytes = progress.totalBytes
                                }
                                entry.transferredBytes = progress.receivedBytes
                                entry.state = .inProgress
                            }
                        }
                    }
                )
                self.updateFileBrowserTransfer(id: transferId) { entry in
                    entry.transferredBytes = max(entry.totalBytes, entry.transferredBytes)
                    entry.state = .completed
                }
                self.showToast(
                    OpalL10n.format("app.files.downloaded_fmt", remoteItem.name),
                    durationSeconds: 5.0,
                    actionTitle: OpalL10n.text("common.reveal_in_finder"),
                    revealURL: savedURL
                )
            } catch is CancellationError {
                self.updateFileBrowserTransfer(id: transferId) { $0.state = .cancelled }
            } catch RemoteFileBrowserError.cancelled {
                self.updateFileBrowserTransfer(id: transferId) { $0.state = .cancelled }
            } catch {
                self.updateFileBrowserTransfer(id: transferId) { $0.state = .failed(error.localizedDescription) }
            }
        }
        fileBrowserDownloadTasks[transferId] = task
    }

    public func requestRemoteThumbnail(for item: RemoteFileItem) {
        guard isAnyChannelConnected, !item.isDirectory else { return }
        guard fileBrowserThumbnailCache[item.path] == nil else { return }
        guard inFlightThumbnailPaths.insert(item.path).inserted else { return }

        let targetPath = item.path
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.inFlightThumbnailPaths.remove(targetPath) }
            do {
                let data = try await self.fileBrowserClient.fetchThumbnail(path: targetPath)
                if self.fileBrowserThumbnailCache.count >= FileBrowserTuning.maxThumbnailCacheEntries {
                    self.fileBrowserThumbnailCache.removeAll(keepingCapacity: true)
                }
                self.fileBrowserThumbnailCache[targetPath] = data
                if let idx = self.fileBrowserItems.firstIndex(where: { $0.path == targetPath }) {
                    self.fileBrowserItems[idx].thumbnailData = data
                }
            } catch {
                OpalDeskLog.file.debug("Thumbnail unavailable for \(targetPath, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    public func scanRemoteStorage() {
        guard isAnyChannelConnected else {
            fileBrowserStorageError = OpalL10n.text("app.files.disconnected")
            isScanningRemoteStorage = false
            return
        }
        fileBrowserStorageTask?.cancel()
        isScanningRemoteStorage = true
        fileBrowserStorageError = nil

        fileBrowserStorageTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let summary = try await self.fileBrowserClient.scanStorage(path: "/sdcard")
                guard !Task.isCancelled else { return }
                let categories = summary.categories.map { cat in
                    RemoteStorageCategory(
                        name: Self.localizedStorageCategoryName(cat.name),
                        bytes: cat.bytes,
                        fileCount: cat.fileCount,
                        colorHint: RemoteStorageColorHint.defaultHint(for: cat.name)
                    )
                }
                let largeFiles = summary.largeFiles.map { file in
                    RemoteFileItem(
                        name: file.name,
                        path: file.path,
                        isDirectory: false,
                        sizeBytes: file.size,
                        modifiedAtMs: file.modifiedMs
                    )
                }
                self.fileBrowserStorageReport = RemoteStorageReport(
                    totalBytes: summary.totalBytes,
                    freeBytes: summary.freeBytes,
                    usedBytes: summary.usedBytes,
                    categories: categories,
                    largeFiles: largeFiles,
                    folders: summary.folders,
                    apps: summary.apps
                )
                self.isScanningRemoteStorage = false
                self.fileBrowserStorageError = nil
            } catch is CancellationError {
                self.isScanningRemoteStorage = false
            } catch {
                OpalDeskLog.file.warning("scanStorage failed: \(error.localizedDescription, privacy: .public)")
                self.isScanningRemoteStorage = false
                self.fileBrowserStorageError = error.localizedDescription
            }
        }
    }

    public func cancelFileBrowserTransfer(id: String) {
        if let downloadTask = fileBrowserDownloadTasks.removeValue(forKey: id) {
            downloadTask.cancel()
            updateFileBrowserTransfer(id: id) { $0.state = .cancelled }
            return
        }
        if id.hasPrefix("up-"), let tid = UInt32(id.dropFirst(3)) {
            cancelFileTransfer(tid)
        }
    }

    public func dismissFileBrowserTransfer(id: String) {
        fileBrowserTransfers.removeAll { $0.id == id }
    }

    public func clearCompletedFileBrowserTransfers() {
        fileBrowserTransfers.removeAll { $0.state.isTerminal }
    }

    // MARK: - Internal & Private File Browser Transfer Helpers

    func upsertFileBrowserTransfer(_ item: RemoteFileTransferItem) {
        if let idx = fileBrowserTransfers.firstIndex(where: { $0.id == item.id }) {
            fileBrowserTransfers[idx] = item
        } else {
            fileBrowserTransfers.insert(item, at: 0)
        }
    }

    func updateFileBrowserTransfer(id: String, mutate: (inout RemoteFileTransferItem) -> Void) {
        guard let idx = fileBrowserTransfers.firstIndex(where: { $0.id == id }) else { return }
        mutate(&fileBrowserTransfers[idx])
    }

    func updateFileBrowserUploadProgress(itemId: String, progress: FileTransferProgress) {
        updateFileBrowserTransfer(id: itemId) { item in
            item.totalBytes = max(progress.totalBytes, item.totalBytes)
            item.transferredBytes = progress.sentBytes
            switch progress.status {
            case .sending, .awaitingAck:
                item.state = .inProgress
            case .completed:
                item.transferredBytes = item.totalBytes
                item.state = .completed
            case .failed(let reason):
                item.state = .failed(reason)
            case .cancelled:
                item.state = .cancelled
            }
        }
    }

    private func uniqueLocalURL(in folder: URL, fileName: String) -> URL {
        let initial = folder.appendingPathComponent(fileName)
        guard FileManager.default.fileExists(atPath: initial.path) else { return initial }
        let stem = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension
        for idx in 1...999 {
            let candidateName = ext.isEmpty ? "\(stem) (\(idx))" : "\(stem) (\(idx)).\(ext)"
            let candidate = folder.appendingPathComponent(candidateName)
            if !FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
        }
        return folder.appendingPathComponent("\(UUID().uuidString)-\(fileName)")
    }

    private static func localizedStorageCategoryName(_ raw: String) -> String {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "images", "image", "photos": return OpalL10n.text("files.cat.images")
        case "videos", "video": return OpalL10n.text("files.cat.videos")
        case "audio", "music": return OpalL10n.text("files.cat.audio")
        case "documents", "docs", "document": return OpalL10n.text("files.cat.documents")
        case "apks", "apk", "packages", "apps": return OpalL10n.text("files.cat.apks")
        case "archives", "archive", "zip": return OpalL10n.text("files.cat.archives")
        case "others", "other": return OpalL10n.text("files.cat.others")
        default: return raw
        }
    }
}
