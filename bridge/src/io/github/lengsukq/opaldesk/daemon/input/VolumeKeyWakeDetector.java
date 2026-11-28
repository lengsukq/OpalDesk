package io.github.lengsukq.opaldesk.daemon.input;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure JVM-testable Linux {@code input_event} physical volume-key wake detector used while
 * Display 0 is in soft screen-off ({@code SurfaceControl.setDisplayPowerMode(POWER_MODE_OFF)}).
 *
 * <p>Avoids hardware power-button conflicts by waking Display 0 on either:
 * <ul>
 *   <li>Double-press {@code KEY_VOLUMEUP} (115 / 0x0073) within {@link #MAX_DOUBLE_PRESS_INTERVAL_MS} (450ms).</li>
 *   <li>Simultaneous press of {@code KEY_VOLUMEUP} (115) and {@code KEY_VOLUMEDOWN} (114 / 0x0072).</li>
 * </ul>
 *
 * <p>Thread-safe so a single detector instance can be shared across separate evdev nodes
 * (e.g. when Volume+ and Volume- live on different PMIC/GPIO input devices).
 */
public final class VolumeKeyWakeDetector {
    public static final int INPUT_EVENT_SIZE_64 = DoubleTapWakeDetector.INPUT_EVENT_SIZE_64;

    public static final int EV_KEY = DoubleTapWakeDetector.EV_KEY;
    public static final int KEY_VOLUMEDOWN = 114; // 0x0072
    public static final int KEY_VOLUMEUP = 115;   // 0x0073

    public static final long GRACE_AFTER_ARM_MS = 350L;
    public static final long MAX_DOUBLE_PRESS_INTERVAL_MS = 450L;

    private boolean hasArmTimestamp = false;
    private long armedAtMs = 0L;
    private boolean volumeUpPressed = false;
    private boolean volumeDownPressed = false;
    private long lastVolumeUpDownMs = 0L;

    public synchronized void arm(long nowMs) {
        hasArmTimestamp = true;
        armedAtMs = nowMs;
        resetState();
    }

    /**
     * Parses a 64-bit Linux {@code struct input_event} (24 bytes: sec(8), usec(8), type(2), code(2), value(4))
     * and feeds it into {@link #onEvent(int, int, int, long)}.
     */
    public boolean onRawEvent64(byte[] buf, int offset, long nowMs) {
        if (buf == null || offset < 0 || offset + INPUT_EVENT_SIZE_64 > buf.length) return false;
        ByteBuffer bb = ByteBuffer.wrap(buf, offset, INPUT_EVENT_SIZE_64).order(ByteOrder.LITTLE_ENDIAN);
        int type = bb.getShort(16) & 0xFFFF;
        int code = bb.getShort(18) & 0xFFFF;
        int value = bb.getInt(20);
        return onEvent(type, code, value, nowMs);
    }

    /**
     * Processes one Linux input event and returns {@code true} when Display 0 should immediately wake.
     */
    public synchronized boolean onEvent(int type, int code, int value, long nowMs) {
        if (type != EV_KEY) {
            return false;
        }
        if (hasArmTimestamp && (nowMs - armedAtMs) < GRACE_AFTER_ARM_MS) {
            return false;
        }
        if (code == KEY_VOLUMEUP) {
            return handleVolumeUp(value, nowMs);
        }
        if (code == KEY_VOLUMEDOWN) {
            return handleVolumeDown(value);
        }
        return false;
    }

    private boolean handleVolumeUp(int value, long nowMs) {
        if (value == 1) {
            if (volumeUpPressed) {
                return false;
            }
            volumeUpPressed = true;
            if (volumeDownPressed) {
                resetState();
                return true;
            }
            if (lastVolumeUpDownMs > 0L) {
                long delta = nowMs - lastVolumeUpDownMs;
                if (delta >= 0L && delta <= MAX_DOUBLE_PRESS_INTERVAL_MS) {
                    resetState();
                    return true;
                }
            }
            lastVolumeUpDownMs = nowMs;
            return false;
        }
        if (value == 0) {
            volumeUpPressed = false;
        }
        return false;
    }

    private boolean handleVolumeDown(int value) {
        if (value == 1) {
            volumeDownPressed = true;
            if (volumeUpPressed) {
                resetState();
                return true;
            }
            return false;
        }
        if (value == 0) {
            volumeDownPressed = false;
        }
        return false;
    }

    private void resetState() {
        volumeUpPressed = false;
        volumeDownPressed = false;
        lastVolumeUpDownMs = 0L;
    }
}
