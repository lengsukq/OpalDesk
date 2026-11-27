/**
 * OpalDesk Standalone App Popout Preferences & Favorite Pinning Store.
 * Pure, storage-injectable ES module shared by Windows Tauri Desktop & Web Cast.
 * Mirrors macOS `StandaloneAppPreferencesStore` + `PopoutAspectPreset`.
 */

export const STORAGE_STANDALONE_PREFS = 'opaldesk_standalone_app_prefs_v1';

export const POPOUT_ASPECT_PRESETS = Object.freeze({
  phone: Object.freeze({
    id: 'phone',
    label: '竖屏手机',
    shortLabel: '手机',
    winWidth: 460,
    winHeight: 860,
    streamWidth: 1080,
    streamHeight: 2020,
    defaultDpi: 360,
  }),
  fold: Object.freeze({
    id: 'fold',
    label: '折叠展开',
    shortLabel: '折叠',
    winWidth: 760,
    winHeight: 820,
    streamWidth: 1520,
    streamHeight: 1640,
    defaultDpi: 320,
  }),
  tablet: Object.freeze({
    id: 'tablet',
    label: '横屏平板',
    shortLabel: '平板',
    winWidth: 1040,
    winHeight: 680,
    streamWidth: 1920,
    streamHeight: 1256,
    defaultDpi: 280,
  }),
  custom: Object.freeze({
    id: 'custom',
    label: '自由尺寸',
    shortLabel: '自由',
    winWidth: 520,
    winHeight: 880,
    streamWidth: 1080,
    streamHeight: 1828,
    defaultDpi: 0,
  }),
});

export const POPOUT_PRESET_IDS = Object.freeze(Object.keys(POPOUT_ASPECT_PRESETS));

export const COMPAT_RESOLUTION_SPEC = Object.freeze({
  winWidth: 440,
  winHeight: 782,
  streamWidth: 1080,
  streamHeight: 1920,
  dpi: 400,
});

export const POPOUT_DPI_CHOICES = Object.freeze([0, 200, 240, 280, 320, 360, 400, 440, 480]);

const MIN_WIN_WIDTH = 340;
const MAX_WIN_WIDTH = 1920;
const MIN_WIN_HEIGHT = 420;
const MAX_WIN_HEIGHT = 1280;
const MIN_STREAM_DIM = 480;
const MAX_STREAM_DIM = 2560;
const DEFAULT_BASE_DPI = 320;

export function alignEven(value, minVal, maxVal) {
  const num = Number.isFinite(Number(value)) ? Math.round(Number(value)) : minVal;
  const clamped = Math.max(minVal, Math.min(maxVal, num));
  return clamped & ~1;
}

export function canonicalDeviceKey(deviceKey) {
  const cleaned = String(deviceKey || '').trim().toLowerCase();
  return cleaned || '__default__';
}

function entryStorageKey(deviceKey, pkg) {
  return `${canonicalDeviceKey(deviceKey)}::${String(pkg || '').trim()}`;
}

export function createDefaultAppPref() {
  return {
    isFavorite: false,
    favoriteOrder: 0,
    preferredPreset: 'phone',
    customWidth: 0,
    customHeight: 0,
    pinnedOnTop: false,
    customDpi: 0,
    compatibilityResolution: false,
    updatedAtMs: 0,
  };
}

export function normalizeAppPref(raw = {}) {
  const preset = POPOUT_PRESET_IDS.includes(raw?.preferredPreset) ? raw.preferredPreset : 'phone';
  const rawW = Number(raw?.customWidth || 0);
  const rawH = Number(raw?.customHeight || 0);
  const rawDpi = Number(raw?.customDpi || 0);
  const customDpi = POPOUT_DPI_CHOICES.includes(rawDpi)
    ? rawDpi
    : (rawDpi >= 160 && rawDpi <= 560 ? Math.round(rawDpi) : 0);

  return {
    isFavorite: Boolean(raw?.isFavorite),
    favoriteOrder: Number.isFinite(Number(raw?.favoriteOrder)) ? Math.max(0, Math.round(Number(raw.favoriteOrder))) : 0,
    preferredPreset: preset,
    customWidth: rawW >= MIN_WIN_WIDTH ? Math.min(MAX_WIN_WIDTH, Math.round(rawW)) : 0,
    customHeight: rawH >= MIN_WIN_HEIGHT ? Math.min(MAX_WIN_HEIGHT, Math.round(rawH)) : 0,
    pinnedOnTop: Boolean(raw?.pinnedOnTop),
    customDpi,
    compatibilityResolution: Boolean(raw?.compatibilityResolution),
    updatedAtMs: Number.isFinite(Number(raw?.updatedAtMs)) ? Math.max(0, Math.round(Number(raw.updatedAtMs))) : 0,
  };
}

