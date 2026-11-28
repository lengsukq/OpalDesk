package io.github.lengsukq.opaldesk.daemon.stream;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.Display;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidDisplayDriver;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.UserProfileAppPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the launchable app catalog (primary + secondary user profiles) and tracks
 * Display 0 foreground app metadata for OpalCast Mac Handoff.
 */
final class OpalCastAppCatalogBuilder {
    private final AndroidServices services;
    private final DaemonConfig config;
    private final AndroidDisplayDriver displayDriver;
    private final Map<String, String> hostAppLabelCache = new ConcurrentHashMap<>();
    private volatile String hostTopAppPackage = "";
    private volatile String hostTopAppLabel = "";

    OpalCastAppCatalogBuilder(
            AndroidServices services,
            DaemonConfig config,
            AndroidDisplayDriver displayDriver) {
        this.services = services;
        this.config = config;
        this.displayDriver = displayDriver;
    }

    String getHostTopAppPackage() {
        return hostTopAppPackage;
    }

    String getHostTopAppLabel() {
        return hostTopAppLabel;
    }

    /**
     * Probes Display 0's current foreground user app and returns {@code true} if either
     * package or resolved label changed.
     */
    boolean refreshHostTopAppIfChanged() {
        if (displayDriver == null) return false;
        String pkg = "";
        try {
            pkg = displayDriver.findTopVisibleUserAppPackageOnDisplay(Display.DEFAULT_DISPLAY);
        } catch (RuntimeException ignored) {
        }
        if (pkg == null) pkg = "";
        String label = pkg.isEmpty() ? "" : resolveCachedHostAppLabel(pkg);
        if (pkg.equals(hostTopAppPackage) && label.equals(hostTopAppLabel)) {
            return false;
        }
        hostTopAppPackage = pkg;
        hostTopAppLabel = label;
        return true;
    }

    String resolveCachedHostAppLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = hostAppLabelCache.get(pkg);
        if (cached != null) return cached;
        String resolved = (displayDriver != null) ? displayDriver.resolveAppLabel(pkg) : pkg;
        if (resolved == null || resolved.isEmpty()) resolved = pkg;
        if (hostAppLabelCache.size() < 256) {
            hostAppLabelCache.put(pkg, resolved);
        }
        return resolved;
    }

    List<OpalCastProtocol.AppEntry> queryInstalledApps() {
        List<OpalCastProtocol.AppEntry> out = new ArrayList<>();
        if (services.context == null) return out;
        try {
            PackageManager pm = services.context.getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(q, 0);
            Set<String> seenKeys = new HashSet<>();
            for (ResolveInfo ri : resolved) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (DaemonConfig.PackagePolicy.isBlockedWindowPkg(pkg)) continue;
                String key = UserProfileAppPolicy.formatAppKey(pkg, 0);
                if (!seenKeys.add(key)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                String label = (lbl != null && lbl.length() > 0) ? lbl.toString().trim() : pkg;
                int openCount = config.getAppLaunchCount(pkg);
                out.add(new OpalCastProtocol.AppEntry(pkg, ri.activityInfo.name, label, false, -1, "", openCount, 0, ""));
            }
            for (UserProfileAppPolicy.SecondaryAppEntry sec : services.querySecondaryUserLaunchableApps()) {
                if (sec == null || sec.pkg.isEmpty() || sec.userId <= 0) continue;
                String key = sec.appKey();
                if (!seenKeys.add(key)) continue;
                int openCount = config.getAppLaunchCount(sec.pkg);
                out.add(new OpalCastProtocol.AppEntry(
                        sec.pkg, sec.cls, sec.label, false, -1, "", openCount, sec.userId, sec.profileType));
            }
            out.sort(Comparator
                    .comparing((OpalCastProtocol.AppEntry a) -> a.label.toLowerCase(Locale.ROOT))
                    .thenComparingInt(a -> a.userId));
        } catch (RuntimeException ignored) {
        }
        return out;
    }
}
