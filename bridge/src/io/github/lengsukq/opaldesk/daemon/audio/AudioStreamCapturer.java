package io.github.lengsukq.opaldesk.daemon.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Pair;

import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Rootless-first Android playback audio capturer encoding 48 kHz stereo PCM into AAC-LC raw AUs
 * for OpalCast v1 {@code AUDIO_CONFIG + AUDIO_FRAME}.
 *
 * <p>Supports two standard AOSP modes under {@code uid=2000}:
 * <ul>
 *   <li>{@code ALL_TO_DEX}: standard {@code REMOTE_SUBMIX} capturing all device playback audio.</li>
 *   <li>{@code SPLIT_BY_APP}: registers an AOSP {@code AudioPolicy} ({@code ROUTE_FLAG_LOOP_BACK})
 *       with {@code RULE_MATCH_UID} restricted strictly to apps currently hosted in DeX windows.
 *       Phone Display 0 apps remain untouched on the phone speaker/Bluetooth, while DeX apps route
 *       exclusively to the remote DeX receiver (hot-updated via {@code updateMixingRules} on
 *       Android 14+ without dropping the stream).</li>
 * </ul>
 * Gracefully degrades to {@code REMOTE_SUBMIX} (or {@code audioEnabled=false}) if an OEM ROM
 * rejects {@code AudioPolicy} registration.
 */
public final class AudioStreamCapturer {
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 2;
    public static final int AAC_BITRATE = 128_000;
    private static final String MIME_AAC = "audio/mp4a-latm";
    // Standard 2-byte AudioSpecificConfig: AAC-LC (2), 48kHz (idx 3), stereo (2) -> 0x11 0x90
    private static final byte[] FALLBACK_AAC_LC_48K_STEREO_ASC = new byte[]{0x11, (byte) 0x90};

    private static final int[] SPLIT_MATCH_USAGES = new int[]{
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
    };

    public interface AudioListener {
        void onAudioConfig(OpalCastProtocol.AudioConfig config, long timestampUs);
        void onAudioFrame(byte[] aacAu, long timestampUs);
        void onAudioStateChanged(boolean active);
    }

    private final Context ctx;
    private final AudioListener listener;
    private final Object lock = new Object();
    private final Object dtxWakeLock = new Object();
    private final AudioSilenceGate silenceGate = new AudioSilenceGate();

    private volatile boolean running = false;
    private volatile boolean active = false;
    private volatile boolean dtxSuppressed = false;
    private volatile boolean splitModeRequested = false;
    private volatile boolean splitPolicyActive = false;
    private volatile List<Integer> appliedSplitUids =
            Collections.singletonList(AudioRoutingPolicy.PLACEHOLDER_EMPTY_UID);

    private AudioManager splitAudioManager;
    private Object registeredSplitPolicy;
    private Object registeredSplitMix;
    private AudioRecord audioRecord;
    private MediaCodec encoder;
    private Thread captureThread;
    private Thread drainThread;

