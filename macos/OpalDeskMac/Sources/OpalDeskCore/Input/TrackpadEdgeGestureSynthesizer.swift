import CoreGraphics
import Foundation

/// Active edge zone on a macOS trackpad mapped to Android native edge navigation gestures.
///
/// Normalized trackpad coordinates (`NSTouch.normalizedPosition`):
/// - `x`: `0.0` (left edge) -> `1.0` (right edge)
/// - `y`: `0.0` (bottom edge) -> `1.0` (top edge)
public enum TrackpadEdgeZone: String, Sendable, Equatable, CaseIterable {
    /// Left trackpad edge swiped rightward -> Android left-edge Back (with Predictive Back live preview).
    case leftEdge
    /// Right trackpad edge swiped leftward -> Android right-edge Back (with Predictive Back live preview).
    case rightEdge
    /// Bottom trackpad edge swiped upward -> Android Home / Recents (pause to enter Overview).
    case bottomEdge
    /// Top trackpad edge swiped downward -> Android Notification Shade / Quick Settings pull-down.
    case topEdge
}

/// Tuning parameters for `TrackpadEdgeGestureSynthesizer`.
public struct TrackpadEdgeGestureConfig: Sendable, Equatable {
    /// Master switch for two-finger edge navigation gesture synthesis.
    public var isEnabled: Bool
    /// Normalized trackpad band thickness `[0, edgeBandWidth]` / `[1 - edgeBandWidth, 1]` that arms edge gestures.
    public var edgeBandWidth: CGFloat
    /// Minimum normalized inward displacement required before committing `ACTION_DOWN` (prevents accidental triggers).
    public var activationDisplacement: CGFloat
    /// Minimum ratio of inward axis movement over orthogonal axis movement (separates edge swipes from normal scrolling).
    public var directionalBias: CGFloat
    /// Pixel inset from the Android screen edge where synthetic `ACTION_DOWN` lands (`x = 2`, `x = width - 2`, etc.).
    public var edgeInsetPx: Float
    /// Horizontal drag amplification from normalized trackpad travel to Android display width.
    public var horizontalTravelMultiplier: CGFloat
    /// Vertical drag amplification from normalized trackpad travel to Android display height.
    public var verticalTravelMultiplier: CGFloat
    /// Android touch `pointerId` assigned to synthetic edge navigation touches.
    public var pointerId: Int32

    public init(
        isEnabled: Bool = true,
        edgeBandWidth: CGFloat = 0.14,
        activationDisplacement: CGFloat = 0.025,
        directionalBias: CGFloat = 1.2,
        edgeInsetPx: Float = 2.0,
        horizontalTravelMultiplier: CGFloat = 1.15,
        verticalTravelMultiplier: CGFloat = 1.15,
        pointerId: Int32 = 0
    ) {
        self.isEnabled = isEnabled
        self.edgeBandWidth = edgeBandWidth
        self.activationDisplacement = activationDisplacement
        self.directionalBias = directionalBias
        self.edgeInsetPx = edgeInsetPx
        self.horizontalTravelMultiplier = horizontalTravelMultiplier
        self.verticalTravelMultiplier = verticalTravelMultiplier
        self.pointerId = pointerId
    }
}

/// Lifecycle state of `TrackpadEdgeGestureSynthesizer`.
public enum TrackpadEdgeGesturePhase: Sendable, Equatable {
    case idle
    case armed(candidates: [TrackpadEdgeZone], startCentroid: CGPoint)
    case active(zone: TrackpadEdgeZone, startCentroid: CGPoint, anchorPoint: CGPoint, lastPoint: CGPoint)
    case ignored
}

