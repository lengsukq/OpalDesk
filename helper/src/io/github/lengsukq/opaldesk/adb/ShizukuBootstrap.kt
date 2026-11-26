package io.github.lengsukq.opaldesk.adb

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Privileged shell bootstrap integration via Shizuku / Sui (`dev.rikka.shizuku:api:13.1.5`).
 * Coexists alongside built-in Wireless Debugging (`libadb.so`), Root (`su`), and PC ADB.
 */
object ShizukuBootstrap {
    private const val TAG = "OpalDeskShizuku"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_WEBSITE = "https://shizuku.rikka.app/"
    const val REQUEST_CODE_PERMISSION = 3908

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenersAttached = false

    @Volatile
    private var stateObserver: (() -> Unit)? = null

    @Volatile
    private var permCallback: ((Boolean) -> Unit)? = null

    data class ShellResult(val code: Int, val output: String)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        OpalLogStore.i(TAG, "Shizuku binder received (uid=${getRemoteUid()}, granted=${isPermissionGranted()})")
        val obs = stateObserver
        if (obs != null) mainHandler.post(obs)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        OpalLogStore.w(TAG, "Shizuku binder dead")
        val obs = stateObserver
        if (obs != null) mainHandler.post(obs)
    }

    private val permResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE_PERMISSION) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        OpalLogStore.i(TAG, "Shizuku permission result: granted=$granted")
        mainHandler.post {
            stateObserver?.invoke()
            permCallback?.invoke(granted)
        }
    }

    @Synchronized
    fun bindUiCallbacks(onStateChanged: () -> Unit, onPermissionResult: (Boolean) -> Unit) {
        stateObserver = onStateChanged
        permCallback = onPermissionResult
        if (!listenersAttached) {
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permResultListener)
                listenersAttached = true
            } catch (t: Throwable) {
                OpalLogStore.w(TAG, "Failed to attach Shizuku listeners", t)
            }
        } else {
            mainHandler.post(onStateChanged)
        }
    }

    @Synchronized
    fun unbindUiCallbacks() {
        stateObserver = null
        permCallback = null
    }

    fun isShizukuInstalled(ctx: Context?): Boolean {
        if (ctx == null) return false
        return try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isPreV11(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.isPreV11()
    } catch (_: Throwable) {
        false
    }

    fun isPermissionGranted(): Boolean = try {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun isReadyAndGranted(): Boolean = isBinderAlive() && isPermissionGranted()

    fun shouldShowRationale(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.shouldShowRequestPermissionRationale()
    } catch (_: Throwable) {
        false
    }

    fun getRemoteUid(): Int = try {
        if (Shizuku.pingBinder()) Shizuku.getUid() else -1
    } catch (_: Throwable) {
        -1
    }

    fun buildStateSummary(ctx: Context): String {
        if (isBinderAlive()) {
            if (isPreV11()) {
                return "当前 Shizuku 版本过低（低于 v11），请升级 Shizuku 后重试。"
            }
            val uid = getRemoteUid()
            val mode = if (uid == 0) "Root / Sui 模式" else "ADB Shell 模式 · uid=${if (uid > 0) uid else 2000}"
            if (isPermissionGranted()) {
                return "Shizuku 服务运行中（$mode）· 已授权，可免配对码一键激活"
            }
            if (shouldShowRationale()) {
                return "Shizuku 服务运行中（$mode）· 曾在弹窗中设为不再询问，请打开 Shizuku 手动勾选 OpalDesk"
            }
            return "Shizuku 服务运行中（$mode）· 点击下方按钮授权后自动激活"
        }
        if (isShizukuInstalled(ctx)) {
            return "已安装 Shizuku，但后台服务尚未启动。点击「打开 Shizuku」启动后再返回即可一键激活。"
        }
        return "未检测到 Shizuku / Sui 服务。若设备已常驻 Shizuku 可免输配对码秒级激活；未安装也可直接使用「无线调试」。"
    }

    fun requestPermissionIfPossible(): Boolean = try {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
            Shizuku.shouldShowRequestPermissionRationale() -> false
            else -> {
                Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
                true
            }
        }
    } catch (t: Throwable) {
        OpalLogStore.w(TAG, "requestPermission failed", t)
        false
    }

    fun openShizukuAppOrWebsite(ctx: Context?): Boolean {
        if (ctx == null) return false
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchOnDisplay0(ctx, launch)
                return true
            }
        } catch (_: Throwable) {}
        try {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_WEBSITE)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchOnDisplay0(ctx, web)
        } catch (_: Throwable) {}
        return false
    }

    private fun launchOnDisplay0(ctx: Context, intent: Intent) {
        try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(0)
            ctx.startActivity(intent, opts.toBundle())
        } catch (_: Throwable) {
            ctx.startActivity(intent)
        }
    }

    fun execShell(timeoutMs: Long, stdinBytes: ByteArray?, shellCmd: String): ShellResult {
        var proc: IRemoteProcess? = null
        var pfdIn: ParcelFileDescriptor? = null
        var pfdOut: ParcelFileDescriptor? = null
        var pfdErr: ParcelFileDescriptor? = null
        try {
            val binder = Shizuku.getBinder()
            if (binder == null || !binder.pingBinder()) {
                return ShellResult(-1, "Shizuku Binder 不可用，请确认 Shizuku 服务正在运行")
            }
            val svc = IShizukuService.Stub.asInterface(binder)
                ?: return ShellResult(-1, "无法连接到 IShizukuService")
            proc = svc.newProcess(arrayOf("/system/bin/sh", "-c", shellCmd), null, "/data/local/tmp")
                ?: return ShellResult(-1, "Shizuku newProcess 返回 null")

            pfdIn = proc.outputStream
            pfdOut = proc.inputStream
            pfdErr = proc.errorStream

            if (pfdIn != null) {
                if (stdinBytes != null && stdinBytes.isNotEmpty()) {
                    val inRef = pfdIn
                    pfdIn = null
                    Thread({
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(inRef).use { os ->
                                var off = 0
                                while (off < stdinBytes.size) {
                                    val chunk = minOf(8192, stdinBytes.size - off)
                                    os.write(stdinBytes, off, chunk)
                                    off += chunk
                                }
                                os.flush()
                            }
                        } catch (_: Throwable) {}
                    }, "OpalShizukuIn").apply { isDaemon = true; start() }
                } else {
                    closeQuietly(pfdIn)
                    pfdIn = null
                }
            }

            val outBuf = StringBuilder()
            val errBuf = StringBuilder()

            val outReader = pfdOut?.let { outRef ->
                Thread({
                    try {
                        BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(outRef))).use { br ->
                            var ln: String?
                            while (br.readLine().also { ln = it } != null) {
                                if (outBuf.length < 8192) {
                                    if (outBuf.isNotEmpty()) outBuf.append('\n')
                                    outBuf.append(ln)
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }, "OpalShizukuOut").apply { isDaemon = true; start() }
            }

            val errReader = pfdErr?.let { errRef ->
                Thread({
                    try {
                        BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(errRef))).use { br ->
                            var ln: String?
                            while (br.readLine().also { ln = it } != null) {
                                if (errBuf.length < 4096) {
                                    if (errBuf.isNotEmpty()) errBuf.append('\n')
                                    errBuf.append(ln)
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }, "OpalShizukuErr").apply { isDaemon = true; start() }
            }

            val procRef = proc
            val exitHolder = AtomicInteger(-1)
            val latch = CountDownLatch(1)
            Thread({
                try {
                    exitHolder.set(procRef.waitFor())
                } catch (_: Throwable) {
                } finally {
                    latch.countDown()
                }
            }, "OpalShizukuWait").apply { isDaemon = true; start() }

            val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                try { proc.destroy() } catch (_: Throwable) {}
                closeQuietly(pfdOut)
                closeQuietly(pfdErr)
                outReader?.join(300)
                errReader?.join(300)
                return ShellResult(-1, "Shizuku Shell 执行超时 (${timeoutMs}ms)\n${mergeOutErr(outBuf, errBuf)}")
            }

            outReader?.join(700)
            errReader?.join(700)
            closeQuietly(pfdOut)
            closeQuietly(pfdErr)
            return ShellResult(exitHolder.get(), mergeOutErr(outBuf, errBuf))
        } catch (t: Throwable) {
            OpalLogStore.e(TAG, "execShell failed", t)
            closeQuietly(pfdIn)
            closeQuietly(pfdOut)
            closeQuietly(pfdErr)
            try { proc?.destroy() } catch (_: Throwable) {}
            return ShellResult(-1, "Shizuku 执行异常: ${t.message}")
        }
    }

    private fun mergeOutErr(out: StringBuilder, err: StringBuilder): String {
        val sOut = out.toString().trim()
        val sErr = err.toString().trim()
        return when {
            sOut.isEmpty() -> sErr
            sErr.isEmpty() -> sOut
            else -> "$sOut\n$sErr"
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        if (pfd != null) {
            try { pfd.close() } catch (_: Throwable) {}
        }
    }
}
