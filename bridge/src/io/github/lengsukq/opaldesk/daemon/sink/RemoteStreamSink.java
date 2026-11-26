package io.github.lengsukq.opaldesk.daemon.sink;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.VideoFrameBufferPool;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Zero-copy MediaCodec hardware video encoder sink feeding OpalCast v1 VIDEO_CONFIG & VIDEO_FRAME.
 * Prefers HEVC (video/hevc) with graceful fallback to H.264 (video/avc), enforces encoder alignment
 * queried from the selected codec, ultra-low-latency flags, dynamic ABR bitrate, and on-demand IDR.
 */
public final class RemoteStreamSink implements DisplaySink {
    public static final int CODEC_PREF_AUTO_HEVC = 0;
    public static final int CODEC_PREF_H264 = 1;

    private static final String MIME_HEVC = "video/hevc";
    private static final String MIME_AVC = "video/avc";
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";
    private static final int MIN_BITRATE_KBPS = 1500;
    private static final int MAX_BITRATE_KBPS = DisplayGeometryUtil.WIRED_MAX_BITRATE_KBPS;
    private static final int DEFAULT_ALIGNMENT = 8;
    private static final int GOP_INTERVAL_SECONDS = 15;
    public static final long AGGRESSIVE_REPEAT_FRAME_AFTER_US = DisplayGeometryUtil.AGGRESSIVE_REPEAT_FRAME_AFTER_US;
    public static final long DEFAULT_REPEAT_FRAME_AFTER_US = DisplayGeometryUtil.DEFAULT_REPEAT_FRAME_AFTER_US;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final long ABR_SOFT_CONGESTION_COOLDOWN_MS = 2_500L;
    private static final long ABR_CONGESTION_COOLDOWN_MS = 2_000L;
    private static final long ABR_IDR_COOLDOWN_MS = 350L;
    private static final long ABR_RECOVERY_INTERVAL_MS = 6_000L;
    private static final long ABR_SOFT_RECOVERY_HOLD_MS = 8_000L;

    private enum ConfigTier {
        OPTIMAL,
        LOW_LATENCY_COMPAT,
        MINIMAL
    }

    public interface FrameListener {
        void onVideoConfig(OpalCastProtocol.VideoConfig config, long timestampUs);
        void onVideoFrame(byte[] annexBData, int flags, long timestampUs);
        default void onVideoFrame(
                byte[] annexBData, int length, int flags, long timestampUs, Consumer<byte[]> onRelease) {
            byte[] exact = (annexBData != null && annexBData.length == length)
                    ? annexBData
                    : (annexBData != null ? Arrays.copyOf(annexBData, length) : OpalCastProtocol.EMPTY_BYTES);
            try {
                onVideoFrame(exact, flags, timestampUs);
            } finally {
                if (onRelease != null && annexBData != null && exact != annexBData) {
                    onRelease.accept(annexBData);
                }
            }
        }
        void onEncoderError(String reason);
    }

    /**
     * Callback invoked with the new Surface BEFORE tearing down the old encoder session,
     * ensuring seamless zero-flicker VirtualDisplay surface handover.
     */
    public interface SurfaceSwapper {
        void onNewSurfaceReady(Surface newSurface, int alignedW, int alignedH, int dpi);
    }

    private static final class SelectedEncoder {
        final String codecName; // nullable -> fallback to MediaCodec.createEncoderByType
        final int alignW;
        final int alignH;
        final int maxW;
        final int maxH;
        final float maxFps;
        final int preferredProfile;
        final int preferredLevel;

        SelectedEncoder(
                String codecName, int alignW, int alignH, int maxW, int maxH, float maxFps,
                int preferredProfile, int preferredLevel) {
            this.codecName = codecName;
            this.alignW = alignW;
            this.alignH = alignH;
            this.maxW = maxW;
            this.maxH = maxH;
            this.maxFps = maxFps;
            this.preferredProfile = preferredProfile;
            this.preferredLevel = preferredLevel;
        }
    }

    private static final class EncoderSession {
        final MediaCodec codec;
        final Surface inputSurface;
        final int codecId;
        final int width;
        final int height;
        volatile int dpi;
        final float fps;
        final int displayMode;
        final int rotation;
        volatile boolean running = true;
        Thread drainThread;
        byte[] lastCsd;

