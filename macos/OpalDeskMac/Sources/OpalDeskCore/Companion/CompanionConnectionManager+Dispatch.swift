import Foundation
import os

// MARK: - Inbound Frame Dispatch & Subcomponent Wiring

extension CompanionConnectionManager {
    func handleInboundFrame(_ frame: OpalCastFrame, generation: UInt64) {
        switch frame.type {
        case .authResult:
            do {
                let auth = try AuthResultPayload.decode(from: frame.payload)
                handleAuthResult(auth, generation: generation)
            } catch {
                Self.logger.error("Failed to decode companion AUTH_RESULT: \(error.localizedDescription, privacy: .public)")
            }

        case .deviceState:
            do {
                let deviceState = try DeviceStatePayload.decode(from: frame.payload)
                handleDeviceState(deviceState)
            } catch {
                Self.logger.error("Failed to decode companion DEVICE_STATE: \(error.localizedDescription, privacy: .public)")
            }

        case .clipboardPush:
            handleClipboardPushFrame(frame)

        case .notificationEvent:
            do {
                let event = try NotificationEventPayload.decode(from: frame.payload)
                notificationBridge.handleEvent(event)
                let callback = state.withLock { $0.callbacks.onNotificationEvent }
                callback?(event)
            } catch {
                Self.logger.error("Failed to decode NOTIFICATION_EVENT: \(error.localizedDescription, privacy: .public)")
            }

        case .mediaState:
            do {
                let media = try MediaStatePayload.decode(from: frame.payload)
                mediaBridge.applyRemoteMediaState(media)
                let callback = state.withLock { $0.callbacks.onMediaStateChanged }
                callback?(media)
            } catch {
                Self.logger.error("Failed to decode MEDIA_STATE: \(error.localizedDescription, privacy: .public)")
            }

        case .fileOpResponse:
            do {
                let resp = try FileOpResponsePayload.decode(from: frame.payload)
                fileBrowserClient.handleFileOpResponse(resp)
            } catch {
                Self.logger.error("Failed to decode FILE_OP_RESPONSE: \(error.localizedDescription, privacy: .public)")
            }

        case .filePullChunk:
            do {
                let chunk = try FilePullChunkPayload.decode(from: frame.payload)
                fileBrowserClient.handleFilePullChunk(chunk)
            } catch {
                Self.logger.error("Failed to decode FILE_PULL_CHUNK: \(error.localizedDescription, privacy: .public)")
            }

        case .apkInstallStatus:
            do {
                let status = try ApkInstallStatusPayload.decode(from: frame.payload)
                fileBrowserClient.handleApkInstallStatus(status)
                let callback = state.withLock { $0.callbacks.onApkInstallStatus }
                callback?(status)
            } catch {
                Self.logger.error("Failed to decode APK_INSTALL_STATUS: \(error.localizedDescription, privacy: .public)")
            }

        case .callState:
            do {
                let callState = try CallStatePayload.decode(from: frame.payload)
                callBridge.handleCallState(callState)
                let callback = state.withLock { $0.callbacks.onCallStateChanged }
                callback?(callState)
            } catch {
                Self.logger.error("Failed to decode CALL_STATE: \(error.localizedDescription, privacy: .public)")
            }

        case .fileTransferAck:
            do {
                let ack = try FileTransferAckPayload.decode(from: frame.payload)
                let callback = state.withLock { $0.callbacks.onFileTransferAck }
                callback?(ack)
            } catch {
                Self.logger.error("Failed to decode companion FILE_TRANSFER_ACK: \(error.localizedDescription, privacy: .public)")
            }

        case .appList:
            do {
                let list = try AppListPayload.decode(from: frame.payload)
                let callback = state.withLock { $0.callbacks.onAppListReceived }
                callback?(list.apps)
            } catch {
                Self.logger.error("Failed to decode companion APP_LIST: \(error.localizedDescription, privacy: .public)")
            }

        default:
            break
        }
    }

