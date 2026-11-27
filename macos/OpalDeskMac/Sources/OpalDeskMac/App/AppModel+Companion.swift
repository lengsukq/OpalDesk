import AppKit
import Foundation
import OpalDeskCore
import os

// MARK: - Background Companion, Notifications, Media Remote, Handoff & Dual-Channel Routing

extension AppModel {
    /// True whenever either the primary desktop cast session or the lightweight background `.companion`
    /// session is authenticated and ready to exchange OPD1 v1.2 control / file frames.
    public var isAnyChannelConnected: Bool {
        isAuthenticated || companionState.isConnected
    }

    /// Friendly display name of the currently active connected Android host (prefers main cast session, then companion).
    public var activeChannelDeviceName: String {
        if isAuthenticated, let name = activeConnection?.deviceName, !name.isEmpty {
            return name
        }
        if let target = companionTarget, !target.deviceName.isEmpty {
            return target.deviceName
        }
        if case let .connected(name) = companionState, !name.isEmpty {
            return name
        }
        return "Android 设备"
    }

    /// Configures `CompanionConnectionManager`, `NotificationBridgeManager`, `MediaRemoteBridge`,
    /// and `RemoteFileBrowserClient` with unified dual-channel routing (main cast session preferred,
    /// companion session fallback) and preference synchronization.
    func configureCompanionSubsystem() {
        let primarySession = session
        let authLock = mainSessionAuthLock
        let companion = companionManager

        // Thread-safe dual-channel control frame router: prefers active main desktop session, then companion.
        let controlRouter: @Sendable (OpalCastFrame) -> Bool = { frame in
            if authLock.withLock({ $0 }) {
                primarySession.send(frame, allowDropOnCongestion: false)
                return true
            }
            return companion.sendFrame(frame, allowDropOnCongestion: false)
        }

        companionManager.setExternalFrameRouter(controlRouter)
        fileBrowserClient.setFrameSender(controlRouter)

        notificationBridge.setCallbacks(NotificationBridgeManager.Callbacks(
            onNotificationsChanged: { [weak self] items in
                Task { @MainActor [weak self] in
                    self?.remoteNotifications = items
                }
            },
            onOpenApp: { [weak self] pkg, appLabel in
                Task { @MainActor [weak self] in
                    self?.openAppPopoutForRemotePackage(packageName: pkg, appLabel: appLabel)
                }
            },
            onDismissNotification: { [weak self] key in
                _ = self?.sendNotificationActionOnActiveChannel(.dismiss(key: key))
            },
            onReplyNotification: { [weak self] key, replyText in
                _ = self?.sendNotificationActionOnActiveChannel(.reply(key: key, text: replyText))
            },
            onOtpCopied: { [weak self] code, item in
                Task { @MainActor [weak self] in
                    self?.showToast("已复制「\(item.displayAppLabel)」验证码：\(code)")
                }
            }
        ))

        mediaBridge.setOnStateChanged { [weak self] media in
            Task { @MainActor [weak self] in
                self?.mediaState = media
            }
        }

        companionManager.setCallbacks(CompanionConnectionManager.Callbacks(
            onStateChanged: { [weak self] state in
                Task { @MainActor [weak self] in
                    self?.handleCompanionStateChanged(state)
                }
            },
            onDeviceStateUpdated: { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.companionTarget = self.companionManager.activeTarget
                }
            },
            onHostTopAppChanged: { [weak self] topApp in
                Task { @MainActor [weak self] in
                    self?.handleCompanionHostTopAppChanged(topApp)
                }
            },
            onNotificationEvent: nil,
            onMediaStateChanged: nil,
            onAppListReceived: { [weak self] apps in
                Task { @MainActor [weak self] in
                    self?.cachedCompanionApps = apps
                }
            },
            onClipboardTextReceived: { [weak self] seq, text in
                Task { @MainActor [weak self] in
                    guard let self, !self.isAuthenticated else { return }
                    self.clipboardSync.applyRemoteClipboard(seq: seq, text: text)
                }
            },
            onClipboardImageReceived: { [weak self] seq, pngData in
                Task { @MainActor [weak self] in
                    guard let self, !self.isAuthenticated else { return }
                    self.clipboardSync.applyRemoteClipboardImage(seq: seq, pngData: pngData)
                }
            },
            onFileTransferAck: { [weak self] ack in
                Task { @MainActor [weak self] in
                    self?.handleInboundFileTransferAck(ack)
                }
            },
            onApkInstallStatus: nil,
            onError: { [weak self] message in
                Task { @MainActor [weak self] in
                    OpalDeskLog.warn("companion", "Companion error: \(message)")
                    self?.companionTarget = self?.companionManager.activeTarget
                }
            }
        ))

