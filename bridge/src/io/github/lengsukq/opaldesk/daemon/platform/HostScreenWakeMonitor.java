package io.github.lengsukq.opaldesk.daemon.platform;

import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.input.DoubleTapWakeDetector;
import io.github.lengsukq.opaldesk.daemon.input.VolumeKeyWakeDetector;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-overhead rootless ({@code uid=2000}, group {@code input}) Display 0 wake monitor.
 *
 * <p>Activated strictly while Display 0 is physically powered off via
 * {@link AndroidServices#setPrimaryPhysicalDisplayPower(boolean)}, blocking silently on kernel
 * {@code /dev/input/event*} reads until the user double-taps the screen, triggers the touch IC's
 * native {@code KEY_WAKEUP} gesture, double-presses Volume+, or presses Volume+ and Volume-
 * simultaneously, then immediately wakes Display 0 and closes all file handles.
 */
public final class HostScreenWakeMonitor {
    static final class DeviceSpec {
        final String path;
        final String name;
        final boolean isTouch;
        final boolean isVolumeKey;

        DeviceSpec(String path, String name, boolean isTouch, boolean isVolumeKey) {
            this.path = path;
            this.name = name;
            this.isTouch = isTouch;
            this.isVolumeKey = isVolumeKey;
        }
    }

    private final Object lock = new Object();
    private final List<FileInputStream> openStreams = new ArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile int generation = 0;

    public void start(Runnable onWake) {
        stop();
        if (onWake == null) return;
        final int gen;
        synchronized (lock) {
            active.set(true);
            gen = ++generation;
        }
        Thread launcher = new Thread(() -> startMonitorsAsync(gen, onWake), "OpalWakeDiscovery");
        launcher.setDaemon(true);
        launcher.start();
    }

    public void stop() {
        List<FileInputStream> toClose;
        synchronized (lock) {
            active.set(false);
            generation++;
            toClose = new ArrayList<>(openStreams);
            openStreams.clear();
        }
        for (FileInputStream fis : toClose) {
            try {
                fis.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void startMonitorsAsync(int gen, Runnable onWake) {
        List<DeviceSpec> devices = discoverWakeDevices();
        if (devices.isEmpty()) {
            System.out.println("OPALDESK_WAKE_MONITOR_SKIP no readable internal touch/volume evdev found (using helper wake fallback)");
            return;
        }
        final AtomicBoolean woken = new AtomicBoolean(false);
        final VolumeKeyWakeDetector sharedVolumeDetector = new VolumeKeyWakeDetector();
        sharedVolumeDetector.arm(SystemClock.uptimeMillis());
        for (DeviceSpec spec : devices) {
            if (!active.get() || generation != gen) return;
            Thread t = new Thread(
                    () -> readDeviceLoop(gen, spec, sharedVolumeDetector, woken, onWake),
                    "OpalWake-" + spec.path);
            t.setDaemon(true);
            t.start();
        }
    }

    private void readDeviceLoop(
            int gen,
            DeviceSpec spec,
            VolumeKeyWakeDetector sharedVolumeDetector,
            AtomicBoolean woken,
            Runnable onWake) {
        DoubleTapWakeDetector touchDetector = spec.isTouch ? new DoubleTapWakeDetector() : null;
        if (touchDetector != null) {
            touchDetector.arm(SystemClock.uptimeMillis());
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(spec.path);
            synchronized (lock) {
                if (!active.get() || generation != gen) {
                    try { fis.close(); } catch (IOException ignored) {}
                    return;
                }
                openStreams.add(fis);
            }
            System.out.println("OPALDESK_WAKE_MONITOR_ARMED dev=" + spec.path + " name=" + spec.name
                    + " touch=" + spec.isTouch + " vol=" + spec.isVolumeKey);
            byte[] buf = new byte[DoubleTapWakeDetector.INPUT_EVENT_SIZE_64 * 8];
            while (active.get() && generation == gen && !woken.get()) {
                int n = fis.read(buf);
                if (n < DoubleTapWakeDetector.INPUT_EVENT_SIZE_64) {
                    if (n < 0) break;
                    continue;
                }
                long nowMs = SystemClock.uptimeMillis();
                int count = n / DoubleTapWakeDetector.INPUT_EVENT_SIZE_64;
                for (int i = 0; i < count; i++) {
                    int offset = i * DoubleTapWakeDetector.INPUT_EVENT_SIZE_64;
                    boolean touchWoke = touchDetector != null
                            && touchDetector.onRawEvent64(buf, offset, nowMs, true);
                    boolean volumeWoke = !touchWoke
                            && spec.isVolumeKey
                            && sharedVolumeDetector.onRawEvent64(buf, offset, nowMs);
                    if (touchWoke || volumeWoke) {
                        if (woken.compareAndSet(false, true)) {
                            String reason = volumeWoke
                                    ? "OPALDESK_VOLUME_KEY_WAKE_TRIGGERED"
                                    : "OPALDESK_DOUBLE_TAP_WAKE_TRIGGERED";
                            System.out.println(reason + " dev=" + spec.path);
                            stop();
                            try {
                                onWake.run();
                            } catch (RuntimeException ignored) {
                            }
                        }
                        return;
                    }
                }
            }
        } catch (IOException e) {
            if (active.get() && generation == gen) {
                System.err.println("OPALDESK_WAKE_MONITOR_IO_WARN dev=" + spec.path + ": " + e.getMessage());
            }
        } finally {
            if (fis != null) {
                synchronized (lock) {
                    openStreams.remove(fis);
                }
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Discovers internal touchscreen and physical volume-key evdev nodes via {@code getevent -p}
     * without vendor hardcodes.
     */
    private static List<DeviceSpec> discoverWakeDevices() {
        String dump = ShellCommand.runOutput("getevent", "-p");
        return parseWakeDevicesFromDump(dump);
    }

    static List<DeviceSpec> parseWakeDevicesFromDump(String dump) {
        List<DeviceSpec> out = new ArrayList<>();
        if (dump == null || dump.isEmpty()) return out;

        String curPath = null;
        String curName = "";
        boolean hasTouchCaps = false;
        boolean hasWakeKey = false;
        boolean hasVolumeKey = false;
        boolean inKeySection = false;

        String[] lines = dump.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("add device ")) {
                maybeAddDevice(out, curPath, curName, hasTouchCaps, hasWakeKey, hasVolumeKey);
                int colon = line.indexOf(':');
                curPath = (colon > 0) ? line.substring(colon + 1).trim() : null;
                curName = "";
                hasTouchCaps = false;
                hasWakeKey = false;
                hasVolumeKey = false;
                inKeySection = false;
            } else if (line.startsWith("name:")) {
                curName = line.substring(5).trim().replace("\"", "");
                inKeySection = false;
            } else if (line.startsWith("KEY (0001):")) {
                inKeySection = true;
                String keyLine = line.substring("KEY (0001):".length());
                if (containsHexCodeToken(keyLine, "014a")) hasTouchCaps = true;
                if (containsHexCodeToken(keyLine, "008f")) hasWakeKey = true;
                if (containsHexCodeToken(keyLine, "0073") || containsHexCodeToken(keyLine, "0072")) {
                    hasVolumeKey = true;
                }
            } else if (line.endsWith(":") || line.contains(" (00")) {
                inKeySection = false;
            } else if (inKeySection) {
                if (containsHexCodeToken(line, "014a")) hasTouchCaps = true;
                if (containsHexCodeToken(line, "008f")) hasWakeKey = true;
                if (containsHexCodeToken(line, "0073") || containsHexCodeToken(line, "0072")) {
                    hasVolumeKey = true;
                }
            } else {
                if (line.contains("0035") && line.contains("min") && line.contains("max")) {
                    // ABS_MT_POSITION_X (0x0035) capability line on multi-touch screens
                    hasTouchCaps = true;
                } else if (containsHexCodeToken(line, "014a")) {
                    hasTouchCaps = true;
                } else if (containsHexCodeToken(line, "008f")) {
                    hasWakeKey = true;
                }
            }
        }
        maybeAddDevice(out, curPath, curName, hasTouchCaps, hasWakeKey, hasVolumeKey);
        return out;
    }

    private static boolean containsHexCodeToken(String line, String codeHex) {
        if (line == null || line.isEmpty()) return false;
        String[] tokens = line.trim().split("\\s+");
        for (String token : tokens) {
            if (codeHex.equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    private static void maybeAddDevice(
            List<DeviceSpec> out, String path, String name, boolean hasTouchCaps, boolean hasWakeKey, boolean hasVolumeKey) {
        if (path == null || !path.startsWith("/dev/input/event")) return;
        String lower = (name != null) ? name.toLowerCase(Locale.US) : "";
        if (lower.contains("opaldesk") || lower.contains("virtual") || lower.contains("uinput")) return;
        boolean isTouch = hasTouchCaps || (hasWakeKey && lower.contains("touch"));
        if (isTouch || hasVolumeKey) {
            out.add(new DeviceSpec(path, name != null ? name : "", isTouch, hasVolumeKey));
        }
    }
}
