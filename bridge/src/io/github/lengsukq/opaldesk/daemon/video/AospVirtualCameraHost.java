package io.github.lengsukq.opaldesk.daemon.video;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Standard AOSP Android 15+ (API 35+) {@code VirtualDeviceManager} + {@code VirtualCamera} host.
 *
 * <p>100% OEM-agnostic and runs completely under {@code uid=2000} ({@code com.android.shell} with
 * AOSP {@code CREATE_VIRTUAL_DEVICE}): registers both a FRONT and BACK {@code VirtualCamera} inside
 * Android's native {@code CameraService} (backed by {@code /system/bin/virtual_camera} GPU HAL) and
 * supplies a {@code VirtualDisplay} factory bound to that {@code VirtualDevice}. Any app running on
 * those {@code OpalWin-*} {@code VirtualDisplay}s (e.g. WeChat, Zoom, Camera) sees our live remote
 * tablet/Mac/Web camera feed natively through standard Camera1/Camera2 APIs.
 */
public final class AospVirtualCameraHost {
    public static final int MIN_SUPPORTED_API = VirtualCameraPolicy.MIN_SUPPORTED_API;
    static final String CDM_MAC = "02:4f:50:41:4c:01";
    private static final String CDM_PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING";
    private static final String SHELL_PKG = "com.android.shell";
    private static final long STALE_REPAINT_MS = 120L;

    public interface FacingChangeListener {
        void onActiveFacingChanged(String facing);
    }

    private static final class ActiveStream {
        final Surface surface;
        final int width;
        final int height;
        final int preRotCwDeg;
        final Object drawLock = new Object();
        volatile long lastPaintMs = 0L;

        ActiveStream(Surface surface, int width, int height, int sensorOrientation) {
            this.surface = surface;
            this.width = width;
            this.height = height;
            this.preRotCwDeg = VirtualCameraPolicy.computePreRotationCwDegrees(sensorOrientation);
        }
    }

    private final Context shellContext;
    private final Object lifecycleLock = new Object();
    private final ConcurrentHashMap<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();
    private final Set<String> mirroredCameraPackages = ConcurrentHashMap.newKeySet();
    private final Object bitmapLock = new Object();
    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private volatile FacingChangeListener facingChangeListener;
    private volatile String activeStreamFacing = "";
    private volatile boolean active = false;
    private volatile Object virtualDevice;
    private volatile Object frontCamera;
    private volatile Object backCamera;
    private volatile int virtualDeviceId = -1;
    private volatile int associationId = -1;
    private volatile String persistentDeviceId;
    private volatile byte[] latestJpegBytes;
    private volatile Bitmap latestBitmap;
    private volatile boolean latestJpegDirty = false;

    public AospVirtualCameraHost(Context shellContext) {
        this.shellContext = shellContext;
    }

    public void setFacingChangeListener(FacingChangeListener listener) {
        this.facingChangeListener = listener;
    }

    public String getActiveStreamFacing() {
        return activeStreamFacing;
    }

    public static boolean isSupportedSdkInt(int sdkInt) {
        return VirtualCameraPolicy.isSupportedSdkInt(sdkInt);
    }

    public static int parseAssociationId(String listOutput, String pkg, String mac) {
        return VirtualCameraPolicy.parseAssociationId(listOutput, pkg, mac);
    }

    public static int[] computeCenterCropSrcRect(int srcW, int srcH, int dstW, int dstH) {
        return VirtualCameraPolicy.computeCenterCropSrcRect(srcW, srcH, dstW, dstH);
    }

    public boolean isActive() {
        return active && virtualDevice != null;
    }

    public int getVirtualDeviceId() {
        return virtualDeviceId;
    }

