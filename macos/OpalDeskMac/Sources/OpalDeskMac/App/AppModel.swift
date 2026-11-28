import AppKit
import CoreGraphics
import OpalDeskCore
import Foundation
import Observation
import os

struct PendingConnectionAuth {
    var authToken: String = ""
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
        static let wiredUsbMaxLongEdge: Int = 3840
        static let wirelessAdbMaxLongEdge: Int = 2560
        static let defaultMaxLongEdge: Int = 2560
        static let wifiMaxBitrateKbps: Int = 32_000
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
    public let companionManager: CompanionConnectionManager
    public let notificationBridge: NotificationBridgeManager
    public let mediaBridge: MediaRemoteBridge
    public let callBridge: CallBridgeManager
    public let fileBrowserClient: RemoteFileBrowserClient
    public let finderMountManager: FinderMountManager
    public let quickLookCoordinator: QuickLookPreviewCoordinator
    public let callHudCoordinator: CallControlHudCoordinator
    public let screenRecorder: WindowScreenRecorder
    public let popoutManager: PopoutWindowManager
    public let popoutHistoryStore: PopoutHistoryStore
    public let popoutAppPrefsStore: PopoutAppPreferencesStore
    public let remoteAppCatalogFetcher: RemoteAppCatalogFetcher
    public let feedbackConfig: FeedbackBuildConfig
    public let feedbackHistoryStore: FeedbackHistoryStore
    public let feedbackQuotaStorage: UserDefaultsFeedbackQuotaStorage
    public let feedbackRateLimiter: FeedbackRateLimiter

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

    // Background companion, notifications, media remote, calls & phone handoff
    public var companionState: CompanionConnectionState = .idle
    public var companionTarget: CompanionTargetDevice?
    public var hostTopApp = HostTopAppState()
    public var remoteNotifications: [RemoteNotificationItem] = []
    public var mediaState: MediaStatePayload?
    public var callState: CallStatePayload = .idle
    public var cachedCompanionApps: [RemoteAppInfo] = []
    public var triggeringBleDeviceId: String?
    public var bleHotspotBanner: BleHotspotBannerState?

    // Screen recording & rich toast action state
    public var isMainWindowRecording: Bool = false
    public var mainRecordingDurationSeconds: TimeInterval = 0
    public var toastActionTitle: String?
    public var toastActionURL: URL?

    // Remote file browser, Finder WebDAV mount, Quick Look & storage analyzer state
    public var fileBrowserCurrentPath: String = "/sdcard"
    public var fileBrowserItems: [RemoteFileItem] = []
    public var isLoadingFileBrowser: Bool = false
    public var fileBrowserError: String?
    public var fileBrowserStorageReport: RemoteStorageReport?
    public var isScanningRemoteStorage: Bool = false
    public var fileBrowserStorageError: String?
    public var fileBrowserTransfers: [RemoteFileTransferItem] = []
    public var finderMountState: FinderMountState = .unmounted
    public var quickLookLoadingPath: String?

    // APK installer & Spotlight shortcut manager sheet state
    public var apkInstallerContext: ApkInstallerContext?
    public var apkInstallState: ApkInstallState = .idle
    public var isShortcutManagerPresented: Bool = false

    // Standalone floating app windows, history & per-app preferences
    public var popoutHistory: [PopoutHistoryEntry] = []
    public var popoutAppPrefs: [String: PopoutAppPreference] = [:]
    public var remoteAppPickerContext: RemoteAppPickerContext?
    public var pendingPopoutReloadPrompt: PendingPopoutReloadPrompt?
    public var cachedDesktopApps: [RemoteAppInfo] = []

    // Diagnostics logs & feedback tracking
    public var logEntries: [OpalLogEntry] = []
    public var feedbackRecords: [FeedbackRecord] = []
    public var feedbackQuota: FeedbackQuotaSnapshot
    public var lastFeedbackSyncAtMs: Int64 = 0
    public var savedFeedbackReporterName: String = ""

