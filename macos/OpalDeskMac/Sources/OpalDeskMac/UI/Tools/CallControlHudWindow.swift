import AppKit
import Foundation
import Observation
import OpalDeskCore
import SwiftUI

// MARK: - Call Duration & Presets (Single Source of Truth)

enum CallHudFormatting {
    static let quickRejectSmsPresets: [String] = [
        "稍后回电",
        "开会中，请发消息",
        "正在忙，稍后联系",
    ]

    static func formattedDuration(startedAtMs: Int64, now: Date = Date()) -> String? {
        guard startedAtMs > 0 else { return nil }
        let startDate = Date(timeIntervalSince1970: Double(startedAtMs) / 1000.0)
        let elapsed = max(0, Int(now.timeIntervalSince(startDate).rounded(.down)))
        let hours = elapsed / 3600
        let minutes = (elapsed % 3600) / 60
        let seconds = elapsed % 60
        if hours > 0 {
            return String(format: "%02d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%02d:%02d", minutes, seconds)
    }

    static func statusSubtitle(for callState: CallStatePayload, now: Date = Date()) -> String {
        switch callState.state {
        case .idle:
            return ""
        case .ringing:
            return callState.source.isEmpty ? "手机来电" : "手机来电 · \(callState.source)"
        case .dialing:
            return "正在手机端拨号…"
        case .offhook:
            if let dur = formattedDuration(startedAtMs: callState.startedAtMs, now: now) {
                return "通话中 · \(dur)"
            }
            return "通话中"
        }
    }
}

// MARK: - Observable HUD State Bridge

@MainActor
@Observable
final class CallControlHudModel {
    var callState: CallStatePayload = .idle
    var onAction: @MainActor (CallActionPayload) -> Void = { _ in }
    var onDismissTemporarily: @MainActor () -> Void = {}
}

// MARK: - Floating Non-Activating NSPanel Coordinator

/// Manages a floating top-right Liquid Glass `NSPanel` for incoming and active Android phone calls.
/// Auto-shows on `.ringing` / `.offhook` / `.dialing` and auto-hides on `.idle` without stealing app focus.
@MainActor
public final class CallControlHudCoordinator {
    private enum Layout {
        static let panelSize = NSSize(width: 368, height: 148)
        static let topRightMargin: CGFloat = 18
    }

    private let hudModel = CallControlHudModel()
    private var panel: KeyableFloatingHudPanel?
    private var isPanelShown: Bool = false
    private var dismissedCallIdentity: String?

    public init() {}

    public func update(
        callState: CallStatePayload,
        enabled: Bool,
        onAction: @escaping @MainActor (CallActionPayload) -> Void
    ) {
        let identity = callIdentity(for: callState)
        if !callState.isActive {
            dismissedCallIdentity = nil
        }

        hudModel.callState = callState
        hudModel.onAction = onAction
        hudModel.onDismissTemporarily = { [weak self] in
            self?.dismissedCallIdentity = identity
            self?.hide()
        }

        guard enabled && callState.isActive && dismissedCallIdentity != identity else {
            hide()
            return
        }

        let hudPanel = ensurePanel()
        if !isPanelShown {
            positionTopRight(hudPanel)
            hudPanel.orderFrontRegardless()
            isPanelShown = true
        }
    }

    private func callIdentity(for callState: CallStatePayload) -> String {
        "\(callState.state.rawValue)|\(callState.number)|\(callState.contactName)"
    }

    public func hide() {
        guard isPanelShown || panel?.isVisible == true else { return }
        panel?.orderOut(nil)
        isPanelShown = false
    }

    public func close() {
        isPanelShown = false
        panel?.orderOut(nil)
        panel?.close()
        panel = nil
    }

