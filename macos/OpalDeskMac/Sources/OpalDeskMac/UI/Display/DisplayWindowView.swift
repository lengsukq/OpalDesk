import AppKit
import OpalDeskCore
import SwiftUI

/// Pure OpalDesk casting viewport: edge-to-edge projected Android display canvas,
/// optional top-right telemetry HUD, hover top-edge quick disconnect/exit pill,
/// file-drop target, and disconnect/reconnect recovery notices.
public struct DisplayWindowView: View {
    @Bindable public var model: AppModel

    @State private var isDragOverCanvas: Bool = false
    @State private var isInjectWarningDismissed: Bool = false
    @State private var isHoveringTopEdge: Bool = false
    @State private var canvasFocusGeneration: Int = 0

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        canvasStage
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
            .background(DeXTheme.canvasBackground)
            .preferredColorScheme(.dark)
            .background(WindowChromeConfigurator(isImmersiveCanvas: true) { window in
                model.mainDisplayWindow = window
            })
            .animation(.easeOut(duration: 0.2), value: model.toastNotice)
            .animation(.easeOut(duration: 0.2), value: model.injectStatus)
            .animation(.easeOut(duration: 0.16), value: isHoveringTopEdge)
            .sheet(item: $model.remoteAppPickerContext) { ctx in
                RemoteAppPickerSheet(model: model, context: ctx) {
                    model.closeRemoteAppPicker()
                }
            }
            .confirmationDialog(
                "应用「\(model.pendingPopoutReloadPrompt?.appLabel ?? "")」的小窗已打开",
                isPresented: Binding(
                    get: { model.pendingPopoutReloadPrompt != nil },
                    set: { if !$0 { model.pendingPopoutReloadPrompt = nil } }
                ),
                titleVisibility: .visible
            ) {
                if let prompt = model.pendingPopoutReloadPrompt {
                    Button("重载小窗+应用") {
                        model.pendingPopoutReloadPrompt = nil
                        prompt.onReloadWindowAndApp()
                    }
                    Button("仅聚焦现有窗口") {
                        model.pendingPopoutReloadPrompt = nil
                        prompt.onFocusExisting()
                    }
                    Button("取消", role: .cancel) {
                        model.pendingPopoutReloadPrompt = nil
                    }
                }
            } message: {
                Text("该应用当前已有运行中的独立悬浮小窗。选择「重载小窗+应用」将断开并重新拉起全新悬浮窗与应用实例。")
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
                    hideLocalCursor: model.preferences.hideLocalCursor
                ),
                callbacks: model.streamBridge.makeCanvasCallbacks(
                    isAuthenticated: { model.isAuthenticated },
                    toolbar: nil,
                    onFilesDropped: { model.sendFiles($0) },
                    onDragHoverChange: { hovering in
                        withAnimation(.easeInOut(duration: 0.15)) { isDragOverCanvas = hovering }
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

            if model.preferences.showHUDStats && model.isAuthenticated {
                VStack {
                    HStack {
                        Spacer()
                        compactCornerHUD
                    }
                    Spacer()
                }
                .padding(12)
                .allowsHitTesting(false)
            }

            if !model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0 {
                connectionOverlay
            }

            if isDragOverCanvas {
                fileDropOverlay
            }

            notificationsOverlay
        }
    }

    // MARK: - Hover Top Edge Disconnect / Force Quit Pill

