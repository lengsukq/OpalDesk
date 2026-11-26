import assert from 'node:assert/strict';
import test from 'node:test';
import {
  clearStandaloneHistory,
  getAppAvatarColor,
  getAppAvatarInitial,
  getStandalonePinPref,
  loadStandaloneHistory,
  MAX_STANDALONE_HISTORY,
  MAX_STANDALONE_WINDOWS,
  recordStandaloneLaunch,
  removeStandaloneHistory,
  sortAppsForLauncher,
  updateStandalonePinPref,
} from '../js/standalone_history.js';
import {
  buildWebDiagnosticReport,
  compareSemver,
  extractSemver,
  filterLogEntries,
  isFeedbackSubmitAllowed,
  normalizeFeedbackKind,
  normalizeShortCode,
  QQ_GROUP_NUMBER,
  resolveFeedbackBadge,
} from '../js/feedback_helpers.js';

function createMemoryStorage() {
  const map = new Map();
  return {
    getItem(k) {
      return map.has(k) ? map.get(k) : null;
    },
    setItem(k, v) {
      map.set(k, String(v));
    },
    removeItem(k) {
      map.delete(k);
    },
  };
}

test('standalone history records launches, increments openCount, remembers pin pref, and caps entries', () => {
  const storage = createMemoryStorage();
  assert.equal(MAX_STANDALONE_WINDOWS, 3);

  recordStandaloneLaunch(
    {
      pkg: 'com.tencent.mm',
      label: '微信',
      deviceKey: '192.168.1.50:19878',
      deviceName: 'Xiaomi 15',
      transport: 'wifi',
      host: '192.168.1.50',
      port: 19878,
      lastOpenedAtMs: 1000,
    },
    storage
  );

  updateStandalonePinPref('com.tencent.mm', true, '192.168.1.50:19878', storage);
  assert.equal(getStandalonePinPref('com.tencent.mm', '192.168.1.50:19878', storage), true);

  // Re-launching without explicit pinnedOnTop preserves the remembered pin preference
  const afterRelaunch = recordStandaloneLaunch(
    {
      pkg: 'com.tencent.mm',
      label: '微信',
      deviceKey: '192.168.1.50:19878',
      transport: 'wifi',
      lastOpenedAtMs: 2000,
    },
    storage
  );
  assert.equal(afterRelaunch.length, 1);
  assert.equal(afterRelaunch[0].openCount, 2);
  assert.equal(afterRelaunch[0].pinnedOnTop, true);
  assert.equal(afterRelaunch[0].host, '192.168.1.50');

  // Exceeding cap trims oldest entries
  for (let i = 0; i < MAX_STANDALONE_HISTORY + 5; i++) {
    recordStandaloneLaunch(
      {
        pkg: `com.example.app${i}`,
        label: `App ${i}`,
        deviceKey: 'usb:SER01',
        transport: 'usb',
        lastOpenedAtMs: 3000 + i,
      },
      storage
    );
  }
  const list = loadStandaloneHistory(storage);
  assert.equal(list.length, MAX_STANDALONE_HISTORY);
  assert.equal(list[0].pkg, `com.example.app${MAX_STANDALONE_HISTORY + 4}`);

  const afterRemove = removeStandaloneHistory(list[0].id, storage);
  assert.equal(afterRemove.length, MAX_STANDALONE_HISTORY - 1);

  assert.deepEqual(clearStandaloneHistory(storage), []);
});

test('sortAppsForLauncher orders by recent history and daemon openCount before name', () => {
  const apps = [
    { pkg: 'com.zeta', label: 'Zeta', openCount: 0 },
    { pkg: 'com.alpha', label: 'Alpha', openCount: 2 },
    { pkg: 'com.beta', label: 'Beta', openCount: 0 },
  ];
  const history = [{ pkg: 'com.beta', lastOpenedAtMs: 9999 }];

  const recentSorted = sortAppsForLauncher(apps, history, 'recent');
  assert.deepEqual(
    recentSorted.map((a) => a.pkg),
    ['com.beta', 'com.alpha', 'com.zeta']
  );

  const nameSorted = sortAppsForLauncher(apps, history, 'name');
  assert.deepEqual(
    nameSorted.map((a) => a.pkg),
    ['com.alpha', 'com.beta', 'com.zeta']
  );

  assert.equal(getAppAvatarInitial('微信', 'com.tencent.mm'), '微');
  assert.equal(getAppAvatarColor('com.tencent.mm'), getAppAvatarColor('com.tencent.mm'));
});

