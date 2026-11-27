import AppKit
import OpalDeskCore
import SwiftUI

// MARK: - Status Pill & Toast

/// Compact Liquid Glass status pill with subtle tinted rim.
struct StatusBadgeView: View {
    let text: String
    let color: Color
    var font: Font = .caption2.weight(.semibold)
    var horizontalPadding: CGFloat = 8
    var verticalPadding: CGFloat = 2.5
    var backgroundOpacity: Double = 0.14

    var body: some View {
        Text(text)
            .font(font)
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, verticalPadding)
            .background(color.opacity(backgroundOpacity), in: Capsule())
            .overlay(
                Capsule()
                    .strokeBorder(color.opacity(0.24), lineWidth: 0.65)
            )
            .foregroundStyle(color)
    }
}

/// Translucent floating Liquid Glass notification pill displayed over canvases and hubs.
struct ToastPillView: View {
    let message: String
    var actionTitle: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: OpalSpacing.sm) {
            Image(systemName: "sparkles")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.accentColor)
            Text(message)
                .font(.system(size: 12.5, weight: .medium))
                .lineLimit(2)

            if let actionTitle, let onAction {
                Button(actionTitle, action: onAction)
                    .buttonStyle(.bordered)
                    .controlSize(.mini)
            }
        }
        .padding(.horizontal, OpalSpacing.lg)
        .padding(.vertical, 9)
        .opalFloatingGlassCapsule()
    }
}

// MARK: - Unified Semantic Notice Banner

enum OpalNoticeTone {
    case info
    case success
    case warning
    case error
    case loading

    var tint: Color {
        switch self {
        case .info, .loading: return .accentColor
        case .success: return .green
        case .warning: return .orange
        case .error: return .red
        }
    }

    var defaultIcon: String {
        switch self {
        case .info, .loading: return "info.circle.fill"
        case .success: return "checkmark.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        case .error: return "xmark.octagon.fill"
        }
    }
}

/// Single authoritative inline status/warning/error/loading banner shared across all views and sheets.
struct OpalNoticeBanner: View {
    let tone: OpalNoticeTone
    let title: String?
    let message: String
    var icon: String? = nil
    var isSelectable: Bool = false
    var onDismiss: (() -> Void)? = nil

    init(
        tone: OpalNoticeTone,
        title: String? = nil,
        message: String,
        icon: String? = nil,
        isSelectable: Bool = false,
        onDismiss: (() -> Void)? = nil
    ) {
        self.tone = tone
        self.title = title
        self.message = message
        self.icon = icon
        self.isSelectable = isSelectable
        self.onDismiss = onDismiss
    }

    var body: some View {
        HStack(alignment: title == nil ? .center : .top, spacing: OpalSpacing.md) {
            if tone == .loading {
                ProgressView()
                    .controlSize(.small)
                    .padding(.top, title == nil ? 0 : 2)
            } else {
                Image(systemName: icon ?? tone.defaultIcon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(tone.tint)
                    .padding(.top, title == nil ? 0 : 1)
            }

            VStack(alignment: .leading, spacing: 2) {
                if let title, !title.isEmpty {
                    Text(title)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(tone == .error ? .red : .primary)
                }
                messageText
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let onDismiss {
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 10.5, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 22, height: 22)
                }
                .buttonStyle(.plain)
                .help("关闭提示")
                .accessibilityLabel("关闭提示")
            }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 10)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: tone.tint)
    }

    @ViewBuilder
    private var messageText: some View {
        let base = Text(message)
            .font(title == nil ? .callout : .caption)
            .foregroundStyle(title == nil ? (tone == .error ? .red : .primary) : .secondary)
            .fixedSize(horizontal: false, vertical: true)
        if isSelectable {
            base.textSelection(.enabled)
        } else {
            base
        }
    }
}

// MARK: - Unified Headers & Search Field

