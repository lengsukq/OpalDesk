package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig.SavedWindowBounds;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;
import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Persistence serializer for per-app {@link WindowProfile}, launch counts, custom DPI overrides,
 * normalized {@link SavedWindowBounds}, and DeX IME configuration files for {@link DaemonConfig}.
 */
final class WindowProfileStore {
    static final class ImeConfSnapshot {
        final int displayMode;
        final String lockedImeId;
        final boolean muteHostOnCast;
        final int showWithHardKbd;
        final boolean autoBridgeImeOnCast;

        ImeConfSnapshot(
                int displayMode, String lockedImeId, boolean muteHostOnCast,
                int showWithHardKbd, boolean autoBridgeImeOnCast) {
            this.displayMode = displayMode;
            this.lockedImeId = lockedImeId;
            this.muteHostOnCast = muteHostOnCast;
            this.showWithHardKbd = showWithHardKbd;
            this.autoBridgeImeOnCast = autoBridgeImeOnCast;
        }
    }

    private final Object lock;
    private final Object ioLock;
    private final File profileFile;
    private final File launchCountsFile;
    private final File appDpisFile;
    private final File appBoundsFile;
    private final File imeConfFile;

    WindowProfileStore(
            Object lock,
            Object ioLock,
            File profileFile,
            File launchCountsFile,
            File appDpisFile,
            File appBoundsFile,
            File imeConfFile) {
        this.lock = lock;
        this.ioLock = ioLock;
        this.profileFile = profileFile;
        this.launchCountsFile = launchCountsFile;
        this.appDpisFile = appDpisFile;
        this.appBoundsFile = appBoundsFile;
        this.imeConfFile = imeConfFile;
    }

