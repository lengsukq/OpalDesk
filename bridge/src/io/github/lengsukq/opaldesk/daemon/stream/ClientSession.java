package io.github.lengsukq.opaldesk.daemon.stream;

import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Dedicated outbound OpalCast v1.1 sender session with bounded video/audio queues,
 * two-tier soft/hard ABR congestion handling, pooled frame buffer return, and lock-free teardown.
 */
final class ClientSession {
    static final int SOFT_CONGESTION_VIDEO_FRAMES = 6;
    static final int MAX_QUEUED_VIDEO_FRAMES = 12;
    static final int MAX_QUEUED_AUDIO_FRAMES = 16;
    static final int MAX_TOTAL_QUEUED_FRAMES = 64;
    static final long SOFT_CONGESTION_NOTIFY_COOLDOWN_MS = 2_500L;
    static final long AWAITING_KEYFRAME_RETRY_MS = 450L;
    private static final long HEALTHY_NOTIFY_INTERVAL_MS = 2000L;

    /** Scales soft congestion watermark (~100 ms of video frames) cleanly for 30–165 Hz streams. */
    static int resolveSoftCongestionWatermark(float targetFps) {
        float safeFps = (targetFps > 0f && !Float.isNaN(targetFps)) ? targetFps : 60f;
        int scaled = Math.round(safeFps * 0.10f);
        return Math.max(SOFT_CONGESTION_VIDEO_FRAMES, Math.min(16, scaled));
    }

    /** Scales hard congestion drop ceiling (~170–200 ms of video frames) cleanly for 30–165 Hz streams. */
    static int resolveHardCongestionWatermark(float targetFps) {
        float safeFps = (targetFps > 0f && !Float.isNaN(targetFps)) ? targetFps : 60f;
        int soft = resolveSoftCongestionWatermark(safeFps);
        int scaled = Math.round(safeFps * 0.17f);
        return Math.max(Math.max(MAX_QUEUED_VIDEO_FRAMES, soft + 6), Math.min(28, scaled));
    }

    private static final class QueuedFrame {
        final int type;
        final int flags;
        final long timestampUs;
        final byte[] payload;
        final int payloadLength;
        final boolean isVideo;
        final boolean isAudio;
        final boolean isKeyFrame;
        private Consumer<byte[]> onRelease;

        QueuedFrame(int type, int flags, long timestampUs, byte[] payload, int payloadLength, Consumer<byte[]> onRelease) {
            this.type = type;
            this.flags = flags;
            this.timestampUs = timestampUs;
            this.payload = (payload != null) ? payload : OpalCastProtocol.EMPTY_BYTES;
            this.payloadLength = Math.max(0, Math.min(payloadLength, this.payload.length));
            this.isVideo = (type == OpalCastProtocol.TYPE_VIDEO_FRAME);
            this.isAudio = (type == OpalCastProtocol.TYPE_AUDIO_FRAME);
            this.isKeyFrame = this.isVideo && ((flags & OpalCastProtocol.FLAG_VIDEO_KEYFRAME) != 0);
            this.onRelease = onRelease;
        }

        void releaseBufferOnce() {
            Consumer<byte[]> cb = this.onRelease;
            if (cb != null) {
                this.onRelease = null;
                cb.accept(this.payload);
            }
        }
    }

    final int sessionId;
    final int sinkType;
    final boolean isAdbLocalSocket;
    final boolean permanentTrust;
    final String sessionRole;
    final String appPackage;
    final String clientId;
    final String clientName;
    private final Closeable transport;
    private final OpalCastFrameTransport frameWriter;
    private final RemoteStreamServer.SessionListener listener;

    private final Object queueLock = new Object();
    private final ArrayDeque<QueuedFrame> priorityQueue = new ArrayDeque<>();
    private final ArrayDeque<QueuedFrame> videoQueue = new ArrayDeque<>();
    private int queuedVideoFrames = 0;
    private int queuedAudioFrames = 0;
    private int softVideoWatermark = SOFT_CONGESTION_VIDEO_FRAMES;
    private int hardVideoWatermark = MAX_QUEUED_VIDEO_FRAMES;
    private boolean awaitingKeyFrame = false;
    private long lastKeyFrameRequestMs = 0L;
    private long lastSoftCongestionNotifyMs = 0L;
    private long lastHealthyNotifyMs = 0L;
    private final java.util.function.LongSupplier monotonicClockMs;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean companionEventsSubscribed = false;
    volatile String closeReason = "";
    private Thread senderThread;

