import AppKit
import Foundation
import Observation
import OpalDeskCore
import os

/// Discrete user-driven preference mutations emitted by `PopoutAppController` for persistence in `PopoutAppPreferencesStore`.
public enum PopoutAppPreferenceChange: Sendable, Equatable {
    case favorite(Bool)
    case preset(String?)
    case customWindowSize(widthPt: Double, heightPt: Double)
    case pinnedOnTop(Bool)
    case customDpi(Int?)
    case compatibilityResolution(Bool)
}

extension PopoutAspectPreset {
    var preferenceKey: String {
        switch self {
        case .phone: return "phone"
        case .foldable: return "fold"
        case .tablet: return "tablet"
        }
    }

    static func fromPreferenceKey(_ raw: String?) -> PopoutAspectPreset? {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "phone": return .phone
        case "fold", "foldable": return .foldable
        case "tablet": return .tablet
        default: return nil
        }
    }
}

/// Manages the lifecycle, video stream, input forwarding, always-on-top state, and desktop window state
/// of one standalone popout Android app window.
@MainActor
@Observable
public final class PopoutAppController: Identifiable {
    public static let customDpiOptions: [Int] = [240, 280, 320, 360, 400, 480]

    private enum Tuning {
        static let defaultVideoWidth: Int = 848
        static let defaultVideoHeight: Int = 1680
        static let maxBitrateKbps: Int = 10_000
        static let minCanvasDimension: CGFloat = 200
        static let resizeDebounceNs: UInt64 = 260_000_000
        static let returnToDesktopFlushNs: UInt64 = 90_000_000
        static let firstFrameNudgeDelaysNs: [UInt64] = [900_000_000, 1_800_000_000]
        static let firstFrameTimeoutNs: UInt64 = 6_500_000_000
        static let presetMatchTolerancePt: CGFloat = 14
        static let screenEdgePaddingPt: CGFloat = 12
    }

    public let id = UUID()
    public let appPackage: String
    public let appActivity: String
    public var appLabel: String
    public let appUserId: Int
    public let appProfileType: String
    public let deviceKey: String
    public var deviceName: String
    public var taskId: Int

    public var appKey: String {
        RemoteAppInfo.formatAppKey(pkg: appPackage, userId: appUserId)
    }

    public var isSecondaryProfile: Bool {
        appUserId > 0
    }

    public var profileBadgeText: String? {
        RemoteAppInfo.profileBadgeText(userId: appUserId, profileType: appProfileType)
    }

    public let session: OpalCastSession
    public let streamBridge: StreamSessionBridge
    public let videoRenderer: VideoDisplayRenderer
    public let audioPlayer: AudioStreamPlayer
    public let screenRecorder = WindowScreenRecorder()

    public var sessionState: OpalCastSessionState = .idle
    public var isAuthenticated: Bool = false
    public var isWaitingForApproval: Bool = false
    public var isMigratingConnection: Bool = false
    public var isAudioMuted: Bool
    public var deviceState = DeviceStatePayload()
    public var videoMetrics = VideoRendererMetrics()
    public var rttMs: Double = 0
    public var errorMessage: String?

    public var isPinnedOnTop: Bool
    public var isFavorite: Bool
    public var windowOpacity: Double = DisplayPresets.popoutOpacityRange.upperBound
    public var activePreset: PopoutAspectPreset? = .phone
    public var customDpi: Int?
    public var isCompatibilityResolution: Bool
    public var mapCommandToCtrl: Bool
    public var enableEdgeNavigationGestures: Bool
    public var canReturnToDesktop: Bool

    public var isRecording: Bool = false
    public var recordingDurationSeconds: TimeInterval = 0
    public var toastNotice: String?
    public var toastRevealURL: URL?

    public weak var hostWindow: NSWindow?
    public var onRequestClose: (() -> Void)?
    public var onPinStateChanged: ((Bool) -> Void)?
    public var onPreferenceChanged: ((PopoutAppPreferenceChange) -> Void)?
    public var onAuthTokenIssued: ((_ deviceName: String, _ fingerprint: String, _ authToken: String) -> Void)?

