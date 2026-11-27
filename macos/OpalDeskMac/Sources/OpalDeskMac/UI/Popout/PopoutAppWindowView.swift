import AppKit
import OpalDeskCore
import SwiftUI

/// Frameless-style standalone Android floating app window with continuous macOS squircle chrome
/// and a Liquid Glass floating hover bar (pin, opacity, aspect presets, audio, capture, DPI, Back, close).
public struct PopoutAppWindowView: View {
    private static let opacityStep: Double = 0.05

    @Bindable public var controller: PopoutAppController
    public let onFilesDropped: ([URL]) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var toolbar = HoverToolbarController(
        initialRevealSeconds: 2.0,
        topHoverRevealSeconds: 1.8,
        leaveHideDelaySeconds: 0.38,
        clearZoneHideDelaySeconds: 0.25
    )
    @State private var isOpacityPopoverShown: Bool = false
    @State private var isDragOverCanvas: Bool = false

    public init(controller: PopoutAppController, onFilesDropped: @escaping ([URL]) -> Void) {
        self.controller = controller
        self.onFilesDropped = onFilesDropped
    }

    private var shouldShowToolbar: Bool {
        toolbar.isVisible(forceVisible: !controller.isAuthenticated && !controller.isMigratingConnection)
    }

    private var windowShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: OpalRadius.popoutWindow, style: .continuous)
    }

    public var body: some View {
        ZStack {
            Color.black

            InteractiveDisplayNSView(
                videoRenderer: controller.videoRenderer,
                config: DisplayCanvasConfig(
                    videoWidth: controller.currentVideoWidth,
                    videoHeight: controller.currentVideoHeight,
                    mapCommandToCtrl: controller.mapCommandToCtrl,
                    enableEdgeNavigationGestures: controller.enableEdgeNavigationGestures
                ),
                callbacks: controller.streamBridge.makeCanvasCallbacks(
                    isAuthenticated: { controller.isAuthenticated },
                    toolbar: toolbar,
                    onFilesDropped: onFilesDropped,
                    onDragHoverChange: { hovering in
                        withAnimation(OpalMotion.animation(OpalMotion.hover, reduceMotion: reduceMotion)) {
                            isDragOverCanvas = hovering
                        }
                    },
                    onResize: { size, scale in controller.handleCanvasResize(viewSize: size, backingScale: scale) },
                    onTogglePin: {
                        controller.togglePinnedOnTop()
                        toolbar.revealTemporarily(hideAfter: 1.4)
                    }
                )
            )

            if controller.isShowingLoadingOverlay { loadingOverlay }
            if isDragOverCanvas {
                OpalDropZoneOverlay(
                    icon: "arrow.down.doc.fill",
                    title: "发送文件",
                    subtitle: nil,
                    cornerRadius: OpalRadius.card,
                    insetPadding: 10,
                    isCompact: true
                )
            }

            VStack(spacing: OpalSpacing.xxs) {
                if !shouldShowToolbar {
                    collapsedTopIndicator
                        .transition(.opacity)
                } else {
                    hoverControlBar
                        .padding(.top, 10)
                        .padding(.horizontal, 10)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .onHover { toolbar.handleToolbarHover($0) }
                }

                Spacer()

                if let toast = controller.toastNotice {
                    ToastPillView(
                        message: toast,
                        actionTitle: controller.toastRevealURL != nil ? "在 Finder 显示" : nil,
                        onAction: controller.toastRevealURL.map { url in
                            { NSWorkspace.shared.activateFileViewerSelecting([url]) }
                        }
                    )
                    .padding(.bottom, OpalSpacing.md)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
        .clipShape(windowShape)
        .overlay(
            windowShape
                .strokeBorder(
                    LinearGradient(
                        colors: controller.isPinnedOnTop
                            ? [Color.orange.opacity(0.68), Color.orange.opacity(0.24), Color.orange.opacity(0.45)]
                            : [Color.white.opacity(0.26), Color.white.opacity(0.06), Color.white.opacity(0.14)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: controller.isPinnedOnTop ? 1.35 : 0.9
                )
                .allowsHitTesting(false)
        )
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .animation(OpalMotion.animation(OpalMotion.quick, reduceMotion: reduceMotion), value: shouldShowToolbar)
        .animation(OpalMotion.animation(OpalMotion.smooth, reduceMotion: reduceMotion), value: controller.toastNotice)
        .onChange(of: isOpacityPopoverShown) { _, shown in toolbar.setLockedOpen(shown) }
        .onAppear { toolbar.onAppear() }
        .onDisappear { toolbar.onDisappear() }
    }

    // MARK: - Collapsed Top Handle & Recording Pill

    private var collapsedTopIndicator: some View {
        HStack {
            if controller.isRecording {
                Button {
                    controller.stopRecording()
                } label: {
                    HStack(spacing: 5) {
                        Circle().fill(Color.red).frame(width: 7, height: 7)
                        Text(controller.formattedRecordingDuration)
                            .font(.system(size: 10, weight: .semibold, design: .monospaced))
                            .foregroundStyle(.white)
                    }
                    .padding(.horizontal, 9)
                    .padding(.vertical, 3.5)
                    .opalFloatingGlassCapsule(strokeColor: Color.red.opacity(0.62))
                }
                .buttonStyle(.plain)
                .padding(.leading, 10)
                .padding(.top, 8)
                .help("点击停止录屏")
            }
            Spacer()
        }
        .overlay(alignment: .top) {
            Capsule()
                .fill(controller.isPinnedOnTop ? Color.orange.opacity(0.72) : Color.white.opacity(0.38))
                .frame(width: 40, height: 4)
                .padding(.top, 6)
                .allowsHitTesting(false)
        }
    }

    // MARK: - Liquid Glass Floating Hover Control Bar

    private var hoverControlBar: some View {
        HStack(spacing: 4) {
            HStack(spacing: 5) {
                Circle()
                    .fill(controller.isAuthenticated ? Color.green : Color.orange)
                    .frame(width: 6, height: 6)
                Text(controller.appLabel)
                    .font(.system(size: 11, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(.leading, 5)
            .padding(.trailing, 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay { PopoutWindowDragArea() }
            .help("按住拖动移动悬浮小窗")

            OpalGlassIconButton(
                systemName: controller.isPinnedOnTop ? "pin.fill" : "pin",
                tint: controller.isPinnedOnTop ? .orange : .white,
                isActive: controller.isPinnedOnTop,
                size: 24,
                iconSize: 11,
                helpText: controller.isPinnedOnTop ? "取消置顶 (⌘⇧T)" : "窗口置顶 (⌘⇧T)"
            ) { controller.togglePinnedOnTop() }

            Button { isOpacityPopoverShown.toggle() } label: {
                let tinted = controller.windowOpacity < 0.99
                Image(systemName: "circle.lefthalf.filled")
                    .font(.system(size: 11, weight: .medium))
                    .frame(width: 24, height: 24)
                    .background(tinted ? Color.accentColor.opacity(0.24) : Color.clear, in: Capsule())
                    .foregroundStyle(tinted ? Color.accentColor : Color.white)
            }
            .buttonStyle(.plain)
            .help("调节窗口透明度 (\(Int((controller.windowOpacity * 100).rounded()))%)")
            .accessibilityLabel("调节窗口透明度")
            .popover(isPresented: $isOpacityPopoverShown, arrowEdge: .bottom) { opacityPopoverContent }

            aspectPresetMenu

            OpalGlassIconButton(
                systemName: controller.isAudioMuted ? "speaker.slash" : "speaker.wave.2",
                tint: controller.isAudioMuted ? .secondary : .white,
                isActive: !controller.isAudioMuted,
                size: 24,
                iconSize: 11,
                helpText: controller.isAudioMuted ? "已静音 · 点击恢复音频回传" : "静音此小窗音频回传"
            ) { controller.toggleAudioMute() }

            OpalGlassIconButton(
                systemName: "camera",
                tint: .white,
                size: 24,
                iconSize: 11,
                helpText: "一键截图到桌面并复制剪贴板"
            ) { controller.captureScreenshot() }

            recordingButton

            OpalGlassIconButton(
                systemName: "textformat.size",
                tint: .white,
                size: 24,
                iconSize: 11,
                helpText: "重载 App DPI（修复字体过大或缩放异常）"
            ) { controller.reloadAppDPI() }

            OpalGlassIconButton(
                systemName: "chevron.backward",
                tint: .white,
                size: 24,
                iconSize: 11,
                helpText: "Android 返回"
            ) { controller.sendAndroidBack() }

            if controller.canReturnToDesktop {
                OpalGlassIconButton(
                    systemName: "macwindow.on.rectangle",
                    tint: .accentColor,
                    size: 24,
                    iconSize: 11,
                    helpText: "收回主大屏并关闭小窗"
                ) { controller.returnToDesktop() }
            }

            OpalGlassIconButton(
                systemName: "xmark.circle.fill",
                tint: .red,
                size: 24,
                iconSize: 12,
                helpText: "关闭悬浮小窗"
            ) { controller.close(sendDisconnect: true) }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .opalFloatingGlassCapsule(strokeColor: controller.isPinnedOnTop ? Color.orange.opacity(0.64) : nil)
    }

    private var recordingButton: some View {
        Button {
            controller.toggleRecording()
        } label: {
            if controller.isRecording {
                HStack(spacing: 3) {
                    Circle().fill(Color.red).frame(width: 6, height: 6)
                    Text(controller.formattedRecordingDuration)
                        .font(.system(size: 9.5, weight: .semibold, design: .monospaced))
                }
                .padding(.horizontal, 7)
                .frame(height: 24)
                .background(Color.red.opacity(0.24), in: Capsule())
                .foregroundStyle(.red)
            } else {
                Image(systemName: "record.circle")
                    .font(.system(size: 11, weight: .semibold))
                    .frame(width: 24, height: 24)
                    .foregroundStyle(.white)
            }
        }
        .buttonStyle(.plain)
        .help(controller.isRecording ? "停止录屏并保存 MP4" : "录制小窗 MP4 视频")
        .accessibilityLabel(controller.isRecording ? "停止录屏" : "录制视频")
    }

    private var aspectPresetMenu: some View {
        Menu {
            Section("窗口形态比例") {
                ForEach(PopoutAspectPreset.allCases) { preset in
                    Button { controller.applyAspectPreset(preset) } label: {
                        HStack {
                            Label(preset.title, systemImage: preset.systemImage)
                            if controller.activePreset == preset { Image(systemName: "checkmark") }
                        }
                    }
                }
            }
            Divider()
            Button("重载悬浮小窗", systemImage: "arrow.clockwise") { controller.retryStart() }
            Button("刷新关键帧 (IDR)", systemImage: "arrow.triangle.2.circlepath") { controller.requestIDR() }
        } label: {
            HStack(spacing: 3) {
                Image(systemName: controller.activePreset?.systemImage ?? "aspectratio")
                    .font(.system(size: 10, weight: .semibold))
                Text(controller.activePreset?.title ?? "自由")
                    .font(.system(size: 10, weight: .medium))
            }
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .background(Color.white.opacity(0.10), in: Capsule())
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .help("快选手机 / 折叠 / 平板比例")
    }

    private var opacityPopoverContent: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            HStack {
                Text("窗口透明度").font(.caption.weight(.semibold))
                Spacer()
                Text("\(Int((controller.windowOpacity * 100).rounded()))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Slider(value: $controller.windowOpacity, in: DisplayPresets.popoutOpacityRange, step: Self.opacityStep)
                .frame(width: 176)
                .onChange(of: controller.windowOpacity) { _, newValue in
                    controller.setWindowOpacity(newValue)
                }
        }
        .padding(OpalSpacing.md)
    }

    // MARK: - Loading Overlay

    private var loadingOverlay: some View {
        VStack(spacing: OpalSpacing.sm) {
            if let error = controller.errorMessage {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(.orange)
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 240)
                Button("重试启动") { controller.retryStart() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
            } else {
                ProgressView().controlSize(.regular).tint(.white)
                Text(
                    controller.isWaitingForApproval
                        ? "请在手机端点击「允许」…"
                        : "正在独立启动 \(controller.appLabel)…"
                )
                .font(.callout.weight(.medium))
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.center)
            }
        }
        .padding(OpalSpacing.xl)
        .opalGlassSurface(.stageOverlay, cornerRadius: OpalRadius.card)
    }
}

private struct PopoutWindowDragArea: NSViewRepresentable {
    func makeNSView(context: Context) -> DragHandleNSView { DragHandleNSView() }
    func updateNSView(_ nsView: DragHandleNSView, context: Context) {}

    @MainActor
    final class DragHandleNSView: NSView {
        override func mouseDown(with event: NSEvent) { window?.performDrag(with: event) }
    }
}