    ClientSession(
            int sessionId,
            int sinkType,
            boolean isAdbLocalSocket,
            boolean permanentTrust,
            String sessionRole,
            String appPackage,
            String clientId,
            String clientName,
            Closeable transport,
            OutputStream out,
            RemoteStreamServer.SessionListener listener) {
        this(sessionId, sinkType, isAdbLocalSocket, permanentTrust, sessionRole, appPackage,
                clientId, clientName, transport, OpalCastFrameTransport.raw(null, out), listener);
    }

    ClientSession(
            int sessionId,
            int sinkType,
            boolean isAdbLocalSocket,
            boolean permanentTrust,
            String sessionRole,
            String appPackage,
            String clientId,
            String clientName,
            Closeable transport,
            OpalCastFrameTransport frameWriter,
            RemoteStreamServer.SessionListener listener) {
        this(sessionId, sinkType, isAdbLocalSocket, permanentTrust, sessionRole, appPackage,
                clientId, clientName, transport, frameWriter, listener,
                () -> System.nanoTime() / 1_000_000L);
    }

    ClientSession(
            int sessionId,
            int sinkType,
            boolean isAdbLocalSocket,
            boolean permanentTrust,
            String sessionRole,
            String appPackage,
            String clientId,
            String clientName,
            Closeable transport,
            OpalCastFrameTransport frameWriter,
            RemoteStreamServer.SessionListener listener,
            java.util.function.LongSupplier monotonicClockMs) {
        this.sessionId = sessionId;
        this.sinkType = sinkType;
        this.isAdbLocalSocket = isAdbLocalSocket;
        this.permanentTrust = permanentTrust;
        this.sessionRole = (sessionRole != null && !sessionRole.isEmpty())
                ? sessionRole : OpalCastProtocol.SESSION_ROLE_DESKTOP;
        this.appPackage = (appPackage != null) ? appPackage : "";
        this.clientId = clientId;
        this.clientName = clientName;
        this.transport = transport;
        this.frameWriter = frameWriter;
        this.listener = listener;
        this.monotonicClockMs = (monotonicClockMs != null)
                ? monotonicClockMs : (() -> System.nanoTime() / 1_000_000L);
    }

    boolean isClosed() {
        return closed.get();
    }

    boolean isCompanionEventsSubscribed() {
        return companionEventsSubscribed;
    }

    void setCompanionEventsSubscribed(boolean subscribed) {
        this.companionEventsSubscribed = subscribed;
    }

    void setTargetFps(float targetFps) {
        int nextSoft = resolveSoftCongestionWatermark(targetFps);
        int nextHard = resolveHardCongestionWatermark(targetFps);
        synchronized (queueLock) {
            this.softVideoWatermark = nextSoft;
            this.hardVideoWatermark = nextHard;
        }
    }

    void startSenderThread() {
        senderThread = new Thread(this::sendLoop, "ExtStreamSender-" + sessionId);
        senderThread.setDaemon(true);
        senderThread.start();
    }

    void enqueue(int type, int flags, long timestampUs, byte[] payload) {
        int len = (payload != null) ? payload.length : 0;
        enqueue(type, flags, timestampUs, payload, len, null);
    }

