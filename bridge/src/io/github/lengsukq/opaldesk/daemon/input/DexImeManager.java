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
 * Crash-safe lifecycle manager for DeX IME locking and physical-keyboard soft-IME overrides.
 *
 * <p>When a DeX workspace (local desktop, external monitor, or OpalCast session) becomes active and
 * the user has configured a locked DeX IME, this manager atomically backs up the device's original
 * {@code default_input_method} (and optional {@code show_ime_with_hard_keyboard} value) before
 * switching. Exiting DeX, losing the helper process, shutting down the daemon, or restarting after
 * an unexpected crash automatically restores the user's original phone IME.
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
            reconcileLocked();
        }
    }

    public void setRemoteSessionActive(boolean active) {
        synchronized (lock) {
            if (remoteSessionActive == active) return;
            remoteSessionActive = active;
            reconcileLocked();
        }
    }

    public void onConfigUpdated() {
        synchronized (lock) {
            reconcileLocked();
        }
    }

    public void onDaemonShutdown() {
        synchronized (lock) {
            clientDexSessionActive = false;
            remoteSessionActive = false;
            restoreAllLocked();
        }
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

    /** Serializes the full authoritative IME & policy state for Helper UI inspection. */
    public String toStateJson(int effectiveWindowImePolicy, boolean physicalKeyboardDetected) {
        String currentIme = getCurrentDefaultImeIdSafe();
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
                    .put("bridgeImeActive", BridgeProtocol.OPALDESK_BRIDGE_IME_ID.equals(currentIme))
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

    /**
     * Resolves the effective target IME ID to lock for the current session state:
     * <ul>
     *   <li>During remote OpalCast sessions with {@code autoBridgeImeOnCast} enabled (unless the user
     *       explicitly chose {@code ALWAYS_LOCAL} with a specific third-party IME), automatically locks
     *       OpalDesk's built-in invisible channel IME ({@link BridgeProtocol#OPALDESK_BRIDGE_IME_ID})
     *       for zero-clipboard direct {@code InputConnection.commitText()} and zero host popup.</li>
     *   <li>Otherwise honors the user's configured {@code lockedImeId} whenever a DeX or remote
     *       session is active.</li>
     * </ul>
     */
    public static String resolveDesiredImeId(
            boolean clientDexSessionActive,
            boolean remoteSessionActive,
            int dexImeDisplayMode,
            String lockedImeId,
            boolean autoBridgeImeOnCast) {
        String cleanLocked = (lockedImeId != null) ? lockedImeId.trim() : "";
        if (remoteSessionActive && autoBridgeImeOnCast) {
            boolean explicitLocalThirdParty = (dexImeDisplayMode == BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL)
                    && !cleanLocked.isEmpty();
            if (!explicitLocalThirdParty) {
                return BridgeProtocol.OPALDESK_BRIDGE_IME_ID;
            }
        }
        if ((clientDexSessionActive || remoteSessionActive) && !cleanLocked.isEmpty()) {
            return cleanLocked;
        }
        return "";
    }

    public boolean isBridgeImeCurrentlyActive() {
        return BridgeProtocol.OPALDESK_BRIDGE_IME_ID.equals(getCurrentDefaultImeIdSafe());
    }

    private void reconcileLocked() {
        if (config == null || bridge == null) return;
        boolean sessionActive = clientDexSessionActive || remoteSessionActive;
        reconcileLockedImeLocked(sessionActive);
        reconcileHardKeyboardSoftImeLocked(sessionActive);
        syncBackupFileLocked();
    }

    private void reconcileLockedImeLocked(boolean sessionActive) {
        String desiredIme = resolveDesiredImeId(
                clientDexSessionActive,
                remoteSessionActive,
                config.getDexImeDisplayMode(),
                config.getDexLockedImeId(),
                config.isAutoBridgeImeOnCast());
        if (!sessionActive || desiredIme.isEmpty()) {
            restoreImeOnlyLocked();
            return;
        }
        String currentIme = safeTrim(bridge.getDefaultInputMethodId());
        if (backedUpOriginalImeId.isEmpty() && DaemonConfig.PackagePolicy.isValidImeId(currentIme)) {
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
