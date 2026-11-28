package io.github.lengsukq.opaldesk.daemon.stream;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.input.ExternalMouseRouter;
import io.github.lengsukq.opaldesk.daemon.input.MouseRoutingPolicy;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.VirtualDisplayFactory;
import io.github.lengsukq.opaldesk.daemon.sink.RemoteStreamSink;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Coordinates DeX vs. Mirror display mode switching, mirror orientation tracking,
 * and live resolution/DPI/FPS/bitrate reconfiguration for active OpalCast sessions.
 */
final class OpalCastDisplayModeCoordinator {
    private final AndroidServices services;
    private final DaemonConfig config;
    private final ExternalMouseRouter mouseRouter;
    private final RemoteStreamServer server;
    private final RemoteMediaDevicesCoordinator mediaDevices;
    private final Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions;
    private final IntSupplier desktopSessionIdSupplier;
    private final BooleanSupplier bridgeImeActiveSupplier;
    private final ExecutorService worker;
    private final Consumer<OpalCastCompositorBridge.ActiveRemoteSession> pushDeviceState;
    private final Runnable notifyRemoteStateChanged;
    private final OpalCastStandaloneSessionHandler sessionHandler;

    private final Object mirrorListenerLock = new Object();
    private DisplayManager.DisplayListener mirrorDisplayListener;

    OpalCastDisplayModeCoordinator(
            AndroidServices services,
            DaemonConfig config,
            ExternalMouseRouter mouseRouter,
            RemoteStreamServer server,
            RemoteMediaDevicesCoordinator mediaDevices,
            Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions,
            IntSupplier desktopSessionIdSupplier,
            BooleanSupplier bridgeImeActiveSupplier,
            ExecutorService worker,
            Consumer<OpalCastCompositorBridge.ActiveRemoteSession> pushDeviceState,
            Runnable notifyRemoteStateChanged,
            OpalCastStandaloneSessionHandler sessionHandler) {
        this.services = services;
        this.config = config;
        this.mouseRouter = mouseRouter;
        this.server = server;
        this.mediaDevices = mediaDevices;
        this.sessions = sessions;
        this.desktopSessionIdSupplier = desktopSessionIdSupplier;
        this.bridgeImeActiveSupplier = bridgeImeActiveSupplier;
        this.worker = worker;
        this.pushDeviceState = pushDeviceState;
        this.notifyRemoteStateChanged = notifyRemoteStateChanged;
        this.sessionHandler = sessionHandler;
    }

    boolean reconfigureActiveDesktop(int reqW, int reqH, float reqFps) {
        int sid = desktopSessionIdSupplier.getAsInt();
        OpalCastCompositorBridge.ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
        if (s == null || s.closed) return false;
        boolean resetToNative = (reqW <= 0 && reqH <= 0 && reqFps <= 0f);
        int targetW = resetToNative ? s.initialWidth : (reqW > 0 ? reqW : s.width);
        int targetH = resetToNative ? s.initialHeight : (reqH > 0 ? reqH : s.height);
        int targetDpi;
        if (resetToNative) {
            targetDpi = s.initialDpi;
        } else if (reqW > 0 && reqH > 0 && (reqW != s.width || reqH != s.height)) {
            int shortEdge = Math.min(targetW, targetH);
            targetDpi = Math.max(160, Math.min(440, Math.round(shortEdge * 160f / 720f)));
        } else {
            targetDpi = s.dpi;
        }
        int fpsX100 = resetToNative
                ? Math.round(s.requestedNativeFps * 100f)
                : (reqFps > 0f ? Math.round(reqFps * 100f) : Math.round(s.fps * 100f));
        OpalCastProtocol.ResizeConfig cfg = new OpalCastProtocol.ResizeConfig(
                targetW, targetH, targetDpi, fpsX100, 0,
                OpalCastProtocol.DISPLAY_MODE_UNCHANGED, 0xFF);
        worker.execute(() -> reconfigureSession(s, cfg));
        return true;
    }

    void switchDesktopDisplayMode(OpalCastCompositorBridge.ActiveRemoteSession s, int targetMode) {
        if (s == null || s.standalone) return;
        int normalized = (targetMode == OpalCastProtocol.DISPLAY_MODE_MIRROR)
                ? OpalCastProtocol.DISPLAY_MODE_MIRROR
                : OpalCastProtocol.DISPLAY_MODE_DEX;
        synchronized (s.lock) {
            if (s.closed || s.sink == null) return;
            if (s.displayMode == normalized) {
                pushDeviceState.accept(s);
                return;
            }
            if (normalized == OpalCastProtocol.DISPLAY_MODE_MIRROR) {
                switchToMirrorModeLocked(s);
            } else {
                switchToDexModeLocked(s);
            }
        }
    }

