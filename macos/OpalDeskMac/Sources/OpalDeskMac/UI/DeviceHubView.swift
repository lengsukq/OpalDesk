import AppKit
import OpalDeskCore
import SwiftUI

/// Native macOS light/dark adaptive connection hub for launching the Samsung DeX desktop experience
/// over USB/wireless ADB or automatic local-network discovery.
public struct DeviceHubView: View {
    @Bindable public var model: AppModel

    @State private var manualHost: String = ""
    @State private var manualPortText: String = String(OpalCastProtocol.defaultWifiPort)
    @State private var showManualConnect: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                headerSection

                if let status = model.statusMessage {
                    infoBanner(icon: "hourglass", text: status, tint: .accentColor, isLoading: true)
                }
                if let error = model.bannerError {
                    errorBanner(error)
                }

                adbDevicesSection
                if model.preferences.usbTetherDiscoveryEnabled {
                    usbTetherSection
                }
                wirelessLANSection
                manualConnectSection
            }
            .padding(24)
            .frame(maxWidth: 820, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
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
                onSubmit: { model.submitPairing(pinCode: $0) },
                onCancel: { model.cancelPairing() }
            )
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        @Bindable var prefs = model.preferences

        return HStack(alignment: .center, spacing: 14) {
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
                Text("连接 Android 设备，体验与 USB 显示器一致的电脑多窗口与融合镜像")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            HStack(spacing: 8) {
                Picker("模式", selection: $prefs.defaultDisplayMode) {
                    Text("电脑桌面").tag(OpalCastDisplayMode.dex)
                    Text("融合镜像").tag(OpalCastDisplayMode.mirror)
                }
                .onChange(of: prefs.defaultDisplayMode) { _, mode in model.displayMode = mode }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: 164)
                .help("选择连接后默认进入电脑多窗口桌面或融合边栏镜像")

                SettingsLink {
                    Image(systemName: "gearshape").font(.system(size: 14))
                }
                .buttonStyle(.bordered)
                .help("打开偏好设置 (⌘,)")
            }
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
                        subtitle: "使用数据线连接手机并开启「USB 调试」，即可一键启动大屏桌面。"
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

            Button(isTrusted ? "进入桌面" : "配对并连接") { model.connectUsbTetherDevice(device) }
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

            Button(isTrusted ? "进入桌面" : "配对并连接") { model.connectBonjourDevice(device) }
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
