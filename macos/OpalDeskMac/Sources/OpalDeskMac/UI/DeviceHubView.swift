import AppKit
import OpalDeskCore
import SwiftUI

private enum HubTab: String, CaseIterable, Identifiable {
    case devices = "设备连接"
    case logs = "日志与反馈"

    var id: String { rawValue }
}

/// Native macOS light/dark adaptive connection hub for launching the OpalDesk desktop experience,
/// independent standalone floating app windows (up to 3 concurrent with always-on-top + history),
/// and the in-app diagnostic logs & feature feedback console.
public struct DeviceHubView: View {
    @Bindable public var model: AppModel

    @State private var activeTab: HubTab = .devices
    @State private var manualHost: String = ""
    @State private var manualPortText: String = String(OpalCastProtocol.defaultWifiPort)
    @State private var showManualConnect: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    headerSection

                    if let status = model.statusMessage {
                        infoBanner(icon: "hourglass", text: status, tint: .accentColor, isLoading: true)
                    }
                    if let error = model.bannerError {
                        errorBanner(error)
                    }

                    if activeTab == .devices {
                        standaloneHistorySection
                        adbDevicesSection
                        if model.preferences.usbTetherDiscoveryEnabled {
                            usbTetherSection
                        }
                        wirelessLANSection
                        manualConnectSection
                    } else {
                        DiagnosticsLogView(model: model)
                    }
                }
                .padding(24)
                .frame(maxWidth: 860, alignment: .leading)
                .frame(maxWidth: .infinity)
            }

            if let toast = model.toastNotice {
                ToastPillView(message: toast)
                    .padding(.bottom, 18)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.toastNotice)
        .background(
            ZStack {
                Color(nsColor: .windowBackgroundColor)
                RadialGradient(
                    colors: [Color.accentColor.opacity(0.14), Color.clear],
                    center: .topLeading,
                    startRadius: 20,
                    endRadius: 520
                )
                RadialGradient(
                    colors: [Color.indigo.opacity(0.11), Color.clear],
                    center: .bottomTrailing,
                    startRadius: 30,
                    endRadius: 480
                )
            }
        )
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

    // MARK: - Header

    private var headerSection: some View {
        @Bindable var prefs = model.preferences

        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color.accentColor.opacity(0.14))
                        .frame(width: 46, height: 46)
                    Image(systemName: "macbook.and.iphone")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text("OpalDesk 桌面中心")
                        .font(.title2.weight(.semibold))
                    Text("连接 Android 设备，体验电脑多窗口、独立置顶小窗与融合镜像")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                HStack(spacing: 8) {
                    if activeTab == .devices {
                        Picker("模式", selection: $prefs.defaultDisplayMode) {
                            Text("电脑桌面").tag(OpalCastDisplayMode.dex)
                            Text("融合镜像").tag(OpalCastDisplayMode.mirror)
                        }
                        .onChange(of: prefs.defaultDisplayMode) { _, mode in model.displayMode = mode }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                        .frame(width: 164)
                        .help("选择连接后默认进入电脑多窗口桌面或融合边栏镜像")
                    }

                    SettingsLink {
                        Image(systemName: "gearshape").font(.system(size: 14))
                    }
                    .buttonStyle(.bordered)
                    .help("打开偏好设置 (⌘,)")
                }
            }

            Picker("视图", selection: $activeTab) {
                ForEach(HubTab.allCases) { tab in
                    Text(tab.rawValue).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 320)
        }
    }

    // MARK: - Recent / Active Standalone Floating Apps Section

    private var standaloneHistorySection: some View {
        let activeControllers = model.popoutManager.controllers
        let history = model.popoutHistory
        let maxCount = PopoutWindowManager.maxConcurrentWindows

        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Label("历史 APP 小窗", systemImage: "macwindow.on.rectangle")
                    .font(.headline)
                StatusBadgeView(
                    text: "已开 \(activeControllers.count)/\(maxCount)",
                    color: activeControllers.count >= maxCount ? .orange : .accentColor
                )
                Spacer()
                if !activeControllers.isEmpty {
                    Button("关闭全部小窗") {
                        model.popoutManager.closeAll(sendDisconnect: true)
                    }
                    .buttonStyle(.borderless)
                    .font(.caption)
                    .foregroundStyle(.red)
                }
                if !history.isEmpty {
                    Button("清空历史") {
                        model.clearPopoutHistory()
                    }
                    .buttonStyle(.borderless)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }

            VStack(alignment: .leading, spacing: 10) {
                if history.isEmpty && activeControllers.isEmpty {
                    emptyStateRow(
                        icon: "rectangle.stack.badge.plus",
                        title: "暂无历史 APP 小窗记录",
                        subtitle: "点击下方任意设备的「应用小窗」，即可免开全屏桌面直接弹出独立悬浮窗（最多同时开启 \(maxCount) 个，支持窗口置顶）。"
                    )
                } else {
                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 220, maximum: 280), spacing: 10)],
                        spacing: 10
                    ) {
                        ForEach(history) { entry in
                            historyAppCard(entry)
                        }
                    }
                    .padding(14)
                }
            }
            .opalDeskCardContainer()
        }
    }

    private func historyAppCard(_ entry: PopoutHistoryEntry) -> some View {
        let activeCtrl = model.popoutManager.existingController(for: entry.appPackage)
        let isRunning = activeCtrl != nil
        let isPinned = activeCtrl?.isPinnedOnTop ?? entry.pinnedOnTop

        return HStack(spacing: 10) {
            ZStack(alignment: .topTrailing) {
                let palette: [Color] = [.blue, .green, .indigo, .orange, .cyan, .pink, .purple]
                let tint = palette[abs(entry.appPackage.hashValue) % palette.count]
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(tint.opacity(0.16))
                    .frame(width: 36, height: 36)
                    .overlay {
                        Text(String(entry.displayLabel.prefix(1)))
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(tint)
                    }
                if isRunning {
                    Circle()
                        .fill(Color.green)
                        .frame(width: 8, height: 8)
                        .offset(x: 2, y: -2)
                }
            }

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(entry.displayLabel)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    if isPinned {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 9))
                            .foregroundStyle(.orange)
                    }
                }
                Text("\(entry.deviceName) · \(isRunning ? "运行中（点击重载/聚焦）" : "打开 \(entry.openCount) 次")")
                    .font(.caption2)
                    .foregroundStyle(isRunning ? .green : .secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            if let ctrl = activeCtrl {
                Button {
                    ctrl.togglePinnedOnTop()
                } label: {
                    Image(systemName: ctrl.isPinnedOnTop ? "pin.fill" : "pin")
                        .font(.system(size: 11))
                        .foregroundStyle(ctrl.isPinnedOnTop ? .orange : .secondary)
                }
                .buttonStyle(.borderless)
                .help(ctrl.isPinnedOnTop ? "取消置顶" : "置顶该悬浮窗")

                Button {
                    model.popoutManager.closeWindow(id: ctrl.id, sendDisconnect: true)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .help("关闭此小窗")
            } else {
                Button {
                    model.removePopoutHistoryEntry(entry)
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .help("移除此历史记录")
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background(
            (isRunning ? Color.green.opacity(0.09) : Color.primary.opacity(0.04)),
            in: RoundedRectangle(cornerRadius: 13, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .strokeBorder(isRunning ? Color.green.opacity(0.32) : Color.primary.opacity(0.08), lineWidth: 0.9)
        )
        .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        .onTapGesture {
            model.openPopoutFromHistory(entry)
        }
    }

    // MARK: - USB & Wireless Debugging Section

    private var adbDevicesSection: some View {
        let discovery = model.discovery

        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Label("已连接的手机（USB / 无线调试）", systemImage: "cable.connector")
                    .font(.headline)
                Spacer()
                if discovery.isScanningUSB { ProgressView().controlSize(.small) }
                Button { Task { await model.refreshUSBDevices() } } label: {
                    Label("刷新", systemImage: "arrow.clockwise").font(.caption)
                }
                .buttonStyle(.borderless)
                .disabled(discovery.isScanningUSB)
            }

            VStack(spacing: 0) {
                if discovery.adbBinaryPath == nil {
                    emptyStateRow(
                        icon: "exclamationmark.triangle",
                        tint: .orange,
                        title: "未找到 adb 工具",
                        subtitle: "请选择本机或外接硬盘中的 adb 可执行文件，或前往偏好设置配置路径。"
                    ) {
                        HStack(spacing: 8) {
                            Button("选择 adb 文件...") { pickAdbBinary() }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                            SettingsLink { Text("偏好设置") }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                        }
                    }
                } else if discovery.usbDevices.isEmpty {
                    emptyStateRow(
                        icon: "cable.connector.horizontal",
                        title: "等待 USB 或无线调试设备接入",
                        subtitle: "使用数据线连接手机并开启「USB 调试」，即可一键启动大屏桌面或单应用悬浮窗。"
                    )
                } else {
                    ForEach(Array(discovery.usbDevices.enumerated()), id: \.element.id) { index, device in
                        adbDeviceRow(device)
                        if index < discovery.usbDevices.count - 1 { Divider().padding(.leading, 56) }
                    }
                }
            }
            .opalDeskCardContainer()
        }
    }

    private func adbDeviceRow(_ device: AdbDevice) -> some View {
        let readyBadgeText = device.isWirelessAdb ? "已就绪 · 无线调试" : "已就绪 · USB 有线"

        return HStack(spacing: 12) {
            Image(systemName: device.isWirelessAdb ? "iphone.radiowaves.left.and.right" : "iphone.gen3")
                .font(.system(size: 20))
                .foregroundStyle(device.isReady ? Color.accentColor : Color.secondary)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(device.displayName).font(.body.weight(.medium))
                    StatusBadgeView(
                        text: device.isReady ? readyBadgeText : device.state.displayLabel,
                        color: device.isReady ? .green : .orange
                    )
                }
                Text(device.isWirelessAdb
                    ? "无线调试地址：\(device.serial) · 连接后自动切换高速无线直连"
                    : "设备标识：\(device.serial)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button("应用小窗") {
                model.openRemoteAppPicker(for: .adb(device))
            }
            .buttonStyle(.bordered)
            .disabled(!device.isReady || model.isConnectingFlow)

            Button("进入桌面") { Task { await model.connectWiredUSB(device) } }
                .buttonStyle(.borderedProminent)
                .disabled(!device.isReady || model.isConnectingFlow)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    // MARK: - Beta USB Tethering Section (Opt-in, Hidden by Default)

    private var usbTetherSection: some View {
        let discovery = model.discovery
        let tetherDevices = discovery.usbTetherDevices

        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Label("USB 网络共享设备（免 ADB）", systemImage: "network")
                    .font(.headline)
                StatusBadgeView(text: "Beta", color: .orange)
                Spacer()
                if discovery.isScanningUsbTether { ProgressView().controlSize(.small) }
                Button { Task { await model.refreshUsbTetherDevices() } } label: {
                    Label("刷新", systemImage: "arrow.clockwise").font(.caption)
                }
                .buttonStyle(.borderless)
                .disabled(discovery.isScanningUsbTether)
            }

            VStack(spacing: 0) {
                if tetherDevices.isEmpty {
                    emptyStateRow(
                        icon: "network.badge.shield.half.filled",
                        tint: .orange,
                        title: "未识别到 USB 网络共享手机",
                        subtitle: "Beta 测试功能（不保证所有机型正常）：请用数据线连接手机并开启系统「USB 网络共享」及手机端共享服务；macOS 原生支持较新安卓 CDC-NCM，旧 RNDIS 机型建议使用上方有线 ADB。"
                    )
                } else {
                    ForEach(Array(tetherDevices.enumerated()), id: \.element.id) { index, device in
                        usbTetherDeviceRow(device)
                        if index < tetherDevices.count - 1 { Divider().padding(.leading, 56) }
                    }
                }
            }
            .opalDeskCardContainer()
        }
    }

    private func usbTetherDeviceRow(_ device: DiscoveredUsbTetherDevice) -> some View {
        let isTrusted = model.discovery.isTrusted(device)

        return HStack(spacing: 12) {
            Image(systemName: "cable.connector.horizontal")
                .font(.system(size: 20))
                .foregroundStyle(Color.accentColor)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(device.displayName).font(.body.weight(.medium))
                    StatusBadgeView(text: isTrusted ? "已信任" : "需配对", color: isTrusted ? .green : .blue)
                }
                HStack(spacing: 8) {
                    Text("\(device.host):\(device.port)").font(.caption.monospaced())
                    Text("· \(device.interfaceLabel)")
                    if !device.shortFingerprint.isEmpty {
                        Text("· 安全指纹 \(device.shortFingerprint)").font(.caption.monospaced())
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            Button("应用小窗") {
                model.openRemoteAppPicker(for: .usbTether(device))
            }
            .buttonStyle(.bordered)
            .disabled(model.isConnectingFlow || model.isProbingManualHost)

            Button(isTrusted ? "进入桌面" : "请求配对") { model.connectUsbTetherDevice(device) }
                .buttonStyle(.borderedProminent)
                .disabled(model.isConnectingFlow || model.isProbingManualHost)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    // MARK: - Wireless LAN Section

    private var wirelessLANSection: some View {
        let bonjourDevices = model.discovery.bonjourDevices

        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Label("附近无线设备", systemImage: "wifi").font(.headline)
                Spacer()
                HStack(spacing: 5) {
                    Circle().fill(Color.green).frame(width: 7, height: 7)
                    Text("自动搜索中")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            VStack(spacing: 0) {
                if bonjourDevices.isEmpty {
                    emptyStateRow(
                        icon: "dot.radiowaves.left.and.right",
                        title: "正在局域网中搜索已开启服务的手机...",
                        subtitle: "请确保手机与 Mac 处于同一 Wi-Fi，并在手机端开启无线大屏服务。"
                    )
                } else {
                    ForEach(Array(bonjourDevices.enumerated()), id: \.element.id) { index, device in
                        bonjourDeviceRow(device)
                        if index < bonjourDevices.count - 1 { Divider().padding(.leading, 56) }
                    }
                }
            }
            .opalDeskCardContainer()
        }
    }

    private func bonjourDeviceRow(_ device: DiscoveredBonjourDevice) -> some View {
        let isTrusted = model.discovery.isTrusted(device)

        return HStack(spacing: 12) {
            Image(systemName: "iphone.radiowaves.left.and.right")
                .font(.system(size: 20))
                .foregroundStyle(Color.accentColor)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(device.displayName).font(.body.weight(.medium))
                    StatusBadgeView(text: isTrusted ? "已信任" : "需配对", color: isTrusted ? .green : .blue)
                }
                HStack(spacing: 8) {
                    if !device.model.isEmpty { Text(device.model) }
                    if !device.fingerprintPrefix.isEmpty {
                        Text("安全指纹 \(device.shortFingerprint)").font(.caption.monospaced())
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            Button("应用小窗") {
                model.openRemoteAppPicker(for: .bonjour(device))
            }
            .buttonStyle(.bordered)
            .disabled(model.isConnectingFlow || model.isProbingManualHost)

            Button(isTrusted ? "进入桌面" : "请求配对") { model.connectBonjourDevice(device) }
                .buttonStyle(.borderedProminent)
                .disabled(model.isConnectingFlow || model.isProbingManualHost)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    // MARK: - Manual IP:Port Connection

    private var manualConnectSection: some View {
        DisclosureGroup(isExpanded: $showManualConnect) {
            HStack(spacing: 10) {
                TextField("手机局域网 IP（如 192.168.31.102）", text: $manualHost)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(triggerManualConnect)

                TextField("端口", text: $manualPortText)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 82)
                    .onSubmit(triggerManualConnect)

                Button(action: triggerManualConnect) {
                    if model.isProbingManualHost {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("连接")
                    }
                }
                .buttonStyle(.bordered)
                .disabled(manualHost.trimmingCharacters(in: .whitespaces).isEmpty || model.isProbingManualHost || model.isConnectingFlow)
            }
            .padding(.top, 10)
        } label: {
            Label("手动输入 IP 地址连接", systemImage: "network")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .padding(14)
        .opalDeskCardContainer()
    }

    private func triggerManualConnect() {
        let port = UInt16(manualPortText.trimmingCharacters(in: .whitespaces)) ?? OpalCastProtocol.defaultWifiPort
        Task { await model.connectManualHost(host: manualHost, port: port) }
    }

    private func pickAdbBinary() {
        let panel = NSOpenPanel()
        panel.title = "选择 adb 可执行文件"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.showsHiddenFiles = true
        if panel.runModal() == .OK, let url = panel.url {
            model.preferences.customAdbPath = url.path
            model.refreshAdbPath()
            Task { await model.refreshUSBDevices() }
        }
    }

    // MARK: - Reusable Rows & Banners

    private func emptyStateRow<Accessory: View>(
        icon: String,
        tint: Color = .secondary,
        title: String,
        subtitle: String,
        @ViewBuilder accessory: () -> Accessory = { EmptyView() }
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 22)).foregroundStyle(tint)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.subheadline.weight(.medium))
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            accessory()
        }
        .padding(16)
    }

    private func infoBanner(icon: String, text: String, tint: Color, isLoading: Bool) -> some View {
        HStack(spacing: 10) {
            if isLoading { ProgressView().controlSize(.small) } else { Image(systemName: icon).foregroundStyle(tint) }
            Text(text).font(.callout)
            Spacer()
        }
        .padding(12)
        .background(tint.opacity(0.1), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
            Text(message).font(.callout).textSelection(.enabled)
            Spacer()
            Button { model.bannerError = nil } label: { Image(systemName: "xmark").font(.caption) }
                .buttonStyle(.borderless)
                .help("关闭提示")
        }
        .padding(12)
        .background(Color.red.opacity(0.1), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(Color.red.opacity(0.22), lineWidth: 1)
        )
    }
}
