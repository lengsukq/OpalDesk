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
    /** Matches {@code android.view.InputDevice.SOURCE_TOUCHPAD} (0x00100008). */
    public static final int SOURCE_TOUCHPAD = 0x00100008;
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
     * Returns {@code true} for passive pointer hover actions ({@code HOVER_ENTER/MOVE/EXIT})
     * that must neither spawn a cursorless UHID device nor permanently latch global AUTO-&gt;UHID
     * fallback when a newly launched {@code VirtualDisplay} transiently rejects hover before its
     * first window finishes registering.
     */
    public static boolean isPassiveHoverAction(int actionMasked) {
        return actionMasked == ACTION_HOVER_MOVE
                || actionMasked == ACTION_HOVER_ENTER
                || actionMasked == ACTION_HOVER_EXIT;
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
     * Identifies non-mouse auxiliary HID sub-devices exposed alongside Bluetooth / 2.4GHz mechanical
     * keyboards (rotary knobs, volume dials, consumer/system multi-axis collections) so they never
     * masquerade as dedicated physical mice or overwrite a keyboard's window-routing association.
     */
    public static boolean isAuxiliaryKeyboardMediaSubDevice(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("system multi axis")
                || lower.contains("consumer control")
                || lower.contains("system control");
    }

    /**
     * Decides whether an InputDevice represents a real physical external mouse
     * (excluding built-in devices, virtual devices, auxiliary keyboard knob/media sub-devices,
     * and OpalDesk's own UHID devices).
     */
    public static boolean isPhysicalMouse(String name, int sources, boolean isVirtual, boolean isExternal) {
        if (isVirtual || !isExternal) return false;
        if (name != null && name.startsWith("OpalDesk Virtual")) return false;
        if (isAuxiliaryKeyboardMediaSubDevice(name)) return false;
        return (sources & SOURCE_MOUSE) == SOURCE_MOUSE;
    }

    /**
     * Decides whether an InputDevice is a dedicated physical mouse/trackpad (not an alphabetic
     * keyboard) so the compositor knows a separate hardware pointer already drives the outer monitor.
     */
    public static boolean isDedicatedPhysicalMouse(
            String name, int sources, int keyboardType, boolean isVirtual, boolean isExternal) {
        return isPhysicalMouse(name, sources, isVirtual, isExternal)
                && !isAlphabeticHardwareKeyboardForIme(name, sources, keyboardType, isVirtual, isExternal);
    }

    private static boolean hasPointerOrTouchpadName(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("touchpad")
                || lower.contains("trackpad")
                || lower.contains("mouse")
                || lower.contains("pointer")
                || lower.contains("combo");
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
     * Decides whether an InputDevice represents an external physical touchscreen (such as a USB-C /
     * HDMI portable touchscreen monitor), excluding OpalDesk's own virtual UHID devices.
     */
    public static boolean isExternalTouchscreen(
            String name, int sources, boolean isVirtual, boolean isExternal) {
        if (isVirtual || !isExternal) return false;
        if (name != null && name.startsWith("OpalDesk Virtual")) return false;
        return (sources & SOURCE_TOUCHSCREEN) == SOURCE_TOUCHSCREEN;
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
     * Pogo-Pin dock) that do NOT also report {@code SOURCE_MOUSE}.
     */
    public static boolean isPurePhysicalKeyboardForWindowRouting(
            String name, int sources, int keyboardType, boolean isVirtual, boolean isExternal) {
        return isAlphabeticHardwareKeyboardForIme(name, sources, keyboardType, isVirtual, isExternal)
                && !isPhysicalMouse(name, sources, isVirtual, isExternal);
    }

    /**
     * Returns {@code true} when a physical alphabetic keyboard should be treated as a standalone
     * window keyboard (rather than an outer-display mouse/touchpad).
     *
     * <p>Covers both pure keyboards AND composite Bluetooth/2.4GHz keyboards (such as mechanical
     * keyboards with a knob / {@code System Multi Axis} / boot-mouse HID descriptor that also set
     * {@code SOURCE_MOUSE}). Genuine keyboard+touchpad combos stay on the outer display ONLY when
     * no separate dedicated physical mouse is connected, preventing pointer trapping.
     */
    public static boolean isPhysicalKeyboardForWindowRouting(
            String name,
            int sources,
            int keyboardType,
            boolean isVirtual,
            boolean isExternal,
            boolean hasDedicatedMouse) {
        if (!isAlphabeticHardwareKeyboardForIme(name, sources, keyboardType, isVirtual, isExternal)) {
            return false;
        }
        if (!isPhysicalMouse(name, sources, isVirtual, isExternal)) {
            return true;
        }
        if (hasDedicatedMouse) {
            return true;
        }
        boolean isTouchpad = (sources & SOURCE_TOUCHPAD) == SOURCE_TOUCHPAD;
        return !isTouchpad && !hasPointerOrTouchpadName(name);
    }

    /**
     * Returns {@code true} when pure physical keyboards should be associated directly with the
     * focused {@code OpalWin-*} VirtualDisplay instead of the outer desktop/external display.
     */
    public static boolean shouldRoutePhysicalKeyboardToFocusedWindow(
            boolean workstationDisplayActive,
            boolean remoteDesktopActive,
            boolean localImeActive,
            int dexImeDisplayMode,
            int focusedWindowDisplayId,
            boolean hasFocusedWindowUniqueId) {
        return shouldRoutePhysicalKeyboardToFocusedWindow(
                workstationDisplayActive,
                remoteDesktopActive,
                localImeActive,
                dexImeDisplayMode,
                focusedWindowDisplayId,
                hasFocusedWindowUniqueId,
                false);
    }

    /**
     * Returns {@code true} when pure physical keyboards should be associated directly with the
     * focused {@code OpalWin-*} VirtualDisplay.
     *
     * <p>Even when {@code bridgeImeActive} ({@code OpalYuyanImeService} / {@code OpalDeskImeService})
     * is active, physical Bluetooth/USB keyboards MUST be associated directly with the focused
     * {@code OpalWin-*} VirtualDisplay while a DeX window is active: on Android 14–17 / HyperOS,
     * leaving Bluetooth keyboards unassociated while {@code DesktopActivity} carries
     * {@code FLAG_NOT_FOCUSABLE} causes keystrokes to route to Display 0 ({@code mCurrentFocus=null})
     * instead of entering the VirtualDisplay's {@code InputMethodService.onKeyDown()}.
     * Whenever {@code localImeActive} becomes {@code true} (e.g. OpalDesk search box) or the
     * workstation exits, this returns {@code false} and immediately unbinds back to Display 0.
     */
    public static boolean shouldRoutePhysicalKeyboardToFocusedWindow(
            boolean workstationDisplayActive,
            boolean remoteDesktopActive,
            boolean localImeActive,
            int dexImeDisplayMode,
            int focusedWindowDisplayId,
            boolean hasFocusedWindowUniqueId,
            boolean bridgeImeActive) {
        return shouldRoutePhysicalKeyboardToFocusedWindow(
                workstationDisplayActive,
                remoteDesktopActive,
                localImeActive,
                dexImeDisplayMode,
                focusedWindowDisplayId,
                hasFocusedWindowUniqueId,
                bridgeImeActive,
                false);
    }

    /**
     * When {@code hasEnabledKeyBindings} is {@code true}, keeps physical keyboards routed to the
     * outer compositor display instead of directly binding them to {@code OpalWin-*} so the
     * compositor can intercept custom keyboard bindings first and forward all non-mapped typing
     * keys into {@code OpalWin-*} via kernel {@code /dev/uhid} (allowing Yuyan IME typing and
     * custom key remapping to work simultaneously without opening the control center).
     */
    public static boolean shouldRoutePhysicalKeyboardToFocusedWindow(
            boolean workstationDisplayActive,
            boolean remoteDesktopActive,
            boolean localImeActive,
            int dexImeDisplayMode,
            int focusedWindowDisplayId,
            boolean hasFocusedWindowUniqueId,
            boolean bridgeImeActive,
            boolean hasEnabledKeyBindings) {
        if (hasEnabledKeyBindings) {
            return false;
        }
        if (!workstationDisplayActive || remoteDesktopActive || localImeActive) {
            return false;
        }
        if (dexImeDisplayMode == 2 /* BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST */) {
            return false;
        }
        return focusedWindowDisplayId > 0 && hasFocusedWindowUniqueId;
    }

    /**
     * Decides whether a candidate peripheral should be bound to {@code outerDisplayId} via
     * {@code UniqueIdAssociation}.
     *
     * <p>When {@code bridgeImeActive} is {@code true} (and the host compositor window carries
     * {@code FLAG_NOT_FOCUSABLE}), ONLY pointer-bearing devices that are NOT standalone/composite
     * window keyboards (i.e. dedicated mice, or sole-pointer keyboard+touchpad combos) bind to
     * {@code outerDisplayId}; pure/composite alphabetic keyboards stay unassociated so their keys
     * follow {@code mTopFocusedDisplayId} directly instead of dropping on an unfocusable host.
     */
    public static boolean shouldAssociateDeviceToOuterDisplay(
            boolean isMouse,
            boolean isWindowKbd,
            boolean isExternal,
            int keyboardType,
            boolean bridgeImeActive) {
        return shouldAssociateDeviceToOuterDisplay(
                isMouse, isWindowKbd, isExternal, keyboardType, bridgeImeActive, false);
    }

    public static boolean shouldAssociateDeviceToOuterDisplay(
            boolean isMouse,
            boolean isWindowKbd,
            boolean isExternal,
            int keyboardType,
            boolean bridgeImeActive,
            boolean hasEnabledKeyBindings) {
        if (hasEnabledKeyBindings) {
            return isMouse || (isExternal && keyboardType == KEYBOARD_TYPE_ALPHABETIC);
        }
        if (bridgeImeActive) {
            return isMouse && !isWindowKbd;
        }
        return isMouse || (isExternal && keyboardType == KEYBOARD_TYPE_ALPHABETIC);
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
        return resolveWindowDisplayImePolicy(
                hasPhysicalKeyboard,
                remoteDesktopActive,
                displayIsTrusted,
                dexImeDisplayMode,
                muteHostImeOnCast,
                false,
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
            boolean muteHostImeOnCast,
            boolean hasPhysicalExternalDisplay) {
        return resolveWindowDisplayImePolicy(
                hasPhysicalKeyboard,
                remoteDesktopActive,
                displayIsTrusted,
                dexImeDisplayMode,
                muteHostImeOnCast,
                hasPhysicalExternalDisplay,
                false);
    }

    /**
     * Resolves the Android {@code DisplayImePolicy} ({@code 0 = LOCAL}, {@code 1 = FALLBACK_DISPLAY},
     * {@code 2 = HIDE}) for a managed {@code OpalWin-*} VirtualDisplay.
     *
     * <p>When {@code bridgeImeActive} ({@code OpalDeskImeService}) is active on a trusted display,
     * ALWAYS returns {@code DISPLAY_IME_POLICY_LOCAL (0)}: {@code OpalDeskImeService} renders a
     * {@code 0x0} invisible view (zero window occlusion), while {@code LOCAL} keeps IMMS bound
     * directly to {@code ws.displayId > 0} (distinguishing DeX windows from Display 0 phone apps)
     * and completely bypasses Android 16/17 {@code RemoteInsetsControlTarget} fallback aborts.
     */
    public static int resolveWindowDisplayImePolicy(
            boolean hasPhysicalKeyboard,
            boolean remoteDesktopActive,
            boolean displayIsTrusted,
            int dexImeDisplayMode,
            boolean muteHostImeOnCast,
            boolean hasPhysicalExternalDisplay,
            boolean bridgeImeActive) {
        if (bridgeImeActive && displayIsTrusted) {
            return 0; // BridgeProtocol.DISPLAY_IME_POLICY_LOCAL
        }
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
        if (hasPhysicalExternalDisplay && !hasPhysicalKeyboard) {
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
        return resolveRemoteOuterDisplayImePolicy(
                dexImeDisplayMode, muteHostImeOnCast, displayIsTrusted, false);
    }

    /**
     * Resolves the Android {@code DisplayImePolicy} for an outer remote OpalCast VirtualDisplay
     * ({@code OpalDesk-Remote} or {@code OpalDesk-App-*}), using {@code LOCAL} whenever the
     * invisible {@code OpalDeskImeService} bus is active on a trusted VirtualDisplay.
     */
    public static int resolveRemoteOuterDisplayImePolicy(
            int dexImeDisplayMode,
            boolean muteHostImeOnCast,
            boolean displayIsTrusted,
            boolean bridgeImeActive) {
        if (bridgeImeActive && displayIsTrusted) {
            return 0; // BridgeProtocol.DISPLAY_IME_POLICY_LOCAL
        }
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
     * Returns {@code true} when connected physical USB/Bluetooth mice must be explicitly pinned to
     * {@code Display 0} rather than left unassociated ({@code ADISPLAY_ID_NONE}).
     *
     * <p>Leaving physical mice unassociated while OpalDesk focuses {@code OpalWin-*} VirtualDisplays
     * allows Android 14–17 {@code PointerChoreographer} to teleport the hardware cursor onto
     * {@code mTopFocusedDisplayId} (inside the app window's framebuffer), causing a duplicate cursor
     * during local DeX, PC Cast, and external-monitor {@code LOCAL} mouse mode.
     */
    public static boolean shouldPinPhysicalMiceToDisplay0(
            boolean localDesktopPinned,
            boolean remoteDesktopActive,
            boolean hasObservedExternalDisplay,
            int mouseTargetMode) {
        if (localDesktopPinned || remoteDesktopActive) {
            return true;
        }
        return hasObservedExternalDisplay && mouseTargetMode == 0 /* BridgeProtocol.MOUSE_TARGET_LOCAL */;
    }

    /**
     * Resolves whether a physical external monitor ({@code HDMI / USB-C / Miracast}) should be
     * explicitly overridden to {@code DISPLAY_IME_POLICY_LOCAL (0)} by {@code ExternalMouseRouter}.
     */
    public static boolean shouldForcePhysicalExternalDisplayLocalIme(
            boolean isPhysicalExternal,
            boolean hasPhysicalKeyboard,
            int dexImeDisplayMode) {
        return shouldForcePhysicalExternalDisplayLocalIme(
                isPhysicalExternal, hasPhysicalKeyboard, false, dexImeDisplayMode);
    }

    /**
     * Resolves whether a physical external monitor ({@code HDMI / USB-C / Miracast}) should be
     * explicitly overridden to {@code DISPLAY_IME_POLICY_LOCAL (0)} by {@code ExternalMouseRouter}.
     *
     * <p>In {@code AUTO} mode, attaches {@code LOCAL} policy whenever either a physical keyboard OR
     * an external portable touchscreen is connected so touch users type directly on the monitor.
     */
    public static boolean shouldForcePhysicalExternalDisplayLocalIme(
            boolean isPhysicalExternal,
            boolean hasPhysicalKeyboard,
            boolean hasExternalTouchscreen,
            int dexImeDisplayMode) {
        if (!isPhysicalExternal) return false;
        if (dexImeDisplayMode == 1 /* DEX_IME_DISPLAY_ALWAYS_LOCAL */) return true;
        if (dexImeDisplayMode == 2 /* DEX_IME_DISPLAY_FALLBACK_HOST */) return false;
        return hasPhysicalKeyboard || hasExternalTouchscreen;
    }

    /**
     * Returns {@code true} when a physical keyboard {@code KeyEvent} targeting an isolated
     * {@code VirtualDisplay} should prefer Linux {@code /dev/uhid} keyboard injection over
     * {@code IInputManager.injectInputEvent}.
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
     */
    public static boolean shouldRunFallbackImeProbe(
            boolean localImeActive,
            boolean hasPhysicalKeyboard,
            boolean remoteDesktopActive,
            int dexImeDisplayMode) {
        return shouldRunFallbackImeProbe(
                localImeActive, hasPhysicalKeyboard, remoteDesktopActive, dexImeDisplayMode, false, false);
    }

    /**
     * Returns {@code true} when {@code OpalDeskDaemon} should probe {@code dumpsys input_method}
     * after a tap inside a window, taking the user's DeX IME display mode and physical external
     * monitor state into account.
     */
    public static boolean shouldRunFallbackImeProbe(
            boolean localImeActive,
            boolean hasPhysicalKeyboard,
            boolean remoteDesktopActive,
            int dexImeDisplayMode,
            boolean hasPhysicalExternalDisplay) {
        return shouldRunFallbackImeProbe(
                localImeActive, hasPhysicalKeyboard, remoteDesktopActive, dexImeDisplayMode,
                hasPhysicalExternalDisplay, false);
    }

    /**
     * Returns {@code true} when {@code OpalDeskDaemon} should probe {@code dumpsys input_method}
     * after a tap inside a window. Never runs when {@code bridgeImeActive} ({@code OpalDeskImeService})
     * is active, because {@code OpalDeskImeService.onShowInputRequested} delivers 0ms deterministic
     * IME events directly without {@code dumpsys} polling.
     */
    public static boolean shouldRunFallbackImeProbe(
            boolean localImeActive,
            boolean hasPhysicalKeyboard,
            boolean remoteDesktopActive,
            int dexImeDisplayMode,
            boolean hasPhysicalExternalDisplay,
            boolean bridgeImeActive) {
        if (bridgeImeActive || localImeActive) return false;
        if (dexImeDisplayMode == 1 /* DEX_IME_DISPLAY_ALWAYS_LOCAL */) {
            return hasPhysicalExternalDisplay && !remoteDesktopActive && !hasPhysicalKeyboard;
        }
        if (remoteDesktopActive) return true;
        if (dexImeDisplayMode == 2 /* DEX_IME_DISPLAY_FALLBACK_HOST */) return true;
        return !hasPhysicalKeyboard;
    }

    /**
     * Returns {@code true} when {@code OpalDeskDaemon} should summon the host-side IME bridge
     * even if {@code dumpsys input_method} reports {@code mInputShown=true}.
     */
    public static boolean shouldBridgeHostImeWhenSystemInputShown(
            boolean remoteDesktopActive,
            boolean hasPhysicalExternalDisplay,
            boolean hasPhysicalKeyboard,
            boolean hasExternalTouchscreen,
            int dexImeDisplayMode) {
        if (remoteDesktopActive) return true;
        if (!hasPhysicalExternalDisplay || hasPhysicalKeyboard) return false;
        if (dexImeDisplayMode == 1 /* DEX_IME_DISPLAY_ALWAYS_LOCAL */) return true;
        if (dexImeDisplayMode == 2 /* DEX_IME_DISPLAY_FALLBACK_HOST */) return false;
        return hasExternalTouchscreen;
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
