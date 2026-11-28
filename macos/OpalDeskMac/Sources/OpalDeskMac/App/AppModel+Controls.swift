import AppKit
import OpalDeskCore
import Foundation
import os

// MARK: - Popout Windows, Display Controls & File Transfers

extension AppModel {
    // MARK: - Window Layout & Input Engine Controls (`0x2B WINDOW_ACTION`)

    public func setAdaptiveSingle(enabled: Bool, defaultWidthDp: Int) {
        let clampedDp = DisplayPresets.clampSingleWidthDp(defaultWidthDp)
        preferences.adaptiveSingleEnabled = enabled
        preferences.defaultSingleWidthDp = clampedDp
        deviceState.adaptiveSingleEnabled = enabled
        deviceState.defaultSingleWidthDp = clampedDp
        streamBridge.sendWindowAction(.setAdaptiveSingle(enabled: enabled, defaultWidthDp: clampedDp), isAuthenticated: isAuthenticated)
    }

    public func setSingleWidthPreset(widthPct: Int, remember: Bool = true) {
        let clampedPct = DisplayPresets.clampSingleWidthPct(widthPct)
        streamBridge.sendWindowAction(
            .setSingleWidth(pkg: deviceState.topAppPackage, widthPct: clampedPct, remember: remember),
            isAuthenticated: isAuthenticated
        )
        showToast(OpalL10n.format("app.controls.width_fmt", clampedPct))
    }

    public func launchApp(_ app: RemoteAppInfo, mode: OpalCastLaunchMode = .adaptive, slot: Int = 0) {
        streamBridge.sendWindowAction(
            .launchApp(pkg: app.pkg, cls: app.cls, mode: mode, slot: slot, userId: app.userId),
            isAuthenticated: isAuthenticated
        )
    }

    public func setTilingTopology(_ topology: OpalCastTilingTopology, pkgs: [String] = [], ratios: [Int]? = nil) {
        streamBridge.sendWindowAction(.setTiling(topology: topology, pkgs: pkgs, ratios: ratios), isAuthenticated: isAuthenticated)
        showToast(topology == .exit ? OpalL10n.text("app.controls.exit_split") : OpalL10n.format("app.controls.switched_layout_fmt", topology.displayName))
    }

    public func reloadCurrentAppDPI() {
        let pkg = deviceState.topAppPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pkg.isEmpty else {
            showToast(OpalL10n.text("app.controls.no_dpi_app"))
            return
        }
        streamBridge.sendWindowAction(.reloadAppDpi(pkg: pkg), isAuthenticated: isAuthenticated)
        let name = deviceState.topAppLabel.isEmpty ? pkg : deviceState.topAppLabel
        showToast(OpalL10n.format("app.controls.reloading_dpi_fmt", name))
    }

    public func setInputBackend(_ backend: OpalCastInputBackend) {
        preferences.inputBackend = backend
        streamBridge.sendWindowAction(.setInputBackend(backend), isAuthenticated: isAuthenticated)
        if isAuthenticated { showToast(OpalL10n.format("app.controls.input_switched_fmt", backend.displayName)) }
    }

    // MARK: - Display & System Controls

    public func handleCanvasResize(viewSize: CGSize, backingScale: CGFloat) {
        guard viewSize.width >= Tuning.minCanvasDimension, viewSize.height >= Tuning.minCanvasDimension else { return }
        lastCanvasSize = viewSize
        lastBackingScale = backingScale

        guard isAuthenticated, selectedPreset == .matchWindow else { return }
        resizeDebounceTask?.cancel()
        resizeDebounceTask = Task { [weak self] in
            guard await sleepCancellable(nanoseconds: Tuning.resizeDebounceNs), let self else { return }
            let target = CoordinateMapper.aspectExactAdaptiveResolution(
                viewSize: viewSize,
                backingScale: backingScale,
                maxLongEdge: self.effectiveAdaptiveMaxLongEdge()
            )
            self.sendResizeIfNeeded(width: target.width, height: target.height, dpi: self.selectedDPI)
        }
    }