    @ObservationIgnored private var connectionContext: PopoutConnectionContext
    @ObservationIgnored private var resizeDebounceTask: Task<Void, Never>?
    @ObservationIgnored private var firstFrameNudgeTask: Task<Void, Never>?
    @ObservationIgnored private var toastClearTask: Task<Void, Never>?
    @ObservationIgnored private var lastSentGeometry: PopoutStreamGeometry?
    @ObservationIgnored private var lastCanvasSize: CGSize = PopoutAspectPreset.phone.windowSize
    @ObservationIgnored private var lastPersistedWindowSize: CGSize = PopoutAspectPreset.phone.windowSize
    @ObservationIgnored private var lastBackingScale: CGFloat = 2.0
    @ObservationIgnored private var isClosing: Bool = false

    public init(
        appPackage: String,
        appActivity: String,
        appLabel: String,
        appUserId: Int = 0,
        appProfileType: String = "",
        deviceKey: String,
        deviceName: String,
        taskId: Int = -1,
        initialPinnedOnTop: Bool = false,
        initialWindowSize: CGSize = PopoutAspectPreset.phone.windowSize,
        initialPreference: PopoutAppPreference? = nil,
        canReturnToDesktop: Bool = false,
        connectionContext: PopoutConnectionContext,
        trustStore: TrustStore
    ) {
        let castSession = OpalCastSession(trustStore: trustStore)
        let normalizedUid = max(0, appUserId)
        self.appPackage = appPackage
        self.appActivity = appActivity
        self.appLabel = appLabel.isEmpty ? appPackage : appLabel
        self.appUserId = normalizedUid
        self.appProfileType = RemoteAppInfo.normalizeProfileType(appProfileType, userId: normalizedUid)
        self.deviceKey = deviceKey
        self.deviceName = deviceName
        self.taskId = taskId
        self.isPinnedOnTop = initialPreference?.pinnedOnTop ?? initialPinnedOnTop
        self.isFavorite = initialPreference?.isFavorite ?? false
        self.customDpi = initialPreference?.customDpi.map(PopoutCompatibilityResolution.clampCustomDpi)
        self.isCompatibilityResolution = initialPreference?.compatibilityResolution ?? false
        self.activePreset = PopoutAspectPreset.fromPreferenceKey(initialPreference?.preferredPreset)
            ?? Self.matchingPreset(for: initialWindowSize)
        self.lastCanvasSize = initialWindowSize
        self.lastPersistedWindowSize = initialWindowSize
        self.canReturnToDesktop = canReturnToDesktop
        self.connectionContext = connectionContext
        self.mapCommandToCtrl = connectionContext.mapCommandToCtrl
        self.enableEdgeNavigationGestures = connectionContext.enableEdgeNavigationGestures
        self.isAudioMuted = !connectionContext.audioEnabled
        self.session = castSession
        self.streamBridge = StreamSessionBridge(session: castSession, logger: OpalDeskLog.popout)
        self.videoRenderer = VideoDisplayRenderer()
        let player = AudioStreamPlayer()
        player.setMuted(!connectionContext.audioEnabled)
        self.audioPlayer = player
    }

    public var currentVideoWidth: Int {
        if videoMetrics.width > 0 { return videoMetrics.width }
        if deviceState.width > 0 { return deviceState.width }
        return Tuning.defaultVideoWidth
    }

    public var currentVideoHeight: Int {
        if videoMetrics.height > 0 { return videoMetrics.height }
        if deviceState.height > 0 { return deviceState.height }
        return Tuning.defaultVideoHeight
    }

    public var isShowingLoadingOverlay: Bool {
        if errorMessage != nil { return true }
        if isMigratingConnection && videoMetrics.totalFramesDecoded > 0 { return false }
        return !isAuthenticated || videoMetrics.totalFramesDecoded == 0
    }

