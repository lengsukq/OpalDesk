/**
 * Low-overhead browser camera MJPEG capturer for OpalCast v1.2 Root-gated camera mapping.
 * Captures webcam frames via getUserMedia and encodes compact JPEG frames at ~15 fps (640x480)
 * using an offscreen canvas for `TYPE_CAMERA_FRAME (0x30)`.
 */

export const DEFAULT_CAMERA_WIDTH = 640;
export const DEFAULT_CAMERA_HEIGHT = 480;
export const DEFAULT_CAMERA_FPS = 15;

export class OpalCameraCapturer {
  constructor({
    width = DEFAULT_CAMERA_WIDTH,
    height = DEFAULT_CAMERA_HEIGHT,
    fps = DEFAULT_CAMERA_FPS,
    quality = 0.72,
    onJpegFrame = null,
    onError = null,
  } = {}) {
    this.width = width;
    this.height = height;
    this.fps = Math.max(5, Math.min(30, fps));
    this.quality = quality;
    this.onJpegFrame = onJpegFrame;
    this.onError = onError;
    this.running = false;
    this.stream = null;
    this.video = null;
    this.canvas = null;
    this.ctx = null;
    this.timer = null;
    this.encoding = false;
    this.facing = 'front';
  }

  async start(facing = this.facing) {
    const normFacing = facing === 'back' ? 'back' : 'front';
    if (this.running && this.facing === normFacing) return true;
    if (this.running) {
      this.stop();
    }
    this.facing = normFacing;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      this.onError?.('当前浏览器不支持摄像头采集（需通过 HTTPS 或 localhost 访问）');
      return false;
    }

    const facingMode = normFacing === 'back' ? 'environment' : 'user';
    try {
      let stream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: {
            width: { ideal: this.width },
            height: { ideal: this.height },
            frameRate: { ideal: this.fps, max: 30 },
            facingMode: { ideal: facingMode },
          },
          audio: false,
        });
      } catch {
        stream = await navigator.mediaDevices.getUserMedia({
          video: {
            width: { ideal: this.width },
            height: { ideal: this.height },
            frameRate: { ideal: this.fps, max: 30 },
          },
          audio: false,
        });
      }

      const video = document.createElement('video');
      video.srcObject = stream;
      video.muted = true;
      video.playsInline = true;
      await video.play();

      const canvas = document.createElement('canvas');
      canvas.width = this.width;
      canvas.height = this.height;
      const ctx = canvas.getContext('2d');

      this.stream = stream;
      this.video = video;
      this.canvas = canvas;
      this.ctx = ctx;
      this.running = true;
      this.encoding = false;

      const intervalMs = Math.max(33, Math.round(1000 / this.fps));
      this.timer = setInterval(() => this.captureFrame(), intervalMs);
      return true;
    } catch (err) {
      this.stop();
      const msg = err?.name === 'NotAllowedError'
        ? '摄像头权限被拒绝，请在浏览器地址栏允许摄像头访问'
        : `无法启动摄像头：${err?.message || err}`;
      this.onError?.(msg);
      return false;
    }
  }

  captureFrame() {
    if (!this.running || this.encoding || !this.video || !this.ctx || !this.canvas) return;
    if (this.video.readyState < 2) return;
    this.encoding = true;
    try {
      this.ctx.drawImage(this.video, 0, 0, this.canvas.width, this.canvas.height);
      this.canvas.toBlob(
        async (blob) => {
          try {
            if (!this.running || !blob) return;
            const buf = new Uint8Array(await blob.arrayBuffer());
            if (buf.byteLength > 0) {
              this.onJpegFrame?.(buf);
            }
          } finally {
            this.encoding = false;
          }
        },
        'image/jpeg',
        this.quality
      );
    } catch {
      this.encoding = false;
    }
  }

  stop() {
    this.running = false;
    this.encoding = false;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    if (this.video) {
      try { this.video.pause(); } catch {}
      this.video.srcObject = null;
      this.video = null;
    }
    if (this.stream) {
      for (const track of this.stream.getTracks()) {
        try { track.stop(); } catch {}
      }
      this.stream = null;
    }
    this.canvas = null;
    this.ctx = null;
  }
}