    public boolean start(int width, int height, int fps) {
        if (!isSupportedSdkInt(Build.VERSION.SDK_INT) || shellContext == null) {
            return false;
        }
        synchronized (lifecycleLock) {
            if (active && virtualDevice != null && (frontCamera != null || backCamera != null)) {
                return true;
            }
            stopLocked(false);

            try {
                int aid = ensureCompanionAssociation();
                if (aid <= 0) {
                    System.err.println("OPALDESK_AOSP_VCAM_WARN: failed to obtain CompanionDevice association");
                    return false;
                }
                this.associationId = aid;

                Context appCtx = new ContextWrapper(shellContext) {
                    @Override
                    public Context getApplicationContext() {
                        return this;
                    }
                };

                Class<?> smCls = Class.forName("android.os.ServiceManager");
                android.os.IBinder vdmBinder = (android.os.IBinder) smCls
                        .getMethod("getService", String.class).invoke(null, "virtualdevice");
                if (vdmBinder == null) {
                    System.err.println("OPALDESK_AOSP_VCAM_WARN: virtualdevice binder unavailable");
                    return false;
                }
                Object ivdm = Class.forName("android.companion.virtual.IVirtualDeviceManager$Stub")
                        .getMethod("asInterface", android.os.IBinder.class).invoke(null, vdmBinder);
                Class<?> ivdmCls = Class.forName("android.companion.virtual.IVirtualDeviceManager");
                Class<?> vdmCls = Class.forName("android.companion.virtual.VirtualDeviceManager");
                Constructor<?> vdmCtor = vdmCls.getDeclaredConstructor(ivdmCls, Context.class);
                vdmCtor.setAccessible(true);
                Object vdm = vdmCtor.newInstance(ivdm, appCtx);

                Class<?> paramsCls = Class.forName("android.companion.virtual.VirtualDeviceParams");
                Class<?> paramsBuilderCls = Class.forName("android.companion.virtual.VirtualDeviceParams$Builder");
                int policyCamera = paramsCls.getField("POLICY_TYPE_CAMERA").getInt(null);
                int policyCustom = paramsCls.getField("DEVICE_POLICY_CUSTOM").getInt(null);
                int lockAlwaysUnlocked = paramsCls.getField("LOCK_STATE_ALWAYS_UNLOCKED").getInt(null);

                Object pb = paramsBuilderCls.getConstructor().newInstance();
                paramsBuilderCls.getMethod("setName", String.class).invoke(pb, "OpalDeskVirtualCam");
                paramsBuilderCls.getMethod("setLockState", int.class).invoke(pb, lockAlwaysUnlocked);
                paramsBuilderCls.getMethod("setDevicePolicy", int.class, int.class).invoke(pb, policyCamera, policyCustom);
                Object params = paramsBuilderCls.getMethod("build").invoke(pb);

                Object vd = vdmCls.getMethod("createVirtualDevice", int.class, paramsCls).invoke(vdm, aid, params);
                Class<?> vdCls = Class.forName("android.companion.virtual.VirtualDeviceManager$VirtualDevice");
                int devId = (Integer) vdCls.getMethod("getDeviceId").invoke(vd);

                int camFps = VirtualCameraPolicy.resolveVirtualCameraStreamFps(fps);
                Object front = registerVirtualCamera(vd, vdCls, "OpalDesk Front",
                        CameraCharacteristics.LENS_FACING_FRONT,
                        VirtualCameraPolicy.FRONT_SENSOR_ORIENTATION,
                        width, height, camFps, "front");
                Object back = registerVirtualCamera(vd, vdCls, "OpalDesk Back",
                        CameraCharacteristics.LENS_FACING_BACK,
                        VirtualCameraPolicy.BACK_SENSOR_ORIENTATION,
                        width, height, camFps, "back");
                if (front == null && back == null) {
                    closeQuietly(vd);
                    return false;
                }

                String pDevId = readPersistentDeviceIdQuietly(vd, ivdm, devId, aid);

                this.virtualDevice = vd;
                this.virtualDeviceId = devId;
                this.persistentDeviceId = pDevId;
                this.frontCamera = front;
                this.backCamera = back;
                this.active = true;
                this.mirroredCameraPackages.clear();
                int mirroredCount = mirrorAlreadyGrantedCameraPermissions(pDevId);
                System.out.println("OPALDESK_AOSP_VCAM_STARTED devId=" + devId
                        + " persistentId=" + pDevId
                        + " mirroredCameraApps=" + mirroredCount
                        + " front=" + readCameraIdQuietly(front)
                        + " back=" + readCameraIdQuietly(back));
                return true;
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                System.err.println("OPALDESK_AOSP_VCAM_START_WARN: " + cause);
                stopLocked(false);
                return false;
            }
        }
    }

