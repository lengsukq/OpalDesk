package io.github.lengsukq.opaldesk.daemon.platform;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure JVM-testable, OEM-agnostic policy for discovering, classifying, filtering, and isolating
 * Android secondary-user profiles (Dual Apps / App Cloner / Work Profile / Clone Profile / Private Space).
 *
 * <p>Uses standard AOSP user flags, user types, and shell output formats with zero {@code android.*}
 * compile-time coupling so all classification, filtering, key formatting, and UID math are
 * deterministically verified by JVM JUnit 4 tests.
 */
public final class UserProfileAppPolicy {
    public static final int PRIMARY_USER_ID = 0;
    public static final int PER_USER_UID_RANGE = 100_000;

    // Canonical wire profile types shared across daemon, Android Helper, macOS, Web/Windows, and Receiver.
    public static final String PROFILE_PRIMARY = "";
    public static final String PROFILE_CLONE = "clone";
    public static final String PROFILE_WORK = "work";
    public static final String PROFILE_PRIVATE = "private";
    public static final String PROFILE_SECONDARY = "profile";

    // Standard AOSP android.content.pm.UserInfo flags.
    public static final int FLAG_GUEST = 0x00000004;
    public static final int FLAG_RESTRICTED = 0x00000008;
    public static final int FLAG_MANAGED_PROFILE = 0x00000020;
    public static final int FLAG_DISABLED = 0x00000040;
    public static final int FLAG_QUIET_MODE = 0x00000080;
    public static final int FLAG_DEMO = 0x00000200;

    // Well-known secondary user IDs assigned by mainstream Android implementations for app clones.
    private static final int USER_ID_CLONE_999 = 999; // MIUI/HyperOS, ColorOS, OriginOS, etc.
    private static final int USER_ID_CLONE_95 = 95;   // Samsung Dual Messenger
    private static final int USER_ID_CLONE_128 = 128; // Huawei/Honor App Twin

    // Matches: UserInfo{999:XSpace:800010} running
    private static final Pattern PM_LIST_USER_LINE = Pattern.compile(
            "UserInfo\\{(\\d+):(.*):([0-9a-fA-F]+)\\}(.*)$");

    private UserProfileAppPolicy() {}

    /** Normalized metadata for an Android user profile. */
    public static final class UserProfileInfo {
        public final int userId;
        public final String name;
        public final int flags;
        public final String userType;
        public final boolean running;
        public final boolean quietMode;
        public final String profileType;

        public UserProfileInfo(
                int userId,
                String name,
                int flags,
                String userType,
                boolean running,
                boolean quietMode) {
            this.userId = Math.max(0, userId);
            this.name = (name != null) ? name.trim() : "";
            this.flags = flags;
            this.userType = (userType != null) ? userType.trim() : "";
            this.running = running;
            this.quietMode = quietMode || ((flags & FLAG_QUIET_MODE) != 0);
            this.profileType = classifyProfileType(this.userId, this.name, this.flags, this.userType);
        }

        public boolean isGuest() {
            return (flags & FLAG_GUEST) != 0;
        }

        public boolean isDisabled() {
            return (flags & FLAG_DISABLED) != 0;
        }

        public boolean isDemo() {
            return (flags & FLAG_DEMO) != 0;
        }

        public boolean isRestricted() {
            return (flags & FLAG_RESTRICTED) != 0;
        }
    }

    /** Normalized launchable app entry discovered in a secondary Android user profile. */
    public static final class SecondaryAppEntry {
        public final String pkg;
        public final String cls;
        public final String label;
        public final int userId;
        public final String profileType;

        public SecondaryAppEntry(String pkg, String cls, String label, int userId, String profileType) {
            this.pkg = (pkg != null) ? pkg.trim() : "";
            this.cls = (cls != null) ? cls.trim() : "";
            this.label = (label != null && !label.trim().isEmpty()) ? label.trim() : this.pkg;
            this.userId = Math.max(0, userId);
            this.profileType = normalizeProfileType(profileType, this.userId);
        }

        public String appKey() {
            return formatAppKey(pkg, userId);
        }
    }

    /**
     * Classifies a user profile into a stable cross-platform tag:
     * {@code ""}, {@code "clone"}, {@code "work"}, {@code "private"}, or {@code "profile"}.
     */
    public static String classifyProfileType(int userId, String name, int flags, String userType) {
        if (userId <= PRIMARY_USER_ID) {
            return PROFILE_PRIMARY;
        }
        String ut = (userType != null) ? userType.toUpperCase(Locale.ROOT) : "";
        if (ut.contains("PRIVATE")) {
            return PROFILE_PRIVATE;
        }
        if (ut.contains("CLONE")) {
            return PROFILE_CLONE;
        }
        if ((flags & FLAG_MANAGED_PROFILE) != 0 || ut.contains("MANAGED")) {
            return PROFILE_WORK;
        }
        if (userId == USER_ID_CLONE_999 || userId == USER_ID_CLONE_95 || userId == USER_ID_CLONE_128) {
            return PROFILE_CLONE;
        }
        String n = (name != null) ? name.toLowerCase(Locale.ROOT) : "";
        if (n.contains("clone")
                || n.contains("dual")
                || n.contains("xspace")
                || n.contains("multiapp")
                || n.contains("twin")
                || n.contains("parallel")
                || n.contains("分身")
                || n.contains("双开")
                || n.contains("多开")) {
            return PROFILE_CLONE;
        }
        if (n.contains("work") || n.contains("工作")) {
            return PROFILE_WORK;
        }
        if (n.contains("private") || n.contains("隐私")) {
            return PROFILE_PRIVATE;
        }
        return PROFILE_SECONDARY;
    }

