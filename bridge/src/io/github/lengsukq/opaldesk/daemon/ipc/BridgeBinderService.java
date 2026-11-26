package io.github.lengsukq.opaldesk.daemon.ipc;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.input.ExternalMouseRouter;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.TaskApiAdapter;
import io.github.lengsukq.opaldesk.daemon.stream.OpalCastCompositorBridge;
import io.github.lengsukq.opaldesk.daemon.stream.RemoteStreamServer;
import io.github.lengsukq.opaldesk.daemon.util.DaemonLogBuffer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binder IPC dispatcher for the OpalDesk Universal Compositor, External Display EDID controls,
 * physical mouse display routing, and MacOS OpalCast v1.1 remote streaming.
 */
public final class BridgeBinderService extends Binder {
    private final AndroidServices services;
    private final DaemonConfig config;
    private final HelperClientBridge helperBridge;
    private final CompositorEngine compositor;
    private final PerDisplayInputRouter inputRouter;
    private final ExternalMouseRouter mouseRouter;
    private final TaskApiAdapter taskApi;
    private final OpalCastCompositorBridge remoteBridge;
    private final Runnable shutdownTrigger;

    public BridgeBinderService(
            AndroidServices services,
            DaemonConfig config,
            HelperClientBridge helperBridge,
            CompositorEngine compositor,
            PerDisplayInputRouter inputRouter,
            ExternalMouseRouter mouseRouter,
            TaskApiAdapter taskApi,
            OpalCastCompositorBridge remoteBridge,
            Runnable shutdownTrigger) {
        this.services = services;
        this.config = config;
        this.helperBridge = helperBridge;
        this.compositor = compositor;
        this.inputRouter = inputRouter;
        this.mouseRouter = mouseRouter;
        this.taskApi = taskApi;
        this.remoteBridge = remoteBridge;
        this.shutdownTrigger = shutdownTrigger;
        attachInterface(null, BridgeProtocol.BINDER_DESC);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(BridgeProtocol.BINDER_DESC);
            return true;
        }
        if (!helperBridge.isTrustedCaller(Binder.getCallingUid())) {
            int callerUid = Binder.getCallingUid();
            System.err.println("OPALDESK_BINDER_REJECTED uid=" + callerUid + " code=" + code);
            throw new SecurityException("Untrusted OpalDesk caller uid=" + callerUid);
        }
        if (code >= 10 && code <= 99) {
            if (data == null) return false;
            data.enforceInterface(BridgeProtocol.BINDER_DESC);
        }

