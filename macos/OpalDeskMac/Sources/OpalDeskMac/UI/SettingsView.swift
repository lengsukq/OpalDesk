import AppKit
import OpalDeskCore
import SwiftUI

/// Native macOS tabbed Settings window covering stream codec/FPS/adaptive single width,
/// input backend/keyboard/clipboard sync, ADB path detection, and trusted wireless devices.
public struct SettingsView: View {
    private static let singleWidthDpStep: Int = 20

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
            adbSettingsTab
                .tabItem { Label("有线 ADB", systemImage: "cable.connector") }
            trustedDevicesTab
                .tabItem { Label("已信任设备", systemImage: "lock.shield") }
        }
        .frame(width: 560, height: 470)
    }

    // MARK: - Tab 1: Stream, High-FPS & Adaptive Single Layout

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

    // MARK: - Tab 2: Input Engine, Shortcuts & Bidirectional Clipboard Sync

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
                Text("快捷键：⌘[ 返回 · ⌘⇧H 桌面 · ⌘⇧B 熄屏 · ⌘⇧M 静音 · ⌘⇧D 切换模式 · 触控板三指手势直达对应操作。")
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

    // MARK: - Tab 3: ADB Path

    private var adbSettingsTab: some View {
        @Bindable var prefs = model.preferences

        return Form {
            Section("当前识别状态") {
                HStack {
                    Image(systemName: model.discovery.adbBinaryPath != nil ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(model.discovery.adbBinaryPath != nil ? .green : .orange)
                    Text(model.discovery.adbBinaryPath ?? "未检测到可用的 adb 可执行文件")
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                    Spacer()
                }
            }

            Section("自定义 adb 路径（留空则自动探测）") {
                HStack(spacing: 8) {
                    TextField("/opt/homebrew/bin/adb", text: $prefs.customAdbPath)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: prefs.customAdbPath) { _, _ in model.refreshAdbPath() }
                    Button("浏览...") { pickCustomAdbBinary() }
                }
                Text("自动探测顺序：App 内置 → ANDROID_HOME → Homebrew → ~/Library/Android/sdk → 外接卷 Android SDK。")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("实验性：USB 网络共享自动识别 (Beta · 免 ADB)") {
                Toggle("启用 USB 网络共享自动识别 (Beta)", isOn: $prefs.usbTetherDiscoveryEnabled)
                    .onChange(of: prefs.usbTetherDiscoveryEnabled) { _, enabled in
                        model.setUsbTetherDiscoveryEnabled(enabled)
                    }
                Text("Beta 测试功能（默认关闭，不影响主线 USB ADB 与 Wi-Fi 投屏，不保证所有设备均可正常使用）：开启后将自动识别通过 CDC-NCM 等 USB 网络共享接入的 Android 手机。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Tab 4: Trusted Wireless Devices

    private var trustedDevicesTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("已信任的无线 Android 设备").font(.headline)
                    Text("配对或无线 ADB 自动升级后采用 SHA-256 证书指纹固定（TOFU）校验，防止局域网中间人劫持。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if !model.discovery.trustedDevices.isEmpty {
                    Button("清除全部", role: .destructive) { confirmRevokeAll = true }
                        .buttonStyle(.bordered)
                }
            }

            if model.discovery.trustedDevices.isEmpty {
                ContentUnavailableView(
                    "暂无已信任的无线设备",
                    systemImage: "lock.shield",
                    description: Text("首次无线配对或通过无线 ADB 自动升级 Wi-Fi 直连后，设备会出现在此处。")
                )
            } else {
                List(model.discovery.trustedDevices) { device in
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(device.deviceName).font(.body.weight(.medium))
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
                            .buttonStyle(.borderless)
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.inset)
            }
        }
        .padding(16)
        .confirmationDialog("确定要撤销全部已信任的无线 Android 设备吗？", isPresented: $confirmRevokeAll, titleVisibility: .visible) {
            Button("全部撤销", role: .destructive) { model.revokeAllTrustedDevices() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("撤销后再次无线连接需要重新核对证书指纹，并在手机端确认同意配对请求。")
        }
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
        VStack(alignment: .leading, spacing: 6) {
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

    private func pickCustomAdbBinary() {
        let panel = NSOpenPanel()
        panel.title = "选择 adb 可执行文件"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.showsHiddenFiles = true
        if panel.runModal() == .OK, let url = panel.url {
            model.preferences.customAdbPath = url.path
            model.refreshAdbPath()
        }
    }
}
