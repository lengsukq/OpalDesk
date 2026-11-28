package io.github.lengsukq.opaldesk.daemon.input;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VolumeKeyWakeDetectorTest {

    private static byte[] encodeRawEvent64(int type, int code, int value) {
        byte[] buf = new byte[VolumeKeyWakeDetector.INPUT_EVENT_SIZE_64];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(16, (short) type);
        bb.putShort(18, (short) code);
        bb.putInt(20, value);
        return buf;
    }

    @Test
    public void singleVolumeUpOrDownPressDoesNotTriggerWake() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2000L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0, 2080L));

        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 1, 2200L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 0, 2280L));
    }

    @Test
    public void doublePressVolumeUpWithin450msTriggersWake() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2000L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0, 2090L));
        assertTrue(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2450L));
    }

    @Test
    public void doublePressVolumeUpBeyond450msDoesNotTriggerWake() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2000L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0, 2080L));
        // 451ms later: expired
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2451L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0, 2520L));
        // Follow-up press within 450ms of the second press (2451ms) should wake
        assertTrue(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2800L));
    }

    @Test
    public void heldVolumeUpRepeatEventsDoNotFalseTriggerDoublePress() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2000L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 2, 2150L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 2, 2250L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0, 2350L));
    }

    @Test
    public void simultaneousVolumeUpAndVolumeDownTriggersWakeInEitherOrder() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        // Volume+ then Volume-
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2000L));
        assertTrue(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 1, 2060L));

        // After wake resets state, Volume- then Volume+ also wakes
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 1, 3000L));
        assertTrue(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 3090L));
    }

    @Test
    public void nonOverlappingVolumeUpAndVolumeDownDoesNotTriggerComboWake() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2000L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0, 2050L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 1, 2100L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 0, 2150L));
    }

    @Test
    public void graceWindowAfterArmSuppressesImmediateEvents() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 1100L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 1, 1150L));
    }

    @Test
    public void rawEvent64ParsesVolumeUpDoublePressAndCombo() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        byte[] upDown = encodeRawEvent64(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1);
        byte[] upUp = encodeRawEvent64(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0);
        byte[] downDown = encodeRawEvent64(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 1);

        assertFalse(detector.onRawEvent64(upDown, 0, 2000L));
        assertFalse(detector.onRawEvent64(upUp, 0, 2080L));
        assertTrue(detector.onRawEvent64(upDown, 0, 2320L));

        assertFalse(detector.onRawEvent64(downDown, 0, 3000L));
        assertTrue(detector.onRawEvent64(upDown, 0, 3050L));
    }

    @Test
    public void ignoresNonVolumeKeysAndNonKeyEvents() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        // Power key (116) must not trigger volume detector
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, DoubleTapWakeDetector.KEY_POWER, 1, 2000L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, DoubleTapWakeDetector.KEY_POWER, 0, 2050L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, DoubleTapWakeDetector.KEY_POWER, 1, 2100L));
        // EV_ABS with code 115 must not trigger
        assertFalse(detector.onEvent(DoubleTapWakeDetector.EV_ABS, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2200L));
        assertFalse(detector.onEvent(DoubleTapWakeDetector.EV_ABS, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 1, 2220L));
    }
}
