/**
 * Local storage persistence, preference normalization, and browser capability helpers
 * for OpalDesk Web Cast Receiver.
 */

import {
  clampCustomBitrateMbps,
  DEFAULT_CUSTOM_BITRATE_MBPS,
  isTauriDesktop,
  MAX_CUSTOM_BITRATE_MBPS,
  MIN_CUSTOM_BITRATE_MBPS,
  normalizeCastPrefs,
  PRESET_BITRATE_KBPS,
  sanitizeCustomAdbPath,
  SUPPORTED_DPI_OPTIONS,
  SUPPORTED_FPS_OPTIONS,
  SUPPORTED_RESIZE_MODES,
} from './desktop_bridge.js';
import { t } from './i18n.js';

export const STORAGE_CLIENT_ID = 'opaldesk-web-client-id-v1';
export const STORAGE_DEVICES = 'opaldesk-web-devices-v1';
export const STORAGE_THEME = 'opaldesk-showcase-theme-v1';
export const STORAGE_CODEC = 'opaldesk-web-codec-pref-v1';
export const STORAGE_FPS = 'opaldesk-web-fps-pref-v1';
export const STORAGE_BITRATE = 'opaldesk-web-bitrate-pref-v1';
export const STORAGE_BITRATE_MODE = 'opaldesk-web-bitrate-mode-v1';
export const STORAGE_CUSTOM_BITRATE_MBPS = 'opaldesk-web-custom-bitrate-mbps-v1';
export const STORAGE_RESIZE_MODE = 'opaldesk-web-resize-mode-v1';
export const STORAGE_LIVE_DPI = 'opaldesk-web-live-dpi-v1';
export const STORAGE_AUDIO = 'opaldesk-web-audio-pref-v1';
export const STORAGE_HIDE_CURSOR = 'opaldesk-web-hide-cursor-v1';
export const STORAGE_USB_TETHER_BETA = 'opaldesk-usb-tether-beta-v1';
export const STORAGE_CUSTOM_ADB_PATH = 'opaldesk-custom-adb-path-v1';
export const FILE_CHUNK_SIZE = 64 * 1024;
export const REVEAL_HANDLE_HOVER_DELAY_MS = 180;

const $ = (id) => document.getElementById(id);

export function loadCodecPref() {
  try {
    return localStorage.getItem(STORAGE_CODEC) || '';
  } catch {
    return '';
  }
}

export function saveCodecPref(codec) {
  try {
    if (codec === 'hevc' || codec === 'h264') {
      localStorage.setItem(STORAGE_CODEC, codec);
    }
  } catch {
    // Ignore storage restriction
  }
}

export function loadCustomBitrateMbps() {
  try {
    const raw = localStorage.getItem(STORAGE_CUSTOM_BITRATE_MBPS);
    if (!raw) return DEFAULT_CUSTOM_BITRATE_MBPS;
    return clampCustomBitrateMbps(raw).mbps;
  } catch {
    return DEFAULT_CUSTOM_BITRATE_MBPS;
  }
}

export function saveCustomBitrateMbps(mbps) {
  try {
    const res = clampCustomBitrateMbps(mbps);
    localStorage.setItem(STORAGE_CUSTOM_BITRATE_MBPS, String(res.mbps));
  } catch {
    // Ignore storage restriction
  }
}

export function loadLocalCastPrefs() {
  try {
    const rawAudio = localStorage.getItem(STORAGE_AUDIO);
    const rawHideCursor = localStorage.getItem(STORAGE_HIDE_CURSOR);
    const rawMode = localStorage.getItem(STORAGE_BITRATE_MODE) || '';
    const rawResizeMode = localStorage.getItem(STORAGE_RESIZE_MODE) || '';
    const rawLiveDpi = Number(localStorage.getItem(STORAGE_LIVE_DPI) || 0);
    return normalizeCastPrefs({
      preferredCodec: loadCodecPref(),
      targetFps: Number(localStorage.getItem(STORAGE_FPS) || 0),
      bitrateKbps: Number(localStorage.getItem(STORAGE_BITRATE) || 0),
      bitrateMode: rawMode,
      resizeMode: rawResizeMode,
      liveDpi: rawLiveDpi,
      audioEnabled: rawAudio === null ? null : rawAudio === '1',
      hideLocalCursor: rawHideCursor === null ? null : rawHideCursor === '1',
    });
  } catch {
    return normalizeCastPrefs();
  }
}