    void switchToMirrorModeLocked(OpalCastCompositorBridge.ActiveRemoteSession s) {
        int[] d0 = services.getPrimaryDisplayState();
        sessionHandler.populatePrimaryMirrorStateLocked(s, d0);
        int[] mSpec = DisplayGeometryUtil.computeMirrorStreamSpec(
                s.mirrorSourceWidth, s.mirrorSourceHeight, s.mirrorSourceDpi,
                Math.max(s.dexWidth, s.dexHeight), 8);
        int mirrorBitrate = OpalCastCompositorBridge.resolveSessionBitrateKbps(
                0, mSpec[0], mSpec[1], s.fps, s.codecPref, s.sinkType);
        final boolean[] vdSwapped = new boolean[]{false};

        boolean ok = s.sink.reconfigureAndSwap(
                mSpec[0], mSpec[1], mSpec[2], s.fps, mirrorBitrate,
                s.codecPref, OpalCastProtocol.DISPLAY_MODE_MIRROR, s.mirrorRotation,
                (newSurface, alignedW, alignedH, alignedDpi) -> {
                    sessionHandler.releaseSessionVirtualDisplayLocked(s, true);
                    VirtualDisplay mirrorVd = VirtualDisplayFactory.createMirrorDisplay(
                            services.displayManager, "OpalDesk-Mirror",
                            alignedW, alignedH, alignedDpi, Display.DEFAULT_DISPLAY, newSurface);
                    if (mirrorVd != null && mirrorVd.getDisplay() != null) {
                        sessionHandler.bindVirtualDisplayMetadataLocked(s, mirrorVd);
                        s.displayMode = OpalCastProtocol.DISPLAY_MODE_MIRROR;
                        s.width = alignedW;
                        s.height = alignedH;
                        s.dpi = alignedDpi;
                        s.bitrateKbps = mirrorBitrate;
                        vdSwapped[0] = true;
                    }
                });

        if (!ok || !vdSwapped[0]) {
            System.err.println("OPALDESK_REMOTE_MODE_SWITCH_FAILED sid=" + s.sessionId + " target=MIRROR");
            if (s.vd == null) {
                restoreDexDisplayLocked(s);
            }
            pushDeviceState.accept(s);
            notifyRemoteStateChanged.run();
            return;
        }

        if (mouseRouter != null) {
            mouseRouter.releaseToLocal();
        }
        services.focusDisplay(Display.DEFAULT_DISPLAY);
        mediaDevices.syncAudioRoutingForMode(true);
        registerMirrorDisplayListener();
        pushDeviceState.accept(s);
        notifyRemoteStateChanged.run();
        System.out.println("OPALDESK_REMOTE_MODE_SWITCH sid=" + s.sessionId
                + " mode=MIRROR stream=" + s.width + "x" + s.height + "@" + s.dpi
                + " d0=" + s.mirrorSourceWidth + "x" + s.mirrorSourceHeight + " rot=" + s.mirrorRotation
                + " vdDisplayId=" + s.displayId);
    }

    void switchToDexModeLocked(OpalCastCompositorBridge.ActiveRemoteSession s) {
        unregisterMirrorDisplayListener();
        restoreDexDisplayLocked(s);
        pushDeviceState.accept(s);
        notifyRemoteStateChanged.run();
    }

