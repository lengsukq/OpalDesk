package io.github.lengsukq.opaldesk.daemon.input;

import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.view.InputDevice;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Daemon-level physical mouse display router ({@code uid=2000}, {@code ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}).
 *
 * <p>Routes connected external Bluetooth/USB mice directly onto an active external display
 * (e.g. USB-C / HDMI monitor running full-bleed Presentation) via standard AOSP
 * {@code InputManager.addUniqueIdAssociationByDescriptor} (Android 14–17) with graceful
 * port-association fallback (Android 11–13). Because the association lives in Android's
 * {@code InputReader}/{@code PointerChoreographer}, the physical mouse continues operating
 * the external monitor smoothly even when Display 0 switches to Home or other Android apps.
 *
 * <p>Automatically restores every mouse to Display 0 when the user selects {@code LOCAL},
 * exits projection, unplugs the monitor, or stops the daemon.
 */
public final class ExternalMouseRouter {
    private static final class BoundMouse {
        final String descriptor;
        final String location;
        final int bindKind; // 1=descriptor, 2=uniquePort, 3=legacyUniquePort, 4=physicalPort
        final int targetDisplayId;
        final String displayUniqueId;
        final boolean isMouse;
        final boolean boundAsWindowKeyboard;

        BoundMouse(
                String descriptor,
                String location,
                int bindKind,
                int targetDisplayId,
                String displayUniqueId,
                boolean isMouse,
                boolean boundAsWindowKeyboard) {
            this.descriptor = descriptor;
            this.location = location;
            this.bindKind = bindKind;
            this.targetDisplayId = targetDisplayId;
            this.displayUniqueId = displayUniqueId;
            this.isMouse = isMouse;
            this.boundAsWindowKeyboard = boundAsWindowKeyboard;
        }
    }

    private static final class CandidateDevice {
        String name;
        final String descriptor;
        final String location;
        int sources;
        int keyboardType;
        boolean isExternal;
        boolean isMouse;
        boolean isWindowKbd;

        CandidateDevice(
                String name,
                String descriptor,
                String location,
                int sources,
                int keyboardType,
                boolean isExternal,
                boolean isMouse,
                boolean isWindowKbd) {
            this.name = name;
            this.descriptor = descriptor;
            this.location = location;
            this.sources = sources;
            this.keyboardType = keyboardType;
            this.isExternal = isExternal;
            this.isMouse = isMouse;
            this.isWindowKbd = isWindowKbd;
        }
    }

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
    private final Object lock = new Object();
    private final Map<String, BoundMouse> boundByKey = new HashMap<>();

    private volatile int activeExternalDisplayId = -1;
    private volatile String activeDisplayUniqueId = null;
    private volatile int activeDisplayPort = -1;
    private volatile int observedExternalDisplayId = -1;
    private volatile String observedExternalDisplayUniqueId = null;
    private volatile int focusedWindowDisplayId = -1;
    private volatile String focusedWindowUniqueId = null;
    private volatile int keyboardRoutedDisplayId = -1;
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

    private InputManager.InputDeviceListener inputListener;
    private DisplayManager.DisplayListener displayListener;

    public ExternalMouseRouter(AndroidServices services, DaemonConfig config, Handler mainHandler) {
        this.services = services;
        this.config = config;
        this.mainHandler = mainHandler;
    }

    public void setPhysicalKeyboardListener(PhysicalKeyboardListener listener) {
        this.physicalKeyboardListener = listener;
    }

    /** Notified when external touchscreen or physical external display presence changes. */
    public void setExternalImeContextListener(Runnable listener) {
        this.externalImeContextListener = listener;
    }

    public void setKeyboardRoutingListener(KeyboardRoutingListener listener) {
        this.keyboardRoutingListener = listener;
    }

    public void setFocusedWindowTargetProvider(FocusedWindowTargetProvider provider) {
        this.focusedWindowTargetProvider = provider;
    }

    public boolean hasPhysicalKeyboard() {
        return physicalKeyboardConnected;
    }

    public boolean hasExternalTouchscreen() {
        return externalTouchscreenConnected;
    }

    public boolean hasPhysicalExternalDisplay() {
        int extId = observedExternalDisplayId;
        String extUid = observedExternalDisplayUniqueId;
        return extId > 0 && (extUid == null || !extUid.startsWith("virtual:"));
    }

