package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OEM-agnostic persistent daemon configuration ({@code /data/local/tmp/opaldesk_v2.conf},
 * {@code /data/local/tmp/opaldesk_profiles.conf}, {@code /data/local/tmp/opaldesk_app_dpis.conf},
 * and {@code /data/local/tmp/opaldesk_app_bounds.conf}) and package safety policy.
 *
 * <p>Contains zero vendor-specific (MIUI/HyperOS/ColorOS/OneUI) package hardcodes.
 */
public final class DaemonConfig {
    public static final String DEFAULT_CONF_PATH = "/data/local/tmp/opaldesk_v2.conf";
    public static final String DEFAULT_PROFILE_CONF_PATH = "/data/local/tmp/opaldesk_profiles.conf";
    public static final String DEFAULT_LAUNCH_COUNTS_PATH = "/data/local/tmp/opaldesk_app_counts.conf";
    public static final String DEFAULT_APP_DPIS_PATH = "/data/local/tmp/opaldesk_app_dpis.conf";
    public static final String DEFAULT_APP_BOUNDS_PATH = "/data/local/tmp/opaldesk_app_bounds.conf";
    public static final int DEFAULT_ROOTLESS_MAX_VD = 6;
    public static final int DEFAULT_ROOT_ENHANCED_MAX_VD = 10;
    public static final int MIN_MAX_VD = 2;
    public static final int HARD_MAX_VD = 12;

    /** Normalized last-closed window geometry for a package (adapts across display resolutions). */
    public static final class SavedWindowBounds {
        public final float leftRatio;
        public final float topRatio;
        public final float rightRatio;
        public final float bottomRatio;
        public final String presentation;

        public SavedWindowBounds(
                float leftRatio,
                float topRatio,
                float rightRatio,
                float bottomRatio,
                String presentation) {
            this.leftRatio = clampRatio(leftRatio, -0.5f, 0.95f);
            this.topRatio = clampRatio(topRatio, 0.0f, 0.95f);
            this.rightRatio = clampRatio(rightRatio, this.leftRatio + 0.10f, 1.5f);
            this.bottomRatio = clampRatio(bottomRatio, this.topRatio + 0.10f, 1.2f);
            this.presentation = (presentation != null && !presentation.isEmpty())
                    ? presentation : BridgeProtocol.PRES_FLOATING;
        }

        private static float clampRatio(float v, float lo, float hi) {
            if (!Float.isFinite(v)) return lo;
            return Math.max(lo, Math.min(hi, v));
        }

        public IntRect toCanvasRect(int canvasW, int canvasH, int canvasDpi) {
            int w = Math.max(360, canvasW);
            int h = Math.max(360, canvasH);
            int l = Math.round(leftRatio * w);
            int t = Math.round(topRatio * h);
            int r = Math.round(rightRatio * w);
            int b = Math.round(bottomRatio * h);
            return DisplayGeometryUtil.clampWindowFrame(new IntRect(l, t, r, b), w, h, canvasDpi);
        }

        public static SavedWindowBounds fromCanvasRect(
                IntRect frame, int canvasW, int canvasH, String presentation) {
            if (frame == null || frame.isEmpty() || canvasW <= 0 || canvasH <= 0) return null;
            return new SavedWindowBounds(
                    frame.left / (float) canvasW,
                    frame.top / (float) canvasH,
                    frame.right / (float) canvasW,
                    frame.bottom / (float) canvasH,
                    presentation);
        }
    }

    /** Universal AOSP package validation & system task protection rules. */
    public static final class PackagePolicy {
        private static final Pattern PKG_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$");
        private static final Pattern CLS_PATTERN = Pattern.compile("^[a-zA-Z0-9_$]+(\\.[a-zA-Z0-9_$]+)+$");

        private static final Set<String> CORE_BLOCKED_PKGS = Set.of(
                "android",
                BridgeBuildConfig.HELPER_PACKAGE,
                "com.android.systemui",
                "com.android.phone",
                "com.android.server.telecom"
        );

        private PackagePolicy() {}

        public static boolean isValidPackageName(String pkg) {
            return pkg != null && pkg.length() >= 3 && pkg.length() <= 160 && PKG_PATTERN.matcher(pkg).matches();
        }

