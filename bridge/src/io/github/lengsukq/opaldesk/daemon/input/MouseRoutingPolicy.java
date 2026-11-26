package io.github.lengsukq.opaldesk.daemon.input;

/**
 * Pure JVM-testable policy rules for physical external mouse routing & stepless pointer speed mapping.
 *
 * <p>Kept free of {@code android.*} imports so deterministic JUnit 4 unit tests execute on standard JVM.
 */
public final class MouseRoutingPolicy {
    /** Matches {@code android.view.InputDevice.SOURCE_MOUSE} (0x00002002). */
    public static final int SOURCE_MOUSE = 0x00002002;
    /** Matches {@code android.view.InputDevice.SOURCE_KEYBOARD} (0x00000101). */
    public static final int SOURCE_KEYBOARD = 0x00000101;
    /** Matches {@code android.view.InputDevice.SOURCE_TOUCHSCREEN} (0x00001002). */
    public static final int SOURCE_TOUCHSCREEN = 0x00001002;
    /** Matches {@code android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC} (2). */
    public static final int KEYBOARD_TYPE_ALPHABETIC = 2;

    public static final float MIN_SENSITIVITY = 0.50f;
    public static final float DEFAULT_SENSITIVITY = 1.45f;
    public static final float MAX_SENSITIVITY = 3.00f;

    // Standard android.view.MotionEvent action & button constants
    public static final int ACTION_MASK = 0xff;
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_HOVER_MOVE = 7;
    public static final int ACTION_SCROLL = 8;
    public static final int ACTION_HOVER_ENTER = 9;
    public static final int ACTION_HOVER_EXIT = 10;
    public static final int ACTION_BUTTON_PRESS = 11;
    public static final int ACTION_BUTTON_RELEASE = 12;
    public static final int BUTTON_PRIMARY = 1;
    public static final int BUTTON_SECONDARY = 2;
    public static final int BUTTON_TERTIARY = 4;
    public static final int BUTTON_BACK = 8;
    public static final int BUTTON_FORWARD = 16;

    private MouseRoutingPolicy() {}

    /**
     * Returns {@code true} only for generic motion actions that are valid to inject into
     * {@code IInputManager.injectInputEvent} ({@code HOVER_ENTER}, {@code HOVER_MOVE},
     * {@code HOVER_EXIT}, {@code SCROLL}).
     *
     * <p>Explicitly rejects {@code ACTION_BUTTON_PRESS (11)} and {@code ACTION_BUTTON_RELEASE (12)},
     * which Android's {@code InputDispatcher} synthesizes internally from {@code ACTION_DOWN}/{@code UP};
     * injecting them directly corrupts button state tracking and causes first-click drops.
     */
    public static boolean isInjectableGenericMotionAction(int actionMasked) {
        return actionMasked == ACTION_HOVER_MOVE
                || actionMasked == ACTION_HOVER_ENTER
                || actionMasked == ACTION_HOVER_EXIT
                || actionMasked == ACTION_SCROLL;
    }

    /**
     * Normalizes mouse {@code buttonState} so composite USB / 2.4GHz OTG HID receivers that emit
     * {@code ACTION_DOWN} or {@code ACTION_MOVE} with {@code buttonState == 0} still report
     * {@link #BUTTON_PRIMARY}.
     */
    public static int normalizeMouseButtonState(int actionMasked, boolean isMouseSource, int rawButtonState) {
        if (isMouseSource
                && (actionMasked == ACTION_DOWN || actionMasked == ACTION_MOVE)
                && rawButtonState == 0) {
            return BUTTON_PRIMARY;
        }
        return rawButtonState;
    }

    /**
     * Returns {@code true} when a primary left-button mouse click sequence ({@code DOWN/MOVE/UP/CANCEL})
     * should be delivered as {@code SOURCE_TOUCHSCREEN}/{@code TOOL_TYPE_FINGER} so both desktop web
     * pages and mobile H5 pages (which rely on {@code touchstart}/{@code touchend} or reject synthetic
     * {@code SOURCE_MOUSE} events without {@code ACTION_BUTTON_PRESS}) respond identically to touch.
     * Hover, scroll wheel, and secondary/tertiary mouse buttons stay pure {@code SOURCE_MOUSE}.
     */
    public static boolean shouldMapPrimaryMouseClickToTouch(
            int actionMasked,
            boolean isMouseSource,
            int rawButtonState,
            boolean activePrimaryClick) {
        if (!isMouseSource) return false;
        if (actionMasked == ACTION_DOWN) {
            return rawButtonState == 0 || rawButtonState == BUTTON_PRIMARY;
        }
        if (actionMasked == ACTION_MOVE) {
            return activePrimaryClick && (rawButtonState == 0 || rawButtonState == BUTTON_PRIMARY);
        }
        return activePrimaryClick && (actionMasked == ACTION_UP || actionMasked == ACTION_CANCEL);
    }

