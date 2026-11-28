/**
 * Pure policy, certificate trust evaluation, preference normalization,
 * query building, and adaptive bitrate helpers for OpalDesk Desktop Bridge.
 * Re-exported by `desktop_bridge.js` to preserve 100% backward-compatible imports.
 */

export const MIN_FINGERPRINT_PREFIX_LEN = 16;
export const FULL_SHA256_HEX_LEN = 64;

export function resolveTauriInvoke(win = globalThis.window) {
  if (!win || typeof win !== 'object') return null;
  if (typeof win.__TAURI__?.core?.invoke === 'function') {
    return win.__TAURI__.core.invoke.bind(win.__TAURI__.core);
  }
  if (typeof win.__TAURI_INTERNALS__?.invoke === 'function') {
    return win.__TAURI_INTERNALS__.invoke.bind(win.__TAURI_INTERNALS__);
  }
  return null;
}

export function isTauriDesktop(win = globalThis.window) {
  return resolveTauriInvoke(win) !== null;
}

export function normalizeHexFingerprint(raw) {
  const trimmed = String(raw || '').trim();
  if (!trimmed) return '';
  const body = trimmed.replace(/^sha256:/i, '');
  let out = '';
  for (const ch of body) {
    if (/^[0-9a-fA-F]$/.test(ch)) {
      out += ch.toLowerCase();
    } else if (ch === ':' || ch === '-' || /\s/.test(ch)) {
      continue;
    } else {
      return '';
    }
  }
  return out;
}

export function formatShortFingerprint(raw) {
  const norm = normalizeHexFingerprint(raw);
  if (!norm) return '';
  const head = norm.slice(0, 16).toUpperCase();
  const groups = [];
  for (let i = 0; i < head.length; i += 4) {
    groups.push(head.slice(i, i + 4));
  }
  return groups.join('-');
}

export function fingerprintMatchesPin(actualFullHex, expectedPin) {
  const actual = normalizeHexFingerprint(actualFullHex);
  const expected = normalizeHexFingerprint(expectedPin);
  if (actual.length !== FULL_SHA256_HEX_LEN || expected.length < MIN_FINGERPRINT_PREFIX_LEN) {
    return false;
  }
  if (expected.length === FULL_SHA256_HEX_LEN) {
    return actual === expected;
  }
  if (expected.length < FULL_SHA256_HEX_LEN) {
    return actual.startsWith(expected);
  }
  return false;
}

export function evaluateCertificateTrust({
  host,
  port,
  probedFingerprint,
  trustedDevices = [],
  mdnsDevices = [],
  explicitPin = '',
}) {
  const actual = normalizeHexFingerprint(probedFingerprint);
  if (actual.length !== FULL_SHA256_HEX_LEN) {
    return {
      status: 'invalid',
      reason: 'invalid_probed_cert',
      actualFingerprint: actual,
      actualShort: formatShortFingerprint(actual),
    };
  }

  const cleanHost = String(host || '').trim();
  const numPort = Number(port) || 19878;
  const normExplicit = normalizeHexFingerprint(explicitPin);

  if (normExplicit.length >= MIN_FINGERPRINT_PREFIX_LEN) {
    if (fingerprintMatchesPin(actual, normExplicit)) {
      return {
        status: 'trusted',
        reason: 'explicit_pin',
        actualFingerprint: actual,
        actualShort: formatShortFingerprint(actual),
      };
    }
    return {
      status: 'mismatch',
      reason: 'explicit_pin_mismatch',
      expectedShort: formatShortFingerprint(normExplicit),
      actualFingerprint: actual,
      actualShort: formatShortFingerprint(actual),
    };
  }

  const matchedTrusted = trustedDevices.find((d) =>
    fingerprintMatchesPin(actual, d?.certFingerprint || '')
  );
  if (matchedTrusted) {
    return {
      status: 'trusted',
      reason: 'trust_store',
      matchedDevice: matchedTrusted,
      actualFingerprint: actual,
      actualShort: formatShortFingerprint(actual),
    };
  }

  const conflictingHostEntry = trustedDevices.find(
    (d) => d?.host === cleanHost && Number(d?.port) === numPort && d?.certFingerprint
  );
  const matchingMdns = mdnsDevices.find(
    (m) =>
      m?.host === cleanHost &&
      Number(m?.port || 19878) === numPort &&
      fingerprintMatchesPin(actual, m?.fingerprintPrefix || '')
  );

  if (conflictingHostEntry) {
    return {
      status: 'changed',
      reason: 'host_certificate_changed',
      previousDevice: conflictingHostEntry,
      mdnsVerified: Boolean(matchingMdns),
      mdnsDevice: matchingMdns || null,
      expectedShort: formatShortFingerprint(conflictingHostEntry.certFingerprint),
      actualFingerprint: actual,
      actualShort: formatShortFingerprint(actual),
    };
  }

  return {
    status: 'tofu_required',
    reason: matchingMdns ? 'mdns_discovered_first_use' : 'manual_ip_first_use',
    mdnsVerified: Boolean(matchingMdns),
    mdnsDevice: matchingMdns || null,
    actualFingerprint: actual,
    actualShort: formatShortFingerprint(actual),
  };
}

