import AppKit
import Carbon.HIToolbox
import CoreGraphics
import Foundation
import Testing
@testable import OpalDeskCore

@Suite("ImeKeyRoutingPolicy — macOS CJK IME Switching, Composition & Anchoring")
struct ImeKeyRoutingPolicyTests {

    @Test("selectedRange never returns NSNotFound so macOS TSM/IMK keeps Chinese IME enabled")
    func selectedRangeNeverReturnsNSNotFound() {
        let idle = ImeKeyRoutingPolicy.selectedRange(
            markedLength: 0,
            markedSelectedRange: NSRange(location: NSNotFound, length: 0)
        )
        #expect(idle == NSRange(location: 0, length: 0))
        #expect(idle.location != NSNotFound)

        let composing = ImeKeyRoutingPolicy.selectedRange(
            markedLength: 4,
            markedSelectedRange: NSRange(location: 2, length: 1)
        )
        #expect(composing == NSRange(location: 2, length: 1))

        let clamped = ImeKeyRoutingPolicy.selectedRange(
            markedLength: 3,
            markedSelectedRange: NSRange(location: 10, length: 5)
        )
        #expect(clamped == NSRange(location: 3, length: 0))

        let notFoundWhileMarked = ImeKeyRoutingPolicy.selectedRange(
            markedLength: 3,
            markedSelectedRange: NSRange(location: NSNotFound, length: 0)
        )
        #expect(notFoundWhileMarked == NSRange(location: 3, length: 0))
    }

    @Test("Control and Option shortcuts flow through IME while Command shortcuts bypass when idle")
    func keyDownImeBypassPolicy() {
        // Ctrl+Space / Ctrl+Option+Space / Ctrl+. must NOT bypass NSTextInputContext
        #expect(!ImeKeyRoutingPolicy.shouldBypassImeOnKeyDown(modifiers: [.control], hasMarkedText: false))
        #expect(!ImeKeyRoutingPolicy.shouldBypassImeOnKeyDown(modifiers: [.control, .option], hasMarkedText: false))
        #expect(!ImeKeyRoutingPolicy.shouldBypassImeOnKeyDown(modifiers: [.control, .shift], hasMarkedText: false))
        #expect(!ImeKeyRoutingPolicy.shouldBypassImeOnKeyDown(modifiers: [.option], hasMarkedText: false))
        #expect(!ImeKeyRoutingPolicy.shouldBypassImeOnKeyDown(modifiers: [], hasMarkedText: false))

        // Cmd+C / Cmd+V / Cmd+A bypass IME only when not composing marked text
        #expect(ImeKeyRoutingPolicy.shouldBypassImeOnKeyDown(modifiers: [.command], hasMarkedText: false))
        #expect(!ImeKeyRoutingPolicy.shouldBypassImeOnKeyDown(modifiers: [.command], hasMarkedText: true))
    }

    @Test("Commits CJK text, Chinese punctuation, emoji, and composed text via INPUT_TEXT; preserves single ASCII hardware keys")
    func commitTextClassification() {
        #expect(ImeKeyRoutingPolicy.shouldCommitViaInputText(text: "你好", wasMarked: true))
        #expect(ImeKeyRoutingPolicy.shouldCommitViaInputText(text: "，", wasMarked: false))
        #expect(ImeKeyRoutingPolicy.shouldCommitViaInputText(text: "。", wasMarked: false))
        #expect(ImeKeyRoutingPolicy.shouldCommitViaInputText(text: "¥", wasMarked: false))
        #expect(ImeKeyRoutingPolicy.shouldCommitViaInputText(text: "😀", wasMarked: false))
        #expect(ImeKeyRoutingPolicy.shouldCommitViaInputText(text: "a", wasMarked: true))

        #expect(!ImeKeyRoutingPolicy.shouldCommitViaInputText(text: "a", wasMarked: false))
        #expect(!ImeKeyRoutingPolicy.shouldCommitViaInputText(text: ",", wasMarked: false))
        #expect(!ImeKeyRoutingPolicy.shouldCommitViaInputText(text: " ", wasMarked: false))
        #expect(!ImeKeyRoutingPolicy.shouldCommitViaInputText(text: "", wasMarked: true))
    }