    /// Returns `true` when this popout session targets localhost on `port` (e.g., an ADB forward).
    public func isUsingLocalPort(_ port: UInt16) -> Bool {
        guard case let .hostPort(host, endpointPort) = connectionContext.endpoint.target else { return false }
        return (host == "127.0.0.1" || host == "localhost") && endpointPort == port
    }

    public func start() {
        errorMessage = nil
        firstFrameNudgeTask?.cancel()
        applyWindowPinLevel()
        videoRenderer.setCallbacks(
            onRequestIDR: { [weak self] in self?.session.send(.requestIdr()) },
            onMetricsUpdate: { [weak self] metrics in
                Task { @MainActor [weak self] in
                    self?.videoMetrics = metrics
                    if metrics.totalFramesDecoded > 0 {
                        self?.firstFrameNudgeTask?.cancel()
                        self?.firstFrameNudgeTask = nil
                    }
                }
            }
        )
        OpalDeskLog.info("popout", "Starting standalone window pkg=\(appPackage) device=\(deviceName) pinned=\(isPinnedOnTop)")
        connectCurrentEndpoint()
    }

    public func retryStart() {
        guard !isClosing else { return }
        OpalDeskLog.info("popout", "Retrying standalone window pkg=\(appPackage)")
        session.disconnect()
        videoRenderer.reset()
        audioPlayer.stop()
        isAuthenticated = false
        isWaitingForApproval = false
        isMigratingConnection = false
        errorMessage = nil
        lastSentGeometry = nil
        start()
    }

    /// Seamlessly migrates this popout window to a newly upgraded transport context (e.g. Wireless ADB -> Wi-Fi TLS).
    public func migrateConnectionContext(to newContext: PopoutConnectionContext) {
        guard !isClosing else { return }
        guard connectionContext.endpoint != newContext.endpoint || connectionContext.authToken != newContext.authToken else {
            return
        }
        OpalDeskLog.info("popout", "Migrating popout \(appPackage) to \(newContext.transportHint.rawValue)")
        connectionContext = newContext
        mapCommandToCtrl = newContext.mapCommandToCtrl
        isMigratingConnection = true
        errorMessage = nil
        firstFrameNudgeTask?.cancel()
        firstFrameNudgeTask = nil
        lastSentGeometry = nil
        connectCurrentEndpoint()
    }

    // MARK: - Window & Remote Controls

    public func togglePinnedOnTop() {
        setPinnedOnTop(!isPinnedOnTop)
    }

    public func setPinnedOnTop(_ pinned: Bool) {
        isPinnedOnTop = pinned
        applyWindowPinLevel()
        onPinStateChanged?(pinned)
        onPreferenceChanged?(.pinnedOnTop(pinned))
        OpalDeskLog.info("popout", "Set always-on-top=\(pinned) for pkg=\(appPackage)")
    }

    public func toggleFavorite() {
        setFavorite(!isFavorite)
    }

    public func setFavorite(_ favorite: Bool) {
        guard isFavorite != favorite else { return }
        isFavorite = favorite
        onPreferenceChanged?(.favorite(favorite))
        showToast(favorite ? OpalL10n.text("app.popout.fav_added") : OpalL10n.text("app.popout.fav_removed"))
        OpalDeskLog.info("popout", "Set favorite=\(favorite) for pkg=\(appPackage)")
    }

    public func applyWindowPinLevel() {
        hostWindow?.level = isPinnedOnTop ? .floating : .normal
    }

    public func setWindowOpacity(_ value: Double) {
        let clamped = DisplayPresets.clampPopoutOpacity(value)
        windowOpacity = clamped
        hostWindow?.alphaValue = CGFloat(clamped)
    }

    public func setCustomDpi(_ dpi: Int?) {
        let clamped = dpi.map(PopoutCompatibilityResolution.clampCustomDpi)
        guard customDpi != clamped else { return }
        customDpi = clamped
        onPreferenceChanged?(.customDpi(clamped))
        if isAuthenticated {
            let geom = computeStreamGeometry(viewSize: lastCanvasSize, backingScale: lastBackingScale)
            sendResizeIfNeeded(geom, force: true)
        }
        if let clamped {
            showToast(OpalL10n.format("app.popout.custom_dpi_fmt", clamped))
        } else {
            showToast(OpalL10n.text("app.popout.auto_dpi"))
        }
    }