export function buildStandaloneWindowQuery({
  host,
  port,
  transport = 'wifi',
  usbLocalPort = 0,
  pkg,
  cls = '',
  label = '',
  userId = 0,
  profileType = '',
  deviceKey = '',
  deviceName = '',
  certFingerprint = '',
  alwaysOnTop = false,
  preset = '',
  dpi = 0,
  compat = false,
}) {
  const params = new URLSearchParams();
  if (transport === 'usb') {
    params.set('transport', 'usb');
    params.set('host', '127.0.0.1');
    params.set('port', String(usbLocalPort || port || 19878));
  } else {
    params.set('host', String(host || '').trim());
    params.set('port', String(port || 19878));
    const normFp = normalizeHexFingerprint(certFingerprint);
    if (normFp.length >= MIN_FINGERPRINT_PREFIX_LEN) {
      params.set('fp', normFp);
    }
  }
  params.set('role', 'standalone_app');
  params.set('pkg', String(pkg || '').trim());
  if (cls) {
    params.set('cls', String(cls).trim());
  }
  if (label) {
    params.set('appLabel', String(label).trim());
  }
  const normalizedUid = Math.max(0, Math.round(Number(userId) || 0));
  if (normalizedUid > 0) {
    params.set('userId', String(normalizedUid));
    if (profileType) {
      params.set('profileType', String(profileType).trim());
    }
  }
  if (deviceKey) {
    params.set('deviceKey', String(deviceKey).trim());
  }
  if (deviceName) {
    params.set('deviceName', String(deviceName).trim());
  }
  if (alwaysOnTop) {
    params.set('pin', '1');
  }
  if (preset) {
    params.set('preset', String(preset).trim());
  }
  if (Number(dpi) > 0) {
    params.set('dpi', String(Math.round(Number(dpi))));
  }
  if (compat) {
    params.set('compat', '1');
  }
  return params.toString();
}

