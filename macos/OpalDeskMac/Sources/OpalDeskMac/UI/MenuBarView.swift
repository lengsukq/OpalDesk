import AppKit
import OpalDeskCore
import SwiftUI

/// Menu bar resident dropdown for instant device connection, background companion status,
/// phone foreground app handoff, media playback mini-bar, synced phone notifications (with OTP copy & inline reply),
/// quick tools (File Browser / APK Installer / Spotlight Shortcuts), standalone popout windows, and display controls.
public struct MenuBarView: View {
    @Bindable public var model: AppModel
    @Environment(\.openWindow) private var openWindow

    @State private var replyingNotificationKey: String?
    @State private var replyDraftText: String = ""

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let conn = model.activeConnection {
                connectedSection(conn)
            } else {
                disconnectedDevicesSection
            }

            if !model.hostTopApp.isEmpty {
                Divider()
                handoffSection
            }

            if let media = model.mediaState, media.active {
                Divider()
                mediaMiniBarSection(media)
            }

            if !model.remoteNotifications.isEmpty {
                Divider()
                notificationsSection
            }

            if !model.popoutManager.controllers.isEmpty {
                Divider()
                activePopoutsSection
            } else if !model.popoutHistory.isEmpty {
                Divider()
                Text("历史 APP 小窗").font(.caption).foregroundStyle(.secondary)
                ForEach(model.popoutHistory.prefix(4)) { entry in
                    Button("\(entry.displayLabel) · \(entry.deviceName)", systemImage: entry.pinnedOnTop ? "pin.fill" : "clock") {
                        model.openPopoutFromHistory(entry)
                    }
                }
            }

            Divider()
            quickToolsSection

            Divider()
            Button("显示主窗口", systemImage: "macwindow") { openMainWindow() }
            SettingsLink { Label("偏好设置...", systemImage: "gearshape") }

