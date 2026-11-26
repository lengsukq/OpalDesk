import AppKit
import OpalDeskCore
import SwiftUI

/// Menu bar resident dropdown for instant device connection, quick launcher, standalone popout windows,
/// blackout/audio control, and main window focus.
public struct MenuBarView: View {
    @Bindable public var model: AppModel
    @Environment(\.openWindow) private var openWindow

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

            if !model.popoutManager.controllers.isEmpty {
                Divider()
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
            Button("显示主窗口", systemImage: "macwindow") { openMainWindow() }
            SettingsLink { Label("偏好设置...", systemImage: "gearshape") }

            Divider()
            Button("断开当前连接") { model.forceDisconnectAndReturnHome() }
                .disabled(model.activeConnection == nil && !model.isConnectingFlow)
            Button("强制退出 OpalDeskMac", role: .destructive) { model.forceTerminateApp() }
                .keyboardShortcut("q")
        }
        .padding(10)
        .frame(width: 264)
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

        Divider()
        Button("断开连接", systemImage: "xmark.circle", role: .destructive) { model.forceDisconnectAndReturnHome() }
    }

    // MARK: - Disconnected Quick Connect

    @ViewBuilder
    private var disconnectedDevicesSection: some View {
        let usbDevices = model.discovery.usbDevices
        let bonjourDevices = model.discovery.bonjourDevices

        Text("OpalDesk 未连接").font(.headline)

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

    private func openMainWindow() {
        openWindow(id: WindowID.main)
        NSApp.activate(ignoringOtherApps: true)
    }
}
