/**
 * Pure DOM-to-Android keycode/modifier/button/IME/viewport mapping helpers.
 * Re-exported by `input_mapper.js` to preserve 100% backward-compatible imports.
 */

import {
  BUTTON_BACK,
  BUTTON_FORWARD,
  BUTTON_PRIMARY,
  BUTTON_SECONDARY,
  BUTTON_TERTIARY,
} from './protocol.js';

// Android android.view.KeyEvent META_* masks
export const META_SHIFT_ON = 0x00000001;
export const META_ALT_ON = 0x00000002;
export const META_ALT_LEFT_ON = 0x00000010;
export const META_SHIFT_LEFT_ON = 0x00000040;
export const META_CTRL_ON = 0x00001000;
export const META_CTRL_LEFT_ON = 0x00002000;
export const META_META_ON = 0x00010000;
export const META_META_LEFT_ON = 0x00020000;
export const META_CAPS_LOCK_ON = 0x00100000;

// DOM KeyboardEvent.code -> Android KeyEvent.KEYCODE_*
export const DOM_CODE_TO_ANDROID_KEYCODE = {
  Digit0: 7, Digit1: 8, Digit2: 9, Digit3: 10, Digit4: 11,
  Digit5: 12, Digit6: 13, Digit7: 14, Digit8: 15, Digit9: 16,
  KeyA: 29, KeyB: 30, KeyC: 31, KeyD: 32, KeyE: 33, KeyF: 34, KeyG: 35,
  KeyH: 36, KeyI: 37, KeyJ: 38, KeyK: 39, KeyL: 40, KeyM: 41, KeyN: 42,
  KeyO: 43, KeyP: 44, KeyQ: 45, KeyR: 46, KeyS: 47, KeyT: 48, KeyU: 49,
  KeyV: 50, KeyW: 51, KeyX: 52, KeyY: 53, KeyZ: 54,
  Comma: 55, Period: 56, AltLeft: 57, AltRight: 58, ShiftLeft: 59, ShiftRight: 60,
  Tab: 61, Space: 62, Enter: 66, Backspace: 67, Backquote: 68, Minus: 69,
  Equal: 70, BracketLeft: 71, BracketRight: 72, Backslash: 73, Semicolon: 74,
  Quote: 75, Slash: 76,
  Escape: 111, Delete: 112, ControlLeft: 113, ControlRight: 114, CapsLock: 115,
  MetaLeft: 117, MetaRight: 118,
  Home: 122, End: 123, Insert: 124, PageUp: 92, PageDown: 93,
  ArrowUp: 19, ArrowDown: 20, ArrowLeft: 21, ArrowRight: 22,
  F1: 131, F2: 132, F3: 133, F4: 134, F5: 135, F6: 136,
  F7: 137, F8: 138, F9: 139, F10: 140, F11: 141, F12: 142,
  Numpad0: 144, Numpad1: 145, Numpad2: 146, Numpad3: 147, Numpad4: 148,
  Numpad5: 149, Numpad6: 150, Numpad7: 151, Numpad8: 152, Numpad9: 153,
  NumpadDivide: 154, NumpadMultiply: 155, NumpadSubtract: 156, NumpadAdd: 157,
  NumpadDecimal: 158, NumpadEnter: 160,
};

const MAC_CMD_TO_CTRL_CODES = new Set([
  'KeyA', 'KeyB', 'KeyC', 'KeyF', 'KeyI', 'KeyK', 'KeyL', 'KeyN',
  'KeyO', 'KeyP', 'KeyR', 'KeyS', 'KeyT', 'KeyU', 'KeyV', 'KeyW',
  'KeyX', 'KeyY', 'KeyZ', 'Equal', 'Minus', 'Digit0',
]);

const MODIFIER_DOM_CODES = new Set([
  'ShiftLeft', 'ShiftRight',
  'ControlLeft', 'ControlRight',
  'AltLeft', 'AltRight',
  'MetaLeft', 'MetaRight',
  'CapsLock',
]);

export function isModifierDomCode(code) {
  return MODIFIER_DOM_CODES.has(code);
}

/**
 * Detects OS / CJK IME mode-switching shortcuts that must never be swallowed by preventDefault()
 * or forwarded to Android (Ctrl+Space, Ctrl+Alt+Space, Win/Cmd+Space, Ctrl+. punctuation toggle).
 */
