package io.github.lengsukq.opaldesk.daemon.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Pure-JVM notification cache, OTP enrichment, and action/sync coordinator shared between
 * {@link OpalCastCompositorBridge} and deterministic JUnit 4 unit tests.
 */
final class CompanionNotificationRelay {
    private static final int MAX_CACHED_NOTIFICATIONS = 40;

    interface OutboundSender {
        void sendNotificationEventToSession(int sessionId, byte[] jsonUtf8);
        void broadcastNotificationEvent(byte[] jsonUtf8);
    }

    interface HelperActionForwarder {
        void forwardNotificationAction(String op, String key, String replyText, int actionIndex);
    }

    private final Object cacheLock = new Object();
    private final LinkedHashMap<String, OpalCastProtocol.NotificationItem> cachedNotifications = new LinkedHashMap<>();
    private final OutboundSender outboundSender;
    private volatile HelperActionForwarder actionForwarder;

    CompanionNotificationRelay(OutboundSender outboundSender) {
        this.outboundSender = outboundSender;
    }

    void setActionForwarder(HelperActionForwarder forwarder) {
        this.actionForwarder = forwarder;
    }

    void onNotificationsSyncedFromHelper(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        try {
            OpalCastProtocol.NotificationEventMsg decoded =
                    OpalCastProtocol.decodeNotificationEvent(rawJson.getBytes(StandardCharsets.UTF_8));
            OpalCastProtocol.NotificationEventMsg outbound;
            synchronized (cacheLock) {
                if ("removed".equals(decoded.action)) {
                    if (!decoded.key.isEmpty()) {
                        cachedNotifications.remove(decoded.key);
                    }
                    outbound = new OpalCastProtocol.NotificationEventMsg(
                            "removed", decoded.key, Collections.emptyList());
                } else {
                    List<OpalCastProtocol.NotificationItem> enriched = new ArrayList<>(decoded.items.size());
                    for (OpalCastProtocol.NotificationItem it : decoded.items) {
                        if (it == null) continue;
                        String otp = (it.otpCode != null && !it.otpCode.isEmpty())
                                ? it.otpCode
                                : OtpCodeExtractor.extract(it.title, it.text);
                        enriched.add(new OpalCastProtocol.NotificationItem(
                                it.key, it.pkg, it.appLabel, it.title, it.text,
                                it.postTimeMs, it.clearable, it.canReply, it.replyLabel, otp, it.actions));
                    }
                    if ("snapshot".equals(decoded.action)) {
                        cachedNotifications.clear();
                        for (OpalCastProtocol.NotificationItem it : enriched) {
                            if (!it.key.isEmpty()) {
                                cachedNotifications.put(it.key, it);
                            }
                        }
                    } else {
                        for (OpalCastProtocol.NotificationItem it : enriched) {
                            if (!it.key.isEmpty()) {
                                cachedNotifications.remove(it.key);
                                cachedNotifications.put(it.key, it);
                            }
                        }
                        while (cachedNotifications.size() > MAX_CACHED_NOTIFICATIONS) {
                            String oldestKey = cachedNotifications.keySet().iterator().next();
                            cachedNotifications.remove(oldestKey);
                        }
                    }
                    String act = "snapshot".equals(decoded.action) ? "snapshot" : "posted";
                    outbound = new OpalCastProtocol.NotificationEventMsg(act, "", enriched);
                }
            }
            if (outboundSender != null) {
                outboundSender.broadcastNotificationEvent(OpalCastProtocol.encodeNotificationEvent(outbound));
            }
        } catch (OpalCastProtocol.ProtocolException | RuntimeException e) {
            System.err.println("OPALDESK_NOTIF_SYNC_WARN: " + e.getMessage());
        }
    }

    /**
     * Pushes the cached snapshot to {@code sessionId} if non-empty.
     *
     * @return {@code true} if a cached snapshot was sent, {@code false} if cache was empty.
     */
    boolean pushCachedSnapshotToSession(int sessionId) {
        byte[] snapshotBytes = buildCachedSnapshotBytes();
        if (snapshotBytes != null && outboundSender != null) {
            outboundSender.sendNotificationEventToSession(sessionId, snapshotBytes);
            return true;
        }
        return false;
    }

    /**
     * Called on newly authenticated companion or subscribed desktop sessions: pushes cached
     * notifications immediately if available, or triggers a single refresh from Helper if empty.
     */
    void pushInitialSnapshotOrRequestSync(int sessionId) {
        if (!pushCachedSnapshotToSession(sessionId)) {
            HelperActionForwarder fwd = actionForwarder;
            if (fwd != null) {
                fwd.forwardNotificationAction("sync", "", "", -1);
            }
        }
    }

    /**
     * Handles an inbound {@code 0x31 NOTIFICATION_ACTION} from Mac.
     * For {@code op="sync"}, sends any cached snapshot immediately WITHOUT triggering a duplicate
     * Helper sync, then forwards {@code op="sync"} to Helper exactly once.
     */
    void handleRemoteAction(int sessionId, OpalCastProtocol.NotificationActionMsg msg) {
        if (msg == null || msg.op.isEmpty()) return;
        String op = msg.op.toLowerCase(Locale.ROOT);
        if ("sync".equals(op)) {
            pushCachedSnapshotToSession(sessionId);
        } else if ("dismiss".equals(op) && !msg.key.isEmpty()) {
            synchronized (cacheLock) {
                cachedNotifications.remove(msg.key);
            }
        } else if ("clear_all".equals(op)) {
            synchronized (cacheLock) {
                cachedNotifications.entrySet().removeIf(e -> e.getValue() == null || e.getValue().clearable);
            }
        }
        HelperActionForwarder fwd = actionForwarder;
        if (fwd != null) {
            fwd.forwardNotificationAction(op, msg.key, msg.replyText, msg.actionIndex);
        }
    }

    private byte[] buildCachedSnapshotBytes() {
        synchronized (cacheLock) {
            if (cachedNotifications.isEmpty()) return null;
            List<OpalCastProtocol.NotificationItem> items = new ArrayList<>(cachedNotifications.values());
            items.sort((a, b) -> Long.compare(b.postTimeMs, a.postTimeMs));
            return OpalCastProtocol.encodeNotificationEvent(
                    new OpalCastProtocol.NotificationEventMsg("snapshot", "", items));
        }
    }
}
