import AppKit
import OpalDeskCore
import SwiftUI

/// macOS Control Center–inspired Liquid Glass menu bar popover for instant device connection,
/// background companion status, handoff continuity, media playback remote, synced notifications
/// (with OTP copy & inline reply), standalone popouts, and display quick toggles.
public struct MenuBarView: View {
    private enum Layout {
        static let popoverWidth: CGFloat = 334
        static let maxNotificationsHeight: CGFloat = 196
        static let maxVisibleNotifications: Int = 6
        static let maxVisibleHistory: Int = 4
    }

    @Bindable public var model: AppModel
    @Environment(\.openWindow) private var openWindow

    @State private var replyingNotificationKey: String?
    @State private var replyDraftText: String = ""

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.md) {
            if let conn = model.activeConnection {
                connectedHeaderCard(conn)
                quickControlsGrid
            } else {
                disconnectedStatusAndDevicesCard
            }

            if !model.hostTopApp.isEmpty {
                OpalHandoffCard(
                    appLabel: model.hostTopApp.displayLabel,
                    packageName: model.hostTopApp.packageName,
                    isCompact: true
                ) {
                    model.handoffHostTopApp()
                }
            }

            if let media = model.mediaState, media.active {
                OpalMediaPlayerCard(media: media, isCompact: true) { action in
                    model.sendMediaAction(action)
                }
            }

            if !model.remoteNotifications.isEmpty {
                notificationsCard
            }

            if !model.popoutManager.controllers.isEmpty {
                activePopoutsCard
            } else if !model.popoutHistory.isEmpty {
                recentPopoutsCard
            }

            quickToolsCard
            footerActionsCard
        }
        .padding(OpalSpacing.md)
        .frame(width: Layout.popoverWidth)
        .background(OpalAmbientBackdrop(intensity: 0.55))
    }

    // MARK: - Connected Header & Control Center Grid

    private func connectedHeaderCard(_ conn: ActiveConnectionInfo) -> some View {
        HStack(spacing: OpalSpacing.md) {
            OpalIconBadge(
                systemName: "macbook.and.iphone",
                tint: model.isAuthenticated ? .green : .orange,
                size: 36,
                iconSize: 16,
                cornerRadius: 11
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: OpalSpacing.xs) {
                    Text(conn.deviceName)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    StatusBadgeView(
                        text: conn.transport.shortBadge,
                        color: conn.transport.badgeColor,
                        horizontalPadding: 6,
                        verticalPadding: 1.5
                    )
                }
                Text("\(Int(model.videoMetrics.currentFPS.rounded())) fps · \(Int(model.rttMs.rounded())) ms")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: OpalSpacing.xs)

            OpalGlassIconButton(
                systemName: "xmark.circle.fill",
                tint: .red,
                size: 26,
                iconSize: 14,
                helpText: "断开当前投屏连接"
            ) {
                model.forceDisconnectAndReturnHome()
            }
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var quickControlsGrid: some View {
        VStack(spacing: OpalSpacing.xs) {
            LazyVGrid(
                columns: [GridItem(.flexible(), spacing: OpalSpacing.xs), GridItem(.flexible(), spacing: OpalSpacing.xs)],
                spacing: OpalSpacing.xs
            ) {
                controlTileButton(
                    title: model.displayMode == .dex ? "电脑桌面" : "融合镜像",
                    systemImage: model.displayMode == .dex ? "macwindow" : "rectangle.on.rectangle",
                    isActive: true,
                    tint: .accentColor
                ) { model.toggleDisplayMode() }

                controlTileButton(
                    title: model.isBlackoutActive ? "已熄屏" : "熄灭主屏",
                    systemImage: model.isBlackoutActive ? "iphone.slash" : "iphone",
                    isActive: model.isBlackoutActive,
                    tint: .blue
                ) { model.toggleBlackout() }

                controlTileButton(
                    title: model.isAudioMuted ? "已静音" : "音频回传",
                    systemImage: model.isAudioMuted ? "speaker.slash" : "speaker.wave.2.fill",
                    isActive: !model.isAudioMuted,
                    tint: .green
                ) { model.toggleAudio() }

                controlTileButton(
                    title: model.isMicEnabled ? "麦克风开" : "麦克风关",
                    systemImage: model.isMicEnabled ? "mic.fill" : "mic.slash",
                    isActive: model.isMicEnabled,
                    tint: .green
                ) { model.toggleMicrophone() }

                controlTileButton(
                    title: model.isCameraEnabled ? "摄像头开" : "摄像头",
                    systemImage: model.isCameraEnabled ? "video.fill" : "video.slash",
                    isActive: model.isCameraEnabled,
                    tint: .green
                ) { model.toggleCamera() }

                controlTileButton(
                    title: model.isMainWindowRecording ? "停录 \(model.formattedRecordingDuration)" : "录制 MP4",
                    systemImage: model.isMainWindowRecording ? "stop.circle.fill" : "record.circle",
                    isActive: model.isMainWindowRecording,
                    tint: .red,
                    isDisabled: !model.isAuthenticated || (!model.isMainWindowRecording && model.videoMetrics.totalFramesDecoded == 0)
                ) { model.toggleMainWindowRecording() }
            }

            OpalMenuActionRow(
                title: "一键截图（保存桌面并复制）",
                systemImage: "camera",
                isDisabled: !model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0
            ) {
                model.captureMainWindowScreenshot()
            }
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func controlTileButton(
        title: String,
        systemImage: String,
        isActive: Bool,
        tint: Color,
        isDisabled: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: OpalSpacing.sm) {
                Image(systemName: systemImage)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(isActive ? tint : .secondary)
                    .frame(width: 18)
                Text(title)
                    .font(.system(size: 11.5, weight: .semibold))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .opalGlassSurface(.inset, cornerRadius: OpalRadius.control, tint: isActive ? tint : nil)
            .opalInteractiveHover(cornerRadius: OpalRadius.control, tint: isActive ? tint : nil)
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
        .opacity(isDisabled ? 0.45 : 1.0)
    }

    // MARK: - Disconnected Quick Connect + Companion Status

    private var disconnectedStatusAndDevicesCard: some View {
        let usbDevices = model.discovery.usbDevices
        let bonjourDevices = model.discovery.bonjourDevices

        return VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            if case let .connected(deviceName) = model.companionState {
                HStack(spacing: OpalSpacing.sm) {
                    OpalIconBadge(
                        systemName: "iphone.radiowaves.left.and.right",
                        tint: .green,
                        size: 32,
                        iconSize: 14,
                        cornerRadius: 10
                    )
                    VStack(alignment: .leading, spacing: 1) {
                        Text(deviceName)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text("后台伴侣已连接 · 通知/剪贴板/文件就绪")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 4)
                    OpalGlassIconButton(
                        systemName: "bolt.slash",
                        tint: .secondary,
                        size: 24,
                        iconSize: 11,
                        helpText: "断开后台伴侣连接"
                    ) {
                        model.disconnectCompanion()
                    }
                }
            } else {
                HStack(spacing: OpalSpacing.sm) {
                    OpalIconBadge(systemName: "display", tint: .secondary, size: 32, iconSize: 14, cornerRadius: 10)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("OpalDesk 就绪")
                            .font(.subheadline.weight(.semibold))
                        Text("选择下方设备或打开主窗口开始连接")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if !usbDevices.isEmpty {
                Divider()
                Text("ADB 设备（USB / 无线调试）")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)
                ForEach(usbDevices) { device in
                    OpalMenuActionRow(
                        title: device.displayName,
                        systemImage: device.isWirelessAdb ? "wifi" : "cable.connector",
                        tint: device.isReady ? .accentColor : .secondary,
                        isDisabled: !device.isReady
                    ) {
                        openMainWindow()
                        Task { await model.connectWiredUSB(device) }
                    }
                }
            }

            if !bonjourDevices.isEmpty {
                Divider()
                Text("无线局域网设备")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)
                ForEach(bonjourDevices) { device in
                    OpalMenuActionRow(
                        title: device.displayName,
                        systemImage: "wifi",
                        tint: .green
                    ) {
                        openMainWindow()
                        model.connectBonjourDevice(device)
                    }
                }
            }
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    // MARK: - Synced Phone Notifications Card

    private var notificationsCard: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.sm) {
            HStack {
                Label("手机通知 (\(model.remoteNotifications.count))", systemImage: "bell.badge.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Button("清空全部") {
                    replyingNotificationKey = nil
                    replyDraftText = ""
                    model.clearAllRemoteNotifications()
                }
                .buttonStyle(.borderless)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: OpalSpacing.xs) {
                    ForEach(model.remoteNotifications.prefix(Layout.maxVisibleNotifications)) { item in
                        notificationRow(item)
                    }
                }
            }
            .frame(maxHeight: Layout.maxNotificationsHeight)
        }
        .padding(OpalSpacing.md)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func notificationRow(_ item: RemoteNotificationItem) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .top, spacing: OpalSpacing.xs) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Text(item.displayAppLabel)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        if !item.title.isEmpty {
                            Text("· \(item.title)")
                                .font(.caption.weight(.semibold))
                                .lineLimit(1)
                        }
                    }
                    if !item.text.isEmpty {
                        Text(item.text)
                            .font(.caption2)
                            .foregroundStyle(.primary.opacity(0.86))
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { model.openNotificationApp(item) }
                .help("点击在 Mac 小窗打开「\(item.displayAppLabel)」")

                if item.clearable {
                    OpalGlassIconButton(
                        systemName: "xmark",
                        tint: .secondary,
                        size: 20,
                        iconSize: 9,
                        helpText: "忽略此通知"
                    ) {
                        if replyingNotificationKey == item.key {
                            replyingNotificationKey = nil
                            replyDraftText = ""
                        }
                        model.dismissRemoteNotification(item)
                    }
                }
            }

            if !item.otpCode.isEmpty || item.canReply {
                HStack(spacing: OpalSpacing.xs) {
                    if !item.otpCode.isEmpty {
                        Button {
                            model.copyNotificationOtp(item)
                        } label: {
                            Label("复制 \(item.otpCode)", systemImage: "doc.on.doc")
                                .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.green)
                        .controlSize(.mini)
                    }

                    if item.canReply {
                        Button {
                            if replyingNotificationKey == item.key {
                                replyingNotificationKey = nil
                                replyDraftText = ""
                            } else {
                                replyingNotificationKey = item.key
                                replyDraftText = ""
                            }
                        } label: {
                            Label(item.replyLabel.isEmpty ? "回复" : item.replyLabel, systemImage: "arrowshape.turn.up.left")
                                .font(.caption2)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.mini)
                    }

                    Spacer(minLength: 0)
                }
            }

            if replyingNotificationKey == item.key {
                HStack(spacing: OpalSpacing.xs) {
                    TextField("输入回复内容…", text: $replyDraftText)
                        .textFieldStyle(.roundedBorder)
                        .font(.caption)
                        .onSubmit { submitInlineReply(for: item) }

                    Button("发送") { submitInlineReply(for: item) }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.mini)
                        .disabled(replyDraftText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding(.top, 2)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .opalGlassSurface(.inset, cornerRadius: OpalRadius.tile)
    }

    private func submitInlineReply(for item: RemoteNotificationItem) {
        let draft = replyDraftText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !draft.isEmpty else { return }
        model.replyToRemoteNotification(item, text: draft)
        replyingNotificationKey = nil
        replyDraftText = ""
    }

    // MARK: - Active / Recent Popouts, Quick Tools & Footer

    private var activePopoutsCard: some View {
        VStack(alignment: .leading, spacing: OpalSpacing.xs) {
            Text("已开悬浮小窗 (\(model.activePopoutCount)/\(PopoutWindowManager.maxConcurrentWindows))")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 4)

            ForEach(model.popoutManager.controllers) { ctrl in
                HStack(spacing: OpalSpacing.xs) {
                    OpalMenuActionRow(
                        title: ctrl.appLabel,
                        systemImage: ctrl.isPinnedOnTop ? "pin.fill" : "macwindow",
                        tint: ctrl.isPinnedOnTop ? .orange : .accentColor
                    ) {
                        model.popoutManager.focusWindow(id: ctrl.id)
                    }

                    OpalGlassIconButton(
                        systemName: ctrl.isPinnedOnTop ? "pin.slash" : "pin",
                        tint: ctrl.isPinnedOnTop ? .orange : .secondary,
                        size: 24,
                        iconSize: 10.5,
                        helpText: ctrl.isPinnedOnTop ? "取消置顶" : "窗口置顶"
                    ) { ctrl.togglePinnedOnTop() }

                    OpalGlassIconButton(
                        systemName: "xmark",
                        tint: .secondary,
                        size: 24,
                        iconSize: 10,
                        helpText: "关闭小窗"
                    ) { model.popoutManager.closeWindow(id: ctrl.id, sendDisconnect: true) }
                }
            }
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var recentPopoutsCard: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("历史 APP 小窗")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 8)
                .padding(.bottom, 2)

            ForEach(model.popoutHistory.prefix(Layout.maxVisibleHistory)) { entry in
                OpalMenuActionRow(
                    title: entry.displayLabel,
                    systemImage: entry.pinnedOnTop ? "pin.fill" : "clock",
                    subtitle: entry.deviceName,
                    tint: entry.pinnedOnTop ? .orange : .secondary
                ) {
                    model.openPopoutFromHistory(entry)
                }
            }
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var quickToolsCard: some View {
        VStack(alignment: .leading, spacing: 2) {
            OpalMenuActionRow(title: "手机文件与存储", systemImage: "folder", tint: .accentColor) {
                openWindow(id: WindowID.fileBrowser)
                NSApp.activate(ignoringOtherApps: true)
            }
            OpalMenuActionRow(title: "安装 Android 安装包 (.apk/.xapk)…", systemImage: "shippingbox", tint: .green) {
                model.presentApkFileChooser(targetWindow: .main)
            }
            OpalMenuActionRow(title: "Spotlight 快捷方式管理", systemImage: "sparkles.rectangle.stack", tint: .purple) {
                model.openShortcutManager()
            }
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private var footerActionsCard: some View {
        VStack(alignment: .leading, spacing: 2) {
            OpalMenuActionRow(title: "显示主窗口", systemImage: "macwindow") { openMainWindow() }

            SettingsLink {
                HStack(spacing: OpalSpacing.sm) {
                    Image(systemName: "gearshape")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.secondary)
                        .frame(width: 20)
                    Text("偏好设置…")
                        .font(.system(size: 12.5, weight: .medium))
                    Spacer()
                }
                .padding(.horizontal, OpalSpacing.sm)
                .padding(.vertical, 6)
                .opalInteractiveHover(cornerRadius: OpalRadius.control)
                .contentShape(RoundedRectangle(cornerRadius: OpalRadius.control, style: .continuous))
            }
            .buttonStyle(.plain)

            Divider().padding(.vertical, 2)

            OpalMenuActionRow(
                title: "断开当前连接",
                systemImage: "wifi.slash",
                isDisabled: model.activeConnection == nil && !model.isConnectingFlow
            ) {
                model.forceDisconnectAndReturnHome()
            }

            OpalMenuActionRow(
                title: "强制退出 OpalDesk",
                systemImage: "power",
                isDestructive: true
            ) {
                model.forceTerminateApp()
            }
            .keyboardShortcut("q")
        }
        .padding(OpalSpacing.sm)
        .opalGlassSurface(.card, cornerRadius: OpalRadius.card)
    }

    private func openMainWindow() {
        openWindow(id: WindowID.main)
        NSApp.activate(ignoringOtherApps: true)
    }
}
