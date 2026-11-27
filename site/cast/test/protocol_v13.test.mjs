import test from 'node:test';
import assert from 'node:assert/strict';

import {
  SESSION_ROLE_COMPANION,
  SESSION_ROLE_DESKTOP,
  TYPE_NOTIFICATION_EVENT,
  TYPE_MEDIA_STATE,
  TYPE_FILE_OP_RESPONSE,
  TYPE_FILE_PULL_CHUNK,
  TYPE_APK_INSTALL_STATUS,
  TYPE_CALL_STATE,
  TYPE_NOTIFICATION_ACTION,
  TYPE_MEDIA_ACTION,
  TYPE_FILE_OP_REQUEST,
  TYPE_APK_INSTALL_REQUEST,
  TYPE_CALL_ACTION,
  FILE_FLAG_START,
  FILE_FLAG_DATA,
  FILE_FLAG_END,
  FILE_FLAG_ABORT,
  shouldRequestSessionAudio,
  encodeHello,
  encodeNotificationAction,
  decodeNotificationEvent,
  encodeMediaAction,
  decodeMediaState,
  encodeFileOpRequest,
  decodeFileOpResponse,
  encodeFilePullChunk,
  decodeFilePullChunk,
  inferApkFormat,
  encodeApkInstallRequest,
  decodeApkInstallStatus,
  decodeCallState,
  encodeCallAction,
} from '../js/protocol.js';

const td = new TextDecoder();
const te = new TextEncoder();

test('OPD1 v1.2/v1.3 frame type constants match Android OpalCastProtocol.java', () => {
  assert.equal(TYPE_NOTIFICATION_EVENT, 0x19);
  assert.equal(TYPE_MEDIA_STATE, 0x1a);
  assert.equal(TYPE_FILE_OP_RESPONSE, 0x1b);
  assert.equal(TYPE_FILE_PULL_CHUNK, 0x1c);
  assert.equal(TYPE_APK_INSTALL_STATUS, 0x1d);
  assert.equal(TYPE_CALL_STATE, 0x1e);
  assert.equal(TYPE_NOTIFICATION_ACTION, 0x31);
  assert.equal(TYPE_MEDIA_ACTION, 0x32);
  assert.equal(TYPE_FILE_OP_REQUEST, 0x33);
  assert.equal(TYPE_APK_INSTALL_REQUEST, 0x34);
  assert.equal(TYPE_CALL_ACTION, 0x35);
});

test('encodeHello includes companionEvents for desktop & companion roles and companion stays audio-silent', () => {
  assert.equal(shouldRequestSessionAudio(SESSION_ROLE_COMPANION, true), false);
  assert.equal(shouldRequestSessionAudio(SESSION_ROLE_DESKTOP, true), true);

  const desktopHello = JSON.parse(td.decode(encodeHello({ sessionRole: SESSION_ROLE_DESKTOP })));
  assert.equal(desktopHello.companionEvents, true);

  const companionHello = JSON.parse(td.decode(encodeHello({ sessionRole: SESSION_ROLE_COMPANION, audioEnabled: false })));
  assert.equal(companionHello.sessionRole, 'companion');
  assert.equal(companionHello.companionEvents, true);
  assert.equal(companionHello.audioEnabled, false);
});

test('notification event decode & action encode match Android/macOS wire schema', () => {
  const raw = te.encode(JSON.stringify({
    action: 'posted',
    items: [{
      key: '0|com.tencent.mm|1',
      pkg: 'com.tencent.mm',
      appLabel: '微信',
      title: '张三',
      text: '验证码 482910',
      postTimeMs: 1730000000000,
      clearable: true,
      canReply: true,
      replyLabel: '快捷回复',
      otpCode: '482910',
      actions: [{ index: 0, label: '标为已读' }],
    }],
  }));
  const decoded = decodeNotificationEvent(raw);
  assert.equal(decoded.action, 'posted');
  assert.equal(decoded.items.length, 1);
  assert.equal(decoded.items[0].otpCode, '482910');
  assert.deepEqual(decoded.items[0].actions, [{ index: 0, label: '标为已读' }]);

  const act = JSON.parse(td.decode(encodeNotificationAction({ op: 'invoke_action', key: '0|com.tencent.mm|1', actionIndex: 0 })));
  assert.deepEqual(act, { op: 'invoke_action', key: '0|com.tencent.mm|1', actionIndex: 0 });

  const reply = JSON.parse(td.decode(encodeNotificationAction({ op: 'reply', key: 'k1', replyText: '收到' })));
  assert.deepEqual(reply, { op: 'reply', key: 'k1', replyText: '收到' });
});

