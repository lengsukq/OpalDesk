import OpalDeskCore
import Foundation
import os

// MARK: - Session Event Loop, Control Frame Dispatch & Wi-Fi Upgrade Handoff

extension AppModel {
    func handleSessionEvent(_ event: OpalCastSessionEvent) {
        switch event {
        case .stateChanged(let newState):
            sessionState = newState
            switch newState {
            case .connected:
                disconnectReason = nil
                sendHelloHandshake()
            case .disconnected:
                guard !wifiUpgrade.isUpgradingToWifiTls else { return }
                let hadConn = activeConnection
                let wasExplicit = isUserInitiatedDisconnect
                isUserInitiatedDisconnect = false
                isConnectingFlow = false
                isAuthenticated = false
                notifyMainSessionAuthChanged(false)
                stopMainRecordingIfNeededOnDisconnect()
                micCapturer.stop()
                isMicEnabled = false
                cameraCapturer.stop()
                isCameraEnabled = false
                audioPlayer.stop()
                if let hadConn, !wasExplicit {
                    let msg = "与手机「\(hadConn.deviceName)」的投屏连接已断开"
                    bannerError = msg
                    disconnectReason = msg
                    showToast(msg)
                    Task { await teardownCurrentSession(sendRemoteDisconnect: false, clearBannerError: false) }
                }
            case .failed(let message):
                if wifiUpgrade.isUpgradingToWifiTls {
                    revertFailedWifiUpgradeSilently()
                    return
                }
                isUserInitiatedDisconnect = false
                isConnectingFlow = false
                isAuthenticated = false
                notifyMainSessionAuthChanged(false)
                stopMainRecordingIfNeededOnDisconnect()
                micCapturer.stop()
                isMicEnabled = false
                cameraCapturer.stop()
                isCameraEnabled = false
                disconnectReason = message
                if var ctx = pairingContext {
                    ctx.isSubmitting = false
                    ctx.errorMessage = message
                    pairingContext = ctx
                } else {
                    bannerError = "投屏连接中断：\(message)"
                    activeConnection = nil
                }
            case .reconnecting(let attempt):
                isAuthenticated = false
                notifyMainSessionAuthChanged(false)
                micCapturer.stop()
                isMicEnabled = false
                cameraCapturer.stop()
                isCameraEnabled = false
                if disconnectReason == nil { disconnectReason = "网络或手机守护进程无响应" }
                showToast("连接中断，正在尝试第 \(attempt) 次重连...", durationSeconds: 2.2)
            default:
                break
            }

        case .verifiedPeerFingerprint(let fullHex):
            activeConnection?.fingerprint = fullHex
        case .rttUpdated(let rtt):
            rttMs = rtt
        case .error(let sessionError):
            disconnectReason = sessionError.localizedDescription
            if !wifiUpgrade.isUpgradingToWifiTls, case .certificateFingerprintMismatch = sessionError {
                bannerError = sessionError.localizedDescription
            }
        case .frame(let frame):
            handleControlFrame(frame)
        }
    }

    private func sendHelloHandshake() {
        let initialSize = resolveCurrentTargetDimensions()
        lastRequestedConfig = (initialSize.width, initialSize.height, selectedDPI, preferences.targetFPS)
        streamBridge.sendHello(HelloPayload(
            clientName: StreamSessionBridge.localClientName,
            clientId: discovery.trustStore.clientId,
            preferredCodec: preferences.preferredCodec.preferredName,
            width: initialSize.width,
            height: initialSize.height,
            dpi: selectedDPI,
            fps: preferences.targetFPS,
            bitrateKbps: effectiveBitrateKbps(),
            displayMode: Int(displayMode.rawValue),
            audioEnabled: !isAudioMuted,
            authToken: pendingAuth.authToken,
            pinCode: "",
            transportHint: (activeConnection?.transport.transportHint ?? .usb).rawValue,
            sessionRole: OpalCastSessionRole.desktop.rawValue
        ))
    }

