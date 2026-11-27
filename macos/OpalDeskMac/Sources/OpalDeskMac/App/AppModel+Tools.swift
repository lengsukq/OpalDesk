import AppKit
import CryptoKit
import Foundation
import OpalDeskCore
import UniformTypeIdentifiers
import os

// MARK: - Capture/Recording, Deep Links, Shortcuts, Remote File Browser, APK Installer & Finder Services

extension AppModel {
    private enum ToolsTuning {
        static let urlDedupWindowSeconds: TimeInterval = 0.45
        static let maxThumbnailCacheEntries: Int = 240
        static let maxQuickLookCacheBytes: Int64 = 256 * 1024 * 1024
        static let maxQuickLookCacheEntries: Int = 64
    }

    // MARK: - Formatting Helpers

    public var formattedRecordingDuration: String {
        Self.formatRecordingDuration(mainRecordingDurationSeconds)
    }

    public static func formatRecordingDuration(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60
        if hours > 0 {
            return String(format: "%02d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%02d:%02d", minutes, secs)
    }

    // MARK: - Main Window Screenshot & MP4 Screen Recording

    /// Captures the current projected frame to Desktop as PNG and simultaneously copies it to the macOS clipboard.
    public func captureMainWindowScreenshot() {
        guard isAuthenticated else {
            showToast("请先连接手机投屏后再截图")
            return
        }
        let renderer = videoRenderer
        let prefix = Self.captureFilePrefix(deviceName: activeConnection?.deviceName, suffix: "Screenshot")

        Task { @MainActor [weak self] in
            guard let self else { return }
            guard let image = await renderer.captureCurrentFrameCGImageAsync(timeoutSeconds: 2.0) else {
                self.showToast("当前暂无可用视频帧，请稍后重试截图")
                return
            }
            do {
                let savedURL = try WindowMediaCapture.saveImageToFile(image, prefix: prefix)
                _ = try? WindowMediaCapture.writeImageToClipboard(image)
                self.showToast(
                    "截图已保存到桌面并复制剪贴板",
                    durationSeconds: 5.0,
                    actionTitle: "在 Finder 显示",
                    revealURL: savedURL
                )
            } catch {
                OpalDeskLog.video.error("Main screenshot failed: \(error.localizedDescription, privacy: .public)")
                self.showToast("截图失败：\(error.localizedDescription)")
            }
        }
    }

    /// Toggles hardware-passthrough MP4 recording of the main desktop canvas.
    public func toggleMainWindowRecording() {
        if isMainWindowRecording {
            stopMainWindowRecording()
        } else {
            startMainWindowRecording()
        }
    }

    public func startMainWindowRecording() {
        guard isAuthenticated else {
            showToast("请先连接手机投屏后再录屏")
            return
        }
        guard !screenRecorder.isRecording else { return }
        let prefix = Self.captureFilePrefix(deviceName: activeConnection?.deviceName, suffix: "Recording")
        do {
            _ = try screenRecorder.start(
                renderer: videoRenderer,
                prefix: prefix,
                format: .mp4,
                onMetricsUpdate: { [weak self] metrics in
                    Task { @MainActor [weak self] in
                        guard let self else { return }
                        self.mainRecordingDurationSeconds = metrics.durationSeconds
                        self.isMainWindowRecording = metrics.state == .waitingForKeyframe || metrics.state == .recording
                    }
                }
            )
            isMainWindowRecording = true
            mainRecordingDurationSeconds = 0
            showToast("开始录制 MP4 视频（零编码直写）", durationSeconds: 2.2)
        } catch {
            isMainWindowRecording = false
            OpalDeskLog.video.error("Failed to start main screen recording: \(error.localizedDescription, privacy: .public)")
            showToast("启动录屏失败：\(error.localizedDescription)")
        }
    }

    public func stopMainWindowRecording() {
        guard screenRecorder.isRecording else {
            isMainWindowRecording = false
            mainRecordingDurationSeconds = 0
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let result = try await self.screenRecorder.stop()
                self.isMainWindowRecording = false
                self.mainRecordingDurationSeconds = 0
                let durText = Self.formatRecordingDuration(result.durationSeconds)
                self.showToast(
                    "录屏已保存至桌面（\(durText)）",
                    durationSeconds: 6.0,
                    actionTitle: "在 Finder 显示",
                    revealURL: result.url
                )
            } catch {
                self.isMainWindowRecording = false
                self.mainRecordingDurationSeconds = 0
                OpalDeskLog.video.warning("Main screen recording stop error: \(error.localizedDescription, privacy: .public)")
                self.showToast("录屏结束失败：\(error.localizedDescription)")
            }
        }
    }