test('feedback helpers resolve adaptive BUG vs FEATURE badges and semver comparison', () => {
  assert.equal(QQ_GROUP_NUMBER, '1042317917');
  assert.equal(normalizeFeedbackKind('FEATURE'), 'feature');
  assert.equal(normalizeFeedbackKind('other'), 'bug');
  assert.equal(normalizeShortCode('#a1b2c3d4'), 'A1B2C3D4');
  assert.equal(normalizeShortCode('invalid'), '');
  assert.equal(extractSemver('io.github.lengsukq.opaldesk@1.2.3+45'), '1.2.3');
  assert.equal(compareSemver('1.0.19', '1.0.18'), 1);
  assert.equal(compareSemver('1.0.18-beta.1', '1.0.18'), -1);

  const featureOpen = resolveFeedbackBadge({ kind: 'feature', status: 'open' }, '1.0.18');
  assert.equal(featureOpen.label, '评估中');

  const featureShippedNewer = resolveFeedbackBadge(
    { kind: 'feature', status: 'fixed_in_version', fixedVersion: '1.0.19' },
    '1.0.18'
  );
  assert.equal(featureShippedNewer.label, '已实现 v1.0.19');
  assert.match(featureShippedNewer.hint, /更新至 v1\.0\.19/);

  const bugFixedNewer = resolveFeedbackBadge(
    { kind: 'bug', status: 'fixed_in_version', fixedVersion: '1.0.20' },
    '1.0.18'
  );
  assert.equal(bugFixedNewer.label, '已在 v1.0.20 修复');
  assert.equal(bugFixedNewer.tone, 'ok');
});

test('log filtering and duplicate-submit guard match Android semantics', () => {
  const entries = [
    { timestampMs: 1000, level: 'I', tag: 'App', message: 'Started' },
    { timestampMs: 2000, level: 'W', tag: 'TLS', message: 'Handshake timeout' },
    { timestampMs: 3000, level: 'E', tag: 'Video', message: 'Decoder reset' },
  ];

  const warnNewest = filterLogEntries(entries, { warningsOnly: true, newestFirst: true });
  assert.deepEqual(
    warnNewest.map((e) => e.tag),
    ['Video', 'TLS']
  );

  const queryMatch = filterLogEntries(entries, { query: 'timeout', newestFirst: false });
  assert.equal(queryMatch.length, 1);
  assert.equal(queryMatch[0].tag, 'TLS');

  const report = buildWebDiagnosticReport(entries, { userAgent: 'TestAgent' });
  assert.match(report, /TestAgent/);
  assert.match(report, /Handshake timeout/);

  // Duplicate submit guard blocks identical re-submission of same kind right after success
  assert.equal(
    isFeedbackSubmitAllowed({
      submitting: false,
      quotaExhausted: false,
      sentryConfigured: true,
      lastUploadSucceeded: true,
      currentDescription: '添加置顶功能',
      lastSubmittedDescription: '添加置顶功能',
      currentKind: 'feature',
      lastSubmittedKind: 'feature',
    }),
    false
  );
  // Switching kind or editing text unlocks submission
  assert.equal(
    isFeedbackSubmitAllowed({
      submitting: false,
      quotaExhausted: false,
      sentryConfigured: true,
      lastUploadSucceeded: true,
      currentDescription: '添加置顶功能',
      lastSubmittedDescription: '添加置顶功能',
      currentKind: 'bug',
      lastSubmittedKind: 'feature',
    }),
    true
  );
});