    /// Handles control-plane downlink frames on `@MainActor`.
    /// High-frequency media frames (`.videoConfig`, `.videoFrame`, `.audioConfig`, `.audioFrame`, `.pong`)
    /// are routed synchronously on `networkQueue` by `StreamSessionBridge.makeEventHandler`.
    private func handleControlFrame(_ frame: OpalCastFrame) {
        switch frame.type {
        case .deviceState:
            guard let state = StreamSessionBridge.decodePayload("DEVICE_STATE", logger: OpalDeskLog.app, {
                try DeviceStatePayload.decode(from: frame.payload)
            }) else { return }
            deviceState = state
            isBlackoutActive = state.blackout
            if let mode = OpalCastDisplayMode(rawValue: UInt8(clamping: state.displayMode)), mode != .unchanged {
                displayMode = mode
            }
            if !state.deviceName.isEmpty { activeConnection?.deviceName = state.deviceName }
            handleDeviceStateHostTopApp(state)

        case .clipboardPush:
            if (frame.flags & OpalCastProtocol.flagClipboardImagePng) != 0 {
                if let img = StreamSessionBridge.decodePayload("image CLIPBOARD_PUSH", logger: OpalDeskLog.clipboard, {
                    try ClipboardImagePushPayload.decode(from: frame.payload)
                }) {
                    companionManager.recordExternalClipboardDigest(ClipboardSyncManager.imageDigest(img.pngData))
                    clipboardSync.applyRemoteClipboardImage(seq: img.seq, pngData: img.pngData)
                }
            } else if let clip = StreamSessionBridge.decodePayload("text CLIPBOARD_PUSH", logger: OpalDeskLog.clipboard, {
                try ClipboardPushPayload.decode(from: frame.payload)
            }) {
                companionManager.recordExternalClipboardDigest(ClipboardSyncManager.textDigest(clip.text))
                clipboardSync.applyRemoteClipboard(seq: clip.seq, text: clip.text)
            }

        case .authResult:
            do {
                handleAuthResult(try AuthResultPayload.decode(from: frame.payload))
            } catch {
                OpalDeskLog.app.error("Failed to decode AUTH_RESULT: \(error.localizedDescription, privacy: .public)")
                bannerError = "认证响应解析失败：\(error.localizedDescription)"
            }

        case .fileTransferAck:
            guard let ack = StreamSessionBridge.decodePayload("FILE_TRANSFER_ACK", logger: OpalDeskLog.file, {
                try FileTransferAckPayload.decode(from: frame.payload)
            }) else { return }
            handleInboundFileTransferAck(ack)

        case .wifiUpgradeReady:
            if let ready = StreamSessionBridge.decodePayload("WIFI_UPGRADE_READY", logger: OpalDeskLog.app, {
                try WifiUpgradeReadyPayload.decode(from: frame.payload)
            }) {
                handleWifiUpgradeReady(ready)
            }

        case .appList:
            if let list = StreamSessionBridge.decodePayload("APP_LIST", logger: OpalDeskLog.app, {
                try AppListPayload.decode(from: frame.payload)
            }) {
                handleMainSessionAppList(list.apps)
            }

        case .micState:
            if let st = StreamSessionBridge.decodePayload("MIC_STATE", logger: OpalDeskLog.audio, {
                try MicStatePayload.decode(from: frame.payload)
            }) {
                if !st.active {
                    micCapturer.stop()
                    isMicEnabled = false
                } else {
                    isMicEnabled = true
                }
                if !st.message.isEmpty {
                    showToast(st.message)
                }
            }

        case .cameraState:
            if let st = StreamSessionBridge.decodePayload("CAMERA_STATE", logger: OpalDeskLog.video, {
                try CameraStatePayload.decode(from: frame.payload)
            }) {
                if !st.active {
                    cameraCapturer.stop()
                    isCameraEnabled = false
                } else {
                    isCameraEnabled = true
                }
                if !st.message.isEmpty {
                    showToast(st.message)
                }
            }

        case .notificationEvent:
            if let event = StreamSessionBridge.decodePayload("NOTIFICATION_EVENT", logger: OpalDeskLog.app, {
                try NotificationEventPayload.decode(from: frame.payload)
            }) {
                notificationBridge.handleEvent(event)
            }

        case .mediaState:
            if let media = StreamSessionBridge.decodePayload("MEDIA_STATE", logger: OpalDeskLog.app, {
                try MediaStatePayload.decode(from: frame.payload)
            }) {
                mediaBridge.applyRemoteMediaState(media)
            }

        case .fileOpResponse:
            if let response = StreamSessionBridge.decodePayload("FILE_OP_RESPONSE", logger: OpalDeskLog.file, {
                try FileOpResponsePayload.decode(from: frame.payload)
            }) {
                fileBrowserClient.handleFileOpResponse(response)
            }

        case .filePullChunk:
            if let chunk = StreamSessionBridge.decodePayload("FILE_PULL_CHUNK", logger: OpalDeskLog.file, {
                try FilePullChunkPayload.decode(from: frame.payload)
            }) {
                fileBrowserClient.handleFilePullChunk(chunk)
            }

        case .apkInstallStatus:
            if let status = StreamSessionBridge.decodePayload("APK_INSTALL_STATUS", logger: OpalDeskLog.file, {
                try ApkInstallStatusPayload.decode(from: frame.payload)
            }) {
                fileBrowserClient.handleApkInstallStatus(status)
            }

        default:
            break
        }
    }

