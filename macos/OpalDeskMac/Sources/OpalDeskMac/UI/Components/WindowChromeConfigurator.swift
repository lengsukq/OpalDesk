import AppKit
import SwiftUI

/// Configures the hosting macOS `NSWindow` chrome cleanly between immersive full-bleed dark canvas
/// (`DisplayWindowView`) and native Liquid Glass unified-titlebar macOS windows (`DeviceHubView`,
/// `RemoteFileBrowserWindowView`).
struct WindowChromeConfigurator: NSViewRepresentable {
    let isImmersiveCanvas: Bool
    var onWindowChanged: ((NSWindow?) -> Void)? = nil

    func makeNSView(context: Context) -> ChromeConfiguratorNSView {
        let view = ChromeConfiguratorNSView()
        view.isImmersiveCanvas = isImmersiveCanvas
        view.onWindowChanged = onWindowChanged
        return view
    }

    func updateNSView(_ nsView: ChromeConfiguratorNSView, context: Context) {
        nsView.isImmersiveCanvas = isImmersiveCanvas
        nsView.onWindowChanged = onWindowChanged
        nsView.applyIfNeeded()
    }

    @MainActor
    final class ChromeConfiguratorNSView: NSView {
        var isImmersiveCanvas: Bool = false
        var onWindowChanged: ((NSWindow?) -> Void)?
        private var lastAppliedMode: Bool?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            lastAppliedMode = nil
            onWindowChanged?(window)
            applyIfNeeded()
        }

        func applyIfNeeded() {
            guard let window else { return }
            if lastAppliedMode == isImmersiveCanvas {
                return
            }
            lastAppliedMode = isImmersiveCanvas

            // LightIsle-inspired seamless fluid canvas: hide macOS title bar chrome so the continuous
            // backdrop flows uninterrupted behind the traffic lights.
            window.titlebarAppearsTransparent = true
            window.titleVisibility = isImmersiveCanvas ? .visible : .hidden
            window.toolbarStyle = .unified
            if !isImmersiveCanvas {
                window.styleMask.insert(.fullSizeContentView)
            }
            window.backgroundColor = isImmersiveCanvas ? DeXTheme.canvasNSColor : .clear
            window.appearance = isImmersiveCanvas ? NSAppearance(named: .darkAqua) : nil
            window.isMovableByWindowBackground = !isImmersiveCanvas
        }
    }
}
