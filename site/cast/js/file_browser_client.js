/**
 * OpalDesk Remote File Browser, 3-Tab Storage Analyzer & Wireless APK Installer Client.
 * Pure OPD1 v1.2/v1.3 RPC & streaming transfer state machine (`0x33/0x1B/0x1C/0x34/0x1D/0x28/0x13`).
 * Zero DOM or Tauri dependencies — works identically on Windows Tauri Desktop & Web Cast.
 */

import {
  TYPE_FILE_OP_REQUEST,
  TYPE_FILE_OP_RESPONSE,
  TYPE_FILE_PULL_CHUNK,
  TYPE_APK_INSTALL_REQUEST,
  TYPE_APK_INSTALL_STATUS,
  TYPE_FILE_TRANSFER_CHUNK,
  TYPE_FILE_TRANSFER_ACK,
  FILE_FLAG_START,
  FILE_FLAG_DATA,
  FILE_FLAG_END,
  FILE_FLAG_ABORT,
  encodeFileOpRequest,
  decodeFileOpResponse,
  decodeFilePullChunk,
  encodeApkInstallRequest,
  decodeApkInstallStatus,
  encodeFileTransferChunk,
  decodeFileTransferAck,
  inferApkFormat,
} from './protocol.js';

export const DEFAULT_REMOTE_ROOT = '/sdcard';
export const QUICK_NAV_SHORTCUTS = Object.freeze([
  { label: '内部存储', path: '/sdcard', icon: 'home' },
  { label: '下载', path: '/sdcard/Download', icon: 'download' },
  { label: '相册 DCIM', path: '/sdcard/DCIM', icon: 'photo' },
  { label: '图片 Pictures', path: '/sdcard/Pictures', icon: 'image' },
  { label: '文档 Documents', path: '/sdcard/Documents', icon: 'doc' },
  { label: '微信文件', path: '/sdcard/Android/data/com.tencent.mm/MicroMsg/Download', icon: 'chat' },
]);

export const STORAGE_CATEGORY_META = Object.freeze({
  photos: { label: '图片与相册', color: '#0071e3' },
  images: { label: '图片与相册', color: '#0071e3' },
  videos: { label: '视频录像', color: '#8b5cf6' },
  audio: { label: '音频音乐', color: '#ec4899' },
  documents: { label: '文档资料', color: '#17964b' },
  apks: { label: '安装包 APK', color: '#f59e0b' },
  archives: { label: '压缩归档', color: '#06b6d4' },
  downloads: { label: '下载目录', color: '#6366f1' },
  other: { label: '其他文件', color: '#64748b' },
});

const UPLOAD_CHUNK_BYTES = 240 * 1024;
const DEFAULT_RPC_TIMEOUT_MS = 20_000;
const STORAGE_SCAN_TIMEOUT_MS = 90_000;
const APK_INSTALL_TIMEOUT_MS = 180_000;

export function normalizeRemotePath(rawPath, fallback = DEFAULT_REMOTE_ROOT) {
  const raw = String(rawPath || '').trim().replace(/\\/g, '/');
  if (!raw) return fallback;
  const parts = raw.split('/').filter(Boolean);
  const stack = [];
  for (const part of parts) {
    if (part === '.') continue;
    if (part === '..') {
      if (stack.length > 0) stack.pop();
      continue;
    }
    stack.push(part);
  }
  const joined = '/' + stack.join('/');
  return joined || '/';
}

export function joinRemotePath(dirPath, name) {
  const cleanDir = normalizeRemotePath(dirPath);
  const cleanName = String(name || '').replace(/[/\\]+/g, '').trim();
  if (!cleanName) return cleanDir;
  return cleanDir === '/' ? `/${cleanName}` : `${cleanDir}/${cleanName}`;
}

export function getParentRemotePath(rawPath) {
  const norm = normalizeRemotePath(rawPath);
  if (norm === '/' || norm === DEFAULT_REMOTE_ROOT) return DEFAULT_REMOTE_ROOT;
  const idx = norm.lastIndexOf('/');
  if (idx <= 0) return DEFAULT_REMOTE_ROOT;
  const parent = norm.slice(0, idx);
  return parent.startsWith(DEFAULT_REMOTE_ROOT) ? parent : DEFAULT_REMOTE_ROOT;
}

