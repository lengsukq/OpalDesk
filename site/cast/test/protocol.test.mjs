import test from 'node:test';
import assert from 'node:assert/strict';

import {
  BUTTON_PRIMARY,
  decodeAudioConfig,
  decodeAuthResult,
  decodeClipboardPush,
  decodeFileTransferAck,
  decodeFrame,
  decodePingPong,
  decodeVideoConfig,
  encodeFileTransferChunk,
  encodeFrame,
  encodeHello,
  encodeKeyEvent,
  encodeMouseEvent,
  encodePingPong,
  encodeResizeConfig,
  encodeSystemAction,
  encodeTextEvent,
  encodeTouchEvent,
  FILE_FLAG_DATA,
  FILE_FLAG_END,
  FILE_FLAG_START,
  fitDimensionsToMaxBounds,
  FLAG_CLIPBOARD_IMAGE_PNG,
  FLAG_VIDEO_KEYFRAME,
  HEADER_SIZE,
  KEY_DOWN,
  MOUSE_SCROLL,
  PROTOCOL_VERSION,
  resolveAutoCodecPreference,
  SESSION_ROLE_APP_CATALOG,
  SESSION_ROLE_DESKTOP,
  SESSION_ROLE_STANDALONE_APP,
  shouldRequestSessionAudio,
  SYS_BACK,
  TEXT_MODE_COMMIT_PASTE,
  TOUCH_DOWN,
  TYPE_HELLO,
  TYPE_PING,
  TYPE_VIDEO_FRAME,
  VIDEO_CODEC_H264,
} from '../js/protocol.js';
import { mapDomKeyboardEvent, META_CTRL_LEFT_ON, META_CTRL_ON } from '../js/input_mapper.js';

test('OpalCast 18-byte Big-Endian frame header round-trip', () => {
  const payload = encodePingPong(0x0102030405060708n);
  const wire = encodeFrame(TYPE_PING, FLAG_VIDEO_KEYFRAME, 123456789n, payload);

  assert.equal(wire.byteLength, HEADER_SIZE + 8);
  assert.deepEqual(Array.from(wire.subarray(0, 4)), [0x4f, 0x50, 0x44, 0x31]); // "OPD1"

  const decoded = decodeFrame(wire);
  assert.equal(decoded.type, TYPE_PING);
  assert.equal(decoded.flags, FLAG_VIDEO_KEYFRAME);
  assert.equal(decoded.timestampUs, 123456789n);
  assert.equal(decodePingPong(decoded.payload), 0x0102030405060708n);
});

test('HELLO JSON encodes exact OpalCast v1.1 fields', () => {
  const helloBytes = encodeHello({
    clientName: 'Chrome Test',
    clientId: 'web-abcdef123456',
    preferredCodec: 'h264',
    width: 1920,
    height: 1080,
    dpi: 240,
    fps: 120,
    bitrateKbps: 16000,
    audioEnabled: true,
    authToken: 'tok123',
  });
  const frame = decodeFrame(encodeFrame(TYPE_HELLO, 0, 1n, helloBytes));
  const parsed = JSON.parse(new TextDecoder().decode(frame.payload));
  assert.equal(parsed.protocolVersion, PROTOCOL_VERSION);
  assert.equal(parsed.clientName, 'Chrome Test');
  assert.equal(parsed.preferredCodec, 'h264');
  assert.equal(parsed.fps, 120);
  assert.equal(parsed.sessionRole, 'desktop');
  assert.equal(parsed.authToken, 'tok123');
});

test('RESIZE_CONFIG, INPUT_MOUSE, INPUT_TOUCH, INPUT_KEY, INPUT_TEXT, SYSTEM_ACTION wire layouts', () => {
  const resize = encodeResizeConfig({ width: 1925, height: 1083, dpi: 240, fps: 60, bitrateKbps: 14000 });
  assert.equal(resize.byteLength, 14);
  const rv = new DataView(resize.buffer);
  assert.equal(rv.getUint16(0, false), 1920); // 8-pixel aligned
  assert.equal(rv.getUint16(2, false), 1080);
  assert.equal(rv.getUint16(4, false), 240);
  assert.equal(rv.getUint16(6, false), 6000);
  assert.equal(rv.getInt32(8, false), 14000);

  const mouse = encodeMouseEvent(MOUSE_SCROLL, 512.5, 300.25, BUTTON_PRIMARY, 0, -1.5, 2.5);
  assert.equal(mouse.byteLength, 25);
  const mv = new DataView(mouse.buffer);
  assert.equal(mv.getUint8(0), MOUSE_SCROLL);
  assert.equal(mv.getFloat32(1, false), 512.5);
  assert.equal(mv.getFloat32(21, false), 2.5);

  const touch = encodeTouchEvent(TOUCH_DOWN, 0, [{ pointerId: 3, x: 100, y: 200, pressure: 0.75 }]);
  assert.equal(touch.byteLength, 19);
  const tv = new DataView(touch.buffer);
  assert.equal(tv.getUint8(2), 1);
  assert.equal(tv.getInt32(3, false), 3);

  const key = encodeKeyEvent(KEY_DOWN, 66, 0, 0);
  assert.equal(key.byteLength, 13);
  assert.equal(new DataView(key.buffer).getInt32(1, false), 66);

  const txt = encodeTextEvent(TEXT_MODE_COMMIT_PASTE, 42, '你好 OpalDesk');
  assert.equal(txt[0], TEXT_MODE_COMMIT_PASTE);
  assert.equal(new DataView(txt.buffer).getBigInt64(1, false), 42n);
  assert.equal(new TextDecoder().decode(txt.subarray(9)), '你好 OpalDesk');

  const sys = encodeSystemAction(SYS_BACK, 0);
  assert.equal(sys.byteLength, 5);
  assert.equal(sys[0], SYS_BACK);
});

