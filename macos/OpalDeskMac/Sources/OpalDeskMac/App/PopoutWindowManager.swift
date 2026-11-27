import AppKit
import Foundation
import Observation
import OpalDeskCore
import SwiftUI

public enum PopoutOpenResult: Sendable, Equatable {
    case opened
    case alreadyOpen(existingId: UUID, appLabel: String)
    case invalidPackage
    case limitReached(maxCount: Int)
}

/// Native AppKit `NSWindow` coordinator hosting up to 3 independent standalone Android app windows.
/// Popout windows stay alive independently of the main OpalDesk window and support native always-on-top pinning.
@MainActor
@Observable
public final class PopoutWindowManager {
    private enum Layout {
        static let minWindowSize = NSSize(width: 320, height: 480)
        static let fallbackOriginX: CGFloat = 160
        static let fallbackOriginY: CGFloat = 120
        static let fallbackCascadeStep: CGFloat = 40
        static let screenEdgeInset: CGFloat = 20
        static let rightMargin: CGFloat = 36
        static let cascadeXStep: CGFloat = 44
        static let cascadeYStep: CGFloat = 22
    }

    public static let maxConcurrentWindows: Int = PopoutHistoryLogic.maxConcurrentFloatingWindows

    public private(set) var controllers: [PopoutAppController] = []

    @ObservationIgnored
    public var onControllersChanged: (() -> Void)?

    @ObservationIgnored
    private var hosts: [UUID: PopoutWindowHost] = [:]

    public init() {}

