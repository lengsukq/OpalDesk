import AppKit
import Foundation
import OpalDeskCore
import UniformTypeIdentifiers
import os

// MARK: - Capture/Recording, Deep Links, Shortcuts, Wireless APK Installer & Finder Services

extension AppModel {
    private enum ToolsTuning {
        static let urlDedupWindowSeconds: TimeInterval = 0.45
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
                case .wiredUSB(let s, _), .wirelessADB(let s, _): return PopoutDeviceKey.adb(s)
                case .wirelessWiFi:
                    return conn.fingerprint.isEmpty ? nil : PopoutDeviceKey.fingerprint(conn.fingerprint)
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
            let devKey = target.certFingerprint.isEmpty ? nil : PopoutDeviceKey.fingerprint(target.certFingerprint)
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

    func uploadFileAwaitingAck(
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
}
