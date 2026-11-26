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

    @Test
    public void testSoftCongestionFiresBeforeHardDropAndReturnsPooledBuffers() {
        AtomicInteger softCount = new AtomicInteger(0);
        AtomicInteger hardCount = new AtomicInteger(0);
        final long[] nowMs = new long[]{5_000L};
        VideoFrameBufferPool pool = new VideoFrameBufferPool();

        RemoteStreamServer.SessionListener listener = new RemoteStreamServer.SessionListener() {
            @Override public void onSessionConnected(int sid, int st, String cid, String cn, OpalCastProtocol.HelloMsg h) {}
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
            @Override public void onWifiUpgradeEnabled() {}
            @Override public void onVideoSoftCongestion(int sid) { softCount.incrementAndGet(); }
            @Override public void onVideoCongestion(int sid) { hardCount.incrementAndGet(); }
            @Override public void onVideoQueueHealthy(int sid) {}
        };

        ClientSession session = new ClientSession(
                9, DisplaySink.SINK_REMOTE_WIFI, false, true, OpalCastProtocol.SESSION_ROLE_DESKTOP,
                "", "cid", "Mac", () -> {}, OpalCastFrameTransport.raw(null, new ByteArrayOutputStream()),
                listener, () -> nowMs[0]);

        // Enqueue below soft watermark -> zero congestion callbacks.
        for (int i = 0; i < ClientSession.SOFT_CONGESTION_VIDEO_FRAMES - 1; i++) {
            byte[] buf = pool.acquire(1024);
            session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, i * 8_000L, buf, 1024, pool::release);
        }
        assertEquals(0, softCount.get());
        assertEquals(0, hardCount.get());

        // Reaching soft watermark triggers smooth soft ABR step-down WITHOUT dropping frames or hard congestion.
        byte[] softBuf = pool.acquire(1024);
        session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, 50_000L, softBuf, 1024, pool::release);
        assertEquals(1, softCount.get());
        assertEquals(0, hardCount.get());
        assertEquals(0, pool.pooledCountForSize(1024));

        // Cooldown prevents spamming soft ABR on the next frame immediately after.
        nowMs[0] += 100L;
        for (int i = ClientSession.SOFT_CONGESTION_VIDEO_FRAMES; i < ClientSession.MAX_QUEUED_VIDEO_FRAMES; i++) {
            byte[] buf = pool.acquire(1024);
            session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, i * 16_000L, buf, 1024, pool::release);
        }
        assertEquals(1, softCount.get());
        assertEquals(0, hardCount.get());

        // Exceeding MAX_QUEUED_VIDEO_FRAMES triggers hard congestion and returns dropped buffers to pool.
        byte[] overflowBuf = pool.acquire(1024);
        session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, 999_000L, overflowBuf, 1024, pool::release);
        assertEquals(1, hardCount.get());
        assertTrue("Dropped video buffers must be returned to VideoFrameBufferPool", pool.pooledCountForSize(1024) > 0);

        session.close("test done");
    }

    @Test
    public void testAudioPriorityDequeuesBeforeQueuedVideoAndWatermarksScaleWithFps() throws Exception {
        // FPS-scaled watermarks keep ~100ms soft / ~170-200ms hard tolerance across 60/90/120Hz
        assertEquals(6, ClientSession.resolveSoftCongestionWatermark(60f));
        assertEquals(12, ClientSession.resolveHardCongestionWatermark(60f));
        assertEquals(9, ClientSession.resolveSoftCongestionWatermark(90f));
        assertEquals(15, ClientSession.resolveHardCongestionWatermark(90f));
        assertEquals(12, ClientSession.resolveSoftCongestionWatermark(120f));
        assertEquals(20, ClientSession.resolveHardCongestionWatermark(120f));

        // 120Hz session absorbs a 10-frame Wi-Fi jitter burst without triggering soft or hard congestion
        AtomicInteger soft120 = new AtomicInteger(0);
        AtomicInteger hard120 = new AtomicInteger(0);
        RemoteStreamServer.SessionListener listener = new RemoteStreamServer.SessionListener() {
            @Override public void onSessionConnected(int sid, int st, String cid, String cn, OpalCastProtocol.HelloMsg h) {}
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
            @Override public void onWifiUpgradeEnabled() {}
            @Override public void onVideoSoftCongestion(int sid) { soft120.incrementAndGet(); }
            @Override public void onVideoCongestion(int sid) { hard120.incrementAndGet(); }
            @Override public void onVideoQueueHealthy(int sid) {}
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ClientSession session = new ClientSession(
                11, DisplaySink.SINK_REMOTE_WIFI, false, true, OpalCastProtocol.SESSION_ROLE_DESKTOP,
                "", "cid", "Tablet", () -> {}, OpalCastFrameTransport.raw(null, out), listener);
        session.setTargetFps(120f);

        for (int i = 0; i < 10; i++) {
            session.enqueue(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, i * 8_333L, new byte[64]);
        }
        assertEquals(0, soft120.get());
        assertEquals(0, hard120.get());

        // Enqueue an audio frame AFTER 10 video frames; sender thread must emit the audio frame FIRST
        session.enqueue(OpalCastProtocol.TYPE_AUDIO_FRAME, 0, 50_000L, new byte[]{1, 2, 3, 4});
        session.startSenderThread();

        long deadline = System.currentTimeMillis() + 1_500L;
        while (out.size() < OpalCastProtocol.HEADER_SIZE + 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        session.close("test done");

        byte[] wire = out.toByteArray();
        assertTrue("Expected frames written to wire", wire.length >= OpalCastProtocol.HEADER_SIZE + 4);
        int firstType = wire[4] & 0xFF;
        assertEquals("Audio frame must bypass queued video frames to avoid HoL blocking",
                OpalCastProtocol.TYPE_AUDIO_FRAME, firstType);
    }
}
