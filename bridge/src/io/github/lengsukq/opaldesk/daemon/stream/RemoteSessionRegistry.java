package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Thread-safe multi-session registry for {@link RemoteStreamServer}: tracks 1 desktop session,
 * up to {@link RemoteStreamServer#MAX_STANDALONE_SESSIONS} standalone app pop-outs, and up to
 * {@link RemoteStreamServer#MAX_COMPANION_SESSIONS} companion sessions.
 */
final class RemoteSessionRegistry {
    static final class RegistrationOutcome {
        final boolean capacityExceeded;
        final ClientSession replacedSession;

        RegistrationOutcome(boolean capacityExceeded, ClientSession replacedSession) {
            this.capacityExceeded = capacityExceeded;
            this.replacedSession = replacedSession;
        }
    }

    private final AtomicInteger nextSessionId = new AtomicInteger(0);
    private final Object sessionLock = new Object();
    private volatile ClientSession desktopSession;
    private final LinkedHashMap<Integer, ClientSession> standaloneSessions = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, ClientSession> companionSessions = new LinkedHashMap<>();
    private final ConcurrentHashMap<Integer, ClientSession> allSessions = new ConcurrentHashMap<>();

    int nextSessionId() {
        return nextSessionId.incrementAndGet();
    }

    ClientSession getSession(int sessionId) {
        return allSessions.get(sessionId);
    }

    ClientSession getDesktopSession() {
        return desktopSession;
    }

    boolean isActiveSessionPermanent() {
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed()) {
            return d.permanentTrust;
        }
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) return s.permanentTrust;
            }
        }
        return false;
    }

    boolean hasDesktopSession() {
        ClientSession s = desktopSession;
        return s != null && !s.isClosed();
    }

    boolean hasActiveSession() {
        if (hasDesktopSession()) return true;
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) return true;
            }
        }
        return false;
    }

    boolean hasCompanionSession() {
        synchronized (sessionLock) {
            for (ClientSession s : companionSessions.values()) {
                if (s != null && !s.isClosed()) return true;
            }
        }
        return false;
    }

    int getCompanionSessionCount() {
        synchronized (sessionLock) {
            int count = 0;
            for (ClientSession s : companionSessions.values()) {
                if (s != null && !s.isClosed()) count++;
            }
            return count;
        }
    }

    int getStandaloneSessionCount() {
        synchronized (sessionLock) {
            int count = 0;
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) count++;
            }
            return count;
        }
    }

    List<Integer> getDesktopAndCompanionSessionIds() {
        List<Integer> ids = new ArrayList<>(3);
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed()) {
            ids.add(d.sessionId);
        }
        synchronized (sessionLock) {
            for (ClientSession s : companionSessions.values()) {
                if (s != null && !s.isClosed()) {
                    ids.add(s.sessionId);
                }
            }
        }
        return ids;
    }

    List<Integer> getCompanionEventSubscriberSessionIds() {
        List<Integer> ids = new ArrayList<>(3);
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed() && d.isCompanionEventsSubscribed()) {
            ids.add(d.sessionId);
        }
        synchronized (sessionLock) {
            for (ClientSession s : companionSessions.values()) {
                if (s != null && !s.isClosed() && s.isCompanionEventsSubscribed()) {
                    ids.add(s.sessionId);
                }
            }
        }
        return ids;
    }

    boolean markSessionCompanionEventsSubscribed(int sessionId) {
        ClientSession s = allSessions.get(sessionId);
        if (s == null || s.isClosed()) return false;
        boolean wasSubscribed = s.isCompanionEventsSubscribed();
        s.setCompanionEventsSubscribed(true);
        return !wasSubscribed;
    }

    int getActiveSinkType() {
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed()) {
            return d.sinkType;
        }
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) return s.sinkType;
            }
        }
        return DisplaySink.SINK_NONE;
    }

    String getActivePeerName() {
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed() && d.clientName != null) {
            return d.clientName;
        }
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed() && s.clientName != null) return s.clientName;
            }
        }
        return "";
    }

    boolean hasCapacityForSession(OpalCastProtocol.HelloMsg hello, String clientId) {
        if (hello.isCompanion()) {
            synchronized (sessionLock) {
                if (clientId != null && !clientId.isEmpty()) {
                    for (ClientSession existing : companionSessions.values()) {
                        if (existing != null && !existing.isClosed() && clientId.equals(existing.clientId)) {
                            return true;
                        }
                    }
                }
                return companionSessions.size() < RemoteStreamServer.MAX_COMPANION_SESSIONS;
            }
        }
        if (!hello.isStandaloneApp()) return true;
        synchronized (sessionLock) {
            for (ClientSession existing : standaloneSessions.values()) {
                if (existing != null && !existing.isClosed() && hello.appPackage.equals(existing.appPackage)) {
                    return true;
                }
            }
            return standaloneSessions.size() < RemoteStreamServer.MAX_STANDALONE_SESSIONS;
        }
    }

    RegistrationOutcome registerSession(ClientSession current, OpalCastProtocol.HelloMsg hello, String clientId) {
        boolean isStandalone = hello.isStandaloneApp();
        boolean isCatalog = hello.isAppCatalog();
        boolean isCompanion = hello.isCompanion();
        ClientSession replaced = null;
        boolean capacityExceeded = false;

        synchronized (sessionLock) {
            if (isCatalog) {
                allSessions.put(current.sessionId, current);
            } else if (isCompanion) {
                if (clientId != null && !clientId.isEmpty()) {
                    for (ClientSession existing : companionSessions.values()) {
                        if (existing != null && clientId.equals(existing.clientId)) {
                            replaced = existing;
                            break;
                        }
                    }
                }
                if (replaced != null) {
                    companionSessions.remove(replaced.sessionId);
                } else if (companionSessions.size() >= RemoteStreamServer.MAX_COMPANION_SESSIONS) {
                    capacityExceeded = true;
                }
                if (!capacityExceeded) {
                    companionSessions.put(current.sessionId, current);
                    allSessions.put(current.sessionId, current);
                }
            } else if (isStandalone) {
                for (ClientSession existing : standaloneSessions.values()) {
                    if (existing != null && hello.appPackage.equals(existing.appPackage)) {
                        replaced = existing;
                        break;
                    }
                }
                if (replaced != null) {
                    standaloneSessions.remove(replaced.sessionId);
                } else if (standaloneSessions.size() >= RemoteStreamServer.MAX_STANDALONE_SESSIONS) {
                    capacityExceeded = true;
                }
                if (!capacityExceeded) {
                    standaloneSessions.put(current.sessionId, current);
                    allSessions.put(current.sessionId, current);
                }
            } else {
                replaced = desktopSession;
                desktopSession = current;
                allSessions.put(current.sessionId, current);
            }
        }
        return new RegistrationOutcome(capacityExceeded, replaced);
    }

    void unregisterSession(ClientSession current, boolean isCompanion, boolean isStandalone, boolean isCatalog) {
        allSessions.remove(current.sessionId, current);
        synchronized (sessionLock) {
            if (isCompanion) {
                if (companionSessions.get(current.sessionId) == current) {
                    companionSessions.remove(current.sessionId);
                }
            } else if (isStandalone) {
                if (standaloneSessions.get(current.sessionId) == current) {
                    standaloneSessions.remove(current.sessionId);
                }
            } else if (!isCatalog && desktopSession == current) {
                desktopSession = null;
            }
        }
    }

    void disconnectSession(int sessionId, String reason) {
        ClientSession target = allSessions.get(sessionId);
        if (target != null) {
            target.close(reason);
        }
    }

    void disconnectAll(String reason) {
        List<ClientSession> toClose = new ArrayList<>(allSessions.values());
        for (ClientSession s : toClose) {
            if (s != null) s.close(reason);
        }
    }

    void disconnectMatching(Predicate<ClientSession> pred, String reason) {
        List<ClientSession> toClose = new ArrayList<>();
        for (ClientSession s : allSessions.values()) {
            if (s != null && pred.test(s)) {
                toClose.add(s);
            }
        }
        for (ClientSession s : toClose) {
            s.close(reason);
        }
    }
}