    // Transfers & notifications
    public var pairingContext: PairingPromptContext?
    public var isProbingManualHost: Bool = false
    public var activeTransfers: [FileTransferProgress] = []
    public var bannerError: String?
    public var statusMessage: String?
    public var toastNotice: String?
    public var disconnectReason: String?

    @ObservationIgnored public lazy var finderServicesProvider: FinderServicesProvider = makeFinderServicesProvider()
    @ObservationIgnored let mainSessionAuthLock = OSAllocatedUnfairLock<Bool>(initialState: false)
    @ObservationIgnored var isClipboardSyncRunning: Bool = false
    @ObservationIgnored var nextCompanionClipboardSeq: Int64 = 1
    @ObservationIgnored var fileBrowserThumbnailCache: [String: Data] = [:]
    @ObservationIgnored var inFlightThumbnailPaths: Set<String> = []
    @ObservationIgnored var fileBrowserDirectoryTask: Task<Void, Never>?
    @ObservationIgnored var fileBrowserStorageTask: Task<Void, Never>?
    @ObservationIgnored var fileBrowserDownloadTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored var finderMountTask: Task<Void, Never>?
    @ObservationIgnored var quickLookDownloadTask: Task<Void, Never>?
    @ObservationIgnored var bleHotspotProbeTask: Task<Void, Never>?
    @ObservationIgnored var fileBrowserUploadTransferMap: [UInt32: (transferItemId: String, remoteDirectory: String)] = [:]
    @ObservationIgnored var pendingFileTransferAckWaiters: [UInt32: CheckedContinuation<String, Error>] = [:]
    @ObservationIgnored var pendingFileTransferProgressHandlers: [UInt32: @MainActor (FileTransferProgress) -> Void] = [:]
    @ObservationIgnored var activeApkInstallTask: Task<Void, Never>?
    @ObservationIgnored var activeApkUploadTransferId: UInt32?
    @ObservationIgnored var recentIncomingURLTimestamps: [String: Date] = [:]
    @ObservationIgnored public var onRequestOpenMainWindow: (@MainActor () -> Void)?
    @ObservationIgnored public var onRequestOpenFileBrowserWindow: (@MainActor () -> Void)?

    @ObservationIgnored var isUserInitiatedDisconnect: Bool = false
    @ObservationIgnored var activeSessionGeneration: UInt64 = 0

    @ObservationIgnored var activeAdbForward: AdbForwardHandle?
    @ObservationIgnored var popoutAdbForwards: [String: AdbForwardHandle] = [:]
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
        let notifBridge = NotificationBridgeManager()
        let mediaRemoteBridge = MediaRemoteBridge()
        let telephonyBridge = CallBridgeManager()
        let remoteFileClient = RemoteFileBrowserClient()
        let finderMountMgr = FinderMountManager()
        let qlCoordinator = QuickLookPreviewCoordinator()
        let callHud = CallControlHudCoordinator()
        let companionMgr = CompanionConnectionManager(
            trustStore: discoveryCoord.trustStore,
            fileBrowserClient: remoteFileClient,
            notificationBridge: notifBridge,
            mediaBridge: mediaRemoteBridge,
            callBridge: telephonyBridge
        )
        let recorder = WindowScreenRecorder()
        let popoutMgr = PopoutWindowManager()
        let popoutStore = PopoutHistoryStore()
        let popoutPrefsStore = PopoutAppPreferencesStore()
        let catalogFetcher = RemoteAppCatalogFetcher()
        let fbConfig = FeedbackBuildConfig.current()
        let fbHistoryStore = FeedbackHistoryStore()
        let fbQuotaStorage = UserDefaultsFeedbackQuotaStorage()
        let fbLimiter = FeedbackRateLimiter(storage: fbQuotaStorage)

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
        self.companionManager = companionMgr
        self.notificationBridge = notifBridge
        self.mediaBridge = mediaRemoteBridge
        self.callBridge = telephonyBridge
        self.fileBrowserClient = remoteFileClient
        self.finderMountManager = finderMountMgr
        self.quickLookCoordinator = qlCoordinator
        self.callHudCoordinator = callHud
        self.screenRecorder = recorder
        self.popoutManager = popoutMgr
        self.popoutHistoryStore = popoutStore
        self.popoutAppPrefsStore = popoutPrefsStore
        self.remoteAppCatalogFetcher = catalogFetcher
        self.feedbackConfig = fbConfig
        self.feedbackHistoryStore = fbHistoryStore
        self.feedbackQuotaStorage = fbQuotaStorage
        self.feedbackRateLimiter = fbLimiter
        self.deviceState = DeviceStatePayload()
        self.popoutHistory = popoutStore.snapshot()
        self.popoutAppPrefs = popoutPrefsStore.snapshot()
        self.logEntries = OpalLogStore.shared.snapshot()
        self.feedbackRecords = fbHistoryStore.snapshot()
        self.feedbackQuota = fbLimiter.currentQuota()
        self.lastFeedbackSyncAtMs = fbHistoryStore.lastCheckedAtMs
        self.savedFeedbackReporterName = fbQuotaStorage.getReporterName()