    void loadAppProfiles(Map<String, WindowProfile> appProfiles) {
        if (!profileFile.isFile()) return;
        synchronized (lock) {
            appProfiles.clear();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(profileFile), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = br.readLine()) != null) {
                    int eq = ln.indexOf('=');
                    if (eq <= 0) continue;
                    String pkg = ln.substring(0, eq).trim();
                    WindowProfile p = WindowProfile.fromName(ln.substring(eq + 1).trim());
                    if (PackagePolicy.isValidPackageName(pkg) && p != null) {
                        appProfiles.put(pkg, p);
                    }
                }
            } catch (IOException e) {
                System.err.println("OPALDESK_PROFILES_LOAD_WARN: " + e.getMessage());
            }
        }
    }

    void saveAppProfiles(Map<String, WindowProfile> appProfiles) {
        synchronized (ioLock) {
            StringBuilder sb = new StringBuilder();
            synchronized (lock) {
                for (Map.Entry<String, WindowProfile> e : appProfiles.entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue().name()).append('\n');
                }
            }
            try {
                AtomicFiles.writeAtomicUtf8(profileFile, sb.toString(), AtomicFiles.MODE_OWNER_RW);
            } catch (IOException e) {
                System.err.println("OPALDESK_PROFILES_SAVE_WARN: " + e.getMessage());
            }
        }
    }

    void loadAppLaunchCounts(Map<String, Integer> appLaunchCounts) {
        if (!launchCountsFile.isFile()) return;
        synchronized (lock) {
            appLaunchCounts.clear();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(launchCountsFile), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = br.readLine()) != null) {
                    int eq = ln.indexOf('=');
                    if (eq <= 0) continue;
                    String pkg = ln.substring(0, eq).trim();
                    if (!PackagePolicy.isValidPackageName(pkg)) continue;
                    try {
                        int c = Integer.parseInt(ln.substring(eq + 1).trim());
                        if (c > 0) appLaunchCounts.put(pkg, c);
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (IOException e) {
                System.err.println("OPALDESK_COUNTS_LOAD_WARN: " + e.getMessage());
            }
        }
    }

    void saveAppLaunchCounts(Map<String, Integer> appLaunchCounts) {
        synchronized (ioLock) {
            StringBuilder sb = new StringBuilder();
            synchronized (lock) {
                for (Map.Entry<String, Integer> e : appLaunchCounts.entrySet()) {
                    if (e.getValue() != null && e.getValue() > 0) {
                        sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                    }
                }
            }
            try {
                AtomicFiles.writeAtomicUtf8(launchCountsFile, sb.toString(), AtomicFiles.MODE_OWNER_RW);
            } catch (IOException e) {
                System.err.println("OPALDESK_COUNTS_SAVE_WARN: " + e.getMessage());
            }
        }
    }

    void loadAppCustomDpis(Map<String, Integer> appCustomDpis) {
        if (!appDpisFile.isFile()) return;
        synchronized (lock) {
            appCustomDpis.clear();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(appDpisFile), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = br.readLine()) != null) {
                    int eq = ln.indexOf('=');
                    if (eq <= 0) continue;
                    String pkg = ln.substring(0, eq).trim();
                    if (!PackagePolicy.isValidPackageName(pkg)) continue;
                    try {
                        int dpi = Integer.parseInt(ln.substring(eq + 1).trim());
                        if (dpi >= DisplayGeometryUtil.MIN_DPI && dpi <= DisplayGeometryUtil.MAX_DPI) {
                            appCustomDpis.put(pkg, dpi);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (IOException e) {
                System.err.println("OPALDESK_APP_DPIS_LOAD_WARN: " + e.getMessage());
            }
        }
    }

    void saveAppCustomDpis(Map<String, Integer> appCustomDpis) {
        synchronized (ioLock) {
            StringBuilder sb = new StringBuilder();
            synchronized (lock) {
                for (Map.Entry<String, Integer> e : appCustomDpis.entrySet()) {
                    if (e.getValue() != null && e.getValue() >= DisplayGeometryUtil.MIN_DPI) {
                        sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                    }
                }
            }
            try {
                AtomicFiles.writeAtomicUtf8(appDpisFile, sb.toString(), AtomicFiles.MODE_OWNER_RW);
            } catch (IOException e) {
                System.err.println("OPALDESK_APP_DPIS_SAVE_WARN: " + e.getMessage());
            }
        }
    }

    void loadAppWindowBounds(Map<String, SavedWindowBounds> appWindowBounds) {
        if (!appBoundsFile.isFile()) return;
        synchronized (lock) {
            appWindowBounds.clear();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(appBoundsFile), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = br.readLine()) != null) {
                    int eq = ln.indexOf('=');
                    if (eq <= 0) continue;
                    String pkg = ln.substring(0, eq).trim();
                    if (!PackagePolicy.isValidPackageName(pkg)) continue;
                    String[] parts = ln.substring(eq + 1).trim().split(",");
                    if (parts.length < 4) continue;
                    try {
                        float l = Float.parseFloat(parts[0].trim());
                        float t = Float.parseFloat(parts[1].trim());
                        float r = Float.parseFloat(parts[2].trim());
                        float b = Float.parseFloat(parts[3].trim());
                        String pres = parts.length >= 5 ? parts[4].trim() : BridgeProtocol.PRES_FLOATING;
                        appWindowBounds.put(pkg, new SavedWindowBounds(l, t, r, b, pres));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (IOException e) {
                System.err.println("OPALDESK_APP_BOUNDS_LOAD_WARN: " + e.getMessage());
            }
        }
    }

    void saveAppWindowBounds(Map<String, SavedWindowBounds> appWindowBounds) {
        synchronized (ioLock) {
            StringBuilder sb = new StringBuilder();
            synchronized (lock) {
                for (Map.Entry<String, SavedWindowBounds> e : appWindowBounds.entrySet()) {
                    SavedWindowBounds b = e.getValue();
                    if (b == null) continue;
                    sb.append(e.getKey()).append('=')
                            .append(String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f,",
                                    b.leftRatio, b.topRatio, b.rightRatio, b.bottomRatio))
                            .append(b.presentation).append('\n');
                }
            }
            try {
                AtomicFiles.writeAtomicUtf8(appBoundsFile, sb.toString(), AtomicFiles.MODE_OWNER_RW);
            } catch (IOException e) {
                System.err.println("OPALDESK_APP_BOUNDS_SAVE_WARN: " + e.getMessage());
            }
        }
    }

    ImeConfSnapshot loadImeConf() {
        if (!imeConfFile.isFile()) return null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(imeConfFile), StandardCharsets.UTF_8))) {
            int mode = BridgeProtocol.DEX_IME_DISPLAY_AUTO;
            String locked = "";
            boolean muteCast = false;
            int hardKbd = BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM;
            boolean autoBridgeCast = true;
            String ln;
            while ((ln = br.readLine()) != null) {
                int eq = ln.indexOf('=');
                if (eq <= 0) continue;
                String k = ln.substring(0, eq).trim();
                String v = ln.substring(eq + 1).trim();
                try {
                    if ("displayMode".equals(k)) {
                        int parsed = Integer.parseInt(v);
                        if (parsed >= BridgeProtocol.DEX_IME_DISPLAY_AUTO
                                && parsed <= BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST) {
                            mode = parsed;
                        }
                    } else if ("lockedImeId".equals(k)) {
                        if (v.isEmpty() || PackagePolicy.isValidImeId(v)) locked = v;
                    } else if ("muteHostOnCast".equals(k)) {
                        muteCast = Integer.parseInt(v) != 0;
                    } else if ("showWithHardKbd".equals(k)) {
                        int parsed = Integer.parseInt(v);
                        hardKbd = (parsed == BridgeProtocol.HARD_KBD_SOFT_IME_HIDE
                                || parsed == BridgeProtocol.HARD_KBD_SOFT_IME_SHOW)
                                ? parsed : BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM;
                    } else if ("autoBridgeImeOnCast".equals(k)) {
                        autoBridgeCast = Integer.parseInt(v) != 0;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return new ImeConfSnapshot(mode, locked, muteCast, hardKbd, autoBridgeCast);
        } catch (IOException e) {
            System.err.println("OPALDESK_IME_CONF_LOAD_WARN: " + e.getMessage());
            return null;
        }
    }

    void saveImeConf(ImeConfSnapshot snap) {
        synchronized (ioLock) {
            String content = "displayMode=" + snap.displayMode + "\n"
                    + "lockedImeId=" + snap.lockedImeId + "\n"
                    + "muteHostOnCast=" + (snap.muteHostOnCast ? 1 : 0) + "\n"
                    + "showWithHardKbd=" + snap.showWithHardKbd + "\n"
                    + "autoBridgeImeOnCast=" + (snap.autoBridgeImeOnCast ? 1 : 0) + "\n";
            try {
                AtomicFiles.writeAtomicUtf8(imeConfFile, content, AtomicFiles.MODE_OWNER_RW);
            } catch (IOException e) {
                System.err.println("OPALDESK_IME_CONF_SAVE_WARN: " + e.getMessage());
            }
        }
    }

    static String toAdvancedStateJson(
            int globalCustomDpi,
            Map<String, Integer> appCustomDpis,
            Map<String, SavedWindowBounds> appWindowBounds) {
        SimpleJson.ObjectBuilder dpisObj = new SimpleJson.ObjectBuilder();
        for (Map.Entry<String, Integer> e : appCustomDpis.entrySet()) {
            dpisObj.put(e.getKey(), e.getValue());
        }
        SimpleJson.ObjectBuilder boundsObj = new SimpleJson.ObjectBuilder();
        for (Map.Entry<String, SavedWindowBounds> e : appWindowBounds.entrySet()) {
            SavedWindowBounds b = e.getValue();
            String entryJson = new SimpleJson.ObjectBuilder()
                    .put("l", Math.round(b.leftRatio * 10000f))
                    .put("t", Math.round(b.topRatio * 10000f))
                    .put("r", Math.round(b.rightRatio * 10000f))
                    .put("b", Math.round(b.bottomRatio * 10000f))
                    .put("p", b.presentation)
                    .build();
            boundsObj.putRaw(e.getKey(), entryJson);
        }
        return new SimpleJson.ObjectBuilder()
                .put("globalCustomDpi", globalCustomDpi)
                .putRaw("appCustomDpis", dpisObj.build())
                .putRaw("appWindowBounds", boundsObj.build())
                .build();
    }
}
