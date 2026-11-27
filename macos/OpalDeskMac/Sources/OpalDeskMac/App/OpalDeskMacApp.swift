import AppKit
import OpalDeskCore
import SwiftUI

enum WindowID {
    static let main = "main"
    static let fileBrowser = "file-browser"
}

@MainActor
final class OpalDeskAppDelegate: NSObject, NSApplicationDelegate {
    var onTerminate: (@MainActor () -> Void)?

    var onOpenURLs: (@MainActor ([URL]) -> Void)? {
        didSet {
            flushPendingURLsIfReady()
        }
    }

    var servicesProvider: Any? {
        didSet {
            registerServicesProviderIfAvailable()
        }
    }

    private var pendingOpenURLs: [URL] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        registerServicesProviderIfAvailable()
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        guard !urls.isEmpty else { return }
        if let onOpenURLs {
            onOpenURLs(urls)
        } else {
            pendingOpenURLs.append(contentsOf: urls)
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        onTerminate?()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        // Stay resident in the macOS Menu Bar when the main window is closed.
        false
    }

    private func flushPendingURLsIfReady() {
        guard let onOpenURLs, !pendingOpenURLs.isEmpty else { return }
        let queued = pendingOpenURLs
        pendingOpenURLs.removeAll()
        onOpenURLs(queued)
    }

    private func registerServicesProviderIfAvailable() {
        guard let servicesProvider, let app = NSApp else { return }
        app.servicesProvider = servicesProvider
        NSUpdateDynamicServices()
    }
}

@main
struct OpalDeskMacApp: App {
    @NSApplicationDelegateAdaptor(OpalDeskAppDelegate.self) private var appDelegate
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup("OpalDesk", id: WindowID.main) {
            MainWindowRootView(model: model, appDelegate: appDelegate)
        }
        .defaultSize(width: 1180, height: 740)
        .commands {
            OpalDeskCommands(model: model)
        }

        Window("手机文件与存储", id: WindowID.fileBrowser) {
            FileBrowserWindowHostView(model: model)
        }
        .defaultSize(width: 980, height: 640)

        MenuBarExtra {
            MenuBarHostView(model: model, appDelegate: appDelegate)
        } label: {
            Image(systemName: menuBarIconName)
                .symbolRenderingMode(.hierarchical)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView(model: model)
        }
    }

    private var menuBarIconName: String {
        if model.isAuthenticated {
            return "macbook.and.iphone"
        }
        if model.companionState.isConnected {
            return "iphone.radiowaves.left.and.right"
        }
        return "display"
    }
}

// MARK: - Window Root Hosts

