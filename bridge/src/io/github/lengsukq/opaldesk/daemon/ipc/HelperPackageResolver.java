package io.github.lengsukq.opaldesk.daemon.ipc;

import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-JVM helpers for fail-closed helper package/user/APK path resolution, certificate digest
 * verification, and ContentProvider authority validation across Android 11-17 and OEM ROMs.
 */
public final class HelperPackageResolver {
    private static final Pattern PM_USER_ID_PATTERN = Pattern.compile("UserInfo\\{(\\d+):");
    private static final Pattern DUMPSYS_SECTION_PACKAGE_PATTERN = Pattern.compile("^Package \\[([^\\]]+)\\].*");
    private static final Pattern DUMPSYS_APP_ID_PATTERN = Pattern.compile("\\b(?:appId|userId)=(\\d+)\\b");
    private static final int PER_USER_UID_RANGE = 100000;
    private static final String[] TRUSTED_APK_PREFIXES = {
            "/data/app/",
            "/mnt/expand/",
            "/system/",
            "/system_ext/",
            "/product/",
            "/vendor/",
            "/apex/",
            "/oem/",
    };

    public enum CertCheckStatus {
        VERIFIED,
        MISSING_SIGNING_INFO,
        MULTI_SIGNER_REJECTED,
        CERT_MISMATCH,
    }

    public static final class CertCheckResult {
        public final CertCheckStatus status;
        public final String actualSha256;

        public CertCheckResult(CertCheckStatus status, String actualSha256) {
            this.status = status;
            this.actualSha256 = actualSha256;
        }
    }

    private HelperPackageResolver() {
    }

    public static int extractUserIdFromUid(int uid) {
        return uid > 0 ? (uid / 100000) : -1;
    }

