import AppKit
import OpalDeskCore
import SwiftUI

enum HubSection: String, CaseIterable, Identifiable {
    case devices
    case popouts
    case settings
    case diagnostics

    var id: String { rawValue }

    var navLabel: String {
        switch self {
        case .devices: return OpalL10n.text("hub.nav.devices")
        case .popouts: return OpalL10n.text("hub.nav.popouts")
        case .settings: return OpalL10n.text("hub.nav.settings")
        case .diagnostics: return OpalL10n.text("hub.nav.diagnostics")
        }
    }

    var title: String {
        switch self {
        case .devices: return OpalL10n.text("hub.nav.devices.title")
        case .popouts: return OpalL10n.text("hub.nav.popouts")
        case .settings: return OpalL10n.text("hub.nav.settings")
        case .diagnostics: return OpalL10n.text("hub.nav.diagnostics")
        }
    }

    var subtitle: String {
        switch self {
        case .devices: return OpalL10n.text("hub.nav.devices.sub")
        case .popouts: return OpalL10n.text("hub.nav.popouts.sub")
        case .settings: return OpalL10n.text("hub.nav.settings.sub")
        case .diagnostics: return OpalL10n.text("hub.nav.diagnostics.sub")
        }
    }

    var icon: String {
        switch self {
        case .devices: return "macbook.and.iphone"
        case .popouts: return "square.grid.2x2"
        case .settings: return "slider.horizontal.3"
        case .diagnostics: return "waveform.path.ecg"
        }
    }

    var tint: Color {
        switch self {
        case .devices: return OpalFluidPalette.brandBlue
        case .popouts: return OpalFluidPalette.brandCyan
        case .settings: return OpalFluidPalette.brandIndigo
        case .diagnostics: return OpalFluidPalette.brandViolet
        }
    }
}

/// LightIsle-inspired iOS-refined macOS workstation hub featuring a continuous fluid canvas,
/// floating Liquid Glass icon rail, fixed breathable page header, and luminous top-lit content cards.
public struct DeviceHubView: View {
    private enum Layout {
        static let maxContentWidth: CGFloat = 940
        static let collapsedRailWidth: CGFloat = 68
        static let expandedRailWidth: CGFloat = 186
    }

    @Bindable public var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @AppStorage("opaldesk.hub.sidebarExpanded") private var sidebarExpanded: Bool = false
    @State private var activeSection: HubSection = .devices
    @State private var showingStatusPopover: Bool = false
    @Namespace private var railSelectionNamespace

