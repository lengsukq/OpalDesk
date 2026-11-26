package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientSessionTest {

    @Test
    public void testOverflowClosesTransportOutsideQueueLock() throws Exception {
        AtomicInteger closeCount = new AtomicInteger(0);
        AtomicBoolean concurrentEnqueueBlockedOnClose = new AtomicBoolean(false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ClientSession[] sessionRef = new ClientSession[1];

        Closeable transport = () -> {
            closeCount.incrementAndGet();
            // Verify another thread calling enqueue() while transport.close() is running
            // does NOT deadlock waiting on queueLock (closed flag is already set outside lock).
            CountDownLatch done = new CountDownLatch(1);
            Thread probe = new Thread(() -> {
                sessionRef[0].enqueue(OpalCastProtocol.TYPE_PONG, 0, 0L, new byte[8]);
                done.countDown();
            });
            probe.start();
            try {
                if (!done.await(500L, TimeUnit.MILLISECONDS)) {
                    concurrentEnqueueBlockedOnClose.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ClientSession session = new ClientSession(
                1, DisplaySink.SINK_REMOTE_USB, true, true, OpalCastProtocol.SESSION_ROLE_DESKTOP,
                "", "cid", "Mac", transport, out, null);
        sessionRef[0] = session;

        // Fill queue with MAX_TOTAL_QUEUED_FRAMES non-video frames (sender thread intentionally not started)
        for (int i = 0; i < ClientSession.MAX_TOTAL_QUEUED_FRAMES; i++) {
            session.enqueue(OpalCastProtocol.TYPE_PONG, 0, i, new byte[8]);
            assertFalse(session.isClosed());
        }

        // Next non-video frame overflows and must close outside queueLock
        session.enqueue(OpalCastProtocol.TYPE_PONG, 0, 999L, new byte[8]);
        assertTrue(session.isClosed());
        assertEquals(1, closeCount.get());
        assertFalse("close() must never run while holding queueLock", concurrentEnqueueBlockedOnClose.get());

        // Subsequent close calls are idempotent
        session.close("duplicate");
        assertEquals(1, closeCount.get());
    }

    @Test
    public void testAwaitingKeyFrameSelfHealsByRetryingIdrRequest() {
        AtomicInteger congestionCount = new AtomicInteger(0);
        AtomicInteger idrRetryCount = new AtomicInteger(0);
        final long[] nowMs = new long[]{1_000L};

        RemoteStreamServer.SessionListener listener = new RemoteStreamServer.SessionListener() {
            @Override public void onSessionConnected(int sid, int st, String cid, String cn, OpalCastProtocol.HelloMsg h) {}
            @Override public void onSessionDisconnected(int sid, int st, String role, String reason) {}
            @Override public void onResizeConfig(int sid, OpalCastProtocol.ResizeConfig cfg) {}
            @Override public void onRequestIdr(int sid) { idrRetryCount.incrementAndGet(); }
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
            @Override public void onVideoCongestion(int sid) { congestionCount.incrementAndGet(); }
            @Override public void onVideoQueueHealthy(int sid) {}
        };

        ClientSession session = new ClientSession(
                7, DisplaySink.SINK_REMOTE_WIFI, false, true, OpalCastProtocol.SESSION_ROLE_DESKTOP,
                "", "cid", "Tablet", () -> {}, OpalCastFrameTransport.raw(null, new ByteArrayOutputStream()),
                listener, () -> nowMs[0]);

        // Fill video queue until congestion triggers awaitingKeyFrame = true
        for (int i = 0; i < ClientSession.MAX_QUEUED_VIDEO_FRAMES; i++) {
            session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, i * 8_000L, new byte[16]);
        }
        assertEquals(0, congestionCount.get());
        session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, 999_000L, new byte[16]);
        assertEquals(1, congestionCount.get());
        assertEquals(0, idrRetryCount.get());

        // P-frame arriving before retry interval must NOT spam IDR retries
        nowMs[0] += ClientSession.AWAITING_KEYFRAME_RETRY_MS - 50L;
        session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, 1_100_000L, new byte[16]);
        assertEquals(0, idrRetryCount.get());

        // Once retry interval elapses without an IDR arriving, ClientSession must self-heal via onRequestIdr
        // without triggering extra ABR bitrate collapse (congestionCount stays 1).
        nowMs[0] += 60L;
        session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, 1_200_000L, new byte[16]);
        assertEquals(1, idrRetryCount.get());
        assertEquals(1, congestionCount.get());

        // Once a keyframe arrives, awaitingKeyFrame clears and subsequent P-frames flow normally.
        session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, OpalCastProtocol.FLAG_VIDEO_KEYFRAME, 1_300_000L, new byte[32]);
        nowMs[0] += ClientSession.AWAITING_KEYFRAME_RETRY_MS * 2;
        session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, 1_400_000L, new byte[16]);
        assertEquals(1, idrRetryCount.get());
    }
}