        applyCompanionPreferences()
    }

    // MARK: - Dual-Channel Frame Senders

    /// Sends a control frame (`0x31`–`0x34`) over the highest-priority active channel:
    /// authenticated main cast session first, authenticated companion session second.
    @discardableResult
    public nonisolated func sendControlFrameOnActiveChannel(_ frame: OpalCastFrame) -> Bool {
        if mainSessionAuthLock.withLock({ $0 }) {
            session.send(frame, allowDropOnCongestion: false)
            return true
        }
        return companionManager.sendFrame(frame, allowDropOnCongestion: false)
    }

    /// Sends a paced file-transfer chunk (`0x28`) over the highest-priority active channel.
    @discardableResult
    public nonisolated func sendPacedFileFrameOnActiveChannel(_ frame: OpalCastFrame) async -> Bool {
        if mainSessionAuthLock.withLock({ $0 }) {
            await session.sendPaced(frame)
            return true
        }
        return await companionManager.sendFileTransferFrame(frame)
    }

    @discardableResult
    public nonisolated func sendNotificationActionOnActiveChannel(_ payload: NotificationActionPayload) -> Bool {
        do {
            return sendControlFrameOnActiveChannel(try OpalCastFrame.notificationAction(payload))
        } catch {
            OpalDeskLog.app.error("Failed to encode NOTIFICATION_ACTION: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    @discardableResult
    public nonisolated func sendMediaActionOnActiveChannel(_ payload: MediaActionPayload) -> Bool {
        do {
            return sendControlFrameOnActiveChannel(try OpalCastFrame.mediaAction(payload))
        } catch {
            OpalDeskLog.app.error("Failed to encode MEDIA_ACTION: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    // MARK: - Preferences Synchronization

    public func applyCompanionPreferences() {
        companionManager.clipboardSyncEnabled = preferences.clipboardSyncEnabled
        companionManager.setAutoConnectEnabled(preferences.companionAutoConnectEnabled)
        notificationBridge.isEnabled = preferences.notificationsSyncEnabled
        notificationBridge.autoCopyOtpToClipboard = preferences.autoCopyOtpEnabled
        mediaBridge.isEnabled = preferences.mediaRemoteControlEnabled
        popoutManager.updateEdgeNavigationGestures(preferences.enableEdgeNavigationGestures)

        if preferences.notificationsSyncEnabled && isAnyChannelConnected {
            notificationBridge.start()
            Task { [notificationBridge] in
                _ = await notificationBridge.requestAuthorization()
            }
        }
        if !preferences.companionAutoConnectEnabled && companionState.isActiveOrAttempting {
            companionManager.disconnect()
        }
        updateClipboardSyncLifecycle()
    }

    // MARK: - Explicit Companion Controls

    public func connectCompanion(to device: DiscoveredBonjourDevice) {
        if companionManager.connect(to: device) {
            companionTarget = companionManager.activeTarget
        } else {
            showToast("「\(device.displayName)」尚未建立永久信任，请先完成一次配对")
        }
    }

    public func disconnectCompanion() {
        companionManager.disconnect()
    }

    public func reconnectCompanionIfAvailable() {
        companionManager.setAutoConnectEnabled(true)
        companionManager.updateDiscoveredDevices(discovery.bonjourDevices)
    }

    // MARK: - Main Session <-> Companion Coordination

    func notifyMainSessionAuthChanged(_ authenticated: Bool) {
        mainSessionAuthLock.withLock { $0 = authenticated }
        companionManager.setMainSessionActive(authenticated)

        if authenticated {
            ensureSharedBridgesStarted()
            _ = sendNotificationActionOnActiveChannel(.sync())
            updateClipboardSyncLifecycle()
        } else {
            updateClipboardSyncLifecycle()
            if !companionManager.isAuthenticated {
                resetSharedCompanionPayloads(reason: "主投屏会话已断开")
            } else {
                // Restore companion's reported host top app when main session ends.
                hostTopApp = companionManager.hostTopApp
            }
        }
    }

    func updateClipboardSyncLifecycle() {
        clipboardSync.isEnabled = preferences.clipboardSyncEnabled
        let shouldRun = preferences.clipboardSyncEnabled && isAnyChannelConnected

        guard shouldRun else {
            if isClipboardSyncRunning {
                clipboardSync.stop()
                isClipboardSyncRunning = false
            }
            return
        }

        guard !isClipboardSyncRunning else { return }
        isClipboardSyncRunning = true
        clipboardSync.start(
            onLocalChange: { [weak self] seq, text in
                guard let self else { return }
                if self.isAuthenticated {
                    self.companionManager.recordExternalClipboardDigest(ClipboardSyncManager.textDigest(text))
                    self.session.send(.inputText(InputTextPayload(mode: .setClipboardOnly, seq: seq, text: text)))
                } else if self.companionManager.isAuthenticated {
                    _ = self.companionManager.sendClipboardText(seq: seq, text: text)
                }
            },
            onLocalImageChange: { [weak self] seq, pngData in
                guard let self else { return }
                if self.isAuthenticated {
                    self.companionManager.recordExternalClipboardDigest(ClipboardSyncManager.imageDigest(pngData))
                    let payload = InputImagePayload(mode: .setClipboardImageOnly, seq: seq, pngData: pngData)
                    do {
                        self.session.send(try OpalCastFrame.inputImage(payload))
                    } catch {
                        OpalDeskLog.clipboard.error("Failed to encode clipboard image frame: \(error.localizedDescription, privacy: .public)")
                    }
                } else if self.companionManager.isAuthenticated {
                    _ = self.companionManager.sendClipboardImage(seq: seq, pngData: pngData)
                }
            }
        )
    }

    // MARK: - Notification, Media & Handoff User Actions

    public func copyNotificationOtp(_ item: RemoteNotificationItem) {
        guard !item.otpCode.isEmpty else { return }
        notificationBridge.handleLocalAction(.copyOtp(key: item.key, otpCode: item.otpCode))
    }

    public func openNotificationApp(_ item: RemoteNotificationItem) {
        guard !item.pkg.isEmpty else { return }
        openAppPopoutForRemotePackage(packageName: item.pkg, appLabel: item.displayAppLabel)
    }

    public func dismissRemoteNotification(_ item: RemoteNotificationItem) {
        notificationBridge.handleLocalAction(.dismissed(key: item.key))
    }

    public func replyToRemoteNotification(_ item: RemoteNotificationItem, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        notificationBridge.handleLocalAction(.inlineReply(key: item.key, pkg: item.pkg, replyText: trimmed))
        showToast("已回复「\(item.displayAppLabel)」")
    }

    public func clearAllRemoteNotifications() {
        _ = sendNotificationActionOnActiveChannel(.clearAll())
        notificationBridge.clearLocalState(removeDelivered: true)
    }

    public func sendMediaAction(_ action: MediaActionPayload) {
        mediaBridge.sendAction(action)
    }

    /// Launches the Android phone's current Display 0 foreground app inside a standalone macOS floating popout window.
    public func handoffHostTopApp() {
        guard !hostTopApp.isEmpty else {
            showToast("当前未检测到手机前台应用")
            return
        }
        openAppPopoutForRemotePackage(packageName: hostTopApp.packageName, appLabel: hostTopApp.displayLabel)
    }

    /// Resolves an online Android target (preferring active main cast session, then active companion,
    /// then USB/Bonjour devices) and opens a standalone floating popout window for `packageName`.
    public func openAppPopoutForRemotePackage(
        packageName: String,
        activityClass: String = "",
        appLabel: String = "",
        preferredDeviceKey: String? = nil,
        preferredDeviceName: String? = nil
    ) {
        let cleanPkg = packageName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPkg.isEmpty else { return }
        let displayLabel = appLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? cleanPkg : appLabel

        if let existing = popoutManager.existingController(for: cleanPkg) {
            popoutManager.focusWindow(id: existing.id)
            return
        }

        if popoutManager.controllers.count >= PopoutWindowManager.maxConcurrentWindows {
            let msg = "最多同时打开 \(PopoutWindowManager.maxConcurrentWindows) 个悬浮小窗，请先关闭一个已有小窗"
            bannerError = msg
            showToast(msg)
            return
        }

        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.resolveAndOpenAppPopout(
                packageName: cleanPkg,
                activityClass: activityClass,
                appLabel: displayLabel,
                preferredDeviceKey: preferredDeviceKey,
                preferredDeviceName: preferredDeviceName,
                waitForDiscoveryIfNeeded: false
            )
        }
    }

    /// Sends plain text (e.g. from Finder Services) straight to Android's clipboard over any active channel.
    public func sendPlainTextToRemoteClipboard(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let seq = nextCompanionClipboardSeq
        nextCompanionClipboardSeq += 1

        if isAuthenticated {
            companionManager.recordExternalClipboardDigest(ClipboardSyncManager.textDigest(trimmed))
            session.send(.inputText(InputTextPayload(mode: .setClipboardOnly, seq: seq, text: trimmed)))
            showToast("已发送文本到「\(activeChannelDeviceName)」剪贴板")
        } else if companionManager.isAuthenticated {
            if companionManager.sendClipboardText(seq: seq, text: trimmed) {
                showToast("已通过后台伴侣发送到「\(activeChannelDeviceName)」剪贴板")
            } else {
                // Even if deduped against identical digest, force push explicitly requested by user action.
                _ = companionManager.sendFrame(
                    .inputText(InputTextPayload(mode: .setClipboardOnly, seq: seq, text: trimmed)),
                    allowDropOnCongestion: false
                )
                showToast("已发送到「\(activeChannelDeviceName)」剪贴板")
            }
        } else {
            onRequestOpenMainWindow?()
            let msg = "未连接到 Android 设备，无法发送文本至手机剪贴板"
            bannerError = msg
            showToast(msg)
        }
    }

    // MARK: - Internal Companion Lifecycle Helpers

    func handleDeviceStateHostTopApp(_ state: DeviceStatePayload) {
        let incoming = HostTopAppState(
            packageName: state.hostTopAppPackage,
            appLabel: state.hostTopAppLabel
        )
        if !incoming.isEmpty || !companionManager.isAuthenticated {
            hostTopApp = incoming
        }
    }

    private func ensureSharedBridgesStarted() {
        notificationBridge.start()
        if preferences.notificationsSyncEnabled {
            Task { [notificationBridge] in
                _ = await notificationBridge.requestAuthorization()
            }
        }
        mediaBridge.start { [weak self] action in
            _ = self?.sendMediaActionOnActiveChannel(action)
        }
    }

    private func handleCompanionStateChanged(_ newState: CompanionConnectionState) {
        companionState = newState
        companionTarget = companionManager.activeTarget

        switch newState {
        case .connected(let deviceName):
            OpalDeskLog.info("companion", "Background companion connected to \(deviceName)")
            ensureSharedBridgesStarted()
            _ = companionManager.requestAppList()
            updateClipboardSyncLifecycle()
            if !fileBrowserItems.isEmpty || isLoadingFileBrowser {
                refreshFileBrowser(path: fileBrowserCurrentPath)
            }
        case .disconnected, .failed:
            updateClipboardSyncLifecycle()
            if !isAuthenticated {
                resetSharedCompanionPayloads(reason: "后台伴侣已断开")
            }
        case .reconnecting:
            updateClipboardSyncLifecycle()
        case .idle, .connecting, .authenticating:
            break
        }
    }

    private func handleCompanionHostTopAppChanged(_ topApp: HostTopAppState) {
        if !isAuthenticated || deviceState.hostTopAppPackage.isEmpty {
            hostTopApp = topApp
        }
    }

    private func resetSharedCompanionPayloads(reason: String) {
        fileBrowserClient.handleSessionDisconnected(reason: reason)
        notificationBridge.clearLocalState(removeDelivered: true)
        mediaBridge.stop()
        remoteNotifications = []
        mediaState = nil
        hostTopApp = HostTopAppState()
        cachedCompanionApps = []
        cancelPendingFileTransferWaiters(reason: reason)
    }
}