    /**
     * Returns {@code true} when {@code buttonState} contains only auxiliary side navigation buttons
     * ({@link #BUTTON_BACK} / {@link #BUTTON_FORWARD}) without any primary, secondary, tertiary,
     * or other pointer button pressed.
     */
    public static boolean isSideNavigationOnlyButtonState(int buttonState) {
        int sideNavMask = BUTTON_BACK | BUTTON_FORWARD;
        return (buttonState & sideNavMask) != 0 && (buttonState & ~sideNavMask) == 0;
    }

    /**
     * Suppresses companion mouse touch events ({@code ACTION_DOWN} .. {@code ACTION_UP/CANCEL})
     * generated by side navigation buttons ({@link #BUTTON_BACK} / {@link #BUTTON_FORWARD}) because
     * side-button navigation is already handled once via {@code ACTION_BUTTON_PRESS}.
     */
    public static boolean shouldSuppressSideNavigationMouseTouch(
            int actionMasked,
            boolean isMouseSource,
            int rawButtonState,
            boolean activeSuppression) {
        if (!isMouseSource) return false;
        if (actionMasked == ACTION_DOWN) {
            return isSideNavigationOnlyButtonState(rawButtonState);
        }
        if (!activeSuppression) return false;
        return actionMasked == ACTION_MOVE
                || actionMasked == ACTION_UP
                || actionMasked == ACTION_CANCEL;
    }

    /**
     * Returns {@code true} when a virtual UHID pointer is transitioning from released ({@code 0})
     * to pressed ({@code != 0}) at a new coordinate {@code (nextAbsX, nextAbsY)} different from
     * {@code (lastAbsX, lastAbsY)}. Sending a 0-button position report first prevents Android's
     * {@code InputReader} from interpreting a single click at a new spot as a drag from the old spot.
     */
    public static boolean needsPrePressPositionReport(
            int lastButtons,
            int nextButtons,
            int lastAbsX,
            int lastAbsY,
            int nextAbsX,
            int nextAbsY) {
        return lastButtons == 0
                && nextButtons != 0
                && (nextAbsX != lastAbsX || nextAbsY != lastAbsY);
    }

    /** Decides whether an InputDevice represents a routable physical external mouse. */
    public static boolean shouldRouteDevice(String name, int sources, boolean isVirtual, boolean isExternal) {
        return shouldRouteDevice(name, sources, 0, isVirtual, isExternal);
    }

    /**
     * Decides whether an InputDevice represents a real physical external mouse
     * (excluding built-in devices, virtual devices, and OpalDesk's own UHID devices).
     */
    public static boolean isPhysicalMouse(String name, int sources, boolean isVirtual, boolean isExternal) {
        if (isVirtual || !isExternal) return false;
        if (name != null && name.startsWith("OpalDesk Virtual")) return false;
        return (sources & SOURCE_MOUSE) == SOURCE_MOUSE;
    }

    /**
     * Decides whether an InputDevice represents a real physical external alphabetic keyboard
     * (excluding built-in gpio-keys, Bluetooth headphone media buttons, virtual devices, and
     * OpalDesk's own UHID keyboards).
     */
    public static boolean isPhysicalAlphabeticKeyboard(
            String name, int sources, int keyboardType, boolean isVirtual, boolean isExternal) {
        if (isVirtual || !isExternal) return false;
        if (name != null && name.startsWith("OpalDesk Virtual")) return false;
        return (sources & SOURCE_KEYBOARD) == SOURCE_KEYBOARD
                && keyboardType == KEYBOARD_TYPE_ALPHABETIC;
    }

