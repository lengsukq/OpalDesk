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

            // Seamless macOS translucent titlebar lets the ambient opalescent backdrop flow behind
            // system traffic lights while keeping native window controls and title positioning intact.
            window.titlebarAppearsTransparent = true
            window.titleVisibility = .visible
            window.toolbarStyle = .unified
            window.backgroundColor = isImmersiveCanvas ? DeXTheme.canvasNSColor : .windowBackgroundColor
            window.appearance = isImmersiveCanvas ? NSAppearance(named: .darkAqua) : nil
            window.isMovableByWindowBackground = false
        }
    }
}