    public static String normalizeProfileType(String raw, int userId) {
        if (userId <= PRIMARY_USER_ID) {
            return PROFILE_PRIMARY;
        }
        if (raw == null || raw.trim().isEmpty()) {
            return classifyProfileType(userId, "", 0, "");
        }
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (PROFILE_CLONE.equals(clean)
                || PROFILE_WORK.equals(clean)
                || PROFILE_PRIVATE.equals(clean)
                || PROFILE_SECONDARY.equals(clean)) {
            return clean;
        }
        return classifyProfileType(userId, clean, 0, clean);
    }

    /** Returns {@code true} if the user profile is an active secondary profile worth scanning. */
    public static boolean shouldScanUser(UserProfileInfo info) {
        if (info == null || info.userId <= PRIMARY_USER_ID) {
            return false;
        }
        if (!info.running || info.quietMode) {
            return false;
        }
        return !info.isGuest() && !info.isDisabled() && !info.isDemo();
    }

    /**
     * Decides whether a launchable app inside a secondary user profile should be surfaced to users.
     *
     * <p>In dual/clone profiles (e.g. user 999), OEMs mirror dozens of system stubs (Settings,
     * DocumentsUI, Gallery, Play Services) alongside the handful of third-party apps the user
     * explicitly cloned. Requiring {@code !isSystemApp} for clone profiles surfaces exact cloned
     * apps with zero system-stub clutter, while Work / Private profiles keep standard launchable
     * profile apps (excluding blocked core system packages).
     */
    public static boolean shouldIncludeSecondaryProfileApp(
            String profileType, String pkg, boolean isSystemApp) {
        if (PackagePolicy.isBlockedWindowPkg(pkg)) {
            return false;
        }
        if (PROFILE_CLONE.equals(profileType)) {
            return !isSystemApp;
        }
        // In Work / Private / other secondary profiles, exclude Settings stub noise if system.
        if (isSystemApp && "com.android.settings".equals(pkg)) {
            return false;
        }
        return true;
    }

    /**
     * Formats the canonical cross-platform application identity key.
     * User 0 maps to plain {@code pkg} for 100% backward compatibility with persisted config/prefs;
     * secondary users map to {@code pkg + "#u" + userId}.
     */
    public static String formatAppKey(String pkg, int userId) {
        String safePkg = (pkg != null) ? pkg.trim() : "";
        if (safePkg.isEmpty()) return "";
        return (userId > PRIMARY_USER_ID) ? (safePkg + "#u" + userId) : safePkg;
    }

    /**
     * Computes Android's multi-user Linux UID ({@code userId * 100000 + appId}) from a primary/base UID.
     * Returns {@code -1} if {@code baseUid < 0}.
     */
    public static int computeUserUid(int baseUid, int userId) {
        if (baseUid < 0) return -1;
        int safeUserId = Math.max(0, userId);
        int appId = baseUid % PER_USER_UID_RANGE;
        return safeUserId * PER_USER_UID_RANGE + appId;
    }

    /** Parses standard {@code pm list users} output into structured {@link UserProfileInfo} records. */
    public static List<UserProfileInfo> parsePmListUsersOutput(String output) {
        if (output == null || output.isEmpty()) {
            return Collections.emptyList();
        }
        List<UserProfileInfo> out = new ArrayList<>();
        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher m = PM_LIST_USER_LINE.matcher(line);
            if (!m.find()) continue;
            try {
                int id = Integer.parseInt(m.group(1));
                String name = m.group(2);
                int flags = (int) Long.parseLong(m.group(3), 16);
                String tail = m.group(4) != null ? m.group(4).toLowerCase(Locale.ROOT) : "";
                boolean running = tail.contains("running");
                out.add(new UserProfileInfo(id, name, flags, "", running, false));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Parses {@code pm list packages [-3] --user <id>} output into validated package names. */
    public static Set<String> parsePmListPackagesOutput(String output) {
        if (output == null || output.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> pkgs = new LinkedHashSet<>();
        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("package:")) continue;
            String pkg = line.substring("package:".length()).trim();
            if (PackagePolicy.isValidPackageName(pkg)) {
                pkgs.add(pkg);
            }
        }
        return pkgs;
    }

    /** Encodes a list of secondary-profile launchable apps into a compact JSON array for Binder IPC. */
    public static String encodeSecondaryAppsJson(List<SecondaryAppEntry> apps) {
        SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
        if (apps != null) {
            for (SecondaryAppEntry e : apps) {
                if (e == null || e.pkg.isEmpty() || e.userId <= PRIMARY_USER_ID) continue;
                arr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("pkg", e.pkg)
                        .put("cls", e.cls)
                        .put("label", e.label)
                        .put("userId", e.userId)
                        .put("profileType", e.profileType)
                        .build());
            }
        }
        return arr.build();
    }
}
