package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RemoteStreamServerCompanionTest {

    private static final class TestPeer {
        final PipedOutputStream clientToServer = new PipedOutputStream();
        final PipedInputStream serverReads;
        final ByteArrayOutputStream serverWrites = new ByteArrayOutputStream();
        final Thread thread;

        TestPeer(
                RemoteStreamServer server,
                String clientId,
                String clientName,
                OpalCastProtocol.HelloMsg hello) throws Exception {
            this.serverReads = new PipedInputStream(clientToServer, 64 * 1024);
            OpalCastFrameTransport transport = OpalCastFrameTransport.raw(serverReads, serverWrites);
            this.thread = new Thread(() -> server.runAuthenticatedSessionForTest(
                    DisplaySink.SINK_REMOTE_WIFI, false, true, clientId, clientName, hello,
                    () -> {
                        try { serverReads.close(); } catch (Exception ignored) {}
                        try { clientToServer.close(); } catch (Exception ignored) {}
                    },
                    transport), "TestPeer-" + clientId);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void sendUplink(int type, byte[] payload) throws Exception {
            OpalCastProtocol.writeFrame(clientToServer, type, 0, 1L, payload, 0, payload.length);
            clientToServer.flush();
        }

        void close() {
            try { clientToServer.close(); } catch (Exception ignored) {}
            try { serverReads.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    public void testCompanionCoexistsWithDesktopAndStandaloneDedupesClientIdAndEnforcesCap() throws Exception {
        CountDownLatch connected3 = new CountDownLatch(3);
        CountDownLatch notifLatch = new CountDownLatch(1);
        CountDownLatch mediaLatch = new CountDownLatch(1);
        CountDownLatch fileLatch = new CountDownLatch(1);
        CountDownLatch apkLatch = new CountDownLatch(1);
        List<String> disconnectedRoles = new CopyOnWriteArrayList<>();
        AtomicReference<OpalCastProtocol.NotificationActionMsg> gotNotif = new AtomicReference<>();
        AtomicReference<OpalCastProtocol.MediaActionMsg> gotMedia = new AtomicReference<>();
        AtomicReference<OpalCastProtocol.FileOpRequestMsg> gotFile = new AtomicReference<>();
        AtomicReference<OpalCastProtocol.ApkInstallRequestMsg> gotApk = new AtomicReference<>();

        RemoteStreamServer server = new RemoteStreamServer(
                new RemoteStreamServer.SessionListener() {
                    @Override
                    public void onSessionConnected(int sid, int st, String cid, String cn, OpalCastProtocol.HelloMsg h) {
                        connected3.countDown();
                    }
                    @Override
                    public void onSessionDisconnected(int sid, int st, String role, String reason) {
                        disconnectedRoles.add(role + ":" + reason);
                    }
                    @Override public void onResizeConfig(int sid, OpalCastProtocol.ResizeConfig cfg) {}
                    @Override public void onRequestIdr(int sid) {}
                    @Override public void onMouseEvent(int sid, OpalCastProtocol.MouseEventMsg m) {}
                    @Override public void onTouchEvent(int sid, OpalCastProtocol.TouchEventMsg m) {}
                    @Override public void onKeyEvent(int sid, OpalCastProtocol.KeyEventMsg m) {}
                    @Override public void onTextEvent(int sid, OpalCastProtocol.TextEventMsg m) {}
                    @Override public void onImageClipboardEvent(int sid, OpalCastProtocol.ImageClipboardEventMsg m) {}
                    @Override public void onSystemAction(int sid, OpalCastProtocol.SystemActionMsg m) {}
                    @Override public void onFileTransferChunk(int sid, OpalCastProtocol.FileTransferChunkMsg m) {}
                    @Override public void onListApps(int sid) {}
                    @Override public void onWindowAction(int sid, OpalCastProtocol.WindowActionMsg m) {}
                    @Override public void onWifiUpgradeEnabled() {}
                    @Override public void onVideoCongestion(int sid) {}
                    @Override public void onVideoQueueHealthy(int sid) {}
                    @Override
                    public void onNotificationAction(int sid, OpalCastProtocol.NotificationActionMsg m) {
                        gotNotif.set(m);
                        notifLatch.countDown();
                    }
                    @Override
                    public void onMediaAction(int sid, OpalCastProtocol.MediaActionMsg m) {
                        gotMedia.set(m);
                        mediaLatch.countDown();
                    }
                    @Override
                    public void onFileOpRequest(int sid, OpalCastProtocol.FileOpRequestMsg m) {
                        gotFile.set(m);
                        fileLatch.countDown();
                    }
                    @Override
                    public void onApkInstallRequest(int sid, OpalCastProtocol.ApkInstallRequestMsg m) {
                        gotApk.set(m);
                        apkLatch.countDown();
                    }
                },
                new TrustStoreManager(),
                null,
                "TestPhone");

        OpalCastProtocol.HelloMsg desktopHello = new OpalCastProtocol.HelloMsg(
                1, "MacDesktop", "mac-desk", "hevc", 1920, 1080, 240, 60, 12000, 0, true, "", "",
                "wifi_tls", OpalCastProtocol.SESSION_ROLE_DESKTOP, "", "", "macos");
        OpalCastProtocol.HelloMsg standaloneHello = new OpalCastProtocol.HelloMsg(
                1, "MacApp", "mac-desk", "hevc", 800, 1400, 320, 60, 8000, 0, false, "", "",
                "wifi_tls", OpalCastProtocol.SESSION_ROLE_STANDALONE_APP, "com.tencent.mm", "", "macos");
        OpalCastProtocol.HelloMsg companion1Hello = new OpalCastProtocol.HelloMsg(
                1, "MacComp1", "mac-c1", "hevc", 0, 0, 0, 0, 0, 0, false, "", "",
                "wifi_tls", OpalCastProtocol.SESSION_ROLE_COMPANION, "", "", "macos");
        OpalCastProtocol.HelloMsg companion2Hello = new OpalCastProtocol.HelloMsg(
                1, "MacComp2", "mac-c2", "hevc", 0, 0, 0, 0, 0, 0, false, "", "",
                "wifi_tls", OpalCastProtocol.SESSION_ROLE_COMPANION, "", "", "macos");
        OpalCastProtocol.HelloMsg companion3Hello = new OpalCastProtocol.HelloMsg(
                1, "MacComp3", "mac-c3", "hevc", 0, 0, 0, 0, 0, 0, false, "", "",
                "wifi_tls", OpalCastProtocol.SESSION_ROLE_COMPANION, "", "", "macos");

        TestPeer desk = new TestPeer(server, "mac-desk", "MacDesktop", desktopHello);
        TestPeer app = new TestPeer(server, "mac-desk", "MacApp", standaloneHello);
        TestPeer comp1 = new TestPeer(server, "mac-c1", "MacComp1", companion1Hello);
        assertTrue(connected3.await(2, TimeUnit.SECONDS));

        // All three roles coexist without kicking each other off
        assertTrue(server.hasDesktopSession());
        assertEquals(1, server.getStandaloneSessionCount());
        assertTrue(server.hasCompanionSession());
        assertEquals(1, server.getCompanionSessionCount());
        assertEquals(2, server.getDesktopAndCompanionSessionIds().size());

        // Second distinct companion connects cleanly (cap = 2)
        TestPeer comp2 = new TestPeer(server, "mac-c2", "MacComp2", companion2Hello);
        waitUntil(() -> server.getCompanionSessionCount() == 2, 1500);
        assertEquals(2, server.getCompanionSessionCount());
        assertTrue(server.hasDesktopSession());
        assertEquals(1, server.getStandaloneSessionCount());

        // Third distinct companion exceeds MAX_COMPANION_SESSIONS (2) and is rejected immediately
        TestPeer comp3 = new TestPeer(server, "mac-c3", "MacComp3", companion3Hello);
        comp3.thread.join(1500);
        assertFalse("3rd distinct companion must exit immediately", comp3.thread.isAlive());
        assertEquals(2, server.getCompanionSessionCount());

        // Reconnecting with same clientId "mac-c1" replaces comp1 without exceeding cap or kicking desktop/comp2
        TestPeer comp1Replaced = new TestPeer(server, "mac-c1", "MacComp1-Reconn", companion1Hello);
        comp1.thread.join(1500);
        assertFalse("Old companion session for mac-c1 must be replaced", comp1.thread.isAlive());
        assertEquals(2, server.getCompanionSessionCount());
        assertTrue(server.hasDesktopSession());

        // Verify all 4 new v1.2 uplink frames dispatch through companion session
        comp1Replaced.sendUplink(OpalCastProtocol.TYPE_NOTIFICATION_ACTION,
                OpalCastProtocol.encodeNotificationAction(new OpalCastProtocol.NotificationActionMsg("dismiss", "k1", "")));
        comp1Replaced.sendUplink(OpalCastProtocol.TYPE_MEDIA_ACTION,
                OpalCastProtocol.encodeMediaAction(new OpalCastProtocol.MediaActionMsg("toggle", 0L)));
        comp1Replaced.sendUplink(OpalCastProtocol.TYPE_FILE_OP_REQUEST,
                OpalCastProtocol.encodeFileOpRequest(new OpalCastProtocol.FileOpRequestMsg(11, "list", "/sdcard", "", 0, 0)));
        comp1Replaced.sendUplink(OpalCastProtocol.TYPE_APK_INSTALL_REQUEST,
                OpalCastProtocol.encodeApkInstallRequest(new OpalCastProtocol.ApkInstallRequestMsg(22, "/sdcard/a.apk", "apk", true, false)));

        assertTrue(notifLatch.await(2, TimeUnit.SECONDS));
        assertTrue(mediaLatch.await(2, TimeUnit.SECONDS));
        assertTrue(fileLatch.await(2, TimeUnit.SECONDS));
        assertTrue(apkLatch.await(2, TimeUnit.SECONDS));
        assertEquals("dismiss", gotNotif.get().op);
        assertEquals("toggle", gotMedia.get().op);
        assertEquals("list", gotFile.get().op);
        assertEquals("/sdcard/a.apk", gotApk.get().filePath);

        // Broadcast notification reaches desktop + both companions (not standalone)
        byte[] notifPayload = "{\"action\":\"removed\",\"key\":\"k1\"}".getBytes(StandardCharsets.UTF_8);
        server.broadcastNotificationEvent(notifPayload);
        waitUntil(() -> comp1Replaced.serverWrites.size() > 0 && comp2.serverWrites.size() > 0 && desk.serverWrites.size() > 0, 1500);
        assertTrue(desk.serverWrites.size() >= OpalCastProtocol.HEADER_SIZE + notifPayload.length);
        assertTrue(comp1Replaced.serverWrites.size() >= OpalCastProtocol.HEADER_SIZE + notifPayload.length);
        assertTrue(comp2.serverWrites.size() >= OpalCastProtocol.HEADER_SIZE + notifPayload.length);
        assertEquals("Standalone session should not receive companion notification broadcast", 0, app.serverWrites.size());

        server.shutdown();
        desk.close();
        app.close();
        comp2.close();
        comp1Replaced.close();
        assertNotNull(disconnectedRoles);
    }

    private static void waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(15L);
        }
    }
}