/// Consistent modal/sheet header featuring a tinted squircle icon badge, title, subtitle, and optional trailing content.
struct OpalSheetHeader<Trailing: View>: View {
    let icon: String
    var iconTint: Color = .accentColor
    let title: String
    let subtitle: String?
    var badgeText: String? = nil
    var badgeColor: Color = .accentColor
    @ViewBuilder let trailing: () -> Trailing

    init(
        icon: String,
        iconTint: Color = .accentColor,
        title: String,
        subtitle: String? = nil,
        badgeText: String? = nil,
        badgeColor: Color = .accentColor,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }
    ) {
        self.icon = icon
        self.iconTint = iconTint
        self.title = title
        self.subtitle = subtitle
        self.badgeText = badgeText
        self.badgeColor = badgeColor
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .center, spacing: OpalSpacing.md) {
            OpalIconBadge(systemName: icon, tint: iconTint, size: 40, iconSize: 18, cornerRadius: 12)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: OpalSpacing.sm) {
                    Text(title)
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)
                    if let badgeText, !badgeText.isEmpty {
                        StatusBadgeView(text: badgeText, color: badgeColor)
                    }
                }
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }

            Spacer(minLength: OpalSpacing.sm)

            trailing()
        }
    }
}

/// Consistent section header shared across Hub, Diagnostics, and Tools.
struct OpalSectionHeader<Trailing: View>: View {
    let title: String
    let systemImage: String
    var badgeText: String? = nil
    var badgeColor: Color = .accentColor
    @ViewBuilder let trailing: () -> Trailing

    init(
        title: String,
        systemImage: String,
        badgeText: String? = nil,
        badgeColor: Color = .accentColor,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }
    ) {
        self.title = title
        self.systemImage = systemImage
        self.badgeText = badgeText
        self.badgeColor = badgeColor
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: OpalSpacing.sm) {
            Label(title, systemImage: systemImage)
                .font(.headline)
            if let badgeText, !badgeText.isEmpty {
                StatusBadgeView(text: badgeText, color: badgeColor)
            }
            Spacer(minLength: OpalSpacing.sm)
            trailing()
        }
    }
}

/// Unified macOS Liquid Glass search bar with clear button and subtle focus/inset border.
struct OpalSearchField: View {
    let placeholder: String
    @Binding var text: String
    var accessibilityClearLabel: String = "清除搜索内容"

    var body: some View {
        HStack(spacing: OpalSpacing.xs) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.secondary)

            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .font(.callout)

            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(accessibilityClearLabel)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.control)
    }
}

// MARK: - Icon Badges, App Monograms & Hover Controls

/// Luminous continuous-squircle SF Symbol container.
struct OpalIconBadge: View {
    let systemName: String
    var tint: Color = .accentColor
    var size: CGFloat = OpalControlSize.badgeIconMedium
    var iconSize: CGFloat = 17
    var cornerRadius: CGFloat = OpalRadius.control

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        ZStack {
            shape
                .fill(
                    LinearGradient(
                        colors: [tint.opacity(0.22), tint.opacity(0.11)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            shape
                .strokeBorder(tint.opacity(0.28), lineWidth: 0.75)
            Image(systemName: systemName)
                .font(.system(size: iconSize, weight: .semibold))
                .foregroundStyle(tint)
        }
        .frame(width: size, height: size)
    }
}

/// Unified Android app icon / monogram thumbnail shared across Hub history, App Picker, APK Installer, and Shortcuts.
struct OpalAppMonogram: View {
    let label: String
    let packageKey: String
    var iconData: Data? = nil
    var fallbackSymbol: String? = nil
    var size: CGFloat = 36
    var cornerRadius: CGFloat = 10
    var showActiveIndicator: Bool = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            iconContent
            if showActiveIndicator {
                Circle()
                    .fill(Color.green)
                    .frame(width: 9, height: 9)
                    .overlay(Circle().strokeBorder(Color(nsColor: .windowBackgroundColor), lineWidth: 1.5))
                    .offset(x: 2, y: -2)
            }
        }
    }

