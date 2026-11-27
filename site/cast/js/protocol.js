/**
 * OpalCast v1.1 Binary Wire Codec (Big-Endian network byte order).
 * Byte-for-byte compatible with bridge `OpalCastProtocol.java` and macOS `OpalCastFrame.swift`.
 */

export const PROTOCOL_VERSION = 1;
export const HEADER_SIZE = 18;
export const MAGIC_BYTES = new Uint8Array([0x4f, 0x50, 0x44, 0x31]); // "OPD1"
export const MAX_PAYLOAD_LEN = 16 * 1024 * 1024;

// Downlink frame types (Android -> Web)
export const TYPE_VIDEO_CONFIG = 0x01;
export const TYPE_VIDEO_FRAME = 0x02;
export const TYPE_AUDIO_CONFIG = 0x03;
export const TYPE_AUDIO_FRAME = 0x04;
export const TYPE_DEVICE_STATE = 0x10;
export const TYPE_CLIPBOARD_PUSH = 0x11;
export const TYPE_AUTH_RESULT = 0x12;
export const TYPE_FILE_TRANSFER_ACK = 0x13;
export const TYPE_WIFI_UPGRADE_READY = 0x14;
export const TYPE_APP_LIST = 0x15;
export const TYPE_IME_REQUEST = 0x16;
export const TYPE_MIC_STATE = 0x17;
export const TYPE_CAMERA_STATE = 0x18;
export const TYPE_PONG = 0x1f;

// Uplink frame types (Web -> Android)
export const TYPE_HELLO = 0x20;
export const TYPE_RESIZE_CONFIG = 0x21;
export const TYPE_REQUEST_IDR = 0x22;
export const TYPE_INPUT_MOUSE = 0x23;
export const TYPE_INPUT_TOUCH = 0x24;
export const TYPE_INPUT_KEY = 0x25;
export const TYPE_INPUT_TEXT = 0x26;
export const TYPE_SYSTEM_ACTION = 0x27;
export const TYPE_FILE_TRANSFER_CHUNK = 0x28;
export const TYPE_LIST_APPS = 0x2a;
export const TYPE_WINDOW_ACTION = 0x2b;
export const TYPE_MIC_CONFIG = 0x2c;
export const TYPE_MIC_FRAME = 0x2d;
export const TYPE_CAMERA_CONFIG = 0x2e;
export const TYPE_PING = 0x2f;
export const TYPE_CAMERA_FRAME = 0x30;

export const VIDEO_CODEC_H264 = 1;
export const VIDEO_CODEC_HEVC = 2;
export const AUDIO_CODEC_AAC_LC = 1;

export const FLAG_VIDEO_KEYFRAME = 0x01;
export const FLAG_VIDEO_CONFIG_INLINE = 0x02;
export const FLAG_CLIPBOARD_IMAGE_PNG = 0x01;

export const DISPLAY_MODE_DEX = 0;
export const DISPLAY_MODE_MIRROR = 1;
export const DISPLAY_MODE_UNCHANGED = 0xff;

export const MOUSE_MOVE_ABS = 0;
export const MOUSE_BUTTON_DOWN = 1;
export const MOUSE_BUTTON_UP = 2;
export const MOUSE_SCROLL = 3;
export const MOUSE_HOVER_EXIT = 4;

export const BUTTON_PRIMARY = 1;
export const BUTTON_SECONDARY = 2;
export const BUTTON_TERTIARY = 4;
export const BUTTON_BACK = 8;
export const BUTTON_FORWARD = 16;

export const TOUCH_DOWN = 0;
export const TOUCH_UP = 1;
export const TOUCH_MOVE = 2;
export const TOUCH_CANCEL = 3;
export const TOUCH_POINTER_DOWN = 5;
export const TOUCH_POINTER_UP = 6;

export const KEY_DOWN = 0;
export const KEY_UP = 1;

export const TEXT_MODE_COMMIT_PASTE = 0;
export const TEXT_MODE_SET_CLIPBOARD_ONLY = 1;

