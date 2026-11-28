package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

/**
 * Pure Java 11 geometry math for the Universal Per-Window Compositor:
 * dimension alignment, DPI clamping, work-area constraints, cascade placement, and snap zones.
 */
public final class DisplayGeometryUtil {
    public static final int MIN_WINDOW_EDGE_PX = 260;
    public static final int MAX_WINDOW_EDGE_PX = 4096;
    public static final int MIN_LONG_EDGE = 640;
    public static final int MIN_SHORT_EDGE = 480;
    public static final int MAX_ENCODER_LONG_EDGE = 4096;
    public static final int MAX_ENCODER_SHORT_EDGE = 2160;
    public static final int MIN_DPI = 120;
    public static final int MAX_DPI = 560;
    public static final float MIN_FPS = 24.0f;
    public static final float MAX_FPS = 165.0f;

    public static final int TOP_BAR_HEIGHT_DP = 28;
    public static final int WINDOW_CHROME_HEADER_DP = 36;
    public static final int DOCK_RESERVED_BOTTOM_DP = 70;
    public static final int SNAP_GAP_DP = 8;

    private DisplayGeometryUtil() {}

    public static final class IntRect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public IntRect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = Math.max(left, right);
            this.bottom = Math.max(top, bottom);
        }

        public int width() {
            return Math.max(0, right - left);
        }

        public int height() {
            return Math.max(0, bottom - top);
        }

        public boolean isEmpty() {
            return width() <= 0 || height() <= 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IntRect)) return false;
            IntRect r = (IntRect) o;
            return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
        }

        @Override
        public int hashCode() {
            int res = left;
            res = 31 * res + top;
            res = 31 * res + right;
            res = 31 * res + bottom;
            return res;
        }
    }

    public static int dpToPx(float dp, int dpi) {
        int effDpi = clampDpi(dpi);
        return Math.max(1, Math.round(dp * (effDpi / 160.0f)));
    }

    public static int alignEven(int value, int minVal, int maxVal) {
        int clamped = Math.max(minVal, Math.min(maxVal, value));
        return (clamped & ~1);
    }

    public static int clampDpi(int dpi) {
        if (dpi <= 0) return 220;
        return Math.max(MIN_DPI, Math.min(MAX_DPI, dpi));
    }

    public static final int MAX_SUPERSAMPLED_LONG_EDGE = 2400;
    public static final long MAX_SUPERSAMPLED_PIXELS = 3_800_000L;
    public static final int MAX_STANDALONE_LONG_EDGE = 2160;
    public static final long MAX_STANDALONE_PIXELS = 2_800_000L;

    public static int computeAutoDpi(int w, int h) {
        int minEdge = Math.min(Math.max(1, w), Math.max(1, h));
        float targetDp = (minEdge <= 960) ? 412f : 720f;
        return Math.max(160, Math.min(440, Math.round(minEdge * 160f / targetDp)));
    }

    public static int clampDpi(int dpi, int fallbackW, int fallbackH) {
        if (dpi < 80 || dpi > 640) {
            return computeAutoDpi(fallbackW, fallbackH);
        }
        return dpi;
    }

    /**
     * Computes a crisp, readable DPI for a remote standalone floating app VirtualDisplay
     * ({@code streamW x streamH}) when the receiver did not specify an explicit DPI.
     */
    public static int computeStandaloneAppDpi(
            int streamW,
            int streamH,
            int hostDisplayW,
            int hostDisplayH,
            int hostDisplayDpi,
            int dpiScalePct) {
        int safeStreamW = Math.max(240, streamW);
        int safeStreamH = Math.max(240, streamH);
        int streamShort = Math.min(safeStreamW, safeStreamH);
        boolean isPortrait = safeStreamH >= safeStreamW;

        float hostShortDp = 400f;
        if (hostDisplayW > 0 && hostDisplayH > 0 && hostDisplayDpi >= 120 && hostDisplayDpi <= 800) {
            int hostShortPx = Math.min(hostDisplayW, hostDisplayH);
            hostShortDp = Math.max(360f, Math.min(960f, hostShortPx * 160f / hostDisplayDpi));
        }

        float targetShortDp;
        if (isPortrait) {
            if (hostShortDp <= 520f) {
                // Phone host: keep readable ~385-412dp logical width so text is never tiny inside floating windows
                targetShortDp = Math.max(360f, Math.min(412f, hostShortDp));
            } else {
                float aspect = (float) safeStreamW / (float) safeStreamH;
                targetShortDp = (aspect < 0.72f)
                        ? Math.max(380f, Math.min(440f, hostShortDp * 0.58f))
                        : Math.max(460f, Math.min(600f, hostShortDp * 0.78f));
            }
        } else {
            targetShortDp = Math.max(360f, Math.min(540f, hostShortDp));
        }

        int baseDpi = Math.round(streamShort * 160f / targetShortDp);
        int clampedPct = Math.max(70, Math.min(140, dpiScalePct <= 0 ? 100 : dpiScalePct));
        int scaled = Math.round(baseDpi * (clampedPct / 100f));
        return Math.max(MIN_DPI, Math.min(MAX_DPI, scaled));
    }

    /**
     * Resolves encoder-aligned {@code [streamW, streamH, streamDpi]} for a standalone floating app
     * session. Respects receiver-computed 1:1 readable DPI and proportionally supersamples up toward
     * {@code hostDisplayDpi} so density-pinned apps (e.g. WeChat {@code always-compat} on 560dpi phones)
     * render at the exact same logical layout & crispness as standard Android apps.
     */
    public static int[] resolveStandaloneStreamSpec(
            int reqW,
            int reqH,
            int reqDpi,
            int hostDisplayW,
            int hostDisplayH,
            int hostDisplayDpi,
            int dpiScalePct) {
        int[] aligned = alignForEncoder(reqW, reqH, 8);
        int baseW = aligned[0];
        int baseH = aligned[1];
        int clampedPct = Math.max(70, Math.min(140, dpiScalePct <= 0 ? 100 : dpiScalePct));
        int baseDpi;
        if (reqDpi >= 120 && reqDpi <= MAX_DPI) {
            baseDpi = Math.max(MIN_DPI, Math.min(MAX_DPI, Math.round(reqDpi * (clampedPct / 100f))));
        } else {
            baseDpi = computeStandaloneAppDpi(baseW, baseH, hostDisplayW, hostDisplayH, hostDisplayDpi, clampedPct);
        }

        int targetHostDpi = (hostDisplayDpi >= 240 && hostDisplayDpi <= MAX_DPI) ? hostDisplayDpi : 0;
        if (targetHostDpi > baseDpi) {
            float scale = computeSupersampleScale(
                    baseW, baseH, baseDpi, targetHostDpi, MAX_STANDALONE_LONG_EDGE, MAX_STANDALONE_PIXELS);
            if (scale > 1.05f) {
                int[] up = alignForEncoder(
                        Math.round(baseW * scale),
                        Math.round(baseH * scale),
                        8);
                // Compute effective actual ratio so logical dp (width / (dpi/160)) stays exact
                float actualScale = (float) up[0] / (float) baseW;
                int upDpi = Math.max(MIN_DPI, Math.min(MAX_DPI, Math.round(baseDpi * actualScale)));
                return new int[]{up[0], up[1], upDpi};
            }
        }
        return new int[]{baseW, baseH, baseDpi};
    }

    public static final int WINDOW_SUPERSAMPLE_COMPAT = 0;
    public static final int WINDOW_SUPERSAMPLE_BALANCED = 1;
    public static final int WINDOW_SUPERSAMPLE_NATIVE_1X = 2;

    private static final int BALANCED_SUPERSAMPLED_LONG_EDGE = 1920;
    private static final long BALANCED_SUPERSAMPLED_PIXELS = 2_200_000L;
    private static final float BALANCED_SUPERSAMPLE_MAX_SCALE = 1.45f;

    public static int clampWindowSupersampleMode(int mode) {
        if (mode == WINDOW_SUPERSAMPLE_BALANCED || mode == WINDOW_SUPERSAMPLE_NATIVE_1X) {
            return mode;
        }
        return WINDOW_SUPERSAMPLE_COMPAT;
    }

    public static int[] resolveWindowBufferSpec(
            IntRect windowFrame,
            int canvasDpi,
            boolean fullscreen,
            WindowProfile profile,
            int dpiScalePct,
            int hostDisplayDpi) {
        return resolveWindowBufferSpec(
                windowFrame, canvasDpi, fullscreen, profile, dpiScalePct, hostDisplayDpi, 0, WINDOW_SUPERSAMPLE_COMPAT);
    }

    /**
     * Resolves {@code [bufW, bufH, vdDpi]} for a compositor window's isolated VirtualDisplay.
     * When {@code customDpiOverride} is in {@code [MIN_DPI, MAX_DPI]}, applies that exact DPI directly
     * without supersampling distortion. Otherwise scales buffer pixels and VirtualDisplay DPI together
     * toward {@code hostDisplayDpi} (keeping exact logical dp invariant!) so apps that pin Display 0
     * compat density (e.g. WeChat {@code compat={440/560dpi}}) never explode or clip when shrunk.
     */
    public static int[] resolveWindowBufferSpec(
            IntRect windowFrame,
            int canvasDpi,
            boolean fullscreen,
            WindowProfile profile,
            int dpiScalePct,
            int hostDisplayDpi,
            int customDpiOverride) {
        return resolveWindowBufferSpec(
                windowFrame, canvasDpi, fullscreen, profile, dpiScalePct, hostDisplayDpi,
                customDpiOverride, WINDOW_SUPERSAMPLE_COMPAT);
    }

    public static int[] resolveWindowBufferSpec(
            IntRect windowFrame,
            int canvasDpi,
            boolean fullscreen,
            WindowProfile profile,
            int dpiScalePct,
            int hostDisplayDpi,
            int customDpiOverride,
            int supersampleMode) {
        int[] raw = computeContentBufferSize(windowFrame, canvasDpi, fullscreen);
        int rawW = raw[0];
        int rawH = raw[1];
        if (customDpiOverride >= MIN_DPI && customDpiOverride <= MAX_DPI) {
            return new int[]{rawW, rawH, clampDpi(customDpiOverride)};
        }
        WindowProfile effProfile = (profile != null) ? profile : WindowProfile.DESKTOP;
        int baseDpi = effProfile.resolveContentDpi(rawW, rawH, dpiScalePct);
        int mode = clampWindowSupersampleMode(supersampleMode);
        if (mode == WINDOW_SUPERSAMPLE_NATIVE_1X) {
            return new int[]{rawW, rawH, baseDpi};
        }
        int targetHostDpi = (hostDisplayDpi >= 240 && hostDisplayDpi <= MAX_DPI) ? hostDisplayDpi : 0;
        if (targetHostDpi > baseDpi) {
            int maxLong = (mode == WINDOW_SUPERSAMPLE_BALANCED)
                    ? BALANCED_SUPERSAMPLED_LONG_EDGE : MAX_SUPERSAMPLED_LONG_EDGE;
            long maxPixels = (mode == WINDOW_SUPERSAMPLE_BALANCED)
                    ? BALANCED_SUPERSAMPLED_PIXELS : MAX_SUPERSAMPLED_PIXELS;
            float scale = computeSupersampleScale(rawW, rawH, baseDpi, targetHostDpi, maxLong, maxPixels);
            if (mode == WINDOW_SUPERSAMPLE_BALANCED) {
                scale = Math.min(scale, BALANCED_SUPERSAMPLE_MAX_SCALE);
            }
            if (scale > 1.05f) {
                int upW = alignEven(Math.round(rawW * scale), 240, MAX_WINDOW_EDGE_PX);
                int upH = alignEven(Math.round(rawH * scale), 200, MAX_WINDOW_EDGE_PX);
                float actualScale = (float) upW / (float) rawW;
                int upDpi = Math.max(MIN_DPI, Math.min(MAX_DPI, Math.round(baseDpi * actualScale)));
                return new int[]{upW, upH, upDpi};
            }
        }
        return new int[]{rawW, rawH, baseDpi};
    }

    private static float computeSupersampleScale(
            int w, int h, int baseDpi, int targetHostDpi, int maxLongEdge, long maxPixels) {
        if (w <= 0 || h <= 0 || baseDpi <= 0 || targetHostDpi <= baseDpi) return 1.0f;
        int longEdge = Math.max(w, h);
        if (longEdge >= maxLongEdge) return 1.0f;
        double pixels = (double) w * (double) h;
        if (pixels >= (double) maxPixels) return 1.0f;
        float dpiScale = (float) targetHostDpi / (float) baseDpi;
        float longCap = (float) maxLongEdge / (float) longEdge;
        float pixelCap = (float) Math.sqrt((double) maxPixels / pixels);
        return Math.max(1.0f, Math.min(dpiScale, Math.min(longCap, pixelCap)));
    }

    public static int normalizeRotation(int rot) {
        return ((rot % 4) + 4) % 4;
    }

    public static final int DISPLAY_MODE_DEX = 0;
    public static final int DISPLAY_MODE_MIRROR = 1;
    public static final int DISPLAY_MODE_UNCHANGED = 0xFF;
    public static final int MIN_MIRROR_LONG_EDGE = 1280;
    public static final int MAX_MIRROR_LONG_EDGE = 2400;

    /**
     * Resolves the target OpalCast display mode for {@code SYS_TOGGLE_DISPLAY_MODE}.
     * Explicit {@code 0} ({@link #DISPLAY_MODE_DEX}) and {@code 1} ({@link #DISPLAY_MODE_MIRROR})
     * select that mode idempotently; negative values or {@code 0xFF} toggle the current mode.
     */
    public static int resolveTargetDisplayMode(int currentMode, int actionArg) {
        if (actionArg == DISPLAY_MODE_DEX) return DISPLAY_MODE_DEX;
        if (actionArg == DISPLAY_MODE_MIRROR) return DISPLAY_MODE_MIRROR;
        return (currentMode == DISPLAY_MODE_MIRROR) ? DISPLAY_MODE_DEX : DISPLAY_MODE_MIRROR;
    }

    /**
     * Computes encoder-aligned {@code [mirrorW, mirrorH, mirrorDpi]} matching Display 0's live
     * orientation and aspect ratio while scaling the long edge to fit inside {@code maxLongEdge}
     * (clamped to {@code [1280, 2400]}) so hardware encoders sustain smooth high-FPS mirroring
     * without letterboxing or distortion.
     */
    public static int[] computeMirrorStreamSpec(
            int primaryW,
            int primaryH,
            int primaryDpi,
            int maxLongEdge,
            int alignment) {
        int srcW = primaryW > 0 ? primaryW : 1080;
        int srcH = primaryH > 0 ? primaryH : 1920;
        int srcDpi = clampDpi(primaryDpi, srcW, srcH);
        int align = Math.max(2, alignment);
        if ((align & 1) != 0) align += 1;

        int capLong = (maxLongEdge > 0)
                ? Math.max(MIN_MIRROR_LONG_EDGE, Math.min(MAX_MIRROR_LONG_EDGE, maxLongEdge))
                : MAX_MIRROR_LONG_EDGE;
        int srcLong = Math.max(srcW, srcH);
        double scale = (srcLong > capLong) ? ((double) capLong / (double) srcLong) : 1.0;

        int rawW = (int) Math.round(srcW * scale);
        int rawH = (int) Math.round(srcH * scale);
        int[] aligned = alignForEncoder(rawW, rawH, align, align);
        double actualScale = (double) Math.max(aligned[0], aligned[1]) / (double) Math.max(1, srcLong);
        int mirrorDpi = clampDpi((int) Math.round(srcDpi * actualScale), aligned[0], aligned[1]);
        return new int[]{aligned[0], aligned[1], mirrorDpi};
    }

    /**
     * Maps a remote pointer coordinate {@code (x, y)} from encoded stream space
     * {@code [0, streamW) x [0, streamH)} into the target display's logical coordinate space
     * {@code [0, targetW) x [0, targetH)}.
     */
    public static float[] mapRemotePointToTargetDisplay(
            float x,
            float y,
            int streamW,
            int streamH,
            int targetW,
            int targetH) {
        int sw = Math.max(2, streamW);
        int sh = Math.max(2, streamH);
        int tw = Math.max(2, targetW);
        int th = Math.max(2, targetH);
        float clampedX = Math.max(0f, Math.min(sw - 1f, x));
        float clampedY = Math.max(0f, Math.min(sh - 1f, y));
        float mappedX = (sw == tw) ? clampedX : (clampedX * ((float) tw / (float) sw));
        float mappedY = (sh == th) ? clampedY : (clampedY * ((float) th / (float) sh));
        return new float[]{
                Math.max(0f, Math.min(tw - 1f, mappedX)),
                Math.max(0f, Math.min(th - 1f, mappedY))
        };
    }

    public static final int MIN_SESSION_BITRATE_KBPS = 2_500;
    public static final int WIRELESS_MAX_BITRATE_KBPS = 80_000;
    public static final int WIRED_MAX_BITRATE_KBPS = 100_000;
    public static final int AUTO_RECOMMENDED_MAX_BITRATE_KBPS = 60_000;
    public static final int HEVC_BITRATE_SCALE_PCT = 85;
    public static final int MIN_STATUS_BAR_INSET_SDK = 34;

    public static final long AGGRESSIVE_REPEAT_FRAME_AFTER_US = 100_000L;
    public static final long DEFAULT_REPEAT_FRAME_AFTER_US = 1_000_000L;
    public static final int AVC_PROFILE_MAIN = 2;
    public static final int AVC_PROFILE_HIGH = 8;
    public static final int HEVC_PROFILE_MAIN = 1;

    private static final int SINK_TYPE_REMOTE_USB = 2;
    private static final int SINK_TYPE_REMOTE_USB_TETHER = 6;

    public enum ReconfigureAction {
        NO_OP,
        BITRATE_ONLY,
        DPI_ONLY,
        FULL_RECONFIGURE
    }

    public static boolean isWiredSinkType(int sinkType) {
        return sinkType == SINK_TYPE_REMOTE_USB || sinkType == SINK_TYPE_REMOTE_USB_TETHER;
    }

    public static int resolveMaxBitrateKbpsForSink(int sinkType) {
        return isWiredSinkType(sinkType) ? WIRED_MAX_BITRATE_KBPS : WIRELESS_MAX_BITRATE_KBPS;
    }

    /**
     * Classifies a live RESIZE_CONFIG transition so bitrate-only and DPI-only updates avoid tearing
     * down and recreating the hardware MediaCodec encoder (zero black flash, zero IDR storm).
     */
    public static ReconfigureAction planReconfigureAction(
            int curW, int curH, int curDpi, float curFps, int curBitrateKbps, int curMode, int curRot,
            int nextW, int nextH, int nextDpi, float nextFps, int nextBitrateKbps, int nextMode, int nextRot) {
        boolean encoderShapeUnchanged = (curW == nextW)
                && (curH == nextH)
                && (Math.abs(curFps - nextFps) < 0.5f)
                && (curMode == nextMode)
                && (curRot == nextRot);
        if (!encoderShapeUnchanged) {
            return ReconfigureAction.FULL_RECONFIGURE;
        }
        if (curDpi != nextDpi) {
            return ReconfigureAction.DPI_ONLY;
        }
        if (curBitrateKbps != nextBitrateKbps) {
            return ReconfigureAction.BITRATE_ONLY;
        }
        return ReconfigureAction.NO_OP;
    }

    /**
     * Chooses static-screen repeat-frame cadence based on receiver platform & target FPS:
     * Android high-refresh peers (>60Hz) use 10Hz keepalive to prevent receiver panels (e.g.
     * HyperOS MiMediaCodec) from throttling down to 30Hz; Mac/Windows/Web and 60Hz streams use
     * 1Hz to slash static VENC & Wi-Fi radio wakeups (and heat) by 10x.
     */
    public static long resolveRepeatPreviousFrameAfterUs(String clientPlatform, float targetFps) {
        boolean isAndroidPeer = "android".equalsIgnoreCase(
                clientPlatform != null ? clientPlatform.trim() : "");
        return (isAndroidPeer && targetFps > 60.5f)
                ? AGGRESSIVE_REPEAT_FRAME_AFTER_US
                : DEFAULT_REPEAT_FRAME_AFTER_US;
    }

    /**
     * Qualcomm C2/OMX hardware encoders require CBR to disable CVPFilter/evaDme 4-frame lookahead;
     * known non-Qualcomm HW encoders (MediaTek/Exynos/HiSilicon/Unisoc) benefit from VBR for lower
     * static power and cleaner high-motion text bursts.
     */
    public static boolean shouldPreferCbrMode(String codecName) {
        if (codecName == null || codecName.trim().isEmpty()) {
            return true;
        }
        String lower = codecName.trim().toLowerCase(java.util.Locale.US);
        if (lower.startsWith("c2.mtk.") || lower.startsWith("omx.mtk.")
                || lower.startsWith("c2.exynos.") || lower.startsWith("omx.exynos.")
                || lower.startsWith("c2.hisi.") || lower.startsWith("omx.hisi.")
                || lower.startsWith("c2.unisoc.") || lower.startsWith("omx.sprd.")) {
            return false;
        }
        return true;
    }

    /**
     * Selects the highest-efficiency standard profile (H.264 High -> Main, HEVC Main) and its
     * highest advertised level from an encoder's capability table so H.264 encoders do not silently
     * default to Baseline (CAVLC-only).
     */
    public static int[] selectPreferredProfileLevel(String mime, int[] profiles, int[] levels) {
        if (mime == null || profiles == null || levels == null || profiles.length == 0) {
            return new int[]{0, 0};
        }
        int count = Math.min(profiles.length, levels.length);
        if ("video/avc".equalsIgnoreCase(mime)) {
            int highLevel = findMaxLevelForProfile(profiles, levels, count, AVC_PROFILE_HIGH);
            if (highLevel > 0) return new int[]{AVC_PROFILE_HIGH, highLevel};
            int mainLevel = findMaxLevelForProfile(profiles, levels, count, AVC_PROFILE_MAIN);
            if (mainLevel > 0) return new int[]{AVC_PROFILE_MAIN, mainLevel};
        } else if ("video/hevc".equalsIgnoreCase(mime)) {
            int mainLevel = findMaxLevelForProfile(profiles, levels, count, HEVC_PROFILE_MAIN);
            if (mainLevel > 0) return new int[]{HEVC_PROFILE_MAIN, mainLevel};
        }
        return new int[]{0, 0};
    }

    private static int findMaxLevelForProfile(int[] profiles, int[] levels, int count, int targetProfile) {
        int bestLevel = 0;
        for (int i = 0; i < count; i++) {
            if (profiles[i] == targetProfile && levels[i] > bestLevel) {
                bestLevel = levels[i];
            }
        }
        return bestLevel;
    }

    /**
     * Only attach shell-owned {@code TYPE_STATUS_BAR} inset windows on Android 14+ (API 34+),
     * avoiding asynchronous main-Looper {@code ViewRootImpl} crashes on Android 11-13 ROMs.
     */
    public static boolean isVirtualDisplayStatusBarInsetSupported(int sdkInt) {
        return sdkInt >= MIN_STATUS_BAR_INSET_SDK;
    }

    public static int clampBitrateKbps(int bitrateKbps, int minKbps, int maxKbps) {
        int lo = Math.max(500, minKbps);
        int hi = Math.max(lo, maxKbps);
        if (bitrateKbps <= 0) return Math.min(hi, Math.max(lo, 12_000));
        return Math.max(lo, Math.min(hi, bitrateKbps));
    }

    /**
     * Resolves the effective OpalCast session bitrate: honors any explicit positive client bitrate
     * request (so power-saving or standalone-window caps are never overridden upward) and falls back
     * to codec-aware recommendations only when the client passes {@code <= 0} (auto).
     */
    public static int resolveSessionBitrateKbps(
            int requestedBitrateKbps,
            int w,
            int h,
            float fps,
            boolean preferHevc,
            int maxBitrateKbps) {
        int raw = (requestedBitrateKbps > 0)
                ? requestedBitrateKbps
                : computeRecommendedBitrateKbps(w, h, fps, preferHevc, maxBitrateKbps);
        return clampBitrateKbps(raw, MIN_SESSION_BITRATE_KBPS, maxBitrateKbps);
    }

    public static int computeRecommendedBitrateKbps(
            int w,
            int h,
            float fps,
            boolean preferHevc,
            int maxBitrateKbps) {
        long pixels = (long) Math.max(640, w) * Math.max(480, h);
        boolean uhd = pixels >= 5_500_000L; // >= 4K / super-ultrawide
        boolean highRes = pixels >= 2_800_000L; // >= 2560x1100
        boolean highFps = fps >= 85f;
        int baseKbps;
        int autoCeiling = Math.min(maxBitrateKbps, AUTO_RECOMMENDED_MAX_BITRATE_KBPS);
        if (uhd && highFps) baseKbps = autoCeiling;
        else if (uhd) baseKbps = 24_000;
        else if (highRes && highFps) baseKbps = 22_000;
        else if (highRes) baseKbps = 16_000;
        else if (highFps) baseKbps = 15_000;
        else baseKbps = 11_000;
        if (preferHevc) {
            return Math.max(MIN_SESSION_BITRATE_KBPS, (baseKbps * HEVC_BITRATE_SCALE_PCT) / 100);
        }
        return baseKbps;
    }

    /**
     * Scales {@code (reqW, reqH)} proportionally to fit inside an encoder's maximum supported
     * {@code (maxW, maxH)} bounds while preserving aspect ratio and aligning down to
     * {@code (alignW, alignH)} so neither edge exceeds the hardware limit.
     */
    public static int[] fitDimensionsToMaxBounds(
            int reqW,
            int reqH,
            int maxW,
            int maxH,
            int alignW,
            int alignH) {
        int aw = Math.max(2, alignW);
        int ah = Math.max(2, alignH);
        int[] base = alignForEncoder(reqW, reqH, aw, ah);
        int w = base[0];
        int h = base[1];
        if (maxW <= 0 || maxH <= 0 || (w <= maxW && h <= maxH)) {
            return new int[]{w, h};
        }
        double scale = Math.min((double) maxW / (double) w, (double) maxH / (double) h);
        int capW = (maxW / aw) * aw;
        int capH = (maxH / ah) * ah;
        int scaledW = ((int) Math.floor(w * scale) / aw) * aw;
        int scaledH = ((int) Math.floor(h * scale) / ah) * ah;
        int minW = Math.min(capW, Math.max(aw, (320 / aw) * aw));
        int minH = Math.min(capH, Math.max(ah, (320 / ah) * ah));
        scaledW = Math.max(minW, Math.min(capW, scaledW));
        scaledH = Math.max(minH, Math.min(capH, scaledH));
        return new int[]{Math.max(aw, scaledW), Math.max(ah, scaledH)};
    }

    /**
     * Builds a deduplicated descending list of FPS candidates starting from
     * {@code min(requestedFps, maxSupportedFps)} with safe {@code 60f} / {@code 30f} fallbacks for
     * encoders whose hardware rate ceiling rejects high-refresh requests (e.g. 120fps on mid-tier SoCs).
     */
    public static float[] buildEncoderFpsCandidates(float requestedFps, float maxSupportedFps) {
        float req = clampFps(requestedFps, 60f);
        float primary = (Float.isFinite(maxSupportedFps) && maxSupportedFps >= 15f)
                ? Math.max(15f, Math.min(req, maxSupportedFps))
                : req;
        float[] buf = new float[3];
        int count = 0;
        buf[count++] = primary;
        if (primary > 60.5f) {
            buf[count++] = 60f;
        }
        if (primary > 30.5f) {
            buf[count++] = 30f;
        }
        float[] out = new float[count];
        System.arraycopy(buf, 0, out, 0, count);
        return out;
    }

    public static int alignDimension(int value, int alignment, int minVal, int maxVal) {
        int align = Math.max(2, alignment);
        if ((align & 1) != 0) align += 1;
        int clampedMin = ((minVal + align - 1) / align) * align;
        int clampedMax = (maxVal / align) * align;
        if (clampedMax < clampedMin) clampedMax = clampedMin;
        int v = Math.max(clampedMin, Math.min(clampedMax, value));
        int rounded = ((v + (align / 2)) / align) * align;
        return Math.max(clampedMin, Math.min(clampedMax, rounded));
    }

    public static int[] alignForEncoder(int width, int height, int alignment) {
        return alignForEncoder(width, height, alignment, alignment);
    }

    public static int[] alignForEncoder(int width, int height, int alignW, int alignH) {
        int safeW = width > 0 ? width : 1920;
        int safeH = height > 0 ? height : 1080;
        boolean portrait = safeH > safeW;
        int minW = portrait ? MIN_SHORT_EDGE : MIN_LONG_EDGE;
        int maxW = portrait ? MAX_ENCODER_SHORT_EDGE : MAX_ENCODER_LONG_EDGE;
        int minH = portrait ? MIN_LONG_EDGE : MIN_SHORT_EDGE;
        int maxH = portrait ? MAX_ENCODER_LONG_EDGE : MAX_ENCODER_SHORT_EDGE;
        if (safeW > maxW || safeH > maxH) {
            double scale = Math.min((double) maxW / safeW, (double) maxH / safeH);
            safeW = (int) Math.round(safeW * scale);
            safeH = (int) Math.round(safeH * scale);
        }
        return new int[]{
                alignDimension(safeW, alignW, minW, maxW),
                alignDimension(safeH, alignH, minH, maxH)
        };
    }

    public static float clampFps(float fps, float fallback) {
        if (!Float.isFinite(fps) || fps <= 0f) return fallback;
        return Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
    }

    // ---- External display EDID / anisotropy & window snap geometry delegates ----

    public static final float ANISOTROPY_DPI_RATIO_THRESHOLD = WindowSnapGeometry.ANISOTROPY_DPI_RATIO_THRESHOLD;

    public static int[] alignExternalForcedSize(int width, int height) {
        return WindowSnapGeometry.alignExternalForcedSize(width, height);
    }

    public static boolean matchesDimensionsIgnoringRotation(int w1, int h1, int w2, int h2) {
        return WindowSnapGeometry.matchesDimensionsIgnoringRotation(w1, h1, w2, h2);
    }

    public static boolean isAnisotropicPixelDensity(float xDpi, float yDpi) {
        return WindowSnapGeometry.isAnisotropicPixelDensity(xDpi, yDpi);
    }

    public static boolean hasExternalPixelAnisotropy(
            int nativeW, int nativeH, float xDpi, float yDpi,
            int[] modeWidths, int[] modeHeights, boolean[] modeSynthetic) {
        return WindowSnapGeometry.hasExternalPixelAnisotropy(
                nativeW, nativeH, xDpi, yDpi, modeWidths, modeHeights, modeSynthetic);
    }

    public static boolean isMirroredPrimaryDisplayCollapse(
            int nativeW, int nativeH, int logicalW, int logicalH, int primaryW, int primaryH) {
        return WindowSnapGeometry.isMirroredPrimaryDisplayCollapse(
                nativeW, nativeH, logicalW, logicalH, primaryW, primaryH);
    }

    public static boolean shouldDisableScalingForNativeAnisotropy(
            int targetW, int targetH, int nativeW, int nativeH, boolean hasAnisotropy, int scalingModePref) {
        return WindowSnapGeometry.shouldDisableScalingForNativeAnisotropy(
                targetW, targetH, nativeW, nativeH, hasAnisotropy, scalingModePref);
    }

    public static int selectBestEdidModeIndex(
            int[] widths, int[] heights, float[] rates, int reqW, int reqH, float reqFps) {
        return selectBestEdidModeIndex(widths, heights, rates, null, reqW, reqH, reqFps);
    }

    public static int selectBestEdidModeIndex(
            int[] widths, int[] heights, float[] rates, boolean[] syntheticFlags,
            int reqW, int reqH, float reqFps) {
        return WindowSnapGeometry.selectBestEdidModeIndex(widths, heights, rates, syntheticFlags, reqW, reqH, reqFps);
    }

    public static int selectBestEdidModeForRefreshRate(
            int[] widths, int[] heights, float[] rates, int curW, int curH, float reqFps) {
        return WindowSnapGeometry.selectBestEdidModeForRefreshRate(widths, heights, rates, curW, curH, reqFps);
    }

    public static IntRect computeWorkArea(int canvasW, int canvasH, int canvasDpi) {
        return WindowSnapGeometry.computeWorkArea(canvasW, canvasH, canvasDpi);
    }

    public static IntRect computeInitialWindowFrame(
            WindowProfile profile, int cascadeIndex, int canvasW, int canvasH, int canvasDpi) {
        return WindowSnapGeometry.computeInitialWindowFrame(profile, cascadeIndex, canvasW, canvasH, canvasDpi);
    }

    public static IntRect clampWindowFrame(IntRect raw, int canvasW, int canvasH, int canvasDpi) {
        return WindowSnapGeometry.clampWindowFrame(raw, canvasW, canvasH, canvasDpi);
    }

    public static IntRect computeSnapFrame(String presentation, int canvasW, int canvasH, int canvasDpi) {
        return WindowSnapGeometry.computeSnapFrame(presentation, canvasW, canvasH, canvasDpi);
    }

    public static int[] computeContentBufferSize(IntRect windowFrame, int canvasDpi) {
        return computeContentBufferSize(windowFrame, canvasDpi, false);
    }

    public static int[] computeContentBufferSize(IntRect windowFrame, int canvasDpi, boolean fullscreen) {
        return WindowSnapGeometry.computeContentBufferSize(windowFrame, canvasDpi, fullscreen);
    }
}