test('media state decode & media action encode roundtrip', () => {
  const st = decodeMediaState(te.encode(JSON.stringify({
    active: true,
    pkg: 'com.netease.cloudmusic',
    appLabel: '网易云音乐',
    title: '晴天',
    artist: '周杰伦',
    album: '叶惠美',
    playing: true,
    positionMs: 42000,
    durationMs: 269000,
    artworkBase64: 'AQID',
  })));
  assert.equal(st.active, true);
  assert.equal(st.playing, true);
  assert.equal(st.title, '晴天');
  assert.equal(st.artworkBase64, 'AQID');

  const act = JSON.parse(td.decode(encodeMediaAction({ op: 'seek', positionMs: 90000 })));
  assert.deepEqual(act, { op: 'seek', positionMs: 90000 });
});

test('file op request/response including 3-tab storage folders & apps', () => {
  const req = JSON.parse(td.decode(encodeFileOpRequest({ requestId: 7, op: 'storage_scan', path: '/sdcard', maxDepth: 4 })));
  assert.deepEqual(req, { requestId: 7, op: 'storage_scan', path: '/sdcard', maxDepth: 4 });

  const resp = decodeFileOpResponse(te.encode(JSON.stringify({
    requestId: 7,
    op: 'storage_scan',
    ok: true,
    path: '/sdcard',
    storage: {
      totalBytes: 256_000_000_000,
      freeBytes: 120_000_000_000,
      usedBytes: 136_000_000_000,
      categories: [{ name: 'videos', bytes: 20_000_000_000, fileCount: 18 }],
      largeFiles: [{ name: 'clip.mp4', path: '/sdcard/Movies/clip.mp4', size: 1_500_000_000, modifiedMs: 1700000000000 }],
      folders: [{ name: 'DCIM', path: '/sdcard/DCIM', bytes: 32_000_000_000, fileCount: 410 }],
      apps: [{ pkg: 'com.tencent.mm', appLabel: '微信', apkBytes: 250_000_000, dataBytes: 12_000_000_000, cacheBytes: 800_000_000 }],
    },
  })));
  assert.equal(resp.ok, true);
  assert.equal(resp.storage.folders[0].path, '/sdcard/DCIM');
  assert.equal(resp.storage.apps[0].totalBytes, 13_050_000_000);
});

test('0x1C FILE_PULL_CHUNK 15-byte BE binary codec roundtrips data & abort reasons', () => {
  const payload = encodeFilePullChunk({
    transferId: 42,
    chunkFlags: FILE_FLAG_START | FILE_FLAG_DATA | FILE_FLAG_END,
    totalSize: 1024 * 1024 * 5,
    nameOrPath: '/sdcard/Download/报告.pdf',
    data: new Uint8Array([1, 2, 3, 4]),
  });
  const decoded = decodeFilePullChunk(payload);
  assert.equal(decoded.transferId, 42);
  assert.equal(decoded.chunkFlags, FILE_FLAG_START | FILE_FLAG_DATA | FILE_FLAG_END);
  assert.equal(decoded.totalSize, 1024 * 1024 * 5);
  assert.equal(decoded.nameOrPath, '/sdcard/Download/报告.pdf');
  assert.deepEqual(Array.from(decoded.data), [1, 2, 3, 4]);
  assert.equal(decoded.abortErrorMessage, null);

  const abortPayload = encodeFilePullChunk({
    transferId: 43,
    chunkFlags: FILE_FLAG_ABORT,
    totalSize: 0,
    nameOrPath: '',
    data: te.encode('permission denied'),
  });
  const decodedAbort = decodeFilePullChunk(abortPayload);
  assert.equal(decodedAbort.abortErrorMessage, 'permission denied');
});

test('APK install request/status & call bridge state/action codecs match wire spec', () => {
  assert.equal(inferApkFormat('/sdcard/Download/demo.XAPK'), 'xapk');
  assert.equal(inferApkFormat('/sdcard/Download/readme.txt'), null);

  const req = JSON.parse(td.decode(encodeApkInstallRequest({
    installId: 9,
    filePath: '/sdcard/Download/app.apkm',
    replaceExisting: true,
    allowDowngrade: false,
  })));
  assert.deepEqual(req, {
    installId: 9,
    filePath: '/sdcard/Download/app.apkm',
    format: 'apkm',
    replaceExisting: true,
    allowDowngrade: false,
  });

  const st = decodeApkInstallStatus(te.encode(JSON.stringify({
    installId: 9,
    stage: 'succeeded',
    progress: 100,
    packageName: 'com.example.demo',
    message: '安装完成',
  })));
  assert.equal(st.isTerminal, true);
  assert.equal(st.progress, 100);

  const call = decodeCallState(te.encode(JSON.stringify({
    state: 'ringing',
    number: '13800138000',
    contactName: '林经理',
    startedAtMs: 0,
    isMuted: false,
    isSpeakerOn: false,
    canAnswer: true,
    canReject: true,
    canDial: true,
    source: 'telephony',
  })));
  assert.equal(call.isActive, true);
  assert.equal(call.displayCaller, '林经理');

  const callAct = JSON.parse(td.decode(encodeCallAction({ op: 'reject_sms', smsText: '开会中' })));
  assert.deepEqual(callAct, { op: 'reject_sms', smsText: '开会中' });
});
