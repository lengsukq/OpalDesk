import AppKit
import Carbon.HIToolbox
import OpalDeskCore

// MARK: - Hardware Keyboard, Local Shortcuts & CJK IME (`NSTextInputClient`)

extension DisplayCanvasNSView: @preconcurrency NSTextInputClient {
    public override func performKeyEquivalent(with event: NSEvent) -> Bool {
        if handleLocalShortcutIfNeeded(event) {
            return true
        }
        return super.performKeyEquivalent(with: event)
    }

    public override func keyDown(with event: NSEvent) {
        guard canHandleCanvasKeyboardInput else {
            super.keyDown(with: event)
            return
        }
        if handleLocalShortcutIfNeeded(event) {
            return
        }

        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let wasComposing = hasMarkedText()

        // Only Command shortcuts bypass IME when not actively composing; Control/Option combinations
        // (Ctrl+Space, Ctrl+Option+Space, Ctrl+., Ctrl+`, Option+Y) must reach NSTextInputContext first.
        if ImeKeyRoutingPolicy.shouldBypassImeOnKeyDown(modifiers: modifiers, hasMarkedText: wasComposing) {
            sendHardwareKey(event: event, action: .down)
            return
        }

        // Let NSTextInputContext handle CJK composition, IME mode switches, dead keys, and Unicode characters.
        imeHandledCurrentKeyDown = false
        didReceiveDoCommandInCurrentKeyDown = false
        didReceivePlainAsciiInsertInCurrentKeyDown = false

        let handledByInputContext: Bool
        if let context = inputContext {
            handledByInputContext = context.handleEvent(event)
        } else {
            interpretKeyEvents([event])
            handledByInputContext = false
        }

        let shouldForward = ImeKeyRoutingPolicy.shouldForwardHardwareKeyAfterIme(
            imeConsumedTextOrMarked: imeHandledCurrentKeyDown,
            handledByInputContext: handledByInputContext,
            didReceiveDoCommand: didReceiveDoCommandInCurrentKeyDown,
            didReceivePlainAsciiInsert: didReceivePlainAsciiInsertInCurrentKeyDown,
            wasComposing: wasComposing,
            isComposingNow: hasMarkedText()
        )

        if shouldForward {
            sendHardwareKey(event: event, action: .down)
        } else {
            imeConsumedKeyCodes.insert(event.keyCode)
        }
    }

    public override func keyUp(with event: NSEvent) {
        guard canHandleCanvasKeyboardInput else {
            super.keyUp(with: event)
            return
        }
        if consumedShortcutKeyCodes.remove(event.keyCode) != nil {
            return
        }
        if imeConsumedKeyCodes.remove(event.keyCode) != nil {
            return
        }
        guard !hasMarkedText() else { return }
        sendHardwareKey(event: event, action: .up)
    }

    public override func flagsChanged(with event: NSEvent) {
        guard canHandleCanvasKeyboardInput else {
            super.flagsChanged(with: event)
            return
        }
        let previousFlags = lastModifierFlags
        let current = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let sourceBefore = currentInputSourceID()

        // Always deliver modifier transitions (Shift-tap Chinese/English toggle, CapsLock switch, etc.)
        // to NSTextInputContext so macOS Apple Pinyin, Sogou, WeType, Baidu, and Rime can switch modes.
        let consumedByContext = inputContext?.handleEvent(event) ?? false
        if !consumedByContext {
            super.flagsChanged(with: event)
        }

        let sourceAfter = currentInputSourceID()
        let inputSourceChanged = (sourceBefore != nil && sourceAfter != nil && sourceBefore != sourceAfter)
        lastModifierFlags = current

        guard ImeKeyRoutingPolicy.shouldForwardFlagsChanged(
            macKeyCode: event.keyCode,
            currentFlags: current,
            previousFlags: previousFlags,
            consumedByInputContext: consumedByContext,
            hasMarkedText: hasMarkedText(),
            inputSourceChanged: inputSourceChanged
        ) else {
            return
        }

        let action = KeyCodeMapper.modifierKeyAction(
            for: event.keyCode,
            currentFlags: current,
            previousFlags: previousFlags
        )

        if let mapped = KeyCodeMapper.map(
            macKeyCode: event.keyCode,
            modifiers: current,
            mapCommandToCtrl: false
        ) {
            callbacks.onKey(InputKeyPayload(
                action: action,
                androidKeyCode: mapped.androidKeyCode,
                metaState: mapped.metaState,
                repeatCount: 0
            ))
        }
    }

