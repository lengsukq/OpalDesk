package io.github.lengsukq.opaldesk.daemon.input;

import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Daemon-level physical mouse & keyboard display router ({@code uid=2000},
 * {@code ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}). Delegates low-level InputDevice discovery and
 * AOSP UniqueId/Port association binding to {@link ExternalMouseEventSynthesizer}.
 */
public final class ExternalMouseRouter {
    /** Snapshot of the currently focused live {@code OpalWin-*} VirtualDisplay target. */
    public static final class FocusedWindowTarget {
        public final int displayId;
        public final String vdUniqueId;

        public FocusedWindowTarget(int displayId, String vdUniqueId) {
            this.displayId = displayId;
            this.vdUniqueId = vdUniqueId;
        }
    }

    /** Supplies the latest focused live {@code OpalWin-*} target before peripheral resyncs. */
    public interface FocusedWindowTargetProvider {
        FocusedWindowTarget getFocusedWindowTarget();
    }

    /** Callback notified whenever a physical Bluetooth/USB alphabetic keyboard connects or disconnects. */
    public interface PhysicalKeyboardListener {
        void onPhysicalKeyboardPresenceChanged(boolean connected);
    }

    /** Callback notified whenever direct physical-keyboard routing to an {@code OpalWin-*} changes. */
    public interface KeyboardRoutingListener {
        void onKeyboardRoutedDisplayChanged(int routedDisplayId);
    }

    private final AndroidServices services;
    private final DaemonConfig config;
    private final Handler mainHandler;
    private final ExternalMouseEventSynthesizer synthesizer;
    private final Object lock = new Object();
    private final Map<String, ExternalMouseEventSynthesizer.BoundMouse> boundByKey = new HashMap<>();

    private volatile int activeExternalDisplayId = -1;
    private volatile String activeDisplayUniqueId = null;
    private volatile int activeDisplayPort = -1;
    private volatile int observedExternalDisplayId = -1;
    private volatile String observedExternalDisplayUniqueId = null;
    private volatile int focusedWindowDisplayId = -1;
    private volatile String focusedWindowUniqueId = null;
    private volatile int keyboardRoutedDisplayId = -1;
    private volatile boolean comboKeyboardBoundToOuter = false;
    private volatile boolean bridgeImeActive = false;
    private volatile boolean hasEnabledKeyBindings = false;
    private volatile boolean localImeActive = false;
    private volatile boolean remoteDesktopActive = false;
    private int imePolicyOverriddenDisplayId = -1;
    private int imePolicyOriginalValue = -1;
    private volatile boolean physicalKeyboardConnected = false;
    private volatile boolean externalTouchscreenConnected = false;
    private volatile PhysicalKeyboardListener physicalKeyboardListener;
    private volatile Runnable externalImeContextListener;
    private volatile KeyboardRoutingListener keyboardRoutingListener;
    private volatile FocusedWindowTargetProvider focusedWindowTargetProvider;
    private volatile boolean localDesktopPinned = false;
    private volatile boolean listenersRegistered = false;

    private PlatformListeners platformListeners;

    public ExternalMouseRouter(AndroidServices services, DaemonConfig config, Handler mainHandler) {
        this(services, config, mainHandler, new ExternalMouseEventSynthesizer(services));
    }

    ExternalMouseRouter(
            AndroidServices services,
            DaemonConfig config,
            Handler mainHandler,
            ExternalMouseEventSynthesizer synthesizer) {
        this.services = services;
        this.config = config;
        this.mainHandler = mainHandler;
        this.synthesizer = synthesizer;
    }

    public void setPhysicalKeyboardListener(PhysicalKeyboardListener listener) { this.physicalKeyboardListener = listener; }
    public void setExternalImeContextListener(Runnable listener) { this.externalImeContextListener = listener; }
    public void setKeyboardRoutingListener(KeyboardRoutingListener listener) { this.keyboardRoutingListener = listener; }
    public void setFocusedWindowTargetProvider(FocusedWindowTargetProvider provider) { this.focusedWindowTargetProvider = provider; }

    public boolean hasPhysicalKeyboard() { return physicalKeyboardConnected; }
    public boolean hasExternalTouchscreen() { return externalTouchscreenConnected; }