    public func setCompatibilityResolution(_ enabled: Bool) {
        guard isCompatibilityResolution != enabled else { return }
        isCompatibilityResolution = enabled
        onPreferenceChanged?(.compatibilityResolution(enabled))
        if isAuthenticated {
            let geom = computeStreamGeometry(viewSize: lastCanvasSize, backingScale: lastBackingScale)
            sendResizeIfNeeded(geom, force: true)
        }
        showToast(enabled ? OpalL10n.text("app.popout.compat_on") : OpalL10n.text("app.popout.compat_off"))
    }

    public func applyAspectPreset(_ preset: PopoutAspectPreset) {
        activePreset = preset
        let targetSize = preset.windowSize
        lastCanvasSize = targetSize
        lastPersistedWindowSize = targetSize
        onPreferenceChanged?(.preset(preset.preferenceKey))

        if let window = hostWindow {
            let currentFrame = window.frame
            var newOrigin = CGPoint(
                x: currentFrame.midX - targetSize.width * 0.5,
                y: currentFrame.maxY - targetSize.height
            )
            if let visible = (window.screen ?? NSScreen.main)?.visibleFrame {
                let pad = Tuning.screenEdgePaddingPt
                newOrigin.x = min(max(newOrigin.x, visible.minX + pad), visible.maxX - targetSize.width - pad)
                newOrigin.y = min(max(newOrigin.y, visible.minY + pad), visible.maxY - targetSize.height - pad)
            }
            window.setFrame(NSRect(origin: newOrigin, size: targetSize), display: true, animate: true)
            lastBackingScale = window.backingScaleFactor
        }

        resizeDebounceTask?.cancel()
        if isAuthenticated {
            let geom = computeStreamGeometry(viewSize: targetSize, backingScale: lastBackingScale)
            sendResizeIfNeeded(geom, force: true)
        }
    }

    public func handleCanvasResize(viewSize: CGSize, backingScale: CGFloat) {
        guard viewSize.width >= Tuning.minCanvasDimension, viewSize.height >= Tuning.minCanvasDimension else { return }
        lastCanvasSize = viewSize
        lastBackingScale = backingScale
        let matchedPreset = Self.matchingPreset(for: viewSize)
        activePreset = matchedPreset

        resizeDebounceTask?.cancel()
        resizeDebounceTask = Task { [weak self] in
            guard await sleepCancellable(nanoseconds: Tuning.resizeDebounceNs), let self else { return }
            let sizeChanged = abs(viewSize.width - self.lastPersistedWindowSize.width) > 2
                || abs(viewSize.height - self.lastPersistedWindowSize.height) > 2
            if sizeChanged {
                self.lastPersistedWindowSize = viewSize
                if let matched = Self.matchingPreset(for: viewSize) {
                    self.onPreferenceChanged?(.preset(matched.preferenceKey))
                } else {
                    self.onPreferenceChanged?(.customWindowSize(
                        widthPt: Double(viewSize.width.rounded()),
                        heightPt: Double(viewSize.height.rounded())
                    ))
                }
            }
            guard self.isAuthenticated else { return }
            let geom = self.computeStreamGeometry(viewSize: viewSize, backingScale: backingScale)
            self.sendResizeIfNeeded(geom, force: false)
        }
    }

    public func toggleAudioMute() {
        isAudioMuted.toggle()
        audioPlayer.setMuted(isAudioMuted)
        streamBridge.sendSystemAction(.setAudioEnabled, arg: isAudioMuted ? 0 : 1, isAuthenticated: isAuthenticated)
        OpalDeskLog.info("popout", "Set audioMuted=\(isAudioMuted) for pkg=\(appPackage)")
    }

