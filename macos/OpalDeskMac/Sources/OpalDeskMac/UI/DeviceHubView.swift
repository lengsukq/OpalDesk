import AppKit
import OpalDeskCore
import SwiftUI

enum HubTab: String, CaseIterable, Identifiable {
    case devices = "设备连接"
    case logs = "日志与反馈"

    var id: String { rawValue }
}

/// Native macOS Liquid Glass connection hub hosting desktop workstation launch,
/// background companion continuity (handoff, media remote, OTP), independent floating apps,
/// and the diagnostic logs & feedback console.
public struct DeviceHubView: View {
    private enum Layout {
        static let maxContentWidth: CGFloat = 880
        static let displayModePickerWidth: CGFloat = 166
        static let tabPickerWidth: CGFloat = 296
    }

    @Bindable public var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var activeTab: HubTab = .devices
    @State var manualHost: String = ""
    @State var manualPortText: String = String(OpalCastProtocol.defaultWifiPort)
    @State var showManualConnect: Bool = false
    @State var isQuickDialPopoverShown: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: OpalSpacing.xl) {
                    headerCard

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

                    if activeTab == .devices {
                        if shouldShowCompanionCard {
                            companionOnlineCardSection
                        }
                        standaloneHistorySection
                        adbDevicesSection
                        if model.preferences.usbTetherDiscoveryEnabled {
                            usbTetherSection
                        }
                        wirelessLANSection
                        if shouldShowBleNearbySection {
                            bleNearbySection
                        }
                        manualConnectSection
                    } else {
                        DiagnosticsLogView(model: model)
                    }
                }
                .padding(.horizontal, OpalSpacing.xxl)
                .padding(.top, OpalSpacing.lg)
                .padding(.bottom, OpalSpacing.xxl)
                .frame(maxWidth: Layout.maxContentWidth, alignment: .leading)
                .frame(maxWidth: .infinity)
            }

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
        .animation(OpalMotion.animation(OpalMotion.smooth, reduceMotion: reduceMotion), value: model.toastNotice)
        .background(OpalAmbientBackdrop())
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
            "应用「\(model.pendingPopoutReloadPrompt?.appLabel ?? "")」的小窗已打开",
            isPresented: Binding(
                get: { model.pendingPopoutReloadPrompt != nil },
                set: { if !$0 { model.pendingPopoutReloadPrompt = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let prompt = model.pendingPopoutReloadPrompt {
                Button("重载小窗+应用") {
                    model.pendingPopoutReloadPrompt = nil
                    prompt.onReloadWindowAndApp()
                }
                Button("仅聚焦现有窗口") {
                    model.pendingPopoutReloadPrompt = nil
                    prompt.onFocusExisting()
                }
                Button("取消", role: .cancel) {
                    model.pendingPopoutReloadPrompt = nil
                }
            }
        } message: {
            Text("该应用当前已有运行中的独立悬浮小窗。选择「重载小窗+应用」将断开并重新拉起全新悬浮窗与应用实例。")
        }
    }

    // MARK: - Unified Liquid Glass Header

    private var headerCard: some View {
        @Bindable var prefs = model.preferences

        return VStack(alignment: .leading, spacing: OpalSpacing.lg) {
            HStack(alignment: .center, spacing: OpalSpacing.md) {
                OpalIconBadge(
                    systemName: "macbook.and.iphone",
                    tint: .accentColor,
                    size: OpalControlSize.badgeIconHero,
                    iconSize: 22,
                    cornerRadius: 15
                )

                VStack(alignment: .leading, spacing: 3) {
                    Text("OpalDesk 桌面中心")
                        .font(.title2.weight(.semibold))
                    Text("连接 Android 设备，体验电脑多窗口、独立置顶小窗与全天候跨端伴侣联动")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: OpalSpacing.md)

                headerQuickToolBar
            }

            HStack(alignment: .center, spacing: OpalSpacing.md) {
                Picker("视图", selection: $activeTab) {
                    ForEach(HubTab.allCases) { tab in
                        Text(tab.rawValue).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: Layout.tabPickerWidth)

                Spacer(minLength: OpalSpacing.sm)

                if activeTab == .devices {
                    HStack(spacing: OpalSpacing.sm) {
                        Text("默认模式")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)

                        Picker("模式", selection: $prefs.defaultDisplayMode) {
                            Text("电脑桌面").tag(OpalCastDisplayMode.dex)
                            Text("融合镜像").tag(OpalCastDisplayMode.mirror)
                        }
                        .onChange(of: prefs.defaultDisplayMode) { _, mode in model.displayMode = mode }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                        .frame(width: Layout.displayModePickerWidth)
                        .help("选择连接后默认进入电脑多窗口桌面或融合边栏镜像")
                    }
                }
            }
        }
        .padding(OpalSpacing.xl)
        .opalGlassSurface(.panel)
    }

    private var headerQuickToolBar: some View {
        HStack(spacing: OpalSpacing.xxs) {
            OpalGlassIconButton(
                systemName: "folder",
                size: OpalControlSize.iconButtonRegular,
                iconSize: 13.5,
                helpText: "手机文件与存储管理 (⌘⇧F)"
            ) {
                openWindow(id: WindowID.fileBrowser)
                NSApp.activate(ignoringOtherApps: true)
            }

            OpalGlassIconButton(
                systemName: "shippingbox",
                size: OpalControlSize.iconButtonRegular,
                iconSize: 13.5,
                helpText: "安装 Android 安装包 (.apk/.xapk) (⌘⇧I)"
            ) {
                model.presentApkFileChooser(targetWindow: .main)
            }

            OpalGlassIconButton(
                systemName: "sparkles.rectangle.stack",
                size: OpalControlSize.iconButtonRegular,
                iconSize: 13.5,
                helpText: "管理 Spotlight 与桌面应用快捷方式"
            ) {
                model.openShortcutManager()
            }

            Divider()
                .frame(height: 16)
                .padding(.horizontal, 3)

            SettingsLink {
                Image(systemName: "gearshape")
                    .font(.system(size: 13.5, weight: .semibold))
                    .frame(width: OpalControlSize.iconButtonRegular, height: OpalControlSize.iconButtonRegular)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .help("打开偏好设置 (⌘,)")
            .accessibilityLabel("打开偏好设置")
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.card)
    }

    func openFileBrowserWindow() {
        openWindow(id: WindowID.fileBrowser)
        NSApp.activate(ignoringOtherApps: true)
    }
}
