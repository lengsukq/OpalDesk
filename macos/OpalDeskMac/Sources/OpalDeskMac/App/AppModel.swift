import AppKit
import CoreGraphics
import OpalDeskCore
import Foundation
import Observation
import os

struct PendingConnectionAuth {
    var authToken: String = ""
    var pinCode: String = ""
    var hostForTrust: String?
    var portForTrust: UInt16?
    var deviceName: String = ""
    var deviceModel: String = ""
    var pairingSource: PairingPromptContext.Source?
}

/// Central `@MainActor` state coordinator for `OpalDeskMac`.
/// Delegates discovery/trust to `DiscoveryCoordinator`, Wireless ADB -> Wi-Fi TLS upgrades
/// to `WifiUpgradeCoordinator`, media/input framing to `StreamSessionBridge`, and standalone
/// app windows to `PopoutWindowManager`.
@MainActor
@Observable
public final class AppModel {
    enum Tuning {
        static let minCanvasDimension: CGFloat = 200
        static let resizeDebounceNs: UInt64 = 300_000_000
        static let remoteDisconnectFlushNs: UInt64 = 60_000_000
        static let appListRefreshThrottleNs: UInt64 = 1_500_000_000
        static let postWindowActionRefreshDelayNs: UInt64 = 650_000_000
        static let wirelessAdbMaxLongEdge: Int = 2560
        static let defaultMaxLongEdge: Int = 2560
        static let wirelessAdbMaxBitrateKbps: Int = 16_000
        static let maxVisibleTransfers: Int = 6
        static let defaultCanvasSize = CGSize(width: 1280, height: 720)
    }

    public let preferences: PreferencesStore
    public let discovery: DiscoveryCoordinator
    public let wifiUpgrade: WifiUpgradeCoordinator
    public let session: OpalCastSession
    public let streamBridge: StreamSessionBridge
    public let videoRenderer: VideoDisplayRenderer
    public let audioPlayer: AudioStreamPlayer
    public let micCapturer: MicrophoneStreamCapturer
    public let cameraCapturer: CameraStreamCapturer
    public let clipboardSync: ClipboardSyncManager
    public let fileSender: FileSender

    // Active connection & stream state
    public var activeConnection: ActiveConnectionInfo?
    public var sessionState: OpalCastSessionState = .idle
    public var isAuthenticated: Bool = false
    public var isConnectingFlow: Bool = false
    public var deviceState = DeviceStatePayload()
    public var videoMetrics = VideoRendererMetrics()
    public var rttMs: Double = 0
    public var isBlackoutActive: Bool = false
    public var isAudioMuted: Bool = false
    public var isMicEnabled: Bool = false
    public var isCameraEnabled: Bool = false
    public var displayMode: OpalCastDisplayMode = .dex
    public var selectedPreset: DisplayResolutionPreset = .matchWindow
    public var selectedDPI: Int = DisplayPresets.defaultDPI

    // Transfers & notifications
    public var pairingContext: PairingPromptContext?
    public var isProbingManualHost: Bool = false
    public var activeTransfers: [FileTransferProgress] = []
    public var bannerError: String?
    public var statusMessage: String?
    public var toastNotice: String?
    public var disconnectReason: String?

    @ObservationIgnored var isUserInitiatedDisconnect: Bool = false
    @ObservationIgnored var activeSessionGeneration: UInt64 = 0

    @ObservationIgnored var activeAdbForward: AdbForwardHandle?
    @ObservationIgnored var activeSessionEndpoint: OpalCastEndpoint?
    @ObservationIgnored var pendingAuth = PendingConnectionAuth()
    @ObservationIgnored var resizeDebounceTask: Task<Void, Never>?
    @ObservationIgnored var toastClearTask: Task<Void, Never>?
    @ObservationIgnored var lastRequestedConfig: (width: Int, height: Int, dpi: Int, fps: Int)?
    @ObservationIgnored var lastCanvasSize: CGSize = Tuning.defaultCanvasSize
    @ObservationIgnored var lastBackingScale: CGFloat = 2.0
    @ObservationIgnored weak var mainDisplayWindow: NSWindow?
    @ObservationIgnored private var hasCleanedUpOnTerminate: Bool = false

