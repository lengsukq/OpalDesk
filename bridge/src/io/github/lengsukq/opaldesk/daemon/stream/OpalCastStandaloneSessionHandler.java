package io.github.lengsukq.opaldesk.daemon.stream;

import android.hardware.display.VirtualDisplay;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.input.ExternalMouseRouter;
import io.github.lengsukq.opaldesk.daemon.input.MouseRoutingPolicy;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidDisplayDriver;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.UserProfileAppPolicy;
import io.github.lengsukq.opaldesk.daemon.platform.VirtualDisplayFactory;
import io.github.lengsukq.opaldesk.daemon.sink.RemoteStreamSink;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Handles OpalCast session setup and teardown across desktop, mirror, standalone-app,
 * companion, and app-catalog session roles, including VirtualDisplay creation and cleanup.
 */
final class OpalCastStandaloneSessionHandler {
    interface SessionCallbacks {
        void onDesktopSessionAssigned(int sessionId);
        void onRegisterMirrorListener();
        void onUnregisterMirrorListener();
        void onRefreshHostTopApp();
        void onPushDeviceState(OpalCastCompositorBridge.ActiveRemoteSession session);
        void onPushCompanionSnapshots(int sessionId, boolean requestSyncIfEmpty);
        void onCompositorWindowsChanged();
        void onRemoteStateChanged();
    }

    private final AndroidServices services;
    private final DaemonConfig config;
    private final CompositorEngine compositor;
    private final AndroidDisplayDriver displayDriver;
    private final PerDisplayInputRouter inputRouter;
    private final ExternalMouseRouter mouseRouter;
    private final RemoteStreamServer server;
    private final RemoteMediaDevicesCoordinator mediaDevices;
    private final Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions;
    private final IntSupplier desktopSessionIdSupplier;
    private final BooleanSupplier localHostTextFieldFocusedSupplier;
    private final BooleanSupplier bridgeImeActiveSupplier;
    private final SessionCallbacks callbacks;

    OpalCastStandaloneSessionHandler(
            AndroidServices services,
            DaemonConfig config,
            CompositorEngine compositor,
            AndroidDisplayDriver displayDriver,
            PerDisplayInputRouter inputRouter,
            ExternalMouseRouter mouseRouter,
            RemoteStreamServer server,
            RemoteMediaDevicesCoordinator mediaDevices,
            Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions,
            IntSupplier desktopSessionIdSupplier,
            BooleanSupplier localHostTextFieldFocusedSupplier,
            BooleanSupplier bridgeImeActiveSupplier,
            SessionCallbacks callbacks) {
        this.services = services;
        this.config = config;
        this.compositor = compositor;
        this.displayDriver = displayDriver;
        this.inputRouter = inputRouter;
        this.mouseRouter = mouseRouter;
        this.server = server;
        this.mediaDevices = mediaDevices;
        this.sessions = sessions;
        this.desktopSessionIdSupplier = desktopSessionIdSupplier;
        this.localHostTextFieldFocusedSupplier = localHostTextFieldFocusedSupplier;
        this.bridgeImeActiveSupplier = bridgeImeActiveSupplier;
        this.callbacks = callbacks;
    }

    static int[] desktopVdCandidates() {
        int dcor = BridgeProtocol.VD_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        return new int[]{
                BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | dcor,
                BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS,
                BridgeProtocol.VD_FLAGS_BASE_PUBLIC | dcor,
                BridgeProtocol.VD_FLAGS_BASE_PUBLIC
        };
    }

    static int[] standaloneVdCandidates() {
        int dcor = BridgeProtocol.VD_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        return new int[]{
                BridgeProtocol.VD_FLAGS_TRUSTED_IME | dcor,
                BridgeProtocol.VD_FLAGS_TRUSTED_IME,
                BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | dcor,
                BridgeProtocol.VD_FLAGS_BASE_PUBLIC | dcor,
                BridgeProtocol.VD_FLAGS_BASE_PUBLIC
        };
    }

