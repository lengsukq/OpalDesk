/**
 * Standalone floating app window history & launcher sorting helpers.
 * Pure, storage-injectable module shared by Lobby History Bar and In-Cast App Picker.
 */

export const MAX_STANDALONE_WINDOWS = 3;
export const MAX_STANDALONE_HISTORY = 16;
export const STORAGE_STANDALONE_HISTORY = 'opaldesk-standalone-history-v1';

const AVATAR_PALETTE = [
  '#2563eb',
  '#7c3aed',
  '#0891b2',
  '#059669',
  '#d97706',
  '#dc2626',
  '#db2777',
  '#4f46e5',
];

function resolveStorage(storage) {
  if (storage && typeof storage.getItem === 'function') return storage;
  try {
    return globalThis.localStorage || null;
  } catch {
    return null;
  }
}

export function makeStandaloneHistoryId(deviceKey, pkg) {
  const dev = String(deviceKey || '').trim();
  const cleanPkg = String(pkg || '').trim();
  return `${dev}::${cleanPkg}`;
}

export function normalizeStandaloneHistory(entries) {
  if (!Array.isArray(entries)) return [];
  const seen = new Set();
  return entries
    .filter((item) => item && typeof item.pkg === 'string' && item.pkg.trim().length > 0)
    .map((item) => {
      const pkg = item.pkg.trim();
      const deviceKey = String(item.deviceKey || item.host || 'usb').trim();
      const id = makeStandaloneHistoryId(deviceKey, pkg);
      return {
        id,
        pkg,
        cls: String(item.cls || '').trim(),
        label: String(item.label || '').trim() || pkg,
        deviceKey,
        deviceName: String(item.deviceName || item.host || 'Android').trim(),
        transport: item.transport === 'usb' ? 'usb' : 'wifi',
        host: String(item.host || '').trim(),
        port: Number(item.port) || 19878,
        adbSerial: String(item.adbSerial || '').trim(),
        certFingerprint: String(item.certFingerprint || '').trim(),
        lastOpenedAtMs: Number(item.lastOpenedAtMs) || 0,
        openCount: Math.max(1, Number(item.openCount) || 1),
        pinnedOnTop: Boolean(item.pinnedOnTop),
      };
    })
    .sort((a, b) => b.lastOpenedAtMs - a.lastOpenedAtMs)
    .filter((item) => {
      if (seen.has(item.id)) return false;
      seen.add(item.id);
      return true;
    })
    .slice(0, MAX_STANDALONE_HISTORY);
}

export function loadStandaloneHistory(storage) {
  const store = resolveStorage(storage);
  if (!store) return [];
  try {
    const raw = store.getItem(STORAGE_STANDALONE_HISTORY);
    if (!raw) return [];
    return normalizeStandaloneHistory(JSON.parse(raw));
  } catch {
    return [];
  }
}

export function saveStandaloneHistory(entries, storage) {
  const normalized = normalizeStandaloneHistory(entries);
  const store = resolveStorage(storage);
  if (store) {
    try {
      store.setItem(STORAGE_STANDALONE_HISTORY, JSON.stringify(normalized));
    } catch {
      // Ignore storage quota errors
    }
  }
  return normalized;
}

