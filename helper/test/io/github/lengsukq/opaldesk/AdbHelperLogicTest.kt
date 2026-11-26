package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.adb.AdbProcessRunner
import io.github.lengsukq.opaldesk.adb.DaemonBootstrapScripts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class AdbHelperLogicTest {

    @Test
    fun isLocalDeviceAddressAcceptsLoopbackAndLocalNicAndRejectsRemoteAddresses() {
        assertFalse(AdbProcessRunner.isLocalDeviceAddress(null))
        assertTrue(AdbProcessRunner.isLocalDeviceAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(AdbProcessRunner.isLocalDeviceAddress(InetAddress.getByName("127.0.0.2")))
        assertTrue(AdbProcessRunner.isLocalDeviceAddress(InetAddress.getByName("::1")))

        val localIpv4 = AdbProcessRunner.getLocalIpv4()
        assertTrue(
            "Expected getLocalIpv4()=$localIpv4 to be recognized as local",
            AdbProcessRunner.isLocalDeviceAddress(InetAddress.getByName(localIpv4)),
        )

        val nonLocalAddresses = listOf("8.8.8.8", "1.1.1.1", "198.51.100.42", "203.0.113.99", "2001:db8::1")
        for (ip in nonLocalAddresses) {
            assertFalse("Expected $ip to be rejected", AdbProcessRunner.isLocalDeviceAddress(InetAddress.getByName(ip)))
        }

        val foreignPrivateIps = findNonLocalPrivateLanAddresses(localIpv4)
        assertTrue("Expected at least two unused private LAN test IPs", foreignPrivateIps.size >= 2)
        for (addr in foreignPrivateIps) {
            assertFalse(
                "Expected foreign private LAN IP ${addr.hostAddress} to be rejected",
                AdbProcessRunner.isLocalDeviceAddress(addr),
            )
        }
    }

    @Test
    fun isAllowedLocalHostAcceptsLoopbackIpv6AndOwnIpv4AndRejectsForeignOrMalformedHosts() {
        val localIpv4 = AdbProcessRunner.getLocalIpv4()

        val allowed = listOf(
            "127.0.0.1",
            "  127.0.0.1  ",
            "localhost",
            "LOCALHOST",
            "::1",
            "[::1]",
            "[::1%lo0]",
            "::1%lo0",
            localIpv4,
        )
        for (host in allowed) {
            assertTrue("Expected host '$host' to be allowed", AdbProcessRunner.isAllowedLocalHost(host))
        }

        for (ip in findLocalNonLoopbackIpv4Addresses()) {
            assertTrue("Expected local NIC IPv4 '$ip' to be allowed", AdbProcessRunner.isAllowedLocalHost(ip))
        }

        val rejected = buildList {
            add(null)
            add("")
            add("   ")
            add("[]")
            add("[ ]")
            add("example.com")
            add("my-device.local")
            add("127.0.0.1:5555")
            add("http://127.0.0.1")
            add("127.0.0.1; id")
            add("$(whoami)")
            add("127.0.0.1 && echo pwn")
            add("192.168.1.1/24")
            add("8.8.8.8")
            add("198.51.100.42")
            add("2001:db8::1")
            add("[2001:db8::1]")
            findNonLocalPrivateLanAddresses(localIpv4).forEach { add(it.hostAddress) }
        }
        for (host in rejected) {
            assertFalse("Expected host '$host' to be rejected", AdbProcessRunner.isAllowedLocalHost(host))
        }
    }

    @Test
    fun buildPcAdbCommandSatisfiesVendorNeutralCrossShellAndFailClosedInvariants() {
        val cmd = DaemonBootstrapScripts.buildPcAdbCommand()
        assertTrue("Must start with adb shell quote", cmd.startsWith("adb shell \""))
        assertTrue("Must end with closing quote", cmd.endsWith("\""))

        val inner = cmd.removePrefix("adb shell \"").removeSuffix("\"")

        // Cross-shell safety (Bash, Zsh, sh, PowerShell, cmd.exe): no unescaped host expansions or inner quotes.
        assertFalse("PC ADB command must not contain '$' host expansion", inner.contains('$'))
        assertFalse("PC ADB command must not contain backticks", inner.contains('`'))
        assertFalse("PC ADB command must not contain inner double quotes", inner.contains('"'))

        // Vendor-neutral: zero MIUI/HyperOS 10021 or SYSTEM_ALERT_WINDOW overlay dependencies!
        assertFalse("Must not contain MIUI 10021 AppOp", inner.contains("10021"))
        assertFalse("Must not require SYSTEM_ALERT_WINDOW", inner.contains("SYSTEM_ALERT_WINDOW"))

        // Required AOSP permission & privilege grants.
        assertTrue(inner.contains("appops set io.github.lengsukq.opaldesk ACCESS_RESTRICTED_SETTINGS allow"))
        assertTrue(inner.contains("appops set io.github.lengsukq.opaldesk RUN_ANY_IN_BACKGROUND allow"))
        assertTrue(inner.contains("pm grant io.github.lengsukq.opaldesk android.permission.POST_NOTIFICATIONS"))
        assertTrue(inner.contains("pm grant io.github.lengsukq.opaldesk android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(inner.contains("dumpsys deviceidle whitelist +io.github.lengsukq.opaldesk"))
        assertTrue(inner.contains("grep '^package:'"))

        // Must never use Toybox-incompatible `unzip -tq` (broken on Android 11-14 / early 15).
        assertFalse("PC ADB command must not invoke Toybox-incompatible unzip -tq", inner.contains("unzip -tq"))

        // Fail-closed ordering: extract -> --verify-helper -> --stop -> atomic mv swap -> launch.
        val extractIdx = inner.indexOf("assets/opaldesk.jar > ")
        val verifyIdx = inner.indexOf("--verify-helper")
        val stopIdx = inner.indexOf("--stop")
        val swapIdx = inner.indexOf("mv -f ")
        val launchIdx = inner.indexOf("nohup app_process")
        assertTrue("Missing assets/opaldesk.jar extraction", extractIdx >= 0)
        assertTrue("Missing --verify-helper check", verifyIdx > extractIdx)
        assertTrue("--verify-helper must run before --stop", stopIdx > verifyIdx)
        assertTrue("--stop must run before atomic mv swap", swapIdx > stopIdx)
        assertTrue("Atomic swap must precede daemon launch", launchIdx > swapIdx)
    }

    @Test
    fun buildShellSwapAndLaunchScriptEnforcesGuardsAndValidatesSha256() {
        val validUpperSha = "  " + "A1B2C3D4".repeat(8) + "  "
        val expectedLowerSha = "a1b2c3d4".repeat(8)
        val script = DaemonBootstrapScripts.buildShellSwapAndLaunchScript("\"\$TMP_JAR\"", validUpperSha)

        // Vendor-neutral AOSP permissions only.
        assertFalse(script.contains("10021"))
        assertFalse(script.contains("SYSTEM_ALERT_WINDOW"))
        assertTrue(script.contains("appops set io.github.lengsukq.opaldesk ACCESS_RESTRICTED_SETTINGS allow"))
        assertTrue(script.contains("appops set io.github.lengsukq.opaldesk POST_NOTIFICATION allow"))
        assertTrue(script.contains("appops set io.github.lengsukq.opaldesk RUN_ANY_IN_BACKGROUND allow"))
        assertTrue(script.contains("pm grant io.github.lengsukq.opaldesk android.permission.POST_NOTIFICATIONS"))
        assertTrue(script.contains("pm grant io.github.lengsukq.opaldesk android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(script.contains("dumpsys deviceidle whitelist +io.github.lengsukq.opaldesk"))

        // Symlink protection, PK magic check (no unzip -tq), SHA-256 verification, atomic swap, SELinux shell context.
        assertTrue(script.contains("[ -L \"\$TMP_JAR\" ]"))
        assertFalse("Swap script must not invoke Toybox-incompatible unzip -tq", script.contains("unzip -tq"))
        assertTrue(script.contains("dd if=\"\$TMP_JAR\" bs=1 count=2"))
        assertTrue(script.contains("!= \"PK\""))
        assertTrue(script.contains("ERR_JAR_SHA256"))
        assertTrue(script.contains("ERR_FINAL_SHA256"))
        assertTrue(script.contains("'$expectedLowerSha'"))
        assertTrue(script.contains("mv -f \"\$TMP_JAR\" /data/local/tmp/opaldesk.jar"))
        // Must enter u:r:shell:s0 via /system/bin/sh (shell_exec entrypoint), never directly on app_process (zygote_exec).
        assertTrue(script.contains("runcon u:r:shell:s0 /system/bin/sh -c \"exec /system/bin/app_process /data/local/tmp"))
        assertFalse(script.contains("runcon u:r:shell:s0 /system/bin/app_process"))
        assertTrue(script.contains("LAUNCH_DONE"))
        assertTrue(script.contains("ERR_DAEMON_DIED"))

        val untrustedShas = listOf(null, "", "deadbeef", "g".repeat(64), "\$(touch /tmp/pwn)", "'; rm -rf /; '")
        for (badSha in untrustedShas) {
            val sanitized = DaemonBootstrapScripts.buildShellSwapAndLaunchScript("'/data/local/tmp/t.jar'", badSha)
            assertFalse("Malformed SHA '$badSha' must not emit ERR_JAR_SHA256", sanitized.contains("ERR_JAR_SHA256"))
            assertFalse("Malformed SHA '$badSha' must not emit ERR_FINAL_SHA256", sanitized.contains("ERR_FINAL_SHA256"))
            if (!badSha.isNullOrEmpty()) {
                assertFalse("Malformed SHA '$badSha' must not leak into script", sanitized.contains(badSha))
            }
        }
    }

    @Test
    fun wirelessShizukuAndRootBootstrapScriptsPreservePrivilegeDropAndOptionalRootEnhance() {
        val validSha = "0123456789abcdef".repeat(4)

        val wireless = DaemonBootstrapScripts.buildWirelessAdbBootstrapScript(validSha, userId = 10)
        assertTrue(wireless.contains("set -C; : > '/data/local/tmp/opd_"))
        assertTrue(wireless.contains("ERR_TMP_SYMLINK"))
        assertTrue(wireless.contains("ERR_STAGE_JAR"))
        assertTrue(wireless.contains("pm path --user 10 io.github.lengsukq.opaldesk"))
        assertFalse(wireless.contains("unzip -tq"))
        assertFalse(wireless.contains("10021"))
        assertTrue(wireless.contains("'$validSha'"))

        val postPush = DaemonBootstrapScripts.buildWirelessPostPushScript("/data/local/tmp/opd_1234.jar", pushedOk = true, userId = 0, expectedSha256 = validSha)
        assertTrue(postPush.contains("'/data/local/tmp/opd_1234.jar'"))
        assertTrue(postPush.contains("pm path --user 0 io.github.lengsukq.opaldesk"))
        assertFalse(postPush.contains("unzip -tq"))

        val shizukuStage = DaemonBootstrapScripts.buildShizukuStageScript("/data/local/tmp/opd_abcd.jar")
        assertTrue(shizukuStage.contains("set -C; cat > '/data/local/tmp/opd_abcd.jar'"))
        assertTrue(shizukuStage.contains("STAGE_OK"))

        val shizukuLaunch = DaemonBootstrapScripts.buildShizukuLaunchScript("/data/local/tmp/opd_abcd.jar", stdinStaged = true, expectedSha256 = validSha, userId = 0)
        assertTrue(shizukuLaunch.contains("UID_NOW="))
        assertTrue(shizukuLaunch.contains("su 2000 -c"))
        assertTrue(shizukuLaunch.contains("LAUNCH_DONE"))
        assertFalse(shizukuLaunch.contains("unzip -tq"))

        // Root standard (rootEnhance = false)
        val rootPlain = DaemonBootstrapScripts.buildRootBootstrapScript("/data/user/0/app/a'b;id.jar", validSha, userId = 0, rootEnhance = false)
        assertTrue(rootPlain.contains("UID_NOW="))
        assertTrue(rootPlain.contains("ERR_STAGE_HASH:"))
        assertTrue(rootPlain.contains("chown -h 2000:2000"))
        assertTrue(rootPlain.contains("su 2000 -c"))
        assertFalse(rootPlain.contains("--root-enhance"))
        assertFalse("Unstripped single quote must not appear in staged path", rootPlain.contains("a'b;id.jar"))
        assertTrue(rootPlain.contains("'/data/user/0/app/ab;id.jar'"))

        // Root with optional ~5% enhancements (rootEnhance = true)
        val rootEnhanced = DaemonBootstrapScripts.buildRootBootstrapScript("/data/user/0/app/s.jar", validSha, userId = 0, rootEnhance = true)
        assertTrue(rootEnhanced.contains("force_resizable_activities 1"))
        assertTrue(rootEnhanced.contains("--root-enhance"))

        // Bootstrap scripts must never disable system wireless ADB (which can kill adbd's cgroup).
        assertFalse(wireless.contains("adb_wifi_enabled"))
        assertFalse(postPush.contains("adb_wifi_enabled"))
        assertFalse(DaemonBootstrapScripts.buildPcAdbCommand().contains("adb_wifi_enabled"))

        // Bootstrap scripts rotate opaldesk.log -> opaldesk.prev.log with symlink guards, and postmortem stays bounded + symlink-safe.
        assertTrue(DaemonBootstrapScripts.buildPcAdbCommand().contains("/data/local/tmp/opaldesk.prev.log"))
        assertTrue(DaemonBootstrapScripts.buildShellSwapAndLaunchScript("'/data/local/tmp/t.jar'", validSha).contains("/data/local/tmp/opaldesk.prev.log"))
        val postmortemCmd = DaemonBootstrapScripts.buildPostmortemDiagCommand()
        assertTrue(postmortemCmd.contains("===OPAL_DAEMON_ALIVE==="))
        assertTrue(postmortemCmd.contains("===OPAL_CRASH_LOGCAT==="))
        assertTrue(postmortemCmd.contains("===OPAL_CUR_LOG==="))
        assertTrue(postmortemCmd.contains("===OPAL_PREV_LOG==="))
        assertTrue(postmortemCmd.contains("[ ! -L /data/local/tmp/opaldesk.log ]"))
        assertTrue(postmortemCmd.contains("[ ! -L /data/local/tmp/opaldesk.prev.log ]"))
    }

    @Test
    fun formatBinderTimeoutMessageSurfacesOfflineRecoveryHintAndTailLog() {
        val plain = AdbBootstrap.formatBinderTimeoutMessage("")
        assertTrue(plain.contains("守护进程命令已下发"))
        assertFalse(plain.contains("检测到无线调试连接已断开"))
        assertFalse(plain.contains("日志摘要:"))

        val withDaemonTail = AdbBootstrap.formatBinderTimeoutMessage("OPALDESK_LOCK_BUSY")
        assertTrue(withDaemonTail.contains("日志摘要:\nOPALDESK_LOCK_BUSY"))
        assertFalse(withDaemonTail.contains("检测到无线调试连接已断开"))

        val withOffline = AdbBootstrap.formatBinderTimeoutMessage("libadb.so: device offline")
        assertTrue(withOffline.contains("检测到无线调试连接已断开"))
        assertTrue(withOffline.contains("保持「无线调试」开启"))
        assertTrue(withOffline.contains("日志摘要:\nlibadb.so: device offline"))
    }

    private fun findNonLocalPrivateLanAddresses(localIpv4: String): List<InetAddress> {
        val candidates = listOf("10.254.253.252", "172.31.254.253", "192.168.254.253", "192.168.253.254")
        return candidates.mapNotNull { ip ->
            val addr = InetAddress.getByName(ip)
            val boundLocally = try {
                NetworkInterface.getByInetAddress(addr) != null
            } catch (_: Throwable) {
                false
            }
            if (!boundLocally && ip != localIpv4) addr else null
        }
    }

    private fun findLocalNonLoopbackIpv4Addresses(): List<String> {
        val out = mutableListOf<String>()
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Throwable) {
            null
        } ?: return out
        while (ifaces.hasMoreElements()) {
            val nif = ifaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let(out::add)
                }
            }
        }
        return out
    }
}