    void releaseSessionVirtualDisplayLocked(OpalCastCompositorBridge.ActiveRemoteSession s, boolean removeTasks) {
        if (s.vd == null) return;
        int oldId = s.displayId;
        if (removeTasks && oldId > 0) {
            List<Integer> allTids = displayDriver.findAllTaskIdsOnDisplay(oldId);
            if (allTids != null && !allTids.isEmpty()) {
                for (Integer tid : allTids) {
                    if (tid != null && tid > 0) {
                        displayDriver.removeTask(tid);
                    }
                }
            } else {
                String expectedPkg = s.standalone ? s.appPackage : BridgeBuildConfig.HELPER_PACKAGE;
                int topTid = displayDriver.findTopTaskIdOnDisplay(oldId, expectedPkg);
                if (topTid > 0) {
                    displayDriver.removeTask(topTid);
                }
            }
        }
        try { s.vd.setSurface(null); } catch (RuntimeException ignored) {}
        try { s.vd.release(); } catch (RuntimeException ignored) {}
        s.vd = null;
        s.displayId = -1;
        s.vdUniqueId = "";
        if (oldId > 0) {
            inputRouter.releaseDisplayResources(oldId);
        }
    }

    void bindVirtualDisplayMetadataLocked(OpalCastCompositorBridge.ActiveRemoteSession s, VirtualDisplay vd) {
        Display disp = vd.getDisplay();
        s.vd = vd;
        s.displayId = disp.getDisplayId();
        String uid = VirtualDisplayFactory.getUniqueId(disp);
        if (uid == null || uid.isEmpty()) {
            uid = services.getDisplayUniqueId(s.displayId);
        }
        s.vdUniqueId = uid != null ? uid : "";
    }

    void populatePrimaryMirrorStateLocked(OpalCastCompositorBridge.ActiveRemoteSession s, int[] d0State) {
        int d0W = (d0State != null && d0State.length >= 4 && d0State[0] > 0) ? d0State[0] : 1080;
        int d0H = (d0State != null && d0State.length >= 4 && d0State[1] > 0) ? d0State[1] : 1920;
        int d0Dpi = (d0State != null && d0State.length >= 4 && d0State[2] > 0) ? d0State[2] : 420;
        int d0Rot = (d0State != null && d0State.length >= 4) ? DisplayGeometryUtil.normalizeRotation(d0State[3]) : 0;
        s.mirrorSourceWidth = d0W;
        s.mirrorSourceHeight = d0H;
        s.mirrorSourceDpi = d0Dpi;
        s.mirrorRotation = d0Rot;
        String d0Uid = services.getDisplayUniqueId(Display.DEFAULT_DISPLAY);
        s.mirrorTargetUniqueId = (d0Uid != null) ? d0Uid : "";
    }

    void launchRemoteDesktopHost(int displayId) {
        boolean ok = services.launchExternalDesktopHost(displayId, false, false);
        System.out.println("OPALDESK_REMOTE_HOST_LAUNCH displayId=" + displayId + " ok=" + ok);
    }

    int[] resolveStandaloneStreamSpec(String appPkg, int reqW, int reqH, int requestedDpi) {
        int customDpi = config.resolveEffectiveCustomDpi(appPkg);
        if (customDpi > 0) {
            int[] aligned = DisplayGeometryUtil.alignForEncoder(reqW, reqH, 8);
            return new int[]{aligned[0], aligned[1], customDpi};
        }
        int[] hostMetrics = services.getPrimaryDisplayMetrics();
        int hostW = (hostMetrics != null && hostMetrics.length >= 3 && hostMetrics[0] > 0)
                ? hostMetrics[0] : config.getCanvasWidth();
        int hostH = (hostMetrics != null && hostMetrics.length >= 3 && hostMetrics[1] > 0)
                ? hostMetrics[1] : config.getCanvasHeight();
        int hostDpi = (hostMetrics != null && hostMetrics.length >= 3 && hostMetrics[2] > 0)
                ? hostMetrics[2] : config.getCanvasDpi();
        return DisplayGeometryUtil.resolveStandaloneStreamSpec(
                reqW, reqH, requestedDpi, hostW, hostH, hostDpi, config.getWindowDpiScalePct());
    }

