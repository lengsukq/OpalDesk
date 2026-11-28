import Foundation
import os

// MARK: - Auto-Connect Selection, Backoff & Transport State Machine

extension CompanionConnectionManager {
    func evaluateAutoConnect() {
        let now = nowProvider()

        let candidate: CompanionTargetDevice? = state.withLock { s in
            guard s.autoConnectEnabled,
                  !s.isManuallyDisconnected,
                  !s.connectionState.isActiveOrAttempting
            else {
                return nil
            }

            var eligible: [(device: DiscoveredBonjourDevice, trusted: TrustedDevice)] = []
            for bonjour in s.discoveredDevices {
                guard let trusted = trustStore.find(byFingerprint: bonjour.fingerprintPrefix),
                      !trusted.authToken.isEmpty
                else {
                    continue
                }
                let fp = trusted.certFingerprint
                if let rejectedToken = s.authRejectedTokenByFingerprint[fp], rejectedToken == trusted.authToken {
                    continue
                }
                if let backoff = s.backoffByFingerprint[fp], now < backoff.nextAllowedAttempt {
                    continue
                }
                eligible.append((bonjour, trusted))
            }

            guard !eligible.isEmpty else { return nil }

            let chosen: (device: DiscoveredBonjourDevice, trusted: TrustedDevice)
            if let preferred = s.preferredDeviceFingerprint,
               let match = eligible.first(where: { $0.trusted.certFingerprint == preferred })
            {
                chosen = match
            } else {
                chosen = eligible.max(by: { $0.trusted.lastConnectedAt < $1.trusted.lastConnectedAt }) ?? eligible[0]
            }

            let endpoint = OpalCastEndpoint(
                target: .bonjour(serviceName: chosen.device.serviceName, domain: chosen.device.domain),
                security: .tlsPinned(expectedFingerprint: chosen.trusted.certFingerprint),
                autoReconnect: true
            )
            return CompanionTargetDevice(
                deviceId: chosen.trusted.id,
                deviceName: chosen.device.displayName.isEmpty ? chosen.trusted.deviceName : chosen.device.displayName,
                model: chosen.device.model.isEmpty ? chosen.trusted.model : chosen.device.model,
                certFingerprint: chosen.trusted.certFingerprint,
                authToken: chosen.trusted.authToken,
                endpoint: endpoint,
                lastHost: chosen.trusted.lastHost,
                lastPort: chosen.trusted.lastPort
            )
        }

        if let candidate {
            startConnection(to: candidate, clearManualSuppression: false)
        }
    }

    func startConnection(to target: CompanionTargetDevice, clearManualSuppression: Bool) {
        let (generation, onStateChanged, mainActive): (UInt64, (@Sendable (CompanionConnectionState) -> Void)?, Bool) = state.withLock { s in
            s.activeGeneration &+= 1
            if clearManualSuppression {
                s.isManuallyDisconnected = false
                s.backoffByFingerprint.removeValue(forKey: target.certFingerprint)
                s.authRejectedTokenByFingerprint.removeValue(forKey: target.certFingerprint)
            }
            s.activeTarget = target
            s.isAuthenticated = false
            s.connectionState = .connecting(deviceName: target.deviceName)
            s.lastRemoteClipboardSeq = 0
            return (s.activeGeneration, s.callbacks.onStateChanged, s.isMainSessionActive)
        }

        if !mainActive {
            fileBrowserClient.handleSessionDisconnected(reason: "正在切换伴侣连接")
        }
        onStateChanged?(.connecting(deviceName: target.deviceName))

        transport.connect(to: target.endpoint) { [weak self] event in
            self?.handleSessionEvent(event, generation: generation)
        }
    }

    func handleSessionEvent(_ event: OpalCastSessionEvent, generation: UInt64) {
        let isCurrent: Bool = state.withLock { $0.activeGeneration == generation }
        guard isCurrent else { return }

        switch event {
        case .stateChanged(let newState):
            handleTransportStateChange(newState, generation: generation)

        case .verifiedPeerFingerprint(let fullHex):
            let normalized = FingerprintUtil.normalize(fullHex)
            state.withLock { s in
                if s.activeGeneration == generation {
                    s.activeTarget?.certFingerprint = normalized
                }
            }

        case .rttUpdated:
            break

        case .error(let sessionError):
            let onError = state.withLock { $0.callbacks.onError }
            onError?(sessionError.localizedDescription)

        case .frame(let frame):
            handleInboundFrame(frame, generation: generation)
        }
    }

