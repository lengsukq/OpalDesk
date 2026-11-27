/**
 * OpalDesk 1-Click Screenshot & Screen Recording Engine.
 * Works across Windows Tauri Desktop (saves directly to Desktop + Reveal in Explorer + Clipboard PNG)
 * and Web browsers (Clipboard PNG + browser download).
 */

function formatFileStamp(date = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}_` +
    `${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
  );
}

function triggerBrowserDownload(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    URL.revokeObjectURL(url);
    a.remove();
  }, 1500);
}

export function formatRecordElapsed(ms) {
  const totalSec = Math.max(0, Math.floor(ms / 1000));
  const mins = Math.floor(totalSec / 60);
  const secs = totalSec % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

export function selectPreferredRecordingMimeType() {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
    return '';
  }
  const candidates = [
    'video/mp4;codecs=avc1.42E01E',
    'video/mp4',
    'video/webm;codecs=vp9',
    'video/webm;codecs=vp8',
    'video/webm',
  ];
  for (const mime of candidates) {
    try {
      if (MediaRecorder.isTypeSupported(mime)) return mime;
    } catch {
      // Ignore unsupported probe
    }
  }
  return '';
}

/**
 * Captures the current cast canvas as a lossless PNG, copies it to clipboard when permitted,
 * and saves it to Desktop (Tauri) or triggers browser download.
 */
export async function captureCanvasScreenshot(canvas, { desktop = null, label = 'OpalDesk' } = {}) {
  if (!canvas || !canvas.width || !canvas.height) {
    throw new Error('当前暂无可截取的画面');
  }
  const blob = await new Promise((resolve, reject) => {
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('无法导出 PNG 截图'))), 'image/png');
  });

  let copiedToClipboard = false;
  if (navigator.clipboard && typeof window.ClipboardItem === 'function') {
    try {
      await navigator.clipboard.write([new window.ClipboardItem({ 'image/png': blob })]);
      copiedToClipboard = true;
    } catch {
      // Clipboard write may fail if window is unfocused
    }
  }

  const safePrefix = String(label || 'OpalDesk').replace(/[^a-zA-Z0-9\u4e00-\u9fa5_-]+/g, '_').slice(0, 28) || 'OpalDesk';
  const fileName = `${safePrefix}_${formatFileStamp()}.png`;
  let savedPath = '';

  if (desktop?.enabled && typeof desktop.saveBinaryFile === 'function') {
    const bytes = new Uint8Array(await blob.arrayBuffer());
    const res = await desktop.saveBinaryFile({ fileName, bytes, targetDir: 'desktop' });
    savedPath = res?.savedPath || '';
  } else {
    triggerBrowserDownload(blob, fileName);
  }

  return { fileName, savedPath, copiedToClipboard, sizeBytes: blob.size };
}

/**
 * Live Canvas screen recorder outputting MP4 (when WebView2 supports H.264 MediaRecorder) or WebM.
 */
export class OpalScreenRecorder {
  constructor({ getCanvas, desktop = null, getLabel = () => 'OpalDesk', onStateChange, onSaved, onError } = {}) {
    this.getCanvas = getCanvas;
    this.desktop = desktop;
    this.getLabel = getLabel;
    this.onStateChange = onStateChange;
    this.onSaved = onSaved;
    this.onError = onError;

    this.recorder = null;
    this.stream = null;
    this.chunks = [];
    this.startedAtMs = 0;
    this.tickTimer = null;
    this.mimeType = '';
  }

  get isRecording() {
    return Boolean(this.recorder && this.recorder.state === 'recording');
  }

  toggle() {
    if (this.isRecording) {
      this.stop();
    } else {
      this.start();
    }
  }

  start() {
    if (this.isRecording) return;
    const canvas = this.getCanvas?.();
    if (!canvas || !canvas.width || !canvas.height) {
      this.onError?.('当前暂无可录制的投屏画面');
      return;
    }
    if (typeof canvas.captureStream !== 'function' || typeof MediaRecorder === 'undefined') {
      this.onError?.('当前环境不支持录屏 (MediaRecorder 不可用)');
      return;
    }

    try {
      this.mimeType = selectPreferredRecordingMimeType();
      this.stream = canvas.captureStream(30);
      const opts = { videoBitsPerSecond: 8_000_000 };
      if (this.mimeType) opts.mimeType = this.mimeType;
      this.recorder = new MediaRecorder(this.stream, opts);
      this.chunks = [];
      this.startedAtMs = Date.now();

      this.recorder.ondataavailable = (ev) => {
        if (ev.data && ev.data.size > 0) this.chunks.push(ev.data);
      };
      this.recorder.onerror = () => {
        this._cleanupStream();
        this._emitState(false);
        this.onError?.('录屏过程中发生编码错误');
      };
      this.recorder.onstop = () => {
        void this._finalizeRecording();
      };

      this.recorder.start(1000);
      this._emitState(true);
      this.tickTimer = setInterval(() => this._emitState(true), 500);
    } catch (err) {
      this._cleanupStream();
      this._emitState(false);
      this.onError?.(err?.message || '启动录屏失败');
    }
  }

  stop() {
    if (!this.recorder) return;
    clearInterval(this.tickTimer);
    this.tickTimer = null;
    try {
      if (this.recorder.state !== 'inactive') {
        this.recorder.stop();
      }
    } catch {
      this._cleanupStream();
      this._emitState(false);
    }
  }

  abort() {
    clearInterval(this.tickTimer);
    this.tickTimer = null;
    if (this.recorder) {
      this.recorder.onstop = null;
      try {
        if (this.recorder.state !== 'inactive') this.recorder.stop();
      } catch {
        // Ignore
      }
    }
    this.chunks = [];
    this._cleanupStream();
    this._emitState(false);
  }

  async _finalizeRecording() {
    clearInterval(this.tickTimer);
    this.tickTimer = null;
    const chunks = this.chunks;
    const mime = this.mimeType || 'video/webm';
    this.chunks = [];
    this._cleanupStream();
    this._emitState(false);

    if (!chunks.length) {
      this.onError?.('录屏时长过短，未生成视频文件');
      return;
    }

    try {
      const blob = new Blob(chunks, { type: mime });
      const ext = mime.includes('mp4') ? 'mp4' : 'webm';
      const safePrefix = String(this.getLabel?.() || 'OpalDesk')
        .replace(/[^a-zA-Z0-9\u4e00-\u9fa5_-]+/g, '_')
        .slice(0, 28) || 'OpalDesk';
      const fileName = `${safePrefix}_Rec_${formatFileStamp()}.${ext}`;
      let savedPath = '';

      if (this.desktop?.enabled && typeof this.desktop.saveBinaryFile === 'function') {
        const bytes = new Uint8Array(await blob.arrayBuffer());
        const res = await this.desktop.saveBinaryFile({ fileName, bytes, targetDir: 'desktop' });
        savedPath = res?.savedPath || '';
      } else {
        triggerBrowserDownload(blob, fileName);
      }

      this.onSaved?.({ fileName, savedPath, sizeBytes: blob.size, ext });
    } catch (err) {
      this.onError?.(err?.message || '保存录屏文件失败');
    }
  }

  _cleanupStream() {
    if (this.stream) {
      for (const track of this.stream.getTracks()) {
        try { track.stop(); } catch {}
      }
    }
    this.stream = null;
    this.recorder = null;
  }

  _emitState(recording) {
    this.onStateChange?.({
      recording,
      elapsedLabel: recording ? formatRecordElapsed(Date.now() - this.startedAtMs) : '00:00',
      mimeType: this.mimeType,
    });
  }
}
