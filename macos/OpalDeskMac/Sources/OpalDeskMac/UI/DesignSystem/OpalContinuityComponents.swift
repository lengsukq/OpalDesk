import AppKit
import OpalDeskCore
import SwiftUI

// MARK: - Shared Continuity Cards (Handoff, Media Remote, Call Banner & Quick Dialer)

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
                Text(OpalL10n.format("handoff.using_fmt", appLabel))
                    .font((isCompact ? Font.caption : Font.subheadline).weight(.semibold))
                    .lineLimit(1)
                Text(isCompact ? OpalL10n.text("handoff.continue_sub") : packageName)
                    .font(isCompact ? .caption2 : .caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.xs)

            Button(isCompact ? OpalL10n.text("handoff.continue_short") : OpalL10n.text("handoff.continue_full"), action: onContinue)
                .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: isCompact ? .mini : .small, isPill: true))
        }
        .padding(.horizontal, isCompact ? 10 : OpalSpacing.md)
        .padding(.vertical, isCompact ? 8 : 10)
        .opalGlassSurface(.inset, cornerRadius: isCompact ? OpalRadius.tile : OpalRadius.card, tint: OpalFluidPalette.brandBlue)
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
                Text(media.title.isEmpty ? (media.appLabel.isEmpty ? OpalL10n.text("media.playing_fallback") : media.appLabel) : media.title)
                    .font((isCompact ? Font.caption : Font.subheadline).weight(.semibold))
                    .lineLimit(1)
                Text(subtitleText)
                    .font(isCompact ? .caption2 : .caption)
                    .foregroundStyle(OpalFluidPalette.secondaryText)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.xs)

            HStack(spacing: isCompact ? 5 : 7) {
                OpalCircularActionButton(
                    systemName: "backward.fill",
                    size: isCompact ? 26 : 30,
                    iconSize: isCompact ? 10 : 11.5,
                    helpText: OpalL10n.text("media.prev_help")
                ) {
                    onAction(.previous())
                }

                OpalCircularActionButton(
                    systemName: media.playing ? "pause.fill" : "play.fill",
                    fill: OpalFluidPalette.brandBlue,
                    size: isCompact ? 28 : 32,
                    iconSize: isCompact ? 11 : 12.5,
                    helpText: media.playing ? OpalL10n.text("media.pause_help") : OpalL10n.text("media.play_help")
                ) {
                    onAction(.toggle())
                }

                OpalCircularActionButton(
                    systemName: "forward.fill",
                    size: isCompact ? 26 : 30,
                    iconSize: isCompact ? 10 : 11.5,
                    helpText: OpalL10n.text("media.next_help")
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
        case (true, true): return media.playing ? OpalL10n.text("media.state.playing") : OpalL10n.text("media.state.paused")
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
        let btnSize: CGFloat = isCompact ? 28 : 34
        let iconSz: CGFloat = isCompact ? 11 : 13
        switch callState.state {
        case .ringing:
            HStack(spacing: isCompact ? 6 : 8) {
                if callState.canReject {
                    OpalCircularActionButton(
                        systemName: "phone.down.fill",
                        fill: OpalFluidPalette.iosCallRed,
                        size: btnSize,
                        iconSize: iconSz,
                        helpText: OpalL10n.text("call.action.decline_help")
                    ) {
                        onAction(.reject())
                    }
                }
                if callState.canAnswer {
                    OpalCircularActionButton(
                        systemName: "phone.fill",
                        fill: OpalFluidPalette.iosCallGreen,
                        size: btnSize,
                        iconSize: iconSz,
                        helpText: OpalL10n.text("call.action.answer_help")
                    ) {
                        onAction(.answer())
                    }
                }
            }
        case .offhook, .dialing:
            HStack(spacing: isCompact ? 6 : 8) {
                OpalCircularActionButton(
                    systemName: callState.isMuted ? "mic.slash.fill" : "mic.fill",
                    fill: callState.isMuted ? OpalFluidPalette.brandAmber : nil,
                    size: btnSize,
                    iconSize: iconSz,
                    isActive: callState.isMuted,
                    helpText: callState.isMuted ? OpalL10n.text("call.action.unmute_help") : OpalL10n.text("call.action.mute_help")
                ) {
                    onAction(.toggleMute())
                }

                OpalCircularActionButton(
                    systemName: callState.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.wave.2.fill",
                    fill: callState.isSpeakerOn ? OpalFluidPalette.brandBlue : nil,
                    size: btnSize,
                    iconSize: iconSz,
                    isActive: callState.isSpeakerOn,
                    helpText: callState.isSpeakerOn ? OpalL10n.text("call.action.speaker_off_help") : OpalL10n.text("call.action.speaker_on_help")
                ) {
                    onAction(.toggleSpeaker())
                }

                OpalCircularActionButton(
                    systemName: "phone.down.fill",
                    fill: OpalFluidPalette.iosCallRed,
                    size: btnSize,
                    iconSize: iconSz,
                    helpText: OpalL10n.text("call.action.hangup_help")
                ) {
                    onAction(.hangup())
                }
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
                OpalIconBadge(systemName: "phone.badge.waveform.fill", tint: OpalFluidPalette.iosCallGreen, size: 26, iconSize: 12, cornerRadius: 8)
                Text(OpalL10n.format("call.dialer.title_fmt", deviceName))
                    .font(.system(size: 12.5, weight: .semibold))
                    .lineLimit(1)
            }

            HStack(spacing: OpalSpacing.xs) {
                TextField(OpalL10n.text("call.dialer.placeholder"), text: $phoneNumber)
                    .opalLiquidInput(cornerRadius: 11)
                    .font(.system(size: 16, weight: .semibold, design: .rounded).monospacedDigit())
                    .onSubmit { triggerDial() }

                if !phoneNumber.isEmpty {
                    OpalCircularActionButton(
                        systemName: "delete.left.fill",
                        size: 30,
                        iconSize: 12.5,
                        helpText: OpalL10n.text("call.dialer.backspace_help")
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
                                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                                    Text(key.letters.isEmpty ? " " : key.letters)
                                        .font(.system(size: 8.5, weight: .semibold, design: .rounded))
                                        .foregroundStyle(OpalFluidPalette.secondaryText)
                                        .tracking(0.8)
                                }
                                .frame(maxWidth: .infinity, minHeight: 40)
                                .opalGlassSurface(.card, cornerRadius: 12)
                                .opalInteractiveHover(cornerRadius: 12, tint: OpalFluidPalette.iosCallGreen, lift: false)
                            }
                            .buttonStyle(OpalPressStyle())
                        }
                    }
                }
            }

            Button {
                triggerDial()
            } label: {
                Label(OpalL10n.text("call.dialer.call_now"), systemImage: "phone.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .emerald, size: .large, isPill: true))
            .disabled(sanitizedNumber.isEmpty)
        }
        .padding(OpalSpacing.lg)
        .frame(width: 268)
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