    void restoreDexDisplayLocked(OpalCastCompositorBridge.ActiveRemoteSession s) {
        int targetW = s.dexWidth > 0 ? s.dexWidth : s.initialWidth;
        int targetH = s.dexHeight > 0 ? s.dexHeight : s.initialHeight;
        int targetDpi = s.dexDpi > 0 ? s.dexDpi : s.initialDpi;
        int dexBitrate = OpalCastCompositorBridge.resolveSessionBitrateKbps(
                s.dexRequestedBitrateKbps, targetW, targetH, s.fps, s.codecPref, s.sinkType);
        final boolean[] vdSwapped = new boolean[]{false};

        boolean ok = s.sink.reconfigureAndSwap(
                targetW, targetH, targetDpi, s.fps, dexBitrate,
                s.codecPref, OpalCastProtocol.DISPLAY_MODE_DEX, 0,
                (newSurface, alignedW, alignedH, alignedDpi) -> {
                    boolean wasDex = !s.isMirror();
                    sessionHandler.releaseSessionVirtualDisplayLocked(s, wasDex);
                    int[] chosenFlags = new int[1];
                    VirtualDisplay dexVd = VirtualDisplayFactory.createWithFlagFallback(
                            services.displayManager, "OpalDesk-Remote",
                            alignedW, alignedH, alignedDpi,
                            OpalCastStandaloneSessionHandler.desktopVdCandidates(), 0f, newSurface, chosenFlags);
                    if (dexVd != null && dexVd.getDisplay() != null) {
                        s.vdTrusted = (chosenFlags[0] & BridgeProtocol.VD_FLAG_TRUSTED) != 0;
                        sessionHandler.bindVirtualDisplayMetadataLocked(s, dexVd);
                        int remoteImePolicy = MouseRoutingPolicy.resolveRemoteOuterDisplayImePolicy(
                                config.getDexImeDisplayMode(), config.isMuteHostImeOnCast(),
                                s.vdTrusted, bridgeImeActiveSupplier.getAsBoolean());
                        services.configureVirtualDisplayWindowing(s.displayId, remoteImePolicy);
                        s.displayMode = OpalCastProtocol.DISPLAY_MODE_DEX;
                        s.width = alignedW;
                        s.height = alignedH;
                        s.dpi = alignedDpi;
                        s.dexWidth = alignedW;
                        s.dexHeight = alignedH;
                        s.dexDpi = alignedDpi;
                        s.bitrateKbps = dexBitrate;
                        vdSwapped[0] = true;
                    }
                });

        if (!ok || !vdSwapped[0]) {
            System.err.println("OPALDESK_REMOTE_MODE_SWITCH_FAILED sid=" + s.sessionId + " target=DEX");
            return;
        }
        sessionHandler.launchRemoteDesktopHost(s.displayId);
        if (mouseRouter != null) {
            mouseRouter.setMouseTarget(config.getMouseTargetMode(), s.displayId);
        }
        mediaDevices.syncAudioRoutingForMode(false);
        System.out.println("OPALDESK_REMOTE_MODE_SWITCH sid=" + s.sessionId
                + " mode=DEX size=" + s.width + "x" + s.height + "@" + s.dpi
                + " displayId=" + s.displayId);
    }