    func handleAuthResult(_ auth: AuthResultPayload, generation: UInt64) {
        guard auth.ok else {
            let reason = auth.message.isEmpty ? "后台伴侣认证失败，授权可能已在手机端撤销" : auth.message
            let (onState, onError, mainActive): (
                (@Sendable (CompanionConnectionState) -> Void)?,
                (@Sendable (String) -> Void)?,
                Bool
            ) = state.withLock { s in
                guard s.activeGeneration == generation else { return (nil, nil, s.isMainSessionActive) }
                s.activeGeneration &+= 1
                s.isAuthenticated = false
                s.connectionState = .failed(reason)
                if let target = s.activeTarget, !target.certFingerprint.isEmpty {
                    s.authRejectedTokenByFingerprint[target.certFingerprint] = target.authToken
                }
                return (s.callbacks.onStateChanged, s.callbacks.onError, s.isMainSessionActive)
            }
            transport.disconnect()
            if !mainActive {
                fileBrowserClient.handleSessionDisconnected(reason: reason)
                mediaBridge.stop()
                callBridge.handleSessionDisconnected()
            }
            onError?(reason)
            onState?(.failed(reason))
            return
        }

        let (resolvedTarget, onState): (
            CompanionTargetDevice?,
            (@Sendable (CompanionConnectionState) -> Void)?
        ) = state.withLock { s in
            guard s.activeGeneration == generation, var target = s.activeTarget else {
                return (nil, nil)
            }
            if !auth.deviceName.isEmpty {
                target.deviceName = auth.deviceName
            }
            let verifiedFp = FingerprintUtil.normalize(
                transport.verifiedPeerFingerprint ?? (auth.certFingerprint.isEmpty ? target.certFingerprint : auth.certFingerprint)
            )
            if !verifiedFp.isEmpty {
                target.certFingerprint = verifiedFp
            }
            if !auth.authToken.isEmpty {
                target.authToken = auth.authToken
            }
            s.activeTarget = target
            s.isAuthenticated = true
            s.connectionState = .connected(deviceName: target.deviceName)
            s.backoffByFingerprint.removeValue(forKey: target.certFingerprint)
            s.authRejectedTokenByFingerprint.removeValue(forKey: target.certFingerprint)
            return (target, s.callbacks.onStateChanged)
        }

        guard let resolvedTarget else { return }

        // Persist refreshed permanent token if Android rotated it.
        if !auth.authToken.isEmpty && !resolvedTarget.certFingerprint.isEmpty {
            let updated = TrustedDevice(
                id: resolvedTarget.deviceId,
                deviceName: resolvedTarget.deviceName,
                model: resolvedTarget.model,
                certFingerprint: resolvedTarget.certFingerprint,
                authToken: auth.authToken,
                lastHost: resolvedTarget.lastHost,
                lastPort: resolvedTarget.lastPort
            )
            do {
                try trustStore.upsert(updated)
            } catch {
                Self.logger.warning("Failed to persist refreshed companion token: \(error.localizedDescription, privacy: .public)")
            }
        }

        notificationBridge.start()
        mediaBridge.start { [weak self] action in
            _ = self?.sendMediaAction(action)
        }
        onState?(.connected(deviceName: resolvedTarget.deviceName))
        _ = syncNotifications()
    }

    func handleDeviceState(_ incoming: DeviceStatePayload) {
        let newTopApp = HostTopAppState(
            packageName: incoming.hostTopAppPackage,
            appLabel: incoming.hostTopAppLabel
        )
        let (onDeviceState, topAppChanged, onTopApp): (
            (@Sendable (DeviceStatePayload) -> Void)?,
            Bool,
            (@Sendable (HostTopAppState) -> Void)?
        ) = state.withLock { s in
            s.deviceState = incoming
            let changed = (s.hostTopApp != newTopApp)
            if changed {
                s.hostTopApp = newTopApp
            }
            return (s.callbacks.onDeviceStateUpdated, changed, s.callbacks.onHostTopAppChanged)
        }

        onDeviceState?(incoming)
        if topAppChanged {
            onTopApp?(newTopApp)
        }
    }

    func handleClipboardPushFrame(_ frame: OpalCastFrame) {
        let isPng = (frame.flags & OpalCastProtocol.flagClipboardImagePng) != 0
        if isPng {
            let payload: ClipboardImagePushPayload
            do {
                payload = try ClipboardImagePushPayload.decode(from: frame.payload)
            } catch {
                Self.logger.error("Failed to decode companion image CLIPBOARD_PUSH: \(error.localizedDescription, privacy: .public)")
                return
            }
            guard !payload.pngData.isEmpty else { return }
            let digest = ClipboardSyncManager.imageDigest(payload.pngData)
            let callback: (@Sendable (Int64, Data) -> Void)? = state.withLock { s in
                guard s.clipboardSyncEnabled, !s.isMainSessionActive else { return nil }
                if payload.seq > 0 && payload.seq <= s.lastRemoteClipboardSeq { return nil }
                s.lastRemoteClipboardSeq = max(s.lastRemoteClipboardSeq, payload.seq)
                guard s.lastClipboardDigest != digest else { return nil }
                s.lastClipboardDigest = digest
                return s.callbacks.onClipboardImageReceived
            }
            callback?(payload.seq, payload.pngData)
        } else {
            let payload: ClipboardPushPayload
            do {
                payload = try ClipboardPushPayload.decode(from: frame.payload)
            } catch {
                Self.logger.error("Failed to decode companion text CLIPBOARD_PUSH: \(error.localizedDescription, privacy: .public)")
                return
            }
            let digest = ClipboardSyncManager.textDigest(payload.text)
            let callback: (@Sendable (Int64, String) -> Void)? = state.withLock { s in
                guard s.clipboardSyncEnabled, !s.isMainSessionActive else { return nil }
                if payload.seq > 0 && payload.seq <= s.lastRemoteClipboardSeq { return nil }
                s.lastRemoteClipboardSeq = max(s.lastRemoteClipboardSeq, payload.seq)
                guard s.lastClipboardDigest != digest else { return nil }
                s.lastClipboardDigest = digest
                return s.callbacks.onClipboardTextReceived
            }
            callback?(payload.seq, payload.text)
        }
    }

    func wireSubcomponents() {
        let router: @Sendable (OpalCastFrame) -> Bool = { [weak self] frame in
            self?.routeControlFrame(frame) ?? false
        }

        fileBrowserClient.setFrameSender(router)
        callBridge.setFrameSender(router)
        notificationBridge.setFrameSender(router)

        notificationBridge.updateCallbacks { cb in
            if cb.onDismissNotification == nil {
                cb.onDismissNotification = { [weak self] key in
                    _ = self?.dismissNotification(key: key)
                }
            }
            if cb.onReplyNotification == nil {
                cb.onReplyNotification = { [weak self] key, replyText in
                    _ = self?.replyToNotification(key: key, text: replyText)
                }
            }
        }
    }
}
