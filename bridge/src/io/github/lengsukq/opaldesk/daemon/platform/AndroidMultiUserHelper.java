package io.github.lengsukq.opaldesk.daemon.platform;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Package-private multi-user / cloned-app / work-profile introspection helper ({@code uid=2000})
 * extracted from {@link AndroidServices}.
 */
final class AndroidMultiUserHelper {
    private AndroidMultiUserHelper() {}

    static List<UserProfileAppPolicy.UserProfileInfo> querySecondaryUserProfiles() {
        List<UserProfileAppPolicy.UserProfileInfo> all = queryUsersViaBinder();
        if (all.isEmpty()) {
            ShellCommand.Result res = ShellCommand.runWithTimeout(2000L, "pm", "list", "users");
            if (res.isSuccess() && res.output != null && !res.output.isEmpty()) {
                all = UserProfileAppPolicy.parsePmListUsersOutput(res.output);
            }
        }
        List<UserProfileAppPolicy.UserProfileInfo> active = new ArrayList<>();
        for (UserProfileAppPolicy.UserProfileInfo info : all) {
            if (UserProfileAppPolicy.shouldScanUser(info)) {
                active.add(info);
            }
        }
        return active;
    }

    static String resolveProfileTypeForUser(int userId) {
        if (userId <= UserProfileAppPolicy.PRIMARY_USER_ID) {
            return UserProfileAppPolicy.PROFILE_PRIMARY;
        }
        for (UserProfileAppPolicy.UserProfileInfo info : querySecondaryUserProfiles()) {
            if (info.userId == userId) {
                return info.profileType;
            }
        }
        return UserProfileAppPolicy.classifyProfileType(userId, "", 0, "");
    }

