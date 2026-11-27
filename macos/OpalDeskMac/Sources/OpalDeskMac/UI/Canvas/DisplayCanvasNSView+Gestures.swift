import AppKit
import OpalDeskCore

// MARK: - Trackpad Pinch, Rotate, Two-Finger Edge Navigation & Three-Finger Swipes

extension DisplayCanvasNSView {
    private enum GestureMetrics {
        static let twoFingerCount: Int = 2
        static let threeFingerCount: Int = 3
        static let threeFingerSwipeThreshold: CGFloat = 0.14
        static let threeFingerCooldownNs: UInt64 = 450_000_000
    }

    // MARK: - Pinch-to-Zoom & Two-Finger Rotation -> Synthetic 2-Finger Android Touch

    public override func magnify(with event: NSEvent) {
        guard let phase = mapGesturePhase(event.phase) else { return }
        if phase == .began {
            cancelEdgeGestureIfNeeded()
        }
        let pt = mapEventPoint(event)
        let payloads = twoFingerTransform.handleMagnify(
            phase: phase,
            magnification: event.magnification,
            center: pt,
            videoWidth: config.videoWidth,
            videoHeight: config.videoHeight
        )
        emitTouchPayloads(payloads)
    }

    public override func rotate(with event: NSEvent) {
        guard let phase = mapGesturePhase(event.phase) else { return }
        if phase == .began {
            cancelEdgeGestureIfNeeded()
        }
        let pt = mapEventPoint(event)
        let payloads = twoFingerTransform.handleRotate(
            phase: phase,
            rotationDegrees: CGFloat(event.rotation),
            center: pt,
            videoWidth: config.videoWidth,
            videoHeight: config.videoHeight
        )
        emitTouchPayloads(payloads)
    }

    // MARK: - Two-Finger Edge Navigation & Three-Finger Trackpad Swipes

    public override func touchesBegan(with event: NSEvent) {
        super.touchesBegan(with: event)
        let active = event.touches(matching: .touching, in: self)
        if active.count == GestureMetrics.threeFingerCount {
            cancelEdgeGestureIfNeeded()
            threeFingerStartCentroid = centroid(of: active)
            threeFingerDidTrigger = false
            return
        }

        threeFingerStartCentroid = nil
        threeFingerDidTrigger = false

        guard config.enableEdgeNavigationGestures, !twoFingerTransform.isActive else { return }
        let payloads = edgeGestureSynthesizer.handleTouchesBegan(
            activeTouchCount: active.count,
            centroid: centroid(of: active)
        )
        emitEdgeTouchPayloads(payloads)
    }

    public override func touchesMoved(with event: NSEvent) {
        super.touchesMoved(with: event)
        let active = event.touches(matching: .touching, in: self)
        if active.count == GestureMetrics.threeFingerCount {
            cancelEdgeGestureIfNeeded()
            handleThreeFingerMove(active: active)
            return
        }

        threeFingerStartCentroid = nil

        guard config.enableEdgeNavigationGestures, !twoFingerTransform.isActive else { return }
        guard active.count == GestureMetrics.twoFingerCount || edgeGestureSynthesizer.isActive else { return }
        let payloads = edgeGestureSynthesizer.handleTouchesMoved(
            activeTouchCount: active.count,
            centroid: centroid(of: active),
            videoWidth: config.videoWidth,
            videoHeight: config.videoHeight
        )
        emitEdgeTouchPayloads(payloads)
    }

    public override func touchesEnded(with event: NSEvent) {
        super.touchesEnded(with: event)
        let remaining = event.touches(matching: .touching, in: self).count
        if remaining < GestureMetrics.threeFingerCount {
            threeFingerStartCentroid = nil
            threeFingerDidTrigger = false
        }
        let payloads = edgeGestureSynthesizer.handleTouchesEnded(remainingTouchCount: remaining)
        emitEdgeTouchPayloads(payloads)
    }

    public override func touchesCancelled(with event: NSEvent) {
        super.touchesCancelled(with: event)
        threeFingerStartCentroid = nil
        threeFingerDidTrigger = false
        cancelEdgeGestureIfNeeded()
    }

