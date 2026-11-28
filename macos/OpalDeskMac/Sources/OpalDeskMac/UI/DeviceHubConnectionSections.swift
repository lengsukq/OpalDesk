import AppKit
import OpalDeskCore
import SwiftUI

// MARK: - Device Hub Connection Sections (ADB, USB Tether, LAN, BLE & Manual)

extension DeviceHubView {
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