    /**
     * Decides whether an InputDevice represents a physical alphabetic keyboard for IME policy
     * switching — includes all external Bluetooth/USB keyboards plus built-in tablet Pogo-Pin /
     * dock keyboards while rejecting phone {@code gpio-keys}, power keys, and virtual keyboards.
     */
    public static boolean isAlphabeticHardwareKeyboardForIme(
            String name, int sources, int keyboardType, boolean isVirtual, boolean isExternal) {
        if (isPhysicalAlphabeticKeyboard(name, sources, keyboardType, isVirtual, isExternal)) {
            return true;
        }
        if (isVirtual) return false;
        if ((sources & SOURCE_KEYBOARD) != SOURCE_KEYBOARD || keyboardType != KEYBOARD_TYPE_ALPHABETIC) {
            return false;
        }
        if (name == null || name.isEmpty()) return false;
        if (name.startsWith("OpalDesk Virtual")) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("gpio")
                || lower.contains("pwrkey")
                || lower.contains("pon")
                || lower.contains("kpd")
                || lower.contains("headset")
                || lower.contains("jack")
                || lower.contains("hall")
                || lower.contains("virtual")
                || lower.contains("touch")) {
            return false;
        }
        return lower.contains("keyboard") || lower.contains("kbd") || lower.contains("pogo");
    }

    /**
     * Decides whether an InputDevice represents a routable physical external mouse OR alphabetic
     * external physical keyboard (excluding built-in keys, virtual UHID devices, and Bluetooth
     * headphone non-alphabetic media-key devices).
     */
    public static boolean shouldRouteDevice(
            String name, int sources, int keyboardType, boolean isVirtual, boolean isExternal) {
        return isPhysicalMouse(name, sources, isVirtual, isExternal)
                || isPhysicalAlphabeticKeyboard(name, sources, keyboardType, isVirtual, isExternal);
    }

    /**
     * Returns {@code true} for pure physical alphabetic keyboards (Bluetooth / USB / tablet
     * Pogo-Pin dock) that do NOT also report {@code SOURCE_MOUSE}, so they can be associated
     * directly with the focused {@code OpalWin-*} VirtualDisplay without ever trapping a mouse
     * pointer offscreen.
     */
    public static boolean isPurePhysicalKeyboardForWindowRouting(
            String name, int sources, int keyboardType, boolean isVirtual, boolean isExternal) {
        return isAlphabeticHardwareKeyboardForIme(name, sources, keyboardType, isVirtual, isExternal)
                && !isPhysicalMouse(name, sources, isVirtual, isExternal);
    }

    /**
     * Returns {@code true} when pure physical keyboards should be associated directly with the
     * focused {@code OpalWin-*} VirtualDisplay instead of the outer desktop/external display.
     *
     * <p>Direct association delivers keystrokes straight from Android's {@code InputDispatcher}
     * into the app window and its local CJK IME, eliminating the outer-display <-> VirtualDisplay
     * window-focus ping-pong that otherwise triggers {@code IMMS.unbindIme()} and cancels
     * {@code ACTION_UP} on every keystroke.
     */
    public static boolean shouldRoutePhysicalKeyboardToFocusedWindow(
            boolean workstationDisplayActive,
            boolean remoteDesktopActive,
            boolean localImeActive,
            int dexImeDisplayMode,
            int focusedWindowDisplayId,
            boolean hasFocusedWindowUniqueId) {
        if (!workstationDisplayActive || remoteDesktopActive || localImeActive) {
            return false;
        }
        if (dexImeDisplayMode == 2 /* BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST */) {
            return false;
        }
        return focusedWindowDisplayId > 0 && hasFocusedWindowUniqueId;
    }

    /**
     * Resolves the Android {@code DisplayImePolicy} for a managed {@code OpalWin-*} VirtualDisplay
     * using the default automatic DeX IME display mode (preserving backward compatibility).
     */
    public static int resolveWindowDisplayImePolicy(
            boolean hasPhysicalKeyboard,
            boolean remoteDesktopActive,
            boolean displayIsTrusted) {
        return resolveWindowDisplayImePolicy(
                hasPhysicalKeyboard,
                remoteDesktopActive,
                displayIsTrusted,
                0 /* DEX_IME_DISPLAY_AUTO */,
                false);
    }

    /**
     * Resolves the Android {@code DisplayImePolicy} ({@code 0 = LOCAL}, {@code 1 = FALLBACK_DISPLAY},
     * {@code 2 = HIDE}) for a managed {@code OpalWin-*} VirtualDisplay.
     */
    public static int resolveWindowDisplayImePolicy(
            boolean hasPhysicalKeyboard,
            boolean remoteDesktopActive,
            boolean displayIsTrusted,
            int dexImeDisplayMode,
            boolean muteHostImeOnCast) {
        if (remoteDesktopActive) {
            if (dexImeDisplayMode == 1 /* DEX_IME_DISPLAY_ALWAYS_LOCAL */ && displayIsTrusted) {
                return 0; // BridgeProtocol.DISPLAY_IME_POLICY_LOCAL
            }
            if (muteHostImeOnCast && displayIsTrusted) {
                return 2; // BridgeProtocol.DISPLAY_IME_POLICY_HIDE
            }
            return 1; // BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
        }
        if (!displayIsTrusted) {
            return 1; // BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
        }
        if (dexImeDisplayMode == 1 /* DEX_IME_DISPLAY_ALWAYS_LOCAL */) {
            return 0; // BridgeProtocol.DISPLAY_IME_POLICY_LOCAL
        }
        if (dexImeDisplayMode == 2 /* DEX_IME_DISPLAY_FALLBACK_HOST */) {
            return 1; // BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
        }
        return hasPhysicalKeyboard
                ? 0 // BridgeProtocol.DISPLAY_IME_POLICY_LOCAL
                : 1; // BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
    }

    /**
     * Resolves the Android {@code DisplayImePolicy} for an outer remote OpalCast VirtualDisplay
     * ({@code OpalDesk-Remote} or {@code OpalDesk-App-*}).
     */
    public static int resolveRemoteOuterDisplayImePolicy(
            int dexImeDisplayMode,
            boolean muteHostImeOnCast,
            boolean displayIsTrusted) {
        if (dexImeDisplayMode == 1 /* DEX_IME_DISPLAY_ALWAYS_LOCAL */ && displayIsTrusted) {
            return 0; // BridgeProtocol.DISPLAY_IME_POLICY_LOCAL
        }
        if (muteHostImeOnCast && displayIsTrusted) {
            return 2; // BridgeProtocol.DISPLAY_IME_POLICY_HIDE
        }
        return 1; // BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
    }

    /**
     * Returns {@code true} when a display uniqueId belongs to an OpalCast virtual cast display
     * ({@code virtual:*}) rather than a physical HDMI / USB-C / Miracast monitor.
     */
    public static boolean isVirtualCastDisplayUniqueId(String displayUniqueId) {
        return displayUniqueId != null && displayUniqueId.startsWith("virtual:");
    }

    /**
     * Decides whether physical USB/Bluetooth mice and keyboards attached to the host Android device
     * should be bound to {@code externalDisplayId}.
     *
     * <p>OpalCast virtual displays ({@code virtual:*}) are explicitly isolated so host USB peripherals
     * stay on Display 0 instead of hijacking the remote Windows/macOS cast display or painting a
     * duplicate Android cursor inside the encoded video stream.
     */
    public static boolean shouldRoutePhysicalPeripheralsToDisplay(
            int targetMode,
            int externalDisplayId,
            String displayUniqueId,
            int displayPhysicalPort) {
        if (targetMode != 1 /* BridgeProtocol.MOUSE_TARGET_EXTERNAL */ || externalDisplayId <= 0) {
            return false;
        }
        if (isVirtualCastDisplayUniqueId(displayUniqueId)) {
            return false;
        }
        return (displayUniqueId != null && !displayUniqueId.isEmpty()) || displayPhysicalPort >= 0;
    }

    /**
     * Resolves whether a physical external monitor ({@code HDMI / USB-C / Miracast}) should be
     * explicitly overridden to {@code DISPLAY_IME_POLICY_LOCAL (0)} by {@code ExternalMouseRouter}.
     */
    public static boolean shouldForcePhysicalExternalDisplayLocalIme(
            boolean isPhysicalExternal,
            boolean hasPhysicalKeyboard,
            int dexImeDisplayMode) {
        if (!isPhysicalExternal) return false;
        if (dexImeDisplayMode == 1 /* DEX_IME_DISPLAY_ALWAYS_LOCAL */) return true;
        if (dexImeDisplayMode == 2 /* DEX_IME_DISPLAY_FALLBACK_HOST */) return false;
        return hasPhysicalKeyboard;
    }

    /**
     * Returns {@code true} when a physical keyboard {@code KeyEvent} targeting an isolated
     * {@code VirtualDisplay} should prefer Linux {@code /dev/uhid} keyboard injection over
     * {@code IInputManager.injectInputEvent}.
     *
     * <p>AOSP {@code InputDispatcher::injectInputEvent} forcibly rewrites {@code deviceId} to
     * {@code VIRTUAL_KEYBOARD_ID (-1)}, which causes many Chinese IMEs (Gboard, Sogou, WeType, Baidu)
     * to bypass hardware-keyboard Pinyin composition or suppress their candidate bar. Delivering the
     * keypress through a display-associated kernel UHID keyboard preserves authentic physical HID
     * semantics while keeping {@code INPUT_BACKEND_SYSTEM} strictly system-only.
     */
    public static boolean shouldPreferUhidForPhysicalKey(
            int inputBackendMode,
            boolean isPhysicalKeyEvent,
            boolean hasVdUniqueId) {
        if (!isPhysicalKeyEvent || !hasVdUniqueId) return false;
        return inputBackendMode != 1; // BridgeProtocol.INPUT_BACKEND_SYSTEM
    }

    /**
     * Returns {@code true} when {@code OpalDeskDaemon} should probe {@code dumpsys input_method}
     * after a tap inside a window to auto-summon the Display 0 / remote host soft-keyboard bridge.
     */
    public static boolean shouldRunFallbackImeProbe(
            boolean localImeActive,
            boolean hasPhysicalKeyboard,
            boolean remoteDesktopActive) {
        return shouldRunFallbackImeProbe(
                localImeActive, hasPhysicalKeyboard, remoteDesktopActive, 0 /* DEX_IME_DISPLAY_AUTO */);
    }

    /**
     * Returns {@code true} when {@code OpalDeskDaemon} should probe {@code dumpsys input_method}
     * after a tap inside a window, taking the user's DeX IME display mode into account.
     *
     * <p>When {@code DEX_IME_DISPLAY_ALWAYS_LOCAL} is selected, the window VirtualDisplay hosts the
     * IME locally — firing the Display 0 fallback bridge would steal focus back to the phone screen.
     */
    public static boolean shouldRunFallbackImeProbe(
            boolean localImeActive,
            boolean hasPhysicalKeyboard,
            boolean remoteDesktopActive,
            int dexImeDisplayMode) {
        if (localImeActive) return false;
        if (dexImeDisplayMode == 1 /* DEX_IME_DISPLAY_ALWAYS_LOCAL */) return false;
        if (remoteDesktopActive) return true;
        if (dexImeDisplayMode == 2 /* DEX_IME_DISPLAY_FALLBACK_HOST */) return true;
        return !hasPhysicalKeyboard;
    }

    /**
     * Returns {@code true} when exiting/unpinning a local desktop or detaching client surfaces
     * must explicitly restore Android's {@code mTopFocusedDisplayId} back to {@code Display 0}
     * so hardware USB/Bluetooth mice never get stranded on an invisible {@code OWN_FOCUS}
     * {@code OpalWin-*} VirtualDisplay.
     */
    public static boolean shouldRestoreDefaultDisplayFocusOnLocalExit(
            boolean localDesktopPinned,
            int boundExternalDisplayId,
            boolean remoteDesktopActive) {
        return !localDesktopPinned && boundExternalDisplayId <= 0 && !remoteDesktopActive;
    }

    /**
     * Maps OpalDesk's continuous stepless pointer sensitivity ({@code 0.50f..3.00f}, default {@code 1.45f})
     * smoothly onto Android's hardware pointer speed integer scale ({@code -7..+7}, default {@code 0}).
     */
    public static int mapSensitivityToAndroidPointerSpeed(float sensitivity) {
        float clamped = Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, sensitivity));
        float norm;
        if (clamped <= DEFAULT_SENSITIVITY) {
            norm = (clamped - DEFAULT_SENSITIVITY) / (DEFAULT_SENSITIVITY - MIN_SENSITIVITY);
        } else {
            norm = (clamped - DEFAULT_SENSITIVITY) / (MAX_SENSITIVITY - DEFAULT_SENSITIVITY);
        }
        return Math.max(-7, Math.min(7, Math.round(norm * 7.0f)));
    }
}