    void registerMirrorDisplayListener() {
        if (services.displayManager == null) return;
        synchronized (mirrorListenerLock) {
            if (mirrorDisplayListener != null) return;
            DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) return;
                    int sid = desktopSessionIdSupplier.getAsInt();
                    OpalCastCompositorBridge.ActiveRemoteSession s = (sid > 0) ? sessions.get(sid) : null;
                    if (s != null && !s.closed && s.isMirror()) {
                        worker.execute(() -> syncMirrorOrientationIfChanged(s));
                    }
                }
            };
            try {
                Looper mainLooper = Looper.getMainLooper();
                Handler handler = (mainLooper != null) ? new Handler(mainLooper) : null;
                services.displayManager.registerDisplayListener(listener, handler);
                mirrorDisplayListener = listener;
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_MIRROR_LISTENER_WARN: " + e.getMessage());
            }
        }
    }

    void unregisterMirrorDisplayListener() {
        DisplayManager.DisplayListener old;
        synchronized (mirrorListenerLock) {
            old = mirrorDisplayListener;
            mirrorDisplayListener = null;
        }
        if (old != null && services.displayManager != null) {
            try {
                services.displayManager.unregisterDisplayListener(old);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void syncMirrorOrientationIfChanged(OpalCastCompositorBridge.ActiveRemoteSession s) {
        synchronized (s.lock) {
            if (s.closed || !s.isMirror() || s.sink == null || s.vd == null) return;
            int[] d0 = services.getPrimaryDisplayState();
            int d0W = (d0.length >= 4 && d0[0] > 0) ? d0[0] : s.mirrorSourceWidth;
            int d0H = (d0.length >= 4 && d0[1] > 0) ? d0[1] : s.mirrorSourceHeight;
            int d0Dpi = (d0.length >= 4 && d0[2] > 0) ? d0[2] : s.mirrorSourceDpi;
            int d0Rot = (d0.length >= 4) ? DisplayGeometryUtil.normalizeRotation(d0[3]) : s.mirrorRotation;
            if (d0W == s.mirrorSourceWidth && d0H == s.mirrorSourceHeight && d0Rot == s.mirrorRotation) {
                return;
            }
            s.mirrorSourceWidth = d0W;
            s.mirrorSourceHeight = d0H;
            s.mirrorSourceDpi = d0Dpi;
            s.mirrorRotation = d0Rot;
            int[] mSpec = DisplayGeometryUtil.computeMirrorStreamSpec(
                    d0W, d0H, d0Dpi, Math.max(s.dexWidth, s.dexHeight), 8);
            if (mSpec[0] == s.width && mSpec[1] == s.height) {
                pushDeviceState.accept(s);
                return;
            }
            int mirrorBitrate = OpalCastCompositorBridge.resolveSessionBitrateKbps(
                    0, mSpec[0], mSpec[1], s.fps, s.codecPref, s.sinkType);
            boolean ok = s.sink.reconfigureAndSwap(
                    mSpec[0], mSpec[1], mSpec[2], s.fps, mirrorBitrate,
                    s.codecPref, OpalCastProtocol.DISPLAY_MODE_MIRROR, d0Rot,
                    (newSurface, alignedW, alignedH, alignedDpi) -> {
                        s.width = alignedW;
                        s.height = alignedH;
                        s.dpi = alignedDpi;
                        s.bitrateKbps = mirrorBitrate;
                        try {
                            s.vd.resize(alignedW, alignedH, alignedDpi);
                            s.vd.setSurface(newSurface);
                        } catch (RuntimeException e) {
                            System.err.println("OPALDESK_MIRROR_ROTATE_WARN: " + e.getMessage());
                        }
                    });
            if (!ok) return;
            server.updateSessionTargetFps(s.sessionId, s.fps);
            pushDeviceState.accept(s);
            notifyRemoteStateChanged.run();
            System.out.println("OPALDESK_REMOTE_MIRROR_ROTATED sid=" + s.sessionId
                    + " d0=" + d0W + "x" + d0H + " rot=" + d0Rot
                    + " stream=" + s.width + "x" + s.height + "@" + s.dpi);
        }
    }

    void reconfigureSession(OpalCastCompositorBridge.ActiveRemoteSession s, OpalCastProtocol.ResizeConfig cfg) {
        synchronized (s.lock) {
            if (s.closed || s.sink == null || s.vd == null) return;
            float nextFps = (cfg.fpsX100 > 0) ? DisplayGeometryUtil.clampFps(cfg.fpsX100 / 100f, s.fps) : s.fps;

            if (!s.standalone) {
                if (cfg.width > 0 && cfg.height > 0) {
                    int[] alignedDex = DisplayGeometryUtil.alignForEncoder(cfg.width, cfg.height, 8);
                    s.dexWidth = alignedDex[0];
                    s.dexHeight = alignedDex[1];
                    if (cfg.dpi > 0) {
                        s.dexDpi = DisplayGeometryUtil.clampDpi(cfg.dpi, s.dexWidth, s.dexHeight);
                    }
                } else if (cfg.dpi > 0) {
                    s.dexDpi = DisplayGeometryUtil.clampDpi(cfg.dpi, s.dexWidth, s.dexHeight);
                }
                if (cfg.bitrateKbps > 0) {
                    s.dexRequestedBitrateKbps = cfg.bitrateKbps;
                }
                s.fps = nextFps;

                if ((cfg.displayMode == OpalCastProtocol.DISPLAY_MODE_DEX
                        || cfg.displayMode == OpalCastProtocol.DISPLAY_MODE_MIRROR)
                        && cfg.displayMode != s.displayMode) {
                    if (cfg.displayMode == OpalCastProtocol.DISPLAY_MODE_MIRROR) {
                        switchToMirrorModeLocked(s);
                    } else {
                        switchToDexModeLocked(s);
                    }
                    return;
                }
            }

            int nextW, nextH, nextDpi, nextBitrate;
            int nextMode = s.displayMode;
            int nextRot = 0;

            if (s.standalone) {
                int reqW = cfg.width > 0 ? cfg.width : s.width;
                int reqH = cfg.height > 0 ? cfg.height : s.height;
                int[] spec = sessionHandler.resolveStandaloneStreamSpec(s.appPackage, reqW, reqH, cfg.dpi);
                nextW = spec[0];
                nextH = spec[1];
                nextDpi = spec[2];
                nextBitrate = OpalCastCompositorBridge.resolveSessionBitrateKbps(
                        cfg.bitrateKbps, nextW, nextH, nextFps, s.codecPref, s.sinkType);
                nextMode = OpalCastProtocol.DISPLAY_MODE_DEX;
            } else if (s.isMirror()) {
                int[] d0 = services.getPrimaryDisplayState();
                sessionHandler.populatePrimaryMirrorStateLocked(s, d0);
                int[] mSpec = DisplayGeometryUtil.computeMirrorStreamSpec(
                        s.mirrorSourceWidth, s.mirrorSourceHeight, s.mirrorSourceDpi,
                        Math.max(s.dexWidth, s.dexHeight), 8);
                nextW = mSpec[0];
                nextH = mSpec[1];
                nextDpi = mSpec[2];
                nextBitrate = OpalCastCompositorBridge.resolveSessionBitrateKbps(
                        cfg.bitrateKbps, nextW, nextH, nextFps, s.codecPref, s.sinkType);
                nextMode = OpalCastProtocol.DISPLAY_MODE_MIRROR;
                nextRot = s.mirrorRotation;
            } else {
                nextW = s.dexWidth;
                nextH = s.dexHeight;
                nextDpi = s.dexDpi;
                nextBitrate = OpalCastCompositorBridge.resolveSessionBitrateKbps(
                        cfg.bitrateKbps, nextW, nextH, nextFps, s.codecPref, s.sinkType);
                nextMode = OpalCastProtocol.DISPLAY_MODE_DEX;
            }

            DisplayGeometryUtil.ReconfigureAction action = DisplayGeometryUtil.planReconfigureAction(
                    s.sink.getWidth(), s.sink.getHeight(), s.sink.getDpi(),
                    s.sink.getFps(), s.bitrateKbps, s.sink.getDisplayMode(), s.sink.getRotation(),
                    nextW, nextH, nextDpi, nextFps, nextBitrate, nextMode, nextRot);
            if (action == DisplayGeometryUtil.ReconfigureAction.NO_OP) return;
            if (action == DisplayGeometryUtil.ReconfigureAction.BITRATE_ONLY) {
                s.sink.updateBitrateKbps(nextBitrate);
                s.bitrateKbps = nextBitrate;
                server.updateSessionTargetBitrateKbps(s.sessionId, nextBitrate);
                pushDeviceState.accept(s);
                notifyRemoteStateChanged.run();
                return;
            }
            if (action == DisplayGeometryUtil.ReconfigureAction.DPI_ONLY) {
                try {
                    s.vd.resize(s.width, s.height, nextDpi);
                    s.dpi = nextDpi;
                    if (!s.standalone && !s.isMirror()) {
                        s.dexDpi = nextDpi;
                    }
                    s.sink.updateDpiOnly(nextDpi);
                    if (nextBitrate != s.bitrateKbps) {
                        s.sink.updateBitrateKbps(nextBitrate);
                        s.bitrateKbps = nextBitrate;
                        server.updateSessionTargetBitrateKbps(s.sessionId, nextBitrate);
                    }
                    pushDeviceState.accept(s);
                    notifyRemoteStateChanged.run();
                    return;
                } catch (RuntimeException e) {
                    System.err.println("OPALDESK_REMOTE_DPI_FASTPATH_WARN: " + e.getMessage());
                }
            }

            s.sink.setRepeatPreviousFrameAfterUs(
                    RemoteStreamSink.resolveRepeatPreviousFrameAfterUs(s.clientPlatform, nextFps));
            boolean ok = s.sink.reconfigureAndSwap(
                    nextW, nextH, nextDpi, nextFps, nextBitrate,
                    s.codecPref, nextMode, nextRot,
                    (newSurface, alignedW, alignedH, alignedDpi) -> {
                        s.width = alignedW;
                        s.height = alignedH;
                        s.dpi = alignedDpi;
                        s.fps = nextFps;
                        s.bitrateKbps = nextBitrate;
                        if (!s.standalone && !s.isMirror()) {
                            s.dexWidth = alignedW;
                            s.dexHeight = alignedH;
                            s.dexDpi = alignedDpi;
                        }
                        try {
                            s.vd.resize(alignedW, alignedH, alignedDpi);
                            s.vd.setSurface(newSurface);
                        } catch (RuntimeException e) {
                            System.err.println("OPALDESK_REMOTE_RESIZE_WARN: " + e.getMessage());
                        }
                    });
            if (!ok) return;
            server.updateSessionTargetFps(s.sessionId, s.fps);
            server.updateSessionTargetBitrateKbps(s.sessionId, s.bitrateKbps);
            pushDeviceState.accept(s);
            notifyRemoteStateChanged.run();
        }
    }
}
