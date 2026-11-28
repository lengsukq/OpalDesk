package io.github.lengsukq.opaldesk.daemon.input;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;
import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Crash-safe lifecycle manager for DeX IME locking, optional cert-pinned YuyanIme plugin
 * auto-activation, Display 0 smart handoff, and physical-keyboard soft-IME overrides.
 *
 * <p>Pure Java 11 with zero hard Android Binder dependencies so every transition and crash-recovery
 * path is verified deterministically in JVM unit tests.
 */
public final class DexImeManager {
    public interface SystemImeBridge {
        String getDefaultInputMethodId();
        List<String> listEnabledInputMethodIds();
        boolean setDefaultInputMethodId(String imeId);
        int getShowImeWithHardKeyboard();
        boolean setShowImeWithHardKeyboard(int value);
        default boolean isImePluginVerifiedAndInstalled() {
            return false;
        }
    }

    private final Object lock = new Object();
    private final DaemonConfig config;
    private final SystemImeBridge bridge;
    private final File backupFile;

    private boolean clientDexSessionActive = false;
    private boolean remoteSessionActive = false;
    private String backedUpOriginalImeId = "";
    private int backedUpOriginalHardKbd = BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM;
    private boolean imeLockApplied = false;
    private boolean hardKbdOverrideApplied = false;
    private volatile boolean yieldedToDisplay0 = false;

    public DexImeManager(DaemonConfig config, SystemImeBridge bridge) {
        this.config = config;
        this.bridge = bridge;
        this.backupFile = (config != null) ? config.getImeBackupFile() : new File(DaemonConfig.DEFAULT_IME_BACKUP_PATH);
    }

    /**
     * Restores any IME / hard-keyboard override left behind by a previously crashed daemon process
     * before a new DeX session starts.
     */
    public void recoverFromPreviousCrashIfNeeded() {
        synchronized (lock) {
            if (!backupFile.isFile()) return;
            loadBackupLocked();
            if (clientDexSessionActive || remoteSessionActive) {
                reconcileLocked();
                return;
            }
            String origIme = backedUpOriginalImeId;
            int origHardKbd = backedUpOriginalHardKbd;
            if (!origIme.isEmpty() || origHardKbd == 0 || origHardKbd == 1) {
                System.out.println("OPALDESK_IME_CRASH_RECOVERY_START origIme=" + origIme
                        + " origHardKbd=" + origHardKbd);
                restoreAllLocked();
            } else {
                deleteBackupQuietly();
            }
        }
    }

    public void setClientDexSessionActive(boolean active) {
        synchronized (lock) {
            if (clientDexSessionActive == active) return;
            clientDexSessionActive = active;
            if (!clientDexSessionActive && !remoteSessionActive) {
                yieldedToDisplay0 = false;
            }
            reconcileLocked();
        }
    }

    public void setRemoteSessionActive(boolean active) {
        synchronized (lock) {
            if (remoteSessionActive == active) return;
            remoteSessionActive = active;
            if (!clientDexSessionActive && !remoteSessionActive) {
                yieldedToDisplay0 = false;
            }
            reconcileLocked();
        }
    }

    public void onConfigUpdated() {
        synchronized (lock) {
            yieldedToDisplay0 = false;
            reconcileLocked();
        }
    }

    /** Reconciles IME state immediately when the optional YuyanIme plugin APK is installed or removed. */
    public void onPluginPackageChanged() {
        synchronized (lock) {
            yieldedToDisplay0 = false;
            reconcileLocked();
        }
    }

    public void onDaemonShutdown() {
        synchronized (lock) {
            clientDexSessionActive = false;
            remoteSessionActive = false;
            yieldedToDisplay0 = false;
            restoreAllLocked();
        }
    }

    /**
     * Temporarily hands Display 0 back to the user's original phone IME (e.g. Sogou / WeType / Gboard)
     * while an external monitor or wireless cast session remains active in the background, preserving
     * the crash-recovery backup so the next DeX click/keystroke seamlessly reclaims the DeX IME.
     *
     * @return the restored original IME ID, or {@code ""} if no yield was applicable.
     */
    public String yieldToDisplay0OriginalIme() {
        synchronized (lock) {
            if (!clientDexSessionActive && !remoteSessionActive) return "";
            String orig = backedUpOriginalImeId;
            if (orig.isEmpty()
                    || BridgeProtocol.isManagedOpalImeId(orig)
                    || bridge == null) {
                return "";
            }
            yieldedToDisplay0 = true;
            String cur = safeTrim(bridge.getDefaultInputMethodId());
            if (!orig.equals(cur)) {
                boolean ok = bridge.setDefaultInputMethodId(orig);
                System.out.println("OPALDESK_IME_YIELD_DISPLAY0 orig=" + orig + " prev=" + cur + " ok=" + ok);
                if (!ok) {
                    yieldedToDisplay0 = false;
                    return "";
                }
            }
            return orig;
        }
    }