    void setupSession(int sessionId, int sinkType, OpalCastProtocol.HelloMsg hello) {
        if (hello != null && hello.isAppCatalog()) {
            System.out.println("OPALDESK_REMOTE_CATALOG_SESSION_READY sid=" + sessionId);
            return;
        }
        if (hello != null && hello.isCompanion()) {
            setupCompanionSession(sessionId, sinkType, hello);
            return;
        }
        boolean standalone = hello.isStandaloneApp();
        OpalCastCompositorBridge.ActiveRemoteSession s = new OpalCastCompositorBridge.ActiveRemoteSession(
                sessionId, sinkType, standalone, false,
                hello.appPackage, hello.appActivity, hello.appUserId, hello.clientPlatform);
        synchronized (s.lock) {
            configureInitialGeometryLocked(s, standalone, hello);
            RemoteStreamSink sink = createAndStartSinkLocked(s, sessionId, sinkType);
            if (sink == null) return;

            VirtualDisplay vd = createSessionVirtualDisplayLocked(s, sessionId, standalone, sink);
            if (vd == null || vd.getDisplay() == null) {
                sink.stop();
                server.disconnectSession(sessionId, "virtual_display_failed");
                return;
            }
            bindVirtualDisplayMetadataLocked(s, vd);
            if (!s.isMirror()) {
                int remoteImePolicy = MouseRoutingPolicy.resolveRemoteOuterDisplayImePolicy(
                        config.getDexImeDisplayMode(), config.isMuteHostImeOnCast(),
                        s.vdTrusted, bridgeImeActiveSupplier.getAsBoolean());
                services.configureVirtualDisplayWindowing(s.displayId, remoteImePolicy);
            }

            s.clipboard = createSessionClipboard(sessionId, s, standalone);
            s.fileReceiver = new FileTransferReceiver((transferId, status, pathOrError) ->
                    server.sendFileTransferAck(sessionId, transferId, status, pathOrError));
            s.audioRequested = hello.audioEnabled;
            sessions.put(sessionId, s);

            if (!standalone) {
                callbacks.onDesktopSessionAssigned(sessionId);
                if (s.isMirror()) {
                    if (mouseRouter != null) mouseRouter.releaseToLocal();
                    callbacks.onRegisterMirrorListener();
                } else {
                    launchRemoteDesktopHost(s.displayId);
                    if (mouseRouter != null) {
                        mouseRouter.setMouseTarget(config.getMouseTargetMode(), s.displayId);
                    }
                }
            } else if (!s.appPackage.isEmpty()) {
                config.recordAppLaunch(s.appPackage);
                displayDriver.launchOrMoveFullscreenApp(
                        s.appPackage, s.appActivity, s.appUserId, s.displayId, -1);
                callbacks.onCompositorWindowsChanged();
            }
            mediaDevices.reconcileAudioOwner();
            s.clipboard.onSessionStarted();
            if (!standalone) {
                callbacks.onRefreshHostTopApp();
            }
            callbacks.onPushDeviceState(s);
            if (!standalone && hello.companionEvents) {
                callbacks.onPushCompanionSnapshots(sessionId, true);
            }
            callbacks.onRemoteStateChanged();
            System.out.println("OPALDESK_REMOTE_SESSION_READY sid=" + sessionId
                    + " role=" + (standalone ? "standalone" : (s.isMirror() ? "mirror" : "desktop"))
                    + " displayId=" + s.displayId + " " + s.width + "x" + s.height + "@" + s.fps
                    + " vdUid=" + s.vdUniqueId);
        }
    }

