/**
 * OpalCast OPD1 v1.2 / v1.3 Companion, File Browser, APK Install & Call Bridge Codecs.
 * Re-exported by `protocol.js` to preserve 100% backward-compatible imports.
 */

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();
const EMPTY_U8 = new Uint8Array(0);
const FILE_PULL_FLAG_ABORT = 0x08;

export const FILE_PULL_HEADER_SIZE = 15;
export const MAX_FILE_NAME_UTF8_BYTES = 1024;
export const APK_FORMATS = Object.freeze(['apk', 'apks', 'apkm', 'xapk']);

const NOTIFICATION_EVENT_ACTIONS = new Set(['snapshot', 'posted', 'removed']);
const CALL_LIFECYCLE_STATES = new Set(['idle', 'ringing', 'offhook', 'dialing']);
const APK_TERMINAL_STAGES = new Set(['succeeded', 'failed']);

/**
 * Infers supported Android package format from filename/path extension.
 */
export function inferApkFormat(filePath) {
  const raw = String(filePath || '').trim().toLowerCase();
  const dot = raw.lastIndexOf('.');
  if (dot < 0) return null;
  const ext = raw.slice(dot + 1);
  return APK_FORMATS.includes(ext) ? ext : null;
}

export function encodeNotificationAction({ op, key = null, replyText = null, actionIndex = null }) {
  const obj = { op: String(op || 'sync') };
  if (key !== null && key !== undefined && key !== '') obj.key = String(key);
  if (replyText !== null && replyText !== undefined) obj.replyText = String(replyText);
  if (actionIndex !== null && actionIndex !== undefined) obj.actionIndex = Math.max(0, Math.round(Number(actionIndex)));
  return textEncoder.encode(JSON.stringify(obj));
}

export function decodeNotificationEvent(payload) {
  const obj = JSON.parse(textDecoder.decode(payload));
  const action = String(obj.action || '').trim();
  if (!NOTIFICATION_EVENT_ACTIONS.has(action)) {
    throw new Error(`NOTIFICATION_EVENT: unsupported action '${action}'`);
  }
  const rawItems = Array.isArray(obj.items) ? obj.items : [];
  const items = rawItems.map((it) => ({
    key: String(it?.key || ''),
    pkg: String(it?.pkg || ''),
    appLabel: String(it?.appLabel || ''),
    title: String(it?.title || ''),
    text: String(it?.text || ''),
    postTimeMs: Number(it?.postTimeMs || 0),
    clearable: it?.clearable !== false,
    canReply: Boolean(it?.canReply),
    replyLabel: String(it?.replyLabel || ''),
    otpCode: String(it?.otpCode || ''),
    actions: Array.isArray(it?.actions)
      ? it.actions.map((a) => ({
          index: Number(a?.index || 0),
          label: String(a?.label || ''),
        }))
      : [],
  }));
  return {
    action,
    key: obj.key != null ? String(obj.key) : null,
    items,
  };
}

export function encodeMediaAction({ op, positionMs = null }) {
  const obj = { op: String(op || 'toggle') };
  if (positionMs !== null && positionMs !== undefined) {
    obj.positionMs = Math.max(0, Math.round(Number(positionMs)));
  }
  return textEncoder.encode(JSON.stringify(obj));
}

export function decodeMediaState(payload) {
  const obj = JSON.parse(textDecoder.decode(payload));
  return {
    active: Boolean(obj.active),
    pkg: String(obj.pkg || ''),
    appLabel: String(obj.appLabel || ''),
    title: String(obj.title || ''),
    artist: String(obj.artist || ''),
    album: String(obj.album || ''),
    playing: Boolean(obj.playing),
    positionMs: Math.max(0, Number(obj.positionMs || 0)),
    durationMs: Math.max(0, Number(obj.durationMs || 0)),
    artworkBase64: String(obj.artworkBase64 || ''),
  };
}

export function encodeFileOpRequest({
  requestId,
  op,
  path = '/sdcard',
  newPath = null,
  transferId = null,
  maxDepth = null,
}) {
  const obj = {
    requestId: Math.round(Number(requestId || 0)),
    op: String(op || 'list'),
    path: String(path || '/sdcard'),
  };
  if (newPath !== null && newPath !== undefined && newPath !== '') obj.newPath = String(newPath);
  if (transferId !== null && transferId !== undefined) obj.transferId = Math.round(Number(transferId));
  if (maxDepth !== null && maxDepth !== undefined) obj.maxDepth = Math.max(1, Math.round(Number(maxDepth)));
  return textEncoder.encode(JSON.stringify(obj));
}