test('FILE_TRANSFER_CHUNK encodes START+DATA+END matching Java decoder layout', () => {
  const chunk = encodeFileTransferChunk(7, FILE_FLAG_START | FILE_FLAG_DATA | FILE_FLAG_END, {
    fileName: 'note.txt',
    totalSize: 5,
    data: new TextEncoder().encode('hello'),
  });
  const v = new DataView(chunk.buffer);
  assert.equal(v.getInt32(0, false), 7);
  assert.equal(v.getUint8(4), FILE_FLAG_START | FILE_FLAG_DATA | FILE_FLAG_END);
  assert.equal(v.getUint16(5, false), 8); // "note.txt"
  assert.equal(new TextDecoder().decode(chunk.subarray(7, 15)), 'note.txt');
  assert.equal(v.getBigInt64(15, false), 5n);
  assert.equal(new TextDecoder().decode(chunk.subarray(23)), 'hello');
});

test('Downlink decoders parse VIDEO_CONFIG, AUDIO_CONFIG, AUTH_RESULT, CLIPBOARD_PUSH, FILE_ACK', () => {
  const vcBuf = new Uint8Array(11 + 4);
  const vcv = new DataView(vcBuf.buffer);
  vcv.setUint8(0, VIDEO_CODEC_H264);
  vcv.setUint16(1, 1920, false);
  vcv.setUint16(3, 1080, false);
  vcv.setUint16(5, 220, false);
  vcv.setUint8(7, 0);
  vcv.setUint16(8, 6000, false);
  vcv.setUint8(10, 0);
  vcBuf.set([0, 0, 0, 1], 11);
  const vc = decodeVideoConfig(vcBuf);
  assert.equal(vc.width, 1920);
  assert.equal(vc.fpsX100, 6000);
  assert.equal(vc.csdAnnexB.byteLength, 4);

  const acBuf = new Uint8Array(8);
  const acv = new DataView(acBuf.buffer);
  acv.setUint8(0, 1);
  acv.setInt32(1, 48000, false);
  acv.setUint8(5, 2);
  acBuf.set([0x11, 0x90], 6);
  const ac = decodeAudioConfig(acBuf);
  assert.equal(ac.sampleRate, 48000);
  assert.equal(ac.channels, 2);
  assert.deepEqual(Array.from(ac.audioSpecificConfig), [0x11, 0x90]);

  const authJson = new TextEncoder().encode(JSON.stringify({
    ok: true, authToken: 'abc', permanent: true, message: 'ok', deviceName: 'Xiaomi 14 Pro', certFingerprint: 'ff',
  }));
  const authBuf = new Uint8Array(1 + authJson.byteLength);
  authBuf[0] = 1;
  authBuf.set(authJson, 1);
  const auth = decodeAuthResult(authBuf);
  assert.equal(auth.ok, true);
  assert.equal(auth.authToken, 'abc');
  assert.equal(auth.deviceName, 'Xiaomi 14 Pro');

  const clipPayload = new Uint8Array(8 + 3);
  new DataView(clipPayload.buffer).setBigInt64(0, 99n, false);
  clipPayload.set([0x89, 0x50, 0x4e], 8);
  const clip = decodeClipboardPush(clipPayload, FLAG_CLIPBOARD_IMAGE_PNG);
  assert.equal(clip.seq, 99n);
  assert.equal(clip.isImage, true);
  assert.equal(clip.pngData.byteLength, 3);

  const ackBuf = new Uint8Array(5 + 2);
  const ackv = new DataView(ackBuf.buffer);
  ackv.setInt32(0, 12, false);
  ackv.setUint8(4, 0);
  ackBuf.set(new TextEncoder().encode('ok'), 5);
  const ack = decodeFileTransferAck(ackBuf);
  assert.equal(ack.transferId, 12);
  assert.equal(ack.pathOrError, 'ok');
});

