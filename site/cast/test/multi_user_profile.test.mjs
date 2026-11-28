import test from 'node:test';
import assert from 'node:assert/strict';

import {
  decodeAppList,
  encodeHello,
  encodeWindowAction,
  formatAppKey,
  getProfileSearchKeywords,
  normalizeProfileType,
  parseAppKey,
  SESSION_ROLE_STANDALONE_APP,
} from '../js/protocol.js';
import { createStandalonePrefsStore } from '../js/standalone_prefs.js';
import {
  getStandalonePinPref,
  recordStandaloneLaunch,
  sortAppsForLauncher,
  updateStandalonePinPref,
} from '../js/standalone_history.js';
import { buildStandaloneWindowQuery } from '../js/desktop_bridge.js';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function createMemoryStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
  };
}

test('formatAppKey and parseAppKey keep user 0 backward-compatible and encode secondary users', () => {
  assert.equal(formatAppKey('com.tencent.mm', 0), 'com.tencent.mm');
  assert.equal(formatAppKey('com.tencent.mm', 999), 'com.tencent.mm#u999');
  assert.deepEqual(parseAppKey('com.tencent.mm'), { pkg: 'com.tencent.mm', userId: 0 });
  assert.deepEqual(parseAppKey('com.tencent.mm#u999'), { pkg: 'com.tencent.mm', userId: 999 });
  assert.equal(normalizeProfileType('', 999), 'clone');
  assert.equal(normalizeProfileType('work', 10), 'work');
  assert.equal(normalizeProfileType('clone', 0), '');
  assert.ok(getProfileSearchKeywords(999, 'clone').includes('分身'));
  assert.ok(getProfileSearchKeywords(10, 'work').includes('work'));
});

test('encodeHello and decodeAppList preserve multi-user app fields cleanly', () => {
  const primaryHello = JSON.parse(decoder.decode(encodeHello({
    sessionRole: SESSION_ROLE_STANDALONE_APP,
    appPackage: 'com.tencent.mm',
    appUserId: 0,
  })));
  assert.equal('appUserId' in primaryHello, false);

  const cloneHello = JSON.parse(decoder.decode(encodeHello({
    sessionRole: SESSION_ROLE_STANDALONE_APP,
    appPackage: 'com.tencent.mm',
    appUserId: 999,
  })));
  assert.equal(cloneHello.appUserId, 999);

  const decodedApps = decodeAppList(encoder.encode(JSON.stringify({
    apps: [
      { pkg: 'com.tencent.mm', label: '微信' },
      { pkg: 'com.tencent.mm', label: '微信', userId: 999, profileType: 'clone' },
    ],
  })));
  assert.equal(decodedApps.length, 2);
  assert.equal(decodedApps[0].userId, 0);
  assert.equal(decodedApps[0].appKey, 'com.tencent.mm');
  assert.equal(decodedApps[1].userId, 999);
  assert.equal(decodedApps[1].profileType, 'clone');
  assert.equal(decodedApps[1].appKey, 'com.tencent.mm#u999');

  const winAction = JSON.parse(decoder.decode(encodeWindowAction({
    action: 'launch',
    pkg: 'com.tencent.mm',
    userId: 999,
  })));
  assert.equal(winAction.userId, 999);
});

test('standalone prefs, history, and query params isolate primary and cloned apps', () => {
  const storage = createMemoryStorage();
  const store = createStandalonePrefsStore({ storage });
  const dev = '192.168.31.40:19878';

  store.toggleFavorite(dev, 'com.tencent.mm#u999');
  assert.equal(store.getPref(dev, 'com.tencent.mm').isFavorite, false);
  assert.equal(store.getPref(dev, 'com.tencent.mm#u999').isFavorite, true);

  const apps = [
    { pkg: 'com.tencent.mm', label: '微信', userId: 0, appKey: 'com.tencent.mm' },
    { pkg: 'com.tencent.mm', label: '微信', userId: 999, profileType: 'clone', appKey: 'com.tencent.mm#u999' },
  ];
  const favSorted = store.sortAppsWithFavorites(apps, dev);
  assert.equal(favSorted[0].appKey, 'com.tencent.mm#u999');

  const histStorage = createMemoryStorage();
  recordStandaloneLaunch({ pkg: 'com.tencent.mm', label: '微信', deviceKey: dev, host: '192.168.31.40', lastOpenedAtMs: 1000 }, histStorage);
  const history = recordStandaloneLaunch(
    { pkg: 'com.tencent.mm', label: '微信', userId: 999, profileType: 'clone', deviceKey: dev, host: '192.168.31.40', lastOpenedAtMs: 2000 },
    histStorage
  );
  assert.equal(history.length, 2);

  updateStandalonePinPref('com.tencent.mm#u999', true, dev, histStorage);
  assert.equal(getStandalonePinPref('com.tencent.mm', dev, histStorage), false);
  assert.equal(getStandalonePinPref('com.tencent.mm#u999', dev, histStorage), true);

  const recentSorted = sortAppsForLauncher(apps, history, 'recent');
  assert.equal(recentSorted[0].appKey, 'com.tencent.mm#u999');

  const query = buildStandaloneWindowQuery({
    host: '192.168.31.40',
    port: 19878,
    pkg: 'com.tencent.mm',
    userId: 999,
    profileType: 'clone',
  });
  const params = new URLSearchParams(query);
  assert.equal(params.get('userId'), '999');
  assert.equal(params.get('profileType'), 'clone');
});
