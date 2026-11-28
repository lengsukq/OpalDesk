/**
 * Pure Companion Hub core state & helpers:
 * - Notification store (snapshot/posted/removed, OTP freshness window, action filtering)
 * - Call Control HUD visibility & temporary-dismiss session tracking
 * - T9 Quick Dialer sanitization & SMS reject templates
 * - Handoff metadata normalization
 *
 * Shared by Windows Tauri Desktop & Web Cast. Zero DOM or Tauri dependencies.
 */

import { getSmsRejectTemplates, t } from './i18n.js';

export const OTP_FRESHNESS_WINDOW_MS = 3 * 60 * 1000;
export const MAX_STORED_NOTIFICATIONS = 60;

export const DEFAULT_SMS_REJECT_TEMPLATES = Object.freeze([
  '我现在正在开会，稍后给您回电。',
  '正在忙，暂时不方便接听，稍后联系您。',
  '请发消息联系，谢谢。',
]);

export { getSmsRejectTemplates };

export const T9_DIALPAD_KEYS = Object.freeze([
  { digit: '1', letters: '' },
  { digit: '2', letters: 'ABC' },
  { digit: '3', letters: 'DEF' },
  { digit: '4', letters: 'GHI' },
  { digit: '5', letters: 'JKL' },
  { digit: '6', letters: 'MNO' },
  { digit: '7', letters: 'PQRS' },
  { digit: '8', letters: 'TUV' },
  { digit: '9', letters: 'WXYZ' },
  { digit: '*', letters: '' },
  { digit: '0', letters: '+' },
  { digit: '#', letters: '' },
]);

const OTP_KEYWORD_RE = /(验证码|校验码|动态码|确认码|安全码|口令|code|otp|pin|passcode|verification)/i;
const OTP_CODE_RE = /(?<![0-9a-zA-Z])(\d{4,8})(?![0-9a-zA-Z])/g;

/**
 * Fallback client-side OTP extraction when Android didn't populate `otpCode`.
 */
export function extractOtpCode(title = '', text = '') {
  const combined = `${String(title || '')} ${String(text || '')}`.trim();
  if (!combined || !OTP_KEYWORD_RE.test(combined)) return '';
  let best = '';
  let match;
  OTP_CODE_RE.lastIndex = 0;
  while ((match = OTP_CODE_RE.exec(combined)) !== null) {
    const candidate = match[1];
    // Skip plausible years like 2025/2026 unless 6 digits
    if (candidate.length === 4 && Number(candidate) >= 1990 && Number(candidate) <= 2099) {
      continue;
    }
    if (candidate.length === 6) return candidate;
    if (!best) best = candidate;
  }
  return best;
}

/**
 * Returns non-blank interactive notification action buttons (SSOT with macOS `displayActions`).
 */
export function filterDisplayActions(actions) {
  if (!Array.isArray(actions)) return [];
  return actions
    .filter((a) => a && typeof a.label === 'string' && a.label.trim().length > 0)
    .map((a) => ({
      index: Number.isFinite(Number(a.index)) ? Number(a.index) : 0,
      label: a.label.trim(),
    }));
}

/**
 * Pure stateful Android notification mirror store.
 */