    public init() {
        let prefs = PreferencesStore()
        let discoveryCoord = DiscoveryCoordinator(customAdbPath: prefs.customAdbPath)
        let extSession = OpalCastSession(trustStore: discoveryCoord.trustStore)

        self.preferences = prefs
        self.discovery = discoveryCoord
        self.wifiUpgrade = WifiUpgradeCoordinator()
        self.session = extSession
        self.streamBridge = StreamSessionBridge(session: extSession, logger: OpalDeskLog.app)
        self.videoRenderer = VideoDisplayRenderer()
        self.audioPlayer = AudioStreamPlayer()
        self.micCapturer = MicrophoneStreamCapturer()
        self.cameraCapturer = CameraStreamCapturer()
        self.clipboardSync = ClipboardSyncManager()
        self.fileSender = FileSender()
        self.deviceState = DeviceStatePayload()

        applyInitialSessionPreferences()
        configureCallbacks()
        discoveryCoord.start(
            customAdbPath: { [weak self] in self?.preferences.customAdbPath ?? "" },
            isCanvasActive: { [weak self] in self?.isShowingDisplayCanvas ?? false },
            isUsbTetherEnabled: { [weak self] in self?.preferences.usbTetherDiscoveryEnabled ?? false }
        )
    }

    public var isShowingDisplayCanvas: Bool {
        activeConnection != nil
            && (sessionState == .connected || sessionState == .connecting || isReconnecting || wifiUpgrade.isUpgradingToWifiTls)
    }

    public var isReconnecting: Bool {
        if case .reconnecting = sessionState { return true }
        return false
    }

    public var currentVideoWidth: Int {
        videoMetrics.width > 0 ? videoMetrics.width : (deviceState.width > 0 ? deviceState.width : OpalCastProtocol.defaultWidth)
    }

    public var currentVideoHeight: Int {
        videoMetrics.height > 0 ? videoMetrics.height : (deviceState.height > 0 ? deviceState.height : OpalCastProtocol.defaultHeight)
    }

    public var injectStatus: OpalCastInjectStatus { OpalCastInjectStatus(rawStatus: deviceState.injectStatus) }

    // MARK: - Discovery & Settings Actions

    public func refreshAdbPath() { discovery.refreshAdbPath(customPath: preferences.customAdbPath) }
    public func refreshUSBDevices() async { await discovery.refreshUSBDevices(customPath: preferences.customAdbPath) }
    public func refreshUsbTetherDevices() async {
        await discovery.refreshUsbTetherDevices(enabled: preferences.usbTetherDiscoveryEnabled)
    }
    public func setUsbTetherDiscoveryEnabled(_ enabled: Bool) {
        Task { await discovery.refreshUsbTetherDevices(enabled: enabled) }
    }
    public func revokeTrustedDevice(_ device: TrustedDevice) { if let err = discovery.revokeTrustedDevice(device) { bannerError = err } }
    public func revokeAllTrustedDevices() { if let err = discovery.revokeAllTrustedDevices() { bannerError = err } }
    public func applyClipboardSyncPreference() { clipboardSync.isEnabled = preferences.clipboardSyncEnabled }

    public func showToast(_ message: String, durationSeconds: Double = 3.2) {
        toastClearTask?.cancel()
        toastNotice = message
        toastClearTask = Task { [weak self] in
            guard await sleepCancellable(nanoseconds: UInt64(durationSeconds * 1_000_000_000)) else { return }
            self?.toastNotice = nil
        }
    }

    // MARK: - Wired USB / Wireless ADB & Wi-Fi Connections