    public AudioStreamCapturer(Context ctx, AudioListener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public boolean isActive() {
        return running && active;
    }

    public boolean isSplitPolicyActive() {
        return running && active && splitPolicyActive;
    }

    public boolean start() {
        return start(false, null);
    }

    public boolean start(boolean splitByUid, Collection<Integer> initialUids) {
        synchronized (lock) {
            List<Integer> normUids = AudioRoutingPolicy.normalizeTargetUids(initialUids);
            if (running && active && splitModeRequested == splitByUid) {
                if (splitByUid) {
                    updateTargetUidsLocked(normUids);
                }
                return true;
            }
            return startInternalLocked(splitByUid, normUids, true);
        }
    }

    /**
     * Switches live between {@code ALL_TO_DEX} and {@code SPLIT_BY_APP} without tearing down the
     * parent OpalCast video session.
     */
    public boolean setSplitMode(boolean splitByUid, Collection<Integer> currentUids) {
        synchronized (lock) {
            List<Integer> normUids = AudioRoutingPolicy.normalizeTargetUids(currentUids);
            if (!running || !active) {
                this.splitModeRequested = splitByUid;
                this.appliedSplitUids = normUids;
                return false;
            }
            if (this.splitModeRequested == splitByUid) {
                if (splitByUid) {
                    updateTargetUidsLocked(normUids);
                }
                return true;
            }
            return startInternalLocked(splitByUid, normUids, false);
        }
    }

    /**
     * Updates the DeX app UID set routed exclusively to the remote DeX receiver when running in
     * {@code SPLIT_BY_APP} mode. Uses zero-drop {@code AudioPolicy.updateMixingRules} on Android 14+
     * and seamless policy rebuild on Android 11–13.
     */
    public void updateTargetUids(Collection<Integer> uids) {
        List<Integer> normUids = AudioRoutingPolicy.normalizeTargetUids(uids);
        synchronized (lock) {
            if (!running || !active || !splitModeRequested) {
                this.appliedSplitUids = normUids;
                return;
            }
            updateTargetUidsLocked(normUids);
        }
    }

    private void updateTargetUidsLocked(List<Integer> normUids) {
        if (AudioRoutingPolicy.uidListsEqual(this.appliedSplitUids, normUids)) {
            return;
        }
        if (!splitPolicyActive || registeredSplitPolicy == null || registeredSplitMix == null) {
            this.appliedSplitUids = normUids;
            return;
        }
        if (tryHotUpdateSplitMixingRules(registeredSplitPolicy, registeredSplitMix, normUids)) {
            this.appliedSplitUids = normUids;
            System.out.println("OPALDESK_AUDIO_SPLIT_UIDS_UPDATED mode=hot uids=" + normUids);
            return;
        }
        // Android 11–13 fallback (no updateMixingRules): rebuild policy + AudioRecord cleanly.
        System.out.println("OPALDESK_AUDIO_SPLIT_UIDS_UPDATED mode=rebuild uids=" + normUids);
        startInternalLocked(true, normUids, false);
    }

    private boolean startInternalLocked(boolean splitByUid, List<Integer> normUids, boolean notifyOnStart) {
        stopLocked(false);
        this.splitModeRequested = splitByUid;
        this.appliedSplitUids = normUids;

        AudioRecord rec = null;
        MediaCodec enc = null;
        boolean usingSplitPolicy = false;
        try {
            if (splitByUid) {
                rec = tryCreateSplitPolicyAudioRecordLocked(normUids);
                if (rec != null) {
                    usingSplitPolicy = true;
                } else {
                    System.err.println("OPALDESK_AUDIO_SPLIT_UNAVAILABLE_FALLBACK: falling back to REMOTE_SUBMIX");
                }
            }
            if (rec == null) {
                rec = createRemoteSubmixAudioRecord();
            }
            if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                if (rec != null) {
                    try { rec.release(); } catch (RuntimeException e) {
                        System.err.println("OPALDESK_AUDIO_REC_RELEASE_WARN: " + e.getMessage());
                    }
                }
                unregisterSplitPolicyLocked();
                System.out.println("OPALDESK_AUDIO_UNAVAILABLE AudioRecord not initialized; degrading gracefully");
                notifyState(false);
                return false;
            }

            enc = MediaCodec.createEncoderByType(MIME_AAC);
            MediaFormat fmt = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, CHANNELS);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING");
            }
            enc.start();

            this.audioRecord = rec;
            this.encoder = enc;
            this.splitPolicyActive = usingSplitPolicy;
            this.silenceGate.reset();
            this.dtxSuppressed = false;
            this.running = true;
            this.active = true;

            final AudioRecord finalRec = rec;
            final MediaCodec finalEnc = enc;
            this.drainThread = new Thread(() -> drainLoop(finalEnc), "ExtAudioDrain");
            this.drainThread.setDaemon(true);
            this.drainThread.start();

            this.captureThread = new Thread(() -> captureLoop(finalRec, finalEnc), "ExtAudioCapture");
            this.captureThread.setDaemon(true);
            this.captureThread.start();

