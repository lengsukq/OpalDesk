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
        let resolvedLocale = model.preferences.resolvedLocale
        WindowGroup("OpalDesk", id: WindowID.main) {
            MainWindowRootView(model: model, appDelegate: appDelegate)
                .environment(\.locale, resolvedLocale)
        }
        .defaultSize(width: 1180, height: 740)
        .commands {
            OpalDeskCommands(model: model)
        }

        Window(OpalL10n.text("window.file_browser"), id: WindowID.fileBrowser) {
            FileBrowserWindowHostView(model: model)
                .environment(\.locale, resolvedLocale)
        }
        .defaultSize(width: 980, height: 640)

        MenuBarExtra {
            MenuBarHostView(model: model, appDelegate: appDelegate)
                .environment(\.locale, resolvedLocale)
        } label: {
            Image(systemName: menuBarIconName)
                .symbolRenderingMode(.hierarchical)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView(model: model)
                .environment(\.locale, resolvedLocale)
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

// MARK: - Shared Integration Helpers (DRY across Scenes)

@MainActor
private func wireSharedAppIntegration(
    model: AppModel,
    appDelegate: OpalDeskAppDelegate,
    openWindow: OpenWindowAction
) {
    model.onRequestOpenMainWindow = {
        openWindow(id: WindowID.main)
        NSApp.activate(ignoringOtherApps: true)
    }
    model.onRequestOpenFileBrowserWindow = {
        openWindow(id: WindowID.fileBrowser)
        NSApp.activate(ignoringOtherApps: true)
    }
    appDelegate.onTerminate = {
        model.cleanupOnTerminate()
    }
    appDelegate.onOpenURLs = { urls in
        model.handleIncomingURLs(urls)
    }
    appDelegate.servicesProvider = model.finderServicesProvider
}

/// Shared APK installer sheet modifier so `MainWindowRootView` and `FileBrowserWindowHostView` stay DRY.
private struct ApkInstallerSheetHostModifier: ViewModifier {
    @Bindable var model: AppModel
    let targetWindow: ApkInstallerContext.PresentationTarget

    func body(content: Content) -> some View {
        content.sheet(item: installerBinding) { ctx in
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

    private var installerBinding: Binding<ApkInstallerContext?> {
        Binding(
            get: {
                guard let ctx = model.apkInstallerContext, ctx.targetWindow == targetWindow else { return nil }
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
                    .modifier(OpalTitleFreeToolbar())
            }
        }
        .frame(minWidth: 820, minHeight: 540)
        .onAppear {
            wireSharedAppIntegration(model: model, appDelegate: appDelegate, openWindow: openWindow)
        }
        .onOpenURL { url in
            model.handleIncomingURL(url)
        }
        .modifier(ApkInstallerSheetHostModifier(model: model, targetWindow: .main))
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

    private var windowTitle: String {
        guard let conn = model.activeConnection else { return "OpalDesk" }
        let appLabel = model.deviceState.topAppLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        return appLabel.isEmpty ? conn.deviceName : "\(conn.deviceName) · \(appLabel)"
    }
}

/// Removes the redundant macOS 15+ titlebar text when our borderless fluid canvas renders its own header.
private struct OpalTitleFreeToolbar: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 15, *) {
            content.toolbar(removing: .title)
        } else {
            content
        }
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
            finderMountState: model.finderMountState,
            quickLookLoadingPath: model.quickLookLoadingPath,
            directOpenMaxMB: model.preferences.fileDirectOpenMaxMB,
            actions: model.makeFileBrowserActions()
        )
        .modifier(OpalTitleFreeToolbar())
        .background(WindowChromeConfigurator(isImmersiveCanvas: false))
        .onAppear {
            model.ensureFileBrowserInitialLoad()
        }
        .onChange(of: model.isAnyChannelConnected) { _, connected in
            if connected {
                model.ensureFileBrowserInitialLoad()
            }
        }
        .modifier(ApkInstallerSheetHostModifier(model: model, targetWindow: .fileBrowser))
    }
}