    /**
     * Creates a {@link VirtualDisplay} owned by this {@code VirtualDevice} so any Activity running
     * on it inherits {@code Context.getDeviceId() == virtualDeviceId} and automatically routes
     * Camera1/Camera2 requests to our {@code VirtualCamera}s.
     */
    public VirtualDisplay createVirtualDisplay(
            String name,
            int width,
            int height,
            int dpi,
            int flags,
            float refreshRate,
            Surface surface) {
        Object vd = this.virtualDevice;
        if (!active || vd == null || surface == null || !surface.isValid()) return null;
        try {
            Class<?> builderCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Class<?> configCls = Class.forName("android.hardware.display.VirtualDisplayConfig");
            Object builder = builderCls.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(name, width, height, dpi);
            builderCls.getMethod("setFlags", int.class).invoke(builder, flags);
            builderCls.getMethod("setSurface", Surface.class).invoke(builder, surface);
            try {
                builderCls.getMethod("setHomeSupported", boolean.class).invoke(builder, false);
            } catch (NoSuchMethodException ignored) {
            }
            if (Float.isFinite(refreshRate) && refreshRate > 0f) {
                try {
                    builderCls.getMethod("setRequestedRefreshRate", float.class).invoke(builder, refreshRate);
                } catch (NoSuchMethodException ignored) {
                }
            }
            Object cfg = builderCls.getMethod("build").invoke(builder);
            Method createMethod = vd.getClass().getMethod(
                    "createVirtualDisplay", configCls, Executor.class, VirtualDisplay.Callback.class);
            VirtualDisplay created = (VirtualDisplay) createMethod.invoke(vd, cfg, (Executor) Runnable::run, null);
            if (created != null && created.getDisplay() != null) {
                return created;
            }
            if (created != null) {
                try { created.release(); } catch (RuntimeException ignored) {}
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.err.println("OPALDESK_AOSP_VCAM_VD_WARN name=" + name
                    + " flags=0x" + Integer.toHexString(flags) + ": " + cause);
        }
        return null;
    }

    /**
     * Feeds a remote MJPEG frame from macOS / Android Tablet / Web into all active
     * {@code VirtualCamera} output streams. Decodes JPEG lazily only when at least one Android
     * app currently holds an open camera stream.
     */
    public void onJpegFrame(byte[] jpegBytes) {
        if (!active || jpegBytes == null || jpegBytes.length == 0) return;
        this.latestJpegBytes = jpegBytes;
        this.latestJpegDirty = true;
        if (activeStreams.isEmpty()) return;

        Bitmap bmp = ensureDecodedLatestBitmap();
        if (bmp == null) return;
        paintBitmapToAllStreams(bmp);
    }

    public void stop(boolean disassociateCdm) {
        synchronized (lifecycleLock) {
            stopLocked(disassociateCdm);
        }
    }

    private void stopLocked(boolean disassociateCdm) {
        boolean wasActive = active || virtualDevice != null || frontCamera != null || backCamera != null;
        active = false;
        activeStreams.clear();

        Object fc = frontCamera;
        Object bc = backCamera;
        Object vd = virtualDevice;
        frontCamera = null;
        backCamera = null;
        virtualDevice = null;
        virtualDeviceId = -1;
        persistentDeviceId = null;
        activeStreamFacing = "";
        mirroredCameraPackages.clear();

        closeQuietly(fc);
        closeQuietly(bc);
        closeQuietly(vd);

        synchronized (bitmapLock) {
            if (latestBitmap != null && !latestBitmap.isRecycled()) {
                latestBitmap.recycle();
            }
            latestBitmap = null;
            latestJpegBytes = null;
            latestJpegDirty = false;
        }

        if (disassociateCdm && associationId > 0) {
            try {
                ShellCommand.runOutput("cmd", "companiondevice", "disassociate", "0", SHELL_PKG, CDM_MAC);
            } catch (RuntimeException ignored) {
            }
            associationId = -1;
        }
        if (wasActive) {
            System.out.println("OPALDESK_AOSP_VCAM_STOPPED");
        }
    }

    private Object registerVirtualCamera(
            Object vd,
            Class<?> vdCls,
            String cameraName,
            int lensFacing,
            int sensorOrientation,
            int reqW,
            int reqH,
            int maxFps,
            String camTag) {
        try {
            Class<?> cbCls = Class.forName("android.companion.virtual.camera.VirtualCameraCallback");
            Object cbProxy = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class<?>[]{cbCls}, (proxy, method, args) -> {
                String mn = method.getName();
                if ("onStreamConfigured".equals(mn) && args != null && args.length >= 4 && args[1] instanceof Surface) {
                    int streamId = (Integer) args[0];
                    Surface surf = (Surface) args[1];
                    int w = (Integer) args[2];
                    int h = (Integer) args[3];
                    String key = camTag + ":" + streamId;
                    ActiveStream st = new ActiveStream(surf, w, h, sensorOrientation);
                    activeStreams.put(key, st);
                    activeStreamFacing = camTag;
                    System.out.println("OPALDESK_AOSP_VCAM_STREAM_OPEN " + key + " " + w + "x" + h
                            + " sensorOrient=" + sensorOrientation + " preRot=" + st.preRotCwDeg);
                    FacingChangeListener l = this.facingChangeListener;
                    if (l != null) {
                        try { l.onActiveFacingChanged(camTag); } catch (Throwable ignored) {}
                    }
                    paintSingleStreamInitial(st);
                } else if ("onProcessCaptureRequest".equals(mn) && args != null && args.length >= 1) {
                    int streamId = (Integer) args[0];
                    ActiveStream st = activeStreams.get(camTag + ":" + streamId);
                    if (st != null && (SystemClock.uptimeMillis() - st.lastPaintMs) >= STALE_REPAINT_MS) {
                        paintSingleStreamInitial(st);
                    }
                } else if ("onStreamClosed".equals(mn) && args != null && args.length >= 1) {
                    int streamId = (Integer) args[0];
                    String key = camTag + ":" + streamId;
                    activeStreams.remove(key);
                    System.out.println("OPALDESK_AOSP_VCAM_STREAM_CLOSED " + key);
                }
                return null;
            });

            Class<?> vccBuilderCls = Class.forName("android.companion.virtual.camera.VirtualCameraConfig$Builder");
            Class<?> vccCls = Class.forName("android.companion.virtual.camera.VirtualCameraConfig");
            Object b = vccBuilderCls.getConstructor(String.class).newInstance(cameraName);
            Method addStream = vccBuilderCls.getMethod("addStreamConfig", int.class, int.class, int.class, int.class);

            int baseW = (reqW >= 320 && reqW <= 1920) ? reqW : 640;
            int baseH = (reqH >= 240 && reqH <= 1080) ? reqH : 480;
            addStream.invoke(b, baseW, baseH, PixelFormat.RGBA_8888, maxFps);
            if (baseW != 640 || baseH != 480) {
                addStream.invoke(b, 640, 480, PixelFormat.RGBA_8888, maxFps);
            }
            if (baseW != 1280 || baseH != 720) {
                addStream.invoke(b, 1280, 720, PixelFormat.RGBA_8888, maxFps);
            }
            vccBuilderCls.getMethod("setLensFacing", int.class).invoke(b, lensFacing);
            vccBuilderCls.getMethod("setSensorOrientation", int.class).invoke(b, sensorOrientation);
            vccBuilderCls.getMethod("setVirtualCameraCallback", Executor.class, cbCls)
                    .invoke(b, (Executor) Runnable::run, cbProxy);
            Object cfg = vccBuilderCls.getMethod("build").invoke(b);
            return vdCls.getMethod("createVirtualCamera", vccCls).invoke(vd, cfg);
        } catch (Throwable t) {
            System.err.println("OPALDESK_AOSP_VCAM_REG_WARN tag=" + camTag + ": " + unwrap(t));
            return null;
        }
    }