    public func connectWiredUSB(_ device: AdbDevice) async {
        guard device.isReady else {
            bannerError = "设备 \(device.displayName) 当前状态为「\(device.state.displayLabel)」，请先在手机上允许 USB 调试"
            return
        }
        guard !isConnectingFlow else { return }

        bannerError = nil
        statusMessage = device.isWirelessAdb
            ? "正在通过无线 ADB 拉起守护进程并准备升级 Wi-Fi 直连..."
            : "正在拉起手机端 OpalDesk 守护进程并建立 USB 隧道..."
        isConnectingFlow = true

        await teardownCurrentSession(sendRemoteDisconnect: false)

        do {
            let forward = try await discovery.adbManager.bootstrapDaemon(serial: device.serial)
            activeAdbForward = forward
            pendingAuth = PendingConnectionAuth(deviceName: device.displayName, deviceModel: device.model)

            let transport: ActiveTransportKind = device.isWirelessAdb
                ? .wirelessADB(serial: device.serial, localPort: forward.localPort)
                : .wiredUSB(serial: device.serial, localPort: forward.localPort)
            activeConnection = ActiveConnectionInfo(
                deviceName: device.displayName,
                model: device.model,
                transport: transport,
                fingerprint: "",
                connectedAt: Date()
            )
            applyInitialSessionPreferences()
            connectSession(to: OpalCastEndpoint(
                target: .hostPort(host: "127.0.0.1", port: forward.localPort),
                security: .wiredLocalhostPlaintext,
                autoReconnect: true
            ))
            statusMessage = nil
        } catch {
            OpalDeskLog.app.error("ADB connect failed for \(device.serial, privacy: .public): \(error.localizedDescription, privacy: .public)")
            isConnectingFlow = false
            statusMessage = nil
            bannerError = error.localizedDescription
            await teardownCurrentSession(sendRemoteDisconnect: false)
        }
    }

    public func connectBonjourDevice(_ device: DiscoveredBonjourDevice) {
        bannerError = nil
        if let trusted = discovery.trustStore.find(byFingerprint: device.fingerprintPrefix), !trusted.authToken.isEmpty {
            openWirelessSession(
                source: .bonjour(device),
                displayName: device.displayName,
                model: device.model,
                expectedFingerprint: trusted.certFingerprint,
                authToken: trusted.authToken,
                pinCode: ""
            )
            return
        }
        if device.fingerprintPrefix.count < FingerprintUtil.minimumPrefixHexLength {
            Task { await probeAndPromptBonjour(device) }
            return
        }
        pairingContext = PairingPromptContext(
            source: .bonjour(device),
            displayName: device.displayName,
            model: device.model,
            expectedFingerprint: device.fingerprintPrefix
        )
    }

    public func connectUsbTetherDevice(_ device: DiscoveredUsbTetherDevice) {
        bannerError = nil
        let source = PairingPromptContext.Source.manualHostPort(host: device.host, port: device.port)
        if let trusted = discovery.trustStore.find(byFingerprint: device.certFingerprint), !trusted.authToken.isEmpty {
            openTrustedWirelessSession(source: source, trusted: trusted)
            return
        }
        if let trusted = discovery.trustStore.find(byHost: device.host, port: device.port), !trusted.authToken.isEmpty {
            openTrustedWirelessSession(source: source, trusted: trusted)
            return
        }
        pairingContext = PairingPromptContext(
            source: source,
            displayName: device.displayName,
            model: "USB 网络共享 · \(device.interfaceLabel) (Beta)",
            expectedFingerprint: device.certFingerprint
        )
    }