        public static boolean isValidClassName(String cls) {
            return cls != null && cls.length() >= 3 && cls.length() <= 220 && CLS_PATTERN.matcher(cls).matches();
        }

        public static boolean isBlockedWindowPkg(String pkg) {
            if (!isValidPackageName(pkg) || CORE_BLOCKED_PKGS.contains(pkg)) return true;
            return pkg.contains("permissioncontroller")
                    || pkg.contains("packageinstaller")
                    || pkg.contains("keyguard");
        }
    }

    private final Object lock = new Object();
    private final File confFile;
    private final File profileFile;
    private final File launchCountsFile;
    private final File appDpisFile;
    private final File appBoundsFile;
    private final Map<String, WindowProfile> appProfiles = new HashMap<>();
    private final Map<String, Integer> appLaunchCounts = new HashMap<>();
    private final Map<String, Integer> appCustomDpis = new LinkedHashMap<>();
    private final Map<String, SavedWindowBounds> appWindowBounds = new LinkedHashMap<>();

    public volatile boolean shuttingDown = false;
    private int canvasWidth = 1920;
    private int canvasHeight = 1200;
    private int canvasDpi = 240;
    private int maxActiveDisplays = DEFAULT_ROOTLESS_MAX_VD;
    private int inputBackendMode = BridgeProtocol.INPUT_BACKEND_AUTO;
    private int targetFps = 0; // 0 = auto match display refresh rate
    private int windowDpiScalePct = 100; // 85=compact, 100=standard/auto, 115=large
    private int globalCustomDpi = 0; // 0 = auto (use profile + scalePct), 120..560 = fixed global DPI
    private int mouseTargetMode = BridgeProtocol.MOUSE_TARGET_EXTERNAL;
    private boolean rootEnhanced = false;
    private boolean secureCaptureEnabled = false;
    private boolean remoteMicEnabled = false;
    private boolean remoteCameraEnabled = false;

    public DaemonConfig() {
        this(new File(DEFAULT_CONF_PATH), new File(DEFAULT_PROFILE_CONF_PATH), new File(DEFAULT_LAUNCH_COUNTS_PATH));
    }

    public DaemonConfig(File confFile, File profileFile) {
        this(confFile, profileFile, deriveSiblingFile(confFile, "opaldesk_app_counts.conf", DEFAULT_LAUNCH_COUNTS_PATH));
    }

    public DaemonConfig(File confFile, File profileFile, File launchCountsFile) {
        this.confFile = (confFile != null) ? confFile : new File(DEFAULT_CONF_PATH);
        this.profileFile = (profileFile != null) ? profileFile : new File(DEFAULT_PROFILE_CONF_PATH);
        this.launchCountsFile = (launchCountsFile != null) ? launchCountsFile : new File(DEFAULT_LAUNCH_COUNTS_PATH);
        this.appDpisFile = deriveSiblingFile(this.confFile, "opaldesk_app_dpis.conf", DEFAULT_APP_DPIS_PATH);
        this.appBoundsFile = deriveSiblingFile(this.confFile, "opaldesk_app_bounds.conf", DEFAULT_APP_BOUNDS_PATH);
    }

    private static File deriveSiblingFile(File confFile, String fileName, String defaultPath) {
        if (confFile != null && confFile.getParentFile() != null) {
            return new File(confFile.getParentFile(), fileName);
        }
        return new File(defaultPath);
    }

    public void loadAll() {
        loadMainConf();
        loadAppProfiles();
        loadAppLaunchCounts();
        loadAppCustomDpis();
        loadAppWindowBounds();
    }

    public int getCanvasWidth() {
        synchronized (lock) { return canvasWidth; }
    }

    public int getCanvasHeight() {
        synchronized (lock) { return canvasHeight; }
    }

    public int getCanvasDpi() {
        synchronized (lock) { return canvasDpi; }
    }

    public int getMaxActiveDisplays() {
        synchronized (lock) { return maxActiveDisplays; }
    }

    public int getInputBackendMode() {
        synchronized (lock) { return inputBackendMode; }
    }

    public int getTargetFps() {
        synchronized (lock) { return targetFps; }
    }

    public int getWindowDpiScalePct() {
        synchronized (lock) { return windowDpiScalePct; }
    }