    /**
     * Fast-path check called on {@code ACTION_DOWN} / {@code KeyEvent} targeting any DeX or OpalCast
     * display: if Display 0 had temporarily borrowed the phone's original IME, switches back to the
     * desired DeX IME before {@code ACTION_UP} triggers {@code showSoftInput()}.
     */
    public boolean reclaimBridgeImeIfYielded() {
        if (!yieldedToDisplay0) return false;
        synchronized (lock) {
            if (!yieldedToDisplay0) return false;
            yieldedToDisplay0 = false;
            reconcileLocked();
            System.out.println("OPALDESK_IME_RECLAIMED_FOR_DEX active=" + getCurrentDefaultImeIdSafe());
            return true;
        }
    }

    public boolean isYieldedToDisplay0() {
        return yieldedToDisplay0;
    }

    public boolean isDexSessionActive() {
        synchronized (lock) {
            return clientDexSessionActive || remoteSessionActive;
        }
    }

    public boolean isImeLockApplied() {
        synchronized (lock) {
            return imeLockApplied;
        }
    }

    public String getBackedUpOriginalImeId() {
        synchronized (lock) {
            return backedUpOriginalImeId;
        }
    }

    public String getCurrentDefaultImeIdSafe() {
        if (bridge == null) return "";
        String val = bridge.getDefaultInputMethodId();
        return (val != null) ? val.trim() : "";
    }

    public boolean isPluginImeInstalledSafe() {
        return bridge != null && bridge.isImePluginVerifiedAndInstalled();
    }

    /** Serializes the full authoritative IME & policy state for Helper UI inspection. */
    public String toStateJson(int effectiveWindowImePolicy, boolean physicalKeyboardDetected) {
        String currentIme = getCurrentDefaultImeIdSafe();
        boolean pluginInstalled = isPluginImeInstalledSafe();
        List<String> enabledList = (bridge != null) ? bridge.listEnabledInputMethodIds() : Collections.emptyList();
        if (enabledList == null) enabledList = Collections.emptyList();

        SimpleJson.ArrayBuilder enabledArr = new SimpleJson.ArrayBuilder();
        for (String id : enabledList) {
            if (id != null && !id.isEmpty()) {
                enabledArr.add(id);
            }
        }

        synchronized (lock) {
            return new SimpleJson.ObjectBuilder()
                    .put("currentImeId", currentIme)
                    .put("originalImeId", backedUpOriginalImeId)
                    .put("lockedImeId", config != null ? config.getDexLockedImeId() : "")
                    .put("bridgeImeId", BridgeProtocol.OPALDESK_BRIDGE_IME_ID)
                    .put("pluginImeId", BridgeProtocol.OPALDESK_IME_PLUGIN_ID)
                    .put("pluginImeInstalled", pluginInstalled)
                    .put("pluginImeActive", BridgeProtocol.OPALDESK_IME_PLUGIN_ID.equals(currentIme))
                    .put("bridgeImeActive", BridgeProtocol.isManagedOpalImeId(currentIme))
                    .put("yieldedToDisplay0", yieldedToDisplay0)
                    .put("autoBridgeImeOnCast", config != null && config.isAutoBridgeImeOnCast())
                    .put("imeLockApplied", imeLockApplied)
                    .put("hardKbdOverrideApplied", hardKbdOverrideApplied)
                    .put("dexSessionActive", clientDexSessionActive || remoteSessionActive)
                    .put("dexImeDisplayMode", config != null ? config.getDexImeDisplayMode() : BridgeProtocol.DEX_IME_DISPLAY_AUTO)
                    .put("muteHostImeOnCast", config != null && config.isMuteHostImeOnCast())
                    .put("showImeWithHardKeyboard", config != null ? config.getShowImeWithHardKeyboard() : BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM)
                    .put("effectiveWindowImePolicy", effectiveWindowImePolicy)
                    .put("physicalKeyboardDetected", physicalKeyboardDetected)
                    .putRaw("enabledImeIds", enabledArr.build())
                    .build();
        }
    }

    /** Backward-compatible 5-arg overload assuming the optional plugin APK is not installed. */
    public static String resolveDesiredImeId(
            boolean clientDexSessionActive,
            boolean remoteSessionActive,
            int dexImeDisplayMode,
            String lockedImeId,
            boolean autoBridgeImeOnCast) {
        return resolveDesiredImeId(
                clientDexSessionActive,
                remoteSessionActive,
                dexImeDisplayMode,
                lockedImeId,
                autoBridgeImeOnCast,
                false);
    }