    private void setupCompanionSession(int sessionId, int sinkType, OpalCastProtocol.HelloMsg hello) {
        OpalCastCompositorBridge.ActiveRemoteSession comp = new OpalCastCompositorBridge.ActiveRemoteSession(
                sessionId, sinkType, false, true, "", "", 0, hello.clientPlatform);
        synchronized (comp.lock) {
            comp.width = 0;
            comp.height = 0;
            comp.dpi = 0;
            comp.fps = 0f;
            comp.bitrateKbps = 0;
            comp.audioRequested = false;
            comp.clipboard = new ClipboardSyncService(new ClipboardSyncService.ClipboardHost() {
                @Override public void sendClipboardPush(long seq, String text) {
                    server.sendClipboardPush(sessionId, seq, text);
                }
                @Override public void sendClipboardImagePush(long seq, byte[] pngData) {
                    server.sendClipboardImagePush(sessionId, seq, pngData);
                }
                @Override public void triggerPasteKey() {
                }
            }, null, true);
            comp.fileReceiver = new FileTransferReceiver((transferId, status, pathOrError) ->
                    server.sendFileTransferAck(sessionId, transferId, status, pathOrError));
            sessions.put(sessionId, comp);
            comp.clipboard.onSessionStarted();
            callbacks.onRefreshHostTopApp();
            callbacks.onPushDeviceState(comp);
            callbacks.onPushCompanionSnapshots(sessionId, true);
            System.out.println("OPALDESK_REMOTE_COMPANION_SESSION_READY sid=" + sessionId
                    + " sinkType=" + sinkType);
        }
    }

    private void configureInitialGeometryLocked(
            OpalCastCompositorBridge.ActiveRemoteSession s,
            boolean standalone,
            OpalCastProtocol.HelloMsg hello) {
        if (standalone) {
            int[] spec = resolveStandaloneStreamSpec(s.appPackage, hello.width, hello.height, hello.dpi);
            s.width = spec[0];
            s.height = spec[1];
            s.dpi = spec[2];
            s.displayMode = OpalCastProtocol.DISPLAY_MODE_DEX;
        } else {
            int[] aligned = DisplayGeometryUtil.alignForEncoder(hello.width, hello.height, 8);
            s.dexWidth = aligned[0];
            s.dexHeight = aligned[1];
            s.dexDpi = DisplayGeometryUtil.clampDpi(hello.dpi, s.dexWidth, s.dexHeight);
            s.dexRequestedBitrateKbps = hello.bitrateKbps;
            s.initialWidth = s.dexWidth;
            s.initialHeight = s.dexHeight;
            s.initialDpi = s.dexDpi;
            boolean wantMirror = (hello.displayMode == OpalCastProtocol.DISPLAY_MODE_MIRROR);
            if (wantMirror) {
                int[] d0 = services.getPrimaryDisplayState();
                populatePrimaryMirrorStateLocked(s, d0);
                int[] mSpec = DisplayGeometryUtil.computeMirrorStreamSpec(
                        s.mirrorSourceWidth, s.mirrorSourceHeight, s.mirrorSourceDpi,
                        Math.max(s.dexWidth, s.dexHeight), 8);
                s.width = mSpec[0];
                s.height = mSpec[1];
                s.dpi = mSpec[2];
                s.displayMode = OpalCastProtocol.DISPLAY_MODE_MIRROR;
            } else {
                s.width = s.dexWidth;
                s.height = s.dexHeight;
                s.dpi = s.dexDpi;
                s.displayMode = OpalCastProtocol.DISPLAY_MODE_DEX;
            }
        }
        float nativeFps = DisplayGeometryUtil.clampFps(hello.fps > 0 ? hello.fps : 60f, 60f);
        s.requestedNativeFps = nativeFps;
        int cfgFps = config.getTargetFps();
        s.fps = DisplayGeometryUtil.clampFps(cfgFps > 0 ? cfgFps : nativeFps, nativeFps);
        s.codecPref = "h264".equalsIgnoreCase(hello.preferredCodec)
                ? RemoteStreamSink.CODEC_PREF_H264 : RemoteStreamSink.CODEC_PREF_AUTO_HEVC;
        int reqBitrate = s.isMirror() ? 0 : hello.bitrateKbps;
        s.bitrateKbps = OpalCastCompositorBridge.resolveSessionBitrateKbps(
                reqBitrate, s.width, s.height, s.fps, s.codecPref, s.sinkType);
    }