test('Keyboard mapper maps macOS Command+C to Android Ctrl+C', () => {
  const mapped = mapDomKeyboardEvent({
    code: 'KeyC',
    metaKey: true,
    ctrlKey: false,
    shiftKey: false,
    altKey: false,
    getModifierState: () => false,
  }, true);
  assert.deepEqual(mapped, {
    androidKeyCode: 31,
    metaState: META_CTRL_ON | META_CTRL_LEFT_ON,
  });
});

test('decodeFrame rejects corrupted magic and truncated buffer', () => {
  assert.throws(() => decodeFrame(new Uint8Array([0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14])));
  assert.throws(() => decodeFrame(encodeFrame(TYPE_VIDEO_FRAME, 0, 1n, new Uint8Array([1, 2, 3])).subarray(0, 19)));
});

test('fitDimensionsToMaxBounds preserves 16:9 aspect ratio on 4K displays without pillarbox distortion', () => {
  // Wi-Fi desktop cap (2560x1600): 4K 16:9 scales proportionally to 2560x1440 (NOT 2560x1600!)
  assert.deepEqual(fitDimensionsToMaxBounds(3840, 2160, 2560, 1600, 8), { width: 2560, height: 1440 });
  // Wired USB desktop cap (3840x2160): native 4K stays crisp 1:1
  assert.deepEqual(fitDimensionsToMaxBounds(3840, 2160, 3840, 2160, 8), { width: 3840, height: 2160 });
  // Ultrawide 21:9 (3440x1440) inside 2560x1600 stays 21:9 aligned to 8px
  assert.deepEqual(fitDimensionsToMaxBounds(3440, 1440, 2560, 1600, 8), { width: 2560, height: 1064 });
});

test('resolveAutoCodecPreference auto-selects HEVC when hardware-supported and honors saved choice', () => {
  assert.equal(resolveAutoCodecPreference({ hevcSupported: true, savedCodec: '' }), 'hevc');
  assert.equal(resolveAutoCodecPreference({ hevcSupported: false, savedCodec: '' }), 'h264');
  assert.equal(resolveAutoCodecPreference({ hevcSupported: true, savedCodec: 'h264' }), 'h264');
  assert.equal(resolveAutoCodecPreference({ hevcSupported: false, savedCodec: 'hevc' }), 'h264');
});

test('encodeHello and encodeResizeConfig support wired 45-50 Mbps bitrates and clientPlatform', () => {
  const helloBytes = encodeHello({
    clientName: 'Windows Edge',
    clientPlatform: 'windows',
    bitrateKbps: 45000,
    transportHint: 'usb',
  });
  const parsed = JSON.parse(new TextDecoder().decode(helloBytes));
  assert.equal(parsed.clientPlatform, 'windows');
  assert.equal(parsed.bitrateKbps, 45000);

  const resize = encodeResizeConfig({ width: 3840, height: 2160, dpi: 240, fps: 60, bitrateKbps: 48000 });
  assert.equal(new DataView(resize.buffer).getInt32(8, false), 48000);

  const autoHello = JSON.parse(new TextDecoder().decode(encodeHello({ bitrateKbps: 0 })));
  assert.equal(autoHello.bitrateKbps, 0);
  const autoResize = encodeResizeConfig({ width: 1920, height: 1080, dpi: 240, fps: 60, bitrateKbps: 0 });
  assert.equal(new DataView(autoResize.buffer).getInt32(8, false), 0);
  const maxResize = encodeResizeConfig({ width: 3840, height: 2160, dpi: 240, fps: 60, bitrateKbps: 100000 });
  assert.equal(new DataView(maxResize.buffer).getInt32(8, false), 100000);
});

test('shouldRequestSessionAudio enables audio for both desktop and standalone app windows while keeping app_catalog silent', () => {
  assert.equal(shouldRequestSessionAudio(SESSION_ROLE_DESKTOP, true), true);
  assert.equal(shouldRequestSessionAudio(SESSION_ROLE_STANDALONE_APP, true), true);
  assert.equal(shouldRequestSessionAudio(SESSION_ROLE_APP_CATALOG, true), false);
  assert.equal(shouldRequestSessionAudio(SESSION_ROLE_DESKTOP, false), false);
  assert.equal(shouldRequestSessionAudio(SESSION_ROLE_STANDALONE_APP, false), false);
});