export function sanitizeCustomAdbPath(raw) {
  if (typeof raw !== 'string') return '';
  let cleaned = raw.trim();
  while (
    (cleaned.startsWith('"') && cleaned.endsWith('"')) ||
    (cleaned.startsWith("'") && cleaned.endsWith("'"))
  ) {
    cleaned = cleaned.slice(1, -1).trim();
  }
  return cleaned.replace(/^["']+|["']+$/g, '').trim();
}

export const MIN_CUSTOM_BITRATE_MBPS = 4;
export const MAX_CUSTOM_BITRATE_MBPS = 100;
export const DEFAULT_CUSTOM_BITRATE_MBPS = 50;
export const PRESET_BITRATE_KBPS = [8000, 14000, 22000, 32000, 45000];
export const SUPPORTED_FPS_OPTIONS = [60, 90, 120, 144, 165];
export const SUPPORTED_RESIZE_MODES = ['auto', '1920x1080', '2560x1440', '1280x800'];
export const SUPPORTED_DPI_OPTIONS = [0, 160, 200, 240, 280, 320, 360, 420];

export function clampCustomBitrateMbps(raw) {
  const parsed = Number(raw);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return {
      mbps: DEFAULT_CUSTOM_BITRATE_MBPS,
      kbps: DEFAULT_CUSTOM_BITRATE_MBPS * 1000,
      clamped: true,
    };
  }
  const rounded = Math.round(parsed);
  const clampedMbps = Math.max(MIN_CUSTOM_BITRATE_MBPS, Math.min(MAX_CUSTOM_BITRATE_MBPS, rounded));
  return {
    mbps: clampedMbps,
    kbps: clampedMbps * 1000,
    clamped: clampedMbps !== rounded,
  };
}

export function computeAdaptiveAutoBitrateStep({
  currentKbps = 0,
  targetFps = 60,
  actualFps = 60,
  rttMs = 0,
  isWired = false,
  width = 1920,
  height = 1080,
  preferHevc = false,
  healthyStreak = 0,
} = {}) {
  const minKbps = 4000;
  const maxKbps = isWired ? 60000 : 36000;
  const pixels = Math.max(640, width) * Math.max(480, height);
  let baseKbps = 14000;
  if (pixels >= 5_500_000) baseKbps = targetFps >= 85 ? 28000 : 22000;
  else if (pixels >= 2_800_000) baseKbps = targetFps >= 85 ? 22000 : 16000;
  else if (targetFps >= 85) baseKbps = 16000;
  if (preferHevc) baseKbps = Math.max(minKbps, Math.round(baseKbps * 0.78));

  const cur = currentKbps >= minKbps ? currentKbps : Math.min(maxKbps, baseKbps);
  const safeTargetFps = Math.max(30, Number(targetFps) || 60);
  const hasFps = Number(actualFps) > 0;
  const fpsRatio = hasFps ? (Number(actualFps) / safeTargetFps) : 1;
  const safeRtt = Math.max(0, Number(rttMs) || 0);

  // Heavy congestion / frame drop -> immediate smooth step down (-20%)
  if (safeRtt >= 65 || (hasFps && fpsRatio < 0.78)) {
    const nextKbps = Math.max(minKbps, Math.round((cur * 0.80) / 500) * 500);
    return { nextKbps, changed: Math.abs(nextKbps - cur) >= 1000, healthyStreak: 0, reason: 'down' };
  }
  // Moderate pressure -> hold steady without oscillation
  if (safeRtt >= 38 || (hasFps && fpsRatio < 0.88)) {
    return { nextKbps: cur, changed: false, healthyStreak: 0, reason: 'hold' };
  }
  // Healthy link + smooth frame rate -> ramp up gently after 2 consecutive healthy windows
  const nextStreak = healthyStreak + 1;
  if (nextStreak >= 2 && cur < maxKbps && (safeRtt === 0 || safeRtt <= 22) && fpsRatio >= 0.94) {
    const step = Math.max(2000, Math.round((cur * 0.15) / 500) * 500);
    const nextKbps = Math.min(maxKbps, cur + step);
    return {
      nextKbps,
      changed: Math.abs(nextKbps - cur) >= 1000,
      healthyStreak: 0,
      reason: 'up',
    };
  }
  return { nextKbps: cur, changed: false, healthyStreak: nextStreak, reason: 'steady' };
}

export function normalizeCastPrefs(raw = {}) {
  const codec = raw?.preferredCodec === 'hevc' || raw?.preferredCodec === 'h264'
    ? raw.preferredCodec
    : '';
  const fpsNum = Number(raw?.targetFps || 0);
  const targetFps = SUPPORTED_FPS_OPTIONS.includes(fpsNum) ? fpsNum : 0;
  const brNum = Number(raw?.bitrateKbps || 0);
  const bitrateKbps = Number.isFinite(brNum) && brNum >= 2500 && brNum <= MAX_CUSTOM_BITRATE_MBPS * 1000
    ? Math.round(brNum)
    : 0;
  const bitrateMode = raw?.bitrateMode === 'auto' || raw?.bitrateMode === 'custom' || raw?.bitrateMode === 'preset'
    ? raw.bitrateMode
    : (bitrateKbps > 0 && !PRESET_BITRATE_KBPS.includes(bitrateKbps) ? 'custom' : '');
  const resizeMode = SUPPORTED_RESIZE_MODES.includes(raw?.resizeMode) ? raw.resizeMode : '';
  const dpiNum = Number(raw?.liveDpi);
  const liveDpi = Number.isFinite(dpiNum) && SUPPORTED_DPI_OPTIONS.includes(dpiNum) ? dpiNum : 0;
  const audioEnabled = typeof raw?.audioEnabled === 'boolean' ? raw.audioEnabled : null;
  const hideLocalCursor = typeof raw?.hideLocalCursor === 'boolean' ? raw.hideLocalCursor : null;
  const hideToTrayOnClose = typeof raw?.hideToTrayOnClose === 'boolean' ? raw.hideToTrayOnClose : null;
  const companionAutoConnect = typeof raw?.companionAutoConnect === 'boolean' ? raw.companionAutoConnect : null;
  const bleNearbyEnabled = typeof raw?.bleNearbyEnabled === 'boolean' ? raw.bleNearbyEnabled : null;
  const directOpenRaw = raw?.directOpenMaxMb;
  const directOpenNum = directOpenRaw === null || directOpenRaw === undefined || directOpenRaw === '' ? NaN : Number(directOpenRaw);
  const directOpenMaxMb = Number.isFinite(directOpenNum) ? Math.max(0, Math.min(200, Math.round(directOpenNum))) : null;
  const customAdbPath = sanitizeCustomAdbPath(raw?.customAdbPath);
  return {
    preferredCodec: codec,
    targetFps,
    bitrateKbps,
    bitrateMode,
    resizeMode,
    liveDpi,
    audioEnabled,
    hideLocalCursor,
    hideToTrayOnClose,
    companionAutoConnect,
    bleNearbyEnabled,
    directOpenMaxMb,
    customAdbPath,
  };
}