    func stopMainRecordingIfNeededOnDisconnect() {
        guard screenRecorder.isRecording else {
            isMainWindowRecording = false
            mainRecordingDurationSeconds = 0
            return
        }
        if screenRecorder.currentMetrics.videoFrameCount > 0 {
            stopMainWindowRecording()
        } else {
            screenRecorder.cancel()
            isMainWindowRecording = false
            mainRecordingDurationSeconds = 0
        }
    }

    public func revealInFinder(_ url: URL) {
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    private static func captureFilePrefix(deviceName: String?, suffix: String) -> String {
        let cleanDevice = (deviceName ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
        if cleanDevice.isEmpty {
            return "OpalDesk-\(suffix)"
        }
        return "OpalDesk-\(cleanDevice)-\(suffix)"
    }

    // MARK: - Finder Services, Incoming URLs & `opaldesk://` Deep Links

    func makeFinderServicesProvider() -> FinderServicesProvider {
        FinderServicesProvider(
            onFilesReceived: { [weak self] urls in
                guard let self else { return }
                guard self.isAnyChannelConnected else {
                    self.onRequestOpenMainWindow?()
                    NSApp.activate(ignoringOtherApps: true)
                    let msg = "未连接到 Android 设备，无法通过 Finder 发送文件"
                    self.bannerError = msg
                    self.showToast(msg)
                    return
                }
                self.sendFiles(urls)
                self.showToast("正在发送 \(urls.count) 个文件到「\(self.activeChannelDeviceName)」…")
            },
            onApkPackageReceived: { [weak self] apkURL in
                self?.presentApkInstaller(forLocalURL: apkURL, targetWindow: .main)
            },
            onPlainTextReceived: { [weak self] text in
                self?.sendPlainTextToRemoteClipboard(text)
            }
        )
    }

    public func handleIncomingURLs(_ urls: [URL]) {
        for url in urls {
            handleIncomingURL(url)
        }
    }

    public func handleIncomingURL(_ url: URL) {
        let now = Date()
        let key = url.absoluteString
        recentIncomingURLTimestamps = recentIncomingURLTimestamps.filter {
            now.timeIntervalSince($0.value) < ToolsTuning.urlDedupWindowSeconds * 4
        }
        if let prev = recentIncomingURLTimestamps[key],
           now.timeIntervalSince(prev) < ToolsTuning.urlDedupWindowSeconds
        {
            return
        }
        recentIncomingURLTimestamps[key] = now

        if url.scheme?.lowercased() == OpalDeskDeepLink.scheme {
            handleDeepLinkURL(url)
            return
        }

        if url.isFileURL {
            if FileBrowserPresentation.isAndroidPackageFile(url) {
                presentApkInstaller(forLocalURL: url, targetWindow: .main)
            } else {
                let uploadable = FileBrowserPresentation.filterUploadableLocalFiles([url])
                if !uploadable.isEmpty {
                    sendFiles(uploadable)
                }
            }
        }
    }

    private func handleDeepLinkURL(_ url: URL) {
        do {
            let route = try OpalDeskDeepLink.parse(url)
            switch route {
            case .popout(let request):
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    await self.resolveAndOpenAppPopout(
                        packageName: request.packageName,
                        activityClass: request.activityClass ?? "",
                        appLabel: request.label ?? request.packageName,
                        preferredDeviceKey: request.deviceKey,
                        preferredDeviceName: request.deviceName,
                        waitForDiscoveryIfNeeded: true
                    )
                }
            }
        } catch {
            OpalDeskLog.warn("app", "Rejected invalid deep link \(url.absoluteString): \(error.localizedDescription)")
            onRequestOpenMainWindow?()
            bannerError = error.localizedDescription
            showToast(error.localizedDescription)
        }
    }

    // MARK: - Spotlight & Desktop `.app` Shortcuts

    public func createSpotlightShortcut(
        for app: RemoteAppInfo,
        deviceKey: String?,
        deviceName: String?,
        directory: URL = AppShortcutBuilder.defaultShortcutsDirectory
    ) {
        let spec = AppShortcutSpec(
            packageName: app.pkg,
            activityClass: app.cls.isEmpty ? nil : app.cls,
            appLabel: app.displayLabel,
            deviceKey: deviceKey,
            deviceName: deviceName,
            iconPNGData: nil
        )
        do {
            let created = try AppShortcutBuilder.createShortcut(
                spec: spec,
                in: directory,
                overwrite: true,
                refreshSpotlight: true
            )
            showToast(
                "已创建「\(created.displayName)」Spotlight 快捷方式",
                durationSeconds: 5.0,
                actionTitle: "在 Finder 显示",
                revealURL: created.bundleURL
            )
        } catch {
            OpalDeskLog.warn("app", "Failed to create Spotlight shortcut for \(app.pkg): \(error.localizedDescription)")
            bannerError = error.localizedDescription
            showToast("创建快捷方式失败：\(error.localizedDescription)")
        }
    }

    public func shortcutAppCandidates() -> [ShortcutAppCandidate] {
        var result: [ShortcutAppCandidate] = []
        var seen = Set<String>()

        func appendCandidate(_ candidate: ShortcutAppCandidate) {
            guard !candidate.packageName.isEmpty, seen.insert(candidate.id).inserted else { return }
            result.append(candidate)
        }

        if let conn = activeConnection, !cachedDesktopApps.isEmpty {
            let devKey: String? = {
                switch conn.transport {
                case .wiredUSB(let s, _), .wirelessADB(let s, _): return "adb:\(s)"
                case .wirelessWiFi:
                    return conn.fingerprint.isEmpty ? nil : "fp:\(conn.fingerprint.prefix(16))"
                }
            }()
            for app in cachedDesktopApps {
                appendCandidate(ShortcutAppCandidate(
                    packageName: app.pkg,
                    activityClass: app.cls.isEmpty ? nil : app.cls,
                    appLabel: app.displayLabel,
                    deviceKey: devKey,
                    deviceName: conn.deviceName
                ))
            }
        }

        if let target = companionTarget, !cachedCompanionApps.isEmpty {
            let devKey = target.certFingerprint.isEmpty ? nil : "fp:\(target.certFingerprint.prefix(16))"
            for app in cachedCompanionApps {
                appendCandidate(ShortcutAppCandidate(
                    packageName: app.pkg,
                    activityClass: app.cls.isEmpty ? nil : app.cls,
                    appLabel: app.displayLabel,
                    deviceKey: devKey,
                    deviceName: target.deviceName
                ))
            }
        }

        for entry in popoutHistory {
            appendCandidate(ShortcutAppCandidate(
                packageName: entry.appPackage,
                activityClass: entry.appActivity.isEmpty ? nil : entry.appActivity,
                appLabel: entry.displayLabel,
                deviceKey: entry.deviceKey.isEmpty ? nil : entry.deviceKey,
                deviceName: entry.deviceName
            ))
        }

        return result
    }

    public func openShortcutManager() {
        onRequestOpenMainWindow?()
        NSApp.activate(ignoringOtherApps: true)
        isShortcutManagerPresented = true
    }

    // MARK: - Wireless APK / APKS / APKM / XAPK Installation Pipeline

    public func presentApkFileChooser(targetWindow: ApkInstallerContext.PresentationTarget = .main) {
        let panel = NSOpenPanel()
        panel.title = "选择要安装的 Android 安装包 (.apk / .apks / .apkm / .xapk)"
        panel.prompt = "预览并安装"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = FileBrowserPresentation.androidPackageExtensions.compactMap {
            UTType(filenameExtension: $0)
        }
        if panel.runModal() == .OK, let url = panel.url {
            presentApkInstaller(forLocalURL: url, targetWindow: targetWindow)
        }
    }

    public func presentApkInstaller(
        forLocalURL url: URL,
        targetWindow: ApkInstallerContext.PresentationTarget = .main
    ) {
        activeApkInstallTask?.cancel()
        activeApkInstallTask = nil
        apkInstallState = .idle
        apkInstallerContext = ApkInstallerContext(
            packageURL: url,
            preloadedInfo: nil,
            remoteFilePath: nil,
            targetWindow: targetWindow
        )
        if targetWindow == .main {
            onRequestOpenMainWindow?()
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    public func presentApkInstaller(forRemoteItem item: RemoteFileItem) {
        guard let detectedFormat = AndroidPackageFormat.detect(fileExtension: item.fileExtension) else {
            showToast("不支持的安装包格式：\(item.name)")
            return
        }
        let preloaded = AndroidPackageInfo(
            fileURL: URL(fileURLWithPath: item.path),
            fileName: item.name,
            fileSizeBytes: UInt64(max(item.sizeBytes, 0)),
            format: detectedFormat,
            packageName: item.path,
            appName: (item.name as NSString).deletingPathExtension,
            warnings: ["此安装包已位于手机存储（\(item.path)），点击安装将直接调用手机端安装器，无需重复上传。"]
        )
        activeApkInstallTask?.cancel()
        activeApkInstallTask = nil
        apkInstallState = .idle
        apkInstallerContext = ApkInstallerContext(
            packageURL: URL(fileURLWithPath: item.path),
            preloadedInfo: preloaded,
            remoteFilePath: item.path,
            targetWindow: .fileBrowser
        )
    }

    public func dismissApkInstaller() {
        activeApkInstallTask?.cancel()
        activeApkInstallTask = nil
        if let transferId = activeApkUploadTransferId {
            activeApkUploadTransferId = nil
            cancelFileTransfer(transferId)
        }
        apkInstallerContext = nil
        apkInstallState = .idle
    }

    /// Executes the full wireless installation pipeline:
    /// 1. Local file: `FileSender.sendFile(remoteFileNameOverride: "@apk_stage/<name>")` -> wait for `0x13 FILE_TRANSFER_ACK`
    /// 2. Remote `0x34 APK_INSTALL_REQUEST` -> stream `0x1D APK_INSTALL_STATUS` into `apkInstallState`.
    public func startApkInstallation(
        info: AndroidPackageInfo,
        replaceExisting: Bool,
        allowDowngrade: Bool
    ) {
        guard let context = apkInstallerContext else { return }
        guard isAnyChannelConnected else {
            apkInstallState = .failed(message: "未连接到 Android 设备，请先连接投屏或等待后台伴侣上线")
            return
        }
        guard let wireFormat = OpalCastApkFormat(rawValue: info.format.rawValue) else {
            apkInstallState = .failed(message: "不支持的安装包格式：\(info.format.rawValue)")
            return
        }

        activeApkInstallTask?.cancel()
        let installLabel = info.appName.isEmpty ? info.fileName : info.appName

        activeApkInstallTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let remotePath: String
                if let existingRemotePath = context.remoteFilePath, !existingRemotePath.isEmpty {
                    remotePath = existingRemotePath
                } else {
                    let cleanName = info.fileName
                        .replacingOccurrences(of: "/", with: "_")
                        .replacingOccurrences(of: "\\", with: "_")
                    let stagedRemoteName = "@apk_stage/\(cleanName)"
                    self.apkInstallState = .uploading(progress: 0.0, message: "正在上传 \(info.fileName)…")
                    remotePath = try await self.uploadFileAwaitingAck(
                        url: context.packageURL,
                        remoteFileNameOverride: stagedRemoteName,
                        onProgress: { [weak self] progress in
                            guard let self else { return }
                            switch progress.status {
                            case .sending:
                                let pct = Int((progress.fractionCompleted * 100).rounded())
                                self.apkInstallState = .uploading(
                                    progress: progress.fractionCompleted,
                                    message: "正在上传 \(info.fileName)（\(pct)%）"
                                )
                            case .awaitingAck:
                                self.apkInstallState = .uploading(
                                    progress: 0.99,
                                    message: "上传完毕，等待手机端校验落盘…"
                                )
                            default:
                                break
                            }
                        }
                    )
                }

                try Task.checkCancellation()
                self.apkInstallState = .installing(progress: 0.05, message: "已提交安装请求，等待手机端响应…")

                let statusStream = self.fileBrowserClient.requestApkInstall(
                    filePath: remotePath,
                    format: wireFormat,
                    replaceExisting: replaceExisting,
                    allowDowngrade: allowDowngrade
                )

                var reachedTerminal = false
                for try await status in statusStream {
                    try Task.checkCancellation()
                    let fraction = Double(status.progress) / 100.0
                    let detail = status.message.isEmpty ? nil : status.message
                    switch status.knownStage {
                    case .uploading:
                        self.apkInstallState = .uploading(progress: fraction, message: detail)
                    case .extracting:
                        self.apkInstallState = .extracting(progress: fraction, message: detail)
                    case .installing, nil:
                        self.apkInstallState = .installing(progress: fraction, message: detail)
                    case .succeeded:
                        reachedTerminal = true
                        let msg = detail ?? "已成功安装 \(status.packageName.isEmpty ? installLabel : status.packageName)"
                        self.apkInstallState = .succeeded(message: msg)
                        self.showToast("「\(installLabel)」已安装至「\(self.activeChannelDeviceName)」")
                    case .failed:
                        reachedTerminal = true
                        self.apkInstallState = .failed(message: detail ?? "手机端安装包安装失败")
                    }
                }

                if !reachedTerminal && !Task.isCancelled {
                    self.apkInstallState = .succeeded(message: "安装流程已完成")
                }
            } catch is CancellationError {
                self.apkInstallState = .idle
            } catch {
                OpalDeskLog.file.error("APK installation failed for \(info.fileName, privacy: .public): \(error.localizedDescription, privacy: .public)")
                self.apkInstallState = .failed(message: error.localizedDescription)
            }
        }
    }

    private func uploadFileAwaitingAck(
        url: URL,
        remoteFileNameOverride: String,
        trackAsApkUpload: Bool = true,
        onProgress: @escaping @MainActor (FileTransferProgress) -> Void
    ) async throws -> String {
        let startedTransferIdLock = OSAllocatedUnfairLock<UInt32?>(initialState: nil)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
                do {
                    let transferId = try fileSender.sendFile(
                        at: url,
                        remoteFileNameOverride: remoteFileNameOverride,
                        sendFrame: { [weak self] frame in
                            guard let self else { return }
                            _ = await self.sendPacedFileFrameOnActiveChannel(frame)
                        },
                        onProgress: { [weak self] progress in
                            Task { @MainActor [weak self] in
                                self?.handleFileSenderProgress(progress)
                            }
                        }
                    )
                    startedTransferIdLock.withLock { $0 = transferId }
                    if trackAsApkUpload {
                        activeApkUploadTransferId = transferId
                    }
                    pendingFileTransferAckWaiters[transferId] = continuation
                    pendingFileTransferProgressHandlers[transferId] = onProgress
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        } onCancel: { [weak self] in
            let capturedId = startedTransferIdLock.withLock { $0 }
            Task { @MainActor [weak self] in
                guard let self else { return }
                let tid = capturedId ?? (trackAsApkUpload ? self.activeApkUploadTransferId : nil)
                if let tid {
                    self.cancelFileTransfer(tid)
                }
            }
        }
    }

    // MARK: - Unified FileSender Progress & `0x13 FILE_TRANSFER_ACK` Routing

    func handleInboundFileTransferAck(_ ack: FileTransferAckPayload) {
        fileSender.handleAck(ack) { [weak self] progress in
            Task { @MainActor [weak self] in
                self?.handleFileSenderProgress(progress)
            }
        }
    }

    func handleFileSenderProgress(_ progress: FileTransferProgress) {
        let tid = progress.transferId

        // 1. Dedicated APK staging upload waiter
        if let handler = pendingFileTransferProgressHandlers[tid] {
            handler(progress)
            switch progress.status {
            case .completed(let remotePath):
                pendingFileTransferProgressHandlers.removeValue(forKey: tid)
                if activeApkUploadTransferId == tid { activeApkUploadTransferId = nil }
                if let cont = pendingFileTransferAckWaiters.removeValue(forKey: tid) {
                    cont.resume(returning: remotePath)
                }
            case .failed(let reason):
                pendingFileTransferProgressHandlers.removeValue(forKey: tid)
                if activeApkUploadTransferId == tid { activeApkUploadTransferId = nil }
                if let cont = pendingFileTransferAckWaiters.removeValue(forKey: tid) {
                    cont.resume(throwing: NSError(
                        domain: "OpalDeskApkUpload",
                        code: -1,
                        userInfo: [NSLocalizedDescriptionKey: reason.isEmpty ? "安装包上传失败" : reason]
                    ))
                }
            case .cancelled:
                pendingFileTransferProgressHandlers.removeValue(forKey: tid)
                if activeApkUploadTransferId == tid { activeApkUploadTransferId = nil }
                if let cont = pendingFileTransferAckWaiters.removeValue(forKey: tid) {
                    cont.resume(throwing: CancellationError())
                }
            case .sending, .awaitingAck:
                break
            }
            return
        }

        // 2. File browser upload queue entry
        if let mapped = fileBrowserUploadTransferMap[tid] {
            updateFileBrowserUploadProgress(itemId: mapped.transferItemId, progress: progress)
            if case .completed = progress.status {
                fileBrowserUploadTransferMap.removeValue(forKey: tid)
                if FileBrowserPresentation.normalizePath(fileBrowserCurrentPath)
                    == FileBrowserPresentation.normalizePath(mapped.remoteDirectory)
                {
                    refreshFileBrowser(path: fileBrowserCurrentPath)
                }
            } else if case .failed = progress.status {
                fileBrowserUploadTransferMap.removeValue(forKey: tid)
            } else if case .cancelled = progress.status {
                fileBrowserUploadTransferMap.removeValue(forKey: tid)
            }
            return
        }

        // 3. Standard canvas / Finder file drop toast stack
        upsertTransferProgress(progress)
    }

    func cancelPendingFileTransferWaiters(reason: String) {
        let waiters = pendingFileTransferAckWaiters
        pendingFileTransferAckWaiters.removeAll()
        pendingFileTransferProgressHandlers.removeAll()
        activeApkUploadTransferId = nil
        let err = NSError(domain: "OpalDeskFileTransfer", code: -2, userInfo: [NSLocalizedDescriptionKey: reason])
        for cont in waiters.values {
            cont.resume(throwing: err)
        }
    }

    // MARK: - Bidirectional Remote File Browser & Storage Analyzer

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
            let msg = "尚未连接到 Android 设备，无法挂载到访达"
            fileBrowserError = msg
            showToast(msg)
            return
        }