    @ViewBuilder
    private var iconContent: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if let iconData, let image = NSImage(data: iconData) {
            Image(nsImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: size, height: size)
                .clipShape(shape)
                .overlay(shape.strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.6))
        } else {
            let tint = OpalPalette.monogramColor(for: packageKey)
            let initial = String(label.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1))
            ZStack {
                shape
                    .fill(
                        LinearGradient(
                            colors: [tint.opacity(0.22), tint.opacity(0.11)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                shape
                    .strokeBorder(tint.opacity(0.25), lineWidth: 0.7)
                if let fallbackSymbol, initial.isEmpty {
                    Image(systemName: fallbackSymbol)
                        .font(.system(size: size * 0.44, weight: .semibold))
                        .foregroundStyle(tint)
                } else {
                    Text(initial.isEmpty ? "A" : initial)
                        .font(.system(size: size * 0.42, weight: .bold, design: .rounded))
                        .foregroundStyle(tint)
                }
            }
            .frame(width: size, height: size)
        }
    }
}

/// Hover-reactive icon button for floating Liquid Glass toolbars and headers.
struct OpalGlassIconButton: View {
    let systemName: String
    var tint: Color = .primary
    var isActive: Bool = false
    var size: CGFloat = OpalControlSize.iconButtonCompact
    var iconSize: CGFloat = 12
    var helpText: String
    let action: () -> Void

    @State private var isHovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: iconSize, weight: .semibold))
                .frame(width: size, height: size)
                .background(backgroundColor, in: Capsule())
                .foregroundStyle(isActive ? tint : (isHovered ? .primary : tint.opacity(0.92)))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .help(helpText)
        .accessibilityLabel(helpText)
        .onHover { hovering in
            if reduceMotion {
                isHovered = hovering
            } else {
                withAnimation(OpalMotion.hover) {
                    isHovered = hovering
                }
            }
        }
    }

    private var backgroundColor: Color {
        if isActive { return tint.opacity(isHovered ? 0.30 : 0.22) }
        return isHovered ? Color.primary.opacity(0.12) : .clear
    }
}

/// Native macOS Control Center–style hover-highlighted menu row for `MenuBarView`.
struct OpalMenuActionRow: View {
    let title: String
    let systemImage: String
    var subtitle: String? = nil
    var tint: Color = .primary
    var isDestructive: Bool = false
    var isDisabled: Bool = false
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: OpalSpacing.sm) {
                Image(systemName: systemImage)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 20, alignment: .center)

                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(labelColor)
                        .lineLimit(1)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 4)
            }
            .padding(.horizontal, OpalSpacing.sm)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                isHovered && !isDisabled ? Color.primary.opacity(0.08) : Color.clear,
                in: RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous)
            )
            .contentShape(RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
        .opacity(isDisabled ? 0.45 : 1.0)
        .onHover { isHovered = $0 }
    }

    private var iconColor: Color {
        if isDestructive { return .red }
        return tint == .primary ? .secondary : tint
    }

    private var labelColor: Color {
        isDestructive ? .red : .primary
    }
}

/// Unified empty-state row used inside glass cards when no devices or records are available.
struct OpalEmptyStateRow<Accessory: View>: View {
    let icon: String
    var tint: Color = .secondary
    let title: String
    let subtitle: String
    @ViewBuilder let accessory: () -> Accessory

    init(
        icon: String,
        tint: Color = .secondary,
        title: String,
        subtitle: String,
        @ViewBuilder accessory: @escaping () -> Accessory = { EmptyView() }
    ) {
        self.icon = icon
        self.tint = tint
        self.title = title
        self.subtitle = subtitle
        self.accessory = accessory
    }

    var body: some View {
        HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(systemName: icon, tint: tint, size: 38, iconSize: 17, cornerRadius: 11)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: OpalSpacing.sm)
            accessory()
        }
        .padding(OpalSpacing.lg)
    }
}

