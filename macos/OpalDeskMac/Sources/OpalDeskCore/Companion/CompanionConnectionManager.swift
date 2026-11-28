import Foundation
import os

// MARK: - Transport Abstraction & Public Companion State Models

/// Transport protocol satisfied by `OpalCastSession` so `CompanionConnectionManager`
/// can be verified end-to-end with deterministic mock transports under `swift test`.
public protocol CompanionSessionTransport: Sendable {
    var currentState: OpalCastSessionState { get }
    var verifiedPeerFingerprint: String? { get }
    func connect(to endpoint: OpalCastEndpoint, onEvent: @escaping @Sendable (OpalCastSessionEvent) -> Void)
    func disconnect()
    func send(_ frame: OpalCastFrame, allowDropOnCongestion: Bool)
    func sendPaced(_ frame: OpalCastFrame) async
}

extension OpalCastSession: CompanionSessionTransport {}

/// Represents the currently active foreground app on the Android phone's primary Display 0 (for Handoff).
public struct HostTopAppState: Sendable, Equatable, Hashable {
    public var packageName: String
    public var appLabel: String

    public var isEmpty: Bool {
        packageName.isEmpty
    }

    public var displayLabel: String {
        appLabel.isEmpty ? packageName : appLabel
    }

    public init(packageName: String = "", appLabel: String = "") {
        self.packageName = packageName
        self.appLabel = appLabel
    }
}

/// Active target metadata for the lightweight `.companion` session.
public struct CompanionTargetDevice: Sendable, Equatable {
    public var deviceId: String
    public var deviceName: String
    public var model: String
    public var certFingerprint: String
    public var authToken: String
    public var endpoint: OpalCastEndpoint
    public var lastHost: String?
    public var lastPort: UInt16?

    public init(
        deviceId: String,
        deviceName: String,
        model: String = "",
        certFingerprint: String,
        authToken: String,
        endpoint: OpalCastEndpoint,
        lastHost: String? = nil,
        lastPort: UInt16? = nil
    ) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.model = model
        self.certFingerprint = FingerprintUtil.normalize(certFingerprint)
        self.authToken = authToken
        self.endpoint = endpoint
        self.lastHost = lastHost
        self.lastPort = lastPort
    }
}

public enum CompanionConnectionState: Sendable, Equatable {
    case idle
    case connecting(deviceName: String)
    case authenticating(deviceName: String)
    case connected(deviceName: String)
    case reconnecting(deviceName: String, attempt: Int)
    case failed(String)
    case disconnected

    public var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }

    public var isActiveOrAttempting: Bool {
        switch self {
        case .connecting, .authenticating, .connected, .reconnecting:
            return true
        case .idle, .failed, .disconnected:
            return false
        }
    }
}

// MARK: - CompanionConnectionManager