        switch (code) {
            case BridgeProtocol.TX_GET_DAEMON_STATUS: {
                if (reply != null) {
                    reply.writeInt(BridgeProtocol.PROTOCOL_VERSION);
                    reply.writeInt(android.os.Process.myUid());
                    reply.writeInt(inputRouter.getLastInjectStatus());
                    reply.writeInt(config.getInputBackendMode());
                    reply.writeInt(compositor.getActiveDisplayCount());
                    reply.writeInt(compositor.getEffectiveMaxDisplays());
                    reply.writeInt(compositor.getWindowCount());
                    reply.writeInt(config.isRootEnhanced() ? 1 : 0);
                    reply.writeInt(config.getCanvasWidth());
                    reply.writeInt(config.getCanvasHeight());
                    reply.writeInt(config.getCanvasDpi());
                    reply.writeInt(config.getTargetFps());
                    reply.writeInt(config.getWindowDpiScalePct());
                    reply.writeInt(config.getMouseTargetMode());
                    reply.writeInt(mouseRouter != null ? mouseRouter.getBoundMouseCount() : 0);
                    reply.writeInt(mouseRouter != null ? mouseRouter.getConnectedMouseCount() : 0);
                    reply.writeInt(mouseRouter != null ? mouseRouter.getBoundDisplayId() : -1);
                    reply.writeInt(services != null && services.isHostScreenOff() ? 1 : 0);
                    reply.writeInt(config.getGlobalCustomDpi());
                    reply.writeInt(config.isRemoteMicEnabled() ? 1 : 0);
                    reply.writeInt(config.isRemoteCameraEnabled() ? 1 : 0);
                    reply.writeInt(config.getAudioRouteMode());
                }
                return true;
            }
            case BridgeProtocol.TX_GET_SCENE_SNAPSHOT: {
                if (reply != null) {
                    reply.writeString(compositor.toSceneJson());
                }
                return true;
            }
            case BridgeProtocol.TX_OPEN_WINDOW: {
                String pkg = data.readString();
                String cls = data.readString();
                int profileCode = data.readInt();
                int left = data.readInt();
                int top = data.readInt();
                int right = data.readInt();
                int bottom = data.readInt();
                int winId = compositor.openOrFocusWindow(pkg, cls, profileCode, left, top, right, bottom);
                if (reply != null) reply.writeInt(winId);
                return true;
            }
            case BridgeProtocol.TX_ATTACH_WINDOW_SURFACE: {
                int winId = data.readInt();
                int cw = data.readInt();
                int ch = data.readInt();
                int dpi = data.readInt();
                int fpsX100 = data.readInt();
                Surface surf = (data.readInt() != 0) ? Surface.CREATOR.createFromParcel(data) : null;
                float fps = (fpsX100 > 0) ? (fpsX100 / 100.0f) : 60.0f;
                int displayId = compositor.attachWindowSurface(winId, surf, cw, ch, dpi, fps);
                if (reply != null) reply.writeInt(displayId);
                return true;
            }
            case BridgeProtocol.TX_DETACH_WINDOW_SURFACE: {
                int winId = data.readInt();
                boolean releaseDisplay = data.readInt() != 0;
                if (releaseDisplay) {
                    WindowSession ws = compositor.getWindowSnapshot(winId);
                    if (ws != null && ws.taskId > 0) {
                        byte[] snap = taskApi.captureTaskSnapshotJpeg(ws.taskId);
                        compositor.updateCachedSnapshot(winId, snap);
                    }
                }
                boolean ok = compositor.detachWindowSurface(winId, releaseDisplay);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_UPDATE_WINDOW_FRAME: {
                int winId = data.readInt();
                int left = data.readInt();
                int top = data.readInt();
                int right = data.readInt();
                int bottom = data.readInt();
                String pres = data.readString();
                boolean ok = compositor.updateWindowFrame(winId, left, top, right, bottom, pres);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_RESIZE_WINDOW_DISPLAY: {
                int winId = data.readInt();
                int cw = data.readInt();
                int ch = data.readInt();
                int dpi = data.readInt();
                boolean ok = compositor.resizeWindowDisplay(winId, cw, ch, dpi);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_WINDOW_ACTION: {
                int winId = data.readInt();
                int action = data.readInt();
                WindowSession ws = compositor.getWindowSnapshot(winId);
                if (action == BridgeProtocol.WIN_ACTION_SEND_BACK) {
                    boolean ok = inputRouter.sendBackToWindow(ws);
                    if (ok && ws != null) {
                        compositor.notifyBackSent(ws.windowId);
                    }
                    if (reply != null) reply.writeInt(ok ? 1 : 0);
                    return true;
                }
                if (action == BridgeProtocol.WIN_ACTION_FOCUS
                        || action == BridgeProtocol.WIN_ACTION_MINIMIZE
                        || action == BridgeProtocol.WIN_ACTION_CLOSE) {
                    inputRouter.releaseAllHeldKeys();
                }
                byte[] preSnap = null;
                if (action == BridgeProtocol.WIN_ACTION_MINIMIZE && ws != null && ws.taskId > 0) {
                    preSnap = taskApi.captureTaskSnapshotJpeg(ws.taskId);
                }
                boolean ok = compositor.performWindowAction(winId, action, preSnap);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_INJECT_WINDOW_MOTION: {
                int winId = data.readInt();
                int action = data.readInt();
                long downTime = data.readLong();
                long eventTime = data.readLong();
                int source = data.readInt();
                int buttonState = data.readInt();
                int actionButton = data.readInt();
                float hScroll = data.readFloat();
                float vScroll = data.readFloat();
                int count = Math.max(0, Math.min(10, data.readInt()));
                if (count <= 0) {
                    if (reply != null) reply.writeInt(0);
                    return true;
                }
                int[] ids = new int[count];
                int[] toolTypes = new int[count];
                float[] xs = new float[count];
                float[] ys = new float[count];
                float[] pressures = new float[count];
                for (int i = 0; i < count; i++) {
                    ids[i] = data.readInt();
                    toolTypes[i] = data.readInt();
                    xs[i] = data.readFloat();
                    ys[i] = data.readFloat();
                    pressures[i] = data.readFloat();
                }
                WindowSession ws = compositor.getWindowSnapshot(winId);
                boolean ok = inputRouter.injectWindowMotion(
                        ws, action, downTime, eventTime, source, buttonState, actionButton,
                        hScroll, vScroll, ids, toolTypes, xs, ys, pressures);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_INJECT_WINDOW_KEY: {
                int winId = data.readInt();
                int action = data.readInt();
                int keyCode = data.readInt();
                int metaState = data.readInt();
                int deviceId = (data.dataAvail() >= 4) ? data.readInt() : KeyCharacterMap.VIRTUAL_KEYBOARD;
                int scanCode = (data.dataAvail() >= 4) ? data.readInt() : 0;
                int repeatCount = (data.dataAvail() >= 4) ? data.readInt() : 0;
                WindowSession ws = compositor.getWindowSnapshot(winId);
                boolean ok = inputRouter.injectWindowKey(ws, action, keyCode, metaState, deviceId, scanCode, repeatCount);
                if (ok && ws != null && keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_UP) {
                    compositor.notifyBackSent(ws.windowId);
                }
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_GET_WINDOW_SNAPSHOT: {
                int winId = data.readInt();
                byte[] snap = compositor.getHibernatedSnapshotBytes(winId);
                if (snap == null) {
                    WindowSession ws = compositor.getWindowSnapshot(winId);
                    if (ws != null && ws.taskId > 0) {
                        snap = taskApi.captureTaskSnapshotJpeg(ws.taskId);
                        compositor.updateCachedSnapshot(winId, snap);
                    }
                }
                if (reply != null) reply.writeByteArray(snap != null ? snap : new byte[0]);
                return true;
            }
            case BridgeProtocol.TX_UPDATE_COMPOSITOR_CONFIG: {
                int inputMode = data.readInt();
                int maxVd = data.readInt();
                int canvasW = data.readInt();
                int canvasH = data.readInt();
                int canvasDpi = data.readInt();
                int prevInputMode = config.getInputBackendMode();
                int prevMaxVd = config.getMaxActiveDisplays();
                config.updateRuntimeConfig(inputMode, maxVd, canvasW, canvasH, canvasDpi);
                if (maxVd >= DaemonConfig.MIN_MAX_VD && maxVd <= DaemonConfig.HARD_MAX_VD) {
                    compositor.setConfiguredMaxDisplays(config.getMaxActiveDisplays());
                }
                int nextInputMode = config.getInputBackendMode();
                int nextMaxVd = config.getMaxActiveDisplays();
                int status = (inputMode >= BridgeProtocol.INPUT_BACKEND_AUTO
                        && inputMode <= BridgeProtocol.INPUT_BACKEND_UHID
                        && (nextInputMode != prevInputMode || nextInputMode == BridgeProtocol.INPUT_BACKEND_UHID))
                        ? inputRouter.onInputBackendConfigChanged(nextInputMode)
                        : inputRouter.getLastInjectStatus();
                if (nextInputMode != prevInputMode || nextMaxVd != prevMaxVd) {
                    System.out.println("OPALDESK_CFG_UPDATE inputMode=" + prevInputMode + "->" + nextInputMode
                            + " injectStatus=" + status + " maxVd=" + prevMaxVd + "->" + nextMaxVd);
                }
                if (reply != null) reply.writeInt(status);
                return true;
            }
            case BridgeProtocol.TX_CLOSE_ALL_WINDOWS: {
                boolean removeTasks = data.readInt() != 0;
                System.out.println("OPALDESK_CLOSE_ALL_WINDOWS removeTasks=" + removeTasks);
                compositor.closeAllWindows(removeTasks);
                if (reply != null) reply.writeInt(1);
                return true;
            }
            case BridgeProtocol.TX_GET_REMOTE_STREAM_STATUS: {
                OpalCastCompositorBridge.RemoteStatusSnapshot st = (remoteBridge != null)
                        ? remoteBridge.getStatusSnapshot()
                        : new OpalCastCompositorBridge.RemoteStatusSnapshot(false, 0, "", 0, 0, 0, 0, false, 0, "", false, 0, "", -1);
                if (reply != null) {
                    reply.writeInt(st.connected ? 1 : 0);
                    reply.writeInt(st.sinkType);
                    reply.writeString(st.peerName);
                    reply.writeInt(st.width);
                    reply.writeInt(st.height);
                    reply.writeInt(st.fpsX100);
                    reply.writeInt(st.bitrateKbps);
                    reply.writeInt(st.wifiEnabled ? 1 : 0);
                    reply.writeInt(st.wifiPort);
                    reply.writeString(st.certFingerprint);
                    reply.writeInt(st.activeSessionPermanent ? 1 : 0);
                    reply.writeInt(st.trustedCount);
                    reply.writeString(st.lanIp);
                    reply.writeInt(st.desktopDisplayId);
                    reply.writeString(st.usbTetherIp);
                    reply.writeString(st.usbTetherInterface);
                    reply.writeInt(st.tlsBindScope);
                }
                return true;
            }
            case BridgeProtocol.TX_SET_WIFI_STREAM_ENABLED: {
                boolean enable = data.readInt() != 0;
                int port = data.readInt();
                int bindScope = (data.dataAvail() >= 4)
                        ? data.readInt()
                        : BridgeProtocol.TLS_BIND_SCOPE_ALL;
                RemoteStreamServer.WifiConfigStatus st = (remoteBridge != null)
                        ? remoteBridge.configureWifi(enable, port, bindScope)
                        : new RemoteStreamServer.WifiConfigStatus(false, 0, "", BridgeProtocol.TLS_BIND_SCOPE_ALL);
                System.out.println("OPALDESK_WIFI_STREAM_CFG reqEnable=" + enable
                        + " scope=" + bindScope + " effective=" + st.enabled
                        + " effScope=" + st.bindScope + " port=" + st.port);
                if (reply != null) {
                    reply.writeInt(st.enabled ? 1 : 0);
                    reply.writeInt(st.port);
                    reply.writeString(st.certFingerprint);
                    reply.writeInt(st.bindScope);
                }
                return true;
            }
            case BridgeProtocol.TX_REGENERATE_WIFI_PIN: {
                // Legacy no-op: static 6-digit PIN pairing has been replaced by interactive consent.
                if (reply != null) {
                    reply.writeString("");
                }
                return true;
            }
            case BridgeProtocol.TX_DISCONNECT_REMOTE_STREAM: {
                if (remoteBridge != null) remoteBridge.disconnectActiveClient();
                if (reply != null) reply.writeInt(1);
                return true;
            }
            case BridgeProtocol.TX_SET_EXTERNAL_DISPLAY_MODE: {
                int displayId = data.readInt();
                int reqW = data.readInt();
                int reqH = data.readInt();
                int fpsX100 = data.readInt();
                float reqFps = fpsX100 / 100f;
                boolean ok;
                if (remoteBridge != null && remoteBridge.hasActiveDesktopSession()
                        && (displayId <= 0 || displayId == remoteBridge.getActiveDesktopDisplayId())) {
                    ok = remoteBridge.reconfigureActiveDesktop(reqW, reqH, reqFps);
                } else {
                    ok = services != null
                            && services.setExternalDisplayMode(displayId, reqW, reqH, reqFps);
                }
                System.out.println("OPALDESK_EXT_DISPLAY_MODE displayId=" + displayId
                        + " req=" + reqW + "x" + reqH + "@" + reqFps + " ok=" + ok);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_UPDATE_DISPLAY_PREFS: {
                int targetFps = data.readInt();
                int dpiScalePct = data.readInt();
                int prevFps = config.getTargetFps();
                int prevDpi = config.getWindowDpiScalePct();
                config.updateDisplayPrefs(targetFps, dpiScalePct);
                int nextFps = config.getTargetFps();
                int nextDpi = config.getWindowDpiScalePct();
                if (nextDpi != prevDpi) {
                    compositor.reapplyWindowDpiScale();
                }
                if (nextFps != prevFps) {
                    float autoFallback = (remoteBridge != null && remoteBridge.hasActiveDesktopSession())
                            ? remoteBridge.getActiveNativeFps() : 60f;
                    compositor.reapplyTargetFps(autoFallback);
                    if (remoteBridge != null) {
                        remoteBridge.applyTargetFpsIfActive(nextFps);
                    }
                }
                if (reply != null) reply.writeInt(1);
                return true;
            }
            case BridgeProtocol.TX_SET_MOUSE_TARGET: {
                int targetMode = data.readInt();
                int externalDisplayId = data.readInt();
                if (externalDisplayId <= 0 && targetMode == BridgeProtocol.MOUSE_TARGET_EXTERNAL
                        && remoteBridge != null && remoteBridge.hasActiveDesktopSession()) {
                    externalDisplayId = remoteBridge.getActiveDesktopDisplayId();
                }
                int boundCount = (mouseRouter != null)
                        ? mouseRouter.setMouseTarget(targetMode, externalDisplayId)
                        : 0;
                if (reply != null) {
                    reply.writeInt(1);
                    reply.writeInt(boundCount);
                }
                return true;
            }
            case BridgeProtocol.TX_SET_POINTER_SPEED: {
                float sensitivity = data.readFloat();
                boolean ok = mouseRouter != null && mouseRouter.applyHardwarePointerSpeed(sensitivity);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_SET_HOST_SCREEN_POWER: {
                boolean screenOn = data.readInt() != 0;
                boolean ok = services != null && services.setPrimaryPhysicalDisplayPower(screenOn);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_SET_LOCAL_IME_FOCUS: {
                boolean focused = data.readInt() != 0;
                if (inputRouter != null) {
                    inputRouter.setLocalImeActive(focused);
                }
                if (remoteBridge != null) {
                    remoteBridge.setLocalHostTextFieldFocused(focused);
                }
                if (focused && services != null) {
                    services.focusDisplay(android.view.Display.DEFAULT_DISPLAY);
                }
                if (reply != null) reply.writeInt(1);
                return true;
            }
            case BridgeProtocol.TX_GET_TRUSTED_PEERS: {
                String json = (remoteBridge != null) ? remoteBridge.getTrustedPeersJson() : "[]";
                if (reply != null) reply.writeString(json);
                return true;
            }
            case BridgeProtocol.TX_REVOKE_TRUSTED_PEER: {
                String clientId = data.readString();
                boolean ok = remoteBridge != null && remoteBridge.revokeTrustedClient(clientId);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_GET_PENDING_PAIRING: {
                String json = (remoteBridge != null) ? remoteBridge.getPendingPairingJson() : "";
                if (reply != null) reply.writeString(json);
                return true;
            }
            case BridgeProtocol.TX_RESPOND_PAIRING: {
                String requestId = data.readString();
                int decision = data.readInt();
                boolean ok = remoteBridge != null && remoteBridge.respondToPairing(requestId, decision);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_INJECT_REMOTE_TRACKPAD: {
                int action = data.readInt();
                float x = data.readFloat();
                float y = data.readFloat();
                float hScroll = data.readFloat();
                float vScroll = data.readFloat();
                int buttonState = data.readInt();
                boolean ok = remoteBridge != null
                        && remoteBridge.injectHostTrackpadEvent(action, x, y, hScroll, vScroll, buttonState);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_GET_DAEMON_LOGS: {
                int maxLines = data.readInt();
                String logs = DaemonLogBuffer.snapshotTail(maxLines);
                if (reply != null) reply.writeString(logs);
                return true;
            }
            case BridgeProtocol.TX_SET_ADVANCED_DPI_CONFIG: {
                int globalCustomDpi = data.readInt();
                boolean replaceAppDpis = data.readInt() != 0;
                String appDpisRaw = data.readString();
                config.setGlobalCustomDpi(globalCustomDpi);
                if (replaceAppDpis) {
                    config.replaceAllAppCustomDpis(parseAppDpisPayload(appDpisRaw));
                }
                compositor.reapplyWindowDpiScale();
                if (reply != null) reply.writeInt(1);
                return true;
            }
            case BridgeProtocol.TX_SET_APP_CUSTOM_DPI: {
                String pkg = data.readString();
                int customDpi = data.readInt();
                config.setAppCustomDpi(pkg, customDpi);
                compositor.reapplyAppDpi(pkg);
                if (reply != null) reply.writeInt(1);
                return true;
            }
            case BridgeProtocol.TX_SYNC_APP_WINDOW_BOUNDS: {
                String boundsRaw = data.readString();
                config.replaceAllWindowBounds(parseAppBoundsPayload(boundsRaw));
                if (reply != null) reply.writeInt(1);
                return true;
            }
            case BridgeProtocol.TX_GET_ADVANCED_CONFIG_STATE: {
                if (reply != null) reply.writeString(config.toAdvancedStateJson());
                return true;
            }
            case BridgeProtocol.TX_SET_REMOTE_MEDIA_CONFIG: {
                boolean micEnabled = data.readInt() != 0;
                boolean cameraEnabled = data.readInt() != 0;
                boolean accepted = config.setRemoteMediaConfig(micEnabled, cameraEnabled);
                boolean effectiveCamera = config.isRemoteCameraEnabled();
                if (remoteBridge != null) {
                    remoteBridge.applyRemoteMediaPolicy(micEnabled, effectiveCamera);
                }
                if (reply != null) reply.writeInt(accepted ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_SET_AUDIO_ROUTE_MODE: {
                int mode = data.readInt();
                config.setAudioRouteMode(mode);
                if (remoteBridge != null) {
                    remoteBridge.applyAudioRouteMode(config.getAudioRouteMode());
                }
                if (reply != null) reply.writeInt(1);
                return true;
            }
            case BridgeProtocol.TX_LAUNCH_EXTERNAL_DESKTOP: {
                int displayId = data.readInt();
                boolean isWireless = data.readInt() != 0;
                boolean ok = services != null && services.launchExternalDesktopOnDisplay(displayId, isWireless);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case BridgeProtocol.TX_SHUTDOWN: {
                System.out.println("OPALDESK_SHUTDOWN_REQUESTED");
                shutdownTrigger.run();
                if (reply != null) reply.writeInt(1);
                new Thread(() -> {
                    SystemClock.sleep(150L);
                    System.exit(0);
                }, "OpalBinderExit").start();
                return true;
            }
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    private static Map<String, Integer> parseAppDpisPayload(String raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\r?\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String pkg = line.substring(0, eq).trim();
            try {
                int dpi = Integer.parseInt(line.substring(eq + 1).trim());
                if (dpi > 0) out.put(pkg, dpi);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static Map<String, DaemonConfig.SavedWindowBounds> parseAppBoundsPayload(String raw) {
        Map<String, DaemonConfig.SavedWindowBounds> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\r?\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String pkg = line.substring(0, eq).trim();
            String[] parts = line.substring(eq + 1).trim().split(",");
            if (parts.length < 4) continue;
            try {
                float l = Float.parseFloat(parts[0].trim());
                float t = Float.parseFloat(parts[1].trim());
                float r = Float.parseFloat(parts[2].trim());
                float b = Float.parseFloat(parts[3].trim());
                String pres = parts.length >= 5 ? parts[4].trim() : BridgeProtocol.PRES_FLOATING;
                out.put(pkg, new DaemonConfig.SavedWindowBounds(l, t, r, b, pres));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
