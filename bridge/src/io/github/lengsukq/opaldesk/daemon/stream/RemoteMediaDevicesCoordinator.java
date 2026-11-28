package io.github.lengsukq.opaldesk.daemon.stream;

import android.hardware.display.VirtualDisplay;
import android.os.SystemClock;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.audio.AudioRoutingPolicy;
import io.github.lengsukq.opaldesk.daemon.audio.AudioStreamCapturer;
import io.github.lengsukq.opaldesk.daemon.audio.RemoteMicInjector;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidDisplayDriver;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.UserProfileAppPolicy;
import io.github.lengsukq.opaldesk.daemon.video.RootCameraBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.IntSupplier;

/**
 * Package-private coordinator extracted from {@link OpalCastCompositorBridge} managing remote
 * system audio capture/routing, remote microphone PCM injection, and Root virtual camera mapping
 * (including AOSP VirtualDevice display migration).
 */
final class RemoteMediaDevicesCoordinator {
    private final AndroidServices services;
    private final DaemonConfig config;
    private final CompositorEngine compositor;
    private final AndroidDisplayDriver displayDriver;
    private final RemoteStreamServer server;
    private final Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions;
    private final IntSupplier desktopSessionIdSupplier;
    private final ExecutorService worker;

    private final Object audioLock = new Object();
    private volatile AudioStreamCapturer audioCapturer;
    private volatile int audioOwnerSessionId = -1;
    private volatile OpalCastProtocol.AudioConfig lastAudioConfig;

    private final Object micLock = new Object();
    private RemoteMicInjector micInjector;
    private volatile int micOwnerSessionId = -1;

    private final Object cameraLock = new Object();
    private RootCameraBridge cameraBridge;
    private volatile int cameraOwnerSessionId = -1;

    RemoteMediaDevicesCoordinator(
            AndroidServices services,
            DaemonConfig config,
            CompositorEngine compositor,
            AndroidDisplayDriver displayDriver,
            RemoteStreamServer server,
            Map<Integer, OpalCastCompositorBridge.ActiveRemoteSession> sessions,
            IntSupplier desktopSessionIdSupplier,
            ExecutorService worker) {
        this.services = services;
        this.config = config;
        this.compositor = compositor;
        this.displayDriver = displayDriver;
        this.server = server;
        this.sessions = sessions;
        this.desktopSessionIdSupplier = desktopSessionIdSupplier;
        this.worker = worker;
    }

    void shutdown() {
        stopAudio();
        stopRemoteMic(false);
        stopRemoteCamera(false, true);
    }

    void onSessionDisconnected(int sessionId) {
        if (sessionId == micOwnerSessionId) {
            stopRemoteMic(false);
        }
        if (sessionId == cameraOwnerSessionId) {
            stopRemoteCamera(false);
        }
    }

    boolean isAudioActive() {
        AudioStreamCapturer cap = audioCapturer;
        return cap != null && cap.isActive();
    }

    VirtualDisplay tryCreateStandaloneCameraVirtualDisplay(
            String vdName,
            int width,
            int height,
            int dpi,
            int[] candidates,
            Surface codecSurf,
            int[] chosenFlagsOut) {
        RootCameraBridge cam = this.cameraBridge;
        if (cam == null || !cam.hasAospVirtualCamera()) {
            return null;
        }
        for (int candFlags : candidates) {
            VirtualDisplay customVd = cam.createVirtualDisplay(
                    vdName, width, height, dpi, candFlags, 0f, codecSurf);
            if (customVd != null && customVd.getDisplay() != null) {
                if (chosenFlagsOut != null && chosenFlagsOut.length > 0) {
                    chosenFlagsOut[0] = candFlags;
                }
                return customVd;
            }
        }
        return null;
    }