/// Manages the lightweight background `.companion` connection to a trusted Android OpalDesk host.
///
/// Responsibilities:
/// - Automatically connects to trusted Bonjour-discovered devices that hold a permanent `authToken`
///   while enforcing exponential backoff and revocation suppression to prevent reconnect storms.
/// - Runs background clipboard sync with mutual exclusion against an active main desktop cast session.
/// - Dispatches Android notifications (`0x19`), media playback state (`0x1A`), phone foreground app
///   handoff (`0x10` `hostTopApp*`), remote file responses/chunks (`0x1B`/`0x1C`), APK install progress
///   (`0x1D`), file upload ACKs (`0x13`), and app catalog snapshots (`0x15`).
/// - Exposes unified control APIs for sending notification actions, media actions, file operations,
///   clipboard pushes, and app catalog queries.
public final class CompanionConnectionManager: Sendable {
    static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "companion")

    public struct Callbacks: Sendable {
        public var onStateChanged: (@Sendable (CompanionConnectionState) -> Void)?
        public var onDeviceStateUpdated: (@Sendable (DeviceStatePayload) -> Void)?
        public var onHostTopAppChanged: (@Sendable (HostTopAppState) -> Void)?
        public var onNotificationEvent: (@Sendable (NotificationEventPayload) -> Void)?
        public var onMediaStateChanged: (@Sendable (MediaStatePayload) -> Void)?
        public var onAppListReceived: (@Sendable ([RemoteAppInfo]) -> Void)?
        public var onClipboardTextReceived: (@Sendable (_ seq: Int64, _ text: String) -> Void)?
        public var onClipboardImageReceived: (@Sendable (_ seq: Int64, _ pngData: Data) -> Void)?
        public var onFileTransferAck: (@Sendable (FileTransferAckPayload) -> Void)?
        public var onApkInstallStatus: (@Sendable (ApkInstallStatusPayload) -> Void)?
        public var onError: (@Sendable (String) -> Void)?
        public var onCallStateChanged: (@Sendable (CallStatePayload) -> Void)?

        public init(
            onStateChanged: (@Sendable (CompanionConnectionState) -> Void)? = nil,
            onDeviceStateUpdated: (@Sendable (DeviceStatePayload) -> Void)? = nil,
            onHostTopAppChanged: (@Sendable (HostTopAppState) -> Void)? = nil,
            onNotificationEvent: (@Sendable (NotificationEventPayload) -> Void)? = nil,
            onMediaStateChanged: (@Sendable (MediaStatePayload) -> Void)? = nil,
            onAppListReceived: (@Sendable ([RemoteAppInfo]) -> Void)? = nil,
            onClipboardTextReceived: (@Sendable (_ seq: Int64, _ text: String) -> Void)? = nil,
            onClipboardImageReceived: (@Sendable (_ seq: Int64, _ pngData: Data) -> Void)? = nil,
            onFileTransferAck: (@Sendable (FileTransferAckPayload) -> Void)? = nil,
            onApkInstallStatus: (@Sendable (ApkInstallStatusPayload) -> Void)? = nil,
            onError: (@Sendable (String) -> Void)? = nil,
            onCallStateChanged: (@Sendable (CallStatePayload) -> Void)? = nil
        ) {
            self.onStateChanged = onStateChanged
            self.onDeviceStateUpdated = onDeviceStateUpdated
            self.onHostTopAppChanged = onHostTopAppChanged
            self.onNotificationEvent = onNotificationEvent
            self.onMediaStateChanged = onMediaStateChanged
            self.onAppListReceived = onAppListReceived
            self.onClipboardTextReceived = onClipboardTextReceived
            self.onClipboardImageReceived = onClipboardImageReceived
            self.onFileTransferAck = onFileTransferAck
            self.onApkInstallStatus = onApkInstallStatus
            self.onError = onError
            self.onCallStateChanged = onCallStateChanged
        }
    }

    struct BackoffRecord {
        var failureCount: Int = 0
        var nextAllowedAttempt: Date = .distantPast
    }

    struct MutableState {
        var autoConnectEnabled: Bool = true
        var clipboardSyncEnabled: Bool = true
        var isMainSessionActive: Bool = false
        var isManuallyDisconnected: Bool = false
        var preferredDeviceFingerprint: String?
        var discoveredDevices: [DiscoveredBonjourDevice] = []
        var activeTarget: CompanionTargetDevice?
        var connectionState: CompanionConnectionState = .idle
        var isAuthenticated: Bool = false
        var activeGeneration: UInt64 = 0
        var deviceState = DeviceStatePayload()
        var hostTopApp = HostTopAppState()
        var backoffByFingerprint: [String: BackoffRecord] = [:]
        var authRejectedTokenByFingerprint: [String: String] = [:]
        var lastRemoteClipboardSeq: Int64 = 0
        var lastClipboardDigest: String = ""
        var externalFrameRouter: (@Sendable (OpalCastFrame) -> Bool)?
        var callbacks = Callbacks()
    }

    public let trustStore: TrustStore
    public let fileBrowserClient: RemoteFileBrowserClient
    public let notificationBridge: NotificationBridgeManager
    public let mediaBridge: MediaRemoteBridge
    public let callBridge: CallBridgeManager

    let transport: any CompanionSessionTransport
    let clientName: String
    let baseBackoffSeconds: TimeInterval
    let maxBackoffSeconds: TimeInterval
    let nowProvider: @Sendable () -> Date
    let state = OSAllocatedUnfairLock(initialState: MutableState())

    public init(
        trustStore: TrustStore,
        transport: (any CompanionSessionTransport)? = nil,
        fileBrowserClient: RemoteFileBrowserClient = RemoteFileBrowserClient(),
        notificationBridge: NotificationBridgeManager = NotificationBridgeManager(),
        mediaBridge: MediaRemoteBridge = MediaRemoteBridge(),
        callBridge: CallBridgeManager = CallBridgeManager(),
        clientName: String? = nil,
        baseBackoffSeconds: TimeInterval = 5.0,
        maxBackoffSeconds: TimeInterval = 300.0,
        nowProvider: @escaping @Sendable () -> Date = { Date() },
        callbacks: Callbacks = Callbacks()
    ) {
        self.trustStore = trustStore
        self.transport = transport ?? OpalCastSession(trustStore: trustStore)
        self.fileBrowserClient = fileBrowserClient
        self.notificationBridge = notificationBridge
        self.mediaBridge = mediaBridge
        self.callBridge = callBridge
        self.clientName = clientName ?? (Host.current().localizedName ?? ProcessInfo.processInfo.hostName)
        self.baseBackoffSeconds = max(0.05, baseBackoffSeconds)
        self.maxBackoffSeconds = max(self.baseBackoffSeconds, maxBackoffSeconds)
        self.nowProvider = nowProvider
        self.state.withLock { $0.callbacks = callbacks }

        wireSubcomponents()
    }

    deinit {
        transport.disconnect()
        mediaBridge.stop()
        callBridge.handleSessionDisconnected()
        fileBrowserClient.handleSessionDisconnected(reason: "伴侣管理器已释放")
    }

    // MARK: - Observable State Snapshots

    public var connectionState: CompanionConnectionState {
        state.withLock { $0.connectionState }
    }

    public var isAuthenticated: Bool {
        state.withLock { $0.isAuthenticated }
    }

    public var activeTarget: CompanionTargetDevice? {
        state.withLock { $0.activeTarget }
    }

    public var latestDeviceState: DeviceStatePayload {
        state.withLock { $0.deviceState }
    }

    public var hostTopApp: HostTopAppState {
        state.withLock { $0.hostTopApp }
    }

    public var autoConnectEnabled: Bool {
        state.withLock { $0.autoConnectEnabled }
    }

    public var clipboardSyncEnabled: Bool {
        get { state.withLock { $0.clipboardSyncEnabled } }
        set { state.withLock { $0.clipboardSyncEnabled = newValue } }
    }

    public var isMainSessionActive: Bool {
        state.withLock { $0.isMainSessionActive }
    }

    public var preferredDeviceFingerprint: String? {
        get { state.withLock { $0.preferredDeviceFingerprint } }
        set {
            let normalized = newValue.map(FingerprintUtil.normalize)
            state.withLock { $0.preferredDeviceFingerprint = (normalized?.isEmpty == false) ? normalized : nil }
        }
    }

    public func setCallbacks(_ callbacks: Callbacks) {
        state.withLock { $0.callbacks = callbacks }
    }

    public func updateCallbacks(_ mutate: @Sendable (inout Callbacks) -> Void) {
        state.withLock { mutate(&$0.callbacks) }
    }

    /// Installs an optional external control-frame router (e.g. active main desktop session preferred,
    /// falling back to this companion session) used by subcomponents (`fileBrowserClient`,
    /// `notificationBridge`, `mediaBridge`).
    public func setExternalFrameRouter(_ router: (@Sendable (OpalCastFrame) -> Bool)?) {
        state.withLock { $0.externalFrameRouter = router }
    }

    func routeControlFrame(_ frame: OpalCastFrame) -> Bool {
        let external = state.withLock { $0.externalFrameRouter }
        if let external {
            return external(frame)
        }
        return sendFrame(frame)
    }

    // MARK: - Configuration & Discovery Integration

    /// Toggles automatic silent companion connection for trusted Bonjour devices.
    /// Re-enabling clears manual disconnect suppression and evaluates currently discovered devices.
    public func setAutoConnectEnabled(_ enabled: Bool) {
        state.withLock { s in
            s.autoConnectEnabled = enabled
            if enabled {
                s.isManuallyDisconnected = false
            }
        }
        if enabled {
            evaluateAutoConnect()
        }
    }

    /// Coordinates clipboard ownership with the primary desktop casting session so both sessions
    /// never duplicate clipboard reads or writes.
    public func setMainSessionActive(_ active: Bool) {
        state.withLock { $0.isMainSessionActive = active }
    }

    /// Seeds the deduplication digest when handing clipboard ownership back from the main session.
    public func recordExternalClipboardDigest(_ digest: String) {
        guard !digest.isEmpty else { return }
        state.withLock { $0.lastClipboardDigest = digest }
    }

    /// Feeds updated Bonjour discovery results (`_opaldesk._tcp`) and triggers silent auto-connect if eligible.
    public func updateDiscoveredDevices(_ devices: [DiscoveredBonjourDevice]) {
        state.withLock { $0.discoveredDevices = devices }
        evaluateAutoConnect()
    }

    // MARK: - Explicit Connection Lifecycle

    /// Explicitly connects the `.companion` session to a discovered Bonjour device using its stored permanent `authToken`.
    /// Returns `false` if the device is not paired with a permanent token in `TrustStore`.
    @discardableResult
    public func connect(to device: DiscoveredBonjourDevice) -> Bool {
        guard let trusted = trustStore.find(byFingerprint: device.fingerprintPrefix),
              !trusted.authToken.isEmpty
        else {
            Self.logger.info("Skipping companion connect for untrusted Bonjour device \(device.displayName, privacy: .public)")
            return false
        }

        let endpoint = OpalCastEndpoint(
            target: .bonjour(serviceName: device.serviceName, domain: device.domain),
            security: .tlsPinned(expectedFingerprint: trusted.certFingerprint),
            autoReconnect: true
        )
        let target = CompanionTargetDevice(
            deviceId: trusted.id,
            deviceName: device.displayName.isEmpty ? trusted.deviceName : device.displayName,
            model: device.model.isEmpty ? trusted.model : device.model,
            certFingerprint: trusted.certFingerprint,
            authToken: trusted.authToken,
            endpoint: endpoint,
            lastHost: trusted.lastHost,
            lastPort: trusted.lastPort
        )
        startConnection(to: target, clearManualSuppression: true)
        return true
    }

    /// Explicitly connects the `.companion` session to a trusted device over a specific endpoint (e.g. manual host or localhost).
    public func connect(to trusted: TrustedDevice, endpoint: OpalCastEndpoint) {
        let target = CompanionTargetDevice(
            deviceId: trusted.id,
            deviceName: trusted.deviceName,
            model: trusted.model,
            certFingerprint: trusted.certFingerprint,
            authToken: trusted.authToken,
            endpoint: endpoint,
            lastHost: trusted.lastHost,
            lastPort: trusted.lastPort
        )
        startConnection(to: target, clearManualSuppression: true)
    }

    /// Switches the companion target to a different trusted Bonjour device and persists it as preferred.
    @discardableResult
    public func switchTargetDevice(to device: DiscoveredBonjourDevice) -> Bool {
        guard let trusted = trustStore.find(byFingerprint: device.fingerprintPrefix),
              !trusted.authToken.isEmpty
        else {
            return false
        }
        state.withLock { $0.preferredDeviceFingerprint = trusted.certFingerprint }
        return connect(to: device)
    }

    /// Manually disconnects the companion session and suppresses auto-reconnect until explicitly reconnected
    /// or `setAutoConnectEnabled(true)` is called.
    public func disconnect() {
        let onStateChanged: (@Sendable (CompanionConnectionState) -> Void)?
        let hadTopApp: Bool
        let onTopAppChanged: (@Sendable (HostTopAppState) -> Void)?
        let mainActive: Bool

        (onStateChanged, hadTopApp, onTopAppChanged, mainActive) = state.withLock { s in
            s.activeGeneration &+= 1
            s.isManuallyDisconnected = true
            s.isAuthenticated = false
            s.activeTarget = nil
            s.connectionState = .disconnected
            s.deviceState = DeviceStatePayload()
            let prevTop = s.hostTopApp
            s.hostTopApp = HostTopAppState()
            return (s.callbacks.onStateChanged, !prevTop.isEmpty, s.callbacks.onHostTopAppChanged, s.isMainSessionActive)
        }

        transport.disconnect()
        if !mainActive {
            fileBrowserClient.handleSessionDisconnected(reason: "用户手动断开连接")
            notificationBridge.clearLocalState(removeDelivered: true)
            mediaBridge.stop()
            callBridge.handleSessionDisconnected()
        }

        onStateChanged?(.disconnected)
        if hadTopApp {
            onTopAppChanged?(HostTopAppState())
        }
    }

    // MARK: - Unified Control APIs (Mac -> Android)

    @discardableResult
    public func sendFrame(_ frame: OpalCastFrame, allowDropOnCongestion: Bool = false) -> Bool {
        let authenticated = state.withLock { $0.isAuthenticated }
        guard authenticated else { return false }
        transport.send(frame, allowDropOnCongestion: allowDropOnCongestion)
        return true
    }

    public func sendFileTransferFrame(_ frame: OpalCastFrame) async -> Bool {
        let authenticated = state.withLock { $0.isAuthenticated }
        guard authenticated else { return false }
        await transport.sendPaced(frame)
        return true
    }

    @discardableResult
    public func sendNotificationAction(_ payload: NotificationActionPayload) -> Bool {
        do {
            return routeControlFrame(try OpalCastFrame.notificationAction(payload))
        } catch {
            Self.logger.error("Failed to encode NOTIFICATION_ACTION: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    @discardableResult
    public func syncNotifications() -> Bool {
        sendNotificationAction(.sync())
    }

    @discardableResult
    public func dismissNotification(key: String) -> Bool {
        guard !key.isEmpty else { return false }
        return sendNotificationAction(.dismiss(key: key))
    }

    @discardableResult
    public func clearAllNotifications() -> Bool {
        sendNotificationAction(.clearAll())
    }

    @discardableResult
    public func replyToNotification(key: String, text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, !trimmed.isEmpty else { return false }
        return sendNotificationAction(.reply(key: key, text: trimmed))
    }

    @discardableResult
    public func invokeNotificationAction(key: String, actionIndex: Int) -> Bool {
        guard !key.isEmpty, actionIndex >= 0 else { return false }
        return sendNotificationAction(.invokeAction(key: key, actionIndex: actionIndex))
    }

    @discardableResult
    public func sendMediaAction(_ payload: MediaActionPayload) -> Bool {
        do {
            return routeControlFrame(try OpalCastFrame.mediaAction(payload))
        } catch {
            Self.logger.error("Failed to encode MEDIA_ACTION: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    @discardableResult
    public func sendCallAction(_ payload: CallActionPayload) -> Bool {
        do {
            return routeControlFrame(try OpalCastFrame.callAction(payload))
        } catch {
            Self.logger.error("Failed to encode CALL_ACTION: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    @discardableResult
    public func requestAppList() -> Bool {
        sendFrame(.listApps())
    }

    /// Sends Mac local clipboard text over the companion link unless suppressed by the main cast session or deduped.
    @discardableResult
    public func sendClipboardText(seq: Int64, text: String) -> Bool {
        guard !text.isEmpty else { return false }
        let digest = ClipboardSyncManager.textDigest(text)
        let allowed: Bool = state.withLock { s in
            guard s.isAuthenticated, s.clipboardSyncEnabled, !s.isMainSessionActive else {
                return false
            }
            guard s.lastClipboardDigest != digest else { return false }
            s.lastClipboardDigest = digest
            return true
        }
        guard allowed else { return false }
        transport.send(.inputText(InputTextPayload(mode: .setClipboardOnly, seq: seq, text: text)), allowDropOnCongestion: false)
        return true
    }

    /// Sends Mac local clipboard PNG image over the companion link unless suppressed by the main cast session or deduped.
    @discardableResult
    public func sendClipboardImage(seq: Int64, pngData: Data) -> Bool {
        guard !pngData.isEmpty, pngData.count <= OpalCastProtocol.maxClipboardImageBytes else { return false }
        let digest = ClipboardSyncManager.imageDigest(pngData)
        let allowed: Bool = state.withLock { s in
            guard s.isAuthenticated, s.clipboardSyncEnabled, !s.isMainSessionActive else {
                return false
            }
            guard s.lastClipboardDigest != digest else { return false }
            s.lastClipboardDigest = digest
            return true
        }
        guard allowed else { return false }
        do {
            let frame = try OpalCastFrame.inputImage(InputImagePayload(mode: .setClipboardImageOnly, seq: seq, pngData: pngData))
            transport.send(frame, allowDropOnCongestion: false)
            return true
        } catch {
            Self.logger.error("Failed to encode companion clipboard PNG: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }
}
