import AppKit
import Foundation
import OpalDeskCore
import SwiftUI
import UniformTypeIdentifiers

// MARK: - NSFilePromiseProvider Drag-Out Support

/// Async pull closure invoked when macOS Finder, Desktop, or a target app resolves a promised remote file.
public typealias RemoteFileDragPullHandler = @Sendable (
    _ remotePath: String,
    _ destinationURL: URL
) async throws -> URL

/// Wraps AppKit's non-Sendable completion callback for safe delivery across Swift 6 async boundaries.
private struct UncheckedErrorCompletion: @unchecked Sendable {
    let call: (Error?) -> Void
}

/// Dedicated `NSFilePromiseProviderDelegate` streaming a remote Android file directly into the drop destination.
public final class RemoteFilePromiseDelegate: NSObject, NSFilePromiseProviderDelegate, @unchecked Sendable {
    private let remotePath: String
    private let fileName: String
    private let pullFile: RemoteFileDragPullHandler
    private let workQueue: OperationQueue

    public init(
        item: RemoteFileItem,
        pullFile: @escaping RemoteFileDragPullHandler
    ) {
        self.remotePath = item.path
        self.fileName = Self.sanitizedFileName(item.name)
        self.pullFile = pullFile

        let queue = OperationQueue()
        queue.name = "io.github.lengsukq.opaldesk.mac.file-promise"
        queue.qualityOfService = .userInitiated
        queue.maxConcurrentOperationCount = 2
        self.workQueue = queue
        super.init()
    }

    @MainActor
    public func filePromiseProvider(
        _ filePromiseProvider: NSFilePromiseProvider,
        fileNameForType fileType: String
    ) -> String {
        fileName
    }

    public nonisolated func filePromiseProvider(
        _ filePromiseProvider: NSFilePromiseProvider,
        writePromiseTo url: URL,
        completionHandler: @escaping (Error?) -> Void
    ) {
        let completion = UncheckedErrorCompletion(call: completionHandler)
        let remotePath = self.remotePath
        let pullFile = self.pullFile

        Task.detached(priority: .userInitiated) {
            do {
                _ = try await pullFile(remotePath, url)
                completion.call(nil)
            } catch {
                completion.call(error)
            }
        }
    }

    @MainActor
    public func operationQueue(for filePromiseProvider: NSFilePromiseProvider) -> OperationQueue {
        workQueue
    }

    /// Ensures the promised filename is a safe single path component for macOS destinations.
    static func sanitizedFileName(_ raw: String) -> String {
        let trimmed = raw
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "." || trimmed == ".." {
            return "AndroidFile"
        }
        return trimmed
    }

    /// Resolves a UTI guaranteed to conform to `public.data` (required by `NSFilePromiseProvider`).
    static func promisedUTTypeIdentifier(for item: RemoteFileItem) -> String {
        if !item.fileExtension.isEmpty,
           let byExt = UTType(filenameExtension: item.fileExtension),
           byExt.conforms(to: .data)
        {
            return byExt.identifier
        }
        if !item.mimeType.isEmpty,
           let byMime = UTType(mimeType: item.mimeType),
           byMime.conforms(to: .data)
        {
            return byMime.identifier
        }
        return UTType.data.identifier
    }
}

/// Strongly retains its `RemoteFilePromiseDelegate` for the full lifetime of the drag session,
/// preventing premature deallocation of `NSFilePromiseProvider`'s weak `delegate` reference.
public final class RetainedRemoteFilePromiseProvider: NSFilePromiseProvider {
    private let retainedDelegate: RemoteFilePromiseDelegate

    public init(item: RemoteFileItem, pullFile: @escaping RemoteFileDragPullHandler) {
        let promiseDelegate = RemoteFilePromiseDelegate(item: item, pullFile: pullFile)
        self.retainedDelegate = promiseDelegate
        let utTypeId = RemoteFilePromiseDelegate.promisedUTTypeIdentifier(for: item)
        super.init(fileType: utTypeId, delegate: promiseDelegate)
    }
}

// MARK: - SwiftUI Overlay Bridging Click / Double-Click / NSFilePromiseProvider Drag-Out

/// Transparent AppKit interaction overlay enabling native `NSFilePromiseProvider` drag-out to
/// Finder, Desktop, WeChat, Mail, etc. while preserving single-click selection, double-click
/// activation, right-click context menus, and scroll-wheel forwarding.
struct RemoteFileDragOutOverlay: NSViewRepresentable {
    let item: RemoteFileItem
    let isDragEnabled: Bool
    let pullFile: RemoteFileDragPullHandler?
    let onSelect: @MainActor (NSEvent.ModifierFlags) -> Void
    let onActivate: @MainActor () -> Void

    func makeNSView(context: Context) -> DragOutNSView {
        let view = DragOutNSView()
        updateNSView(view, context: context)
        return view
    }

    func updateNSView(_ nsView: DragOutNSView, context: Context) {
        nsView.item = item
        nsView.isDragEnabled = isDragEnabled && !item.isDirectory && pullFile != nil
        nsView.pullFile = pullFile
        nsView.onSelect = onSelect
        nsView.onActivate = onActivate
    }

