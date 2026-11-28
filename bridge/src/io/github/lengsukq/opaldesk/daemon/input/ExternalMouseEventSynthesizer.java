package io.github.lengsukq.opaldesk.daemon.input;

import android.view.InputDevice;

import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Low-level AOSP {@code InputManager} / {@code IInputManager} peripheral discovery, composite
 * EventHub device merging, and display UniqueId/Port association helper for {@link ExternalMouseRouter}.
 */
class ExternalMouseEventSynthesizer {
    static final class BoundMouse {
        final String descriptor;
        final String location;
        final int bindKind; // 1=descriptor, 2=uniquePort, 3=legacyUniquePort, 4=physicalPort
        final int targetDisplayId;
        final String displayUniqueId;
        final boolean isMouse;
        final boolean boundAsWindowKeyboard;

        BoundMouse(
                String descriptor, String location, int bindKind, int targetDisplayId,
                String displayUniqueId, boolean isMouse, boolean boundAsWindowKeyboard) {
            this.descriptor = descriptor;
            this.location = location;
            this.bindKind = bindKind;
            this.targetDisplayId = targetDisplayId;
            this.displayUniqueId = displayUniqueId;
            this.isMouse = isMouse;
            this.boundAsWindowKeyboard = boundAsWindowKeyboard;
        }
    }

    static final class CandidateDevice {
        String name;
        final String descriptor;
        final String location;
        int sources;
        int keyboardType;
        boolean isExternal;
        boolean isMouse;
        boolean isWindowKbd;

        CandidateDevice(
                String name, String descriptor, String location, int sources,
                int keyboardType, boolean isExternal, boolean isMouse, boolean isWindowKbd) {
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

    static final class CandidateScanResult {
        final Map<String, CandidateDevice> candidatesByKey;
        final boolean hasDedicatedMouse;

        CandidateScanResult(Map<String, CandidateDevice> candidatesByKey, boolean hasDedicatedMouse) {
            this.candidatesByKey = candidatesByKey;
            this.hasDedicatedMouse = hasDedicatedMouse;
        }
    }

    private final AndroidServices services;

    ExternalMouseEventSynthesizer(AndroidServices services) {
        this.services = services;
    }

    boolean isReady() {
        return services != null && services.inputManager != null;
    }

    String getDisplayUniqueId(int displayId) {
        return (displayId >= 0 && services != null) ? services.getDisplayUniqueId(displayId) : null;
    }

    int getDisplayPhysicalPort(int displayId) {
        return (displayId >= 0 && services != null) ? services.getDisplayPhysicalPort(displayId) : -1;
    }

    int getConnectedMouseCount() {
        if (services == null || services.inputManager == null) return 0;
        int count = 0;
        try {
            int[] ids = services.inputManager.getInputDeviceIds();
            if (ids == null) return 0;
            for (int id : ids) {
                InputDevice dev = services.inputManager.getInputDevice(id);
                if (dev != null && MouseRoutingPolicy.shouldRouteDevice(
                        dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal())) {
                    count++;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return count;
    }

    int detectHardwareInputFlags() {
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

    CandidateScanResult scanCandidateDevices() {
        int[] ids = services.inputManager.getInputDeviceIds();
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
        Map<String, CandidateDevice> candidatesByKey = new LinkedHashMap<>();
        for (InputDevice dev : liveDevices) {
            boolean isMouse = MouseRoutingPolicy.isPhysicalMouse(
                    dev.getName(), dev.getSources(), dev.isVirtual(), dev.isExternal());
            boolean isWindowKbd = MouseRoutingPolicy.isPhysicalKeyboardForWindowRouting(
                    dev.getName(), dev.getSources(), dev.getKeyboardType(),
                    dev.isVirtual(), dev.isExternal(), hasDedicatedMouse);
            boolean isExternalAlphaKbd = MouseRoutingPolicy.isPhysicalAlphabeticKeyboard(
                    dev.getName(), dev.getSources(), dev.getKeyboardType(), dev.isVirtual(), dev.isExternal());
            if (!isMouse && !isWindowKbd && !isExternalAlphaKbd) continue;
            String descriptor = dev.getDescriptor();
            String location = extractInputLocation(dev);
            String key = (descriptor != null && !descriptor.isEmpty()) ? descriptor : location;
            if (key == null || key.isEmpty()) continue;

            CandidateDevice prev = candidatesByKey.get(key);
            if (prev == null) {
                candidatesByKey.put(key, new CandidateDevice(
                        dev.getName(), descriptor, location, dev.getSources(),
                        dev.getKeyboardType(), dev.isExternal(), isMouse, isWindowKbd));
            } else {
                prev.sources |= dev.getSources();
                if (dev.getKeyboardType() == MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC) {
                    prev.keyboardType = dev.getKeyboardType();
                }
                prev.isExternal |= dev.isExternal();
                prev.isMouse |= isMouse;
                if (isWindowKbd && !prev.isWindowKbd) prev.name = dev.getName();
                prev.isWindowKbd |= isWindowKbd;
            }
        }
        return new CandidateScanResult(candidatesByKey, hasDedicatedMouse);
    }

    void sweepClearConnectedDeviceAssociations() {
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
            if (!MouseRoutingPolicy.shouldRouteDevice(
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

    int bindSingle(String descriptor, String location, String displayUniqueId, int displayPort) {
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

    void unbindSingle(BoundMouse bm) {
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