    public func reloadAppDPI() {
        streamBridge.sendWindowAction(.reloadAppDpi(pkg: appPackage, userId: appUserId), isAuthenticated: isAuthenticated)
    }

    public func sendAndroidBack() {
        streamBridge.sendSystemAction(.back, isAuthenticated: isAuthenticated)
    }

    public func requestIDR() {
        guard isAuthenticated else { return }
        streamBridge.sendRequestIDR()
    }

    // MARK: - Popout Window Screenshot & MP4 Recording

    public var formattedRecordingDuration: String {
        AppModel.formatRecordingDuration(recordingDurationSeconds)
    }

    public func captureScreenshot() {
        guard isAuthenticated else { return }
        let renderer = videoRenderer
        let cleanLabel = appLabel
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
        let prefix = "OpalDesk-\(cleanLabel)-Screenshot"
        Task { @MainActor [weak self] in
            guard let self else { return }
            guard let image = await renderer.captureCurrentFrameCGImageAsync(timeoutSeconds: 2.0) else {
                self.showToast(OpalL10n.text("app.popout.no_frame"))
                return
            }
            do {
                let savedURL = try WindowMediaCapture.saveImageToFile(image, prefix: prefix)
                _ = try? WindowMediaCapture.writeImageToClipboard(image)
                self.showToast(OpalL10n.text("app.popout.screenshot_saved"), revealURL: savedURL)
            } catch {
                self.showToast(OpalL10n.format("app.popout.screenshot_failed_fmt", error.localizedDescription))
            }
        }
    }

    public func toggleRecording() {
        if isRecording {
            stopRecording()
        } else {
            startRecording()
        }
    }

    public func startRecording() {
        guard isAuthenticated, !screenRecorder.isRecording else { return }
        let cleanLabel = appLabel
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
        let prefix = "OpalDesk-\(cleanLabel)-Recording"
        do {
            _ = try screenRecorder.start(
                renderer: videoRenderer,
                prefix: prefix,
                format: .mp4,
                onMetricsUpdate: { [weak self] metrics in
                    Task { @MainActor [weak self] in
                        guard let self else { return }
                        self.recordingDurationSeconds = metrics.durationSeconds
                        self.isRecording = metrics.state == .waitingForKeyframe || metrics.state == .recording
                    }
                }
            )
            isRecording = true
            recordingDurationSeconds = 0
            showToast(OpalL10n.text("app.popout.rec_started"))
        } catch {
            isRecording = false
            showToast(OpalL10n.format("app.popout.rec_start_failed_fmt", error.localizedDescription))
        }
    }