        applyInitialSessionPreferences()
        configureCallbacks()
        configureCompanionSubsystem()
        OpalLogStore.shared.setEntriesObserver { [weak self] entries in
            Task { @MainActor [weak self] in
                self?.logEntries = entries
            }
        }
        popoutMgr.onControllersChanged = { [weak self] in
            self?.cleanupUnusedPopoutAdbForwards()
        }
        discoveryCoord.onBonjourDevicesChanged = { [weak self] devices in
            self?.companionManager.updateDiscoveredDevices(devices)
        }
        discoveryCoord.onTrustedDevicesChanged = { [weak self] _ in
            guard let self else { return }
            self.companionManager.updateDiscoveredDevices(self.discovery.bonjourDevices)
        }
        OpalDeskLog.info("app", "OpalDesk macOS v\(fbConfig.appVersion) initialized")
        discoveryCoord.start(
            customAdbPath: { [weak self] in self?.preferences.customAdbPath ?? "" },
            isCanvasActive: { [weak self] in self?.isShowingDisplayCanvas ?? false },
            isUsbTetherEnabled: { [weak self] in self?.preferences.usbTetherDiscoveryEnabled ?? false },
            isBleEnabled: { [weak self] in self?.preferences.bleNearbyDiscoveryEnabled ?? true }
        )
    }

    public var isShowingDisplayCanvas: Bool {
        pairingContext == nil
            && activeConnection != nil
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
    public func revokeTrustedDevice(_ device: TrustedDevice) {
        if let err = discovery.revokeTrustedDevice(device) {
            bannerError = err
            return
        }
        if let activeFp = companionManager.activeTarget?.certFingerprint,
           FingerprintUtil.matches(actualFullHex: device.certFingerprint, expected: activeFp)
        {
            companionManager.disconnect()
        }
    }
    public func revokeAllTrustedDevices() {
        if let err = discovery.revokeAllTrustedDevices() {
            bannerError = err
            return
        }
        if companionState.isActiveOrAttempting {
            companionManager.disconnect()
        }
    }
    public func applyClipboardSyncPreference() {
        clipboardSync.isEnabled = preferences.clipboardSyncEnabled
        companionManager.clipboardSyncEnabled = preferences.clipboardSyncEnabled
        updateClipboardSyncLifecycle()
    }

    public func showToast(
        _ message: String,
        durationSeconds: Double = 3.2,
        actionTitle: String? = nil,
        revealURL: URL? = nil
    ) {
        toastClearTask?.cancel()
        toastNotice = message
        toastActionTitle = actionTitle
        toastActionURL = revealURL
        let effectiveDuration = (revealURL != nil) ? max(durationSeconds, 5.5) : durationSeconds
        toastClearTask = Task { [weak self] in
            guard await sleepCancellable(nanoseconds: UInt64(effectiveDuration * 1_000_000_000)) else { return }
            self?.toastNotice = nil
            self?.toastActionTitle = nil
            self?.toastActionURL = nil
        }
    }

    // MARK: - Wired USB / Wireless ADB & Wi-Fi Connections

    public func connectWiredUSB(_ device: AdbDevice) async {
        guard device.isReady else {
            bannerError = OpalL10n.format("app.adb.device_state_err_fmt", device.displayName, device.state.displayLabel)
            return
        }
        guard !isConnectingFlow else { return }

        bannerError = nil
        statusMessage = device.isWirelessAdb
            ? OpalL10n.text("app.adb.bootstrapping_wireless")
            : OpalL10n.text("app.adb.bootstrapping_usb")
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
                authToken: trusted.authToken
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
            model: OpalL10n.format("app.tether.model_fmt", device.interfaceLabel),
            expectedFingerprint: device.certFingerprint
        )
    }

    public func connectManualHost(host: String, port: UInt16) async {
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedHost.isEmpty else {
            bannerError = OpalL10n.text("app.manual.invalid_host")
            return
        }
        bannerError = nil
        let source = PairingPromptContext.Source.manualHostPort(host: trimmedHost, port: port)
        if let trusted = discovery.trustStore.find(byHost: trimmedHost, port: port), !trusted.authToken.isEmpty {
            openTrustedWirelessSession(source: source, trusted: trusted)
            return
        }

        isProbingManualHost = true
        statusMessage = OpalL10n.format("app.manual.probing_fmt", trimmedHost, Int(port))
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
                model: OpalL10n.text("app.manual.model"),
                expectedFingerprint: fingerprint
            )
        } catch {
            OpalDeskLog.app.warning("Manual host TLS probe failed for \(trimmedHost, privacy: .public):\(port): \(error.localizedDescription, privacy: .public)")
            bannerError = error.localizedDescription
        }
    }

    /// Sends an interactive pairing / connection consent request to the Android host
    /// after the user verifies the TOFU TLS certificate fingerprint.
    public func requestPairing() {
        guard var context = pairingContext, !context.isSubmitting else { return }
        context.errorMessage = nil
        context.isSubmitting = true
        pairingContext = context
        openWirelessSession(
            source: context.source,
            displayName: context.displayName,
            model: context.model,
            expectedFingerprint: context.expectedFingerprint,
            authToken: ""
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
        fileBrowserDirectoryTask?.cancel()
        fileBrowserStorageTask?.cancel()
        finderMountTask?.cancel()
        quickLookDownloadTask?.cancel()
        bleHotspotProbeTask?.cancel()
        for task in fileBrowserDownloadTasks.values { task.cancel() }
        fileBrowserDownloadTasks.removeAll()
        activeApkInstallTask?.cancel()
        activeApkInstallTask = nil
        cancelPendingFileTransferWaiters(reason: OpalL10n.text("app.terminating"))

        quickLookCoordinator.closeIfVisible()
        callHudCoordinator.close()
        callBridge.handleSessionDisconnected()
        detachFinderUnmountIfNeeded()

        if screenRecorder.isRecording {
            screenRecorder.cancel()
        }
        isMainWindowRecording = false
        mainRecordingDurationSeconds = 0

        remoteAppCatalogFetcher.cancel()
        popoutManager.closeAll(sendDisconnect: true)
        discovery.stop()
        clipboardSync.stop()
        isClipboardSyncRunning = false
        mainSessionAuthLock.withLock { $0 = false }
        companionManager.setMainSessionActive(false)
        companionManager.disconnect()
        notificationBridge.stop(clearDelivered: false)
        mediaBridge.stop()
        fileBrowserClient.handleSessionDisconnected(reason: OpalL10n.text("app.terminating"))

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
        forwardsToClean.append(contentsOf: popoutAdbForwards.values)
        popoutAdbForwards.removeAll()
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
            authToken: trusted.authToken
        )
    }

    private func probeAndPromptBonjour(_ device: DiscoveredBonjourDevice) async {
        isProbingManualHost = true
        statusMessage = OpalL10n.format("app.bonjour.probing_fmt", device.displayName)
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
        authToken: String
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
                autoReconnect: !authToken.isEmpty
            ))
        }
    }
}
