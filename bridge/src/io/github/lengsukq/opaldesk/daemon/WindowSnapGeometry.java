package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

/**
 * Pure Java 11 window snap-zone, work-area, cascade placement, and external display EDID /
 * anisotropy geometry helper delegated by {@link DisplayGeometryUtil}.
 */
public final class WindowSnapGeometry {
    public static final float ANISOTROPY_DPI_RATIO_THRESHOLD = 1.05f;
    private static final float MIRROR_COLLAPSE_ASPECT_DIFF_THRESHOLD = 0.20f;
    private static final int MIRROR_COLLAPSE_SIZE_TOLERANCE_PX = 4;

    private WindowSnapGeometry() {}

    public static int[] alignExternalForcedSize(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        boolean portrait = height > width;
        int minW = portrait ? DisplayGeometryUtil.MIN_SHORT_EDGE : DisplayGeometryUtil.MIN_LONG_EDGE;
        int maxW = portrait ? 2560 : DisplayGeometryUtil.MAX_ENCODER_LONG_EDGE;
        int minH = portrait ? DisplayGeometryUtil.MIN_LONG_EDGE : DisplayGeometryUtil.MIN_SHORT_EDGE;
        int maxH = portrait ? DisplayGeometryUtil.MAX_ENCODER_LONG_EDGE : 2560;
        int safeW = width;
        int safeH = height;
        if (safeW > maxW || safeH > maxH) {
            double scale = Math.min((double) maxW / safeW, (double) maxH / safeH);
            safeW = (int) Math.round(safeW * scale);
            safeH = (int) Math.round(safeH * scale);
        }
        return new int[]{
                DisplayGeometryUtil.alignEven(safeW, minW, maxW),
                DisplayGeometryUtil.alignEven(safeH, minH, maxH)
        };
    }

    public static boolean matchesDimensionsIgnoringRotation(int w1, int h1, int w2, int h2) {
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return false;
        return (w1 == w2 && h1 == h2) || (w1 == h2 && h1 == w2);
    }

    public static boolean isAnisotropicPixelDensity(float xDpi, float yDpi) {
        if (!Float.isFinite(xDpi) || !Float.isFinite(yDpi) || xDpi < 20f || yDpi < 20f) return false;
        float ratio = (xDpi >= yDpi) ? (xDpi / yDpi) : (yDpi / xDpi);
        return ratio > ANISOTROPY_DPI_RATIO_THRESHOLD;
    }

    public static boolean hasExternalPixelAnisotropy(
            int nativeW, int nativeH, float xDpi, float yDpi,
            int[] modeWidths, int[] modeHeights, boolean[] modeSynthetic) {
        if (isAnisotropicPixelDensity(xDpi, yDpi)) return true;
        if (nativeW < 320 || nativeH < 320 || modeWidths == null || modeHeights == null) return false;
        int count = Math.min(modeWidths.length, modeHeights.length);
        if (count <= 0) return false;
        boolean nativePresent = false;
        boolean hasInflatedSameEdge = false;
        for (int i = 0; i < count; i++) {
            int w = modeWidths[i];
            int h = modeHeights[i];
            if (matchesDimensionsIgnoringRotation(w, h, nativeW, nativeH)) nativePresent = true;
            boolean inflatedW = (h == nativeH && w > Math.round(nativeW * ANISOTROPY_DPI_RATIO_THRESHOLD));
            boolean inflatedH = (w == nativeW && h > Math.round(nativeH * ANISOTROPY_DPI_RATIO_THRESHOLD));
            if (inflatedW || inflatedH) {
                if (modeSynthetic != null && i < modeSynthetic.length && modeSynthetic[i]) return true;
                if ((w & 1) != 0 || (h & 1) != 0) return true;
                hasInflatedSameEdge = true;
            }
        }
        return !nativePresent && hasInflatedSameEdge;
    }

