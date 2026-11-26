import AVFoundation
import AppKit
import OpalDeskCore
import QuartzCore

/// Core AppKit view hosting `AVSampleBufferDisplayLayer`, pointer/scroll mapping,
/// top-edge hover detection, 120Hz mouse move coalescing, and Finder file drag-and-drop.
/// Trackpad gestures and keyboard/IME live in cohesive sibling extensions.
@MainActor
public final class DisplayCanvasNSView: NSView {
    private enum Metrics {
        static let topHoverZoneHeight: CGFloat = 18
        static let toolbarClearZoneY: CGFloat = 84
        static let mouseMoveMinIntervalNs: UInt64 = 8_000_000 // ~120Hz coalescing
        static let preciseScrollDivisor: CGFloat = 18.0
        static let fallbackBackingScale: CGFloat = 2.0
    }

    private let videoLayer: AVSampleBufferDisplayLayer
    private var trackingAreaRef: NSTrackingArea?
    private static let transparentCursor: NSCursor = {
        let img = NSImage(size: NSSize(width: 1, height: 1))
        img.lockFocus()
        NSColor.clear.setFill()
        NSRect(x: 0, y: 0, width: 1, height: 1).fill()
        img.unlockFocus()
        return NSCursor(image: img, hotSpot: .zero)
    }()

    public var config: DisplayCanvasConfig
    public var callbacks: DisplayCanvasCallbacks

    // Pointer state
    private var pressedButtons = OpalCastMouseButtons()
    private var lastDisplayPoint = CGPoint(
        x: Double(OpalCastProtocol.defaultWidth) * 0.5,
        y: Double(OpalCastProtocol.defaultHeight) * 0.5
    )
    private var lastReportedTopHover: Bool = false
    private var lastMouseMoveSentNs: UInt64 = 0

    // Pinch & three-finger gesture state (accessed by DisplayCanvasNSView+Gestures)
    var isPinching: Bool = false
    var pinchCenter = CGPoint(
        x: Double(OpalCastProtocol.defaultWidth) * 0.5,
        y: Double(OpalCastProtocol.defaultHeight) * 0.5
    )
    var pinchScale: CGFloat = 1.0
    var threeFingerStartCentroid: CGPoint?
    var threeFingerDidTrigger: Bool = false
    var lastThreeFingerTriggerNs: UInt64 = 0

    // Keyboard & IME state (accessed by DisplayCanvasNSView+KeyboardIME)
    var markedString: String = ""
    var markedSelectedRange = NSRange(location: 0, length: 0)
    var imeHandledCurrentKeyDown: Bool = false
    var didReceiveDoCommandInCurrentKeyDown: Bool = false
    var didReceivePlainAsciiInsertInCurrentKeyDown: Bool = false
    var lastModifierFlags: NSEvent.ModifierFlags = []
    var consumedShortcutKeyCodes: Set<UInt16> = []
    var imeConsumedKeyCodes: Set<UInt16> = []
    var lastClickLocalPoint: CGPoint?
    nonisolated(unsafe) private var windowKeyObserver: NSObjectProtocol?

