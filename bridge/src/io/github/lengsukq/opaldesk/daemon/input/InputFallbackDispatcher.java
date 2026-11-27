package io.github.lengsukq.opaldesk.daemon.input;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.UhidProtocol;

import java.lang.reflect.InvocationTargetException;

/**
 * Pure-Java input backend dispatch & automatic {@code IInputManager -> /dev/uhid} fallback coordinator.
 *
 * <p>Has zero {@code android.*} dependencies so every fallback transition (silent {@code false} return
 * on HyperOS/Android 17, {@code SecurityException}, AIDL signature mismatch, or missing system injector)
 * is verified deterministically in JVM unit tests.
 */
public final class InputFallbackDispatcher {
    private static final long INJECT_WARN_COOLDOWN_MS = 3_000L;

    public interface BackendAdapter {
        boolean isSystemInjectorAvailable();
        boolean injectViaSystem(Object ev, int displayId) throws Exception;
        boolean injectViaUhid(Object ev, int displayId, String vdUniqueId, int dispW, int dispH);
    }

    public interface StatusListener {
        void onInjectStatusChanged(int newStatus);
    }

    private final DaemonConfig config;
    private final BackendAdapter adapter;
    private final Object stateLock = new Object();

    private volatile boolean uhidAutoActivated = false;
    private volatile int lastInjectStatus = BridgeProtocol.INJECT_STATUS_SYSTEM;
    private volatile long lastInjectWarnMs = 0L;
    private volatile StatusListener statusListener;

    public InputFallbackDispatcher(DaemonConfig config, BackendAdapter adapter) {
        this.config = config;
        this.adapter = adapter;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public int getLastInjectStatus() {
        return lastInjectStatus;
    }

    /** Returns {@code true} when input injection is currently routed through Linux {@code /dev/uhid}. */
    public boolean isUsingUhid() {
        int backend = config.getInputBackendMode();
        return backend == BridgeProtocol.INPUT_BACKEND_UHID
                || (backend == BridgeProtocol.INPUT_BACKEND_AUTO && uhidAutoActivated);
    }

    public int onInputBackendConfigChanged(int mode, Runnable releaseDisplaysAction) {
        if (mode != BridgeProtocol.INPUT_BACKEND_UHID) {
            uhidAutoActivated = false;
            if (releaseDisplaysAction != null) {
                releaseDisplaysAction.run();
            }
            updateInjectStatus(BridgeProtocol.INJECT_STATUS_SYSTEM);
        } else {
            uhidAutoActivated = true;
            updateInjectStatus(BridgeProtocol.INJECT_STATUS_UHID);
        }
        return lastInjectStatus;
    }

    public boolean dispatch(Object ev, int displayId, String vdUniqueId, int dispW, int dispH) {
        return dispatch(ev, displayId, vdUniqueId, dispW, dispH, true);
    }

    public boolean dispatch(
            Object ev,
            int displayId,
            String vdUniqueId,
            int dispW,
            int dispH,
            boolean allowAutoUhidFallback) {
        int backend = config.getInputBackendMode();
        boolean uhidTried = false;
        if (backend == BridgeProtocol.INPUT_BACKEND_UHID
                || (backend == BridgeProtocol.INPUT_BACKEND_AUTO && uhidAutoActivated)) {
            if (!allowAutoUhidFallback) {
                // Passive hover events are cursorless no-ops under UHID and must never
                // eagerly open a new /dev/uhid device before real touch/key interaction.
                return true;
            }
            uhidTried = true;
            if (adapter.injectViaUhid(ev, displayId, vdUniqueId, dispW, dispH)) {
                updateInjectStatus(BridgeProtocol.INJECT_STATUS_UHID);
                return true;
            }
            if (backend == BridgeProtocol.INPUT_BACKEND_UHID) {
                updateInjectStatus(BridgeProtocol.INJECT_STATUS_BLOCKED);
                return false;
            }
        }

        String systemFailureReason;
        if (adapter.isSystemInjectorAvailable()) {
            try {
                if (adapter.injectViaSystem(ev, displayId)) {
                    updateInjectStatus(BridgeProtocol.INJECT_STATUS_SYSTEM);
                    return true;
                }
                systemFailureReason = "injectInputEvent returned false";
            } catch (Exception e) {
                Throwable root = (e instanceof InvocationTargetException && e.getCause() != null)
                        ? e.getCause() : e;
                systemFailureReason = UhidProtocol.unwrapThrowableMessage(root);
            }
        } else {
            systemFailureReason = "system InputManager injector unavailable";
        }

        if (!allowAutoUhidFallback) {
            // Do not latch permanent UHID fallback or report INJECT_STATUS_BLOCKED on benign
            // passive hover drops (e.g. hovering a freshly created VirtualDisplay while its
            // Activity is still registering its first InputWindowHandle).
            return false;
        }

        // AUTO fallback to Linux /dev/uhid whenever system injection is rejected (SecurityException,
        // silent boolean false return on HyperOS / Android 17 InputDispatcher, or missing AIDL method).
        if (backend == BridgeProtocol.INPUT_BACKEND_AUTO && !uhidTried) {
            uhidTried = true;
            if (adapter.injectViaUhid(ev, displayId, vdUniqueId, dispW, dispH)) {
                boolean firstFallback = false;
                synchronized (stateLock) {
                    if (!uhidAutoActivated) {
                        uhidAutoActivated = true;
                        firstFallback = true;
                    }
                }
                updateInjectStatus(BridgeProtocol.INJECT_STATUS_UHID);
                if (firstFallback) {
                    System.out.println("OPALDESK_UHID_AUTO_FALLBACK_ACTIVE displayId=" + displayId
                            + " reason=" + systemFailureReason);
                }
                return true;
            }
        }

        long nowMs = System.nanoTime() / 1_000_000L;
        if (lastInjectWarnMs == 0L || (nowMs - lastInjectWarnMs) >= INJECT_WARN_COOLDOWN_MS) {
            lastInjectWarnMs = nowMs;
            System.err.println("OPALDESK_INPUT_INJECT_WARN displayId=" + displayId
                    + " mode=" + backend
                    + " uhidTried=" + uhidTried
                    + ": " + systemFailureReason);
        }
        updateInjectStatus(BridgeProtocol.INJECT_STATUS_BLOCKED);
        return false;
    }

    private void updateInjectStatus(int nextStatus) {
        if (lastInjectStatus == nextStatus) return;
        lastInjectStatus = nextStatus;
        StatusListener l = statusListener;
        if (l != null) {
            try {
                l.onInjectStatusChanged(nextStatus);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
