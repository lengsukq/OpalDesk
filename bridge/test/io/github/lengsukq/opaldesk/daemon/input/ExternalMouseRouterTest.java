package io.github.lengsukq.opaldesk.daemon.input;

import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExternalMouseRouterTest {

    @Test
    public void shouldRouteOnlyPhysicalExternalMice() {
        assertTrue(MouseRoutingPolicy.shouldRouteDevice(
                "BT5.0 Mouse", MouseRoutingPolicy.SOURCE_MOUSE, false, true));
        assertTrue(MouseRoutingPolicy.shouldRouteDevice(
                "Logitech MX Master 3S",
                MouseRoutingPolicy.SOURCE_MOUSE | MouseRoutingPolicy.SOURCE_KEYBOARD,
                false,
                true));

        // Reject virtual, internal, touchscreen, and OpalDesk's own virtual UHID pointer
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "OpalDesk Virtual Pointer 21", MouseRoutingPolicy.SOURCE_MOUSE, false, true));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "Virtual Mouse", MouseRoutingPolicy.SOURCE_MOUSE, true, true));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "NVTCapacitiveTouchScreen", MouseRoutingPolicy.SOURCE_TOUCHSCREEN, false, false));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "Built-in Trackpad", MouseRoutingPolicy.SOURCE_MOUSE, false, false));
    }

    @Test
    public void shouldRoutePhysicalExternalAlphabeticKeyboardsOnly() {
        // Real external Bluetooth / USB keyboard -> routed to external/casted display
        assertTrue(MouseRoutingPolicy.shouldRouteDevice(
                "Keychron K3 Pro",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                true));

        // Reject Bluetooth headset media buttons (non-alphabetic), built-in gpio-keys, and virtual keyboards
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "AirPods Pro AVRCP",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                1 /* KEYBOARD_TYPE_NON_ALPHABETIC */,
                false,
                true));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "gpio-keys",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                false));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "OpalDesk Virtual Keyboard 12",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                true));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "Virtual KB",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                true,
                true));
    }

    @Test
    public void sensitivityMapsSmoothlyToAndroidPointerSpeedRange() {
        assertEquals(-7, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(0.20f));
        assertEquals(-7, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(0.50f));
        assertEquals(0, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(1.45f));
        assertEquals(7, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(3.00f));
        assertEquals(7, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(5.00f));

        int slower = MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(0.95f);
        int faster = MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(2.25f);
        assertTrue("Expected negative speed below default, got " + slower, slower < 0 && slower > -7);
        assertTrue("Expected positive speed above default, got " + faster, faster > 0 && faster < 7);
    }

    @Test
    public void doubleTapWakeDetectorHandlesTouchAndHardwareWakeKey() {
        DoubleTapWakeDetector det = new DoubleTapWakeDetector();
        det.arm(1000L);

        // Inside arming grace window (350ms): must ignore residual touch-up
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 1100L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 1150L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 1200L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 1250L, true));

        // After grace window: two quick taps within 520ms -> wakes!
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 2000L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 2060L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 2220L, true));
        assertTrue(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 2290L, true));

        // Hardware KEY_WAKEUP from touch IC double-tap firmware wakes immediately
        det.arm(3000L);
        assertTrue(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.KEY_WAKEUP, 1, 3500L, true));

        // Long press (>360ms) must not trigger accidental wake
        det.arm(5000L);
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 5500L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 6200L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 6300L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 6350L, true));
    }

    @Test
    public void genericMotionFilterRejectsButtonPressReleaseAndNormalizesUsbMouseButtonState() {
        assertTrue(MouseRoutingPolicy.isInjectableGenericMotionAction(MouseRoutingPolicy.ACTION_HOVER_ENTER));
        assertTrue(MouseRoutingPolicy.isInjectableGenericMotionAction(MouseRoutingPolicy.ACTION_HOVER_MOVE));
        assertTrue(MouseRoutingPolicy.isInjectableGenericMotionAction(MouseRoutingPolicy.ACTION_HOVER_EXIT));
        assertTrue(MouseRoutingPolicy.isInjectableGenericMotionAction(MouseRoutingPolicy.ACTION_SCROLL));

        // ACTION_BUTTON_PRESS (11) & ACTION_BUTTON_RELEASE (12) must NEVER be injected via injectInputEvent
        assertFalse(MouseRoutingPolicy.isInjectableGenericMotionAction(MouseRoutingPolicy.ACTION_BUTTON_PRESS));
        assertFalse(MouseRoutingPolicy.isInjectableGenericMotionAction(MouseRoutingPolicy.ACTION_BUTTON_RELEASE));
        assertFalse(MouseRoutingPolicy.isInjectableGenericMotionAction(MouseRoutingPolicy.ACTION_DOWN));

        // Composite USB / OTG mice that report buttonState=0 on ACTION_DOWN/MOVE normalize to BUTTON_PRIMARY
        assertEquals(MouseRoutingPolicy.BUTTON_PRIMARY,
                MouseRoutingPolicy.normalizeMouseButtonState(MouseRoutingPolicy.ACTION_DOWN, true, 0));
        assertEquals(MouseRoutingPolicy.BUTTON_PRIMARY,
                MouseRoutingPolicy.normalizeMouseButtonState(MouseRoutingPolicy.ACTION_MOVE, true, 0));
        assertEquals(2 /* BUTTON_SECONDARY */,
                MouseRoutingPolicy.normalizeMouseButtonState(MouseRoutingPolicy.ACTION_DOWN, true, 2));
        assertEquals(0,
                MouseRoutingPolicy.normalizeMouseButtonState(MouseRoutingPolicy.ACTION_UP, true, 0));
        assertEquals(0,
                MouseRoutingPolicy.normalizeMouseButtonState(MouseRoutingPolicy.ACTION_DOWN, false, 0));

        // Primary left-button mouse clicks map to touch for web/H5 compatibility while hover/scroll/right-click stay mouse
        assertTrue(MouseRoutingPolicy.shouldMapPrimaryMouseClickToTouch(
                MouseRoutingPolicy.ACTION_DOWN, true, MouseRoutingPolicy.BUTTON_PRIMARY, false));
        assertTrue(MouseRoutingPolicy.shouldMapPrimaryMouseClickToTouch(
                MouseRoutingPolicy.ACTION_DOWN, true, 0, false));
        assertTrue(MouseRoutingPolicy.shouldMapPrimaryMouseClickToTouch(
                MouseRoutingPolicy.ACTION_MOVE, true, MouseRoutingPolicy.BUTTON_PRIMARY, true));
        assertTrue(MouseRoutingPolicy.shouldMapPrimaryMouseClickToTouch(
                MouseRoutingPolicy.ACTION_UP, true, 0, true));
        assertFalse(MouseRoutingPolicy.shouldMapPrimaryMouseClickToTouch(
                MouseRoutingPolicy.ACTION_DOWN, true, 2 /* BUTTON_SECONDARY */, false));
        assertFalse(MouseRoutingPolicy.shouldMapPrimaryMouseClickToTouch(
                MouseRoutingPolicy.ACTION_HOVER_MOVE, true, 0, false));
        assertFalse(MouseRoutingPolicy.shouldMapPrimaryMouseClickToTouch(
                MouseRoutingPolicy.ACTION_SCROLL, true, 0, false));

        // UHID pre-press positioning report prevents single-click-from-old-coord drag swallow
        assertTrue(MouseRoutingPolicy.needsPrePressPositionReport(0, 1, 100, 100, 420, 360));
        assertFalse(MouseRoutingPolicy.needsPrePressPositionReport(0, 1, 420, 360, 420, 360));
        assertFalse(MouseRoutingPolicy.needsPrePressPositionReport(1, 1, 100, 100, 420, 360));
        assertFalse(MouseRoutingPolicy.needsPrePressPositionReport(1, 0, 100, 100, 420, 360));

        // Exiting local desktop / detaching surfaces restores Display 0 focus so mice never vanish
        // into an invisible OWN_FOCUS VirtualDisplay, while leaving active external/cast displays intact
        assertTrue(MouseRoutingPolicy.shouldRestoreDefaultDisplayFocusOnLocalExit(false, -1, false));
        assertFalse(MouseRoutingPolicy.shouldRestoreDefaultDisplayFocusOnLocalExit(true, -1, false));
        assertFalse(MouseRoutingPolicy.shouldRestoreDefaultDisplayFocusOnLocalExit(false, 2, false));
        assertFalse(MouseRoutingPolicy.shouldRestoreDefaultDisplayFocusOnLocalExit(false, -1, true));
    }

    @Test
    public void physicalKeyboardPolicySelectsLocalWindowImeAndUhidKeyboardDelivery() {
        // Distinguish real physical alphabetic keyboards from mice, media buttons, built-in & UHID keyboards
        assertTrue(MouseRoutingPolicy.isPhysicalAlphabeticKeyboard(
                "Keychron K3 Pro", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, true));
        assertFalse(MouseRoutingPolicy.isPhysicalAlphabeticKeyboard(
                "BT5.0 Mouse", MouseRoutingPolicy.SOURCE_MOUSE, 0, false, true));
        assertFalse(MouseRoutingPolicy.isPhysicalAlphabeticKeyboard(
                "OpalDesk Virtual Keyboard 12", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, true));
        assertFalse(MouseRoutingPolicy.isPhysicalAlphabeticKeyboard(
                "AirPods AVRCP", MouseRoutingPolicy.SOURCE_KEYBOARD, 1, false, true));

        // Trusted OpalWin-* uses DISPLAY_IME_POLICY_LOCAL when physical keyboard is connected (local/external DeX)
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_LOCAL,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(true, false, true));
        // Without physical keyboard -> FALLBACK_DISPLAY so phone trackpad / touch pops soft keyboard on Display 0
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(false, false, true));
        // During remote PC/tablet casting -> always FALLBACK_DISPLAY so sender phone IME stays out of remote desktop
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(true, true, true));
        // Untrusted displays never request DISPLAY_IME_POLICY_LOCAL (avoids WMS SecurityException)
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(true, false, false));

        // Physical key events prefer per-display kernel UHID keyboard in AUTO & UHID modes (preserving positive deviceId
        // for CJK IME candidate bars), while honoring explicit INPUT_BACKEND_SYSTEM and skipping non-physical events
        assertTrue(MouseRoutingPolicy.shouldPreferUhidForPhysicalKey(
                BridgeProtocol.INPUT_BACKEND_AUTO, true, true));
        assertTrue(MouseRoutingPolicy.shouldPreferUhidForPhysicalKey(
                BridgeProtocol.INPUT_BACKEND_UHID, true, true));
        assertFalse(MouseRoutingPolicy.shouldPreferUhidForPhysicalKey(
                BridgeProtocol.INPUT_BACKEND_SYSTEM, true, true));
        assertFalse(MouseRoutingPolicy.shouldPreferUhidForPhysicalKey(
                BridgeProtocol.INPUT_BACKEND_AUTO, false, true));
        assertFalse(MouseRoutingPolicy.shouldPreferUhidForPhysicalKey(
                BridgeProtocol.INPUT_BACKEND_AUTO, true, false));

        // Do not fire Display 0 soft-keyboard fallback probe when physical keyboard owns local window IME
        assertFalse(MouseRoutingPolicy.shouldRunFallbackImeProbe(false, true, false));
        assertTrue(MouseRoutingPolicy.shouldRunFallbackImeProbe(false, false, false));
        assertTrue(MouseRoutingPolicy.shouldRunFallbackImeProbe(false, true, true));
        assertFalse(MouseRoutingPolicy.shouldRunFallbackImeProbe(true, false, false));
        assertFalse(MouseRoutingPolicy.shouldRunFallbackImeProbe(true, false, true));
    }

    @Test
    public void configurableDexImeModesAndPogoKeyboardDetectionBehaveDeterministically() {
        // Built-in tablet Pogo-Pin / dock keyboards are recognized for IME switching without matching phone gpio-keys
        assertTrue(MouseRoutingPolicy.isAlphabeticHardwareKeyboardForIme(
                "Xiaomi Pad Keyboard", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, false));
        assertTrue(MouseRoutingPolicy.isAlphabeticHardwareKeyboardForIme(
                "pogo-kbd", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, false));
        assertFalse(MouseRoutingPolicy.isAlphabeticHardwareKeyboardForIme(
                "gpio-keys", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, false));
        assertFalse(MouseRoutingPolicy.isAlphabeticHardwareKeyboardForIme(
                "OpalDesk Virtual Keyboard 4", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, false));

        // DEX_IME_DISPLAY_ALWAYS_LOCAL forces LOCAL even without physical keyboard, including remote cast
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_LOCAL,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(
                        false, false, true, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, false));
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_LOCAL,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(
                        false, true, true, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, true));
        // Untrusted VirtualDisplays still degrade safely to FALLBACK_DISPLAY to avoid WMS SecurityException
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(
                        false, false, false, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, false));

        // DEX_IME_DISPLAY_FALLBACK_HOST keeps FALLBACK_DISPLAY even with physical keyboard
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(
                        true, false, true, BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST, false));

        // muteHostImeOnCast applies DISPLAY_IME_POLICY_HIDE (2) during remote cast in AUTO mode
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_HIDE,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(
                        false, true, true, BridgeProtocol.DEX_IME_DISPLAY_AUTO, true));
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_HIDE,
                MouseRoutingPolicy.resolveRemoteOuterDisplayImePolicy(
                        BridgeProtocol.DEX_IME_DISPLAY_AUTO, true, true));
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_LOCAL,
                MouseRoutingPolicy.resolveRemoteOuterDisplayImePolicy(
                        BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, true, true));
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
                MouseRoutingPolicy.resolveRemoteOuterDisplayImePolicy(
                        BridgeProtocol.DEX_IME_DISPLAY_AUTO, false, true));

        // Physical external monitor forces LOCAL in ALWAYS_LOCAL even without physical keyboard
        assertTrue(MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                true, false, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL));
        assertFalse(MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                true, false, BridgeProtocol.DEX_IME_DISPLAY_AUTO));
        assertTrue(MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                true, true, BridgeProtocol.DEX_IME_DISPLAY_AUTO));
        assertFalse(MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                true, true, BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST));
        assertFalse(MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                false, true, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL));

        // Display 0 fallback IME probe is suppressed in ALWAYS_LOCAL so Display 0 never steals focus
        assertFalse(MouseRoutingPolicy.shouldRunFallbackImeProbe(
                false, false, false, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL));
        assertFalse(MouseRoutingPolicy.shouldRunFallbackImeProbe(
                false, false, true, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL));
        assertTrue(MouseRoutingPolicy.shouldRunFallbackImeProbe(
                false, true, false, BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST));
    }

    @Test
    public void sideNavigationMouseButtonTouchSequenceIsSuppressed() {
        assertTrue(MouseRoutingPolicy.isSideNavigationOnlyButtonState(MouseRoutingPolicy.BUTTON_BACK));
        assertTrue(MouseRoutingPolicy.isSideNavigationOnlyButtonState(MouseRoutingPolicy.BUTTON_FORWARD));
        assertTrue(MouseRoutingPolicy.isSideNavigationOnlyButtonState(
                MouseRoutingPolicy.BUTTON_BACK | MouseRoutingPolicy.BUTTON_FORWARD));
        assertFalse(MouseRoutingPolicy.isSideNavigationOnlyButtonState(0));
        assertFalse(MouseRoutingPolicy.isSideNavigationOnlyButtonState(MouseRoutingPolicy.BUTTON_PRIMARY));
        assertFalse(MouseRoutingPolicy.isSideNavigationOnlyButtonState(MouseRoutingPolicy.BUTTON_SECONDARY));
        assertFalse(MouseRoutingPolicy.isSideNavigationOnlyButtonState(MouseRoutingPolicy.BUTTON_TERTIARY));
        assertFalse(MouseRoutingPolicy.isSideNavigationOnlyButtonState(
                MouseRoutingPolicy.BUTTON_PRIMARY | MouseRoutingPolicy.BUTTON_BACK));

        // Standalone BUTTON_BACK / BUTTON_FORWARD click sequence must be suppressed across DOWN -> MOVE -> UP
        assertTrue(MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                MouseRoutingPolicy.ACTION_DOWN, true, MouseRoutingPolicy.BUTTON_BACK, false));
        assertTrue(MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                MouseRoutingPolicy.ACTION_MOVE, true, MouseRoutingPolicy.BUTTON_BACK, true));
        assertTrue(MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                MouseRoutingPolicy.ACTION_UP, true, 0, true));
        assertTrue(MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                MouseRoutingPolicy.ACTION_CANCEL, true, 0, true));

        // Non-mouse touch, hover/scroll, and primary/secondary mouse clicks remain unaffected
        assertFalse(MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                MouseRoutingPolicy.ACTION_DOWN, false, MouseRoutingPolicy.BUTTON_BACK, false));
        assertFalse(MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                MouseRoutingPolicy.ACTION_DOWN, true, MouseRoutingPolicy.BUTTON_PRIMARY, false));
        assertFalse(MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                MouseRoutingPolicy.ACTION_DOWN, true, MouseRoutingPolicy.BUTTON_SECONDARY, false));
        assertFalse(MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                MouseRoutingPolicy.ACTION_HOVER_MOVE, true, MouseRoutingPolicy.BUTTON_BACK, true));
        assertFalse(MouseRoutingPolicy.shouldSuppressSideNavigationMouseTouch(
                MouseRoutingPolicy.ACTION_SCROLL, true, MouseRoutingPolicy.BUTTON_BACK, true));
    }

    @Test
    public void purePhysicalKeyboardsRouteDirectlyToFocusedWindowToPreventImePingPong() {
        // Pure Bluetooth / USB / Pogo keyboards qualify for direct focused-window association
        assertTrue(MouseRoutingPolicy.isPurePhysicalKeyboardForWindowRouting(
                "T88-BT2 Keyboard", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, true));
        assertTrue(MouseRoutingPolicy.isPurePhysicalKeyboardForWindowRouting(
                "Xiaomi Pad Keyboard", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, false));

        // Combo mouse+keyboard devices stay anchored on outer display so the mouse pointer never traps offscreen
        assertFalse(MouseRoutingPolicy.isPurePhysicalKeyboardForWindowRouting(
                "Logitech MX Combo",
                MouseRoutingPolicy.SOURCE_MOUSE | MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                true));
        assertFalse(MouseRoutingPolicy.isPurePhysicalKeyboardForWindowRouting(
                "BT5.0 Mouse", MouseRoutingPolicy.SOURCE_MOUSE, 0, false, true));
        assertFalse(MouseRoutingPolicy.isPurePhysicalKeyboardForWindowRouting(
                "OpalDesk Virtual Keyboard 82", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, true));
        assertFalse(MouseRoutingPolicy.isPurePhysicalKeyboardForWindowRouting(
                "gpio-keys", MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, false));

        // Route directly to focused OpalWin-* when workstation active, local/external (not remote cast),
        // no local OpalDesk TextField active, and not forced to FALLBACK_HOST
        assertTrue(MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                true, false, false, BridgeProtocol.DEX_IME_DISPLAY_AUTO, 82, true));
        assertTrue(MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                true, false, false, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, 82, true));

        // Must immediately yield back to outer display / host whenever local search box opens,
        // remote cast is active, FALLBACK_HOST is chosen, workstation exits, or no window is focused
        assertFalse(MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                true, false, true, BridgeProtocol.DEX_IME_DISPLAY_AUTO, 82, true));
        assertFalse(MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                true, true, false, BridgeProtocol.DEX_IME_DISPLAY_AUTO, 82, true));
        assertFalse(MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                true, false, false, BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST, 82, true));
        assertFalse(MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                false, false, false, BridgeProtocol.DEX_IME_DISPLAY_AUTO, 82, true));
        assertFalse(MouseRoutingPolicy.shouldRoutePhysicalKeyboardToFocusedWindow(
                true, false, false, BridgeProtocol.DEX_IME_DISPLAY_AUTO, -1, false));
    }

    @Test
    public void compositeBluetoothKeyboardsAndAuxiliaryMultiAxisNodesRouteCleanlyForIme() {
        // Keyboard auxiliary knob / multi-axis HID sub-devices must NEVER be classified as physical mice
        assertTrue(MouseRoutingPolicy.isAuxiliaryKeyboardMediaSubDevice("T88-BT2 System Multi Axis"));
        assertTrue(MouseRoutingPolicy.isAuxiliaryKeyboardMediaSubDevice("Keychron Consumer Control"));
        assertFalse(MouseRoutingPolicy.isPhysicalMouse(
                "T88-BT2 System Multi Axis", MouseRoutingPolicy.SOURCE_MOUSE, false, true));
        assertFalse(MouseRoutingPolicy.isDedicatedPhysicalMouse(
                "T88-BT2 System Multi Axis", MouseRoutingPolicy.SOURCE_MOUSE, 0, false, true));

        // Dedicated mice still qualify as physical mice & dedicated mice
        assertTrue(MouseRoutingPolicy.isDedicatedPhysicalMouse(
                "BT5.0 Mouse", MouseRoutingPolicy.SOURCE_MOUSE, 0, false, true));
        assertTrue(MouseRoutingPolicy.isDedicatedPhysicalMouse(
                "Logitech MX Master 3S",
                MouseRoutingPolicy.SOURCE_MOUSE | MouseRoutingPolicy.SOURCE_KEYBOARD,
                1 /* KEYBOARD_TYPE_NON_ALPHABETIC */,
                false,
                true));

        // Composite BT mechanical keyboards like T88-BT2 that expose SOURCE_MOUSE alongside
        // KEYBOARD_TYPE_ALPHABETIC route directly to the focused OpalWin-* with OR without a separate mouse
        int compositeSources = MouseRoutingPolicy.SOURCE_KEYBOARD | MouseRoutingPolicy.SOURCE_MOUSE;
        assertTrue(MouseRoutingPolicy.isPhysicalKeyboardForWindowRouting(
                "T88-BT2 Keyboard", compositeSources,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, true, true));
        assertTrue(MouseRoutingPolicy.isPhysicalKeyboardForWindowRouting(
                "T88-BT2 Keyboard", compositeSources,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC, false, true, false));

        // Genuine keyboard+touchpad combos stay on outer display when they are the sole pointer,
        // but route directly to the focused window whenever a separate dedicated mouse is connected
        assertFalse(MouseRoutingPolicy.isPhysicalKeyboardForWindowRouting(
                "Logitech K400 Touchpad",
                compositeSources | MouseRoutingPolicy.SOURCE_TOUCHPAD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                true,
                false));
        assertFalse(MouseRoutingPolicy.isPhysicalKeyboardForWindowRouting(
                "Logitech MX Combo",
                compositeSources,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                true,
                false));
        assertTrue(MouseRoutingPolicy.isPhysicalKeyboardForWindowRouting(
                "Logitech K400 Touchpad",
                compositeSources | MouseRoutingPolicy.SOURCE_TOUCHPAD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                true,
                true));
        assertFalse(MouseRoutingPolicy.isPhysicalKeyboardForWindowRouting(
                "BT5.0 Mouse", MouseRoutingPolicy.SOURCE_MOUSE, 0, false, true, true));
    }

    @Test
    public void remoteVirtualCastDisplaysIsolateHostPhysicalPeripherals() {
        assertTrue(MouseRoutingPolicy.isVirtualCastDisplayUniqueId("virtual:com.android.shell,2000,OpalDesk-Remote,68"));
        assertFalse(MouseRoutingPolicy.isVirtualCastDisplayUniqueId("local:4630947134992368259"));

        // Physical HDMI/USB-C external monitor accepts host peripheral routing in EXTERNAL mode
        assertTrue(MouseRoutingPolicy.shouldRoutePhysicalPeripheralsToDisplay(
                BridgeProtocol.MOUSE_TARGET_EXTERNAL, 5, "local:4630947134992368259", 1));
        // Virtual OpalCast displays MUST NOT hijack host USB/Bluetooth mice/keyboards
        assertFalse(MouseRoutingPolicy.shouldRoutePhysicalPeripheralsToDisplay(
                BridgeProtocol.MOUSE_TARGET_EXTERNAL, 83, "virtual:com.android.shell,2000,OpalDesk-Remote,68", -1));
        // LOCAL mode never routes to external displays
        assertFalse(MouseRoutingPolicy.shouldRoutePhysicalPeripheralsToDisplay(
                BridgeProtocol.MOUSE_TARGET_LOCAL, 5, "local:4630947134992368259", 1));
    }

    @Test
    public void portableTouchscreenExternalMonitorRoutesSoftImeToExternalDisplayHost() {
        // External USB/HDMI portable touchscreen classification rejects built-in & virtual screens
        assertTrue(MouseRoutingPolicy.isExternalTouchscreen(
                "WingCool Touch", MouseRoutingPolicy.SOURCE_TOUCHSCREEN, false, true));
        assertFalse(MouseRoutingPolicy.isExternalTouchscreen(
                "fts_ts", MouseRoutingPolicy.SOURCE_TOUCHSCREEN, false, false));
        assertFalse(MouseRoutingPolicy.isExternalTouchscreen(
                "OpalDesk Virtual Touch 1", MouseRoutingPolicy.SOURCE_TOUCHSCREEN, false, true));
        assertFalse(MouseRoutingPolicy.isExternalTouchscreen(
                "BT5.0 Mouse", MouseRoutingPolicy.SOURCE_MOUSE, false, true));

        // Physical external monitor forces LOCAL in AUTO when an external touchscreen is present
        assertTrue(MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                true, false, true, BridgeProtocol.DEX_IME_DISPLAY_AUTO));
        assertFalse(MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                true, false, false, BridgeProtocol.DEX_IME_DISPLAY_AUTO));
        assertTrue(MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                true, false, false, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL));
        assertFalse(MouseRoutingPolicy.shouldForcePhysicalExternalDisplayLocalIme(
                true, false, true, BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST));

        // Without physical keyboard on an external monitor, OpalWin-* keeps FALLBACK_DISPLAY so
        // soft IME routes onto the physical external monitor's NativeImeBridgeHost instead of
        // rendering inside an offscreen VirtualDisplay without system decors.
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(
                        false, false, true, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, false, true));
        assertEquals(BridgeProtocol.DISPLAY_IME_POLICY_LOCAL,
                MouseRoutingPolicy.resolveWindowDisplayImePolicy(
                        true, false, true, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, false, true));

        // Fallback IME probe stays active for touchscreen external monitors even in ALWAYS_LOCAL
        assertTrue(MouseRoutingPolicy.shouldRunFallbackImeProbe(
                false, false, false, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, true));
        assertFalse(MouseRoutingPolicy.shouldRunFallbackImeProbe(
                false, true, false, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL, true));

        // Override Display 0 fallback IME onto the external monitor when ALWAYS_LOCAL or extTouch
        assertTrue(MouseRoutingPolicy.shouldBridgeHostImeWhenSystemInputShown(
                false, true, false, false, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL));
        assertTrue(MouseRoutingPolicy.shouldBridgeHostImeWhenSystemInputShown(
                false, true, false, true, BridgeProtocol.DEX_IME_DISPLAY_AUTO));
        assertFalse(MouseRoutingPolicy.shouldBridgeHostImeWhenSystemInputShown(
                false, true, false, false, BridgeProtocol.DEX_IME_DISPLAY_AUTO));
        assertFalse(MouseRoutingPolicy.shouldBridgeHostImeWhenSystemInputShown(
                false, true, false, true, BridgeProtocol.DEX_IME_DISPLAY_FALLBACK_HOST));
        assertFalse(MouseRoutingPolicy.shouldBridgeHostImeWhenSystemInputShown(
                false, true, true, true, BridgeProtocol.DEX_IME_DISPLAY_ALWAYS_LOCAL));
    }

    @Test
    public void passiveHoverClassificationAndDisplay0MousePinningPreventGhostCursors() {
        assertTrue(MouseRoutingPolicy.isPassiveHoverAction(MouseRoutingPolicy.ACTION_HOVER_ENTER));
        assertTrue(MouseRoutingPolicy.isPassiveHoverAction(MouseRoutingPolicy.ACTION_HOVER_MOVE));
        assertTrue(MouseRoutingPolicy.isPassiveHoverAction(MouseRoutingPolicy.ACTION_HOVER_EXIT));
        assertFalse(MouseRoutingPolicy.isPassiveHoverAction(MouseRoutingPolicy.ACTION_SCROLL));
        assertFalse(MouseRoutingPolicy.isPassiveHoverAction(MouseRoutingPolicy.ACTION_DOWN));
        assertFalse(MouseRoutingPolicy.isPassiveHoverAction(MouseRoutingPolicy.ACTION_MOVE));
        assertFalse(MouseRoutingPolicy.isPassiveHoverAction(MouseRoutingPolicy.ACTION_UP));

        // Pin physical USB/Bluetooth mice to Display 0 during Local Desktop, PC Cast, and External LOCAL mode
        assertTrue(MouseRoutingPolicy.shouldPinPhysicalMiceToDisplay0(
                true, false, false, BridgeProtocol.MOUSE_TARGET_EXTERNAL));
        assertTrue(MouseRoutingPolicy.shouldPinPhysicalMiceToDisplay0(
                false, true, false, BridgeProtocol.MOUSE_TARGET_EXTERNAL));
        assertTrue(MouseRoutingPolicy.shouldPinPhysicalMiceToDisplay0(
                false, false, true, BridgeProtocol.MOUSE_TARGET_LOCAL));

        // Do NOT pin Display 0 when routing to an external monitor or when no OpalDesk desktop session is active
        assertFalse(MouseRoutingPolicy.shouldPinPhysicalMiceToDisplay0(
                false, false, true, BridgeProtocol.MOUSE_TARGET_EXTERNAL));
        assertFalse(MouseRoutingPolicy.shouldPinPhysicalMiceToDisplay0(
                false, false, false, BridgeProtocol.MOUSE_TARGET_LOCAL));
        assertFalse(MouseRoutingPolicy.shouldPinPhysicalMiceToDisplay0(
                false, false, false, BridgeProtocol.MOUSE_TARGET_EXTERNAL));
    }
}
