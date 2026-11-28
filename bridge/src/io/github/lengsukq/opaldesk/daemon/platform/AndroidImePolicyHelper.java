package io.github.lengsukq.opaldesk.daemon.platform;

import android.content.Context;
import android.hardware.input.InputManager;
import android.provider.Settings;
import android.view.Display;

import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Manages per-display IME policies, VirtualDisplay windowing flags, display focus,
 * pointer icon visibility, and hardware mouse pointer speed.
 */
final class AndroidImePolicyHelper {
    private static volatile int sRemoveContentModeDestroy = -1;

    private final AndroidServices services;
    private volatile boolean tryPointerSpeedDenied = false;

    AndroidImePolicyHelper(AndroidServices services) {
        this.services = services;
    }

    int getDisplayImePolicy(int displayId) {
        if (displayId <= 0 || services.iWindowManager == null) return -1;
        if (services.getDisplayImePolicyMethod != null) {
            try {
                Object val = services.getDisplayImePolicyMethod.invoke(services.iWindowManager, displayId);
                if (val instanceof Number) return ((Number) val).intValue();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (services.shouldShowImeMethod != null) {
            try {
                Object val = services.shouldShowImeMethod.invoke(services.iWindowManager, displayId);
                if (val instanceof Boolean) {
                    return ((Boolean) val)
                            ? BridgeProtocol.DISPLAY_IME_POLICY_LOCAL
                            : BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return -1;
    }

    boolean setDisplayImePolicy(int displayId, int imePolicy) {
        if (displayId <= 0 || services.iWindowManager == null) return false;
        if (services.setDisplayImePolicyMethod != null) {
            try {
                services.setDisplayImePolicyMethod.invoke(services.iWindowManager, displayId, imePolicy);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_IME_POLICY_WARN displayId=" + displayId
                        + " policy=" + imePolicy + ": " + AndroidServices.unwrapMessage(e));
            }
        }
        if (services.setShouldShowImeMethod != null) {
            try {
                boolean local = (imePolicy == BridgeProtocol.DISPLAY_IME_POLICY_LOCAL);
                services.setShouldShowImeMethod.invoke(services.iWindowManager, displayId, local);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_IME_SHOW_WARN displayId=" + displayId
                        + " policy=" + imePolicy + ": " + AndroidServices.unwrapMessage(e));
            }
        }
        return false;
    }

    void configureVirtualDisplayWindowing(int displayId, int imePolicy) {
        if (displayId <= 0 || services.iWindowManager == null) return;
        if (services.shouldShowSystemDecorsMethod != null) {
            try {
                services.shouldShowSystemDecorsMethod.invoke(services.iWindowManager, displayId, false);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        setDisplayImePolicy(displayId, imePolicy);
        if (services.setIgnoreOrientationRequestMethod != null) {
            try {
                services.setIgnoreOrientationRequestMethod.invoke(services.iWindowManager, displayId, true);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (services.setRemoveContentModeMethod != null) {
            try {
                services.setRemoveContentModeMethod.invoke(
                        services.iWindowManager, displayId, resolveRemoveContentModeDestroy());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }

    static int resolveRemoveContentModeDestroy() {
        int cached = sRemoveContentModeDestroy;
        if (cached > 0) return cached;
        int resolved = BridgeProtocol.REMOVE_CONTENT_MODE_DESTROY;
        try {
            Field f = android.view.WindowManager.class.getField("REMOVE_CONTENT_MODE_DESTROY");
            int val = f.getInt(null);
            if (val > 0) resolved = val;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        sRemoveContentModeDestroy = resolved;
        return resolved;
    }

    void focusDisplay(int displayId) {
        if (displayId < 0) return;
        focusDisplayTaskOnly(displayId);
        if (services.moveDisplayToTopMethod != null && services.iWindowManager != null) {
            try {
                services.moveDisplayToTopMethod.invoke(services.iWindowManager, displayId, "OpalDesk");
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }

    void restoreDefaultDisplayTopFocus() {
        ensurePointerIconVisible();
        focusDisplay(Display.DEFAULT_DISPLAY);
    }

    void ensurePointerIconVisible() {
        if (services.iInputManager != null) {
            Method m = AndroidServices.findMethodOrNull(
                    services.iInputManager.getClass(), "setPointerIconVisible", boolean.class);
            if (m != null) {
                try {
                    m.invoke(services.iInputManager, true);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
    }

    void focusDisplayTaskOnly(int displayId) {
        if (displayId < 0) return;
        if (services.focusTopTaskMethod != null && services.iActivityTaskManager != null) {
            try {
                services.focusTopTaskMethod.invoke(services.iActivityTaskManager, displayId);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }

    boolean setHardwarePointerSpeed(int speed) {
        int clamped = Math.max(-7, Math.min(7, speed));
        boolean ok = false;
        if (services.iInputManager != null && !tryPointerSpeedDenied) {
            Method tryM = AndroidServices.findMethodOrNull(
                    services.iInputManager.getClass(), "tryPointerSpeed", int.class);
            if (tryM != null) {
                try {
                    tryM.invoke(services.iInputManager, clamped);
                    ok = true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    String msg = AndroidServices.unwrapMessage(e);
                    if (msg != null && (msg.contains("SecurityException") || msg.contains("SET_POINTER_SPEED"))) {
                        tryPointerSpeedDenied = true;
                    }
                    System.err.println("OPALDESK_PTR_SPEED_WARN: " + msg);
                }
            }
        }
        if (services.inputManager != null) {
            Method setM = AndroidServices.findMethodOrNull(
                    InputManager.class, "setPointerSpeed", Context.class, int.class);
            if (setM != null && services.context != null) {
                try {
                    setM.invoke(services.inputManager, services.context, clamped);
                    ok = true;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        if (!ok && services.context != null) {
            try {
                ok = Settings.System.putInt(services.context.getContentResolver(), "pointer_speed", clamped);
            } catch (RuntimeException ignored) {
            }
        }
        if (!ok) {
            ShellCommand.Result res = ShellCommand.runWithTimeout(
                    1200L, "settings", "put", "system", "pointer_speed", String.valueOf(clamped));
            ok = res.isSuccess();
        }
        return ok;
    }
}