    private func handleAuthResult(_ auth: AuthResultPayload) {
        isConnectingFlow = false
        guard auth.ok else {
            handleAuthFailure(auth)
            return
        }

        let wasInteractivePairing = pairingContext != nil
        isAuthenticated = true
        pairingContext = nil
        bannerError = nil
        popoutManager.updateCanReturnToDesktop(true)
        OpalDeskLog.info("session", "Authenticated desktop session with \(auth.deviceName.isEmpty ? pendingAuth.deviceName : auth.deviceName) permanent=\(auth.permanent)")

        let resolvedName = auth.deviceName.isEmpty ? pendingAuth.deviceName : auth.deviceName
        if !resolvedName.isEmpty { activeConnection?.deviceName = resolvedName }

        let resolvedFp = FingerprintUtil.normalize(
            session.verifiedPeerFingerprint ?? (auth.certFingerprint.isEmpty ? (activeConnection?.fingerprint ?? "") : auth.certFingerprint)
        )
        if !auth.authToken.isEmpty {
            discovery.upsertTrustedDevice(
                deviceName: resolvedName,
                model: pendingAuth.deviceModel,
                certFingerprint: resolvedFp,
                authToken: auth.authToken,
                lastHost: pendingAuth.hostForTrust,
                lastPort: pendingAuth.portForTrust
            )
            pendingAuth.authToken = auth.authToken
        }

        if wifiUpgrade.finalizeUpgradeIfInProgress(adbManager: discovery.adbManager) {
            activeAdbForward = nil
            showToast("已自动无缝切换至原生 Wi-Fi TLS 直连")
        } else {
            wifiUpgrade.requestUpgradeIfEligible(transport: activeConnection?.transport, session: session)
            if wasInteractivePairing {
                showToast(auth.permanent ? "已获手机端永久信任并建立连接" : "已获手机端本次临时允许")
            }
        }

        applyPostAuthBootstrap()
    }

    private func applyPostAuthBootstrap() {
        notifyMainSessionAuthChanged(true)
        syncInitialDesktopPreferences()
        syncCanvasSizeAfterAuth()
    }

    private func handleAuthFailure(_ auth: AuthResultPayload) {
        if wifiUpgrade.isUpgradingToWifiTls {
            revertFailedWifiUpgradeSilently()
            return
        }

        isAuthenticated = false
        let failureText = auth.message.isEmpty ? "配对请求被手机端拒绝或已超时，请重试" : auth.message

        if var ctx = pairingContext {
            ctx.isSubmitting = false
            ctx.errorMessage = failureText
            pairingContext = ctx
            session.disconnect()
        } else if let source = pendingAuth.pairingSource {
            let fp = session.verifiedPeerFingerprint ?? (activeConnection?.fingerprint ?? "")
            session.disconnect()
            activeConnection = nil
            pairingContext = PairingPromptContext(
                source: source,
                displayName: pendingAuth.deviceName,
                model: pendingAuth.deviceModel,
                expectedFingerprint: fp,
                errorMessage: "原有授权已失效或已在手机端撤销，请重新向手机发起配对请求"
            )
        } else {
            bannerError = failureText
            Task { await teardownCurrentSession(sendRemoteDisconnect: false) }
        }
    }