    void reconcileAudioOwner() {
        synchronized (audioLock) {
            List<AudioRoutingPolicy.AudioSessionCandidate> candidates = new ArrayList<>();
            for (OpalCastCompositorBridge.ActiveRemoteSession session : sessions.values()) {
                if (session != null && !session.closed && !session.companion) {
                    candidates.add(new AudioRoutingPolicy.AudioSessionCandidate(
                            session.sessionId, session.standalone, session.audioRequested));
                }
            }
            int prevOwner = this.audioOwnerSessionId;
            int nextOwner = AudioRoutingPolicy.selectAudioOwnerSessionId(
                    prevOwner, desktopSessionIdSupplier.getAsInt(), candidates);
            if (nextOwner <= 0) {
                stopAudioLocked();
                return;
            }

            boolean splitByApp = !isSessionInMirrorMode(nextOwner)
                    && (config.getAudioRouteMode() == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP);
            List<Integer> targetUids = splitByApp ? collectDexAppUids() : Collections.emptyList();

            AudioStreamCapturer cap = this.audioCapturer;
            if (cap == null || !cap.isActive()) {
                startAudioForOwnerLocked(nextOwner, splitByApp, targetUids);
                return;
            }

            boolean ownerChanged = (prevOwner != nextOwner);
            this.audioOwnerSessionId = nextOwner;
            cap.setSplitMode(splitByApp, targetUids);
            if (ownerChanged) {
                OpalCastProtocol.AudioConfig cached = this.lastAudioConfig;
                if (cached != null) {
                    server.sendAudioConfig(nextOwner, cached, SystemClock.elapsedRealtimeNanos() / 1000L);
                }
                System.out.println("OPALDESK_REMOTE_AUDIO_OWNER sid=" + nextOwner + " split=" + splitByApp);
            }
        }
    }

    private void startAudioForOwnerLocked(int ownerSessionId, boolean splitByApp, List<Integer> initialUids) {
        stopAudioLocked();
        this.audioOwnerSessionId = ownerSessionId;
        final AudioStreamCapturer[] holder = new AudioStreamCapturer[1];
        AudioStreamCapturer cap = new AudioStreamCapturer(services.context, new AudioStreamCapturer.AudioListener() {
            @Override
            public void onAudioConfig(OpalCastProtocol.AudioConfig cfg, long tsUs) {
                int targetSid;
                synchronized (audioLock) {
                    if (audioCapturer != holder[0]) return;
                    lastAudioConfig = cfg;
                    targetSid = audioOwnerSessionId;
                }
                if (targetSid > 0) {
                    server.sendAudioConfig(targetSid, cfg, tsUs);
                }
            }

            @Override
            public void onAudioFrame(byte[] aacAu, long tsUs) {
                int targetSid = audioOwnerSessionId;
                if (targetSid > 0 && audioCapturer == holder[0]) {
                    server.sendAudioFrame(targetSid, aacAu, tsUs);
                }
            }

            @Override
            public void onAudioStateChanged(boolean active) {
                if (!active) {
                    synchronized (audioLock) {
                        if (audioCapturer == holder[0]) {
                            audioCapturer = null;
                            audioOwnerSessionId = -1;
                            lastAudioConfig = null;
                        }
                    }
                }
                System.out.println("OPALDESK_REMOTE_AUDIO_STATE active=" + active + " owner=" + audioOwnerSessionId);
            }
        });
        holder[0] = cap;
        this.audioCapturer = cap;
        if (!cap.start(splitByApp, initialUids)) {
            if (this.audioCapturer == cap) {
                this.audioCapturer = null;
                this.audioOwnerSessionId = -1;
                this.lastAudioConfig = null;
            }
        } else {
            System.out.println("OPALDESK_REMOTE_AUDIO_OWNER sid=" + ownerSessionId + " split=" + splitByApp);
        }
    }

    private boolean isSessionInMirrorMode(int sessionId) {
        if (sessionId <= 0) return false;
        OpalCastCompositorBridge.ActiveRemoteSession s = sessions.get(sessionId);
        return s != null && !s.closed && s.isMirror();
    }

    private boolean isAudioOwnerInMirrorMode() {
        return isSessionInMirrorMode(audioOwnerSessionId);
    }

    void syncAudioRoutingForMode(boolean mirrorMode) {
        synchronized (audioLock) {
            AudioStreamCapturer cap = audioCapturer;
            if (cap == null || !cap.isActive()) return;
            boolean effectiveMirror = mirrorMode && (audioOwnerSessionId == desktopSessionIdSupplier.getAsInt());
            boolean splitByApp = !effectiveMirror && (config.getAudioRouteMode() == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP);
            List<Integer> uids = splitByApp ? collectDexAppUids() : Collections.emptyList();
            cap.setSplitMode(splitByApp, uids);
        }
    }

    void syncSplitAudioUidsIfActive() {
        synchronized (audioLock) {
            AudioStreamCapturer cap = audioCapturer;
            if (cap != null && cap.isActive()
                    && !isAudioOwnerInMirrorMode()
                    && config.getAudioRouteMode() == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP) {
                cap.updateTargetUids(collectDexAppUids());
            }
        }
    }