    public int getGlobalCustomDpi() {
        synchronized (lock) { return globalCustomDpi; }
    }

    public void setGlobalCustomDpi(int dpi) {
        synchronized (lock) {
            if (dpi <= 0) {
                this.globalCustomDpi = 0;
            } else {
                this.globalCustomDpi = DisplayGeometryUtil.clampDpi(dpi);
            }
        }
        saveMainConf();
    }

    public int getAppCustomDpi(String pkg) {
        if (!PackagePolicy.isValidPackageName(pkg)) return 0;
        synchronized (lock) {
            Integer v = appCustomDpis.get(pkg);
            return (v != null && v >= DisplayGeometryUtil.MIN_DPI && v <= DisplayGeometryUtil.MAX_DPI) ? v : 0;
        }
    }

    /**
     * Resolves the effective custom DPI override for {@code pkg}:
     * per-app custom DPI takes precedence over global custom DPI; returns {@code 0} for Auto mode.
     */
    public int resolveEffectiveCustomDpi(String pkg) {
        synchronized (lock) {
            if (PackagePolicy.isValidPackageName(pkg)) {
                Integer perApp = appCustomDpis.get(pkg);
                if (perApp != null && perApp >= DisplayGeometryUtil.MIN_DPI && perApp <= DisplayGeometryUtil.MAX_DPI) {
                    return perApp;
                }
            }
            return (globalCustomDpi >= DisplayGeometryUtil.MIN_DPI && globalCustomDpi <= DisplayGeometryUtil.MAX_DPI)
                    ? globalCustomDpi : 0;
        }
    }

    public void setAppCustomDpi(String pkg, int dpi) {
        if (!PackagePolicy.isValidPackageName(pkg)) return;
        synchronized (lock) {
            if (dpi <= 0) {
                appCustomDpis.remove(pkg);
            } else if (appCustomDpis.size() < 512 || appCustomDpis.containsKey(pkg)) {
                appCustomDpis.put(pkg, DisplayGeometryUtil.clampDpi(dpi));
            }
        }
        saveAppCustomDpis();
    }

    public void replaceAllAppCustomDpis(Map<String, Integer> nextMap) {
        synchronized (lock) {
            appCustomDpis.clear();
            if (nextMap != null) {
                for (Map.Entry<String, Integer> e : nextMap.entrySet()) {
                    if (appCustomDpis.size() >= 512) break;
                    String pkg = e.getKey();
                    Integer v = e.getValue();
                    if (PackagePolicy.isValidPackageName(pkg) && v != null && v > 0) {
                        appCustomDpis.put(pkg, DisplayGeometryUtil.clampDpi(v));
                    }
                }
            }
        }
        saveAppCustomDpis();
    }

    public Map<String, Integer> getAllAppCustomDpisSnapshot() {
        synchronized (lock) {
            return new LinkedHashMap<>(appCustomDpis);
        }
    }

    public SavedWindowBounds getSavedWindowBounds(String pkg) {
        if (!PackagePolicy.isValidPackageName(pkg)) return null;
        synchronized (lock) {
            return appWindowBounds.get(pkg);
        }
    }

    public void rememberWindowBounds(String pkg, IntRect frame, int curCanvasW, int curCanvasH, String presentation) {
        if (!PackagePolicy.isValidPackageName(pkg) || frame == null || frame.isEmpty()) return;
        SavedWindowBounds bounds = SavedWindowBounds.fromCanvasRect(frame, curCanvasW, curCanvasH, presentation);
        if (bounds == null) return;
        synchronized (lock) {
            if (appWindowBounds.size() < 512 || appWindowBounds.containsKey(pkg)) {
                appWindowBounds.put(pkg, bounds);
            }
        }
        saveAppWindowBounds();
    }

    public void replaceAllWindowBounds(Map<String, SavedWindowBounds> nextMap) {
        synchronized (lock) {
            appWindowBounds.clear();
            if (nextMap != null) {
                for (Map.Entry<String, SavedWindowBounds> e : nextMap.entrySet()) {
                    if (appWindowBounds.size() >= 512) break;
                    if (PackagePolicy.isValidPackageName(e.getKey()) && e.getValue() != null) {
                        appWindowBounds.put(e.getKey(), e.getValue());
                    }
                }
            }
        }
        saveAppWindowBounds();
    }

