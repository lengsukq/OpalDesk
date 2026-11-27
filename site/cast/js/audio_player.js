/**
 * Low-latency WebCodecs AAC-LC (`mp4a.40.2`) decoder + inline Blob AudioWorklet ring buffer player.
 */

const WORKLET_SOURCE = `
class OpalPcmRingProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.capacity = 48000; // ~1s max per channel, trimmed tightly to low latency
    this.maxBuffered = 4800; // ~100ms ceiling before trimming to ~32ms
    this.targetTrim = 1536; // ~32ms
    this.left = new Float32Array(this.capacity);
    this.right = new Float32Array(this.capacity);
    this.readPos = 0;
    this.writePos = 0;
    this.available = 0;

    this.port.onmessage = (e) => {
      const msg = e.data;
      if (!msg || msg.type !== 'pcm') return;
      const ch0 = msg.left;
      const ch1 = msg.right || msg.left;
      const count = ch0.length;
      if (this.available + count > this.maxBuffered) {
        const drop = Math.max(0, this.available + count - this.targetTrim);
        this.readPos = (this.readPos + Math.min(drop, this.available)) % this.capacity;
        this.available = Math.max(0, this.available - drop);
      }
      for (let i = 0; i < count; i++) {
        this.left[this.writePos] = ch0[i];
        this.right[this.writePos] = ch1[i];
        this.writePos = (this.writePos + 1) % this.capacity;
      }
      this.available = Math.min(this.capacity, this.available + count);
    };
  }

  process(_inputs, outputs) {
    const out = outputs[0];
    if (!out || out.length === 0) return true;
    const outL = out[0];
    const outR = out.length > 1 ? out[1] : null;
    const frames = outL.length;
    for (let i = 0; i < frames; i++) {
      if (this.available > 0) {
        outL[i] = this.left[this.readPos];
        if (outR) outR[i] = this.right[this.readPos];
        this.readPos = (this.readPos + 1) % this.capacity;
        this.available--;
      } else {
        outL[i] = 0;
        if (outR) outR[i] = 0;
      }
    }
    return true;
  }
}
registerProcessor('opal-pcm-ring', OpalPcmRingProcessor);
`;

export class OpalAudioPlayer {
  constructor({ onStateChange } = {}) {
    this.onStateChange = onStateChange || (() => {});
    this.audioCtx = null;
    this.workletNode = null;
    this.gainNode = null;
    this.decoder = null;
    this.workletUrl = null;
    this.muted = false;
    this.configured = false;
    this.sampleRate = 48000;
    this.channels = 2;
    this.asc = null;
    this.initPromise = null;
  }

  async configure(audioConfig) {
    if (typeof AudioDecoder === 'undefined' || typeof AudioContext === 'undefined') {
      return false;
    }
    this.sampleRate = audioConfig.sampleRate || 48000;
    this.channels = audioConfig.channels || 2;
    this.asc = audioConfig.audioSpecificConfig && audioConfig.audioSpecificConfig.byteLength >= 2
      ? audioConfig.audioSpecificConfig
      : new Uint8Array([0x11, 0x90]);

    await this.ensureAudioContext();
    try {
      if (!this.decoder || this.decoder.state === 'closed') {
        this.decoder = new AudioDecoder({
          output: (audioData) => this.handleAudioData(audioData),
          error: () => {
            this.configured = false;
          },
        });
      }
      this.decoder.configure({
        codec: 'mp4a.40.2',
        sampleRate: this.sampleRate,
        numberOfChannels: this.channels,
        description: this.asc,
      });
      this.configured = true;
      this.emitState();
      return true;
    } catch {
      this.configured = false;
      return false;
    }
  }

  decodeFrame(aacAu, timestampUs) {
    if (!this.configured || !this.decoder || this.decoder.state !== 'configured') return;
    if (!aacAu || aacAu.byteLength === 0) return;
    if (this.decoder.decodeQueueSize > 8) return;
    try {
      this.decoder.decode(
        new EncodedAudioChunk({
          type: 'key',
          timestamp: Number(timestampUs),
          data: aacAu,
        })
      );
    } catch {
      // Drop malformed AU safely
    }
  }

