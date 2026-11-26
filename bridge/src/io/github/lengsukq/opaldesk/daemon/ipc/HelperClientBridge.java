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
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cert-pinned IPC bridge to {@code io.github.lengsukq.opaldesk}: SHA-256 certificate verification,
 * caller trust checks, ContentProvider Binder push, death linking, and coalesced scene-state sync.
 */
public final class HelperClientBridge {
    private static final long HELPER_UID_CACHE_MS = 5000L;
    private static final long SHELL_RETRY_COOLDOWN_MS = 1200L;
    private static final long PUSH_RATE_LIMIT_MS = 600L;
    private static final long TICK_PUSH_INTERVAL_MS = 2500L;
    private static final long PM_SHELL_TIMEOUT_MS = 1500L;

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

    private static final class ResolvedHelper {
        final int uid;
        final int userId;
        final String apkPath;
        final boolean viaShellFallback;

        ResolvedHelper(int uid, int userId, String apkPath, boolean viaShellFallback) {
            this.uid = uid;
            this.userId = userId;
            this.apkPath = apkPath;
            this.viaShellFallback = viaShellFallback;
        }
    }

    public HelperClientBridge(AndroidServices services, DaemonConfig config) {
        this.services = services;
        this.config = config;
    }

    public static boolean isTrustedDaemonServerUid(int uid) {
        return uid == 0 || uid == 1000 || uid == 2000;
    }