/**
 * Resolves effective desktop window size + Android VirtualDisplay stream resolution & DPI
 * for launching or re-configuring a standalone app popout window.
 */
export function resolvePopoutLaunchGeometry(prefRaw, { baseDpi = DEFAULT_BASE_DPI, scaleFactor = 1.5 } = {}) {
  const pref = normalizeAppPref(prefRaw);
  const presetSpec = POPOUT_ASPECT_PRESETS[pref.preferredPreset] || POPOUT_ASPECT_PRESETS.phone;

  if (pref.compatibilityResolution) {
    return {
      preset: pref.preferredPreset,
      winWidth: pref.customWidth >= MIN_WIN_WIDTH ? pref.customWidth : COMPAT_RESOLUTION_SPEC.winWidth,
      winHeight: pref.customHeight >= MIN_WIN_HEIGHT ? pref.customHeight : COMPAT_RESOLUTION_SPEC.winHeight,
      streamWidth: COMPAT_RESOLUTION_SPEC.streamWidth,
      streamHeight: COMPAT_RESOLUTION_SPEC.streamHeight,
      dpi: pref.customDpi > 0 ? pref.customDpi : COMPAT_RESOLUTION_SPEC.dpi,
      pinnedOnTop: pref.pinnedOnTop,
      compatibilityResolution: true,
    };
  }

  const useCustomWin = pref.preferredPreset === 'custom' && pref.customWidth >= MIN_WIN_WIDTH && pref.customHeight >= MIN_WIN_HEIGHT;
  const winWidth = useCustomWin ? pref.customWidth : (pref.customWidth >= MIN_WIN_WIDTH && pref.preferredPreset === 'custom' ? pref.customWidth : presetSpec.winWidth);
  const winHeight = useCustomWin ? pref.customHeight : (pref.customHeight >= MIN_WIN_HEIGHT && pref.preferredPreset === 'custom' ? pref.customHeight : presetSpec.winHeight);

  let streamWidth;
  let streamHeight;
  if (useCustomWin) {
    const scale = Math.max(1.25, Math.min(2.5, Number(scaleFactor) || 1.5));
    streamWidth = alignEven(winWidth * scale, MIN_STREAM_DIM, MAX_STREAM_DIM);
    streamHeight = alignEven(winHeight * scale, MIN_STREAM_DIM, MAX_STREAM_DIM);
  } else {
    streamWidth = presetSpec.streamWidth;
    streamHeight = presetSpec.streamHeight;
  }

  const dpi = pref.customDpi > 0
    ? pref.customDpi
    : (presetSpec.defaultDpi > 0 ? presetSpec.defaultDpi : baseDpi);

  return {
    preset: pref.preferredPreset,
    winWidth,
    winHeight,
    streamWidth,
    streamHeight,
    dpi,
    pinnedOnTop: pref.pinnedOnTop,
    compatibilityResolution: false,
  };
}

/**
 * Computes concise human-readable preference badges for app cards & tooltips.
 */
export function formatPopoutPrefBadges(prefRaw) {
  const pref = normalizeAppPref(prefRaw);
  const badges = [];
  if (pref.compatibilityResolution) {
    badges.push('兼容模式');
  } else if (pref.preferredPreset !== 'phone') {
    const spec = POPOUT_ASPECT_PRESETS[pref.preferredPreset];
    if (spec) badges.push(spec.shortLabel);
  }
  if (pref.customDpi > 0) {
    badges.push(`${pref.customDpi} DPI`);
  }
  if (pref.pinnedOnTop) {
    badges.push('置顶');
  }
  return badges;
}