export function decodeFileOpResponse(payload) {
  const obj = JSON.parse(textDecoder.decode(payload));
  const entries = Array.isArray(obj.entries)
    ? obj.entries.map((e) => ({
        name: String(e?.name || ''),
        path: String(e?.path || ''),
        isDir: Boolean(e?.isDir),
        size: Math.max(0, Number(e?.size || 0)),
        modifiedMs: Math.max(0, Number(e?.modifiedMs || 0)),
        mime: String(e?.mime || ''),
      }))
    : [];

  let storage = null;
  if (obj.storage && typeof obj.storage === 'object') {
    const s = obj.storage;
    storage = {
      totalBytes: Math.max(0, Number(s.totalBytes || 0)),
      freeBytes: Math.max(0, Number(s.freeBytes || 0)),
      usedBytes: Math.max(0, Number(s.usedBytes || 0)),
      categories: Array.isArray(s.categories)
        ? s.categories.map((c) => ({
            name: String(c?.name || ''),
            bytes: Math.max(0, Number(c?.bytes || 0)),
            fileCount: Math.max(0, Number(c?.fileCount || 0)),
          }))
        : [],
      largeFiles: Array.isArray(s.largeFiles)
        ? s.largeFiles.map((f) => ({
            name: String(f?.name || ''),
            path: String(f?.path || ''),
            size: Math.max(0, Number(f?.size || 0)),
            modifiedMs: Math.max(0, Number(f?.modifiedMs || 0)),
          }))
        : [],
      folders: Array.isArray(s.folders)
        ? s.folders.map((f) => ({
            path: String(f?.path || ''),
            name: String(f?.name || ''),
            bytes: Math.max(0, Number(f?.bytes || 0)),
            fileCount: Math.max(0, Number(f?.fileCount || 0)),
          }))
        : [],
      apps: Array.isArray(s.apps)
        ? s.apps.map((a) => {
            const apkBytes = Math.max(0, Number(a?.apkBytes || 0));
            const dataBytes = Math.max(0, Number(a?.dataBytes || 0));
            const cacheBytes = Math.max(0, Number(a?.cacheBytes || 0));
            const totalBytes = a?.totalBytes != null
              ? Math.max(0, Number(a.totalBytes))
              : apkBytes + dataBytes + cacheBytes;
            return {
              pkg: String(a?.pkg || ''),
              appLabel: String(a?.appLabel || ''),
              apkBytes,
              dataBytes,
              cacheBytes,
              totalBytes,
            };
          })
        : [],
    };
  }

  return {
    requestId: Number(obj.requestId || 0),
    op: String(obj.op || ''),
    ok: Boolean(obj.ok),
    error: String(obj.error || ''),
    path: String(obj.path || ''),
    entries,
    storage,
    totalSize: Math.max(0, Number(obj.totalSize || 0)),
    thumbnailBase64: String(obj.thumbnailBase64 || ''),
  };
}

/**
 * Encodes `0x1C FILE_PULL_CHUNK` fixed 15-byte BE header + name + chunk bytes:
 * [4B uint32 BE transferId][1B uint8 chunkFlags][8B int64 BE totalSize][2B uint16 BE nameLen][name][data]
 */
export function encodeFilePullChunk({
  transferId,
  chunkFlags,
  totalSize = 0,
  nameOrPath = '',
  data = EMPTY_U8,
}) {
  const nameBytes = textEncoder.encode(String(nameOrPath || ''));
  if (nameBytes.byteLength > MAX_FILE_NAME_UTF8_BYTES) {
    throw new RangeError(`filePullChunk.nameOrPath exceeds ${MAX_FILE_NAME_UTF8_BYTES} bytes`);
  }
  const chunkBytes = data instanceof Uint8Array ? data : new Uint8Array(data || 0);
  const out = new Uint8Array(FILE_PULL_HEADER_SIZE + nameBytes.byteLength + chunkBytes.byteLength);
  const v = new DataView(out.buffer);
  v.setUint32(0, transferId >>> 0, false);
  v.setUint8(4, chunkFlags & 0xff);
  v.setBigInt64(5, BigInt(Math.max(0, Math.round(Number(totalSize || 0)))), false);
  v.setUint16(13, nameBytes.byteLength, false);
  if (nameBytes.byteLength > 0) out.set(nameBytes, FILE_PULL_HEADER_SIZE);
  if (chunkBytes.byteLength > 0) out.set(chunkBytes, FILE_PULL_HEADER_SIZE + nameBytes.byteLength);
  return out;
}