    public func connectManualHost(host: String, port: UInt16) async {
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedHost.isEmpty else {
            bannerError = "请输入有效的局域网 IP 地址或主机名"
            return
        }
        bannerError = nil
        let source = PairingPromptContext.Source.manualHostPort(host: trimmedHost, port: port)
        if let trusted = discovery.trustStore.find(byHost: trimmedHost, port: port), !trusted.authToken.isEmpty {
            openTrustedWirelessSession(source: source, trusted: trusted)
            return
        }

        isProbingManualHost = true
        statusMessage = "正在安全探测 \(trimmedHost):\(port) 的 TLS 证书指纹..."
        defer {
            isProbingManualHost = false
            statusMessage = nil
        }
        do {
            let fingerprint = try await OpalCastSession.probeCertificateFingerprint(host: trimmedHost, port: port)
            if let trusted = discovery.trustStore.find(byFingerprint: fingerprint), !trusted.authToken.isEmpty {
                openTrustedWirelessSession(source: source, trusted: trusted)
                return
            }
            pairingContext = PairingPromptContext(
                source: source,
                displayName: "\(trimmedHost):\(port)",
                model: "手动无线主机",
                expectedFingerprint: fingerprint
            )
        } catch {
            OpalDeskLog.app.warning("Manual host TLS probe failed for \(trimmedHost, privacy: .public):\(port): \(error.localizedDescription, privacy: .public)")
            bannerError = error.localizedDescription
        }
    }

    public func submitPairing(pinCode: String) {
        guard var context = pairingContext else { return }
        let cleanedPin = pinCode.filter(\.isNumber)
        guard cleanedPin.count == DisplayPresets.pairingPinLength else {
            context.errorMessage = "请输入手机端显示的 \(DisplayPresets.pairingPinLength) 位数字配对 PIN 码"
            pairingContext = context
            return
        }
        context.errorMessage = nil
        context.isSubmitting = true
        pairingContext = context
        openWirelessSession(
            source: context.source,
            displayName: context.displayName,
            model: context.model,
            expectedFingerprint: context.expectedFingerprint,
            authToken: "",
            pinCode: cleanedPin
        )
    }

    public func cancelPairing() {
        pairingContext = nil
        if !isAuthenticated { Task { await teardownCurrentSession(sendRemoteDisconnect: false) } }
    }

    public func disconnect() {
        isUserInitiatedDisconnect = true
        disconnectReason = nil
        Task { await teardownCurrentSession(sendRemoteDisconnect: true) }
    }

    /// Immediately aborts any ongoing reconnect/stream, disconnects the socket synchronously, and returns to Device Hub.
    public func forceDisconnectAndReturnHome() {
        isUserInitiatedDisconnect = true
        disconnectReason = nil
        activeSessionGeneration &+= 1
        session.disconnect()
        Task { await teardownCurrentSession(sendRemoteDisconnect: false) }
    }

    /// Force-quits the macOS application even if active modals or streams are hung.
    public func forceTerminateApp() {
        cleanupOnTerminate()
        NSApp.terminate(nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            exit(0)
        }
    }

    /// Idempotent synchronous termination teardown (never blocks `@MainActor` with semaphores).
    public func cleanupOnTerminate() {
        guard !hasCleanedUpOnTerminate else { return }
        hasCleanedUpOnTerminate = true

        resizeDebounceTask?.cancel()
        toastClearTask?.cancel()
        discovery.stop()
        clipboardSync.stop()
        micCapturer.stop()
        isMicEnabled = false
        cameraCapturer.stop()
        isCameraEnabled = false
        audioPlayer.stop()
        videoRenderer.reset()
        session.disconnect()

        var forwardsToClean = wifiUpgrade.reset()
        if let active = activeAdbForward {
            forwardsToClean.append(active)
            activeAdbForward = nil
        }
        Self.detachAdbForwardRemoval(forwardsToClean, adbPath: discovery.adbManager.locateAdb())
    }

    // MARK: - Internal Session Lifecycle Helpers

    func applyInitialSessionPreferences() {
        displayMode = preferences.defaultDisplayMode
        selectedPreset = preferences.resolutionPreset
        selectedDPI = preferences.defaultDPI
        isAudioMuted = !preferences.audioEnabled
        audioPlayer.setMuted(isAudioMuted)
    }

