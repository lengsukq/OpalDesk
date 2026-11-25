package io.github.lengsukq.opaldesk.daemon.video;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Root-gated remote camera uplink bridge ({@code TYPE_CAMERA_CONFIG 0x2E} / {@code TYPE_CAMERA_FRAME 0x30}).
 *
 * <p>Preserves {@code OpalDeskDaemon.dropRootFailClosed()} security invariant: never keeps {@code uid=0}
 * inside the daemon loop. Instead, requires the daemon to have been started via Root
 * ({@code --root-enhance}), and publishes incoming MJPEG frames from macOS / Tablet / Web receivers
 * to both:
 * 1) Abstract Unix socket {@code @opaldesk_cam_feed} (4-byte BE length-prefixed JPEG frames)
 * 2) Atomic ring file {@code /data/local/tmp/opaldesk_cam/latest.jpg} + {@code meta.json}
 * so Root/LSPosed virtual camera modules or V4L2 loopback feeders can consume the live stream with
 * minimal latency.
 */
public final class RootCameraBridge {
    public static final String FEED_SOCKET_NAME = "opaldesk_cam_feed";
    public static final String FEED_DIR_PATH = "/data/local/tmp/opaldesk_cam";
    public static final String BACKEND_AOSP_VIRTUAL_CAMERA = "aosp_virtual_camera";
    public static final String BACKEND_ROOT_MJPEG_FEED = "root_mjpeg_feed";
    private static final int MAX_QUEUED_FRAMES = 2;

    public static final class StartResult {
        public final boolean active;
        public final boolean supported;
        public final boolean rootRequired;
        public final int width;
        public final int height;
        public final int fps;
        public final String backend;
        public final String message;

        public StartResult(
                boolean active, boolean supported, boolean rootRequired,
                int width, int height, int fps, String backend, String message) {
            this.active = active;
            this.supported = supported;
            this.rootRequired = rootRequired;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.backend = (backend != null) ? backend : "";
            this.message = (message != null) ? message : "";
        }
    }

    private final Context shellContext;
    private final File feedDir;
    private final Object lock = new Object();
    private final ArrayBlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
    private final CopyOnWriteArrayList<LocalSocket> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong frameSeq = new AtomicLong(0L);

    public interface FacingChangeListener {
        void onActiveFacingChanged(String facing);
    }

    private volatile boolean running = false;
    private volatile int activeWidth = OpalCastProtocol.DEFAULT_CAMERA_WIDTH;
    private volatile int activeHeight = OpalCastProtocol.DEFAULT_CAMERA_HEIGHT;
    private volatile int activeFps = OpalCastProtocol.DEFAULT_CAMERA_FPS;
    private volatile String activeFacing = "front";
    private volatile String activeBackend = BACKEND_ROOT_MJPEG_FEED;
    private volatile FacingChangeListener facingChangeListener;
    private AospVirtualCameraHost aospCamHost;
    private LocalServerSocket serverSocket;
    private Thread acceptThread;
    private Thread writerThread;

    public RootCameraBridge() {
        this(null, new File(FEED_DIR_PATH));
    }

    public RootCameraBridge(Context shellContext) {
        this(shellContext, new File(FEED_DIR_PATH));
    }

    public RootCameraBridge(File feedDir) {
        this(null, feedDir);
    }

    public RootCameraBridge(Context shellContext, File feedDir) {
        this.shellContext = shellContext;
        this.feedDir = (feedDir != null) ? feedDir : new File(FEED_DIR_PATH);
    }

    public void setFacingChangeListener(FacingChangeListener listener) {
        this.facingChangeListener = listener;
        AospVirtualCameraHost host = this.aospCamHost;
        if (host != null) {
            host.setFacingChangeListener(listener != null ? listener::onActiveFacingChanged : null);
        }
    }

    public int getActiveWidth() { return activeWidth; }
    public int getActiveHeight() { return activeHeight; }
    public int getActiveFps() { return activeFps; }
    public String getActiveBackend() { return activeBackend; }

    public boolean hasAospVirtualCamera() {
        AospVirtualCameraHost host = this.aospCamHost;
        return running && host != null && host.isActive();
    }

    public VirtualDisplay createVirtualDisplay(
            String name, int width, int height, int dpi, int flags, float refreshRate, Surface surface) {
        AospVirtualCameraHost host = this.aospCamHost;
        if (!running || host == null || !host.isActive()) return null;
        return host.createVirtualDisplay(name, width, height, dpi, flags, refreshRate, surface);
    }

    public boolean mirrorCameraPermissionIfAlreadyGranted(String packageName) {
        AospVirtualCameraHost host = this.aospCamHost;
        if (!running || host == null || !host.isActive()) return false;
        return host.mirrorCameraPermissionIfAlreadyGranted(packageName);
    }

    public static int clampWidth(int w) {
        return Math.max(160, Math.min(1920, w > 0 ? w : OpalCastProtocol.DEFAULT_CAMERA_WIDTH));
    }

    public static int clampHeight(int h) {
        return Math.max(120, Math.min(1080, h > 0 ? h : OpalCastProtocol.DEFAULT_CAMERA_HEIGHT));
    }