    public static int verifyHelperUid(Context ctx) {
        if (ctx == null) return -1;
        List<Context> contexts = collectContexts(ctx, null);
        StringBuilder diag = new StringBuilder();
        ResolvedHelper resolved = resolveAndVerifyHelper(contexts, null, 0, true, diag);
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
        List<Context> contexts = collectContexts(services.context, services.systemContext);
        if (cachedUid > 0 && (now - lastHelperUidVerifyMs) < HELPER_UID_CACHE_MS) {
            if (verifiedViaShellFallback) {
                return cachedUid;
            }
            int fastUid = resolvePackageUidBinderOnly(contexts, getIPackageManagerQuietly(), cachedUser, null);
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
                int fastUid = resolvePackageUidBinderOnly(contexts, getIPackageManagerQuietly(), cachedUser, null);
                if (fastUid == cachedUid) {
                    return cachedUid;
                }
            }
            boolean allowShell = (now - lastShellFallbackMs) >= SHELL_RETRY_COOLDOWN_MS;
            if (allowShell) {
                lastShellFallbackMs = now;
            }
            StringBuilder diag = new StringBuilder();
            ResolvedHelper resolved = resolveAndVerifyHelper(contexts, services, cachedUser, allowShell, diag);
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
        if (helperUid <= 0) return false;
        List<Context> contexts = collectContexts(services.context, services.systemContext);
        if (contexts.isEmpty()) return false;
        int userId = verifiedHelperUserId;
        Object iPm = getIPackageManagerQuietly();
        StringBuilder diag = new StringBuilder();

        for (Context ctx : contexts) {
            try {
                PackageManager pm = ctx.getPackageManager();
                ProviderInfo pi = resolveProviderInfo(pm, userId);
                if (pi != null) {
                    boolean match = isMatchingProviderInfo(pi, helperUid);
                    if (!match) {
                        appendDiag(diag, "pmProvMismatch:" + pi.packageName + "/" + (pi.applicationInfo != null ? pi.applicationInfo.uid : -1));
                        lastLookupDiag = diag.toString();
                    }
                    return match;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                appendDiag(diag, "pmProvErr:" + HelperPackageResolver.summarizeThrowable(e));
            }
        }

        if (iPm != null) {
            try {
                ProviderInfo pi = resolveProviderInfoViaIPm(iPm, userId);
                if (pi != null) {
                    boolean match = isMatchingProviderInfo(pi, helperUid);
                    if (!match) {
                        appendDiag(diag, "ipmProvMismatch:" + pi.packageName);
                        lastLookupDiag = diag.toString();
                    }
                    return match;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                appendDiag(diag, "ipmProvErr:" + HelperPackageResolver.summarizeThrowable(e));
            }
        }

        // Fallback when resolveContentProvider is filtered by OEM visibility rules: verify declared
        // provider authority from the verified helper's own PackageInfo or installed base APK.
        if (hasProviderAuthorityInVerifiedPackage(contexts, iPm, userId, verifiedHelperApkPath, diag)) {
            return true;
        }
        lastLookupDiag = diag.toString();
        return false;
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
     * Synchronously commits UTF-8 text into the currently focused Android {@code InputConnection}
     * via the cert-pinned Helper process's built-in {@code OpalDeskImeService}.
     *
     * @return {@code true} only when {@code OpalDeskImeService} held an active editable
     * {@code InputConnection} and committed the text cleanly without touching the clipboard.
     */
    public boolean commitTextViaBridgeIme(String text) {
        if (config.shuttingDown || !isHelperAlive() || text == null || text.isEmpty() || text.length() > 65_536) {
            return false;
        }
        Bundle extras = new Bundle();
        extras.putString(BridgeProtocol.EXTRA_IME_COMMIT_TEXT, text);
        Bundle res = callHelperProvider(BridgeProtocol.PROVIDER_METHOD_IME_COMMIT_TEXT, extras);
        return res != null && res.getBoolean(BridgeProtocol.EXTRA_IME_COMMITTED, false);
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
        String userFlag = (verifiedHelperUserId > 0) ? String.valueOf(verifiedHelperUserId) : null;
        // Standard AOSP background & notification grants (zero SYSTEM_ALERT_WINDOW or MIUI ops needed!)
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
        Context svcCtx = services.context != null ? services.context : services.systemContext;
        if (svcCtx != null && verifiedHelperUserId <= 0) {
            try {
                Intent svc = new Intent().setComponent(new ComponentName(
                        BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.HELPER_SERVICE_CLASS));
                if (Build.VERSION.SDK_INT >= 26) svcCtx.startForegroundService(svc);
                else svcCtx.startService(svc);
            } catch (RuntimeException ignored) {
            }
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

    private static List<Context> collectContexts(Context primary, Context secondary) {
        List<Context> list = new ArrayList<>(2);
        if (primary != null) list.add(primary);
        if (secondary != null && secondary != primary) list.add(secondary);
        return list;
    }

    private static Object getIPackageManagerQuietly() {
        return AndroidServices.getServiceInterface("package", "android.content.pm.IPackageManager$Stub");
    }

    private static ResolvedHelper resolveAndVerifyHelper(
            List<Context> contexts,
            AndroidServices services,
            int preferredUserId,
            boolean allowShellFallback,
            StringBuilder diag) {
        Object iPm = getIPackageManagerQuietly();
        Set<Integer> users = collectCandidateUserIds(contexts, services, preferredUserId, allowShellFallback, diag);
        for (int userId : users) {
            ResolvedHelper candidate = verifyHelperForUser(contexts, iPm, userId, allowShellFallback, diag);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static Set<Integer> collectCandidateUserIds(
            List<Context> contexts,
            AndroidServices services,
            int preferredUserId,
            boolean allowShellFallback,
            StringBuilder diag) {
        Set<Integer> users = new LinkedHashSet<>();
        if (preferredUserId > 0) users.add(preferredUserId);
        users.add(0);

        if (services != null) {
            List<ActivityManager.RunningAppProcessInfo> procs = services.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo info : procs) {
                    if (info != null && BridgeBuildConfig.HELPER_PACKAGE.equals(info.processName)) {
                        int procUser = HelperPackageResolver.extractUserIdFromUid(info.uid);
                        if (procUser >= 0) users.add(procUser);
                    }
                }
            }
            int curUser = getCurrentUserIdQuietly(services.iActivityManager);
            if (curUser >= 0) users.add(curUser);
        }

        boolean userListResolved = false;
        for (Context ctx : contexts) {
            try {
                Object um = ctx.getSystemService(Context.USER_SERVICE);
                if (um == null) continue;
                Object rawUsers = invokeGetUsers(um);
                if (rawUsers instanceof List<?>) {
                    for (Object u : (List<?>) rawUsers) {
                        if (u == null) continue;
                        int userId = u.getClass().getField("id").getInt(u);
                        if (userId >= 0) users.add(userId);
                    }
                    userListResolved = true;
                    break;
                }
            } catch (Throwable t) {
                appendDiag(diag, "umUsers:" + HelperPackageResolver.summarizeThrowable(t));
            }
        }

        if (!userListResolved && allowShellFallback) {
            ShellCommand.Result res = ShellCommand.runWithTimeout(PM_SHELL_TIMEOUT_MS, "pm", "list", "users");
            if (res.isSuccess()) {
                for (int uidUser : HelperPackageResolver.parseUserIdsFromPmListUsers(res.output)) {
                    users.add(uidUser);
                }
            } else {
                appendDiag(diag, "pmUsersRc:" + res.exitCode);
            }
        }
        return users;
    }

    private static Object invokeGetUsers(Object um) throws ReflectiveOperationException {
        try {
            return um.getClass().getMethod("getUsers").invoke(um);
        } catch (NoSuchMethodException ignored) {
            return um.getClass().getMethod("getUsers", boolean.class).invoke(um, true);
        }
    }

    private static int getCurrentUserIdQuietly(Object iam) {
        if (iam == null) return -1;
        try {
            Object res = iam.getClass().getMethod("getCurrentUser").invoke(iam);
            if (res instanceof Integer) return (Integer) res;
            if (res != null) return res.getClass().getField("id").getInt(res);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static ResolvedHelper verifyHelperForUser(
            List<Context> contexts,
            Object iPm,
            int userId,
            boolean allowShellFallback,
            StringBuilder diag) {
        int curUid = resolvePackageUidBinderOnly(contexts, iPm, userId, diag);
        int effectiveUserId = userId;
        boolean viaShell = false;

        if (curUid <= 0 && allowShellFallback) {
            curUid = resolvePackageUidViaPmShell(userId, diag);
            if (curUid > 0) {
                viaShell = true;
                int uidUser = HelperPackageResolver.extractUserIdFromUid(curUid);
                if (uidUser >= 0) effectiveUserId = uidUser;
            }
        }

        String apkPath = null;
        PackageInfo pi = resolveSigningPackageInfoBinder(contexts, iPm, effectiveUserId, diag);
        HelperPackageResolver.CertCheckResult certCheck = verifyPackageInfoCertificate(pi);

        if (certCheck.status == HelperPackageResolver.CertCheckStatus.CERT_MISMATCH
                || certCheck.status == HelperPackageResolver.CertCheckStatus.MULTI_SIGNER_REJECTED) {
            logSecurityReject(certCheck);
            appendDiag(diag, "u" + effectiveUserId + ":" + certCheck.status.name());
            return null;
        }

        if (certCheck.status != HelperPackageResolver.CertCheckStatus.VERIFIED && allowShellFallback) {
            List<String> apkPaths = resolveApkPathsViaPmShell(effectiveUserId, diag);
            for (String candidatePath : apkPaths) {
                if (!isTrustedReadableApkFile(candidatePath, diag)) continue;
                PackageInfo archivePi = resolveArchivePackageInfo(contexts, candidatePath, diag);
                if (archivePi == null) {
                    appendDiag(diag, "archiveNull");
                    continue;
                }
                if (!BridgeBuildConfig.HELPER_PACKAGE.equals(archivePi.packageName)) {
                    appendDiag(diag, "archivePkgMismatch:" + archivePi.packageName);
                    continue;
                }
                HelperPackageResolver.CertCheckResult archiveCheck = verifyPackageInfoCertificate(archivePi);
                if (archiveCheck.status == HelperPackageResolver.CertCheckStatus.CERT_MISMATCH
                        || archiveCheck.status == HelperPackageResolver.CertCheckStatus.MULTI_SIGNER_REJECTED) {
                    logSecurityReject(archiveCheck);
                    appendDiag(diag, "u" + effectiveUserId + ":archive_" + archiveCheck.status.name());
                    return null;
                }
                if (archiveCheck.status == HelperPackageResolver.CertCheckStatus.VERIFIED) {
                    certCheck = archiveCheck;
                    apkPath = candidatePath;
                    viaShell = true;
                    break;
                }
            }
        }

        if (certCheck.status != HelperPackageResolver.CertCheckStatus.VERIFIED) {
            if (curUid > 0) {
                appendDiag(diag, "u" + effectiveUserId + ":cert=" + certCheck.status.name());
            }
            return null;
        }

        if (curUid <= 0 && pi != null && pi.applicationInfo != null && pi.applicationInfo.uid > 0) {
            curUid = pi.applicationInfo.uid;
        }
        if (curUid <= 0 && allowShellFallback) {
            curUid = resolvePackageUidViaPmShell(effectiveUserId, diag);
            if (curUid > 0) viaShell = true;
        }
        if (curUid <= 0) {
            appendDiag(diag, "u" + effectiveUserId + ":uidMissingAfterCert");
            return null;
        }
        if (pi != null && pi.applicationInfo != null && pi.applicationInfo.uid > 0
                && pi.applicationInfo.uid != curUid) {
            appendDiag(diag, "u" + effectiveUserId + ":uidMismatch:" + curUid + "!=" + pi.applicationInfo.uid);
            return null;
        }
        if (apkPath == null && pi != null && pi.applicationInfo != null) {
            String srcDir = pi.applicationInfo.sourceDir;
            if (HelperPackageResolver.isTrustedInstalledApkPath(srcDir)) {
                apkPath = srcDir;
            }
        }
        return new ResolvedHelper(curUid, effectiveUserId, apkPath, viaShell);
    }

    private static int resolvePackageUidBinderOnly(
            List<Context> contexts, Object iPm, int userId, StringBuilder diag) {
        for (Context ctx : contexts) {
            try {
                PackageManager pm = ctx.getPackageManager();
                int uid = resolvePackageUid(pm, BridgeBuildConfig.HELPER_PACKAGE, userId);
                if (uid > 0) return uid;
            } catch (PackageManager.NameNotFoundException e) {
                appendDiag(diag, "u" + userId + ":pmNotFound");
            } catch (RuntimeException e) {
                appendDiag(diag, "u" + userId + ":pmUidErr=" + HelperPackageResolver.summarizeThrowable(e));
            }
        }
        if (iPm != null) {
            try {
                int uid = resolvePackageUidViaIPm(iPm, BridgeBuildConfig.HELPER_PACKAGE, userId);
                if (uid > 0) return uid;
            } catch (Throwable t) {
                appendDiag(diag, "u" + userId + ":ipmUidErr=" + HelperPackageResolver.summarizeThrowable(t));
            }
        }
        return -1;
    }

    private static PackageInfo resolveSigningPackageInfoBinder(
            List<Context> contexts, Object iPm, int userId, StringBuilder diag) {
        int[] flagAttempts = {
                PackageManager.GET_SIGNING_CERTIFICATES,
                PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES,
        };
        for (int flags : flagAttempts) {
            for (Context ctx : contexts) {
                try {
                    PackageInfo pi = resolvePackageInfo(ctx.getPackageManager(), BridgeBuildConfig.HELPER_PACKAGE, flags, userId);
                    if (pi != null && (pi.signingInfo != null || (pi.signatures != null && pi.signatures.length > 0))) {
                        return pi;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    appendDiag(diag, "u" + userId + ":piNotFound");
                } catch (RuntimeException e) {
                    appendDiag(diag, "u" + userId + ":pmPiErr=" + HelperPackageResolver.summarizeThrowable(e));
                }
            }
            if (iPm != null) {
                try {
                    PackageInfo pi = resolvePackageInfoViaIPm(iPm, BridgeBuildConfig.HELPER_PACKAGE, flags, userId);
                    if (pi != null && (pi.signingInfo != null || (pi.signatures != null && pi.signatures.length > 0))) {
                        return pi;
                    }
                } catch (Throwable t) {
                    appendDiag(diag, "u" + userId + ":ipmPiErr=" + HelperPackageResolver.summarizeThrowable(t));
                }
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static HelperPackageResolver.CertCheckResult verifyPackageInfoCertificate(PackageInfo pi) {
        if (pi == null) {
            return new HelperPackageResolver.CertCheckResult(
                    HelperPackageResolver.CertCheckStatus.MISSING_SIGNING_INFO, "");
        }
        SigningInfo si = pi.signingInfo;
        byte[][] apkSigners = null;
        boolean hasMultiple = false;
        if (si != null) {
            hasMultiple = si.hasMultipleSigners();
            Signature[] raw = si.getApkContentsSigners();
            if (raw != null) {
                apkSigners = new byte[raw.length][];
                for (int i = 0; i < raw.length; i++) {
                    apkSigners[i] = (raw[i] != null) ? raw[i].toByteArray() : null;
                }
            }
        }
        byte[][] legacySigners = null;
        if (pi.signatures != null) {
            legacySigners = new byte[pi.signatures.length][];
            for (int i = 0; i < pi.signatures.length; i++) {
                legacySigners[i] = (pi.signatures[i] != null) ? pi.signatures[i].toByteArray() : null;
            }
        }
        return HelperPackageResolver.verifySignerDigests(
                si != null,
                hasMultiple,
                apkSigners,
                legacySigners,
                BridgeBuildConfig.EXPECTED_HELPER_CERT_SHA256);
    }

    private static void logSecurityReject(HelperPackageResolver.CertCheckResult res) {
        System.err.println("OPALDESK_SECURITY_REJECT untrusted "
                + BridgeBuildConfig.HELPER_PACKAGE
                + " reason=" + res.status.name()
                + (res.actualSha256 != null && !res.actualSha256.isEmpty() ? " cert=" + res.actualSha256 : ""));
    }

    private static int resolvePackageUidViaPmShell(int userId, StringBuilder diag) {
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                PM_SHELL_TIMEOUT_MS,
                "pm", "list", "packages", "-U", "--user", String.valueOf(userId), BridgeBuildConfig.HELPER_PACKAGE);
        int uid = res.isSuccess()
                ? HelperPackageResolver.parsePackageUidFromPmListPackages(res.output, BridgeBuildConfig.HELPER_PACKAGE)
                : -1;
        if (uid <= 0 && userId == 0) {
            ShellCommand.Result fallback = ShellCommand.runWithTimeout(
                    PM_SHELL_TIMEOUT_MS, "pm", "list", "packages", "-U", BridgeBuildConfig.HELPER_PACKAGE);
            if (fallback.isSuccess()) {
                uid = HelperPackageResolver.parsePackageUidFromPmListPackages(
                        fallback.output, BridgeBuildConfig.HELPER_PACKAGE);
            } else {
                appendDiag(diag, "pmListRc:" + fallback.exitCode);
            }
        } else if (!res.isSuccess()) {
            appendDiag(diag, "pmListU" + userId + "Rc:" + res.exitCode);
        }
        return uid;
    }

    private static List<String> resolveApkPathsViaPmShell(int userId, StringBuilder diag) {
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                PM_SHELL_TIMEOUT_MS,
                "pm", "path", "--user", String.valueOf(userId), BridgeBuildConfig.HELPER_PACKAGE);
        List<String> paths = res.isSuccess()
                ? HelperPackageResolver.parseApkPathsFromPmPath(res.output)
                : new ArrayList<>();
        if (paths.isEmpty() && userId == 0) {
            ShellCommand.Result fallback = ShellCommand.runWithTimeout(
                    PM_SHELL_TIMEOUT_MS, "pm", "path", BridgeBuildConfig.HELPER_PACKAGE);
            if (fallback.isSuccess()) {
                paths = HelperPackageResolver.parseApkPathsFromPmPath(fallback.output);
            } else {
                appendDiag(diag, "pmPathRc:" + fallback.exitCode);
            }
        } else if (!res.isSuccess()) {
            appendDiag(diag, "pmPathU" + userId + "Rc:" + res.exitCode);
        }
        return paths;
    }

    private static boolean isTrustedReadableApkFile(String path, StringBuilder diag) {
        if (!HelperPackageResolver.isTrustedInstalledApkPath(path)) return false;
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            appendDiag(diag, "apkUnreadable");
            return false;
        }
        try {
            String canonical = f.getCanonicalPath();
            if (!HelperPackageResolver.isTrustedInstalledApkPath(canonical)) {
                appendDiag(diag, "apkSymlinkUntrusted");
                return false;
            }
            return true;
        } catch (IOException e) {
            appendDiag(diag, "apkCanonErr");
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo resolveArchivePackageInfo(
            List<Context> contexts, String apkPath, StringBuilder diag) {
        int[] flagAttempts = {
                PackageManager.GET_SIGNING_CERTIFICATES,
                PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES,
        };
        for (int flags : flagAttempts) {
            for (Context ctx : contexts) {
                try {
                    PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apkPath, flags);
                    if (pi != null && (pi.signingInfo != null || (pi.signatures != null && pi.signatures.length > 0))) {
                        return pi;
                    }
                } catch (RuntimeException e) {
                    appendDiag(diag, "archiveErr:" + HelperPackageResolver.summarizeThrowable(e));
                }
            }
        }
        return null;
    }

    private static boolean hasVerifiedProviderAuthority(ProviderInfo[] providers) {
        if (providers == null) return false;
        for (ProviderInfo pi : providers) {
            if (pi != null && HelperPackageResolver.isMatchingProviderAuthority(
                    pi.packageName,
                    pi.authority,
                    pi.enabled,
                    BridgeBuildConfig.HELPER_PACKAGE,
                    BridgeProtocol.HELPER_PROVIDER_AUTHORITY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProviderAuthorityInVerifiedPackage(
            List<Context> contexts, Object iPm, int userId, String apkPath, StringBuilder diag) {
        for (Context ctx : contexts) {
            try {
                PackageInfo pi = resolvePackageInfo(
                        ctx.getPackageManager(), BridgeBuildConfig.HELPER_PACKAGE, PackageManager.GET_PROVIDERS, userId);
                if (pi != null && hasVerifiedProviderAuthority(pi.providers)) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                appendDiag(diag, "pmGetProvErr:" + HelperPackageResolver.summarizeThrowable(e));
            }
        }
        if (iPm != null) {
            try {
                PackageInfo pi = resolvePackageInfoViaIPm(
                        iPm, BridgeBuildConfig.HELPER_PACKAGE, PackageManager.GET_PROVIDERS, userId);
                if (pi != null && hasVerifiedProviderAuthority(pi.providers)) {
                    return true;
                }
            } catch (Throwable t) {
                appendDiag(diag, "ipmGetProvErr:" + HelperPackageResolver.summarizeThrowable(t));
            }
        }
        if (apkPath != null && isTrustedReadableApkFile(apkPath, diag)) {
            for (Context ctx : contexts) {
                try {
                    PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apkPath, PackageManager.GET_PROVIDERS);
                    if (pi != null && hasVerifiedProviderAuthority(pi.providers)) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    appendDiag(diag, "archiveProvErr:" + HelperPackageResolver.summarizeThrowable(e));
                }
            }
        }
        return false;
    }

    private static boolean isMatchingProviderInfo(ProviderInfo pi, int helperUid) {
        return pi != null && pi.applicationInfo != null
                && pi.applicationInfo.uid == helperUid
                && BridgeBuildConfig.HELPER_PACKAGE.equals(pi.packageName);
    }

    private static ProviderInfo resolveProviderInfo(PackageManager pm, int userId)
            throws ReflectiveOperationException {
        if (userId <= 0) {
            return pm.resolveContentProvider(BridgeProtocol.HELPER_PROVIDER_AUTHORITY, 0);
        }
        return (ProviderInfo) pm.getClass()
                .getMethod("resolveContentProviderAsUser", String.class, int.class, int.class)
                .invoke(pm, BridgeProtocol.HELPER_PROVIDER_AUTHORITY, 0, userId);
    }

    private static ProviderInfo resolveProviderInfoViaIPm(Object iPm, int userId)
            throws ReflectiveOperationException {
        try {
            Method m = iPm.getClass().getMethod("resolveContentProvider", String.class, long.class, int.class);
            return (ProviderInfo) m.invoke(iPm, BridgeProtocol.HELPER_PROVIDER_AUTHORITY, 0L, userId);
        } catch (NoSuchMethodException ignored) {
            Method m = iPm.getClass().getMethod("resolveContentProvider", String.class, int.class, int.class);
            return (ProviderInfo) m.invoke(iPm, BridgeProtocol.HELPER_PROVIDER_AUTHORITY, 0, userId);
        }
    }

    private static int resolvePackageUid(PackageManager pm, String pkg, int userId)
            throws PackageManager.NameNotFoundException {
        if (userId <= 0) {
            return pm.getPackageUid(pkg, 0);
        }
        try {
            Method m = pm.getClass().getMethod("getPackageUidAsUser", String.class, int.class);
            Object v = m.invoke(pm, pkg, userId);
            return (v instanceof Integer) ? (Integer) v : -1;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof PackageManager.NameNotFoundException) {
                throw (PackageManager.NameNotFoundException) ite.getCause();
            }
            if (ite.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ite.getCause();
            }
            return -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static int resolvePackageUidViaIPm(Object iPm, String pkg, int userId)
            throws ReflectiveOperationException {
        try {
            Method m = iPm.getClass().getMethod("getPackageUid", String.class, long.class, int.class);
            Object v = m.invoke(iPm, pkg, 0L, userId);
            return (v instanceof Integer) ? (Integer) v : -1;
        } catch (NoSuchMethodException ignored) {
            Method m = iPm.getClass().getMethod("getPackageUid", String.class, int.class, int.class);
            Object v = m.invoke(iPm, pkg, 0, userId);
            return (v instanceof Integer) ? (Integer) v : -1;
        }
    }

    private static PackageInfo resolvePackageInfo(PackageManager pm, String pkg, int flags, int userId)
            throws PackageManager.NameNotFoundException {
        if (userId <= 0) {
            return pm.getPackageInfo(pkg, flags);
        }
        try {
            Method m = pm.getClass().getMethod("getPackageInfoAsUser", String.class, int.class, int.class);
            return (PackageInfo) m.invoke(pm, pkg, flags, userId);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof PackageManager.NameNotFoundException) {
                throw (PackageManager.NameNotFoundException) ite.getCause();
            }
            if (ite.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ite.getCause();
            }
            return null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static PackageInfo resolvePackageInfoViaIPm(Object iPm, String pkg, int flags, int userId)
            throws ReflectiveOperationException {
        try {
            Method m = iPm.getClass().getMethod("getPackageInfo", String.class, long.class, int.class);
            return (PackageInfo) m.invoke(iPm, pkg, (long) flags, userId);
        } catch (NoSuchMethodException ignored) {
            Method m = iPm.getClass().getMethod("getPackageInfo", String.class, int.class, int.class);
            return (PackageInfo) m.invoke(iPm, pkg, flags, userId);
        }
    }

    private static void appendDiag(StringBuilder diag, String part) {
        if (diag == null || part == null || part.isEmpty()) return;
        if (diag.length() > 160) return;
        if (diag.length() > 0) diag.append('|');
        diag.append(part);
    }
}