// MARK: - Shared Continuity Cards (Handoff & Media Remote)

/// Shared Handoff Continuity banner rendered in both `DeviceHubView` and `MenuBarView`.
struct OpalHandoffCard: View {
    let appLabel: String
    let packageName: String
    var isCompact: Bool = false
    let onContinue: () -> Void

    var body: some View {
        HStack(spacing: isCompact ? OpalSpacing.sm : OpalSpacing.md) {
            OpalIconBadge(
                systemName: "iphone.and.arrow.forward",
                tint: .accentColor,
                size: isCompact ? 30 : 36,
                iconSize: isCompact ? 13 : 16,
                cornerRadius: isCompact ? 9 : 11
            )

            VStack(alignment: .leading, spacing: 1) {
                Text("正在手机上使用：\(appLabel)")
                    .font((isCompact ? Font.caption : Font.subheadline).weight(.semibold))
                    .lineLimit(1)
                Text(isCompact ? "在 Mac 悬浮小窗继续" : packageName)
                    .font(isCompact ? .caption2 : .caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.xs)

            Button(isCompact ? "继续" : "在 Mac 小窗继续", action: onContinue)
                .buttonStyle(.borderedProminent)
                .controlSize(isCompact ? .mini : .small)
        }
        .padding(.horizontal, isCompact ? 10 : OpalSpacing.md)
        .padding(.vertical, isCompact ? 8 : 10)
        .opalGlassSurface(.inset, cornerRadius: isCompact ? OpalRadius.tile : OpalRadius.card, tint: .accentColor)
    }
}

/// Shared Media Remote mini-player rendered in both `DeviceHubView` and `MenuBarView`.
struct OpalMediaPlayerCard: View {
    let media: MediaStatePayload
    var isCompact: Bool = false
    let onAction: (MediaActionPayload) -> Void

    var body: some View {
        HStack(spacing: isCompact ? 9 : OpalSpacing.md) {
            artworkThumbnail

            VStack(alignment: .leading, spacing: 2) {
                Text(media.title.isEmpty ? (media.appLabel.isEmpty ? "正在播放媒体" : media.appLabel) : media.title)
                    .font((isCompact ? Font.caption : Font.subheadline).weight(.semibold))
                    .lineLimit(1)
                Text(subtitleText)
                    .font(isCompact ? .caption2 : .caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.xs)

            HStack(spacing: isCompact ? 3 : 6) {
                OpalGlassIconButton(
                    systemName: "backward.fill",
                    size: isCompact ? 24 : 28,
                    iconSize: isCompact ? 10.5 : 12,
                    helpText: "上一首"
                ) {
                    onAction(.previous())
                }

                Button {
                    onAction(.toggle())
                } label: {
                    Image(systemName: media.playing ? "pause.fill" : "play.fill")
                        .font(.system(size: isCompact ? 11.5 : 12.5, weight: .semibold))
                        .frame(width: isCompact ? 24 : 28, height: isCompact ? 22 : 26)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(isCompact ? .mini : .small)
                .help(media.playing ? "暂停" : "播放")
                .accessibilityLabel(media.playing ? "暂停" : "播放")

                OpalGlassIconButton(
                    systemName: "forward.fill",
                    size: isCompact ? 24 : 28,
                    iconSize: isCompact ? 10.5 : 12,
                    helpText: "下一首"
                ) {
                    onAction(.next())
                }
            }
        }
        .padding(.horizontal, isCompact ? 10 : OpalSpacing.md)
        .padding(.vertical, isCompact ? 8 : 10)
        .opalGlassSurface(.inset, cornerRadius: isCompact ? OpalRadius.tile : OpalRadius.card)
    }

    @ViewBuilder
    private var artworkThumbnail: some View {
        let side: CGFloat = isCompact ? 34 : 42
        let radius: CGFloat = isCompact ? OpalRadius.thumbnail : 10
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)

        if let data = media.artworkData, let img = NSImage(data: data) {
            Image(nsImage: img)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: side, height: side)
                .clipShape(shape)
                .overlay(shape.strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.6))
        } else {
            OpalIconBadge(
                systemName: "music.note",
                tint: .accentColor,
                size: side,
                iconSize: isCompact ? 14 : 17,
                cornerRadius: radius
            )
        }
    }

