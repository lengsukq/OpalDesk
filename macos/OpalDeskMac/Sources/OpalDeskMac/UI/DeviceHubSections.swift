import AppKit
import OpalDeskCore
import SwiftUI

// MARK: - Device Hub Content Sections

extension DeviceHubView {
    // MARK: - 1. Background Companion Live Card

    var shouldShowCompanionCard: Bool {
        model.companionState.isActiveOrAttempting
            || !model.hostTopApp.isEmpty
            || (model.mediaState?.active == true)
            || model.callState.isActive
    }

    var companionOnlineCardSection: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            HStack(alignment: .center, spacing: OpalSpacing.md) {
                OpalIconBadge(
                    systemName: "iphone.radiowaves.left.and.right",
                    tint: model.companionState.isConnected ? .green : .orange,
                    size: 40,
                    iconSize: 18,
                    cornerRadius: 12
                )

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: OpalSpacing.sm) {
                        Text(companionTitleText)
                            .font(.headline)
                        StatusBadgeView(
                            text: model.companionState.isConnected ? OpalL10n.text("hub.companion.online") : OpalL10n.text("common.connecting"),
                            color: model.companionState.isConnected ? .green : .orange
                        )
                        if !model.remoteNotifications.isEmpty {
                            StatusBadgeView(text: OpalL10n.format("hub.companion.notifications_fmt", model.remoteNotifications.count), color: .blue)
                        }
                    }
                    Text(OpalL10n.text("hub.companion.subtitle"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: OpalSpacing.sm)

                if model.companionState.isActiveOrAttempting {
                    OpalGlassIconButton(
                        systemName: "bolt.slash",
                        tint: .secondary,
                        size: OpalControlSize.iconButtonCompact,
                        iconSize: 11.5,
                        helpText: OpalL10n.text("hub.companion.disconnect_help")
                    ) {
                        model.disconnectCompanion()
                    }
                }
            }

