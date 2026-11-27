import CoreGraphics
import Foundation
import Testing
@testable import OpalDeskCore

@Suite("Trackpad Edge Navigation & Two-Finger Transform Gestures")
struct TrackpadEdgeGestureSynthesizerTests {
    private let videoWidth = 1080
    private let videoHeight = 2400

    @Test("Left-edge two-finger swipe right synthesizes live Predictive Back touch stream starting at x=2")
    func leftEdgeSwipeRightTriggersPredictiveBack() {
        var synth = TrackpadEdgeGestureSynthesizer()

        let began = synth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.08, y: 0.50))
        #expect(began.isEmpty)
        #expect(synth.isSuppressingScroll == true)
        #expect(synth.activeZone == nil)

        // Sub-threshold jitter should not emit touch events yet.
        let jitter = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.09, y: 0.50),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(jitter.isEmpty)
        #expect(synth.activeZone == nil)

        // Crossing inward threshold commits ACTION_DOWN at Android left edge x=2 followed by live ACTION_MOVE.
        let activate = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.18, y: 0.50),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(activate.count == 2)
        #expect(synth.activeZone == .leftEdge)
        #expect(activate[0].action == .down)
        #expect(activate[0].pointers.count == 1)
        #expect(activate[0].pointers[0].x == 2.0)
        #expect(activate[1].action == .move)
        #expect(activate[1].pointers[0].x > activate[0].pointers[0].x)

        // Continuing rightward advances X smoothly; dragging back toward edge pulls X back.
        let dragFurther = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.35, y: 0.48),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(dragFurther.count == 1)
        #expect(dragFurther[0].action == .move)
        #expect(dragFurther[0].pointers[0].x > activate[1].pointers[0].x)

        let dragBack = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.10, y: 0.50),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(dragBack.count == 1)
        #expect(dragBack[0].pointers[0].x < dragFurther[0].pointers[0].x)

        // Lifting fingers emits ACTION_UP at the last tracked coordinate and resets to idle.
        let up = synth.handleTouchesEnded(remainingTouchCount: 0)
        #expect(up.count == 1)
        #expect(up[0].action == .up)
        #expect(up[0].pointers[0] == dragBack[0].pointers[0])
        #expect(synth.phase == .idle)
        #expect(synth.isActive == false)
    }

    @Test("Right-edge two-finger swipe left lands at x = videoWidth - 2 and moves leftward")
    func rightEdgeSwipeLeftTriggersBack() {
        var synth = TrackpadEdgeGestureSynthesizer()
        _ = synth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.92, y: 0.50))

        let events = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.78, y: 0.50),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(events.count == 2)
        #expect(synth.activeZone == .rightEdge)
        #expect(events[0].action == .down)
        #expect(events[0].pointers[0].x == Float(videoWidth - 2))
        #expect(events[1].action == .move)
        #expect(events[1].pointers[0].x < events[0].pointers[0].x)

        let end = synth.handleTouchesEnded(remainingTouchCount: 0)
        #expect(end.count == 1)
        #expect(end[0].action == .up)
    }

    @Test("Bottom-edge two-finger swipe up lands at y = videoHeight - 2 and moves upward")
    func bottomEdgeSwipeUpTriggersHomeOrRecents() {
        var synth = TrackpadEdgeGestureSynthesizer()
        _ = synth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.50, y: 0.08))

        let events = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.50, y: 0.25),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(events.count == 2)
        #expect(synth.activeZone == .bottomEdge)
        #expect(events[0].action == .down)
        #expect(events[0].pointers[0].y == Float(videoHeight - 2))
        #expect(events[1].action == .move)
        #expect(events[1].pointers[0].y < events[0].pointers[0].y)

        let end = synth.handleTouchesEnded(remainingTouchCount: 0)
        #expect(end.count == 1)
        #expect(end[0].action == .up)
    }

    @Test("Top-edge two-finger swipe down lands at y = 2 and pulls notification shade downward")
    func topEdgeSwipeDownTriggersNotificationShade() {
        var synth = TrackpadEdgeGestureSynthesizer()
        _ = synth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.50, y: 0.92))

        let events = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.50, y: 0.72),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(events.count == 2)
        #expect(synth.activeZone == .topEdge)
        #expect(events[0].action == .down)
        #expect(events[0].pointers[0].y == 2.0)
        #expect(events[1].action == .move)
        #expect(events[1].pointers[0].y > events[0].pointers[0].y)

        let end = synth.handleTouchesEnded(remainingTouchCount: 0)
        #expect(end.count == 1)
        #expect(end[0].action == .up)
    }

    @Test("Normal two-finger scroll starting in trackpad center never activates edge gesture even if drifting to edge")
    func centerScrollIsIgnoredEvenWhenDriftingToEdge() {
        var synth = TrackpadEdgeGestureSynthesizer()
        _ = synth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.50, y: 0.50))
        #expect(synth.phase == .ignored)
        #expect(synth.isSuppressingScroll == false)

        // Drift into left edge and swipe right -> must stay ignored until fingers release.
        let drift = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.05, y: 0.50),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        let swipeAfterDrift = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.30, y: 0.50),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(drift.isEmpty)
        #expect(swipeAfterDrift.isEmpty)
        #expect(synth.isActive == false)

        _ = synth.handleTouchesEnded(remainingTouchCount: 0)
        #expect(synth.phase == .idle)
    }

    @Test("Vertical scrolling near left edge disarms cleanly instead of triggering Back")
    func verticalScrollNearLeftEdgeIsIgnored() {
        var synth = TrackpadEdgeGestureSynthesizer()
        _ = synth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.08, y: 0.50))

        // Vertical scroll along left edge: dy=0.10, dx=0.01 -> orthogonal to left edge inward axis.
        let events = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.09, y: 0.60),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(events.isEmpty)
        #expect(synth.phase == .ignored)
        #expect(synth.isSuppressingScroll == false)
    }

    @Test("Corner start disambiguates between horizontal left-edge and vertical bottom-edge by swipe vector")
    func cornerDisambiguatesByDirection() {
        var horizontalSynth = TrackpadEdgeGestureSynthesizer()
        _ = horizontalSynth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.06, y: 0.06))
        _ = horizontalSynth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.20, y: 0.07),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(horizontalSynth.activeZone == .leftEdge)

        var verticalSynth = TrackpadEdgeGestureSynthesizer()
        _ = verticalSynth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.06, y: 0.06))
        _ = verticalSynth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.07, y: 0.20),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(verticalSynth.activeZone == .bottomEdge)
    }

    @Test("Three-finger transition or disabling mid-gesture emits ACTION_CANCEL cleanly")
    func cancelOnThreeFingerOrDisable() {
        var synth = TrackpadEdgeGestureSynthesizer()
        _ = synth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.06, y: 0.50))
        _ = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.20, y: 0.50),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(synth.isActive == true)

        // Adding a 3rd finger cancels the active edge touch cleanly.
        let threeFingerCancel = synth.handleTouchesBegan(activeTouchCount: 3, centroid: CGPoint(x: 0.22, y: 0.50))
        #expect(threeFingerCancel.count == 1)
        #expect(threeFingerCancel[0].action == .cancel)
        #expect(synth.isActive == false)

        // Reset and test disabling mid-gesture.
        _ = synth.handleTouchesEnded(remainingTouchCount: 0)
        _ = synth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.94, y: 0.50))
        _ = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.78, y: 0.50),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        let disableCancel = synth.setEnabled(false)
        #expect(disableCancel.count == 1)
        #expect(disableCancel[0].action == .cancel)
        #expect(synth.phase == .idle)

        // Subsequent edge swipes while disabled produce nothing.
        _ = synth.handleTouchesBegan(activeTouchCount: 2, centroid: CGPoint(x: 0.05, y: 0.50))
        let whileDisabled = synth.handleTouchesMoved(
            activeTouchCount: 2,
            centroid: CGPoint(x: 0.25, y: 0.50),
            videoWidth: videoWidth,
            videoHeight: videoHeight
        )
        #expect(whileDisabled.isEmpty)
    }

    @Test("Two-finger rotation synthesizes two opposing pointers rotating around center")
    func twoFingerRotationSynthesis() {
        var transform = TwoFingerTransformSynthesizer(baseHalfSpan: 100)
        let center = CGPoint(x: 500, y: 500)

        let began = transform.handleRotate(
            phase: .began,
            rotationDegrees: 0,
            center: center,
            videoWidth: 1000,
            videoHeight: 1000
        )
        #expect(began.count == 2)
        #expect(began[0].action == .down)
        #expect(began[1].action == .pointerDown)
        #expect(abs(began[1].pointers[0].x - 400) < 0.01)
        #expect(abs(began[1].pointers[0].y - 500) < 0.01)
        #expect(abs(began[1].pointers[1].x - 600) < 0.01)
        #expect(abs(began[1].pointers[1].y - 500) < 0.01)

        // +90 degrees on macOS = 90 degrees counter-clockwise visually in Android (y-down) coordinates:
        // left pointer (400, 500) moves to bottom (500, 600); right pointer (600, 500) moves to top (500, 400).
        let changed = transform.handleRotate(
            phase: .changed,
            rotationDegrees: 90,
            center: center,
            videoWidth: 1000,
            videoHeight: 1000
        )
        #expect(changed.count == 1)
        #expect(changed[0].action == .move)
        #expect(abs(changed[0].pointers[0].x - 500) < 0.1)
        #expect(abs(changed[0].pointers[0].y - 600) < 0.1)
        #expect(abs(changed[0].pointers[1].x - 500) < 0.1)
        #expect(abs(changed[0].pointers[1].y - 400) < 0.1)

        let ended = transform.handleRotate(
            phase: .ended,
            rotationDegrees: 0,
            center: center,
            videoWidth: 1000,
            videoHeight: 1000
        )
        #expect(ended.count == 2)
        #expect(ended[0].action == .pointerUp)
        #expect(ended[1].action == .up)
        #expect(transform.isActive == false)
    }

    @Test("Simultaneous pinch and rotate share one coherent 2-pointer touch lifecycle")
    func simultaneousPinchAndRotateLifecycle() {
        var transform = TwoFingerTransformSynthesizer(baseHalfSpan: 100)
        let center = CGPoint(x: 500, y: 500)

        let pinchBegin = transform.handleMagnify(
            phase: .began,
            magnification: 0,
            center: center,
            videoWidth: 1000,
            videoHeight: 1000
        )
        #expect(pinchBegin.count == 2)

        // Rotate starts while pinch is already active: must NOT emit duplicate ACTION_DOWN.
        let rotateBegin = transform.handleRotate(
            phase: .began,
            rotationDegrees: 0,
            center: center,
            videoWidth: 1000,
            videoHeight: 1000
        )
        #expect(rotateBegin.isEmpty)

        // Magnify by +1.0 (scale=2.0 -> halfSpan=200) then rotate +90 deg.
        _ = transform.handleMagnify(
            phase: .changed,
            magnification: 1.0,
            center: center,
            videoWidth: 1000,
            videoHeight: 1000
        )
        let combined = transform.handleRotate(
            phase: .changed,
            rotationDegrees: 90,
            center: center,
            videoWidth: 1000,
            videoHeight: 1000
        )
        #expect(combined.count == 1)
        #expect(abs(combined[0].pointers[0].y - 700) < 0.2)
        #expect(abs(combined[0].pointers[1].y - 300) < 0.2)

        // Ending pinch while rotate is still active must NOT lift fingers yet.
        let pinchEnd = transform.handleMagnify(
            phase: .ended,
            magnification: 0,
            center: center,
            videoWidth: 1000,
            videoHeight: 1000
        )
        #expect(pinchEnd.isEmpty)
        #expect(transform.isActive == true)

        // Ending rotate now completes the shared touch sequence cleanly.
        let rotateEnd = transform.handleRotate(
            phase: .ended,
            rotationDegrees: 0,
            center: center,
            videoWidth: 1000,
            videoHeight: 1000
        )
        #expect(rotateEnd.count == 2)
        #expect(rotateEnd[0].action == .pointerUp)
        #expect(rotateEnd[1].action == .up)
        #expect(transform.isActive == false)
    }
}