export function saveLocalCastPrefs(patch = {}) {
  try {
    if (patch.preferredCodec === 'hevc' || patch.preferredCodec === 'h264') {
      localStorage.setItem(STORAGE_CODEC, patch.preferredCodec);
    }
    if (typeof patch.targetFps === 'number' && SUPPORTED_FPS_OPTIONS.includes(patch.targetFps)) {
      localStorage.setItem(STORAGE_FPS, String(patch.targetFps));
    }
    if (patch.bitrateMode === 'auto' || patch.bitrateMode === 'preset' || patch.bitrateMode === 'custom') {
      localStorage.setItem(STORAGE_BITRATE_MODE, patch.bitrateMode);
    }
    if (typeof patch.bitrateKbps === 'number') {
      if (patch.bitrateKbps === 0) {
        localStorage.setItem(STORAGE_BITRATE, '0');
      } else if (patch.bitrateKbps >= 2500 && patch.bitrateKbps <= MAX_CUSTOM_BITRATE_MBPS * 1000) {
        localStorage.setItem(STORAGE_BITRATE, String(Math.round(patch.bitrateKbps)));
      }
    }
    if (typeof patch.resizeMode === 'string' && SUPPORTED_RESIZE_MODES.includes(patch.resizeMode)) {
      localStorage.setItem(STORAGE_RESIZE_MODE, patch.resizeMode);
    }
    if (typeof patch.liveDpi === 'number' && SUPPORTED_DPI_OPTIONS.includes(patch.liveDpi)) {
      localStorage.setItem(STORAGE_LIVE_DPI, String(patch.liveDpi));
    }
    if (typeof patch.audioEnabled === 'boolean') {
      localStorage.setItem(STORAGE_AUDIO, patch.audioEnabled ? '1' : '0');
    }
    if (typeof patch.hideLocalCursor === 'boolean') {
      localStorage.setItem(STORAGE_HIDE_CURSOR, patch.hideLocalCursor ? '1' : '0');
    }
  } catch {
    // Ignore storage restriction
  }
}

export function loadUsbTetherBetaPref() {
  try {
    return localStorage.getItem(STORAGE_USB_TETHER_BETA) === '1';
  } catch {
    return false;
  }
}

export function saveUsbTetherBetaPref(enabled) {
  try {
    localStorage.setItem(STORAGE_USB_TETHER_BETA, enabled ? '1' : '0');
  } catch {
    // Ignore storage restriction
  }
}

export function loadLocalCustomAdbPath() {
  try {
    return sanitizeCustomAdbPath(localStorage.getItem(STORAGE_CUSTOM_ADB_PATH) || '');
  } catch {
    return '';
  }
}

export function saveLocalCustomAdbPath(customPath) {
  try {
    const cleaned = sanitizeCustomAdbPath(customPath);
    if (cleaned) {
      localStorage.setItem(STORAGE_CUSTOM_ADB_PATH, cleaned);
    } else {
      localStorage.removeItem(STORAGE_CUSTOM_ADB_PATH);
    }
  } catch {
    // Ignore storage restriction
  }
}

export function getOrCreateClientId() {
  try {
    let cid = localStorage.getItem(STORAGE_CLIENT_ID);
    if (cid && cid.length >= 12) return cid;
    const arr = new Uint8Array(12);
    crypto.getRandomValues(arr);
    cid = 'web-' + Array.from(arr, (b) => b.toString(16).padStart(2, '0')).join('');
    localStorage.setItem(STORAGE_CLIENT_ID, cid);
    return cid;
  } catch {
    return 'web-' + Math.random().toString(16).slice(2, 14);
  }
}