    private RemoteStreamSink createAndStartSinkLocked(
            OpalCastCompositorBridge.ActiveRemoteSession s, int sessionId, int sinkType) {
        RemoteStreamSink sink = new RemoteStreamSink(sinkType, new RemoteStreamSink.FrameListener() {
            @Override public void onVideoConfig(OpalCastProtocol.VideoConfig cfg, long tsUs) {
                server.sendVideoConfig(sessionId, cfg, tsUs);
            }
            @Override public void onVideoFrame(byte[] annexB, int flags, long tsUs) {
                server.sendVideoFrame(sessionId, annexB, flags, tsUs);
            }
            @Override public void onVideoFrame(
                    byte[] annexB, int length, int flags, long tsUs, Consumer<byte[]> onRelease) {
                server.sendVideoFrame(sessionId, annexB, length, flags, tsUs, onRelease);
            }
            @Override public void onEncoderError(String reason) {
                System.err.println("OPALDESK_REMOTE_ENC_ERR sid=" + sessionId + ": " + reason);
            }
        });
        sink.setMaxBitrateKbps(OpalCastCompositorBridge.resolveMaxBitrateKbpsForSink(sinkType));
        sink.setRepeatPreviousFrameAfterUs(
                RemoteStreamSink.resolveRepeatPreviousFrameAfterUs(s.clientPlatform, s.fps));
        int startRot = s.isMirror() ? s.mirrorRotation : 0;
        Surface codecSurf = sink.startOrReconfigure(
                s.width, s.height, s.dpi, s.fps, s.bitrateKbps,
                s.codecPref, s.displayMode, startRot);
        if (codecSurf == null) {
            server.disconnectSession(sessionId, "encoder_start_failed");
            return null;
        }
        s.sink = sink;
        s.width = sink.getWidth();
        s.height = sink.getHeight();
        s.dpi = sink.getDpi();
        s.fps = sink.getFps();
        server.updateSessionTargetFps(sessionId, s.fps);
        if (s.standalone) {
            s.initialWidth = s.width;
            s.initialHeight = s.height;
            s.initialDpi = s.dpi;
        } else if (!s.isMirror()) {
            s.dexWidth = s.width;
            s.dexHeight = s.height;
            s.dexDpi = s.dpi;
            s.initialWidth = s.width;
            s.initialHeight = s.height;
            s.initialDpi = s.dpi;
        }
        return sink;
    }