export function isImeToggleShortcut(ev) {
  if (!ev) return false;
  if (ev.ctrlKey && !ev.metaKey && ev.code === 'Space') return true;
  if (ev.metaKey && !ev.ctrlKey && !ev.altKey && ev.code === 'Space') return true;
  if (ev.ctrlKey && !ev.metaKey && !ev.altKey && !ev.shiftKey && ev.code === 'Period') return true;
  return false;
}

/**
 * Detects events belonging to active IME composition, dead keys, or direct full-width CJK punctuation
 * (such as `，`, `。`, `？`, `！`, `、`, `￥`) that must flow into `#imeBridge` and commit via INPUT_TEXT.
 */
export function isImeCompositionOrNonAsciiKey(ev) {
  if (!ev) return false;
  if (ev.isComposing || ev.keyCode === 229) return true;
  if (ev.key === 'Process' || ev.key === 'Dead' || ev.key === 'Unidentified') return true;
  if (typeof ev.key === 'string' && ev.key.length === 1 && /[^\x20-\x7e]/.test(ev.key)) {
    return true;
  }
  return false;
}

/**
 * Decides whether the browser default action should be prevented for a forwarded key event.
 * Never calls preventDefault() on standalone modifiers, IME shortcuts, or printable characters
 * inside the focused IME bridge textarea so Shift/CapsLock/Ctrl+Space switching and CJK composition
 * work natively across Chrome, Edge, Firefox, and Safari.
 */
export function shouldPreventDefaultForKeyEvent(ev, { imeFocused = false, isModifier = false } = {}) {
  if (!ev) return false;
  if (isModifier || isImeToggleShortcut(ev) || isImeCompositionOrNonAsciiKey(ev)) {
    return false;
  }
  const isPrintableInIme = imeFocused
    && !ev.ctrlKey
    && !ev.metaKey
    && !ev.altKey
    && typeof ev.key === 'string'
    && ev.key.length === 1;
  if (isPrintableInIme) {
    return false;
  }
  return true;
}

/**
 * Detects touch / tablet / mobile environments where focusing a text field summons an on-screen
 * virtual keyboard (Android tablets/phones, iOS, iPadOS desktop-class Safari `MacIntel` with
 * `maxTouchPoints > 1`, and primary coarse-pointer tablets), vs. desktop PC browsers where
 * `#imeBridge` can remain silently focused in the background for hardware CJK IME composition.
 */
export function isVirtualKeyboardDevice({
  userAgent = '',
  platform = '',
  maxTouchPoints = 0,
  coarsePointer = false,
  anyCoarsePointer = false,
  uaDataMobile = false,
} = {}) {
  if (uaDataMobile) return true;
  const ua = String(userAgent || '');
  const plat = String(platform || '');
  const touchPts = Number(maxTouchPoints || 0);
  if (/Android|iPhone|iPad|iPod|Mobile|Tablet|Silk|HarmonyOS|OpenHarmony/i.test(ua)) {
    return true;
  }
  // iPadOS 13+ desktop Safari ('MacIntel') and Android/HarmonyOS Chrome "Request Desktop Site"
  // ('Linux x86_64' / 'Linux armv81') report desktop platforms alongside multi-touch support.
  if ((plat === 'MacIntel' || /^Linux/i.test(plat)) && touchPts > 1) {
    return true;
  }
  if (coarsePointer) {
    return true;
  }
  if (anyCoarsePointer && !/^Win/i.test(plat)) {
    return true;
  }
  return touchPts > 0 && !/Win32|Win64|Linux x86_64|MacIntel/.test(plat);
}

/**
 * Maps Android `EditorInfo.inputType` flags onto HTML `inputmode` values when summoning
 * a tablet/mobile virtual keyboard.
 */
export function resolveWebInputMode(androidInputType = 0) {
  const raw = Number(androidInputType || 0);
  const cls = raw & 0x0f;
  const variation = raw & 0x0ff0;
  if (cls === 2) {
    // TYPE_CLASS_NUMBER (TYPE_NUMBER_FLAG_DECIMAL = 0x2000)
    return (raw & 0x2000) !== 0 ? 'decimal' : 'numeric';
  }
  if (cls === 3) {
    // TYPE_CLASS_PHONE
    return 'tel';
  }
  if (cls === 1) {
    if (variation === 0x10) return 'url';
    if (variation === 0x20 || variation === 0xd0) return 'email';
  }
  return 'text';
}

