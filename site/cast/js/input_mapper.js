/**
 * Browser Mouse, Multi-Touch, Keyboard (DOM KeyboardEvent.code -> Android KeyEvent.KEYCODE_*),
 * Chinese/CJK IME bridge, Clipboard paste, and Drag-and-Drop File Transfer input controller.
 */

import {
  KEY_DOWN,
  KEY_UP,
  MOUSE_BUTTON_DOWN,
  MOUSE_BUTTON_UP,
  MOUSE_HOVER_EXIT,
  MOUSE_MOVE_ABS,
  MOUSE_SCROLL,
  TOUCH_CANCEL,
  TOUCH_DOWN,
  TOUCH_MOVE,
  TOUCH_POINTER_DOWN,
  TOUCH_POINTER_UP,
  TOUCH_UP,
} from './protocol.js';
import {
  clampImeBridgeCoords,
  detectBrowserVirtualKeyboardDevice,
  domButtonToAndroidMask,
  domButtonsToAndroidMask,
  isImeCompositionOrNonAsciiKey,
  isImeToggleShortcut,
  isModifierDomCode,
  mapClientPointToRemote,
  mapDomKeyboardEvent,
  resolveWebInputMode,
  shouldPreventDefaultForKeyEvent,
} from './input_keymap.js';

export {
  META_SHIFT_ON,
  META_ALT_ON,
  META_ALT_LEFT_ON,
  META_SHIFT_LEFT_ON,
  META_CTRL_ON,
  META_CTRL_LEFT_ON,
  META_META_ON,
  META_META_LEFT_ON,
  META_CAPS_LOCK_ON,
  DOM_CODE_TO_ANDROID_KEYCODE,
  isModifierDomCode,
  isImeToggleShortcut,
  isImeCompositionOrNonAsciiKey,
  shouldPreventDefaultForKeyEvent,
  isVirtualKeyboardDevice,
  resolveWebInputMode,
  clampImeBridgeCoords,
  mapDomKeyboardEvent,
} from './input_keymap.js';

const MOVE_COALESCE_INTERVAL_MS = 8; // ~120Hz ceiling; prevents 500-1000Hz gaming mice from flooding Wi-Fi/Binder

function defaultScheduleMoveFlush(cb) {
  if (typeof requestAnimationFrame === 'function') {
    return { kind: 'raf', id: requestAnimationFrame(cb) };
  }
  return { kind: 'timeout', id: setTimeout(cb, MOVE_COALESCE_INTERVAL_MS) };
}

function defaultCancelMoveFlush(token) {
  if (!token) return;
  if (token.kind === 'raf' && typeof cancelAnimationFrame === 'function') {
    cancelAnimationFrame(token.id);
  } else if (token.kind === 'timeout') {
    clearTimeout(token.id);
  }
}

