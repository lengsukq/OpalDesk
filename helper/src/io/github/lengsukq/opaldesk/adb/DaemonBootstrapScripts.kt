package io.github.lengsukq.opaldesk.adb

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Single source of truth for `opaldesk.jar` asset SHA-256 verification/staging and
 * fail-closed daemon bootstrap shell scripts (PC ADB, Wireless ADB, Shizuku/Sui, and Root-to-Shell).
 *
 * 100% OEM-agnostic (zero MIUI/HyperOS AppOps `10021`, zero `SYSTEM_ALERT_WINDOW` overlays) and
 * avoids Toybox-incompatible `unzip -tq` (broken on Android 11-14 / early 15) in favor of
 * `PK` magic check + 256-bit SHA-256 verification + `app_process --verify-helper`.
 */
object DaemonBootstrapScripts {
    private const val TAG = "OpalBootstrapScript"
    private const val ASSET_JAR_NAME = "opaldesk.jar"
    private const val HELPER_PKG = "io.github.lengsukq.opaldesk"
    private const val DAEMON_CLASS = "io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon"
    private const val DST_JAR = "/data/local/tmp/opaldesk.jar"
    private const val DST_LOG = "/data/local/tmp/opaldesk.log"
    private const val DST_PREV_LOG = "/data/local/tmp/opaldesk.prev.log"
    private const val PROC_PAT_SHORT = "^app_process.*OpalDeskDaemon"
    private const val PROC_PAT_FULL = "^/system/bin/app_process.*OpalDeskDaemon"
    private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
    private val SECURE_RANDOM = SecureRandom()

    private val APPOPS_BEST_EFFORT = listOf(
        "appops set $HELPER_PKG ACCESS_RESTRICTED_SETTINGS allow",
        "pm grant $HELPER_PKG android.permission.POST_NOTIFICATIONS",
        "pm grant $HELPER_PKG android.permission.ACCESS_LOCAL_NETWORK",
        "dumpsys deviceidle whitelist +$HELPER_PKG",
    )

