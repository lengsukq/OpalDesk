import AppKit
import OpalDeskCore
import SwiftUI

/// Frameless-style standalone Android floating app window view.
/// Shows a pure, zero-clutter phone screen when idle, and smoothly reveals a translucent hover bar
/// near the top edge with always-on-top pin (`⌘⇧T`), opacity, aspect presets, DPI reload, Back,
/// return-to-desktop (when connected), and close controls.
public struct PopoutAppWindowView: View {
    private static let opacityStep: Double = 0.05

    @Bindable public var controller: PopoutAppController
    public let onFilesDropped: ([URL]) -> Void

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

    public var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            InteractiveDisplayNSView(
                videoRenderer: controller.videoRenderer,
                config: DisplayCanvasConfig(
                    videoWidth: controller.currentVideoWidth,
                    videoHeight: controller.currentVideoHeight,
                    mapCommandToCtrl: controller.mapCommandToCtrl
                ),
                callbacks: controller.streamBridge.makeCanvasCallbacks(
                    isAuthenticated: { controller.isAuthenticated },
                    toolbar: toolbar,
                    onFilesDropped: onFilesDropped,
                    onDragHoverChange: { hovering in
                        withAnimation(.easeInOut(duration: 0.15)) { isDragOverCanvas = hovering }
                    },
                    onResize: { size, scale in controller.handleCanvasResize(viewSize: size, backingScale: scale) },
                    onTogglePin: {
                        controller.togglePinnedOnTop()
                        toolbar.revealTemporarily(hideAfter: 1.4)
                    }
                )
            )
            .ignoresSafeArea()

            if controller.isShowingLoadingOverlay { loadingOverlay }
            if isDragOverCanvas { dropHighlightOverlay }

            VStack(spacing: 4) {
                if !shouldShowToolbar {
                    Capsule()
                        .fill(controller.isPinnedOnTop ? Color.orange.opacity(0.65) : Color.white.opacity(0.35))
                        .frame(width: 38, height: 4)
                        .padding(.top, 5)
                        .transition(.opacity)
                        .allowsHitTesting(false)
                } else {
                    hoverControlBar
                        .padding(.top, 10)
                        .padding(.horizontal, 10)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .onHover { toolbar.handleToolbarHover($0) }
                }
                Spacer()
            }
        }
        .preferredColorScheme(.dark)
        .animation(.easeOut(duration: 0.16), value: shouldShowToolbar)
        .onChange(of: isOpacityPopoverShown) { _, shown in toolbar.setLockedOpen(shown) }
        .onAppear { toolbar.onAppear() }
        .onDisappear { toolbar.onDisappear() }
    }

    // MARK: - Translucent Hover Control Bar

    private var hoverControlBar: some View {
        HStack(spacing: 5) {
            HStack(spacing: 5) {
                Circle()
                    .fill(controller.isAuthenticated ? Color.green : Color.orange)
                    .frame(width: 6, height: 6)
                Text(controller.appLabel)
                    .font(.system(size: 11, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(.leading, 4)
            .padding(.trailing, 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay { PopoutWindowDragArea() }
            .help("按住拖动移动悬浮小窗")

            iconButton(
                controller.isPinnedOnTop ? "pin.fill" : "pin",
                tint: controller.isPinnedOnTop ? .orange : .primary,
                highlighted: controller.isPinnedOnTop,
                help: controller.isPinnedOnTop ? "取消置顶 (⌘⇧T)" : "窗口置顶 (⌘⇧T)"
            ) { controller.togglePinnedOnTop() }

            Button { isOpacityPopoverShown.toggle() } label: {
                let tinted = controller.windowOpacity < 0.99
                Image(systemName: "circle.lefthalf.filled")
                    .font(.system(size: 11, weight: .medium))
                    .frame(width: 24, height: 22)
                    .background(tinted ? Color.accentColor.opacity(0.24) : Color.clear, in: Capsule())
                    .foregroundStyle(tinted ? Color.accentColor : Color.primary)
            }
            .buttonStyle(.plain)
            .help("调节窗口透明度 (\(Int((controller.windowOpacity * 100).rounded()))%)")
            .popover(isPresented: $isOpacityPopoverShown, arrowEdge: .bottom) { opacityPopoverContent }

            aspectPresetMenu

            iconButton("textformat.size", help: "重载 App DPI（修复字体过大或缩放异常）") { controller.reloadAppDPI() }
            iconButton("chevron.backward", help: "Android 返回") { controller.sendAndroidBack() }
            if controller.canReturnToDesktop {
                iconButton("macwindow.on.rectangle", tint: .accentColor, help: "收回主大屏并关闭小窗") {
                    controller.returnToDesktop()
                }
            }
            iconButton("xmark.circle.fill", tint: .red, help: "关闭悬浮小窗") { controller.close(sendDisconnect: true) }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .floatingCapsuleChrome(
            strokeColor: controller.isPinnedOnTop ? Color.orange.opacity(0.62) : Color.white.opacity(0.18),
            lineWidth: controller.isPinnedOnTop ? 1.25 : 0.8,
            shadowRadius: 10,
            shadowY: 4,
            shadowOpacity: 0.38
        )
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
            HStack(spacing: 2) {
                Image(systemName: controller.activePreset?.systemImage ?? "aspectratio")
                    .font(.system(size: 10, weight: .semibold))
                Text(controller.activePreset?.title ?? "自由")
                    .font(.system(size: 10, weight: .medium))
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(Color.primary.opacity(0.08), in: Capsule())
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .help("快选手机 / 折叠 / 平板比例")
    }

    private var opacityPopoverContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("窗口透明度").font(.caption.weight(.semibold))
                Spacer()
                Text("\(Int((controller.windowOpacity * 100).rounded()))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Slider(value: $controller.windowOpacity, in: DisplayPresets.popoutOpacityRange, step: Self.opacityStep)
                .frame(width: 170)
                .onChange(of: controller.windowOpacity) { _, newValue in
                    controller.setWindowOpacity(newValue)
                }
        }
        .padding(12)
    }

    private func iconButton(
        _ icon: String,
        tint: Color = .primary,
        highlighted: Bool = false,
        help: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 11, weight: .semibold))
                .frame(width: 24, height: 22)
                .background(highlighted ? tint.opacity(0.24) : Color.clear, in: Capsule())
                .foregroundStyle(tint)
        }
        .buttonStyle(.plain)
        .help(help)
        .accessibilityLabel(help)
    }

    // MARK: - Loading & Drop Overlays

    private var loadingOverlay: some View {
        VStack(spacing: 10) {
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
        .padding(18)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var dropHighlightOverlay: some View {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, dash: [8, 5]))
            .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(10)
            .allowsHitTesting(false)
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