    public Map<String, SavedWindowBounds> getAllSavedWindowBoundsSnapshot() {
        synchronized (lock) {
            return new LinkedHashMap<>(appWindowBounds);
        }
    }

    public String toAdvancedStateJson() {
        synchronized (lock) {
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

    public int getMouseTargetMode() {
        synchronized (lock) { return mouseTargetMode; }
    }

    public void setMouseTargetMode(int mode) {
        synchronized (lock) {
            this.mouseTargetMode = (mode == BridgeProtocol.MOUSE_TARGET_LOCAL)
                    ? BridgeProtocol.MOUSE_TARGET_LOCAL
                    : BridgeProtocol.MOUSE_TARGET_EXTERNAL;
        }
        saveMainConf();
    }

    public void updateDisplayPrefs(int reqTargetFps, int reqDpiScalePct) {
        synchronized (lock) {
            if (reqTargetFps == 0 || (reqTargetFps >= 30 && reqTargetFps <= 165)) {
                this.targetFps = reqTargetFps;
            }
            if (reqDpiScalePct >= 70 && reqDpiScalePct <= 160) {
                this.windowDpiScalePct = reqDpiScalePct;
            }
        }
        saveMainConf();
    }

    public boolean isRootEnhanced() {
        synchronized (lock) { return rootEnhanced; }
    }

    public boolean isSecureCaptureEnabled() {
        synchronized (lock) { return secureCaptureEnabled; }
    }

    public boolean isRemoteMicEnabled() {
        synchronized (lock) { return remoteMicEnabled; }
    }

    public boolean isRemoteCameraEnabled() {
        synchronized (lock) { return remoteCameraEnabled; }
    }

    public void setRemoteMediaConfig(boolean micEnabled, boolean cameraEnabled) {
        synchronized (lock) {
            this.remoteMicEnabled = micEnabled;
            this.remoteCameraEnabled = cameraEnabled;
        }
        saveMainConf();
    }

    public void markRootEnhanced(boolean enhanced, boolean secureCapture) {
        synchronized (lock) {
            this.rootEnhanced = enhanced;
            this.secureCaptureEnabled = secureCapture;
            if (enhanced && this.maxActiveDisplays == DEFAULT_ROOTLESS_MAX_VD) {
                this.maxActiveDisplays = DEFAULT_ROOT_ENHANCED_MAX_VD;
            }
        }
    }

    public void updateRuntimeConfig(int inputMode, int maxVd, int workW, int workH, int workDpi) {
        synchronized (lock) {
            if (inputMode >= BridgeProtocol.INPUT_BACKEND_AUTO && inputMode <= BridgeProtocol.INPUT_BACKEND_UHID) {
                this.inputBackendMode = inputMode;
            }
            if (maxVd >= MIN_MAX_VD && maxVd <= HARD_MAX_VD) {
                this.maxActiveDisplays = maxVd;
            }
            if (workW >= 360 && workH >= 360) {
                this.canvasWidth = DisplayGeometryUtil.alignEven(workW, 360, 4096);
                this.canvasHeight = DisplayGeometryUtil.alignEven(workH, 360, 4096);
            }
            if (workDpi >= DisplayGeometryUtil.MIN_DPI && workDpi <= DisplayGeometryUtil.MAX_DPI) {
                this.canvasDpi = workDpi;
            }
        }
        saveMainConf();
    }

    public WindowProfile getSavedProfile(String pkg) {
        if (!PackagePolicy.isValidPackageName(pkg)) return null;
        synchronized (lock) {
            return appProfiles.get(pkg);
        }
    }

    public void rememberProfile(String pkg, WindowProfile profile) {
        if (!PackagePolicy.isValidPackageName(pkg) || profile == null) return;
        synchronized (lock) {
            if (appProfiles.size() < 512 || appProfiles.containsKey(pkg)) {
                appProfiles.put(pkg, profile);
            }
        }
        saveAppProfiles();
    }

    public int getAppLaunchCount(String pkg) {
        if (!PackagePolicy.isValidPackageName(pkg)) return 0;
        synchronized (lock) {
            Integer c = appLaunchCounts.get(pkg);
            return c != null ? c : 0;
        }
    }

    public int recordAppLaunch(String pkg) {
        if (!PackagePolicy.isValidPackageName(pkg)) return 0;
        int next;
        synchronized (lock) {
            if (appLaunchCounts.size() >= 1024 && !appLaunchCounts.containsKey(pkg)) {
                return 0;
            }
            Integer prev = appLaunchCounts.get(pkg);
            next = (prev != null ? prev : 0) + 1;
            appLaunchCounts.put(pkg, next);
        }
        saveAppLaunchCounts();
        return next;
    }

    private void loadMainConf() {
        if (!confFile.isFile()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(confFile), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return;
            String[] parts = line.trim().split(",");
            if (parts.length < 5) return;
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            int dpi = Integer.parseInt(parts[2].trim());
            int maxVd = Integer.parseInt(parts[3].trim());
            int inMode = Integer.parseInt(parts[4].trim());
            synchronized (lock) {
                if (w >= 360 && h >= 360) {
                    canvasWidth = DisplayGeometryUtil.alignEven(w, 360, 4096);
                    canvasHeight = DisplayGeometryUtil.alignEven(h, 360, 4096);
                }
                canvasDpi = DisplayGeometryUtil.clampDpi(dpi);
                maxActiveDisplays = Math.max(MIN_MAX_VD, Math.min(HARD_MAX_VD, maxVd));
                if (inMode >= BridgeProtocol.INPUT_BACKEND_AUTO && inMode <= BridgeProtocol.INPUT_BACKEND_UHID) {
                    inputBackendMode = inMode;
                }
                if (parts.length >= 6) {
                    int fps = Integer.parseInt(parts[5].trim());
                    if (fps == 0 || (fps >= 30 && fps <= 165)) targetFps = fps;
                }
                if (parts.length >= 7) {
                    int dpiPct = Integer.parseInt(parts[6].trim());
                    if (dpiPct >= 70 && dpiPct <= 160) windowDpiScalePct = dpiPct;
                }
                if (parts.length >= 8) {
                    int mt = Integer.parseInt(parts[7].trim());
                    mouseTargetMode = (mt == BridgeProtocol.MOUSE_TARGET_LOCAL)
                            ? BridgeProtocol.MOUSE_TARGET_LOCAL
                            : BridgeProtocol.MOUSE_TARGET_EXTERNAL;
                }
                if (parts.length >= 9) {
                    int gDpi = Integer.parseInt(parts[8].trim());
                    globalCustomDpi = (gDpi >= DisplayGeometryUtil.MIN_DPI && gDpi <= DisplayGeometryUtil.MAX_DPI)
                            ? gDpi : 0;
                }
                if (parts.length >= 10) {
                    remoteMicEnabled = Integer.parseInt(parts[9].trim()) != 0;
                }
                if (parts.length >= 11) {
                    remoteCameraEnabled = Integer.parseInt(parts[10].trim()) != 0;
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("OPALDESK_CONF_LOAD_WARN: " + e.getMessage());
        }
    }

    public void saveMainConf() {
        String content;
        synchronized (lock) {
            content = canvasWidth + "," + canvasHeight + "," + canvasDpi + ","
                    + maxActiveDisplays + "," + inputBackendMode + ","
                    + targetFps + "," + windowDpiScalePct + "," + mouseTargetMode + ","
                    + globalCustomDpi + ","
                    + (remoteMicEnabled ? 1 : 0) + ","
                    + (remoteCameraEnabled ? 1 : 0) + "\n";
        }
        try {
            AtomicFiles.writeAtomicUtf8(confFile, content, AtomicFiles.MODE_OWNER_RW);
        } catch (IOException e) {
            System.err.println("OPALDESK_CONF_SAVE_WARN: " + e.getMessage());
        }
    }

    private void loadAppProfiles() {
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

    private void saveAppProfiles() {
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

    private void loadAppLaunchCounts() {
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

    private void saveAppLaunchCounts() {
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

    private void loadAppCustomDpis() {
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

    private void saveAppCustomDpis() {
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

    private void loadAppWindowBounds() {
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

    private void saveAppWindowBounds() {
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