/// Pure, deterministic state machine that translates macOS two-finger trackpad edge swipes
/// into live Android `TYPE_INPUT_TOUCH (0x24)` streams (`down` -> continuous `move` -> `up` / `cancel`).
///
/// Starting on the true Android screen edge (`x = 2`, `x = videoWidth - 2`, `y = 2`, `y = videoHeight - 2`)
/// triggers native Android 14+ Predictive Back preview animations, bottom Home/Recents navigation,
/// and top Notification Shade pull-down without interfering with normal two-finger scrolling.
public struct TrackpadEdgeGestureSynthesizer: Sendable {
    private static let verticalSafeBandMinRatio: CGFloat = 0.15
    private static let verticalSafeBandMaxRatio: CGFloat = 0.85
    private static let horizontalSafeBandMinRatio: CGFloat = 0.20
    private static let horizontalSafeBandMaxRatio: CGFloat = 0.80
    private static let orthogonalFollowFactor: CGFloat = 0.45

    public var config: TrackpadEdgeGestureConfig
    public private(set) var phase: TrackpadEdgeGesturePhase = .idle

    public init(config: TrackpadEdgeGestureConfig = TrackpadEdgeGestureConfig()) {
        self.config = config
    }

    /// Currently active Android edge gesture zone, or `nil` when idle/armed/ignored.
    public var activeZone: TrackpadEdgeZone? {
        if case .active(let zone, _, _, _) = phase {
            return zone
        }
        return nil
    }

    /// `true` once `ACTION_DOWN` has been emitted and live `ACTION_MOVE` tracking is in progress.
    public var isActive: Bool {
        activeZone != nil
    }

    /// `true` while an edge gesture is armed or actively tracking and trackpad scroll events should be suppressed.
    public var isSuppressingScroll: Bool {
        switch phase {
        case .armed, .active:
            return true
        case .idle, .ignored:
            return false
        }
    }

    /// Updates enabled state; if disabled mid-gesture, emits a clean `.cancel` touch event.
    public mutating func setEnabled(_ enabled: Bool) -> [InputTouchPayload] {
        config.isEnabled = enabled
        if !enabled {
            return cancel()
        }
        return []
    }

    /// Returns all edge zones containing `centroid` (multiple zones may match at trackpad corners).
    public func matchingEdgeZones(for centroid: CGPoint) -> [TrackpadEdgeZone] {
        let band = min(max(config.edgeBandWidth, 0.01), 0.45)
        var zones: [TrackpadEdgeZone] = []
        if centroid.x <= band {
            zones.append(.leftEdge)
        } else if centroid.x >= (1.0 - band) {
            zones.append(.rightEdge)
        }
        if centroid.y <= band {
            zones.append(.bottomEdge)
        } else if centroid.y >= (1.0 - band) {
            zones.append(.topEdge)
        }
        return zones
    }

    /// Call from `touchesBegan(with:)` with the touching finger count and normalized centroid.
    public mutating func handleTouchesBegan(activeTouchCount: Int, centroid: CGPoint) -> [InputTouchPayload] {
        guard config.isEnabled else {
            return cancel()
        }

        if activeTouchCount > 2 {
            // Transitioning to 3+ fingers (e.g. three-finger navigation swipe) aborts any edge touch cleanly.
            let payloads = cancelToIgnored()
            return payloads
        }

        guard activeTouchCount == 2 else {
            return []
        }

        switch phase {
        case .idle:
            let candidates = matchingEdgeZones(for: centroid)
            if candidates.isEmpty {
                phase = .ignored
            } else {
                phase = .armed(candidates: candidates, startCentroid: centroid)
            }
            return []

        case .armed, .active, .ignored:
            return []
        }
    }