export class OpalInputController {
  constructor(canvas, imeInput, {
    getRemoteSize,
    onMouse,
    onTouch,
    onKey,
    onText,
    onImageClipboard,
    onFileDrop,
    onUserGesture,
    onSoftKeyboardStateChange,
    virtualKeyboardDevice,
    now,
    scheduleMoveFlush,
    cancelMoveFlush,
  }) {
    this.canvas = canvas;
    this.imeInput = imeInput;
    this.getRemoteSize = getRemoteSize;
    this.onMouse = onMouse;
    this.onTouch = onTouch;
    this.onKey = onKey;
    this.onText = onText;
    this.onImageClipboard = onImageClipboard;
    this.onFileDrop = onFileDrop;
    this.onUserGesture = onUserGesture || (() => {});
    this.onSoftKeyboardStateChange = onSoftKeyboardStateChange || (() => {});
    this.now = now || (() => (typeof performance !== 'undefined' ? performance.now() : Date.now()));
    this.scheduleMoveFlush = scheduleMoveFlush || defaultScheduleMoveFlush;
    this.cancelMoveFlush = cancelMoveFlush || defaultCancelMoveFlush;

    this.virtualKeyboardDevice = typeof virtualKeyboardDevice === 'boolean'
      ? virtualKeyboardDevice
      : detectBrowserVirtualKeyboardDevice();
    this.softKeyboardOpen = false;
    this.suppressedEchoChar = '';
    this.suppressedEchoAt = 0;

    this.enabled = false;
    this.composing = false;
    this.imeConsumedCodes = new Set();
    this.lastCompositionEndAt = 0;
    this.lastCapsLockState = false;
    this.primaryLeftAsTouch = true; // Default: left-click & drag emits Android TOUCH for smooth scroll/swipe
    this.primaryTouchPointerId = null;
    this.lastTouchPt = null;
    this.pendingMove = null;
    this.lastMoveSentAt = -MOVE_COALESCE_INTERVAL_MS;
    this.moveFlushHandle = null;
    this.isMac = /Mac|iPhone|iPad/.test(
      (typeof navigator !== 'undefined' && (navigator.platform || navigator.userAgent)) || ''
    );
    this.touchMap = new Map();
    this.boundHandlers = [];
    this.applyImeInputMode(this.virtualKeyboardDevice ? 'none' : 'text');
  }

  queueCoalescedMove(move) {
    this.pendingMove = move;
    const elapsed = this.now() - this.lastMoveSentAt;
    if (elapsed >= MOVE_COALESCE_INTERVAL_MS && this.moveFlushHandle === null) {
      this.flushPendingMove();
      return;
    }
    if (this.moveFlushHandle === null) {
      this.moveFlushHandle = this.scheduleMoveFlush(() => {
        this.moveFlushHandle = null;
        this.flushPendingMove();
      });
    }
  }

  flushPendingMove() {
    if (this.moveFlushHandle !== null) {
      this.cancelMoveFlush(this.moveFlushHandle);
      this.moveFlushHandle = null;
    }
    const move = this.pendingMove;
    this.pendingMove = null;
    if (!move || !this.enabled) return;
    this.lastMoveSentAt = this.now();
    if (move.kind === 'mouse') {
      this.onMouse(MOUSE_MOVE_ABS, move.x, move.y, move.buttons, 0, 0, 0);
    } else if (move.kind === 'touch') {
      this.onTouch(TOUCH_MOVE, 0, move.pointers);
    }
  }

  cancelPendingMove() {
    if (this.moveFlushHandle !== null) {
      this.cancelMoveFlush(this.moveFlushHandle);
      this.moveFlushHandle = null;
    }
    this.pendingMove = null;
  }

  setPrimaryLeftAsTouch(useTouch) {
    this.flushPendingMove();
    if (this.primaryTouchPointerId !== null && this.lastTouchPt) {
      this.onTouch(TOUCH_CANCEL, 0, [{ pointerId: 0, x: this.lastTouchPt.x, y: this.lastTouchPt.y, pressure: 0 }]);
      this.primaryTouchPointerId = null;
    }
    this.primaryLeftAsTouch = Boolean(useTouch);
  }

