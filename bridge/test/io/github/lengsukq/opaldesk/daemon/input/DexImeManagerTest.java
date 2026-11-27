package io.github.lengsukq.opaldesk.daemon.input;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DexImeManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String IME_SOGOU = "com.sohu.inputmethod.sogou/.SogouIME";
    private static final String IME_GBOARD = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME";
    private static final String IME_WETYPE = "com.tencent.wetype/.plugin.hld.WxHldService";

    private static final class FakeImeBridge implements DexImeManager.SystemImeBridge {
        String currentIme = IME_SOGOU;
        List<String> enabledImes = Arrays.asList(IME_SOGOU, IME_GBOARD, IME_WETYPE);
        int showWithHardKbd = 0;
        boolean failSetIme = false;
        int setImeCalls = 0;
        int setHardKbdCalls = 0;

        @Override
        public String getDefaultInputMethodId() {
            return currentIme;
        }

        @Override
        public List<String> listEnabledInputMethodIds() {
            return enabledImes;
        }

        @Override
        public boolean setDefaultInputMethodId(String imeId) {
            setImeCalls++;
            if (failSetIme) return false;
            currentIme = imeId;
            return true;
        }

        @Override
        public int getShowImeWithHardKeyboard() {
            return showWithHardKbd;
        }

        @Override
        public boolean setShowImeWithHardKeyboard(int value) {
            setHardKbdCalls++;
            showWithHardKbd = value;
            return true;
        }
    }

    private DaemonConfig newTempConfig() throws Exception {
        File dir = tmp.newFolder();
        return new DaemonConfig(new File(dir, "opaldesk_v2.conf"), new File(dir, "opaldesk_profiles.conf"));
    }

    @Test
    public void validatesAndroidImeComponentIdsStrictly() {
        assertTrue(DaemonConfig.PackagePolicy.isValidImeId(IME_SOGOU));
        assertTrue(DaemonConfig.PackagePolicy.isValidImeId(IME_GBOARD));
        assertTrue(DaemonConfig.PackagePolicy.isValidImeId(IME_WETYPE));
        assertTrue(DaemonConfig.PackagePolicy.isValidImeId("com.baidu.input_mi/.ImeService$Main"));

        assertFalse(DaemonConfig.PackagePolicy.isValidImeId(""));
        assertFalse(DaemonConfig.PackagePolicy.isValidImeId(null));
        assertFalse(DaemonConfig.PackagePolicy.isValidImeId("com.example.pkg"));
        assertFalse(DaemonConfig.PackagePolicy.isValidImeId("com.example.pkg/; rm -rf /"));
        assertFalse(DaemonConfig.PackagePolicy.isValidImeId("com.example.pkg/.Svc\nfoo"));
        assertFalse(DaemonConfig.PackagePolicy.isValidImeId("bad/.Svc"));
    }

    @Test
    public void daemonConfigPersistsAndReloadsDexImeSettings() throws Exception {
        File dir = tmp.newFolder();
        File mainConf = new File(dir, "opaldesk_v2.conf");
        File profConf = new File(dir, "opaldesk_profiles.conf");

        DaemonConfig cfg = new DaemonConfig(mainConf, profConf);
        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL,
                IME_WETYPE,
                true,
                BridgeProtocol.HARD_KBD_SOFT_IME_HIDE);

        DaemonConfig reloaded = new DaemonConfig(mainConf, profConf);
        reloaded.loadAll();
        assertEquals(BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, reloaded.getDexImeDisplayMode());
        assertEquals(IME_WETYPE, reloaded.getDexLockedImeId());
        assertTrue(reloaded.isMuteHostImeOnCast());
        assertEquals(BridgeProtocol.HARD_KBD_SOFT_IME_HIDE, reloaded.getShowImeWithHardKeyboard());
    }

    @Test
    public void locksImeOnDexEnterPreservesOriginalAcrossLiveSwitchAndRestoresOnExit() throws Exception {
        DaemonConfig cfg = newTempConfig();
        FakeImeBridge bridge = new FakeImeBridge();
        DexImeManager mgr = new DexImeManager(cfg, bridge);

        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL,
                IME_GBOARD,
                false,
                BridgeProtocol.HARD_KBD_SOFT_IME_SHOW);
        mgr.onConfigUpdated();

        // Not in DeX yet -> phone IME and hard-keyboard setting stay untouched
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertEquals(0, bridge.showWithHardKbd);
        assertFalse(mgr.isImeLockApplied());
        assertFalse(cfg.getImeBackupFile().exists());

        // Enter DeX -> backup persisted, IME switches to Gboard, hard-kbd soft IME switches to SHOW (1)
        mgr.setClientDexSessionActive(true);
        assertEquals(IME_GBOARD, bridge.currentIme);
        assertEquals(1, bridge.showWithHardKbd);
        assertTrue(mgr.isImeLockApplied());
        assertEquals(IME_SOGOU, mgr.getBackedUpOriginalImeId());
        assertTrue(cfg.getImeBackupFile().isFile());

        // Switch locked IME live to WeType while still in DeX -> original backup MUST remain Sogou!
        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL,
                IME_WETYPE,
                false,
                BridgeProtocol.HARD_KBD_SOFT_IME_SHOW);
        mgr.onConfigUpdated();
        assertEquals(IME_WETYPE, bridge.currentIme);
        assertEquals(IME_SOGOU, mgr.getBackedUpOriginalImeId());

        // Exit DeX -> both original IME (Sogou) and original hard-kbd value (0) restored, backup deleted
        mgr.setClientDexSessionActive(false);
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertEquals(0, bridge.showWithHardKbd);
        assertFalse(mgr.isImeLockApplied());
        assertEquals("", mgr.getBackedUpOriginalImeId());
        assertFalse(cfg.getImeBackupFile().exists());
    }

    @Test
    public void keepsLockActiveWhileEitherClientOrRemoteSessionIsRunning() throws Exception {
        DaemonConfig cfg = newTempConfig();
        FakeImeBridge bridge = new FakeImeBridge();
        DexImeManager mgr = new DexImeManager(cfg, bridge);

        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_AUTO,
                IME_WETYPE,
                true,
                BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM,
                false);

        mgr.setClientDexSessionActive(true);
        mgr.setRemoteSessionActive(true);
        assertEquals(IME_WETYPE, bridge.currentIme);

        // Helper closes while remote cast continues -> keep locked IME active
        mgr.setClientDexSessionActive(false);
        assertEquals(IME_WETYPE, bridge.currentIme);
        assertTrue(mgr.isImeLockApplied());

        // Remote cast ends -> restore original IME cleanly
        mgr.setRemoteSessionActive(false);
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertFalse(mgr.isImeLockApplied());
    }

    @Test
    public void autoLocksBuiltInBridgeImeDuringRemoteCastAndRestoresOnDisconnect() throws Exception {
        DaemonConfig cfg = newTempConfig();
        FakeImeBridge bridge = new FakeImeBridge();
        DexImeManager mgr = new DexImeManager(cfg, bridge);

        // Verify pure resolution precedence rules
        assertEquals(
                BridgeProtocol.OPALDESK_BRIDGE_IME_ID,
                DexImeManager.resolveDesiredImeId(
                        false, true, BridgeProtocol.DEX_IME_DISPLAY_AUTO, "", true));
        assertEquals(
                BridgeProtocol.OPALDESK_BRIDGE_IME_ID,
                DexImeManager.resolveDesiredImeId(
                        true, true, BridgeProtocol.DEX_IME_DISPLAY_AUTO, IME_GBOARD, true));
        // Explicit ALWAYS_LOCAL + locked third-party IME overrides auto Bridge IME
        assertEquals(
                IME_GBOARD,
                DexImeManager.resolveDesiredImeId(
                        false, true, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, IME_GBOARD, true));
        // Local DeX without remote cast never auto-switches to Bridge IME unless explicitly selected
        assertEquals(
                "",
                DexImeManager.resolveDesiredImeId(
                        true, false, BridgeProtocol.DEX_IME_DISPLAY_AUTO, "", true));
        assertEquals(
                IME_GBOARD,
                DexImeManager.resolveDesiredImeId(
                        true, false, BridgeProtocol.DEX_IME_DISPLAY_AUTO, IME_GBOARD, true));

        // Default config has autoBridgeImeOnCast = true -> starting remote cast auto-locks Bridge IME
        assertTrue(cfg.isAutoBridgeImeOnCast());
        mgr.setRemoteSessionActive(true);
        assertEquals(BridgeProtocol.OPALDESK_BRIDGE_IME_ID, bridge.currentIme);
        assertTrue(mgr.isBridgeImeCurrentlyActive());
        assertTrue(mgr.isImeLockApplied());
        assertEquals(IME_SOGOU, mgr.getBackedUpOriginalImeId());

        SimpleJson.JsonObjectView state = SimpleJson.parseObject(
                mgr.toStateJson(BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY, false));
        assertTrue(state.getBoolean("bridgeImeActive", false));
        assertTrue(state.getBoolean("autoBridgeImeOnCast", false));
        assertEquals(BridgeProtocol.OPALDESK_BRIDGE_IME_ID, state.getString("bridgeImeId", ""));

        // Remote cast disconnects -> original phone IME restored automatically
        mgr.setRemoteSessionActive(false);
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertFalse(mgr.isBridgeImeCurrentlyActive());
        assertFalse(cfg.getImeBackupFile().exists());
    }

    @Test
    public void recoversOriginalImeAndHardKeyboardSettingAfterAbnormalDaemonCrash() throws Exception {
        DaemonConfig cfg = newTempConfig();
        FakeImeBridge bridge = new FakeImeBridge();
        DexImeManager crashedInstance = new DexImeManager(cfg, bridge);

        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL,
                IME_GBOARD,
                false,
                BridgeProtocol.HARD_KBD_SOFT_IME_SHOW);
        crashedInstance.setClientDexSessionActive(true);
        assertEquals(IME_GBOARD, bridge.currentIme);
        assertEquals(1, bridge.showWithHardKbd);
        assertTrue(cfg.getImeBackupFile().isFile());

        // Simulate abrupt daemon crash (no onDaemonShutdown called) and fresh daemon restart
        DexImeManager restartedInstance = new DexImeManager(cfg, bridge);
        restartedInstance.recoverFromPreviousCrashIfNeeded();

        assertEquals(IME_SOGOU, bridge.currentIme);
        assertEquals(0, bridge.showWithHardKbd);
        assertFalse(cfg.getImeBackupFile().exists());
    }

    @Test
    public void reportsFailureHonestlyWithoutLeavingStaleBackupWhenSwitchFails() throws Exception {
        DaemonConfig cfg = newTempConfig();
        FakeImeBridge bridge = new FakeImeBridge();
        bridge.failSetIme = true;
        DexImeManager mgr = new DexImeManager(cfg, bridge);

        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_AUTO,
                IME_GBOARD,
                false,
                BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM);
        mgr.setClientDexSessionActive(true);

        assertFalse(mgr.isImeLockApplied());
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertEquals("", mgr.getBackedUpOriginalImeId());
        assertFalse(cfg.getImeBackupFile().exists());
    }

    @Test
    public void serializesAuthoritativeStateJsonForHelperUi() throws Exception {
        DaemonConfig cfg = newTempConfig();
        FakeImeBridge bridge = new FakeImeBridge();
        bridge.enabledImes = Arrays.asList(IME_SOGOU, IME_GBOARD);
        DexImeManager mgr = new DexImeManager(cfg, bridge);

        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL,
                IME_GBOARD,
                true,
                BridgeProtocol.HARD_KBD_SOFT_IME_HIDE);
        mgr.setClientDexSessionActive(true);

        String json = mgr.toStateJson(BridgeProtocol.DISPLAY_IME_POLICY_LOCAL, true);
        SimpleJson.JsonObjectView obj = SimpleJson.parseObject(json);
        assertEquals(IME_GBOARD, obj.getString("currentImeId", ""));
        assertEquals(IME_SOGOU, obj.getString("originalImeId", ""));
        assertEquals(IME_GBOARD, obj.getString("lockedImeId", ""));
        assertTrue(obj.getBoolean("imeLockApplied", false));
        assertTrue(obj.getBoolean("dexSessionActive", false));
        assertEquals(BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, obj.getInt("dexImeDisplayMode", -1));
        assertTrue(obj.getBoolean("muteHostImeOnCast", false));
        assertEquals(BridgeProtocol.HARD_KBD_SOFT_IME_HIDE, obj.getInt("showImeWithHardKeyboard", 99));
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_LOCAL, obj.getInt("effectiveWindowImePolicy", -1));
        assertTrue(obj.getBoolean("physicalKeyboardDetected", false));
        assertEquals(Arrays.asList(IME_SOGOU, IME_GBOARD), obj.getStringList("enabledImeIds"));

        // Clear locked IME while inside DeX -> restores immediately
        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_AUTO,
                "",
                false,
                BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM);
        mgr.onConfigUpdated();
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertFalse(mgr.isImeLockApplied());
        assertEquals("", mgr.getBackedUpOriginalImeId());
        assertFalse(cfg.getImeBackupFile().exists());
    }

    @Test
    public void autoEnablesOptionalYuyanPluginOnDexWhenInstalledAndYieldsOnDisplay0() throws Exception {
        DaemonConfig cfg = newTempConfig();
        FakeImeBridge bridge = new FakeImeBridge();
        final boolean[] pluginInstalled = new boolean[] { false };
        DexImeManager mgr = new DexImeManager(cfg, new DexImeManager.SystemImeBridge() {
            @Override public String getDefaultInputMethodId() { return bridge.getDefaultInputMethodId(); }
            @Override public List<String> listEnabledInputMethodIds() { return bridge.listEnabledInputMethodIds(); }
            @Override public boolean setDefaultInputMethodId(String imeId) { return bridge.setDefaultInputMethodId(imeId); }
            @Override public int getShowImeWithHardKeyboard() { return bridge.getShowImeWithHardKeyboard(); }
            @Override public boolean setShowImeWithHardKeyboard(int value) { return bridge.setShowImeWithHardKeyboard(value); }
            @Override public boolean isImePluginVerifiedAndInstalled() { return pluginInstalled[0]; }
        });

        // 1. Plugin NOT installed: entering local DeX in AUTO mode leaves user's phone IME (Sogou) untouched!
        mgr.setClientDexSessionActive(true);
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertFalse(mgr.isBridgeImeCurrentlyActive());
        assertFalse(mgr.isPluginImeInstalledSafe());

        // 2. User installs same-cert io.github.lengsukq.opaldesk.ime plugin APK at runtime -> hot-enables!
        pluginInstalled[0] = true;
        mgr.onPluginPackageChanged();
        assertEquals(BridgeProtocol.OPALDESK_IME_PLUGIN_ID, bridge.currentIme);
        assertTrue(mgr.isBridgeImeCurrentlyActive());
        assertTrue(mgr.isPluginImeInstalledSafe());
        assertEquals(IME_SOGOU, mgr.getBackedUpOriginalImeId());

        // 3. User taps an EditText on Phone Display 0 while DeX is on external monitor -> yields back to Sogou!
        assertEquals(IME_SOGOU, mgr.yieldToDisplay0OriginalIme());
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertTrue(mgr.isYieldedToDisplay0());

        // 4. User taps or types back inside a DeX window -> reclaims Yuyan plugin automatically!
        mgr.reclaimBridgeImeIfYielded();
        assertFalse(mgr.isYieldedToDisplay0());
        assertEquals(BridgeProtocol.OPALDESK_IME_PLUGIN_ID, bridge.currentIme);

        // 4b. User freely opts OUT of using built-in Yuyan plugin on local DeX without uninstalling it!
        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_AUTO,
                "",
                false,
                BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM,
                false);
        mgr.onConfigUpdated();
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertFalse(mgr.isBridgeImeCurrentlyActive());

        // Re-enabling explicitly restores Yuyan plugin immediately on local DeX!
        cfg.updateDexImeConfig(
                BridgeProtocol.DEX_IME_DISPLAY_AUTO,
                BridgeProtocol.OPALDESK_IME_PLUGIN_ID,
                false,
                BridgeProtocol.HARD_KBD_SOFT_IME_SYSTEM,
                true);
        mgr.onConfigUpdated();
        assertEquals(BridgeProtocol.OPALDESK_IME_PLUGIN_ID, bridge.currentIme);

        // 5. User uninstalls plugin while in DeX -> seamlessly restores Sogou!
        pluginInstalled[0] = false;
        mgr.onPluginPackageChanged();
        assertEquals(IME_SOGOU, bridge.currentIme);
        assertFalse(mgr.isPluginImeInstalledSafe());

        mgr.setClientDexSessionActive(false);
        assertEquals(IME_SOGOU, bridge.currentIme);
    }
}