    private func handleWifiUpgradeReady(_ ready: WifiUpgradeReadyPayload) {
        wifiUpgrade.handleUpgradeReady(
            ready,
            currentConnection: activeConnection,
            fallbackDeviceName: pendingAuth.deviceName,
            fallbackModel: pendingAuth.deviceModel,
            isEligibleForHandoff: { [weak self] in
                guard let self, case .wirelessADB = self.activeConnection?.transport else { return false }
                return self.isAuthenticated
            },
            currentAdbForward: { [weak self] in self?.activeAdbForward },
            discovery: discovery,
            onStageHandoff: { [weak self] host, port, verifiedFp, authToken, tlsEndpoint in
                guard let self else { return }
                self.pendingAuth.authToken = authToken
                self.pendingAuth.hostForTrust = host
                self.pendingAuth.portForTrust = port
                self.activeConnection?.transport = .wirelessWiFi(displayTarget: "\(host):\(port)", host: host, port: port)
                self.activeConnection?.fingerprint = verifiedFp
                self.connectSession(to: tlsEndpoint)
                self.migratePopoutsAfterWifiUpgrade(endpoint: tlsEndpoint, authToken: authToken)
            }
        )
    }

    private func revertFailedWifiUpgradeSilently() {
        let (adbForward, adbTransport) = wifiUpgrade.consumeFallbackForSilentRevert()
        pendingAuth.authToken = ""
        pendingAuth.hostForTrust = nil
        pendingAuth.portForTrust = nil

        if let adbTransport { activeConnection?.transport = adbTransport }
        if let adbForward {
            activeAdbForward = adbForward
            connectSession(to: OpalCastEndpoint(
                target: .hostPort(host: "127.0.0.1", port: adbForward.localPort),
                security: .wiredLocalhostPlaintext,
                autoReconnect: true
            ))
        }
    }

    func teardownCurrentSession(sendRemoteDisconnect: Bool, clearBannerError: Bool = true) async {
        resizeDebounceTask?.cancel()
        if clearBannerError { bannerError = nil }

        if sendRemoteDisconnect && isAuthenticated {
            streamBridge.sendSystemAction(.disconnect, isAuthenticated: true)
            _ = await sleepCancellable(nanoseconds: Tuning.remoteDisconnectFlushNs)
        }

        stopMainRecordingIfNeededOnDisconnect()
        micCapturer.stop()
        isMicEnabled = false
        cameraCapturer.stop()
        isCameraEnabled = false
        audioPlayer.stop()
        videoRenderer.reset()
        activeSessionGeneration &+= 1
        session.disconnect()
        isUserInitiatedDisconnect = false

        var forwards = wifiUpgrade.reset()
        if let active = activeAdbForward {
            if popoutManager.hasActiveController(usingLocalPort: active.localPort) {
                popoutAdbForwards[active.serial] = active
            } else {
                forwards.append(active)
            }
            activeAdbForward = nil
        }
        for forward in Self.uniqueForwards(forwards) {
            if !popoutManager.hasActiveController(usingLocalPort: forward.localPort) {
                await discovery.adbManager.removeForward(forward)
            }
        }

        popoutManager.updateCanReturnToDesktop(false)
        cachedDesktopApps = []
        activeSessionEndpoint = nil
        isConnectingFlow = false
        isAuthenticated = false
        activeConnection = nil
        sessionState = .idle
        rttMs = 0
        videoMetrics = VideoRendererMetrics()
        deviceState = DeviceStatePayload()
        lastRequestedConfig = nil
        activeTransfers.removeAll()
        notifyMainSessionAuthChanged(false)
    }

    static func uniqueForwards(_ forwards: [AdbForwardHandle]) -> [AdbForwardHandle] {
        var result: [AdbForwardHandle] = []
        for forward in forwards where !result.contains(forward) { result.append(forward) }
        return result
    }

    /// Launches non-blocking detached `adb forward --remove` processes during app termination
    /// so `@MainActor` never blocks on a semaphore while dangling forwards are still cleaned up.
    static func detachAdbForwardRemoval(_ forwards: [AdbForwardHandle], adbPath: String?) {
        let unique = uniqueForwards(forwards)
        guard !unique.isEmpty, let adbPath else { return }
        let executableURL = URL(fileURLWithPath: adbPath)
        for forward in unique {
            let process = Process()
            process.executableURL = executableURL
            process.arguments = ["-s", forward.serial, "forward", "--remove", "tcp:\(forward.localPort)"]
            process.standardOutput = FileHandle.nullDevice
            process.standardError = FileHandle.nullDevice
            do {
                try process.run()
            } catch {
                OpalDeskLog.adb.warning(
                    "Failed to detach adb forward cleanup for tcp:\(forward.localPort): \(error.localizedDescription, privacy: .public)"
                )
            }
        }
    }
}