export function recordStandaloneLaunch(incoming, storage) {
  const cleanPkg = String(incoming?.pkg || '').trim();
  const cleanDeviceKey = String(incoming?.deviceKey || incoming?.host || 'usb').trim();
  if (!cleanPkg || !cleanDeviceKey) {
    return loadStandaloneHistory(storage);
  }
  const existing = loadStandaloneHistory(storage);
  const targetId = makeStandaloneHistoryId(cleanDeviceKey, cleanPkg);
  const prev = existing.find((e) => e.id === targetId);
  const nextPinned =
    typeof incoming?.pinnedOnTop === 'boolean'
      ? incoming.pinnedOnTop
      : Boolean(prev?.pinnedOnTop);

  const merged = {
    id: targetId,
    pkg: cleanPkg,
    cls: String(incoming?.cls || '').trim() || prev?.cls || '',
    label: String(incoming?.label || '').trim() || prev?.label || cleanPkg,
    deviceKey: cleanDeviceKey,
    deviceName: String(incoming?.deviceName || '').trim() || prev?.deviceName || cleanDeviceKey,
    transport: incoming?.transport === 'usb' ? 'usb' : 'wifi',
    host: String(incoming?.host || '').trim() || prev?.host || '',
    port: Number(incoming?.port) || prev?.port || 19878,
    adbSerial: String(incoming?.adbSerial || '').trim() || prev?.adbSerial || '',
    certFingerprint: String(incoming?.certFingerprint || '').trim() || prev?.certFingerprint || '',
    lastOpenedAtMs: Number(incoming?.lastOpenedAtMs) || Date.now(),
    openCount: (prev?.openCount || 0) + 1,
    pinnedOnTop: nextPinned,
  };

  const rest = existing.filter((e) => e.id !== targetId);
  return saveStandaloneHistory([merged, ...rest], storage);
}

export function updateStandalonePinPref(pkg, pinnedOnTop, deviceKey = '', storage) {
  const cleanPkg = String(pkg || '').trim();
  if (!cleanPkg) return loadStandaloneHistory(storage);
  const cleanDevice = String(deviceKey || '').trim();
  const existing = loadStandaloneHistory(storage);
  const updated = existing.map((entry) => {
    const matchesPkg = entry.pkg === cleanPkg;
    const matchesDevice = !cleanDevice || entry.deviceKey === cleanDevice;
    if (matchesPkg && matchesDevice) {
      return { ...entry, pinnedOnTop: Boolean(pinnedOnTop) };
    }
    return entry;
  });
  return saveStandaloneHistory(updated, storage);
}

export function getStandalonePinPref(pkg, deviceKey = '', storage) {
  const cleanPkg = String(pkg || '').trim();
  if (!cleanPkg) return false;
  const cleanDevice = String(deviceKey || '').trim();
  const existing = loadStandaloneHistory(storage);
  if (cleanDevice) {
    const exact = existing.find((e) => e.pkg === cleanPkg && e.deviceKey === cleanDevice);
    if (exact) return Boolean(exact.pinnedOnTop);
  }
  const fallback = existing.find((e) => e.pkg === cleanPkg);
  return Boolean(fallback?.pinnedOnTop);
}

export function removeStandaloneHistory(id, storage) {
  const existing = loadStandaloneHistory(storage);
  return saveStandaloneHistory(
    existing.filter((e) => e.id !== id),
    storage
  );
}

export function clearStandaloneHistory(storage) {
  return saveStandaloneHistory([], storage);
}

export function sortAppsForLauncher(apps, historyEntries = [], sortMode = 'recent') {
  const list = Array.isArray(apps) ? [...apps] : [];
  const historyMap = new Map();
  for (const h of historyEntries || []) {
    if (!h?.pkg) continue;
    const prev = historyMap.get(h.pkg);
    if (!prev || (h.lastOpenedAtMs || 0) > (prev.lastOpenedAtMs || 0)) {
      historyMap.set(h.pkg, h);
    }
  }

  const compareName = (a, b) =>
    String(a.label || a.pkg || '').localeCompare(String(b.label || b.pkg || ''), 'zh-Hans-CN');

  if (sortMode === 'name') {
    return list.sort(compareName);
  }

  return list.sort((a, b) => {
    const ha = historyMap.get(a.pkg);
    const hb = historyMap.get(b.pkg);
    const ta = ha?.lastOpenedAtMs || 0;
    const tb = hb?.lastOpenedAtMs || 0;
    if (ta !== tb) return tb - ta;

    const oa = Number(a.openCount) || 0;
    const ob = Number(b.openCount) || 0;
    if (oa !== ob) return ob - oa;

    return compareName(a, b);
  });
}

export function getAppAvatarInitial(label, pkg) {
  const text = String(label || pkg || '?').trim();
  if (!text) return '?';
  return Array.from(text)[0].toUpperCase();
}

export function getAppAvatarColor(pkg) {
  const str = String(pkg || '');
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash * 31 + str.charCodeAt(i)) | 0;
  }
  return AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length];
}
