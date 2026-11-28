import AppKit
import OpalDeskCore
import SwiftUI

enum OpalSettingsCategory: String, CaseIterable, Identifiable {
    case display
    case companion
    case devices

    var id: String { rawValue }

    var title: String {
        switch self {
        case .display: return OpalL10n.text("settings.cat.display")
        case .companion: return OpalL10n.text("settings.cat.companion")
        case .devices: return OpalL10n.text("settings.cat.devices")
        }
    }

    var icon: String {
        switch self {
        case .display: return "display"
        case .companion: return "iphone.radiowaves.left.and.right"
        case .devices: return "lock.shield"
        }
    }
}

/// Standalone `⌘,` macOS Settings window hosting the exact same iOS Inset-Grouped settings
/// design as the embedded Hub settings tab (zero dated macOS `Form.formStyle(.grouped)` chrome).
public struct SettingsView: View {
    @Bindable public var model: AppModel

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        let _ = model.preferences.appLanguage
        ScrollView {
            OpalSettingsContentView(model: model, isEmbeddedInHub: false)
                .padding(OpalSpacing.xl)
        }
        .frame(width: 680, height: 600)
        .background(OpalAmbientBackdrop(intensity: 0.72, accent: OpalFluidPalette.brandIndigo))
        .environment(\.opalAccent, OpalFluidPalette.brandIndigo)
    }
}

/// Shared LightIsle / iOS Inset-Grouped settings body used both in `DeviceHubView` and `SettingsView`.
struct OpalSettingsContentView: View {
    @Bindable var model: AppModel
    var isEmbeddedInHub: Bool = true

    @State private var activeCategory: OpalSettingsCategory = .display
    @State private var confirmRevokeAll: Bool = false

    var body: some View {
        let _ = model.preferences.appLanguage
        VStack(alignment: .leading, spacing: OpalSpacing.xl) {
            HStack {
                OpalSegmentedControl(
                    selection: $activeCategory,
                    options: OpalSettingsCategory.allCases.map {
                        OpalSegmentOption(value: $0, label: $0.title, icon: $0.icon)
                    }
                )
                Spacer(minLength: 0)
            }

            switch activeCategory {
            case .display:
                displaySettingsSections
            case .companion:
                inputAndCompanionSections
            case .devices:
                adbAndTrustedDevicesSections
            }
        }
        .confirmationDialog(
            OpalL10n.text("settings.trusted.revoke_all_title"),
            isPresented: $confirmRevokeAll,
            titleVisibility: .visible
        ) {
            Button(OpalL10n.text("settings.trusted.revoke_all_btn"), role: .destructive) { model.revokeAllTrustedDevices() }
            Button(OpalL10n.text("common.cancel"), role: .cancel) {}
        } message: {
            Text(OpalL10n.text("settings.trusted.revoke_all_msg"))
        }
    }

    // MARK: - 1. Display & Stream

