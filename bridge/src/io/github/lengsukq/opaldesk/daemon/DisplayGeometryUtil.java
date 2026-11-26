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

    public static int[] resolveWindowBufferSpec(
            IntRect windowFrame,
            int canvasDpi,
            boolean fullscreen,
            WindowProfile profile,
            int dpiScalePct,
            int hostDisplayDpi) {
        return resolveWindowBufferSpec(windowFrame, canvasDpi, fullscreen, profile, dpiScalePct, hostDisplayDpi, 0);
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
        int[] raw = computeContentBufferSize(windowFrame, canvasDpi, fullscreen);
        int rawW = raw[0];
        int rawH = raw[1];
        if (customDpiOverride >= MIN_DPI && customDpiOverride <= MAX_DPI) {
            return new int[]{rawW, rawH, clampDpi(customDpiOverride)};
        }
        WindowProfile effProfile = (profile != null) ? profile : WindowProfile.DESKTOP;
        int baseDpi = effProfile.resolveContentDpi(rawW, rawH, dpiScalePct);
        int targetHostDpi = (hostDisplayDpi >= 240 && hostDisplayDpi <= MAX_DPI) ? hostDisplayDpi : 0;
        if (targetHostDpi > baseDpi) {
            float scale = computeSupersampleScale(
                    rawW, rawH, baseDpi, targetHostDpi, MAX_SUPERSAMPLED_LONG_EDGE, MAX_SUPERSAMPLED_PIXELS);
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

    public static int clampBitrateKbps(int bitrateKbps, int minKbps, int maxKbps) {
        int lo = Math.max(500, minKbps);
        int hi = Math.max(lo, maxKbps);
        if (bitrateKbps <= 0) return Math.min(hi, Math.max(lo, 12_000));
        return Math.max(lo, Math.min(hi, bitrateKbps));
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

    /**
     * Validates and even-aligns a custom external display size override ({@code wm size WxH -d <id>}).
     * Supports both landscape and portrait dimensions up to 4K / ultrawide, returning {@code null}
     * when {@code width <= 0} or {@code height <= 0}.
     */
    public static int[] alignExternalForcedSize(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        boolean portrait = height > width;
        int minW = portrait ? MIN_SHORT_EDGE : MIN_LONG_EDGE;
        int maxW = portrait ? 2560 : MAX_ENCODER_LONG_EDGE;
        int minH = portrait ? MIN_LONG_EDGE : MIN_SHORT_EDGE;
        int maxH = portrait ? MAX_ENCODER_LONG_EDGE : 2560;
        int safeW = width;
        int safeH = height;
        if (safeW > maxW || safeH > maxH) {
            double scale = Math.min((double) maxW / safeW, (double) maxH / safeH);
            safeW = (int) Math.round(safeW * scale);
            safeH = (int) Math.round(safeH * scale);
        }
        return new int[]{
                alignEven(safeW, minW, maxW),
                alignEven(safeH, minH, maxH)
        };
    }

    /**
     * Picks the best matching EDID mode index for an exact {@code reqW x reqH} request.
     * Matches either normal orientation or swapped orientation when physical EDID is reported rotated;
     * when {@code reqFps > 1f}, chooses the closest refresh rate, otherwise chooses the highest refresh rate.
     */
    public static int selectBestEdidModeIndex(
            int[] widths, int[] heights, float[] rates, int reqW, int reqH, float reqFps) {
        if (widths == null || heights == null || rates == null) return -1;
        int count = Math.min(widths.length, Math.min(heights.length, rates.length));
        if (count <= 0 || reqW <= 0 || reqH <= 0) return -1;
        int bestExactIdx = -1;
        float bestExactScore = Float.MAX_VALUE;
        int bestRotatedIdx = -1;
        float bestRotatedScore = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float r = rates[i];
            float score = (reqFps > 1f) ? Math.abs(r - reqFps) : -r;
            if (widths[i] == reqW && heights[i] == reqH) {
                if (bestExactIdx < 0 || score < bestExactScore) {
                    bestExactIdx = i;
                    bestExactScore = score;
                }
            } else if (reqW != reqH && widths[i] == reqH && heights[i] == reqW) {
                if (bestRotatedIdx < 0 || score < bestRotatedScore) {
                    bestRotatedIdx = i;
                    bestRotatedScore = score;
                }
            }
        }
        return bestExactIdx >= 0 ? bestExactIdx : bestRotatedIdx;
    }

    /**
     * When applying a custom forced size not present in EDID, optionally finds an EDID mode at the
     * display's current physical dimensions (or highest native resolution) that matches {@code reqFps}.
     */
    public static int selectBestEdidModeForRefreshRate(
            int[] widths, int[] heights, float[] rates, int curW, int curH, float reqFps) {
        if (widths == null || heights == null || rates == null || reqFps <= 1f) return -1;
        int count = Math.min(widths.length, Math.min(heights.length, rates.length));
        if (count <= 0) return -1;
        int sameSizeBest = -1;
        float sameSizeDiff = Float.MAX_VALUE;
        int anyBest = -1;
        float anyDiff = Float.MAX_VALUE;
        long anyBestArea = -1L;
        for (int i = 0; i < count; i++) {
            float diff = Math.abs(rates[i] - reqFps);
            if (diff > 2.5f) continue;
            if (curW > 0 && curH > 0 && widths[i] == curW && heights[i] == curH) {
                if (sameSizeBest < 0 || diff < sameSizeDiff) {
                    sameSizeBest = i;
                    sameSizeDiff = diff;
                }
            }
            long area = (long) widths[i] * (long) heights[i];
            if (anyBest < 0 || diff + 0.1f < anyDiff || (Math.abs(diff - anyDiff) <= 0.1f && area > anyBestArea)) {
                anyBest = i;
                anyDiff = diff;
                anyBestArea = area;
            }
        }
        return sameSizeBest >= 0 ? sameSizeBest : anyBest;
    }

    /**
     * Computes the usable desktop work area (excluding top menu bar and bottom floating dock).
     */
    public static IntRect computeWorkArea(int canvasW, int canvasH, int canvasDpi) {
        int w = Math.max(360, canvasW);
        int h = Math.max(360, canvasH);
        int topInset = dpToPx(TOP_BAR_HEIGHT_DP + 4, canvasDpi);
        int bottomInset = dpToPx(DOCK_RESERVED_BOTTOM_DP, canvasDpi);
        int padX = dpToPx(6, canvasDpi);
        int bottom = Math.max(topInset + MIN_WINDOW_EDGE_PX, h - bottomInset);
        return new IntRect(padX, topInset, Math.max(padX + MIN_WINDOW_EDGE_PX, w - padX), bottom);
    }

    /**
     * Computes staggered cascading initial frame for a newly opened window according to its profile.
     * Adapts cleanly to both landscape desktop monitors and portrait phone canvases.
     */
    public static IntRect computeInitialWindowFrame(
            WindowProfile profile, int cascadeIndex, int canvasW, int canvasH, int canvasDpi) {
        IntRect work = computeWorkArea(canvasW, canvasH, canvasDpi);
        int workW = work.width();
        int workH = work.height();
        boolean portraitCanvas = workH > workW;

        int targetW;
        int targetH;
        if (portraitCanvas) {
            if (profile == WindowProfile.PORTRAIT) {
                targetW = Math.min(workW - dpToPx(12, canvasDpi), Math.round(workW * 0.90f));
                targetH = Math.min(workH - dpToPx(16, canvasDpi), Math.round(workH * 0.72f));
            } else if (profile == WindowProfile.TABLET) {
                targetW = Math.min(workW - dpToPx(12, canvasDpi), Math.round(workW * 0.94f));
                targetH = Math.min(workH - dpToPx(20, canvasDpi), Math.round(workH * 0.62f));
            } else {
                targetW = Math.min(workW - dpToPx(12, canvasDpi), Math.round(workW * 0.92f));
                targetH = Math.min(workH - dpToPx(20, canvasDpi), Math.round(workH * 0.54f));
            }
        } else if (profile == WindowProfile.PORTRAIT) {
            targetH = Math.min(workH - dpToPx(16, canvasDpi), Math.max(dpToPx(420, canvasDpi), Math.round(workH * 0.86f)));
            int contentH = Math.max(240, targetH - dpToPx(WINDOW_CHROME_HEADER_DP, canvasDpi));
            targetW = Math.min(workW - dpToPx(24, canvasDpi), Math.max(dpToPx(280, canvasDpi), Math.round(contentH * 0.486f)));
        } else if (profile == WindowProfile.TABLET) {
            targetH = Math.min(workH - dpToPx(24, canvasDpi), Math.round(workH * 0.80f));
            targetW = Math.min(workW - dpToPx(32, canvasDpi), Math.round(targetH * 1.33f));
        } else {
            targetW = Math.min(workW - dpToPx(36, canvasDpi), Math.max(dpToPx(520, canvasDpi), Math.round(workW * 0.64f)));
            targetH = Math.min(workH - dpToPx(28, canvasDpi), Math.max(dpToPx(360, canvasDpi), Math.round(workH * 0.76f)));
        }

        targetW = alignEven(targetW, MIN_WINDOW_EDGE_PX, Math.max(MIN_WINDOW_EDGE_PX, workW));
        targetH = alignEven(targetH, MIN_WINDOW_EDGE_PX, Math.max(MIN_WINDOW_EDGE_PX, workH));

        int step = Math.max(0, cascadeIndex % 6);
        int offsetStepPx = dpToPx(portraitCanvas ? 18 : 26, canvasDpi);
        int baseLeft = portraitCanvas
                ? work.left + Math.max(0, (workW - targetW) / 2)
                : work.left + Math.max(0, (workW - targetW) / 2) - dpToPx(40, canvasDpi);
        int baseTop = work.top + Math.max(0, (workH - targetH) / (portraitCanvas ? 4 : 3));

        int left = baseLeft + (portraitCanvas ? 0 : step * offsetStepPx);
        int top = baseTop + step * offsetStepPx;
        return clampWindowFrame(new IntRect(left, top, left + targetW, top + targetH), canvasW, canvasH, canvasDpi);
    }

    /**
     * Clamps a window frame so its title bar stays reachable inside the desktop canvas.
     */
    public static IntRect clampWindowFrame(IntRect raw, int canvasW, int canvasH, int canvasDpi) {
        int w = alignEven(raw.width(), MIN_WINDOW_EDGE_PX, Math.max(MIN_WINDOW_EDGE_PX, canvasW));
        int h = alignEven(raw.height(), MIN_WINDOW_EDGE_PX, Math.max(MIN_WINDOW_EDGE_PX, canvasH - dpToPx(TOP_BAR_HEIGHT_DP, canvasDpi)));
        int minTop = dpToPx(TOP_BAR_HEIGHT_DP, canvasDpi);
        int maxTop = Math.max(minTop, canvasH - dpToPx(WINDOW_CHROME_HEADER_DP + 24, canvasDpi));
        int minVisX = dpToPx(96, canvasDpi);
        int minLeft = -w + minVisX;
        int maxLeft = Math.max(0, canvasW - minVisX);

        int clampedLeft = Math.max(minLeft, Math.min(maxLeft, raw.left));
        int clampedTop = Math.max(minTop, Math.min(maxTop, raw.top));
        return new IntRect(clampedLeft, clampedTop, clampedLeft + w, clampedTop + h);
    }

    /**
     * Computes exact snap-zone bounds inside the desktop work area.
     */
    public static IntRect computeSnapFrame(String presentation, int canvasW, int canvasH, int canvasDpi) {
        if (BridgeProtocol.PRES_FULLSCREEN.equals(presentation)) {
            int w = alignEven(Math.max(360, canvasW), 360, MAX_WINDOW_EDGE_PX);
            int h = alignEven(Math.max(360, canvasH), 360, MAX_WINDOW_EDGE_PX);
            return new IntRect(0, 0, w, h);
        }
        IntRect work = computeWorkArea(canvasW, canvasH, canvasDpi);
        int gap = dpToPx(SNAP_GAP_DP, canvasDpi);
        int midX = work.left + work.width() / 2;
        int midY = work.top + work.height() / 2;

        if (BridgeProtocol.PRES_SNAP_LEFT.equals(presentation)) {
            return new IntRect(work.left, work.top, midX - gap / 2, work.bottom);
        }
        if (BridgeProtocol.PRES_SNAP_RIGHT.equals(presentation)) {
            return new IntRect(midX + gap / 2, work.top, work.right, work.bottom);
        }
        if (BridgeProtocol.PRES_SNAP_TL.equals(presentation)) {
            return new IntRect(work.left, work.top, midX - gap / 2, midY - gap / 2);
        }
        if (BridgeProtocol.PRES_SNAP_TR.equals(presentation)) {
            return new IntRect(midX + gap / 2, work.top, work.right, midY - gap / 2);
        }
        if (BridgeProtocol.PRES_SNAP_BL.equals(presentation)) {
            return new IntRect(work.left, midY + gap / 2, midX - gap / 2, work.bottom);
        }
        if (BridgeProtocol.PRES_SNAP_BR.equals(presentation)) {
            return new IntRect(midX + gap / 2, midY + gap / 2, work.right, work.bottom);
        }
        return work;
    }

    /**
     * Computes the VirtualDisplay pixel dimensions for a window's client content area (excluding header bar).
     */
    public static int[] computeContentBufferSize(IntRect windowFrame, int canvasDpi) {
        return computeContentBufferSize(windowFrame, canvasDpi, false);
    }

    /**
     * Computes the VirtualDisplay pixel dimensions for a window's client content area.
     * When {@code fullscreen} is true, no header height is subtracted so video fills 100% edge-to-edge.
     */
    public static int[] computeContentBufferSize(IntRect windowFrame, int canvasDpi, boolean fullscreen) {
        int headerPx = fullscreen ? 0 : dpToPx(WINDOW_CHROME_HEADER_DP, canvasDpi);
        int cw = alignEven(windowFrame.width(), 240, MAX_WINDOW_EDGE_PX);
        int ch = alignEven(Math.max(200, windowFrame.height() - headerPx), 200, MAX_WINDOW_EDGE_PX);
        return new int[]{cw, ch};
    }
}