            HStack(spacing: OpalSpacing.sm) {
                if model.isAnyChannelConnected {
                    Button {
                        isQuickDialPopoverShown.toggle()
                    } label: {
                        Label(OpalL10n.text("hub.companion.quick_dial"), systemImage: "phone.badge.waveform.fill")
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                    .popover(isPresented: $isQuickDialPopoverShown, arrowEdge: .bottom) {
                        OpalQuickDialPopoverView(deviceName: model.activeChannelDeviceName) { number in
                            isQuickDialPopoverShown = false
                            model.dialPhoneNumber(number)
                        }
                    }
                }

                Button {
                    openFileBrowserWindow()
                } label: {
                    Label(OpalL10n.text("hub.companion.files"), systemImage: "folder")
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                .disabled(!model.isAnyChannelConnected)

                companionFinderMountControl

                Button {
                    model.presentApkFileChooser(targetWindow: .main)
                } label: {
                    Label(OpalL10n.text("hub.companion.install_apk"), systemImage: "shippingbox")
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                .disabled(!model.isAnyChannelConnected)

                Spacer(minLength: 0)
            }

            if model.callState.isActive {
                OpalCallBannerCard(callState: model.callState, isCompact: false) { action in
                    model.sendCallAction(action)
                }
            }

            if !model.hostTopApp.isEmpty {
                OpalHandoffCard(
                    appLabel: model.hostTopApp.displayLabel,
                    packageName: model.hostTopApp.packageName,
                    isCompact: false
                ) {
                    model.handoffHostTopApp()
                }
            }

            if let media = model.mediaState, media.active {
                OpalMediaPlayerCard(media: media, isCompact: false) { action in
                    model.sendMediaAction(action)
                }
            }

            if let otpItem = model.remoteNotifications.first(where: { !$0.otpCode.isEmpty }) {
                otpQuickCopyStrip(otpItem)
            }

            if let latestNotif = model.remoteNotifications.first(where: { $0.otpCode.isEmpty }) {
                recentNotificationStrip(latestNotif)
            }
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel)
    }

    @ViewBuilder
    private var companionFinderMountControl: some View {
        if model.finderMountState.isMounted {
            Menu {
                Button(OpalL10n.text("menu.open_finder_disk"), systemImage: "folder") {
                    model.openMountedFinderVolume()
                }
                Divider()
                Button(OpalL10n.text("hub.companion.eject_disk"), systemImage: "eject") {
                    model.toggleFinderMount()
                }
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "externaldrive.fill.badge.checkmark")
                        .font(.system(size: 11, weight: .semibold))
                    Text(OpalL10n.text("hub.companion.mounted_finder"))
                        .font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(OpalFluidPalette.brandEmerald)
                .padding(.horizontal, 11)
                .frame(height: 28)
                .background(OpalFluidPalette.brandEmerald.opacity(0.12), in: Capsule())
                .overlay(Capsule().strokeBorder(OpalFluidPalette.brandEmerald.opacity(0.28), lineWidth: 0.85))
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .help(OpalL10n.text("hub.companion.mounted_finder_help"))
        } else if model.finderMountState == .mounting {
            Button {} label: {
                HStack(spacing: 5) {
                    ProgressView().controlSize(.mini)
                    Text(OpalL10n.text("hub.companion.mounting"))
                }
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
            .disabled(true)
        } else {
            Button {
                model.toggleFinderMount()
            } label: {
                Label(OpalL10n.text("hub.companion.mount_finder"), systemImage: "externaldrive.badge.plus")
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
            .disabled(!model.isAnyChannelConnected)
            .help(OpalL10n.text("hub.companion.mount_finder_help"))
        }
    }

    private func recentNotificationStrip(_ item: RemoteNotificationItem) -> some View {
        let validActions = item.displayActions
        let extraCount = max(0, model.remoteNotifications.filter { $0.otpCode.isEmpty }.count - 1)

        return HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(systemName: "bell.badge.fill", tint: .blue, size: 32, iconSize: 13.5, cornerRadius: 9)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: OpalSpacing.xs) {
                    Text(item.displayAppLabel)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    if !item.title.isEmpty {
                        Text("· \(item.title)")
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                    }
                    if extraCount > 0 {
                        StatusBadgeView(
                            text: OpalL10n.format("hub.notif.more_fmt", extraCount),
                            color: .secondary,
                            horizontalPadding: 6,
                            verticalPadding: 1.5,
                            backgroundOpacity: 0.10
                        )
                    }
                }
                if !item.text.isEmpty {
                    Text(item.text)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { model.openNotificationApp(item) }
            .help(OpalL10n.format("hub.notif.open_help_fmt", item.displayAppLabel))

            HStack(spacing: OpalSpacing.xs) {
                ForEach(validActions.prefix(2)) { action in
                    Button(action.label) {
                        model.invokeNotificationAction(item, actionIndex: action.index)
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .mini, isPill: true))
                }

                OpalGlassIconButton(
                    systemName: "macwindow.on.rectangle",
                    tint: .accentColor,
                    size: 24,
                    iconSize: 11,
                    helpText: OpalL10n.format("hub.notif.open_small_help_fmt", item.displayAppLabel)
                ) {
                    model.openNotificationApp(item)
                }

                if item.clearable {
                    OpalGlassIconButton(
                        systemName: "xmark",
                        tint: .secondary,
                        size: 24,
                        iconSize: 10,
                        helpText: OpalL10n.text("hub.notif.dismiss_help")
                    ) {
                        model.dismissRemoteNotification(item)
                    }
                }
            }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 9)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.card, tint: .blue)
    }