export function createStandalonePrefsStore({ storage = null, storageKey = STORAGE_STANDALONE_PREFS } = {}) {
  const mem = new Map();

  function resolveStorage() {
    if (storage) return storage;
    try {
      return typeof localStorage !== 'undefined' ? localStorage : null;
    } catch {
      return null;
    }
  }

  function loadAll() {
    const store = resolveStorage();
    if (!store) {
      return Object.fromEntries(mem.entries());
    }
    try {
      const raw = store.getItem(storageKey);
      if (!raw) return {};
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch {
      return {};
    }
  }

  function saveAll(mapObj) {
    const store = resolveStorage();
    if (!store) {
      mem.clear();
      for (const [k, v] of Object.entries(mapObj)) mem.set(k, v);
      return;
    }
    try {
      store.setItem(storageKey, JSON.stringify(mapObj));
    } catch {
      // Ignore quota errors
    }
  }

  function getPref(deviceKey, pkg) {
    if (!pkg) return createDefaultAppPref();
    const all = loadAll();
    const exact = all[entryStorageKey(deviceKey, pkg)];
    if (exact) return normalizeAppPref(exact);
    // Fallback to global default device key if deviceKey wasn't known previously
    const fallback = all[entryStorageKey('__default__', pkg)];
    return fallback ? normalizeAppPref(fallback) : createDefaultAppPref();
  }

  function updatePref(deviceKey, pkg, patch = {}) {
    if (!pkg) return createDefaultAppPref();
    const all = loadAll();
    const key = entryStorageKey(deviceKey, pkg);
    const current = normalizeAppPref(all[key] || all[entryStorageKey('__default__', pkg)]);
    const next = normalizeAppPref({
      ...current,
      ...patch,
      updatedAtMs: Date.now(),
    });
    all[key] = next;
    saveAll(all);
    return next;
  }

  function nextFavoriteOrder(all, deviceKey) {
    const prefix = `${canonicalDeviceKey(deviceKey)}::`;
    let maxOrder = 0;
    for (const [k, v] of Object.entries(all)) {
      if (k.startsWith(prefix) && v?.isFavorite) {
        const order = Number(v.favoriteOrder) || 0;
        if (order > maxOrder) maxOrder = order;
      }
    }
    return maxOrder + 1;
  }

  function toggleFavorite(deviceKey, pkg) {
    if (!pkg) return createDefaultAppPref();
    const all = loadAll();
    const key = entryStorageKey(deviceKey, pkg);
    const current = normalizeAppPref(all[key]);
    const nextFav = !current.isFavorite;
    const next = normalizeAppPref({
      ...current,
      isFavorite: nextFav,
      favoriteOrder: nextFav ? nextFavoriteOrder(all, deviceKey) : 0,
      updatedAtMs: Date.now(),
    });
    all[key] = next;
    saveAll(all);
    return next;
  }

  function listFavorites(deviceKey) {
    const all = loadAll();
    const prefix = `${canonicalDeviceKey(deviceKey)}::`;
    const items = [];
    for (const [k, v] of Object.entries(all)) {
      if (k.startsWith(prefix) && v?.isFavorite) {
        const pkg = k.slice(prefix.length);
        if (pkg) {
          items.push({ pkg, pref: normalizeAppPref(v) });
        }
      }
    }
    items.sort((a, b) => (a.pref.favoriteOrder - b.pref.favoriteOrder) || a.pkg.localeCompare(b.pkg));
    return items;
  }

  function sortAppsWithFavorites(apps, deviceKey) {
    if (!Array.isArray(apps) || apps.length <= 1) return Array.isArray(apps) ? [...apps] : [];
    const decorated = apps.map((app, index) => {
      const pref = getPref(deviceKey, app?.pkg);
      return { app, index, isFavorite: pref.isFavorite, favoriteOrder: pref.favoriteOrder };
    });
    decorated.sort((a, b) => {
      if (a.isFavorite !== b.isFavorite) return a.isFavorite ? -1 : 1;
      if (a.isFavorite && b.isFavorite && a.favoriteOrder !== b.favoriteOrder) {
        return a.favoriteOrder - b.favoriteOrder;
      }
      return a.index - b.index;
    });
    return decorated.map((d) => d.app);
  }

  return {
    getPref,
    updatePref,
    toggleFavorite,
    listFavorites,
    sortAppsWithFavorites,
  };
}
