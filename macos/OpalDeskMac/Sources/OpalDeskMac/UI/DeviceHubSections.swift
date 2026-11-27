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
                            text: model.companionState.isConnected ? "后台伴侣在线" : "连接中",
                            color: model.companionState.isConnected ? .green : .orange
                        )
                        if !model.remoteNotifications.isEmpty {
                            StatusBadgeView(text: "\(model.remoteNotifications.count) 条手机通知", color: .blue)
                        }
                    }
                    Text("免开大屏即可收发手机通知、同步剪贴板、控制媒体、互传文件与无线安装 APK")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: OpalSpacing.sm)

                HStack(spacing: OpalSpacing.xs) {
                    if model.isAnyChannelConnected {
                        Button {
                            isQuickDialPopoverShown.toggle()
                        } label: {
                            Label("快捷拨号", systemImage: "phone.badge.waveform.fill")
                                .font(.caption.weight(.medium))
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
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
                        Label("文件与存储", systemImage: "folder")
                            .font(.caption.weight(.medium))
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .disabled(!model.isAnyChannelConnected)

                    Button {
                        model.presentApkFileChooser(targetWindow: .main)
                    } label: {
                        Label("安装 APK", systemImage: "shippingbox")
                            .font(.caption.weight(.medium))
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .disabled(!model.isAnyChannelConnected)

                    if model.companionState.isActiveOrAttempting {
                        OpalGlassIconButton(
                            systemName: "bolt.slash",
                            tint: .secondary,
                            size: OpalControlSize.iconButtonCompact,
                            iconSize: 11.5,
                            helpText: "断开后台伴侣连接"
                        ) {
                            model.disconnectCompanion()
                        }
                    }
                }
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
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.panel)
    }

    private func otpQuickCopyStrip(_ item: RemoteNotificationItem) -> some View {
        HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(systemName: "key.fill", tint: .green, size: 32, iconSize: 13, cornerRadius: 9)
            VStack(alignment: .leading, spacing: 1) {
                Text("「\(item.displayAppLabel)」验证码")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(item.otpCode)
                    .font(.headline.monospaced())
            }
            Spacer()
            Button("一键复制") {
                model.copyNotificationOtp(item)
            }
            .buttonStyle(.borderedProminent)
            .tint(.green)
            .controlSize(.small)
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 9)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.card, tint: .green)
    }

    private var companionTitleText: String {
        switch model.companionState {
        case .connected(let name): return name
        case .connecting(let name): return "正在静默连接 \(name)…"
        case .authenticating(let name): return "正在验证 \(name)…"
        case .reconnecting(let name, let attempt): return "正在重连 \(name)（第 \(attempt) 次）…"
        default: return model.activeChannelDeviceName
        }
    }

    // MARK: - 2. Recent / Active Standalone Floating Apps Section

    var standaloneHistorySection: some View {
        let activeControllers = model.popoutManager.controllers
        let history = model.popoutHistory
        let maxCount = PopoutWindowManager.maxConcurrentWindows

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(
                title: "历史 APP 小窗",
                systemImage: "macwindow.on.rectangle",
                badgeText: "已开 \(activeControllers.count)/\(maxCount)",
                badgeColor: activeControllers.count >= maxCount ? .orange : .accentColor
            ) {
                if !activeControllers.isEmpty {
                    Button("关闭全部小窗") {
                        model.popoutManager.closeAll(sendDisconnect: true)
                    }
                    .buttonStyle(.borderless)
                    .font(.caption.weight(.medium))
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

            VStack(alignment: .leading, spacing: OpalSpacing.md) {
                if history.isEmpty && activeControllers.isEmpty {
                    OpalEmptyStateRow(
                        icon: "rectangle.stack.badge.plus",
                        title: "暂无历史 APP 小窗记录",
                        subtitle: "点击下方任意设备的「应用小窗」，即可免开全屏桌面直接弹出独立悬浮窗（最多同时开启 \(maxCount) 个，支持窗口置顶）。"
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
        let activeCtrl = model.popoutManager.existingController(for: entry.appPackage)
        let isRunning = activeCtrl != nil
        let isPinned = activeCtrl?.isPinnedOnTop ?? entry.pinnedOnTop

        return HStack(spacing: OpalSpacing.md) {
            OpalAppMonogram(
                label: entry.displayLabel,
                packageKey: entry.appPackage,
                size: 38,
                cornerRadius: 11,
                showActiveIndicator: isRunning
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(entry.displayLabel)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    if isPinned {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 9.5))
                            .foregroundStyle(.orange)
                    }
                }
                Text("\(entry.deviceName) · \(isRunning ? "运行中（点击聚焦/重载）" : "打开 \(entry.openCount) 次")")
                    .font(.caption2)
                    .foregroundStyle(isRunning ? .green : .secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.xxs)

            if let ctrl = activeCtrl {
                HStack(spacing: 2) {
                    OpalGlassIconButton(
                        systemName: ctrl.isPinnedOnTop ? "pin.fill" : "pin",
                        tint: ctrl.isPinnedOnTop ? .orange : .secondary,
                        isActive: ctrl.isPinnedOnTop,
                        size: 24,
                        iconSize: 10.5,
                        helpText: ctrl.isPinnedOnTop ? "取消置顶" : "置顶该悬浮窗"
                    ) {
                        ctrl.togglePinnedOnTop()
                    }

                    OpalGlassIconButton(
                        systemName: "xmark.circle.fill",
                        tint: .secondary,
                        size: 24,
                        iconSize: 11.5,
                        helpText: "关闭此小窗"
                    ) {
                        model.popoutManager.closeWindow(id: ctrl.id, sendDisconnect: true)
                    }
                }
            } else {
                OpalGlassIconButton(
                    systemName: "xmark",
                    tint: .secondary,
                    size: 24,
                    iconSize: 10,
                    helpText: "移除此历史记录"
                ) {
                    model.removePopoutHistoryEntry(entry)
                }
            }
        }
        .padding(.horizontal, OpalSpacing.md)
        .padding(.vertical, 10)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile, tint: isRunning ? .green : nil)
        .opalInteractiveHover(cornerRadius: OpalRadius.tile, tint: isRunning ? .green : nil)
        .contentShape(RoundedRectangle(cornerRadius: OpalRadius.tile, style: .continuous))
        .onTapGesture {
            model.openPopoutFromHistory(entry)
        }
    }

    // MARK: - 3. USB & Wireless Debugging Section

    var adbDevicesSection: some View {
        let discovery = model.discovery

        return VStack(alignment: .leading, spacing: OpalSpacing.md) {
            OpalSectionHeader(title: "已连接的手机（USB / 无线调试）", systemImage: "cable.connector") {
                if discovery.isScanningUSB { ProgressView().controlSize(.small) }
                Button { Task { await model.refreshUSBDevices() } } label: {
                    Label("刷新", systemImage: "arrow.clockwise").font(.caption.weight(.medium))
                }
                .buttonStyle(.borderless)
                .disabled(discovery.isScanningUSB)
            }

            VStack(spacing: 0) {
                if discovery.adbBinaryPath == nil {
                    OpalEmptyStateRow(
                        icon: "exclamationmark.triangle",
                        tint: .orange,
                        title: "未找到 adb 工具",
                        subtitle: "请选择本机或外接硬盘中的 adb 可执行文件，或前往偏好设置配置路径。"
                    ) {
                        HStack(spacing: OpalSpacing.sm) {
                            Button("选择 adb 文件...") { pickAdbBinary() }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                            SettingsLink { Text("偏好设置") }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                        }
                    }
                } else if discovery.usbDevices.isEmpty {
                    OpalEmptyStateRow(
                        icon: "cable.connector.horizontal",
                        title: "等待 USB 或无线调试设备接入",
                        subtitle: "使用数据线连接手机并开启「USB 调试」，即可一键启动大屏桌面或单应用悬浮窗。"
                    )
                } else {
                    ForEach(Array(discovery.usbDevices.enumerated()), id: \.element.id) { index, device in
                        DeviceConnectionRow(
                            icon: device.isWirelessAdb ? "iphone.radiowaves.left.and.right" : "iphone.gen3",
                            iconTint: device.isReady ? .accentColor : .secondary,
                            title: device.displayName,
                            badgeText: device.isReady
                                ? (device.isWirelessAdb ? "已就绪 · 无线调试" : "已就绪 · USB 有线")
                                : device.state.displayLabel,
                            badgeColor: device.isReady ? .green : .orange,
                            subtitle: device.isWirelessAdb
                                ? "无线调试地址：\(device.serial) · 连接后自动切换高速无线直连"
                                : "设备标识：\(device.serial)",
                            primaryTitle: "进入桌面",
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
            OpalSectionHeader(title: "USB 网络共享设备（免 ADB）", systemImage: "network", badgeText: "Beta", badgeColor: .orange) {
                if discovery.isScanningUsbTether { ProgressView().controlSize(.small) }
                Button { Task { await model.refreshUsbTetherDevices() } } label: {
                    Label("刷新", systemImage: "arrow.clockwise").font(.caption.weight(.medium))
                }
                .buttonStyle(.borderless)
                .disabled(discovery.isScanningUsbTether)
            }

            VStack(spacing: 0) {
                if tetherDevices.isEmpty {
                    OpalEmptyStateRow(
                        icon: "network.badge.shield.half.filled",
                        tint: .orange,
                        title: "未识别到 USB 网络共享手机",
                        subtitle: "Beta 测试功能：请用数据线连接手机并开启「USB 网络共享」及手机端共享服务；macOS 原生支持较新安卓 CDC-NCM。"
                    )
                } else {
                    ForEach(Array(tetherDevices.enumerated()), id: \.element.id) { index, device in
                        let isTrusted = discovery.isTrusted(device)
                        let detailParts = [
                            "\(device.host):\(device.port)",
                            device.interfaceLabel,
                            device.shortFingerprint.isEmpty ? nil : "安全指纹 \(device.shortFingerprint)",
                        ].compactMap { $0 }.joined(separator: " · ")

                        DeviceConnectionRow(
                            icon: "cable.connector.horizontal",
                            iconTint: .accentColor,
                            title: device.displayName,
                            badgeText: isTrusted ? "已信任" : "需配对",
                            badgeColor: isTrusted ? .green : .blue,
                            subtitle: detailParts,
                            primaryTitle: isTrusted ? "进入桌面" : "请求配对",
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
            OpalSectionHeader(title: "附近无线设备", systemImage: "wifi") {
                HStack(spacing: 6) {
                    Circle().fill(Color.green).frame(width: 7, height: 7)
                    Text("自动搜索中")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            VStack(spacing: 0) {
                if bonjourDevices.isEmpty {
                    OpalEmptyStateRow(
                        icon: "dot.radiowaves.left.and.right",
                        title: "正在局域网中搜索已开启服务的手机...",
                        subtitle: "请确保手机与 Mac 处于同一 Wi-Fi，并在手机端开启无线大屏服务。"
                    )
                } else {
                    ForEach(Array(bonjourDevices.enumerated()), id: \.element.id) { index, device in
                        let isTrusted = model.discovery.isTrusted(device)
                        let detailParts = [
                            device.model.isEmpty ? nil : device.model,
                            device.fingerprintPrefix.isEmpty ? nil : "安全指纹 \(device.shortFingerprint)",
                        ].compactMap { $0 }.joined(separator: " · ")

                        DeviceConnectionRow(
                            icon: "iphone.radiowaves.left.and.right",
                            iconTint: .accentColor,
                            title: device.displayName,
                            badgeText: isTrusted ? "已信任" : "需配对",
                            badgeColor: isTrusted ? .green : .blue,
                            subtitle: detailParts.isEmpty ? "同局域网 Bonjour 自动发现设备" : detailParts,
                            primaryTitle: isTrusted ? "进入桌面" : "请求配对",
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
                title: "蓝牙近场设备",
                systemImage: "dot.radiowaves.left.and.right",
                badgeText: "BLE 近场",
                badgeColor: .blue
            ) {
                Text("未在同一 Wi-Fi 时可一键拉起热点")
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
                        bleNearbyDeviceRow(device)
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

    private func bleNearbyDeviceRow(_ device: DiscoveredBleDevice) -> some View {
        let isTriggering = model.triggeringBleDeviceId == device.id
        let statusText = device.isHotspotActive
            ? "热点已开启"
            : (device.isLanShareActive ? "无线服务待连接" : "蓝牙附近")
        let statusColor: Color = device.isHotspotActive ? .green : .blue

        return HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(
                systemName: "iphone.radiowaves.left.and.right",
                tint: .blue,
                size: 38,
                iconSize: 17,
                cornerRadius: 11
            )

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: OpalSpacing.sm) {
                    Text(device.trustedDevice.deviceName)
                        .font(.body.weight(.semibold))
                        .lineLimit(1)

                    StatusBadgeView(text: statusText, color: statusColor)

                    if let battery = device.batteryPercent {
                        StatusBadgeView(
                            text: "电量 \(battery)%",
                            color: battery <= 20 ? .orange : .green
                        )
                    }
                }

                Text(bleDeviceSubtitle(for: device))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: OpalSpacing.sm)

            Button {
                model.triggerBleHotspot(for: device)
            } label: {
                if isTriggering {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small)
                        Text("唤醒热点中…")
                    }
                } else {
                    Label(
                        device.isHotspotActive ? "获取热点信息" : "一键开启手机热点",
                        systemImage: "personalhotspot"
                    )
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.triggeringBleDeviceId != nil)
        }
        .padding(.horizontal, OpalSpacing.lg)
        .padding(.vertical, 13)
    }

    private func bleDeviceSubtitle(for device: DiscoveredBleDevice) -> String {
        var parts: [String] = []
        if !device.trustedDevice.model.isEmpty {
            parts.append(device.trustedDevice.model)
        }
        parts.append("信号 \(device.rssi) dBm")
        if !device.supportsHotspotTrigger && !device.isHotspotActive {
            parts.append("尝试蓝牙唤起热点（若 ROM 限制需手机确认）")
        } else {
            parts.append("已信任设备 · 安全挑战鉴权")
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
                TextField("手机局域网 IP（如 192.168.31.102）", text: $manualHost)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(triggerManualConnect)

                TextField("端口", text: $manualPortText)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 84)
                    .onSubmit(triggerManualConnect)

                Button(action: triggerManualConnect) {
                    if model.isProbingManualHost {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("连接")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(
                    manualHost.trimmingCharacters(in: .whitespaces).isEmpty
                        || model.isProbingManualHost
                        || model.isConnectingFlow
                )
            }
            .padding(.top, OpalSpacing.md)
        } label: {
            Label("手动输入 IP 地址连接", systemImage: "network")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .padding(OpalSpacing.lg)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.panel)
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

// MARK: - Unified Device Row Component (DRY across ADB, USB Tether & Bonjour)

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

    @State private var isHovered = false

    var body: some View {
        HStack(spacing: OpalSpacing.md) {
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
                Button("应用小窗", action: onPopout)
                    .buttonStyle(.bordered)
                    .disabled(isDisabled)

                Button(primaryTitle, action: onPrimary)
                    .buttonStyle(.borderedProminent)
                    .disabled(isDisabled)
            }
        }
        .padding(.horizontal, OpalSpacing.lg)
        .padding(.vertical, 13)
        .background(isHovered ? Color.primary.opacity(0.035) : .clear)
        .onHover { isHovered = $0 }
    }
}
