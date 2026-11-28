package io.github.lengsukq.opaldesk.daemon.platform;

import android.os.IBinder;

import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Captures the primary display brightness prior to blackout and performs two-layer
 * backlight restoration upon wake (SurfaceControl.setDisplayBrightness reflection +
 * DisplayPowerController settings nudge / cmd display set-brightness fallback).
 */
final class DisplayBrightnessRestorer {
    static final float DEFAULT_BRIGHTNESS = 0.5f;
    static final float MIN_BRIGHTNESS = 0.05f;
    static final float MAX_BRIGHTNESS = 1.0f;
    static final int INVALID_RAW_BRIGHTNESS = -1;
    static final long SETTINGS_CMD_TIMEOUT_MS = 800L;

    interface ShellExecutor {
        ShellCommand.Result run(String[] cmd, long timeoutMs);
    }

    static final class BrightnessSnapshot {
        private static final BrightnessSnapshot DEFAULT =
                new BrightnessSnapshot(INVALID_RAW_BRIGHTNESS, DEFAULT_BRIGHTNESS);

        final int rawSetting;
        final float normalized;

        BrightnessSnapshot(int rawSetting, float normalized) {
            this.rawSetting = rawSetting;
            this.normalized = clampBrightness(normalized);
        }

        static BrightnessSnapshot defaultSnapshot() {
            return DEFAULT;
        }

        boolean hasRawSetting() {
            return rawSetting > 0;
        }
    }

    private static final ShellExecutor DEFAULT_SHELL = ShellCommand::runWithTimeout;

    private DisplayBrightnessRestorer() {}

    static float clampBrightness(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return DEFAULT_BRIGHTNESS;
        }
        if (value < MIN_BRIGHTNESS) return MIN_BRIGHTNESS;
        if (value > MAX_BRIGHTNESS) return MAX_BRIGHTNESS;
        return value;
    }

    static BrightnessSnapshot captureCurrentBrightness() {
        return captureCurrentBrightness(DEFAULT_SHELL);
    }

    static BrightnessSnapshot captureCurrentBrightness(ShellExecutor executor) {
        if (executor == null) {
            return BrightnessSnapshot.defaultSnapshot();
        }
        try {
            ShellCommand.Result res = executor.run(
                    new String[]{"settings", "get", "system", "screen_brightness"},
                    SETTINGS_CMD_TIMEOUT_MS);
            if (res == null || !res.isSuccess()) {
                return BrightnessSnapshot.defaultSnapshot();
            }
            return parseBrightnessSetting(res.output);
        } catch (RuntimeException ignored) {
            return BrightnessSnapshot.defaultSnapshot();
        }
    }

    static BrightnessSnapshot parseBrightnessSetting(String rawOutput) {
        if (rawOutput == null) {
            return BrightnessSnapshot.defaultSnapshot();
        }
        String trimmed = rawOutput.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return BrightnessSnapshot.defaultSnapshot();
        }
        int nl = trimmed.indexOf('\n');
        if (nl >= 0) {
            trimmed = trimmed.substring(0, nl).trim();
        }
        if (trimmed.indexOf('.') >= 0) {
            try {
                float f = Float.parseFloat(trimmed);
                if (f > 0f && f <= 1.0f) {
                    return new BrightnessSnapshot(INVALID_RAW_BRIGHTNESS, clampBrightness(f));
                }
                if (f > 1.0f) {
                    return parseIntegerBrightness(Math.round(f));
                }
            } catch (NumberFormatException ignored) {
            }
            return BrightnessSnapshot.defaultSnapshot();
        }
        try {
            int raw = Integer.parseInt(trimmed);
            return parseIntegerBrightness(raw);
        } catch (NumberFormatException ignored) {
            return BrightnessSnapshot.defaultSnapshot();
        }
    }

    private static BrightnessSnapshot parseIntegerBrightness(int raw) {
        if (raw < 0) {
            return BrightnessSnapshot.defaultSnapshot();
        }
        if (raw == 0) {
            return new BrightnessSnapshot(INVALID_RAW_BRIGHTNESS, MIN_BRIGHTNESS);
        }
        float normalized;
        if (raw <= 255) {
            normalized = raw / 255.0f;
        } else if (raw <= 4095) {
            normalized = raw / 4095.0f;
        } else if (raw <= 16383) {
            normalized = raw / 16383.0f;
        } else {
            normalized = MAX_BRIGHTNESS;
        }
        return new BrightnessSnapshot(raw, clampBrightness(normalized));
    }

    static int computeNudgedBrightness(int rawSetting) {
        return (rawSetting > 1) ? (rawSetting - 1) : (rawSetting + 1);
    }

    static boolean restoreSurfaceControlBrightness(IBinder token, float brightness) {
        if (token == null) return false;
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            return invokeSurfaceControlSetDisplayBrightness(sc, IBinder.class, token, brightness);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SC_BRIGHTNESS_WARN: " + AndroidServices.unwrapMessage(e));
            return false;
        }
    }

    static boolean invokeSurfaceControlSetDisplayBrightness(
            Class<?> surfaceControlClass, Class<?> tokenParamType, Object displayToken, float brightness) {
        if (surfaceControlClass == null || tokenParamType == null || displayToken == null) return false;
        float clamped = clampBrightness(brightness);
        try {
            Method m5 = AndroidServices.findMethodOrNull(
                    surfaceControlClass, "setDisplayBrightness",
                    tokenParamType, float.class, float.class, float.class, float.class);
            if (m5 != null) {
                m5.invoke(null, displayToken, clamped, -1.0f, clamped, -1.0f);
                return true;
            }
            Method m2 = AndroidServices.findMethodOrNull(
                    surfaceControlClass, "setDisplayBrightness",
                    tokenParamType, float.class);
            if (m2 != null) {
                m2.invoke(null, displayToken, clamped);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SC_BRIGHTNESS_WARN: " + AndroidServices.unwrapMessage(e));
        }
        return false;
    }

    static boolean refreshDisplayPowerControllerBrightness(BrightnessSnapshot snapshot) {
        return refreshDisplayPowerControllerBrightness(snapshot, DEFAULT_SHELL);
    }

    static boolean refreshDisplayPowerControllerBrightness(
            BrightnessSnapshot snapshot, ShellExecutor executor) {
        if (executor == null) return false;
        BrightnessSnapshot effective = (snapshot != null) ? snapshot : BrightnessSnapshot.defaultSnapshot();
        if (effective.hasRawSetting()) {
            int nudged = computeNudgedBrightness(effective.rawSetting);
            ShellCommand.Result r1 = executor.run(
                    new String[]{"settings", "put", "system", "screen_brightness", Integer.toString(nudged)},
                    SETTINGS_CMD_TIMEOUT_MS);
            ShellCommand.Result r2 = executor.run(
                    new String[]{"settings", "put", "system", "screen_brightness", Integer.toString(effective.rawSetting)},
                    SETTINGS_CMD_TIMEOUT_MS);
            if (isShellOk(r1) && isShellOk(r2)) {
                return true;
            }
        }
        String formatted = String.format(Locale.US, "%.3f", clampBrightness(effective.normalized));
        ShellCommand.Result res = executor.run(
                new String[]{"/system/bin/cmd", "display", "set-brightness", formatted},
                SETTINGS_CMD_TIMEOUT_MS);
        return isShellOk(res);
    }

    private static boolean isShellOk(ShellCommand.Result res) {
        if (res == null || !res.isSuccess()) return false;
        String out = (res.output != null) ? res.output.toLowerCase(Locale.US) : "";
        return !out.contains("error") && !out.contains("exception") && !out.contains("unknown command");
    }
}