    public init(
        displayLayer: AVSampleBufferDisplayLayer,
        config: DisplayCanvasConfig,
        callbacks: DisplayCanvasCallbacks
    ) {
        self.videoLayer = displayLayer
        self.config = config
        self.callbacks = callbacks
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = DeXTheme.canvasNSColor.cgColor
        allowedTouchTypes = [.indirect]
        wantsRestingTouches = false
        videoLayer.frame = bounds
        videoLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        if videoLayer.superlayer !== layer {
            videoLayer.removeFromSuperlayer()
            layer?.addSublayer(videoLayer)
        }
        registerForDraggedTypes([.fileURL])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        if let observer = windowKeyObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    public override var isFlipped: Bool { true }
    public override var acceptsFirstResponder: Bool { true }

    public override func becomeFirstResponder() -> Bool {
        let accepted = super.becomeFirstResponder()
        if accepted {
            lastModifierFlags = NSEvent.modifierFlags.intersection(.deviceIndependentFlagsMask)
            inputContext?.activate()
        }
        return accepted
    }

    public override func resignFirstResponder() -> Bool {
        if hasMarkedText() {
            inputContext?.discardMarkedText()
            unmarkText()
        }
        imeConsumedKeyCodes.removeAll()
        return super.resignFirstResponder()
    }

    public func claimFirstResponderIfNeeded() {
        guard let window, window.isKeyWindow || window.isVisible else { return }
        if window.firstResponder is NSTextView {
            return
        }
        if window.firstResponder !== self {
            window.makeFirstResponder(self)
        }
        inputContext?.activate()
    }

    public override func viewWillMove(toWindow newWindow: NSWindow?) {
        super.viewWillMove(toWindow: newWindow)
        if let observer = windowKeyObserver {
            NotificationCenter.default.removeObserver(observer)
            windowKeyObserver = nil
        }
    }

    public override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard let window else { return }
        window.acceptsMouseMovedEvents = true
        windowKeyObserver = NotificationCenter.default.addObserver(
            forName: NSWindow.didBecomeKeyNotification,
            object: window,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.claimFirstResponderIfNeeded()
            }
        }
        claimFirstResponderIfNeeded()
        DispatchQueue.main.async { [weak self] in
            self?.claimFirstResponderIfNeeded()
        }
        reportCurrentSize()
    }

    public override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        if let scale = window?.backingScaleFactor {
            videoLayer.contentsScale = scale
        }
        reportCurrentSize()
    }

    public override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer.frame = bounds
        CATransaction.commit()
        reportCurrentSize()
    }

    public override func resetCursorRects() {
        super.resetCursorRects()
        if config.hideLocalCursor {
            addCursorRect(bounds, cursor: Self.transparentCursor)
        }
    }

    public override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let existing = trackingAreaRef {
            removeTrackingArea(existing)
        }
        let area = NSTrackingArea(
            rect: bounds,
            options: [.activeAlways, .mouseMoved, .mouseEnteredAndExited, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(area)
        trackingAreaRef = area
    }

    private func reportCurrentSize() {
        guard bounds.width > 0, bounds.height > 0 else { return }
        let scale = window?.backingScaleFactor ?? Metrics.fallbackBackingScale
        callbacks.onResize(bounds.size, scale)
    }

    // MARK: - Pointer Mapping, Mouse & Scroll Events

    func mapEventPoint(_ event: NSEvent) -> CGPoint {
        let localPoint = convert(event.locationInWindow, from: nil)
        let nearTop = localPoint.y <= Metrics.topHoverZoneHeight && localPoint.y >= 0
        if nearTop != lastReportedTopHover {
            lastReportedTopHover = nearTop
            callbacks.onTopEdgeHover(nearTop)
        }
        if localPoint.y > Metrics.toolbarClearZoneY {
            callbacks.onCursorClearedTopZone?()
        }
        if let mapped = CoordinateMapper.mapViewPointToDisplay(
            viewPoint: localPoint,
            viewSize: bounds.size,
            videoWidth: config.videoWidth,
            videoHeight: config.videoHeight,
            isViewFlipped: true,
            clampToBounds: true
        ) {
            lastDisplayPoint = mapped
        }
        return lastDisplayPoint
    }

    public override func mouseMoved(with event: NSEvent) {
        let pt = mapEventPoint(event)
        let nowNs = DispatchTime.now().uptimeNanoseconds
        if nowNs >= lastMouseMoveSentNs && (nowNs - lastMouseMoveSentNs) < Metrics.mouseMoveMinIntervalNs {
            return
        }
        lastMouseMoveSentNs = nowNs
        callbacks.onMouse(InputMousePayload(
            action: .moveAbs,
            x: Float(pt.x),
            y: Float(pt.y),
            buttons: pressedButtons
        ))
    }

    public override func mouseDragged(with event: NSEvent) { mouseMoved(with: event) }
    public override func rightMouseDragged(with event: NSEvent) { mouseMoved(with: event) }
    public override func otherMouseDragged(with event: NSEvent) { mouseMoved(with: event) }

    public override func mouseExited(with event: NSEvent) {
        lastReportedTopHover = false
        callbacks.onTopEdgeHover(false)
        callbacks.onCursorClearedTopZone?()
        callbacks.onMouse(InputMousePayload(
            action: .hoverExit,
            x: Float(lastDisplayPoint.x),
            y: Float(lastDisplayPoint.y),
            buttons: pressedButtons
        ))
    }

    public override func mouseDown(with event: NSEvent) {
        lastClickLocalPoint = convert(event.locationInWindow, from: nil)
        claimFirstResponderIfNeeded()
        if hasMarkedText() {
            let handled = inputContext?.handleEvent(event) ?? false
            if !handled && hasMarkedText() {
                inputContext?.discardMarkedText()
                unmarkText()
            }
        }
        callbacks.onCanvasInteract?()
        handleButton(event: event, button: .primary, isDown: true)
    }

    public override func mouseUp(with event: NSEvent) {
        handleButton(event: event, button: .primary, isDown: false)
    }

    public override func rightMouseDown(with event: NSEvent) {
        lastClickLocalPoint = convert(event.locationInWindow, from: nil)
        claimFirstResponderIfNeeded()
        callbacks.onCanvasInteract?()
        handleButton(event: event, button: .secondary, isDown: true)
    }

    public override func rightMouseUp(with event: NSEvent) {
        handleButton(event: event, button: .secondary, isDown: false)
    }

    public override func otherMouseDown(with event: NSEvent) {
        lastClickLocalPoint = convert(event.locationInWindow, from: nil)
        claimFirstResponderIfNeeded()
        handleButton(event: event, button: mapOtherMouseButton(event.buttonNumber), isDown: true)
    }

    public override func otherMouseUp(with event: NSEvent) {
        handleButton(event: event, button: mapOtherMouseButton(event.buttonNumber), isDown: false)
    }

    public override func scrollWheel(with event: NSEvent) {
        callbacks.onCanvasInteract?()
        let pt = mapEventPoint(event)
        let divisor: CGFloat = event.hasPreciseScrollingDeltas ? Metrics.preciseScrollDivisor : 1.0
        let hScroll = Float(event.scrollingDeltaX / divisor)
        let vScroll = Float(event.scrollingDeltaY / divisor)
        guard hScroll != 0 || vScroll != 0 else { return }
        callbacks.onMouse(InputMousePayload(
            action: .scroll,
            x: Float(pt.x),
            y: Float(pt.y),
            buttons: pressedButtons,
            hScroll: hScroll,
            vScroll: vScroll
        ))
    }

    private func mapOtherMouseButton(_ buttonNumber: Int) -> OpalCastMouseButtons {
        switch buttonNumber {
        case 2: return .tertiary
        case 3: return .back
        case 4: return .forward
        default: return .tertiary
        }
    }

    private func handleButton(event: NSEvent, button: OpalCastMouseButtons, isDown: Bool) {
        let pt = mapEventPoint(event)
        if isDown {
            pressedButtons.insert(button)
        } else {
            pressedButtons.remove(button)
        }
        callbacks.onMouse(InputMousePayload(
            action: isDown ? .buttonDown : .buttonUp,
            x: Float(pt.x),
            y: Float(pt.y),
            buttons: pressedButtons,
            actionButton: button.rawValue
        ))
    }

    // MARK: - Finder File Drag & Drop

    public override func draggingEntered(_ sender: any NSDraggingInfo) -> NSDragOperation {
        guard !extractFileURLs(from: sender).isEmpty else { return [] }
        callbacks.onDragHoverChange(true)
        return .copy
    }

    public override func draggingExited(_ sender: (any NSDraggingInfo)?) {
        callbacks.onDragHoverChange(false)
    }

    public override func draggingEnded(_ sender: any NSDraggingInfo) {
        callbacks.onDragHoverChange(false)
    }

    public override func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        callbacks.onDragHoverChange(false)
        let urls = extractFileURLs(from: sender)
        guard !urls.isEmpty else { return false }
        callbacks.onFilesDropped(urls)
        return true
    }

    private func extractFileURLs(from sender: any NSDraggingInfo) -> [URL] {
        let options: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        guard let objects = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: options) as? [URL] else {
            return []
        }
        return objects.filter(\.isFileURL)
    }
}
