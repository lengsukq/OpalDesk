import AppKit
import Foundation
@preconcurrency import QuickLookUI

/// Native macOS Quick Look (`QLPreviewPanel`) coordinator for previewing downloaded remote Android files.
@MainActor
public final class QuickLookPreviewCoordinator: NSObject, QLPreviewPanelDataSource, QLPreviewPanelDelegate {
    private var previewURLs: [URL] = []

    public override init() {
        super.init()
    }

    public var isVisible: Bool {
        QLPreviewPanel.sharedPreviewPanelExists() && (QLPreviewPanel.shared()?.isVisible == true)
    }

    /// Presents (or refreshes) the shared `QLPreviewPanel` for the given local file URL.
    public func preview(url: URL) {
        preview(urls: [url], initialIndex: 0)
    }

    /// Presents (or refreshes) the shared `QLPreviewPanel` for an ordered list of local file URLs.
    public func preview(urls: [URL], initialIndex: Int = 0) {
        guard !urls.isEmpty, let panel = QLPreviewPanel.shared() else { return }
        previewURLs = urls
        panel.dataSource = self
        panel.delegate = self
        panel.reloadData()
        panel.currentPreviewItemIndex = min(max(initialIndex, 0), urls.count - 1)
        if panel.isVisible {
            panel.refreshCurrentPreviewItem()
            panel.orderFront(nil)
        } else {
            panel.makeKeyAndOrderFront(nil)
        }
    }

    /// Toggles Quick Look for a single local file URL (closes if already showing the same file).
    public func togglePreview(url: URL) {
        if isVisible, previewURLs == [url] {
            closeIfVisible()
            return
        }
        preview(url: url)
    }

    /// Dismisses the shared Quick Look panel if currently open.
    public func closeIfVisible() {
        guard QLPreviewPanel.sharedPreviewPanelExists(), let panel = QLPreviewPanel.shared() else {
            return
        }
        if panel.isVisible {
            panel.orderOut(nil)
        }
    }

    // MARK: - QLPreviewPanelDataSource

    nonisolated public func numberOfPreviewItems(in panel: QLPreviewPanel!) -> Int {
        MainActor.assumeIsolated {
            previewURLs.count
        }
    }

    nonisolated public func previewPanel(_ panel: QLPreviewPanel!, previewItemAt index: Int) -> (any QLPreviewItem)! {
        MainActor.assumeIsolated {
            guard index >= 0 && index < previewURLs.count else {
                return nil
            }
            return previewURLs[index] as NSURL
        }
    }

    // MARK: - QLPreviewPanelDelegate

    nonisolated public func previewPanel(_ panel: QLPreviewPanel!, handle event: NSEvent!) -> Bool {
        guard let event, event.type == .keyDown, event.keyCode == 49 else {
            return false
        }
        MainActor.assumeIsolated {
            panel?.orderOut(nil)
        }
        return true
    }
}
