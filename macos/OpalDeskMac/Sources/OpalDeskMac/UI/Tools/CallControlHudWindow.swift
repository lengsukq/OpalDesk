import AppKit
import Foundation
import Observation
import OpalDeskCore
import SwiftUI

// MARK: - Call Duration & Presets (Single Source of Truth)

enum CallHudFormatting {
    static var quickRejectSmsPresets: [String] {
        [
            OpalL10n.text("call.sms.preset1"),
            OpalL10n.text("call.sms.preset2"),
            OpalL10n.text("call.sms.preset3"),
        ]
    }

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

    /// Clean, human-readable call subtitle (never leaks internal `"telephony"` / `"notification"` strings).
    static func statusSubtitle(for callState: CallStatePayload, now: Date = Date()) -> String {
        switch callState.state {
        case .idle:
            return ""
        case .ringing:
            if !callState.contactName.isEmpty && !callState.number.isEmpty && callState.contactName != callState.number {
                return OpalL10n.format("call.status.ringing_fmt", callState.number)
            }
            return OpalL10n.text("call.status.ringing")
        case .dialing:
            return OpalL10n.text("call.status.dialing")
        case .offhook:
            if let dur = formattedDuration(startedAtMs: callState.startedAtMs, now: now) {
                return OpalL10n.format("call.status.offhook_fmt", dur)
            }
            return OpalL10n.text("call.status.offhook")
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

// MARK: - Exact-Bounds Continuous Masked NSVisualEffectView (Zero Square Corners or Blur Bleed)

/// Custom `NSVisualEffectView` that renders an exact pixel-matched Apple `.continuous` squircle
/// `maskImage` matching SwiftUI's `RoundedRectangle(cornerRadius:style:.continuous)` with zero
/// 9-slice stretching artifacts and zero rectangular `CABackdropLayer` bleed on borderless NSPanels.
@MainActor
private final class ContinuousMaskedVisualEffectView: NSVisualEffectView {
    var cornerRadius: CGFloat = 28 {
        didSet {
            if abs(oldValue - cornerRadius) > 0.01 {
                lastMaskSize = .zero
                needsLayout = true
            }
        }
    }

    private var lastMaskSize: NSSize = .zero
    private var lastMaskScale: CGFloat = 0

    override func layout() {
        super.layout()
        wantsLayer = true
        layer?.isOpaque = false
        layer?.backgroundColor = NSColor.clear.cgColor
        layer?.cornerRadius = cornerRadius
        layer?.cornerCurve = .continuous
        layer?.masksToBounds = true
        updateMaskIfNeeded()
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        lastMaskSize = .zero
        updateMaskIfNeeded()
    }

    private func updateMaskIfNeeded() {
        let size = bounds.size
        guard size.width > 1, size.height > 1 else { return }
        let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2.0
        guard size != lastMaskSize || scale != lastMaskScale else { return }
        lastMaskSize = size
        lastMaskScale = scale
        maskImage = Self.makeExactContinuousMask(size: size, cornerRadius: cornerRadius, scale: scale)
    }

    private static func makeExactContinuousMask(
        size: NSSize,
        cornerRadius: CGFloat,
        scale: CGFloat
    ) -> NSImage {
        let pixelW = max(1, Int((size.width * scale).rounded()))
        let pixelH = max(1, Int((size.height * scale).rounded()))

        // Render an offscreen CALayer with `.continuous` cornerCurve so WindowServer masks
        // behind-window blur to the exact sub-pixel continuous superellipse silhouette.
        let maskLayer = CALayer()
        maskLayer.frame = CGRect(origin: .zero, size: size)
        maskLayer.backgroundColor = NSColor.black.cgColor
        maskLayer.cornerRadius = cornerRadius
        maskLayer.cornerCurve = .continuous
        maskLayer.masksToBounds = true

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
        guard let ctx = CGContext(
            data: nil,
            width: pixelW,
            height: pixelH,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else {
            return NSImage(size: size)
        }

        ctx.clear(CGRect(x: 0, y: 0, width: pixelW, height: pixelH))
        ctx.scaleBy(x: scale, y: scale)
        maskLayer.render(in: ctx)

        guard let cgImage = ctx.makeImage() else {
            return NSImage(size: size)
        }
        return NSImage(cgImage: cgImage, size: size)
    }
}

private struct OpalMaskedGlassBackdrop: NSViewRepresentable {
    let cornerRadius: CGFloat
    let material: NSVisualEffectView.Material

    func makeNSView(context: Context) -> ContinuousMaskedVisualEffectView {
        let view = ContinuousMaskedVisualEffectView()
        view.blendingMode = .behindWindow
        view.state = .active
        view.material = material
        view.cornerRadius = cornerRadius
        return view
    }

    func updateNSView(_ nsView: ContinuousMaskedVisualEffectView, context: Context) {
        if nsView.material != material {
            nsView.material = material
        }
        nsView.cornerRadius = cornerRadius
    }
}

// MARK: - Floating Non-Activating NSPanel Coordinator

/// Manages a sleek iOS-inspired floating top-right call control banner without stealing focus.
/// Auto-shows on `.ringing` / `.offhook` / `.dialing` and auto-hides on `.idle`.
@MainActor
public final class CallControlHudCoordinator {
    private enum Layout {
        /// Visible iOS banner is 384×80 inside a 472×168 transparent shadow canvas (44pt margin on all
        /// sides > 3-sigma Gaussian tail of `radius: 14, y: 6`) so drop shadow fades to zero alpha well
        /// before the NSPanel bounds — guaranteeing zero rectangular shadow clipping.
        static let cardWidth: CGFloat = 384
        static let cardHeight: CGFloat = 80
        static let shadowMargin: CGFloat = 44
        static let panelSize = NSSize(
            width: cardWidth + shadowMargin * 2,
            height: cardHeight + shadowMargin * 2
        )
        static let topRightMargin: CGFloat = 14
    }

    private let hudModel = CallControlHudModel()
    private var panel: KeyableFloatingHudPanel?
    private var isPanelShown: Bool = false
    private var dismissedCallIdentity: String?
    private var previewDismissTask: Task<Void, Never>?

    public init() {}

    public func update(
        callState: CallStatePayload,
        enabled: Bool,
        onAction: @escaping @MainActor (CallActionPayload) -> Void
    ) {
        if callState.isActive {
            previewDismissTask?.cancel()
            previewDismissTask = nil
        }

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
            if previewDismissTask == nil {
                hide()
            }
            return
        }

        showPanelAnimated()
    }

    /// Displays a realistic temporary preview of the iOS incoming call HUD so users can inspect its appearance.
    public func previewDemo(durationSeconds: TimeInterval = 6.0) {
        previewDismissTask?.cancel()

        let demoCall = CallStatePayload(
            state: .ringing,
            number: "138 0013 8000",
            contactName: OpalL10n.text("call.hud.preview_name"),
            startedAtMs: 0,
            isMuted: false,
            isSpeakerOn: false,
            canAnswer: true,
            canReject: true,
            source: "preview"
        )
        hudModel.callState = demoCall
        hudModel.onAction = { [weak self] _ in
            self?.previewDismissTask?.cancel()
            self?.previewDismissTask = nil
            self?.hide()
        }
        hudModel.onDismissTemporarily = { [weak self] in
            self?.previewDismissTask?.cancel()
            self?.previewDismissTask = nil
            self?.hide()
        }

        showPanelAnimated()

        let clamped = max(2.0, durationSeconds)
        previewDismissTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(clamped * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.previewDismissTask = nil
            self?.hide()
        }
    }

    private func showPanelAnimated() {
        let hudPanel = ensurePanel()
        if !isPanelShown {
            positionTopRight(hudPanel)
            hudPanel.alphaValue = 0
            hudPanel.orderFrontRegardless()
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.22
                ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
                hudPanel.animator().alphaValue = 1
            }
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
        previewDismissTask?.cancel()
        previewDismissTask = nil
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
            styleMask: [.nonactivatingPanel, .borderless],
            backing: .buffered,
            defer: false
        )
        created.level = .floating
        created.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        created.isOpaque = false
        created.backgroundColor = .clear
        // Critical: disable rectangular AppKit window shadow; SwiftUI renders a pure rounded pill shadow
        // decoupled from NSVisualEffectView inside the generous transparent shadow margin.
        created.hasShadow = false
        created.isMovableByWindowBackground = true
        created.hidesOnDeactivate = false
        created.isReleasedWhenClosed = false

        let rootView = CallControlHudView(
            model: hudModel,
            cardWidth: Layout.cardWidth,
            cardHeight: Layout.cardHeight,
            shadowMargin: Layout.shadowMargin
        )
        let hostingView = NSHostingView(rootView: rootView)
        hostingView.safeAreaRegions = []
        hostingView.wantsLayer = true
        hostingView.layer?.isOpaque = false
        hostingView.layer?.backgroundColor = NSColor.clear.cgColor
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
            x: visible.maxX - Layout.cardWidth - Layout.topRightMargin - Layout.shadowMargin,
            y: visible.maxY - Layout.cardHeight - Layout.topRightMargin - Layout.shadowMargin
        )
        panel.setFrame(NSRect(origin: origin, size: size), display: true)
    }
}

/// Non-activating floating panel that can still accept keyboard input when typing a custom SMS reply.
@MainActor
private final class KeyableFloatingHudPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

// MARK: - iOS Incoming Call Banner HUD View

private struct CallControlHudView: View {
    @Bindable var model: CallControlHudModel
    let cardWidth: CGFloat
    let cardHeight: CGFloat
    let shadowMargin: CGFloat