    public boolean hasPhysicalExternalDisplay() {
        int extId = observedExternalDisplayId;
        String extUid = observedExternalDisplayUniqueId;
        return extId > 0 && (extUid == null || !extUid.startsWith("virtual:"));
    }

    public int getKeyboardRoutedDisplayId() { return keyboardRoutedDisplayId; }
    public boolean hasComboKeyboardBoundToOuterDisplay() { return comboKeyboardBoundToOuter; }

    public void setBridgeImeActive(boolean active) {
        boolean changed;
        synchronized (lock) {
            changed = (bridgeImeActive != active);
            bridgeImeActive = active;
        }
        if (changed) resyncBindings();
    }

    public void setHasEnabledKeyBindings(boolean active) {
        boolean changed;
        synchronized (lock) {
            changed = (hasEnabledKeyBindings != active);
            hasEnabledKeyBindings = active;
        }
        if (changed) resyncBindings();
    }

    public void start() {
        if (listenersRegistered || services == null) return;
        listenersRegistered = true;
        synchronized (lock) {
            if (activeExternalDisplayId <= 0 && !localDesktopPinned) {
                synthesizer.sweepClearConnectedDeviceAssociations();
            }
        }
        refreshPhysicalKeyboardState(false);
        platformListeners = new PlatformListeners(this, services, mainHandler);
        platformListeners.register();
    }

    private void onExternalDisplayRemoved(int displayId) {
        if (displayId > 0 && (displayId == activeExternalDisplayId || displayId == observedExternalDisplayId)) {
            System.out.println("OPALDESK_MOUSE_EXT_DISPLAY_REMOVED id=" + displayId + " -> returning mouse to Display 0");
            boolean prevPhysicalExt = hasPhysicalExternalDisplay();
            synchronized (lock) {
                if (displayId == activeExternalDisplayId) {
                    activeExternalDisplayId = -1;
                    activeDisplayUniqueId = null;
                    activeDisplayPort = -1;
                }
                if (displayId == observedExternalDisplayId) {
                    observedExternalDisplayId = -1;
                    observedExternalDisplayUniqueId = null;
                }
                if (displayId == imePolicyOverriddenDisplayId) {
                    imePolicyOverriddenDisplayId = -1;
                    imePolicyOriginalValue = -1;
                }
            }
            resyncBindings();
            if (prevPhysicalExt != hasPhysicalExternalDisplay()) notifyExternalImeContextChanged();
            if (!localDesktopPinned) services.restoreDefaultDisplayTopFocus();
        }
    }

    private void onInputTopologyChanged() {
        refreshFocusedWindowTargetFromProvider();
        refreshPhysicalKeyboardState(true);
        resyncBindings();
    }

    public void shutdown() {
        localDesktopPinned = false;
        remoteDesktopActive = false;
        unbindAllInternal(true, true);
        if (services != null && !remoteDesktopActive) {
            services.restoreDefaultDisplayTopFocus();
        }
        if (!listenersRegistered || services == null) return;
        listenersRegistered = false;
        if (platformListeners != null) {
            platformListeners.unregister();
            platformListeners = null;
        }
    }

    private static final class PlatformListeners {
        private final ExternalMouseRouter owner;
        private final AndroidServices services;
        private final Handler mainHandler;
        private InputManager.InputDeviceListener inputListener;
        private DisplayManager.DisplayListener displayListener;

        PlatformListeners(ExternalMouseRouter owner, AndroidServices services, Handler mainHandler) {
            this.owner = owner;
            this.services = services;
            this.mainHandler = mainHandler;
        }