    @Test("Hardware key forwarding suppresses IMK-consumed shortcuts while keeping Enter/Backspace/Ctrl+A/ASCII working")
    func hardwareForwardingAfterIme() {
        // Plain ASCII 'a' inserted via insertText -> forward hardware key
        #expect(ImeKeyRoutingPolicy.shouldForwardHardwareKeyAfterIme(
            imeConsumedTextOrMarked: false,
            handledByInputContext: true,
            didReceiveDoCommand: false,
            didReceivePlainAsciiInsert: true,
            wasComposing: false,
            isComposingNow: false
        ))

        // Return / Backspace / Arrows / Ctrl+A -> doCommand called -> forward hardware key
        #expect(ImeKeyRoutingPolicy.shouldForwardHardwareKeyAfterIme(
            imeConsumedTextOrMarked: false,
            handledByInputContext: true,
            didReceiveDoCommand: true,
            didReceivePlainAsciiInsert: false,
            wasComposing: false,
            isComposingNow: false
        ))

        // Ctrl+C not consumed by IMK -> forward hardware key
        #expect(ImeKeyRoutingPolicy.shouldForwardHardwareKeyAfterIme(
            imeConsumedTextOrMarked: false,
            handledByInputContext: false,
            didReceiveDoCommand: false,
            didReceivePlainAsciiInsert: false,
            wasComposing: false,
            isComposingNow: false
        ))

        // IMK silently consumed Ctrl+. or Ctrl+Space or candidate paging -> suppress hardware key
        #expect(!ImeKeyRoutingPolicy.shouldForwardHardwareKeyAfterIme(
            imeConsumedTextOrMarked: false,
            handledByInputContext: true,
            didReceiveDoCommand: false,
            didReceivePlainAsciiInsert: false,
            wasComposing: false,
            isComposingNow: false
        ))

        // Committed Chinese word or Escape cancelling marked text -> suppress hardware key
        #expect(!ImeKeyRoutingPolicy.shouldForwardHardwareKeyAfterIme(
            imeConsumedTextOrMarked: true,
            handledByInputContext: true,
            didReceiveDoCommand: false,
            didReceivePlainAsciiInsert: false,
            wasComposing: true,
            isComposingNow: false
        ))
        #expect(!ImeKeyRoutingPolicy.shouldForwardHardwareKeyAfterIme(
            imeConsumedTextOrMarked: false,
            handledByInputContext: true,
            didReceiveDoCommand: true,
            didReceivePlainAsciiInsert: false,
            wasComposing: true,
            isComposingNow: false
        ))
    }

    @Test("flagsChanged suppresses IME-consumed Shift, CapsLock input-source taps, and active composition modifiers")
    func flagsChangedPolicy() {
        // Shift consumed by Sogou/WeType/Apple Pinyin toggle
        #expect(!ImeKeyRoutingPolicy.shouldForwardFlagsChanged(
            macKeyCode: UInt16(kVK_Shift),
            currentFlags: [.shift],
            previousFlags: [],
            consumedByInputContext: true,
            hasMarkedText: false,
            inputSourceChanged: false
        ))

        // CapsLock short-tap switching ABC <-> Chinese on macOS (capsLock flag unchanged)
        #expect(!ImeKeyRoutingPolicy.shouldForwardFlagsChanged(
            macKeyCode: UInt16(kVK_CapsLock),
            currentFlags: [],
            previousFlags: [],
            consumedByInputContext: false,
            hasMarkedText: false,
            inputSourceChanged: false
        ))

        // Input source switched by OS on modifier key
        #expect(!ImeKeyRoutingPolicy.shouldForwardFlagsChanged(
            macKeyCode: UInt16(kVK_CapsLock),
            currentFlags: [.capsLock],
            previousFlags: [],
            consumedByInputContext: false,
            hasMarkedText: false,
            inputSourceChanged: true
        ))

        // Regular Shift press while idle & not consumed by IME still forwards normally
        #expect(ImeKeyRoutingPolicy.shouldForwardFlagsChanged(
            macKeyCode: UInt16(kVK_Shift),
            currentFlags: [.shift],
            previousFlags: [],
            consumedByInputContext: false,
            hasMarkedText: false,
            inputSourceChanged: false
        ))

        // Long-press genuine CapsLock toggle (flag actually flipped, source did not change)
        #expect(ImeKeyRoutingPolicy.shouldForwardFlagsChanged(
            macKeyCode: UInt16(kVK_CapsLock),
            currentFlags: [.capsLock],
            previousFlags: [],
            consumedByInputContext: false,
            hasMarkedText: false,
            inputSourceChanged: false
        ))
    }

    @Test("candidateAnchorRect tracks last click clamped inside canvas and falls back to bottom-center")
    func candidateAnchorRectPlacement() {
        let bounds = CGRect(x: 0, y: 0, width: 1200, height: 800)

        let fallback = ImeKeyRoutingPolicy.candidateAnchorRect(lastClickPoint: nil, bounds: bounds)
        #expect(fallback.midX == 600)
        #expect(fallback.minY == 736)

        let nearClick = ImeKeyRoutingPolicy.candidateAnchorRect(lastClickPoint: CGPoint(x: 420, y: 310), bounds: bounds)
        #expect(nearClick.midX == 420)
        #expect(nearClick.minY == 310)

        let clampedEdge = ImeKeyRoutingPolicy.candidateAnchorRect(lastClickPoint: CGPoint(x: 1198, y: 798), bounds: bounds)
        #expect(clampedEdge.maxX <= bounds.width - 24)
        #expect(clampedEdge.maxY <= bounds.height - 24)
    }

    @Test("shouldClaimCanvasFirstResponder never steals focus when modal sheet is attached or window is not key")
    func canvasFirstResponderPolicy() {
        #expect(ImeKeyRoutingPolicy.shouldClaimCanvasFirstResponder(
            isCanvasInteractive: true,
            isWindowKey: true,
            hasAttachedSheet: false,
            isFirstResponderTextInput: false
        ))

        // Host window behind RemoteAppPickerSheet is still visible, not key, and has an attached sheet.
        #expect(!ImeKeyRoutingPolicy.shouldClaimCanvasFirstResponder(
            isCanvasInteractive: true,
            isWindowKey: false,
            hasAttachedSheet: true,
            isFirstResponderTextInput: false
        ))

        // Even if window momentarily reports key while sheet is attached, do not steal focus.
        #expect(!ImeKeyRoutingPolicy.shouldClaimCanvasFirstResponder(
            isCanvasInteractive: true,
            isWindowKey: true,
            hasAttachedSheet: true,
            isFirstResponderTextInput: false
        ))

        // Non-key visible window must not steal TSM inputContext.
        #expect(!ImeKeyRoutingPolicy.shouldClaimCanvasFirstResponder(
            isCanvasInteractive: true,
            isWindowKey: false,
            hasAttachedSheet: false,
            isFirstResponderTextInput: false
        ))

        // Non-interactive canvas (e.g., modal overlay presented) must not claim focus.
        #expect(!ImeKeyRoutingPolicy.shouldClaimCanvasFirstResponder(
            isCanvasInteractive: false,
            isWindowKey: true,
            hasAttachedSheet: false,
            isFirstResponderTextInput: false
        ))

        // Native text editor owning first responder must not be preempted.
        #expect(!ImeKeyRoutingPolicy.shouldClaimCanvasFirstResponder(
            isCanvasInteractive: true,
            isWindowKey: true,
            hasAttachedSheet: false,
            isFirstResponderTextInput: true
        ))
    }

    @Test("shouldRelinquishCanvasFocus triggers when canvas leaves interactive state or sheet attaches")
    func canvasRelinquishFocusPolicy() {
        #expect(ImeKeyRoutingPolicy.shouldRelinquishCanvasFocus(wasCanvasInteractive: true, isCanvasInteractive: false, hasAttachedSheet: false))
        #expect(ImeKeyRoutingPolicy.shouldRelinquishCanvasFocus(wasCanvasInteractive: true, isCanvasInteractive: true, hasAttachedSheet: true))
        #expect(!ImeKeyRoutingPolicy.shouldRelinquishCanvasFocus(wasCanvasInteractive: true, isCanvasInteractive: true, hasAttachedSheet: false))
        #expect(!ImeKeyRoutingPolicy.shouldRelinquishCanvasFocus(wasCanvasInteractive: false, isCanvasInteractive: true, hasAttachedSheet: false))
    }
}
