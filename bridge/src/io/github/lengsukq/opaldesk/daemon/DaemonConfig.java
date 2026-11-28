package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.input.InputBindingRule;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
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
    public static final String DEFAULT_IME_CONF_PATH = "/data/local/tmp/opaldesk_ime.conf";
    public static final String DEFAULT_IME_BACKUP_PATH = "/data/local/tmp/opaldesk_ime_backup.conf";
    public static final String DEFAULT_INPUT_BINDINGS_PATH = "/data/local/tmp/opaldesk_input_bindings.json";
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
        private static final Pattern IME_ID_PATTERN = Pattern.compile(
                "^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+/(\\.?[a-zA-Z0-9_$]+(\\.[a-zA-Z0-9_$]+)*)$");

        private static final Set<String> CORE_BLOCKED_PKGS = Set.of(
                "android",
                BridgeBuildConfig.HELPER_PACKAGE,
                "com.android.systemui",
                "com.android.phone",
                "com.android.server.telecom"
        );

        /**
         * Standard AOSP system utility packages that do not play background media and should not be
         * killed via {@code am force-stop} on window close (avoids disrupting system services or
         * unrelated host Display 0 tasks).
         */
        private static final Set<String> NO_FORCE_STOP_ON_CLOSE_PKGS = Set.of(
                "com.android.settings"
        );

        private PackagePolicy() {}

        public static boolean isValidPackageName(String pkg) {
            return pkg != null && pkg.length() >= 3 && pkg.length() <= 160 && PKG_PATTERN.matcher(pkg).matches();
        }

        public static boolean isValidClassName(String cls) {
            return cls != null && cls.length() >= 3 && cls.length() <= 220 && CLS_PATTERN.matcher(cls).matches();
        }

        public static boolean isValidImeId(String imeId) {
            return imeId != null && imeId.length() >= 5 && imeId.length() <= 240 && IME_ID_PATTERN.matcher(imeId).matches();
        }

        public static boolean isBlockedWindowPkg(String pkg) {
            if (!isValidPackageName(pkg) || CORE_BLOCKED_PKGS.contains(pkg)) return true;
            return pkg.contains("permissioncontroller")
                    || pkg.contains("packageinstaller")
                    || pkg.contains("keyguard");
        }

        public static boolean shouldSkipForceStopOnClose(String pkg) {
            return pkg != null && NO_FORCE_STOP_ON_CLOSE_PKGS.contains(pkg);
        }
    }

    private final Object lock = new Object();
    private final Object ioLock = new Object();
    private final File confFile;
    private final File imeBackupFile;
    private final WindowProfileStore profileStore;
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
    private int windowSupersampleMode = DisplayGeometryUtil.WINDOW_SUPERSAMPLE_COMPAT;
    private int globalCustomDpi = 0; // 0 = auto (use profile + scalePct), 120..560 = fixed global DPI
    private int mouseTargetMode = BridgeProtocol.MOUSE_TARGET_EXTERNAL;
    private boolean rootEnhanced = false;
    private boolean secureCaptureEnabled = false;
    private boolean remoteMicEnabled = false;
    private boolean remoteCameraEnabled = false;
    private int audioRouteMode = BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP;
    private int dexImeDisplayMode = BridgeProtocol.DEX_IME_DISPLAY_AUTO;
    private String dexLockedImeId = "";
    private boolean muteHostImeOnCast = false;
    private int showImeWithHardKeyboard = BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM;
    private boolean autoBridgeImeOnCast = true;
    private String inputBindingsJson = InputBindingRule.toJsonList(InputBindingRule.defaultRules());

    public DaemonConfig() {
        this(new File(DEFAULT_CONF_PATH), new File(DEFAULT_PROFILE_CONF_PATH), new File(DEFAULT_LAUNCH_COUNTS_PATH));
    }

    public DaemonConfig(File confFile, File profileFile) {
        this(confFile, profileFile, deriveSiblingFile(confFile, "opaldesk_app_counts.conf", DEFAULT_LAUNCH_COUNTS_PATH));
    }

    public DaemonConfig(File confFile, File profileFile, File launchCountsFile) {
        this.confFile = (confFile != null) ? confFile : new File(DEFAULT_CONF_PATH);
        File resolvedProfileFile = (profileFile != null) ? profileFile : new File(DEFAULT_PROFILE_CONF_PATH);
        File resolvedCountsFile = (launchCountsFile != null) ? launchCountsFile : new File(DEFAULT_LAUNCH_COUNTS_PATH);
        File appDpisFile = deriveSiblingFile(this.confFile, "opaldesk_app_dpis.conf", DEFAULT_APP_DPIS_PATH);
        File appBoundsFile = deriveSiblingFile(this.confFile, "opaldesk_app_bounds.conf", DEFAULT_APP_BOUNDS_PATH);
        File imeConfFile = deriveSiblingFile(this.confFile, "opaldesk_ime.conf", DEFAULT_IME_CONF_PATH);
        File inputBindingsFile = deriveSiblingFile(
                this.confFile, "opaldesk_input_bindings.json", DEFAULT_INPUT_BINDINGS_PATH);
        this.imeBackupFile = deriveSiblingFile(this.confFile, "opaldesk_ime_backup.conf", DEFAULT_IME_BACKUP_PATH);
        this.profileStore = new WindowProfileStore(
                lock, ioLock, resolvedProfileFile, resolvedCountsFile,
                appDpisFile, appBoundsFile, imeConfFile, inputBindingsFile);
    }

    private static File deriveSiblingFile(File confFile, String fileName, String defaultPath) {
        if (confFile != null && confFile.getParentFile() != null) {
            return new File(confFile.getParentFile(), fileName);
        }
        return new File(defaultPath);
    }

    public void loadAll() {
        loadMainConf();
        profileStore.loadAppProfiles(appProfiles);
        profileStore.loadAppLaunchCounts(appLaunchCounts);
        profileStore.loadAppCustomDpis(appCustomDpis);
        profileStore.loadAppWindowBounds(appWindowBounds);
        WindowProfileStore.ImeConfSnapshot imeSnap = profileStore.loadImeConf();
        if (imeSnap != null) {
            synchronized (lock) {
                dexImeDisplayMode = imeSnap.displayMode;
                dexLockedImeId = imeSnap.lockedImeId;
                muteHostImeOnCast = imeSnap.muteHostOnCast;
                showImeWithHardKeyboard = imeSnap.showWithHardKbd;
                autoBridgeImeOnCast = imeSnap.autoBridgeImeOnCast;
            }
        }
        String loadedBindings = profileStore.loadInputBindingsJson();
        if (loadedBindings != null && !loadedBindings.isEmpty()) {
            try {
                List<InputBindingRule> parsed = InputBindingRule.fromJsonList(loadedBindings);
                synchronized (lock) {
                    inputBindingsJson = InputBindingRule.toJsonList(parsed);
                }
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_INPUT_BINDINGS_PARSE_WARN: " + e.getMessage());
            }
        }
    }

    public String getInputBindingsJson() {
        synchronized (lock) { return inputBindingsJson; }
    }

    public String setInputBindingsJson(String rawJson) {
        String normalized;
        if (rawJson == null || rawJson.trim().isEmpty()) {
            normalized = InputBindingRule.toJsonList(InputBindingRule.defaultRules());
        } else {
            List<InputBindingRule> parsed = InputBindingRule.fromJsonList(rawJson);
            normalized = InputBindingRule.toJsonList(parsed);
        }
        synchronized (lock) {
            this.inputBindingsJson = normalized;
        }
        profileStore.saveInputBindingsJson(normalized);
        return normalized;
    }

    public File getImeBackupFile() {
        return imeBackupFile;
    }

    public int getDexImeDisplayMode() {
        synchronized (lock) { return dexImeDisplayMode; }
    }

    public String getDexLockedImeId() {
        synchronized (lock) { return dexLockedImeId; }
    }

    public boolean isMuteHostImeOnCast() {
        synchronized (lock) { return muteHostImeOnCast; }
    }

    public int getShowImeWithHardKeyboard() {
        synchronized (lock) { return showImeWithHardKeyboard; }
    }

    public boolean isAutoBridgeImeOnCast() {
        synchronized (lock) { return autoBridgeImeOnCast; }
    }

    public void updateDexImeConfig(
            int displayMode,
            String lockedImeId,
            boolean muteHostOnCast,
            int showWithHardKbd) {
        updateDexImeConfig(displayMode, lockedImeId, muteHostOnCast, showWithHardKbd, isAutoBridgeImeOnCast());
    }

    public void updateDexImeConfig(
            int displayMode,
            String lockedImeId,
            boolean muteHostOnCast,
            int showWithHardKbd,
            boolean autoBridgeOnCast) {
        int clampedMode = (displayMode >= BridgeProtocol.DEX_IME_DISPLAY_AUTO
                && displayMode <= BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST)
                ? displayMode
                : BridgeProtocol.DEX_IME_DISPLAY_AUTO;
        String cleanIme = (lockedImeId != null) ? lockedImeId.trim() : "";
        if (!cleanIme.isEmpty() && !PackagePolicy.isValidImeId(cleanIme)) {
            cleanIme = "";
        }
        int clampedHardKbd = (showWithHardKbd == BridgeProtocol.HARD_KBD_SOFT_IME_HIDE
                || showWithHardKbd == BridgeProtocol.HARD_KBD_SOFT_IME_SHOW)
                ? showWithHardKbd
                : BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM;
        WindowProfileStore.ImeConfSnapshot snap;
        synchronized (lock) {
            this.dexImeDisplayMode = clampedMode;
            this.dexLockedImeId = cleanIme;
            this.muteHostImeOnCast = muteHostOnCast;
            this.showImeWithHardKeyboard = clampedHardKbd;
            this.autoBridgeImeOnCast = autoBridgeOnCast;
            snap = new WindowProfileStore.ImeConfSnapshot(
                    dexImeDisplayMode, dexLockedImeId, muteHostImeOnCast, showImeWithHardKeyboard, autoBridgeImeOnCast);
        }
        profileStore.saveImeConf(snap);
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

    public int getWindowSupersampleMode() {
        synchronized (lock) { return windowSupersampleMode; }
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
        profileStore.saveAppCustomDpis(appCustomDpis);
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
        profileStore.saveAppCustomDpis(appCustomDpis);
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
        profileStore.saveAppWindowBounds(appWindowBounds);
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
        profileStore.saveAppWindowBounds(appWindowBounds);
    }

    public Map<String, SavedWindowBounds> getAllSavedWindowBoundsSnapshot() {
        synchronized (lock) {
            return new LinkedHashMap<>(appWindowBounds);
        }
    }

    public String toAdvancedStateJson() {
        synchronized (lock) {
            return WindowProfileStore.toAdvancedStateJson(globalCustomDpi, appCustomDpis, appWindowBounds);
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
        updateDisplayPrefs(reqTargetFps, reqDpiScalePct, -1);
    }

    public void updateDisplayPrefs(int reqTargetFps, int reqDpiScalePct, int reqSupersampleMode) {
        synchronized (lock) {
            if (reqTargetFps == 0 || (reqTargetFps >= 30 && reqTargetFps <= 165)) {
                this.targetFps = reqTargetFps;
            }
            if (reqDpiScalePct >= 70 && reqDpiScalePct <= 160) {
                this.windowDpiScalePct = reqDpiScalePct;
            }
            if (reqSupersampleMode >= 0) {
                this.windowSupersampleMode = DisplayGeometryUtil.clampWindowSupersampleMode(reqSupersampleMode);
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
        synchronized (lock) { return rootEnhanced && remoteCameraEnabled; }
    }

    public boolean setRemoteMediaConfig(boolean micEnabled, boolean cameraEnabled) {
        boolean accepted;
        synchronized (lock) {
            this.remoteMicEnabled = micEnabled;
            accepted = !cameraEnabled || this.rootEnhanced;
            this.remoteCameraEnabled = this.rootEnhanced && cameraEnabled;
        }
        saveMainConf();
        return accepted;
    }

    public int getAudioRouteMode() {
        synchronized (lock) { return audioRouteMode; }
    }

    public void setAudioRouteMode(int mode) {
        int clamped = (mode == BridgeProtocol.AUDIO_ROUTE_ALL_TO_DEX)
                ? BridgeProtocol.AUDIO_ROUTE_ALL_TO_DEX
                : BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP;
        synchronized (lock) {
            this.audioRouteMode = clamped;
        }
        saveMainConf();
    }

    public void markRootEnhanced(boolean enhanced, boolean secureCapture) {
        synchronized (lock) {
            this.rootEnhanced = enhanced;
            this.secureCaptureEnabled = secureCapture;
            if (!enhanced) {
                this.remoteCameraEnabled = false;
            }
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
        profileStore.saveAppProfiles(appProfiles);
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
        profileStore.saveAppLaunchCounts(appLaunchCounts);
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
                if (parts.length >= 12) {
                    int arm = Integer.parseInt(parts[11].trim());
                    audioRouteMode = (arm == BridgeProtocol.AUDIO_ROUTE_ALL_TO_DEX)
                            ? BridgeProtocol.AUDIO_ROUTE_ALL_TO_DEX
                            : BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP;
                }
                if (parts.length >= 13) {
                    windowSupersampleMode = DisplayGeometryUtil.clampWindowSupersampleMode(
                            Integer.parseInt(parts[12].trim()));
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("OPALDESK_CONF_LOAD_WARN: " + e.getMessage());
        }
    }

    public void saveMainConf() {
        synchronized (ioLock) {
            String content;
            synchronized (lock) {
                content = canvasWidth + "," + canvasHeight + "," + canvasDpi + ","
                        + maxActiveDisplays + "," + inputBackendMode + ","
                        + targetFps + "," + windowDpiScalePct + "," + mouseTargetMode + ","
                        + globalCustomDpi + ","
                        + (remoteMicEnabled ? 1 : 0) + ","
                        + (remoteCameraEnabled ? 1 : 0) + ","
                        + audioRouteMode + ","
                        + windowSupersampleMode + "\n";
            }
            try {
                AtomicFiles.writeAtomicUtf8(confFile, content, AtomicFiles.MODE_OWNER_RW);
            } catch (IOException e) {
                System.err.println("OPALDESK_CONF_SAVE_WARN: " + e.getMessage());
            }
        }
    }
}