    /**
     * Parses distinct Android user IDs from {@code pm list users} output, preserving user {@code 0}
     * first if present.
     */
    public static int[] parseUserIdsFromPmListUsers(String output) {
        Set<Integer> ordered = new LinkedHashSet<>();
        if (output != null && !output.isEmpty()) {
            Matcher m = PM_USER_ID_PATTERN.matcher(output);
            while (m.find()) {
                try {
                    int id = Integer.parseInt(m.group(1));
                    if (id >= 0) {
                        ordered.add(id);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        List<Integer> list = new ArrayList<>(ordered.size());
        if (ordered.remove(0)) {
            list.add(0);
        }
        list.addAll(ordered);
        int[] res = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            res[i] = list.get(i);
        }
        return res;
    }

    /**
     * Parses the numeric UID for an exact {@code targetPackage} from {@code pm list packages -U}
     * output (e.g. {@code package:io.github.lengsukq.opaldesk uid:10345}), or {@code -1} if absent.
     */
    public static int parsePackageUidFromPmListPackages(String output, String targetPackage) {
        if (output == null || output.isEmpty() || targetPackage == null || targetPackage.isEmpty()) {
            return -1;
        }
        String prefix = "package:" + targetPackage;
        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (!line.startsWith(prefix)) continue;
            if (line.length() > prefix.length()) {
                char sep = line.charAt(prefix.length());
                if (!Character.isWhitespace(sep)) continue;
            }
            int uidIdx = line.indexOf("uid:");
            if (uidIdx < 0) continue;
            String rest = line.substring(uidIdx + 4).trim();
            int end = 0;
            while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
                end++;
            }
            if (end == 0) continue;
            try {
                int uid = Integer.parseInt(rest.substring(0, end));
                if (uid > 0) return uid;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /**
     * Validates that an APK path returned by {@code pm path} resides strictly inside a system/app
     * installation directory (never {@code /data/local/tmp}, {@code /sdcard}, or traversal paths).
     */
    public static boolean isTrustedInstalledApkPath(String path) {
        if (path == null) return false;
        String trimmed = path.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return false;
        if (trimmed.contains("..") || trimmed.indexOf('\0') >= 0) return false;
        if (!trimmed.toLowerCase(Locale.US).endsWith(".apk")) return false;
        for (String prefix : TRUSTED_APK_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses trusted installed APK paths from {@code pm path} output, prioritizing {@code base.apk}.
     */
    public static List<String> parseApkPathsFromPmPath(String output) {
        List<String> primary = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return primary;
        }
        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("package:")) continue;
            String candidate = line.substring("package:".length()).trim();
            if (!isTrustedInstalledApkPath(candidate)) continue;
            if (candidate.endsWith("/base.apk")) {
                if (!primary.contains(candidate)) primary.add(candidate);
            } else {
                if (!secondary.contains(candidate)) secondary.add(candidate);
            }
        }
        primary.addAll(secondary);
        return primary;
    }

    /**
     * Extracts trusted APK paths for {@code targetPackage} from {@code dumpsys package <pkg>}
     * output when {@code PackageManager} / {@code pm path} are filtered for {@code uid=2000}
     * (e.g. privacy/hide-app hooks or OEM package-visibility rules).
     *
     * <p>Only lines inside the exact {@code Package [<targetPackage>]} block are inspected, and
     * every candidate is validated via {@link #isTrustedInstalledApkPath(String)} before return.
     */
    public static List<String> parseApkPathsFromDumpsysPackage(String output, String targetPackage) {
        List<String> primary = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        if (output == null || output.isEmpty() || targetPackage == null || targetPackage.isEmpty()) {
            return primary;
        }
        boolean inTargetBlock = false;
        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher section = DUMPSYS_SECTION_PACKAGE_PATTERN.matcher(line);
            if (section.matches()) {
                inTargetBlock = targetPackage.equals(section.group(1).trim());
                continue;
            }
            if (!inTargetBlock) continue;
            if (line.startsWith("Queries:") || line.startsWith("Packages:") || line.startsWith("Shared users:")) {
                inTargetBlock = false;
                continue;
            }
            String candidate = extractDumpsysApkCandidate(line);
            if (candidate == null || !isTrustedInstalledApkPath(candidate)) continue;
            if (candidate.endsWith("/base.apk")) {
                if (!primary.contains(candidate)) primary.add(candidate);
            } else {
                if (!secondary.contains(candidate)) secondary.add(candidate);
            }
        }
        primary.addAll(secondary);
        return primary;
    }

    private static String extractDumpsysApkCandidate(String trimmedLine) {
        String rawPath = null;
        if (trimmedLine.startsWith("codePath=")) {
            rawPath = trimmedLine.substring("codePath=".length()).trim();
        } else if (trimmedLine.startsWith("resourcePath=")) {
            rawPath = trimmedLine.substring("resourcePath=".length()).trim();
        } else if (trimmedLine.startsWith("path:")) {
            rawPath = trimmedLine.substring("path:".length()).trim();
        }
        if (rawPath == null || rawPath.isEmpty()) return null;
        int ws = rawPath.indexOf(' ');
        if (ws > 0) {
            rawPath = rawPath.substring(0, ws).trim();
        }
        if (!rawPath.startsWith("/") || rawPath.contains("..")) return null;
        if (rawPath.toLowerCase(Locale.US).endsWith(".apk")) {
            return rawPath;
        }
        // Android 11+ dumpsys prints directory codePath=/data/app/~~xxx==/pkg-yyy==
        while (rawPath.endsWith("/") && rawPath.length() > 1) {
            rawPath = rawPath.substring(0, rawPath.length() - 1);
        }
        return rawPath + "/base.apk";
    }

    /**
     * Extracts the Linux UID for {@code targetPackage} under {@code userId} from
     * {@code dumpsys package <pkg>} ({@code appId=} or {@code userId=} inside the exact package
     * block), returning {@code -1} if absent or out of range.
     */
    public static int parseUidFromDumpsysPackage(String output, String targetPackage, int userId) {
        if (output == null || output.isEmpty() || targetPackage == null || targetPackage.isEmpty() || userId < 0) {
            return -1;
        }
        boolean inTargetBlock = false;
        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher section = DUMPSYS_SECTION_PACKAGE_PATTERN.matcher(line);
            if (section.matches()) {
                inTargetBlock = targetPackage.equals(section.group(1).trim());
                continue;
            }
            if (!inTargetBlock) continue;
            if (line.startsWith("Queries:") || line.startsWith("Packages:") || line.startsWith("Shared users:")) {
                inTargetBlock = false;
                continue;
            }
            Matcher m = DUMPSYS_APP_ID_PATTERN.matcher(line);
            if (m.find()) {
                try {
                    int appId = Integer.parseInt(m.group(1));
                    if (appId > 0 && appId < PER_USER_UID_RANGE) {
                        return userId * PER_USER_UID_RANGE + appId;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    /**
     * Fail-closed single-signer SHA-256 certificate verification using {@code SigningInfo} signers
     * first, falling back to legacy {@code PackageInfo.signatures} only when {@code SigningInfo} is
     * absent.
     */
    public static CertCheckResult verifySignerDigests(
            boolean hasSigningInfo,
            boolean hasMultipleSigners,
            byte[][] apkContentsSigners,
            byte[][] legacySignatures,
            String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isEmpty()) {
            return new CertCheckResult(CertCheckStatus.CERT_MISMATCH, "");
        }
        byte[][] signersToCheck;
        if (hasSigningInfo) {
            if (hasMultipleSigners) {
                return new CertCheckResult(CertCheckStatus.MULTI_SIGNER_REJECTED, "");
            }
            if (apkContentsSigners == null || apkContentsSigners.length == 0) {
                return new CertCheckResult(CertCheckStatus.MISSING_SIGNING_INFO, "");
            }
            if (apkContentsSigners.length != 1 || apkContentsSigners[0] == null) {
                return new CertCheckResult(CertCheckStatus.MULTI_SIGNER_REJECTED, "");
            }
            signersToCheck = apkContentsSigners;
        } else {
            if (legacySignatures == null || legacySignatures.length == 0) {
                return new CertCheckResult(CertCheckStatus.MISSING_SIGNING_INFO, "");
            }
            if (legacySignatures.length != 1 || legacySignatures[0] == null) {
                return new CertCheckResult(CertCheckStatus.MULTI_SIGNER_REJECTED, "");
            }
            signersToCheck = legacySignatures;
        }

        String actualHex = CryptoHex.sha256Hex(signersToCheck[0]);
        if (!expectedSha256.equalsIgnoreCase(actualHex)) {
            return new CertCheckResult(CertCheckStatus.CERT_MISMATCH, actualHex);
        }
        return new CertCheckResult(CertCheckStatus.VERIFIED, actualHex);
    }

    /**
     * Verifies that a single provider entry is enabled, owned by {@code expectedPackage}, and
     * declares {@code expectedAuthority} (supporting semicolon-separated authority lists).
     */
    public static boolean isMatchingProviderAuthority(
            String packageName,
            String authority,
            boolean enabled,
            String expectedPackage,
            String expectedAuthority) {
        if (!enabled || packageName == null || authority == null
                || expectedPackage == null || expectedAuthority == null) {
            return false;
        }
        if (!expectedPackage.equals(packageName)) {
            return false;
        }
        for (String auth : authority.split(";")) {
            if (expectedAuthority.equals(auth.trim())) {
                return true;
            }
        }
        return false;
    }

    public static String summarizeThrowable(Throwable t) {
        if (t == null) return "unknown";
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String cls = cur.getClass().getSimpleName();
        String msg = cur.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return cls;
        }
        String clean = msg.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 80) {
            clean = clean.substring(0, 80);
        }
        return cls + ":" + clean;
    }
}