export function loadSavedDevices() {
  try {
    const raw = localStorage.getItem(STORAGE_DEVICES);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function saveDeviceRecord(rec) {
  try {
    const list = loadSavedDevices().filter((d) => !(d.host === rec.host && d.port === rec.port));
    list.unshift({ ...rec, updatedAt: Date.now() });
    localStorage.setItem(STORAGE_DEVICES, JSON.stringify(list.slice(0, 8)));
  } catch {
    // Ignore storage quota errors
  }
}

export function getSavedToken(host, port) {
  const found = loadSavedDevices().find((d) => d.host === host && Number(d.port) === Number(port));
  return found?.authToken || '';
}

export function detectBrowserName() {
  if (isTauriDesktop()) {
    const ua = navigator.userAgent || '';
    if (ua.includes('Windows')) return 'OpalDesk Windows';
    if (ua.includes('Macintosh')) return 'OpalDesk Desktop (macOS)';
    return 'OpalDesk Desktop';
  }
  const ua = navigator.userAgent || '';
  if (ua.includes('Edg/')) return 'OpalDesk Web (Edge)';
  if (ua.includes('Chrome/')) return 'OpalDesk Web (Chrome)';
  if (ua.includes('Firefox/')) return 'OpalDesk Web (Firefox)';
  if (ua.includes('Safari/')) return 'OpalDesk Web (Safari)';
  return 'OpalDesk Web';
}

export function isSafariBrowser() {
  const ua = navigator.userAgent || '';
  return ua.includes('Safari/') && !ua.includes('Chrome/') && !ua.includes('Chromium/') && !ua.includes('Edg/');
}

export function resolveMergedCastPrefs(desktop) {
  const local = loadLocalCastPrefs();
  const native = desktop.enabled && desktop.clientPrefs
    ? normalizeCastPrefs(desktop.clientPrefs)
    : normalizeCastPrefs();
  return {
    preferredCodec: native.preferredCodec || local.preferredCodec || '',
    targetFps: native.targetFps || local.targetFps || 0,
    bitrateKbps: native.bitrateKbps || local.bitrateKbps || 0,
    bitrateMode: local.bitrateMode || native.bitrateMode || '',
    resizeMode: local.resizeMode || native.resizeMode || 'auto',
    liveDpi: local.liveDpi || native.liveDpi || 0,
    audioEnabled: native.audioEnabled !== null ? native.audioEnabled : local.audioEnabled,
    hideLocalCursor: native.hideLocalCursor !== null ? native.hideLocalCursor : Boolean(local.hideLocalCursor),
  };
}

export function restoreSavedCastPrefsUi(app) {
  const saved = resolveMergedCastPrefs(app.desktop);
  const fpsEl = $('castFpsSelect');
  if (fpsEl && saved.targetFps && fpsEl.querySelector(`option[value="${saved.targetFps}"]`)) {
    fpsEl.value = String(saved.targetFps);
  }
  app.customBitrateMbps = loadCustomBitrateMbps();
  const brEl = $('castBitrateSelect');
  if (brEl) {
    if (saved.bitrateMode === 'auto') {
      app.bitrateMode = 'auto';
      brEl.value = 'auto';
    } else if (saved.bitrateMode === 'custom' || (saved.bitrateKbps > 0 && !PRESET_BITRATE_KBPS.includes(saved.bitrateKbps))) {
      app.bitrateMode = 'custom';
      brEl.value = 'custom';
      if (saved.bitrateKbps > 0) {
        app.customBitrateMbps = clampCustomBitrateMbps(saved.bitrateKbps / 1000).mbps;
      }
    } else if (saved.bitrateKbps && brEl.querySelector(`option[value="${saved.bitrateKbps}"]`)) {
      app.bitrateMode = 'preset';
      brEl.value = String(saved.bitrateKbps);
    } else {
      app.bitrateMode = 'auto';
      brEl.value = 'auto';
    }
  }
  app.resizeMode = SUPPORTED_RESIZE_MODES.includes(saved.resizeMode) ? saved.resizeMode : 'auto';
  app.syncResizePresetUi();
  const dpiEl = $('liveDpiSelect');
  if (dpiEl && dpiEl.querySelector(`option[value="${saved.liveDpi}"]`)) {
    dpiEl.value = String(saved.liveDpi);
  }
  syncBitrateInputsUi(app, 'both');
  const audioEl = $('castAudioCheck');
  if (audioEl && typeof saved.audioEnabled === 'boolean') {
    audioEl.checked = saved.audioEnabled;
  }
  const codecEl = $('castCodecSelect');
  if (codecEl && saved.preferredCodec && codecEl.querySelector(`option[value="${saved.preferredCodec}"]`)) {
    codecEl.value = saved.preferredCodec;
  }
  app.hideLocalCursor = Boolean(saved.hideLocalCursor);
  app.syncLocalCursorUi?.();
}

export function syncBitrateInputsUi(app, scope = 'both') {
  const castSel = $('castBitrateSelect');
  const castWrap = $('castCustomBitrateWrap');
  const castInput = $('castCustomBitrateInput');
  const liveSel = $('liveBitrateSelect');
  const liveWrap = $('liveCustomBitrateWrap');
  const liveInput = $('liveCustomBitrateInput');
  if ((scope === 'both' || scope === 'cast') && castSel) {
    const isCustom = castSel.value === 'custom';
    if (castWrap) castWrap.hidden = !isCustom;
    if (castInput && document.activeElement !== castInput) {
      castInput.value = String(app.customBitrateMbps);
    }
  }
  if ((scope === 'both' || scope === 'live') && liveSel) {
    const isCustom = liveSel.value === 'custom';
    if (liveWrap) liveWrap.hidden = !isCustom;
    if (liveInput && document.activeElement !== liveInput) {
      liveInput.value = String(app.customBitrateMbps);
    }
  }
}

export function resolveSelectedBitrateConfig(app, selectId, inputId, { notifyOnClamp = false } = {}) {
  const sel = $(selectId);
  const val = sel ? sel.value : 'auto';
  if (val === 'auto') {
    return { mode: 'auto', bitrateKbps: 0, mbps: 0 };
  }
  if (val === 'custom') {
    const inputEl = $(inputId);
    const raw = inputEl ? inputEl.value : app.customBitrateMbps;
    const res = clampCustomBitrateMbps(raw);
    app.customBitrateMbps = res.mbps;
    if (inputEl) inputEl.value = String(res.mbps);
    saveCustomBitrateMbps(res.mbps);
    if (notifyOnClamp && res.clamped) {
      app.showToast(t('cast.customBitrateClamped', {
        min: MIN_CUSTOM_BITRATE_MBPS,
        max: MAX_CUSTOM_BITRATE_MBPS,
        mbps: res.mbps,
      }));
    }
    return { mode: 'custom', bitrateKbps: res.kbps, mbps: res.mbps };
  }
  const presetKbps = Number(val) || 0;
  if (presetKbps <= 0) {
    return { mode: 'auto', bitrateKbps: 0, mbps: 0 };
  }
  return { mode: 'preset', bitrateKbps: presetKbps, mbps: Math.round(presetKbps / 1000) };
}