    /** Returns the {@code OpalWin-*} displayId currently bound to a physical keyboard directly, or {@code -1}. */
    public int getKeyboardRoutedDisplayId() {
        return keyboardRoutedDisplayId;
    }

    public void start() {
        if (listenersRegistered || services == null) return;
        listenersRegistered = true;
        // Defensive startup sweep: clear any stale InputManager UniqueId/Port associations left in
        // system_server by a prior crashed or force-stopped daemon session so mice are never hidden.
        synchronized (lock) {
            if (activeExternalDisplayId <= 0 && !localDesktopPinned) {
                sweepClearConnectedDeviceAssociationsLocked();
            }
        }
        refreshPhysicalKeyboardState(false);
        if (services.inputManager != null) {
            inputListener = new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    refreshFocusedWindowTargetFromProvider();
                    refreshPhysicalKeyboardState(true);
                    resyncBindings();
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    refreshFocusedWindowTargetFromProvider();
                    refreshPhysicalKeyboardState(true);
                    resyncBindings();
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    refreshFocusedWindowTargetFromProvider();
                    refreshPhysicalKeyboardState(true);
                    resyncBindings();
                }
            };
            try {
                services.inputManager.registerInputDeviceListener(inputListener, mainHandler);
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_MOUSE_LISTENER_WARN: " + e.getMessage());
            }
        }
        if (services.displayManager != null) {
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {
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
                        if (prevPhysicalExt != hasPhysicalExternalDisplay()) {
                            notifyExternalImeContextChanged();
                        }
                        if (!localDesktopPinned) {
                            services.restoreDefaultDisplayTopFocus();
                        }
                    }
                }
            };
            try {
                services.displayManager.registerDisplayListener(displayListener, mainHandler);
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_MOUSE_DISP_LISTENER_WARN: " + e.getMessage());
            }
        }
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
        if (services.inputManager != null && inputListener != null) {
            try {
                services.inputManager.unregisterInputDeviceListener(inputListener);
            } catch (RuntimeException ignored) {
            }
        }
        if (services.displayManager != null && displayListener != null) {
            try {
                services.displayManager.unregisterDisplayListener(displayListener);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Applies mouse target routing mode ({@link BridgeProtocol#MOUSE_TARGET_LOCAL},
     * {@link BridgeProtocol#MOUSE_TARGET_EXTERNAL}, {@link BridgeProtocol#MOUSE_TARGET_PIN_LOCAL_DISPLAY0},
     * {@link BridgeProtocol#MOUSE_TARGET_UNPIN_LOCAL_DISPLAY0}, or
     * {@link BridgeProtocol#MOUSE_TARGET_RELEASE_EXTERNAL}).
     *
     * @return number of physical external mice currently bound to an external monitor (0 in LOCAL mode).
     */
    public int setMouseTarget(int targetMode, int externalDisplayId) {
        if (targetMode == BridgeProtocol.MOUSE_TARGET_PIN_LOCAL_DISPLAY0) {
            localDesktopPinned = true;
            refreshFocusedWindowTargetFromProvider();
            refreshPhysicalKeyboardState(true);
            return resyncBindings();
        }
        if (targetMode == BridgeProtocol.MOUSE_TARGET_UNPIN_LOCAL_DISPLAY0) {
            localDesktopPinned = false;
            int bound = resyncBindings();
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
        String uniqueId = (externalDisplayId > 0 && services != null)
                ? services.getDisplayUniqueId(externalDisplayId) : null;
        int physPort = (externalDisplayId > 0 && services != null)
                ? services.getDisplayPhysicalPort(externalDisplayId) : -1;
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
            int bound = resyncBindings();
            if (prevPhysicalExt != hasPhysicalExternalDisplay()) {
                notifyExternalImeContextChanged();
            }
            if (services != null && !localDesktopPinned) {
                services.restoreDefaultDisplayTopFocus();
            }
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
            int bound = resyncBindings();
            if (prevPhysicalExt != hasPhysicalExternalDisplay()) {
                notifyExternalImeContextChanged();
            }
            return bound;
        }
        synchronized (lock) {
            activeExternalDisplayId = externalDisplayId;
            activeDisplayUniqueId = uniqueId;
            activeDisplayPort = physPort;
            syncExternalDisplayImePolicyLocked();
        }
        int bound = resyncBindings();
        if (prevPhysicalExt != hasPhysicalExternalDisplay()) {
            notifyExternalImeContextChanged();
        }
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
        if (prevPhysicalExt != hasPhysicalExternalDisplay()) {
            notifyExternalImeContextChanged();
        }
        if (services != null && !remoteDesktopActive) {
            services.restoreDefaultDisplayTopFocus();
        }
    }

    /**
     * Updates the focused live {@code OpalWin-*} VirtualDisplay target for direct physical-keyboard
     * routing so Bluetooth/USB/Pogo keyboards feed local CJK IMEs directly without bouncing window
     * focus against the outer external monitor or Display 0.
     */
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
        if (changed) {
            resyncBindings();
        }
    }

    /** Routes physical keyboards back to the host outer display while an OpalDesk Compose TextField or soft IME is active. */
    public void setLocalImeActive(boolean active) {
        boolean changed;
        synchronized (lock) {
            changed = (localImeActive != active);
            localImeActive = active;
        }
        if (changed) {
            resyncBindings();
        }
    }

    public void setRemoteDesktopActive(boolean active) {
        boolean changed;
        synchronized (lock) {
            changed = (remoteDesktopActive != active);
            remoteDesktopActive = active;
        }
        if (changed) {
            resyncBindings();
        }
    }

    public boolean isLocalDesktopPinned() {
        return localDesktopPinned;
    }

    public int getBoundMouseCount() {
        synchronized (lock) {
            int extCount = 0;
            for (BoundMouse bm : boundByKey.values()) {
                if (bm.isMouse && bm.targetDisplayId > 0) extCount++;
            }
            return extCount;
        }
    }

    public int getBoundDisplayId() {
        synchronized (lock) {
            for (BoundMouse bm : boundByKey.values()) {
                if (bm.isMouse && bm.targetDisplayId > 0) return bm.targetDisplayId;
            }
            return -1;
        }
    }

    /** Returns {@code true} when host pointer/workstation display anchoring keeps focus on the active monitor. */
    public boolean isHostMouseDisplayAnchored() {
        synchronized (lock) {
            if (localDesktopPinned || activeExternalDisplayId > 0 || keyboardRoutedDisplayId > 0) return true;
            for (BoundMouse bm : boundByKey.values()) {
                if (bm.isMouse && bm.targetDisplayId >= 0) return true;
            }
            return false;
        }
    }

    private void refreshFocusedWindowTargetFromProvider() {
        FocusedWindowTargetProvider provider = this.focusedWindowTargetProvider;
        if (provider == null) return;
        FocusedWindowTarget target = null;
        try {
            target = provider.getFocusedWindowTarget();
        } catch (RuntimeException ignored) {
        }
        int nextId = (target != null && target.displayId > 0
                && target.vdUniqueId != null && !target.vdUniqueId.isEmpty())
                ? target.displayId : -1;
        String nextUid = (nextId > 0) ? target.vdUniqueId : null;
        synchronized (lock) {
            focusedWindowDisplayId = nextId;
            focusedWindowUniqueId = nextUid;
        }
    }

    public int getConnectedMouseCount() {
        if (services == null || services.inputManager == null) return 0;
        int count = 0;
        try {
            int[] ids = services.inputManager.getInputDeviceIds();
            if (ids == null) return 0;
            for (int id : ids) {
                InputDevice dev = services.inputManager.getInputDevice(id);
                if (dev != null && shouldRouteDevice(
                        dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal())) {
                    count++;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return count;
    }

    public boolean applyHardwarePointerSpeed(float sensitivity) {
        if (services == null) return false;
        int androidSpeed = mapSensitivityToAndroidPointerSpeed(sensitivity);
        return services.setHardwarePointerSpeed(androidSpeed);
    }

    /** Pure JVM-testable filter deciding whether an InputDevice is a routable physical mouse or keyboard. */
    public static boolean shouldRouteDevice(String name, int sources, boolean isVirtual, boolean isExternal) {
        return MouseRoutingPolicy.shouldRouteDevice(name, sources, isVirtual, isExternal);
    }

    /** Pure JVM-testable filter deciding whether an InputDevice is a routable physical mouse or keyboard. */
    public static boolean shouldRouteDevice(
            String name, int sources, int keyboardType, boolean isVirtual, boolean isExternal) {
        return MouseRoutingPolicy.shouldRouteDevice(name, sources, keyboardType, isVirtual, isExternal);
    }

    /** Maps continuous pointer sensitivity to Android's hardware pointer speed ({@code -7..+7}). */
    public static int mapSensitivityToAndroidPointerSpeed(float sensitivity) {
        return MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(sensitivity);
    }

    private int resyncBindings() {
        if (services == null || services.inputManager == null) return 0;
        int prevKbdRouted;
        int nextKbdRouted;
        int boundMouseCount;
        synchronized (lock) {
            prevKbdRouted = keyboardRoutedDisplayId;
            int outerDisplayId = -1;
            String outerUniqueId = null;
            int outerPort = -1;

            boolean routeExternal = MouseRoutingPolicy.shouldRoutePhysicalPeripheralsToDisplay(
                    config.getMouseTargetMode(),
                    activeExternalDisplayId,
                    activeDisplayUniqueId,
                    activeDisplayPort);
            boolean pinDisplay0 = !routeExternal && MouseRoutingPolicy.shouldPinPhysicalMiceToDisplay0(
                    localDesktopPinned,
                    remoteDesktopActive,
                    observedExternalDisplayId > 0,
                    config.getMouseTargetMode());
            if (routeExternal) {
                outerDisplayId = activeExternalDisplayId;
                outerUniqueId = activeDisplayUniqueId;
                outerPort = activeDisplayPort;
            } else if (pinDisplay0) {
                String d0Uid = services.getDisplayUniqueId(android.view.Display.DEFAULT_DISPLAY);
                int d0Port = services.getDisplayPhysicalPort(android.view.Display.DEFAULT_DISPLAY);
                if ((d0Uid != null && !d0Uid.isEmpty()) || d0Port >= 0) {
                    outerDisplayId = android.view.Display.DEFAULT_DISPLAY;
                    outerUniqueId = d0Uid;
                    outerPort = d0Port;
                }
            }

            int dexImeMode = (config != null) ? config.getDexImeDisplayMode() : BridgeProtocol.DEX_IME_DISPLAY_AUTO;
            boolean workstationActive = routeExternal || localDesktopPinned;
            boolean routeKbdToWindow = MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                    workstationActive,
                    remoteDesktopActive,
                    localImeActive,
                    dexImeMode,
                    focusedWindowDisplayId,
                    focusedWindowUniqueId != null && !focusedWindowUniqueId.isEmpty());

            if (outerDisplayId < 0 && !routeKbdToWindow) {
                unbindLocked();
                nextKbdRouted = keyboardRoutedDisplayId;
                boundMouseCount = 0;
            } else {
                int[] ids = null;
                try {
                    ids = services.inputManager.getInputDeviceIds();
                } catch (RuntimeException e) {
                    return getBoundMouseCount();
                }
                ArrayList<InputDevice> liveDevices = new ArrayList<>();
                boolean hasDedicatedMouse = false;
                if (ids != null) {
                    for (int id : ids) {
                        InputDevice dev = services.inputManager.getInputDevice(id);
                        if (dev == null) continue;
                        liveDevices.add(dev);
                        if (!hasDedicatedMouse && MouseRoutingPolicy.isDedicatedPhysicalMouse(
                                dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal())) {
                            hasDedicatedMouse = true;
                        }
                    }
                }

                // Merge sibling EventHub/InputDevice nodes sharing the same descriptor/location key
                // so a keyboard's composite mouse/multi-axis interface never overwrites its window route.
                Map<String, CandidateDevice> candidatesByKey = new LinkedHashMap<>();
                for (InputDevice dev : liveDevices) {
                    boolean isMouse = MouseRoutingPolicy.isPhysicalMouse(
                            dev.getName(), dev.getSources(), dev.isVirtual(), dev.isExternal());
                    boolean isWindowKbd = MouseRoutingPolicy.isPhysicalKeyboardForWindowRouting(
                            dev.getName(),
                            dev.getSources(),
                            dev.getKeyboardType(),
                            dev.isVirtual(),
                            dev.isExternal(),
                            hasDedicatedMouse);
                    boolean isExternalAlphaKbd = MouseRoutingPolicy.isPhysicalAlphabeticKeyboard(
                            dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal());
                    if (!isMouse && !isWindowKbd && !isExternalAlphaKbd) {
                        continue;
                    }
                    String descriptor = dev.getDescriptor();
                    String location = extractInputLocation(dev);
                    String key = (descriptor != null && !descriptor.isEmpty()) ? descriptor : location;
                    if (key == null || key.isEmpty()) continue;

                    CandidateDevice prev = candidatesByKey.get(key);
                    if (prev == null) {
                        candidatesByKey.put(key, new CandidateDevice(
                                dev.getName(),
                                descriptor,
                                location,
                                dev.getSources(),
                                dev.getKeyboardType(),
                                dev.isExternal(),
                                isMouse,
                                isWindowKbd));
                    } else {
                        prev.sources |= dev.getSources();
                        if (dev.getKeyboardType() == MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC) {
                            prev.keyboardType = dev.getKeyboardType();
                        }
                        prev.isExternal |= dev.isExternal();
                        prev.isMouse |= isMouse;
                        if (isWindowKbd && !prev.isWindowKbd) {
                            prev.name = dev.getName();
                        }
                        prev.isWindowKbd |= isWindowKbd;
                    }
                }

                Set<String> stillPresentKeys = new HashSet<>();
                for (Map.Entry<String, CandidateDevice> entry : candidatesByKey.entrySet()) {
                    String key = entry.getKey();
                    CandidateDevice cand = entry.getValue();

                    int desiredDisplayId;
                    String desiredUniqueId;
                    int desiredPort;
                    boolean boundAsMouse;
                    boolean boundAsWindowKbd;
                    if (cand.isWindowKbd && routeKbdToWindow) {
                        desiredDisplayId = focusedWindowDisplayId;
                        desiredUniqueId = focusedWindowUniqueId;
                        desiredPort = -1;
                        boundAsMouse = false;
                        boundAsWindowKbd = true;
                    } else if (cand.isMouse || (cand.isExternal && cand.keyboardType == MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC)) {
                        if (outerDisplayId < 0) continue;
                        desiredDisplayId = outerDisplayId;
                        desiredUniqueId = outerUniqueId;
                        desiredPort = outerPort;
                        boundAsMouse = cand.isMouse;
                        boundAsWindowKbd = false;
                    } else {
                        // Built-in Pogo-Pin keyboard when not routing directly to a focused OpalWin-*
                        // stays unassociated on Display 0.
                        continue;
                    }

                    stillPresentKeys.add(key);

                    BoundMouse existing = boundByKey.get(key);
                    boolean sameTarget = existing != null
                            && existing.isMouse == boundAsMouse
                            && existing.boundAsWindowKeyboard == boundAsWindowKbd
                            && existing.targetDisplayId == desiredDisplayId
                            && ((desiredUniqueId == null && existing.displayUniqueId == null)
                                    || (desiredUniqueId != null && desiredUniqueId.equals(existing.displayUniqueId)));
                    if (sameTarget) {
                        continue;
                    }
                    if (existing != null) {
                        unbindSingle(existing);
                        boundByKey.remove(key);
                    }
                    int kind = bindSingle(cand.descriptor, cand.location, desiredUniqueId, desiredPort);
                    if (kind > 0) {
                        boundByKey.put(key, new BoundMouse(
                                cand.descriptor,
                                cand.location,
                                kind,
                                desiredDisplayId,
                                desiredUniqueId,
                                boundAsMouse,
                                boundAsWindowKbd));
                        String tag = boundAsWindowKbd
                                ? "OPALDESK_KBD_ROUTED_WINDOW"
                                : "OPALDESK_MOUSE_ROUTED";
                        System.out.println(tag + " name=" + cand.name
                                + " displayId=" + desiredDisplayId
                                + " uniqueId=" + desiredUniqueId
                                + " kind=" + kind
                                + " src=0x" + Integer.toHexString(cand.sources)
                                + " kbdType=" + cand.keyboardType
                                + " dedMouse=" + hasDedicatedMouse);
                    }
                }

                // Prune disconnected or newly-unrouted devices cleanly
                ArrayList<String> staleKeys = new ArrayList<>();
                for (String k : boundByKey.keySet()) {
                    if (!stillPresentKeys.contains(k)) staleKeys.add(k);
                }
                for (String stale : staleKeys) {
                    BoundMouse bm = boundByKey.remove(stale);
                    if (bm != null) unbindSingle(bm);
                }

                int routedDisp = -1;
                for (BoundMouse bm : boundByKey.values()) {
                    if (bm.boundAsWindowKeyboard && bm.targetDisplayId > 0) {
                        routedDisp = bm.targetDisplayId;
                        break;
                    }
                }
                keyboardRoutedDisplayId = routedDisp;
                nextKbdRouted = routedDisp;
                boundMouseCount = getBoundMouseCount();
            }
        }
        if (prevKbdRouted != nextKbdRouted) {
            KeyboardRoutingListener listener = this.keyboardRoutingListener;
            if (listener != null) {
                try {
                    listener.onKeyboardRoutedDisplayChanged(nextKbdRouted);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return boundMouseCount;
    }

    private void refreshPhysicalKeyboardState(boolean notifyListener) {
        int mask = detectHardwareInputFlags();
        boolean kbdDetected = (mask & 1) != 0;
        boolean touchDetected = (mask & 2) != 0;
        boolean kbdChanged;
        boolean touchChanged;
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
                    try {
                        listener.onPhysicalKeyboardPresenceChanged(kbdDetected);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        if (touchChanged) {
            System.out.println("OPALDESK_EXTERNAL_TOUCH_STATE connected=" + touchDetected);
            if (notifyListener) {
                notifyExternalImeContextChanged();
            }
        }
    }

    private void notifyExternalImeContextChanged() {
        Runnable listener = externalImeContextListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public void resyncExternalDisplayImePolicy() {
        synchronized (lock) {
            syncExternalDisplayImePolicyLocked();
        }
        resyncBindings();
    }

    private int detectHardwareInputFlags() {
        if (services == null || services.inputManager == null) return 0;
        int flags = 0;
        try {
            int[] ids = services.inputManager.getInputDeviceIds();
            if (ids == null) return 0;
            for (int id : ids) {
                InputDevice dev = services.inputManager.getInputDevice(id);
                if (dev == null) continue;
                if (MouseRoutingPolicy.isAlphabeticHardwareKeyboardForIme(
                        dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal())) {
                    flags |= 1;
                }
                if (MouseRoutingPolicy.isExternalTouchscreen(
                        dev.getName(), dev.getSources(), dev.isVirtual(), dev.isExternal())) {
                    flags |= 2;
                }
                if (flags == 3) break;
            }
        } catch (RuntimeException ignored) {
        }
        return flags;
    }

    /**
     * Synchronizes a physical external monitor's {@code DisplayImePolicy} (HDMI / USB-C / Miracast,
     * never OpalCast {@code virtual:*} displays) according to the user's DeX IME display mode and
     * connected physical/Pogo-Pin keyboard or external touchscreen state; restores the prior policy
     * on exit or unplug.
     */
    private void syncExternalDisplayImePolicyLocked() {
        if (services == null) return;
        int extId = observedExternalDisplayId;
        String extUid = observedExternalDisplayUniqueId;
        boolean isPhysicalExternal = extId > 0 && (extUid == null || !extUid.startsWith("virtual:"));
        int dexImeMode = (config != null) ? config.getDexImeDisplayMode() : BridgeProtocol.DEX_IME_DISPLAY_AUTO;
        boolean wantLocal = MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                isPhysicalExternal, physicalKeyboardConnected, externalTouchscreenConnected, dexImeMode);
        int desiredTarget = wantLocal ? extId : -1;

        if (desiredTarget == imePolicyOverriddenDisplayId) {
            return;
        }
        restoreExternalDisplayImePolicyLocked();
        if (desiredTarget > 0) {
            int prior = services.getDisplayImePolicy(desiredTarget);
            if (prior == BridgeProtocol.DISPLAY_IME_POLICY_LOCAL) {
                return;
            }
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
            if (sweepSystemDevices) {
                sweepClearConnectedDeviceAssociationsLocked();
            }
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
        if (boundByKey.isEmpty()) return;
        for (BoundMouse bm : boundByKey.values()) {
            unbindSingle(bm);
        }
        boundByKey.clear();
        System.out.println("OPALDESK_MOUSE_RETURNED_LOCAL");
    }

    private void sweepClearConnectedDeviceAssociationsLocked() {
        if (services == null || services.inputManager == null) return;
        int[] ids;
        try {
            ids = services.inputManager.getInputDeviceIds();
        } catch (RuntimeException ignored) {
            return;
        }
        if (ids == null) return;
        for (int id : ids) {
            InputDevice dev;
            try {
                dev = services.inputManager.getInputDevice(id);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (dev == null) continue;
            if (!shouldRouteDevice(
                    dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal())
                    && !MouseRoutingPolicy.isPurePhysicalKeyboardForWindowRouting(
                    dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal())) {
                continue;
            }
            String descriptor = dev.getDescriptor();
            String location = extractInputLocation(dev);
            if (descriptor != null && !descriptor.isEmpty()) {
                invokeInputMethod("removeUniqueIdAssociationByDescriptor",
                        new Class<?>[]{String.class}, descriptor);
            }
            if (location != null && !location.isEmpty()) {
                invokeInputMethod("removeUniqueIdAssociationByPort",
                        new Class<?>[]{String.class}, location);
                invokeInputMethod("removeUniqueIdAssociation",
                        new Class<?>[]{String.class}, location);
                invokeInputMethod("removePortAssociation",
                        new Class<?>[]{String.class}, location);
            }
        }
    }

    private int bindSingle(String descriptor, String location, String displayUniqueId, int displayPort) {
        if (displayUniqueId != null && !displayUniqueId.isEmpty()) {
            if (descriptor != null && !descriptor.isEmpty()) {
                if (invokeInputMethod("addUniqueIdAssociationByDescriptor",
                        new Class<?>[]{String.class, String.class}, descriptor, displayUniqueId)) {
                    return 1;
                }
            }
            if (location != null && !location.isEmpty()) {
                if (invokeInputMethod("addUniqueIdAssociationByPort",
                        new Class<?>[]{String.class, String.class}, location, displayUniqueId)) {
                    return 2;
                }
                if (invokeInputMethod("addUniqueIdAssociation",
                        new Class<?>[]{String.class, String.class}, location, displayUniqueId)) {
                    return 3;
                }
            }
        }
        if (location != null && !location.isEmpty() && displayPort >= 0) {
            if (invokeInputMethod("addPortAssociation",
                    new Class<?>[]{String.class, int.class}, location, displayPort)) {
                return 4;
            }
        }
        return 0;
    }

    private void unbindSingle(BoundMouse bm) {
        switch (bm.bindKind) {
            case 1:
                if (bm.descriptor != null) {
                    invokeInputMethod("removeUniqueIdAssociationByDescriptor",
                            new Class<?>[]{String.class}, bm.descriptor);
                }
                break;
            case 2:
                if (bm.location != null) {
                    invokeInputMethod("removeUniqueIdAssociationByPort",
                            new Class<?>[]{String.class}, bm.location);
                }
                break;
            case 3:
                if (bm.location != null) {
                    invokeInputMethod("removeUniqueIdAssociation",
                            new Class<?>[]{String.class}, bm.location);
                }
                break;
            case 4:
                if (bm.location != null) {
                    invokeInputMethod("removePortAssociation",
                            new Class<?>[]{String.class}, bm.location);
                }
                break;
            default:
                break;
        }
    }

    private boolean invokeInputMethod(String name, Class<?>[] paramTypes, Object... args) {
        if (services == null) return false;
        if (invokeOnTarget(services.iInputManager, name, paramTypes, args)) return true;
        return invokeOnTarget(services.inputManager, name, paramTypes, args);
    }

    private static boolean invokeOnTarget(Object target, String name, Class<?>[] paramTypes, Object... args) {
        if (target == null) return false;
        try {
            Method m = target.getClass().getMethod(name, paramTypes);
            m.invoke(target, args);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (InvocationTargetException e) {
            Throwable root = (e.getCause() != null) ? e.getCause() : e;
            System.err.println("OPALDESK_MOUSE_ASSOC_WARN " + name + ": " + root);
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_MOUSE_ASSOC_WARN " + name + ": " + e.getMessage());
            return false;
        }
    }

    private static String extractInputLocation(InputDevice dev) {
        if (dev == null) return null;
        try {
            Method m = dev.getClass().getMethod("getLocation");
            Object v = m.invoke(dev);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        try {
            Method m = dev.getClass().getMethod("getExternalLocation");
            Object v = m.invoke(dev);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }
}