export function detectBrowserVirtualKeyboardDevice() {
  if (typeof navigator === 'undefined') return false;
  const hasMatchMedia = typeof window !== 'undefined' && typeof window.matchMedia === 'function';
  const coarse = hasMatchMedia && window.matchMedia('(pointer: coarse)').matches;
  const anyCoarse = hasMatchMedia && window.matchMedia('(any-pointer: coarse)').matches;
  const uaMobile = Boolean(navigator.userAgentData && navigator.userAgentData.mobile);
  return isVirtualKeyboardDevice({
    userAgent: navigator.userAgent || '',
    platform: navigator.platform || '',
    maxTouchPoints: navigator.maxTouchPoints || 0,
    coarsePointer: coarse,
    anyCoarsePointer: anyCoarse,
    uaDataMobile: uaMobile,
  });
}

export function clampImeBridgeCoords(clientX, clientY, viewportWidth, viewportHeight, margin = 16) {
  const maxX = Math.max(margin, (viewportWidth || 0) - margin);
  const maxY = Math.max(margin, (viewportHeight || 0) - margin);
  return {
    left: Math.round(Math.min(Math.max(margin, clientX), maxX)),
    top: Math.round(Math.min(Math.max(margin, clientY), maxY)),
  };
}

export function mapDomKeyboardEvent(ev, mapMacCommandToCtrl = true) {
  let androidKeyCode = DOM_CODE_TO_ANDROID_KEYCODE[ev.code];
  if (!androidKeyCode) {
    if (ev.key === 'Backspace') androidKeyCode = 67;
    else if (ev.key === 'Enter') androidKeyCode = 66;
    else return null;
  }

  const promoteCmd = mapMacCommandToCtrl && ev.metaKey && !ev.ctrlKey && MAC_CMD_TO_CTRL_CODES.has(ev.code);
  let meta = 0;
  if (ev.shiftKey) meta |= META_SHIFT_ON | META_SHIFT_LEFT_ON;
  if (ev.altKey) meta |= META_ALT_ON | META_ALT_LEFT_ON;
  if (ev.ctrlKey || promoteCmd) meta |= META_CTRL_ON | META_CTRL_LEFT_ON;
  if (ev.metaKey && !promoteCmd) meta |= META_META_ON | META_META_LEFT_ON;
  if (ev.getModifierState && ev.getModifierState('CapsLock')) meta |= META_CAPS_LOCK_ON;

  return { androidKeyCode, metaState: meta };
}

export function domButtonToAndroidMask(button) {
  switch (button) {
    case 0: return BUTTON_PRIMARY;
    case 2: return BUTTON_SECONDARY;
    case 1: return BUTTON_TERTIARY;
    case 3: return BUTTON_BACK;
    case 4: return BUTTON_FORWARD;
    default: return BUTTON_PRIMARY;
  }
}

export function domButtonsToAndroidMask(buttons) {
  let mask = 0;
  if (buttons & 1) mask |= BUTTON_PRIMARY;
  if (buttons & 2) mask |= BUTTON_SECONDARY;
  if (buttons & 4) mask |= BUTTON_TERTIARY;
  if (buttons & 8) mask |= BUTTON_BACK;
  if (buttons & 16) mask |= BUTTON_FORWARD;
  return mask;
}

/**
 * Maps a DOM client point onto remote framebuffer coordinates accounting for `object-fit: contain`.
 */
export function mapClientPointToRemote(rect, rw, rh, clientX, clientY) {
  if (!rect || rect.width <= 0 || rect.height <= 0 || rw <= 0 || rh <= 0) return null;
  const elemRatio = rect.width / rect.height;
  const videoRatio = rw / rh;
  let drawW = rect.width;
  let drawH = rect.height;
  let offsetX = 0;
  let offsetY = 0;
  if (elemRatio > videoRatio) {
    drawW = rect.height * videoRatio;
    offsetX = (rect.width - drawW) / 2;
  } else {
    drawH = rect.width / videoRatio;
    offsetY = (rect.height - drawH) / 2;
  }
  const relX = (clientX - rect.left - offsetX) / drawW;
  const relY = (clientY - rect.top - offsetY) / drawH;
  const x = Math.max(0, Math.min(rw - 1, relX * rw));
  const y = Math.max(0, Math.min(rh - 1, relY * rh));
  return { x, y };
}