    public func existingController(for appPackage: String) -> PopoutAppController? {
        let normalized = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }
        return controllers.first { $0.appPackage == normalized }
    }

    public func openPopout(
        appPackage: String,
        appActivity: String = "",
        appLabel: String = "",
        deviceKey: String,
        deviceName: String,
        taskId: Int = -1,
        initialPinnedOnTop: Bool = false,
        canReturnToDesktop: Bool = false,
        forceReloadExisting: Bool = false,
        context: PopoutConnectionContext,
        trustStore: TrustStore,
        onPinChanged: @escaping (Bool) -> Void,
        onAuthTokenIssued: @escaping (_ deviceName: String, _ fingerprint: String, _ authToken: String) -> Void,
        onFilesDropped: @escaping ([URL]) -> Void
    ) -> PopoutOpenResult {
        let normalizedPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedPkg.isEmpty else {
            return .invalidPackage
        }

        if let existing = controllers.first(where: { $0.appPackage == normalizedPkg }) {
            if !forceReloadExisting {
                return .alreadyOpen(existingId: existing.id, appLabel: existing.appLabel)
            }
            closeWindow(id: existing.id, sendDisconnect: true)
        }

        guard controllers.count < Self.maxConcurrentWindows else {
            return .limitReached(maxCount: Self.maxConcurrentWindows)
        }

        let controller = PopoutAppController(
            appPackage: normalizedPkg,
            appActivity: appActivity,
            appLabel: appLabel.isEmpty ? normalizedPkg : appLabel,
            deviceKey: deviceKey,
            deviceName: deviceName,
            taskId: taskId,
            initialPinnedOnTop: initialPinnedOnTop,
            canReturnToDesktop: canReturnToDesktop,
            connectionContext: context,
            trustStore: trustStore
        )

        let window = makePopoutWindow(for: controller, cascadeIndex: controllers.count, onFilesDropped: onFilesDropped)
        controller.hostWindow = window
        controller.applyWindowPinLevel()

        let host = PopoutWindowHost(window: window) { [weak self, id = controller.id] in
            self?.handleHostWindowClosed(id: id)
        }
        window.delegate = host

        controller.onRequestClose = { [weak self, id = controller.id] in
            self?.closeWindow(id: id, sendDisconnect: false)
        }
        controller.onPinStateChanged = onPinChanged
        controller.onAuthTokenIssued = onAuthTokenIssued

        hosts[controller.id] = host
        controllers.append(controller)
        onControllersChanged?()

        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        controller.start()
        return .opened
    }

    public func migrateConnectionContext(to context: PopoutConnectionContext) {
        for controller in controllers {
            controller.migrateConnectionContext(to: context)
        }
    }

    public func hasActiveController(usingLocalPort port: UInt16) -> Bool {
        controllers.contains { $0.isUsingLocalPort(port) }
    }

    public func updateCanReturnToDesktop(_ canReturn: Bool) {
        for controller in controllers {
            controller.canReturnToDesktop = canReturn
        }
    }

    public func updateEdgeNavigationGestures(_ enabled: Bool) {
        for controller in controllers {
            controller.enableEdgeNavigationGestures = enabled
        }
    }

    public func focusWindow(id: UUID) {
        guard let host = hosts[id] else { return }
        if host.window.isMiniaturized {
            host.window.deminiaturize(nil)
        }
        host.window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    public func closeWindow(id: UUID, sendDisconnect: Bool = true) {
        guard let idx = controllers.firstIndex(where: { $0.id == id }) else {
            hosts.removeValue(forKey: id)?.window.close()
            return
        }
        let controller = controllers.remove(at: idx)
        let host = hosts.removeValue(forKey: id)
        controller.onRequestClose = nil
        controller.close(sendDisconnect: sendDisconnect)
        if let host {
            host.window.delegate = nil
            host.window.orderOut(nil)
            host.window.close()
        }
        onControllersChanged?()
    }

    public func closeAll(sendDisconnect: Bool = true) {
        let ids = controllers.map(\.id)
        for id in ids {
            closeWindow(id: id, sendDisconnect: sendDisconnect)
        }
    }

    // MARK: - Private Window Factory

    private func handleHostWindowClosed(id: UUID) {
        guard let idx = controllers.firstIndex(where: { $0.id == id }) else {
            hosts.removeValue(forKey: id)
            return
        }
        let controller = controllers.remove(at: idx)
        hosts.removeValue(forKey: id)
        controller.onRequestClose = nil
        controller.close(sendDisconnect: true)
        onControllersChanged?()
    }

    private func makePopoutWindow(
        for controller: PopoutAppController,
        cascadeIndex: Int,
        onFilesDropped: @escaping ([URL]) -> Void
    ) -> NSWindow {
        let initialSize = PopoutAspectPreset.phone.windowSize
        let origin = initialOrigin(for: initialSize, cascadeIndex: cascadeIndex)
        let rect = NSRect(origin: origin, size: initialSize)

        let window = NSWindow(
            contentRect: rect,
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = controller.appLabel
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = false
        window.isReleasedWhenClosed = false
        window.isOpaque = false
        window.backgroundColor = .clear
        window.appearance = NSAppearance(named: .darkAqua)
        window.minSize = Layout.minWindowSize
        window.hasShadow = true
        window.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]

        window.standardWindowButton(.closeButton)?.isHidden = true
        window.standardWindowButton(.miniaturizeButton)?.isHidden = true
        window.standardWindowButton(.zoomButton)?.isHidden = true

        let rootView = PopoutAppWindowView(
            controller: controller,
            onFilesDropped: onFilesDropped
        )
        let hostingView = NSHostingView(rootView: rootView)
        hostingView.frame = NSRect(origin: .zero, size: initialSize)
        hostingView.autoresizingMask = [.width, .height]
        window.contentView = hostingView
        return window
    }

    private func initialOrigin(for size: CGSize, cascadeIndex: Int) -> CGPoint {
        guard let visible = NSScreen.main?.visibleFrame else {
            return CGPoint(
                x: Layout.fallbackOriginX + CGFloat(cascadeIndex) * Layout.fallbackCascadeStep,
                y: Layout.fallbackOriginY
            )
        }
        let xOffset = CGFloat(cascadeIndex) * Layout.cascadeXStep
        let yOffset = CGFloat(cascadeIndex) * Layout.cascadeYStep
        let x = max(visible.minX + Layout.screenEdgeInset, visible.maxX - size.width - Layout.rightMargin - xOffset)
        let y = max(visible.minY + Layout.screenEdgeInset, visible.midY - size.height * 0.5 - yOffset)
        return CGPoint(x: x, y: y)
    }
}

@MainActor
private final class PopoutWindowHost: NSObject, NSWindowDelegate {
    let window: NSWindow
    private var onClose: (() -> Void)?

    init(window: NSWindow, onClose: @escaping () -> Void) {
        self.window = window
        self.onClose = onClose
        super.init()
    }

    func windowWillClose(_ notification: Notification) {
        let cb = onClose
        onClose = nil
        cb?()
    }
}