    @MainActor
    final class DragOutNSView: NSView, NSDraggingSource {
        private static let dragThresholdPt: CGFloat = 5.0

        var item: RemoteFileItem?
        var isDragEnabled: Bool = false
        var pullFile: RemoteFileDragPullHandler?
        var onSelect: (@MainActor (NSEvent.ModifierFlags) -> Void)?
        var onActivate: (@MainActor () -> Void)?

        override var isOpaque: Bool { false }

        override func acceptsFirstMouse(for event: NSEvent?) -> Bool {
            true
        }

        override func mouseDown(with event: NSEvent) {
            if event.clickCount >= 2 {
                onActivate?()
                return
            }

            let downPoint = convert(event.locationInWindow, from: nil)
            let modifiers = event.modifierFlags

            guard isDragEnabled, let item, let pullFile else {
                onSelect?(modifiers)
                return
            }

            // Track drag threshold synchronously within standard AppKit event loop.
            guard let window else {
                onSelect?(modifiers)
                return
            }

            var startedDrag = false
            while let nextEvent = window.nextEvent(matching: [.leftMouseDragged, .leftMouseUp]) {
                if nextEvent.type == .leftMouseUp {
                    break
                }
                let currentPoint = convert(nextEvent.locationInWindow, from: nil)
                let dist = hypot(currentPoint.x - downPoint.x, currentPoint.y - downPoint.y)
                if dist >= Self.dragThresholdPt {
                    startedDrag = true
                    onSelect?(modifiers)
                    beginPromiseDrag(item: item, pullFile: pullFile, event: event, at: currentPoint)
                    break
                }
            }

            if !startedDrag {
                onSelect?(modifiers)
            }
        }

        private func beginPromiseDrag(
            item: RemoteFileItem,
            pullFile: @escaping RemoteFileDragPullHandler,
            event: NSEvent,
            at point: NSPoint
        ) {
            let promiseProvider = RetainedRemoteFilePromiseProvider(item: item, pullFile: pullFile)
            let draggingItem = NSDraggingItem(pasteboardWriter: promiseProvider)
            let dragImage = makeDragPreviewImage(for: item)
            let iconSize = NSSize(width: 48, height: 48)
            let dragFrame = NSRect(
                x: point.x - iconSize.width * 0.5,
                y: point.y - iconSize.height * 0.5,
                width: iconSize.width,
                height: iconSize.height
            )
            draggingItem.setDraggingFrame(dragFrame, contents: dragImage)
            beginDraggingSession(with: [draggingItem], event: event, source: self)
        }

        private func makeDragPreviewImage(for item: RemoteFileItem) -> NSImage {
            if let thumbData = item.thumbnailData, let img = NSImage(data: thumbData) {
                return img
            }
            if !item.fileExtension.isEmpty, let utType = UTType(filenameExtension: item.fileExtension) {
                return NSWorkspace.shared.icon(for: utType)
            }
            return NSWorkspace.shared.icon(for: .data)
        }

        func draggingSession(
            _ session: NSDraggingSession,
            sourceOperationMaskFor context: NSDraggingContext
        ) -> NSDragOperation {
            .copy
        }
    }
}

// MARK: - Window-Scoped Spacebar Key Monitor for Finder-Style Quick Look

/// Listens for bare Spacebar presses in the hosting `NSWindow` whenever no text input control
/// (e.g. search box, rename sheet, new folder dialog) holds first responder focus.
struct RemoteFileBrowserSpacebarMonitor: NSViewRepresentable {
    let isEnabled: Bool
    let onSpacePressed: @MainActor () -> Void

    func makeNSView(context: Context) -> MonitorHostNSView {
        let view = MonitorHostNSView()
        view.isEnabled = isEnabled
        view.onSpacePressed = onSpacePressed
        return view
    }

    func updateNSView(_ nsView: MonitorHostNSView, context: Context) {
        nsView.isEnabled = isEnabled
        nsView.onSpacePressed = onSpacePressed
    }

    static func dismantleNSView(_ nsView: MonitorHostNSView, coordinator: ()) {
        nsView.removeMonitor()
    }

    @MainActor
    final class MonitorHostNSView: NSView {
        var isEnabled: Bool = true
        var onSpacePressed: (@MainActor () -> Void)?
        private var monitor: Any?

        override func viewWillMove(toWindow newWindow: NSWindow?) {
            if newWindow == nil {
                removeMonitor()
            }
            super.viewWillMove(toWindow: newWindow)
        }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            removeMonitor()
            guard window != nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                guard let self else { return event }
                return self.handleKeyDown(event)
            }
        }

        func removeMonitor() {
            if let monitor {
                NSEvent.removeMonitor(monitor)
                self.monitor = nil
            }
        }

        private func handleKeyDown(_ event: NSEvent) -> NSEvent? {
            guard isEnabled,
                  let hostWindow = window,
                  event.window === hostWindow,
                  event.keyCode == 49, // Spacebar
                  event.modifierFlags.intersection([.command, .option, .control, .shift]).isEmpty
            else {
                return event
            }
            if hostWindow.firstResponder is NSText {
                return event
            }
            if hostWindow.attachedSheet != nil {
                return event
            }
            onSpacePressed?()
            return nil
        }
    }
}
