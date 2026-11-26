package io.github.lengsukq.opaldesk.daemon.stream;

import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated outbound OpalCast v1.1 sender session with bounded video/audio queues,
 * keyframe-aware congestion dropping, and lock-free atomic teardown.
 */
final class ClientSession {
    static final int MAX_QUEUED_VIDEO_FRAMES = 14;
    static final int MAX_QUEUED_AUDIO_FRAMES = 12;
    static final int MAX_TOTAL_QUEUED_FRAMES = 64;
    static final long AWAITING_KEYFRAME_RETRY_MS = 450L;
    private static final long HEALTHY_NOTIFY_INTERVAL_MS = 2000L;

    private static final class QueuedFrame {
        final int type;
        final int flags;
        final long timestampUs;
        final byte[] payload;
        final boolean isVideo;
        final boolean isAudio;
        final boolean isKeyFrame;

        QueuedFrame(int type, int flags, long timestampUs, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.timestampUs = timestampUs;
            this.payload = (payload != null) ? payload : OpalCastProtocol.EMPTY_BYTES;
            this.isVideo = (type == OpalCastProtocol.TYPE_VIDEO_FRAME);
            this.isAudio = (type == OpalCastProtocol.TYPE_AUDIO_FRAME);
            this.isKeyFrame = this.isVideo && ((flags & OpalCastProtocol.FLAG_VIDEO_KEYFRAME) != 0);
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
    private final ArrayDeque<QueuedFrame> queue = new ArrayDeque<>();
    private int queuedVideoFrames = 0;
    private int queuedAudioFrames = 0;
    private boolean awaitingKeyFrame = false;
    private long lastKeyFrameRequestMs = 0L;
    private long lastHealthyNotifyMs = 0L;
    private final java.util.function.LongSupplier monotonicClockMs;

    private final AtomicBoolean closed = new AtomicBoolean(false);
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

    void startSenderThread() {
        senderThread = new Thread(this::sendLoop, "ExtStreamSender-" + sessionId);
        senderThread.setDaemon(true);
        senderThread.start();
    }

    void enqueue(int type, int flags, long timestampUs, byte[] payload) {
        if (closed.get()) return;
        QueuedFrame qf = new QueuedFrame(type, flags, timestampUs, payload);
        boolean triggerCongestion = false;
        boolean triggerIdrRetry = false;
        boolean triggerHealthy = false;
        boolean overflowClose = false;
        long nowMs = monotonicClockMs.getAsLong();

        synchronized (queueLock) {
            if (closed.get()) return;

            if (type == OpalCastProtocol.TYPE_VIDEO_CONFIG) {
                dropAllQueuedVideoLocked(false);
                awaitingKeyFrame = false;
                lastKeyFrameRequestMs = 0L;
                queue.addLast(qf);
                queueLock.notifyAll();
                return;
            }

            if (qf.isVideo) {
                if (qf.isKeyFrame) {
                    if (queuedVideoFrames >= MAX_QUEUED_VIDEO_FRAMES / 2) {
                        dropAllQueuedVideoLocked(false);
                    }
                    awaitingKeyFrame = false;
                    lastKeyFrameRequestMs = 0L;
                    queue.addLast(qf);
                    queuedVideoFrames++;
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
                } else if (queuedVideoFrames >= MAX_QUEUED_VIDEO_FRAMES) {
                    dropAllQueuedVideoLocked(true);
                    awaitingKeyFrame = true;
                    lastKeyFrameRequestMs = nowMs;
                    triggerCongestion = true;
                } else {
                    queue.addLast(qf);
                    queuedVideoFrames++;
                    if (queuedVideoFrames <= 1) {
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
                queue.addLast(qf);
                queuedAudioFrames++;
                queueLock.notifyAll();
            } else {
                if (queue.size() >= MAX_TOTAL_QUEUED_FRAMES) {
                    dropAllQueuedVideoLocked(false);
                    awaitingKeyFrame = true;
                    lastKeyFrameRequestMs = nowMs;
                    triggerCongestion = true;
                }
                if (queue.size() >= MAX_TOTAL_QUEUED_FRAMES) {
                    overflowClose = true;
                } else {
                    queue.addLast(qf);
                    queueLock.notifyAll();
                }
            }
        }

        if (overflowClose) {
            close("Outbound queue overflow (unresponsive peer)");
            return;
        }
        if (triggerCongestion && listener != null) {
            listener.onVideoCongestion(sessionId);
        } else if (triggerIdrRetry && listener != null) {
            System.out.println("OPALDESK_STREAM_IDR_RETRY sid=" + sessionId);
            listener.onRequestIdr(sessionId);
        } else if (triggerHealthy && listener != null) {
            listener.onVideoQueueHealthy(sessionId);
        }
    }

    private void dropAllQueuedVideoLocked(boolean keepKeyFrames) {
        Iterator<QueuedFrame> it = queue.iterator();
        while (it.hasNext()) {
            QueuedFrame item = it.next();
            if (item.isVideo && (!keepKeyFrames || !item.isKeyFrame)) {
                it.remove();
                queuedVideoFrames = Math.max(0, queuedVideoFrames - 1);
            }
        }
    }

    private void dropOldestAudioLocked() {
        Iterator<QueuedFrame> it = queue.iterator();
        while (it.hasNext()) {
            QueuedFrame item = it.next();
            if (item.isAudio) {
                it.remove();
                queuedAudioFrames = Math.max(0, queuedAudioFrames - 1);
                return;
            }
        }
    }

    private void sendLoop() {
        try {
            while (!closed.get()) {
                QueuedFrame next;
                boolean shouldFlush;
                synchronized (queueLock) {
                    while (!closed.get() && queue.isEmpty()) {
                        queueLock.wait(250L);
                    }
                    if (closed.get()) break;
                    next = queue.pollFirst();
                    if (next == null) continue;
                    if (next.isVideo) queuedVideoFrames = Math.max(0, queuedVideoFrames - 1);
                    if (next.isAudio) queuedAudioFrames = Math.max(0, queuedAudioFrames - 1);
                    shouldFlush = queue.isEmpty() || next.isVideo
                            || next.type == OpalCastProtocol.TYPE_VIDEO_CONFIG
                            || next.type == OpalCastProtocol.TYPE_WIFI_UPGRADE_READY
                            || next.type == OpalCastProtocol.TYPE_APP_LIST
                            || next.type == OpalCastProtocol.TYPE_IME_REQUEST
                            || next.type == OpalCastProtocol.TYPE_MIC_STATE
                            || next.type == OpalCastProtocol.TYPE_CAMERA_STATE
                            || next.type == OpalCastProtocol.TYPE_PONG;
                }
                frameWriter.writeFrame(
                        next.type, next.flags, next.timestampUs,
                        next.payload, 0, next.payload.length);
                if (shouldFlush) {
                    frameWriter.flush();
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
            queue.clear();
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