        void register() {
            if (services.inputManager != null) {
                inputListener = new InputManager.InputDeviceListener() {
                    @Override public void onInputDeviceAdded(int deviceId) { owner.onInputTopologyChanged(); }
                    @Override public void onInputDeviceRemoved(int deviceId) { owner.onInputTopologyChanged(); }
                    @Override public void onInputDeviceChanged(int deviceId) { owner.onInputTopologyChanged(); }
                };
                try {
                    services.inputManager.registerInputDeviceListener(inputListener, mainHandler);
                } catch (RuntimeException e) {
                    System.err.println("OPALDESK_MOUSE_LISTENER_WARN: " + e.getMessage());
                }
            }
            if (services.displayManager != null) {
                displayListener = new DisplayManager.DisplayListener() {
                    @Override public void onDisplayAdded(int displayId) {}
                    @Override public void onDisplayChanged(int displayId) {}
                    @Override public void onDisplayRemoved(int displayId) { owner.onExternalDisplayRemoved(displayId); }
                };
                try {
                    services.displayManager.registerDisplayListener(displayListener, mainHandler);
                } catch (RuntimeException e) {
                    System.err.println("OPALDESK_MOUSE_DISP_LISTENER_WARN: " + e.getMessage());
                }
            }
        }

        void unregister() {
            if (services.inputManager != null && inputListener != null) {
                try { services.inputManager.unregisterInputDeviceListener(inputListener); } catch (RuntimeException ignored) {}
            }
            if (services.displayManager != null && displayListener != null) {
                try { services.displayManager.unregisterDisplayListener(displayListener); } catch (RuntimeException ignored) {}
            }
        }
    }

    public int setMouseTarget(int targetMode, int externalDisplayId) {
        if (targetMode == BridgeProtocol.MOUSE_TARGET_PIN_LOCAL_DISPLAY0) {
            localDesktopPinned = true;
            refreshFocusedWindowTargetFromProvider();
            refreshPhysicalKeyboardState(true);
            return resyncBindings(true);
        }
        if (targetMode == BridgeProtocol.MOUSE_TARGET_UNPIN_LOCAL_DISPLAY0) {
            localDesktopPinned = false;
            int bound = resyncBindings(true);
            if (services != null && MouseRoutingPolicy.shouldRestoreDefaultDisplayFocusOnLocalExit(
                    false, getBoundDisplayId(), false)) {
                services.restoreDefaultDisplayTopFocus();
            }
            return bound;
        }
        if (targetMode == BridgeProtocol.MOUSE_TARGET_RELEASE_EXTERNAL) {
            releaseToLocal();
            return 0;
        }

        config.setMouseTargetMode(targetMode);
        String uniqueId = (externalDisplayId > 0) ? synthesizer.getDisplayUniqueId(externalDisplayId) : null;
        int physPort = (externalDisplayId > 0) ? synthesizer.getDisplayPhysicalPort(externalDisplayId) : -1;
        boolean prevPhysicalExt = hasPhysicalExternalDisplay();

        synchronized (lock) {
            if (externalDisplayId > 0) {
                observedExternalDisplayId = externalDisplayId;
                observedExternalDisplayUniqueId = uniqueId;
            } else {
                observedExternalDisplayId = -1;
                observedExternalDisplayUniqueId = null;
            }
        }
        refreshFocusedWindowTargetFromProvider();
        refreshPhysicalKeyboardState(true);

        if (targetMode != BridgeProtocol.MOUSE_TARGET_EXTERNAL || externalDisplayId <= 0) {
            synchronized (lock) {
                activeExternalDisplayId = -1;
                activeDisplayUniqueId = null;
                activeDisplayPort = -1;
                syncExternalDisplayImePolicyLocked();
            }
            int bound = resyncBindings(true);
            if (prevPhysicalExt != hasPhysicalExternalDisplay()) notifyExternalImeContextChanged();
            if (services != null && !localDesktopPinned) services.restoreDefaultDisplayTopFocus();
            return bound;
        }
        if ((uniqueId == null || uniqueId.isEmpty()) && physPort < 0) {
            System.err.println("OPALDESK_MOUSE_ROUTE_SKIP no uniqueId/port for displayId=" + externalDisplayId);
            synchronized (lock) {
                activeExternalDisplayId = -1;
                activeDisplayUniqueId = null;
                activeDisplayPort = -1;
                syncExternalDisplayImePolicyLocked();
            }
            int bound = resyncBindings(true);
            if (prevPhysicalExt != hasPhysicalExternalDisplay()) notifyExternalImeContextChanged();
            return bound;
        }
        synchronized (lock) {
            activeExternalDisplayId = externalDisplayId;
            activeDisplayUniqueId = uniqueId;
            activeDisplayPort = physPort;
            syncExternalDisplayImePolicyLocked();
        }
        int bound = resyncBindings(true);
        if (prevPhysicalExt != hasPhysicalExternalDisplay()) notifyExternalImeContextChanged();
        return bound;
    }