    /// Call from `touchesMoved(with:)` with the touching finger count, normalized centroid, and Android display size.
    public mutating func handleTouchesMoved(
        activeTouchCount: Int,
        centroid: CGPoint,
        videoWidth: Int,
        videoHeight: Int
    ) -> [InputTouchPayload] {
        guard config.isEnabled else {
            return cancel()
        }
        guard videoWidth > 1, videoHeight > 1 else {
            return []
        }

        if activeTouchCount > 2 {
            return cancelToIgnored()
        }
        if activeTouchCount < 2 {
            return handleTouchesEnded(remainingTouchCount: activeTouchCount)
        }

        switch phase {
        case .idle:
            let candidates = matchingEdgeZones(for: centroid)
            phase = candidates.isEmpty ? .ignored : .armed(candidates: candidates, startCentroid: centroid)
            return []

        case .ignored:
            return []

        case .armed(let candidates, let start):
            let dx = centroid.x - start.x
            let dy = centroid.y - start.y
            let maxDelta = max(abs(dx), abs(dy))
            guard maxDelta >= config.activationDisplacement else {
                return []
            }

            guard let zone = selectInwardZone(from: candidates, dx: dx, dy: dy) else {
                // Movement was orthogonal (normal edge scrolling) or outward -> release to normal scroll.
                phase = .ignored
                return []
            }

            let anchor = anchorPoint(for: zone, startCentroid: start, videoWidth: videoWidth, videoHeight: videoHeight)
            let current = mappedPoint(
                for: zone,
                anchor: anchor,
                dx: dx,
                dy: dy,
                videoWidth: videoWidth,
                videoHeight: videoHeight
            )
            phase = .active(zone: zone, startCentroid: start, anchorPoint: anchor, lastPoint: current)
            return [
                makeTouchPayload(action: .down, point: anchor),
                makeTouchPayload(action: .move, point: current)
            ]

        case .active(let zone, let start, let anchor, _):
            let dx = centroid.x - start.x
            let dy = centroid.y - start.y
            let current = mappedPoint(
                for: zone,
                anchor: anchor,
                dx: dx,
                dy: dy,
                videoWidth: videoWidth,
                videoHeight: videoHeight
            )
            phase = .active(zone: zone, startCentroid: start, anchorPoint: anchor, lastPoint: current)
            return [makeTouchPayload(action: .move, point: current)]
        }
    }

    /// Call from `touchesEnded(with:)` with the count of fingers still touching the trackpad.
    public mutating func handleTouchesEnded(remainingTouchCount: Int) -> [InputTouchPayload] {
        guard remainingTouchCount < 2 else {
            return []
        }

        switch phase {
        case .active(_, _, _, let lastPoint):
            phase = remainingTouchCount == 0 ? .idle : .ignored
            return [makeTouchPayload(action: .up, point: lastPoint)]

        case .armed:
            phase = remainingTouchCount == 0 ? .idle : .ignored
            return []

        case .ignored:
            if remainingTouchCount == 0 {
                phase = .idle
            }
            return []

        case .idle:
            return []
        }
    }

    /// Cancels any in-flight synthetic edge gesture and resets state to `.idle`.
    public mutating func cancel() -> [InputTouchPayload] {
        defer { phase = .idle }
        if case .active(_, _, _, let lastPoint) = phase {
            return [makeTouchPayload(action: .cancel, point: lastPoint)]
        }
        return []
    }

    // MARK: - Private Geometry Helpers

    private mutating func cancelToIgnored() -> [InputTouchPayload] {
        defer { phase = .ignored }
        if case .active(_, _, _, let lastPoint) = phase {
            return [makeTouchPayload(action: .cancel, point: lastPoint)]
        }
        return []
    }

    private func selectInwardZone(from candidates: [TrackpadEdgeZone], dx: CGFloat, dy: CGFloat) -> TrackpadEdgeZone? {
        let bias = max(config.directionalBias, 1.0)
        var bestZone: TrackpadEdgeZone?
        var bestScore: CGFloat = 0

        for zone in candidates {
            switch zone {
            case .leftEdge:
                if dx > 0 && dx >= abs(dy) * bias && dx > bestScore {
                    bestScore = dx
                    bestZone = .leftEdge
                }
            case .rightEdge:
                if dx < 0 && (-dx) >= abs(dy) * bias && (-dx) > bestScore {
                    bestScore = -dx
                    bestZone = .rightEdge
                }
            case .bottomEdge:
                // Trackpad +Y points upward; swiping up from bottom edge means dy > 0.
                if dy > 0 && dy >= abs(dx) * bias && dy > bestScore {
                    bestScore = dy
                    bestZone = .bottomEdge
                }
            case .topEdge:
                // Trackpad -Y points downward; swiping down from top edge means dy < 0.
                if dy < 0 && (-dy) >= abs(dx) * bias && (-dy) > bestScore {
                    bestScore = -dy
                    bestZone = .topEdge
                }
            }
        }
        return bestZone
    }