    static List<UserProfileAppPolicy.SecondaryAppEntry> querySecondaryUserLaunchableApps(Context context) {
        List<UserProfileAppPolicy.UserProfileInfo> profiles = querySecondaryUserProfiles();
        if (profiles.isEmpty()) {
            return Collections.emptyList();
        }
        PackageManager pm = (context != null) ? context.getPackageManager() : null;
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<UserProfileAppPolicy.SecondaryAppEntry> out = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (UserProfileAppPolicy.UserProfileInfo profile : profiles) {
            List<ResolveInfo> resolved = queryIntentActivitiesForUser(launcherIntent, profile.userId);
            if (resolved != null && !resolved.isEmpty()) {
                for (ResolveInfo ri : resolved) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    String cls = ri.activityInfo.name;
                    ApplicationInfo ai = ri.activityInfo.applicationInfo;
                    boolean isSystem = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (!UserProfileAppPolicy.shouldIncludeSecondaryProfileApp(profile.profileType, pkg, isSystem)) {
                        continue;
                    }
                    String key = UserProfileAppPolicy.formatAppKey(pkg, profile.userId);
                    if (!seenKeys.add(key)) continue;
                    String label = resolveResolveInfoLabel(context, ri, pm, pkg, profile.userId);
                    out.add(new UserProfileAppPolicy.SecondaryAppEntry(
                            pkg, cls, label, profile.userId, profile.profileType));
                }
            } else if (UserProfileAppPolicy.PROFILE_CLONE.equals(profile.profileType)) {
                // Fallback when an OEM ROM restricts IPackageManager.queryIntentActivities across users:
                // `pm list packages -3 --user <id>` returns exact user-cloned third-party packages.
                ShellCommand.Result pkgRes = ShellCommand.runWithTimeout(
                        2500L, "pm", "list", "packages", "-3", "--user", String.valueOf(profile.userId));
                if (pkgRes.isSuccess()) {
                    for (String pkg : UserProfileAppPolicy.parsePmListPackagesOutput(pkgRes.output)) {
                        if (!UserProfileAppPolicy.shouldIncludeSecondaryProfileApp(profile.profileType, pkg, false)) {
                            continue;
                        }
                        String key = UserProfileAppPolicy.formatAppKey(pkg, profile.userId);
                        if (!seenKeys.add(key)) continue;
                        ComponentName comp = resolveLaunchComponentForUser(context, pkg, profile.userId);
                        String cls = (comp != null && comp.getClassName() != null) ? comp.getClassName() : "";
                        String label = resolveAppLabelForUser(context, pkg, profile.userId);
                        out.add(new UserProfileAppPolicy.SecondaryAppEntry(
                                pkg, cls, label, profile.userId, profile.profileType));
                    }
                }
            }
        }
        out.sort((a, b) -> {
            int cmp = a.label.compareToIgnoreCase(b.label);
            if (cmp != 0) return cmp;
            cmp = a.pkg.compareTo(b.pkg);
            return cmp != 0 ? cmp : Integer.compare(a.userId, b.userId);
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<ResolveInfo> queryIntentActivitiesForUser(Intent intent, int userId) {
        if (intent == null) return Collections.emptyList();
        int safeUserId = Math.max(0, userId);
        Object ipm = AndroidServices.getServiceInterface("package", "android.content.pm.IPackageManager$Stub");
        if (ipm == null) return Collections.emptyList();
        try {
            for (Method m : ipm.getClass().getMethods()) {
                if (!"queryIntentActivities".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                Object res = null;
                if (pt.length == 4 && pt[0] == Intent.class && pt[1] == String.class && pt[3] == int.class) {
                    if (pt[2] == long.class) {
                        res = m.invoke(ipm, intent, null, 0L, safeUserId);
                    } else if (pt[2] == int.class) {
                        res = m.invoke(ipm, intent, null, 0, safeUserId);
                    }
                }
                if (res instanceof List<?>) {
                    return (List<ResolveInfo>) res;
                }
                if (res != null) {
                    Method getList = AndroidServices.findMethodOrNull(res.getClass(), "getList");
                    if (getList != null) {
                        Object list = getList.invoke(res);
                        if (list instanceof List<?>) {
                            return (List<ResolveInfo>) list;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_QUERY_USER_APPS_WARN user=" + safeUserId + ": " + AndroidServices.unwrapMessage(e));
        }
        return Collections.emptyList();
    }

    static ComponentName resolveLaunchComponentForUser(Context context, String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) return null;
        int safeUserId = Math.max(0, userId);
        if (safeUserId > 0) {
            Intent q = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(pkg);
            List<ResolveInfo> list = queryIntentActivitiesForUser(q, safeUserId);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri != null && ri.activityInfo != null
                            && pkg.equals(ri.activityInfo.packageName)
                            && ri.activityInfo.name != null && !ri.activityInfo.name.isEmpty()) {
                        return new ComponentName(pkg, ri.activityInfo.name);
                    }
                }
            }
        }
        if (context != null) {
            try {
                Intent fallback = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (fallback != null && fallback.getComponent() != null) {
                    return fallback.getComponent();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    static boolean canResolveActivityForUser(Context context, Intent intent, int userId) {
        if (intent == null) return false;
        int safeUserId = Math.max(0, userId);
        if (safeUserId > 0) {
            List<ResolveInfo> list = queryIntentActivitiesForUser(intent, safeUserId);
            if (list != null && !list.isEmpty()) {
                return true;
            }
        }
        if (context != null) {
            try {
                return context.getPackageManager().resolveActivity(intent, 0) != null;
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    static String resolveAppLabelForUser(Context context, String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) return "";
        PackageManager pm = (context != null) ? context.getPackageManager() : null;
        if (pm == null) return pkg;
        int safeUserId = Math.max(0, userId);
        if (safeUserId > 0) {
            ApplicationInfo ai = getApplicationInfoForUser(pkg, safeUserId);
            if (ai != null) {
                try {
                    CharSequence lbl = pm.getApplicationLabel(ai);
                    if (lbl != null && lbl.length() > 0) {
                        return lbl.toString().trim();
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence lbl = pm.getApplicationLabel(ai);
            if (lbl != null && lbl.length() > 0) {
                return lbl.toString().trim();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return pkg;
    }

    static int getPackageUidForUser(Context context, String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) return -1;
        int safeUserId = Math.max(0, userId);
        if (safeUserId > 0) {
            Object ipm = AndroidServices.getServiceInterface("package", "android.content.pm.IPackageManager$Stub");
            if (ipm != null) {
                try {
                    for (Method m : ipm.getClass().getMethods()) {
                        if (!"getPackageUid".equals(m.getName())) continue;
                        Class<?>[] pt = m.getParameterTypes();
                        if (pt.length == 3 && pt[0] == String.class && pt[2] == int.class) {
                            Object val = (pt[1] == long.class)
                                    ? m.invoke(ipm, pkg, 0L, safeUserId)
                                    : (pt[1] == int.class ? m.invoke(ipm, pkg, 0, safeUserId) : null);
                            if (val instanceof Integer && (Integer) val >= 0) {
                                return (Integer) val;
                            }
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        if (context != null) {
            try {
                int primaryUid = context.getPackageManager().getPackageUid(pkg, 0);
                return (safeUserId > 0)
                        ? UserProfileAppPolicy.computeUserUid(primaryUid, safeUserId)
                        : primaryUid;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }
        return -1;
    }

    private static ApplicationInfo getApplicationInfoForUser(String pkg, int userId) {
        Object ipm = AndroidServices.getServiceInterface("package", "android.content.pm.IPackageManager$Stub");
        if (ipm == null) return null;
        try {
            for (Method m : ipm.getClass().getMethods()) {
                if (!"getApplicationInfo".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == String.class && pt[2] == int.class) {
                    Object res = (pt[1] == long.class)
                            ? m.invoke(ipm, pkg, 0L, userId)
                            : (pt[1] == int.class ? m.invoke(ipm, pkg, 0, userId) : null);
                    if (res instanceof ApplicationInfo) {
                        return (ApplicationInfo) res;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static String resolveResolveInfoLabel(
            Context context, ResolveInfo ri, PackageManager pm, String pkg, int userId) {
        if (pm != null && ri != null) {
            try {
                CharSequence lbl = ri.loadLabel(pm);
                if (lbl != null && lbl.length() > 0) {
                    return lbl.toString().trim();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return resolveAppLabelForUser(context, pkg, userId);
    }

    private static List<UserProfileAppPolicy.UserProfileInfo> queryUsersViaBinder() {
        Object ium = AndroidServices.getServiceInterface("user", "android.os.IUserManager$Stub");
        if (ium == null) return Collections.emptyList();
        try {
            Object rawUsers = null;
            for (Method m : ium.getClass().getMethods()) {
                if (!"getUsers".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == boolean.class && pt[1] == boolean.class && pt[2] == boolean.class) {
                    rawUsers = m.invoke(ium, true, true, true);
                    break;
                }
                if (pt.length == 1 && pt[0] == boolean.class) {
                    rawUsers = m.invoke(ium, true);
                }
            }
            if (!(rawUsers instanceof List<?>)) return Collections.emptyList();
            Method isUserRunning = AndroidServices.findMethodOrNull(ium.getClass(), "isUserRunning", int.class);
            List<UserProfileAppPolicy.UserProfileInfo> out = new ArrayList<>();
            for (Object u : (List<?>) rawUsers) {
                if (u == null) continue;
                int id = TaskApiAdapter.readIntField(u, "id", -1);
                if (id < 0) continue;
                Object nameObj = TaskApiAdapter.readObjField(u, "name");
                String name = (nameObj instanceof String) ? (String) nameObj : "";
                int flags = TaskApiAdapter.readIntField(u, "flags", 0);
                Object typeObj = TaskApiAdapter.readObjField(u, "userType");
                String userType = (typeObj instanceof String) ? (String) typeObj : "";
                boolean quiet = (flags & UserProfileAppPolicy.FLAG_QUIET_MODE) != 0;
                Method quietM = AndroidServices.findMethodOrNull(u.getClass(), "isQuietModeEnabled");
                if (quietM != null) {
                    try {
                        Object qv = quietM.invoke(u);
                        if (qv instanceof Boolean) quiet = (Boolean) qv;
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                    }
                }
                boolean running = (id == 0);
                if (isUserRunning != null) {
                    try {
                        Object rv = isUserRunning.invoke(ium, id);
                        if (rv instanceof Boolean) running = (Boolean) rv;
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                    }
                }
                out.add(new UserProfileAppPolicy.UserProfileInfo(id, name, flags, userType, running, quiet));
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_QUERY_USERS_WARN: " + AndroidServices.unwrapMessage(e));
            return Collections.emptyList();
        }
    }
}