    private var subtitleText: String {
        switch (media.artist.isEmpty, media.appLabel.isEmpty) {
        case (false, false): return "\(media.artist) · \(media.appLabel)"
        case (false, true): return media.artist
        case (true, false): return media.appLabel
        case (true, true): return media.playing ? "播放中" : "已暂停"
        }
    }
}

/// Shared live phone call banner rendered in both `DeviceHubView` and `MenuBarView` when a call is active.
struct OpalCallBannerCard: View {
    let callState: CallStatePayload
    var isCompact: Bool = false
    let onAction: (CallActionPayload) -> Void

    private var tint: Color {
        callState.state == .dialing ? .accentColor : .green
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1.0)) { timeline in
            HStack(spacing: isCompact ? OpalSpacing.sm : OpalSpacing.md) {
                OpalIconBadge(
                    systemName: callState.state == .ringing ? "phone.arrow.down.left.fill" : "phone.fill",
                    tint: tint,
                    size: isCompact ? 30 : 36,
                    iconSize: isCompact ? 13 : 16,
                    cornerRadius: isCompact ? 9 : 11
                )

                VStack(alignment: .leading, spacing: 1) {
                    Text(callState.displayCaller)
                        .font((isCompact ? Font.caption : Font.subheadline).weight(.semibold))
                        .lineLimit(1)
                    Text(CallHudFormatting.statusSubtitle(for: callState, now: timeline.date))
                        .font(isCompact ? .caption2.monospacedDigit() : .caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: OpalSpacing.xs)

                callActionButtons
            }
            .padding(.horizontal, isCompact ? 10 : OpalSpacing.md)
            .padding(.vertical, isCompact ? 8 : 10)
            .opalGlassSurface(.inset, cornerRadius: isCompact ? OpalRadius.tile : OpalRadius.card, tint: tint)
        }
    }

    @ViewBuilder
    private var callActionButtons: some View {
        switch callState.state {
        case .ringing:
            HStack(spacing: isCompact ? 4 : 6) {
                if callState.canAnswer {
                    Button {
                        onAction(.answer())
                    } label: {
                        Image(systemName: "phone.fill")
                            .font(.system(size: isCompact ? 10.5 : 12, weight: .semibold))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                    .controlSize(isCompact ? .mini : .small)
                    .help("接听来电")
                }
                if callState.canReject {
                    Button {
                        onAction(.reject())
                    } label: {
                        Image(systemName: "phone.down.fill")
                            .font(.system(size: isCompact ? 10.5 : 12, weight: .semibold))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .controlSize(isCompact ? .mini : .small)
                    .help("拒接来电")
                }
            }
        case .offhook, .dialing:
            HStack(spacing: isCompact ? 4 : 6) {
                OpalGlassIconButton(
                    systemName: callState.isMuted ? "mic.slash.fill" : "mic.fill",
                    tint: callState.isMuted ? .orange : .secondary,
                    isActive: callState.isMuted,
                    size: isCompact ? 24 : 28,
                    iconSize: isCompact ? 10.5 : 12,
                    helpText: callState.isMuted ? "取消静音" : "静音"
                ) {
                    onAction(.toggleMute())
                }

                OpalGlassIconButton(
                    systemName: callState.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.wave.2",
                    tint: callState.isSpeakerOn ? .accentColor : .secondary,
                    isActive: callState.isSpeakerOn,
                    size: isCompact ? 24 : 28,
                    iconSize: isCompact ? 10.5 : 12,
                    helpText: callState.isSpeakerOn ? "关闭免提" : "开启免提"
                ) {
                    onAction(.toggleSpeaker())
                }

                Button {
                    onAction(.hangup())
                } label: {
                    Image(systemName: "phone.down.fill")
                        .font(.system(size: isCompact ? 10.5 : 12, weight: .semibold))
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .controlSize(isCompact ? .mini : .small)
                .help("挂断通话")
            }
        case .idle:
            EmptyView()
        }
    }
}

/// Shared Quick Dialer popover body used in both `DeviceHubSections` and `MenuBarView`.
struct OpalQuickDialPopoverView: View {
    let deviceName: String
    let onDial: (String) -> Void

    @State private var phoneNumber: String = ""

    private struct DialKey: Hashable {
        let digit: String
        let letters: String
    }

    private let dialKeys: [[DialKey]] = [
        [DialKey(digit: "1", letters: ""), DialKey(digit: "2", letters: "ABC"), DialKey(digit: "3", letters: "DEF")],
        [DialKey(digit: "4", letters: "GHI"), DialKey(digit: "5", letters: "JKL"), DialKey(digit: "6", letters: "MNO")],
        [DialKey(digit: "7", letters: "PQRS"), DialKey(digit: "8", letters: "TUV"), DialKey(digit: "9", letters: "WXYZ")],
        [DialKey(digit: "*", letters: ""), DialKey(digit: "0", letters: "+"), DialKey(digit: "#", letters: "")],
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            HStack(spacing: OpalSpacing.xs) {
                OpalIconBadge(systemName: "phone.badge.waveform.fill", tint: .green, size: 24, iconSize: 11.5, cornerRadius: 7)
                Text("通过「\(deviceName)」快捷拨号")
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
            }

            HStack(spacing: OpalSpacing.xs) {
                TextField("输入或粘贴号码…", text: $phoneNumber)
                    .textFieldStyle(.roundedBorder)
                    .font(.body.monospaced())
                    .onSubmit { triggerDial() }

                if !phoneNumber.isEmpty {
                    OpalGlassIconButton(
                        systemName: "delete.left",
                        tint: .secondary,
                        size: 24,
                        iconSize: 12,
                        helpText: "退格"
                    ) {
                        phoneNumber.removeLast()
                    }
                }
            }

            VStack(spacing: OpalSpacing.xs) {
                ForEach(dialKeys, id: \.self) { row in
                    HStack(spacing: OpalSpacing.xs) {
                        ForEach(row, id: \.self) { key in
                            Button {
                                phoneNumber.append(key.digit)
                            } label: {
                                VStack(spacing: 0) {
                                    Text(key.digit)
                                        .font(.system(size: 14.5, weight: .semibold, design: .rounded))
                                    Text(key.letters.isEmpty ? " " : key.letters)
                                        .font(.system(size: 8, weight: .semibold, design: .rounded))
                                        .foregroundStyle(.secondary)
                                        .tracking(0.6)
                                }
                                .frame(maxWidth: .infinity, minHeight: 34)
                                .opalGlassSurface(.inset, cornerRadius: OpalRadius.control)
                                .opalInteractiveHover(cornerRadius: OpalRadius.control, tint: .green)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }

            Button {
                triggerDial()
            } label: {
                Label("立即在手机端拨打", systemImage: "phone.fill")
                    .font(.callout.weight(.semibold))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.green)
            .disabled(sanitizedNumber.isEmpty)
        }
        .padding(OpalSpacing.md)
        .frame(width: 252)
    }

    private var sanitizedNumber: String {
        let allowed = CharacterSet(charactersIn: "0123456789+*#()- ")
        return String(phoneNumber.unicodeScalars.filter { allowed.contains($0) })
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func triggerDial() {
        let clean = sanitizedNumber
        guard !clean.isEmpty else { return }
        onDial(clean)
        phoneNumber = ""
    }
}