    private func anchorPoint(
        for zone: TrackpadEdgeZone,
        startCentroid: CGPoint,
        videoWidth: Int,
        videoHeight: Int
    ) -> CGPoint {
        let width = CGFloat(videoWidth)
        let height = CGFloat(videoHeight)
        let maxX = CGFloat(max(videoWidth - 1, 1))
        let maxY = CGFloat(max(videoHeight - 1, 1))
        let inset = CGFloat(max(config.edgeInsetPx, 0))

        // Flip trackpad normalized Y (0=bottom, 1=top) into Android normalized Y (0=top, 1=bottom).
        let androidNormY = clamp(1.0 - startCentroid.y, min: Self.verticalSafeBandMinRatio, max: Self.verticalSafeBandMaxRatio)
        let androidNormX = clamp(startCentroid.x, min: Self.horizontalSafeBandMinRatio, max: Self.horizontalSafeBandMaxRatio)

        switch zone {
        case .leftEdge:
            let x = clamp(inset, min: 0, max: maxX)
            let y = clamp(androidNormY * maxY, min: 0, max: maxY)
            return CGPoint(x: x, y: y)

        case .rightEdge:
            let x = clamp(width - inset, min: 0, max: maxX)
            let y = clamp(androidNormY * maxY, min: 0, max: maxY)
            return CGPoint(x: x, y: y)

        case .bottomEdge:
            let x = clamp(androidNormX * maxX, min: 0, max: maxX)
            let y = clamp(height - inset, min: 0, max: maxY)
            return CGPoint(x: x, y: y)

        case .topEdge:
            let x = clamp(androidNormX * maxX, min: 0, max: maxX)
            let y = clamp(inset, min: 0, max: maxY)
            return CGPoint(x: x, y: y)
        }
    }

    private func mappedPoint(
        for zone: TrackpadEdgeZone,
        anchor: CGPoint,
        dx: CGFloat,
        dy: CGFloat,
        videoWidth: Int,
        videoHeight: Int
    ) -> CGPoint {
        let width = CGFloat(videoWidth)
        let height = CGFloat(videoHeight)
        let maxX = CGFloat(max(videoWidth - 1, 1))
        let maxY = CGFloat(max(videoHeight - 1, 1))

        // Trackpad +Y is upward, while Android +Y is downward.
        let androidDx = dx * width
        let androidDy = -dy * height

        let targetX: CGFloat
        let targetY: CGFloat
        switch zone {
        case .leftEdge, .rightEdge:
            targetX = anchor.x + androidDx * config.horizontalTravelMultiplier
            targetY = anchor.y + androidDy * Self.orthogonalFollowFactor
        case .bottomEdge, .topEdge:
            targetX = anchor.x + androidDx * Self.orthogonalFollowFactor
            targetY = anchor.y + androidDy * config.verticalTravelMultiplier
        }

        return CGPoint(
            x: clamp(targetX, min: 0, max: maxX),
            y: clamp(targetY, min: 0, max: maxY)
        )
    }

    private func makeTouchPayload(action: OpalCastTouchAction, point: CGPoint) -> InputTouchPayload {
        InputTouchPayload(
            action: action,
            actionIndex: 0,
            pointers: [
                TouchPointer(
                    pointerId: config.pointerId,
                    x: Float(point.x),
                    y: Float(point.y),
                    pressure: 1.0
                )
            ]
        )
    }

    private func clamp(_ value: CGFloat, min minValue: CGFloat, max maxValue: CGFloat) -> CGFloat {
        Swift.min(Swift.max(value, minValue), maxValue)
    }
}

