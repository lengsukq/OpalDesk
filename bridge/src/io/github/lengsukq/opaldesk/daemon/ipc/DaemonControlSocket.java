package io.github.lengsukq.opaldesk.daemon.ipc;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/**
 * Single-instance abstract LocalSocket ({@code @io.github.lengsukq.opaldesk.daemon.ctl}) lock and control server.
 * Handles {@code --stop}, evicting an older trusted daemon instance, rejecting untrusted squatters,
 * and dispatching {@code SHUTDOWN -> DONE} and {@code NEED_BINDER -> OK}.
 */
public final class DaemonControlSocket {
    private static final long BROKEN_PIPE_LOG_INTERVAL_MS = 60_000L;

    private static volatile LocalServerSocket sServerSocket;
    private static volatile long sLastBrokenPipeLogMs = 0L;
    private static final AtomicInteger sSuppressedBrokenPipeCount = new AtomicInteger(0);

    private static volatile BooleanSupplier sIsShuttingDown;
    private static volatile IntPredicate sIsTrustedCaller;
    private static volatile Runnable sOnShutdown;
    private static volatile Runnable sOnNeedBinder;

    private DaemonControlSocket() {}

    public static void bindHandlers(
            BooleanSupplier isShuttingDown, IntPredicate isTrustedCaller,
            Runnable onShutdown, Runnable onNeedBinder) {
        sIsShuttingDown = isShuttingDown;
        sIsTrustedCaller = isTrustedCaller;
        sOnShutdown = onShutdown;
        sOnNeedBinder = onNeedBinder;
    }

    public static boolean sendShutdown(int timeoutMs) {
        try (LocalSocket client = new LocalSocket()) {
            client.connect(new LocalSocketAddress(
                    BridgeProtocol.CTL_SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            Credentials peer = client.getPeerCredentials();
            if (peer == null || !HelperClientBridge.isTrustedDaemonServerUid(peer.getUid())) {
                System.err.println("OPALDESK_CTL_SQUATTER_REJECTED peerUid=" + (peer == null ? -1 : peer.getUid()));
                return false;
            }
            client.setSoTimeout(timeoutMs);
            client.getOutputStream().write((BridgeProtocol.CTL_CMD_SHUTDOWN + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)).readLine();
        } catch (IOException e) {
            System.err.println("OPALDESK_CTL_SEND_INFO: " + e.getMessage());
        }
        return true;
    }

    public static boolean acquireAndStart() {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                sServerSocket = new LocalServerSocket(BridgeProtocol.CTL_SOCKET_NAME);
                break;
            } catch (IOException bindBusy) {
                if (!sendShutdown(4500)) return false;
                SystemClock.sleep(200L);
            }
        }
        if (sServerSocket == null) return false;
        Thread t = new Thread(DaemonControlSocket::acceptLoop, "ExtCtlSocket");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void acceptLoop() {
        while (sServerSocket != null) {
            BooleanSupplier shuttingDown = sIsShuttingDown;
            if (shuttingDown != null && shuttingDown.getAsBoolean()) break;
            try (LocalSocket sock = sServerSocket.accept()) {
                sock.setSoTimeout(1500);
                Credentials cred = sock.getPeerCredentials();
                IntPredicate trustCheck = sIsTrustedCaller;
                boolean trusted = (cred != null) && (trustCheck != null
                        ? trustCheck.test(cred.getUid())
                        : HelperClientBridge.isTrustedDaemonServerUid(cred.getUid()));
                if (!trusted) continue;
                String cmd = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)).readLine();
                if (BridgeProtocol.CTL_CMD_SHUTDOWN.equals(cmd)) {
                    Runnable shutdown = sOnShutdown;
                    if (shutdown != null) shutdown.run();
                    writeReply(sock, BridgeProtocol.CTL_REPLY_DONE);
                    LocalServerSocket srv = sServerSocket;
                    sServerSocket = null;
                    if (srv != null) {
                        try { srv.close(); } catch (IOException e) { System.err.println("OPALDESK_CTL_CLOSE_WARN: " + e.getMessage()); }
                    }
                    SystemClock.sleep(40L);
                    System.exit(0);
                    return;
                } else if (BridgeProtocol.CTL_CMD_NEED_BINDER.equals(cmd)) {
                    Runnable needBinder = sOnNeedBinder;
                    if (needBinder != null) needBinder.run();
                    writeReply(sock, BridgeProtocol.CTL_REPLY_OK);
                }
            } catch (IOException e) {
                BooleanSupplier sd = sIsShuttingDown;
                if (sd != null && sd.getAsBoolean()) break;
                System.err.println("OPALDESK_CTL_ACCEPT_WARN: " + e.getMessage());
            }
        }
    }

    private static void writeReply(LocalSocket sock, String reply) {
        try {
            sock.getOutputStream().write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            sock.getOutputStream().flush();
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Broken pipe") || msg.contains("EPIPE"))) {
                long now = SystemClock.uptimeMillis();
                long prev = sLastBrokenPipeLogMs;
                if (prev != 0L && now - prev < BROKEN_PIPE_LOG_INTERVAL_MS) {
                    sSuppressedBrokenPipeCount.incrementAndGet();
                    return;
                }
                sLastBrokenPipeLogMs = now;
                int suppressed = sSuppressedBrokenPipeCount.getAndSet(0);
                System.err.println("OPALDESK_CTL_REPLY_WARN: " + msg
                        + (suppressed > 0 ? " (suppressed=" + suppressed + ")" : ""));
                return;
            }
            System.err.println("OPALDESK_CTL_REPLY_WARN: " + msg);
        }
    }
}