    public static boolean isMirroredPrimaryDisplayCollapse(
            int nativeW, int nativeH, int logicalW, int logicalH, int primaryW, int primaryH) {
        if (nativeW < 320 || nativeH < 320 || logicalW < 320 || logicalH < 320
                || primaryW < 320 || primaryH < 320) {
            return false;
        }
        boolean logicalMatchesPrimary =
                Math.abs(logicalW - primaryW) <= MIRROR_COLLAPSE_SIZE_TOLERANCE_PX
                        && Math.abs(logicalH - primaryH) <= MIRROR_COLLAPSE_SIZE_TOLERANCE_PX;
        if (!logicalMatchesPrimary) return false;
        boolean nativeMatchesPrimarySameOrientation =
                Math.abs(nativeW - primaryW) <= MIRROR_COLLAPSE_SIZE_TOLERANCE_PX
                        && Math.abs(nativeH - primaryH) <= MIRROR_COLLAPSE_SIZE_TOLERANCE_PX;
        if (nativeMatchesPrimarySameOrientation) return false;
        boolean nativeLandscape = nativeW >= nativeH;
        boolean logicalLandscape = logicalW >= logicalH;
        if (nativeLandscape != logicalLandscape) return true;
        float nativeAspect = (float) nativeW / (float) nativeH;
        float logicalAspect = (float) logicalW / (float) logicalH;
        return Math.abs(nativeAspect - logicalAspect) / Math.max(nativeAspect, logicalAspect)
                >= MIRROR_COLLAPSE_ASPECT_DIFF_THRESHOLD;
    }

    public static boolean shouldDisableScalingForNativeAnisotropy(
            int targetW, int targetH, int nativeW, int nativeH, boolean hasAnisotropy, int scalingModePref) {
        if (scalingModePref == BridgeProtocol.EXTERNAL_SCALING_FORCE_SYSTEM_AUTO) return false;
        if (!matchesDimensionsIgnoringRotation(targetW, targetH, nativeW, nativeH)) return false;
        if (scalingModePref == BridgeProtocol.EXTERNAL_SCALING_NATIVE_1TO1) return true;
        return hasAnisotropy;
    }