  attach() {
    const add = (el, type, fn, opts) => {
      el.addEventListener(type, fn, opts);
      this.boundHandlers.push([el, type, fn, opts]);
    };

    add(this.canvas, 'contextmenu', (e) => {
      if (this.enabled) e.preventDefault();
    });

    add(this.canvas, 'pointerdown', (e) => {
      if (!this.enabled || e.pointerType === 'touch') return;
      this.onUserGesture();
      e.preventDefault();
      this.flushPendingMove();
      try { this.canvas.setPointerCapture(e.pointerId); } catch {}
      const pt = this.mapClientPoint(e.clientX, e.clientY);
      if (!pt) return;

      if (this.primaryLeftAsTouch && e.button === 0) {
        this.primaryTouchPointerId = e.pointerId;
        this.lastTouchPt = pt;
        this.onTouch(TOUCH_DOWN, 0, [{ pointerId: 0, x: pt.x, y: pt.y, pressure: 1.0 }]);
      } else {
        this.onMouse(
          MOUSE_BUTTON_DOWN,
          pt.x,
          pt.y,
          domButtonsToAndroidMask(e.buttons) || domButtonToAndroidMask(e.button),
          domButtonToAndroidMask(e.button),
          0,
          0
        );
      }
      this.positionImeBridge(e.clientX, e.clientY);
      this.focusIme();
    });

    add(this.canvas, 'pointermove', (e) => {
      if (!this.enabled || e.pointerType === 'touch') return;
      const pt = this.mapClientPoint(e.clientX, e.clientY);
      if (!pt) return;
      if (this.primaryTouchPointerId === e.pointerId) {
        if ((e.buttons & 1) === 0) {
          this.flushPendingMove();
          this.primaryTouchPointerId = null;
          this.lastTouchPt = pt;
          this.onTouch(TOUCH_UP, 0, [{ pointerId: 0, x: pt.x, y: pt.y, pressure: 0 }]);
          return;
        }
        this.lastTouchPt = pt;
        this.queueCoalescedMove({
          kind: 'touch',
          pointers: [{ pointerId: 0, x: pt.x, y: pt.y, pressure: 1.0 }],
        });
        return;
      }
      this.queueCoalescedMove({
        kind: 'mouse',
        x: pt.x,
        y: pt.y,
        buttons: domButtonsToAndroidMask(e.buttons),
      });
    });

    const endPrimaryTouch = (e, cancelled) => {
      if (this.primaryTouchPointerId !== e.pointerId) return false;
      this.flushPendingMove();
      this.primaryTouchPointerId = null;
      const pt = this.mapClientPoint(e.clientX, e.clientY) || this.lastTouchPt;
      if (pt) {
        this.lastTouchPt = pt;
        this.onTouch(cancelled ? TOUCH_CANCEL : TOUCH_UP, 0, [{ pointerId: 0, x: pt.x, y: pt.y, pressure: 0 }]);
      }
      return true;
    };

    add(this.canvas, 'pointerup', (e) => {
      if (!this.enabled || e.pointerType === 'touch') return;
      e.preventDefault();
      if (endPrimaryTouch(e, false)) return;
      this.flushPendingMove();
      const pt = this.mapClientPoint(e.clientX, e.clientY);
      if (!pt) return;
      this.onMouse(
        MOUSE_BUTTON_UP,
        pt.x,
        pt.y,
        domButtonsToAndroidMask(e.buttons),
        domButtonToAndroidMask(e.button),
        0,
        0
      );
    });

    add(this.canvas, 'pointercancel', (e) => {
      if (e.pointerType === 'touch') return;
      endPrimaryTouch(e, true);
    });

    add(this.canvas, 'lostpointercapture', (e) => {
      if (e.pointerType === 'touch') return;
      endPrimaryTouch(e, false);
    });

    add(this.canvas, 'pointerleave', (e) => {
      if (!this.enabled || e.pointerType === 'touch' || e.buttons !== 0 || this.primaryTouchPointerId !== null) return;
      this.flushPendingMove();
      const pt = this.mapClientPoint(e.clientX, e.clientY);
      if (pt) this.onMouse(MOUSE_HOVER_EXIT, pt.x, pt.y, 0, 0, 0, 0);
    });

    add(
      this.canvas,
      'wheel',
      (e) => {
        if (!this.enabled) return;
        e.preventDefault();
        this.flushPendingMove();
        const pt = this.mapClientPoint(e.clientX, e.clientY);
        if (!pt) return;
        // Android AXIS_VSCROLL positive scrolls up; invert DOM deltaY and normalize tick size
        const scale = e.deltaMode === 1 ? 1.0 : 1 / 48;
        const hScroll = Math.max(-8, Math.min(8, -e.deltaX * scale));
        const vScroll = Math.max(-8, Math.min(8, -e.deltaY * scale));
        this.onMouse(MOUSE_SCROLL, pt.x, pt.y, 0, 0, hScroll, vScroll);
      },
      { passive: false }
    );

    // Multi-touch support for touchscreens / tablets
    const emitTouch = (ev, type) => {
      if (!this.enabled) return;
      this.onUserGesture();
      ev.preventDefault();
      const activeList = [];
      for (let i = 0; i < ev.touches.length; i++) {
        const t = ev.touches[i];
        const pt = this.mapClientPoint(t.clientX, t.clientY);
        if (pt) activeList.push({ pointerId: t.identifier, x: pt.x, y: pt.y, pressure: t.force || 1.0 });
      }
      if (type === 'start') {
        this.flushPendingMove();
        if (ev.changedTouches.length > 0) {
          this.positionImeBridge(ev.changedTouches[0].clientX, ev.changedTouches[0].clientY);
        }
        for (let i = 0; i < ev.changedTouches.length; i++) {
          const ct = ev.changedTouches[i];
          const idx = activeList.findIndex((p) => p.pointerId === ct.identifier);
          if (idx < 0) continue;
          const action = activeList.length === 1 ? TOUCH_DOWN : TOUCH_POINTER_DOWN;
          this.onTouch(action, idx, activeList);
        }
      } else if (type === 'move') {
        if (activeList.length > 0) {
          this.queueCoalescedMove({ kind: 'touch', pointers: activeList });
        }
      } else {
        this.flushPendingMove();
        // On touchend/cancel, include the lifted touch in pointers array per Android MotionEvent semantics
        for (let i = 0; i < ev.changedTouches.length; i++) {
          const ct = ev.changedTouches[i];
          const pt = this.mapClientPoint(ct.clientX, ct.clientY);
          if (!pt) continue;
          const all = activeList.concat([{ pointerId: ct.identifier, x: pt.x, y: pt.y, pressure: 0 }]);
          const idx = all.length - 1;
          const action = type === 'cancel' ? TOUCH_CANCEL : all.length === 1 ? TOUCH_UP : TOUCH_POINTER_UP;
          this.onTouch(action, idx, all);
        }
      }
    };
    add(this.canvas, 'touchstart', (e) => emitTouch(e, 'start'), { passive: false });
    add(this.canvas, 'touchmove', (e) => emitTouch(e, 'move'), { passive: false });
    add(this.canvas, 'touchend', (e) => emitTouch(e, 'end'), { passive: false });
    add(this.canvas, 'touchcancel', (e) => emitTouch(e, 'cancel'), { passive: false });

    // Keyboard & CJK IME bridge
    add(this.imeInput, 'compositionstart', () => {
      this.composing = true;
    });
    add(this.imeInput, 'compositionend', (e) => {
      this.composing = false;
      this.lastCompositionEndAt = typeof performance !== 'undefined' ? performance.now() : Date.now();
      const text = e.data || this.imeInput.value;
      this.imeInput.value = '';
      if (this.enabled && text) {
        this.onText(text, true);
      }
    });
    add(this.imeInput, 'input', () => {
      if (this.composing || !this.enabled) return;
      const val = this.imeInput.value;
      if (val) {
        this.imeInput.value = '';
        const nowMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
        if (val === this.suppressedEchoChar && (nowMs - this.suppressedEchoAt) < 120) {
          this.suppressedEchoChar = '';
          return;
        }
        this.suppressedEchoChar = '';
        // Commit both CJK/non-ASCII punctuation AND mobile/tablet soft-keyboard ASCII/swipe words
        this.onText(val, true);
      }
    });
    add(this.imeInput, 'blur', () => {
      if (this.virtualKeyboardDevice && this.softKeyboardOpen) {
        this.softKeyboardOpen = false;
        this.applyImeInputMode('none');
        this.onSoftKeyboardStateChange(false);
      }
    });
    add(this.imeInput, 'paste', async (e) => {
      if (!this.enabled) return;
      const dt = e.clipboardData;
      if (!dt) return;
      const imgItem = Array.from(dt.items || []).find((it) => it.type && it.type.startsWith('image/png'));
      if (imgItem && this.onImageClipboard) {
        e.preventDefault();
        const file = imgItem.getAsFile();
        if (file) {
          const buf = new Uint8Array(await file.arrayBuffer());
          this.onImageClipboard(buf);
        }
        return;
      }
      const text = dt.getData('text/plain');
      if (text) {
        e.preventDefault();
        this.onText(text, true);
      }
    });

    const handleKey = (ev, action) => {
      if (!this.enabled) return;
      // Do not steal keystrokes if user is typing in modal search/input fields
      const active = document.activeElement;
      if (active && active !== this.imeInput && active !== document.body && ['INPUT', 'TEXTAREA', 'SELECT'].includes(active.tagName)) {
        return;
      }

      if (isImeToggleShortcut(ev)) {
        if (action === KEY_DOWN && ev.code) {
          this.imeConsumedCodes.add(ev.code);
        } else if (action === KEY_UP && ev.code) {
          this.imeConsumedCodes.delete(ev.code);
        }
        return;
      }

      if (this.composing || isImeCompositionOrNonAsciiKey(ev)) {
        if (action === KEY_DOWN && ev.code) {
          this.imeConsumedCodes.add(ev.code);
        }
        return;
      }

      // Guard Safari's duplicate Enter keydown fired right after compositionend commits CJK text
      const nowMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
      if (
        action === KEY_DOWN
        && (ev.code === 'Enter' || ev.code === 'NumpadEnter')
        && (nowMs - this.lastCompositionEndAt) < 50
      ) {
        this.imeConsumedCodes.add(ev.code);
        return;
      }

      if (action === KEY_UP && ev.code && this.imeConsumedCodes.delete(ev.code)) {
        return;
      }

      // Let browser handle paste event cleanly when Ctrl/Cmd+V is pressed inside imeInput
      if (action === KEY_DOWN && (ev.ctrlKey || ev.metaKey) && ev.code === 'KeyV' && active === this.imeInput) {
        return;
      }

      // On macOS, short-pressing CapsLock switches ABC <-> Chinese IME without toggling CapsLock state.
      if (ev.code === 'CapsLock') {
        const capsNow = Boolean(ev.getModifierState && ev.getModifierState('CapsLock'));
        const capsUnchanged = capsNow === this.lastCapsLockState;
        this.lastCapsLockState = capsNow;
        if (this.isMac && capsUnchanged) {
          return;
        }
      }

      const mapped = mapDomKeyboardEvent(ev, this.isMac);
      if (!mapped) return;
      const prevent = shouldPreventDefaultForKeyEvent(ev, {
        imeFocused: active === this.imeInput,
        isModifier: isModifierDomCode(ev.code),
      });
      if (prevent) {
        ev.preventDefault();
      } else if (
        action === KEY_DOWN
        && active === this.imeInput
        && typeof ev.key === 'string'
        && ev.key.length === 1
        && /[\x20-\x7e]/.test(ev.key)
      ) {
        this.suppressedEchoChar = ev.key;
        this.suppressedEchoAt = nowMs;
      }
      this.onKey(action, mapped.androidKeyCode, mapped.metaState, ev.repeat ? 1 : 0);
    };
    add(window, 'keydown', (e) => handleKey(e, KEY_DOWN));
    add(window, 'keyup', (e) => handleKey(e, KEY_UP));
    add(window, 'focus', () => {
      if (!this.enabled) return;
      const active = document.activeElement;
      if (!active || active === document.body || !['INPUT', 'TEXTAREA', 'SELECT'].includes(active.tagName)) {
        this.focusIme();
      }
    });

    // Drag & Drop File Upload onto Canvas
    add(this.canvas, 'dragover', (e) => {
      if (!this.enabled) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = 'copy';
    });
    add(this.canvas, 'drop', (e) => {
      if (!this.enabled) return;
      e.preventDefault();
      const files = Array.from(e.dataTransfer?.files || []);
      if (files.length > 0 && this.onFileDrop) {
        this.onFileDrop(files);
      }
    });
  }

