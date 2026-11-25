package io.github.lengsukq.opaldesk.daemon.ipc;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cert-pinned IPC bridge to {@code io.github.lengsukq.opaldesk}: SHA-256 certificate verification,
 * caller trust checks, ContentProvider Binder push, death linking, and coalesced scene-state sync.
 */
public final class HelperClientBridge {
    private static final long HELPER_UID_CACHE_MS = 5000L;
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

    private volatile IBinder ctlBinder;
    private volatile IBinder helperProcToken;
    private volatile Runnable onHelperDeath;
    private volatile Runnable onHelperBound;
    private volatile int verifiedHelperUid = -1;
    private volatile int verifiedHelperUserId = 0;
    private volatile long lastHelperUidVerifyMs = 0L;
    private volatile long lastPushWarnMs = 0L;
    private volatile boolean preferExternalProviderCall = false;
    private final AtomicLong lastBinderPushAttemptMs = new AtomicLong(0L);

    public HelperClientBridge(AndroidServices services, DaemonConfig config) {
        this.services = services;
        this.config = config;
    }

    public static boolean isTrustedDaemonServerUid(int uid) {
        return uid == 0 || uid == 1000 || uid == 2000;
    }

    public static int verifyHelperUid(Context ctx) {
        return verifyHelperUidForUser(ctx, 0);
    }

    private static int verifyHelperUidForUser(Context ctx, int userId) {
        if (ctx == null) return -1;
        try {
            PackageManager pm = ctx.getPackageManager();
            int curUid = resolvePackageUid(pm, BridgeBuildConfig.HELPER_PACKAGE, userId);
            if (curUid <= 0) return -1;
            PackageInfo pi = resolveSigningPackageInfo(pm, BridgeBuildConfig.HELPER_PACKAGE, userId);
            SigningInfo si = (pi != null) ? pi.signingInfo : null;
            if (si == null || si.hasMultipleSigners()) return -1;
            Signature[] sigs = si.getApkContentsSigners();
            if (sigs == null || sigs.length != 1 || sigs[0] == null) return -1;
            String hex = CryptoHex.sha256Hex(sigs[0].toByteArray());
            if (!BridgeBuildConfig.EXPECTED_HELPER_CERT_SHA256.equalsIgnoreCase(hex)) {
                System.err.println("OPALDESK_SECURITY_REJECT untrusted "
                        + BridgeBuildConfig.HELPER_PACKAGE + " cert=" + hex);
                return -1;
            }
            return curUid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_VERIFY_HELPER_ERR: " + e.getMessage());
            return -1;
        }
    }