    public func selectResolutionPreset(_ preset: DisplayResolutionPreset) {
        selectedPreset = preset
        preferences.resolutionPreset = preset
        adaptMainWindowToPresetIfNeeded(preset)
        guard isAuthenticated else { return }
        let target = resolveCurrentTargetDimensions()
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: selectedDPI, force: true)
    }

    public func selectDPI(_ dpi: Int) {
        selectedDPI = dpi
        preferences.defaultDPI = dpi
        guard isAuthenticated else { return }
        let target = resolveCurrentTargetDimensions()
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: dpi, force: false)
    }

    public func applyTargetFPS(_ fps: Int) {
        preferences.targetFPS = fps
        guard isAuthenticated else { return }
        let target = resolveCurrentTargetDimensions()
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: selectedDPI, force: false)
    }

    public func toggleDisplayMode() {
        let nextMode: OpalCastDisplayMode = (displayMode == .dex) ? .mirror : .dex
        displayMode = nextMode
        streamBridge.sendSystemAction(.toggleDisplayMode, arg: Int32(nextMode.rawValue), isAuthenticated: isAuthenticated)
    }

    public func toggleBlackout() {
        isBlackoutActive.toggle()
        streamBridge.sendSystemAction(.toggleBlackout, arg: isBlackoutActive ? 1 : 0, isAuthenticated: isAuthenticated)
    }

    public func toggleAudio() {
        isAudioMuted.toggle()
        audioPlayer.setMuted(isAudioMuted)
        streamBridge.sendSystemAction(.setAudioEnabled, arg: isAudioMuted ? 0 : 1, isAuthenticated: isAuthenticated)
    }

    public func toggleMicrophone() {
        guard isAuthenticated else {
            showToast(OpalL10n.text("app.controls.mic_wait_connect"))
            return
        }
        if isMicEnabled {
            disableMicrophone(notifyServer: true)
            return
        }
        Task { [weak self] in
            guard let self else { return }
            let granted = await MicrophoneStreamCapturer.requestPermission()
            guard granted else {
                self.showToast(OpalL10n.text("app.controls.mic_perm"))
                return
            }
            guard self.isAuthenticated else { return }
            do {
                let activeSession = self.session
                try self.micCapturer.start(sampleRate: MicrophoneStreamCapturer.defaultSampleRate) { pcmData in
                    activeSession.send(.micFrame(pcmData))
                }
                self.isMicEnabled = true
                if let cfgFrame = try? OpalCastFrame.micConfig(MicConfigPayload(enabled: true)) {
                    self.session.send(cfgFrame)
                }
            } catch {
                self.isMicEnabled = false
                self.showToast(OpalL10n.format("app.controls.mic_err_fmt", error.localizedDescription))
            }
        }
    }

    public func disableMicrophone(notifyServer: Bool = true) {
        micCapturer.stop()
        let wasEnabled = isMicEnabled
        isMicEnabled = false
        if notifyServer && isAuthenticated && wasEnabled {
            if let cfgFrame = try? OpalCastFrame.micConfig(MicConfigPayload(enabled: false)) {
                session.send(cfgFrame)
            }
        }
    }

    public func toggleCamera() {
        guard isAuthenticated else {
            showToast(OpalL10n.text("app.controls.cam_wait_connect"))
            return
        }
        if isCameraEnabled {
            disableCamera(notifyServer: true)
            return
        }
        Task { [weak self] in
            guard let self else { return }
            let granted = await CameraStreamCapturer.requestPermission()
            guard granted else {
                self.showToast(OpalL10n.text("app.controls.cam_perm"))
                return
            }
            guard self.isAuthenticated else { return }
            do {
                let activeSession = self.session
                try self.cameraCapturer.start(fps: CameraStreamCapturer.defaultFps) { jpegData in
                    activeSession.send(.cameraFrame(jpegData))
                }
                self.isCameraEnabled = true
                if let cfgFrame = try? OpalCastFrame.cameraConfig(CameraConfigPayload(enabled: true)) {
                    self.session.send(cfgFrame)
                }
            } catch {
                self.isCameraEnabled = false
                self.showToast(OpalL10n.format("app.controls.cam_err_fmt", error.localizedDescription))
            }
        }
    }

    public func disableCamera(notifyServer: Bool = true) {
        cameraCapturer.stop()
        let wasEnabled = isCameraEnabled
        isCameraEnabled = false
        if notifyServer && isAuthenticated && wasEnabled {
            if let cfgFrame = try? OpalCastFrame.cameraConfig(CameraConfigPayload(enabled: false)) {
                session.send(cfgFrame)
            }
        }
    }

    public func rotateClockwise() { streamBridge.sendSystemAction(.rotateCW, isAuthenticated: isAuthenticated) }
    public func sendAndroidHome() { streamBridge.sendSystemAction(.home, isAuthenticated: isAuthenticated) }
    public func sendAndroidBack() { streamBridge.sendSystemAction(.back, isAuthenticated: isAuthenticated) }
    public func sendAndroidRecents() { streamBridge.sendSystemAction(.recents, isAuthenticated: isAuthenticated) }
    public func requestIDR() { streamBridge.sendRequestIDR() }

    // MARK: - File Transfers

    /// Smart drop handler: a single `.apk`/`.apks`/`.apkm`/`.xapk` launches `ApkInstallerSheet`;
    /// multiple files or non-APK files stream directly to Android over any active channel.
    public func handleDroppedFiles(_ urls: [URL]) {
        let validFiles = FileBrowserPresentation.filterUploadableLocalFiles(urls)
        guard !validFiles.isEmpty else { return }
        if validFiles.count == 1,
           let single = validFiles.first,
           FileBrowserPresentation.isAndroidPackageFile(single)
        {
            presentApkInstaller(forLocalURL: single, targetWindow: .main)
            return
        }
        sendFiles(validFiles)
    }

    public func sendFiles(_ urls: [URL], remoteDirectory: String? = nil) {
        guard isAnyChannelConnected else {
            let msg = OpalL10n.text("app.controls.file_disconnected")
            bannerError = msg
            showToast(msg)
            return
        }
        for url in urls {
            let overrideName: String?
            if let remoteDirectory, !remoteDirectory.isEmpty {
                overrideName = FileBrowserPresentation.joining(base: remoteDirectory, name: url.lastPathComponent)
            } else {
                overrideName = nil
            }
            do {
                try fileSender.sendFile(
                    at: url,
                    remoteFileNameOverride: overrideName,
                    sendFrame: { [weak self] frame in
                        guard let self else { return }
                        _ = await self.sendPacedFileFrameOnActiveChannel(frame)
                    },
                    onProgress: { [weak self] progress in
                        Task { @MainActor [weak self] in self?.handleFileSenderProgress(progress) }
                    }
                )
            } catch {
                OpalDeskLog.file.error("Failed to start file transfer for \(url.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)")
                bannerError = error.localizedDescription
            }
        }
    }

    public func cancelFileTransfer(_ transferId: UInt32) {
        fileSender.cancel(
            transferId: transferId,
            sendFrame: { [weak self] frame in
                _ = self?.sendControlFrameOnActiveChannel(frame)
            },
            onProgress: { [weak self] progress in
                Task { @MainActor [weak self] in self?.handleFileSenderProgress(progress) }
            }
        )
    }

    public func dismissTransfer(_ transferId: UInt32) {
        activeTransfers.removeAll { $0.transferId == transferId }
    }

    // MARK: - Internal Geometry & Sync Helpers

    func syncInitialDesktopPreferences() {
        if preferences.inputBackend != .auto {
            streamBridge.sendWindowAction(.setInputBackend(preferences.inputBackend), isAuthenticated: isAuthenticated)
        }
    }

    func syncCanvasSizeAfterAuth() {
        guard isAuthenticated else { return }
        if selectedPreset != .matchWindow {
            adaptMainWindowToPresetIfNeeded(selectedPreset)
            return
        }
        guard lastCanvasSize.width >= Tuning.minCanvasDimension, lastCanvasSize.height >= Tuning.minCanvasDimension else { return }
        let target = CoordinateMapper.aspectExactAdaptiveResolution(
            viewSize: lastCanvasSize,
            backingScale: lastBackingScale,
            maxLongEdge: effectiveAdaptiveMaxLongEdge()
        )
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: selectedDPI, force: false)
    }

    func adaptMainWindowToPresetIfNeeded(_ preset: DisplayResolutionPreset) {
        guard let fixed = preset.fixedSize else { return }
        guard let window = resolvedMainDisplayWindow(), !window.styleMask.contains(.fullScreen) else { return }

        let visible = (window.screen ?? NSScreen.main)?.visibleFrame
            ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let edgePadding: CGFloat = 24
        let currentFrame = window.frame
        let currentContentRect = window.contentRect(forFrameRect: currentFrame)
        let titlebarHeight = max(currentFrame.height - currentContentRect.height, 0)

        let minContentSize = CGSize(
            width: max(window.contentMinSize.width, 680),
            height: max(window.contentMinSize.height, 480)
        )
        let maxContentSize = CGSize(
            width: max(visible.width - edgePadding * 2, minContentSize.width),
            height: max(visible.height - edgePadding * 2 - titlebarHeight, minContentSize.height)
        )
        let dockTrayHeight = effectiveDockTrayHeight
        let targetContentSize = CoordinateMapper.adaptedWindowContentSize(
            currentCanvasSize: lastCanvasSize,
            targetVideoWidth: fixed.width,
            targetVideoHeight: fixed.height,
            taskbarHeight: dockTrayHeight,
            maxContentSize: maxContentSize,
            minContentSize: minContentSize
        )
        let targetFrameSize = window.frameRect(
            forContentRect: NSRect(origin: .zero, size: targetContentSize)
        ).size
        guard abs(currentFrame.width - targetFrameSize.width) > 2
            || abs(currentFrame.height - targetFrameSize.height) > 2 else {
            return
        }

        var newOrigin = CGPoint(
            x: currentFrame.midX - targetFrameSize.width * 0.5,
            y: currentFrame.midY - targetFrameSize.height * 0.5
        )
        newOrigin.x = min(max(newOrigin.x, visible.minX + edgePadding), visible.maxX - targetFrameSize.width - edgePadding)
        newOrigin.y = min(max(newOrigin.y, visible.minY + edgePadding), visible.maxY - targetFrameSize.height - edgePadding)

        window.setFrame(NSRect(origin: newOrigin, size: targetFrameSize), display: true, animate: true)
        lastCanvasSize = CGSize(
            width: targetContentSize.width,
            height: max(targetContentSize.height - dockTrayHeight, 1)
        )
        lastBackingScale = window.backingScaleFactor
    }

    var effectiveDockTrayHeight: CGFloat { 0 }

    private func resolvedMainDisplayWindow() -> NSWindow? {
        if let mainDisplayWindow { return mainDisplayWindow }
        return NSApplication.shared.windows.first { win in
            win.isVisible && (win.identifier?.rawValue.hasPrefix(WindowID.main) == true)
        }
    }

    func effectiveAdaptiveMaxLongEdge() -> Int {
        switch activeConnection?.transport {
        case .wiredUSB:
            return Tuning.wiredUsbMaxLongEdge
        case .wirelessADB:
            return Tuning.wirelessAdbMaxLongEdge
        case .wirelessWiFi, nil:
            return Tuning.defaultMaxLongEdge
        }
    }

    func effectiveBitrateKbps() -> Int {
        switch activeConnection?.transport {
        case .wiredUSB:
            return preferences.bitrateKbps
        case .wirelessADB:
            return min(preferences.bitrateKbps, Tuning.wirelessAdbMaxBitrateKbps)
        case .wirelessWiFi, nil:
            return min(preferences.bitrateKbps, Tuning.wifiMaxBitrateKbps)
        }
    }

    func resolveCurrentTargetDimensions() -> (width: Int, height: Int) {
        if let fixed = selectedPreset.fixedSize { return fixed }
        return CoordinateMapper.aspectExactAdaptiveResolution(
            viewSize: lastCanvasSize,
            backingScale: lastBackingScale,
            maxLongEdge: effectiveAdaptiveMaxLongEdge()
        )
    }

    func sendResizeIfNeeded(width: Int, height: Int, dpi: Int, force: Bool = false) {
        let alignedW = CoordinateMapper.alignTo8(width)
        let alignedH = CoordinateMapper.alignTo8(height)
        let fps = preferences.targetFPS
        if !force, let last = lastRequestedConfig,
           last.width == alignedW && last.height == alignedH && last.dpi == dpi && last.fps == fps
        {
            return
        }
        lastRequestedConfig = (alignedW, alignedH, dpi, fps)
        streamBridge.sendResize(width: alignedW, height: alignedH, dpi: dpi, fps: fps, bitrateKbps: effectiveBitrateKbps())
    }

    func upsertTransferProgress(_ progress: FileTransferProgress) {
        if let idx = activeTransfers.firstIndex(where: { $0.transferId == progress.transferId }) {
            activeTransfers[idx] = progress
        } else {
            activeTransfers.insert(progress, at: 0)
        }
        if activeTransfers.count > Tuning.maxVisibleTransfers {
            activeTransfers = Array(activeTransfers.prefix(Tuning.maxVisibleTransfers))
        }
    }
}
