package io.github.lengsukq.opaldesk.daemon.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Rootless-first Android 13+ virtual microphone injector for OpalCast uplink audio.
 *
 * <p>Uses {@code com.android.shell} (uid=2000) {@code MODIFY_AUDIO_ROUTING} via reflection on
 * {@code android.media.audiopolicy.AudioPolicy} + {@code REMOTE_SUBMIX} loopback mix to inject
 * remote PCM16 speech from macOS / Android Tablet / Web receivers into standard Android recording
 * presets (MIC, VOICE_COMMUNICATION, VOICE_RECOGNITION, DEFAULT, UNPROCESSED, CAMCORDER).
 *
 * <p>Gracefully degrades with an explicit Chinese diagnostic message on Android 11–12 or OEM ROMs
 * that reject AudioPolicy registration without throwing or disrupting the cast session.
 */
public final class RemoteMicInjector {
    private static final int MIN_SUPPORTED_API = 33;
    private static final int MAX_QUEUED_CHUNKS = 16;
    private static final int[] CAPTURE_PRESETS = new int[]{
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.UNPROCESSED
    };

    public static final class StartResult {
        public final boolean active;
        public final boolean supported;
        public final int sampleRate;
        public final int channels;
        public final String message;

        public StartResult(boolean active, boolean supported, int sampleRate, int channels, String message) {
            this.active = active;
            this.supported = supported;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.message = (message != null) ? message : "";
        }
    }

    private final Context context;
    private final Object lock = new Object();
    private final ArrayBlockingQueue<byte[]> pcmQueue = new ArrayBlockingQueue<>(MAX_QUEUED_CHUNKS);

    private volatile boolean running = false;
    private volatile int activeSampleRate = OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE;
    private volatile int activeChannels = OpalCastProtocol.DEFAULT_MIC_CHANNELS;
    private AudioManager audioManager;
    private Object registeredPolicy;
    private AudioTrack audioTrack;
    private Thread writerThread;

    public RemoteMicInjector(Context context) {
        this.context = context;
    }

    public static boolean isSupportedSdkInt(int sdkInt) {
        return sdkInt >= MIN_SUPPORTED_API;
    }

    public static int clampSampleRate(int requested) {
        if (requested <= 0) return OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE;
        return Math.max(8_000, Math.min(48_000, requested));
    }

    public static int clampChannels(int requested) {
        if (requested <= 0) return OpalCastProtocol.DEFAULT_MIC_CHANNELS;
        return Math.max(1, Math.min(2, requested));
    }

    public static int computeTrackBufferBytes(int sampleRate, int channels, int minBufferBytes) {
        int safeRate = clampSampleRate(sampleRate);
        int safeCh = clampChannels(channels);
        int frame40MsBytes = (safeRate / 25) * safeCh * 2;
        int minDoubled = (minBufferBytes > 0) ? (minBufferBytes * 2) : 0;
        return Math.max(4096, Math.max(minDoubled, frame40MsBytes));
    }

    public static String formatMixAddressTag(String registration) {
        if (registration == null) return "";
        String trimmed = registration.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.startsWith("addr=") ? trimmed : ("addr=" + trimmed);
    }

    public boolean isActive() {
        return running && audioTrack != null;
    }

    public int getActiveSampleRate() {
        return activeSampleRate;
    }

    public int getActiveChannels() {
        return activeChannels;
    }