        EncoderSession(
                MediaCodec codec, Surface inputSurface, int codecId,
                int width, int height, int dpi, float fps, int displayMode, int rotation) {
            this.codec = codec;
            this.inputSurface = inputSurface;
            this.codecId = codecId;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.fps = fps;
            this.displayMode = displayMode;
            this.rotation = rotation;
        }
    }

    private final Object lock = new Object();
    private final FrameListener listener;
    private final int sinkType;
    private final VideoFrameBufferPool framePool = new VideoFrameBufferPool();
    private volatile EncoderSession session;
    private volatile int currentBitrateKbps = 12_000;
    private volatile int targetBitrateKbps = 12_000;
    private volatile int maxBitrateKbps = 32_000;
    private volatile long repeatPreviousFrameAfterUs = DEFAULT_REPEAT_FRAME_AFTER_US;
    private volatile int actualFpsX100 = 0;
    private volatile long lastSoftCongestionMs = 0L;
    private volatile long lastCongestionMs = 0L;
    private volatile long lastCongestionIdrMs = 0L;
    private volatile long lastRecoveryMs = 0L;

    public RemoteStreamSink(int sinkType, FrameListener listener) {
        this.sinkType = sinkType;
        this.listener = listener;
    }

    public void setMaxBitrateKbps(int maxKbps) {
        int clamped = DisplayGeometryUtil.clampBitrateKbps(maxKbps, MIN_BITRATE_KBPS, MAX_BITRATE_KBPS);
        this.maxBitrateKbps = clamped;
        if (currentBitrateKbps > clamped) {
            updateBitrateKbps(clamped);
        }
    }

    public void setRepeatPreviousFrameAfterUs(long repeatUs) {
        if (repeatUs <= 0L) {
            this.repeatPreviousFrameAfterUs = 0L;
        } else {
            this.repeatPreviousFrameAfterUs = Math.max(50_000L, Math.min(5_000_000L, repeatUs));
        }
    }

    /**
     * Updates the cached DPI metadata on the active encoder session without recreating MediaCodec,
     * used by DPI-only VirtualDisplay resize fast paths.
     */
    public void updateDpiOnly(int newDpi) {
        EncoderSession s = this.session;
        if (s != null && s.running) {
            s.dpi = DisplayGeometryUtil.clampDpi(newDpi, s.width, s.height);
        }
    }

    public static long resolveRepeatPreviousFrameAfterUs(String clientPlatform, float targetFps) {
        return DisplayGeometryUtil.resolveRepeatPreviousFrameAfterUs(clientPlatform, targetFps);
    }

    public static boolean shouldPreferCbrMode(String codecName) {
        return DisplayGeometryUtil.shouldPreferCbrMode(codecName);
    }

    public static int[] selectPreferredProfileLevel(String mime, int[] profiles, int[] levels) {
        return DisplayGeometryUtil.selectPreferredProfileLevel(mime, profiles, levels);
    }

    /**
     * Starts or reconfigures the hardware encoder and returns the active input {@link Surface}.
     */
    public Surface startOrReconfigure(
            int reqWidth,
            int reqHeight,
            int reqDpi,
            float reqFps,
            int reqBitrateKbps,
            int codecPref,
            int displayMode,
            int rotation) {
        synchronized (lock) {
            EncoderSession next = activateSessionLocked(
                    reqWidth, reqHeight, reqDpi, reqFps, reqBitrateKbps,
                    codecPref, displayMode, rotation, null, false,
                    "OPALDESK_ENCODER_START_FAILED", "Failed to start video encoder: ");
            return (next != null) ? next.inputSurface : null;
        }
    }

    /**
     * Reconfigures and invokes {@code swapper} with the new Surface BEFORE tearing down
     * the old Surface, ensuring seamless zero-flicker VirtualDisplay surface handover.
     */
    public boolean reconfigureAndSwap(
            int reqWidth,
            int reqHeight,
            int reqDpi,
            float reqFps,
            int reqBitrateKbps,
            int codecPref,
            int displayMode,
            int rotation,
            SurfaceSwapper swapper) {
        synchronized (lock) {
            EncoderSession next = activateSessionLocked(
                    reqWidth, reqHeight, reqDpi, reqFps, reqBitrateKbps,
                    codecPref, displayMode, rotation, swapper, true,
                    "OPALDESK_ENCODER_RECONFIG_FAILED", "Encoder reconfigure failed: ");
            return next != null;
        }
    }