// MARK: - Unified Two-Finger Pinch & Rotate Touch Synthesizer

/// Gesture phase abstraction decoupling macOS `NSEvent.Phase` from pure core synthesis logic.
public enum TwoFingerGesturePhase: Sendable, Equatable {
    case began
    case changed
    case ended
    case cancelled
}

/// Pure synthesizer for two-finger pinch (`magnify(with:)`) and rotation (`rotate(with:)`) gestures.
/// Supports independent pinch, independent rotation, and simultaneous pinch+rotation using a single
/// coherent 2-pointer Android `InputTouchPayload` lifecycle around the cursor center.
public struct TwoFingerTransformSynthesizer: Sendable {
    public static let defaultBaseHalfSpan: CGFloat = 140
    public static let defaultMinScale: CGFloat = 0.25
    public static let defaultMaxScale: CGFloat = 4.0

    public let baseHalfSpan: CGFloat
    public let minScale: CGFloat
    public let maxScale: CGFloat

    public private(set) var isPinching: Bool = false
    public private(set) var isRotating: Bool = false
    public private(set) var center: CGPoint = CGPoint(
        x: Double(OpalCastProtocol.defaultWidth) * 0.5,
        y: Double(OpalCastProtocol.defaultHeight) * 0.5
    )
    public private(set) var scale: CGFloat = 1.0
    public private(set) var angleRadians: CGFloat = 0.0

    public init(
        baseHalfSpan: CGFloat = defaultBaseHalfSpan,
        minScale: CGFloat = defaultMinScale,
        maxScale: CGFloat = defaultMaxScale
    ) {
        self.baseHalfSpan = baseHalfSpan
        self.minScale = minScale
        self.maxScale = maxScale
    }

    public var isActive: Bool {
        isPinching || isRotating
    }

    /// Processes a macOS `magnify(with:)` gesture step and returns synthetic Android touch events.
    public mutating func handleMagnify(
        phase: TwoFingerGesturePhase,
        magnification: CGFloat,
        center: CGPoint,
        videoWidth: Int,
        videoHeight: Int
    ) -> [InputTouchPayload] {
        switch phase {
        case .began:
            let wasActive = isActive
            if !wasActive {
                self.center = center
                self.scale = 1.0
                self.angleRadians = 0.0
            }
            isPinching = true
            guard !wasActive else { return [] }
            let (p1, p2) = currentPointers(videoWidth: videoWidth, videoHeight: videoHeight)
            return [
                InputTouchPayload(action: .down, actionIndex: 0, pointers: [p1]),
                InputTouchPayload(action: .pointerDown, actionIndex: 1, pointers: [p1, p2])
            ]

        case .changed:
            guard isPinching else { return [] }
            scale = min(max(scale * (1.0 + magnification), minScale), maxScale)
            let (p1, p2) = currentPointers(videoWidth: videoWidth, videoHeight: videoHeight)
            return [InputTouchPayload(action: .move, actionIndex: 0, pointers: [p1, p2])]

        case .ended:
            guard isPinching else { return [] }
            isPinching = false
            guard !isActive else { return [] }
            let (p1, p2) = currentPointers(videoWidth: videoWidth, videoHeight: videoHeight)
            resetTransform()
            return [
                InputTouchPayload(action: .pointerUp, actionIndex: 1, pointers: [p1, p2]),
                InputTouchPayload(action: .up, actionIndex: 0, pointers: [p1])
            ]

        case .cancelled:
            guard isPinching else { return [] }
            isPinching = false
            guard !isActive else { return [] }
            let (p1, p2) = currentPointers(videoWidth: videoWidth, videoHeight: videoHeight)
            resetTransform()
            return [InputTouchPayload(action: .cancel, actionIndex: 0, pointers: [p1, p2])]
        }
    }