export function createNotificationStore({ maxItems = MAX_STORED_NOTIFICATIONS } = {}) {
  let items = [];
  let latestOtp = null; // { code, key, appLabel, receivedAtMs }

  function enrichItem(raw) {
    const otpCode = (raw.otpCode && String(raw.otpCode).trim()) || extractOtpCode(raw.title, raw.text);
    return {
      key: String(raw.key || ''),
      pkg: String(raw.pkg || ''),
      appLabel: String(raw.appLabel || raw.pkg || ''),
      title: String(raw.title || ''),
      text: String(raw.text || ''),
      postTimeMs: Number(raw.postTimeMs || 0),
      clearable: raw.clearable !== false,
      canReply: Boolean(raw.canReply),
      replyLabel: String(raw.replyLabel || t('companion.notifReplyDefault')),
      otpCode,
      actions: filterDisplayActions(raw.actions),
    };
  }

  function sortAndTrim() {
    items.sort((a, b) => (b.postTimeMs - a.postTimeMs) || a.key.localeCompare(b.key));
    if (items.length > maxItems) {
      items = items.slice(0, maxItems);
    }
  }

  function trackOtp(item, nowMs) {
    if (!item.otpCode) return;
    const ts = item.postTimeMs > 0 ? item.postTimeMs : nowMs;
    if (!latestOtp || ts >= latestOtp.receivedAtMs || latestOtp.code !== item.otpCode) {
      latestOtp = {
        code: item.otpCode,
        key: item.key,
        appLabel: item.appLabel || item.pkg || t('companion.otpBadge'),
        receivedAtMs: nowMs,
      };
    }
  }

  function applyEvent(event, nowMs = Date.now()) {
    if (!event || !event.action) return { added: [], removedKey: '' };
    if (event.action === 'snapshot') {
      items = (event.items || []).map(enrichItem).filter((i) => i.key);
      sortAndTrim();
      const newestWithOtp = items.find((i) => i.otpCode);
      if (newestWithOtp && (nowMs - (newestWithOtp.postTimeMs || nowMs) <= OTP_FRESHNESS_WINDOW_MS)) {
        trackOtp(newestWithOtp, nowMs);
      }
      return { added: [], removedKey: '' };
    }
    if (event.action === 'posted') {
      const added = [];
      for (const raw of event.items || []) {
        const item = enrichItem(raw);
        if (!item.key) continue;
        const idx = items.findIndex((x) => x.key === item.key);
        if (idx >= 0) {
          items[idx] = item;
        } else {
          items.unshift(item);
          added.push(item);
        }
        trackOtp(item, nowMs);
      }
      sortAndTrim();
      return { added, removedKey: '' };
    }
    if (event.action === 'removed') {
      const targetKey = String(event.key || event.items?.[0]?.key || '');
      if (targetKey) {
        items = items.filter((x) => x.key !== targetKey);
      }
      return { added: [], removedKey: targetKey };
    }
    return { added: [], removedKey: '' };
  }

  function dismissLocal(key) {
    if (!key) return;
    items = items.filter((x) => x.key !== key);
  }

  function clearLocal() {
    items = items.filter((x) => !x.clearable);
  }

  function clearAll() {
    items = [];
    latestOtp = null;
  }

  function getItems() {
    return items.slice();
  }

  function getLatestOtp(nowMs = Date.now()) {
    if (!latestOtp) return null;
    if (nowMs - latestOtp.receivedAtMs > OTP_FRESHNESS_WINDOW_MS) {
      return null;
    }
    return { ...latestOtp };
  }

  return {
    applyEvent,
    dismissLocal,
    clearLocal,
    clearAll,
    getItems,
    getLatestOtp,
  };
}

/**
 * Formats an active offhook call duration from `startedAtMs` as `MM:SS` or `HH:MM:SS`.
 */
export function formatCallDuration(startedAtMs, nowMs = Date.now()) {
  const start = Number(startedAtMs || 0);
  if (start <= 0) return '00:00';
  const elapsedSec = Math.max(0, Math.floor((nowMs - start) / 1000));
  const hrs = Math.floor(elapsedSec / 3600);
  const mins = Math.floor((elapsedSec % 3600) / 60);
  const secs = elapsedSec % 60;
  const mm = String(mins).padStart(2, '0');
  const ss = String(secs).padStart(2, '0');
  if (hrs > 0) {
    return `${String(hrs).padStart(2, '0')}:${mm}:${ss}`;
  }
  return `${mm}:${ss}`;
}

/**
 * Sanitizes dialer phone number input to standard telephony characters.
 */
export function sanitizeDialNumber(raw) {
  return String(raw || '').replace(/[^0-9+*#,-]/g, '').slice(0, 32);
}

/**
 * Stable identifier for a live call session, used so "temporary dismiss" only hides
 * the current call and automatically re-appears for the next incoming/outgoing call.
 */
export function callSessionKey(callState) {
  if (!callState || callState.state === 'idle') return '';
  return `${callState.number || callState.contactName || 'unknown'}#${callState.startedAtMs || 0}`;
}

export function shouldShowCallHud(callState, dismissedSessionKey = '') {
  if (!callState || !callState.state || callState.state === 'idle') return false;
  const key = callSessionKey(callState);
  if (!key) return false;
  return key !== dismissedSessionKey;
}

/**
 * Extracts Handoff foreground app summary from `0x10 DEVICE_STATE`.
 */
export function resolveHandoffInfo(deviceState) {
  if (!deviceState) return null;
  const pkg = String(deviceState.foregroundApp || deviceState.foregroundPkg || '').trim();
  const label = String(deviceState.foregroundAppLabel || deviceState.fullscreenTitle || '').trim();
  if (!pkg && !label) return null;
  return {
    pkg,
    label: label || pkg,
    canPopout: Boolean(pkg),
  };
}