    public static int clampFps(int fps) {
        return Math.max(5, Math.min(30, fps > 0 ? fps : OpalCastProtocol.DEFAULT_CAMERA_FPS));
    }

    public static String buildMetaJson(
            boolean active, int width, int height, int fps, String facing, long seq) {
        return new SimpleJson.ObjectBuilder()
                .put("active", active)
                .put("codec", OpalCastProtocol.CAMERA_CODEC_MJPEG)
                .put("width", width)
                .put("height", height)
                .put("fps", fps)
                .put("facing", facing != null ? facing : "front")
                .put("seq", seq)
                .put("socket", "@" + FEED_SOCKET_NAME)
                .build();
    }

    public boolean isActive() {
        return running;
    }

    public StartResult start(boolean rootEnhanced, int reqWidth, int reqHeight, int reqFps, String facing) {
        int width = clampWidth(reqWidth);
        int height = clampHeight(reqHeight);
        int fps = clampFps(reqFps);
        String normFacing = "back".equalsIgnoreCase(facing) ? "back" : "front";

        if (!rootEnhanced) {
            return new StartResult(
                    false,
                    false,
                    true,
                    width,
                    height,
                    fps,
                    "",
                    "摄像头映射需要 Root 权限（请使用 Root 方式启动 OpalDesk 引擎）");
        }

        synchronized (lock) {
            if (running && activeWidth == width && activeHeight == height && activeFps == fps) {
                boolean facingChanged = !activeFacing.equals(normFacing);
                if (facingChanged) {
                    activeFacing = normFacing;
                    writeMetaFileQuietly(true, frameSeq.get());
                }
                boolean hasAosp = hasAospVirtualCamera();
                String msg = facingChanged
                        ? ("back".equals(normFacing) ? "已切换为后置镜头" : "已切换为前置镜头")
                        : (hasAosp
                                ? "系统虚拟摄像头已启用（前后双摄自动跟随切换，桌面应用可直接调用）"
                                : "Root 摄像头推流已就绪 (@" + FEED_SOCKET_NAME + "，需外部虚拟摄像头模块接入应用)");
                return new StartResult(
                        true, true, false, width, height, fps,
                        hasAosp ? BACKEND_AOSP_VIRTUAL_CAMERA : BACKEND_ROOT_MJPEG_FEED,
                        msg);
            }
            stopLocked(false);

            try {
                if (!feedDir.exists() && !feedDir.mkdirs()) {
                    return new StartResult(
                            false, false, false, width, height, fps, "",
                            "无法创建摄像头桥接目录：" + feedDir.getAbsolutePath());
                }

                boolean aospStarted = false;
                if (shellContext != null && AospVirtualCameraHost.isSupportedSdkInt(Build.VERSION.SDK_INT)) {
                    AospVirtualCameraHost host = new AospVirtualCameraHost(shellContext);
                    FacingChangeListener fl = this.facingChangeListener;
                    if (fl != null) {
                        host.setFacingChangeListener(fl::onActiveFacingChanged);
                    }
                    if (host.start(width, height, fps)) {
                        this.aospCamHost = host;
                        aospStarted = true;
                    }
                }

                this.activeWidth = width;
                this.activeHeight = height;
                this.activeFps = fps;
                this.activeFacing = normFacing;
                this.activeBackend = aospStarted ? BACKEND_AOSP_VIRTUAL_CAMERA : BACKEND_ROOT_MJPEG_FEED;
                this.frameQueue.clear();
                this.running = true;

                startFeedSocketLocked();
                writeMetaFileQuietly(true, frameSeq.get());

                Thread wt = new Thread(this::writeLoop, "OpalCamBridgeTx");
                wt.setDaemon(true);
                this.writerThread = wt;
                wt.start();

                System.out.println("OPALDESK_ROOT_CAM_STARTED " + width + "x" + height
                        + "@" + fps + "fps backend=" + activeBackend + " socket=@" + FEED_SOCKET_NAME);
                return new StartResult(
                        true, true, false, width, height, fps, activeBackend,
                        aospStarted
                                ? "系统虚拟摄像头已启用（前后双摄自动跟随切换，桌面应用可直接调用）"
                                : "Root 摄像头推流已就绪 (@" + FEED_SOCKET_NAME + "，需外部虚拟摄像头模块接入应用)");
            } catch (Throwable t) {
                stopLocked(false);
                String msg = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
                System.err.println("OPALDESK_ROOT_CAM_START_FAILED: " + msg);
                return new StartResult(false, false, false, width, height, fps, "", "摄像头映射启动失败：" + msg);
            }
        }
    }

    public void feedJpeg(byte[] jpegBytes) {
        if (!running || jpegBytes == null || jpegBytes.length == 0) return;
        if (jpegBytes.length > OpalCastProtocol.MAX_CAMERA_FRAME_BYTES) return;
        if (!OpalCastProtocol.isValidJpegHeader(jpegBytes)) return;
        if (!frameQueue.offer(jpegBytes)) {
            frameQueue.poll();
            frameQueue.offer(jpegBytes);
        }
    }