private struct MainWindowRootView: View {
    @Bindable var model: AppModel
    let appDelegate: OpalDeskAppDelegate
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Group {
            if model.isShowingDisplayCanvas {
                DisplayWindowView(model: model)
                    .navigationTitle(windowTitle)
            } else {
                DeviceHubView(model: model)
                    .navigationTitle("OpalDesk")
            }
        }
        .frame(minWidth: 680, minHeight: 480)
        .onAppear(perform: wireAppIntegration)
        .onOpenURL { url in
            model.handleIncomingURL(url)
        }
        .sheet(item: mainWindowApkInstallerBinding) { ctx in
            ApkInstallerSheet(
                packageURL: ctx.packageURL,
                preloadedInfo: ctx.preloadedInfo,
                installState: model.apkInstallState,
                targetDeviceName: model.isAnyChannelConnected ? model.activeChannelDeviceName : nil,
                onInstall: { info, replaceExisting, allowDowngrade in
                    model.startApkInstallation(
                        info: info,
                        replaceExisting: replaceExisting,
                        allowDowngrade: allowDowngrade
                    )
                },
                onCancel: {
                    model.dismissApkInstaller()
                }
            )
        }
        .sheet(isPresented: $model.isShortcutManagerPresented) {
            ShortcutManagerSheet(
                availableApps: model.shortcutAppCandidates(),
                onLaunchShortcut: { shortcut in
                    model.isShortcutManagerPresented = false
                    model.handleIncomingURL(shortcut.deepLinkURL)
                },
                onClose: {
                    model.isShortcutManagerPresented = false
                }
            )
        }
    }

    private var mainWindowApkInstallerBinding: Binding<ApkInstallerContext?> {
        Binding(
            get: {
                guard let ctx = model.apkInstallerContext, ctx.targetWindow == .main else { return nil }
                return ctx
            },
            set: { newValue in
                if newValue == nil {
                    model.dismissApkInstaller()
                }
            }
        )
    }

    private func wireAppIntegration() {
        model.onRequestOpenMainWindow = { [openWindow] in
            openWindow(id: WindowID.main)
            NSApp.activate(ignoringOtherApps: true)
        }
        model.onRequestOpenFileBrowserWindow = { [openWindow] in
            openWindow(id: WindowID.fileBrowser)
            NSApp.activate(ignoringOtherApps: true)
        }
        appDelegate.onTerminate = { [model] in
            model.cleanupOnTerminate()
        }
        appDelegate.onOpenURLs = { [model] urls in
            model.handleIncomingURLs(urls)
        }
        appDelegate.servicesProvider = model.finderServicesProvider
    }

    private var windowTitle: String {
        guard let conn = model.activeConnection else { return "OpalDesk" }
        let appLabel = model.deviceState.topAppLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        return appLabel.isEmpty ? conn.deviceName : "\(conn.deviceName) · \(appLabel)"
    }
}

private struct FileBrowserWindowHostView: View {
    @Bindable var model: AppModel

    var body: some View {
        RemoteFileBrowserWindowView(
            deviceName: model.activeChannelDeviceName,
            isConnected: model.isAnyChannelConnected,
            currentPath: model.fileBrowserCurrentPath,
            items: model.fileBrowserItems,
            isLoadingFiles: model.isLoadingFileBrowser,
            filesError: model.fileBrowserError,
            storageReport: model.fileBrowserStorageReport,
            isScanningStorage: model.isScanningRemoteStorage,
            storageError: model.fileBrowserStorageError,
            transfers: model.fileBrowserTransfers,
            actions: model.makeFileBrowserActions()
        )
        .onAppear {
            model.ensureFileBrowserInitialLoad()
        }
        .onChange(of: model.isAnyChannelConnected) { _, connected in
            if connected {
                model.ensureFileBrowserInitialLoad()
            }
        }
        .sheet(item: fileBrowserApkInstallerBinding) { ctx in
            ApkInstallerSheet(
                packageURL: ctx.packageURL,
                preloadedInfo: ctx.preloadedInfo,
                installState: model.apkInstallState,
                targetDeviceName: model.isAnyChannelConnected ? model.activeChannelDeviceName : nil,
                onInstall: { info, replaceExisting, allowDowngrade in
                    model.startApkInstallation(
                        info: info,
                        replaceExisting: replaceExisting,
                        allowDowngrade: allowDowngrade
                    )
                },
                onCancel: {
                    model.dismissApkInstaller()
                }
            )
        }
    }

    private var fileBrowserApkInstallerBinding: Binding<ApkInstallerContext?> {
        Binding(
            get: {
                guard let ctx = model.apkInstallerContext, ctx.targetWindow == .fileBrowser else { return nil }
                return ctx
            },
            set: { newValue in
                if newValue == nil {
                    model.dismissApkInstaller()
                }
            }
        )
    }
}