    public void releaseToLocal() {
        boolean prevPhysicalExt = hasPhysicalExternalDisplay();
        localDesktopPinned = false;
        synchronized (lock) {
            activeExternalDisplayId = -1;
            activeDisplayUniqueId = null;
            activeDisplayPort = -1;
            observedExternalDisplayId = -1;
            observedExternalDisplayUniqueId = null;
            focusedWindowDisplayId = -1;
            focusedWindowUniqueId = null;
            localImeActive = false;
            restoreExternalDisplayImePolicyLocked();
        }
        resyncBindings();
        if (prevPhysicalExt != hasPhysicalExternalDisplay()) notifyExternalImeContextChanged();
        if (services != null && !remoteDesktopActive) services.restoreDefaultDisplayTopFocus();
    }

    public void setFocusedWindowTarget(int displayId, String vdUniqueId) {
        int nextId = (displayId > 0 && vdUniqueId != null && !vdUniqueId.isEmpty()) ? displayId : -1;
        String nextUid = (nextId > 0) ? vdUniqueId : null;
        boolean changed;
        synchronized (lock) {
            changed = (focusedWindowDisplayId != nextId)
                    || (nextUid == null ? focusedWindowUniqueId != null : !nextUid.equals(focusedWindowUniqueId));
            focusedWindowDisplayId = nextId;
            focusedWindowUniqueId = nextUid;
        }
        if (changed) resyncBindings();
    }

    public void setLocalImeActive(boolean active) {
        boolean changed;
        synchronized (lock) {
            changed = (localImeActive != active);
            localImeActive = active;
        }
        if (changed) resyncBindings();
    }

    public void setRemoteDesktopActive(boolean active) {
        boolean changed;
        synchronized (lock) {
            changed = (remoteDesktopActive != active);
            remoteDesktopActive = active;
        }
        if (changed) resyncBindings();
    }

    public boolean isLocalDesktopPinned() { return localDesktopPinned; }

    public int getBoundMouseCount() {
        synchronized (lock) {
            int extCount = 0;
            for (ExternalMouseEventSynthesizer.BoundMouse bm : boundByKey.values()) {
                if (bm.isMouse && bm.targetDisplayId > 0) extCount++;
            }
            return extCount;
        }
    }

    public int getBoundDisplayId() {
        synchronized (lock) {
            for (ExternalMouseEventSynthesizer.BoundMouse bm : boundByKey.values()) {
                if (bm.isMouse && bm.targetDisplayId > 0) return bm.targetDisplayId;
            }
            return -1;
        }
    }

    public boolean isHostMouseDisplayAnchored() {
        synchronized (lock) {
            if (localDesktopPinned || activeExternalDisplayId > 0 || keyboardRoutedDisplayId > 0) return true;
            for (ExternalMouseEventSynthesizer.BoundMouse bm : boundByKey.values()) {
                if (bm.isMouse && bm.targetDisplayId >= 0) return true;
            }
            return false;
        }
    }

    private void refreshFocusedWindowTargetFromProvider() {
        FocusedWindowTargetProvider provider = this.focusedWindowTargetProvider;
        if (provider == null) return;
        FocusedWindowTarget target = null;
        try { target = provider.getFocusedWindowTarget(); } catch (RuntimeException ignored) {}
        int nextId = (target != null && target.displayId > 0
                && target.vdUniqueId != null && !target.vdUniqueId.isEmpty()) ? target.displayId : -1;
        String nextUid = (nextId > 0) ? target.vdUniqueId : null;
        synchronized (lock) {
            focusedWindowDisplayId = nextId;
            focusedWindowUniqueId = nextUid;
        }
    }

    public int getConnectedMouseCount() { return synthesizer.getConnectedMouseCount(); }

    public boolean applyHardwarePointerSpeed(float sensitivity) {
        if (services == null) return false;
        return services.setHardwarePointerSpeed(mapSensitivityToAndroidPointerSpeed(sensitivity));
    }

    public static boolean shouldRouteDevice(String name, int sources, boolean isVirtual, boolean isExternal) {
        return MouseRoutingPolicy.shouldRouteDevice(name, sources, isVirtual, isExternal);
    }

