package io.github.lengsukq.opaldesk.daemon.platform;

import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.input.DoubleTapWakeDetector;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-overhead rootless ({@code uid=2000}, group {@code input}) Display 0 touchscreen wake monitor.
 *
 * <p>Activated strictly while Display 0 is physically powered off via
 * {@link AndroidServices#setPrimaryPhysicalDisplayPower(boolean)}, blocking silently on kernel
 * {@code /dev/input/event*} reads until the user double-taps the screen (or triggers the touch IC's
 * native {@code KEY_WAKEUP} gesture), then immediately wakes Display 0 and closes all file handles.
 */
public final class HostScreenWakeMonitor {
    private static final class DeviceSpec {
        final String path;
        final String name;
        final boolean isTouch;

        DeviceSpec(String path, String name, boolean isTouch) {
            this.path = path;
            this.name = name;
            this.isTouch = isTouch;
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
        List<DeviceSpec> devices = discoverInternalTouchDevices();
        if (devices.isEmpty()) {
            System.out.println("OPALDESK_WAKE_MONITOR_SKIP no readable internal touch evdev found (using helper double-tap/key wake fallback)");
            return;
        }
        final AtomicBoolean woken = new AtomicBoolean(false);
        for (DeviceSpec spec : devices) {
            if (!active.get() || generation != gen) return;
            Thread t = new Thread(() -> readDeviceLoop(gen, spec, woken, onWake), "OpalWake-" + spec.path);
            t.setDaemon(true);
            t.start();
        }
    }

    private void readDeviceLoop(int gen, DeviceSpec spec, AtomicBoolean woken, Runnable onWake) {
        DoubleTapWakeDetector detector = new DoubleTapWakeDetector();
        detector.arm(SystemClock.uptimeMillis());
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
            System.out.println("OPALDESK_WAKE_MONITOR_ARMED dev=" + spec.path + " name=" + spec.name);
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
                    if (detector.onRawEvent64(buf, i * DoubleTapWakeDetector.INPUT_EVENT_SIZE_64, nowMs, spec.isTouch)) {
                        if (woken.compareAndSet(false, true)) {
                            System.out.println("OPALDESK_DOUBLE_TAP_WAKE_TRIGGERED dev=" + spec.path);
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
     * Discovers internal touchscreen evdev nodes via {@code getevent -p} without vendor hardcodes.
     */
    private static List<DeviceSpec> discoverInternalTouchDevices() {
        List<DeviceSpec> out = new ArrayList<>();
        String dump = ShellCommand.runOutput("getevent", "-p");
        if (dump == null || dump.isEmpty()) return out;

        String curPath = null;
        String curName = "";
        boolean hasTouchCaps = false;
        boolean hasWakeKey = false;

        String[] lines = dump.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("add device ")) {
                maybeAddDevice(out, curPath, curName, hasTouchCaps, hasWakeKey);
                int colon = line.indexOf(':');
                curPath = (colon > 0) ? line.substring(colon + 1).trim() : null;
                curName = "";
                hasTouchCaps = false;
                hasWakeKey = false;
            } else if (line.startsWith("name:")) {
                curName = line.substring(5).trim().replace("\"", "");
            } else if (line.contains("0035") && line.contains("min") && line.contains("max")) {
                // ABS_MT_POSITION_X (0x0035) capability line on multi-touch screens
                hasTouchCaps = true;
            } else if (line.contains("014a")) {
                // BTN_TOUCH (0x014a)
                hasTouchCaps = true;
            } else if (line.contains("008f")) {
                // KEY_WAKEUP (0x008f)
                hasWakeKey = true;
            }
        }
        maybeAddDevice(out, curPath, curName, hasTouchCaps, hasWakeKey);
        return out;
    }

    private static void maybeAddDevice(
            List<DeviceSpec> out, String path, String name, boolean hasTouchCaps, boolean hasWakeKey) {
        if (path == null || !path.startsWith("/dev/input/event")) return;
        String lower = (name != null) ? name.toLowerCase(Locale.US) : "";
        if (lower.contains("opaldesk") || lower.contains("virtual") || lower.contains("uinput")) return;
        if (hasTouchCaps || (hasWakeKey && lower.contains("touch"))) {
            out.add(new DeviceSpec(path, name != null ? name : "", true));
        }
    }
}
