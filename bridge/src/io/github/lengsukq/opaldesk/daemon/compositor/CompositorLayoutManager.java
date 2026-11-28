package io.github.lengsukq.opaldesk.daemon.compositor;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.WindowSnapGeometry;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Pure JVM window frame, snap/maximize/fullscreen/orientation layout, DPI spec, and Z-order
 * focus manager for {@link CompositorEngine}. All methods assume the caller holds the engine lock.
 */
final class CompositorLayoutManager {
    private final DaemonConfig config;
    private final VirtualDisplayPool vdPool;
    private final CompositorEngine.AppRuntimeBridge runtimeBridge;
    private int nextZOrder = 1;

    CompositorLayoutManager(
            DaemonConfig config,
            VirtualDisplayPool vdPool,
            CompositorEngine.AppRuntimeBridge runtimeBridge) {
        this.config = config;
        this.vdPool = vdPool;
        this.runtimeBridge = runtimeBridge;
    }

    int[] computeWindowSpecLocked(IntRect frame, boolean fullscreen, WindowProfile profile, String pkg) {
        return DisplayGeometryUtil.resolveWindowBufferSpec(
                frame,
                config.getCanvasDpi(),
                fullscreen,
                profile,
                config.getWindowDpiScalePct(),
                runtimeBridge.getHostDisplayDpi(),
                config.resolveEffectiveCustomDpi(pkg),
                config.getWindowSupersampleMode());
    }

