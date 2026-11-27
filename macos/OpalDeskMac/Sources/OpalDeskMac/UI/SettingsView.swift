import AppKit
import OpalDeskCore
import SwiftUI

/// Native macOS tabbed Settings window following Apple HIG (`Form.formStyle(.grouped)`)
/// paired with unified OpalDesk Liquid Glass styling on the trusted-devices management tab.
public struct SettingsView: View {
    @Bindable public var model: AppModel
    @State private var confirmRevokeAll: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        TabView {
            streamSettingsTab
                .tabItem { Label("画面与布局", systemImage: "display") }
            inputAndSyncTab
                .tabItem { Label("输入与同步", systemImage: "keyboard") }
            companionSettingsTab
                .tabItem { Label("伴侣联动", systemImage: "iphone.radiowaves.left.and.right") }
            adbSettingsTab
                .tabItem { Label("有线 ADB", systemImage: "cable.connector") }
            trustedDevicesTab
                .tabItem { Label("已信任设备", systemImage: "lock.shield") }
        }
        .frame(width: 620, height: 560)
    }

    // MARK: - Tab 1: Stream & High-FPS

    private var streamSettingsTab: some View {
        @Bindable var prefs = model.preferences

        return Form {
            Section("编码与刷新率") {
                Picker("视频编码器偏好", selection: $prefs.preferredCodec) {
                    Text("自动 HEVC (H.265，画质更佳、省带宽)").tag(OpalCastVideoCodec.hevc)
                    Text("兼容 H.264 (AVC，老机型兼容性更好)").tag(OpalCastVideoCodec.h264)
                }

                steppedSliderRow(
                    title: "视频目标码率",
                    valueText: "\(prefs.bitrateKbps / 1000) Mbps",
                    value: prefs.bitrateKbps,
                    range: DisplayPresets.bitrateKbpsRange,
                    step: DisplayPresets.bitrateStepKbps
                ) { prefs.bitrateKbps = DisplayPresets.clampBitrateKbps($0) }

                Picker("目标刷新率", selection: $prefs.targetFPS) {
                    ForEach(DisplayPresets.fpsOptions, id: \.self) { fps in
                        Text(Self.fpsOptionLabel(for: fps)).tag(fps)
                    }
                }
                .onChange(of: prefs.targetFPS) { _, newFPS in model.applyTargetFPS(newFPS) }

                Picker("默认显示模式", selection: $prefs.defaultDisplayMode) {
                    Text("扩展桌面 (DeX)").tag(OpalCastDisplayMode.dex)
                    Text("屏幕镜像").tag(OpalCastDisplayMode.mirror)
                }

                Picker("默认显示密度 (DPI)", selection: $prefs.defaultDPI) {
                    ForEach(DisplayPresets.dpiOptions, id: \.self) { dpi in
                        Text("\(dpi) DPI").tag(dpi)
                    }
                }
                .onChange(of: prefs.defaultDPI) { _, newDPI in model.selectDPI(newDPI) }
            }

            Section("辅助显示") {
                Toggle("默认开启手机音频回传", isOn: $prefs.audioEnabled)
                Toggle("在画面右上角显示实时 FPS / RTT / 码率 / UHID 性能浮标 (HUD)", isOn: $prefs.showHUDStats)
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Tab 2: Input Engine & Clipboard Sync

    private var inputAndSyncTab: some View {
        @Bindable var prefs = model.preferences

        return Form {
            Section("输入注入引擎") {
                Picker("输入引擎模式", selection: $prefs.inputBackend) {
                    Text("自动（优先系统注入，受限时切换 UHID）").tag(OpalCastInputBackend.auto)
                    Text("UHID 虚拟外设（绕过 MIUI/HyperOS 注入拦截）").tag(OpalCastInputBackend.uhid)
                    Text("系统事件注入 (INJECT_EVENTS)").tag(OpalCastInputBackend.inject)
                }
                .onChange(of: prefs.inputBackend) { _, newBackend in model.setInputBackend(newBackend) }
                Text("UHID 模式会在手机内核注册虚拟键鼠外设，无需开启额外系统安全模拟点击开关。")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("键盘与触控板快捷键") {
                Toggle("将 ⌘C / ⌘V / ⌘X / ⌘A / ⌘Z / ⌘W 等自动映射为 Android Ctrl 快捷键", isOn: $prefs.mapCommandToCtrl)
                Text("快捷键：⌘[ 返回 · ⌘⇧H 桌面 · ⌘⇧S 截图 · ⌘⇧V 录屏 · ⌘⇧F 文件管理 · ⌘⇧B 熄屏 · ⌘⇧M 静音。")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("双向剪贴板同步（文本 + PNG 截图）") {
                Toggle("自动同步 Mac 与 Android 之间的文本及 PNG 图片剪贴板", isOn: $prefs.clipboardSyncEnabled)
                    .onChange(of: prefs.clipboardSyncEnabled) { _, _ in model.applyClipboardSyncPreference() }
                Text("支持 256 KiB 以内纯文本与 8 MiB 以内 PNG/TIFF 截图自动压缩同步，内置 SHA-256 防回环去重。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Tab 3: Companion Auto-Connect, Notifications, Media & Edge Gestures

    private var companionSettingsTab: some View {
        @Bindable var prefs = model.preferences

        return Form {
            Section("后台轻连接（免投屏跨端联动）") {
                Toggle("自动连接局域网中已信任的手机（后台伴侣）", isOn: $prefs.companionAutoConnectEnabled)
                    .onChange(of: prefs.companionAutoConnectEnabled) { _, _ in model.applyCompanionPreferences() }
                Text("不创建虚拟屏幕、零视频编码开销，静默维持双向剪贴板、通知、媒体播控、前台应用接力与文件管理通道。")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("手机通知同步与验证码") {
                Toggle("同步 Android 手机通知至 macOS 通知中心与菜单栏", isOn: $prefs.notificationsSyncEnabled)
                    .onChange(of: prefs.notificationsSyncEnabled) { _, _ in model.applyCompanionPreferences() }
                Toggle("收到短信 / 应用验证码时自动复制到 Mac 剪贴板", isOn: $prefs.autoCopyOtpEnabled)
                    .disabled(!prefs.notificationsSyncEnabled)
                    .onChange(of: prefs.autoCopyOtpEnabled) { _, _ in model.applyCompanionPreferences() }
                Text("支持点击通知直接弹出对应手机应用小窗，以及在通知横幅和菜单栏中行内快捷回复。")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("媒体播控联动") {
                Toggle("同步手机媒体播放信息至 macOS 播放中心（支持硬件媒体键控制）", isOn: $prefs.mediaRemoteControlEnabled)
                    .onChange(of: prefs.mediaRemoteControlEnabled) { _, _ in model.applyCompanionPreferences() }
            }

            Section("手机来电控制与快捷拨号") {
                Toggle("在 Mac 显示手机来电浮窗与快捷接听/拨号", isOn: $prefs.callControlEnabled)
                    .onChange(of: prefs.callControlEnabled) { _, _ in model.applyCompanionPreferences() }
                Text("手机来电或通话时在屏幕右上角自动弹出免抢焦点浮窗，支持接听、拒接、快捷短信拒接、静音、免提与挂断。")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("蓝牙近场发现与一键开热点") {
                Toggle("启用蓝牙近场发现与一键拉起手机热点", isOn: $prefs.bleNearbyDiscoveryEnabled)
                    .onChange(of: prefs.bleNearbyDiscoveryEnabled) { _, enabled in
                        model.setBleNearbyDiscoveryEnabled(enabled)
                    }
                HStack(spacing: 6) {
                    Image(systemName: bleAuthIconName(for: model.discovery.bleAuthState))
                        .foregroundStyle(bleAuthTint(for: model.discovery.bleAuthState))
                    Text(bleAuthDescription(for: model.discovery.bleAuthState))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Section("触控板边缘导航手势与双指旋转") {
                Toggle("启用触控板四边双指导航手势（返回 / 桌面 / 通知栏）与双指旋转", isOn: $prefs.enableEdgeNavigationGestures)
                    .onChange(of: prefs.enableEdgeNavigationGestures) { _, _ in model.applyCompanionPreferences() }
                Text("在 MacBook 触控板左右边缘双指内滑触发 Android 预测返回，底边上滑返回桌面，顶边下滑拉出通知中心。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Tab 4: ADB Path

    private var adbSettingsTab: some View {
        @Bindable var prefs = model.preferences

        return Form {
            Section("当前识别状态") {
                HStack(spacing: OpalSpacing.sm) {
                    Image(systemName: model.discovery.adbBinaryPath != nil ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(model.discovery.adbBinaryPath != nil ? .green : .orange)
                    Text(model.discovery.adbBinaryPath ?? "未检测到可用的 adb 可执行文件")
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                    Spacer()
                }
            }

            Section("自定义 adb 路径（留空则自动探测）") {
                HStack(spacing: OpalSpacing.sm) {
                    TextField("/opt/homebrew/bin/adb", text: $prefs.customAdbPath)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: prefs.customAdbPath) { _, _ in model.refreshAdbPath() }
                    Button("浏览…") { pickCustomAdbBinary() }
                }
                Text("自动探测顺序：App 内置 → ANDROID_HOME → Homebrew → ~/Library/Android/sdk → 外接卷 Android SDK。")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("实验性：USB 网络共享自动识别 (Beta · 免 ADB)") {
                Toggle("启用 USB 网络共享自动识别 (Beta)", isOn: $prefs.usbTetherDiscoveryEnabled)
                    .onChange(of: prefs.usbTetherDiscoveryEnabled) { _, enabled in
                        model.setUsbTetherDiscoveryEnabled(enabled)
                    }
                Text("Beta 测试功能（默认关闭，不影响主线 USB ADB 与 Wi-Fi 投屏）：开启后将自动识别通过 CDC-NCM 等 USB 网络共享接入的 Android 手机。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Tab 5: Trusted Wireless Devices

    private var trustedDevicesTab: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSheetHeader(
                icon: "lock.shield.fill",
                iconTint: .green,
                title: "已信任的无线 Android 设备",
                subtitle: "配对或无线 ADB 升级后采用 SHA-256 证书指纹固定（TOFU），防止局域网中间人劫持。"
            ) {
                if !model.discovery.trustedDevices.isEmpty {
                    Button("清除全部", role: .destructive) { confirmRevokeAll = true }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                }
            }

            if model.discovery.trustedDevices.isEmpty {
                ContentUnavailableView(
                    "暂无已信任的无线设备",
                    systemImage: "lock.shield",
                    description: Text("首次无线配对或通过无线 ADB 自动升级 Wi-Fi 直连后，设备会出现在此处。")
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
            } else {
                ScrollView {
                    LazyVStack(spacing: OpalSpacing.xs) {
                        ForEach(model.discovery.trustedDevices) { device in
                            trustedDeviceRow(device)
                        }
                    }
                    .padding(OpalSpacing.sm)
                }
                .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
            }
        }
        .padding(OpalSpacing.xl)
        .background(OpalAmbientBackdrop(intensity: 0.55))
        .confirmationDialog(
            "确定要撤销全部已信任的无线 Android 设备吗？",
            isPresented: $confirmRevokeAll,
            titleVisibility: .visible
        ) {
            Button("全部撤销", role: .destructive) { model.revokeAllTrustedDevices() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("撤销后再次无线连接需要重新核对证书指纹，并在手机端确认同意配对请求。")
        }
    }

    private func trustedDeviceRow(_ device: TrustedDevice) -> some View {
        HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(systemName: "iphone.radiowaves.left.and.right", tint: .green, size: 34, iconSize: 15, cornerRadius: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.deviceName)
                    .font(.body.weight(.medium))
                HStack(spacing: 8) {
                    Text(device.shortFingerprint).font(.caption.monospaced())
                    if let host = device.lastHost, let port = device.lastPort {
                        Text("· \(host):\(port)").font(.caption.monospaced())
                    }
                }
                .foregroundStyle(.secondary)
            }
            Spacer()
            Button("撤销", role: .destructive) { model.revokeTrustedDevice(device) }
                .buttonStyle(.bordered)
                .controlSize(.small)
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 9)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    // MARK: - Helpers

    private func steppedSliderRow(
        title: String,
        valueText: String,
        value: Int,
        range: ClosedRange<Int>,
        step: Int,
        onChange: @escaping (Int) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: OpalSpacing.xs) {
            HStack {
                Text(title)
                Spacer()
                Text(valueText).font(.body.monospacedDigit()).foregroundStyle(.secondary)
            }
            Slider(
                value: Binding(
                    get: { Double(value) },
                    set: { onChange(Int(($0 / Double(step)).rounded()) * step) }
                ),
                in: Double(range.lowerBound)...Double(range.upperBound),
                step: Double(step)
            )
        }
    }

    private static func fpsOptionLabel(for fps: Int) -> String {
        switch fps {
        case 60: return "60 fps（标准）"
        case 90: return "90 fps（流畅高刷）"
        case 120: return "120 fps（ProMotion 极速）"
        default: return "\(fps) fps"
        }
    }

    private func bleAuthIconName(for state: BleAuthorizationState) -> String {
        switch state {
        case .ready: return "checkmark.circle.fill"
        case .poweredOff: return "bolt.slash.circle.fill"
        case .unauthorized: return "hand.raised.circle.fill"
        case .unsupported: return "xmark.circle.fill"
        case .unknown: return "info.circle"
        }
    }

    private func bleAuthTint(for state: BleAuthorizationState) -> Color {
        switch state {
        case .ready: return .green
        case .poweredOff, .unauthorized: return .orange
        case .unsupported: return .red
        case .unknown: return .secondary
        }
    }

    private func bleAuthDescription(for state: BleAuthorizationState) -> String {
        switch state {
        case .ready:
            return "蓝牙权限已就绪，正在低功耗扫描附近已信任的 Android 手机。"
        case .poweredOff:
            return "当前 Mac 蓝牙处于关闭状态，开启蓝牙后即可使用近场发现与一键开热点。"
        case .unauthorized:
            return "尚未获得蓝牙权限，请前往「系统设置 → 隐私与安全性 → 蓝牙」允许 OpalDesk。"
        case .unsupported:
            return "当前 Mac 硬件不支持低功耗蓝牙 (BLE)。"
        case .unknown:
            return "正在检测蓝牙权限与硬件状态…"
        }
    }

    private func pickCustomAdbBinary() {
        guard let url = OpalFilePanels.pickAdbExecutable() else { return }
        model.preferences.customAdbPath = url.path
        model.refreshAdbPath()
    }
}