    private static int resolvePackageUid(PackageManager pm, String pkg, int userId)
            throws PackageManager.NameNotFoundException {
        if (userId <= 0) {
            return pm.getPackageUid(pkg, 0);
        }
        try {
            java.lang.reflect.Method m = pm.getClass().getMethod("getPackageUidAsUser", String.class, int.class);
            Object v = m.invoke(pm, pkg, userId);
            return (v instanceof Integer) ? (Integer) v : -1;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof PackageManager.NameNotFoundException) {
                throw (PackageManager.NameNotFoundException) ite.getCause();
            }
            return -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static PackageInfo resolveSigningPackageInfo(PackageManager pm, String pkg, int userId)
            throws PackageManager.NameNotFoundException {
        if (userId <= 0) {
            return pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
        }
        try {
            java.lang.reflect.Method m = pm.getClass().getMethod(
                    "getPackageInfoAsUser", String.class, int.class, int.class);
            return (PackageInfo) m.invoke(pm, pkg, PackageManager.GET_SIGNING_CERTIFICATES, userId);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof PackageManager.NameNotFoundException) {
                throw (PackageManager.NameNotFoundException) ite.getCause();
            }
            return null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public void setControlBinder(IBinder binder) { this.ctlBinder = binder; }
    public void setOnHelperDeath(Runnable callback) { this.onHelperDeath = callback; }
    public void setOnHelperBound(Runnable callback) { this.onHelperBound = callback; }

    public int getVerifiedHelperUid() {
        if (services.context == null) return -1;
        long now = SystemClock.uptimeMillis();
        int cachedUid = verifiedHelperUid;
        int cachedUser = verifiedHelperUserId;
        try {
            PackageManager pm = services.context.getPackageManager();
            int curUid = -1;
            int resolvedUser = cachedUser;
            try {
                curUid = resolvePackageUid(pm, BridgeBuildConfig.HELPER_PACKAGE, resolvedUser);
            } catch (PackageManager.NameNotFoundException ignored) {
                curUid = -1;
            }
            if (curUid <= 0 && resolvedUser != 0) {
                try {
                    curUid = resolvePackageUid(pm, BridgeBuildConfig.HELPER_PACKAGE, 0);
                    if (curUid > 0) resolvedUser = 0;
                } catch (PackageManager.NameNotFoundException ignored) {
                    curUid = -1;
                }
            }
            if (curUid <= 0) {
                int[] found = findVerifiedHelperInSecondaryUsers(services.context);
                if (found != null) {
                    verifiedHelperUserId = found[0];
                    verifiedHelperUid = found[1];
                    lastHelperUidVerifyMs = now;
                    return verifiedHelperUid;
                }
                verifiedHelperUid = -1;
                return -1;
            }
            if (curUid == cachedUid && (now - lastHelperUidVerifyMs) < HELPER_UID_CACHE_MS) {
                return curUid;
            }
            int checked = verifyHelperUidForUser(services.context, resolvedUser);
            verifiedHelperUserId = resolvedUser;
            verifiedHelperUid = checked;
            lastHelperUidVerifyMs = now;
            return checked;
        } catch (RuntimeException e) {
            verifiedHelperUid = -1;
            return -1;
        }
    }

    private static int[] findVerifiedHelperInSecondaryUsers(Context ctx) {
        try {
            Object um = ctx.getSystemService(Context.USER_SERVICE);
            if (um == null) return null;
            Object rawUsers = um.getClass().getMethod("getUsers").invoke(um);
            if (!(rawUsers instanceof List<?>)) return null;
            for (Object u : (List<?>) rawUsers) {
                if (u == null) continue;
                int userId = u.getClass().getField("id").getInt(u);
                if (userId <= 0) continue;
                int uid = verifyHelperUidForUser(ctx, userId);
                if (uid > 0) {
                    return new int[]{userId, uid};
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public boolean isTrustedCaller(int callingUid) {
        if (callingUid < 0) return false;
        if (callingUid == android.os.Process.myUid() || isTrustedDaemonServerUid(callingUid)) return true;
        int verifiedUid = getVerifiedHelperUid();
        return verifiedUid > 0 && callingUid == verifiedUid;
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
        if (helperUid <= 0 || services.context == null) return false;
        int userId = verifiedHelperUserId;
        try {
            PackageManager pm = services.context.getPackageManager();
            ProviderInfo pi;
            if (userId <= 0) {
                pi = pm.resolveContentProvider(BridgeProtocol.HELPER_PROVIDER_AUTHORITY, 0);
            } else {
                pi = (ProviderInfo) pm.getClass()
                        .getMethod("resolveContentProviderAsUser", String.class, int.class, int.class)
                        .invoke(pm, BridgeProtocol.HELPER_PROVIDER_AUTHORITY, 0, userId);
            }
            return pi != null && pi.applicationInfo != null
                    && pi.applicationInfo.uid == helperUid
                    && BridgeBuildConfig.HELPER_PACKAGE.equals(pi.packageName);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
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

    /**
     * Pushes an interactive wireless pairing approval event (or empty string when cleared) directly
     * into {@code BridgeProvider} so the Helper can immediately present a heads-up notification or
     * in-app consent modal even if it was idling in the background.
     */
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

    /**
     * Pushes a host-side IME bridge request into {@code BridgeProvider} when an app inside an
     * isolated VirtualDisplay requests soft input but Android's cross-display fallback IME fails
     * to appear (e.g. Android 17 {@code RemoteInsetsControlTarget} fallback abort).
     */
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

    /**
     * Signals the Helper UI immediately when an outgoing OpalCast session connects, resizes, or
     * disconnects so Display 0 console, Local DeX guards, and Remote Trackpad update without polling.
     */
    public void pushRemoteStateChanged() {
        if (config.shuttingDown || !isHelperAlive()) return;
        scenePushExec.execute(() -> {
            if (config.shuttingDown || !isHelperAlive()) return;
            callHelperProvider(BridgeProtocol.PROVIDER_METHOD_REMOTE_STATE_CHANGED, new Bundle());
        });
    }

    public void grantHelperPrivileges() {
        if (getVerifiedHelperUid() <= 0) return;
        // Standard AOSP background & notification grants (zero SYSTEM_ALERT_WINDOW or MIUI ops needed!)
        ShellCommand.runOutput("appops", "set", BridgeBuildConfig.HELPER_PACKAGE, "RUN_ANY_IN_BACKGROUND", "allow");
        ShellCommand.runOutput("pm", "grant", BridgeBuildConfig.HELPER_PACKAGE, "android.permission.POST_NOTIFICATIONS");
        if (Build.VERSION.SDK_INT >= 37) {
            ShellCommand.runOutput("pm", "grant", BridgeBuildConfig.HELPER_PACKAGE, "android.permission.ACCESS_LOCAL_NETWORK");
            ShellCommand.runOutput("appops", "set", BridgeBuildConfig.HELPER_PACKAGE, "ACCESS_LOCAL_NETWORK", "allow");
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
        if (services.context == null || binder == null || config.shuttingDown) return;
        int helperUid = getVerifiedHelperUid();
        if (helperUid <= 0) {
            logPushBinderFailureRateLimited("helperUid=" + helperUid + " pkg=" + BridgeBuildConfig.HELPER_PACKAGE);
            return;
        }
        try {
            if (!isVerifiedHelperProviderOwner()) {
                logPushBinderFailureRateLimited("providerOwnerMismatch user=" + verifiedHelperUserId + " uid=" + helperUid);
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
        try {
            Intent svc = new Intent().setComponent(new ComponentName(
                    BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.HELPER_SERVICE_CLASS));
            if (Build.VERSION.SDK_INT >= 26) services.context.startForegroundService(svc);
            else services.context.startService(svc);
        } catch (RuntimeException ignored) {
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