        let targetDeviceName = activeChannelDeviceName
        let browserClient = fileBrowserClient
        let backend = CompanionRemoteFileSystemBackend(browserClient: browserClient) { [weak self] localURL, toRemoteDirectory, remoteFileName in
            guard let self else { throw RemoteFileSystemError.ioError("应用会话已关闭") }
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
                    "已在访达中挂载「\(targetDeviceName)」",
                    durationSeconds: 5.0,
                    actionTitle: "打开访达",
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
            showToast("已卸载访达挂载卷")
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
            throw RemoteFileBrowserError.localFileError("文件夹暂不支持快速查看")
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
                self.showToast("快速查看失败：\(error.localizedDescription)")
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

        guard stats.count > ToolsTuning.maxQuickLookCacheEntries || totalBytes > ToolsTuning.maxQuickLookCacheBytes else {
            return
        }

        let sortedOldestFirst = stats.sorted { $0.modifiedAt < $1.modifiedAt }
        var remainingCount = stats.count
        var remainingBytes = totalBytes
        for stat in sortedOldestFirst {
            if stat.url.lastPathComponent == keepingDirName { continue }
            if remainingCount <= ToolsTuning.maxQuickLookCacheEntries && remainingBytes <= ToolsTuning.maxQuickLookCacheBytes {
                break
            }
            try? fm.removeItem(at: stat.url)
            remainingCount -= 1
            remainingBytes -= stat.bytes
        }
    }