    @ViewBuilder
    private var displaySettingsSections: some View {
        @Bindable var prefs = model.preferences

        OpalSettingsGroup(title: OpalL10n.text("lang.section")) {
            OpalSettingsRow(
                title: OpalL10n.text("lang.title"),
                subtitle: OpalL10n.text("lang.footer"),
                icon: "globe",
                iconTint: OpalFluidPalette.brandBlue,
                showsDivider: false
            ) {
                OpalSegmentedControl(
                    selection: $prefs.appLanguage,
                    options: AppLanguage.allCases.map {
                        OpalSegmentOption(value: $0, label: $0.displayName)
                    },
                    compact: true
                )
            }
        }

        OpalSettingsGroup(title: OpalL10n.text("settings.section.codec_fps")) {
            OpalSettingsRow(
                title: OpalL10n.text("settings.codec.label"),
                subtitle: OpalL10n.text("settings.codec.sub"),
                icon: "film",
                iconTint: OpalFluidPalette.brandBlue
            ) {
                OpalSegmentedControl(
                    selection: $prefs.preferredCodec,
                    options: [
                        OpalSegmentOption(value: OpalCastVideoCodec.hevc, label: "HEVC"),
                        OpalSegmentOption(value: OpalCastVideoCodec.h264, label: "H.264"),
                    ],
                    compact: true
                )
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.fps.label"),
                subtitle: OpalL10n.text("settings.fps.sub"),
                icon: "speedometer",
                iconTint: OpalFluidPalette.brandCyan
            ) {
                OpalSegmentedControl(
                    selection: Binding(
                        get: { prefs.targetFPS },
                        set: { newFPS in
                            prefs.targetFPS = newFPS
                            model.applyTargetFPS(newFPS)
                        }
                    ),
                    options: DisplayPresets.fpsOptions.map {
                        OpalSegmentOption(value: $0, label: "\($0) Hz")
                    },
                    compact: true
                )
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.bitrate.label"),
                subtitle: OpalL10n.format("settings.bitrate.sub_fmt", prefs.bitrateKbps / 1000),
                icon: "waveform.path",
                iconTint: OpalFluidPalette.brandIndigo
            ) {
                HStack(spacing: 10) {
                    Slider(
                        value: Binding(
                            get: { Double(prefs.bitrateKbps) },
                            set: {
                                let stepped = Int(($0 / Double(DisplayPresets.bitrateStepKbps)).rounded()) * DisplayPresets.bitrateStepKbps
                                prefs.bitrateKbps = DisplayPresets.clampBitrateKbps(stepped)
                            }
                        ),
                        in: Double(DisplayPresets.bitrateKbpsRange.lowerBound)...Double(DisplayPresets.bitrateKbpsRange.upperBound),
                        step: Double(DisplayPresets.bitrateStepKbps)
                    )
                    .frame(width: 150)

                    Text("\(prefs.bitrateKbps / 1000)M")
                        .font(.system(size: 12, weight: .semibold, design: .rounded).monospacedDigit())
                        .foregroundStyle(OpalFluidPalette.secondaryText)
                        .frame(width: 34, alignment: .trailing)
                }
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.display_mode.label"),
                subtitle: OpalL10n.text("settings.display_mode.sub"),
                icon: "macwindow",
                iconTint: OpalFluidPalette.brandBlue
            ) {
                OpalSegmentedControl(
                    selection: Binding(
                        get: { prefs.defaultDisplayMode },
                        set: { mode in
                            prefs.defaultDisplayMode = mode
                            model.displayMode = mode
                        }
                    ),
                    options: [
                        OpalSegmentOption(value: OpalCastDisplayMode.dex, label: OpalL10n.text("hub.mode.dex")),
                        OpalSegmentOption(value: OpalCastDisplayMode.mirror, label: OpalL10n.text("hub.mode.mirror")),
                    ],
                    compact: true
                )
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.dpi.label"),
                subtitle: OpalL10n.text("settings.dpi.sub"),
                icon: "textformat.size",
                iconTint: OpalFluidPalette.brandViolet,
                showsDivider: false
            ) {
                Picker("", selection: Binding(
                    get: { prefs.defaultDPI },
                    set: { newDPI in
                        prefs.defaultDPI = newDPI
                        model.selectDPI(newDPI)
                    }
                )) {
                    ForEach(DisplayPresets.dpiOptions, id: \.self) { dpi in
                        Text("\(dpi) DPI").tag(dpi)
                    }
                }
                .labelsHidden()
                .pickerStyle(.menu)
                .frame(width: 112)
            }
        }

        OpalSettingsGroup(title: OpalL10n.text("settings.section.overlay")) {
            OpalSettingsRow(
                title: OpalL10n.text("settings.audio.toggle"),
                subtitle: OpalL10n.text("settings.audio.sub"),
                icon: "speaker.wave.2.fill",
                iconTint: OpalFluidPalette.brandEmerald
            ) {
                Toggle("", isOn: $prefs.audioEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.hud.title"),
                subtitle: OpalL10n.text("settings.hud.sub"),
                icon: "gauge.with.dots.needle.67percent",
                iconTint: OpalFluidPalette.brandCyan,
                showsDivider: false
            ) {
                Toggle("", isOn: $prefs.showHUDStats)
                    .labelsHidden()
                    .toggleStyle(.switch)
            }
        }
    }

    // MARK: - 2. Input & Companion Continuity

    @ViewBuilder
    private var inputAndCompanionSections: some View {
        @Bindable var prefs = model.preferences

        OpalSettingsGroup(
            title: OpalL10n.text("settings.section.companion"),
            footer: OpalL10n.text("settings.companion.group_footer")
        ) {
            OpalSettingsRow(
                title: OpalL10n.text("settings.companion.toggle"),
                subtitle: OpalL10n.text("settings.companion.auto_sub"),
                icon: "bolt.horizontal.circle.fill",
                iconTint: OpalFluidPalette.brandEmerald
            ) {
                Toggle("", isOn: $prefs.companionAutoConnectEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .onChange(of: prefs.companionAutoConnectEnabled) { _, _ in model.applyCompanionPreferences() }
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.notifications.toggle"),
                subtitle: OpalL10n.text("settings.notifications.sub"),
                icon: "bell.badge.fill",
                iconTint: OpalFluidPalette.brandBlue
            ) {
                Toggle("", isOn: $prefs.notificationsSyncEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .onChange(of: prefs.notificationsSyncEnabled) { _, _ in model.applyCompanionPreferences() }
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.otp.toggle"),
                subtitle: OpalL10n.text("settings.otp.sub"),
                icon: "key.fill",
                iconTint: OpalFluidPalette.brandAmber
            ) {
                Toggle("", isOn: $prefs.autoCopyOtpEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .disabled(!prefs.notificationsSyncEnabled)
                    .onChange(of: prefs.autoCopyOtpEnabled) { _, _ in model.applyCompanionPreferences() }
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.section.calls"),
                subtitle: OpalL10n.text("settings.calls.sub"),
                icon: "phone.fill",
                iconTint: OpalFluidPalette.iosCallGreen
            ) {
                HStack(spacing: 10) {
                    Button(OpalL10n.text("settings.calls.preview_btn")) {
                        model.previewCallHud()
                    }
                    .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .mini, isPill: true))
                    .disabled(!prefs.callControlEnabled)

                    Toggle("", isOn: $prefs.callControlEnabled)
                        .labelsHidden()
                        .toggleStyle(.switch)
                        .onChange(of: prefs.callControlEnabled) { _, _ in model.applyCompanionPreferences() }
                }
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.section.media"),
                subtitle: OpalL10n.text("settings.media.sub"),
                icon: "music.note",
                iconTint: .pink
            ) {
                Toggle("", isOn: $prefs.mediaRemoteControlEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .onChange(of: prefs.mediaRemoteControlEnabled) { _, _ in model.applyCompanionPreferences() }
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.section.ble"),
                subtitle: bleAuthDescription(for: model.discovery.bleAuthState),
                icon: "dot.radiowaves.left.and.right",
                iconTint: OpalFluidPalette.brandCyan
            ) {
                Toggle("", isOn: $prefs.bleNearbyDiscoveryEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .onChange(of: prefs.bleNearbyDiscoveryEnabled) { _, enabled in
                        model.setBleNearbyDiscoveryEnabled(enabled)
                    }
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.direct_open.title"),
                subtitle: prefs.fileDirectOpenMaxMB > 0
                    ? OpalL10n.format("settings.direct_open.on_sub_fmt", prefs.fileDirectOpenMaxMB)
                    : OpalL10n.text("settings.direct_open.off_sub"),
                icon: "doc.viewfinder.fill",
                iconTint: OpalFluidPalette.brandBlue,
                showsDivider: false
            ) {
                OpalSegmentedControl(
                    selection: $prefs.fileDirectOpenMaxMB,
                    options: FileBrowserPresentation.directOpenMaxMBOptions.map { mb in
                        OpalSegmentOption(
                            value: mb,
                            label: mb == 0 ? OpalL10n.text("settings.direct_open.off") : "\(mb)M"
                        )
                    },
                    compact: true
                )
            }
        }

        OpalSettingsGroup(
            title: OpalL10n.text("settings.input.group"),
            footer: OpalL10n.text("settings.input.group_footer")
        ) {
            OpalSettingsRow(
                title: OpalL10n.text("settings.section.input_engine"),
                subtitle: OpalL10n.text("settings.input.sub"),
                icon: "keyboard",
                iconTint: OpalFluidPalette.brandIndigo
            ) {
                OpalSegmentedControl(
                    selection: Binding(
                        get: { prefs.inputBackend },
                        set: { newBackend in
                            prefs.inputBackend = newBackend
                            model.setInputBackend(newBackend)
                        }
                    ),
                    options: [
                        OpalSegmentOption(value: OpalCastInputBackend.auto, label: OpalL10n.text("common.auto")),
                        OpalSegmentOption(value: OpalCastInputBackend.uhid, label: "UHID"),
                        OpalSegmentOption(value: OpalCastInputBackend.inject, label: OpalL10n.text("settings.input.system_short")),
                    ],
                    compact: true
                )
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.cmd_ctrl.title"),
                subtitle: OpalL10n.text("settings.cmd_ctrl.sub"),
                icon: "command",
                iconTint: OpalFluidPalette.brandBlue
            ) {
                Toggle("", isOn: $prefs.mapCommandToCtrl)
                    .labelsHidden()
                    .toggleStyle(.switch)
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.clipboard.title"),
                subtitle: OpalL10n.text("settings.clipboard.sub"),
                icon: "doc.on.clipboard",
                iconTint: OpalFluidPalette.brandCyan
            ) {
                Toggle("", isOn: $prefs.clipboardSyncEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .onChange(of: prefs.clipboardSyncEnabled) { _, _ in model.applyClipboardSyncPreference() }
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.section.gestures"),
                subtitle: OpalL10n.text("settings.gestures.sub"),
                icon: "hand.draw.fill",
                iconTint: OpalFluidPalette.brandViolet,
                showsDivider: false
            ) {
                Toggle("", isOn: $prefs.enableEdgeNavigationGestures)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .onChange(of: prefs.enableEdgeNavigationGestures) { _, _ in model.applyCompanionPreferences() }
            }
        }
    }

    // MARK: - 3. ADB & Trusted Devices

    @ViewBuilder
    private var adbAndTrustedDevicesSections: some View {
        @Bindable var prefs = model.preferences

        OpalSettingsGroup(
            title: OpalL10n.text("settings.adb.group"),
            footer: OpalL10n.text("settings.adb.footer")
        ) {
            OpalSettingsRow(
                title: OpalL10n.text("settings.adb.detected_title"),
                subtitle: model.discovery.adbBinaryPath ?? OpalL10n.text("settings.adb.not_found"),
                icon: "terminal.fill",
                iconTint: model.discovery.adbBinaryPath != nil ? OpalFluidPalette.brandEmerald : OpalFluidPalette.brandAmber
            ) {
                StatusBadgeView(
                    text: model.discovery.adbBinaryPath != nil ? OpalL10n.text("settings.adb.ready") : OpalL10n.text("settings.adb.missing"),
                    color: model.discovery.adbBinaryPath != nil ? OpalFluidPalette.brandEmerald : OpalFluidPalette.brandAmber,
                    showDot: true
                )
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.adb.custom_title"),
                subtitle: OpalL10n.text("settings.adb.custom_sub"),
                icon: "folder.badge.gearshape",
                iconTint: OpalFluidPalette.brandBlue
            ) {
                HStack(spacing: 8) {
                    TextField("/opt/homebrew/bin/adb", text: $prefs.customAdbPath)
                        .opalLiquidInput(cornerRadius: 9)
                        .frame(width: 210)
                        .onChange(of: prefs.customAdbPath) { _, _ in model.refreshAdbPath() }
                    Button(OpalL10n.text("common.browse")) { pickCustomAdbBinary() }
                        .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .small, isPill: true))
                }
            }

            OpalSettingsRow(
                title: OpalL10n.text("settings.usb_tether.toggle"),
                subtitle: OpalL10n.text("settings.usb_tether.sub"),
                icon: "cable.connector",
                iconTint: OpalFluidPalette.brandAmber,
                showsDivider: false
            ) {
                Toggle("", isOn: $prefs.usbTetherDiscoveryEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .onChange(of: prefs.usbTetherDiscoveryEnabled) { _, enabled in
                        model.setUsbTetherDiscoveryEnabled(enabled)
                    }
            }
        }

        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Text(OpalL10n.text("settings.trusted.title"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.primary.opacity(0.86))
                    .padding(.horizontal, 4)
                Spacer()
                if !model.discovery.trustedDevices.isEmpty {
                    Button(OpalL10n.text("common.clear_all")) { confirmRevokeAll = true }
                        .buttonStyle(OpalLiquidButtonStyle(variant: .destructive, size: .mini, isPill: true))
                }
            }

            VStack(spacing: 0) {
                if model.discovery.trustedDevices.isEmpty {
                    OpalEmptyStateRow(
                        icon: "lock.shield",
                        tint: OpalFluidPalette.brandEmerald,
                        title: OpalL10n.text("settings.trusted.empty_title"),
                        subtitle: OpalL10n.text("settings.trusted.empty_desc")
                    )
                } else {
                    ForEach(Array(model.discovery.trustedDevices.enumerated()), id: \.element.id) { idx, device in
                        trustedDeviceRow(device, showsDivider: idx < model.discovery.trustedDevices.count - 1)
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: OpalRadius.panel, style: .continuous))
            .opalGlassSurface(.panel)
        }
    }

    private func trustedDeviceRow(_ device: TrustedDevice, showsDivider: Bool) -> some View {
        let endpointText: String = {
            if let host = device.lastHost, let port = device.lastPort {
                return "\(OpalL10n.format("hub.fingerprint_fmt", device.shortFingerprint)) · \(host):\(port)"
            }
            return OpalL10n.format("hub.fingerprint_fmt", device.shortFingerprint)
        }()

        return OpalSettingsRow(
            title: device.deviceName,
            subtitle: endpointText,
            icon: "iphone.radiowaves.left.and.right",
            iconTint: OpalFluidPalette.brandEmerald,
            showsDivider: showsDivider
        ) {
            Button(OpalL10n.text("settings.trusted.revoke")) { model.revokeTrustedDevice(device) }
                .buttonStyle(OpalLiquidButtonStyle(variant: .destructive, size: .small, isPill: true))
        }
    }

    private func bleAuthDescription(for state: BleAuthorizationState) -> String {
        switch state {
        case .ready:
            return OpalL10n.text("settings.ble.ready")
        case .poweredOff:
            return OpalL10n.text("settings.ble.off")
        case .unauthorized:
            return OpalL10n.text("settings.ble.unauthorized")
        case .unsupported:
            return OpalL10n.text("settings.ble.unsupported")
        case .unknown:
            return OpalL10n.text("settings.ble.unknown")
        }
    }

    private func pickCustomAdbBinary() {
        guard let url = OpalFilePanels.pickAdbExecutable() else { return }
        model.preferences.customAdbPath = url.path
        model.refreshAdbPath()
    }
}
