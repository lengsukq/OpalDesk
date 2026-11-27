import test from 'node:test';
import assert from 'node:assert/strict';

import {
  TYPE_FILE_OP_REQUEST,
  TYPE_FILE_OP_RESPONSE,
  TYPE_FILE_PULL_CHUNK,
  TYPE_APK_INSTALL_REQUEST,
  TYPE_APK_INSTALL_STATUS,
  FILE_FLAG_START,
  FILE_FLAG_DATA,
  FILE_FLAG_END,
  encodeFilePullChunk,
} from '../js/protocol.js';
import {
  normalizeRemotePath,
  getParentRemotePath,
  buildPathBreadcrumbs,
  formatBytes,
  classifyEntryKind,
  sortRemoteEntries,
  OpalFileBrowserClient,
} from '../js/file_browser_client.js';

const te = new TextEncoder();
const td = new TextDecoder();

test('file path helpers normalize, guard /sdcard root, classify & sort folders first', () => {
  assert.equal(normalizeRemotePath('/sdcard//Download/../DCIM/'), '/sdcard/DCIM');
  assert.equal(getParentRemotePath('/sdcard/DCIM/Camera'), '/sdcard/DCIM');
  assert.equal(getParentRemotePath('/sdcard'), '/sdcard');
  assert.deepEqual(buildPathBreadcrumbs('/sdcard/DCIM'), [
    { label: '手机存储', path: '/sdcard' },
    { label: 'DCIM', path: '/sdcard/DCIM' },
  ]);
  assert.equal(formatBytes(1536), '1.50 KB');
  assert.equal(classifyEntryKind({ name: 'bundle.apkm', isDir: false }), 'apk');
  assert.equal(classifyEntryKind({ name: 'IMG_01.jpg', isDir: false }), 'image');

  const sorted = sortRemoteEntries([
    { name: 'b.txt', isDir: false, size: 10 },
    { name: 'ZFolder', isDir: true, size: 0 },
    { name: 'a.txt', isDir: false, size: 200 },
  ], 'name', 'asc');
  assert.deepEqual(sorted.map((x) => x.name), ['ZFolder', 'a.txt', 'b.txt']);
});

test('OpalFileBrowserClient handles list, streaming 0x1C pull & 0x34/0x1D APK install lifecycle', async () => {
  const sent = [];
  const client = new OpalFileBrowserClient({
    sendFrame: (type, flags, payload) => {
      sent.push({ type, flags, payload });
      return true;
    },
    timeoutMs: 2000,
  });

  // 1. Directory list RPC
  const listPromise = client.list('/sdcard/Download');
  assert.equal(sent.length, 1);
  assert.equal(sent[0].type, TYPE_FILE_OP_REQUEST);
  const reqObj = JSON.parse(td.decode(sent[0].payload));
  assert.equal(reqObj.op, 'list');
  assert.equal(reqObj.path, '/sdcard/Download');

  client.handleFrame({
    type: TYPE_FILE_OP_RESPONSE,
    payload: te.encode(JSON.stringify({
      requestId: reqObj.requestId,
      op: 'list',
      ok: true,
      path: '/sdcard/Download',
      entries: [{ name: 'test.apk', path: '/sdcard/Download/test.apk', isDir: false, size: 1234 }],
    })),
  });
  const listRes = await listPromise;
  assert.equal(listRes.entries.length, 1);

  // 2. Streaming pull via 0x1C
  const pulledChunks = [];
  const pullPromise = client.pullFile('/sdcard/Download/hello.txt', {
    onChunk: (u8) => pulledChunks.push(u8),
  });
  const pullReq = JSON.parse(td.decode(sent[1].payload));
  assert.equal(pullReq.op, 'pull');
  client.handleFrame({
    type: TYPE_FILE_OP_RESPONSE,
    payload: te.encode(JSON.stringify({ requestId: pullReq.requestId, op: 'pull', ok: true, totalSize: 5 })),
  });
  client.handleFrame({
    type: TYPE_FILE_PULL_CHUNK,
    payload: encodeFilePullChunk({
      transferId: pullReq.transferId,
      chunkFlags: FILE_FLAG_START | FILE_FLAG_DATA,
      totalSize: 5,
      nameOrPath: 'hello.txt',
      data: te.encode('hel'),
    }),
  });
  client.handleFrame({
    type: TYPE_FILE_PULL_CHUNK,
    payload: encodeFilePullChunk({
      transferId: pullReq.transferId,
      chunkFlags: FILE_FLAG_DATA | FILE_FLAG_END,
      totalSize: 5,
      nameOrPath: '',
      data: te.encode('lo'),
    }),
  });
  const pullResult = await pullPromise;
  assert.equal(pullResult.fileName, 'hello.txt');
  assert.equal(pullResult.receivedBytes, 5);
  assert.equal(td.decode(Buffer.concat(pulledChunks)), 'hello');

  // 3. APK install progress & completion
  const stages = [];
  const installPromise = client.installApk('/sdcard/Download/test.apk', {
    onStatus: (st) => stages.push(st.stage),
  });
  const instReq = JSON.parse(td.decode(sent[2].payload));
  assert.equal(sent[2].type, TYPE_APK_INSTALL_REQUEST);
  client.handleFrame({
    type: TYPE_APK_INSTALL_STATUS,
    payload: te.encode(JSON.stringify({ installId: instReq.installId, stage: 'installing', progress: 60 })),
  });
  client.handleFrame({
    type: TYPE_APK_INSTALL_STATUS,
    payload: te.encode(JSON.stringify({ installId: instReq.installId, stage: 'succeeded', progress: 100, packageName: 'com.demo' })),
  });
  const instRes = await installPromise;
  assert.deepEqual(stages, ['installing', 'succeeded']);
  assert.equal(instRes.packageName, 'com.demo');
});