    private func ensurePanel() -> KeyableFloatingHudPanel {
        if let existing = panel {
            return existing
        }

        let created = KeyableFloatingHudPanel(
            contentRect: NSRect(origin: .zero, size: Layout.panelSize),
            styleMask: [.nonactivatingPanel, .borderless, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        created.level = .floating
        created.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        created.isOpaque = false
        created.backgroundColor = .clear
        created.hasShadow = true
        created.isMovableByWindowBackground = true
        created.hidesOnDeactivate = false
        created.isReleasedWhenClosed = false

        let rootView = CallControlHudView(model: hudModel)
        let hostingView = NSHostingView(rootView: rootView)
        hostingView.frame = NSRect(origin: .zero, size: Layout.panelSize)
        hostingView.autoresizingMask = [.width, .height]
        created.contentView = hostingView

        panel = created
        return created
    }

    private func positionTopRight(_ panel: NSPanel) {
        guard let visible = NSScreen.main?.visibleFrame else { return }
        let size = Layout.panelSize
        let origin = CGPoint(
            x: visible.maxX - size.width - Layout.topRightMargin,
            y: visible.maxY - size.height - Layout.topRightMargin
        )
        panel.setFrame(NSRect(origin: origin, size: size), display: true)
    }
}

/// Non-activating floating panel that can still accept keyboard input when the user clicks
/// inside the "Reject with custom SMS" text field popover.
@MainActor
private final class KeyableFloatingHudPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

// MARK: - Liquid Glass Floating Call HUD View

private struct CallControlHudView: View {
    @Bindable var model: CallControlHudModel
    @State private var isSmsPopoverPresented: Bool = false
    @State private var customSmsText: String = ""
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var call: CallStatePayload { model.callState }

    private var accentTint: Color {
        switch call.state {
        case .ringing: return .green
        case .dialing: return .accentColor
        case .offhook: return .green
        case .idle: return .secondary
        }
    }

    @ViewBuilder
    var body: some View {
        if call.state == .offhook && call.startedAtMs > 0 {
            TimelineView(.periodic(from: .now, by: 1.0)) { timeline in
                hudCardBody(now: timeline.date)
            }
        } else {
            hudCardBody(now: Date())
        }
    }

    private func hudCardBody(now: Date) -> some View {
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            callerHeader(now: now)
            controlsRow
        }
        .padding(OpalSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .opalGlassSurface(.stageOverlay, cornerRadius: OpalRadius.panel, tint: accentTint)
        .padding(4)
    }

    private func callerHeader(now: Date) -> some View {
        HStack(alignment: .top, spacing: OpalSpacing.md) {
            callerMonogram

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: OpalSpacing.xs) {
                    Text(call.displayCaller)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .truncationMode(.tail)

                    StatusBadgeView(
                        text: badgeLabel,
                        color: accentTint,
                        horizontalPadding: 7,
                        verticalPadding: 1.5
                    )
                }

                if !call.contactName.isEmpty && !call.number.isEmpty && call.contactName != call.number {
                    Text(call.number)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Text(CallHudFormatting.statusSubtitle(for: call, now: now))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            OpalGlassIconButton(
                systemName: "xmark",
                tint: .secondary,
                size: 22,
                iconSize: 9.5,
                helpText: "暂时隐藏来电浮窗（可在菜单栏继续控制通话）"
            ) {
                model.onDismissTemporarily()
            }
        }
    }

    private var callerMonogram: some View {
        let initial = String(call.displayCaller.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1))
        return ZStack {
            Circle()
                .fill(
                    LinearGradient(
                        colors: [accentTint.opacity(0.28), accentTint.opacity(0.14)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Circle()
                .strokeBorder(accentTint.opacity(0.45), lineWidth: 1.1)

            if call.contactName.isEmpty {
                Image(systemName: call.state == .ringing ? "phone.arrow.down.left.fill" : "phone.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(accentTint)
            } else {
                Text(initial)
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .foregroundStyle(accentTint)
            }
        }
        .frame(width: 42, height: 42)
        .scaleEffect(call.state == .ringing && !reduceMotion ? 1.03 : 1.0)
        .animation(
            call.state == .ringing && !reduceMotion
                ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true)
                : .default,
            value: call.state
        )
    }

    private var badgeLabel: String {
        switch call.state {
        case .ringing: return "来电"
        case .dialing: return "拨号中"
        case .offhook: return "通话中"
        case .idle: return ""
        }
    }

    @ViewBuilder
    private var controlsRow: some View {
        switch call.state {
        case .ringing:
            HStack(spacing: OpalSpacing.sm) {
                if call.canAnswer {
                    Button {
                        model.onAction(.answer())
                    } label: {
                        Label("接听", systemImage: "phone.fill")
                            .font(.caption.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 3)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                    .controlSize(.regular)
                }

                if call.canReject {
                    Button {
                        model.onAction(.reject())
                    } label: {
                        Label("拒接", systemImage: "phone.down.fill")
                            .font(.caption.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 3)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .controlSize(.regular)
                }

                Button {
                    isSmsPopoverPresented.toggle()
                } label: {
                    Label("短信拒接", systemImage: "message.fill")
                        .font(.caption.weight(.medium))
                        .padding(.vertical, 3)
                }
                .buttonStyle(.bordered)
                .controlSize(.regular)
                .popover(isPresented: $isSmsPopoverPresented, arrowEdge: .bottom) {
                    rejectSmsPopover
                }
            }

        case .offhook, .dialing:
            HStack(spacing: OpalSpacing.sm) {
                Button {
                    model.onAction(.toggleMute())
                } label: {
                    Label(call.isMuted ? "已静音" : "静音", systemImage: call.isMuted ? "mic.slash.fill" : "mic.fill")
                        .font(.caption.weight(.medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 3)
                }
                .buttonStyle(.bordered)
                .tint(call.isMuted ? .orange : nil)
                .controlSize(.regular)

                Button {
                    model.onAction(.toggleSpeaker())
                } label: {
                    Label(call.isSpeakerOn ? "免提开" : "免提", systemImage: call.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.wave.2")
                        .font(.caption.weight(.medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 3)
                }
                .buttonStyle(.bordered)
                .tint(call.isSpeakerOn ? .accentColor : nil)
                .controlSize(.regular)

                Button {
                    model.onAction(.hangup())
                } label: {
                    Label("挂断", systemImage: "phone.down.fill")
                        .font(.caption.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 3)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .controlSize(.regular)
            }

        case .idle:
            EmptyView()
        }
    }

    private var rejectSmsPopover: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            Text("选择快捷短信拒接")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            ForEach(CallHudFormatting.quickRejectSmsPresets, id: \.self) { preset in
                Button {
                    isSmsPopoverPresented = false
                    model.onAction(.rejectSms(text: preset))
                } label: {
                    HStack {
                        Text(preset)
                            .font(.callout)
                            .foregroundStyle(.primary)
                        Spacer()
                        Image(systemName: "arrow.up.circle")
                            .foregroundStyle(Color.accentColor)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .opalGlassSurface(.inset, cornerRadius: OpalRadius.control)
                    .opalInteractiveHover(cornerRadius: OpalRadius.control)
                }
                .buttonStyle(.plain)
            }

            Divider()

            HStack(spacing: OpalSpacing.xs) {
                TextField("自定义短信回复…", text: $customSmsText)
                    .textFieldStyle(.roundedBorder)
                    .font(.caption)
                    .onSubmit { sendCustomRejectSms() }

                Button("发送") { sendCustomRejectSms() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .disabled(customSmsText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(OpalSpacing.md)
        .frame(width: 268)
    }

    private func sendCustomRejectSms() {
        let trimmed = customSmsText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        customSmsText = ""
        isSmsPopoverPresented = false
        model.onAction(.rejectSms(text: trimmed))
    }
}