    void applyAudioRouteMode(int mode) {
        worker.execute(() -> {
            synchronized (audioLock) {
                AudioStreamCapturer cap = audioCapturer;
                if (cap == null || !cap.isActive()) return;
                boolean splitByApp = !isAudioOwnerInMirrorMode()
                        && (mode == BridgeProtocol.AUDIO_ROUTE_SPLIT_BY_APP);
                List<Integer> uids = splitByApp ? collectDexAppUids() : Collections.emptyList();
                cap.setSplitMode(splitByApp, uids);
            }
        });
    }

    private List<Integer> collectDexAppUids() {
        Map<String, CompositorEngine.WindowAppTarget> targets = new LinkedHashMap<>();
        for (CompositorEngine.WindowAppTarget t : compositor.getOpenWindowAppTargets()) {
            if (t != null && !t.pkg.isEmpty()) {
                targets.put(t.appKey(), t);
            }
        }
        for (OpalCastCompositorBridge.ActiveRemoteSession s : sessions.values()) {
            if (s != null && !s.closed && s.standalone && !s.appPackage.isEmpty()) {
                String key = UserProfileAppPolicy.formatAppKey(s.appPackage, s.appUserId);
                if (!targets.containsKey(key)) {
                    targets.put(key, new CompositorEngine.WindowAppTarget(s.appPackage, s.appUserId));
                }
            }
        }
        List<Integer> uids = new ArrayList<>();
        if (targets.isEmpty() || services == null) {
            return uids;
        }
        Set<Integer> seenUids = new HashSet<>();
        for (CompositorEngine.WindowAppTarget target : targets.values()) {
            int uid = services.getPackageUidForUser(target.pkg, target.userId);
            if (uid >= AudioRoutingPolicy.MIN_APP_UID && seenUids.add(uid)) {
                uids.add(uid);
            }
        }
        return uids;
    }

    void stopAudio() {
        synchronized (audioLock) {
            stopAudioLocked();
        }
    }

    private void stopAudioLocked() {
        AudioStreamCapturer cap = audioCapturer;
        audioCapturer = null;
        audioOwnerSessionId = -1;
        lastAudioConfig = null;
        if (cap != null) {
            try { cap.stop(); } catch (RuntimeException ignored) {}
        }
    }

    void applyRemoteMediaPolicy(
            boolean micEnabled,
            boolean cameraEnabled,
            boolean triggerMicStart,
            boolean triggerCameraStart) {
        if (!micEnabled) {
            stopRemoteMic(true);
        } else if (triggerMicStart) {
            int deskSid = desktopSessionIdSupplier.getAsInt();
            OpalCastCompositorBridge.ActiveRemoteSession s = (deskSid > 0) ? sessions.get(deskSid) : null;
            if (s != null && !s.closed && micOwnerSessionId != deskSid) {
                server.sendMicState(deskSid, new OpalCastProtocol.MicStateMsg(
                        true,
                        true,
                        OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE,
                        OpalCastProtocol.DEFAULT_MIC_CHANNELS,
                        ""));
            }
        }
        if (!cameraEnabled) {
            stopRemoteCamera(true);
        } else if (triggerCameraStart && config.isRootEnhanced()) {
            int deskSid = desktopSessionIdSupplier.getAsInt();
            OpalCastCompositorBridge.ActiveRemoteSession s = (deskSid > 0) ? sessions.get(deskSid) : null;
            if (s != null && !s.closed && cameraOwnerSessionId != deskSid) {
                server.sendCameraState(deskSid, new OpalCastProtocol.CameraStateMsg(
                        true,
                        true,
                        false,
                        OpalCastProtocol.DEFAULT_CAMERA_WIDTH,
                        OpalCastProtocol.DEFAULT_CAMERA_HEIGHT,
                        OpalCastProtocol.DEFAULT_CAMERA_FPS,
                        "",
                        "",
                        "front"));
            }
        }
    }