    public StartResult start(int requestedSampleRate, int requestedChannels) {
        int sampleRate = clampSampleRate(requestedSampleRate);
        int channels = clampChannels(requestedChannels);

        synchronized (lock) {
            if (running && audioTrack != null
                    && activeSampleRate == sampleRate
                    && activeChannels == channels) {
                return new StartResult(true, true, sampleRate, channels, "远端麦克风已启用");
            }
            stopLocked();

            if (!isSupportedSdkInt(Build.VERSION.SDK_INT)) {
                return new StartResult(
                        false,
                        false,
                        sampleRate,
                        channels,
                        "当前系统为 Android " + Build.VERSION.RELEASE + "，免 Root 虚拟麦克风注入需要 Android 13+");
            }
            if (context == null) {
                return new StartResult(false, false, sampleRate, channels, "服务端缺少系统 Context，无法注册虚拟麦克风");
            }

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return new StartResult(false, false, sampleRate, channels, "无法获取 AudioManager 服务");
            }

            Object policy = null;
            AudioTrack track = null;
            try {
                int channelMask = (channels == 1)
                        ? AudioFormat.CHANNEL_OUT_MONO
                        : AudioFormat.CHANNEL_OUT_STEREO;
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build();

                Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
                Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
                int ruleMatchCapturePreset = readStaticInt(
                        ruleClass, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET", 2);

                Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
                Method addMixRule = ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
                Method setInternalCapturePreset = AudioAttributes.Builder.class
                        .getMethod("setInternalCapturePreset", int.class);

                int addedPresets = 0;
                for (int preset : CAPTURE_PRESETS) {
                    try {
                        AudioAttributes.Builder ab = new AudioAttributes.Builder();
                        setInternalCapturePreset.invoke(ab, preset);
                        addMixRule.invoke(ruleBuilder, ruleMatchCapturePreset, ab.build());
                        addedPresets++;
                    } catch (Throwable ignored) {
                        // Some OEM ROMs restrict specific presets; continue registering remaining ones.
                    }
                }
                if (addedPresets == 0) {
                    return new StartResult(false, false, sampleRate, channels, "无法构建虚拟麦克风 AudioMixingRule");
                }
                Object mixingRule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

                Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
                Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
                int routeFlagLoopBack = readStaticInt(mixClass, "ROUTE_FLAG_LOOP_BACK", 2);

                Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(mixingRule);
                mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
                mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, routeFlagLoopBack);
                Object audioMix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

                Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
                Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
                Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(context);
                policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, audioMix);
                policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

                Method registerMethod = AudioManager.class.getMethod("registerAudioPolicy", policyClass);
                int status = (Integer) registerMethod.invoke(am, policy);
                if (status != 0) {
                    System.err.println("OPALDESK_REMOTE_MIC_START_FAILED: registerAudioPolicy status=" + status);
                    return new StartResult(
                            false,
                            false,
                            sampleRate,
                            channels,
                            "系统拒绝注册虚拟麦克风 AudioPolicy (code=" + status + ")");
                }

                String[] trackModeOut = new String[1];
                track = createAttributedTrackSource(
                        policyClass, policy, mixClass, audioMix, format, sampleRate, channels, channelMask, trackModeOut);
                if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
                    int badState = (track != null) ? track.getState() : -1;
                    System.err.println("OPALDESK_REMOTE_MIC_START_FAILED: AudioTrack uninitialized state="
                            + badState + " mode=" + trackModeOut[0] + " rate=" + sampleRate + " ch=" + channels);
                    unregisterQuietly(am, policy);
                    if (track != null) {
                        try { track.release(); } catch (RuntimeException ignored) {}
                    }
                    return new StartResult(false, false, sampleRate, channels, "虚拟麦克风 AudioTrack 初始化失败");
                }

                track.play();

                this.audioManager = am;
                this.registeredPolicy = policy;
                this.audioTrack = track;
                this.activeSampleRate = sampleRate;
                this.activeChannels = channels;
                this.pcmQueue.clear();
                this.running = true;

                final AudioTrack finalTrack = track;
                Thread t = new Thread(() -> writeLoop(finalTrack), "OpalMicInject");
                t.setDaemon(true);
                this.writerThread = t;
                t.start();