export function decodeFilePullChunk(payload) {
  if (payload.byteLength < FILE_PULL_HEADER_SIZE) {
    throw new Error(`FILE_PULL_CHUNK payload too short: ${payload.byteLength}`);
  }
  const v = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const transferId = v.getUint32(0, false);
  const chunkFlags = v.getUint8(4);
  const totalSize = Number(v.getBigInt64(5, false));
  const nameLen = v.getUint16(13, false);
  if (payload.byteLength < FILE_PULL_HEADER_SIZE + nameLen) {
    throw new Error(`FILE_PULL_CHUNK truncated name: expected ${nameLen} bytes`);
  }
  const nameOrPath = nameLen > 0
    ? textDecoder.decode(payload.subarray(FILE_PULL_HEADER_SIZE, FILE_PULL_HEADER_SIZE + nameLen))
    : '';
  const data = payload.subarray(FILE_PULL_HEADER_SIZE + nameLen);
  let abortErrorMessage = null;
  if ((chunkFlags & FILE_PULL_FLAG_ABORT) !== 0 && data.byteLength > 0) {
    const msg = textDecoder.decode(data).trim();
    if (msg) abortErrorMessage = msg;
  }
  return {
    transferId,
    chunkFlags,
    totalSize,
    nameOrPath,
    data,
    abortErrorMessage,
  };
}

export function encodeApkInstallRequest({
  installId,
  filePath,
  format = null,
  replaceExisting = true,
  allowDowngrade = false,
}) {
  const resolvedFormat = format || inferApkFormat(filePath) || 'apk';
  return textEncoder.encode(JSON.stringify({
    installId: Math.round(Number(installId || 0)),
    filePath: String(filePath || ''),
    format: String(resolvedFormat),
    replaceExisting: Boolean(replaceExisting),
    allowDowngrade: Boolean(allowDowngrade),
  }));
}

export function decodeApkInstallStatus(payload) {
  const obj = JSON.parse(textDecoder.decode(payload));
  const stage = String(obj.stage || '').trim();
  const rawProgress = Number(obj.progress || 0);
  return {
    installId: Number(obj.installId || 0),
    stage,
    progress: Math.max(0, Math.min(100, Math.round(Number.isFinite(rawProgress) ? rawProgress : 0))),
    packageName: String(obj.packageName || ''),
    message: String(obj.message || ''),
    isTerminal: APK_TERMINAL_STAGES.has(stage),
  };
}

export function decodeCallState(payload) {
  const obj = JSON.parse(textDecoder.decode(payload));
  const rawState = String(obj.state || '').trim();
  const state = rawState === '' ? 'idle' : rawState;
  if (!CALL_LIFECYCLE_STATES.has(state)) {
    throw new Error(`CALL_STATE: unsupported state '${state}'`);
  }
  const number = String(obj.number || '');
  const contactName = String(obj.contactName || '');
  return {
    state,
    number,
    contactName,
    startedAtMs: Math.max(0, Number(obj.startedAtMs || 0)),
    isMuted: Boolean(obj.isMuted),
    isSpeakerOn: Boolean(obj.isSpeakerOn),
    canAnswer: Boolean(obj.canAnswer),
    canReject: Boolean(obj.canReject),
    canDial: Boolean(obj.canDial),
    source: String(obj.source || ''),
    isActive: state !== 'idle',
    displayCaller: contactName || number || '未知来电',
  };
}

export function encodeCallAction({ op, number = null, smsText = null }) {
  const obj = { op: String(op || 'hangup') };
  if (number !== null && number !== undefined && number !== '') obj.number = String(number);
  if (smsText !== null && smsText !== undefined && smsText !== '') obj.smsText = String(smsText);
  return textEncoder.encode(JSON.stringify(obj));
}