    /**
     * Resolves the effective target IME ID to lock for the current session state:
     * <ul>
     *   <li><b>Optional YuyanIme Companion Plugin installed ({@code pluginImeInstalled == true})</b>:
     *       In {@code AUTO} and {@code ALWAYS_LOCAL} DeX/Cast sessions (unless the user explicitly
     *       locked a different third-party IME), automatically activates
     *       {@link BridgeProtocol#OPALDESK_IME_PLUGIN_ID}.</li>
     *   <li><b>Plugin NOT installed ({@code pluginImeInstalled == false})</b>:
     *       <ul>
     *         <li>During remote OpalCast sessions with {@code autoBridgeImeOnCast}: activates the
     *             zero-UI {@link BridgeProtocol#OPALDESK_BRIDGE_IME_ID} pipe so remote PC/Tablet
     *             receivers get direct UTF-8 commits without phone keyboard popups.</li>
     *         <li>During local/external DeX sessions: honors any user-selected {@code lockedImeId},
     *             otherwise returns {@code ""} so the user's native system IME remains untouched.</li>
     *       </ul>
     *   </li>
     * </ul>
     */
    public static String resolveDesiredImeId(
            boolean clientDexSessionActive,
            boolean remoteSessionActive,
            int dexImeDisplayMode,
            String lockedImeId,
            boolean autoBridgeImeOnCast,
            boolean pluginImeInstalled) {
        if (!clientDexSessionActive && !remoteSessionActive) {
            return "";
        }
        String cleanLocked = (lockedImeId != null) ? lockedImeId.trim() : "";
        if (BridgeProtocol.OPALDESK_IME_PLUGIN_ID.equals(cleanLocked) && !pluginImeInstalled) {
            cleanLocked = "";
        }

        if (remoteSessionActive && autoBridgeImeOnCast) {
            boolean explicitLocalThirdParty = (dexImeDisplayMode == BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL)
                    && !cleanLocked.isEmpty()
                    && !BridgeProtocol.isManagedOpalImeId(cleanLocked);
            if (!explicitLocalThirdParty) {
                if (BridgeProtocol.OPALDESK_IME_PLUGIN_ID.equals(cleanLocked) && pluginImeInstalled) {
                    return BridgeProtocol.OPALDESK_IME_PLUGIN_ID;
                }
                // During remote casting (Mac / Windows / Android tablet receiver), always use the
                // zero-UI OpalDeskImeService pipe so the receiver's own local IME handles CJK
                // composition and commits UTF-8 cleanly without double-IME interception on the phone.
                return BridgeProtocol.OPALDESK_BRIDGE_IME_ID;
            }
        }

        if (!cleanLocked.isEmpty()) {
            return cleanLocked;
        }

        if (pluginImeInstalled
                && autoBridgeImeOnCast
                && dexImeDisplayMode != BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST) {
            return BridgeProtocol.OPALDESK_IME_PLUGIN_ID;
        }
        return "";
    }

    public boolean isBridgeImeCurrentlyActive() {
        return BridgeProtocol.isManagedOpalImeId(getCurrentDefaultImeIdSafe());
    }

    public boolean isBridgeImeConfiguredOrActive() {
        synchronized (lock) {
            if (yieldedToDisplay0) return true;
        }
        return isBridgeImeCurrentlyActive();
    }

    private void reconcileLocked() {
        if (config == null || bridge == null) return;
        boolean sessionActive = clientDexSessionActive || remoteSessionActive;
        if (yieldedToDisplay0 && sessionActive) {
            syncBackupFileLocked();
            return;
        }
        reconcileLockedImeLocked(sessionActive);
        reconcileHardKeyboardSoftImeLocked(sessionActive);
        syncBackupFileLocked();
    }

    private void reconcileLockedImeLocked(boolean sessionActive) {
        boolean pluginInstalled = bridge.isImePluginVerifiedAndInstalled();
        String desiredIme = resolveDesiredImeId(
                clientDexSessionActive,
                remoteSessionActive,
                config.getDexImeDisplayMode(),
                config.getDexLockedImeId(),
                config.isAutoBridgeImeOnCast(),
                pluginInstalled);
        if (!sessionActive || desiredIme.isEmpty()) {
            restoreImeOnlyLocked();
            return;
        }
        String currentIme = safeTrim(bridge.getDefaultInputMethodId());
        if (backedUpOriginalImeId.isEmpty()
                && DaemonConfig.PackagePolicy.isValidImeId(currentIme)
                && !BridgeProtocol.isManagedOpalImeId(currentIme)) {
            backedUpOriginalImeId = currentIme;
            // Persist backup atomically BEFORE switching system IME so crash recovery always knows the original.
            writeBackupLocked();
        }
        if (desiredIme.equals(currentIme)) {
            imeLockApplied = true;
            return;
        }
        boolean ok = bridge.setDefaultInputMethodId(desiredIme);
        imeLockApplied = ok;
        System.out.println("OPALDESK_IME_LOCK_APPLY target=" + desiredIme
                + " prev=" + currentIme
                + " orig=" + backedUpOriginalImeId
                + " pluginInstalled=" + pluginInstalled
                + " ok=" + ok);
        if (!ok && backedUpOriginalImeId.equals(currentIme)) {
            // Switch failed while still on original IME — no active override to keep backed up.
            backedUpOriginalImeId = "";
        }
    }

