import AppKit
import OpalDeskCore
import SwiftUI

/// macOS Control Center–inspired Liquid Glass menu bar popover for instant device connection,
/// background companion status, handoff continuity, media playback remote, synced notifications
/// (with OTP copy & inline reply), standalone popouts, and display quick toggles.
public struct MenuBarView: View {
    private enum Layout {
        static let popoverWidth: CGFloat = 334
        static let maxNotificationsHeight: CGFloat = 196
        static let maxVisibleNotifications: Int = 6
        static let maxVisibleHistory: Int = 4
    }

    @Bindable public var model: AppModel
    @Environment(\.openWindow) private var openWindow

    @State private var replyingNotificationKey: String?
    @State private var replyDraftText: String = ""
    @State private var isQuickDialPopoverShown: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        let _ = model.preferences.appLanguage
        let favoriteApps = model.favoriteAppItems()

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            if let conn = model.activeConnection {
                connectedHeaderCard(conn)
                quickControlsGrid
            } else {
                disconnectedStatusAndDevicesCard
            }

            if model.callState.isActive {
                OpalCallBannerCard(callState: model.callState, isCompact: true) { action in
                    model.sendCallAction(action)
                }
            }

            if !model.hostTopApp.isEmpty {
                OpalHandoffCard(
                    appLabel: model.hostTopApp.displayLabel,
                    packageName: model.hostTopApp.packageName,
                    isCompact: true
                ) {
                    model.handoffHostTopApp()
                }
            }

            if let media = model.mediaState, media.active {
                OpalMediaPlayerCard(media: media, isCompact: true) { action in
                    model.sendMediaAction(action)
                }
            }

            if !model.remoteNotifications.isEmpty {
                notificationsCard
            }

            if !favoriteApps.isEmpty {
                favoriteAppsCard(favoriteApps)
            }

            if !model.popoutManager.controllers.isEmpty {
                activePopoutsCard
            } else if !model.popoutHistory.isEmpty {
                recentPopoutsCard
            }

