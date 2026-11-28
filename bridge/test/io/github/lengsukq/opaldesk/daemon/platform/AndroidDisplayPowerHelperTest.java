package io.github.lengsukq.opaldesk.daemon.platform;

import io.github.lengsukq.opaldesk.daemon.input.DoubleTapWakeDetector;
import io.github.lengsukq.opaldesk.daemon.input.VolumeKeyWakeDetector;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AndroidDisplayPowerHelperTest {

    private static final class FakeDisplayPowerBackend implements AndroidDisplayPowerHelper.DisplayPowerBackend {
        boolean surfaceOffOk = true;
        boolean surfaceOnOk = true;
        boolean wakeUpOk = true;
        boolean userActivityOk = true;
        boolean keyEventOk = true;
        long nowMs = 123456L;

        int surfaceOnCalls;
        int surfaceOffCalls;
        int wakeUpCalls;
        int userActivityCalls;
        int keyEventCalls;

        @Override
        public boolean setSurfaceControlPowerMode(boolean screenOn) {
            if (screenOn) {
                surfaceOnCalls++;
                return surfaceOnOk;
            }
            surfaceOffCalls++;
            return surfaceOffOk;
        }

        @Override
        public boolean wakeUpPowerManager(long uptimeMillis) {
            wakeUpCalls++;
            return wakeUpOk;
        }

        @Override
        public boolean triggerUserActivity(long uptimeMillis) {
            userActivityCalls++;
            return userActivityOk;
        }

        @Override
        public boolean sendWakeupKeyEvent() {
            keyEventCalls++;
            return keyEventOk;
        }

        @Override
        public long uptimeMillis() {
            return nowMs;
        }
    }

    public static final class FakePowerManagerApi29To36 {
        long wakeTime;
        int wakeReason;
        String wakeDetails;
        String wakePkg;
        int uaDisplayId = -1;
        long uaTime;
        int uaEvent = -1;
        int uaFlags = -1;

        public void wakeUp(long time, int reason, String details, String opPackageName) {
            this.wakeTime = time;
            this.wakeReason = reason;
            this.wakeDetails = details;
            this.wakePkg = opPackageName;
        }

        public void userActivity(int displayId, long time, int event, int flags) {
            this.uaDisplayId = displayId;
            this.uaTime = time;
            this.uaEvent = event;
            this.uaFlags = flags;
        }
    }

    public static final class FakePowerManagerApi28 {
        long wakeTime;
        String wakeDetails;
        String wakePkg;
        long uaTime;
        int uaEvent = -1;
        int uaFlags = -1;

        public void wakeUp(long time, String details, String opPackageName) {
            this.wakeTime = time;
            this.wakeDetails = details;
            this.wakePkg = opPackageName;
        }

        public void userActivity(long time, int event, int flags) {
            this.uaTime = time;
            this.uaEvent = event;
            this.uaFlags = flags;
        }
    }

    public static final class FakePowerManagerFiveArg {
        int displayGroupId = -1;

        public void wakeUp(long time, int reason, String details, String opPackageName, int displayGroupId) {
            this.displayGroupId = displayGroupId;
        }
    }

    public static final class ThrowingPowerManager {
        public void wakeUp(long time, int reason, String details, String opPackageName) {
            throw new SecurityException("missing DEVICE_POWER");
        }

        public void userActivity(int displayId, long time, int event, int flags) {
            throw new SecurityException("missing USER_ACTIVITY");
        }
    }

    @Test
    public void wakeFailureKeepsHostScreenOffAndRearmsWakeMonitor() {
        FakeDisplayPowerBackend backend = new FakeDisplayPowerBackend();
        AtomicInteger launchCount = new AtomicInteger(0);
        AtomicReference<BooleanSupplier> lastCallback = new AtomicReference<>();
        HostScreenWakeMonitor monitor = new HostScreenWakeMonitor((gen, onWake) -> {
            launchCount.incrementAndGet();
            lastCallback.set(onWake);
        });
        AndroidDisplayPowerHelper helper = new AndroidDisplayPowerHelper(null, backend, monitor);

        assertTrue(helper.setPrimaryPhysicalDisplayPower(false));
        assertTrue(helper.isHostScreenOff());
        assertTrue(monitor.isActive());
        assertEquals(1, launchCount.get());
        assertNotNull(lastCallback.get());

        // Simulate complete wake failure: must NOT flip hostScreenOff and must re-arm monitor
        backend.surfaceOnOk = false;
        backend.wakeUpOk = false;
        backend.userActivityOk = false;
        backend.keyEventOk = false;

        boolean firstWake = monitor.handleWakeTriggered(lastCallback.get(), "/dev/input/event2");
        assertFalse(firstWake);
        assertTrue("hostScreenOff must remain true when all wake paths fail", helper.isHostScreenOff());
        assertTrue("wakeMonitor must be re-armed after failed wake", monitor.isActive());
        assertEquals(2, launchCount.get());
        assertEquals(1, backend.keyEventCalls);

        // User retries double-press Volume+ and PowerManager wake succeeds
        backend.wakeUpOk = true;
        backend.userActivityOk = true;
        boolean secondWake = monitor.handleWakeTriggered(lastCallback.get(), "/dev/input/event2");
        assertTrue(secondWake);
        assertFalse(helper.isHostScreenOff());
        assertFalse(monitor.isActive());
        assertEquals(2, launchCount.get());
    }

    @Test
    public void wakeSucceedsWithSurfaceControlAndPowerManagerWithoutShellFallback() {
        FakeDisplayPowerBackend backend = new FakeDisplayPowerBackend();
        HostScreenWakeMonitor monitor = new HostScreenWakeMonitor((gen, onWake) -> {});
        AndroidDisplayPowerHelper helper = new AndroidDisplayPowerHelper(null, backend, monitor);

        assertTrue(helper.setPrimaryPhysicalDisplayPower(false));
        assertTrue(helper.setPrimaryPhysicalDisplayPower(true));
        assertFalse(helper.isHostScreenOff());
        assertFalse(monitor.isActive());
        assertEquals(1, backend.surfaceOnCalls);
        assertEquals(1, backend.wakeUpCalls);
        assertEquals(1, backend.userActivityCalls);
        assertEquals("Should not fork shell keyevent when wakeUp and userActivity succeed",
                0, backend.keyEventCalls);
    }

    @Test
    public void wakeDualPathFallbacksCoverReflectionAndSurfaceControlFailures() {
        FakeDisplayPowerBackend backend = new FakeDisplayPowerBackend();
        HostScreenWakeMonitor monitor = new HostScreenWakeMonitor((gen, onWake) -> {});
        AndroidDisplayPowerHelper helper = new AndroidDisplayPowerHelper(null, backend, monitor);

        // 1) SurfaceControl succeeds, PowerManager reflection fails -> KEYCODE_WAKEUP backlight fallback runs
        assertTrue(helper.setPrimaryPhysicalDisplayPower(false));
        backend.surfaceOnOk = true;
        backend.wakeUpOk = false;
        backend.userActivityOk = false;
        backend.keyEventOk = true;
        assertTrue(helper.setPrimaryPhysicalDisplayPower(true));
        assertFalse(helper.isHostScreenOff());
        assertEquals(1, backend.keyEventCalls);

        // 2) SurfaceControl fails, PowerManager reflection fails, KEYCODE_WAKEUP succeeds
        assertTrue(helper.setPrimaryPhysicalDisplayPower(false));
        backend.surfaceOnOk = false;
        backend.wakeUpOk = false;
        backend.userActivityOk = false;
        backend.keyEventOk = true;
        assertTrue(helper.setPrimaryPhysicalDisplayPower(true));
        assertFalse(helper.isHostScreenOff());
        assertEquals(2, backend.keyEventCalls);

        // 3) SurfaceControl succeeds, wakeUp succeeds, userActivity fails -> KEYCODE_WAKEUP backlight fallback runs
        assertTrue(helper.setPrimaryPhysicalDisplayPower(false));
        backend.surfaceOnOk = true;
        backend.wakeUpOk = true;
        backend.userActivityOk = false;
        backend.keyEventOk = true;
        assertTrue(helper.setPrimaryPhysicalDisplayPower(true));
        assertFalse(helper.isHostScreenOff());
        assertEquals(3, backend.keyEventCalls);
    }

    @Test
    public void powerManagerReflectionSupportsAndroid9To16Overloads() {
        FakePowerManagerApi29To36 api34 = new FakePowerManagerApi29To36();
        assertTrue(AndroidDisplayPowerHelper.invokePowerManagerWakeUp(api34, 98765L));
        assertEquals(98765L, api34.wakeTime);
        assertEquals(2, api34.wakeReason);
        assertEquals("opaldesk:wake", api34.wakeDetails);
        assertEquals(AndroidServices.SHELL_PACKAGE, api34.wakePkg);
        assertTrue(AndroidDisplayPowerHelper.invokePowerManagerUserActivity(api34, 98765L));
        assertEquals(0, api34.uaDisplayId);
        assertEquals(98765L, api34.uaTime);
        assertEquals(0, api34.uaEvent);
        assertEquals(0, api34.uaFlags);

        FakePowerManagerApi28 api28 = new FakePowerManagerApi28();
        assertTrue(AndroidDisplayPowerHelper.invokePowerManagerWakeUp(api28, 54321L));
        assertEquals(54321L, api28.wakeTime);
        assertEquals("opaldesk:wake", api28.wakeDetails);
        assertEquals(AndroidServices.SHELL_PACKAGE, api28.wakePkg);
        assertTrue(AndroidDisplayPowerHelper.invokePowerManagerUserActivity(api28, 54321L));
        assertEquals(54321L, api28.uaTime);
        assertEquals(0, api28.uaEvent);
        assertEquals(0, api28.uaFlags);

        FakePowerManagerFiveArg fiveArg = new FakePowerManagerFiveArg();
        assertTrue(AndroidDisplayPowerHelper.invokePowerManagerWakeUp(fiveArg, 11111L));
        assertEquals(0, fiveArg.displayGroupId);

        ThrowingPowerManager throwingPm = new ThrowingPowerManager();
        assertFalse(AndroidDisplayPowerHelper.invokePowerManagerWakeUp(throwingPm, 22222L));
        assertFalse(AndroidDisplayPowerHelper.invokePowerManagerUserActivity(throwingPm, 22222L));
        assertFalse(AndroidDisplayPowerHelper.invokePowerManagerWakeUp(null, 22222L));
        assertFalse(AndroidDisplayPowerHelper.invokePowerManagerUserActivity(null, 22222L));
    }

    @Test
    public void wakeMonitorRearmsWhenCallbackThrowsRuntimeException() {
        AtomicInteger launchCount = new AtomicInteger(0);
        HostScreenWakeMonitor monitor = new HostScreenWakeMonitor((gen, onWake) -> launchCount.incrementAndGet());
        BooleanSupplier throwingWake = () -> {
            throw new IllegalStateException("simulated binder failure");
        };
        monitor.start(throwingWake);
        assertEquals(1, launchCount.get());

        boolean ok = monitor.handleWakeTriggered(throwingWake, "/dev/input/event0");
        assertFalse(ok);
        assertTrue("Monitor must re-arm when callback throws RuntimeException", monitor.isActive());
        assertEquals(2, launchCount.get());
    }

    @Test
    public void volumeKeyWakeDetectorStateMachineCoversDoublePressComboAndRearm() {
        VolumeKeyWakeDetector detector = new VolumeKeyWakeDetector();
        detector.arm(1000L);

        // Grace window (< 350ms) suppresses events
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 1200L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0, 1250L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 1300L));

        // Hold repeat (value=2) does not false-trigger
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2000L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 2, 2100L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0, 2200L));

        // Expired interval (> 450ms) does not wake, but next press within 450ms wakes
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2451L));
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 0, 2500L));
        assertTrue(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 2900L));

        // After wake resets state, Volume- + Volume+ combo wakes cleanly
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEDOWN, 1, 3500L));
        assertTrue(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 3580L));

        // Non-volume keys and non-EV_KEY events are ignored
        assertFalse(detector.onEvent(VolumeKeyWakeDetector.EV_KEY, DoubleTapWakeDetector.KEY_POWER, 1, 4000L));
        assertFalse(detector.onEvent(DoubleTapWakeDetector.EV_ABS, VolumeKeyWakeDetector.KEY_VOLUMEUP, 1, 4050L));
    }
}