    public override func swipe(with event: NSEvent) {
        if event.deltaX > 0 {
            emitThreeFingerSwipe(.right)
        } else if event.deltaX < 0 {
            emitThreeFingerSwipe(.left)
        } else if event.deltaY > 0 {
            emitThreeFingerSwipe(.up)
        } else if event.deltaY < 0 {
            emitThreeFingerSwipe(.down)
        }
    }

    /// Suppresses trackpad `scrollWheel` events while a two-finger edge gesture is armed,
    /// actively dragging, or finishing its post-release macOS momentum tail.
    func shouldSuppressScrollWheel(_ event: NSEvent) -> Bool {
        let isTrackpadScroll = event.phase != [] || event.momentumPhase != [] || event.hasPreciseScrollingDeltas
        guard isTrackpadScroll else { return false }

        if edgeGestureSynthesizer.isSuppressingScroll {
            if edgeGestureSynthesizer.isActive {
                suppressMomentumScrollUntilIdle = true
            }
            return true
        }

        if suppressMomentumScrollUntilIdle {
            if event.momentumPhase == .ended || event.momentumPhase == .cancelled {
                suppressMomentumScrollUntilIdle = false
                return true
            }
            if event.momentumPhase != [] {
                return true
            }
            if event.phase == .began {
                suppressMomentumScrollUntilIdle = false
            }
        }

        return false
    }

    // MARK: - Private Helpers

    private func handleThreeFingerMove(active: Set<NSTouch>) {
        let current = centroid(of: active)
        guard let start = threeFingerStartCentroid else {
            threeFingerStartCentroid = current
            threeFingerDidTrigger = false
            return
        }
        guard !threeFingerDidTrigger else { return }

        let dx = current.x - start.x
        let dy = current.y - start.y
        if abs(dx) >= GestureMetrics.threeFingerSwipeThreshold || abs(dy) >= GestureMetrics.threeFingerSwipeThreshold {
            threeFingerDidTrigger = true
            let direction: ThreeFingerSwipeDirection
            if abs(dx) >= abs(dy) {
                direction = dx > 0 ? .right : .left
            } else {
                direction = dy > 0 ? .up : .down
            }
            emitThreeFingerSwipe(direction)
        }
    }

    private func cancelEdgeGestureIfNeeded() {
        let payloads = edgeGestureSynthesizer.cancel()
        emitEdgeTouchPayloads(payloads)
    }

    private func emitEdgeTouchPayloads(_ payloads: [InputTouchPayload]) {
        if edgeGestureSynthesizer.isActive {
            suppressMomentumScrollUntilIdle = true
        }
        emitTouchPayloads(payloads)
        let currentZone = edgeGestureSynthesizer.activeZone
        if currentZone != lastReportedEdgeZone {
            lastReportedEdgeZone = currentZone
            callbacks.onEdgeGestureChange?(currentZone)
        }
    }

    private func emitTouchPayloads(_ payloads: [InputTouchPayload]) {
        for payload in payloads {
            callbacks.onTouch(payload)
        }
    }

    private func mapGesturePhase(_ phase: NSEvent.Phase) -> TwoFingerGesturePhase? {
        switch phase {
        case .began: return .began
        case .changed: return .changed
        case .ended: return .ended
        case .cancelled: return .cancelled
        default: return nil
        }
    }

    private func centroid(of touches: Set<NSTouch>) -> CGPoint {
        guard !touches.isEmpty else { return .zero }
        var sumX: CGFloat = 0
        var sumY: CGFloat = 0
        for touch in touches {
            sumX += touch.normalizedPosition.x
            sumY += touch.normalizedPosition.y
        }
        let count = CGFloat(touches.count)
        return CGPoint(x: sumX / count, y: sumY / count)
    }

    private func emitThreeFingerSwipe(_ direction: ThreeFingerSwipeDirection) {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        if nowNs >= lastThreeFingerTriggerNs && (nowNs - lastThreeFingerTriggerNs) < GestureMetrics.threeFingerCooldownNs {
            return
        }
        lastThreeFingerTriggerNs = nowNs
        callbacks.onThreeFingerSwipe?(direction)
    }
}
