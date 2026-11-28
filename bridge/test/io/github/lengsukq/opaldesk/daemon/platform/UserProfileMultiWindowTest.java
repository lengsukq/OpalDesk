package io.github.lengsukq.opaldesk.daemon.platform;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.VirtualDisplayPool;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class UserProfileMultiWindowTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void classifiesAndFiltersSecondaryUsersAccurately() {
        String pmUsers = "Users:\n"
                + "\tUserInfo{0:Owner:c13} running\n"
                + "\tUserInfo{999:XSpace:800010} running\n"
                + "\tUserInfo{10:Work profile:1030} running\n"
                + "\tUserInfo{11:Guest:404} running\n"
                + "\tUserInfo{12:PausedWork:10b0} running\n"
                + "\tUserInfo{95:DualApp:10}\n";

        List<UserProfileAppPolicy.UserProfileInfo> parsed = UserProfileAppPolicy.parsePmListUsersOutput(pmUsers);
        assertEquals(6, parsed.size());

        // User 0 primary -> never scanned as secondary
        assertFalse(UserProfileAppPolicy.shouldScanUser(parsed.get(0)));
        assertEquals("", parsed.get(0).profileType);

        // User 999 XSpace -> clone, running -> scanned
        assertTrue(UserProfileAppPolicy.shouldScanUser(parsed.get(1)));
        assertEquals(UserProfileAppPolicy.PROFILE_CLONE, parsed.get(1).profileType);

        // User 10 Work profile (0x1030 has FLAG_MANAGED_PROFILE=0x20) -> work, running -> scanned
        assertTrue(UserProfileAppPolicy.shouldScanUser(parsed.get(2)));
        assertEquals(UserProfileAppPolicy.PROFILE_WORK, parsed.get(2).profileType);

        // User 11 Guest -> excluded
        assertFalse(UserProfileAppPolicy.shouldScanUser(parsed.get(3)));

        // User 12 Quiet mode (0x80 bit set) -> excluded
        assertFalse(UserProfileAppPolicy.shouldScanUser(parsed.get(4)));

        // User 95 not running -> excluded
        assertFalse(UserProfileAppPolicy.shouldScanUser(parsed.get(5)));
        assertEquals(UserProfileAppPolicy.PROFILE_CLONE, parsed.get(5).profileType);
    }

    @Test
    public void filtersSystemStubsInCloneProfileWhileKeepingThirdPartyClones() {
        assertTrue(UserProfileAppPolicy.shouldIncludeSecondaryProfileApp(
                UserProfileAppPolicy.PROFILE_CLONE, "com.tencent.mm", false));
        assertFalse(UserProfileAppPolicy.shouldIncludeSecondaryProfileApp(
                UserProfileAppPolicy.PROFILE_CLONE, "com.android.settings", true));
        assertFalse(UserProfileAppPolicy.shouldIncludeSecondaryProfileApp(
                UserProfileAppPolicy.PROFILE_CLONE, "com.android.documentsui", true));
        assertFalse(UserProfileAppPolicy.shouldIncludeSecondaryProfileApp(
                UserProfileAppPolicy.PROFILE_CLONE, "io.github.lengsukq.opaldesk", false));

        // Work profiles keep system apps like Work Calendar / Work Chrome, but still drop Settings stub
        assertTrue(UserProfileAppPolicy.shouldIncludeSecondaryProfileApp(
                UserProfileAppPolicy.PROFILE_WORK, "com.android.chrome", true));
        assertFalse(UserProfileAppPolicy.shouldIncludeSecondaryProfileApp(
                UserProfileAppPolicy.PROFILE_WORK, "com.android.settings", true));
    }

    @Test
    public void computesAppKeysAndMultiUserUids() {
        assertEquals("com.tencent.mm", UserProfileAppPolicy.formatAppKey("com.tencent.mm", 0));
        assertEquals("com.tencent.mm#u999", UserProfileAppPolicy.formatAppKey("com.tencent.mm", 999));

        assertEquals(10256, UserProfileAppPolicy.computeUserUid(10256, 0));
        assertEquals(99910256, UserProfileAppPolicy.computeUserUid(10256, 999));
        assertEquals(1010256, UserProfileAppPolicy.computeUserUid(99910256, 10));
        assertEquals(-1, UserProfileAppPolicy.computeUserUid(-1, 999));

        Set<String> pkgs = UserProfileAppPolicy.parsePmListPackagesOutput(
                "package:com.tencent.mm\npackage:com.eg.android.AlipayGphone\nnot-a-pkg\n");
        assertEquals(new HashSet<>(Arrays.asList("com.tencent.mm", "com.eg.android.AlipayGphone")), pkgs);
    }

    @Test
    public void buildAmStartArgsAppendsUserFlagOnlyForSecondaryUsers() {
        String[] primary = TaskLaunchPolicy.buildAmStartArgs(
                "com.tencent.mm/.ui.LauncherUI", 42, 1, 0x10000000, Collections.emptyMap(), 0);
        assertArrayEquals(
                new String[]{"am", "start", "--display", "42", "--windowingMode", "1",
                        "-f", "0x10000000", "-n", "com.tencent.mm/.ui.LauncherUI"},
                primary);

        String[] cloned = TaskLaunchPolicy.buildAmStartArgs(
                "com.tencent.mm/.ui.LauncherUI", 43, 1, 0x10000000, Collections.emptyMap(), 999);
        assertArrayEquals(
                new String[]{"am", "start", "--user", "999", "--display", "43", "--windowingMode", "1",
                        "-f", "0x10000000", "-n", "com.tencent.mm/.ui.LauncherUI"},
                cloned);
    }

    @Test
    public void compositorIsolatesPrimaryAndClonedWindowsForSamePackage() throws Exception {
        DaemonConfig cfg = new DaemonConfig(
                new File(tmp.getRoot(), "v2.conf"),
                new File(tmp.getRoot(), "profiles.conf"));

        List<String> launchedCalls = new ArrayList<>();
        List<String> forceStoppedCalls = new ArrayList<>();
        int[] nextDisplay = new int[]{100};
        int[] nextTask = new int[]{500};

        VirtualDisplayPool pool = new VirtualDisplayPool(new VirtualDisplayPool.DisplayDriver() {
            @Override
            public VirtualDisplayPool.DisplayDriver.CreatedDisplay createVirtualDisplay(
                    String name, int width, int height, int dpi, float fps, Object surface, boolean allowSecure) {
                int id = nextDisplay[0]++;
                return new VirtualDisplayPool.DisplayDriver.CreatedDisplay(new Object(), id, "vd:" + id);
            }

            @Override public void resizeVirtualDisplay(Object handle, int width, int height, int dpi) {}
            @Override public void setVirtualDisplaySurface(Object handle, Object surface) {}
            @Override public void releaseVirtualDisplay(Object handle, int displayId) {}
            @Override public byte[] captureTaskSnapshotBytes(int taskId) { return null; }
        }, 6, false);

        CompositorEngine.AppRuntimeBridge bridge = new CompositorEngine.AppRuntimeBridge() {
            @Override public String resolveAppLabel(String pkg) { return "WeChat"; }

            @Override
            public int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId) {
                return launchOrMoveFullscreenApp(pkg, cls, 0, displayId, existingTaskId);
            }

            @Override
            public int launchOrMoveFullscreenApp(String pkg, String cls, int userId, int displayId, int existingTaskId) {
                launchedCalls.add(pkg + "#u" + userId + "@d" + displayId);
                return nextTask[0]++;
            }

            @Override public int findTopTaskIdOnDisplay(int displayId, String expectedPkg) { return -1; }
            @Override public Set<Integer> collectAliveTaskIds() { return Collections.emptySet(); }
            @Override public void removeTask(int taskId) {}
            @Override public void forceStopPackage(String pkg) { forceStoppedCalls.add(pkg + "#u0"); }

            @Override
            public void forceStopPackage(String pkg, int userId) {
                forceStoppedCalls.add(pkg + "#u" + userId);
            }

            @Override public void onDisplayReleased(int displayId) {}
            @Override public void releaseSurfaceHandle(Object surface) {}
        };

        CompositorEngine engine = new CompositorEngine(cfg, pool, bridge, null);

        int wPrimary = engine.openOrFocusWindow("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", 0,
                BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
        int wClone = engine.openOrFocusWindow("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", 999,
                BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);

        assertTrue(wPrimary > 0);
        assertTrue(wClone > 0);
        assertNotEquals(wPrimary, wClone);
        assertEquals(2, engine.getWindowCount());

        // Re-opening user 999 focuses existing clone window instead of creating a 3rd window.
        int wCloneAgain = engine.openOrFocusWindow("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", 999,
                BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
        assertEquals(wClone, wCloneAgain);

        WindowSession s0 = engine.getWindowSnapshot(wPrimary);
        WindowSession s999 = engine.getWindowSnapshot(wClone);
        assertEquals(0, s0.userId);
        assertEquals("", s0.profileType);
        assertEquals(999, s999.userId);
        assertEquals(UserProfileAppPolicy.PROFILE_CLONE, s999.profileType);

        int d0 = engine.attachWindowSurface(wPrimary, new Object(), 900, 600, 240, 60f);
        int d999 = engine.attachWindowSurface(wClone, new Object(), 900, 600, 240, 60f);
        assertTrue(d0 > 0);
        assertTrue(d999 > 0);
        assertNotEquals(d0, d999);
        assertEquals(Arrays.asList("com.tencent.mm#u0@d" + d0, "com.tencent.mm#u999@d" + d999), launchedCalls);

        // Multi-user audio targets include both user 0 and user 999.
        List<CompositorEngine.WindowAppTarget> targets = engine.getOpenWindowAppTargets();
        assertEquals(2, targets.size());
        assertEquals("com.tencent.mm", targets.get(0).appKey());
        assertEquals("com.tencent.mm#u999", targets.get(1).appKey());

        // Scene JSON includes userId and profileType for the clone window.
        String sceneJson = engine.toSceneJson();
        assertTrue(sceneJson.contains("\"userId\":999"));
        assertTrue(sceneJson.contains("\"profileType\":\"clone\""));

        // Closing the clone window force-stops ONLY user 999, leaving user 0 untouched.
        assertTrue(engine.performWindowAction(wClone, BridgeProtocol.WIN_ACTION_CLOSE, null));
        assertEquals(Collections.singletonList("com.tencent.mm#u999"), forceStoppedCalls);
        assertEquals(1, engine.getWindowCount());
        assertEquals(0, engine.getWindowSnapshot(wPrimary).userId);
    }

    @Test
    public void opalCastProtocolRoundTripsUserIdFieldsBackwardCompatibly() throws Exception {
        byte[] appListBytes = OpalCastProtocol.encodeAppList(new OpalCastProtocol.AppListMsg(Arrays.asList(
                new OpalCastProtocol.AppEntry("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", "微信", false, -1, "", 3, 0, ""),
                new OpalCastProtocol.AppEntry("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", "微信", false, -1, "", 1, 999, "clone")
        )));
        String appListJson = new String(appListBytes, StandardCharsets.UTF_8);
        SimpleJson.JsonObjectView root = SimpleJson.parseObject(appListJson);
        List<SimpleJson.JsonObjectView> apps = root.getObjectList("apps");
        assertEquals(2, apps.size());
        assertEquals(0, apps.get(0).getInt("userId", 0));
        assertEquals("", apps.get(0).getString("profileType", ""));
        assertEquals(999, apps.get(1).getInt("userId", 0));
        assertEquals("clone", apps.get(1).getString("profileType", ""));

        byte[] helloJson = ("{\"protocolVersion\":1,\"clientName\":\"Mac\",\"sessionRole\":\"standalone_app\","
                + "\"appPackage\":\"com.tencent.mm\",\"appActivity\":\"com.tencent.mm.ui.LauncherUI\",\"appUserId\":999}")
                .getBytes(StandardCharsets.UTF_8);
        OpalCastProtocol.HelloMsg hello = OpalCastProtocol.decodeHello(helloJson);
        assertEquals(999, hello.appUserId);
        assertEquals("com.tencent.mm", hello.appPackage);

        // Legacy HELLO without appUserId defaults to 0
        byte[] legacyHelloJson = ("{\"protocolVersion\":1,\"clientName\":\"Mac\",\"sessionRole\":\"standalone_app\","
                + "\"appPackage\":\"com.tencent.mm\"}").getBytes(StandardCharsets.UTF_8);
        assertEquals(0, OpalCastProtocol.decodeHello(legacyHelloJson).appUserId);

        byte[] winActionJson = ("{\"action\":\"launch\",\"pkg\":\"com.tencent.mm\",\"cls\":\"com.tencent.mm.ui.LauncherUI\",\"userId\":999}")
                .getBytes(StandardCharsets.UTF_8);
        OpalCastProtocol.WindowActionMsg winAction = OpalCastProtocol.decodeWindowAction(winActionJson);
        assertEquals(999, winAction.userId);
        assertEquals("com.tencent.mm", winAction.pkg);
    }
}