    fun randomHex16(): String = ByteArray(8).also { SECURE_RANDOM.nextBytes(it) }
        .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }

    fun currentUserId(): Int = try {
        maxOf(0, Process.myUid() / 100000)
    } catch (_: Throwable) {
        0
    }

    fun computeAssetJarSha256(ctx: Context?): String =
        ctx?.let { hashAssetJar(it, copyTo = null) }.orEmpty()

    fun readAssetJarBytesWithSha256(ctx: Context): Pair<ByteArray, String>? = try {
        ctx.assets.open(ASSET_JAR_NAME).use { input ->
            val bos = ByteArrayOutputStream(96 * 1024)
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                md.update(buf, 0, n)
                bos.write(buf, 0, n)
            }
            val sha = md.digest().joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
            bos.toByteArray() to sha
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read $ASSET_JAR_NAME bytes", t)
        null
    }

    fun stageBridgeJarWithSha256(ctx: Context): Pair<String, String>? {
        val outFile = File(ctx.filesDir, "opaldesk_stage.jar")
        if (outFile.exists()) {
            runCatching { outFile.setWritable(true, true); outFile.delete() }
        }
        val sha = hashAssetJar(ctx, copyTo = outFile) ?: return null
        outFile.setReadable(false, false)
        outFile.setReadable(true, true)
        // Android 17 (API 37) enforces read-only permissions on staged DEX/code archives.
        outFile.setWritable(false, false)
        return outFile.absolutePath to sha
    }

    private fun hashAssetJar(ctx: Context, copyTo: File?): String? = try {
        ctx.assets.open(ASSET_JAR_NAME).use { input ->
            val out = copyTo?.let { FileOutputStream(it, false) }
            out.use { fileOut ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    md.update(buf, 0, n)
                    fileOut?.write(buf, 0, n)
                }
                fileOut?.flush()
                md.digest().joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read/stage $ASSET_JAR_NAME", t)
        null
    }

    /**
     * Multi-user & multi-split APK `pm path` fallback extraction when direct `adb push` or stdin
     * staging cannot populate the temp jar.
     */
    internal fun buildMultiApkUnzipFallback(userId: Int, dstExpr: String): String =
        "for p in \$(pm path --user $userId $HELPER_PKG 2>/dev/null | sed -n 's/^package://p' | tr -d '\\r') " +
            "\$(pm path $HELPER_PKG 2>/dev/null | sed -n 's/^package://p' | tr -d '\\r'); do " +
            "if [ -n \"\$p\" ] && [ -f \"\$p\" ] && [ ! -L \"\$p\" ]; then " +
            "unzip -p \"\$p\" assets/$ASSET_JAR_NAME > $dstExpr 2>/dev/null; " +
            "[ -s $dstExpr ] && [ \"\$(dd if=$dstExpr bs=1 count=2 2>/dev/null)\" = \"PK\" ] && break; fi; done"

    /**
     * One-line PC ADB activation command.
     * Zero '$', zero backticks, zero inner double quotes, zero Toybox-incompatible `unzip -tq`:
     * runs identically in Bash, Zsh, sh, PowerShell, and cmd.exe across Android 11-17.
     */
    fun buildPcAdbCommand(): String {
        val newJar = "$DST_JAR.new"
        val srcApk = "/data/local/tmp/opd_src.apk"
        val grants = buildList {
            add("(appops set $HELPER_PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true)")
            APPOPS_BEST_EFFORT.forEach { cmd ->
                val redir = if (cmd.startsWith("dumpsys")) ">/dev/null 2>&1" else "2>/dev/null"
                add("($cmd $redir || true)")
            }
        }.joinToString(" && ")

        val killBlock =
            "((pgrep -f '$PROC_PAT_SHORT' >/dev/null 2>&1 || pgrep -f '$PROC_PAT_FULL' >/dev/null 2>&1) && " +
                "(pkill -f '$PROC_PAT_SHORT' 2>/dev/null; pkill -f '$PROC_PAT_FULL' 2>/dev/null; true) && sleep 1 && " +
                "(pkill -f -l KILL '$PROC_PAT_SHORT' 2>/dev/null; pkill -f -l KILL '$PROC_PAT_FULL' 2>/dev/null; true) || true)"

        return "adb shell \"" +
            "rm -f $srcApk $newJar && " +
            "pm path $HELPER_PKG | grep '^package:' | cut -d: -f2 | head -n1 | xargs cat > $srcApk && " +
            "test -s $srcApk && " +
            "unzip -p $srcApk assets/$ASSET_JAR_NAME > $newJar && " +
            "rm -f $srcApk && " +
            "test -s $newJar && " +
            "chmod 444 $newJar && " +
            "timeout 6 env CLASSPATH=$newJar app_process /data/local/tmp $DAEMON_CLASS --verify-helper >/dev/null 2>&1 && " +
            "(timeout 8 env CLASSPATH=$newJar app_process /data/local/tmp $DAEMON_CLASS --stop >/dev/null 2>&1 || true) && " +
            "$killBlock && " +
            "rm -f $DST_JAR && " +
            "mv -f $newJar $DST_JAR && " +
            "test -s $DST_JAR && " +
            "$grants && " +
            "rm -f $DST_PREV_LOG && " +
            "((test -f $DST_LOG && test ! -L $DST_LOG && test -s $DST_LOG && umask 022 && (set -C; tail -n 240 $DST_LOG > $DST_PREV_LOG) && chmod 644 $DST_PREV_LOG) 2>/dev/null || rm -f $DST_PREV_LOG) && " +
            "rm -f $DST_LOG && " +
            "(CLASSPATH=$DST_JAR nohup app_process /data/local/tmp $DAEMON_CLASS >$DST_LOG 2>&1 </dev/null &)\""
    }

    /**
     * Post-push Wireless ADB shell script: uses the already-pushed [remoteTmp] (Tier 1/2) or falls
     * back to multi-user/multi-split `pm path` extraction (Tier 3), then verifies PK + SHA-256,
     * swaps atomically, and launches the daemon.
     */
    fun buildWirelessPostPushScript(
        remoteTmp: String,
        pushedOk: Boolean,
        userId: Int = currentUserId(),
        expectedSha256: String = "",
    ): String {
        val qTmp = remoteTmp.replace("'", "")
        val fallbackExtract = buildMultiApkUnzipFallback(userId, "'$qTmp'")
        val ensureStaged = if (pushedOk) {
            "if [ ! -f '$qTmp' ] || [ -L '$qTmp' ] || [ ! -s '$qTmp' ]; then " +
                "rm -f '$qTmp' 2>/dev/null; $fallbackExtract; fi; "
        } else {
            "rm -f '$qTmp' 2>/dev/null; $fallbackExtract; "
        }
        return ensureStaged + buildShellSwapAndLaunchScript("'$qTmp'", expectedSha256)
    }

    /**
     * Standalone Wireless ADB shell bootstrap script (Tier 3 pure-shell fallback) that extracts
     * `opaldesk.jar` from installed split APKs into an exclusive nonce temp file before swapping.
     */
    fun buildWirelessAdbBootstrapScript(expectedSha256: String = "", userId: Int = currentUserId()): String {
        val tmpPath = "/data/local/tmp/opd_${randomHex16()}.jar"
        return "if ! (umask 022; set -C; : > '$tmpPath') 2>/dev/null || [ -L '$tmpPath' ]; then " +
            "rm -f '$tmpPath' 2>/dev/null; echo ERR_TMP_SYMLINK; exit 21; fi; " +
            buildMultiApkUnzipFallback(userId, "'$tmpPath'") + "; " +
            buildShellSwapAndLaunchScript("'$tmpPath'", expectedSha256)
    }

    /**
     * Shizuku Step 1 script: writes raw jar bytes received on stdin into an exclusive `O_EXCL` temp file.
     */
    fun buildShizukuStageScript(remoteTmp: String): String {
        val qTmp = remoteTmp.replace("'", "")
        return "rm -f '$qTmp' 2>/dev/null; " +
            "if ! (umask 022; set -C; cat > '$qTmp') 2>/dev/null || [ -L '$qTmp' ] || [ ! -s '$qTmp' ]; then " +
            "rm -f '$qTmp' 2>/dev/null; echo ERR_SHIZUKU_STDIN; exit 21; fi; " +
            "chmod 444 '$qTmp' 2>/dev/null; " +
            "chown -h 2000:2000 '$qTmp' 2>/dev/null || true; " +
            "echo STAGE_OK"
    }

    /**
     * Shizuku Step 2 script: handles both Shizuku ADB (`uid=2000`) and Sui Root (`uid=0`, drops to
     * `uid=2000` `u:r:shell:s0`), verifying PK + SHA-256 before atomic swap and launch.
     */
    fun buildShizukuLaunchScript(
        remoteTmp: String,
        stdinStaged: Boolean,
        expectedSha256: String,
        userId: Int = currentUserId(),
    ): String {
        val qTmp = remoteTmp.replace("'", "")
        val fallbackExtract = buildMultiApkUnzipFallback(userId, "'$qTmp'")
        val ensureStaged = if (stdinStaged) {
            "if [ ! -f '$qTmp' ] || [ -L '$qTmp' ] || [ ! -s '$qTmp' ]; then " +
                "rm -f '$qTmp' 2>/dev/null; $fallbackExtract; fi; "
        } else {
            "rm -f '$qTmp' 2>/dev/null; $fallbackExtract; "
        }
        val qShellInner = buildShellSwapAndLaunchScript("'$qTmp'", expectedSha256).replace("'", "'\\''")
        return ensureStaged +
            "UID_NOW=\$(id -u 2>/dev/null || echo 2000); " +
            "if [ \"\$UID_NOW\" = \"0\" ]; then " +
            "chmod 444 '$qTmp' 2>/dev/null; chown -h 2000:2000 '$qTmp' 2>/dev/null || true; " +
            "run_as_shell() { " +
            "su 2000 -c \"\$1\" && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
            "su shell -c \"\$1\" 2>/dev/null && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
            "su 2000 /system/bin/sh -c \"\$1\" 2>/dev/null && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
            "/system/bin/sh -c \"\$1\"; " +
            "}; " +
            "run_as_shell '$qShellInner'; RC=\$?; " +
            "else " +
            "/system/bin/sh -c '$qShellInner'; RC=\$?; " +
            "fi; " +
            "rm -f '$qTmp' 2>/dev/null; exit \$RC"
    }

    /**
     * Root-to-shell bootstrap script: Root ONLY copies & SHA-256 verifies a nonce temp jar
     * (with multi-user/split APK fallback for isolated Mount Namespaces), optionally applies
     * benign global multi-window flags (`--root-enhance`), chowns 2000:2000, and drops privileges
     * to uid 2000 (`u:r:shell:s0`) before running `app_process`.
     */
    fun buildRootBootstrapScript(
        stagedPath: String,
        expectedSha256: String,
        userId: Int = currentUserId(),
        rootEnhance: Boolean = false,
    ): String {
        val qStage = stagedPath.replace("'", "")
        val expSha = expectedSha256.lowercase(Locale.US)
        val rootTmp = "/data/local/tmp/opd_${randomHex16()}.jar"
        val qShellInner = buildShellSwapAndLaunchScript("'$rootTmp'", expSha, rootEnhance).replace("'", "'\\''")
        val fallbackExtract = buildMultiApkUnzipFallback(userId, "'$rootTmp'")
        val preDropEnhance = if (rootEnhance) {
            "settings put global force_resizable_activities 1 >/dev/null 2>&1 || true; " +
                "settings put global enable_non_resizable_multi_window 1 >/dev/null 2>&1 || true; "
        } else {
            ""
        }

        return "UID_NOW=\$(id -u 2>/dev/null || echo -1); " +
            "if [ \"\$UID_NOW\" != \"0\" ]; then echo NO_ROOT:\$UID_NOW; exit 12; fi; " +
            "if ! (umask 022; set -C; : > '$rootTmp') 2>/dev/null || [ -L '$rootTmp' ]; then " +
            "rm -f '$rootTmp' 2>/dev/null; echo ERR_MKTEMP; exit 21; fi; " +
            "if [ -f '$qStage' ] && [ ! -L '$qStage' ] && [ -s '$qStage' ]; then " +
            "cat '$qStage' > '$rootTmp' 2>/dev/null; fi; " +
            "SRC_SHA=\$(sha256sum '$rootTmp' 2>/dev/null | cut -d' ' -f1); " +
            "if [ \"\$SRC_SHA\" != '$expSha' ]; then " +
            "$fallbackExtract; " +
            "SRC_SHA=\$(sha256sum '$rootTmp' 2>/dev/null | cut -d' ' -f1); " +
            "fi; " +
            "if [ \"\$SRC_SHA\" != '$expSha' ] || [ \"\$(dd if='$rootTmp' bs=1 count=2 2>/dev/null)\" != \"PK\" ]; then " +
            "rm -f '$rootTmp' 2>/dev/null; echo ERR_STAGE_HASH:\$SRC_SHA; exit 21; fi; " +
            "chmod 444 '$rootTmp' && chown -h 2000:2000 '$rootTmp' " +
            "|| { rm -f '$rootTmp' 2>/dev/null; echo ERR_STAGE_CHOWN; exit 21; }; " +
            preDropEnhance +
            "run_as_shell() { " +
            "su 2000 -c \"\$1\" && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
            "su shell -c \"\$1\" 2>/dev/null && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
            "su 2000 /system/bin/sh -c \"\$1\" 2>/dev/null && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
            "/system/bin/sh -c \"\$1\"; " +
            "}; " +
            "run_as_shell '$qShellInner'; RC=\$?; " +
            "rm -f '$rootTmp' 2>/dev/null; exit \$RC"
    }

    internal fun buildShellSwapAndLaunchScript(
        tmpJarExpr: String,
        expectedSha256: String? = null,
        rootEnhance: Boolean = false,
    ): String {
        val cleanSha = expectedSha256?.trim()?.lowercase(Locale.US).orEmpty()
        val hasSha = SHA256_HEX_REGEX.matches(cleanSha)
        val daemonArgs = if (rootEnhance) "$DAEMON_CLASS --root-enhance" else DAEMON_CLASS
        val shaGuard = if (hasSha) {
            "ACT_SHA=\$(sha256sum $tmpJarExpr 2>/dev/null | cut -d' ' -f1); " +
                "if [ -n \"\$ACT_SHA\" ] && [ \"\$ACT_SHA\" != '$cleanSha' ]; then " +
                "rm -f $tmpJarExpr; echo ERR_JAR_SHA256; exit 21; fi; "
        } else {
            ""
        }
        val postSwapShaGuard = if (hasSha) {
            "FINAL_SHA=\$(sha256sum $DST_JAR 2>/dev/null | cut -d' ' -f1); " +
                "if [ -n \"\$FINAL_SHA\" ] && [ \"\$FINAL_SHA\" != '$cleanSha' ]; then " +
                "echo ERR_FINAL_SHA256; exit 22; fi; "
        } else {
            ""
        }
        val shellGrants = buildList {
            add("appops set $HELPER_PKG POST_NOTIFICATION allow 2>/dev/null")
            add("appops set $HELPER_PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null")
            APPOPS_BEST_EFFORT.forEach { cmd ->
                val redir = if (cmd.startsWith("dumpsys")) ">/dev/null 2>&1" else "2>/dev/null"
                add("$cmd $redir")
            }
        }.joinToString("; ")

        val aliveExpr = "(pgrep -f '$PROC_PAT_SHORT' >/dev/null 2>&1 || pgrep -f '$PROC_PAT_FULL' >/dev/null 2>&1)"
        val killExpr = "(pkill -f '$PROC_PAT_SHORT' 2>/dev/null || true); (pkill -f '$PROC_PAT_FULL' 2>/dev/null || true); " +
            "sleep 1.0; " +
            "(pkill -f -l KILL '$PROC_PAT_SHORT' 2>/dev/null || true); (pkill -f -l KILL '$PROC_PAT_FULL' 2>/dev/null || true)"

        return "if [ ! -f $tmpJarExpr ] || [ -L $tmpJarExpr ] || [ ! -s $tmpJarExpr ] " +
            "|| [ \"\$(dd if=$tmpJarExpr bs=1 count=2 2>/dev/null)\" != \"PK\" ]; then " +
            "rm -f $tmpJarExpr; echo ERR_STAGE_JAR; exit 21; fi; " +
            shaGuard +
            "chmod 444 $tmpJarExpr || { rm -f $tmpJarExpr; echo ERR_CHMOD_JAR; exit 21; }; " +
            "(CLASSPATH=$tmpJarExpr /system/bin/app_process /data/local/tmp $DAEMON_CLASS --stop >/dev/null 2>&1 || true); " +
            "if $aliveExpr; then $killExpr; fi; " +
            "rm -f $DST_JAR; " +
            "if ! mv -f $tmpJarExpr $DST_JAR || [ -L $DST_JAR ] || [ ! -s $DST_JAR ]; then " +
            "rm -f $tmpJarExpr; echo ERR_SWAP_JAR; exit 22; fi; " +
            postSwapShaGuard +
            "chown -h 2000:2000 $DST_JAR 2>/dev/null; " +
            "rm -f $DST_PREV_LOG 2>/dev/null; " +
            "if [ -f $DST_LOG ] && [ ! -L $DST_LOG ] && [ -s $DST_LOG ]; then " +
            "if (umask 022; set -C; tail -n 240 $DST_LOG > $DST_PREV_LOG) 2>/dev/null && [ -f $DST_PREV_LOG ] && [ ! -L $DST_PREV_LOG ]; then " +
            "chmod 644 $DST_PREV_LOG 2>/dev/null; chown -h 2000:2000 $DST_PREV_LOG 2>/dev/null || true; " +
            "else rm -f $DST_PREV_LOG 2>/dev/null; fi; fi; " +
            "LOG_DST=/dev/null; rm -f $DST_LOG 2>/dev/null; " +
            "if (umask 022; set -C; : > $DST_LOG) 2>/dev/null && [ -f $DST_LOG ] && [ ! -L $DST_LOG ]; then " +
            "chmod 644 $DST_LOG 2>/dev/null; chown -h 2000:2000 $DST_LOG 2>/dev/null; LOG_DST=$DST_LOG; fi; " +
            "$shellGrants; " +
            "CUR_CTX=\$(id -Z 2>/dev/null || echo ''); " +
            "case \"\$CUR_CTX\" in " +
            "*:shell:*) " +
            "(CLASSPATH=$DST_JAR setsid /system/bin/app_process /data/local/tmp $daemonArgs </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.6 ;; " +
            "*) " +
            "(CLASSPATH=$DST_JAR setsid runcon u:r:shell:s0 /system/bin/sh -c \"exec /system/bin/app_process /data/local/tmp $daemonArgs\" </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.6; " +
            "if ! $aliveExpr; then " +
            "(CLASSPATH=$DST_JAR setsid /system/bin/app_process /data/local/tmp $daemonArgs </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.6; fi ;; " +
            "esac; " +
            "if ! $aliveExpr; then " +
            "(CLASSPATH=$DST_JAR nohup app_process /data/local/tmp $daemonArgs </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.6; fi; " +
            "if $aliveExpr; then echo LAUNCH_DONE; else echo ERR_DAEMON_DIED; [ \"\$LOG_DST\" != \"/dev/null\" ] && cat \"\$LOG_DST\" 2>/dev/null; exit 24; fi"
    }

    /**
     * Read-only diagnostic tail command executed over an already-authorized shell channel
     * (Shizuku, local Wireless ADB, or opted-in Root) when Binder IPC dies or fails, bounded so
     * stdout fits well inside the 8 KiB shell runner capture budget.
     */
    fun buildPostmortemDiagCommand(): String {
        val aliveExpr = "(pgrep -f '$PROC_PAT_SHORT' >/dev/null 2>&1 || pgrep -f '$PROC_PAT_FULL' >/dev/null 2>&1)"
        return "echo '===OPAL_DAEMON_ALIVE==='; " +
            "if $aliveExpr; then echo 1; else echo 0; fi; " +
            "echo '===OPAL_CRASH_LOGCAT==='; " +
            "(logcat -b crash -d -v threadtime -t 60 2>/dev/null || true); " +
            "echo '===OPAL_CUR_LOG==='; " +
            "if [ -f $DST_LOG ] && [ ! -L $DST_LOG ]; then tail -n 60 $DST_LOG 2>/dev/null || true; fi; " +
            "echo '===OPAL_PREV_LOG==='; " +
            "if [ -f $DST_PREV_LOG ] && [ ! -L $DST_PREV_LOG ]; then tail -n 40 $DST_PREV_LOG 2>/dev/null || true; fi"
    }
}