    void handleMicConfig(int sessionId, OpalCastProtocol.MicConfigMsg msg) {
        synchronized (micLock) {
            if (!msg.enabled) {
                if (micOwnerSessionId == sessionId) {
                    stopRemoteMicLocked(false);
                }
                server.sendMicState(sessionId, new OpalCastProtocol.MicStateMsg(
                        false, config.isRemoteMicEnabled(), msg.sampleRate, msg.channels, "远端麦克风已关闭"));
                return;
            }
            if (!config.isRemoteMicEnabled()) {
                server.sendMicState(sessionId, new OpalCastProtocol.MicStateMsg(
                        false, false, msg.sampleRate, msg.channels,
                        "主机端未开启远端麦克风（可在主应用设置或控制中心开启）"));
                return;
            }
            if (!OpalCastProtocol.MIC_CODEC_PCM16.equalsIgnoreCase(msg.codec)) {
                server.sendMicState(sessionId, new OpalCastProtocol.MicStateMsg(
                        false, false, msg.sampleRate, msg.channels, "不支持的麦克风音频格式：" + msg.codec));
                return;
            }
            if (micOwnerSessionId > 0 && micOwnerSessionId != sessionId) {
                int prevSid = micOwnerSessionId;
                stopRemoteMicLocked(false);
                server.sendMicState(prevSid, new OpalCastProtocol.MicStateMsg(
                        false, true, OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE,
                        OpalCastProtocol.DEFAULT_MIC_CHANNELS, "远端麦克风已被其他会话接管"));
            }
            if (micInjector == null) {
                micInjector = new RemoteMicInjector(services.context);
            }
            RemoteMicInjector.StartResult res = micInjector.start(msg.sampleRate, msg.channels);
            micOwnerSessionId = res.active ? sessionId : -1;
            server.sendMicState(sessionId, new OpalCastProtocol.MicStateMsg(
                    res.active, res.supported, res.sampleRate, res.channels, res.message));
        }
    }

    void feedMicFrame(int sessionId, byte[] pcmData) {
        if (sessionId != micOwnerSessionId || pcmData == null || pcmData.length == 0) return;
        RemoteMicInjector inj = micInjector;
        if (inj != null) {
            inj.feedPcm(pcmData);
        }
    }

    void stopRemoteMic(boolean notifyOwner) {
        synchronized (micLock) {
            stopRemoteMicLocked(notifyOwner);
        }
    }

    private void stopRemoteMicLocked(boolean notifyOwner) {
        int prevSid = micOwnerSessionId;
        micOwnerSessionId = -1;
        if (micInjector != null) {
            try { micInjector.stop(); } catch (RuntimeException ignored) {}
        }
        if (notifyOwner && prevSid > 0) {
            server.sendMicState(prevSid, new OpalCastProtocol.MicStateMsg(
                    false, config.isRemoteMicEnabled(), OpalCastProtocol.DEFAULT_MIC_SAMPLE_RATE,
                    OpalCastProtocol.DEFAULT_MIC_CHANNELS,
                    config.isRemoteMicEnabled() ? "远端麦克风已关闭" : "主机端已关闭远端麦克风开关"));
        }
    }