  applyImeInputMode(mode) {
    if (!this.imeInput) return;
    if (typeof this.imeInput.setAttribute === 'function') {
      this.imeInput.setAttribute('inputmode', mode);
    }
    try {
      this.imeInput.inputMode = mode;
    } catch {
      // Ignore read-only stub in tests
    }
  }

  setEnabled(enabled) {
    this.enabled = Boolean(enabled);
    if (!this.enabled) {
      this.cancelPendingMove();
      this.composing = false;
      this.suppressedEchoChar = '';
      this.imeConsumedCodes.clear();
      this.dismissSoftKeyboard();
      return;
    }
    if (this.virtualKeyboardDevice) {
      this.applyImeInputMode('none');
    } else {
      this.applyImeInputMode('text');
      this.focusIme();
    }
  }

  /**
   * Passive background focus for desktop hardware CJK IME.
   * On tablets / touch devices (`virtualKeyboardDevice === true`), this intentionally avoids
   * focusing `#imeBridge` unless the user or remote app explicitly requested the soft keyboard,
   * preventing the virtual keyboard from popping up on every tap or toolbar button click.
   */
  focusIme() {
    if (!this.enabled || !this.imeInput) return;
    if (this.virtualKeyboardDevice && !this.softKeyboardOpen) return;
    if (typeof document !== 'undefined' && document.activeElement !== this.imeInput) {
      this.imeInput.focus({ preventScroll: true });
    }
  }