    private func otpQuickCopyStrip(_ item: RemoteNotificationItem) -> some View {
        HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(systemName: "key.fill", tint: .green, size: 32, iconSize: 13, cornerRadius: 9)
            VStack(alignment: .leading, spacing: 1) {
                Text(OpalL10n.format("hub.otp.title_fmt", item.displayAppLabel))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(item.otpCode)
                    .font(.headline.monospaced())
            }
            Spacer()
            Button(OpalL10n.text("hub.otp.copy")) {
                model.copyNotificationOtp(item)
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .emerald, size: .small, isPill: true))
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 9)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.card, tint: OpalFluidPalette.brandEmerald)
    }

    private var companionTitleText: String {
        switch model.companionState {
        case .connected(let name): return name
        case .connecting(let name): return OpalL10n.format("hub.companion.connecting_fmt", name)
        case .authenticating(let name): return OpalL10n.format("hub.companion.authenticating_fmt", name)
        case .reconnecting(let name, let attempt): return OpalL10n.format("hub.companion.reconnecting_fmt", name, attempt)
        default: return model.activeChannelDeviceName
        }
    }

    // MARK: - 2. Recent / Active Standalone Floating Apps Section

    var standaloneHistorySection: some View {
        let activeControllers = model.popoutManager.controllers
        let history = model.popoutHistory.sorted { lhs, rhs in
            let lRunning = model.isPopoutActive(pkg: lhs.appPackage, userId: lhs.appUserId)
            let rRunning = model.isPopoutActive(pkg: rhs.appPackage, userId: rhs.appUserId)
            if lRunning != rRunning { return lRunning }
            let lFav = model.isFavoriteApp(pkg: lhs.appPackage, userId: lhs.appUserId, deviceKey: lhs.deviceKey)
            let rFav = model.isFavoriteApp(pkg: rhs.appPackage, userId: rhs.appUserId, deviceKey: rhs.deviceKey)
            if lFav != rFav { return lFav }
            return false
        }
        let maxCount = PopoutWindowManager.maxConcurrentWindows

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(
                title: OpalL10n.text("hub.history.title"),
                systemImage: "macwindow.on.rectangle",
                badgeText: OpalL10n.format("hub.history.open_count_fmt", activeControllers.count, maxCount),
                badgeColor: activeControllers.count >= maxCount ? .orange : .accentColor
            ) {
                if !activeControllers.isEmpty {
                    Button(OpalL10n.text("hub.history.close_all")) {
                        model.popoutManager.closeAll(sendDisconnect: true)
                    }
                    .buttonStyle(.borderless)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.red)
                }
                if !history.isEmpty {
                    Button(OpalL10n.text("hub.history.clear")) {
                        model.clearPopoutHistory()
                    }
                    .buttonStyle(.borderless)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }

            VStack(alignment: .leading, spacing: OpalSpacing.md) {
                if history.isEmpty && activeControllers.isEmpty {
                    OpalEmptyStateRow(
                        icon: "rectangle.stack.badge.plus",
                        title: OpalL10n.text("hub.history.empty_title"),
                        subtitle: OpalL10n.format("hub.history.empty_subtitle_fmt", maxCount)
                    )
                } else {
                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 230, maximum: 290), spacing: OpalSpacing.md)],
                        spacing: OpalSpacing.md
                    ) {
                        ForEach(history) { entry in
                            historyAppTile(entry)
                        }
                    }
                    .padding(OpalSpacing.lg)
                }
            }
            .opalGlassSurface(.panel)
        }
    }

    private func historyAppTile(_ entry: PopoutHistoryEntry) -> some View {
        let activeCtrl = model.popoutManager.existingController(for: entry.appPackage, userId: entry.appUserId)
        let isRunning = activeCtrl != nil
        let isPinned = activeCtrl?.isPinnedOnTop ?? entry.pinnedOnTop
        let isFavorite = model.isFavoriteApp(pkg: entry.appPackage, userId: entry.appUserId, deviceKey: entry.deviceKey)
        let prefSummary = model.popoutPreferenceSummary(pkg: entry.appPackage, userId: entry.appUserId, deviceKey: entry.deviceKey)
        let tint: Color? = isRunning ? .green : (isFavorite ? .orange : nil)

        return HStack(spacing: OpalSpacing.md) {
            OpalAppMonogram(
                label: entry.displayLabel,
                packageKey: entry.appKey,
                size: 38,
                cornerRadius: 11,
                showActiveIndicator: isRunning
            )

            historyAppTileMetadata(
                entry: entry,
                isRunning: isRunning,
                isPinned: isPinned,
                prefSummary: prefSummary
            )

            Spacer(minLength: OpalSpacing.xxs)

            historyAppTileTrailingActions(
                entry: entry,
                activeCtrl: activeCtrl,
                isFavorite: isFavorite
            )
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 10)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: tint)
        .opalInteractiveHover(cornerRadius: OpalRadius.tile, tint: tint)
        .contentShape(RoundedRectangle(cornerRadius: OpalRadius.tile, style: .continuous))
        .onTapGesture {
            model.openPopoutFromHistory(entry)
        }
    }

    private func historyAppTileMetadata(
        entry: PopoutHistoryEntry,
        isRunning: Bool,
        isPinned: Bool,
        prefSummary: String?
    ) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 5) {
                Text(entry.displayLabel)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if let badge = entry.profileBadgeText {
                    StatusBadgeView(
                        text: badge,
                        color: .opalProfileTint(entry.appProfileType),
                        horizontalPadding: 5.5,
                        verticalPadding: 1,
                        backgroundOpacity: 0.16
                    )
                }
                if isPinned {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 9.5))
                        .foregroundStyle(.orange)
                }
                if let prefSummary {
                    StatusBadgeView(
                        text: prefSummary,
                        color: .accentColor,
                        horizontalPadding: 5.5,
                        verticalPadding: 1,
                        backgroundOpacity: 0.11
                    )
                }
            }
            Text(isRunning ? OpalL10n.format("hub.history.running_meta_fmt", entry.deviceName) : OpalL10n.format("hub.history.opened_meta_fmt", entry.deviceName, entry.openCount))
                .font(.caption2)
                .foregroundStyle(isRunning ? .green : .secondary)
                .lineLimit(1)
        }
    }

    private func historyAppTileTrailingActions(
        entry: PopoutHistoryEntry,
        activeCtrl: PopoutAppController?,
        isFavorite: Bool
    ) -> some View {
        HStack(spacing: 2) {
            OpalGlassIconButton(
                systemName: isFavorite ? "star.fill" : "star",
                tint: isFavorite ? .orange : .secondary,
                isActive: isFavorite,
                size: 24,
                iconSize: 10.5,
                helpText: isFavorite ? OpalL10n.text("hub.history.unfav_help") : OpalL10n.text("hub.history.fav_help")
            ) {
                model.toggleFavoriteApp(pkg: entry.appPackage, userId: entry.appUserId, deviceKey: entry.deviceKey)
            }

            if let ctrl = activeCtrl {
                OpalGlassIconButton(
                    systemName: ctrl.isPinnedOnTop ? "pin.fill" : "pin",
                    tint: ctrl.isPinnedOnTop ? .orange : .secondary,
                    isActive: ctrl.isPinnedOnTop,
                    size: 24,
                    iconSize: 10.5,
                    helpText: ctrl.isPinnedOnTop ? OpalL10n.text("hub.history.unpin_help") : OpalL10n.text("hub.history.pin_help")
                ) {
                    ctrl.togglePinnedOnTop()
                }

                OpalGlassIconButton(
                    systemName: "xmark.circle.fill",
                    tint: .secondary,
                    size: 24,
                    iconSize: 11.5,
                    helpText: OpalL10n.text("hub.history.close_help")
                ) {
                    model.popoutManager.closeWindow(id: ctrl.id, sendDisconnect: true)
                }
            } else {
                OpalGlassIconButton(
                    systemName: "xmark",
                    tint: .secondary,
                    size: 24,
                    iconSize: 10,
                    helpText: OpalL10n.text("hub.history.remove_help")
                ) {
                    model.removePopoutHistoryEntry(entry)
                }
            }
        }
    }

    // MARK: - 3. USB & Wireless Debugging Section

    var adbDevicesSection: some View {
        let discovery = model.discovery

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(title: OpalL10n.text("hub.adb.title"), systemImage: "cable.connector") {
                if discovery.isScanningUSB { ProgressView().controlSize(.small) }
                Button { Task { await model.refreshUSBDevices() } } label: {
                    Label(OpalL10n.text("common.refresh"), systemImage: "arrow.clockwise").font(.caption.weight(.medium))
                }
                .buttonStyle(.borderless)
                .disabled(discovery.isScanningUSB)
            }

            VStack(spacing: 0) {
                if discovery.adbBinaryPath == nil {
                    OpalEmptyStateRow(
                        icon: "exclamationmark.triangle",
                        tint: .orange,
                        title: OpalL10n.text("hub.adb.missing_title"),
                        subtitle: OpalL10n.text("hub.adb.missing_subtitle")
                    ) {
                        HStack(spacing: OpalSpacing.sm) {
                            Button(OpalL10n.text("hub.adb.pick_btn")) { pickAdbBinary() }
                                .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .small, isPill: true))
                            SettingsLink { Text(OpalL10n.text("common.settings")) }
                                .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                        }
                    }
                } else if discovery.usbDevices.isEmpty {
                    OpalEmptyStateRow(
                        icon: "cable.connector.horizontal",
                        title: OpalL10n.text("hub.adb.empty_title"),
                        subtitle: OpalL10n.text("hub.adb.empty_subtitle")
                    )
                } else {
                    ForEach(Array(discovery.usbDevices.enumerated()), id: \.element.id) { index, device in
                        DeviceConnectionRow(
                            icon: device.isWirelessAdb ? "iphone.radiowaves.left.and.right" : "iphone.gen3",
                            iconTint: device.isReady ? .accentColor : .secondary,
                            title: device.displayName,
                            badgeText: device.isReady
                                ? (device.isWirelessAdb ? OpalL10n.text("hub.adb.ready_wireless") : OpalL10n.text("hub.adb.ready_usb"))
                                : device.state.displayLabel,
                            badgeColor: device.isReady ? .green : .orange,
                            subtitle: device.isWirelessAdb
                                ? OpalL10n.format("hub.adb.wireless_sub_fmt", device.serial)
                                : OpalL10n.format("hub.adb.usb_sub_fmt", device.serial),
                            primaryTitle: OpalL10n.text("hub.row.enter_desktop"),
                            isDisabled: !device.isReady || model.isConnectingFlow,
                            onPopout: { model.openRemoteAppPicker(for: .adb(device)) },
                            onPrimary: { Task { await model.connectWiredUSB(device) } }
                        )
                        if index < discovery.usbDevices.count - 1 {
                            Divider().padding(.leading, 64)
                        }
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: OpalRadius.panel, style: .continuous))
            .opalGlassSurface(.panel)
        }
    }

    // MARK: - 4. Beta USB Tethering Section

    var usbTetherSection: some View {
        let discovery = model.discovery
        let tetherDevices = discovery.usbTetherDevices

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(title: OpalL10n.text("hub.tether.title"), systemImage: "network", badgeText: "Beta", badgeColor: .orange) {
                if discovery.isScanningUsbTether { ProgressView().controlSize(.small) }
                Button { Task { await model.refreshUsbTetherDevices() } } label: {
                    Label(OpalL10n.text("common.refresh"), systemImage: "arrow.clockwise").font(.caption.weight(.medium))
                }
                .buttonStyle(.borderless)
                .disabled(discovery.isScanningUsbTether)
            }

            VStack(spacing: 0) {
                if tetherDevices.isEmpty {
                    OpalEmptyStateRow(
                        icon: "network.badge.shield.half.filled",
                        tint: .orange,
                        title: OpalL10n.text("hub.tether.empty_title"),
                        subtitle: OpalL10n.text("hub.tether.empty_subtitle")
                    )
                } else {
                    ForEach(Array(tetherDevices.enumerated()), id: \.element.id) { index, device in
                        let isTrusted = discovery.isTrusted(device)
                        let detailParts = [
                            "\(device.host):\(device.port)",
                            device.interfaceLabel,
                            device.shortFingerprint.isEmpty ? nil : OpalL10n.format("hub.fingerprint_fmt", device.shortFingerprint),
                        ].compactMap { $0 }.joined(separator: " · ")

                        DeviceConnectionRow(
                            icon: "cable.connector.horizontal",
                            iconTint: .accentColor,
                            title: device.displayName,
                            badgeText: isTrusted ? OpalL10n.text("hub.badge.trusted") : OpalL10n.text("hub.badge.needs_pairing"),
                            badgeColor: isTrusted ? .green : .blue,
                            subtitle: detailParts,
                            primaryTitle: isTrusted ? OpalL10n.text("hub.row.enter_desktop") : OpalL10n.text("hub.row.request_pairing"),
                            isDisabled: model.isConnectingFlow || model.isProbingManualHost,
                            onPopout: { model.openRemoteAppPicker(for: .usbTether(device)) },
                            onPrimary: { model.connectUsbTetherDevice(device) }
                        )
                        if index < tetherDevices.count - 1 {
                            Divider().padding(.leading, 64)
                        }
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: OpalRadius.panel, style: .continuous))
            .opalGlassSurface(.panel)
        }
    }

    // MARK: - 5. Wireless LAN Section

    var wirelessLANSection: some View {
        let bonjourDevices = model.discovery.bonjourDevices

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(title: OpalL10n.text("hub.lan.title"), systemImage: "wifi") {
                HStack(spacing: 6) {
                    Circle().fill(Color.green).frame(width: 7, height: 7)
                    Text(OpalL10n.text("hub.lan.scanning"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            VStack(spacing: 0) {
                if bonjourDevices.isEmpty {
                    OpalEmptyStateRow(
                        icon: "dot.radiowaves.left.and.right",
                        title: OpalL10n.text("hub.lan.empty_title"),
                        subtitle: OpalL10n.text("hub.lan.empty_subtitle")
                    )
                } else {
                    ForEach(Array(bonjourDevices.enumerated()), id: \.element.id) { index, device in
                        let isTrusted = model.discovery.isTrusted(device)
                        let detailParts = [
                            device.model.isEmpty ? nil : device.model,
                            device.fingerprintPrefix.isEmpty ? nil : OpalL10n.format("hub.fingerprint_fmt", device.shortFingerprint),
                        ].compactMap { $0 }.joined(separator: " · ")

                        DeviceConnectionRow(
                            icon: "iphone.radiowaves.left.and.right",
                            iconTint: .accentColor,
                            title: device.displayName,
                            badgeText: isTrusted ? OpalL10n.text("hub.badge.trusted") : OpalL10n.text("hub.badge.needs_pairing"),
                            badgeColor: isTrusted ? .green : .blue,
                            subtitle: detailParts.isEmpty ? OpalL10n.text("hub.lan.bonjour_sub") : detailParts,
                            primaryTitle: isTrusted ? OpalL10n.text("hub.row.enter_desktop") : OpalL10n.text("hub.row.request_pairing"),
                            isDisabled: model.isConnectingFlow || model.isProbingManualHost,
                            onPopout: { model.openRemoteAppPicker(for: .bonjour(device)) },
                            onPrimary: { model.connectBonjourDevice(device) }
                        )
                        if index < bonjourDevices.count - 1 {
                            Divider().padding(.leading, 64)
                        }
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: OpalRadius.panel, style: .continuous))
            .opalGlassSurface(.panel)
        }
    }

    // MARK: - 5b. BLE Nearby Trusted Devices & 1-Click Hotspot

    var shouldShowBleNearbySection: Bool {
        !model.discovery.bleNearbyDevices.isEmpty || model.bleHotspotBanner != nil
    }

    var bleNearbySection: some View {
        let bleDevices = model.discovery.bleNearbyDevices

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(
                title: OpalL10n.text("hub.ble.title"),
                systemImage: "dot.radiowaves.left.and.right",
                badgeText: OpalL10n.text("hub.ble.badge"),
                badgeColor: .blue
            ) {
                Text(OpalL10n.text("hub.ble.hint"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let banner = model.bleHotspotBanner {
                OpalNoticeBanner(
                    tone: noticeTone(for: banner.kind),
                    title: banner.title,
                    message: banner.message,
                    isSelectable: true,
                    onDismiss: banner.kind == .loading ? nil : { model.dismissBleHotspotBanner() }
                )
            }

            if !bleDevices.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(bleDevices.enumerated()), id: \.element.id) { index, device in
                        BleNearbyDeviceRow(
                            device: device,
                            subtitle: bleDeviceSubtitle(for: device),
                            isTriggering: model.triggeringBleDeviceId == device.id,
                            isAnyTriggering: model.triggeringBleDeviceId != nil,
                            onTrigger: { model.triggerBleHotspot(for: device) }
                        )
                        if index < bleDevices.count - 1 {
                            Divider().padding(.leading, 64)
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: OpalRadius.panel, style: .continuous))
                .opalGlassSurface(.panel)
            }
        }
    }

    private func bleDeviceSubtitle(for device: DiscoveredBleDevice) -> String {
        var parts: [String] = []
        if !device.trustedDevice.model.isEmpty {
            parts.append(device.trustedDevice.model)
        }
        parts.append(OpalL10n.format("hub.ble.signal_fmt", device.rssi))
        if !device.supportsHotspotTrigger && !device.isHotspotActive {
            parts.append(OpalL10n.text("hub.ble.fallback_hint"))
        } else {
            parts.append(OpalL10n.text("hub.ble.trusted_hint"))
        }
        return parts.joined(separator: " · ")
    }

    private func noticeTone(for kind: BleHotspotBannerState.Kind) -> OpalNoticeTone {
        switch kind {
        case .loading: return .loading
        case .info: return .info
        case .success: return .success
        case .warning: return .warning
        case .error: return .error
        }
    }

    // MARK: - 6. Manual IP:Port Connection

    var manualConnectSection: some View {
        DisclosureGroup(isExpanded: $showManualConnect) {
            HStack(spacing: OpalSpacing.sm) {
                TextField(OpalL10n.text("hub.manual.host_placeholder"), text: $manualHost)
                    .opalLiquidInput()
                    .onSubmit(triggerManualConnect)

                TextField(OpalL10n.text("hub.manual.port_placeholder"), text: $manualPortText)
                    .opalLiquidInput()
                    .frame(width: 88)
                    .onSubmit(triggerManualConnect)

                Button(action: triggerManualConnect) {
                    if model.isProbingManualHost {
                        ProgressView().controlSize(.small)
                    } else {
                        Text(OpalL10n.text("hub.manual.connect"))
                    }
                }
                .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
                .disabled(
                    manualHost.trimmingCharacters(in: .whitespaces).isEmpty
                        || model.isProbingManualHost
                        || model.isConnectingFlow
                )
            }
            .padding(.top, OpalSpacing.md)
        } label: {
            Label(OpalL10n.text("hub.manual.title"), systemImage: "network")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(OpalFluidPalette.secondaryText)
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel)
    }

    // MARK: - Actions

    private func triggerManualConnect() {
        let port = UInt16(manualPortText.trimmingCharacters(in: .whitespaces)) ?? OpalCastProtocol.defaultWifiPort
        Task { await model.connectManualHost(host: manualHost, port: port) }
    }

    private func pickAdbBinary() {
        guard let url = OpalFilePanels.pickAdbExecutable() else { return }
        model.preferences.customAdbPath = url.path
        model.refreshAdbPath()
        Task { await model.refreshUSBDevices() }
    }
}

// MARK: - Unified Device Row Components (DRY across ADB, USB Tether, Bonjour & BLE)

private struct HubDeviceListRowContainer<Content: View>: View {
    @ViewBuilder let content: () -> Content
    @State private var isHovered = false

    var body: some View {
        HStack(spacing: OpalSpacing.md) {
            content()
        }
        .padding(.horizontal, OpalSpacing.lg)
        .padding(.vertical, 13)
        .background(isHovered ? Color.primary.opacity(0.035) : .clear)
        .onHover { isHovered = $0 }
    }
}

private struct DeviceConnectionRow: View {
    let icon: String
    let iconTint: Color
    let title: String
    let badgeText: String
    let badgeColor: Color
    let subtitle: String
    let primaryTitle: String
    let isDisabled: Bool
    let onPopout: () -> Void
    let onPrimary: () -> Void

    var body: some View {
        HubDeviceListRowContainer {
            OpalIconBadge(systemName: icon, tint: iconTint, size: 38, iconSize: 17, cornerRadius: 11)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: OpalSpacing.sm) {
                    Text(title)
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    StatusBadgeView(text: badgeText, color: badgeColor)
                }
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.sm)

            HStack(spacing: OpalSpacing.sm) {
                Button(OpalL10n.text("hub.row.standalone_window"), action: onPopout)
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .regular, isPill: true))
                    .disabled(isDisabled)

                Button(primaryTitle, action: onPrimary)
                    .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
                    .disabled(isDisabled)
            }
        }
    }
}

