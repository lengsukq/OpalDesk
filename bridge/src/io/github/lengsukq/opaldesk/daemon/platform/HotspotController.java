package io.github.lengsukq.opaldesk.daemon.platform;

import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rootless ({@code uid=2000}) Android SoftAP / Instant Hotspot detector and trigger controller.
 *
 * <p>Checks existing SoftAP interfaces first, attempts standard AOSP shell SoftAP start commands
 * ({@code cmd wifi start-softap} / {@code cmd connectivity tether wifi start}), polls briefly for
 * an active SoftAP gateway IP, and honestly reports {@link #STATUS_NEEDS_USER_TOGGLE} when the ROM
 * restricts background SoftAP toggling from shell UID.
 */
public final class HotspotController {
    public static final String STATUS_ALREADY_ACTIVE = "already_active";
    public static final String STATUS_STARTED = "started";
    public static final String STATUS_NEEDS_USER_TOGGLE = "needs_user_toggle";
    public static final String STATUS_UNAUTHORIZED = "unauthorized";

    public static final String MSG_ALREADY_ACTIVE = "手机热点已开启";
    public static final String MSG_STARTED = "已启动手机热点";
    public static final String MSG_NEEDS_USER_TOGGLE = "ROM 限制后台自动开热点，请在手机端确认开启";
    public static final String MSG_UNAUTHORIZED = "未经授权的蓝牙热点请求";

    private static final long PROBE_CMD_TIMEOUT_MS = 2_000L;
    private static final long START_CMD_TIMEOUT_MS = 3_500L;
    private static final int DEFAULT_POLL_ATTEMPTS = 6;
    private static final long DEFAULT_POLL_INTERVAL_MS = 350L;

    private static final Pattern IP_ADDR_IFACE_PATTERN = Pattern.compile("^\\d+:\\s+([^:@\\s]+)");
    private static final Pattern IP_ADDR_INET4_PATTERN = Pattern.compile("\\binet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)/\\d+");
    private static final Pattern SSID_QUOTED_PATTERN = Pattern.compile("(?i)(?:soft\\s*ap\\s+ssid|ap\\s*ssid|m?ssid|wifissid)\\s*[:=]\\s*\"([^\"]+)\"");
    private static final Pattern SSID_UNQUOTED_PATTERN = Pattern.compile("(?i)(?:soft\\s*ap\\s+ssid|ap\\s*ssid|m?ssid)\\s*[:=]\\s*([^,\\s}\\]]+)");

    public interface CommandRunner {
        ShellCommand.Result run(long timeoutMs, String... cmd);
    }

    public interface Sleeper {
        void sleepMs(long millis);
    }

    public static final class HotspotResult {
        public final String status;
        public final String ssid;
        public final String gatewayIp;
        public final int port;
        public final String message;

        public HotspotResult(String status, String ssid, String gatewayIp, int port, String message) {
            this.status = (status != null) ? status : STATUS_NEEDS_USER_TOGGLE;
            this.ssid = (ssid != null) ? ssid : "";
            this.gatewayIp = (gatewayIp != null) ? gatewayIp : "";
            this.port = Math.max(0, port);
            this.message = (message != null) ? message : "";
        }

        public static HotspotResult unauthorized(int wifiPort) {
            return new HotspotResult(STATUS_UNAUTHORIZED, "", "", wifiPort, MSG_UNAUTHORIZED);
        }

        public String toJson() {
            return new SimpleJson.ObjectBuilder()
                    .put("status", status)
                    .put("ssid", ssid)
                    .put("gatewayIp", gatewayIp)
                    .put("port", port)
                    .put("message", message)
                    .build();
        }
    }

    static final class SoftApProbe {
        final boolean active;
        final String ssid;
        final String gatewayIp;

        SoftApProbe(boolean active, String ssid, String gatewayIp) {
            this.active = active;
            this.ssid = (ssid != null) ? ssid : "";
            this.gatewayIp = (gatewayIp != null) ? gatewayIp : "";
        }
    }

    private static final class InterfaceIpv4 {
        final String name;
        final String ip;

        InterfaceIpv4(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }
    }

    private final CommandRunner commandRunner;
    private final Sleeper sleeper;
    private final int pollAttempts;
    private final long pollIntervalMs;

    public HotspotController() {
        this(ShellCommand::runWithTimeout, HotspotController::sleepQuietly,
                DEFAULT_POLL_ATTEMPTS, DEFAULT_POLL_INTERVAL_MS);
    }

    public HotspotController(CommandRunner commandRunner, Sleeper sleeper) {
        this(commandRunner, sleeper, DEFAULT_POLL_ATTEMPTS, DEFAULT_POLL_INTERVAL_MS);
    }

    public HotspotController(
            CommandRunner commandRunner,
            Sleeper sleeper,
            int pollAttempts,
            long pollIntervalMs) {
        this.commandRunner = (commandRunner != null) ? commandRunner : ShellCommand::runWithTimeout;
        this.sleeper = (sleeper != null) ? sleeper : HotspotController::sleepQuietly;
        this.pollAttempts = Math.max(1, pollAttempts);
        this.pollIntervalMs = Math.max(0L, pollIntervalMs);
    }

    public HotspotResult triggerHotspot(int wifiPort) {
        SoftApProbe existing = probeActiveSoftAp();
        if (existing.active && !existing.gatewayIp.isEmpty()) {
            return new HotspotResult(
                    STATUS_ALREADY_ACTIVE, existing.ssid, existing.gatewayIp, wifiPort, MSG_ALREADY_ACTIVE);
        }

        boolean startIssued = tryStartSoftApViaShell();
        if (!startIssued) {
            return new HotspotResult(STATUS_NEEDS_USER_TOGGLE, "", "", wifiPort, MSG_NEEDS_USER_TOGGLE);
        }

        for (int attempt = 0; attempt < pollAttempts; attempt++) {
            if (pollIntervalMs > 0L) {
                sleeper.sleepMs(pollIntervalMs);
            }
            SoftApProbe after = probeActiveSoftAp();
            if (after.active && !after.gatewayIp.isEmpty()) {
                return new HotspotResult(
                        STATUS_STARTED, after.ssid, after.gatewayIp, wifiPort, MSG_STARTED);
            }
        }
        return new HotspotResult(STATUS_NEEDS_USER_TOGGLE, "", "", wifiPort, MSG_NEEDS_USER_TOGGLE);
    }

    public boolean isHotspotActive() {
        SoftApProbe probe = probeActiveSoftAp();
        return probe.active && !probe.gatewayIp.isEmpty();
    }

    private boolean tryStartSoftApViaShell() {
        ShellCommand.Result wifiCmd = runSafe(START_CMD_TIMEOUT_MS, "cmd", "wifi", "start-softap");
        if (isShellStartAccepted(wifiCmd)) {
            return true;
        }
        ShellCommand.Result tetherCmd = runSafe(START_CMD_TIMEOUT_MS, "cmd", "connectivity", "tether", "wifi", "start");
        return isShellStartAccepted(tetherCmd);
    }

    private static boolean isShellStartAccepted(ShellCommand.Result res) {
        if (res == null || !res.isSuccess()) {
            return false;
        }
        String lower = res.output.toLowerCase(Locale.ROOT);
        return !lower.contains("securityexception")
                && !lower.contains("permission denial")
                && !lower.contains("not permitted")
                && !lower.contains("unknown command")
                && !lower.contains("unsupported")
                && !lower.contains("usage:")
                && !lower.contains("exception");
    }

    SoftApProbe probeActiveSoftAp() {
        ShellCommand.Result ipRes = runSafe(PROBE_CMD_TIMEOUT_MS, "ip", "-4", "addr");
        List<InterfaceIpv4> ifaces = parseIpAddrInterfaces(ipRes != null ? ipRes.output : "");

        String dedicatedApIp = selectDedicatedSoftApIp(ifaces);
        ShellCommand.Result wifiStatusRes = runSafe(PROBE_CMD_TIMEOUT_MS, "cmd", "wifi", "status");
        String wifiStatusText = (wifiStatusRes != null) ? wifiStatusRes.output : "";

        boolean softApReportedEnabled = isSoftApReportedEnabled(wifiStatusText);
        String ssid = extractSoftApSsid(wifiStatusText);

        if (dedicatedApIp.isEmpty() || ssid.isEmpty()) {
            ShellCommand.Result dumpRes = runSafe(PROBE_CMD_TIMEOUT_MS, "dumpsys", "wifi");
            String dumpText = (dumpRes != null) ? dumpRes.output : "";
            if (!softApReportedEnabled) {
                softApReportedEnabled = isSoftApReportedEnabled(dumpText);
            }
            if (ssid.isEmpty()) {
                ssid = extractSoftApSsid(dumpText);
            }
        }

        if (!dedicatedApIp.isEmpty()) {
            return new SoftApProbe(true, ssid, dedicatedApIp);
        }
        if (softApReportedEnabled) {
            String fallbackGatewayIp = selectFallbackSoftApGatewayIp(ifaces);
            if (!fallbackGatewayIp.isEmpty()) {
                return new SoftApProbe(true, ssid, fallbackGatewayIp);
            }
        }
        return new SoftApProbe(false, "", "");
    }

    private ShellCommand.Result runSafe(long timeoutMs, String... cmd) {
        try {
            ShellCommand.Result res = commandRunner.run(timeoutMs, cmd);
            return (res != null) ? res : new ShellCommand.Result(-1, "", false);
        } catch (RuntimeException e) {
            return new ShellCommand.Result(-1, e.getMessage(), false);
        }
    }

    static List<InterfaceIpv4> parseIpAddrInterfaces(String ipOutput) {
        List<InterfaceIpv4> out = new ArrayList<>();
        if (ipOutput == null || ipOutput.isEmpty()) {
            return out;
        }
        String currentIface = "";
        for (String line : ipOutput.split("\r?\n")) {
            Matcher ifaceMatcher = IP_ADDR_IFACE_PATTERN.matcher(line);
            if (ifaceMatcher.find()) {
                currentIface = ifaceMatcher.group(1).trim().toLowerCase(Locale.ROOT);
            }
            Matcher inetMatcher = IP_ADDR_INET4_PATTERN.matcher(line);
            if (inetMatcher.find() && !currentIface.isEmpty()) {
                String ip = inetMatcher.group(1).trim();
                if (!ip.startsWith("127.")) {
                    out.add(new InterfaceIpv4(currentIface, ip));
                }
            }
        }
        return out;
    }

    private static String selectDedicatedSoftApIp(List<InterfaceIpv4> ifaces) {
        String bestGateway = "";
        String bestAny = "";
        for (InterfaceIpv4 entry : ifaces) {
            if (!isDedicatedSoftApInterface(entry.name)) continue;
            if (isLikelyHotspotGatewayIp(entry.ip)) {
                if (bestGateway.isEmpty()) bestGateway = entry.ip;
            } else if (isPrivateIpv4(entry.ip) && bestAny.isEmpty()) {
                bestAny = entry.ip;
            }
        }
        return !bestGateway.isEmpty() ? bestGateway : bestAny;
    }

    private static String selectFallbackSoftApGatewayIp(List<InterfaceIpv4> ifaces) {
        String bestHotspot43 = "";
        String bestWlanGateway = "";
        for (InterfaceIpv4 entry : ifaces) {
            if (!isWlanOrApInterface(entry.name)) continue;
            if (entry.ip.startsWith("192.168.43.") && bestHotspot43.isEmpty()) {
                bestHotspot43 = entry.ip;
            } else if (isLikelyHotspotGatewayIp(entry.ip) && bestWlanGateway.isEmpty()) {
                bestWlanGateway = entry.ip;
            }
        }
        return !bestHotspot43.isEmpty() ? bestHotspot43 : bestWlanGateway;
    }

    static boolean isDedicatedSoftApInterface(String lowerName) {
        if (lowerName == null || lowerName.isEmpty()) return false;
        return lowerName.startsWith("ap")
                || lowerName.startsWith("swlan")
                || lowerName.startsWith("softap")
                || "wlan1".equals(lowerName)
                || "wlan2".equals(lowerName);
    }

    private static boolean isWlanOrApInterface(String lowerName) {
        return isDedicatedSoftApInterface(lowerName) || lowerName.startsWith("wlan");
    }

    private static boolean isLikelyHotspotGatewayIp(String ip) {
        if (!isPrivateIpv4(ip)) return false;
        return ip.startsWith("192.168.43.") || ip.endsWith(".1");
    }

    private static boolean isPrivateIpv4(String ip) {
        if (ip == null) return false;
        return ip.startsWith("192.168.")
                || ip.startsWith("10.")
                || ip.startsWith("172.16.")
                || ip.startsWith("172.17.")
                || ip.startsWith("172.18.")
                || ip.startsWith("172.19.")
                || ip.startsWith("172.2")
                || ip.startsWith("172.30.")
                || ip.startsWith("172.31.");
    }

    static boolean isSoftApReportedEnabled(String statusOutput) {
        if (statusOutput == null || statusOutput.isEmpty()) return false;
        String lower = statusOutput.toLowerCase(Locale.ROOT);
        if (lower.contains("soft ap is enabled")
                || lower.contains("softap is enabled")
                || lower.contains("ap is enabled")
                || lower.contains("wifiapstate: 13")
                || lower.contains("mwifiapstate=13")
                || lower.contains("wifi_ap_state_enabled")) {
            return true;
        }
        return lower.contains("softapmanager") && lower.contains("curstate=startedstate");
    }

    static String extractSoftApSsid(String output) {
        if (output == null || output.isEmpty()) return "";
        String candidateSection = narrowToSoftApSection(output);
        Matcher quoted = SSID_QUOTED_PATTERN.matcher(candidateSection);
        if (quoted.find()) {
            return sanitizeSsid(quoted.group(1));
        }
        Matcher unquoted = SSID_UNQUOTED_PATTERN.matcher(candidateSection);
        if (unquoted.find()) {
            return sanitizeSsid(unquoted.group(1));
        }
        return "";
    }

    private static String narrowToSoftApSection(String output) {
        String lower = output.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("softap");
        if (idx < 0) {
            idx = lower.indexOf("soft ap");
        }
        if (idx < 0) {
            idx = lower.indexOf("ap configuration");
        }
        if (idx >= 0) {
            int end = Math.min(output.length(), idx + 2048);
            return output.substring(idx, end);
        }
        return output;
    }

    private static String sanitizeSsid(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();
        if (cleaned.equalsIgnoreCase("null") || cleaned.equalsIgnoreCase("<unknown ssid>")) {
            return "";
        }
        return cleaned;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