    public static boolean shouldRouteDevice(
            String name, int sources, int keyboardType, boolean isVirtual, boolean isExternal) {
        return MouseRoutingPolicy.shouldRouteDevice(name, sources, keyboardType, isVirtual, isExternal);
    }

    public static int mapSensitivityToAndroidPointerSpeed(float sensitivity) {
        return MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(sensitivity);
    }

    private int resyncBindings() {
        return resyncBindings(false);
    }

    private int resyncBindings(boolean forceRebind) {
        if (!synthesizer.isReady()) return 0;
        int prevKbdRouted, nextKbdRouted, boundMouseCount;
        boolean prevComboOuter, nextComboOuter;
        synchronized (lock) {
            prevKbdRouted = keyboardRoutedDisplayId;
            prevComboOuter = comboKeyboardBoundToOuter;
            int outerDisplayId = -1;
            String outerUniqueId = null;
            int outerPort = -1;

            boolean routeExternal = MouseRoutingPolicy.shouldRoutePhysicalPeripheralsToDisplay(
                    config.getMouseTargetMode(), activeExternalDisplayId, activeDisplayUniqueId, activeDisplayPort);
            boolean pinDisplay0 = !routeExternal && MouseRoutingPolicy.shouldPinPhysicalMiceToDisplay0(
                    localDesktopPinned, remoteDesktopActive, observedExternalDisplayId > 0, config.getMouseTargetMode());
            if (routeExternal) {
                outerDisplayId = activeExternalDisplayId;
                outerUniqueId = activeDisplayUniqueId;
                outerPort = activeDisplayPort;
            } else if (pinDisplay0) {
                String d0Uid = synthesizer.getDisplayUniqueId(android.view.Display.DEFAULT_DISPLAY);
                int d0Port = synthesizer.getDisplayPhysicalPort(android.view.Display.DEFAULT_DISPLAY);
                if ((d0Uid != null && !d0Uid.isEmpty()) || d0Port >= 0) {
                    outerDisplayId = android.view.Display.DEFAULT_DISPLAY;
                    outerUniqueId = d0Uid;
                    outerPort = d0Port;
                }
            }

            int dexImeMode = (config != null) ? config.getDexImeDisplayMode() : BridgeProtocol.DEX_IME_DISPLAY_AUTO;
            boolean routeKbdToWindow = MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                    routeExternal || localDesktopPinned, remoteDesktopActive, localImeActive,
                    dexImeMode, focusedWindowDisplayId,
                    focusedWindowUniqueId != null && !focusedWindowUniqueId.isEmpty(),
                    bridgeImeActive, hasEnabledKeyBindings);

            if (outerDisplayId < 0 && !routeKbdToWindow) {
                unbindLocked();
                nextKbdRouted = keyboardRoutedDisplayId;
                nextComboOuter = comboKeyboardBoundToOuter;
                boundMouseCount = 0;
            } else {
                ExternalMouseEventSynthesizer.CandidateScanResult scan;
                try {
                    scan = synthesizer.scanCandidateDevices();
                } catch (RuntimeException e) {
                    return getBoundMouseCount();
                }
                Set<String> stillPresentKeys = new HashSet<>();
                boolean sawComboKbdOnOuter = false;
                for (Map.Entry<String, ExternalMouseEventSynthesizer.CandidateDevice> entry : scan.candidatesByKey.entrySet()) {
                    String key = entry.getKey();
                    ExternalMouseEventSynthesizer.CandidateDevice cand = entry.getValue();
                    int desiredDisplayId, desiredPort;
                    String desiredUniqueId;
                    boolean boundAsMouse, boundAsWindowKbd;
                    if (cand.isWindowKbd && routeKbdToWindow) {
                        desiredDisplayId = focusedWindowDisplayId;
                        desiredUniqueId = focusedWindowUniqueId;
                        desiredPort = -1;
                        boundAsMouse = false;
                        boundAsWindowKbd = true;
                    } else if (MouseRoutingPolicy.shouldAssociateDeviceToOuterDisplay(
                            cand.isMouse, cand.isWindowKbd, cand.isExternal, cand.keyboardType,
                            bridgeImeActive, hasEnabledKeyBindings)) {
                        if (outerDisplayId < 0) continue;
                        desiredDisplayId = outerDisplayId;
                        desiredUniqueId = outerUniqueId;
                        desiredPort = outerPort;
                        boundAsMouse = cand.isMouse;
                        boundAsWindowKbd = false;
                    } else {
                        continue;
                    }

                    stillPresentKeys.add(key);
                    ExternalMouseEventSynthesizer.BoundMouse existing = boundByKey.get(key);
                    boolean sameTarget = existing != null
                            && existing.isMouse == boundAsMouse
                            && existing.boundAsWindowKeyboard == boundAsWindowKbd
                            && existing.targetDisplayId == desiredDisplayId
                            && ((desiredUniqueId == null && existing.displayUniqueId == null)
                                    || (desiredUniqueId != null && desiredUniqueId.equals(existing.displayUniqueId)));
                    if (sameTarget && !forceRebind) {
                        if (boundAsMouse && cand.keyboardType == MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC) {
                            sawComboKbdOnOuter = true;
                        }
                        continue;
                    }
                    if (existing != null) {
                        synthesizer.unbindSingle(existing);
                        boundByKey.remove(key);
                    }
                    int kind = synthesizer.bindSingle(cand.descriptor, cand.location, desiredUniqueId, desiredPort);
                    if (kind > 0) {
                        if (boundAsMouse && cand.keyboardType == MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC) {
                            sawComboKbdOnOuter = true;
                        }
                        boundByKey.put(key, new ExternalMouseEventSynthesizer.BoundMouse(
                                cand.descriptor, cand.location, kind, desiredDisplayId,
                                desiredUniqueId, boundAsMouse, boundAsWindowKbd));
                        String tag = boundAsWindowKbd ? "OPALDESK_KBD_ROUTED_WINDOW" : "OPALDESK_MOUSE_ROUTED";
                        System.out.println(tag + " name=" + cand.name
                                + " displayId=" + desiredDisplayId + " uniqueId=" + desiredUniqueId
                                + " kind=" + kind + " src=0x" + Integer.toHexString(cand.sources)
                                + " kbdType=" + cand.keyboardType + " dedMouse=" + scan.hasDedicatedMouse);
                    }
                }

                ArrayList<String> staleKeys = new ArrayList<>();
                for (String k : boundByKey.keySet()) {
                    if (!stillPresentKeys.contains(k)) staleKeys.add(k);
                }
                for (String stale : staleKeys) {
                    ExternalMouseEventSynthesizer.BoundMouse bm = boundByKey.remove(stale);
                    if (bm != null) synthesizer.unbindSingle(bm);
                }

                int routedDisp = -1;
                for (ExternalMouseEventSynthesizer.BoundMouse bm : boundByKey.values()) {
                    if (bm.boundAsWindowKeyboard && bm.targetDisplayId > 0) {
                        routedDisp = bm.targetDisplayId;
                        break;
                    }
                }
                keyboardRoutedDisplayId = routedDisp;
                comboKeyboardBoundToOuter = sawComboKbdOnOuter;
                nextKbdRouted = routedDisp;
                nextComboOuter = sawComboKbdOnOuter;
                boundMouseCount = getBoundMouseCount();
            }
        }
        if (prevKbdRouted != nextKbdRouted || prevComboOuter != nextComboOuter) {
            KeyboardRoutingListener listener = this.keyboardRoutingListener;
            if (listener != null) {
                try { listener.onKeyboardRoutedDisplayChanged(nextKbdRouted); } catch (RuntimeException ignored) {}
            }
        }
        return boundMouseCount;
    }

    private void refreshPhysicalKeyboardState(boolean notifyListener) {
        int mask = synthesizer.detectHardwareInputFlags();
        boolean kbdDetected = (mask & 1) != 0;
        boolean touchDetected = (mask & 2) != 0;
        boolean kbdChanged, touchChanged;
        synchronized (lock) {
            kbdChanged = (kbdDetected != physicalKeyboardConnected);
            touchChanged = (touchDetected != externalTouchscreenConnected);
            physicalKeyboardConnected = kbdDetected;
            externalTouchscreenConnected = touchDetected;
            syncExternalDisplayImePolicyLocked();
        }
        if (kbdChanged) {
            System.out.println("OPALDESK_PHYSICAL_KBD_STATE connected=" + kbdDetected);
            if (notifyListener) {
                PhysicalKeyboardListener listener = physicalKeyboardListener;
                if (listener != null) {
                    try { listener.onPhysicalKeyboardPresenceChanged(kbdDetected); } catch (RuntimeException ignored) {}
                }
            }
        }
        if (touchChanged) {
            System.out.println("OPALDESK_EXTERNAL_TOUCH_STATE connected=" + touchDetected);
            if (notifyListener) notifyExternalImeContextChanged();
        }
    }

    private void notifyExternalImeContextChanged() {
        Runnable listener = externalImeContextListener;
        if (listener != null) {
            try { listener.run(); } catch (RuntimeException ignored) {}
        }
    }

    public void resyncExternalDisplayImePolicy() {
        synchronized (lock) {
            syncExternalDisplayImePolicyLocked();
        }
        resyncBindings();
    }

    private void syncExternalDisplayImePolicyLocked() {
        if (services == null) return;
        int extId = observedExternalDisplayId;
        String extUid = observedExternalDisplayUniqueId;
        boolean isPhysicalExternal = extId > 0 && (extUid == null || !extUid.startsWith("virtual:"));
        int dexImeMode = (config != null) ? config.getDexImeDisplayMode() : BridgeProtocol.DEX_IME_DISPLAY_AUTO;
        boolean wantLocal = MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                isPhysicalExternal, physicalKeyboardConnected, externalTouchscreenConnected, dexImeMode);
        int desiredTarget = wantLocal ? extId : -1;
        if (desiredTarget == imePolicyOverriddenDisplayId) return;
        restoreExternalDisplayImePolicyLocked();
        if (desiredTarget > 0) {
            int prior = services.getDisplayImePolicy(desiredTarget);
            if (prior == BridgeProtocol.DISPLAY_IME_POLICY_LOCAL) return;
            if (services.setDisplayImePolicy(desiredTarget, BridgeProtocol.DISPLAY_IME_POLICY_LOCAL)) {
                imePolicyOverriddenDisplayId = desiredTarget;
                imePolicyOriginalValue = (prior >= 0) ? prior : BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
                System.out.println("OPALDESK_EXT_DISPLAY_IME_POLICY displayId=" + desiredTarget
                        + " policy=LOCAL mode=" + dexImeMode
                        + " extTouch=" + externalTouchscreenConnected
                        + " prior=" + imePolicyOriginalValue);
            }
        }
    }

    private void restoreExternalDisplayImePolicyLocked() {
        int dispId = imePolicyOverriddenDisplayId;
        int orig = imePolicyOriginalValue;
        imePolicyOverriddenDisplayId = -1;
        imePolicyOriginalValue = -1;
        if (dispId > 0 && orig >= 0 && services != null) {
            boolean ok = services.setDisplayImePolicy(dispId, orig);
            System.out.println("OPALDESK_EXT_DISPLAY_IME_POLICY_RESTORED displayId=" + dispId
                    + " policy=" + orig + " ok=" + ok);
        }
    }

    private void unbindAllInternal(boolean clearDisplay, boolean sweepSystemDevices) {
        synchronized (lock) {
            unbindLocked();
            if (sweepSystemDevices) synthesizer.sweepClearConnectedDeviceAssociations();
            if (clearDisplay) {
                activeExternalDisplayId = -1;
                activeDisplayUniqueId = null;
                activeDisplayPort = -1;
                observedExternalDisplayId = -1;
                observedExternalDisplayUniqueId = null;
                restoreExternalDisplayImePolicyLocked();
            }
        }
    }

    private void unbindLocked() {
        keyboardRoutedDisplayId = -1;
        comboKeyboardBoundToOuter = false;
        if (boundByKey.isEmpty()) return;
        for (ExternalMouseEventSynthesizer.BoundMouse bm : boundByKey.values()) {
            synthesizer.unbindSingle(bm);
        }
        boundByKey.clear();
        System.out.println("OPALDESK_MOUSE_RETURNED_LOCAL");
    }
}