    func handleTransportStateChange(_ newState: OpalCastSessionState, generation: UInt64) {
        switch newState {
        case .connecting:
            let (name, cb): (String, (@Sendable (CompanionConnectionState) -> Void)?) = state.withLock { s in
                let deviceName = s.activeTarget?.deviceName ?? ""
                s.connectionState = .connecting(deviceName: deviceName)
                return (deviceName, s.callbacks.onStateChanged)
            }
            cb?(.connecting(deviceName: name))

        case .connected:
            let (target, cb): (CompanionTargetDevice?, (@Sendable (CompanionConnectionState) -> Void)?) = state.withLock { s in
                guard s.activeGeneration == generation, let target = s.activeTarget else { return (nil, nil) }
                s.connectionState = .authenticating(deviceName: target.deviceName)
                return (target, s.callbacks.onStateChanged)
            }
            guard let target else { return }
            cb?(.authenticating(deviceName: target.deviceName))
            sendCompanionHello(for: target)

        case .reconnecting(let attempt):
            let (name, cb, mainActive): (String, (@Sendable (CompanionConnectionState) -> Void)?, Bool) = state.withLock { s in
                s.isAuthenticated = false
                let deviceName = s.activeTarget?.deviceName ?? ""
                s.connectionState = .reconnecting(deviceName: deviceName, attempt: attempt)
                return (deviceName, s.callbacks.onStateChanged, s.isMainSessionActive)
            }
            if !mainActive {
                fileBrowserClient.handleSessionDisconnected(reason: "连接重连中")
            }
            cb?(.reconnecting(deviceName: name, attempt: attempt))

        case .failed(let reason):
            let now = nowProvider()
            let (cb, hadTopApp, onTopApp, mainActive): (
                (@Sendable (CompanionConnectionState) -> Void)?,
                Bool,
                (@Sendable (HostTopAppState) -> Void)?,
                Bool
            ) = state.withLock { s in
                s.isAuthenticated = false
                s.connectionState = .failed(reason)
                if let fp = s.activeTarget?.certFingerprint, !fp.isEmpty {
                    var record = s.backoffByFingerprint[fp] ?? BackoffRecord()
                    record.failureCount += 1
                    let multiplier = pow(2.0, Double(min(record.failureCount - 1, 10)))
                    let delay = min(baseBackoffSeconds * multiplier, maxBackoffSeconds)
                    record.nextAllowedAttempt = now.addingTimeInterval(delay)
                    s.backoffByFingerprint[fp] = record
                }
                let prevTop = s.hostTopApp
                s.hostTopApp = HostTopAppState()
                return (s.callbacks.onStateChanged, !prevTop.isEmpty, s.callbacks.onHostTopAppChanged, s.isMainSessionActive)
            }
            if !mainActive {
                fileBrowserClient.handleSessionDisconnected(reason: reason)
                mediaBridge.stop()
                callBridge.handleSessionDisconnected()
            }
            cb?(.failed(reason))
            if hadTopApp {
                onTopApp?(HostTopAppState())
            }

        case .disconnected:
            let (cb, hadTopApp, onTopApp, mainActive): (
                (@Sendable (CompanionConnectionState) -> Void)?,
                Bool,
                (@Sendable (HostTopAppState) -> Void)?,
                Bool
            ) = state.withLock { s in
                s.isAuthenticated = false
                s.connectionState = .disconnected
                let prevTop = s.hostTopApp
                s.hostTopApp = HostTopAppState()
                return (s.callbacks.onStateChanged, !prevTop.isEmpty, s.callbacks.onHostTopAppChanged, s.isMainSessionActive)
            }
            if !mainActive {
                fileBrowserClient.handleSessionDisconnected(reason: "连接已断开")
                mediaBridge.stop()
                callBridge.handleSessionDisconnected()
            }
            cb?(.disconnected)
            if hadTopApp {
                onTopApp?(HostTopAppState())
            }

        case .idle:
            break
        }
    }

    func sendCompanionHello(for target: CompanionTargetDevice) {
        let transportHint: OpalCastTransportHint = {
            switch target.endpoint.security {
            case .wiredLocalhostPlaintext: return .usb
            case .tlsPinned: return .wifiTls
            }
        }()

        let hello = HelloPayload(
            protocolVersion: OpalCastProtocol.version,
            clientName: clientName,
            clientId: trustStore.clientId,
            preferredCodec: OpalCastVideoCodec.hevc.preferredName,
            width: 0,
            height: 0,
            dpi: 0,
            fps: 0,
            bitrateKbps: 0,
            displayMode: Int(OpalCastDisplayMode.dex.rawValue),
            audioEnabled: false,
            authToken: target.authToken,
            pinCode: "",
            transportHint: transportHint.rawValue,
            clientPlatform: "macos",
            sessionRole: OpalCastSessionRole.companion.rawValue,
            companionEvents: true
        )

        do {
            transport.send(try OpalCastFrame.hello(hello), allowDropOnCongestion: false)
        } catch {
            Self.logger.error("Failed to encode companion HELLO: \(error.localizedDescription, privacy: .public)")
            let onError = state.withLock { $0.callbacks.onError }
            onError?(error.localizedDescription)
        }
    }
}