export const SYS_HOME = 1;
export const SYS_BACK = 2;
export const SYS_RECENTS = 3;
export const SYS_TOGGLE_DISPLAY_MODE = 4;
export const SYS_TOGGLE_BLACKOUT = 5;
export const SYS_ROTATE_CW = 6;
export const SYS_SET_AUDIO_ENABLED = 7;
export const SYS_DISCONNECT = 8;

export const FILE_FLAG_START = 0x01;
export const FILE_FLAG_DATA = 0x02;
export const FILE_FLAG_END = 0x04;
export const FILE_FLAG_ABORT = 0x08;
export const FILE_ACK_OK = 0;

export const SESSION_ROLE_DESKTOP = 'desktop';
export const SESSION_ROLE_STANDALONE_APP = 'standalone_app';
export const SESSION_ROLE_APP_CATALOG = 'app_catalog';

/**
 * Returns whether an OpalCast session role should request phone audio playback.
 * Both full desktop and standalone floating app windows support audio (the Android daemon
 * arbitrates a single audio owner so concurrent windows never echo); app_catalog stays silent.
 */
export function shouldRequestSessionAudio(sessionRole, audioPrefEnabled = true) {
  if (!audioPrefEnabled) return false;
  return sessionRole === SESSION_ROLE_DESKTOP || sessionRole === SESSION_ROLE_STANDALONE_APP;
}

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();
const EMPTY_U8 = new Uint8Array(0);

export function nowTimestampUs() {
  const ms = typeof performance !== 'undefined' ? performance.now() : Date.now();
  return BigInt(Math.max(0, Math.round(ms * 1000)));
}

export function encodeFrame(type, flags = 0, timestampUs = nowTimestampUs(), payload = EMPTY_U8) {
  const data = payload instanceof Uint8Array ? payload : new Uint8Array(payload || 0);
  if (data.byteLength > MAX_PAYLOAD_LEN) {
    throw new RangeError(`Payload exceeds 16 MiB limit: ${data.byteLength}`);
  }
  const out = new Uint8Array(HEADER_SIZE + data.byteLength);
  out.set(MAGIC_BYTES, 0);
  const view = new DataView(out.buffer);
  view.setUint8(4, type & 0xff);
  view.setUint8(5, flags & 0xff);
  view.setUint32(6, data.byteLength, false);
  view.setBigUint64(10, BigInt(timestampUs), false);
  if (data.byteLength > 0) {
    out.set(data, HEADER_SIZE);
  }
  return out;
}