private struct MenuBarHostView: View {
    @Bindable var model: AppModel
    let appDelegate: OpalDeskAppDelegate
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        MenuBarView(model: model)
            .onAppear {
                model.onRequestOpenMainWindow = { [openWindow] in
                    openWindow(id: WindowID.main)
                    NSApp.activate(ignoringOtherApps: true)
                }
                model.onRequestOpenFileBrowserWindow = { [openWindow] in
                    openWindow(id: WindowID.fileBrowser)
                    NSApp.activate(ignoringOtherApps: true)
                }
                appDelegate.onTerminate = { [model] in
                    model.cleanupOnTerminate()
                }
                appDelegate.onOpenURLs = { [model] urls in
                    model.handleIncomingURLs(urls)
                }
                appDelegate.servicesProvider = model.finderServicesProvider
            }
    }
}

// MARK: - Application Commands Menu

private struct OpalDeskCommands: Commands {
    @Bindable var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some Commands {
        CommandMenu("投屏控制") {
            Button("返回") {
                model.sendAndroidBack()
            }
            .keyboardShortcut("[", modifiers: [.command])
            .disabled(!model.isAuthenticated)

            Button("桌面") {
                model.sendAndroidHome()
            }
            .keyboardShortcut("h", modifiers: [.command, .shift])
            .disabled(!model.isAuthenticated)

            Button("多任务") {
                model.sendAndroidRecents()
            }
            .disabled(!model.isAuthenticated)

            Divider()

            Button("一键截图（保存桌面并复制）") {
                model.captureMainWindowScreenshot()
            }
            .keyboardShortcut("s", modifiers: [.command, .shift])
            .disabled(!model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0)

            Button(model.isMainWindowRecording ? "停止屏幕录制 (\(model.formattedRecordingDuration))" : "开始录制 MP4 视频") {
                model.toggleMainWindowRecording()
            }
            .keyboardShortcut("v", modifiers: [.command, .shift])
            .disabled(!model.isAuthenticated || (!model.isMainWindowRecording && model.videoMetrics.totalFramesDecoded == 0))

            Divider()

            Button("手机文件与存储...") {
                openWindow(id: WindowID.fileBrowser)
                NSApp.activate(ignoringOtherApps: true)
            }
            .keyboardShortcut("f", modifiers: [.command, .shift])

            Button("安装 Android 安装包 (.apk/.xapk)...") {
                model.presentApkFileChooser(targetWindow: .main)
            }
            .keyboardShortcut("i", modifiers: [.command, .shift])

            Button("Spotlight 快捷方式管理...") {
                model.openShortcutManager()
            }

            Divider()

            Button("重载当前应用 DPI") {
                model.reloadCurrentAppDPI()
            }
            .disabled(!model.isAuthenticated || model.deviceState.topAppPackage.isEmpty)

            Divider()

            Button(model.isBlackoutActive ? "点亮手机主屏" : "熄灭手机主屏") {
                model.toggleBlackout()
            }
            .keyboardShortcut("b", modifiers: [.command, .shift])
            .disabled(!model.isAuthenticated)

            Button(model.isAudioMuted ? "取消静音" : "静音音频") {
                model.toggleAudio()
            }
            .keyboardShortcut("m", modifiers: [.command, .shift])
            .disabled(!model.isAuthenticated)

            Button("切换 DeX / 镜像") {
                model.toggleDisplayMode()
            }
            .keyboardShortcut("d", modifiers: [.command, .shift])
            .disabled(!model.isAuthenticated)

            Button("顺时针旋转 90°") {
                model.rotateClockwise()
            }
            .keyboardShortcut("r", modifiers: [.command, .shift])
            .disabled(!model.isAuthenticated)

            Button("强制刷新关键帧 (IDR)") {
                model.requestIDR()
            }
            .disabled(!model.isAuthenticated)

            Divider()

            Button("断开并返回列表") {
                model.forceDisconnectAndReturnHome()
            }
            .keyboardShortcut("w", modifiers: [.command, .shift])
            .disabled(model.activeConnection == nil && !model.isConnectingFlow)

            Button("强制退出 OpalDesk") {
                model.forceTerminateApp()
            }
            .keyboardShortcut("q", modifiers: [.command, .option])
        }
    }
}