    boolean reapplyWindowDpiScaleLocked(Collection<WindowSession> sessions) {
        int dpiPct = config.getWindowDpiScalePct();
        boolean changed = false;
        for (WindowSession ws : sessions) {
            boolean isFs = BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation);
            int[] spec = computeWindowSpecLocked(ws.frame, isFs, ws.profile, ws.pkg);
            if (spec[0] != ws.contentWidth || spec[1] != ws.contentHeight || spec[2] != ws.contentDpi) {
                ws.contentWidth = spec[0];
                ws.contentHeight = spec[1];
                ws.contentDpi = spec[2];
                vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
                System.out.println("OPALDESK_WINDOW_DPI_REAPPLIED wid=" + ws.windowId
                        + " pkg=" + ws.pkg + " dpiPct=" + dpiPct
                        + " customDpi=" + config.resolveEffectiveCustomDpi(ws.pkg)
                        + " buf=" + spec[0] + "x" + spec[1] + "@" + spec[2] + "dpi");
                changed = true;
            }
        }
        return changed;
    }

    boolean reapplyAppDpiLocked(Collection<WindowSession> sessions, String pkg) {
        boolean changed = false;
        for (WindowSession ws : sessions) {
            if (!pkg.equals(ws.pkg)) continue;
            boolean isFs = BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation);
            int[] spec = computeWindowSpecLocked(ws.frame, isFs, ws.profile, ws.pkg);
            if (spec[0] != ws.contentWidth || spec[1] != ws.contentHeight || spec[2] != ws.contentDpi) {
                ws.contentWidth = spec[0];
                ws.contentHeight = spec[1];
                ws.contentDpi = spec[2];
                vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
                System.out.println("OPALDESK_APP_DPI_REAPPLIED wid=" + ws.windowId
                        + " pkg=" + ws.pkg + " customDpi=" + config.resolveEffectiveCustomDpi(ws.pkg)
                        + " buf=" + spec[0] + "x" + spec[1] + "@" + spec[2] + "dpi");
                changed = true;
            }
        }
        return changed;
    }

    void applyFrameUpdateLocked(
            Collection<WindowSession> sessions,
            WindowSession ws,
            int left,
            int top,
            int right,
            int bottom,
            String presentation) {
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();

        String nextPres = (presentation != null && !presentation.isEmpty())
                ? presentation : BridgeProtocol.PRES_FLOATING;
        if (BridgeProtocol.PRES_FLOATING.equals(ws.presentation)
                && !BridgeProtocol.PRES_FLOATING.equals(nextPres)) {
            ws.restoreFrame = ws.frame;
        }

        IntRect nextFrame;
        if (!BridgeProtocol.PRES_FLOATING.equals(nextPres)) {
            nextFrame = DisplayGeometryUtil.computeSnapFrame(nextPres, canvasW, canvasH, canvasDpi);
        } else {
            nextFrame = DisplayGeometryUtil.clampWindowFrame(
                    new IntRect(left, top, right, bottom), canvasW, canvasH, canvasDpi);
            ws.restoreFrame = nextFrame;
        }

        boolean wasFullscreen = BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation);
        boolean nextFullscreen = BridgeProtocol.PRES_FULLSCREEN.equals(nextPres);
        boolean sizeChanged = nextFrame.width() != ws.frame.width()
                || nextFrame.height() != ws.frame.height()
                || wasFullscreen != nextFullscreen;
        ws.frame = nextFrame;
        ws.presentation = nextPres;
        focusWindowLocked(sessions, ws, System.currentTimeMillis());

        if (sizeChanged) {
            int[] spec = computeWindowSpecLocked(nextFrame, nextFullscreen, ws.profile, ws.pkg);
            ws.contentWidth = spec[0];
            ws.contentHeight = spec[1];
            ws.contentDpi = spec[2];
            vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
        }
        rememberWindowBoundsLocked(ws);
    }

    void applyToggleMaximizeLocked(Collection<WindowSession> sessions, WindowSession ws, long now) {
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();
        if (BridgeProtocol.PRES_MAXIMIZED.equals(ws.presentation)
                || BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)) {
            ws.presentation = BridgeProtocol.PRES_FLOATING;
            ws.frame = (ws.restoreFrame != null && !ws.restoreFrame.isEmpty())
                    ? DisplayGeometryUtil.clampWindowFrame(ws.restoreFrame, canvasW, canvasH, canvasDpi)
                    : DisplayGeometryUtil.computeInitialWindowFrame(ws.profile, 0, canvasW, canvasH, canvasDpi);
        } else {
            if (BridgeProtocol.PRES_FLOATING.equals(ws.presentation)) {
                ws.restoreFrame = ws.frame;
            }
            ws.presentation = BridgeProtocol.PRES_MAXIMIZED;
            ws.frame = DisplayGeometryUtil.computeSnapFrame(
                    BridgeProtocol.PRES_MAXIMIZED, canvasW, canvasH, canvasDpi);
        }
        int[] spec = computeWindowSpecLocked(ws.frame, false, ws.profile, ws.pkg);
        ws.contentWidth = spec[0];
        ws.contentHeight = spec[1];
        ws.contentDpi = spec[2];
        vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
        rememberWindowBoundsLocked(ws);
        focusWindowLocked(sessions, ws, now);
    }

    void applyToggleFullscreenLocked(Collection<WindowSession> sessions, WindowSession ws, long now) {
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();
        boolean enterFs = !BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation);
        if (enterFs) {
            ws.preFullscreenFrame = ws.frame;
            ws.preFullscreenPresentation = ws.presentation;
            if (BridgeProtocol.PRES_FLOATING.equals(ws.presentation)) {
                ws.restoreFrame = ws.frame;
            }
            ws.presentation = BridgeProtocol.PRES_FULLSCREEN;
            ws.frame = DisplayGeometryUtil.computeSnapFrame(
                    BridgeProtocol.PRES_FULLSCREEN, canvasW, canvasH, canvasDpi);
        } else {
            restoreFromFullscreenLocked(ws, canvasW, canvasH, canvasDpi);
        }
        int[] spec = computeWindowSpecLocked(ws.frame, enterFs, ws.profile, ws.pkg);
        ws.contentWidth = spec[0];
        ws.contentHeight = spec[1];
        ws.contentDpi = spec[2];
        vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
        focusWindowLocked(sessions, ws, now);
    }

    void applyToggleOrientationLocked(Collection<WindowSession> sessions, WindowSession ws, long now) {
        boolean currentlyPortrait = ws.contentHeight > ws.contentWidth;
        boolean toPortrait = !currentlyPortrait;
        WindowProfile nextProfile = toPortrait ? WindowProfile.PORTRAIT : WindowProfile.DESKTOP;
        ws.profile = nextProfile;
        config.rememberProfile(ws.pkg, nextProfile);
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();
        ws.presentation = BridgeProtocol.PRES_FLOATING;
        ws.frame = computeRotatedWindowFrameLocked(ws.frame, toPortrait, canvasW, canvasH, canvasDpi);
        ws.restoreFrame = ws.frame;
        int[] spec = computeWindowSpecLocked(ws.frame, false, nextProfile, ws.pkg);
        ws.contentWidth = spec[0];
        ws.contentHeight = spec[1];
        ws.contentDpi = spec[2];
        vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
        rememberWindowBoundsLocked(ws);
        focusWindowLocked(sessions, ws, now);
    }

    void applyProfileChangeLocked(WindowSession ws, WindowProfile nextProfile) {
        ws.profile = nextProfile;
        config.rememberProfile(ws.pkg, nextProfile);
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();
        ws.presentation = BridgeProtocol.PRES_FLOATING;
        ws.frame = DisplayGeometryUtil.computeInitialWindowFrame(nextProfile, 0, canvasW, canvasH, canvasDpi);
        ws.restoreFrame = ws.frame;
        int[] spec = computeWindowSpecLocked(ws.frame, false, nextProfile, ws.pkg);
        ws.contentWidth = spec[0];
        ws.contentHeight = spec[1];
        ws.contentDpi = spec[2];
        vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
    }

    boolean exitFullscreenIfActiveLocked(Collection<WindowSession> sessions) {
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();
        long now = System.currentTimeMillis();
        boolean restoredAny = false;
        for (WindowSession ws : sessions) {
            if (ws.isMinimized() || !BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)) continue;
            restoreFromFullscreenLocked(ws, canvasW, canvasH, canvasDpi);
            int[] spec = computeWindowSpecLocked(ws.frame, false, ws.profile, ws.pkg);
            ws.contentWidth = spec[0];
            ws.contentHeight = spec[1];
            ws.contentDpi = spec[2];
            vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
            focusWindowLocked(sessions, ws, now);
            restoredAny = true;
        }
        return restoredAny;
    }

    void restoreFromFullscreenLocked(WindowSession ws, int canvasW, int canvasH, int canvasDpi) {
        String prevPres = (ws.preFullscreenPresentation != null
                && !ws.preFullscreenPresentation.isEmpty()
                && !BridgeProtocol.PRES_FULLSCREEN.equals(ws.preFullscreenPresentation))
                ? ws.preFullscreenPresentation : BridgeProtocol.PRES_FLOATING;
        ws.presentation = prevPres;
        if (!BridgeProtocol.PRES_FLOATING.equals(prevPres)) {
            ws.frame = DisplayGeometryUtil.computeSnapFrame(prevPres, canvasW, canvasH, canvasDpi);
        } else {
            IntRect target = (ws.preFullscreenFrame != null && !ws.preFullscreenFrame.isEmpty())
                    ? ws.preFullscreenFrame : ws.restoreFrame;
            ws.frame = (target != null && !target.isEmpty())
                    ? DisplayGeometryUtil.clampWindowFrame(target, canvasW, canvasH, canvasDpi)
                    : DisplayGeometryUtil.computeInitialWindowFrame(ws.profile, 0, canvasW, canvasH, canvasDpi);
        }
        ws.preFullscreenFrame = null;
        ws.preFullscreenPresentation = null;
    }

    void restoreFullscreenBeforeMinimizeLocked(WindowSession ws) {
        if (!BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)) return;
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();
        ws.presentation = BridgeProtocol.PRES_FLOATING;
        ws.frame = (ws.restoreFrame != null && !ws.restoreFrame.isEmpty())
                ? DisplayGeometryUtil.clampWindowFrame(ws.restoreFrame, canvasW, canvasH, canvasDpi)
                : DisplayGeometryUtil.computeInitialWindowFrame(ws.profile, 0, canvasW, canvasH, canvasDpi);
        int[] spec = computeWindowSpecLocked(ws.frame, false, ws.profile, ws.pkg);
        ws.contentWidth = spec[0];
        ws.contentHeight = spec[1];
        ws.contentDpi = spec[2];
    }

    void rememberWindowBoundsLocked(WindowSession ws) {
        if (ws == null) return;
        IntRect rectToSave = BridgeProtocol.PRES_FLOATING.equals(ws.presentation)
                ? ws.frame
                : (ws.restoreFrame != null && !ws.restoreFrame.isEmpty() ? ws.restoreFrame : ws.frame);
        String presToSave = BridgeProtocol.PRES_FULLSCREEN.equals(ws.presentation)
                ? BridgeProtocol.PRES_FLOATING : ws.presentation;
        config.rememberWindowBounds(
                ws.pkg, rectToSave, config.getCanvasWidth(), config.getCanvasHeight(), presToSave);
    }

    private IntRect computeRotatedWindowFrameLocked(
            IntRect currentFrame, boolean toPortrait, int canvasW, int canvasH, int canvasDpi) {
        int cw = Math.max(360, canvasW);
        int ch = Math.max(360, canvasH);
        int topSafe = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.TOP_BAR_HEIGHT_DP, canvasDpi);
        int bottomSafe = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.DOCK_RESERVED_BOTTOM_DP, canvasDpi);
        int usableH = Math.max(260, ch - topSafe - bottomSafe - 16);
        int usableW = Math.max(300, cw - 24);
        int targetW, targetH;
        if (toPortrait) {
            targetH = Math.min(usableH, Math.round(usableH * 0.96f));
            targetW = Math.round(targetH * 9f / 16f);
            if (targetW > usableW) {
                targetW = usableW;
                targetH = Math.min(usableH, Math.round(targetW * 16f / 9f));
            }
            if (targetW < 340) targetW = Math.min(usableW, 340);
        } else {
            targetW = Math.min(usableW, Math.round(usableW * 0.78f));
            targetH = Math.min(usableH, Math.round(targetW * 10f / 16f));
            if (targetH > usableH) {
                targetH = usableH;
                targetW = Math.min(usableW, Math.round(targetH * 16f / 10f));
            }
        }
        int cx = (currentFrame != null && !currentFrame.isEmpty())
                ? (currentFrame.left + currentFrame.right) / 2 : cw / 2;
        int cy = (currentFrame != null && !currentFrame.isEmpty())
                ? (currentFrame.top + currentFrame.bottom) / 2 : topSafe + usableH / 2;
        int left = cx - targetW / 2;
        int top = cy - targetH / 2;
        return DisplayGeometryUtil.clampWindowFrame(
                new IntRect(left, top, left + targetW, top + targetH), cw, ch, canvasDpi);
    }

    void focusWindowLocked(Collection<WindowSession> sessions, WindowSession target, long nowMs) {
        int maxZ = Integer.MIN_VALUE;
        for (WindowSession ws : sessions) {
            if (ws.zOrder > maxZ) maxZ = ws.zOrder;
            ws.focused = (ws == target);
        }
        boolean alreadyStrictlyTop = target.zOrder > 0 && target.zOrder >= maxZ;
        if (alreadyStrictlyTop) {
            for (WindowSession ws : sessions) {
                if (ws != target && ws.zOrder >= target.zOrder) {
                    alreadyStrictlyTop = false;
                    break;
                }
            }
        }
        if (!alreadyStrictlyTop) {
            target.zOrder = nextZOrder++;
        }
        target.lastActiveAtMs = nowMs;
    }

    void promoteTopVisibleFocusLocked(Collection<WindowSession> sessions, long nowMs) {
        WindowSession top = null;
        for (WindowSession ws : sessions) {
            if (ws.isMinimized()) {
                ws.focused = false;
                continue;
            }
            if (top == null || ws.zOrder > top.zOrder) top = ws;
        }
        for (WindowSession ws : sessions) {
            ws.focused = (top != null && ws == top);
        }
        if (top != null) top.lastActiveAtMs = nowMs;
    }

    boolean snapFocusedWindowLocked(Collection<WindowSession> sessions, boolean leftHalf, long nowMs) {
        WindowSession target = resolveFocusedOrTopVisibleLocked(sessions);
        if (target == null) return false;
        String pres = leftHalf ? BridgeProtocol.PRES_SNAP_LEFT : BridgeProtocol.PRES_SNAP_RIGHT;
        applySnapPresetLocked(target, pres);
        focusWindowLocked(sessions, target, nowMs);
        return true;
    }

    boolean applyQuickSplitTwoLocked(Collection<WindowSession> sessions, long nowMs) {
        List<WindowSession> visible = collectVisibleWindowsLocked(sessions);
        if (visible.isEmpty()) return false;
        visible.sort((a, b) -> {
            if (a.focused != b.focused) return a.focused ? -1 : 1;
            int zCmp = Integer.compare(b.zOrder, a.zOrder);
            return (zCmp != 0) ? zCmp : Integer.compare(b.windowId, a.windowId);
        });
        WindowSession primary = visible.get(0);
        applySnapPresetLocked(primary, BridgeProtocol.PRES_SNAP_LEFT);
        if (visible.size() >= 2) {
            WindowSession secondary = visible.get(1);
            applySnapPresetLocked(secondary, BridgeProtocol.PRES_SNAP_RIGHT);
            focusWindowLocked(sessions, secondary, nowMs);
        }
        focusWindowLocked(sessions, primary, nowMs);
        return true;
    }

    boolean switchNextWindowLocked(Collection<WindowSession> sessions, long nowMs) {
        List<WindowSession> visible = collectVisibleWindowsLocked(sessions);
        if (visible.isEmpty()) return false;
        visible.sort(Comparator.comparingInt(w -> w.windowId));
        WindowSession current = resolveFocusedOrTopVisibleLocked(sessions);
        int nextIdx = 0;
        if (current != null && visible.size() > 1) {
            int curIdx = visible.indexOf(current);
            if (curIdx >= 0) {
                nextIdx = (curIdx + 1) % visible.size();
            }
        }
        WindowSession next = visible.get(nextIdx);
        if (next.isHibernated()) {
            next.state = BridgeProtocol.STATE_LIVE;
        }
        focusWindowLocked(sessions, next, nowMs);
        return true;
    }

    private void applySnapPresetLocked(WindowSession ws, String snapPresentation) {
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();
        if (BridgeProtocol.PRES_FLOATING.equals(ws.presentation)) {
            ws.restoreFrame = ws.frame;
        }
        ws.presentation = snapPresentation;
        ws.frame = WindowSnapGeometry.computeSnapFrame(snapPresentation, canvasW, canvasH, canvasDpi);
        int[] spec = computeWindowSpecLocked(ws.frame, false, ws.profile, ws.pkg);
        ws.contentWidth = spec[0];
        ws.contentHeight = spec[1];
        ws.contentDpi = spec[2];
        vdPool.resizeIfActive(ws.windowId, spec[0], spec[1], spec[2]);
        rememberWindowBoundsLocked(ws);
    }

    private static List<WindowSession> collectVisibleWindowsLocked(Collection<WindowSession> sessions) {
        List<WindowSession> visible = new ArrayList<>();
        for (WindowSession ws : sessions) {
            if (ws != null && !ws.isMinimized()) visible.add(ws);
        }
        return visible;
    }

    private static WindowSession resolveFocusedOrTopVisibleLocked(Collection<WindowSession> sessions) {
        WindowSession top = null;
        for (WindowSession ws : sessions) {
            if (ws == null || ws.isMinimized()) continue;
            if (ws.focused) return ws;
            if (top == null || ws.zOrder > top.zOrder) top = ws;
        }
        return top;
    }
}
