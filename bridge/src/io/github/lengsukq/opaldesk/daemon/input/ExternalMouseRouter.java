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

        BoundMouse(String descriptor, String location, int bindKind, int targetDisplayId, String displayUniqueId) {
            this.descriptor = descriptor;
            this.location = location;
            this.bindKind = bindKind;
            this.targetDisplayId = targetDisplayId;
            this.displayUniqueId = displayUniqueId;
        }
    }

    /** Callback notified whenever a physical Bluetooth/USB alphabetic keyboard connects or disconnects. */
    public interface PhysicalKeyboardListener {
        void onPhysicalKeyboardPresenceChanged(boolean connected);
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
    private int imePolicyOverriddenDisplayId = -1;
    private int imePolicyOriginalValue = -1;
    private volatile boolean physicalKeyboardConnected = false;
    private volatile PhysicalKeyboardListener physicalKeyboardListener;
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

    public boolean hasPhysicalKeyboard() {
        return physicalKeyboardConnected;
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
                    refreshPhysicalKeyboardState(true);
                    resyncBindings();
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    refreshPhysicalKeyboardState(true);
                    resyncBindings();
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
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
        unbindAllInternal(true, true);
        if (services != null) {
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

        synchronized (lock) {
            if (externalDisplayId > 0) {
                observedExternalDisplayId = externalDisplayId;
                observedExternalDisplayUniqueId = uniqueId;
            } else {
                observedExternalDisplayId = -1;
                observedExternalDisplayUniqueId = null;
            }
        }
        refreshPhysicalKeyboardState(true);

        if (targetMode != BridgeProtocol.MOUSE_TARGET_EXTERNAL || externalDisplayId <= 0) {
            synchronized (lock) {
                activeExternalDisplayId = -1;
                activeDisplayUniqueId = null;
                activeDisplayPort = -1;
                syncExternalDisplayImePolicyLocked();
            }
            int bound = resyncBindings();
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
            return resyncBindings();
        }
        synchronized (lock) {
            activeExternalDisplayId = externalDisplayId;
            activeDisplayUniqueId = uniqueId;
            activeDisplayPort = physPort;
            syncExternalDisplayImePolicyLocked();
        }
        return resyncBindings();
    }

    public void releaseToLocal() {
        localDesktopPinned = false;
        synchronized (lock) {
            activeExternalDisplayId = -1;
            activeDisplayUniqueId = null;
            activeDisplayPort = -1;
            observedExternalDisplayId = -1;
            observedExternalDisplayUniqueId = null;
            restoreExternalDisplayImePolicyLocked();
        }
        resyncBindings();
        if (services != null) {
            services.restoreDefaultDisplayTopFocus();
        }
    }

    public boolean isLocalDesktopPinned() {
        return localDesktopPinned;
    }

    public int getBoundMouseCount() {
        synchronized (lock) {
            int extCount = 0;
            for (BoundMouse bm : boundByKey.values()) {
                if (bm.targetDisplayId > 0) extCount++;
            }
            return extCount;
        }
    }

    public int getBoundDisplayId() {
        synchronized (lock) {
            for (BoundMouse bm : boundByKey.values()) {
                if (bm.targetDisplayId > 0) return bm.targetDisplayId;
            }
            return -1;
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
        synchronized (lock) {
            int desiredDisplayId = -1;
            String desiredUniqueId = null;
            int desiredPort = -1;

            boolean routeExternal = config.getMouseTargetMode() == BridgeProtocol.MOUSE_TARGET_EXTERNAL
                    && activeExternalDisplayId > 0
                    && ((activeDisplayUniqueId != null && !activeDisplayUniqueId.isEmpty()) || activeDisplayPort >= 0);
            if (routeExternal) {
                desiredDisplayId = activeExternalDisplayId;
                desiredUniqueId = activeDisplayUniqueId;
                desiredPort = activeDisplayPort;
            } else if (localDesktopPinned) {
                String d0Uid = services.getDisplayUniqueId(android.view.Display.DEFAULT_DISPLAY);
                int d0Port = services.getDisplayPhysicalPort(android.view.Display.DEFAULT_DISPLAY);
                if ((d0Uid != null && !d0Uid.isEmpty()) || d0Port >= 0) {
                    desiredDisplayId = android.view.Display.DEFAULT_DISPLAY;
                    desiredUniqueId = d0Uid;
                    desiredPort = d0Port;
                }
            }

            if (desiredDisplayId < 0) {
                unbindLocked();
                return 0;
            }
            Set<String> stillPresentKeys = new HashSet<>();
            int[] ids;
            try {
                ids = services.inputManager.getInputDeviceIds();
            } catch (RuntimeException e) {
                return getBoundMouseCount();
            }
            if (ids != null) {
                for (int id : ids) {
                    InputDevice dev = services.inputManager.getInputDevice(id);
                    if (dev == null) continue;
                    if (!shouldRouteDevice(
                            dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal())) {
                        continue;
                    }
                    String descriptor = dev.getDescriptor();
                    String location = extractInputLocation(dev);
                    String key = (descriptor != null && !descriptor.isEmpty()) ? descriptor : location;
                    if (key == null || key.isEmpty()) continue;
                    stillPresentKeys.add(key);

                    BoundMouse existing = boundByKey.get(key);
                    boolean sameTarget = existing != null
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
                    int kind = bindSingle(descriptor, location, desiredUniqueId, desiredPort);
                    if (kind > 0) {
                        boundByKey.put(key, new BoundMouse(descriptor, location, kind, desiredDisplayId, desiredUniqueId));
                        System.out.println("OPALDESK_MOUSE_ROUTED name=" + dev.getName()
                                + " displayId=" + desiredDisplayId
                                + " uniqueId=" + desiredUniqueId + " kind=" + kind);
                    }
                }
            }
            // Prune disconnected mice cleanly
            ArrayList<String> staleKeys = new ArrayList<>();
            for (String k : boundByKey.keySet()) {
                if (!stillPresentKeys.contains(k)) staleKeys.add(k);
            }
            for (String stale : staleKeys) {
                BoundMouse bm = boundByKey.remove(stale);
                if (bm != null) unbindSingle(bm);
            }
            return getBoundMouseCount();
        }
    }

    private void refreshPhysicalKeyboardState(boolean notifyListener) {
        boolean detected = detectPhysicalKeyboardConnected();
        boolean changed;
        synchronized (lock) {
            changed = (detected != physicalKeyboardConnected);
            physicalKeyboardConnected = detected;
            syncExternalDisplayImePolicyLocked();
        }
        if (changed) {
            System.out.println("OPALDESK_PHYSICAL_KBD_STATE connected=" + detected);
            if (notifyListener) {
                PhysicalKeyboardListener listener = physicalKeyboardListener;
                if (listener != null) {
                    try {
                        listener.onPhysicalKeyboardPresenceChanged(detected);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
    }

    private boolean detectPhysicalKeyboardConnected() {
        if (services == null || services.inputManager == null) return false;
        try {
            int[] ids = services.inputManager.getInputDeviceIds();
            if (ids == null) return false;
            for (int id : ids) {
                InputDevice dev = services.inputManager.getInputDevice(id);
                if (dev != null && MouseRoutingPolicy.isPhysicalAlphabeticKeyboard(
                        dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal())) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /**
     * When a physical keyboard is connected during a physical external-monitor session (HDMI / USB-C /
     * Miracast, never OpalCast {@code virtual:*} displays), switches the external display's IME policy
     * to {@code DISPLAY_IME_POLICY_LOCAL} so OpalDesk's own in-desktop search fields render their CJK
     * candidate bar on the monitor; restores the prior policy as soon as the keyboard or monitor leaves.
     */
    private void syncExternalDisplayImePolicyLocked() {
        if (services == null) return;
        int extId = observedExternalDisplayId;
        String extUid = observedExternalDisplayUniqueId;
        boolean isPhysicalExternal = extId > 0 && (extUid == null || !extUid.startsWith("virtual:"));
        int desiredTarget = (isPhysicalExternal && physicalKeyboardConnected) ? extId : -1;

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
                        + " policy=LOCAL prior=" + imePolicyOriginalValue);
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