    @State private var isSmsPopoverPresented: Bool = false
    @State private var customSmsText: String = ""
    @State private var isHovered: Bool = false
    @State private var isPulsing: Bool = false
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    private var call: CallStatePayload { model.callState }
    private var isDark: Bool { colorScheme == .dark }
    private let bannerRadius: CGFloat = 28

    @ViewBuilder
    var body: some View {
        ZStack {
            if call.state == .offhook && call.startedAtMs > 0 {
                TimelineView(.periodic(from: .now, by: 1.0)) { timeline in
                    bannerPill(now: timeline.date)
                }
            } else {
                bannerPill(now: Date())
            }
        }
        .padding(shadowMargin)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.clear)
    }

    private func bannerPill(now: Date) -> some View {
        let shape = RoundedRectangle(cornerRadius: bannerRadius, style: .continuous)

        return HStack(spacing: 12) {
            callerAvatar

            VStack(alignment: .leading, spacing: 2.5) {
                Text(call.displayCaller)
                    .font(.system(size: 14.5, weight: .semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)

                HStack(spacing: 5) {
                    if call.state == .ringing || call.state == .offhook {
                        Circle()
                            .fill(OpalFluidPalette.iosCallGreen)
                            .frame(width: 6.5, height: 6.5)
                    }
                    Text(CallHudFormatting.statusSubtitle(for: call, now: now))
                        .font(.system(size: 12, weight: .medium).monospacedDigit())
                        .foregroundStyle(OpalFluidPalette.secondaryText)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            circularControls
        }
        .padding(.leading, 14)
        .padding(.trailing, 14)
        .frame(width: cardWidth, height: cardHeight)
        .background {
            ZStack {
                // Layer 1: Pure SwiftUI continuous-rounded-rect drop shadow with a 100% hollow interior.
                // Completely decoupled from NSVisualEffectView so CoreAnimation NEVER synthesizes a
                // square rectangular shadow around the NSView bounds, and never muddies the frosted glass.
                ZStack {
                    shape
                        .inset(by: 0.5)
                        .fill(Color.black)
                        .shadow(color: .black.opacity(isDark ? 0.36 : 0.15), radius: 14, y: 6)
                        .shadow(color: .black.opacity(isDark ? 0.18 : 0.05), radius: 3, y: 1)
                    shape
                        .inset(by: 0.25)
                        .fill(Color.black)
                        .blendMode(.destinationOut)
                }
                .compositingGroup()
                .allowsHitTesting(false)

                // Layer 2: Exact-bounds continuous-squircle masked behind-window glass (NO `.shadow` here!)
                if !reduceTransparency {
                    OpalMaskedGlassBackdrop(
                        cornerRadius: bannerRadius,
                        material: isDark ? .hudWindow : .popover
                    )
                    .frame(width: cardWidth, height: cardHeight)
                }

                // Layer 3: Subtle luminous iOS top-lit tint wash strictly inside continuous squircle
                shape.fill(
                    LinearGradient(
                        colors: isDark
                            ? [
                                Color(red: 32/255, green: 36/255, blue: 50/255).opacity(reduceTransparency ? 0.98 : 0.60),
                                Color(red: 22/255, green: 25/255, blue: 36/255).opacity(reduceTransparency ? 0.98 : 0.72),
                            ]
                            : [
                                Color.white.opacity(reduceTransparency ? 0.98 : 0.76),
                                Color(red: 245/255, green: 249/255, blue: 255/255).opacity(reduceTransparency ? 0.98 : 0.65),
                            ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
            }
        }
        .overlay {
            shape
                .strokeBorder(
                    LinearGradient(
                        colors: isDark
                            ? [Color.white.opacity(0.24), Color.white.opacity(0.08)]
                            : [Color.white.opacity(0.95), Color.black.opacity(0.08)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 0.9
                )
                .allowsHitTesting(false)
        }
        .overlay(alignment: .topLeading) {
            if isHovered {
                Button {
                    model.onDismissTemporarily()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 8.5, weight: .bold))
                        .foregroundStyle(isDark ? Color.white.opacity(0.88) : Color.black.opacity(0.65))
                        .frame(width: 20, height: 20)
                        .background(
                            isDark ? Color(red: 46/255, green: 51/255, blue: 68/255) : Color.white,
                            in: Circle()
                        )
                        .overlay(Circle().strokeBorder(Color.primary.opacity(0.14), lineWidth: 0.7))
                        .shadow(color: .black.opacity(0.16), radius: 4, y: 1)
                }
                .buttonStyle(OpalPressStyle())
                .offset(x: -5, y: -5)
                .help(OpalL10n.text("call.hud.hide_help"))
                .accessibilityLabel(OpalL10n.text("call.hud.hide_a11y"))
                .transition(.scale(scale: 0.75).combined(with: .opacity))
            }
        }
        .onHover { hovering in
            withAnimation(OpalMotion.animation(OpalMotion.quick, reduceMotion: reduceMotion)) {
                isHovered = hovering
            }
        }
        .onAppear {
            updatePulseAnimation()
        }
        .onChange(of: call.state) { _, _ in
            updatePulseAnimation()
        }
    }

    private func updatePulseAnimation() {
        if call.state == .ringing && !reduceMotion {
            isPulsing = false
            withAnimation(.easeInOut(duration: 1.15).repeatForever(autoreverses: true)) {
                isPulsing = true
            }
        } else {
            isPulsing = false
        }
    }

    private var callerAvatar: some View {
        let initial = String(call.displayCaller.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1))
        let ringColor = call.state == .dialing ? OpalFluidPalette.brandBlue : OpalFluidPalette.iosCallGreen

        return ZStack {
            if call.state == .ringing && !reduceMotion {
                Circle()
                    .stroke(ringColor.opacity(isPulsing ? 0.0 : 0.42), lineWidth: 2)
                    .frame(width: 44, height: 44)
                    .scaleEffect(isPulsing ? 1.26 : 1.0)
            }

            Circle()
                .fill(
                    LinearGradient(
                        colors: call.contactName.isEmpty
                            ? [ringColor.opacity(0.22), ringColor.opacity(0.11)]
                            : [OpalFluidPalette.brandBlue.opacity(0.22), OpalFluidPalette.brandIndigo.opacity(0.14)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .frame(width: 44, height: 44)
                .overlay(
                    Circle().strokeBorder(
                        (call.contactName.isEmpty ? ringColor : OpalFluidPalette.brandBlue).opacity(0.32),
                        lineWidth: 0.9
                    )
                )

            if call.contactName.isEmpty {
                Image(systemName: call.state == .ringing ? "phone.arrow.down.left.fill" : "phone.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(ringColor)
            } else {
                Text(initial)
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                    .foregroundStyle(OpalFluidPalette.brandBlue)
            }
        }
        .frame(width: 46, height: 46)
    }

    @ViewBuilder
    private var circularControls: some View {
        switch call.state {
        case .ringing:
            HStack(spacing: 9) {
                OpalCircularActionButton(
                    systemName: "message.fill",
                    size: 36,
                    iconSize: 13.5,
                    helpText: OpalL10n.text("call.action.sms_reject_help")
                ) {
                    isSmsPopoverPresented.toggle()
                }
                .popover(isPresented: $isSmsPopoverPresented, arrowEdge: .bottom) {
                    rejectSmsPopover
                }

                if call.canReject {
                    OpalCircularActionButton(
                        systemName: "phone.down.fill",
                        fill: OpalFluidPalette.iosCallRed,
                        size: 38,
                        iconSize: 14.5,
                        helpText: OpalL10n.text("call.action.decline_help")
                    ) {
                        model.onAction(.reject())
                    }
                }

                if call.canAnswer {
                    OpalCircularActionButton(
                        systemName: "phone.fill",
                        fill: OpalFluidPalette.iosCallGreen,
                        size: 38,
                        iconSize: 15,
                        helpText: OpalL10n.text("call.action.answer_help")
                    ) {
                        model.onAction(.answer())
                    }
                }
            }

        case .offhook, .dialing:
            HStack(spacing: 9) {
                OpalCircularActionButton(
                    systemName: call.isMuted ? "mic.slash.fill" : "mic.fill",
                    fill: call.isMuted ? OpalFluidPalette.brandAmber : nil,
                    foreground: .white,
                    size: 36,
                    iconSize: 14,
                    isActive: call.isMuted,
                    helpText: call.isMuted ? OpalL10n.text("call.action.unmute_help") : OpalL10n.text("call.action.mute_help")
                ) {
                    model.onAction(.toggleMute())
                }

                OpalCircularActionButton(
                    systemName: call.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.wave.2.fill",
                    fill: call.isSpeakerOn ? OpalFluidPalette.brandBlue : nil,
                    foreground: .white,
                    size: 36,
                    iconSize: 13.5,
                    isActive: call.isSpeakerOn,
                    helpText: call.isSpeakerOn ? OpalL10n.text("call.action.speaker_off_help") : OpalL10n.text("call.action.speaker_on_help")
                ) {
                    model.onAction(.toggleSpeaker())
                }

                OpalCircularActionButton(
                    systemName: "phone.down.fill",
                    fill: OpalFluidPalette.iosCallRed,
                    size: 38,
                    iconSize: 14.5,
                    helpText: OpalL10n.text("call.action.hangup_help")
                ) {
                    model.onAction(.hangup())
                }
            }

        case .idle:
            EmptyView()
        }
    }

    private var rejectSmsPopover: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            Text(OpalL10n.text("call.sms.title"))
                .font(.system(size: 13, weight: .semibold))

            ForEach(CallHudFormatting.quickRejectSmsPresets, id: \.self) { preset in
                RejectSmsPresetRow(text: preset) {
                    isSmsPopoverPresented = false
                    model.onAction(.rejectSms(text: preset))
                }
            }

            Divider()

            HStack(spacing: OpalSpacing.xs) {
                TextField(OpalL10n.text("call.sms.placeholder"), text: $customSmsText)
                    .opalLiquidInput(cornerRadius: 10)
                    .font(.caption)
                    .onSubmit { submitCustomSms() }

                Button(OpalL10n.text("common.send")) {
                    submitCustomSms()
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .small))
                .disabled(customSmsText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(OpalSpacing.md)
        .frame(width: 268)
    }

    private func submitCustomSms() {
        let trimmed = customSmsText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        customSmsText = ""
        isSmsPopoverPresented = false
        model.onAction(.rejectSms(text: trimmed))
    }
}

private struct RejectSmsPresetRow: View {
    let text: String
    let action: () -> Void
    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: "bubble.left.fill")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(OpalFluidPalette.brandBlue)
                Text(text)
                    .font(.system(size: 12.5))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                Spacer(minLength: 4)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(
                isHovered ? OpalFluidPalette.cardHover : OpalFluidPalette.canvasTop.opacity(0.65),
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(OpalPressStyle())
        .onHover { isHovered = $0 }
    }
}