    /**
     * Enqueues a streaming data control frame (e.g. {@code TYPE_FILE_PULL_CHUNK}) with bounded
     * backpressure so large multi-megabyte file pulls never trigger {@link #MAX_TOTAL_QUEUED_FRAMES}
     * overflow disconnects on slow Wi-Fi links.
     *
     * @return {@code true} if queued, {@code false} if the session closed or timed out while waiting.
     */
    boolean enqueueBackpressured(int type, int flags, long timestampUs, byte[] payload, long timeoutMs) {
        if (closed.get()) return false;
        int len = (payload != null) ? payload.length : 0;
        QueuedFrame qf = new QueuedFrame(type, flags, timestampUs, payload, len, null);
        long deadlineMs = monotonicClockMs.getAsLong() + Math.max(100L, timeoutMs);
        int highWater = Math.max(8, MAX_TOTAL_QUEUED_FRAMES / 2);
        synchronized (queueLock) {
            while (!closed.get() && totalQueuedLocked() >= highWater) {
                long remaining = deadlineMs - monotonicClockMs.getAsLong();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    queueLock.wait(Math.min(remaining, 250L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (closed.get()) return false;
            priorityQueue.addLast(qf);
            queueLock.notifyAll();
            return true;
        }
    }

    void enqueue(int type, int flags, long timestampUs, byte[] payload, int payloadLength, Consumer<byte[]> onRelease) {
        if (closed.get()) {
            if (onRelease != null && payload != null) onRelease.accept(payload);
            return;
        }
        QueuedFrame qf = new QueuedFrame(type, flags, timestampUs, payload, payloadLength, onRelease);
        boolean triggerSoftCongestion = false;
        boolean triggerCongestion = false;
        boolean triggerIdrRetry = false;
        boolean triggerHealthy = false;
        boolean overflowClose = false;
        boolean accepted = false;
        long nowMs = monotonicClockMs.getAsLong();

        synchronized (queueLock) {
            if (closed.get()) {
                qf.releaseBufferOnce();
                return;
            }

            if (type == OpalCastProtocol.TYPE_VIDEO_CONFIG) {
                dropAllQueuedVideoLocked(false);
                awaitingKeyFrame = false;
                lastKeyFrameRequestMs = 0L;
                videoQueue.addLast(qf);
                accepted = true;
                queueLock.notifyAll();
                return;
            }

            if (qf.isVideo) {
                if (qf.isKeyFrame) {
                    if (queuedVideoFrames >= hardVideoWatermark / 2) {
                        dropAllQueuedVideoLocked(false);
                    }
                    awaitingKeyFrame = false;
                    lastKeyFrameRequestMs = 0L;
                    videoQueue.addLast(qf);
                    queuedVideoFrames++;
                    accepted = true;
                    queueLock.notifyAll();
                    return;
                }
                if (awaitingKeyFrame) {
                    // Self-heal if an earlier congestion IDR was coalesced/cooled-down or dropped by HW encoder:
                    // periodically request a sync frame without further crushing ABR bitrate.
                    if (nowMs - lastKeyFrameRequestMs >= AWAITING_KEYFRAME_RETRY_MS) {
                        lastKeyFrameRequestMs = nowMs;
                        triggerIdrRetry = true;
                    }
                } else if (queuedVideoFrames >= hardVideoWatermark) {
                    dropAllQueuedVideoLocked(true);
                    awaitingKeyFrame = true;
                    lastKeyFrameRequestMs = nowMs;
                    triggerCongestion = true;
                } else {
                    videoQueue.addLast(qf);
                    queuedVideoFrames++;
                    accepted = true;
                    if (queuedVideoFrames >= softVideoWatermark) {
                        if (nowMs - lastSoftCongestionNotifyMs >= SOFT_CONGESTION_NOTIFY_COOLDOWN_MS) {
                            lastSoftCongestionNotifyMs = nowMs;
                            triggerSoftCongestion = true;
                        }
                    } else if (queuedVideoFrames <= 1) {
                        if (nowMs - lastHealthyNotifyMs >= HEALTHY_NOTIFY_INTERVAL_MS) {
                            lastHealthyNotifyMs = nowMs;
                            triggerHealthy = true;
                        }
                    }
                    queueLock.notifyAll();
                }
            } else if (qf.isAudio) {
                if (queuedAudioFrames >= MAX_QUEUED_AUDIO_FRAMES) {
                    dropOldestAudioLocked();
                }
                priorityQueue.addLast(qf);
                queuedAudioFrames++;
                accepted = true;
                queueLock.notifyAll();
            } else {
                if (totalQueuedLocked() >= MAX_TOTAL_QUEUED_FRAMES) {
                    dropAllQueuedVideoLocked(false);
                    awaitingKeyFrame = true;
                    lastKeyFrameRequestMs = nowMs;
                    triggerCongestion = true;
                }
                if (totalQueuedLocked() >= MAX_TOTAL_QUEUED_FRAMES) {
                    overflowClose = true;
                } else {
                    priorityQueue.addLast(qf);
                    accepted = true;
                    queueLock.notifyAll();
                }
            }
        }

        if (!accepted) {
            qf.releaseBufferOnce();
        }
        if (overflowClose) {
            close("Outbound queue overflow (unresponsive peer)");
            return;
        }
        if (triggerCongestion && listener != null) {
            listener.onVideoCongestion(sessionId);
        } else if (triggerSoftCongestion && listener != null) {
            listener.onVideoSoftCongestion(sessionId);
        } else if (triggerIdrRetry && listener != null) {
            System.out.println("OPALDESK_STREAM_IDR_RETRY sid=" + sessionId);
            listener.onRequestIdr(sessionId);
        } else if (triggerHealthy && listener != null) {
            listener.onVideoQueueHealthy(sessionId);
        }
    }

    private int totalQueuedLocked() {
        return priorityQueue.size() + videoQueue.size();
    }

    private void dropAllQueuedVideoLocked(boolean keepKeyFrames) {
        Iterator<QueuedFrame> it = videoQueue.iterator();
        while (it.hasNext()) {
            QueuedFrame item = it.next();
            if (item.isVideo && (!keepKeyFrames || !item.isKeyFrame)) {
                it.remove();
                queuedVideoFrames = Math.max(0, queuedVideoFrames - 1);
                item.releaseBufferOnce();
            }
        }
    }

    private void dropOldestAudioLocked() {
        Iterator<QueuedFrame> it = priorityQueue.iterator();
        while (it.hasNext()) {
            QueuedFrame item = it.next();
            if (item.isAudio) {
                it.remove();
                queuedAudioFrames = Math.max(0, queuedAudioFrames - 1);
                item.releaseBufferOnce();
                return;
            }
        }
    }

    private void sendLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (Throwable ignored) {
            // Safe no-op in pure JVM unit tests.
        }
        try {
            while (!closed.get()) {
                QueuedFrame next;
                boolean shouldFlush;
                synchronized (queueLock) {
                    while (!closed.get() && priorityQueue.isEmpty() && videoQueue.isEmpty()) {
                        queueLock.wait(250L);
                    }
                    if (closed.get()) break;
                    // Always drain audio & control frames ahead of queued video frames so large HEVC
                    // frames never head-of-line block tiny ~350-byte AAC packets on Wi-Fi.
                    next = !priorityQueue.isEmpty() ? priorityQueue.pollFirst() : videoQueue.pollFirst();
                    if (next == null) continue;
                    if (next.isVideo) queuedVideoFrames = Math.max(0, queuedVideoFrames - 1);
                    if (next.isAudio) queuedAudioFrames = Math.max(0, queuedAudioFrames - 1);
                    queueLock.notifyAll();
                    shouldFlush = (priorityQueue.isEmpty() && videoQueue.isEmpty())
                            || next.isVideo
                            || (next.isAudio && priorityQueue.isEmpty())
                            || next.type == OpalCastProtocol.TYPE_VIDEO_CONFIG
                            || next.type == OpalCastProtocol.TYPE_WIFI_UPGRADE_READY
                            || next.type == OpalCastProtocol.TYPE_APP_LIST
                            || next.type == OpalCastProtocol.TYPE_IME_REQUEST
                            || next.type == OpalCastProtocol.TYPE_MIC_STATE
                            || next.type == OpalCastProtocol.TYPE_CAMERA_STATE
                            || next.type == OpalCastProtocol.TYPE_NOTIFICATION_EVENT
                            || next.type == OpalCastProtocol.TYPE_MEDIA_STATE
                            || next.type == OpalCastProtocol.TYPE_FILE_OP_RESPONSE
                            || next.type == OpalCastProtocol.TYPE_FILE_PULL_CHUNK
                            || next.type == OpalCastProtocol.TYPE_APK_INSTALL_STATUS
                            || next.type == OpalCastProtocol.TYPE_CALL_STATE
                            || next.type == OpalCastProtocol.TYPE_PONG;
                }
                try {
                    frameWriter.writeFrame(
                            next.type, next.flags, next.timestampUs,
                            next.payload, 0, next.payloadLength);
                    if (shouldFlush) {
                        frameWriter.flush();
                    }
                } finally {
                    next.releaseBufferOnce();
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (!closed.get()) {
                close("Sender interrupted");
            }
        } catch (Throwable t) {
            if (!closed.get()) {
                System.err.println("OPALDESK_SENDER_ERR sid=" + sessionId + ": " + t.getMessage());
                close("Send error: " + t.getMessage());
            }
        }
    }

    void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        closeReason = (reason != null) ? reason : "";
        synchronized (queueLock) {
            for (QueuedFrame item : priorityQueue) {
                item.releaseBufferOnce();
            }
            for (QueuedFrame item : videoQueue) {
                item.releaseBufferOnce();
            }
            priorityQueue.clear();
            videoQueue.clear();
            queuedVideoFrames = 0;
            queuedAudioFrames = 0;
            queueLock.notifyAll();
        }
        closeTransportQuietly(transport);
    }

    static void closeTransportQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // Expected during concurrent shutdown / peer disconnect.
        }
    }
}
