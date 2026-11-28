package io.github.lengsukq.opaldesk.daemon.platform;

import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.input.DoubleTapWakeDetector;
import io.github.lengsukq.opaldesk.daemon.input.VolumeKeyWakeDetector;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

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
    private static final String SYSFS_INPUT_ROOT = "/sys/class/input";
    private static final String BUS_USB = "0003";
    private static final String BUS_BLUETOOTH = "0005";
    private static final String BUS_VIRTUAL = "0006";

    private static final String[] EXTERNAL_SYSFS_MARKERS = {
            "/usb", "usb-", "uhid", "bluetooth"
    };

    private static final String[] EXTERNAL_TOUCH_NAME_TOKENS = {
            "wacom", "hidtouch", "usb", "external", "pen", "stylus",
            "touchpad", "trackpad", "mouse", "uhid", "opaldesk"
    };

    private static final String[] EXTERNAL_VOLUME_NAME_TOKENS = {
            "usb", "external", "bluetooth", "uhid", "wacom", "hidtouch",
            "keyboard", "mouse", "touchpad", "trackpad", "avrcp",
            "consumer control", "opaldesk"
    };

    interface SysfsProbe {
        SysfsDeviceInfo probe(String devPath);
    }

    static final class SysfsDeviceInfo {
        static final SysfsDeviceInfo EMPTY = new SysfsDeviceInfo("", "", "");

        final String busType;
        final String phys;
        final String canonicalDevicePath;

        SysfsDeviceInfo(String busType, String phys, String canonicalDevicePath) {
            this.busType = (busType != null) ? busType.trim() : "";
            this.phys = (phys != null) ? phys.trim() : "";
            this.canonicalDevicePath = (canonicalDevicePath != null) ? canonicalDevicePath.trim() : "";
        }
    }

    interface WakeLauncher {
        void launch(int gen, BooleanSupplier onWake);
    }

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
    private final WakeLauncher launcher;

    public HostScreenWakeMonitor() {
        this(null);
    }

    HostScreenWakeMonitor(WakeLauncher launcher) {
        this.launcher = (launcher != null) ? launcher : this::launchDiscoveryThread;
    }

    public boolean isActive() {
        return active.get();
    }

    public void start(Runnable onWake) {
        if (onWake == null) {
            stop();
            return;
        }
        start(() -> {
            onWake.run();
            return true;
        });
    }

    public void start(BooleanSupplier onWake) {
        stop();
        if (onWake == null) return;
        final int gen;
        synchronized (lock) {
            active.set(true);
            gen = ++generation;
        }
        launcher.launch(gen, onWake);
    }

    private void launchDiscoveryThread(int gen, BooleanSupplier onWake) {
        Thread t = new Thread(() -> startMonitorsAsync(gen, onWake), "OpalWakeDiscovery");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopAndGetGeneration();
    }

    private int stopAndGetGeneration() {
        List<FileInputStream> toClose;
        int gen;
        synchronized (lock) {
            active.set(false);
            gen = ++generation;
            toClose = new ArrayList<>(openStreams);
            openStreams.clear();
        }
        for (FileInputStream fis : toClose) {
            try {
                fis.close();
            } catch (IOException ignored) {
            }
        }
        return gen;
    }

    private void startMonitorsAsync(int gen, BooleanSupplier onWake) {
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
            BooleanSupplier onWake) {
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
                            handleWakeTriggered(onWake, spec.path);
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

    boolean handleWakeTriggered(BooleanSupplier onWake, String devPath) {
        int stoppedGen = stopAndGetGeneration();
        boolean wokenOk = false;
        try {
            wokenOk = onWake != null && onWake.getAsBoolean();
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_WAKE_CALLBACK_ERR dev=" + devPath + ": " + e.getMessage());
        }
        if (!wokenOk && onWake != null) {
            boolean shouldRearm;
            synchronized (lock) {
                shouldRearm = (generation == stoppedGen && !active.get());
            }
            if (shouldRearm) {
                System.err.println("OPALDESK_WAKE_MONITOR_REARM dev=" + devPath + " reason=wake_failed");
                start(onWake);
            }
        }
        return wokenOk;
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
        return parseWakeDevicesFromDump(dump, HostScreenWakeMonitor::probeSysfsDevice);
    }

    static List<DeviceSpec> parseWakeDevicesFromDump(String dump, SysfsProbe sysfsProbe) {
        List<DeviceSpec> out = new ArrayList<>();
        if (dump == null || dump.isEmpty()) return out;
        SysfsProbe probe = (sysfsProbe != null) ? sysfsProbe : (p -> SysfsDeviceInfo.EMPTY);

        String curPath = null;
        String curName = "";
        String curBusType = "";
        String curLocation = "";
        boolean hasTouchCaps = false;
        boolean hasWakeKey = false;
        boolean hasVolumeKey = false;
        boolean inKeySection = false;

        String[] lines = dump.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("add device ")) {
                maybeAddDevice(out, curPath, curName, curBusType, curLocation,
                        hasTouchCaps, hasWakeKey, hasVolumeKey, probe);
                int colon = line.indexOf(':');
                curPath = (colon > 0) ? line.substring(colon + 1).trim() : null;
                curName = "";
                curBusType = "";
                curLocation = "";
                hasTouchCaps = false;
                hasWakeKey = false;
                hasVolumeKey = false;
                inKeySection = false;
            } else if (line.startsWith("name:")) {
                curName = line.substring(5).trim().replace("\"", "");
                inKeySection = false;
            } else if (line.startsWith("bus:")) {
                curBusType = line.substring(4).trim();
                inKeySection = false;
            } else if (line.startsWith("location:")) {
                curLocation = line.substring(9).trim().replace("\"", "");
                inKeySection = false;
            } else if (line.startsWith("KEY (0001):")) {
                inKeySection = true;
                String keyLine = line.substring("KEY (0001):".length());
                if (containsHexCodeToken(keyLine, "014a")) hasTouchCaps = true;
                if (containsHexCodeToken(keyLine, "008f")) hasWakeKey = true;
                if (containsHexCodeToken(keyLine, "0073") || containsHexCodeToken(keyLine, "0072")) {
                    hasVolumeKey = true;
                }
            } else if (line.startsWith("ABS (0003):")) {
                inKeySection = false;
                if (line.contains("0035") && line.contains("min") && line.contains("max")) {
                    hasTouchCaps = true;
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
        maybeAddDevice(out, curPath, curName, curBusType, curLocation,
                hasTouchCaps, hasWakeKey, hasVolumeKey, probe);
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
            List<DeviceSpec> out,
            String path,
            String name,
            String dumpBusType,
            String dumpLocation,
            boolean hasTouchCaps,
            boolean hasWakeKey,
            boolean hasVolumeKey,
            SysfsProbe sysfsProbe) {
        if (path == null || !path.startsWith("/dev/input/event")) return;
        String safeName = (name != null) ? name : "";
        String lower = safeName.toLowerCase(Locale.US);
        if (lower.contains("opaldesk") || lower.contains("virtual") || lower.contains("uinput")) return;
        boolean isTouch = hasTouchCaps || (hasWakeKey && lower.contains("touch"));
        boolean isVolume = hasVolumeKey;
        if (!isTouch && !isVolume) return;

        SysfsDeviceInfo sysfs = sysfsProbe.probe(path);
        if (sysfs == null) sysfs = SysfsDeviceInfo.EMPTY;

        String externalBusOrPathReason = detectExternalBusOrSysfsReason(dumpBusType, dumpLocation, sysfs);
        if (externalBusOrPathReason != null) {
            System.out.println("OPALDESK_WAKE_MONITOR_SKIP_EXTERNAL dev=" + path
                    + " name=" + safeName + " reason=" + externalBusOrPathReason);
            return;
        }

        if (isTouch) {
            String touchToken = findMatchedToken(lower, EXTERNAL_TOUCH_NAME_TOKENS);
            if (touchToken != null) {
                System.out.println("OPALDESK_WAKE_MONITOR_SKIP_EXTERNAL dev=" + path
                        + " name=" + safeName + " reason=touch_name:" + touchToken);
                isTouch = false;
            }
        }
        if (isVolume) {
            String volToken = findMatchedToken(lower, EXTERNAL_VOLUME_NAME_TOKENS);
            if (volToken != null) {
                System.out.println("OPALDESK_WAKE_MONITOR_SKIP_EXTERNAL dev=" + path
                        + " name=" + safeName + " reason=vol_name:" + volToken);
                isVolume = false;
            }
        }

        if (isTouch || isVolume) {
            out.add(new DeviceSpec(path, safeName, isTouch, isVolume));
        }
    }

    static String detectExternalBusOrSysfsReason(
            String dumpBusType, String dumpLocation, SysfsDeviceInfo sysfs) {
        String sysfsBus = normalizeBusType(sysfs != null ? sysfs.busType : "");
        if (isExternalBusType(sysfsBus)) {
            return "bustype=" + sysfsBus;
        }
        String dumpBus = normalizeBusType(dumpBusType);
        if (isExternalBusType(dumpBus)) {
            return "bus=" + dumpBus;
        }
        String canonical = (sysfs != null) ? sysfs.canonicalDevicePath.toLowerCase(Locale.US) : "";
        String canonicalMarker = findMatchedToken(canonical, EXTERNAL_SYSFS_MARKERS);
        if (canonicalMarker != null) {
            return "sysfs_path:" + canonicalMarker;
        }
        String phys = (sysfs != null) ? sysfs.phys.toLowerCase(Locale.US) : "";
        String physMarker = findMatchedToken(phys, EXTERNAL_SYSFS_MARKERS);
        if (physMarker != null) {
            return "phys:" + physMarker;
        }
        String location = (dumpLocation != null) ? dumpLocation.toLowerCase(Locale.US) : "";
        String locMarker = findMatchedToken(location, EXTERNAL_SYSFS_MARKERS);
        if (locMarker != null) {
            return "location:" + locMarker;
        }
        return null;
    }

    static boolean isExternalBusType(String normalizedBusType) {
        return BUS_USB.equals(normalizedBusType)
                || BUS_BLUETOOTH.equals(normalizedBusType)
                || BUS_VIRTUAL.equals(normalizedBusType);
    }

    static String normalizeBusType(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.isEmpty()) return "";
        if (s.startsWith("0x")) {
            s = s.substring(2);
        }
        try {
            int val = Integer.parseInt(s, 16);
            return String.format(Locale.US, "%04x", val & 0xffff);
        } catch (NumberFormatException ignored) {
            return s;
        }
    }

    private static String findMatchedToken(String lowerText, String[] tokens) {
        if (lowerText == null || lowerText.isEmpty()) return null;
        for (String token : tokens) {
            if (lowerText.contains(token)) return token;
        }
        return null;
    }

    static SysfsDeviceInfo probeSysfsDevice(String devPath) {
        return probeSysfsDevice(devPath, new File(SYSFS_INPUT_ROOT));
    }

    static SysfsDeviceInfo probeSysfsDevice(String devPath, File sysfsInputRoot) {
        if (devPath == null || !devPath.startsWith("/dev/input/") || sysfsInputRoot == null) {
            return SysfsDeviceInfo.EMPTY;
        }
        String eventName = devPath.substring("/dev/input/".length()).trim();
        if (eventName.isEmpty() || eventName.contains("/") || eventName.contains("..")) {
            return SysfsDeviceInfo.EMPTY;
        }
        File deviceDir = new File(new File(sysfsInputRoot, eventName), "device");
        String busType = readFirstLineQuietly(new File(new File(deviceDir, "id"), "bustype"));
        String phys = readFirstLineQuietly(new File(deviceDir, "phys"));
        String canonicalPath = "";
        if (deviceDir.exists()) {
            try {
                canonicalPath = deviceDir.getCanonicalPath();
            } catch (IOException ignored) {
            }
        }
        return new SysfsDeviceInfo(busType, phys, canonicalPath);
    }

    private static String readFirstLineQuietly(File file) {
        if (file == null || !file.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return (line != null) ? line.trim() : "";
        } catch (IOException ignored) {
            return "";
        }
    }
}
