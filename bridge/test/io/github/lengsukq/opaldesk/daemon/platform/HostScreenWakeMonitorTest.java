package io.github.lengsukq.opaldesk.daemon.platform;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostScreenWakeMonitorTest {

    @Test
    public void filtersWacomExternalPortableMonitorAndKeepsInternalTouchpanelAndGpioKeys() {
        String dump = ""
                + "add device 1: /dev/input/event12\n"
                + "  name:     \"Wacom HIDTOUCH Pen\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "    ABS (0003): 0035  : value 0, min 0, max 32767\n"
                + "add device 2: /dev/input/event6\n"
                + "  name:     \"touchpanel\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a 008f\n"
                + "    ABS (0003): 0035  : value 0, min 0, max 1440\n"
                + "add device 3: /dev/input/event13\n"
                + "  name:     \"Wacom HIDTOUCH Finger\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "    ABS (0003): 0035  : value 0, min 0, max 32767\n"
                + "add device 4: /dev/input/event2\n"
                + "  name:     \"gpio-keys\"\n"
                + "  events:\n"
                + "    KEY (0001): 0072 0073\n"
                + "add device 5: /dev/input/event3\n"
                + "  name:     \"pmic_resin\"\n"
                + "  events:\n"
                + "    KEY (0001): 0072\n";

        Map<String, HostScreenWakeMonitor.SysfsDeviceInfo> sysfs = new HashMap<>();
        sysfs.put("/dev/input/event12", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "0003", "usb-a600000.dwc3-1.2/input0",
                "/sys/devices/platform/soc/a600000.ssusb/xhci-hcd/usb1/1-1/1-1.2/input/input12"));
        sysfs.put("/dev/input/event6", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "0018", "",
                "/sys/devices/platform/soc/98c000.i2c/i2c-2/2-0038/input/input6"));
        sysfs.put("/dev/input/event13", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "0003", "usb-a600000.dwc3-1.2/input1",
                "/sys/devices/platform/soc/a600000.ssusb/xhci-hcd/usb1/1-1/1-1.2/input/input13"));
        sysfs.put("/dev/input/event2", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "0019", "gpio-keys/input0",
                "/sys/devices/platform/soc/soc:gpio_keys/input/input2"));
        sysfs.put("/dev/input/event3", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "0000", "",
                "/sys/devices/platform/soc/c440000.qcom,spmi/input/input3"));

        List<HostScreenWakeMonitor.DeviceSpec> devices =
                HostScreenWakeMonitor.parseWakeDevicesFromDump(dump, sysfs::get);

        assertEquals(3, devices.size());
        assertEquals("/dev/input/event6", devices.get(0).path);
        assertEquals("touchpanel", devices.get(0).name);
        assertTrue(devices.get(0).isTouch);
        assertFalse(devices.get(0).isVolumeKey);

        assertEquals("/dev/input/event2", devices.get(1).path);
        assertEquals("gpio-keys", devices.get(1).name);
        assertFalse(devices.get(1).isTouch);
        assertTrue(devices.get(1).isVolumeKey);

        assertEquals("/dev/input/event3", devices.get(2).path);
        assertEquals("pmic_resin", devices.get(2).name);
        assertFalse(devices.get(2).isTouch);
        assertTrue(devices.get(2).isVolumeKey);
    }

    @Test
    public void filtersExternalTouchByNameFallbackWhenSysfsUnavailable() {
        String dump = ""
                + "add device 1: /dev/input/event12\n"
                + "  name:     \"Wacom HIDTOUCH Pen\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "add device 2: /dev/input/event13\n"
                + "  name:     \"Wacom HIDTOUCH Finger\"\n"
                + "  events:\n"
                + "    ABS (0003): 0035  : value 0, min 0, max 32767\n"
                + "add device 3: /dev/input/event14\n"
                + "  name:     \"Generic USB Stylus\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "add device 4: /dev/input/event15\n"
                + "  name:     \"External Touchpad\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "add device 5: /dev/input/event6\n"
                + "  name:     \"touchpanel\"\n"
                + "  events:\n"
                + "    ABS (0003): 0035  : value 0, min 0, max 1440\n";

        List<HostScreenWakeMonitor.DeviceSpec> devices =
                HostScreenWakeMonitor.parseWakeDevicesFromDump(
                        dump, path -> HostScreenWakeMonitor.SysfsDeviceInfo.EMPTY);

        assertEquals(1, devices.size());
        assertEquals("/dev/input/event6", devices.get(0).path);
        assertEquals("touchpanel", devices.get(0).name);
        assertTrue(devices.get(0).isTouch);
    }

    @Test
    public void filtersUsbBluetoothVirtualBusAndSysfsPathsEvenWithGenericNames() {
        String dump = ""
                + "add device 1: /dev/input/event10\n"
                + "  name:     \"Screen A\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "add device 2: /dev/input/event11\n"
                + "  name:     \"Screen B\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "add device 3: /dev/input/event12\n"
                + "  name:     \"Screen C\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "add device 4: /dev/input/event13\n"
                + "  name:     \"Screen D\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "add device 5: /dev/input/event14\n"
                + "  name:     \"Screen E\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "add device 6: /dev/input/event15\n"
                + "  bus:      0003\n"
                + "  name:     \"Screen F\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n"
                + "add device 7: /dev/input/event6\n"
                + "  name:     \"goodix_ts\"\n"
                + "  events:\n"
                + "    KEY (0001): 014a\n";

        Map<String, HostScreenWakeMonitor.SysfsDeviceInfo> sysfs = new HashMap<>();
        // BUS_USB
        sysfs.put("/dev/input/event10", new HostScreenWakeMonitor.SysfsDeviceInfo("0003", "", ""));
        // BUS_BLUETOOTH (unpadded hex)
        sysfs.put("/dev/input/event11", new HostScreenWakeMonitor.SysfsDeviceInfo("0x5", "", ""));
        // BUS_VIRTUAL
        sysfs.put("/dev/input/event12", new HostScreenWakeMonitor.SysfsDeviceInfo("0006", "", ""));
        // /usb canonical path with unknown bustype
        sysfs.put("/dev/input/event13", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "", "", "/sys/devices/platform/soc/xhci/usb1/1-1/input/input13"));
        // uhid in phys with unknown bustype
        sysfs.put("/dev/input/event14", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "", "uhid/hidraw0", ""));
        // Screen F relies on getevent dump bus: 0003
        sysfs.put("/dev/input/event15", HostScreenWakeMonitor.SysfsDeviceInfo.EMPTY);
        // Built-in SPI touchscreen
        sysfs.put("/dev/input/event6", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "001c", "spi0.0/input0", "/sys/devices/platform/soc/spi0/input/input6"));

        List<HostScreenWakeMonitor.DeviceSpec> devices =
                HostScreenWakeMonitor.parseWakeDevicesFromDump(dump, sysfs::get);

        assertEquals(1, devices.size());
        assertEquals("/dev/input/event6", devices.get(0).path);
        assertEquals("goodix_ts", devices.get(0).name);
        assertTrue(devices.get(0).isTouch);
    }

    @Test
    public void filtersExternalUsbAndBluetoothVolumeKeyDevices() {
        String dump = ""
                + "add device 1: /dev/input/event20\n"
                + "  name:     \"Portable Monitor Consumer Control\"\n"
                + "  events:\n"
                + "    KEY (0001): 0072 0073\n"
                + "add device 2: /dev/input/event21\n"
                + "  name:     \"Keychron K2\"\n"
                + "  events:\n"
                + "    KEY (0001): 0072 0073\n"
                + "add device 3: /dev/input/event22\n"
                + "  name:     \"USB Audio Headset\"\n"
                + "  events:\n"
                + "    KEY (0001): 0072 0073\n"
                + "add device 4: /dev/input/event23\n"
                + "  name:     \"BT Media Remote\"\n"
                + "  location: \"usb-0000:00:14.0-1/input0\"\n"
                + "  events:\n"
                + "    KEY (0001): 0073\n"
                + "add device 5: /dev/input/event2\n"
                + "  name:     \"gpio-keys\"\n"
                + "  events:\n"
                + "    KEY (0001): 0072 0073\n";

        Map<String, HostScreenWakeMonitor.SysfsDeviceInfo> sysfs = new HashMap<>();
        // Consumer Control filtered even if sysfs unreadable
        sysfs.put("/dev/input/event20", HostScreenWakeMonitor.SysfsDeviceInfo.EMPTY);
        // Bluetooth keyboard filtered via BUS_BLUETOOTH
        sysfs.put("/dev/input/event21", new HostScreenWakeMonitor.SysfsDeviceInfo("0005", "", ""));
        // USB audio filtered via BUS_USB
        sysfs.put("/dev/input/event22", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "0003", "usb-a600000.dwc3-1/input0", ""));
        // Location fallback
        sysfs.put("/dev/input/event23", HostScreenWakeMonitor.SysfsDeviceInfo.EMPTY);
        // Built-in gpio-keys kept
        sysfs.put("/dev/input/event2", new HostScreenWakeMonitor.SysfsDeviceInfo(
                "0019", "gpio-keys/input0",
                "/sys/devices/platform/soc/soc:gpio_keys/input/input2"));

        List<HostScreenWakeMonitor.DeviceSpec> devices =
                HostScreenWakeMonitor.parseWakeDevicesFromDump(dump, sysfs::get);

        assertEquals(1, devices.size());
        assertEquals("/dev/input/event2", devices.get(0).path);
        assertEquals("gpio-keys", devices.get(0).name);
        assertFalse(devices.get(0).isTouch);
        assertTrue(devices.get(0).isVolumeKey);
    }

    @Test
    public void probesSysfsBustypePhysAndCanonicalSymlinkPath() throws Exception {
        Path root = Files.createTempDirectory("opaldesk-sysfs-test");
        try {
            Path realUsbDevice = root.resolve("devices/platform/soc/usb1/1-1/input/input13");
            Files.createDirectories(realUsbDevice.resolve("id"));
            writeUtf8(realUsbDevice.resolve("id/bustype").toFile(), "0003\n");
            writeUtf8(realUsbDevice.resolve("phys").toFile(), "usb-a600000.dwc3-1.2/input0\n");

            Path sysfsInputRoot = root.resolve("class/input");
            Path event13Dir = sysfsInputRoot.resolve("event13");
            Files.createDirectories(event13Dir);
            Files.createSymbolicLink(event13Dir.resolve("device"), realUsbDevice);

            HostScreenWakeMonitor.SysfsDeviceInfo info =
                    HostScreenWakeMonitor.probeSysfsDevice("/dev/input/event13", sysfsInputRoot.toFile());

            assertEquals("0003", info.busType);
            assertEquals("usb-a600000.dwc3-1.2/input0", info.phys);
            assertTrue(info.canonicalDevicePath.contains("/usb1/"));
            assertEquals("bustype=0003",
                    HostScreenWakeMonitor.detectExternalBusOrSysfsReason("", "", info));
        } finally {
            deleteRecursively(root.toFile());
        }
    }

    private static void writeUtf8(File file, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
