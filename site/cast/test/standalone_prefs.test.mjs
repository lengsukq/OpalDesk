import test from 'node:test';
import assert from 'node:assert/strict';

import {
  POPOUT_ASPECT_PRESETS,
  COMPAT_RESOLUTION_SPEC,
  createStandalonePrefsStore,
  resolvePopoutLaunchGeometry,
  formatPopoutPrefBadges,
} from '../js/standalone_prefs.js';

function createMemoryStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
  };
}

test('favorites toggle preserves insertion order and sorts favorite apps first', () => {
  const store = createStandalonePrefsStore({ storage: createMemoryStorage() });
  const dev = 'fp:abc123';

  store.toggleFavorite(dev, 'com.b');
  store.toggleFavorite(dev, 'com.a');
  assert.deepEqual(store.listFavorites(dev).map((x) => x.pkg), ['com.b', 'com.a']);

  const apps = [
    { pkg: 'com.c', label: 'C' },
    { pkg: 'com.a', label: 'A' },
    { pkg: 'com.b', label: 'B' },
    { pkg: 'com.d', label: 'D' },
  ];
  const sorted = store.sortAppsWithFavorites(apps, dev);
  assert.deepEqual(sorted.map((x) => x.pkg), ['com.b', 'com.a', 'com.c', 'com.d']);

  // Unpin com.b
  const toggled = store.toggleFavorite(dev, 'com.b');
  assert.equal(toggled.isFavorite, false);
  assert.deepEqual(store.listFavorites(dev).map((x) => x.pkg), ['com.a']);
});

test('resolvePopoutLaunchGeometry respects preset, custom DPI, custom window size, and compatibility mode', () => {
  const tabletGeo = resolvePopoutLaunchGeometry({ preferredPreset: 'tablet', customDpi: 240, pinnedOnTop: true });
  assert.equal(tabletGeo.winWidth, POPOUT_ASPECT_PRESETS.tablet.winWidth);
  assert.equal(tabletGeo.streamWidth, POPOUT_ASPECT_PRESETS.tablet.streamWidth);
  assert.equal(tabletGeo.dpi, 240);
  assert.equal(tabletGeo.pinnedOnTop, true);

  const compatGeo = resolvePopoutLaunchGeometry({
    preferredPreset: 'tablet',
    compatibilityResolution: true,
    customDpi: 0,
  });
  assert.equal(compatGeo.streamWidth, COMPAT_RESOLUTION_SPEC.streamWidth);
  assert.equal(compatGeo.streamHeight, COMPAT_RESOLUTION_SPEC.streamHeight);
  assert.equal(compatGeo.dpi, COMPAT_RESOLUTION_SPEC.dpi);
  assert.equal(compatGeo.compatibilityResolution, true);

  const customGeo = resolvePopoutLaunchGeometry({
    preferredPreset: 'custom',
    customWidth: 601,
    customHeight: 903,
  });
  assert.equal(customGeo.streamWidth % 2, 0);
  assert.equal(customGeo.streamHeight % 2, 0);
});

test('formatPopoutPrefBadges reflects active overrides concisely', () => {
  assert.deepEqual(formatPopoutPrefBadges({}), []);
  assert.deepEqual(
    formatPopoutPrefBadges({ preferredPreset: 'fold', customDpi: 360, pinnedOnTop: true }),
    ['折叠', '360 DPI', '置顶']
  );
  assert.deepEqual(
    formatPopoutPrefBadges({ preferredPreset: 'tablet', compatibilityResolution: true }),
    ['兼容模式']
  );
});