                System.out.println("OPALDESK_REMOTE_MIC_STARTED rate=" + sampleRate
                        + " ch=" + channels + " mode=" + trackModeOut[0]);
                return new StartResult(true, true, sampleRate, channels, "远端麦克风已启用");
            } catch (Throwable t) {
                if (track != null) {
                    try { track.stop(); } catch (RuntimeException ignored) {}
                    try { track.release(); } catch (RuntimeException ignored) {}
                }
                if (policy != null) {
                    unregisterQuietly(am, policy);
                }
                Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                System.err.println("OPALDESK_REMOTE_MIC_START_FAILED: " + msg);
                return new StartResult(false, false, sampleRate, channels, "虚拟麦克风启用失败：" + msg);
            }
        }
    }

    public void feedPcm(byte[] pcmData) {
        if (!running || pcmData == null || pcmData.length == 0) return;
        if (pcmData.length > OpalCastProtocol.MAX_MIC_FRAME_BYTES) return;
        if (!pcmQueue.offer(pcmData)) {
            // Drop oldest frame to keep voice latency bounded under jitter.
            pcmQueue.poll();
            pcmQueue.offer(pcmData);
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void stopLocked() {
        boolean wasRunning = running || registeredPolicy != null || audioTrack != null;
        running = false;
        pcmQueue.clear();

        Thread wt = writerThread;
        writerThread = null;
        if (wt != null && wt != Thread.currentThread()) {
            wt.interrupt();
            try { wt.join(250L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        AudioTrack tr = audioTrack;
        audioTrack = null;
        if (tr != null) {
            try { tr.pause(); } catch (RuntimeException ignored) {}
            try { tr.flush(); } catch (RuntimeException ignored) {}
            try { tr.stop(); } catch (RuntimeException ignored) {}
            try { tr.release(); } catch (RuntimeException ignored) {}
        }

        AudioManager am = audioManager;
        Object pol = registeredPolicy;
        audioManager = null;
        registeredPolicy = null;
        if (am != null && pol != null) {
            unregisterQuietly(am, pol);
        }
        if (wasRunning) {
            System.out.println("OPALDESK_REMOTE_MIC_STOPPED");
        }
    }

    private void writeLoop(AudioTrack track) {
        while (running) {
            try {
                byte[] chunk = pcmQueue.poll(120L, TimeUnit.MILLISECONDS);
                if (chunk == null || chunk.length == 0) continue;
                int offset = 0;
                while (running && offset < chunk.length) {
                    int written = track.write(chunk, offset, chunk.length - offset, AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) {
                        break;
                    }
                    offset += written;
                }
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable t) {
                if (running) {
                    System.err.println("OPALDESK_REMOTE_MIC_WRITE_ERR: " + t.getMessage());
                }
                break;
            }
        }
    }

    /**
     * Builds the virtual-source {@link AudioTrack} feeding the registered {@code REMOTE_SUBMIX}
     * recorder mix.
     *
     * <p>In {@code app_process} ({@code uid=2000}), AOSP's {@code AudioPolicy.createAudioTrackSource}
     * constructs {@code new AudioTrack(...)} without binding {@code mContext}, so {@code AudioTrack}
     * inherits {@code ActivityThread.currentOpPackageName() == "android"} and Android 14-16
     * {@code AudioFlinger} rejects it with {@code EX_SECURITY} ({@code uid=2000} != {@code "android"}).
     * Constructing the identical {@code USAGE_VIRTUAL_SOURCE + addr=<registration>} track through
     * {@link AudioTrack.Builder} with {@code setContext(shellContext)} supplies a valid
     * {@code com.android.shell} {@code AttributionSource}, falling back to
     * {@code createAudioTrackSource} if reflection ever differs on an OEM ROM.
     */
    private AudioTrack createAttributedTrackSource(
            Class<?> policyClass,
            Object policy,
            Class<?> mixClass,
            Object audioMix,
            AudioFormat requestedFormat,
            int sampleRate,
            int channels,
            int channelMask,
            String[] modeOut) throws Throwable {
        // Primary: AudioTrack.Builder + setContext(shellContext) matching AudioPolicy's virtual source track
        try {
            String reg = (String) mixClass.getMethod("getRegistration").invoke(audioMix);
            String addrTag = formatMixAddressTag(reg);
            if (!addrTag.isEmpty()) {
                AudioFormat mixFmt = requestedFormat;
                try {
                    AudioFormat resolved = (AudioFormat) mixClass.getMethod("getFormat").invoke(audioMix);
                    if (resolved != null) mixFmt = resolved;
                } catch (Throwable ignored) {
                }

                int usageVirtualSource = readStaticInt(AudioAttributes.class, "USAGE_VIRTUAL_SOURCE", 15);
                AudioAttributes.Builder ab = new AudioAttributes.Builder();
                AudioAttributes.Builder.class.getMethod("setUsage", int.class).invoke(ab, usageVirtualSource);
                AudioAttributes.Builder.class.getMethod("addTag", String.class).invoke(ab, addrTag);
                AudioAttributes attrs = ab.build();

                int minBuf = AudioTrack.getMinBufferSize(
                        mixFmt.getSampleRate(), channelMask, mixFmt.getEncoding());
                int bufSize = computeTrackBufferBytes(sampleRate, channels, minBuf);

                AudioTrack.Builder tb = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(mixFmt)
                        .setBufferSizeInBytes(bufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM);
                applyShellContext(tb);

                AudioTrack built = tb.build();
                if (built.getState() == AudioTrack.STATE_INITIALIZED) {
                    modeOut[0] = "builder_ctx";
                    return built;
                }
                System.err.println("OPALDESK_REMOTE_MIC_BUILDER_WARN state=" + built.getState()
                        + " reg=" + reg + "; falling back to createAudioTrackSource");
                try { built.release(); } catch (RuntimeException ignored) {}
            }
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            System.err.println("OPALDESK_REMOTE_MIC_BUILDER_WARN: " + cause.getMessage()
                    + "; falling back to createAudioTrackSource");
        }

        // Fallback: stock AudioPolicy.createAudioTrackSource(audioMix)
        modeOut[0] = "policy_source";
        Method createTrackMethod = policyClass.getMethod("createAudioTrackSource", mixClass);
        return (AudioTrack) createTrackMethod.invoke(policy, audioMix);
    }

    private void applyShellContext(AudioTrack.Builder trackBuilder) {
        if (context == null || Build.VERSION.SDK_INT < 31) return;
        try {
            AudioTrack.Builder.class.getMethod("setContext", Context.class).invoke(trackBuilder, context);
        } catch (NoSuchMethodException ignored) {
            // Optional hidden/system API on API 31+.
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            System.err.println("OPALDESK_REMOTE_MIC_SET_CTX_WARN: " + cause.getMessage());
        }
    }

    private static int readStaticInt(Class<?> cls, String fieldName, int fallback) {
        try {
            Field f = cls.getField(fieldName);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void unregisterQuietly(AudioManager am, Object policy) {
        try {
            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            AudioManager.class.getMethod("unregisterAudioPolicy", policyClass).invoke(am, policy);
        } catch (Throwable ignored) {
            try {
                Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
                AudioManager.class.getMethod("unregisterAudioPolicyAsync", policyClass).invoke(am, policy);
            } catch (Throwable ignored2) {
            }
        }
    }
}