    private void reconcileHardKeyboardSoftImeLocked(boolean sessionActive) {
        int desired = config.getShowImeWithHardKeyboard();
        boolean wantOverride = sessionActive
                && (desired == BridgeProtocol.HARD_KBD_SOFT_IME_HIDE
                || desired == BridgeProtocol.HARD_KBD_SOFT_IME_SHOW);
        if (!wantOverride) {
            restoreHardKbdOnlyLocked();
            return;
        }
        int current = bridge.getShowImeWithHardKeyboard();
        if (backedUpOriginalHardKbd < 0) {
            backedUpOriginalHardKbd = (current == 0 || current == 1) ? current : 0;
            writeBackupLocked();
        }
        if (current == desired) {
            hardKbdOverrideApplied = true;
            return;
        }
        boolean ok = bridge.setShowImeWithHardKeyboard(desired);
        hardKbdOverrideApplied = ok;
        System.out.println("OPALDESK_IME_HARD_KBD_APPLY desired=" + desired
                + " orig=" + backedUpOriginalHardKbd + " ok=" + ok);
        if (!ok && current == backedUpOriginalHardKbd) {
            backedUpOriginalHardKbd = BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM;
        }
    }

    private void restoreAllLocked() {
        restoreImeOnlyLocked();
        restoreHardKbdOnlyLocked();
        syncBackupFileLocked();
    }

    private void restoreImeOnlyLocked() {
        String orig = backedUpOriginalImeId;
        backedUpOriginalImeId = "";
        imeLockApplied = false;
        if (orig.isEmpty() || bridge == null) return;
        String cur = safeTrim(bridge.getDefaultInputMethodId());
        if (orig.equals(cur)) {
            System.out.println("OPALDESK_IME_LOCK_RESTORE_SKIP already=" + orig);
            return;
        }
        boolean ok = bridge.setDefaultInputMethodId(orig);
        System.out.println("OPALDESK_IME_LOCK_RESTORED target=" + orig + " prev=" + cur + " ok=" + ok);
    }

    private void restoreHardKbdOnlyLocked() {
        int orig = backedUpOriginalHardKbd;
        backedUpOriginalHardKbd = BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM;
        hardKbdOverrideApplied = false;
        if ((orig != 0 && orig != 1) || bridge == null) return;
        int cur = bridge.getShowImeWithHardKeyboard();
        if (cur == orig) return;
        boolean ok = bridge.setShowImeWithHardKeyboard(orig);
        System.out.println("OPALDESK_IME_HARD_KBD_RESTORED value=" + orig + " ok=" + ok);
    }

    private void syncBackupFileLocked() {
        if (backedUpOriginalImeId.isEmpty() && backedUpOriginalHardKbd < 0) {
            deleteBackupQuietly();
        } else {
            writeBackupLocked();
        }
    }

    private void loadBackupLocked() {
        if (!backupFile.isFile()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(backupFile), StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                int eq = ln.indexOf('=');
                if (eq <= 0) continue;
                String k = ln.substring(0, eq).trim();
                String v = ln.substring(eq + 1).trim();
                if ("originalImeId".equals(k) && DaemonConfig.PackagePolicy.isValidImeId(v)) {
                    backedUpOriginalImeId = v;
                } else if ("originalShowWithHardKbd".equals(k)) {
                    try {
                        int parsed = Integer.parseInt(v);
                        if (parsed == 0 || parsed == 1) {
                            backedUpOriginalHardKbd = parsed;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("OPALDESK_IME_BACKUP_LOAD_WARN: " + e.getMessage());
        }
    }

    private void writeBackupLocked() {
        String content = "originalImeId=" + backedUpOriginalImeId + "\n"
                + "originalShowWithHardKbd=" + backedUpOriginalHardKbd + "\n";
        try {
            AtomicFiles.writeAtomicUtf8(backupFile, content, AtomicFiles.MODE_OWNER_RW);
        } catch (IOException e) {
            System.err.println("OPALDESK_IME_BACKUP_SAVE_WARN: " + e.getMessage());
        }
    }

    private void deleteBackupQuietly() {
        if (backupFile.exists() && !backupFile.delete()) {
            System.err.println("OPALDESK_IME_BACKUP_DELETE_WARN path=" + backupFile.getAbsolutePath());
        }
    }

    private static String safeTrim(String s) {
        return (s != null) ? s.trim() : "";
    }
}