    private EncoderSession activateSessionLocked(
            int reqWidth,
            int reqHeight,
            int reqDpi,
            float reqFps,
            int reqBitrateKbps,
            int codecPref,
            int displayMode,
            int rotation,
            SurfaceSwapper swapper,
            boolean requestIdrAfterSwap,
            String failLogTag,
            String failReasonPrefix) {
        int clampedTarget = DisplayGeometryUtil.clampBitrateKbps(
                reqBitrateKbps, MIN_BITRATE_KBPS, Math.max(MIN_BITRATE_KBPS, maxBitrateKbps));
        float clampedFps = DisplayGeometryUtil.clampFps(reqFps, 60f);
        int clampedRot = DisplayGeometryUtil.normalizeRotation(rotation);

        EncoderSession oldSession = this.session;
        EncoderSession nextSession;
        try {
            nextSession = createAndStartEncoder(
                    reqWidth, reqHeight, reqDpi, clampedFps, clampedTarget, codecPref, displayMode, clampedRot);
        } catch (Throwable firstErr) {
            // Some SoCs limit concurrent HW encoders: tear down old session first and retry once.
            if (oldSession != null) {
                stopSessionQuietly(oldSession);
                this.session = null;
                oldSession = null;
            }
            try {
                nextSession = createAndStartEncoder(
                        reqWidth, reqHeight, reqDpi, clampedFps, clampedTarget, codecPref, displayMode, clampedRot);
            } catch (Throwable secondErr) {
                System.err.println(failLogTag + ": " + secondErr);
                secondErr.printStackTrace();
                if (listener != null) {
                    listener.onEncoderError(failReasonPrefix + secondErr.getMessage());
                }
                return null;
            }
        }

        this.targetBitrateKbps = clampedTarget;
        this.currentBitrateKbps = clampedTarget;
        this.session = nextSession;
        startDrainThread(nextSession);

        if (swapper != null) {
            swapper.onNewSurfaceReady(
                    nextSession.inputSurface, nextSession.width, nextSession.height, nextSession.dpi);
        }
        if (oldSession != null) {
            stopSessionQuietly(oldSession);
        }
        if (requestIdrAfterSwap) {
            requestKeyFrame();
        }
        return nextSession;
    }

    @Override
    public int getSinkType() {
        return isActive() ? sinkType : SINK_NONE;
    }

    @Override
    public boolean isActive() {
        EncoderSession s = this.session;
        return s != null && s.running;
    }

    @Override
    public int getWidth() {
        EncoderSession s = this.session;
        return (s != null) ? s.width : 0;
    }

    @Override
    public int getHeight() {
        EncoderSession s = this.session;
        return (s != null) ? s.height : 0;
    }

    @Override
    public int getDpi() {
        EncoderSession s = this.session;
        return (s != null) ? s.dpi : 240;
    }

    @Override
    public float getFps() {
        EncoderSession s = this.session;
        return (s != null) ? s.fps : 60f;
    }

    @Override
    public int getBitrateKbps() {
        return currentBitrateKbps;
    }

    @Override
    public int getActualFpsX100() {
        return actualFpsX100;
    }

    public int getCodecId() {
        EncoderSession s = this.session;
        return (s != null) ? s.codecId : OpalCastProtocol.VIDEO_CODEC_H264;
    }

    public int getRotation() {
        EncoderSession s = this.session;
        return (s != null) ? s.rotation : 0;
    }

    public int getDisplayMode() {
        EncoderSession s = this.session;
        return (s != null) ? s.displayMode : OpalCastProtocol.DISPLAY_MODE_DEX;
    }

    @Override
    public Surface getInputSurface() {
        EncoderSession s = this.session;
        return (s != null && s.running) ? s.inputSurface : null;
    }