    /// Processes a macOS `rotate(with:)` gesture step (degrees; positive = counter-clockwise on macOS).
    public mutating func handleRotate(
        phase: TwoFingerGesturePhase,
        rotationDegrees: CGFloat,
        center: CGPoint,
        videoWidth: Int,
        videoHeight: Int
    ) -> [InputTouchPayload] {
        switch phase {
        case .began:
            let wasActive = isActive
            if !wasActive {
                self.center = center
                self.scale = 1.0
                self.angleRadians = 0.0
            }
            isRotating = true
            guard !wasActive else { return [] }
            let (p1, p2) = currentPointers(videoWidth: videoWidth, videoHeight: videoHeight)
            return [
                InputTouchPayload(action: .down, actionIndex: 0, pointers: [p1]),
                InputTouchPayload(action: .pointerDown, actionIndex: 1, pointers: [p1, p2])
            ]

        case .changed:
            guard isRotating else { return [] }
            // macOS NSEvent.rotation is in degrees, positive = counter-clockwise in AppKit (y-up).
            // In Android screen space (y-down), decreasing angle produces visual counter-clockwise rotation.
            angleRadians -= rotationDegrees * (.pi / 180.0)
            let (p1, p2) = currentPointers(videoWidth: videoWidth, videoHeight: videoHeight)
            return [InputTouchPayload(action: .move, actionIndex: 0, pointers: [p1, p2])]

        case .ended:
            guard isRotating else { return [] }
            isRotating = false
            guard !isActive else { return [] }
            let (p1, p2) = currentPointers(videoWidth: videoWidth, videoHeight: videoHeight)
            resetTransform()
            return [
                InputTouchPayload(action: .pointerUp, actionIndex: 1, pointers: [p1, p2]),
                InputTouchPayload(action: .up, actionIndex: 0, pointers: [p1])
            ]

        case .cancelled:
            guard isRotating else { return [] }
            isRotating = false
            guard !isActive else { return [] }
            let (p1, p2) = currentPointers(videoWidth: videoWidth, videoHeight: videoHeight)
            resetTransform()
            return [InputTouchPayload(action: .cancel, actionIndex: 0, pointers: [p1, p2])]
        }
    }

    /// Cancels any active pinch or rotation sequence cleanly.
    public mutating func cancel(videoWidth: Int, videoHeight: Int) -> [InputTouchPayload] {
        guard isActive else { return [] }
        let (p1, p2) = currentPointers(videoWidth: videoWidth, videoHeight: videoHeight)
        isPinching = false
        isRotating = false
        resetTransform()
        return [InputTouchPayload(action: .cancel, actionIndex: 0, pointers: [p1, p2])]
    }

    /// Computes two opposing Android touch pointers positioned around `center` at `scale` and `angleRadians`.
    public static func computePointers(
        center: CGPoint,
        scale: CGFloat,
        angleRadians: CGFloat,
        baseHalfSpan: CGFloat = defaultBaseHalfSpan,
        videoWidth: Int,
        videoHeight: Int
    ) -> (TouchPointer, TouchPointer) {
        let halfSpan = baseHalfSpan * scale
        let dx = halfSpan * cos(angleRadians)
        let dy = halfSpan * sin(angleRadians)
        let maxX = CGFloat(max(videoWidth - 1, 1))
        let maxY = CGFloat(max(videoHeight - 1, 1))

        let x1 = min(max(center.x - dx, 0), maxX)
        let y1 = min(max(center.y - dy, 0), maxY)
        let x2 = min(max(center.x + dx, 0), maxX)
        let y2 = min(max(center.y + dy, 0), maxY)

        return (
            TouchPointer(pointerId: 1, x: Float(x1), y: Float(y1), pressure: 1.0),
            TouchPointer(pointerId: 2, x: Float(x2), y: Float(y2), pressure: 1.0)
        )
    }

    private func currentPointers(videoWidth: Int, videoHeight: Int) -> (TouchPointer, TouchPointer) {
        Self.computePointers(
            center: center,
            scale: scale,
            angleRadians: angleRadians,
            baseHalfSpan: baseHalfSpan,
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
    }

    private mutating func resetTransform() {
        scale = 1.0
        angleRadians = 0.0
    }
}