private struct MenuBarHostView: View {
    @Bindable var model: AppModel
    let appDelegate: OpalDeskAppDelegate
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        MenuBarView(model: model)
            .onAppear {
                wireSharedAppIntegration(model: model, appDelegate: appDelegate, openWindow: openWindow)
            }
    }
}

// MARK: - Application Commands Menu

private struct OpalDeskCommands: Commands {
    @Bindable var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some Commands {
        let _ = model.preferences.appLanguage
        CommandMenu(OpalL10n.text("menu.cast_controls")) {
            Button(OpalL10n.text("menu.back")) { model.sendAndroidBack() }
                .keyboardShortcut("[", modifiers: [.command])
                .disabled(!model.isAuthenticated)

            Button(OpalL10n.text("menu.home")) { model.sendAndroidHome() }
                .keyboardShortcut("h", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

            Button(OpalL10n.text("menu.recents")) { model.sendAndroidRecents() }
                .disabled(!model.isAuthenticated)

            Divider()

            Button(OpalL10n.text("menu.screenshot")) { model.captureMainWindowScreenshot() }
                .keyboardShortcut("s", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0)

            Button(model.isMainWindowRecording ? OpalL10n.format("menu.stop_recording_fmt", model.formattedRecordingDuration) : OpalL10n.text("menu.start_recording")) {
                model.toggleMainWindowRecording()
            }
            .keyboardShortcut("v", modifiers: [.command, .shift])
            .disabled(!model.isAuthenticated || (!model.isMainWindowRecording && model.videoMetrics.totalFramesDecoded == 0))

            Divider()

            Button(OpalL10n.text("menu.phone_files_ellipsis")) {
                openWindow(id: WindowID.fileBrowser)
                NSApp.activate(ignoringOtherApps: true)
            }
            .keyboardShortcut("f", modifiers: [.command, .shift])

            Button(model.finderMountState.isMounted ? OpalL10n.text("menu.open_finder_disk") : OpalL10n.text("menu.mount_finder")) {
                if model.finderMountState.isMounted {
                    model.openMountedFinderVolume()
                } else {
                    model.toggleFinderMount()
                }
            }
            .keyboardShortcut("f", modifiers: [.command, .option])
            .disabled(!model.isAnyChannelConnected)

            Button(OpalL10n.text("menu.install_apk_ellipsis")) {
                model.presentApkFileChooser(targetWindow: .main)
            }
            .keyboardShortcut("i", modifiers: [.command, .shift])

            Button(OpalL10n.text("menu.shortcuts_ellipsis")) { model.openShortcutManager() }

            Divider()

            Button(OpalL10n.text("menu.reload_dpi")) { model.reloadCurrentAppDPI() }
                .disabled(!model.isAuthenticated || model.deviceState.topAppPackage.isEmpty)

            Divider()

            Button(model.isBlackoutActive ? OpalL10n.text("menu.screen_on") : OpalL10n.text("menu.screen_off")) { model.toggleBlackout() }
                .keyboardShortcut("b", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

            Button(model.isAudioMuted ? OpalL10n.text("menu.unmute") : OpalL10n.text("menu.mute")) { model.toggleAudio() }
                .keyboardShortcut("m", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

            Button(OpalL10n.text("menu.toggle_mode")) { model.toggleDisplayMode() }
                .keyboardShortcut("d", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

            Button(OpalL10n.text("menu.rotate_cw")) { model.rotateClockwise() }
                .keyboardShortcut("r", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

            Button(OpalL10n.text("menu.request_idr")) { model.requestIDR() }
                .disabled(!model.isAuthenticated)

            Divider()

            Button(OpalL10n.text("menu.disconnect_home")) { model.forceDisconnectAndReturnHome() }
                .keyboardShortcut("w", modifiers: [.command, .shift])
                .disabled(model.activeConnection == nil && !model.isConnectingFlow)

            Button(OpalL10n.text("menu.quit_opaldesk")) { model.forceTerminateApp() }
                .keyboardShortcut("q", modifiers: [.command, .option])
        }
    }
}