    public static int selectBestEdidModeIndex(
            int[] widths, int[] heights, float[] rates, boolean[] syntheticFlags,
            int reqW, int reqH, float reqFps) {
        if (widths == null || heights == null || rates == null) return -1;
        int count = Math.min(widths.length, Math.min(heights.length, rates.length));
        if (count <= 0 || reqW <= 0 || reqH <= 0) return -1;
        int bestExactIdx = -1;
        boolean bestExactSynthetic = true;
        float bestExactScore = Float.MAX_VALUE;
        int bestRotatedIdx = -1;
        boolean bestRotatedSynthetic = true;
        float bestRotatedScore = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float r = rates[i];
            float score = (reqFps > 1f) ? Math.abs(r - reqFps) : -r;
            boolean isSynth = (syntheticFlags != null && i < syntheticFlags.length && syntheticFlags[i]);
            if (widths[i] == reqW && heights[i] == reqH) {
                if (bestExactIdx < 0
                        || (bestExactSynthetic && !isSynth)
                        || (bestExactSynthetic == isSynth && score < bestExactScore)) {
                    bestExactIdx = i;
                    bestExactSynthetic = isSynth;
                    bestExactScore = score;
                }
            } else if (reqW != reqH && widths[i] == reqH && heights[i] == reqW) {
                if (bestRotatedIdx < 0
                        || (bestRotatedSynthetic && !isSynth)
                        || (bestRotatedSynthetic == isSynth && score < bestRotatedScore)) {
                    bestRotatedIdx = i;
                    bestRotatedSynthetic = isSynth;
                    bestRotatedScore = score;
                }
            }
        }
        return bestExactIdx >= 0 ? bestExactIdx : bestRotatedIdx;
    }

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

    public static IntRect computeWorkArea(int canvasW, int canvasH, int canvasDpi) {
        return computeWorkArea(canvasW, canvasH, canvasDpi, DisplayGeometryUtil.DOCK_RESERVED_BOTTOM_DP);
    }

    public static IntRect computeWorkArea(int canvasW, int canvasH, int canvasDpi, int dockInsetDp) {
        int w = Math.max(360, canvasW);
        int h = Math.max(360, canvasH);
        int topInset = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.TOP_BAR_HEIGHT_DP + 4, canvasDpi);
        int effectiveBottomDp = (dockInsetDp <= 0) ? 4 : dockInsetDp;
        int bottomInset = DisplayGeometryUtil.dpToPx(effectiveBottomDp, canvasDpi);
        int padX = DisplayGeometryUtil.dpToPx(6, canvasDpi);
        int bottom = Math.max(topInset + DisplayGeometryUtil.MIN_WINDOW_EDGE_PX, h - bottomInset);
        return new IntRect(padX, topInset, Math.max(padX + DisplayGeometryUtil.MIN_WINDOW_EDGE_PX, w - padX), bottom);
    }

    public static IntRect computeInitialWindowFrame(
            WindowProfile profile, int cascadeIndex, int canvasW, int canvasH, int canvasDpi) {
        return computeInitialWindowFrame(
                profile, cascadeIndex, canvasW, canvasH, canvasDpi, DisplayGeometryUtil.DOCK_RESERVED_BOTTOM_DP);
    }

    public static IntRect computeInitialWindowFrame(
            WindowProfile profile, int cascadeIndex, int canvasW, int canvasH, int canvasDpi, int dockInsetDp) {
        IntRect work = computeWorkArea(canvasW, canvasH, canvasDpi, dockInsetDp);
        int workW = work.width();
        int workH = work.height();
        boolean portraitCanvas = workH > workW;

        int targetW, targetH;
        if (portraitCanvas) {
            if (profile == WindowProfile.PORTRAIT) {
                targetW = Math.min(workW - DisplayGeometryUtil.dpToPx(12, canvasDpi), Math.round(workW * 0.90f));
                targetH = Math.min(workH - DisplayGeometryUtil.dpToPx(16, canvasDpi), Math.round(workH * 0.72f));
            } else if (profile == WindowProfile.TABLET) {
                targetW = Math.min(workW - DisplayGeometryUtil.dpToPx(12, canvasDpi), Math.round(workW * 0.94f));
                targetH = Math.min(workH - DisplayGeometryUtil.dpToPx(20, canvasDpi), Math.round(workH * 0.62f));
            } else {
                targetW = Math.min(workW - DisplayGeometryUtil.dpToPx(12, canvasDpi), Math.round(workW * 0.92f));
                targetH = Math.min(workH - DisplayGeometryUtil.dpToPx(20, canvasDpi), Math.round(workH * 0.54f));
            }
        } else if (profile == WindowProfile.PORTRAIT) {
            targetH = Math.min(workH - DisplayGeometryUtil.dpToPx(16, canvasDpi),
                    Math.max(DisplayGeometryUtil.dpToPx(420, canvasDpi), Math.round(workH * 0.86f)));
            int contentH = Math.max(240, targetH - DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.WINDOW_CHROME_HEADER_DP, canvasDpi));
            targetW = Math.min(workW - DisplayGeometryUtil.dpToPx(24, canvasDpi),
                    Math.max(DisplayGeometryUtil.dpToPx(280, canvasDpi), Math.round(contentH * 0.486f)));
        } else if (profile == WindowProfile.TABLET) {
            targetH = Math.min(workH - DisplayGeometryUtil.dpToPx(24, canvasDpi), Math.round(workH * 0.80f));
            targetW = Math.min(workW - DisplayGeometryUtil.dpToPx(32, canvasDpi), Math.round(targetH * 1.33f));
        } else {
            targetW = Math.min(workW - DisplayGeometryUtil.dpToPx(36, canvasDpi),
                    Math.max(DisplayGeometryUtil.dpToPx(520, canvasDpi), Math.round(workW * 0.64f)));
            targetH = Math.min(workH - DisplayGeometryUtil.dpToPx(28, canvasDpi),
                    Math.max(DisplayGeometryUtil.dpToPx(360, canvasDpi), Math.round(workH * 0.76f)));
        }

        targetW = DisplayGeometryUtil.alignEven(targetW, DisplayGeometryUtil.MIN_WINDOW_EDGE_PX,
                Math.max(DisplayGeometryUtil.MIN_WINDOW_EDGE_PX, workW));
        targetH = DisplayGeometryUtil.alignEven(targetH, DisplayGeometryUtil.MIN_WINDOW_EDGE_PX,
                Math.max(DisplayGeometryUtil.MIN_WINDOW_EDGE_PX, workH));

        int step = Math.max(0, cascadeIndex % 6);
        int offsetStepPx = DisplayGeometryUtil.dpToPx(portraitCanvas ? 18 : 26, canvasDpi);
        int baseLeft = portraitCanvas
                ? work.left + Math.max(0, (workW - targetW) / 2)
                : work.left + Math.max(0, (workW - targetW) / 2) - DisplayGeometryUtil.dpToPx(40, canvasDpi);
        int baseTop = work.top + Math.max(0, (workH - targetH) / (portraitCanvas ? 4 : 3));

        int left = baseLeft + (portraitCanvas ? 0 : step * offsetStepPx);
        int top = baseTop + step * offsetStepPx;
        return clampWindowFrame(new IntRect(left, top, left + targetW, top + targetH), canvasW, canvasH, canvasDpi);
    }

    public static IntRect clampWindowFrame(IntRect raw, int canvasW, int canvasH, int canvasDpi) {
        int w = DisplayGeometryUtil.alignEven(raw.width(), DisplayGeometryUtil.MIN_WINDOW_EDGE_PX,
                Math.max(DisplayGeometryUtil.MIN_WINDOW_EDGE_PX, canvasW));
        int h = DisplayGeometryUtil.alignEven(raw.height(), DisplayGeometryUtil.MIN_WINDOW_EDGE_PX,
                Math.max(DisplayGeometryUtil.MIN_WINDOW_EDGE_PX,
                        canvasH - DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.TOP_BAR_HEIGHT_DP, canvasDpi)));
        int minTop = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.TOP_BAR_HEIGHT_DP, canvasDpi);
        int maxTop = Math.max(minTop, canvasH - DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.WINDOW_CHROME_HEADER_DP + 24, canvasDpi));
        int minVisX = DisplayGeometryUtil.dpToPx(96, canvasDpi);
        int minLeft = -w + minVisX;
        int maxLeft = Math.max(0, canvasW - minVisX);

        int clampedLeft = Math.max(minLeft, Math.min(maxLeft, raw.left));
        int clampedTop = Math.max(minTop, Math.min(maxTop, raw.top));
        return new IntRect(clampedLeft, clampedTop, clampedLeft + w, clampedTop + h);
    }

    public static IntRect computeSnapFrame(String presentation, int canvasW, int canvasH, int canvasDpi) {
        return computeSnapFrame(
                presentation, canvasW, canvasH, canvasDpi, DisplayGeometryUtil.DOCK_RESERVED_BOTTOM_DP);
    }

    public static IntRect computeSnapFrame(String presentation, int canvasW, int canvasH, int canvasDpi, int dockInsetDp) {
        if (BridgeProtocol.PRES_FULLSCREEN.equals(presentation)) {
            int w = DisplayGeometryUtil.alignEven(Math.max(360, canvasW), 360, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int h = DisplayGeometryUtil.alignEven(Math.max(360, canvasH), 360, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            return new IntRect(0, 0, w, h);
        }
        IntRect work = computeWorkArea(canvasW, canvasH, canvasDpi, dockInsetDp);
        int gap = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.SNAP_GAP_DP, canvasDpi);
        int midX = work.left + work.width() / 2;
        int midY = work.top + work.height() / 2;

        if (BridgeProtocol.PRES_SNAP_LEFT.equals(presentation)) return new IntRect(work.left, work.top, midX - gap / 2, work.bottom);
        if (BridgeProtocol.PRES_SNAP_RIGHT.equals(presentation)) return new IntRect(midX + gap / 2, work.top, work.right, work.bottom);
        if (BridgeProtocol.PRES_SNAP_TL.equals(presentation)) return new IntRect(work.left, work.top, midX - gap / 2, midY - gap / 2);
        if (BridgeProtocol.PRES_SNAP_TR.equals(presentation)) return new IntRect(midX + gap / 2, work.top, work.right, midY - gap / 2);
        if (BridgeProtocol.PRES_SNAP_BL.equals(presentation)) return new IntRect(work.left, midY + gap / 2, midX - gap / 2, work.bottom);
        if (BridgeProtocol.PRES_SNAP_BR.equals(presentation)) return new IntRect(midX + gap / 2, midY + gap / 2, work.right, work.bottom);
        return work;
    }

    public static int[] computeContentBufferSize(IntRect windowFrame, int canvasDpi, boolean fullscreen) {
        int headerPx = fullscreen ? 0 : DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.WINDOW_CHROME_HEADER_DP, canvasDpi);
        int cw = DisplayGeometryUtil.alignEven(windowFrame.width(), 240, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
        int ch = DisplayGeometryUtil.alignEven(Math.max(200, windowFrame.height() - headerPx), 200, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
        return new int[]{cw, ch};
    }
}
