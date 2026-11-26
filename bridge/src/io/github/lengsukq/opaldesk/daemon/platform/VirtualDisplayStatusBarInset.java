package io.github.lengsukq.opaldesk.daemon.platform;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Attaches an invisible, non-interactive AOSP {@code TYPE_STATUS_BAR} inset window to a managed
 * {@link android.hardware.display.VirtualDisplay} so apps receive a consistent
 * {@code WindowInsets.Type.statusBars()} top inset instead of suffering split-layout bugs
 * (such as WeChat's duplicate ActionBar / TaskBar header when {@code insets.top == 0}).
 */
public final class VirtualDisplayStatusBarInset {
    private static final int ITYPE_STATUS_BAR = 0;
    private static final long ATTACH_WAIT_MS = 800L;

    private static final class Entry {
        final WindowManager wm;
        final View view;
        final int heightPx;

        Entry(WindowManager wm, View view, int heightPx) {
            this.wm = wm;
            this.view = view;
            this.heightPx = heightPx;
        }
    }

    private final Context baseContext;
    private final Handler mainHandler;
    private final Map<Integer, Entry> activeByDisplay = new ConcurrentHashMap<>();
    private volatile boolean windowAddUnsupported = false;

    public VirtualDisplayStatusBarInset(Context baseContext, Handler mainHandler) {
        this.baseContext = baseContext;
        this.mainHandler = (mainHandler != null) ? mainHandler : new Handler(Looper.getMainLooper());
    }

    /**
     * Attaches a transparent {@code TYPE_STATUS_BAR} inset window of {@code heightPx} pixels to {@code display}.
     * Returns {@code true} if WindowManager accepted the inset window.
     */
    public boolean attach(Display display, int heightPx) {
        if (windowAddUnsupported || baseContext == null || display == null || heightPx <= 0) return false;
        int displayId = display.getDisplayId();
        if (displayId <= 0) return false;
        detach(displayId);

        AtomicBoolean ok = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                Context dispCtx = baseContext.createDisplayContext(display);
                dispCtx = AndroidServices.fixShellContext(dispCtx);
                WindowManager wm = (WindowManager) dispCtx.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;

                View view = new View(dispCtx);
                view.setBackgroundColor(Color.TRANSPARENT);

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        heightPx,
                        WindowManager.LayoutParams.TYPE_STATUS_BAR,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
                lp.setTitle("OpalDeskStatusInset-" + displayId);
                configureStatusBarInsetProvider(lp);

                wm.addView(view, lp);
                activeByDisplay.put(displayId, new Entry(wm, view, heightPx));
                ok.set(true);
                System.out.println("OPALDESK_STATUS_INSET_ATTACHED displayId=" + displayId + " h=" + heightPx);
            } catch (Throwable t) {
                String msg = t.getMessage();
                if (t instanceof IllegalStateException && msg != null && msg.contains("Unknown pid")) {
                    windowAddUnsupported = true;
                }
                System.err.println("OPALDESK_STATUS_INSET_WARN displayId=" + displayId + ": " + t);
            } finally {
                latch.countDown();
            }
        };

        if (Looper.myLooper() == mainHandler.getLooper()) {
            task.run();
        } else {
            mainHandler.post(task);
            try {
                latch.await(ATTACH_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return ok.get();
    }

    public void detach(int displayId) {
        if (displayId <= 0) return;
        Entry removed = activeByDisplay.remove(displayId);
        if (removed == null) return;
        Runnable task = () -> {
            try {
                removed.wm.removeViewImmediate(removed.view);
            } catch (Throwable ignored) {
            }
        };
        if (Looper.myLooper() == mainHandler.getLooper()) {
            task.run();
        } else {
            mainHandler.post(task);
        }
    }

    public void detachAll() {
        for (Integer displayId : activeByDisplay.keySet()) {
            detach(displayId);
        }
    }

    private static void configureStatusBarInsetProvider(WindowManager.LayoutParams lp) {
        // Android 14+ (API 34+): LayoutParams.providedInsets = new InsetsFrameProvider[]{ ... }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Class<?> providerCls = Class.forName("android.view.InsetsFrameProvider");
                Constructor<?> ctor = providerCls.getConstructor(Object.class, int.class, int.class);
                Object provider = ctor.newInstance(new Binder(), 0, WindowInsets.Type.statusBars());
                Object arr = Array.newInstance(providerCls, 1);
                Array.set(arr, 0, provider);
                Field f = WindowManager.LayoutParams.class.getField("providedInsets");
                f.set(lp, arr);
                return;
            } catch (Throwable ignored) {
            }
        }
        // Android 12-13 (API 31-33): LayoutParams.providesInsetsTypes = new int[]{ ITYPE_STATUS_BAR }
        try {
            Field f = WindowManager.LayoutParams.class.getField("providesInsetsTypes");
            f.set(lp, new int[]{ITYPE_STATUS_BAR});
        } catch (Throwable ignored) {
        }
    }
}