    public func navigateFileBrowser(to path: String) {
        let normalized = FileBrowserPresentation.normalizePath(path)
        fileBrowserCurrentPath = normalized
        refreshFileBrowser(path: normalized)
    }

    public func refreshFileBrowser(path: String) {
        let normalized = FileBrowserPresentation.normalizePath(path)
        guard isAnyChannelConnected else {
            fileBrowserError = "尚未连接到 Android 设备"
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
            fileBrowserError = "文件夹名称无效：\(name)"
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.fileBrowserClient.makeDirectory(path: targetPath)
                self.refreshFileBrowser(path: parentPath)
                self.showToast("已新建文件夹「\(name)」")
            } catch {
                self.fileBrowserError = error.localizedDescription
            }
        }
    }

    public func renameRemoteItem(_ item: RemoteFileItem, newName: String) {
        guard let parent = FileBrowserPresentation.parentPath(of: item.path),
              let newPath = FileBrowserPresentation.joining(base: parent, name: newName)
        else {
            fileBrowserError = "无效的新文件名：\(newName)"
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.fileBrowserClient.rename(from: item.path, to: newPath)
                self.refreshFileBrowser(path: self.fileBrowserCurrentPath)
                self.showToast("已重命名为「\(newName)」")
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
                self.showToast("已删除 \(deletedCount) 个手机文件")
            }
        }
    }

    public func uploadFilesToRemoteDirectory(_ localURLs: [URL], remoteDirectory: String) {
        guard isAnyChannelConnected else {
            fileBrowserError = "尚未连接到 Android 设备"
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
            panel.title = "保存手机文件到 Mac"
            panel.nameFieldStringValue = single.name
            panel.directoryURL = downloadsDir
            panel.canCreateDirectories = true
            guard panel.runModal() == .OK, let destination = panel.url else { return }
            targets.append((single, destination))
        } else {
            let panel = NSOpenPanel()
            panel.title = "选择保存 \(files.count) 个文件的目标文件夹"
            panel.prompt = "下载到此文件夹"
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
                    "已下载「\(remoteItem.name)」",
                    durationSeconds: 5.0,
                    actionTitle: "在 Finder 显示",
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
                if self.fileBrowserThumbnailCache.count >= ToolsTuning.maxThumbnailCacheEntries {
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
            fileBrowserStorageError = "尚未连接到 Android 设备"
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

    // MARK: - Private File Browser Transfer Helpers

    private func upsertFileBrowserTransfer(_ item: RemoteFileTransferItem) {
        if let idx = fileBrowserTransfers.firstIndex(where: { $0.id == item.id }) {
            fileBrowserTransfers[idx] = item
        } else {
            fileBrowserTransfers.insert(item, at: 0)
        }
    }

    private func updateFileBrowserTransfer(id: String, mutate: (inout RemoteFileTransferItem) -> Void) {
        guard let idx = fileBrowserTransfers.firstIndex(where: { $0.id == id }) else { return }
        mutate(&fileBrowserTransfers[idx])
    }

    private func updateFileBrowserUploadProgress(itemId: String, progress: FileTransferProgress) {
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
        case "images", "image", "photos": return "图片"
        case "videos", "video": return "视频"
        case "audio", "music": return "音频"
        case "documents", "docs", "document": return "文档"
        case "apks", "apk", "packages", "apps": return "安装包"
        case "archives", "archive", "zip": return "压缩包"
        case "others", "other": return "其他文件"
        default: return raw
        }
    }
}
