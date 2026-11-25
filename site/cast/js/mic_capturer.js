/**
 * Low-latency browser microphone capturer for OpalCast v1.2 uplink audio.
 * Captures mono speech with browser AEC/NS/AGC and downsamples to 16 kHz signed 16-bit LE PCM
 * in 20 ms chunks (320 samples = 640 bytes) for minimal jitter and zero codec overhead.
 */

export const DEFAULT_MIC_SAMPLE_RATE = 16000;
export const DEFAULT_MIC_CHANNELS = 1;
const CHUNK_SAMPLES = 320; // 20 ms at 16 kHz

/**
 * Downsamples Float32 [-1, 1] mono audio from `inputSampleRate` to `targetSampleRate`
 * and converts to little-endian Int16 PCM bytes.
 */
export function downsampleFloat32ToPcm16(input, inputSampleRate, targetSampleRate = DEFAULT_MIC_SAMPLE_RATE) {
  if (!input || input.length === 0 || !inputSampleRate || inputSampleRate <= 0) {
    return new Uint8Array(0);
  }
  const targetRate = Math.max(8000, Math.min(48000, Math.round(targetSampleRate || DEFAULT_MIC_SAMPLE_RATE)));
  let samples;
  if (Math.abs(inputSampleRate - targetRate) < 1) {
    samples = input;
  } else {
    const ratio = inputSampleRate / targetRate;
    const outLen = Math.max(1, Math.floor(input.length / ratio));
    samples = new Float32Array(outLen);
    let offsetResult = 0;
    let offsetBuffer = 0;
    while (offsetResult < outLen) {
      const nextOffsetBuffer = Math.min(input.length, Math.round((offsetResult + 1) * ratio));
      let accum = 0;
      let count = 0;
      for (let i = offsetBuffer; i < nextOffsetBuffer; i++) {
        accum += input[i];
        count++;
      }
      samples[offsetResult] = count > 0 ? accum / count : 0;
      offsetResult++;
      offsetBuffer = nextOffsetBuffer;
    }
  }

  const out = new Uint8Array(samples.length * 2);
  const view = new DataView(out.buffer);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(i * 2, s < 0 ? Math.round(s * 0x8000) : Math.round(s * 0x7fff), true);
  }
  return out;
}

const WORKLET_SOURCE = `
class OpalMicProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const input = inputs[0];
    if (input && input[0] && input[0].length > 0) {
      const copy = new Float32Array(input[0].length);
      copy.set(input[0]);
      this.port.postMessage(copy, [copy.buffer]);
    }
    return true;
  }
}
registerProcessor('opal-mic-processor', OpalMicProcessor);
`;

export class OpalMicCapturer {
  constructor({ sampleRate = DEFAULT_MIC_SAMPLE_RATE, onPcmFrame = null, onError = null } = {}) {
    this.targetSampleRate = sampleRate;
    this.onPcmFrame = onPcmFrame;
    this.onError = onError;
    this.running = false;
    this.stream = null;
    this.ctx = null;
    this.source = null;
    this.node = null;
    this.silentGain = null;
    this.workletUrl = null;
    this.pendingBytes = new Uint8Array(0);
  }

  async start() {
    if (this.running) return true;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      this.onError?.('当前浏览器不支持麦克风采集（需通过 HTTPS 或 localhost 访问）');
      return false;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
        video: false,
      });

      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      const ctx = new AudioCtx();
      if (ctx.state === 'suspended') {
        await ctx.resume();
      }
      const source = ctx.createMediaStreamSource(stream);
      const silentGain = ctx.createGain();
      silentGain.gain.value = 0;

      let node = null;
      if (ctx.audioWorklet && typeof AudioWorkletNode !== 'undefined') {
        try {
          const blob = new Blob([WORKLET_SOURCE], { type: 'application/javascript' });
          this.workletUrl = URL.createObjectURL(blob);
          await ctx.audioWorklet.addModule(this.workletUrl);
          const workletNode = new AudioWorkletNode(ctx, 'opal-mic-processor');
          workletNode.port.onmessage = (ev) => {
            if (!this.running || !(ev.data instanceof Float32Array)) return;
            this.handleFloatSamples(ev.data, ctx.sampleRate);
          };
          node = workletNode;
        } catch {
          node = null;
        }
      }

      if (!node) {
        const proc = ctx.createScriptProcessor(1024, 1, 1);
        proc.onaudioprocess = (ev) => {
          if (!this.running) return;
          const ch = ev.inputBuffer.getChannelData(0);
          if (ch && ch.length > 0) {
            this.handleFloatSamples(ch, ctx.sampleRate);
          }
        };
        node = proc;
      }

      source.connect(node);
      node.connect(silentGain);
      silentGain.connect(ctx.destination);

      this.stream = stream;
      this.ctx = ctx;
      this.source = source;
      this.node = node;
      this.silentGain = silentGain;
      this.pendingBytes = new Uint8Array(0);
      this.running = true;
      return true;
    } catch (err) {
      this.stop();
      const msg = err?.name === 'NotAllowedError'
        ? '麦克风权限被拒绝，请在浏览器地址栏允许麦克风访问'
        : `无法启动麦克风：${err?.message || err}`;
      this.onError?.(msg);
      return false;
    }
  }

  handleFloatSamples(floatSamples, inputSampleRate) {
    const pcm = downsampleFloat32ToPcm16(floatSamples, inputSampleRate, this.targetSampleRate);
    if (pcm.byteLength === 0) return;

    const merged = new Uint8Array(this.pendingBytes.byteLength + pcm.byteLength);
    if (this.pendingBytes.byteLength > 0) {
      merged.set(this.pendingBytes, 0);
    }
    merged.set(pcm, this.pendingBytes.byteLength);

    const chunkBytes = CHUNK_SAMPLES * 2;
    let offset = 0;
    while (offset + chunkBytes <= merged.byteLength) {
      const slice = merged.slice(offset, offset + chunkBytes);
      this.onPcmFrame?.(slice);
      offset += chunkBytes;
    }
    this.pendingBytes = offset < merged.byteLength ? merged.slice(offset) : new Uint8Array(0);
  }

  stop() {
    this.running = false;
    this.pendingBytes = new Uint8Array(0);
    try { this.node?.disconnect(); } catch {}
    try { this.source?.disconnect(); } catch {}
    try { this.silentGain?.disconnect(); } catch {}
    if (this.node && this.node.port) {
      this.node.port.onmessage = null;
    }
    if (this.node && 'onaudioprocess' in this.node) {
      this.node.onaudioprocess = null;
    }
    if (this.stream) {
      for (const track of this.stream.getTracks()) {
        try { track.stop(); } catch {}
      }
    }
    if (this.ctx) {
      try { this.ctx.close(); } catch {}
    }
    if (this.workletUrl) {
      try { URL.revokeObjectURL(this.workletUrl); } catch {}
    }
    this.node = null;
    this.source = null;
    this.silentGain = null;
    this.stream = null;
    this.ctx = null;
    this.workletUrl = null;
  }
}
