package io.github.lengsukq.opaldesk.adb

import android.content.Context
import android.util.Log
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CmdResult(val code: Int, val output: String)

/**
 * Process execution, private local ADB socket management, su discovery, and strict local-device
 * IP allowlist validation for on-device ADB/Root bootstrapping.
 */
object AdbProcessRunner {
    private const val TAG = "ExtAdbProc"
    private const val MAX_OUTPUT_CHARS = 8192

    private val ALLOWED_HOST_REGEX = Regex("^[0-9a-fA-F.:]+$")
    private val SU_CANDIDATE_PATHS = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
        "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
    )

    fun getAdbFile(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libadb.so")

    fun isNativeAdbAvailable(ctx: Context): Boolean = getAdbFile(ctx).let {
        it.exists() && it.canExecute() && (!it.canWrite() || it.absolutePath.startsWith("/data/app/"))
    }

    fun getPrivateAdbSocketFile(ctx: Context): File {
        val sockDir = File(ctx.cacheDir, "asrv").apply {
            if (!exists()) mkdirs()
            setReadable(false, false); setReadable(true, true)
            setWritable(false, false); setWritable(true, true)
            setExecutable(false, false); setExecutable(true, true)
        }
        return File(sockDir, "a.sock")
    }

    fun stopLocalAdbServerSync(appCtx: Context?) {
        if (appCtx == null) return
        if (isNativeAdbAvailable(appCtx)) {
            runLogged("adb disconnect") { runAdb(appCtx, 1500, "disconnect") }
            runLogged("adb kill-server") { runAdb(appCtx, 2500, "kill-server") }
        }
        runLogged("delete private adb socket") {
            getPrivateAdbSocketFile(appCtx).takeIf { it.exists() }?.delete()
        }
    }

    fun hasSuBinaryHint(): Boolean {
        val envDirs = System.getenv("PATH")?.split(':')?.filter { it.isNotEmpty() }.orEmpty()
        val candidates = SU_CANDIDATE_PATHS.map(::File) + envDirs.map { File(it, "su") }
        return candidates.any { f ->
            try { f.exists() } catch (t: Throwable) { Log.d(TAG, "su probe skipped for $f: ${t.message}"); false }
        }
    }

    fun runSu(timeoutMs: Long, shellCmd: String): CmdResult = execProcess(
        pb = ProcessBuilder("su", "-c", shellCmd).redirectErrorStream(true),
        timeoutMs = timeoutMs,
        timeoutLabel = "Root 命令执行超时",
        errorPrefix = "未找到 su 或无 Root 权限",
    )

    fun runAdb(ctx: Context, timeoutMs: Long, vararg args: String): CmdResult =
        runAdbWithStdinFile(ctx, timeoutMs, stdinFile = null, *args)

    fun runAdbWithStdinFile(ctx: Context, timeoutMs: Long, stdinFile: File?, vararg args: String): CmdResult {
        val homeDir = File(ctx.filesDir, "adb_home")
        val dotAndroid = File(homeDir, ".android").apply { if (!exists()) mkdirs() }
        val tmpDir = File(ctx.cacheDir, "adb_tmp").apply { if (!exists()) mkdirs() }
        val sockFile = getPrivateAdbSocketFile(ctx)

        val pb = ProcessBuilder(listOf(getAdbFile(ctx).absolutePath) + args).apply {
            directory(homeDir)
            redirectErrorStream(true)
            environment().apply {
                put("HOME", homeDir.absolutePath)
                put("ANDROID_USER_HOME", dotAndroid.absolutePath)
                put("ANDROID_SDK_HOME", homeDir.absolutePath)
                put("TMPDIR", tmpDir.absolutePath)
                remove("ANDROID_ADB_SERVER_PORT")
                put("ADB_SERVER_SOCKET", "localfilesystem:${sockFile.absolutePath}")
                put("ADB_MDNS", "0")
                put("ADB_MDNS_OPENSCREEN", "0")
            }
        }
        return execProcess(pb, timeoutMs, timeoutLabel = "命令执行超时", errorPrefix = "执行出错", stdinFile = stdinFile)
    }

    /** Connects to `host:port` via local ADB with automatic `127.0.0.1` fallback. Returns `(connectedTarget, errorOutput)`. */
    fun connectAdbTarget(appCtx: Context, host: String, port: Int, timeoutMs: Long = 12000L): Pair<String?, String> {
        val primary = "$host:$port"
        val primaryOut = runAdb(appCtx, timeoutMs, "connect", primary).output.trim()
        if (isAdbConnectSuccess(primaryOut)) return primary to ""
        if (host != "127.0.0.1") {
            val fallback = "127.0.0.1:$port"
            val fbTimeout = timeoutMs.coerceAtMost(10000L)
            if (isAdbConnectSuccess(runAdb(appCtx, fbTimeout, "connect", fallback).output.trim())) {
                return fallback to ""
            }
        }
        return null to primaryOut
    }

    private fun isAdbConnectSuccess(output: String): Boolean {
        val low = output.lowercase(Locale.ROOT)
        return (low.contains("connected to") || low.contains("already connected")) &&
            !low.contains("failed") && !low.contains("cannot connect") && !low.contains("unable to connect")
    }

    fun execProcess(
        pb: ProcessBuilder,
        timeoutMs: Long,
        timeoutLabel: String = "命令执行超时",
        errorPrefix: String = "执行出错",
        stdinFile: File? = null,
    ): CmdResult {
        var proc: Process? = null
        return try {
            val started = pb.start().also { proc = it }
            val writer = if (stdinFile != null && stdinFile.isFile) {
                Thread({
                    try {
                        FileInputStream(stdinFile).use { fis ->
                            started.outputStream.use { pos ->
                                val buf = ByteArray(8192)
                                var n: Int
                                while (fis.read(buf).also { n = it } > 0) {
                                    pos.write(buf, 0, n)
                                }
                                pos.flush()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "Process stdin writer ended: ${t.message}")
                    }
                }, "OpalProcStdin").apply { isDaemon = true; start() }
            } else {
                runLogged("close stdin") { started.outputStream.close() }
                null
            }
            val sb = StringBuilder()
            val reader = Thread({
                try {
                    BufferedReader(InputStreamReader(started.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            if (sb.length < MAX_OUTPUT_CHARS) {
                                if (sb.isNotEmpty()) sb.append('\n')
                                sb.append(line)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Process output reader ended: ${t.message}")
                }
            }, "OpalProcRead").apply { start() }

            if (!started.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                started.destroyForcibly()
                writer?.join(300)
                reader.join(500)
                OpalLogStore.w(TAG, "$timeoutLabel (${timeoutMs}ms)")
                return CmdResult(-1, "$timeoutLabel (${timeoutMs}ms)\n$sb")
            }
            writer?.join(500)
            reader.join(1000)
            CmdResult(started.exitValue(), sb.toString())
        } catch (t: Throwable) {
            proc?.let { runLogged("destroy process") { it.destroyForcibly() } }
            OpalLogStore.w(TAG, errorPrefix, t)
            CmdResult(-1, "$errorPrefix: ${t.message}")
        }
    }

    fun isLocalDeviceAddress(addr: InetAddress?): Boolean {
        if (addr == null) return false
        if (addr.isLoopbackAddress) return true
        try { if (NetworkInterface.getByInetAddress(addr) != null) return true }
        catch (t: Throwable) { Log.d(TAG, "NetworkInterface lookup failed for $addr: ${t.message}") }
        return addr.hostAddress?.substringBefore('%') == getLocalIpv4()
    }

    fun isAllowedLocalHost(rawHost: String?): Boolean {
        var host = rawHost?.trim() ?: return false
        if (host.startsWith("[") && host.endsWith("]") && host.length > 2) host = host.substring(1, host.length - 1)
        host = host.substringBefore('%')
        if (host.isEmpty()) return false
        if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "::1") return true
        if (!ALLOWED_HOST_REGEX.matches(host)) return false
        return try { isLocalDeviceAddress(InetAddress.getByName(host)) }
        catch (t: Throwable) { Log.d(TAG, "Rejected host '$host': ${t.message}"); false }
    }

    fun getLocalIpv4(): String {
        var best: String? = null
        var fallback: String? = null
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name?.lowercase(Locale.ROOT).orEmpty()
                if (name.startsWith("tun") || name.startsWith("utun") || name.startsWith("ppp") || name.startsWith("dummy")) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) return ip
                        if (!name.startsWith("rmnet") && !name.startsWith("ccmni")) { if (best == null) best = ip }
                        else if (fallback == null) fallback = ip
                    }
                }
            }
        } catch (t: Throwable) {
            OpalLogStore.w(TAG, "Failed to enumerate local IPv4 interfaces", t)
        }
        return best ?: fallback ?: "127.0.0.1"
    }

    private inline fun runLogged(step: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.d(TAG, "$step ignored: ${t.message}") }
    }
}
