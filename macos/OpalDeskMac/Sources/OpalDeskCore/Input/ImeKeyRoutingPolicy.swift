import AppKit
import Carbon.HIToolbox
import CoreGraphics
import Foundation

/// Pure, deterministic routing policy for macOS `NSTextInputClient` / `NSTextInputContext`
/// CJK IME composition, input-source switching (`Shift`, `CapsLock`, `Ctrl+Space`, `Ctrl+.`),
/// candidate-window anchoring, and hardware key forwarding to Android.
public enum ImeKeyRoutingPolicy {
    private enum AnchorMetrics {
        static let clickAnchorWidth: CGFloat = 24
        static let clickAnchorHeight: CGFloat = 24
        static let edgeMargin: CGFloat = 24
        static let fallbackWidth: CGFloat = 120
        static let fallbackHeight: CGFloat = 24
        static let fallbackBottomOffset: CGFloat = 64
    }

    /// Returns a valid non-`NSNotFound` selection range for `NSTextInputClient.selectedRange()`.
    /// On macOS Sonoma/Sequoia, returning `NSNotFound` causes Text Services Manager (TSM) and
    /// Chinese IMEs (Apple Pinyin, Sogou, WeType, Rime) to treat the view as non-editable and
    /// block CJK input-source switching or composition.
    public static func selectedRange(
        markedLength: Int,
        markedSelectedRange: NSRange
    ) -> NSRange {
        guard markedLength > 0 else {
            return NSRange(location: 0, length: 0)
        }
        if markedSelectedRange.location == NSNotFound {
            return NSRange(location: markedLength, length: 0)
        }
        let clampedLoc = min(max(0, markedSelectedRange.location), markedLength)
        let clampedLen = min(max(0, markedSelectedRange.length), markedLength - clampedLoc)
        return NSRange(location: clampedLoc, length: clampedLen)
    }

    /// Only Command-modified shortcuts bypass `NSTextInputContext` when not composing.
    /// Control and Option combinations (such as `Ctrl+Space`, `Ctrl+Option+Space`, `Ctrl+.`,
    /// `Ctrl+\``, or `Option+Y -> ¥`) must always flow through `NSTextInputContext` first so
    /// macOS system and third-party Chinese IMEs can switch modes or toggle CJK punctuation.
    public static func shouldBypassImeOnKeyDown(
        modifiers: NSEvent.ModifierFlags,
        hasMarkedText: Bool
    ) -> Bool {
        modifiers.contains(.command) && !hasMarkedText
    }

    /// Determines whether text delivered to `insertText(_:replacementRange:)` should be committed
    /// through `INPUT_TEXT` (`TEXT_MODE_COMMIT_PASTE`) rather than forwarded as a single ASCII hardware key.
    public static func shouldCommitViaInputText(text: String, wasMarked: Bool) -> Bool {
        guard !text.isEmpty else { return false }
        let isPureSingleASCII = text.count == 1
            && text.unicodeScalars.count == 1
            && (text.unicodeScalars.first?.isASCII == true)
        return wasMarked || !isPureSingleASCII
    }

    /// Determines whether a `keyDown` event should be forwarded to Android as a raw hardware `KeyEvent`
    /// after `NSTextInputContext.handleEvent(_:)` has inspected it.
    public static func shouldForwardHardwareKeyAfterIme(
        imeConsumedTextOrMarked: Bool,
        handledByInputContext: Bool,
        didReceiveDoCommand: Bool,
        didReceivePlainAsciiInsert: Bool,
        wasComposing: Bool,
        isComposingNow: Bool
    ) -> Bool {
        if imeConsumedTextOrMarked || wasComposing || isComposingNow {
            return false
        }
        // If IMK consumed the event internally (e.g. Ctrl+. punctuation toggle, candidate paging,
        // or IME mode switch) without emitting a command selector or single-ASCII insertText, do not leak it.
        if handledByInputContext && !didReceiveDoCommand && !didReceivePlainAsciiInsert {
            return false
        }
        return true
    }

    /// Determines whether a `flagsChanged` modifier transition should be forwarded to Android
    /// after `NSTextInputContext.handleEvent(_:)` has processed it.
    public static func shouldForwardFlagsChanged(
        macKeyCode: UInt16,
        currentFlags: NSEvent.ModifierFlags,
        previousFlags: NSEvent.ModifierFlags,
        consumedByInputContext: Bool,
        hasMarkedText: Bool,
        inputSourceChanged: Bool
    ) -> Bool {
        if consumedByInputContext || hasMarkedText || inputSourceChanged {
            return false
        }
        // On macOS, short-pressing CapsLock to switch ABC <-> Chinese IME does not toggle `.capsLock`.
        // Never leak a phantom KEYCODE_CAPS_LOCK to Android in that case.
        if macKeyCode == UInt16(kVK_CapsLock)
            && currentFlags.contains(.capsLock) == previousFlags.contains(.capsLock) {
            return false
        }
        return true
    }

    /// Computes a flipped-view-local anchor rect for `firstRect(forCharacterRange:actualRange:)`
    /// so the macOS Chinese IME candidate window appears near the user's last click inside the canvas.
    public static func candidateAnchorRect(lastClickPoint: CGPoint?, bounds: CGRect) -> CGRect {
        guard bounds.width > 0, bounds.height > 0 else { return .zero }
        if let click = lastClickPoint {
            let maxX = max(AnchorMetrics.edgeMargin, bounds.width - AnchorMetrics.edgeMargin - AnchorMetrics.clickAnchorWidth)
            let maxY = max(AnchorMetrics.edgeMargin, bounds.height - AnchorMetrics.edgeMargin - AnchorMetrics.clickAnchorHeight)
            let x = min(max(AnchorMetrics.edgeMargin, click.x - AnchorMetrics.clickAnchorWidth * 0.5), maxX)
            let y = min(max(AnchorMetrics.edgeMargin, click.y), maxY)
            return CGRect(x: x, y: y, width: AnchorMetrics.clickAnchorWidth, height: AnchorMetrics.clickAnchorHeight)
        }
        let x = max(0, bounds.midX - AnchorMetrics.fallbackWidth * 0.5)
        let y = max(0, bounds.height - AnchorMetrics.fallbackBottomOffset)
        return CGRect(x: x, y: y, width: AnchorMetrics.fallbackWidth, height: AnchorMetrics.fallbackHeight)
    }

    /// Determines whether `DisplayCanvasNSView` may claim window first responder and activate
    /// its `NSTextInputContext`. Never steal focus or TSM input context when the host window is
    /// not key, when a modal sheet is attached, when the canvas is non-interactive, or while a
    /// native text view / field editor already owns first responder.
    public static func shouldClaimCanvasFirstResponder(
        isCanvasInteractive: Bool,
        isWindowKey: Bool,
        hasAttachedSheet: Bool,
        isFirstResponderTextInput: Bool
    ) -> Bool {
        isCanvasInteractive && isWindowKey && !hasAttachedSheet && !isFirstResponderTextInput
    }

    /// Determines whether `DisplayCanvasNSView` should immediately relinquish first responder and
    /// deactivate its `NSTextInputContext` so modal sheets (such as `RemoteAppPickerSheet`) can
    /// cleanly receive keyboard and CJK IME input.
    public static func shouldRelinquishCanvasFocus(
        wasCanvasInteractive: Bool,
        isCanvasInteractive: Bool,
        hasAttachedSheet: Bool
    ) -> Bool {
        (wasCanvasInteractive && !isCanvasInteractive) || hasAttachedSheet
    }
}