  openSoftKeyboard(androidInputType = 0) {
    if (!this.enabled || !this.imeInput) return false;
    const mode = resolveWebInputMode(androidInputType);
    this.applyImeInputMode(mode);
    this.softKeyboardOpen = true;
    this.imeInput.focus({ preventScroll: true });
    this.onSoftKeyboardStateChange(true);
    return true;
  }

  dismissSoftKeyboard() {
    const wasOpen = this.softKeyboardOpen;
    this.softKeyboardOpen = false;
    if (this.imeInput && this.virtualKeyboardDevice) {
      this.applyImeInputMode('none');
      if (typeof document !== 'undefined' && document.activeElement === this.imeInput) {
        this.imeInput.blur();
      }
    }
    if (wasOpen) {
      this.onSoftKeyboardStateChange(false);
    }
  }

  toggleSoftKeyboard() {
    if (this.softKeyboardOpen) {
      this.dismissSoftKeyboard();
      return false;
    }
    return this.openSoftKeyboard(0);
  }

  positionImeBridge(clientX, clientY) {
    if (!this.imeInput || !this.imeInput.style || typeof window === 'undefined') return;
    const { left, top } = clampImeBridgeCoords(clientX, clientY, window.innerWidth, window.innerHeight);
    this.imeInput.style.left = `${left}px`;
    this.imeInput.style.top = `${top}px`;
  }

  handleRemoteImeRequest(show, inputType = 0) {
    if (!this.enabled) return;
    if (show) {
      if (this.virtualKeyboardDevice) {
        this.openSoftKeyboard(inputType);
      } else {
        this.focusIme();
      }
    } else if (this.virtualKeyboardDevice) {
      this.dismissSoftKeyboard();
    }
  }

  mapClientPoint(clientX, clientY) {
    const rect = this.canvas.getBoundingClientRect();
    const { width: rw, height: rh } = this.getRemoteSize();
    return mapClientPointToRemote(rect, rw, rh, clientX, clientY);
  }

  detach() {
    this.cancelPendingMove();
    for (const [el, type, fn, opts] of this.boundHandlers) {
      el.removeEventListener(type, fn, opts);
    }
    this.boundHandlers.length = 0;
  }
}