    private var topHoverControlBar: some View {
        VStack(spacing: 0) {
            VStack(spacing: 4) {
                ZStack(alignment: .top) {
                    Color.clear
                        .frame(height: 18)
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())

                    if !isHoveringTopEdge {
                        Capsule()
                            .fill(.white.opacity(0.36))
                            .overlay(Capsule().strokeBorder(.black.opacity(0.28), lineWidth: 0.6))
                            .frame(width: 42, height: 4)
                            .padding(.top, 5)
                            .transition(.opacity)
                    }
                }

                if isHoveringTopEdge {
                    HStack(spacing: 6) {
                        if let conn = model.activeConnection {
                            Text(conn.deviceName)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(.white.opacity(0.88))
                                .lineLimit(1)
                            topBarDivider
                        }

                        Button {
                            model.toggleMicrophone()
                        } label: {
                            Image(systemName: model.isMicEnabled ? "mic.fill" : "mic.slash")
                                .font(.system(size: 11.5, weight: .medium))
                                .frame(width: 16, height: 14)
                        }
                        .buttonStyle(.bordered)
                        .tint(model.isMicEnabled ? .green : nil)
                        .controlSize(.small)
                        .help(model.isMicEnabled ? "关闭麦克风" : "开启麦克风")

                        Button {
                            model.toggleCamera()
                        } label: {
                            Image(systemName: model.isCameraEnabled ? "video.fill" : "video.slash")
                                .font(.system(size: 11.5, weight: .medium))
                                .frame(width: 16, height: 14)
                        }
                        .buttonStyle(.bordered)
                        .tint(model.isCameraEnabled ? .green : nil)
                        .controlSize(.small)
                        .help(model.isCameraEnabled ? "关闭摄像头" : "开启摄像头")

                        topBarDivider

                        if model.displayMode == .dex && !model.deviceState.fullscreenWindowTitle.isEmpty {
                            Button {
                                model.sendAndroidHome()
                            } label: {
                                Label("退出全屏", systemImage: "arrow.down.right.and.arrow.up.left")
                                    .font(.system(size: 11, weight: .semibold))
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(.blue)
                            .controlSize(.small)
                            .help("退出远程应用全屏（\(model.deviceState.fullscreenWindowTitle)）")
                        }

                        Button {
                            model.openRemoteAppPicker(for: .activeDesktop)
                        } label: {
                            Label(
                                model.activePopoutCount > 0
                                    ? "小窗 \(model.activePopoutCount)/\(PopoutWindowManager.maxConcurrentWindows)"
                                    : "应用小窗",
                                systemImage: "macwindow.on.rectangle"
                            )
                            .font(.system(size: 11, weight: .medium))
                        }
                        .buttonStyle(.bordered)
                        .tint(model.activePopoutCount > 0 ? .cyan : nil)
                        .controlSize(.small)
                        .disabled(!model.isAuthenticated)
                        .help("打开手机应用或弹出独立悬浮小窗（最多 3 个）")

                        Button {
                            model.toggleDisplayMode()
                        } label: {
                            Label(
                                model.displayMode == .dex ? "融合镜像" : "电脑桌面",
                                systemImage: model.displayMode == .dex ? "rectangle.on.rectangle" : "macwindow.on.rectangle"
                            )
                            .font(.system(size: 11, weight: .medium))
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .help(model.displayMode == .dex ? "切换为融合镜像" : "切换为电脑桌面")

                        Button {
                            model.toggleBlackout()
                        } label: {
                            Text(model.isBlackoutActive ? "亮屏" : "黑屏")
                                .font(.system(size: 11, weight: .medium))
                        }
                        .buttonStyle(.bordered)
                        .tint(model.isBlackoutActive ? .blue : nil)
                        .controlSize(.small)
                        .help(model.isBlackoutActive ? "点亮手机屏幕" : "熄灭手机屏幕")

                        Button {
                            model.preferences.hideLocalCursor.toggle()
                        } label: {
                            Label(
                                model.preferences.hideLocalCursor ? "单指针" : "本机指针",
                                systemImage: model.preferences.hideLocalCursor ? "cursorarrow.slash" : "cursorarrow"
                            )
                            .font(.system(size: 11, weight: .medium))
                        }
                        .buttonStyle(.bordered)
                        .tint(model.preferences.hideLocalCursor ? .cyan : nil)
                        .controlSize(.small)
                        .help("隐藏投屏画布上的 Mac 本机光标，避免与 Android 端指针重叠显示")

                        topBarDivider

                        Button {
                            model.forceDisconnectAndReturnHome()
                        } label: {
                            Text("断开")
                                .font(.system(size: 11, weight: .medium))
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .help("断开连接并返回主界面")

                        Button(role: .destructive) {
                            model.forceTerminateApp()
                        } label: {
                            Text("强制退出")
                                .font(.system(size: 11, weight: .semibold))
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.red.opacity(0.85))
                        .controlSize(.small)
                        .help("强制退出 OpalDesk")
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .floatingCapsuleChrome(shadowRadius: 10, shadowY: 4, shadowOpacity: 0.38)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .onHover { isHoveringTopEdge = $0 }

            Spacer()
        }
    }

    private var topBarDivider: some View {
        Rectangle()
            .fill(.white.opacity(0.18))
            .frame(width: 1, height: 14)
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
                    horizontalPadding: 5,
                    verticalPadding: 1,
                    backgroundOpacity: 0.24
                )
            }
        }
        .font(.system(size: 10.5, weight: .medium, design: .monospaced))
        .foregroundStyle(.white.opacity(0.84))
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .floatingCapsuleChrome(shadowRadius: 8, shadowY: 3, shadowOpacity: 0.30)
    }

    // MARK: - Bottom Notifications & Status Overlays

    private var notificationsOverlay: some View {
        VStack(spacing: 10) {
            Spacer()

            if let toast = model.toastNotice {
                ToastPillView(message: toast)
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
        .padding(14)
    }

    private var connectionOverlay: some View {
        VStack(spacing: 14) {
            ProgressView().controlSize(.large).tint(.white)

            Text(connectionStatusCaption)
                .font(.headline)
                .foregroundStyle(.white)

            if let detail = model.disconnectReason, !detail.isEmpty, model.isReconnecting {
                Text("原因：\(detail)")
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

            HStack(spacing: 10) {
                Button("断开并返回") {
                    model.forceDisconnectAndReturnHome()
                }
                .buttonStyle(.bordered)
                .tint(.white)

                Button("强制退出 App", role: .destructive) {
                    model.forceTerminateApp()
                }
                .buttonStyle(.borderedProminent)
                .tint(.red.opacity(0.88))
            }
            .padding(.top, 4)
        }
        .padding(26)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .strokeBorder(.white.opacity(0.24), lineWidth: 1)
        )
    }

    private var connectionStatusCaption: String {
        switch model.sessionState {
        case .connecting: return "正在建立低延迟连接..."
        case .reconnecting(let attempt): return "投屏连接已中断，正在尝试第 \(attempt)/5 次重连..."
        case .connected: return model.isAuthenticated ? "正在等待 Android 首个关键帧..." : "正在等待手机端确认连接..."
        case .failed(let msg): return "连接失败：\(msg)"
        case .disconnected: return "投屏连接已断开"
        default: return "准备显示画面..."
        }
    }

    private var fileDropOverlay: some View {
        RoundedRectangle(cornerRadius: 26, style: .continuous)
            .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 3, dash: [10, 6]))
            .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .overlay {
                VStack(spacing: 10) {
                    Image(systemName: "arrow.down.doc.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(Color.accentColor)
                    Text("松开鼠标发送文件到 Android 手机")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)
                    Text("默认保存到手机 Download/OpalDesk 目录")
                        .font(.callout)
                        .foregroundStyle(.white.opacity(0.8))
                }
                .padding(24)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(24)
            .allowsHitTesting(false)
    }
}