    private VirtualDisplay createSessionVirtualDisplayLocked(
            OpalCastCompositorBridge.ActiveRemoteSession s,
            int sessionId,
            boolean standalone,
            RemoteStreamSink sink) {
        Surface codecSurf = sink.getInputSurface();
        VirtualDisplay vd = null;
        if (s.isMirror()) {
            vd = VirtualDisplayFactory.createMirrorDisplay(
                    services.displayManager, "OpalDesk-Mirror",
                    s.width, s.height, s.dpi, Display.DEFAULT_DISPLAY, codecSurf);
            if (vd == null || vd.getDisplay() == null) {
                System.err.println("OPALDESK_REMOTE_MIRROR_START_FALLBACK_DEX sid=" + sessionId);
                s.displayMode = OpalCastProtocol.DISPLAY_MODE_DEX;
                s.width = s.dexWidth;
                s.height = s.dexHeight;
                s.dpi = s.dexDpi;
                s.bitrateKbps = OpalCastCompositorBridge.resolveSessionBitrateKbps(
                        s.dexRequestedBitrateKbps, s.width, s.height, s.fps, s.codecPref, s.sinkType);
                codecSurf = sink.startOrReconfigure(
                        s.width, s.height, s.dpi, s.fps, s.bitrateKbps,
                        s.codecPref, OpalCastProtocol.DISPLAY_MODE_DEX, 0);
                if (codecSurf == null) {
                    sink.stop();
                    server.disconnectSession(sessionId, "encoder_start_failed");
                    return null;
                }
                s.width = sink.getWidth();
                s.height = sink.getHeight();
                s.dpi = sink.getDpi();
                s.dexWidth = s.width;
                s.dexHeight = s.height;
                s.dexDpi = s.dpi;
            }
        }
        if (!s.isMirror()) {
            String vdName = standalone
                    ? ("OpalDesk-App-" + UserProfileAppPolicy.formatAppKey(s.appPackage, s.appUserId))
                    : "OpalDesk-Remote";
            int[] candidates = standalone ? standaloneVdCandidates() : desktopVdCandidates();
            int[] chosenFlags = new int[1];
            if (standalone) {
                vd = mediaDevices.tryCreateStandaloneCameraVirtualDisplay(
                        vdName, s.width, s.height, s.dpi, candidates, codecSurf, chosenFlags);
            }
            if (vd == null) {
                vd = VirtualDisplayFactory.createWithFlagFallback(
                        services.displayManager, vdName, s.width, s.height, s.dpi, candidates, 0f, codecSurf, chosenFlags);
            }
            s.vdTrusted = (chosenFlags[0] & BridgeProtocol.VD_FLAG_TRUSTED) != 0;
        }
        return vd;
    }

    private ClipboardSyncService createSessionClipboard(
            int sessionId, OpalCastCompositorBridge.ActiveRemoteSession s, boolean standalone) {
        return new ClipboardSyncService(new ClipboardSyncService.ClipboardHost() {
            @Override public void sendClipboardPush(long seq, String text) {
                server.sendClipboardPush(sessionId, seq, text);
            }
            @Override public void sendClipboardImagePush(long seq, byte[] pngData) {
                server.sendClipboardImagePush(sessionId, seq, pngData);
            }
            @Override public void triggerPasteKey() {
                int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                if (!s.standalone && !s.isMirror() && !localHostTextFieldFocusedSupplier.getAsBoolean()) {
                    WindowSession focused = compositor.getFocusedLiveWindow();
                    if (focused != null) {
                        inputRouter.injectWindowKey(focused, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl);
                        inputRouter.injectWindowKey(focused, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl);
                        return;
                    }
                }
                int targetDisplayId = s.inputTargetDisplayId();
                if (targetDisplayId < 0) return;
                inputRouter.injectRawKeyToDisplay(
                        targetDisplayId, s.inputTargetUniqueId(), s.inputTargetWidth(), s.inputTargetHeight(),
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl);
                inputRouter.injectRawKeyToDisplay(
                        targetDisplayId, s.inputTargetUniqueId(), s.inputTargetWidth(), s.inputTargetHeight(),
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl);
            }
        }, null, !standalone);
    }

    void closeSessionResources(OpalCastCompositorBridge.ActiveRemoteSession s) {
        synchronized (s.lock) {
            if (s.closed) return;
            s.closed = true;
            int deskSid = desktopSessionIdSupplier.getAsInt();
            if (!s.standalone && !s.companion && (deskSid == s.sessionId || deskSid < 0)) {
                callbacks.onUnregisterMirrorListener();
            }
            if (s.clipboard != null) {
                try { s.clipboard.onSessionEnded(); } catch (RuntimeException ignored) {}
                s.clipboard = null;
            }
            if (s.fileReceiver != null) {
                try { s.fileReceiver.abortAllTransfers(); } catch (RuntimeException ignored) {}
                s.fileReceiver = null;
            }
            releaseSessionVirtualDisplayLocked(s, !s.isMirror());
            if (s.sink != null) {
                try { s.sink.stop(); } catch (RuntimeException ignored) {}
                s.sink = null;
            }
        }
    }
}