    public override func doCommand(by selector: Selector) {
        // Leave navigation/editing commands (Enter, Tab, Backspace, Escape, arrows, Ctrl+A/C/V)
        // to hardware key forwarding when not composing.
        didReceiveDoCommandInCurrentKeyDown = true
        imeHandledCurrentKeyDown = false
    }

    private func handleLocalShortcutIfNeeded(_ event: NSEvent) -> Bool {
        false
    }

    private func currentInputSourceID() -> String? {
        guard let source = TISCopyCurrentKeyboardInputSource()?.takeRetainedValue(),
              let rawPtr = TISGetInputSourceProperty(source, kTISPropertyInputSourceID) else {
            return nil
        }
        return Unmanaged<CFString>.fromOpaque(rawPtr).takeUnretainedValue() as String
    }

    private func sendHardwareKey(event: NSEvent, action: OpalCastKeyAction) {
        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        guard let mapped = KeyCodeMapper.map(
            macKeyCode: event.keyCode,
            modifiers: modifiers,
            mapCommandToCtrl: config.mapCommandToCtrl
        ) else {
            return
        }
        callbacks.onKey(InputKeyPayload(
            action: action,
            androidKeyCode: mapped.androidKeyCode,
            metaState: mapped.metaState,
            repeatCount: event.isARepeat ? 1 : 0
        ))
    }

    // MARK: - NSTextInputClient

    public func insertText(_ string: Any, replacementRange: NSRange) {
        let text: String
        if let attr = string as? NSAttributedString {
            text = attr.string
        } else if let str = string as? String {
            text = str
        } else {
            return
        }

        let wasMarked = !markedString.isEmpty
        markedString = ""
        markedSelectedRange = NSRange(location: 0, length: 0)

        guard !text.isEmpty else { return }

        // Use INPUT_TEXT commit-paste for IME-composed strings, multi-character strings, or non-ASCII text (Chinese/punctuation/emoji).
        if ImeKeyRoutingPolicy.shouldCommitViaInputText(text: text, wasMarked: wasMarked) {
            imeHandledCurrentKeyDown = true
            callbacks.onCommitText(text)
        } else {
            // Single uncomposed ASCII character: let hardware keyDown forward native Android KeyEvent.
            didReceivePlainAsciiInsertInCurrentKeyDown = true
            imeHandledCurrentKeyDown = false
        }
    }

    public func setMarkedText(_ string: Any, selectedRange: NSRange, replacementRange: NSRange) {
        if let attr = string as? NSAttributedString {
            markedString = attr.string
        } else if let str = string as? String {
            markedString = str
        } else {
            markedString = ""
        }
        markedSelectedRange = ImeKeyRoutingPolicy.selectedRange(
            markedLength: (markedString as NSString).length,
            markedSelectedRange: selectedRange
        )
        imeHandledCurrentKeyDown = true
    }

    public func unmarkText() {
        markedString = ""
        markedSelectedRange = NSRange(location: 0, length: 0)
    }

    public func selectedRange() -> NSRange {
        ImeKeyRoutingPolicy.selectedRange(
            markedLength: (markedString as NSString).length,
            markedSelectedRange: markedSelectedRange
        )
    }

    public func markedRange() -> NSRange {
        markedString.isEmpty
            ? NSRange(location: NSNotFound, length: 0)
            : NSRange(location: 0, length: (markedString as NSString).length)
    }

    public func hasMarkedText() -> Bool {
        !markedString.isEmpty
    }

    public func attributedSubstring(
        forProposedRange range: NSRange,
        actualRange: NSRangePointer?
    ) -> NSAttributedString? {
        let nsMarked = markedString as NSString
        guard nsMarked.length > 0 else { return nil }
        let fullRange = NSRange(location: 0, length: nsMarked.length)
        let clipped = NSIntersectionRange(range, fullRange)
        guard clipped.length > 0 else { return nil }
        actualRange?.pointee = clipped
        return NSAttributedString(string: nsMarked.substring(with: clipped))
    }

    public func validAttributesForMarkedText() -> [NSAttributedString.Key] {
        [.underlineStyle, .markedClauseSegment]
    }

    public func firstRect(
        forCharacterRange range: NSRange,
        actualRange: NSRangePointer?
    ) -> NSRect {
        actualRange?.pointee = range
        guard let window else { return .zero }
        let localAnchor = ImeKeyRoutingPolicy.candidateAnchorRect(
            lastClickPoint: lastClickLocalPoint,
            bounds: bounds
        )
        let windowRect = convert(localAnchor, to: nil)
        return window.convertToScreen(windowRect)
    }

    public func characterIndex(for point: NSPoint) -> Int {
        0
    }
}
