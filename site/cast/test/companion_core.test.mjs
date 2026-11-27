import test from 'node:test';
import assert from 'node:assert/strict';

import {
  extractOtpCode,
  filterDisplayActions,
  createNotificationStore,
  formatCallDuration,
  sanitizeDialNumber,
  callSessionKey,
  shouldShowCallHud,
  resolveHandoffInfo,
  OTP_FRESHNESS_WINDOW_MS,
} from '../js/companion_core.js';

test('extractOtpCode extracts 6-digit verification codes and ignores years', () => {
  assert.equal(extractOtpCode('银行短信', '您的验证码是 593821，5分钟内有效'), '593821');
  assert.equal(extractOtpCode('Login Code', '2026 verification code: 7419'), '7419');
  assert.equal(extractOtpCode('日历提醒', '2026年项目评审会'), '');
});

test('notification store handles snapshot/posted/removed, action filtering & OTP freshness', () => {
  const store = createNotificationStore();
  const baseNow = 1_700_000_000_000;

  store.applyEvent({
    action: 'snapshot',
    items: [
      { key: 'k1', pkg: 'com.a', appLabel: 'A', title: 'Old', text: 'Hi', postTimeMs: baseNow - 5000, actions: [{ index: 0, label: '  ' }, { index: 1, label: '已读' }] },
    ],
  }, baseNow);
  assert.equal(store.getItems().length, 1);
  assert.deepEqual(store.getItems()[0].actions, [{ index: 1, label: '已读' }]);

  const res = store.applyEvent({
    action: 'posted',
    items: [
      { key: 'k2', pkg: 'com.sms', appLabel: '短信', title: '安全验证', text: '动态码 882716', postTimeMs: baseNow },
    ],
  }, baseNow);
  assert.equal(res.added.length, 1);
  assert.equal(store.getLatestOtp(baseNow + 1000)?.code, '882716');
  assert.equal(store.getLatestOtp(baseNow + OTP_FRESHNESS_WINDOW_MS + 1), null);

  store.applyEvent({ action: 'removed', key: 'k1' }, baseNow + 2000);
  assert.deepEqual(store.getItems().map((x) => x.key), ['k2']);
  assert.deepEqual(filterDisplayActions([{ index: 2, label: ' 标为已读 ' }]), [{ index: 2, label: '标为已读' }]);
});

test('call HUD session key & temporary dismiss semantics', () => {
  const callA = { state: 'ringing', number: '13800138000', startedAtMs: 0 };
  const keyA = callSessionKey(callA);
  assert.equal(shouldShowCallHud(callA, ''), true);
  assert.equal(shouldShowCallHud(callA, keyA), false);

  const callB = { state: 'ringing', number: '13900139000', startedAtMs: 0 };
  assert.equal(shouldShowCallHud(callB, keyA), true);
  assert.equal(shouldShowCallHud({ state: 'idle' }, ''), false);

  assert.equal(formatCallDuration(10_000, 75_000), '01:05');
  assert.equal(formatCallDuration(10_000, 3_675_000), '01:01:05');
  assert.equal(sanitizeDialNumber('+86 (138) 0013-8000 ext!'), '+861380013-8000');
  assert.deepEqual(
    resolveHandoffInfo({ foregroundApp: 'com.tencent.mm', foregroundAppLabel: '微信' }),
    { pkg: 'com.tencent.mm', label: '微信', canPopout: true }
  );
});