    private int ensureCompanionAssociation() {
        String existing = ShellCommand.runOutput("cmd", "companiondevice", "list", "0");
        int aid = parseAssociationId(existing, SHELL_PKG, CDM_MAC);
        if (aid > 0) return aid;
        ShellCommand.runOutput("cmd", "companiondevice", "associate", "0", SHELL_PKG, CDM_MAC, CDM_PROFILE);
        String after = ShellCommand.runOutput("cmd", "companiondevice", "list", "0");
        return parseAssociationId(after, SHELL_PKG, CDM_MAC);
    }

    private Bitmap ensureDecodedLatestBitmap() {
        byte[] raw = latestJpegBytes;
        if (raw == null || raw.length == 0) {
            return latestBitmap;
        }
        synchronized (bitmapLock) {
            if (!latestJpegDirty && latestBitmap != null && !latestBitmap.isRecycled()) {
                return latestBitmap;
            }
            latestJpegDirty = false;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                if (decoded != null) {
                    Bitmap prev = latestBitmap;
                    latestBitmap = decoded;
                    if (prev != null && prev != decoded && !prev.isRecycled()) {
                        prev.recycle();
                    }
                }
            } catch (Throwable t) {
                System.err.println("OPALDESK_AOSP_VCAM_DECODE_WARN: " + t.getMessage());
            }
            return (latestBitmap != null && !latestBitmap.isRecycled()) ? latestBitmap : null;
        }
    }

    private void paintSingleStreamInitial(ActiveStream st) {
        Bitmap bmp = ensureDecodedLatestBitmap();
        paintStream(st, bmp);
    }

    private void paintBitmapToAllStreams(Bitmap bmp) {
        for (Map.Entry<String, ActiveStream> entry : activeStreams.entrySet()) {
            ActiveStream st = entry.getValue();
            if (st != null) {
                paintStream(st, bmp);
            }
        }
    }

    private void paintStream(ActiveStream st, Bitmap bmp) {
        if (st == null || st.surface == null || !st.surface.isValid()) return;
        synchronized (st.drawLock) {
            if (!st.surface.isValid()) return;
            Canvas canvas = null;
            try {
                canvas = st.surface.lockCanvas(null);
                if (canvas == null) return;
                synchronized (bitmapLock) {
                    Bitmap activeBmp = (latestBitmap != null && !latestBitmap.isRecycled()) ? latestBitmap : bmp;
                    if (activeBmp != null && !activeBmp.isRecycled()
                            && activeBmp.getWidth() > 0 && activeBmp.getHeight() > 0) {
                        int[] crop = VirtualCameraPolicy.computeRotatedCenterCropSrcRect(
                                activeBmp.getWidth(), activeBmp.getHeight(),
                                st.width, st.height, st.preRotCwDeg);
                        Rect src = new Rect(crop[0], crop[1], crop[2], crop[3]);
                        if (st.preRotCwDeg == 0) {
                            Rect dst = new Rect(0, 0, st.width, st.height);
                            canvas.drawBitmap(activeBmp, src, dst, drawPaint);
                        } else {
                            canvas.save();
                            canvas.translate(st.width / 2f, st.height / 2f);
                            canvas.rotate(st.preRotCwDeg);
                            int drawW = (st.preRotCwDeg == 90 || st.preRotCwDeg == 270) ? st.height : st.width;
                            int drawH = (st.preRotCwDeg == 90 || st.preRotCwDeg == 270) ? st.width : st.height;
                            int halfW = drawW / 2;
                            int halfH = drawH / 2;
                            Rect dst = new Rect(-halfW, -halfH, drawW - halfW, drawH - halfH);
                            canvas.drawBitmap(activeBmp, src, dst, drawPaint);
                            canvas.restore();
                        }
                    } else {
                        canvas.drawColor(Color.rgb(18, 22, 30));
                    }
                }
                st.surface.unlockCanvasAndPost(canvas);
                canvas = null;
                st.lastPaintMs = SystemClock.uptimeMillis();
            } catch (Throwable t) {
                if (canvas != null) {
                    try { st.surface.unlockCanvasAndPost(canvas); } catch (Throwable ignored) {}
                }
            }
        }
    }

    /**
     * Mirrors {@link Manifest.permission#CAMERA} onto this {@code VirtualDevice}'s
     * {@code persistentDeviceId} ONLY for {@code packageName} if the user has ALREADY granted
     * {@code CAMERA} to that app on the host phone ({@code deviceId=0}).
     *
     * <p>Android 15+ treats {@code CAMERA} as a device-aware permission: without mirroring an
     * app's existing phone-level grant to {@code companion:<aid>}, apps running inside our
     * {@code VirtualDevice} displays receive {@code CameraService: Permission Denial}.
     */
    public boolean mirrorCameraPermissionIfAlreadyGranted(String packageName) {
        String pDevId = this.persistentDeviceId;
        if (!active || pDevId == null || pDevId.isEmpty()
                || packageName == null || packageName.isEmpty() || shellContext == null) {
            return false;
        }
        if (mirroredCameraPackages.contains(packageName)) {
            return true;
        }
        PackageManager pm = shellContext.getPackageManager();
        if (pm == null || pm.checkPermission(Manifest.permission.CAMERA, packageName)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            Object ipm = resolvePermissionManager();
            if (ipm == null) return false;
            Method grantMethod = ipm.getClass().getMethod(
                    "grantRuntimePermission", String.class, String.class, String.class, int.class);
            grantMethod.invoke(ipm, packageName, Manifest.permission.CAMERA, pDevId, 0);
            mirroredCameraPackages.add(packageName);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int mirrorAlreadyGrantedCameraPermissions(String pDevId) {
        if (pDevId == null || pDevId.isEmpty() || shellContext == null) return 0;
        int grantedCount = 0;
        try {
            PackageManager pm = shellContext.getPackageManager();
            Object ipm = resolvePermissionManager();
            if (pm == null || ipm == null) return 0;
            Method grantMethod = ipm.getClass().getMethod(
                    "grantRuntimePermission", String.class, String.class, String.class, int.class);
            List<PackageInfo> pkgs = pm.getPackagesHoldingPermissions(
                    new String[]{Manifest.permission.CAMERA}, 0);
            if (pkgs == null) return 0;
            for (PackageInfo pi : pkgs) {
                if (pi == null || pi.packageName == null || pi.packageName.isEmpty()) continue;
                try {
                    grantMethod.invoke(ipm, pi.packageName, Manifest.permission.CAMERA, pDevId, 0);
                    mirroredCameraPackages.add(pi.packageName);
                    grantedCount++;
                } catch (Throwable ignored) {
                    // System/immutable packages or non-runtime holders are expected to skip cleanly.
                }
            }
        } catch (Throwable t) {
            System.err.println("OPALDESK_AOSP_VCAM_PERM_MIRROR_WARN: " + unwrap(t));
        }
        return grantedCount;
    }

    private static Object resolvePermissionManager() {
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "permissionmgr");
            if (binder == null) return null;
            return Class.forName("android.permission.IPermissionManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readPersistentDeviceIdQuietly(Object vd, Object ivdm, int devId, int aid) {
        try {
            Object val = vd.getClass().getMethod("getPersistentDeviceId").invoke(vd);
            if (val instanceof String && !((String) val).isEmpty()) {
                return (String) val;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object vdsObj = ivdm.getClass().getMethod("getVirtualDevices").invoke(ivdm);
            if (vdsObj instanceof List<?>) {
                for (Object vdInfo : (List<?>) vdsObj) {
                    if (vdInfo == null) continue;
                    int id = (Integer) vdInfo.getClass().getMethod("getDeviceId").invoke(vdInfo);
                    if (id == devId) {
                        Object val = vdInfo.getClass().getMethod("getPersistentDeviceId").invoke(vdInfo);
                        if (val instanceof String && !((String) val).isEmpty()) {
                            return (String) val;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "companion:" + aid;
    }

    private static String readCameraIdQuietly(Object vcam) {
        if (vcam == null) return "none";
        try {
            Object id = vcam.getClass().getMethod("getId").invoke(vcam);
            return id != null ? id.toString() : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) return;
        try {
            closeable.getClass().getMethod("close").invoke(closeable);
        } catch (Throwable ignored) {
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur instanceof InvocationTargetException && cur.getCause() != null && depth++ < 4) {
            cur = cur.getCause();
        }
        return cur;
    }
}
