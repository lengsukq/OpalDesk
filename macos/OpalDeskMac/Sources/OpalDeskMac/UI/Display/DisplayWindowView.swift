import AppKit
import OpalDeskCore
import SwiftUI

/// Pure OpalDesk casting viewport: edge-to-edge projected Android display canvas,
/// Liquid Glass floating top hover bar, optional telemetry HUD, unified file-drop stage,
/// and disconnect/reconnect recovery overlays.
public struct DisplayWindowView: View {
    @Bindable public var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var isDragOverCanvas: Bool = false
    @State private var isInjectWarningDismissed: Bool = false
    @State private var isHoveringTopEdge: Bool = false
    @State private var canvasFocusGeneration: Int = 0

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        let _ = model.preferences.appLanguage
        canvasStage
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
            .background(DeXTheme.canvasBackground)
            .preferredColorScheme(.dark)
            .background(WindowChromeConfigurator(isImmersiveCanvas: true) { window in
                model.mainDisplayWindow = window
            })
            .animation(OpalMotion.animation(OpalMotion.smooth, reduceMotion: reduceMotion), value: model.toastNotice)
            .animation(OpalMotion.animation(OpalMotion.smooth, reduceMotion: reduceMotion), value: model.injectStatus)
            .animation(OpalMotion.animation(OpalMotion.quick, reduceMotion: reduceMotion), value: isHoveringTopEdge)
            .sheet(item: $model.remoteAppPickerContext) { ctx in
                RemoteAppPickerSheet(model: model, context: ctx) {
                    model.closeRemoteAppPicker()
                }
            }
            .confirmationDialog(
                OpalL10n.format("hub.popout_open_title_fmt", model.pendingPopoutReloadPrompt?.appLabel ?? ""),
                isPresented: Binding(
                    get: { model.pendingPopoutReloadPrompt != nil },
                    set: { if !$0 { model.pendingPopoutReloadPrompt = nil } }
                ),
                titleVisibility: .visible
            ) {
                if let prompt = model.pendingPopoutReloadPrompt {
                    Button(OpalL10n.text("hub.popout_reload_btn")) {
                        model.pendingPopoutReloadPrompt = nil
                        prompt.onReloadWindowAndApp()
                    }
                    Button(OpalL10n.text("hub.popout_focus_btn")) {
                        model.pendingPopoutReloadPrompt = nil
                        prompt.onFocusExisting()
                    }
                    Button(OpalL10n.text("common.cancel"), role: .cancel) {
                        model.pendingPopoutReloadPrompt = nil
                    }
                }
            } message: {
                Text(OpalL10n.text("hub.popout_open_message"))
            }
    }

    // MARK: - Canvas Stage

    private var canvasStage: some View {
        ZStack {
            DeXTheme.canvasBackground

            InteractiveDisplayNSView(
                videoRenderer: model.videoRenderer,
                config: DisplayCanvasConfig(
                    videoWidth: model.currentVideoWidth,
                    videoHeight: model.currentVideoHeight,
                    mapCommandToCtrl: model.preferences.mapCommandToCtrl,
                    isCanvasInteractive: model.isAuthenticated && model.videoMetrics.totalFramesDecoded > 0,
                    focusGeneration: canvasFocusGeneration,
                    hideLocalCursor: model.preferences.hideLocalCursor,
                    enableEdgeNavigationGestures: model.preferences.enableEdgeNavigationGestures
                ),
                callbacks: model.streamBridge.makeCanvasCallbacks(
                    isAuthenticated: { model.isAuthenticated },
                    toolbar: nil,
                    onFilesDropped: { model.handleDroppedFiles($0) },
                    onDragHoverChange: { hovering in
                        withAnimation(OpalMotion.animation(OpalMotion.hover, reduceMotion: reduceMotion)) {
                            isDragOverCanvas = hovering
                        }
                    },
                    onResize: { size, scale in model.handleCanvasResize(viewSize: size, backingScale: scale) }
                )
            )
            .onChange(of: model.injectStatus) { _, newStatus in
                if newStatus != .denied { isInjectWarningDismissed = false }
            }
            .onChange(of: isHoveringTopEdge) { wasHovering, isHovering in
                if wasHovering && !isHovering {
                    canvasFocusGeneration &+= 1
                }
            }

            topHoverControlBar

            if model.isMainWindowRecording && !isHoveringTopEdge {
                VStack {
                    HStack {
                        recordingStatusPill
                        Spacer()
                    }
                    Spacer()
                }
                .padding(OpalSpacing.md)
            }

            if model.preferences.showHUDStats && model.isAuthenticated {
                VStack {
                    HStack {
                        Spacer()
                        compactCornerHUD
                    }
                    Spacer()
                }
                .padding(OpalSpacing.md)
                .allowsHitTesting(false)
            }

            if !model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0 {
                connectionOverlay
            }

            if isDragOverCanvas {
                OpalDropZoneOverlay(
                    icon: "arrow.down.doc.fill",
                    title: OpalL10n.text("display.drop.title"),
                    subtitle: OpalL10n.text("display.drop.subtitle")
                )
            }

            notificationsOverlay
        }
    }

    // MARK: - Liquid Glass Floating Top Control Bar

    private var topHoverControlBar: some View {
        VStack(spacing: 0) {
            VStack(spacing: OpalSpacing.xxs) {
                ZStack(alignment: .top) {
                    Color.clear
                        .frame(height: 18)
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())

                    if !isHoveringTopEdge {
                        Capsule()
                            .fill(.white.opacity(0.38))
                            .overlay(Capsule().strokeBorder(.black.opacity(0.28), lineWidth: 0.6))
                            .frame(width: 44, height: 4)
                            .padding(.top, 5)
                            .transition(.opacity)
                    }
                }

                if isHoveringTopEdge {
                    HStack(spacing: 5) {
                        if let conn = model.activeConnection {
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(model.isAuthenticated ? Color.green : Color.orange)
                                    .frame(width: 6, height: 6)
                                Text(conn.deviceName)
                                    .font(.system(size: 11.5, weight: .semibold))
                                    .foregroundStyle(.white.opacity(0.92))
                                    .lineLimit(1)
                            }
                            .padding(.leading, 4)
                            topBarDivider
                        }

                        mediaAndCaptureControls
                        topBarDivider
                        workspaceAndDisplayControls
                        topBarDivider
                        sessionExitControls
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .opalFloatingGlassCapsule()
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .onHover { isHoveringTopEdge = $0 }

            Spacer()
        }
    }

    @ViewBuilder
    private var mediaAndCaptureControls: some View {
        OpalGlassIconButton(
            systemName: model.isMicEnabled ? "mic.fill" : "mic.slash",
            tint: model.isMicEnabled ? .green : .white,
            isActive: model.isMicEnabled,
            helpText: model.isMicEnabled ? OpalL10n.text("display.mic.off_help") : OpalL10n.text("display.mic.on_help")
        ) { model.toggleMicrophone() }

        OpalGlassIconButton(
            systemName: model.isCameraEnabled ? "video.fill" : "video.slash",
            tint: model.isCameraEnabled ? .green : .white,
            isActive: model.isCameraEnabled,
            helpText: model.isCameraEnabled ? OpalL10n.text("display.cam.off_help") : OpalL10n.text("display.cam.on_help")
        ) { model.toggleCamera() }

        OpalGlassIconButton(
            systemName: "camera",
            tint: .white,
            helpText: OpalL10n.text("display.screenshot_help")
        ) { model.captureMainWindowScreenshot() }
            .disabled(!model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0)

        Button {
            model.toggleMainWindowRecording()
        } label: {
            if model.isMainWindowRecording {
                HStack(spacing: 4) {
                    Circle().fill(Color.red).frame(width: 7, height: 7)
                    Text(model.formattedRecordingDuration)
                        .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                }
                .padding(.horizontal, 8)
                .frame(height: OpalControlSize.iconButtonCompact)
                .background(Color.red.opacity(0.24), in: Capsule())
                .foregroundStyle(.red)
            } else {
                Image(systemName: "record.circle")
                    .font(.system(size: 12, weight: .semibold))
                    .frame(width: OpalControlSize.iconButtonCompact, height: OpalControlSize.iconButtonCompact)
                    .foregroundStyle(.white.opacity(0.92))
            }
        }
        .buttonStyle(.plain)
        .disabled(!model.isAuthenticated || (!model.isMainWindowRecording && model.videoMetrics.totalFramesDecoded == 0))
        .help(model.isMainWindowRecording ? OpalL10n.text("display.rec.stop_help") : OpalL10n.text("display.rec.start_help"))
        .accessibilityLabel(model.isMainWindowRecording ? OpalL10n.text("display.rec.stop_a11y") : OpalL10n.text("display.rec.start_a11y"))

        OpalGlassIconButton(
            systemName: "folder",
            tint: .white,
            helpText: OpalL10n.text("hub.tool.files_help")
        ) {
            openWindow(id: WindowID.fileBrowser)
            NSApp.activate(ignoringOtherApps: true)
        }
        .disabled(!model.isAnyChannelConnected)
    }

    @ViewBuilder
    private var workspaceAndDisplayControls: some View {
        if model.displayMode == .dex && !model.deviceState.fullscreenWindowTitle.isEmpty {
            Button {
                model.sendAndroidHome()
            } label: {
                Label(OpalL10n.text("display.exit_fullscreen"), systemImage: "arrow.down.right.and.arrow.up.left")
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .mini, isPill: true))
            .help(OpalL10n.format("display.exit_fullscreen_help_fmt", model.deviceState.fullscreenWindowTitle))
        }

        floatingTextPillButton(
            title: model.activePopoutCount > 0
                ? OpalL10n.format("display.popout_count_fmt", model.activePopoutCount, PopoutWindowManager.maxConcurrentWindows)
                : OpalL10n.text("hub.row.standalone_window"),
            systemImage: "macwindow.on.rectangle",
            isActive: model.activePopoutCount > 0,
            tint: .cyan,
            help: OpalL10n.text("display.popout_help")
        ) { model.openRemoteAppPicker(for: .activeDesktop) }
            .disabled(!model.isAuthenticated)

        floatingTextPillButton(
            title: model.displayMode == .dex ? OpalL10n.text("hub.mode.mirror") : OpalL10n.text("hub.mode.dex"),
            systemImage: model.displayMode == .dex ? "rectangle.on.rectangle" : "macwindow",
            isActive: false,
            tint: .white,
            help: model.displayMode == .dex ? OpalL10n.text("display.mode.to_mirror_help") : OpalL10n.text("display.mode.to_dex_help")
        ) { model.toggleDisplayMode() }

        floatingTextPillButton(
            title: model.isBlackoutActive ? OpalL10n.text("display.screen_on_short") : OpalL10n.text("display.screen_off_short"),
            systemImage: model.isBlackoutActive ? "iphone" : "iphone.slash",
            isActive: model.isBlackoutActive,
            tint: .blue,
            help: model.isBlackoutActive ? OpalL10n.text("display.screen_on_help") : OpalL10n.text("display.screen_off_help")
        ) { model.toggleBlackout() }

        floatingTextPillButton(
            title: model.preferences.hideLocalCursor ? OpalL10n.text("display.cursor.single") : OpalL10n.text("display.cursor.local"),
            systemImage: model.preferences.hideLocalCursor ? "cursorarrow.slash" : "cursorarrow",
            isActive: model.preferences.hideLocalCursor,
            tint: .cyan,
            help: OpalL10n.text("display.cursor.help")
        ) { model.preferences.hideLocalCursor.toggle() }
    }

    @ViewBuilder
    private var sessionExitControls: some View {
        floatingTextPillButton(
            title: OpalL10n.text("display.disconnect"),
            systemImage: nil,
            isActive: false,
            tint: .white,
            help: OpalL10n.text("display.disconnect_help")
        ) { model.forceDisconnectAndReturnHome() }

        Button(role: .destructive) {
            model.forceTerminateApp()
        } label: {
            Text(OpalL10n.text("display.quit"))
                .font(.system(size: 11, weight: .semibold))
                .padding(.horizontal, 9)
                .frame(height: OpalControlSize.iconButtonCompact)
                .background(Color.red.opacity(0.84), in: Capsule())
                .foregroundStyle(.white)
        }
        .buttonStyle(.plain)
        .help(OpalL10n.text("display.quit_help"))
    }

    private func floatingTextPillButton(
        title: String,
        systemImage: String?,
        isActive: Bool,
        tint: Color,
        help: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 11, weight: .medium))
                }
                Text(title)
                    .font(.system(size: 11, weight: .medium))
            }
            .padding(.horizontal, 9)
            .frame(height: OpalControlSize.iconButtonCompact)
            .background(
                (isActive ? tint.opacity(0.24) : Color.white.opacity(0.10)),
                in: Capsule()
            )
            .foregroundStyle(isActive ? tint : .white.opacity(0.92))
        }
        .buttonStyle(.plain)
        .help(help)
    }

    private var topBarDivider: some View {
        Rectangle()
            .fill(.white.opacity(0.18))
            .frame(width: 1, height: 14)
            .padding(.horizontal, 2)
    }

    private var recordingStatusPill: some View {
        Button {
            model.stopMainWindowRecording()
        } label: {
            HStack(spacing: 6) {
                Circle().fill(Color.red).frame(width: 8, height: 8)
                Text("REC \(model.formattedRecordingDuration)")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white)
                Image(systemName: "stop.fill")
                    .font(.system(size: 9))
                    .foregroundStyle(.red)
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 5)
            .opalFloatingGlassCapsule(strokeColor: Color.red.opacity(0.68))
        }
        .buttonStyle(.plain)
        .help(OpalL10n.text("display.rec.pill_help"))
    }

    // MARK: - Optional Top-Right Performance HUD

    private var compactCornerHUD: some View {
        let fps = Int(model.videoMetrics.currentFPS.rounded())
        let rtt = Int(model.rttMs.rounded())
        let mbps = model.videoMetrics.bitrateKbps / 1000.0
        let codecName = model.videoMetrics.codec?.displayName ?? model.preferences.preferredCodec.displayName

        return HStack(spacing: 6) {
            Text("\(fps) fps")
            Text("·").foregroundStyle(.white.opacity(0.4))
            Text("\(rtt) ms")
            Text("·").foregroundStyle(.white.opacity(0.4))
            Text(String(format: "%.1f Mbps", mbps))
            Text("·").foregroundStyle(.white.opacity(0.4))
            Text("\(model.currentVideoWidth)×\(model.currentVideoHeight) \(codecName)")

            if model.injectStatus == .uhid {
                StatusBadgeView(
                    text: "UHID",
                    color: .green,
                    font: .system(size: 9.5, weight: .bold, design: .rounded),
                    horizontalPadding: 6,
                    verticalPadding: 1.5,
                    backgroundOpacity: 0.24
                )
            }
        }
        .font(.system(size: 10.5, weight: .medium, design: .monospaced))
        .foregroundStyle(.white.opacity(0.86))
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
        .opalFloatingGlassCapsule()
    }

    // MARK: - Bottom Notifications & Connection Stage Overlay

    private var notificationsOverlay: some View {
        VStack(spacing: 10) {
            Spacer()

            if let toast = model.toastNotice {
                ToastPillView(
                    message: toast,
                    actionTitle: model.toastActionTitle,
                    onAction: model.toastActionURL.map { url in
                        { model.revealInFinder(url) }
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if model.isAuthenticated && model.injectStatus == .denied && !isInjectWarningDismissed {
                InjectDeniedSmartBanner(
                    onSwitchToUHID: { model.setInputBackend(.uhid) },
                    onDismiss: { isInjectWarningDismissed = true }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if !model.activeTransfers.isEmpty {
                HStack {
                    Spacer()
                    FileTransferToastStack(
                        transfers: model.activeTransfers,
                        onCancel: { model.cancelFileTransfer($0) },
                        onDismiss: { model.dismissTransfer($0) }
                    )
                }
            }
        }
        .padding(OpalSpacing.lg)
    }

    private var connectionOverlay: some View {
        VStack(spacing: OpalSpacing.md) {
            ProgressView().controlSize(.large).tint(.white)

            Text(connectionStatusCaption)
                .font(.headline)
                .foregroundStyle(.white)

            if let detail = model.disconnectReason, !detail.isEmpty, model.isReconnecting {
                Text(OpalL10n.format("display.reason_fmt", detail))
                    .font(.caption)
                    .foregroundStyle(.orange.opacity(0.95))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 340)
            }

            if let conn = model.activeConnection {
                Text("\(conn.deviceName) · \(conn.transport.shortBadge)")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.72))
            }

            HStack(spacing: OpalSpacing.md) {
                Button(OpalL10n.text("display.disconnect_return")) { model.forceDisconnectAndReturnHome() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .regular, isPill: true))

                Button(OpalL10n.text("display.force_quit"), role: .destructive) { model.forceTerminateApp() }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .destructive, size: .regular, isPill: true))
            }
            .padding(.top, OpalSpacing.xxs)
        }
        .padding(28)
        .opalGlassSurface(.stageOverlay, cornerRadius: OpalRadius.stageOverlay)
    }

    private var connectionStatusCaption: String {
        switch model.sessionState {
        case .connecting: return OpalL10n.text("display.status.connecting")
        case .reconnecting(let attempt): return OpalL10n.format("display.status.reconnecting_fmt", attempt)
        case .connected: return model.isAuthenticated ? OpalL10n.text("display.status.waiting_frame") : OpalL10n.text("display.status.waiting_approval")
        case .failed(let msg): return OpalL10n.format("display.status.failed_fmt", msg)
        case .disconnected: return OpalL10n.text("display.status.disconnected")
        default: return OpalL10n.text("display.status.preparing")
        }
    }
}