export function decodeFrame(bufferOrU8) {
  const bytes = bufferOrU8 instanceof Uint8Array ? bufferOrU8 : new Uint8Array(bufferOrU8);
  if (bytes.byteLength < HEADER_SIZE) {
    throw new Error(`Truncated OpalCast frame: ${bytes.byteLength} bytes`);
  }
  if (
    bytes[0] !== MAGIC_BYTES[0] ||
    bytes[1] !== MAGIC_BYTES[1] ||
    bytes[2] !== MAGIC_BYTES[2] ||
    bytes[3] !== MAGIC_BYTES[3]
  ) {
    throw new Error(`Invalid OpalCast magic: ${bytes[0]},${bytes[1]},${bytes[2]},${bytes[3]}`);
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const type = view.getUint8(4);
  const flags = view.getUint8(5);
  const payloadLen = view.getUint32(6, false);
  if (payloadLen > MAX_PAYLOAD_LEN) {
    throw new Error(`OpalCast payload exceeds 16 MiB limit: ${payloadLen}`);
  }
  if (bytes.byteLength !== HEADER_SIZE + payloadLen) {
    throw new Error(`OpalCast frame length mismatch: expected ${HEADER_SIZE + payloadLen}, got ${bytes.byteLength}`);
  }
  const timestampUs = view.getBigUint64(10, false);
  const payload = payloadLen > 0 ? bytes.subarray(HEADER_SIZE, HEADER_SIZE + payloadLen) : EMPTY_U8;
  return { type, flags, timestampUs, payload };
}

// ---- Uplink encoders (Web -> Android) ----

export function fitDimensionsToMaxBounds(reqW, reqH, maxW, maxH, align = 8) {
  const step = Math.max(2, align & ~1);
  let w = Math.max(320, Math.round(reqW) & ~(step - 1));
  let h = Math.max(320, Math.round(reqH) & ~(step - 1));
  if (maxW > 0 && maxH > 0 && (w > maxW || h > maxH)) {
    const scale = Math.min(maxW / w, maxH / h);
    const capW = Math.floor(maxW / step) * step;
    const capH = Math.floor(maxH / step) * step;
    w = Math.min(capW, Math.max(320, Math.floor((w * scale) / step) * step));
    h = Math.min(capH, Math.max(320, Math.floor((h * scale) / step) * step));
  }
  return { width: w, height: h };
}

export function resolveAutoCodecPreference({ hevcSupported = false, savedCodec = '' } = {}) {
  if (savedCodec === 'hevc' || savedCodec === 'h264') {
    if (savedCodec === 'hevc' && !hevcSupported) return 'h264';
    return savedCodec;
  }
  return hevcSupported ? 'hevc' : 'h264';
}

export function encodeHello({
  clientName = 'OpalDesk Web',
  clientId = '',
  preferredCodec = 'h264',
  width = 1920,
  height = 1080,
  dpi = 220,
  fps = 60,
  bitrateKbps = 12000,
  displayMode = DISPLAY_MODE_DEX,
  audioEnabled = true,
  authToken = '',
  transportHint = 'wifi_tls',
  clientPlatform = 'web',
  sessionRole = SESSION_ROLE_DESKTOP,
  appPackage = '',
  appActivity = '',
} = {}) {
  const json = JSON.stringify({
    protocolVersion: PROTOCOL_VERSION,
    clientName,
    clientId,
    preferredCodec,
    width: Math.max(320, Math.round(width)),
    height: Math.max(320, Math.round(height)),
    dpi: Math.max(120, Math.min(560, Math.round(dpi))),
    fps: Math.max(30, Math.min(120, Math.round(fps))),
    bitrateKbps: Math.max(2500, Math.min(50000, Math.round(bitrateKbps))),
    displayMode,
    audioEnabled: Boolean(audioEnabled),
    authToken,
    pinCode: '',
    transportHint,
    clientPlatform,
    sessionRole,
    appPackage,
    appActivity,
  });
  return textEncoder.encode(json);
}

export function encodeResizeConfig({
  width,
  height,
  dpi = 0,
  fps = 60,
  bitrateKbps = 12000,
  displayMode = DISPLAY_MODE_UNCHANGED,
  rotation = 0xff,
}) {
  const alignedW = Math.max(320, Math.min(3840, Math.round(width) & ~7));
  const alignedH = Math.max(320, Math.min(2560, Math.round(height) & ~7));
  const wireDpi = dpi <= 0 ? 0 : Math.max(120, Math.min(560, Math.round(dpi)));
  const buf = new ArrayBuffer(14);
  const v = new DataView(buf);
  v.setUint16(0, alignedW, false);
  v.setUint16(2, alignedH, false);
  v.setUint16(4, wireDpi, false);
  v.setUint16(6, Math.round(Math.max(30, Math.min(120, fps)) * 100), false);
  v.setInt32(8, Math.max(2000, Math.min(50000, Math.round(bitrateKbps))), false);
  v.setUint8(12, displayMode & 0xff);
  v.setUint8(13, rotation & 0xff);
  return new Uint8Array(buf);
}

export function encodeMouseEvent(action, x, y, buttons, actionButton, hScroll = 0, vScroll = 0) {
  const buf = new ArrayBuffer(25);
  const v = new DataView(buf);
  v.setUint8(0, action & 0xff);
  v.setFloat32(1, x, false);
  v.setFloat32(5, y, false);
  v.setInt32(9, buttons | 0, false);
  v.setInt32(13, actionButton | 0, false);
  v.setFloat32(17, hScroll, false);
  v.setFloat32(21, vScroll, false);
  return new Uint8Array(buf);
}

export function encodeTouchEvent(action, actionIndex, pointers) {
  const count = Math.min(10, pointers.length);
  const buf = new ArrayBuffer(3 + count * 16);
  const v = new DataView(buf);
  v.setUint8(0, action & 0xff);
  v.setUint8(1, actionIndex & 0xff);
  v.setUint8(2, count & 0xff);
  let off = 3;
  for (let i = 0; i < count; i++) {
    const p = pointers[i];
    v.setInt32(off, p.pointerId | 0, false);
    v.setFloat32(off + 4, p.x, false);
    v.setFloat32(off + 8, p.y, false);
    v.setFloat32(off + 12, p.pressure ?? 1.0, false);
    off += 16;
  }
  return new Uint8Array(buf);
}

export function encodeKeyEvent(action, androidKeyCode, metaState = 0, repeatCount = 0) {
  const buf = new ArrayBuffer(13);
  const v = new DataView(buf);
  v.setUint8(0, action & 0xff);
  v.setInt32(1, androidKeyCode | 0, false);
  v.setInt32(5, metaState | 0, false);
  v.setInt32(9, repeatCount | 0, false);
  return new Uint8Array(buf);
}

export function encodeTextEvent(mode, seq, text) {
  const utf8 = textEncoder.encode(text || '');
  const out = new Uint8Array(9 + utf8.byteLength);
  const v = new DataView(out.buffer);
  v.setUint8(0, mode & 0xff);
  v.setBigInt64(1, BigInt(seq), false);
  out.set(utf8, 9);
  return out;
}

export function encodeImageClipboardEvent(mode, seq, pngBytes) {
  const data = pngBytes instanceof Uint8Array ? pngBytes : new Uint8Array(pngBytes);
  const out = new Uint8Array(9 + data.byteLength);
  const v = new DataView(out.buffer);
  v.setUint8(0, mode & 0xff);
  v.setBigInt64(1, BigInt(seq), false);
  out.set(data, 9);
  return out;
}

export function encodeSystemAction(action, arg = 0) {
  const buf = new ArrayBuffer(5);
  const v = new DataView(buf);
  v.setUint8(0, action & 0xff);
  v.setInt32(1, arg | 0, false);
  return new Uint8Array(buf);
}

export function encodeFileTransferChunk(transferId, chunkFlags, { fileName = '', totalSize = 0, data = EMPTY_U8 } = {}) {
  const hasStart = (chunkFlags & FILE_FLAG_START) !== 0;
  const hasData = (chunkFlags & FILE_FLAG_DATA) !== 0 && data && data.byteLength > 0;
  const nameBytes = hasStart ? textEncoder.encode(fileName) : EMPTY_U8;
  const totalLen = 5 + (hasStart ? 2 + nameBytes.byteLength + 8 : 0) + (hasData ? data.byteLength : 0);
  const out = new Uint8Array(totalLen);
  const v = new DataView(out.buffer);
  let off = 0;
  v.setInt32(off, transferId | 0, false);
  off += 4;
  v.setUint8(off, chunkFlags & 0xff);
  off += 1;
  if (hasStart) {
    v.setUint16(off, nameBytes.byteLength, false);
    off += 2;
    out.set(nameBytes, off);
    off += nameBytes.byteLength;
    v.setBigInt64(off, BigInt(totalSize), false);
    off += 8;
  }
  if (hasData) {
    out.set(data, off);
  }
  return out;
}

export function encodeWindowAction(actionObj) {
  return textEncoder.encode(JSON.stringify(actionObj));
}

export function encodeMicConfig({ enabled, codec = 'pcm16', sampleRate = 16000, channels = 1 }) {
  return textEncoder.encode(JSON.stringify({
    enabled: Boolean(enabled),
    codec: String(codec || 'pcm16'),
    sampleRate: Math.max(8000, Math.min(48000, Math.round(sampleRate || 16000))),
    channels: Math.max(1, Math.min(2, Math.round(channels || 1))),
  }));
}

export function encodeCameraConfig({ enabled, codec = 'mjpeg', width = 640, height = 480, fps = 15, facing = 'front' }) {
  return textEncoder.encode(JSON.stringify({
    enabled: Boolean(enabled),
    codec: String(codec || 'mjpeg'),
    width: Math.max(160, Math.min(1920, Math.round(width || 640))),
    height: Math.max(120, Math.min(1080, Math.round(height || 480))),
    fps: Math.max(5, Math.min(30, Math.round(fps || 15))),
    facing: facing === 'back' ? 'back' : 'front',
  }));
}

export function encodePingPong(pingId) {
  const buf = new ArrayBuffer(8);
  new DataView(buf).setBigInt64(0, BigInt(pingId), false);
  return new Uint8Array(buf);
}

// ---- Downlink decoders (Android -> Web) ----

export function decodeVideoConfig(payload) {
  if (payload.byteLength < 11) throw new Error('VIDEO_CONFIG payload too short');
  const v = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  return {
    codec: v.getUint8(0),
    width: v.getUint16(1, false),
    height: v.getUint16(3, false),
    dpi: v.getUint16(5, false),
    rotation: v.getUint8(7),
    fpsX100: v.getUint16(8, false),
    displayMode: v.getUint8(10),
    csdAnnexB: payload.subarray(11),
  };
}

export function decodeAudioConfig(payload) {
  if (payload.byteLength < 6) throw new Error('AUDIO_CONFIG payload too short');
  const v = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  return {
    codec: v.getUint8(0),
    sampleRate: v.getInt32(1, false),
    channels: v.getUint8(5),
    audioSpecificConfig: payload.subarray(6),
  };
}

export function decodeAuthResult(payload) {
  if (payload.byteLength < 2) throw new Error('AUTH_RESULT payload too short');
  const okByte = payload[0] === 1;
  const obj = JSON.parse(textDecoder.decode(payload.subarray(1)));
  return {
    ok: okByte && Boolean(obj.ok),
    authToken: obj.authToken || '',
    permanent: Boolean(obj.permanent),
    message: obj.message || '',
    deviceName: obj.deviceName || 'Android',
    certFingerprint: obj.certFingerprint || '',
  };
}

export function decodeDeviceState(payload) {
  return JSON.parse(textDecoder.decode(payload));
}

export function decodeAppList(payload) {
  const obj = JSON.parse(textDecoder.decode(payload));
  return Array.isArray(obj.apps) ? obj.apps : [];
}

export function decodeImeRequest(payload) {
  const obj = JSON.parse(textDecoder.decode(payload));
  return { show: Boolean(obj.show), inputType: Number(obj.inputType || 0) };
}

export function decodeMicState(payload) {
  const obj = JSON.parse(textDecoder.decode(payload));
  return {
    active: Boolean(obj.active),
    supported: Boolean(obj.supported),
    sampleRate: Number(obj.sampleRate || 16000),
    channels: Number(obj.channels || 1),
    message: String(obj.message || ''),
  };
}

export function decodeCameraState(payload) {
  const obj = JSON.parse(textDecoder.decode(payload));
  return {
    active: Boolean(obj.active),
    supported: Boolean(obj.supported),
    rootRequired: Boolean(obj.rootRequired),
    width: Number(obj.width || 640),
    height: Number(obj.height || 480),
    fps: Number(obj.fps || 15),
    backend: String(obj.backend || ''),
    message: String(obj.message || ''),
    facing: String(obj.facing || ''),
  };
}

export function decodeClipboardPush(payload, flags) {
  if (payload.byteLength < 8) throw new Error('CLIPBOARD_PUSH payload too short');
  const v = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const seq = v.getBigInt64(0, false);
  const body = payload.subarray(8);
  const isImage = (flags & FLAG_CLIPBOARD_IMAGE_PNG) !== 0;
  return isImage
    ? { seq, isImage: true, text: '', pngData: body }
    : { seq, isImage: false, text: textDecoder.decode(body), pngData: null };
}

export function decodeFileTransferAck(payload) {
  if (payload.byteLength < 5) throw new Error('FILE_TRANSFER_ACK payload too short');
  const v = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  return {
    transferId: v.getInt32(0, false),
    status: v.getUint8(4),
    pathOrError: textDecoder.decode(payload.subarray(5)),
  };
}

export function decodePingPong(payload) {
  if (payload.byteLength < 8) throw new Error('PING/PONG payload too short');
  return new DataView(payload.buffer, payload.byteOffset, payload.byteLength).getBigInt64(0, false);
}
