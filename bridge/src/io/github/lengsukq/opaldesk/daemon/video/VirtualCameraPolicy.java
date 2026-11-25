package io.github.lengsukq.opaldesk.daemon.video;

import java.util.Locale;

/**
 * Pure JVM helper logic for Android 15+ AOSP {@code VirtualCamera} host orchestration:
 * SDK gating, {@code cmd companiondevice list} association parsing, standard phone sensor
 * orientation conventions ({@code BACK=90°}, {@code FRONT=270°}), and aspect-preserving
 * rotated center-crop rectangle computation. Zero {@code android.*} references so it is
 * directly testable under {@code :bridge:test}.
 */
public final class VirtualCameraPolicy {
    public static final int MIN_SUPPORTED_API = 35;

    /**
     * Minimum advertised maxFps for AOSP {@code VirtualCameraConfig.Builder.addStreamConfig}.
     * Android's Camera1 legacy HAL3 shim ({@code Camera2-Parameters}) filters out every preview
     * resolution slower than 30fps, causing {@code generated preview size list is empty!!} and
     * {@code connectHelper: No such device (-19)} in Camera1 apps (e.g. WeChat Scan QR).
     */
    public static final int MIN_STREAM_FPS = 30;
    public static final int MAX_STREAM_FPS = 60;

    /** Standard Android phone back-camera sensor orientation (CW degrees to reach portrait upright). */
    public static final int BACK_SENSOR_ORIENTATION = 90;
    /** Standard Android phone front-camera sensor orientation (CW degrees to reach portrait upright). */
    public static final int FRONT_SENSOR_ORIENTATION = 270;

    private VirtualCameraPolicy() {}

    public static boolean isSupportedSdkInt(int sdkInt) {
        return sdkInt >= MIN_SUPPORTED_API;
    }

    public static int resolveVirtualCameraStreamFps(int requestedFps) {
        int candidate = requestedFps > 0 ? requestedFps : MIN_STREAM_FPS;
        return Math.max(MIN_STREAM_FPS, Math.min(MAX_STREAM_FPS, candidate));
    }

    public static String normalizeFacing(String facing) {
        if (facing == null) return "";
        String lower = facing.trim().toLowerCase(Locale.US);
        if ("back".equals(lower) || "rear".equals(lower) || "environment".equals(lower)) return "back";
        if ("front".equals(lower) || "user".equals(lower)) return "front";
        return "";
    }

    /**
     * Computes the CW pre-rotation degrees to apply when drawing an upright (0°) remote bitmap
     * onto a {@code VirtualCamera} output {@code Surface} whose declared
     * {@code SENSOR_ORIENTATION} is {@code sensorOrientation}.
     *
     * <p>When an Android app (Camera1/Camera2/CameraX/WeChat CameraKit) reads this camera on a
     * {@code ROTATION_0} virtual display, it rotates the buffer CW by {@code sensorOrientation};
     * pre-rotating by {@code (360 - sensorOrientation) % 360} cancels that rotation so the app
     * always displays and captures a 100% upright frame (and front selfie mirror flips left↔right
     * instead of upside-down).
     */
    public static int computePreRotationCwDegrees(int sensorOrientation) {
        int norm = ((sensorOrientation % 360) + 360) % 360;
        return (360 - norm) % 360;
    }

    /**
     * Parses the numeric Association ID for {@code pkg} and {@code mac} from
     * {@code cmd companiondevice list 0} output.
     */
    public static int parseAssociationId(String listOutput, String pkg, String mac) {
        if (listOutput == null || listOutput.isEmpty() || pkg == null || pkg.isEmpty()) return -1;
        String targetMac = (mac != null) ? mac.toLowerCase(Locale.US) : "";
        int fallbackId = -1;
        for (String rawLine : listOutput.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || !line.contains(pkg)) continue;
            int id = extractLeadingInt(line);
            if (id <= 0) {
                int idx = line.indexOf("id=");
                if (idx >= 0) {
                    id = extractLeadingInt(line.substring(idx + 3));
                }
            }
            if (id <= 0) continue;
            if (!targetMac.isEmpty() && line.toLowerCase(Locale.US).contains(targetMac)) {
                return id;
            }
            if (fallbackId <= 0) {
                fallbackId = id;
            }
        }
        return fallbackId;
    }

    private static int extractLeadingInt(String s) {
        if (s == null) return -1;
        int i = 0;
        int n = s.length();
        while (i < n && !Character.isDigit(s.charAt(i))) {
            if (s.charAt(i) != ' ' && s.charAt(i) != '\t') return -1;
            i++;
        }
        int start = i;
        while (i < n && Character.isDigit(s.charAt(i))) i++;
        if (i <= start) return -1;
        try {
            return Integer.parseInt(s.substring(start, i));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * Computes a center-crop source rectangle {@code [left, top, right, bottom]} inside
     * {@code srcW x srcH} matching the aspect ratio of {@code dstW x dstH} with zero stretching.
     */
    public static int[] computeCenterCropSrcRect(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return new int[]{0, 0, Math.max(1, srcW), Math.max(1, srcH)};
        }
        double srcRatio = (double) srcW / (double) srcH;
        double dstRatio = (double) dstW / (double) dstH;
        if (Math.abs(srcRatio - dstRatio) < 0.01) {
            return new int[]{0, 0, srcW, srcH};
        }
        if (srcRatio > dstRatio) {
            int cropW = Math.max(2, (int) Math.round(srcH * dstRatio));
            int left = Math.max(0, (srcW - cropW) / 2);
            return new int[]{left, 0, Math.min(srcW, left + cropW), srcH};
        } else {
            int cropH = Math.max(2, (int) Math.round(srcW / dstRatio));
            int top = Math.max(0, (srcH - cropH) / 2);
            return new int[]{0, top, srcW, Math.min(srcH, top + cropH)};
        }
    }

    /**
     * Computes a center-crop source rectangle inside an upright {@code srcW x srcH} bitmap before
     * pre-rotating by {@code preRotCwDeg} onto a {@code dstW x dstH} {@code VirtualCamera} surface.
     * For 90°/270° pre-rotations, the effective upright target aspect ratio is {@code dstH x dstW}.
     */
    public static int[] computeRotatedCenterCropSrcRect(
            int srcW, int srcH, int dstW, int dstH, int preRotCwDeg) {
        int norm = ((preRotCwDeg % 360) + 360) % 360;
        if (norm == 90 || norm == 270) {
            return computeCenterCropSrcRect(srcW, srcH, dstH, dstW);
        }
        return computeCenterCropSrcRect(srcW, srcH, dstW, dstH);
    }
}
