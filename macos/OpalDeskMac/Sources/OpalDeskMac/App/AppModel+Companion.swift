import AppKit
import Foundation
import OpalDeskCore
import os

// MARK: - Background Companion, Notifications, Media Remote, Call Control, BLE Hotspot & Dual-Channel Routing

public struct BleHotspotBannerState: Sendable, Equatable {
    public enum Kind: Sendable, Equatable {
        case loading
        case info
        case success
        case warning
        case error
    }

    public var kind: Kind
    public var title: String
    public var message: String
    public var ssid: String?
    public var gatewayIp: String?
    public var port: UInt16?

    public init(
        kind: Kind,
        title: String,
        message: String,
        ssid: String? = nil,
        gatewayIp: String? = nil,
        port: UInt16? = nil
    ) {
        self.kind = kind
        self.title = title
        self.message = message
        self.ssid = ssid
        self.gatewayIp = gatewayIp
        self.port = port
    }
}

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
        return OpalL10n.text("common.android_device")
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
        callBridge.setFrameSender(controlRouter)

        finderMountManager.setOnStateChanged { [weak self] newState in
            Task { @MainActor [weak self] in
                self?.finderMountState = newState
            }
        }

        callBridge.setOnStateChanged { [weak self] incomingState in
            Task { @MainActor [weak self] in
                self?.handleCallStateChanged(incomingState)
            }
        }

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
                    self?.showToast(OpalL10n.format("app.companion.otp_copied_fmt", item.displayAppLabel, code))
                }
            },
            onInvokeAction: { [weak self] key, actionIndex in
                _ = self?.sendNotificationActionOnActiveChannel(.invokeAction(key: key, actionIndex: actionIndex))
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

    @discardableResult
    public nonisolated func sendCallActionOnActiveChannel(_ payload: CallActionPayload) -> Bool {
        do {
            return sendControlFrameOnActiveChannel(try OpalCastFrame.callAction(payload))
        } catch {
            OpalDeskLog.app.error("Failed to encode CALL_ACTION: \(error.localizedDescription, privacy: .public)")
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
        discovery.setBleScanningEnabled(preferences.bleNearbyDiscoveryEnabled)

        syncCallControlHud()

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

    public func setBleNearbyDiscoveryEnabled(_ enabled: Bool) {
        discovery.setBleScanningEnabled(enabled)
        if !enabled {
            bleHotspotProbeTask?.cancel()
            bleHotspotProbeTask = nil
            bleHotspotBanner = nil
        }
    }

    // MARK: - Explicit Companion Controls

    public func connectCompanion(to device: DiscoveredBonjourDevice) {
        if companionManager.connect(to: device) {
            companionTarget = companionManager.activeTarget
        } else {
            showToast(OpalL10n.format("app.companion.not_trusted_fmt", device.displayName))
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
            bleHotspotProbeTask?.cancel()
            bleHotspotProbeTask = nil
            bleHotspotBanner = nil
            ensureSharedBridgesStarted()
            if preferences.notificationsSyncEnabled {
                _ = sendNotificationActionOnActiveChannel(.sync())
            }
            updateClipboardSyncLifecycle()
        } else {
            updateClipboardSyncLifecycle()
            if !companionManager.isAuthenticated {
                resetSharedCompanionPayloads(reason: OpalL10n.text("app.companion.main_disconnected"))
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
        showToast(OpalL10n.format("app.companion.replied_fmt", item.displayAppLabel))
    }

    public func invokeNotificationAction(_ item: RemoteNotificationItem, actionIndex: Int) {
        let sent = notificationBridge.invokeAction(key: item.key, actionIndex: actionIndex)
        if sent {
            let label = item.actions.first(where: { $0.index == actionIndex })?.label ?? ""
            if !label.isEmpty {
                showToast(OpalL10n.format("app.companion.action_sent_fmt", label))
            }
        } else {
            showToast(OpalL10n.text("app.companion.action_disconnected"))
        }
    }

    public func clearAllRemoteNotifications() {
        _ = sendNotificationActionOnActiveChannel(.clearAll())
        notificationBridge.clearLocalState(removeDelivered: true)
    }

    public func sendMediaAction(_ action: MediaActionPayload) {
        mediaBridge.sendAction(action)
    }

    // MARK: - Phone Call Control & Quick Dialer (`0x1E` / `0x35`)

    public func sendCallAction(_ action: CallActionPayload) {
        guard isAnyChannelConnected else {
            showToast(OpalL10n.text("app.companion.call_disconnected"))
            return
        }
        if !callBridge.sendAction(action) {
            _ = sendCallActionOnActiveChannel(action)
        }
    }

    public func dialPhoneNumber(_ number: String) {
        let trimmed = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard isAnyChannelConnected else {
            showToast(OpalL10n.text("app.companion.dial_disconnected"))
            return
        }
        sendCallAction(.dial(number: trimmed))
        showToast(OpalL10n.format("app.companion.dialing_fmt", trimmed))
    }

    func handleCallStateChanged(_ incoming: CallStatePayload) {
        callState = incoming
        syncCallControlHud()
    }

    public func previewCallHud() {
        guard !callState.isActive else {
            showToast(OpalL10n.text("app.call.preview_busy"))
            return
        }
        callHudCoordinator.previewDemo()
    }

    private func syncCallControlHud() {
        callHudCoordinator.update(
            callState: callState,
            enabled: preferences.callControlEnabled,
            onAction: { [weak self] action in
                self?.sendCallAction(action)
            }
        )
    }

    // MARK: - BLE Nearby Authenticated Hotspot Trigger & 20s Auto-Connect Probe

    public func dismissBleHotspotBanner() {
        bleHotspotProbeTask?.cancel()
        bleHotspotProbeTask = nil
        bleHotspotBanner = nil
    }

    public func triggerBleHotspot(for device: DiscoveredBleDevice) {
        guard triggeringBleDeviceId == nil else { return }
        bleHotspotProbeTask?.cancel()
        bleHotspotProbeTask = nil

        let deviceName = device.trustedDevice.deviceName
        triggeringBleDeviceId = device.id
        bleHotspotBanner = BleHotspotBannerState(
            kind: .loading,
            title: OpalL10n.format("app.ble.waking_title_fmt", deviceName),
            message: OpalL10n.text("app.ble.waking_msg")
        )

        Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.triggeringBleDeviceId == device.id {
                    self.triggeringBleDeviceId = nil
                }
            }

            do {
                let response = try await self.discovery.bleScanner.triggerHotspot(
                    for: device,
                    clientId: self.discovery.trustStore.clientId
                )
                self.handleBleHotspotResponse(response, for: device)
            } catch {
                OpalDeskLog.app.warning("BLE hotspot trigger failed for \(deviceName, privacy: .public): \(error.localizedDescription, privacy: .public)")
                let msg = error.localizedDescription
                self.bleHotspotBanner = BleHotspotBannerState(
                    kind: .error,
                    title: OpalL10n.text("app.ble.failed_title"),
                    message: msg
                )
                self.showToast(msg)
            }
        }
    }

    private func handleBleHotspotResponse(_ response: HotspotCommandResponse, for device: DiscoveredBleDevice) {
        let deviceName = device.trustedDevice.deviceName
        let ssid = response.ssid.trimmingCharacters(in: .whitespacesAndNewlines)
        let gatewayIp = response.gatewayIp.trimmingCharacters(in: .whitespacesAndNewlines)
        let port: UInt16 = response.port > 0
            ? response.port
            : (device.wifiPort > 0 ? device.wifiPort : OpalCastProtocol.defaultWifiPort)
        let detailMsg = response.message.trimmingCharacters(in: .whitespacesAndNewlines)

        switch response.status {
        case .started, .alreadyActive:
            let title = response.status == .alreadyActive
                ? OpalL10n.format("app.ble.already_on_fmt", deviceName)
                : OpalL10n.format("app.ble.enabled_fmt", deviceName)
            var parts: [String] = []
            if !ssid.isEmpty {
                parts.append(OpalL10n.format("app.ble.ssid_fmt", ssid))
            }
            if !gatewayIp.isEmpty {
                parts.append(OpalL10n.format("app.ble.addr_fmt", gatewayIp, Int(port)))
            }
            parts.append(OpalL10n.text("app.ble.join_hint"))
            let message = parts.joined(separator: " · ")

            bleHotspotBanner = BleHotspotBannerState(
                kind: .success,
                title: title,
                message: message,
                ssid: ssid.isEmpty ? nil : ssid,
                gatewayIp: gatewayIp.isEmpty ? nil : gatewayIp,
                port: port
            )
            showToast(ssid.isEmpty ? title : "\(title)：\(ssid)", durationSeconds: 5.0)
            if !gatewayIp.isEmpty {
                startHotspotAutoConnectProbe(device: device, gatewayIp: gatewayIp, port: port, ssid: ssid)
            }

        case .needsUserToggle:
            let base = OpalL10n.text("app.ble.confirm_default")
            let fullMessage = detailMsg.isEmpty ? base : "\(base)（\(detailMsg)）"
            bleHotspotBanner = BleHotspotBannerState(
                kind: .warning,
                title: OpalL10n.text("app.ble.confirm_title"),
                message: fullMessage,
                ssid: ssid.isEmpty ? nil : ssid,
                gatewayIp: gatewayIp.isEmpty ? nil : gatewayIp,
                port: port
            )
            showToast(OpalL10n.text("app.ble.confirm_toast"), durationSeconds: 5.0)
            if !gatewayIp.isEmpty {
                startHotspotAutoConnectProbe(device: device, gatewayIp: gatewayIp, port: port, ssid: ssid)
            }

        case .unauthorized:
            let msg = detailMsg.isEmpty
                ? OpalL10n.text("app.ble.auth_failed_default")
                : detailMsg
            bleHotspotBanner = BleHotspotBannerState(
                kind: .error,
                title: OpalL10n.text("app.ble.auth_failed_title"),
                message: msg
            )
            showToast(msg)

        case .error:
            let msg = detailMsg.isEmpty
                ? OpalL10n.text("app.ble.start_failed_default")
                : detailMsg
            bleHotspotBanner = BleHotspotBannerState(
                kind: .error,
                title: OpalL10n.text("app.ble.start_failed_title"),
                message: msg
            )
            showToast(msg)
        }
    }

    private func startHotspotAutoConnectProbe(
        device: DiscoveredBleDevice,
        gatewayIp: String,
        port: UInt16,
        ssid: String
    ) {
        bleHotspotProbeTask?.cancel()
        let trusted = device.trustedDevice
        let deadline = Date().addingTimeInterval(20.0)

        bleHotspotProbeTask = Task { @MainActor [weak self] in
            guard let self else { return }
            while !Task.isCancelled && Date() < deadline {
                if self.isAnyChannelConnected {
                    self.bleHotspotBanner = nil
                    return
                }
                do {
                    let probedFp = try await OpalCastSession.probeCertificateFingerprint(host: gatewayIp, port: port)
                    guard !Task.isCancelled else { return }
                    if FingerprintUtil.matches(actualFullHex: trusted.certFingerprint, expected: probedFp) {
                        _ = self.discovery.upsertTrustedDevice(
                            deviceName: trusted.deviceName,
                            model: trusted.model,
                            certFingerprint: trusted.certFingerprint,
                            authToken: trusted.authToken,
                            lastHost: gatewayIp,
                            lastPort: port
                        )
                        let endpoint = OpalCastEndpoint(
                            target: .hostPort(host: gatewayIp, port: port),
                            security: .tlsPinned(expectedFingerprint: trusted.certFingerprint),
                            autoReconnect: true
                        )
                        self.companionManager.connect(to: trusted, endpoint: endpoint)
                        self.bleHotspotBanner = BleHotspotBannerState(
                            kind: .success,
                            title: OpalL10n.format("app.ble.joined_title_fmt", trusted.deviceName),
                            message: OpalL10n.format("app.ble.joined_msg_fmt", gatewayIp, Int(port)),
                            ssid: ssid.isEmpty ? nil : ssid,
                            gatewayIp: gatewayIp,
                            port: port
                        )
                        self.showToast(OpalL10n.format("app.ble.joined_toast_fmt", trusted.deviceName))
                        return
                    }
                } catch {
                    // Expected while Mac has not yet joined the phone hotspot Wi-Fi.
                }
                guard await sleepCancellable(nanoseconds: 1_500_000_000) else { return }
            }

            guard !Task.isCancelled, !self.isAnyChannelConnected else { return }
            let ssidHint = ssid.isEmpty ? OpalL10n.text("app.ble.ssid_generic") : OpalL10n.format("app.ble.ssid_named_fmt", ssid)
            self.bleHotspotBanner = BleHotspotBannerState(
                kind: .info,
                title: OpalL10n.format("app.ble.timeout_title_fmt", ssidHint),
                message: OpalL10n.format("app.ble.timeout_msg_fmt", ssidHint),
                ssid: ssid.isEmpty ? nil : ssid,
                gatewayIp: gatewayIp,
                port: port
            )
        }
    }

    /// Launches the Android phone's current Display 0 foreground app inside a standalone macOS floating popout window.
    public func handoffHostTopApp() {
        guard !hostTopApp.isEmpty else {
            showToast(OpalL10n.text("app.handoff.none"))
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
        userId: Int = 0,
        profileType: String = "",
        preferredDeviceKey: String? = nil,
        preferredDeviceName: String? = nil
    ) {
        let cleanPkg = packageName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPkg.isEmpty else { return }
        let normalizedUid = max(0, userId)
        let displayLabel = appLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? cleanPkg : appLabel

        if let existing = popoutManager.existingController(for: cleanPkg, userId: normalizedUid) {
            popoutManager.focusWindow(id: existing.id)
            return
        }

        if popoutManager.controllers.count >= PopoutWindowManager.maxConcurrentWindows {
            let msg = OpalL10n.format("app.popout.limit_fmt", PopoutWindowManager.maxConcurrentWindows)
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
                userId: normalizedUid,
                profileType: profileType,
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
            showToast(OpalL10n.format("app.clipboard.sent_fmt", activeChannelDeviceName))
        } else if companionManager.isAuthenticated {
            if companionManager.sendClipboardText(seq: seq, text: trimmed) {
                showToast(OpalL10n.format("app.clipboard.sent_companion_fmt", activeChannelDeviceName))
            } else {
                // Even if deduped against identical digest, force push explicitly requested by user action.
                _ = companionManager.sendFrame(
                    .inputText(InputTextPayload(mode: .setClipboardOnly, seq: seq, text: trimmed)),
                    allowDropOnCongestion: false
                )
                showToast(OpalL10n.format("app.clipboard.sent_fmt", activeChannelDeviceName))
            }
        } else {
            onRequestOpenMainWindow?()
            let msg = OpalL10n.text("app.clipboard.disconnected")
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
            bleHotspotProbeTask?.cancel()
            bleHotspotProbeTask = nil
            bleHotspotBanner = nil
            ensureSharedBridgesStarted()
            _ = companionManager.requestAppList()
            updateClipboardSyncLifecycle()
            if !fileBrowserItems.isEmpty || isLoadingFileBrowser {
                refreshFileBrowser(path: fileBrowserCurrentPath)
            }
        case .disconnected, .failed:
            updateClipboardSyncLifecycle()
            if !isAuthenticated {
                resetSharedCompanionPayloads(reason: OpalL10n.text("app.companion.disconnected"))
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
        callBridge.handleSessionDisconnected()
        callState = .idle
        callHudCoordinator.hide()
        remoteNotifications = []
        mediaState = nil
        hostTopApp = HostTopAppState()
        cachedCompanionApps = []
        cancelPendingFileTransferWaiters(reason: reason)
        Task { await self.unmountFinderIfNeeded(showToastOnSuccess: false) }
    }
}
