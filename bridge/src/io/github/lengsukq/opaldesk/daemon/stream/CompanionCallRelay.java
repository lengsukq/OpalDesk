package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Pure-JVM call continuity state cache and action relay for OPD1 v1.3 ({@code 0x1E CALL_STATE} /
 * {@code 0x35 CALL_ACTION}), mirroring {@link CompanionNotificationRelay}'s lock-safe,
 * legacy-desktop-safe architecture.
 */
public final class CompanionCallRelay {
    private static final long SHELL_KEYEVENT_TIMEOUT_MS = 2_000L;

    public interface OutboundSender {
        void sendCallStateToSession(int sessionId, byte[] jsonUtf8);
        void broadcastCallState(byte[] jsonUtf8);
    }

    public interface HelperActionDispatcher {
        /**
         * Dispatches a call action to Helper.
         *
         * @return {@code true} if Helper handled the action, {@code false} if unavailable or unhandled.
         */
        boolean dispatchCallAction(String op, String number, String smsText);
    }

    public interface ShellKeyEventExecutor {
        boolean sendKeyEvent(String keyCodeName);
    }

    private final Object stateLock = new Object();
    private OpalCastProtocol.CallStateMsg latestState;
    private byte[] latestEncodedPayload;

    private final OutboundSender outboundSender;
    private final ShellKeyEventExecutor shellExecutor;
    private volatile HelperActionDispatcher actionDispatcher;

    public CompanionCallRelay(OutboundSender outboundSender) {
        this(outboundSender, CompanionCallRelay::runShellKeyEvent);
    }

    public CompanionCallRelay(OutboundSender outboundSender, ShellKeyEventExecutor shellExecutor) {
        this.outboundSender = outboundSender;
        this.shellExecutor = (shellExecutor != null) ? shellExecutor : CompanionCallRelay::runShellKeyEvent;
    }

    public void setActionDispatcher(HelperActionDispatcher dispatcher) {
        this.actionDispatcher = dispatcher;
    }

    /**
     * Ingests a {@code CALL_STATE} JSON string synced from Helper (Binder TX 52), caches the
     * normalized payload, and broadcasts {@code 0x1E TYPE_CALL_STATE} exclusively to sessions
     * subscribed to companion events.
     */
    public void onCallStateSyncedFromHelper(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        try {
            OpalCastProtocol.CallStateMsg decoded =
                    OpalCastProtocol.decodeCallState(rawJson.getBytes(StandardCharsets.UTF_8));
            byte[] normalized = OpalCastProtocol.encodeCallState(decoded);
            synchronized (stateLock) {
                latestState = decoded;
                latestEncodedPayload = normalized;
            }
            if (outboundSender != null) {
                outboundSender.broadcastCallState(normalized);
            }
        } catch (OpalCastProtocol.ProtocolException | RuntimeException e) {
            System.err.println("OPALDESK_CALL_SYNC_WARN: " + e.getMessage());
        }
    }

    /**
     * Pushes the cached call state to {@code sessionId} when present.
     *
     * @param onlyIfActive when {@code true}, suppresses push for {@code idle}/{@code disconnected} states.
     * @return {@code true} if a payload was sent.
     */
    public boolean pushCachedCallStateToSession(int sessionId, boolean onlyIfActive) {
        byte[] payload;
        synchronized (stateLock) {
            if (latestEncodedPayload == null || latestEncodedPayload.length == 0) {
                return false;
            }
            if (onlyIfActive && (latestState == null || !latestState.isActiveOrRinging())) {
                return false;
            }
            payload = latestEncodedPayload;
        }
        if (outboundSender != null) {
            outboundSender.sendCallStateToSession(sessionId, payload);
            return true;
        }
        return false;
    }

    /**
     * Returns the latest cached {@link OpalCastProtocol.CallStateMsg}, or {@code null} if none.
     */
    public OpalCastProtocol.CallStateMsg getLatestCallState() {
        synchronized (stateLock) {
            return latestState;
        }
    }

    /**
     * Handles an inbound {@code 0x35 TYPE_CALL_ACTION} from Mac.
     *
     * <p>Dispatches to Helper first; if Helper is unavailable or reports unhandled for basic
     * controls ({@code answer}/{@code reject}/{@code hangup}), falls back to {@code uid=2000}
     * {@code input keyevent KEYCODE_HEADSETHOOK} / {@code KEYCODE_ENDCALL}.
     */
    public boolean handleRemoteAction(int sessionId, OpalCastProtocol.CallActionMsg msg) {
        if (msg == null || msg.op.isEmpty()) return false;
        String op = msg.op.toLowerCase(Locale.ROOT);
        if ("sync".equals(op)) {
            pushCachedCallStateToSession(sessionId, false);
            HelperActionDispatcher disp = actionDispatcher;
            return disp != null && disp.dispatchCallAction("sync", "", "");
        }

        HelperActionDispatcher disp = actionDispatcher;
        boolean handled = false;
        if (disp != null) {
            try {
                handled = disp.dispatchCallAction(op, msg.number, msg.smsText);
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_CALL_DISPATCH_WARN op=" + op + ": " + e.getMessage());
            }
        }
        if (handled) {
            return true;
        }

        String fallbackKey = resolveShellFallbackKeyCode(op);
        if (fallbackKey != null) {
            try {
                return shellExecutor.sendKeyEvent(fallbackKey);
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_CALL_FALLBACK_WARN op=" + op + ": " + e.getMessage());
            }
        }
        return false;
    }

    static String resolveShellFallbackKeyCode(String normalizedOp) {
        if ("answer".equals(normalizedOp)) {
            return "KEYCODE_HEADSETHOOK";
        }
        if ("reject".equals(normalizedOp) || "hangup".equals(normalizedOp)) {
            return "KEYCODE_ENDCALL";
        }
        return null;
    }

    private static boolean runShellKeyEvent(String keyCodeName) {
        if (keyCodeName == null || keyCodeName.isEmpty()) return false;
        return ShellCommand.runWithTimeout(SHELL_KEYEVENT_TIMEOUT_MS, "input", "keyevent", keyCodeName).isSuccess();
    }
}