    func connectSession(to endpoint: OpalCastEndpoint) {
        activeSessionGeneration &+= 1
        let generation = activeSessionGeneration
        activeSessionEndpoint = endpoint
        let handler = StreamSessionBridge.makeEventHandler(
            videoRenderer: videoRenderer,
            audioPlayer: audioPlayer,
            onVideoDisplayMode: { [weak self] mode in
                guard let self, self.activeSessionGeneration == generation else { return }
                self.displayMode = mode
            },
            onControlEvent: { [weak self] event in
                guard let self, self.activeSessionGeneration == generation else { return }
                self.handleSessionEvent(event)
            }
        )
        session.connect(to: endpoint, onEvent: handler)
    }

    // MARK: - Private Setup & Wireless Helpers

    private func configureCallbacks() {
        videoRenderer.setCallbacks(
            onRequestIDR: { [weak self] in self?.session.send(.requestIdr()) },
            onMetricsUpdate: { [weak self] metrics in
                Task { @MainActor [weak self] in self?.videoMetrics = metrics }
            }
        )
    }

    private func openTrustedWirelessSession(source: PairingPromptContext.Source, trusted: TrustedDevice) {
        openWirelessSession(
            source: source,
            displayName: trusted.deviceName,
            model: trusted.model,
            expectedFingerprint: trusted.certFingerprint,
            authToken: trusted.authToken,
            pinCode: ""
        )
    }

    private func probeAndPromptBonjour(_ device: DiscoveredBonjourDevice) async {
        isProbingManualHost = true
        statusMessage = "正在探测 \(device.displayName) 的证书指纹..."
        defer {
            isProbingManualHost = false
            statusMessage = nil
        }
        do {
            let fp = try await OpalCastSession.probeCertificateFingerprint(nwEndpoint: device.nwEndpoint)
            pairingContext = PairingPromptContext(
                source: .bonjour(device),
                displayName: device.displayName,
                model: device.model,
                expectedFingerprint: fp
            )
        } catch {
            OpalDeskLog.app.warning("Bonjour fingerprint probe failed for \(device.displayName, privacy: .public): \(error.localizedDescription, privacy: .public)")
            bannerError = error.localizedDescription
        }
    }

    private func openWirelessSession(
        source: PairingPromptContext.Source,
        displayName: String,
        model: String,
        expectedFingerprint: String,
        authToken: String,
        pinCode: String
    ) {
        Task { [weak self] in
            guard let self else { return }
            await self.teardownCurrentSession(sendRemoteDisconnect: false)
            self.isConnectingFlow = true

            let target: OpalCastEndpoint.Target
            let transportKind: ActiveTransportKind
            let hostForTrust: String?
            let portForTrust: UInt16?
            switch source {
            case .bonjour(let bonjourDevice):
                target = .bonjour(serviceName: bonjourDevice.serviceName, domain: bonjourDevice.domain)
                transportKind = .wirelessWiFi(displayTarget: bonjourDevice.displayName, host: nil, port: nil)
                hostForTrust = nil
                portForTrust = nil
            case let .manualHostPort(host, port):
                target = .hostPort(host: host, port: port)
                transportKind = .wirelessWiFi(displayTarget: "\(host):\(port)", host: host, port: port)
                hostForTrust = host
                portForTrust = port
            }

            self.pendingAuth = PendingConnectionAuth(
                authToken: authToken,
                pinCode: pinCode,
                hostForTrust: hostForTrust,
                portForTrust: portForTrust,
                deviceName: displayName,
                deviceModel: model,
                pairingSource: source
            )
            self.activeConnection = ActiveConnectionInfo(
                deviceName: displayName,
                model: model,
                transport: transportKind,
                fingerprint: expectedFingerprint,
                connectedAt: Date()
            )
            self.applyInitialSessionPreferences()
            self.connectSession(to: OpalCastEndpoint(
                target: target,
                security: .tlsPinned(expectedFingerprint: expectedFingerprint),
                autoReconnect: pinCode.isEmpty
            ))
        }
    }
}