            Divider()
            Button("断开当前连接") { model.forceDisconnectAndReturnHome() }
                .disabled(model.activeConnection == nil && !model.isConnectingFlow)
            Button("强制退出 OpalDeskMac", role: .destructive) { model.forceTerminateApp() }
                .keyboardShortcut("q")
        }
        .padding(10)
        .frame(width: 312)
    }

    // MARK: - Connected Controls

    @ViewBuilder
    private func connectedSection(_ conn: ActiveConnectionInfo) -> some View {
        HStack(spacing: 8) {
            Circle()
                .fill(model.isAuthenticated ? Color.green : Color.orange)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 1) {
                Text(conn.deviceName).font(.headline)
                Text("\(conn.transport.shortBadge) · \(Int(model.videoMetrics.currentFPS.rounded())) fps · \(Int(model.rttMs.rounded())) ms")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.bottom, 2)

        Divider()

        Button(
            model.displayMode == .dex ? "切换到屏幕镜像" : "切换到扩展桌面 (DeX)",
            systemImage: model.displayMode == .dex ? "rectangle.on.rectangle" : "macwindow"
        ) { model.toggleDisplayMode() }

        Button(
            model.isBlackoutActive ? "点亮手机主屏" : "一键熄灭手机主屏",
            systemImage: model.isBlackoutActive ? "iphone" : "iphone.slash"
        ) { model.toggleBlackout() }

        Button(
            model.isAudioMuted ? "开启音频回传" : "静音音频回传",
            systemImage: model.isAudioMuted ? "speaker.wave.2" : "speaker.slash"
        ) { model.toggleAudio() }

        Button(
            model.isMicEnabled ? "关闭麦克风上行" : "开启麦克风上行",
            systemImage: model.isMicEnabled ? "mic.fill" : "mic.slash"
        ) { model.toggleMicrophone() }

        Button(
            model.isCameraEnabled ? "关闭摄像头映射 (Root)" : "开启摄像头映射 (Root)",
            systemImage: model.isCameraEnabled ? "video.fill" : "video.slash"
        ) { model.toggleCamera() }

        Button("一键截图（保存桌面并复制）", systemImage: "camera") {
            model.captureMainWindowScreenshot()
        }
        .disabled(!model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0)

        Button(
            model.isMainWindowRecording ? "停止录屏 (\(model.formattedRecordingDuration))" : "开始录制 MP4 视频",
            systemImage: model.isMainWindowRecording ? "stop.circle.fill" : "record.circle"
        ) {
            model.toggleMainWindowRecording()
        }
        .disabled(!model.isAuthenticated || (!model.isMainWindowRecording && model.videoMetrics.totalFramesDecoded == 0))

        Divider()
        Button("断开连接", systemImage: "xmark.circle", role: .destructive) { model.forceDisconnectAndReturnHome() }
    }

    // MARK: - Disconnected Quick Connect + Companion Status

    @ViewBuilder
    private var disconnectedDevicesSection: some View {
        let usbDevices = model.discovery.usbDevices
        let bonjourDevices = model.discovery.bonjourDevices

        if case let .connected(deviceName) = model.companionState {
            HStack(spacing: 8) {
                Circle()
                    .fill(Color.green)
                    .frame(width: 8, height: 8)
                VStack(alignment: .leading, spacing: 1) {
                    Text(deviceName).font(.headline)
                    Text("后台伴侣已连接 · 通知/剪贴板/文件就绪")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    model.disconnectCompanion()
                } label: {
                    Image(systemName: "bolt.slash")
                }
                .buttonStyle(.borderless)
                .help("断开后台伴侣连接")
            }
        } else {
            Text("OpalDesk 未连接").font(.headline)
        }

        if !usbDevices.isEmpty {
            Divider()
            Text("ADB 设备（USB / 无线调试）").font(.caption).foregroundStyle(.secondary)
            ForEach(usbDevices) { device in
                Button(device.displayName, systemImage: device.isWirelessAdb ? "wifi" : "cable.connector") {
                    openMainWindow()
                    Task { await model.connectWiredUSB(device) }
                }
                .disabled(!device.isReady)
            }
        }

        if !bonjourDevices.isEmpty {
            Divider()
            Text("无线局域网设备").font(.caption).foregroundStyle(.secondary)
            ForEach(bonjourDevices) { device in
                Button(device.displayName, systemImage: "wifi") {
                    openMainWindow()
                    model.connectBonjourDevice(device)
                }
            }
        }
    }

    // MARK: - Phone Foreground App Handoff

    private var handoffSection: some View {
        HStack(spacing: 8) {
            Image(systemName: "iphone.and.arrow.forward")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Color.accentColor)
                .frame(width: 22)

            VStack(alignment: .leading, spacing: 1) {
                Text("正在手机上使用：\(model.hostTopApp.displayLabel)")
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                Text("在 Mac 悬浮小窗继续")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 4)

            Button("继续") {
                model.handoffHostTopApp()
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.mini)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    // MARK: - Media Remote Mini-Bar

    private func mediaMiniBarSection(_ media: MediaStatePayload) -> some View {
        HStack(spacing: 9) {
            mediaArtworkThumbnail(media)

            VStack(alignment: .leading, spacing: 1) {
                Text(media.title.isEmpty ? (media.appLabel.isEmpty ? "正在播放媒体" : media.appLabel) : media.title)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                Text(mediaSubtitle(media))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            HStack(spacing: 4) {
                Button {
                    model.sendMediaAction(.previous())
                } label: {
                    Image(systemName: "backward.fill")
                        .font(.system(size: 11))
                        .frame(width: 22, height: 22)
                }
                .buttonStyle(.borderless)
                .help("上一首")

                Button {
                    model.sendMediaAction(.toggle())
                } label: {
                    Image(systemName: media.playing ? "pause.fill" : "play.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .frame(width: 24, height: 22)
                }
                .buttonStyle(.bordered)
                .controlSize(.mini)
                .help(media.playing ? "暂停" : "播放")

                Button {
                    model.sendMediaAction(.next())
                } label: {
                    Image(systemName: "forward.fill")
                        .font(.system(size: 11))
                        .frame(width: 22, height: 22)
                }
                .buttonStyle(.borderless)
                .help("下一首")
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    @ViewBuilder
    private func mediaArtworkThumbnail(_ media: MediaStatePayload) -> some View {
        if let data = media.artworkData, let img = NSImage(data: data) {
            Image(nsImage: img)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 34, height: 34)
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color.accentColor.opacity(0.14))
                Image(systemName: "music.note")
                    .font(.system(size: 14))
                    .foregroundStyle(Color.accentColor)
            }
            .frame(width: 34, height: 34)
        }
    }

    private func mediaSubtitle(_ media: MediaStatePayload) -> String {
        switch (media.artist.isEmpty, media.appLabel.isEmpty) {
        case (false, false): return "\(media.artist) · \(media.appLabel)"
        case (false, true): return media.artist
        case (true, false): return media.appLabel
        case (true, true): return media.playing ? "播放中" : "已暂停"
        }
    }

    // MARK: - Synced Phone Notifications Section

    private var notificationsSection: some View {
        VStack(alignment: .leading, spacing: 6) {
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
                .font(.caption2)
                .foregroundStyle(.secondary)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(model.remoteNotifications.prefix(6)) { item in
                        notificationRow(item)
                    }
                }
            }
            .frame(maxHeight: 190)
        }
    }

    private func notificationRow(_ item: RemoteNotificationItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 6) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Text(item.displayAppLabel)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        if !item.title.isEmpty {
                            Text("· \(item.title)")
                                .font(.caption.weight(.medium))
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
                .onTapGesture {
                    model.openNotificationApp(item)
                }
                .help("点击在 Mac 小窗打开「\(item.displayAppLabel)」")

                if item.clearable {
                    Button {
                        if replyingNotificationKey == item.key {
                            replyingNotificationKey = nil
                            replyDraftText = ""
                        }
                        model.dismissRemoteNotification(item)
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 9.5, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .help("忽略此通知")
                }
            }

            if !item.otpCode.isEmpty || item.canReply {
                HStack(spacing: 6) {
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
                HStack(spacing: 6) {
                    TextField("输入回复内容…", text: $replyDraftText)
                        .textFieldStyle(.roundedBorder)
                        .font(.caption)
                        .onSubmit { submitInlineReply(for: item) }

                    Button("发送") {
                        submitInlineReply(for: item)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.mini)
                    .disabled(replyDraftText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding(.top, 2)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private func submitInlineReply(for item: RemoteNotificationItem) {
        let draft = replyDraftText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !draft.isEmpty else { return }
        model.replyToRemoteNotification(item, text: draft)
        replyingNotificationKey = nil
        replyDraftText = ""
    }

    // MARK: - Active Popouts & Quick Tools

    private var activePopoutsSection: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("已开悬浮小窗 (\(model.activePopoutCount)/\(PopoutWindowManager.maxConcurrentWindows))")
                .font(.caption)
                .foregroundStyle(.secondary)
            ForEach(model.popoutManager.controllers) { ctrl in
                HStack(spacing: 6) {
                    Button(ctrl.appLabel, systemImage: ctrl.isPinnedOnTop ? "pin.fill" : "macwindow") {
                        model.popoutManager.focusWindow(id: ctrl.id)
                    }
                    Spacer()
                    Button {
                        ctrl.togglePinnedOnTop()
                    } label: {
                        Image(systemName: ctrl.isPinnedOnTop ? "pin.slash" : "pin")
                    }
                    .buttonStyle(.borderless)
                    .help(ctrl.isPinnedOnTop ? "取消置顶" : "窗口置顶")
                    Button {
                        model.popoutManager.closeWindow(id: ctrl.id, sendDisconnect: true)
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .buttonStyle(.borderless)
                    .help("关闭小窗")
                }
            }
        }
    }

    private var quickToolsSection: some View {
        VStack(alignment: .leading, spacing: 5) {
            Button("手机文件与存储", systemImage: "folder") {
                openWindow(id: WindowID.fileBrowser)
                NSApp.activate(ignoringOtherApps: true)
            }

            Button("安装 Android 安装包 (.apk/.xapk)...", systemImage: "shippingbox") {
                model.presentApkFileChooser(targetWindow: .main)
            }

            Button("Spotlight 快捷方式管理", systemImage: "sparkles.rectangle.stack") {
                model.openShortcutManager()
            }
        }
    }

    private func openMainWindow() {
        openWindow(id: WindowID.main)
        NSApp.activate(ignoringOtherApps: true)
    }
}