    public void stop() {
        stop(false);
    }

    public void stop(boolean disassociateCdm) {
        synchronized (lock) {
            stopLocked(disassociateCdm);
        }
    }

    private void startFeedSocketLocked() {
        try {
            LocalServerSocket ss = new LocalServerSocket(FEED_SOCKET_NAME);
            this.serverSocket = ss;
            Thread at = new Thread(() -> acceptLoop(ss), "OpalCamFeedAccept");
            at.setDaemon(true);
            this.acceptThread = at;
            at.start();
        } catch (Throwable t) {
            // File-based latest.jpg + meta.json still works even if abstract socket is unavailable.
            System.err.println("OPALDESK_ROOT_CAM_SOCK_WARN: " + t.getMessage());
        }
    }

    private void acceptLoop(LocalServerSocket ss) {
        while (running) {
            try {
                LocalSocket client = ss.accept();
                if (!running) {
                    closeQuietly(client);
                    break;
                }
                try {
                    client.setSoTimeout(500);
                } catch (IOException ignored) {
                }
                subscribers.add(client);
            } catch (Throwable t) {
                if (running) {
                    System.err.println("OPALDESK_ROOT_CAM_ACCEPT_WARN: " + t.getMessage());
                }
                break;
            }
        }
    }

    private void writeLoop() {
        while (running) {
            try {
                byte[] jpeg = frameQueue.poll(150L, TimeUnit.MILLISECONDS);
                if (jpeg == null || jpeg.length == 0) continue;
                long seq = frameSeq.incrementAndGet();
                AospVirtualCameraHost vcam = this.aospCamHost;
                if (vcam != null && vcam.isActive()) {
                    vcam.onJpegFrame(jpeg);
                }
                writeLatestFrameFile(jpeg);
                writeMetaFileQuietly(true, seq);
                broadcastToSubscribers(jpeg);
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable t) {
                if (running) {
                    System.err.println("OPALDESK_ROOT_CAM_WRITE_WARN: " + t.getMessage());
                }
            }
        }
    }

    private void writeLatestFrameFile(byte[] jpeg) {
        File tmp = new File(feedDir, "latest.jpg.tmp");
        File dst = new File(feedDir, "latest.jpg");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(jpeg);
            fos.flush();
        } catch (IOException e) {
            return;
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(dst)) {
            // Fallback direct overwrite if rename fails on some filesystems
            try (FileOutputStream fos = new FileOutputStream(dst)) {
                fos.write(jpeg);
                fos.flush();
                dst.setReadable(true, false);
            } catch (IOException ignored) {
            }
        }
    }

    private void writeMetaFileQuietly(boolean active, long seq) {
        File tmp = new File(feedDir, "meta.json.tmp");
        File dst = new File(feedDir, "meta.json");
        byte[] bytes = buildMetaJson(active, activeWidth, activeHeight, activeFps, activeFacing, seq)
                .getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(bytes);
            fos.flush();
        } catch (IOException e) {
            return;
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(dst)) {
            try (FileOutputStream fos = new FileOutputStream(dst)) {
                fos.write(bytes);
                fos.flush();
                dst.setReadable(true, false);
            } catch (IOException ignored) {
            }
        }
    }

    private void broadcastToSubscribers(byte[] jpeg) {
        if (subscribers.isEmpty()) return;
        byte[] lenHdr = new byte[4];
        int len = jpeg.length;
        lenHdr[0] = (byte) ((len >>> 24) & 0xFF);
        lenHdr[1] = (byte) ((len >>> 16) & 0xFF);
        lenHdr[2] = (byte) ((len >>> 8) & 0xFF);
        lenHdr[3] = (byte) (len & 0xFF);

        Iterator<LocalSocket> it = subscribers.iterator();
        while (it.hasNext()) {
            LocalSocket sock = it.next();
            try {
                OutputStream out = sock.getOutputStream();
                out.write(lenHdr);
                out.write(jpeg);
                out.flush();
            } catch (Throwable t) {
                subscribers.remove(sock);
                closeQuietly(sock);
            }
        }
    }

    private void stopLocked(boolean disassociateCdm) {
        boolean wasRunning = running || serverSocket != null || aospCamHost != null;
        running = false;
        frameQueue.clear();

        LocalServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }

        for (LocalSocket s : subscribers) {
            closeQuietly(s);
        }
        subscribers.clear();

        Thread wt = writerThread;
        writerThread = null;
        if (wt != null && wt != Thread.currentThread()) {
            wt.interrupt();
            try { wt.join(250L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        acceptThread = null;

        AospVirtualCameraHost vcam = this.aospCamHost;
        this.aospCamHost = null;
        if (vcam != null) {
            try { vcam.stop(disassociateCdm); } catch (RuntimeException ignored) {}
        }

        if (wasRunning) {
            writeMetaFileQuietly(false, frameSeq.get());
            System.out.println("OPALDESK_ROOT_CAM_STOPPED");
        }
    }

    private static void closeQuietly(LocalSocket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }
}
