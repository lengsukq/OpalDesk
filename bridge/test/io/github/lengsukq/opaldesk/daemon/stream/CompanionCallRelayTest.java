package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CompanionCallRelayTest {

    @Test
    public void testCallStateSyncCachingActiveFilterAndActionDispatchWithShellFallback() throws Exception {
        List<byte[]> directPushes = new CopyOnWriteArrayList<>();
        List<byte[]> broadcasts = new CopyOnWriteArrayList<>();
        List<String> helperCalls = new ArrayList<>();
        List<String> shellKeys = new ArrayList<>();
        boolean[] helperHandles = new boolean[]{true};

        CompanionCallRelay relay = new CompanionCallRelay(
                new CompanionCallRelay.OutboundSender() {
                    @Override
                    public void sendCallStateToSession(int sessionId, byte[] jsonUtf8) {
                        directPushes.add(jsonUtf8);
                    }

                    @Override
                    public void broadcastCallState(byte[] jsonUtf8) {
                        broadcasts.add(jsonUtf8);
                    }
                },
                keyCodeName -> {
                    shellKeys.add(keyCodeName);
                    return true;
                });

        relay.setActionDispatcher((op, number, smsText) -> {
            helperCalls.add(op + "|" + number + "|" + smsText);
            return helperHandles[0];
        });

        // Empty cache does not push anything
        assertFalse(relay.pushCachedCallStateToSession(1, true));
        assertFalse(relay.pushCachedCallStateToSession(1, false));

        // Sync ringing call -> cached & broadcast
        String ringingJson = "{\"state\":\"ringing\",\"number\":\"13800138000\",\"contactName\":\"Alice\","
                + "\"startedAtMs\":1730000500000,\"isMuted\":false,\"isSpeakerOn\":false,"
                + "\"canAnswer\":true,\"canReject\":true,\"canDial\":true,\"source\":\"telecom\"}";
        relay.onCallStateSyncedFromHelper(ringingJson);
        assertEquals(1, broadcasts.size());
        OpalCastProtocol.CallStateMsg cached = OpalCastProtocol.decodeCallState(broadcasts.get(0));
        assertEquals("ringing", cached.state);
        assertEquals("Alice", cached.contactName);
        assertNotNull(relay.getLatestCallState());

        // Active call pushes on auth (onlyIfActive = true)
        assertTrue(relay.pushCachedCallStateToSession(7, true));
        assertEquals(1, directPushes.size());

        // Sync idle call -> broadcast, but suppressed on new-session auth when onlyIfActive = true
        relay.onCallStateSyncedFromHelper("{\"state\":\"idle\",\"number\":\"\",\"contactName\":\"\"}");
        assertEquals(2, broadcasts.size());
        assertFalse(relay.pushCachedCallStateToSession(8, true));
        assertEquals(1, directPushes.size());
        // Explicit op=sync pushes cached idle state and forwards sync to helper
        assertTrue(relay.handleRemoteAction(8, new OpalCastProtocol.CallActionMsg("sync", "", "")));
        assertEquals(2, directPushes.size());
        assertEquals("sync||", helperCalls.get(0));

        // When helper handles answer, shell keyevent fallback is NOT invoked
        helperHandles[0] = true;
        assertTrue(relay.handleRemoteAction(8, new OpalCastProtocol.CallActionMsg("answer", "", "")));
        assertTrue(shellKeys.isEmpty());

        // When helper is unavailable/unhandled, basic controls fall back to uid=2000 input keyevent
        helperHandles[0] = false;
        assertTrue(relay.handleRemoteAction(8, new OpalCastProtocol.CallActionMsg("answer", "", "")));
        assertTrue(relay.handleRemoteAction(8, new OpalCastProtocol.CallActionMsg("reject", "", "")));
        assertTrue(relay.handleRemoteAction(8, new OpalCastProtocol.CallActionMsg("hangup", "", "")));
        assertEquals(List.of("KEYCODE_HEADSETHOOK", "KEYCODE_ENDCALL", "KEYCODE_ENDCALL"), shellKeys);

        // Non-keyevent ops (dial / mute / reject_with_sms) never fake via shell keyevent
        assertFalse(relay.handleRemoteAction(8, new OpalCastProtocol.CallActionMsg("dial", "10086", "")));
        assertFalse(relay.handleRemoteAction(8, new OpalCastProtocol.CallActionMsg("reject_with_sms", "10086", "Busy")));
        assertEquals(3, shellKeys.size());
    }

    @Test
    public void testServerGates0x1ECallStateFromLegacyDesktopAndDispatches0x35CallAction() throws Exception {
        CountDownLatch connected2 = new CountDownLatch(2);
        CountDownLatch callActionLatch = new CountDownLatch(1);
        AtomicReference<OpalCastProtocol.CallActionMsg> receivedAction = new AtomicReference<>();

        RemoteStreamServer server = new RemoteStreamServer(
                new RemoteStreamServer.SessionListener() {
                    @Override public void onSessionConnected(int sid, int st, String cid, String cn, OpalCastProtocol.HelloMsg h) { connected2.countDown(); }
                    @Override public void onSessionDisconnected(int sid, int st, String role, String reason) {}
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
                    @Override public void onCallAction(int sid, OpalCastProtocol.CallActionMsg msg) {
                        receivedAction.set(msg);
                        callActionLatch.countDown();
                    }
                    @Override public void onWifiUpgradeEnabled() {}
                    @Override public void onVideoCongestion(int sid) {}
                    @Override public void onVideoQueueHealthy(int sid) {}
                },
                new TrustStoreManager(),
                null,
                "TestPhone");

        PipedOutputStream legacyOut = new PipedOutputStream();
        PipedInputStream legacyIn = new PipedInputStream(legacyOut, 32 * 1024);
        ByteArrayOutputStream legacyWrites = new ByteArrayOutputStream();
        OpalCastProtocol.HelloMsg legacyHello = new OpalCastProtocol.HelloMsg(
                1, "LegacyMac", "legacy-1", "hevc", 1920, 1080, 240, 60, 12000, 0, true, "", "",
                "wifi_tls", OpalCastProtocol.SESSION_ROLE_DESKTOP, "", "", "macos", false);

        PipedOutputStream compOut = new PipedOutputStream();
        PipedInputStream compIn = new PipedInputStream(compOut, 32 * 1024);
        ByteArrayOutputStream compWrites = new ByteArrayOutputStream();
        OpalCastProtocol.HelloMsg compHello = new OpalCastProtocol.HelloMsg(
                1, "CompMac", "comp-1", "hevc", 0, 0, 0, 0, 0, 0, false, "", "",
                "wifi_tls", OpalCastProtocol.SESSION_ROLE_COMPANION, "", "", "macos", true);

        Thread t1 = new Thread(() -> server.runAuthenticatedSessionForTest(
                DisplaySink.SINK_REMOTE_WIFI, false, true, "legacy-1", "LegacyMac", legacyHello,
                () -> { try { legacyIn.close(); } catch (Exception ignored) {} },
                OpalCastFrameTransport.raw(legacyIn, legacyWrites)), "CallLegacyPeer");
        Thread t2 = new Thread(() -> server.runAuthenticatedSessionForTest(
                DisplaySink.SINK_REMOTE_WIFI, false, true, "comp-1", "CompMac", compHello,
                () -> { try { compIn.close(); } catch (Exception ignored) {} },
                OpalCastFrameTransport.raw(compIn, compWrites)), "CallCompPeer");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        assertTrue(connected2.await(2, TimeUnit.SECONDS));

        byte[] callPayload = OpalCastProtocol.encodeCallState(new OpalCastProtocol.CallStateMsg(
                "ringing", "10086", "中国移动", 1730000000000L, false, false, true, true, true, "telecom"));
        server.broadcastCallState(callPayload);

        long deadline = System.currentTimeMillis() + 1500L;
        while (compWrites.size() < OpalCastProtocol.HEADER_SIZE + callPayload.length && System.currentTimeMillis() < deadline) {
            Thread.sleep(15L);
        }
        Thread.sleep(60L);
        assertTrue("Companion must receive 0x1E CALL_STATE", compWrites.size() >= OpalCastProtocol.HEADER_SIZE + callPayload.length);
        assertEquals("Legacy desktop must NOT receive 0x1E CALL_STATE before subscribing", 0, legacyWrites.size());

        // Sending 0x35 CALL_ACTION dispatches to listener and dynamically subscribes legacy desktop
        byte[] actPayload = OpalCastProtocol.encodeCallAction(new OpalCastProtocol.CallActionMsg("answer", "", ""));
        OpalCastProtocol.writeFrame(legacyOut, OpalCastProtocol.TYPE_CALL_ACTION, 0, 10L, actPayload, 0, actPayload.length);
        legacyOut.flush();
        assertTrue(callActionLatch.await(2, TimeUnit.SECONDS));
        assertNotNull(receivedAction.get());
        assertEquals("answer", receivedAction.get().op);
        assertEquals(2, server.getCompanionEventSubscriberSessionIds().size());

        server.broadcastCallState(callPayload);
        deadline = System.currentTimeMillis() + 1500L;
        while (legacyWrites.size() < OpalCastProtocol.HEADER_SIZE + callPayload.length && System.currentTimeMillis() < deadline) {
            Thread.sleep(15L);
        }
        assertTrue(legacyWrites.size() >= OpalCastProtocol.HEADER_SIZE + callPayload.length);

        server.shutdown();
        legacyOut.close();
        compOut.close();
    }
}
