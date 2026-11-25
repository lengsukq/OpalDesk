import test from 'node:test';
import assert from 'node:assert/strict';
import {
  decodeCameraState,
  decodeFrame,
  decodeMicState,
  encodeCameraConfig,
  encodeFrame,
  encodeMicConfig,
  TYPE_CAMERA_CONFIG,
  TYPE_CAMERA_FRAME,
  TYPE_CAMERA_STATE,
  TYPE_MIC_CONFIG,
  TYPE_MIC_FRAME,
  TYPE_MIC_STATE,
} from '../js/protocol.js';
import { downsampleFloat32ToPcm16 } from '../js/mic_capturer.js';

test('OpalCast mic protocol config/state/frame roundtrip', () => {
  assert.equal(TYPE_MIC_STATE, 0x17);
  assert.equal(TYPE_MIC_CONFIG, 0x2c);
  assert.equal(TYPE_MIC_FRAME, 0x2d);

  const cfgBytes = encodeMicConfig({ enabled: true, codec: 'pcm16', sampleRate: 16000, channels: 1 });
  const wireCfg = encodeFrame(TYPE_MIC_CONFIG, 0, 1234n, cfgBytes);
  const decCfgFrame = decodeFrame(wireCfg);
  assert.equal(decCfgFrame.type, TYPE_MIC_CONFIG);
  const cfgObj = JSON.parse(new TextDecoder().decode(decCfgFrame.payload));
  assert.deepEqual(cfgObj, { enabled: true, codec: 'pcm16', sampleRate: 16000, channels: 1 });

  const stateJson = new TextEncoder().encode(JSON.stringify({
    active: true,
    supported: true,
    sampleRate: 16000,
    channels: 1,
    message: '远端麦克风已启用',
  }));
  const decStateFrame = decodeFrame(encodeFrame(TYPE_MIC_STATE, 0, 1235n, stateJson));
  assert.deepEqual(decodeMicState(decStateFrame.payload), {
    active: true,
    supported: true,
    sampleRate: 16000,
    channels: 1,
    message: '远端麦克风已启用',
  });
});

test('downsampleFloat32ToPcm16 downsamples 48 kHz to 16 kHz mono LE Int16', () => {
  const input = new Float32Array(48);
  input.fill(0.5);
  const pcm = downsampleFloat32ToPcm16(input, 48000, 16000);
  assert.equal(pcm.byteLength, 32);
  const view = new DataView(pcm.buffer, pcm.byteOffset, pcm.byteLength);
  for (let i = 0; i < 16; i++) {
    const sample = view.getInt16(i * 2, true);
    assert.ok(sample >= 16380 && sample <= 16385, `expected ~16384, got ${sample}`);
  }
});

test('OpalCast Root camera protocol config/state/frame roundtrip', () => {
  assert.equal(TYPE_CAMERA_STATE, 0x18);
  assert.equal(TYPE_CAMERA_CONFIG, 0x2e);
  assert.equal(TYPE_CAMERA_FRAME, 0x30);

  const cfgBytes = encodeCameraConfig({ enabled: true, codec: 'mjpeg', width: 640, height: 480, fps: 15, facing: 'front' });
  const decCfgFrame = decodeFrame(encodeFrame(TYPE_CAMERA_CONFIG, 0, 2000n, cfgBytes));
  assert.equal(decCfgFrame.type, TYPE_CAMERA_CONFIG);
  assert.deepEqual(JSON.parse(new TextDecoder().decode(decCfgFrame.payload)), {
    enabled: true,
    codec: 'mjpeg',
    width: 640,
    height: 480,
    fps: 15,
    facing: 'front',
  });

  const stJson = new TextEncoder().encode(JSON.stringify({
    active: false,
    supported: false,
    rootRequired: true,
    width: 640,
    height: 480,
    fps: 15,
    backend: '',
    message: '摄像头映射需要 Root 权限',
    facing: 'back',
  }));
  const decStFrame = decodeFrame(encodeFrame(TYPE_CAMERA_STATE, 0, 2001n, stJson));
  assert.deepEqual(decodeCameraState(decStFrame.payload), {
    active: false,
    supported: false,
    rootRequired: true,
    width: 640,
    height: 480,
    fps: 15,
    backend: '',
    message: '摄像头映射需要 Root 权限',
    facing: 'back',
  });
});