            quickToolsCard
            footerActionsCard
        }
        .padding(OpalSpacing.md)
        .frame(width: Layout.popoverWidth)
        .background(OpalAmbientBackdrop(intensity: 0.55))
    }

    // MARK: - Connected Header & Control Center Grid

    private func connectedHeaderCard(_ conn: ActiveConnectionInfo) -> some View {
        HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(
                systemName: "macbook.and.iphone",
                tint: model.isAuthenticated ? .green : .orange,
                size: 36,
                iconSize: 16,
                cornerRadius: 11
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: OpalSpacing.xs) {
                    Text(conn.deviceName)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    StatusBadgeView(
                        text: conn.transport.shortBadge,
                        color: conn.transport.badgeColor,
                        horizontalPadding: 6,
                        verticalPadding: 1.5
                    )
                }
                Text("\(Int(model.videoMetrics.currentFPS.rounded())) fps · \(Int(model.rttMs.rounded())) ms")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: OpalSpacing.xs)

            OpalGlassIconButton(
                systemName: "xmark.circle.fill",
                tint: .red,
                size: 26,
                iconSize: 14,
                helpText: OpalL10n.text("menubar.disconnect_help")
            ) {
                model.forceDisconnectAndReturnHome()
            }
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var quickControlsGrid: some View {
        VStack(spacing: OpalSpacing.xs) {
            LazyVGrid(
                columns: [GridItem(.flexible(), spacing: OpalSpacing.xs), GridItem(.flexible(), spacing: OpalSpacing.xs)],
                spacing: OpalSpacing.xs
            ) {
                controlTileButton(
                    title: model.displayMode == .dex ? OpalL10n.text("hub.mode.dex") : OpalL10n.text("hub.mode.mirror"),
                    systemImage: model.displayMode == .dex ? "macwindow" : "rectangle.on.rectangle",
                    isActive: true,
                    tint: .accentColor
                ) { model.toggleDisplayMode() }

                controlTileButton(
                    title: model.isBlackoutActive ? OpalL10n.text("menubar.blackout_on") : OpalL10n.text("menubar.blackout_off"),
                    systemImage: model.isBlackoutActive ? "iphone.slash" : "iphone",
                    isActive: model.isBlackoutActive,
                    tint: .blue
                ) { model.toggleBlackout() }

                controlTileButton(
                    title: model.isAudioMuted ? OpalL10n.text("menubar.audio_muted") : OpalL10n.text("menubar.audio_on"),
                    systemImage: model.isAudioMuted ? "speaker.slash" : "speaker.wave.2.fill",
                    isActive: !model.isAudioMuted,
                    tint: .green
                ) { model.toggleAudio() }

                controlTileButton(
                    title: model.isMicEnabled ? OpalL10n.text("menubar.mic_on") : OpalL10n.text("menubar.mic_off"),
                    systemImage: model.isMicEnabled ? "mic.fill" : "mic.slash",
                    isActive: model.isMicEnabled,
                    tint: .green
                ) { model.toggleMicrophone() }

                controlTileButton(
                    title: model.isCameraEnabled ? OpalL10n.text("menubar.cam_on") : OpalL10n.text("menubar.cam_off"),
                    systemImage: model.isCameraEnabled ? "video.fill" : "video.slash",
                    isActive: model.isCameraEnabled,
                    tint: .green
                ) { model.toggleCamera() }

                controlTileButton(
                    title: model.isMainWindowRecording ? OpalL10n.format("menubar.stop_rec_fmt", model.formattedRecordingDuration) : OpalL10n.text("menubar.rec_mp4"),
                    systemImage: model.isMainWindowRecording ? "stop.circle.fill" : "record.circle",
                    isActive: model.isMainWindowRecording,
                    tint: .red,
                    isDisabled: !model.isAuthenticated || (!model.isMainWindowRecording && model.videoMetrics.totalFramesDecoded == 0)
                ) { model.toggleMainWindowRecording() }
            }

            OpalMenuActionRow(
                title: OpalL10n.text("menu.screenshot"),
                systemImage: "camera",
                isDisabled: !model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0
            ) {
                model.captureMainWindowScreenshot()
            }
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func controlTileButton(
        title: String,
        systemImage: String,
        isActive: Bool,
        tint: Color,
        isDisabled: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: OpalSpacing.sm) {
                Image(systemName: systemImage)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(isActive ? tint : .secondary)
                    .frame(width: 18)
                Text(title)
                    .font(.system(size: 11.5, weight: .semibold))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .opalGlassSurface(.inset, cornerRadius: OpalRadius.control, tint: isActive ? tint : nil)
            .opalInteractiveHover(cornerRadius: OpalRadius.control, tint: isActive ? tint : nil)
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
        .opacity(isDisabled ? 0.45 : 1.0)
    }

    // MARK: - Disconnected Quick Connect + Companion Status

    private var disconnectedStatusAndDevicesCard: some View {
        let usbDevices = model.discovery.usbDevices
        let bonjourDevices = model.discovery.bonjourDevices
        let bleDevices = model.discovery.bleNearbyDevices

        return VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            if case let .connected(deviceName) = model.companionState {
                HStack(spacing: OpalSpacing.sm) {
                    OpalIconBadge(
                        systemName: "iphone.radiowaves.left.and.right",
                        tint: .green,
                        size: 32,
                        iconSize: 14,
                        cornerRadius: 10
                    )
                    VStack(alignment: .leading, spacing: 1) {
                        Text(deviceName)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text(OpalL10n.text("menubar.companion_ready"))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 4)
                    OpalGlassIconButton(
                        systemName: "bolt.slash",
                        tint: .secondary,
                        size: 24,
                        iconSize: 11,
                        helpText: OpalL10n.text("hub.companion.disconnect_help")
                    ) {
                        model.disconnectCompanion()
                    }
                }
            } else {
                HStack(spacing: OpalSpacing.sm) {
                    OpalIconBadge(systemName: "display", tint: .secondary, size: 32, iconSize: 14, cornerRadius: 10)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(OpalL10n.text("menubar.ready_title"))
                            .font(.subheadline.weight(.semibold))
                        Text(OpalL10n.text("menubar.ready_subtitle"))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if !usbDevices.isEmpty {
                Divider()
                Text(OpalL10n.text("menubar.section.adb"))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)
                ForEach(usbDevices) { device in
                    OpalMenuActionRow(
                        title: device.displayName,
                        systemImage: device.isWirelessAdb ? "wifi" : "cable.connector",
                        tint: device.isReady ? .accentColor : .secondary,
                        isDisabled: !device.isReady
                    ) {
                        openMainWindow()
                        Task { await model.connectWiredUSB(device) }
                    }
                }
            }

            if !bonjourDevices.isEmpty {
                Divider()
                Text(OpalL10n.text("menubar.section.lan"))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)
                ForEach(bonjourDevices) { device in
                    OpalMenuActionRow(
                        title: device.displayName,
                        systemImage: "wifi",
                        tint: .green
                    ) {
                        openMainWindow()
                        model.connectBonjourDevice(device)
                    }
                }
            }

            if !bleDevices.isEmpty {
                Divider()
                Text(OpalL10n.text("menubar.section.ble"))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)

                ForEach(bleDevices) { device in
                    let isTriggering = model.triggeringBleDeviceId == device.id
                    HStack(spacing: OpalSpacing.sm) {
                        Image(systemName: "dot.radiowaves.left.and.right")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.blue)
                            .frame(width: 20)

                        VStack(alignment: .leading, spacing: 1) {
                            Text(device.trustedDevice.deviceName)
                                .font(.system(size: 12.5, weight: .medium))
                                .lineLimit(1)
                            Text(bleCompactSubtitle(for: device))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }

                        Spacer(minLength: 4)

                        Button {
                            openMainWindow()
                            model.triggerBleHotspot(for: device)
                        } label: {
                            if isTriggering {
                                ProgressView().controlSize(.mini)
                            } else {
                                Text(device.isHotspotActive ? OpalL10n.text("menubar.ble.connect_hotspot") : OpalL10n.text("menubar.ble.open_hotspot"))
                            }
                        }
                        .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .mini, isPill: true))
                        .disabled(model.triggeringBleDeviceId != nil)
                    }
                    .padding(.horizontal, OpalSpacing.sm)
                    .padding(.vertical, 5)
                    .opalGlassSurface(.inset, cornerRadius: OpalRadius.control)
                }
            }
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func bleCompactSubtitle(for device: DiscoveredBleDevice) -> String {
        var parts: [String] = []
        if let battery = device.batteryPercent {
            parts.append(OpalL10n.format("hub.ble.battery_fmt", battery))
        }
        parts.append(device.isHotspotActive ? OpalL10n.text("menubar.ble.hotspot_on") : OpalL10n.text("menubar.ble.hotspot_wake"))
        return parts.joined(separator: " · ")
    }

    // MARK: - Synced Phone Notifications Card

    private var notificationsCard: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            HStack {
                Label(OpalL10n.format("menubar.notif.header_fmt", model.remoteNotifications.count), systemImage: "bell.badge.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Button(OpalL10n.text("common.clear_all")) {
                    replyingNotificationKey = nil
                    replyDraftText = ""
                    model.clearAllRemoteNotifications()
                }
                .buttonStyle(.borderless)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: OpalSpacing.xs) {
                    ForEach(model.remoteNotifications.prefix(Layout.maxVisibleNotifications)) { item in
                        notificationRow(item)
                    }
                }
            }
            .frame(maxHeight: Layout.maxNotificationsHeight)
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func notificationRow(_ item: RemoteNotificationItem) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            notificationHeaderRow(item)

            let validActions = item.displayActions
            if !item.otpCode.isEmpty || item.canReply || !validActions.isEmpty {
                notificationActionChips(for: item, actions: validActions)
            }

            if replyingNotificationKey == item.key {
                notificationInlineReplyBar(for: item)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    private func notificationHeaderRow(_ item: RemoteNotificationItem) -> some View {
        HStack(alignment: .top, spacing: OpalSpacing.xs) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(item.displayAppLabel)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if !item.title.isEmpty {
                        Text("· \(item.title)")
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                    }
                }
                if !item.text.isEmpty {
                    Text(item.text)
                        .font(.caption2)
                        .foregroundStyle(.primary.opacity(0.86))
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { model.openNotificationApp(item) }
            .help(OpalL10n.format("hub.notif.open_help_fmt", item.displayAppLabel))

            if item.clearable {
                OpalGlassIconButton(
                    systemName: "xmark",
                    tint: .secondary,
                    size: 20,
                    iconSize: 9,
                    helpText: OpalL10n.text("hub.notif.dismiss_help")
                ) {
                    if replyingNotificationKey == item.key {
                        replyingNotificationKey = nil
                        replyDraftText = ""
                    }
                    model.dismissRemoteNotification(item)
                }
            }
        }
    }

    private func notificationActionChips(
        for item: RemoteNotificationItem,
        actions: [RemoteNotificationAction]
    ) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: OpalSpacing.xs) {
                if !item.otpCode.isEmpty {
                    Button {
                        model.copyNotificationOtp(item)
                    } label: {
                        Label(OpalL10n.format("menubar.notif.copy_otp_fmt", item.otpCode), systemImage: "doc.on.doc")
                            .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .emerald, size: .mini, isPill: true))
                }

                if item.canReply {
                    Button {
                        if replyingNotificationKey == item.key {
                            replyingNotificationKey = nil
                            replyDraftText = ""
                        } else {
                            replyingNotificationKey = item.key
                            replyDraftText = ""
                        }
                    } label: {
                        Label(item.replyLabel.isEmpty ? OpalL10n.text("menubar.notif.reply") : item.replyLabel, systemImage: "arrowshape.turn.up.left")
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .mini, isPill: true))
                }

                ForEach(actions) { action in
                    Button {
                        model.invokeNotificationAction(item, actionIndex: action.index)
                    } label: {
                        Text(action.label)
                            .lineLimit(1)
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .mini, isPill: true))
                }
            }
        }
    }

    private func notificationInlineReplyBar(for item: RemoteNotificationItem) -> some View {
        HStack(spacing: OpalSpacing.xs) {
            TextField(OpalL10n.text("menubar.notif.reply_placeholder"), text: $replyDraftText)
                .font(.caption)
                .opalLiquidInput(cornerRadius: 8)
                .onSubmit { submitInlineReply(for: item) }

            Button(OpalL10n.text("common.send")) { submitInlineReply(for: item) }
                .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .mini, isPill: true))
                .disabled(replyDraftText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.top, 2)
    }

    private func submitInlineReply(for item: RemoteNotificationItem) {
        let draft = replyDraftText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !draft.isEmpty else { return }
        model.replyToRemoteNotification(item, text: draft)
        replyingNotificationKey = nil
        replyDraftText = ""
    }

    // MARK: - Pinned Favorite Apps, Active / Recent Popouts, Quick Tools & Footer

    private func favoriteAppsCard(_ favorites: [FavoritePopoutAppItem]) -> some View {
        VStack(alignment: .leading, spacing: OpalSpacing.xs) {
            Label(OpalL10n.text("menubar.favorites.title"), systemImage: "star.fill")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.orange)
                .padding(.horizontal, 6)

            LazyVGrid(
                columns: [GridItem(.flexible(), spacing: OpalSpacing.xs), GridItem(.flexible(), spacing: OpalSpacing.xs)],
                spacing: OpalSpacing.xs
            ) {
                ForEach(favorites.prefix(6)) { fav in
                    let isOpen = model.isPopoutActive(pkg: fav.pkg, userId: fav.userId)
                    Button {
                        model.openAppPopoutForRemotePackage(
                            packageName: fav.pkg,
                            activityClass: fav.cls,
                            appLabel: fav.label,
                            userId: fav.userId,
                            profileType: fav.profileType,
                            preferredDeviceKey: fav.deviceKey.isEmpty ? nil : fav.deviceKey,
                            preferredDeviceName: fav.deviceName.isEmpty ? nil : fav.deviceName
                        )
                    } label: {
                        HStack(spacing: OpalSpacing.xs) {
                            OpalAppMonogram(
                                label: fav.label,
                                packageKey: fav.appKey,
                                size: 22,
                                cornerRadius: 6,
                                showActiveIndicator: isOpen
                            )
                            Text(fav.label)
                                .font(.system(size: 11.5, weight: .medium))
                                .lineLimit(1)
                            if let badge = fav.profileBadgeText {
                                StatusBadgeView(
                                    text: badge,
                                    color: .opalProfileTint(fav.profileType),
                                    horizontalPadding: 4.5,
                                    verticalPadding: 0.5,
                                    backgroundOpacity: 0.18
                                )
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 6)
                        .frame(maxWidth: .infinity)
                        .opalGlassSurface(.inset, cornerRadius: OpalRadius.control, tint: isOpen ? .green : nil)
                        .opalInteractiveHover(cornerRadius: OpalRadius.control, tint: isOpen ? .green : .orange)
                    }
                    .buttonStyle(.plain)
                    .help(OpalL10n.format("menubar.favorites.open_help_fmt", fav.label))
                }
            }
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var activePopoutsCard: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.xs) {
            Text(OpalL10n.format("menubar.popouts.active_fmt", model.activePopoutCount, PopoutWindowManager.maxConcurrentWindows))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 4)

            ForEach(model.popoutManager.controllers) { ctrl in
                HStack(spacing: OpalSpacing.xs) {
                    OpalMenuActionRow(
                        title: ctrl.profileBadgeText.map { "\(ctrl.appLabel) (\($0))" } ?? ctrl.appLabel,
                        systemImage: ctrl.isPinnedOnTop ? "pin.fill" : "macwindow",
                        tint: ctrl.isPinnedOnTop ? .orange : .accentColor
                    ) {
                        model.popoutManager.focusWindow(id: ctrl.id)
                    }

                    OpalGlassIconButton(
                        systemName: ctrl.isFavorite ? "star.fill" : "star",
                        tint: ctrl.isFavorite ? .orange : .secondary,
                        isActive: ctrl.isFavorite,
                        size: 24,
                        iconSize: 10.5,
                        helpText: ctrl.isFavorite ? OpalL10n.text("hub.history.unfav_help") : OpalL10n.text("hub.history.fav_help")
                    ) { ctrl.toggleFavorite() }

                    OpalGlassIconButton(
                        systemName: ctrl.isPinnedOnTop ? "pin.slash" : "pin",
                        tint: ctrl.isPinnedOnTop ? .orange : .secondary,
                        size: 24,
                        iconSize: 10.5,
                        helpText: ctrl.isPinnedOnTop ? OpalL10n.text("hub.history.unpin_help") : OpalL10n.text("hub.history.pin_help")
                    ) { ctrl.togglePinnedOnTop() }

                    OpalGlassIconButton(
                        systemName: "xmark",
                        tint: .secondary,
                        size: 24,
                        iconSize: 10,
                        helpText: OpalL10n.text("hub.history.close_help")
                    ) { model.popoutManager.closeWindow(id: ctrl.id, sendDisconnect: true) }
                }
            }
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var recentPopoutsCard: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(OpalL10n.text("hub.history.title"))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 8)
                .padding(.bottom, 2)

            ForEach(model.popoutHistory.prefix(Layout.maxVisibleHistory)) { entry in
                OpalMenuActionRow(
                    title: entry.profileBadgeText.map { "\(entry.displayLabel) (\($0))" } ?? entry.displayLabel,
                    systemImage: entry.pinnedOnTop ? "pin.fill" : "clock",
                    subtitle: entry.deviceName,
                    tint: entry.pinnedOnTop ? .orange : .secondary
                ) {
                    model.openPopoutFromHistory(entry)
                }
            }
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var quickToolsCard: some View {
        VStack(alignment: .leading, spacing: 2) {
            if model.isAnyChannelConnected {
                OpalMenuActionRow(
                    title: OpalL10n.text("menubar.tools.quick_dial"),
                    systemImage: "phone.badge.waveform.fill",
                    tint: .green
                ) {
                    isQuickDialPopoverShown.toggle()
                }
                .popover(isPresented: $isQuickDialPopoverShown, arrowEdge: .leading) {
                    OpalQuickDialPopoverView(deviceName: model.activeChannelDeviceName) { number in
                        isQuickDialPopoverShown = false
                        model.dialPhoneNumber(number)
                    }
                }
            }

            OpalMenuActionRow(title: OpalL10n.text("window.file_browser"), systemImage: "folder", tint: .accentColor) {
                openWindow(id: WindowID.fileBrowser)
                NSApp.activate(ignoringOtherApps: true)
            }

            if model.isAnyChannelConnected {
                if model.finderMountState.isMounted {
                    HStack(spacing: OpalSpacing.xs) {
                        OpalMenuActionRow(
                            title: OpalL10n.text("menu.open_finder_disk"),
                            systemImage: "externaldrive.fill.badge.checkmark",
                            subtitle: OpalL10n.text("menubar.tools.mounted"),
                            tint: .green
                        ) {
                            model.openMountedFinderVolume()
                        }

                        OpalGlassIconButton(
                            systemName: "eject.fill",
                            tint: .secondary,
                            size: 24,
                            iconSize: 10,
                            helpText: OpalL10n.text("hub.companion.eject_disk")
                        ) {
                            model.toggleFinderMount()
                        }
                    }
                } else {
                    OpalMenuActionRow(
                        title: model.finderMountState == .mounting ? OpalL10n.text("menubar.tools.mounting_finder") : OpalL10n.text("menubar.tools.mount_finder"),
                        systemImage: "externaldrive.badge.plus",
                        tint: .blue,
                        isDisabled: model.finderMountState == .mounting
                    ) {
                        model.toggleFinderMount()
                    }
                }
            }

            OpalMenuActionRow(title: OpalL10n.text("menu.install_apk_ellipsis"), systemImage: "shippingbox", tint: .green) {
                model.presentApkFileChooser(targetWindow: .main)
            }
            OpalMenuActionRow(title: OpalL10n.text("menubar.tools.shortcuts"), systemImage: "sparkles.rectangle.stack", tint: .purple) {
                model.openShortcutManager()
            }
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var footerActionsCard: some View {
        VStack(alignment: .leading, spacing: 2) {
            OpalMenuActionRow(title: OpalL10n.text("menubar.footer.show_main"), systemImage: "macwindow") { openMainWindow() }

            SettingsLink {
                HStack(spacing: OpalSpacing.sm) {
                    Image(systemName: "gearshape")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.secondary)
                        .frame(width: 20)
                    Text(OpalL10n.text("common.settings_ellipsis"))
                        .font(.system(size: 12.5, weight: .medium))
                    Spacer()
                }
                .padding(.horizontal, OpalSpacing.sm)
                .padding(.vertical, 6)
                .opalInteractiveHover(cornerRadius: OpalRadius.control)
                .contentShape(RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous))
            }
            .buttonStyle(.plain)

            Divider().padding(.vertical, 2)

            OpalMenuActionRow(
                title: OpalL10n.text("menubar.footer.disconnect"),
                systemImage: "wifi.slash",
                isDisabled: model.activeConnection == nil && !model.isConnectingFlow
            ) {
                model.forceDisconnectAndReturnHome()
            }

            OpalMenuActionRow(
                title: OpalL10n.text("menu.quit_opaldesk"),
                systemImage: "power",
                isDestructive: true
            ) {
                model.forceTerminateApp()
            }
            .keyboardShortcut("q")
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func openMainWindow() {
        openWindow(id: WindowID.main)
        NSApp.activate(ignoringOtherApps: true)
    }
}
