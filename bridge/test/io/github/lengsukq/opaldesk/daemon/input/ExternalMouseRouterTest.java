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
}