    public func stopRecording() {
        guard screenRecorder.isRecording else {
            isRecording = false
            recordingDurationSeconds = 0
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let result = try await self.screenRecorder.stop()
                self.isRecording = false
                self.recordingDurationSeconds = 0
                let dur = AppModel.formatRecordingDuration(result.durationSeconds)
                self.showToast(OpalL10n.format("app.popout.rec_saved_fmt", dur), revealURL: result.url)
            } catch {
                self.isRecording = false
                self.recordingDurationSeconds = 0
                self.showToast(OpalL10n.format("app.popout.rec_stop_failed_fmt", error.localizedDescription))
            }
        }
    }

    public func showToast(_ message: String, revealURL: URL? = nil, durationSeconds: Double = 3.2) {
        toastClearTask?.cancel()
        toastNotice = message
        toastRevealURL = revealURL
        let duration = revealURL != nil ? max(durationSeconds, 5.5) : durationSeconds
        toastClearTask = Task { [weak self] in
            guard await sleepCancellable(nanoseconds: UInt64(duration * 1_000_000_000)) else { return }
            self?.toastNotice = nil
            self?.toastRevealURL = nil
        }
    }

    public func returnToDesktop() {
        guard !isClosing else { return }
        Task { [weak self] in
            guard let self else { return }
            if self.isAuthenticated {
                self.streamBridge.sendWindowAction(
                    .returnToDesktop(pkg: self.appPackage, taskId: self.taskId, userId: self.appUserId),
                    isAuthenticated: true
                )
                guard await sleepCancellable(nanoseconds: Tuning.returnToDesktopFlushNs) else { return }
            }
            self.close(sendDisconnect: false)
        }
    }

    public func close(sendDisconnect: Bool = true) {
        guard !isClosing else { return }
        isClosing = true
        resizeDebounceTask?.cancel()
        resizeDebounceTask = nil
        firstFrameNudgeTask?.cancel()
        firstFrameNudgeTask = nil
        toastClearTask?.cancel()
        toastClearTask = nil
        if screenRecorder.isRecording {
            screenRecorder.cancel()
        }
        isRecording = false
        recordingDurationSeconds = 0

        if sendDisconnect {
            streamBridge.sendSystemAction(.disconnect, isAuthenticated: isAuthenticated)
        }
        session.disconnect()
        videoRenderer.reset()
        audioPlayer.stop()
        isAuthenticated = false
        isWaitingForApproval = false
        isMigratingConnection = false
        OpalDeskLog.info("popout", "Closed standalone window pkg=\(appPackage)")

        let callback = onRequestClose
        onRequestClose = nil
        callback?()
    }

    // MARK: - Private Stream Pipeline

    private func connectCurrentEndpoint() {
        let handler = StreamSessionBridge.makeEventHandler(
            videoRenderer: videoRenderer,
            audioPlayer: audioPlayer,
            onControlEvent: { [weak self] event in self?.handleSessionEvent(event) }
        )
        session.connect(to: connectionContext.endpoint, onEvent: handler)
    }

    private func handleSessionEvent(_ event: OpalCastSessionEvent) {
        guard !isClosing else { return }
        switch event {
        case .stateChanged(let newState):
            sessionState = newState
            switch newState {
            case .connected:
                sendStandaloneHello()
            case .failed(let message):
                audioPlayer.stop()
                isAuthenticated = false
                isWaitingForApproval = false
                isMigratingConnection = false
                errorMessage = message
                OpalDeskLog.warn("popout", "Standalone window failed pkg=\(appPackage): \(message)")
            case .disconnected:
                if !isMigratingConnection {
                    audioPlayer.stop()
                    isAuthenticated = false
                    isWaitingForApproval = false
                }
            default:
                break
            }
        case .rttUpdated(let rtt):
            rttMs = rtt
        case .frame(let frame):
            handleControlFrame(frame)
        case .error(let err):
            if case .certificateFingerprintMismatch = err {
                isMigratingConnection = false
                isWaitingForApproval = false
                errorMessage = err.localizedDescription
                OpalDeskLog.error("popout", "Standalone TLS mismatch pkg=\(appPackage): \(err.localizedDescription)")
            }
        case .verifiedPeerFingerprint:
            break
        }
    }

    private func sendStandaloneHello() {
        let scale = hostWindow?.backingScaleFactor ?? lastBackingScale
        lastBackingScale = scale
        let geom = computeStreamGeometry(viewSize: lastCanvasSize, backingScale: scale)
        lastSentGeometry = geom
        isWaitingForApproval = connectionContext.transportHint == .wifiTls && connectionContext.authToken.isEmpty

        streamBridge.sendHello(HelloPayload(
            clientName: StreamSessionBridge.localClientName,
            clientId: connectionContext.clientId,
            preferredCodec: connectionContext.preferredCodec.preferredName,
            width: geom.width,
            height: geom.height,
            dpi: geom.dpi,
            fps: min(connectionContext.targetFPS, 60),
            bitrateKbps: min(connectionContext.bitrateKbps, Tuning.maxBitrateKbps),
            displayMode: Int(OpalCastDisplayMode.dex.rawValue),
            audioEnabled: !isAudioMuted,
            authToken: connectionContext.authToken,
            transportHint: connectionContext.transportHint.rawValue,
            sessionRole: OpalCastSessionRole.standaloneApp.rawValue,
            appPackage: appPackage,
            appActivity: appActivity,
            appUserId: appUserId > 0 ? appUserId : nil
        ))
    }

    private func handleControlFrame(_ frame: OpalCastFrame) {
        switch frame.type {
        case .authResult:
            guard let auth = StreamSessionBridge.decodePayload("popout AUTH_RESULT", logger: OpalDeskLog.popout, {
                try AuthResultPayload.decode(from: frame.payload)
            }) else { return }
            isWaitingForApproval = false
            if auth.ok {
                isAuthenticated = true
                isMigratingConnection = false
                errorMessage = nil
                if !auth.deviceName.isEmpty {
                    deviceName = auth.deviceName
                }
                if auth.permanent && !auth.authToken.isEmpty {
                    let fp = session.verifiedPeerFingerprint ?? auth.certFingerprint
                    onAuthTokenIssued?(deviceName, fp, auth.authToken)
                }
                let geom = computeStreamGeometry(
                    viewSize: lastCanvasSize,
                    backingScale: hostWindow?.backingScaleFactor ?? lastBackingScale
                )
                sendResizeIfNeeded(geom, force: false)
                if videoMetrics.totalFramesDecoded == 0 { startFirstFrameNudge() }
            } else {
                isAuthenticated = false
                isMigratingConnection = false
                errorMessage = auth.message.isEmpty ? OpalL10n.text("app.popout.auth_failed") : auth.message
                OpalDeskLog.warn("popout", "Standalone auth rejected pkg=\(appPackage): \(errorMessage ?? "")")
            }

        case .deviceState:
            guard let state = StreamSessionBridge.decodePayload("popout DEVICE_STATE", logger: OpalDeskLog.popout, {
                try DeviceStatePayload.decode(from: frame.payload)
            }) else { return }
            deviceState = state
            if !state.topAppLabel.isEmpty && (appLabel.isEmpty || appLabel == appPackage) {
                appLabel = state.topAppLabel
            }

        default:
            break
        }
    }

    private func startFirstFrameNudge() {
        firstFrameNudgeTask?.cancel()
        firstFrameNudgeTask = Task { [weak self] in
            for delayNs in Tuning.firstFrameNudgeDelaysNs {
                guard await sleepCancellable(nanoseconds: delayNs),
                      let self, self.isAuthenticated, self.videoMetrics.totalFramesDecoded == 0 else { return }
                self.streamBridge.sendRequestIDR()
            }
            guard await sleepCancellable(nanoseconds: Tuning.firstFrameTimeoutNs),
                  let self, self.isAuthenticated, self.videoMetrics.totalFramesDecoded == 0 else { return }
            self.errorMessage = OpalL10n.text("app.popout.start_timeout")
        }
    }

    private func computeStreamGeometry(viewSize: CGSize, backingScale: CGFloat) -> PopoutStreamGeometry {
        let pref = PopoutAppPreference(
            preferredPreset: activePreset?.preferenceKey,
            pinnedOnTop: isPinnedOnTop,
            customDpi: customDpi,
            compatibilityResolution: isCompatibilityResolution
        )
        return PopoutCompatibilityResolution.resolveStreamGeometry(
            preference: pref,
            viewSize: viewSize,
            backingScale: backingScale
        )
    }

    private func sendResizeIfNeeded(_ geom: PopoutStreamGeometry, force: Bool) {
        guard force || lastSentGeometry != geom else { return }
        lastSentGeometry = geom
        streamBridge.sendResize(
            width: geom.width,
            height: geom.height,
            dpi: geom.dpi,
            fps: min(connectionContext.targetFPS, 60),
            bitrateKbps: min(connectionContext.bitrateKbps, Tuning.maxBitrateKbps)
        )
    }

    private static func matchingPreset(for size: CGSize) -> PopoutAspectPreset? {
        PopoutAspectPreset.allCases.first { preset in
            abs(size.width - preset.windowSize.width) <= Tuning.presetMatchTolerancePt
                && abs(size.height - preset.windowSize.height) <= Tuning.presetMatchTolerancePt
        }
    }
}