            System.out.println("OPALDESK_AUDIO_STARTED 48kHz stereo AAC-LC mode="
                    + (usingSplitPolicy ? ("split_by_uid uids=" + normUids) : "all_to_dex"));
            if (notifyOnStart) {
                notifyState(true);
            }
            return true;
        } catch (Throwable t) {
            System.err.println("OPALDESK_AUDIO_FALLBACK_OFF: " + t.getMessage());
            if (enc != null) {
                try { enc.release(); } catch (RuntimeException e) {
                    System.err.println("OPALDESK_AUDIO_ENC_CLEANUP_WARN: " + e.getMessage());
                }
            }
            if (rec != null) {
                try { rec.release(); } catch (RuntimeException e) {
                    System.err.println("OPALDESK_AUDIO_REC_CLEANUP_WARN: " + e.getMessage());
                }
            }
            unregisterSplitPolicyLocked();
            this.audioRecord = null;
            this.encoder = null;
            this.splitPolicyActive = false;
            this.running = false;
            this.active = false;
            notifyState(false);
            return false;
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked(true);
        }
    }

    private void stopLocked(boolean notifyIfWasActive) {
        boolean wasActive = this.active;
        this.running = false;
        this.active = false;
        this.dtxSuppressed = false;
        this.splitPolicyActive = false;
        synchronized (dtxWakeLock) {
            dtxWakeLock.notifyAll();
        }

        AudioRecord rec = this.audioRecord;
        MediaCodec enc = this.encoder;
        this.audioRecord = null;
        this.encoder = null;

        if (rec != null) {
            try { rec.stop(); } catch (RuntimeException ignored) {}
            try { rec.release(); } catch (RuntimeException e) {
                System.err.println("OPALDESK_AUDIO_REC_RELEASE_WARN: " + e.getMessage());
            }
        }
        unregisterSplitPolicyLocked();

        Thread ct = this.captureThread;
        this.captureThread = null;
        if (ct != null && ct != Thread.currentThread()) {
            try { ct.join(250L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        if (enc != null) {
            try { enc.stop(); } catch (RuntimeException ignored) {}
            try { enc.release(); } catch (RuntimeException e) {
                System.err.println("OPALDESK_AUDIO_ENC_RELEASE_WARN: " + e.getMessage());
            }
        }
        Thread dt = this.drainThread;
        this.drainThread = null;
        if (dt != null && dt != Thread.currentThread()) {
            try { dt.join(250L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        if (notifyIfWasActive && wasActive) {
            notifyState(false);
        }
    }

    private AudioRecord tryCreateSplitPolicyAudioRecordLocked(List<Integer> targetUids) {
        if (ctx == null) return null;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;

        Object policy = null;
        try {
            Class<?> ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> mixCls = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBCls = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Class<?> polCls = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> polBCls = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");

            Object rule = buildSplitMixingRule(targetUids);
            int routeLoopback = readStaticInt(mixCls, "ROUTE_FLAG_LOOP_BACK", 2);
            AudioFormat mixFmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();

            Object mixB = mixBCls.getConstructor(ruleCls).newInstance(rule);
            mixBCls.getMethod("setFormat", AudioFormat.class).invoke(mixB, mixFmt);
            mixBCls.getMethod("setRouteFlags", int.class).invoke(mixB, routeLoopback);
            Object mix = mixBCls.getMethod("build").invoke(mixB);

            Object polB = polBCls.getConstructor(Context.class).newInstance(ctx);
            polBCls.getMethod("addMix", mixCls).invoke(polB, mix);
            policy = polBCls.getMethod("build").invoke(polB);

            int regRc = (Integer) AudioManager.class.getMethod("registerAudioPolicy", polCls).invoke(am, policy);
            if (regRc != 0) {
                System.err.println("OPALDESK_AUDIO_SPLIT_REG_ERR rc=" + regRc);
                return null;
            }

            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, 8_192);
            AudioRecord rec = buildSplitSinkRecord(policy, mix, mixFmt, bufSize);
            if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                if (rec != null) {
                    try { rec.release(); } catch (RuntimeException ignored) {}
                }
                unregisterPolicyQuietly(am, policy);
                return null;
            }

            this.splitAudioManager = am;
            this.registeredSplitPolicy = policy;
            this.registeredSplitMix = mix;
            return rec;
        } catch (Throwable t) {
            System.err.println("OPALDESK_AUDIO_SPLIT_INIT_WARN: " + t);
            if (policy != null) {
                unregisterPolicyQuietly(am, policy);
            }
            return null;
        }
    }

    private AudioRecord buildSplitSinkRecord(Object policy, Object mix, AudioFormat recFmt, int bufSize) {
        // Primary: AudioRecord.Builder + setContext(shellCtx) + addr=<mixRegistration> (required on Android 16)
        try {
            String reg = (String) mix.getClass().getMethod("getRegistration").invoke(mix);
            if (reg != null && !reg.isEmpty()) {
                AudioAttributes.Builder ab = new AudioAttributes.Builder();
                AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class)
                        .invoke(ab, MediaRecorder.AudioSource.REMOTE_SUBMIX);
                AudioAttributes.Builder.class.getMethod("addTag", String.class)
                        .invoke(ab, RemoteMicInjector.formatMixAddressTag(reg));
                AudioRecord.Builder rb = new AudioRecord.Builder()
                        .setAudioFormat(recFmt)
                        .setBufferSizeInBytes(bufSize);
                AudioRecord.Builder.class.getMethod("setAudioAttributes", AudioAttributes.class)
                        .invoke(rb, ab.build());
                applyShellContext(rb);
                AudioRecord r = rb.build();
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    return r;
                }
                r.release();
            }
        } catch (Throwable t) {
            System.err.println("OPALDESK_AUDIO_SPLIT_SINK_BUILDER_WARN: " + t.getMessage());
        }
        // Fallback: AudioPolicy.createAudioRecordSink(mix)
        try {
            AudioRecord r = (AudioRecord) policy.getClass()
                    .getMethod("createAudioRecordSink", mix.getClass()).invoke(policy, mix);
            if (r != null && r.getState() == AudioRecord.STATE_INITIALIZED) {
                return r;
            }
            if (r != null) r.release();
        } catch (Throwable t) {
            System.err.println("OPALDESK_AUDIO_SPLIT_SINK_FALLBACK_WARN: " + t.getMessage());
        }
        return null;
    }

    private static Object buildSplitMixingRule(List<Integer> targetUids) throws ReflectiveOperationException {
        Class<?> ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBCls = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        int matchUsage = readStaticInt(ruleCls, "RULE_MATCH_ATTRIBUTE_USAGE", 1);
        int matchUid = readStaticInt(ruleCls, "RULE_MATCH_UID", 4);

        Object rb = ruleBCls.getConstructor().newInstance();
        Method addRule = ruleBCls.getMethod("addRule", AudioAttributes.class, int.class);
        for (int usage : SPLIT_MATCH_USAGES) {
            addRule.invoke(rb, new AudioAttributes.Builder().setUsage(usage).build(), matchUsage);
        }
        Method addMixRule = ruleBCls.getMethod("addMixRule", int.class, Object.class);
        for (Integer uid : targetUids) {
            addMixRule.invoke(rb, matchUid, uid);
        }
        return ruleBCls.getMethod("build").invoke(rb);
    }

    private static boolean tryHotUpdateSplitMixingRules(Object policy, Object mix, List<Integer> targetUids) {
        try {
            Method upd = policy.getClass().getMethod("updateMixingRules", List.class);
            Object nextRule = buildSplitMixingRule(targetUids);
            int rc = (Integer) upd.invoke(policy, Collections.singletonList(new Pair<>(mix, nextRule)));
            return rc == 0;
        } catch (NoSuchMethodException ignored) {
            return false; // Android 11–13
        } catch (Throwable t) {
            System.err.println("OPALDESK_AUDIO_SPLIT_HOT_UPDATE_WARN: " + t.getMessage());
            return false;
        }
    }

    private void unregisterSplitPolicyLocked() {
        AudioManager am = this.splitAudioManager;
        Object pol = this.registeredSplitPolicy;
        this.splitAudioManager = null;
        this.registeredSplitPolicy = null;
        this.registeredSplitMix = null;
        if (am != null && pol != null) {
            unregisterPolicyQuietly(am, pol);
        }
    }

    private static void unregisterPolicyQuietly(AudioManager am, Object policy) {
        try {
            Class<?> polCls = Class.forName("android.media.audiopolicy.AudioPolicy");
            AudioManager.class.getMethod("unregisterAudioPolicy", polCls).invoke(am, policy);
        } catch (Throwable ignored) {
        }
    }

    private static int readStaticInt(Class<?> cls, String fieldName, int fallback) {
        try {
            return cls.getField(fieldName).getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private AudioRecord createRemoteSubmixAudioRecord() {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = 8192;
        }
        int bufSize = Math.max(minBuf, 8_192);
        AudioFormat af = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        // Attempt 1: internal capture preset REMOTE_SUBMIX + shell Context;
        // Attempt 2: standard AudioRecord.Builder with REMOTE_SUBMIX + shell Context.
        AudioRecord rec = tryBuildAudioRecord(af, bufSize, true);
        if (rec != null) return rec;
        return tryBuildAudioRecord(af, bufSize, false);
    }

    private AudioRecord tryBuildAudioRecord(AudioFormat af, int bufSize, boolean useInternalPreset) {
        try {
            AudioRecord.Builder recB = new AudioRecord.Builder()
                    .setAudioFormat(af)
                    .setBufferSizeInBytes(bufSize);

            if (useInternalPreset) {
                Method setInternal;
                try {
                    setInternal = AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class);
                } catch (NoSuchMethodException ignored) {
                    return null;
                }
                AudioAttributes.Builder attrB = new AudioAttributes.Builder();
                setInternal.invoke(attrB, MediaRecorder.AudioSource.REMOTE_SUBMIX);
                try {
                    AudioRecord.Builder.class.getMethod("setAudioAttributes", AudioAttributes.class)
                            .invoke(recB, attrB.build());
                } catch (NoSuchMethodException ignored) {
                    recB.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    recB.setPrivacySensitive(false);
                }
            } else {
                recB.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
            }

            applyShellContext(recB);

            AudioRecord r = recB.build();
            if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                return r;
            }
            r.release();
        } catch (Throwable t) {
            System.err.println("OPALDESK_AUDIO_RECORD_ATTEMPT_WARN internalPreset="
                    + useInternalPreset + ": " + t.getMessage());
        }
        return null;
    }

    private void applyShellContext(AudioRecord.Builder recB) {
        if (ctx == null || Build.VERSION.SDK_INT < 31) return;
        try {
            AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(recB, ctx);
        } catch (NoSuchMethodException ignored) {
            // Optional hidden API.
        } catch (ReflectiveOperationException e) {
            System.err.println("OPALDESK_AUDIO_SET_CTX_WARN: " + e.getMessage());
        }
    }

    private void captureLoop(AudioRecord rec, MediaCodec enc) {
        // 1024 stereo 16-bit PCM samples per AAC-LC frame = 4096 bytes (~21.3 ms)
        final int chunkBytes = 1024 * CHANNELS * 2;
        byte[] pcm = new byte[chunkBytes];
        int consecutiveErrors = 0;

        while (running) {
            try {
                int read = rec.read(pcm, 0, pcm.length);
                if (read < 0) {
                    consecutiveErrors++;
                    if (consecutiveErrors >= 5) {
                        System.err.println("OPALDESK_AUDIO_READ_FAILED err=" + read + "; disabling audio");
                        failAndDisableAsync();
                        return;
                    }
                    SystemClock.sleep(20);
                    continue;
                }
                consecutiveErrors = 0;
                if (read == 0) continue;

                // Silence DTX: avoid burning CPU software AAC encoding & 47 Hz Wi-Fi TX wakeups during silence.
                if (!silenceGate.shouldTransmit(pcm, read)) {
                    dtxSuppressed = true;
                    continue;
                }
                if (dtxSuppressed) {
                    dtxSuppressed = false;
                    synchronized (dtxWakeLock) {
                        dtxWakeLock.notifyAll();
                    }
                }

                int inIdx = enc.dequeueInputBuffer(20_000L);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = enc.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        int toCopy = Math.min(read, inBuf.remaining());
                        inBuf.put(pcm, 0, toCopy);
                        long ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L;
                        enc.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0);
                    }
                }
            } catch (IllegalStateException ise) {
                if (running) {
                    System.err.println("OPALDESK_AUDIO_CAPTURE_STATE_ERR: " + ise.getMessage());
                    failAndDisableAsync();
                }
                return;
            } catch (Throwable t) {
                if (running) {
                    System.err.println("OPALDESK_AUDIO_CAPTURE_ERR: " + t.getMessage());
                    failAndDisableAsync();
                }
                return;
            }
        }
    }

    private void drainLoop(MediaCodec enc) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean configSent = false;

        while (running) {
            try {
                int outIdx = enc.dequeueOutputBuffer(info, 25_000L);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (dtxSuppressed && running) {
                        synchronized (dtxWakeLock) {
                            if (dtxSuppressed && running) {
                                try {
                                    dtxWakeLock.wait(250L);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    }
                    continue;
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = enc.getOutputFormat();
                    byte[] asc = extractAsc(outFmt);
                    emitAudioConfig(asc, SystemClock.elapsedRealtimeNanos() / 1000L);
                    configSent = true;
                    continue;
                }
                if (outIdx < 0) continue;

                try {
                    ByteBuffer outBuf = enc.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        outBuf.get(data);
                        long ptsUs = (info.presentationTimeUs > 0)
                                ? info.presentationTimeUs
                                : (SystemClock.elapsedRealtimeNanos() / 1000L);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            emitAudioConfig(data, ptsUs);
                            configSent = true;
                        } else {
                            if (!configSent) {
                                emitAudioConfig(FALLBACK_AAC_LC_48K_STEREO_ASC, ptsUs);
                                configSent = true;
                            }
                            if (listener != null && running) {
                                listener.onAudioFrame(data, ptsUs);
                            }
                        }
                    }
                } finally {
                    enc.releaseOutputBuffer(outIdx, false);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (running) {
                        System.err.println("OPALDESK_AUDIO_DRAIN_UNEXPECTED_EOS; disabling audio");
                        failAndDisableAsync();
                    }
                    break;
                }
            } catch (IllegalStateException ise) {
                if (running) {
                    System.err.println("OPALDESK_AUDIO_DRAIN_STATE_ERR: " + ise.getMessage());
                    failAndDisableAsync();
                }
                break;
            } catch (Throwable t) {
                if (running) {
                    System.err.println("OPALDESK_AUDIO_DRAIN_ERR: " + t);
                    failAndDisableAsync();
                }
                break;
            }
        }
    }

    private byte[] extractAsc(MediaFormat fmt) {
        if (fmt != null && fmt.containsKey("csd-0")) {
            ByteBuffer csd0 = fmt.getByteBuffer("csd-0");
            if (csd0 != null && csd0.remaining() > 0) {
                ByteBuffer dup = csd0.duplicate();
                byte[] out = new byte[dup.remaining()];
                dup.get(out);
                return out;
            }
        }
        return FALLBACK_AAC_LC_48K_STEREO_ASC;
    }

    private void emitAudioConfig(byte[] asc, long ptsUs) {
        if (listener == null || !running) return;
        OpalCastProtocol.AudioConfig cfg = new OpalCastProtocol.AudioConfig(
                OpalCastProtocol.AUDIO_CODEC_AAC_LC,
                SAMPLE_RATE,
                CHANNELS,
                (asc != null && asc.length > 0) ? asc : FALLBACK_AAC_LC_48K_STEREO_ASC);
        listener.onAudioConfig(cfg, ptsUs);
    }

    private void failAndDisableAsync() {
        this.running = false;
        Thread t = new Thread(this::stop, "ExtAudioStop");
        t.setDaemon(true);
        t.start();
    }

    private void notifyState(boolean isActive) {
        if (listener != null) {
            try {
                listener.onAudioStateChanged(isActive);
            } catch (Throwable t) {
                System.err.println("OPALDESK_AUDIO_STATE_CALLBACK_ERR: " + t.getMessage());
            }
        }
    }
}
