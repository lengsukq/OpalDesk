package io.github.lengsukq.opaldesk.daemon.ipc;

import android.app.ActivityManager;
import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cert-pinned IPC bridge to {@code io.github.lengsukq.opaldesk}: caller trust caching,
 * ContentProvider Binder push, death linking, and coalesced scene-state sync.
 * Certificate & UID verification logic is delegated to {@link HelperCertVerifier}.
 */
public final class HelperClientBridge {
    private static final long HELPER_UID_CACHE_MS = 5000L;
    private static final long SHELL_RETRY_COOLDOWN_MS = 1200L;
    private static final long PUSH_RATE_LIMIT_MS = 600L;
    private static final long TICK_PUSH_INTERVAL_MS = 2500L;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final ExecutorService binderPushExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OpalBinderPush");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService scenePushExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OpalScenePush");
        t.setDaemon(true);
        return t;
    });

    private final Object pendingPushLock = new Object();
    private String pendingSceneJson;
    private long pendingStateSeq = -1L;
    private boolean scenePushScheduled;

    private final Object uidResolveLock = new Object();
    private volatile IBinder ctlBinder;
    private volatile IBinder helperProcToken;
    private volatile Runnable onHelperDeath;
    private volatile Runnable onHelperBound;
    private volatile int verifiedHelperUid = -1;
    private volatile int verifiedHelperUserId = 0;
    private volatile String verifiedHelperApkPath = null;
    private volatile boolean verifiedViaShellFallback = false;
    private volatile long lastHelperUidVerifyMs = 0L;
    private volatile long lastShellFallbackMs = 0L;
    private volatile long lastPushWarnMs = 0L;
    private volatile String lastLookupDiag = "";
    private volatile boolean preferExternalProviderCall = false;
    private final AtomicLong lastBinderPushAttemptMs = new AtomicLong(0L);

    private final Object pluginUidLock = new Object();
    private volatile int cachedPluginUid = -2; // -2 = unprobed, -1 = absent/untrusted, >0 = verified
    private volatile long lastPluginProbeMs = 0L;

    public HelperClientBridge(AndroidServices services, DaemonConfig config) {
        this.services = services;
        this.config = config;
    }

    public static boolean isTrustedDaemonServerUid(int uid) {
        return uid == 0 || uid == 1000 || uid == 2000;
    }

    public static int verifyHelperUid(Context ctx) {
        if (ctx == null) return -1;
        List<Context> contexts = HelperCertVerifier.collectContexts(ctx, null);
        StringBuilder diag = new StringBuilder();
        HelperCertVerifier.ResolvedHelper resolved =
                HelperCertVerifier.resolveAndVerifyHelper(contexts, null, 0, true, diag);
        if (resolved == null && diag.length() > 0) {
            System.err.println("OPALDESK_VERIFY_HELPER_FAIL: " + diag);
        }
        return resolved != null ? resolved.uid : -1;
    }

    public void setControlBinder(IBinder binder) { this.ctlBinder = binder; }
    public void setOnHelperDeath(Runnable callback) { this.onHelperDeath = callback; }
    public void setOnHelperBound(Runnable callback) { this.onHelperBound = callback; }

    public int getVerifiedHelperUid() {
        if (services.context == null && services.systemContext == null) {
            lastLookupDiag = "ctx=null";
            return -1;
        }
        long now = SystemClock.uptimeMillis();
        int cachedUid = verifiedHelperUid;
        int cachedUser = verifiedHelperUserId;
        List<Context> contexts = HelperCertVerifier.collectContexts(services.context, services.systemContext);
        if (cachedUid > 0 && (now - lastHelperUidVerifyMs) < HELPER_UID_CACHE_MS) {
            if (verifiedViaShellFallback) {
                return cachedUid;
            }
            int fastUid = HelperCertVerifier.resolvePackageUidBinderOnly(
                    contexts, HelperCertVerifier.getIPackageManagerQuietly(), cachedUser, null);
            if (fastUid == cachedUid) {
                return cachedUid;
            }
        }

        synchronized (uidResolveLock) {
            now = SystemClock.uptimeMillis();
            cachedUid = verifiedHelperUid;
            cachedUser = verifiedHelperUserId;
            if (cachedUid > 0 && (now - lastHelperUidVerifyMs) < HELPER_UID_CACHE_MS) {
                if (verifiedViaShellFallback) {
                    return cachedUid;
                }
                int fastUid = HelperCertVerifier.resolvePackageUidBinderOnly(
                        contexts, HelperCertVerifier.getIPackageManagerQuietly(), cachedUser, null);
                if (fastUid == cachedUid) {
                    return cachedUid;
                }
            }
            boolean allowShell = (now - lastShellFallbackMs) >= SHELL_RETRY_COOLDOWN_MS;
            if (allowShell) {
                lastShellFallbackMs = now;
            }
            StringBuilder diag = new StringBuilder();
            HelperCertVerifier.ResolvedHelper resolved =
                    HelperCertVerifier.resolveAndVerifyHelper(contexts, services, cachedUser, allowShell, diag);
            lastLookupDiag = diag.toString();
            if (resolved != null) {
                verifiedHelperUid = resolved.uid;
                verifiedHelperUserId = resolved.userId;
                verifiedHelperApkPath = resolved.apkPath;
                verifiedViaShellFallback = resolved.viaShellFallback;
                lastHelperUidVerifyMs = now;
                return resolved.uid;
            }
            verifiedHelperUid = -1;
            verifiedHelperApkPath = null;
            verifiedViaShellFallback = false;
            return -1;
        }
    }

    public boolean isTrustedCaller(int callingUid) {
        if (callingUid < 0) return false;
        if (callingUid == android.os.Process.myUid() || isTrustedDaemonServerUid(callingUid)) return true;
        int verifiedUid = getVerifiedHelperUid();
        if (verifiedUid > 0 && callingUid == verifiedUid) return true;
        int pluginUid = getVerifiedImePluginUid();
        return pluginUid > 0 && callingUid == pluginUid;
    }

    public void invalidateImePluginCache() {
        synchronized (pluginUidLock) {
            cachedPluginUid = -2;
            lastPluginProbeMs = 0L;
        }
    }

    public boolean isOptionalImePluginVerified() {
        return getVerifiedImePluginUid() > 0;
    }

    public int getVerifiedImePluginUid() {
        if (services.context == null && services.systemContext == null) return -1;
        long now = SystemClock.uptimeMillis();
        int cached = cachedPluginUid;
        if (cached != -2 && (now - lastPluginProbeMs) < HELPER_UID_CACHE_MS) {
            return cached;
        }
        synchronized (pluginUidLock) {
            now = SystemClock.uptimeMillis();
            if (cachedPluginUid != -2 && (now - lastPluginProbeMs) < HELPER_UID_CACHE_MS) {
                return cachedPluginUid;
            }
            List<Context> contexts = HelperCertVerifier.collectContexts(services.context, services.systemContext);
            int resolved = HelperCertVerifier.resolveVerifiedSameCertPackageUid(
                    contexts, BridgeProtocol.OPALDESK_IME_PLUGIN_PACKAGE, verifiedHelperUserId);
            cachedPluginUid = resolved > 0 ? resolved : -1;
            lastPluginProbeMs = now;
            return cachedPluginUid;
        }
    }

    public boolean isHelperAlive() {
        IBinder tok = helperProcToken;
        return tok != null && tok.pingBinder();
    }

    public boolean isHelperProcessRunning() {
        int helperUid = getVerifiedHelperUid();
        if (helperUid <= 0) return false;
        List<ActivityManager.RunningAppProcessInfo> procs = services.getRunningAppProcesses();
        if (procs == null) return false;
        for (ActivityManager.RunningAppProcessInfo info : procs) {
            if (info != null && info.uid == helperUid
                    && BridgeBuildConfig.HELPER_PACKAGE.equals(info.processName)) {
                return true;
            }
        }
        return false;
    }

    public boolean isVerifiedHelperProviderOwner() {
        int helperUid = getVerifiedHelperUid();
        if (helperUid <= 0) return false;
        List<Context> contexts = HelperCertVerifier.collectContexts(services.context, services.systemContext);
        StringBuilder diag = new StringBuilder();
        boolean verified = HelperCertVerifier.isVerifiedHelperProviderOwner(
                contexts, helperUid, verifiedHelperUserId, verifiedHelperApkPath, diag);
        if (!verified && diag.length() > 0) {
            lastLookupDiag = diag.toString();
        }
        return verified;
    }

    public Bundle callHelperProvider(String method, Bundle extras) {
        if (services.context == null || method == null || !isVerifiedHelperProviderOwner()) return null;
        int helperUserId = verifiedHelperUserId;
        if (preferExternalProviderCall || helperUserId > 0) {
            return AndroidServices.callContentProviderExternal(
                    BridgeProtocol.HELPER_PROVIDER_AUTHORITY, helperUserId, method, null, extras);
        }
        Bundle res = null;
        try {
            ContentProviderClient cpc = services.context.getContentResolver()
                    .acquireUnstableContentProviderClient(BridgeProtocol.HELPER_PROVIDER_AUTHORITY);
            if (cpc != null) {
                try { res = cpc.call(method, null, extras); }
                finally { cpc.close(); }
            }
            if (res == null) {
                res = services.context.getContentResolver().call(
                        Uri.parse("content://" + BridgeProtocol.HELPER_PROVIDER_AUTHORITY), method, null, extras);
            }
        } catch (RemoteException | RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Unable to find app for caller")) {
                preferExternalProviderCall = true;
            }
        }
        if ((res == null || !res.getBoolean("ok", false)) && isVerifiedHelperProviderOwner()) {
            res = AndroidServices.callContentProviderExternal(
                    BridgeProtocol.HELPER_PROVIDER_AUTHORITY, verifiedHelperUserId, method, null, extras);
        }
        return res;
    }

    public void enqueuePushBinder(boolean forceImmediate) {
        long now = SystemClock.uptimeMillis();
        if (!forceImmediate && (now - lastBinderPushAttemptMs.get()) < PUSH_RATE_LIMIT_MS) return;
        lastBinderPushAttemptMs.set(now);
        binderPushExec.execute(this::pushBinderToHelper);
    }

    public void maybePushBinderOnTick(long nowMs) {
        if (!isHelperAlive() && (nowMs - lastBinderPushAttemptMs.get() >= TICK_PUSH_INTERVAL_MS)
                && isHelperProcessRunning()) {
            enqueuePushBinder(false);
        }
    }

    public void pushSceneState(String sceneJson, long stateSeq) {
        if (config.shuttingDown || !isHelperAlive() || sceneJson == null) return;
        boolean shouldSchedule;
        synchronized (pendingPushLock) {
            pendingSceneJson = sceneJson;
            pendingStateSeq = stateSeq;
            shouldSchedule = !scenePushScheduled;
            scenePushScheduled = true;
        }
        if (!shouldSchedule) return;
        scenePushExec.execute(() -> {
            while (!config.shuttingDown && isHelperAlive()) {
                String json;
                long seq;
                synchronized (pendingPushLock) {
                    json = pendingSceneJson;
                    seq = pendingStateSeq;
                    pendingSceneJson = null;
                    if (json == null) {
                        scenePushScheduled = false;
                        return;
                    }
                }
                Bundle extras = new Bundle();
                extras.putString(BridgeProtocol.EXTRA_SYNC_SCENE_JSON, json);
                extras.putLong(BridgeProtocol.EXTRA_SYNC_STATE_SEQ, seq);
                callHelperProvider(BridgeProtocol.PROVIDER_METHOD_SYNC_SCENE, extras);
            }
            synchronized (pendingPushLock) {
                pendingSceneJson = null;
                scenePushScheduled = false;
            }
        });
    }

    public void pushPairingEvent(String pairingJson) {
        if (config.shuttingDown) return;
        final String payload = (pairingJson != null) ? pairingJson : "";
        scenePushExec.execute(() -> {
            if (config.shuttingDown) return;
            Bundle extras = new Bundle();
            extras.putString(BridgeProtocol.EXTRA_PAIRING_JSON, payload);
            callHelperProvider(BridgeProtocol.PROVIDER_METHOD_PAIRING_EVENT, extras);
        });
    }

    public void pushImeRequest(int windowId, boolean show, int inputType) {
        if (config.shuttingDown || !isHelperAlive()) return;
        scenePushExec.execute(() -> {
            if (config.shuttingDown || !isHelperAlive()) return;
            Bundle extras = new Bundle();
            extras.putInt(BridgeProtocol.EXTRA_IME_WINDOW_ID, windowId);
            extras.putBoolean(BridgeProtocol.EXTRA_IME_SHOW, show);
            extras.putInt(BridgeProtocol.EXTRA_IME_INPUT_TYPE, inputType);
            callHelperProvider(BridgeProtocol.PROVIDER_METHOD_IME_REQUEST, extras);
        });
    }

    public boolean commitTextViaBridgeIme(String text) {
        if (config.shuttingDown || !isHelperAlive() || text == null || text.isEmpty() || text.length() > 65_536) {
            return false;
        }
        Bundle extras = new Bundle();
        extras.putString(BridgeProtocol.EXTRA_IME_COMMIT_TEXT, text);
        Bundle res = callHelperProvider(BridgeProtocol.PROVIDER_METHOD_IME_COMMIT_TEXT, extras);
        return res != null && res.getBoolean(BridgeProtocol.EXTRA_IME_COMMITTED, false);
    }

    public void pushRemoteStateChanged() {
        if (config.shuttingDown || !isHelperAlive()) return;
        scenePushExec.execute(() -> {
            if (config.shuttingDown || !isHelperAlive()) return;
            callHelperProvider(BridgeProtocol.PROVIDER_METHOD_REMOTE_STATE_CHANGED, new Bundle());
        });
    }

    public void pushNotificationAction(String op, String key, String replyText) {
        dispatchNotificationAction(op, key, replyText, -1);
    }

    public void dispatchNotificationAction(String op, String key, String replyText, int actionIndex) {
        if (config.shuttingDown || op == null || op.isEmpty()) return;
        final String safeOp = op;
        final String safeKey = (key != null) ? key : "";
        final String safeReply = (replyText != null) ? replyText : "";
        final int safeIndex = actionIndex;
        scenePushExec.execute(() -> {
            if (config.shuttingDown) return;
            Bundle extras = new Bundle();
            extras.putString(BridgeProtocol.EXTRA_NOTIF_ACTION_OP, safeOp);
            extras.putString(BridgeProtocol.EXTRA_NOTIF_ACTION_KEY, safeKey);
            extras.putString(BridgeProtocol.EXTRA_NOTIF_ACTION_REPLY_TEXT, safeReply);
            extras.putInt(BridgeProtocol.EXTRA_NOTIF_ACTION_INDEX, safeIndex);
            callHelperProvider(BridgeProtocol.PROVIDER_METHOD_NOTIFICATION_ACTION, extras);
        });
    }

    public boolean dispatchCallAction(String op, String number, String smsText) {
        if (config.shuttingDown || !isHelperAlive() || op == null || op.trim().isEmpty()) {
            return false;
        }
        Bundle extras = new Bundle();
        extras.putString(BridgeProtocol.EXTRA_CALL_ACTION_OP, op.trim());
        extras.putString(BridgeProtocol.EXTRA_CALL_ACTION_NUMBER, number != null ? number : "");
        extras.putString(BridgeProtocol.EXTRA_CALL_ACTION_SMS_TEXT, smsText != null ? smsText : "");
        Bundle res = callHelperProvider(BridgeProtocol.PROVIDER_METHOD_CALL_ACTION, extras);
        if (res == null) {
            return false;
        }
        return res.containsKey("handled") ? res.getBoolean("handled", false) : res.getBoolean("ok", false);
    }

    public void pushMediaAction(String op, long positionMs) {
        if (config.shuttingDown || op == null || op.isEmpty()) return;
        final String safeOp = op;
        final long safePos = Math.max(0L, positionMs);
        scenePushExec.execute(() -> {
            if (config.shuttingDown) return;
            Bundle extras = new Bundle();
            extras.putString(BridgeProtocol.EXTRA_MEDIA_ACTION_OP, safeOp);
            extras.putLong(BridgeProtocol.EXTRA_MEDIA_ACTION_POSITION_MS, safePos);
            callHelperProvider(BridgeProtocol.PROVIDER_METHOD_MEDIA_ACTION, extras);
        });
    }

    public void grantHelperPrivileges() {
        if (getVerifiedHelperUid() <= 0) return;
        String userFlag = (verifiedHelperUserId > 0) ? String.valueOf(verifiedHelperUserId) : null;
        if (userFlag != null) {
            ShellCommand.runOutput("appops", "set", "--user", userFlag, BridgeBuildConfig.HELPER_PACKAGE, "RUN_ANY_IN_BACKGROUND", "allow");
            ShellCommand.runOutput("pm", "grant", "--user", userFlag, BridgeBuildConfig.HELPER_PACKAGE, "android.permission.POST_NOTIFICATIONS");
            if (Build.VERSION.SDK_INT >= 37) {
                ShellCommand.runOutput("pm", "grant", "--user", userFlag, BridgeBuildConfig.HELPER_PACKAGE, "android.permission.ACCESS_LOCAL_NETWORK");
                ShellCommand.runOutput("appops", "set", "--user", userFlag, BridgeBuildConfig.HELPER_PACKAGE, "ACCESS_LOCAL_NETWORK", "allow");
            }
        } else {
            ShellCommand.runOutput("appops", "set", BridgeBuildConfig.HELPER_PACKAGE, "RUN_ANY_IN_BACKGROUND", "allow");
            ShellCommand.runOutput("pm", "grant", BridgeBuildConfig.HELPER_PACKAGE, "android.permission.POST_NOTIFICATIONS");
            if (Build.VERSION.SDK_INT >= 37) {
                ShellCommand.runOutput("pm", "grant", BridgeBuildConfig.HELPER_PACKAGE, "android.permission.ACCESS_LOCAL_NETWORK");
                ShellCommand.runOutput("appops", "set", BridgeBuildConfig.HELPER_PACKAGE, "ACCESS_LOCAL_NETWORK", "allow");
            }
        }
    }

    private void logPushBinderFailureRateLimited(String detail) {
        long now = SystemClock.uptimeMillis();
        if (now - lastPushWarnMs >= 2500L) {
            lastPushWarnMs = now;
            System.err.println("OPALDESK_PUSH_BINDER_FAIL: " + detail);
        }
    }

    private void pushBinderToHelper() {
        IBinder binder = ctlBinder;
        if ((services.context == null && services.systemContext == null) || binder == null || config.shuttingDown) return;
        int helperUid = getVerifiedHelperUid();
        if (helperUid <= 0) {
            String diag = lastLookupDiag;
            logPushBinderFailureRateLimited(
                    "helperUid=" + helperUid + " pkg=" + BridgeBuildConfig.HELPER_PACKAGE
                            + (diag != null && !diag.isEmpty() ? " diag=" + diag : ""));
            return;
        }
        try {
            if (!isVerifiedHelperProviderOwner()) {
                String diag = lastLookupDiag;
                logPushBinderFailureRateLimited(
                        "providerOwnerMismatch user=" + verifiedHelperUserId + " uid=" + helperUid
                                + (diag != null && !diag.isEmpty() ? " diag=" + diag : ""));
                return;
            }
            Bundle b = new Bundle();
            b.putBinder("cb", binder);
            Bundle res = callHelperProvider(BridgeProtocol.PROVIDER_METHOD_SET_BINDER, b);
            if (res != null && res.getBoolean("ok", false)) {
                boolean newProc = (res.getBinder("proc") != null && res.getBinder("proc") != helperProcToken);
                linkHelperProcessToken(res.getBinder("proc"));
                if (newProc) {
                    grantHelperPrivileges();
                }
                Runnable boundCb = onHelperBound;
                if (boundCb != null) boundCb.run();
            } else {
                logPushBinderFailureRateLimited(
                        "setBinderRejected resNull=" + (res == null) + " user=" + verifiedHelperUserId + " uid=" + helperUid);
            }
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_PUSH_BINDER_WARN: " + e.getMessage());
        }
    }

    private void linkHelperProcessToken(IBinder tok) {
        if (tok == null || tok == helperProcToken) return;
        helperProcToken = tok;
        try {
            tok.linkToDeath(() -> {
                if (helperProcToken == tok) {
                    helperProcToken = null;
                    Runnable cb = onHelperDeath;
                    if (cb != null) cb.run();
                }
            }, 0);
        } catch (RemoteException e) {
            if (helperProcToken == tok) helperProcToken = null;
        }
    }
}
