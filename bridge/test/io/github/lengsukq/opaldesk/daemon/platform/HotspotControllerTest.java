package io.github.lengsukq.opaldesk.daemon.platform;

import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HotspotControllerTest {

    @Test
    public void testAlreadyActiveHotspotDetectedWithoutCallingStartSoftAp() {
        List<String> executed = new ArrayList<>();
        HotspotController controller = new HotspotController(
                (timeoutMs, cmd) -> {
                    String joined = String.join(" ", cmd);
                    executed.add(joined);
                    if ("ip -4 addr".equals(joined)) {
                        return new ShellCommand.Result(0,
                                "15: wlan0: <BROADCAST,MULTICAST,UP>\n"
                                        + "    inet 192.168.1.42/24 brd 192.168.1.255 scope global wlan0\n"
                                        + "16: ap0: <BROADCAST,MULTICAST,UP>\n"
                                        + "    inet 192.168.43.1/24 brd 192.168.43.255 scope global ap0\n",
                                false);
                    }
                    if ("cmd wifi status".equals(joined)) {
                        return new ShellCommand.Result(0,
                                "Wifi is enabled\nSoft AP is enabled\nSoft AP SSID: \"OpalPhone-5G\"\n",
                                false);
                    }
                    return new ShellCommand.Result(0, "", false);
                },
                ms -> {});

        HotspotController.HotspotResult res = controller.triggerHotspot(19878);
        assertEquals(HotspotController.STATUS_ALREADY_ACTIVE, res.status);
        assertEquals("OpalPhone-5G", res.ssid);
        assertEquals("192.168.43.1", res.gatewayIp);
        assertEquals(19878, res.port);
        assertFalse("Should not invoke start-softap when already active",
                executed.contains("cmd wifi start-softap"));

        SimpleJson.JsonObjectView json = SimpleJson.parseObject(res.toJson());
        assertEquals("already_active", json.getString("status", ""));
        assertEquals("OpalPhone-5G", json.getString("ssid", ""));
        assertEquals("192.168.43.1", json.getString("gatewayIp", ""));
        assertEquals(19878, json.getInt("port", 0));
    }

    @Test
    public void testShellStartSoftApSucceedsAndPollsUntilSoftApIpAppears() {
        AtomicInteger ipProbes = new AtomicInteger(0);
        HotspotController controller = new HotspotController(
                (timeoutMs, cmd) -> {
                    String joined = String.join(" ", cmd);
                    if ("ip -4 addr".equals(joined)) {
                        int n = ipProbes.incrementAndGet();
                        if (n <= 2) {
                            // Initial probe + poll #1: only regular Wi-Fi station IP present
                            return new ShellCommand.Result(0,
                                    "15: wlan0: <UP>\n    inet 192.168.1.50/24 scope global wlan0\n",
                                    false);
                        }
                        return new ShellCommand.Result(0,
                                "15: wlan0: <UP>\n    inet 192.168.1.50/24 scope global wlan0\n"
                                        + "18: swlan0: <UP>\n    inet 192.168.137.1/24 scope global swlan0\n",
                                false);
                    }
                    if ("cmd wifi start-softap".equals(joined)) {
                        return new ShellCommand.Result(0, "", false);
                    }
                    if ("cmd wifi status".equals(joined)) {
                        return new ShellCommand.Result(0, "Wifi is enabled\n", false);
                    }
                    if ("dumpsys wifi".equals(joined)) {
                        if (ipProbes.get() >= 3) {
                            return new ShellCommand.Result(0,
                                    "SoftApManager:\n  curState=StartedState\n  mWifiApState=13\n  SSID=\"PixelHotspot\"\n",
                                    false);
                        }
                        return new ShellCommand.Result(0, "mWifiApState=11\n", false);
                    }
                    return new ShellCommand.Result(0, "", false);
                },
                ms -> {},
                4,
                0L);

        HotspotController.HotspotResult res = controller.triggerHotspot(19878);
        assertEquals(HotspotController.STATUS_STARTED, res.status);
        assertEquals("PixelHotspot", res.ssid);
        assertEquals("192.168.137.1", res.gatewayIp);
        assertEquals(19878, res.port);
    }

    @Test
    public void testShellTetherFallbackWhenStartSoftApUnsupported() {
        AtomicInteger tetherStarted = new AtomicInteger(0);
        HotspotController controller = new HotspotController(
                (timeoutMs, cmd) -> {
                    String joined = String.join(" ", cmd);
                    if ("ip -4 addr".equals(joined)) {
                        if (tetherStarted.get() > 0) {
                            return new ShellCommand.Result(0,
                                    "20: wlan1: <UP>\n    inet 192.168.43.1/24 scope global wlan1\n",
                                    false);
                        }
                        return new ShellCommand.Result(0, "", false);
                    }
                    if ("cmd wifi start-softap".equals(joined)) {
                        return new ShellCommand.Result(1, "Usage: start-softap <ssid> open|wpa2", false);
                    }
                    if ("cmd connectivity tether wifi start".equals(joined)) {
                        tetherStarted.incrementAndGet();
                        return new ShellCommand.Result(0, "Tethering started", false);
                    }
                    return new ShellCommand.Result(0, "", false);
                },
                ms -> {},
                2,
                0L);

        HotspotController.HotspotResult res = controller.triggerHotspot(19999);
        assertEquals(HotspotController.STATUS_STARTED, res.status);
        assertEquals("192.168.43.1", res.gatewayIp);
        assertEquals(19999, res.port);
    }

    @Test
    public void testRomSecurityExceptionReturnsHonestNeedsUserToggleAndUnauthorizedJson() {
        HotspotController controller = new HotspotController(
                (timeoutMs, cmd) -> {
                    String joined = String.join(" ", cmd);
                    if ("ip -4 addr".equals(joined)) {
                        return new ShellCommand.Result(0,
                                "15: wlan0: <UP>\n    inet 192.168.1.25/24 scope global wlan0\n",
                                false);
                    }
                    if ("cmd wifi start-softap".equals(joined)) {
                        return new ShellCommand.Result(255,
                                "java.lang.SecurityException: uid 2000 does not have android.permission.TETHER_PRIVILEGED",
                                false);
                    }
                    if ("cmd connectivity tether wifi start".equals(joined)) {
                        return new ShellCommand.Result(255,
                                "SecurityException: Permission Denial",
                                false);
                    }
                    return new ShellCommand.Result(0, "", false);
                },
                ms -> {},
                2,
                0L);

        HotspotController.HotspotResult res = controller.triggerHotspot(19878);
        assertEquals(HotspotController.STATUS_NEEDS_USER_TOGGLE, res.status);
        assertEquals("", res.ssid);
        assertEquals("", res.gatewayIp);
        assertEquals(19878, res.port);
        assertEquals(HotspotController.MSG_NEEDS_USER_TOGGLE, res.message);

        SimpleJson.JsonObjectView unauth = SimpleJson.parseObject(
                HotspotController.HotspotResult.unauthorized(19878).toJson());
        assertEquals("unauthorized", unauth.getString("status", ""));
        assertEquals("", unauth.getString("ssid", ""));
        assertEquals("", unauth.getString("gatewayIp", ""));
        assertEquals(19878, unauth.getInt("port", 0));
        assertEquals(HotspotController.MSG_UNAUTHORIZED, unauth.getString("message", ""));
    }
}