    void handleCameraConfig(int sessionId, OpalCastProtocol.CameraConfigMsg msg) {
        synchronized (cameraLock) {
            if (!msg.enabled) {
                if (cameraOwnerSessionId == sessionId) {
                    stopRemoteCameraLocked(false);
                }
                server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                        false, config.isRemoteCameraEnabled() && config.isRootEnhanced(), !config.isRootEnhanced(),
                        msg.width, msg.height, msg.fps, "", "远端摄像头映射已关闭"));
                return;
            }
            if (!config.isRootEnhanced()) {
                server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                        false, false, true,
                        msg.width, msg.height, msg.fps, "",
                        "摄像头映射需要 Root 权限（请使用 Root 增强模式启动 OpalDesk 引擎）"));
                return;
            }
            if (!config.isRemoteCameraEnabled()) {
                server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                        false, false, false,
                        msg.width, msg.height, msg.fps, "",
                        "主机端未开启 Root 摄像头映射（可在主应用设置或控制中心开启）"));
                return;
            }
            if (!OpalCastProtocol.CAMERA_CODEC_MJPEG.equalsIgnoreCase(msg.codec)) {
                server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                        false, false, false, msg.width, msg.height, msg.fps, "",
                        "不支持的摄像头编码格式：" + msg.codec));
                return;
            }
            if (cameraOwnerSessionId > 0 && cameraOwnerSessionId != sessionId) {
                int prevSid = cameraOwnerSessionId;
                stopRemoteCameraLocked(false);
                server.sendCameraState(prevSid, new OpalCastProtocol.CameraStateMsg(
                        false, true, false,
                        OpalCastProtocol.DEFAULT_CAMERA_WIDTH, OpalCastProtocol.DEFAULT_CAMERA_HEIGHT,
                        OpalCastProtocol.DEFAULT_CAMERA_FPS, "", "远端摄像头映射已被其他会话接管"));
            }
            if (cameraBridge == null) {
                cameraBridge = new RootCameraBridge(services.context);
                cameraBridge.setFacingChangeListener(this::onVirtualCameraActiveFacingChanged);
            }
            boolean wasAospActive = cameraBridge.hasAospVirtualCamera();
            RootCameraBridge.StartResult res = cameraBridge.start(
                    config.isRootEnhanced(), msg.width, msg.height, msg.fps, msg.facing);
            cameraOwnerSessionId = res.active ? sessionId : -1;
            if (res.active && cameraBridge.hasAospVirtualCamera()) {
                final RootCameraBridge activeBridge = cameraBridge;
                displayDriver.setCustomVirtualDisplayProvider(activeBridge::createVirtualDisplay);
                if (!wasAospActive) {
                    int migrated = compositor.migrateLiveWindowsToCurrentDisplayDriver();
                    System.out.println("OPALDESK_AOSP_VCAM_MIGRATED_ON count=" + migrated);
                }
            }
            server.sendCameraState(sessionId, new OpalCastProtocol.CameraStateMsg(
                    res.active, res.supported, res.rootRequired,
                    res.width, res.height, res.fps, res.backend, res.message,
                    msg.facing));
        }
    }

    void feedCameraFrame(int sessionId, byte[] jpegData) {
        if (sessionId != cameraOwnerSessionId || jpegData == null || jpegData.length == 0) return;
        RootCameraBridge bridge = cameraBridge;
        if (bridge != null) {
            bridge.feedJpeg(jpegData);
        }
    }

    private void onVirtualCameraActiveFacingChanged(String facing) {
        if (facing == null || facing.isEmpty()) return;
        int ownerSid = cameraOwnerSessionId;
        RootCameraBridge bridge = cameraBridge;
        if (ownerSid <= 0 || bridge == null || !bridge.isActive()) return;
        System.out.println("OPALDESK_AOSP_VCAM_FACING_SYNC sid=" + ownerSid + " facing=" + facing);
        server.sendCameraState(ownerSid, new OpalCastProtocol.CameraStateMsg(
                true, true, false,
                bridge.getActiveWidth(), bridge.getActiveHeight(), bridge.getActiveFps(),
                bridge.getActiveBackend(), "", facing));
    }

    void stopRemoteCamera(boolean notifyOwner) {
        stopRemoteCamera(notifyOwner, false);
    }

    void stopRemoteCamera(boolean notifyOwner, boolean disassociateCdm) {
        synchronized (cameraLock) {
            stopRemoteCameraLocked(notifyOwner, disassociateCdm);
        }
    }

    private void stopRemoteCameraLocked(boolean notifyOwner) {
        stopRemoteCameraLocked(notifyOwner, false);
    }

    private void stopRemoteCameraLocked(boolean notifyOwner, boolean disassociateCdm) {
        int prevSid = cameraOwnerSessionId;
        cameraOwnerSessionId = -1;
        boolean hadAosp = cameraBridge != null && cameraBridge.hasAospVirtualCamera();
        if (displayDriver != null) {
            displayDriver.setCustomVirtualDisplayProvider(null);
        }
        if (hadAosp && !disassociateCdm) {
            // Migrate live windows back to default DisplayManager BEFORE closing VirtualDevice so
            // apps regain the phone's physical cameras and are never killed by VD teardown.
            int migrated = compositor.migrateLiveWindowsToCurrentDisplayDriver();
            System.out.println("OPALDESK_AOSP_VCAM_MIGRATED_OFF count=" + migrated);
        }
        if (cameraBridge != null) {
            try { cameraBridge.stop(disassociateCdm); } catch (RuntimeException ignored) {}
        }
        if (notifyOwner && prevSid > 0) {
            server.sendCameraState(prevSid, new OpalCastProtocol.CameraStateMsg(
                    false, config.isRemoteCameraEnabled() && config.isRootEnhanced(), !config.isRootEnhanced(),
                    OpalCastProtocol.DEFAULT_CAMERA_WIDTH, OpalCastProtocol.DEFAULT_CAMERA_HEIGHT,
                    OpalCastProtocol.DEFAULT_CAMERA_FPS, "",
                    config.isRemoteCameraEnabled() ? "远端摄像头映射已关闭" : "主机端已关闭 Root 摄像头映射开关"));
        }
    }
}