  async resumeFromGesture() {
    if (!this.audioCtx) return;
    if (this.audioCtx.state === 'suspended') {
      try {
        await this.audioCtx.resume();
      } catch {
        // Ignore if browser still requires another gesture
      }
    }
    this.emitState();
  }

  setMuted(muted) {
    this.muted = Boolean(muted);
    if (this.gainNode) {
      this.gainNode.gain.value = this.muted ? 0 : 1;
    }
    this.emitState();
  }

  async ensureAudioContext() {
    if (this.audioCtx && this.workletNode) return;
    if (this.initPromise) return this.initPromise;

    this.initPromise = (async () => {
      const ctx = new AudioContext({
        sampleRate: this.sampleRate,
        latencyHint: 'interactive',
      });
      const blob = new Blob([WORKLET_SOURCE], { type: 'application/javascript' });
      this.workletUrl = URL.createObjectURL(blob);
      await ctx.audioWorklet.addModule(this.workletUrl);
      const node = new AudioWorkletNode(ctx, 'opal-pcm-ring', {
        numberOfInputs: 0,
        numberOfOutputs: 1,
        outputChannelCount: [2],
      });
      const gain = ctx.createGain();
      gain.gain.value = this.muted ? 0 : 1;
      node.connect(gain).connect(ctx.destination);
      this.audioCtx = ctx;
      this.workletNode = node;
      this.gainNode = gain;
      ctx.onstatechange = () => this.emitState();
      if (ctx.state === 'suspended') {
        try {
          await ctx.resume();
        } catch {
          // Pure web browsers may require a user gesture before AudioContext can resume
        }
      }
      this.emitState();
    })();

    try {
      await this.initPromise;
    } finally {
      this.initPromise = null;
    }
  }

  handleAudioData(audioData) {
    try {
      if (!this.workletNode || !this.audioCtx || this.audioCtx.state !== 'running') {
        return;
      }
      const frames = audioData.numberOfFrames;
      const chCount = audioData.numberOfChannels;
      const left = new Float32Array(frames);
      const right = new Float32Array(frames);
      const format = audioData.format || '';

      if (format === 'f32-planar') {
        audioData.copyTo(left, { planeIndex: 0, format: 'f32-planar' });
        if (chCount > 1) {
          audioData.copyTo(right, { planeIndex: 1, format: 'f32-planar' });
        } else {
          right.set(left);
        }
      } else {
        const interleaved = new Float32Array(frames * chCount);
        audioData.copyTo(interleaved, { planeIndex: 0, format: 'f32' });
        for (let i = 0; i < frames; i++) {
          left[i] = interleaved[i * chCount];
          right[i] = chCount > 1 ? interleaved[i * chCount + 1] : left[i];
        }
      }
      this.workletNode.port.postMessage({ type: 'pcm', left, right }, [left.buffer, right.buffer]);
    } catch {
      // Ignore transient format copy mismatch
    } finally {
      audioData.close();
    }
  }

  emitState() {
    this.onStateChange({
      configured: this.configured,
      muted: this.muted,
      needsGesture: Boolean(this.audioCtx && this.audioCtx.state === 'suspended'),
    });
  }

  reset() {
    this.configured = false;
    if (this.decoder) {
      try {
        if (this.decoder.state !== 'closed') this.decoder.close();
      } catch {
        // Ignore teardown error
      }
      this.decoder = null;
    }
    if (this.audioCtx) {
      try {
        this.audioCtx.close();
      } catch {
        // Ignore teardown error
      }
      this.audioCtx = null;
    }
    if (this.workletUrl) {
      URL.revokeObjectURL(this.workletUrl);
      this.workletUrl = null;
    }
    this.workletNode = null;
    this.gainNode = null;
    this.emitState();
  }
}