private struct BleNearbyDeviceRow: View {
    let device: DiscoveredBleDevice
    let subtitle: String
    let isTriggering: Bool
    let isAnyTriggering: Bool
    let onTrigger: () -> Void

    var body: some View {
        let statusText = device.isHotspotActive
            ? OpalL10n.text("hub.ble.status_hotspot")
            : (device.isLanShareActive ? OpalL10n.text("hub.ble.status_lan") : OpalL10n.text("hub.ble.status_nearby"))
        let statusColor: Color = device.isHotspotActive ? OpalFluidPalette.brandEmerald : OpalFluidPalette.brandBlue

        HubDeviceListRowContainer {
            OpalIconBadge(
                systemName: "iphone.radiowaves.left.and.right",
                tint: OpalFluidPalette.brandBlue,
                size: 38,
                iconSize: 17,
                cornerRadius: 11
            )

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: OpalSpacing.sm) {
                    Text(device.trustedDevice.deviceName)
                        .font(.body.weight(.semibold))
                        .lineLimit(1)

                    StatusBadgeView(text: statusText, color: statusColor, showDot: device.isHotspotActive)

                    if let battery = device.batteryPercent {
                        StatusBadgeView(
                            text: OpalL10n.format("hub.ble.battery_fmt", battery),
                            color: battery <= 20 ? OpalFluidPalette.brandAmber : OpalFluidPalette.brandEmerald
                        )
                    }
                }

                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(OpalFluidPalette.secondaryText)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.sm)

            Button(action: onTrigger) {
                if isTriggering {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small)
                        Text(OpalL10n.text("hub.ble.waking"))
                    }
                } else {
                    Label(
                        device.isHotspotActive ? OpalL10n.text("hub.ble.get_hotspot") : OpalL10n.text("hub.ble.enable_hotspot"),
                        systemImage: "personalhotspot"
                    )
                }
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
            .disabled(isAnyTriggering)
        }
    }
}
