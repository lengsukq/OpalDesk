package io.github.lengsukq.opaldesk.daemon.ipc;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fail-closed certificate pinning, UID resolution, and ContentProvider authority ownership
 * verification extracted from {@link HelperClientBridge}.
 *
 * <p>Every resolved package UID MUST match {@link BridgeBuildConfig#EXPECTED_HELPER_CERT_SHA256}
 * with single-signer enforcement before being trusted by the shell daemon.
 */
final class HelperCertVerifier {
    private static final long PM_SHELL_TIMEOUT_MS = 1500L;

    static final class ResolvedHelper {
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

    private HelperCertVerifier() {}

    static List<Context> collectContexts(Context primary, Context secondary) {
        List<Context> list = new ArrayList<>(2);
        if (primary != null) list.add(primary);
        if (secondary != null && secondary != primary) list.add(secondary);
        return list;
    }

    static Object getIPackageManagerQuietly() {
        return AndroidServices.getServiceInterface("package", "android.content.pm.IPackageManager$Stub");
    }

    static ResolvedHelper resolveAndVerifyHelper(
            List<Context> contexts,
            AndroidServices services,
            int preferredUserId,
            boolean allowShellFallback,
            StringBuilder diag) {
        Object iPm = getIPackageManagerQuietly();
        Set<Integer> users = collectCandidateUserIds(contexts, services, preferredUserId, allowShellFallback, diag);
        String cachedDumpsysOutput = null;
        boolean dumpsysAttempted = false;
        for (int userId : users) {
            int runningUid = findRunningHelperUidForUser(services, userId);
            ResolvedHelper candidate = verifyHelperForUser(
                    contexts, iPm, userId, runningUid, allowShellFallback, null, diag);
            if (candidate != null) {
                return candidate;
            }
            if (allowShellFallback && !dumpsysAttempted) {
                dumpsysAttempted = true;
                cachedDumpsysOutput = queryDumpsysPackageFallback(diag);
            }
            if (cachedDumpsysOutput != null && !cachedDumpsysOutput.isEmpty()) {
                ResolvedHelper dumpsysCandidate = verifyHelperForUser(
                        contexts, iPm, userId, runningUid, true, cachedDumpsysOutput, diag);
                if (dumpsysCandidate != null) {
                    return dumpsysCandidate;
                }
            }
        }
        return null;
    }

    static int resolveVerifiedSameCertPackageUid(
            List<Context> contexts,
            String pkgName,
            int preferredUserId) {
        Object iPm = getIPackageManagerQuietly();
        int[] users = preferredUserId > 0 ? new int[]{preferredUserId, 0} : new int[]{0};
        for (int userId : users) {
            int uid = -1;
            for (Context ctx : contexts) {
                try {
                    uid = resolvePackageUid(ctx.getPackageManager(), pkgName, userId);
                    if (uid > 0) break;
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
            if (uid <= 0 && iPm != null) {
                try {
                    uid = resolvePackageUidViaIPm(iPm, pkgName, userId);
                } catch (Throwable ignored) {
                }
            }
            PackageInfo pi = null;
            int[] flagAttempts = {
                    PackageManager.GET_SIGNING_CERTIFICATES,
                    PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES,
            };
            for (int flags : flagAttempts) {
                for (Context ctx : contexts) {
                    try {
                        pi = resolvePackageInfo(ctx.getPackageManager(), pkgName, flags, userId);
                        if (pi != null && (pi.signingInfo != null || (pi.signatures != null && pi.signatures.length > 0))) {
                            break;
                        }
                    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                    }
                }
                if (pi != null && (pi.signingInfo != null || (pi.signatures != null && pi.signatures.length > 0))) {
                    break;
                }
                if (iPm != null) {
                    try {
                        pi = resolvePackageInfoViaIPm(iPm, pkgName, flags, userId);
                        if (pi != null && (pi.signingInfo != null || (pi.signatures != null && pi.signatures.length > 0))) {
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            HelperPackageResolver.CertCheckResult check = verifyPackageInfoCertificate(pi);
            if (check.status == HelperPackageResolver.CertCheckStatus.VERIFIED) {
                if (uid <= 0 && pi != null && pi.applicationInfo != null) {
                    uid = pi.applicationInfo.uid;
                }
                if (uid > 0) return uid;
            } else if (check.status == HelperPackageResolver.CertCheckStatus.CERT_MISMATCH
                    || check.status == HelperPackageResolver.CertCheckStatus.MULTI_SIGNER_REJECTED) {
                System.err.println("OPALDESK_SECURITY_REJECT untrusted plugin " + pkgName + " reason=" + check.status.name());
                return -1;
            }
        }
        return -1;
    }

    static boolean isVerifiedHelperProviderOwner(
            List<Context> contexts,
            int helperUid,
            int userId,
            String verifiedHelperApkPath,
            StringBuilder diag) {
        if (helperUid <= 0 || contexts.isEmpty()) return false;
        Object iPm = getIPackageManagerQuietly();

        for (Context ctx : contexts) {
            try {
                PackageManager pm = ctx.getPackageManager();
                ProviderInfo pi = resolveProviderInfo(pm, userId);
                if (pi != null) {
                    boolean match = isMatchingProviderInfo(pi, helperUid);
                    if (!match) {
                        appendDiag(diag, "pmProvMismatch:" + pi.packageName + "/"
                                + (pi.applicationInfo != null ? pi.applicationInfo.uid : -1));
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
                    }
                    return match;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                appendDiag(diag, "ipmProvErr:" + HelperPackageResolver.summarizeThrowable(e));
            }
        }

        return hasProviderAuthorityInVerifiedPackage(contexts, iPm, userId, verifiedHelperApkPath, diag);
    }

    static int resolvePackageUidBinderOnly(
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

    private static int findRunningHelperUidForUser(AndroidServices services, int userId) {
        if (services == null || userId < 0) return -1;
        List<ActivityManager.RunningAppProcessInfo> procs = services.getRunningAppProcesses();
        if (procs == null) return -1;
        for (ActivityManager.RunningAppProcessInfo info : procs) {
            if (info != null && info.uid > 0 && BridgeBuildConfig.HELPER_PACKAGE.equals(info.processName)) {
                if (HelperPackageResolver.extractUserIdFromUid(info.uid) == userId) {
                    return info.uid;
                }
            }
        }
        return -1;
    }

    private static String queryDumpsysPackageFallback(StringBuilder diag) {
        ShellCommand.Result res = ShellCommand.runWithTimeout(
                PM_SHELL_TIMEOUT_MS, "dumpsys", "package", BridgeBuildConfig.HELPER_PACKAGE);
        if (!res.isSuccess()) {
            appendDiag(diag, "dumpsysPkgRc:" + res.exitCode);
            return null;
        }
        if (res.output == null || res.output.trim().isEmpty()) {
            appendDiag(diag, "dumpsysPkgEmpty");
            return null;
        }
        return res.output;
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
            int runningProcessUid,
            boolean allowShellFallback,
            String dumpsysPackageOutput,
            StringBuilder diag) {
        boolean usingDumpsysPass = dumpsysPackageOutput != null && !dumpsysPackageOutput.isEmpty();
        int curUid = usingDumpsysPass ? -1 : resolvePackageUidBinderOnly(contexts, iPm, userId, diag);
        int effectiveUserId = userId;
        boolean viaShell = false;

        if (!usingDumpsysPass && curUid <= 0 && allowShellFallback) {
            curUid = resolvePackageUidViaPmShell(userId, diag);
            if (curUid > 0) {
                viaShell = true;
                int uidUser = HelperPackageResolver.extractUserIdFromUid(curUid);
                if (uidUser >= 0) effectiveUserId = uidUser;
            }
        }
        if (curUid <= 0 && usingDumpsysPass) {
            int dumpsysUid = HelperPackageResolver.parseUidFromDumpsysPackage(
                    dumpsysPackageOutput, BridgeBuildConfig.HELPER_PACKAGE, effectiveUserId);
            if (dumpsysUid > 0) {
                curUid = dumpsysUid;
                viaShell = true;
            }
        }
        if (curUid <= 0 && runningProcessUid > 0) {
            curUid = runningProcessUid;
            viaShell = true;
        }

        String apkPath = null;
        PackageInfo pi = usingDumpsysPass ? null : resolveSigningPackageInfoBinder(contexts, iPm, effectiveUserId, diag);
        HelperPackageResolver.CertCheckResult certCheck = verifyPackageInfoCertificate(pi);

        if (certCheck.status == HelperPackageResolver.CertCheckStatus.CERT_MISMATCH
                || certCheck.status == HelperPackageResolver.CertCheckStatus.MULTI_SIGNER_REJECTED) {
            logSecurityReject(certCheck);
            appendDiag(diag, "u" + effectiveUserId + ":" + certCheck.status.name());
            return null;
        }

        if (certCheck.status != HelperPackageResolver.CertCheckStatus.VERIFIED && allowShellFallback) {
            List<String> apkPaths = usingDumpsysPass
                    ? HelperPackageResolver.parseApkPathsFromDumpsysPackage(
                            dumpsysPackageOutput, BridgeBuildConfig.HELPER_PACKAGE)
                    : resolveApkPathsViaPmShell(effectiveUserId, diag);
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
            if (curUid > 0 && !usingDumpsysPass) {
                appendDiag(diag, "u" + effectiveUserId + ":cert=" + certCheck.status.name());
            }
            return null;
        }

        if (curUid <= 0 && pi != null && pi.applicationInfo != null && pi.applicationInfo.uid > 0) {
            curUid = pi.applicationInfo.uid;
        }
        if (curUid <= 0 && allowShellFallback && !usingDumpsysPass) {
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
        if (runningProcessUid > 0 && runningProcessUid != curUid) {
            appendDiag(diag, "u" + effectiveUserId + ":procUidMismatch:" + curUid + "!=" + runningProcessUid);
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