    @Override
    public void requestKeyFrame() {
        EncoderSession s = this.session;
        if (s == null || !s.running) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            s.codec.setParameters(params);
        } catch (Throwable t) {
            System.err.println("OPALDESK_ENCODER_IDR_ERR: " + t.getMessage());
        }
    }

    @Override
    public void updateBitrateKbps(int bitrateKbps) {
        int clamped = DisplayGeometryUtil.clampBitrateKbps(
                bitrateKbps, MIN_BITRATE_KBPS, Math.max(MIN_BITRATE_KBPS, maxBitrateKbps));
        this.targetBitrateKbps = clamped;
        applyBitrateToCodec(clamped);
    }

    /**
     * Soft ABR step-down (~10%) invoked when outbound video queue hits its soft watermark BEFORE
     * any frames are dropped. Smoothly lowers bitrate with zero IDR spike and holds recovery long
     * enough to prevent Qualcomm C2 rate-control sawtooth oscillation.
     */
    public void onSoftNetworkCongestion() {
        long now = SystemClock.uptimeMillis();
        if (now - lastSoftCongestionMs < ABR_SOFT_CONGESTION_COOLDOWN_MS
                || now - lastCongestionMs < ABR_SOFT_CONGESTION_COOLDOWN_MS) {
            return;
        }
        lastSoftCongestionMs = now;
        lastRecoveryMs = now;
        int reduced = Math.max(MIN_BITRATE_KBPS, (currentBitrateKbps * 90) / 100);
        if (reduced < currentBitrateKbps) {
            applyBitrateToCodec(reduced);
        }
    }

    /**
     * Called by RemoteStreamServer when outbound video queue drops frames due to network congestion.
     * Applies immediate ABR step-down (~25%) with cooldown and throttled IDR recovery so weak Wi-Fi
     * links never spiral into an IDR storm.
     */
    public void onNetworkCongestion() {
        long now = SystemClock.uptimeMillis();
        int nextBitrate = -1;
        if (now - lastCongestionMs >= ABR_CONGESTION_COOLDOWN_MS) {
            lastCongestionMs = now;
            lastRecoveryMs = now;
            int reduced = Math.max(MIN_BITRATE_KBPS, (currentBitrateKbps * 3) / 4);
            if (reduced < currentBitrateKbps) {
                this.currentBitrateKbps = reduced;
                nextBitrate = reduced;
            }
        }
        boolean wantIdr = (now - lastCongestionIdrMs >= ABR_IDR_COOLDOWN_MS);
        if (wantIdr) {
            lastCongestionIdrMs = now;
        }
        if (nextBitrate > 0 || wantIdr) {
            EncoderSession s = this.session;
            if (s != null && s.running) {
                try {
                    // Combine bitrate step-down and sync-frame request in one atomic setParameters()
                    // so HW C2 encoders (e.g. c2.qti.hevc.encoder) never swallow back-to-back calls.
                    Bundle params = new Bundle();
                    if (nextBitrate > 0) {
                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, nextBitrate * 1000);
                    }
                    if (wantIdr) {
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    }
                    s.codec.setParameters(params);
                } catch (Throwable t) {
                    System.err.println("OPALDESK_ENCODER_CONGESTION_PARAM_ERR: " + t.getMessage());
                }
            }
        }
    }

    /**
     * Called periodically when outbound queue stays healthy so ABR can gradually climb back
     * toward {@code targetBitrateKbps}.
     */
    public void maybeRecoverBitrate() {
        int target = Math.min(targetBitrateKbps, maxBitrateKbps);
        if (currentBitrateKbps >= target) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastCongestionMs < ABR_RECOVERY_INTERVAL_MS
                || now - lastSoftCongestionMs < ABR_SOFT_RECOVERY_HOLD_MS
                || now - lastRecoveryMs < ABR_RECOVERY_INTERVAL_MS) {
            return;
        }
        lastRecoveryMs = now;
        int next = Math.min(target, currentBitrateKbps + Math.max(500, (target - currentBitrateKbps) / 6));
        applyBitrateToCodec(next);
    }

    private void applyBitrateToCodec(int kbps) {
        EncoderSession s = this.session;
        this.currentBitrateKbps = kbps;
        if (s == null || !s.running) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000);
            s.codec.setParameters(params);
        } catch (Throwable t) {
            System.err.println("OPALDESK_ENCODER_BITRATE_ERR kbps=" + kbps + ": " + t.getMessage());
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            EncoderSession old = this.session;
            this.session = null;
            this.actualFpsX100 = 0;
            if (old != null) {
                stopSessionQuietly(old);
            }
            framePool.clear();
        }
    }

    private EncoderSession createAndStartEncoder(
            int reqW, int reqH, int reqDpi, float fps, int bitrateKbps,
            int codecPref, int displayMode, int rotation) throws Exception {
        String[] mimes = (codecPref == CODEC_PREF_H264)
                ? new String[]{MIME_AVC}
                : new String[]{MIME_HEVC, MIME_AVC};

        Throwable lastErr = null;
        for (String mime : mimes) {
            int codecId = MIME_HEVC.equals(mime)
                    ? OpalCastProtocol.VIDEO_CODEC_HEVC
                    : OpalCastProtocol.VIDEO_CODEC_H264;
            SelectedEncoder selected = selectPreferredEncoder(mime);
            int[][] dimCandidates = buildDimensionCandidates(
                    reqW, reqH, selected.alignW, selected.alignH, selected.maxW, selected.maxH);
            float[] fpsCandidates = DisplayGeometryUtil.buildEncoderFpsCandidates(fps, selected.maxFps);
            String[] codecNames = (selected.codecName != null)
                    ? new String[]{selected.codecName, null}
                    : new String[]{null};

            for (String codecName : codecNames) {
                for (int[] dims : dimCandidates) {
                    int w = dims[0];
                    int h = dims[1];
                    int dpi = DisplayGeometryUtil.clampDpi(reqDpi, w, h);
                    for (float candFps : fpsCandidates) {
                        // Cascade: OPTIMAL (BT.709 + High/Main profile + max-fps cap + low-latency)
                        // -> LOW_LATENCY_COMPAT (proven CBR low-latency) -> MINIMAL safe fallback.
                        for (ConfigTier tier : ConfigTier.values()) {
                            MediaCodec codec = null;
                            Surface surf = null;
                            try {
                                codec = (codecName != null)
                                        ? MediaCodec.createByCodecName(codecName)
                                        : MediaCodec.createEncoderByType(mime);
                                String activeName = (codecName != null) ? codecName : codec.getName();
                                MediaFormat fmt = buildVideoFormat(
                                        mime, activeName, w, h, candFps, bitrateKbps,
                                        selected.preferredProfile, selected.preferredLevel,
                                        repeatPreviousFrameAfterUs, tier);
                                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                                surf = codec.createInputSurface();
                                // Do NOT call surf.setFrameRate(fps, FIXED_SOURCE) on MediaCodec's GraphicBufferSource:
                                // on Android 14-16 / HyperOS it pins VirtualDisplay refreshRateOverride to `fps`, which
                                // stalls SurfaceFlinger output to 0 FPS whenever Display 0 switches to 120Hz Touch Boost.
                                codec.start();
                                System.out.println("OPALDESK_ENCODER_STARTED mime=" + mime
                                        + " codec=" + (activeName != null ? activeName : "default")
                                        + " size=" + w + "x" + h + "@" + dpi + "dpi fps=" + candFps
                                        + " bitrate=" + bitrateKbps + "kbps tier=" + tier
                                        + " repeatUs=" + repeatPreviousFrameAfterUs);
                                return new EncoderSession(codec, surf, codecId, w, h, dpi, candFps, displayMode, rotation);
                            } catch (Throwable t) {
                                lastErr = t;
                                if (surf != null) {
                                    try { surf.release(); } catch (RuntimeException e) {
                                        System.err.println("OPALDESK_ENCODER_SURF_CLEANUP_WARN: " + e.getMessage());
                                    }
                                }
                                if (codec != null) {
                                    try { codec.release(); } catch (RuntimeException e) {
                                        System.err.println("OPALDESK_ENCODER_CODEC_CLEANUP_WARN: " + e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.err.println("OPALDESK_ENCODER_ALL_FAILED req=" + reqW + "x" + reqH + "@" + fps
                + " bitrate=" + bitrateKbps + "kbps err=" + lastErr);
        if (lastErr instanceof Exception) {
            throw (Exception) lastErr;
        }
        throw new RuntimeException("Unable to configure video encoder", lastErr);
    }

    /**
     * Performs a single {@link MediaCodecList} scan to pick the preferred hardware encoder for
     * {@code mime} (falling back to software if no HW codec matches) and extracts alignment and
     * size/frame-rate caps from that exact selected encoder.
     */
    private static SelectedEncoder selectPreferredEncoder(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            MediaCodecInfo swFallback = null;
            String swMatchedType = null;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                String matchedType = matchSupportedType(info, mime);
                if (matchedType == null) continue;
                if (!isSoftwareCodec(info, info.getName())) {
                    return buildSelectedEncoder(info, matchedType);
                }
                if (swFallback == null) {
                    swFallback = info;
                    swMatchedType = matchedType;
                }
            }
            if (swFallback != null) {
                return buildSelectedEncoder(swFallback, swMatchedType);
            }
        } catch (Throwable t) {
            System.err.println("OPALDESK_ENCODER_SCAN_WARN mime=" + mime + ": " + t.getMessage());
        }
        return new SelectedEncoder(null, DEFAULT_ALIGNMENT, DEFAULT_ALIGNMENT, 0, 0, 0f, 0, 0);
    }

    private static SelectedEncoder buildSelectedEncoder(MediaCodecInfo info, String matchedType) {
        int alignW = DEFAULT_ALIGNMENT;
        int alignH = DEFAULT_ALIGNMENT;
        int maxW = 0;
        int maxH = 0;
        float maxFps = 0f;
        int preferredProfile = 0;
        int preferredLevel = 0;
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(matchedType);
            MediaCodecInfo.VideoCapabilities vc = (caps != null) ? caps.getVideoCapabilities() : null;
            if (vc != null) {
                alignW = Math.max(DEFAULT_ALIGNMENT, vc.getWidthAlignment());
                alignH = Math.max(DEFAULT_ALIGNMENT, vc.getHeightAlignment());
                android.util.Range<Integer> wRange = vc.getSupportedWidths();
                android.util.Range<Integer> hRange = vc.getSupportedHeights();
                android.util.Range<Integer> fpsRange = vc.getSupportedFrameRates();
                if (wRange != null && wRange.getUpper() != null) maxW = wRange.getUpper();
                if (hRange != null && hRange.getUpper() != null) maxH = hRange.getUpper();
                if (fpsRange != null && fpsRange.getUpper() != null) maxFps = fpsRange.getUpper();
            }
            if (caps != null && caps.profileLevels != null && caps.profileLevels.length > 0) {
                int[] profiles = new int[caps.profileLevels.length];
                int[] levels = new int[caps.profileLevels.length];
                for (int i = 0; i < caps.profileLevels.length; i++) {
                    MediaCodecInfo.CodecProfileLevel pl = caps.profileLevels[i];
                    if (pl != null) {
                        profiles[i] = pl.profile;
                        levels[i] = pl.level;
                    }
                }
                int[] chosen = selectPreferredProfileLevel(matchedType, profiles, levels);
                preferredProfile = chosen[0];
                preferredLevel = chosen[1];
            }
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_ENCODER_CAPS_WARN codec=" + info.getName() + ": " + e.getMessage());
        }
        return new SelectedEncoder(
                info.getName(), alignW, alignH, maxW, maxH, maxFps, preferredProfile, preferredLevel);
    }

    private static String matchSupportedType(MediaCodecInfo info, String mime) {
        for (String type : info.getSupportedTypes()) {
            if (mime.equalsIgnoreCase(type)) {
                return type;
            }
        }
        return null;
    }

    private static boolean isSoftwareCodec(MediaCodecInfo info, String name) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return info.isSoftwareOnly();
            } catch (NoSuchMethodError ignored) {
                // Fallback to name heuristic below.
            }
        }
        String lower = (name != null) ? name.toLowerCase(Locale.US) : "";
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.");
    }

    private static int[][] buildDimensionCandidates(
            int reqW, int reqH, int capAlignW, int capAlignH, int maxW, int maxH) {
        java.util.ArrayList<int[]> list = new java.util.ArrayList<>(4);
        int[] fitted = DisplayGeometryUtil.fitDimensionsToMaxBounds(reqW, reqH, maxW, maxH, capAlignW, capAlignH);
        addUniqueDims(list, fitted);
        int[] primary = DisplayGeometryUtil.alignForEncoder(reqW, reqH, capAlignW, capAlignH);
        addUniqueDims(list, primary);
        int[] strict16 = DisplayGeometryUtil.fitDimensionsToMaxBounds(reqW, reqH, maxW, maxH, 16, 16);
        addUniqueDims(list, strict16);
        // Safe 1080p-bounded fallback for mid-tier encoders that reject tall portrait / 2K+ heights.
        int[] safe1080 = DisplayGeometryUtil.fitDimensionsToMaxBounds(reqW, reqH, 1920, 1920, 16, 16);
        addUniqueDims(list, safe1080);
        return list.toArray(new int[0][]);
    }

    private static void addUniqueDims(java.util.List<int[]> list, int[] dims) {
        for (int[] existing : list) {
            if (existing[0] == dims[0] && existing[1] == dims[1]) return;
        }
        list.add(dims);
    }

    private static MediaFormat buildVideoFormat(
            String mime,
            String codecName,
            int width,
            int height,
            float fps,
            int bitrateKbps,
            int preferredProfile,
            int preferredLevel,
            long repeatAfterUs,
            ConfigTier tier) {
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        int targetFps = Math.max(15, Math.round(fps));
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
        // Long GOP avoids periodic giant IDR bitrate/heat spikes over reliable TCP/TLS links;
        // on-demand REQUEST_IDR handles instant recovery on connect, resize, or congestion drop.
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_INTERVAL_SECONDS);
        if (repeatAfterUs > 0L) {
            fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatAfterUs);
        }
        if (tier == ConfigTier.OPTIMAL) {
            // Explicit BT.709 SDR + Limited range VUI signaling prevents BT.601 color shift and
            // washed-out/crushed greys on macOS VideoToolbox, WebCodecs, and Android decoders.
            fmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            fmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            if (preferredProfile > 0 && preferredLevel > 0) {
                fmt.setInteger(MediaFormat.KEY_PROFILE, preferredProfile);
                fmt.setInteger(MediaFormat.KEY_LEVEL, preferredLevel);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                // Drop excess SurfaceFlinger frames when Display 0 touch-boosts above the remote
                // target FPS (e.g. 120Hz phone feeding a 60Hz stream), preserving bits/frame & thermals.
                fmt.setFloat(KEY_MAX_FPS_TO_ENCODER, (float) targetFps);
            }
        }
        if (tier != ConfigTier.MINIMAL) {
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
            // Match actual target FPS instead of pinning 60fps streams to a 120fps encoder OPP rail.
            fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps);
            if (Build.VERSION.SDK_INT >= 30) {
                // Critical on Qualcomm C2 (c2.qti.hevc.encoder): disables CVPFilter/evaDme 4-frame
                // lookahead buffering (~400ms latency) and unlocks true zero-lookahead encoding.
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                // MediaFormat.KEY_LATENCY is 1-based ("emit 1 output frame after N input frames"):
                // passing 0 is ignored by Qualcomm C2 (leaving 4-6 frame CVP lookahead); 1 requests
                // immediate frame-by-frame output with zero lookahead.
                fmt.setInteger(MediaFormat.KEY_LATENCY, 1);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            }
            boolean useCbr = (tier == ConfigTier.LOW_LATENCY_COMPAT) || shouldPreferCbrMode(codecName);
            fmt.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    useCbr
                            ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                            : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        }
        return fmt;
    }

    private void startDrainThread(final EncoderSession s) {
        Thread t = new Thread(() -> drainLoop(s), "ExtVideoEncoder");
        t.setDaemon(true);
        s.drainThread = t;
        t.start();
    }

    private void drainLoop(EncoderSession s) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (Throwable ignored) {
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long fpsWindowStartMs = SystemClock.uptimeMillis();
        int fpsWindowFrames = 0;

        while (s.running && this.session == s) {
            try {
                int outIndex = s.codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = s.codec.getOutputFormat();
                    byte[] csd = extractCsdAnnexB(outFmt);
                    if (csd.length > 0) {
                        s.lastCsd = csd;
                        emitVideoConfig(s, csd, SystemClock.elapsedRealtimeNanos() / 1000L);
                    }
                    continue;
                }
                if (outIndex < 0) {
                    continue;
                }

                try {
                    ByteBuffer buf = s.codec.getOutputBuffer(outIndex);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        long ptsUs = (info.presentationTimeUs > 0)
                                ? info.presentationTimeUs
                                : (SystemClock.elapsedRealtimeNanos() / 1000L);
                        boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        if (isConfig) {
                            byte[] data = new byte[info.size];
                            buf.get(data);
                            if (s.lastCsd == null || !Arrays.equals(s.lastCsd, data)) {
                                s.lastCsd = data;
                                emitVideoConfig(s, data, ptsUs);
                            }
                        } else {
                            int frameLen = info.size;
                            byte[] data = framePool.acquire(frameLen);
                            buf.get(data, 0, frameLen);
                            int frameFlags = 0;
                            if (isKey) {
                                frameFlags |= OpalCastProtocol.FLAG_VIDEO_KEYFRAME;
                                if (hasInlineParameterSets(s.codecId, data, frameLen)) {
                                    frameFlags |= OpalCastProtocol.FLAG_VIDEO_CONFIG_INLINE;
                                }
                            }
                            if (listener != null && s.running) {
                                listener.onVideoFrame(data, frameLen, frameFlags, ptsUs, framePool::release);
                            } else {
                                framePool.release(data);
                            }
                            fpsWindowFrames++;
                            long nowMs = SystemClock.uptimeMillis();
                            long elapsed = nowMs - fpsWindowStartMs;
                            if (elapsed >= 1000L) {
                                actualFpsX100 = Math.round(fpsWindowFrames * 100_000f / elapsed);
                                fpsWindowFrames = 0;
                                fpsWindowStartMs = nowMs;
                            }
                        }
                    }
                } finally {
                    s.codec.releaseOutputBuffer(outIndex, false);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    s.running = false;
                    break;
                }
            } catch (IllegalStateException ise) {
                handleDrainFailure(s, "OPALDESK_ENCODER_DRAIN_STATE_ERR",
                        "Video encoder illegal state: " + ise.getMessage(), ise);
                break;
            } catch (Throwable t) {
                handleDrainFailure(s, "OPALDESK_ENCODER_DRAIN_ERR",
                        "Video encoder drain failure: " + t.getMessage(), t);
                break;
            }
        }
    }

    private void handleDrainFailure(EncoderSession s, String tag, String reason, Throwable err) {
        boolean wasActive = s.running && this.session == s;
        s.running = false;
        if (wasActive) {
            System.err.println(tag + ": " + err);
            if (listener != null) {
                listener.onEncoderError(reason);
            }
        }
    }

    private void emitVideoConfig(EncoderSession s, byte[] csd, long ptsUs) {
        if (listener == null || !s.running) return;
        OpalCastProtocol.VideoConfig cfg = new OpalCastProtocol.VideoConfig(
                s.codecId,
                s.width,
                s.height,
                s.dpi,
                s.rotation,
                Math.round(s.fps * 100f),
                s.displayMode,
                csd);
        listener.onVideoConfig(cfg, ptsUs);
    }

    private static byte[] extractCsdAnnexB(MediaFormat fmt) {
        if (fmt == null) return OpalCastProtocol.EMPTY_BYTES;
        ByteBuffer csd0 = fmt.containsKey("csd-0") ? fmt.getByteBuffer("csd-0") : null;
        ByteBuffer csd1 = fmt.containsKey("csd-1") ? fmt.getByteBuffer("csd-1") : null;
        int len0 = (csd0 != null) ? csd0.remaining() : 0;
        int len1 = (csd1 != null) ? csd1.remaining() : 0;
        if (len0 + len1 <= 0) return OpalCastProtocol.EMPTY_BYTES;
        byte[] out = new byte[len0 + len1];
        if (len0 > 0) {
            ByteBuffer dup0 = csd0.duplicate();
            dup0.get(out, 0, len0);
        }
        if (len1 > 0) {
            ByteBuffer dup1 = csd1.duplicate();
            dup1.get(out, len0, len1);
        }
        return out;
    }

    private static boolean hasInlineParameterSets(int codecId, byte[] annexB, int length) {
        if (annexB == null || length < 5 || annexB.length < 5) return false;
        int offset;
        if (annexB[0] == 0 && annexB[1] == 0 && annexB[2] == 0 && annexB[3] == 1) {
            offset = 4;
        } else if (annexB[0] == 0 && annexB[1] == 0 && annexB[2] == 1) {
            offset = 3;
        } else {
            return false;
        }
        int nalByte = annexB[offset] & 0xFF;
        if (codecId == OpalCastProtocol.VIDEO_CODEC_HEVC) {
            int nalType = (nalByte >> 1) & 0x3F;
            return nalType == 32 || nalType == 33 || nalType == 34; // VPS, SPS, PPS
        }
        int nalType = nalByte & 0x1F;
        return nalType == 7 || nalType == 8; // SPS, PPS
    }

    private static void stopSessionQuietly(EncoderSession s) {
        if (s == null) return;
        s.running = false;
        try {
            s.codec.signalEndOfInputStream();
        } catch (RuntimeException e) {
            // Expected if codec was already stopped or in error state.
        }
        Thread t = s.drainThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(350L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            s.codec.stop();
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_ENCODER_STOP_WARN: " + e.getMessage());
        }
        try {
            s.codec.release();
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_ENCODER_RELEASE_WARN: " + e.getMessage());
        }
        if (s.inputSurface != null) {
            try {
                s.inputSurface.release();
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_ENCODER_SURF_RELEASE_WARN: " + e.getMessage());
            }
        }
    }
}