export function buildPathBreadcrumbs(rawPath) {
  const norm = normalizeRemotePath(rawPath);
  const parts = norm.split('/').filter(Boolean);
  const crumbs = [];
  let acc = '';
  for (const part of parts) {
    acc += '/' + part;
    crumbs.push({
      label: acc === '/sdcard' ? '手机存储' : part,
      path: acc,
    });
  }
  return crumbs.length > 0 ? crumbs : [{ label: '手机存储', path: DEFAULT_REMOTE_ROOT }];
}

export function formatBytes(bytes) {
  const val = Number(bytes);
  if (!Number.isFinite(val) || val <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let idx = 0;
  let num = val;
  while (num >= 1024 && idx < units.length - 1) {
    num /= 1024;
    idx++;
  }
  return idx === 0 ? `${Math.round(num)} B` : `${num.toFixed(num >= 100 ? 0 : num >= 10 ? 1 : 2)} ${units[idx]}`;
}

export function formatFileTimestamp(ms) {
  const num = Number(ms || 0);
  if (!Number.isFinite(num) || num <= 0) return '—';
  const d = new Date(num);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${y}-${m}-${day} ${hh}:${mm}`;
}

export function classifyEntryKind(entry) {
  if (!entry) return 'file';
  if (entry.isDir) return 'dir';
  const name = String(entry.name || entry.path || '').toLowerCase();
  const mime = String(entry.mime || '').toLowerCase();
  if (inferApkFormat(name)) return 'apk';
  if (mime.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp|heic|heif|avif)$/.test(name)) return 'image';
  if (mime.startsWith('video/') || /\.(mp4|mkv|mov|webm|avi|3gp|m4v)$/.test(name)) return 'video';
  if (mime.startsWith('audio/') || /\.(mp3|flac|aac|m4a|wav|ogg|opus)$/.test(name)) return 'audio';
  if (/\.(zip|rar|7z|tar|gz|tgz|bz2|xz)$/.test(name)) return 'archive';
  if (mime.startsWith('text/') || mime.includes('pdf') || /\.(pdf|docx?|xlsx?|pptx?|txt|md|json|xml|csv|log)$/.test(name)) return 'doc';
  return 'file';
}

export function isPreviewableImageEntry(entry) {
  return classifyEntryKind(entry) === 'image';
}

export function sortRemoteEntries(entries, sortBy = 'name', sortDir = 'asc') {
  if (!Array.isArray(entries)) return [];
  const dir = sortDir === 'desc' ? -1 : 1;
  return [...entries].sort((a, b) => {
    if (Boolean(a.isDir) !== Boolean(b.isDir)) return a.isDir ? -1 : 1;
    if (sortBy === 'size') {
      return ((Number(a.size || 0) - Number(b.size || 0)) * dir) || a.name.localeCompare(b.name);
    }
    if (sortBy === 'modified') {
      return ((Number(a.modifiedMs || 0) - Number(b.modifiedMs || 0)) * dir) || a.name.localeCompare(b.name);
    }
    return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }) * dir;
  });
}

export function getStorageCategoryMeta(name) {
  const key = String(name || 'other').trim().toLowerCase();
  return STORAGE_CATEGORY_META[key] || { label: name || '其他', color: '#64748b' };
}

/**
 * Pure protocol RPC + streaming pull/upload/APK install client.
 */
export class OpalFileBrowserClient {
  constructor({ sendFrame, timeoutMs = DEFAULT_RPC_TIMEOUT_MS } = {}) {
    this.sendFrame = sendFrame;
    this.timeoutMs = timeoutMs;
    this.nextRequestId = 1;
    this.nextTransferId = 10_000;
    this.nextInstallId = 1;
    this.pendingRpc = new Map();
    this.activePulls = new Map();
    this.activeUploads = new Map();
    this.activeInstalls = new Map();
  }

  setSendFrame(sendFrame) {
    this.sendFrame = sendFrame;
  }

  _requireSender() {
    if (typeof this.sendFrame !== 'function') {
      throw new Error('未连接到手机端会话');
    }
  }

  _sendFileOp(payloadObj, timeoutMs = this.timeoutMs) {
    this._requireSender();
    const requestId = this.nextRequestId++;
    const payload = encodeFileOpRequest({ ...payloadObj, requestId });
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRpc.delete(requestId);
        reject(new Error(`文件操作超时 (${payloadObj.op})`));
      }, timeoutMs);
      this.pendingRpc.set(requestId, { resolve, reject, timer, op: payloadObj.op });
      const sent = this.sendFrame(TYPE_FILE_OP_REQUEST, 0, payload);
      if (sent === false) {
        clearTimeout(timer);
        this.pendingRpc.delete(requestId);
        reject(new Error('当前连接不可用，无法发送文件请求'));
      }
    });
  }

  async list(path = DEFAULT_REMOTE_ROOT) {
    const resp = await this._sendFileOp({ op: 'list', path: normalizeRemotePath(path) });
    if (!resp.ok) throw new Error(resp.error || '读取目录失败');
    return resp;
  }

  async stat(path) {
    const resp = await this._sendFileOp({ op: 'stat', path: normalizeRemotePath(path) });
    if (!resp.ok) throw new Error(resp.error || '读取文件信息失败');
    return resp;
  }

  async mkdir(path) {
    const resp = await this._sendFileOp({ op: 'mkdir', path: normalizeRemotePath(path) });
    if (!resp.ok) throw new Error(resp.error || '新建文件夹失败');
    return resp;
  }

  async rename(path, newPath) {
    const resp = await this._sendFileOp({
      op: 'rename',
      path: normalizeRemotePath(path),
      newPath: normalizeRemotePath(newPath),
    });
    if (!resp.ok) throw new Error(resp.error || '重命名失败');
    return resp;
  }

  async deletePath(path) {
    const resp = await this._sendFileOp({ op: 'delete', path: normalizeRemotePath(path) });
    if (!resp.ok) throw new Error(resp.error || '删除失败');
    return resp;
  }

  async scanStorage(path = DEFAULT_REMOTE_ROOT, maxDepth = 4) {
    const resp = await this._sendFileOp(
      { op: 'storage_scan', path: normalizeRemotePath(path), maxDepth },
      STORAGE_SCAN_TIMEOUT_MS
    );
    if (!resp.ok || !resp.storage) throw new Error(resp.error || '存储分析失败');
    return resp.storage;
  }

  async getThumbnail(path) {
    const resp = await this._sendFileOp({ op: 'thumbnail', path: normalizeRemotePath(path) }, 12_000);
    if (!resp.ok) throw new Error(resp.error || '缩略图加载失败');
    return resp.thumbnailBase64 || '';
  }

  /**
   * Pulls a remote Android file via `0x33 op:"pull"` + streaming `0x1C` binary chunks.
   * Callers can supply `onChunk(uint8Array, meta)` to stream straight to disk (Windows Tauri),
   * or omit `onChunk` to collect chunks in memory and receive `{ name, totalSize, bytes }`.
   */
  async pullFile(remotePath, { onStart, onChunk, onProgress } = {}) {
    this._requireSender();
    const normPath = normalizeRemotePath(remotePath);
    const transferId = this.nextTransferId++;

    const pullPromise = new Promise((resolve, reject) => {
      const state = {
        transferId,
        remotePath: normPath,
        fileName: normPath.split('/').pop() || 'download.bin',
        totalSize: 0,
        receivedBytes: 0,
        chunks: onChunk ? null : [],
        started: false,
        onStart,
        onChunk,
        onProgress,
        resolve,
        reject,
        // Serialize async sink callbacks (e.g. Tauri IPC chunk writes)
        sinkChain: Promise.resolve(),
      };
      this.activePulls.set(transferId, state);
    });

    try {
      const resp = await this._sendFileOp({ op: 'pull', path: normPath, transferId });
      if (!resp.ok) {
        const st = this.activePulls.get(transferId);
        this.activePulls.delete(transferId);
        const err = new Error(resp.error || '发起文件下载失败');
        if (st) st.reject(err);
        throw err;
      }
    } catch (err) {
      this.activePulls.delete(transferId);
      throw err;
    }

    return pullPromise;
  }

  /**
   * Uploads a browser `File` (or `{ name, size, arrayBuffer }`) via `0x28` chunk frames,
   * resolving with the remote path returned in `0x13 FILE_TRANSFER_ACK`.
   */
  async uploadFile(file, { remoteName = '', onProgress } = {}) {
    this._requireSender();
    const transferId = this.nextTransferId++;
    const fileName = String(remoteName || file.name || `upload_${transferId}.bin`);
    const buf = new Uint8Array(await file.arrayBuffer());
    const totalSize = buf.byteLength;

    const ackPromise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.activeUploads.delete(transferId);
        reject(new Error(`文件上传确认超时 (${fileName})`));
      }, 60_000);
      this.activeUploads.set(transferId, { resolve, reject, timer, fileName });
    });

    const sentStart = this.sendFrame(
      TYPE_FILE_TRANSFER_CHUNK,
      0,
      encodeFileTransferChunk(transferId, FILE_FLAG_START, { fileName, totalSize })
    );
    if (sentStart === false) {
      const entry = this.activeUploads.get(transferId);
      if (entry) {
        clearTimeout(entry.timer);
        this.activeUploads.delete(transferId);
      }
      throw new Error('当前连接不可用，无法上传文件');
    }

    for (let offset = 0; offset < totalSize; offset += UPLOAD_CHUNK_BYTES) {
      const end = Math.min(offset + UPLOAD_CHUNK_BYTES, totalSize);
      this.sendFrame(
        TYPE_FILE_TRANSFER_CHUNK,
        0,
        encodeFileTransferChunk(transferId, FILE_FLAG_DATA, { data: buf.subarray(offset, end) })
      );
      if (onProgress) onProgress(end, totalSize);
      if (offset % (UPLOAD_CHUNK_BYTES * 4) === 0) {
        await new Promise((r) => setTimeout(r, 0));
      }
    }
    this.sendFrame(TYPE_FILE_TRANSFER_CHUNK, 0, encodeFileTransferChunk(transferId, FILE_FLAG_END));
    return ackPromise;
  }

  /**
   * Requests Android silent/guided APK installation via `0x34 APK_INSTALL_REQUEST`
   * and monitors `0x1D APK_INSTALL_STATUS` until terminal stage.
   */
  async installApk(remotePath, { format = null, replaceExisting = true, allowDowngrade = false, onStatus } = {}) {
    this._requireSender();
    const normPath = normalizeRemotePath(remotePath);
    const resolvedFormat = format || inferApkFormat(normPath);
    if (!resolvedFormat) {
      throw new Error('仅支持 .apk / .apks / .apkm / .xapk 安装包格式');
    }
    const installId = this.nextInstallId++;
    const payload = encodeApkInstallRequest({
      installId,
      filePath: normPath,
      format: resolvedFormat,
      replaceExisting,
      allowDowngrade,
    });

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.activeInstalls.delete(installId);
        reject(new Error('APK 安装超时，请检查手机端状态'));
      }, APK_INSTALL_TIMEOUT_MS);
      this.activeInstalls.set(installId, { resolve, reject, timer, onStatus });
      const sent = this.sendFrame(TYPE_APK_INSTALL_REQUEST, 0, payload);
      if (sent === false) {
        clearTimeout(timer);
        this.activeInstalls.delete(installId);
        reject(new Error('当前连接不可用，无法发送安装请求'));
      }
    });
  }

  /**
   * Handles downlink OPD1 frames related to files & APK install.
   * Returns `true` if handled.
   */
  handleFrame(frame) {
    if (!frame) return false;
    switch (frame.type) {
      case TYPE_FILE_OP_RESPONSE:
        this._handleFileOpResponse(frame.payload);
        return true;
      case TYPE_FILE_PULL_CHUNK:
        this._handleFilePullChunk(frame.payload);
        return true;
      case TYPE_APK_INSTALL_STATUS:
        this._handleApkInstallStatus(frame.payload);
        return true;
      case TYPE_FILE_TRANSFER_ACK:
        return this._handleUploadAck(frame.payload);
      default:
        return false;
    }
  }

  _handleFileOpResponse(payload) {
    let resp;
    try {
      resp = decodeFileOpResponse(payload);
    } catch {
      return;
    }
    const pending = this.pendingRpc.get(resp.requestId);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pendingRpc.delete(resp.requestId);
    pending.resolve(resp);
  }

  _handleFilePullChunk(payload) {
    let chunk;
    try {
      chunk = decodeFilePullChunk(payload);
    } catch {
      return;
    }
    const state = this.activePulls.get(chunk.transferId);
    if (!state) return;

    const flags = chunk.chunkFlags;
    if ((flags & FILE_FLAG_ABORT) !== 0) {
      this.activePulls.delete(chunk.transferId);
      const err = new Error(chunk.abortErrorMessage || '手机端中断了文件下载');
      state.sinkChain = state.sinkChain.then(() => state.reject(err), () => state.reject(err));
      return;
    }

    if ((flags & FILE_FLAG_START) !== 0 && !state.started) {
      state.started = true;
      if (chunk.totalSize > 0) state.totalSize = chunk.totalSize;
      if (chunk.nameOrPath) {
        const parts = chunk.nameOrPath.split('/');
        state.fileName = parts[parts.length - 1] || state.fileName;
      }
      if (state.onStart) {
        state.sinkChain = state.sinkChain.then(() =>
          state.onStart({ fileName: state.fileName, totalSize: state.totalSize, remotePath: state.remotePath })
        );
      }
    }

    if (chunk.data && chunk.data.byteLength > 0) {
      const dataCopy = chunk.data;
      state.receivedBytes += dataCopy.byteLength;
      const receivedSnapshot = state.receivedBytes;
      const totalSnapshot = state.totalSize;
      if (state.onChunk) {
        state.sinkChain = state.sinkChain.then(() =>
          state.onChunk(dataCopy, { receivedBytes: receivedSnapshot, totalSize: totalSnapshot })
        );
      } else if (state.chunks) {
        state.chunks.push(dataCopy);
      }
      if (state.onProgress) {
        state.sinkChain = state.sinkChain.then(() => state.onProgress(receivedSnapshot, totalSnapshot));
      }
    }

    if ((flags & FILE_FLAG_END) !== 0) {
      this.activePulls.delete(chunk.transferId);
      state.sinkChain
        .then(() => {
          let bytes = null;
          if (state.chunks) {
            const total = state.chunks.reduce((acc, c) => acc + c.byteLength, 0);
            bytes = new Uint8Array(total);
            let off = 0;
            for (const c of state.chunks) {
              bytes.set(c, off);
              off += c.byteLength;
            }
          }
          state.resolve({
            transferId: state.transferId,
            fileName: state.fileName,
            remotePath: state.remotePath,
            totalSize: state.totalSize || state.receivedBytes,
            receivedBytes: state.receivedBytes,
            bytes,
          });
        })
        .catch((err) => state.reject(err));
    }
  }

  _handleUploadAck(payload) {
    let ack;
    try {
      ack = decodeFileTransferAck(payload);
    } catch {
      return false;
    }
    const pending = this.activeUploads.get(ack.transferId);
    if (!pending) return false;
    clearTimeout(pending.timer);
    this.activeUploads.delete(ack.transferId);
    if (ack.status === 0) {
      pending.resolve({
        transferId: ack.transferId,
        remotePath: ack.pathOrError || `/sdcard/Download/${pending.fileName}`,
        fileName: pending.fileName,
      });
    } else {
      pending.reject(new Error(ack.pathOrError || `上传 ${pending.fileName} 失败`));
    }
    return true;
  }

  _handleApkInstallStatus(payload) {
    let status;
    try {
      status = decodeApkInstallStatus(payload);
    } catch {
      return;
    }
    const pending = this.activeInstalls.get(status.installId);
    if (!pending) return;
    if (pending.onStatus) {
      try {
        pending.onStatus(status);
      } catch {
        // Ignore observer errors
      }
    }
    if (status.isTerminal) {
      clearTimeout(pending.timer);
      this.activeInstalls.delete(status.installId);
      if (status.stage === 'succeeded') {
        pending.resolve(status);
      } else {
        pending.reject(new Error(status.message || 'APK 安装失败'));
      }
    }
  }

  reset(reason = '会话已断开') {
    const err = new Error(reason);
    for (const [, p] of this.pendingRpc) {
      clearTimeout(p.timer);
      p.reject(err);
    }
    this.pendingRpc.clear();
    for (const [, p] of this.activePulls) {
      p.reject(err);
    }
    this.activePulls.clear();
    for (const [, p] of this.activeUploads) {
      clearTimeout(p.timer);
      p.reject(err);
    }
    this.activeUploads.clear();
    for (const [, p] of this.activeInstalls) {
      clearTimeout(p.timer);
      p.reject(err);
    }
    this.activeInstalls.clear();
  }
}