    @State var manualHost: String = ""
    @State var manualPortText: String = String(OpalCastProtocol.defaultWifiPort)
    @State var showManualConnect: Bool = false
    @State var isQuickDialPopoverShown: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        let _ = model.preferences.appLanguage
        ZStack(alignment: .bottom) {
            HStack(alignment: .top, spacing: 0) {
                floatingSidebarRail
                    .padding(.leading, 18)
                    .padding(.trailing, 4)
                    .padding(.top, 14)
                    .padding(.bottom, 18)

                detailWorkspace
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

            if let toast = model.toastNotice {
                ToastPillView(
                    message: toast,
                    actionTitle: model.toastActionTitle,
                    onAction: model.toastActionURL.map { url in
                        { model.revealInFinder(url) }
                    }
                )
                .padding(.bottom, OpalSpacing.xl)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .environment(\.opalAccent, activeSection.tint)
        .tint(activeSection.tint)
        .animation(OpalMotion.animation(OpalMotion.smooth, reduceMotion: reduceMotion), value: model.toastNotice)
        .animation(OpalMotion.animation(OpalMotion.navigation, reduceMotion: reduceMotion), value: sidebarExpanded)
        .background(OpalAmbientBackdrop(accent: activeSection.tint))
        .background(WindowChromeConfigurator(isImmersiveCanvas: false))
        .sheet(item: $model.pairingContext) { ctx in
            PairingSheetView(
                context: ctx,
                onRequestPairing: { model.requestPairing() },
                onCancel: { model.cancelPairing() }
            )
        }
        .sheet(item: $model.remoteAppPickerContext) { ctx in
            RemoteAppPickerSheet(model: model, context: ctx) {
                model.closeRemoteAppPicker()
            }
        }
        .confirmationDialog(
            OpalL10n.format("hub.popout_open_title_fmt", model.pendingPopoutReloadPrompt?.appLabel ?? ""),
            isPresented: Binding(
                get: { model.pendingPopoutReloadPrompt != nil },
                set: { if !$0 { model.pendingPopoutReloadPrompt = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let prompt = model.pendingPopoutReloadPrompt {
                Button(OpalL10n.text("hub.popout_reload_btn")) {
                    model.pendingPopoutReloadPrompt = nil
                    prompt.onReloadWindowAndApp()
                }
                Button(OpalL10n.text("hub.popout_focus_btn")) {
                    model.pendingPopoutReloadPrompt = nil
                    prompt.onFocusExisting()
                }
                Button(OpalL10n.text("common.cancel"), role: .cancel) {
                    model.pendingPopoutReloadPrompt = nil
                }
            }
        } message: {
            Text(OpalL10n.text("hub.popout_open_message"))
        }
    }

    // MARK: - Floating Liquid Glass Sidebar Rail (LightIsle Pattern)

    private var floatingSidebarRail: some View {
        VStack(spacing: 8) {
            ForEach(Array(HubSection.allCases.enumerated()), id: \.element.id) { index, section in
                railNavItem(section, shortcutDigit: index + 1)
            }

            Spacer(minLength: 16)

            Divider()
                .opacity(0.45)
                .padding(.horizontal, 6)

            Button {
                withAnimation(OpalMotion.animation(OpalMotion.navigation, reduceMotion: reduceMotion)) {
                    sidebarExpanded.toggle()
                }
            } label: {
                HStack(spacing: 9) {
                    Image(systemName: sidebarExpanded ? "sidebar.left" : "sidebar.right")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(OpalFluidPalette.secondaryText)
                        .frame(width: 24)
                    if sidebarExpanded {
                        Text(OpalL10n.text("hub.nav.collapse"))
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(OpalFluidPalette.secondaryText)
                            .lineLimit(1)
                        Spacer(minLength: 0)
                    }
                }
                .padding(.horizontal, sidebarExpanded ? 12 : 0)
                .frame(maxWidth: .infinity)
                .frame(height: 36)
                .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(OpalPressStyle())
            .help(sidebarExpanded ? OpalL10n.text("hub.nav.collapse_help") : OpalL10n.text("hub.nav.expand_help"))
            .accessibilityLabel(sidebarExpanded ? OpalL10n.text("hub.nav.collapse_help") : OpalL10n.text("hub.nav.expand_help"))
        }
        .padding(9)
        .frame(width: sidebarExpanded ? Layout.expandedRailWidth : Layout.collapsedRailWidth)
        .opalGlassSurface(.floatingBar, cornerRadius: 24)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private func railNavItem(_ section: HubSection, shortcutDigit: Int) -> some View {
        let isSelected = activeSection == section
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)

        return Button {
            withAnimation(OpalMotion.animation(OpalMotion.navigation, reduceMotion: reduceMotion)) {
                activeSection = section
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: section.icon)
                    .font(.system(size: 17, weight: isSelected ? .semibold : .medium))
                    .symbolRenderingMode(.hierarchical)
                    .frame(width: 24)
                    .foregroundStyle(isSelected ? OpalFluidPalette.onAccent : section.tint)

                if sidebarExpanded {
                    Text(section.navLabel)
                        .font(.system(size: 13, weight: isSelected ? .semibold : .medium))
                        .foregroundStyle(isSelected ? OpalFluidPalette.onAccent : .primary)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                    Spacer(minLength: 0)
                }
            }
            .padding(.horizontal, sidebarExpanded ? 12 : 0)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background {
                if isSelected {
                    shape
                        .fill(
                            LinearGradient(
                                colors: [section.tint.opacity(0.92), section.tint],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .overlay {
                            shape.strokeBorder(
                                LinearGradient(
                                    colors: [Color.white.opacity(0.38), Color.white.opacity(0.08)],
                                    startPoint: .top,
                                    endPoint: .bottom
                                ),
                                lineWidth: 1
                            )
                        }
                        .matchedGeometryEffect(id: "hub-nav-pill", in: railSelectionNamespace)
                        .shadow(color: section.tint.opacity(0.28), radius: 10, y: 4)
                }
            }
            .contentShape(shape)
        }
        .buttonStyle(OpalPressStyle())
        .keyboardShortcut(KeyEquivalent(Character("\(shortcutDigit)")), modifiers: [.command])
        .help("\(section.title) (⌘\(shortcutDigit))")
        .accessibilityLabel(section.title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    // MARK: - Detail Workspace (Fixed Header + Scrollable Content)

    private var detailWorkspace: some View {
        VStack(spacing: 0) {
            fixedPageHeader
                .padding(.horizontal, 26)
                .padding(.top, 14)
                .padding(.bottom, 12)
                .frame(maxWidth: Layout.maxContentWidth + 52, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .top)

            ScrollView {
                VStack(alignment: .leading, spacing: OpalSpacing.xl) {
                    if let status = model.statusMessage {
                        OpalNoticeBanner(tone: .loading, message: status)
                    }
                    if let error = model.bannerError {
                        OpalNoticeBanner(
                            tone: .error,
                            message: error,
                            isSelectable: true,
                            onDismiss: { model.bannerError = nil }
                        )
                    }

                    activeSectionContent
                        .id(activeSection)
                        .transition(.opacity)
                }
                .padding(.horizontal, 26)
                .padding(.top, 8)
                .padding(.bottom, 28)
                .frame(maxWidth: Layout.maxContentWidth + 52, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .top)
            }
            .clipped()
        }
    }

    private var fixedPageHeader: some View {
        HStack(alignment: .center, spacing: 14) {
            OpalIconBadge(
                systemName: activeSection.icon,
                tint: activeSection.tint,
                size: 40,
                iconSize: 18,
                cornerRadius: 12
            )

            VStack(alignment: .leading, spacing: 2.5) {
                Text(activeSection.title)
                    .font(.system(size: 21, weight: .semibold))
                    .lineLimit(1)
                Text(activeSection.subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(OpalFluidPalette.secondaryText)
                    .lineLimit(1)
            }

            Spacer(minLength: 14)

            headerStatusCapsule

            Button {
                openFileBrowserWindow()
            } label: {
                Label(OpalL10n.text("hub.companion.files"), systemImage: "folder.fill")
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .prominent, size: .regular, isPill: true))
            .help(OpalL10n.text("hub.tool.files_help"))
        }
    }

    private var headerStatusCapsule: some View {
        @Bindable var prefs = model.preferences
        let online = model.isAnyChannelConnected
        let dotColor = online ? OpalFluidPalette.brandEmerald : OpalFluidPalette.brandAmber
        let statusLabel: String = {
            if model.isAuthenticated {
                return OpalL10n.format("hub.status.casting_fmt", model.activeChannelDeviceName)
            }
            if model.companionState.isConnected {
                return OpalL10n.format("hub.status.companion_fmt", model.activeChannelDeviceName)
            }
            return prefs.defaultDisplayMode == .dex ? OpalL10n.text("hub.status.dex_mode") : OpalL10n.text("hub.status.mirror_mode")
        }()

        return Button {
            showingStatusPopover.toggle()
        } label: {
            HStack(spacing: 7) {
                Circle()
                    .fill(dotColor)
                    .frame(width: 7, height: 7)
                Text(statusLabel)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(OpalFluidPalette.secondaryText)
                    .rotationEffect(.degrees(showingStatusPopover ? 180 : 0))
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 32)
            .opalFloatingGlassCapsule()
            .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(OpalPressStyle())
        .help(OpalL10n.text("hub.status.popover_help"))
        .popover(isPresented: $showingStatusPopover, arrowEdge: .bottom) {
            VStack(alignment: .leading, spacing: OpalSpacing.md) {
                Text(OpalL10n.text("hub.status.default_mode_title"))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(OpalFluidPalette.secondaryText)

                OpalSegmentedControl(
                    selection: Binding(
                        get: { prefs.defaultDisplayMode },
                        set: { newMode in
                            prefs.defaultDisplayMode = newMode
                            model.displayMode = newMode
                        }
                    ),
                    options: [
                        OpalSegmentOption(value: OpalCastDisplayMode.dex, label: OpalL10n.text("hub.mode.dex"), icon: "macwindow"),
                        OpalSegmentOption(value: OpalCastDisplayMode.mirror, label: OpalL10n.text("hub.mode.mirror"), icon: "iphone"),
                    ]
                )

                Divider()

                VStack(spacing: 4) {
                    OpalMenuActionRow(
                        title: OpalL10n.text("menu.install_apk_ellipsis"),
                        systemImage: "shippingbox"
                    ) {
                        showingStatusPopover = false
                        model.presentApkFileChooser(targetWindow: .main)
                    }

                    OpalMenuActionRow(
                        title: OpalL10n.text("hub.tool.shortcuts_help"),
                        systemImage: "sparkles.rectangle.stack"
                    ) {
                        showingStatusPopover = false
                        model.openShortcutManager()
                    }
                }
            }
            .padding(14)
            .frame(width: 280)
        }
    }

    @ViewBuilder
    private var activeSectionContent: some View {
        switch activeSection {
        case .devices:
            if shouldShowCompanionCard {
                companionOnlineCardSection
            }
            if !model.popoutHistory.isEmpty || !model.popoutManager.controllers.isEmpty {
                standaloneHistorySection
            }
            adbDevicesSection
            if model.preferences.usbTetherDiscoveryEnabled {
                usbTetherSection
            }
            wirelessLANSection
            if shouldShowBleNearbySection {
                bleNearbySection
            }
            manualConnectSection

        case .popouts:
            standaloneHistorySection
            shortcutManagerQuickCard

        case .settings:
            OpalSettingsContentView(model: model, isEmbeddedInHub: true)

        case .diagnostics:
            DiagnosticsLogView(model: model)
        }
    }

    private var shortcutManagerQuickCard: some View {
        HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(
                systemName: "sparkles.rectangle.stack.fill",
                tint: OpalFluidPalette.brandCyan,
                size: 40,
                iconSize: 18,
                cornerRadius: 12
            )
            VStack(alignment: .leading, spacing: 3) {
                Text(OpalL10n.text("hub.shortcuts.card_title"))
                    .font(.system(size: 14, weight: .semibold))
                Text(OpalL10n.text("hub.shortcuts.card_sub"))
                    .font(.system(size: 12))
                    .foregroundStyle(OpalFluidPalette.secondaryText)
            }
            Spacer(minLength: 12)
            Button(OpalL10n.text("hub.shortcuts.manage_btn")) {
                model.openShortcutManager()
            }
            .buttonStyle(OpalLiquidButtonStyle(variant: .secondary, size: .regular, isPill: true))
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel)
    }

    func openFileBrowserWindow() {
        openWindow(id: WindowID.fileBrowser)
        NSApp.activate(ignoringOtherApps: true)
    }
}
