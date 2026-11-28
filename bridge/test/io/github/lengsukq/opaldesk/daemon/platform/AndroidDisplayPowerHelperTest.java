package io.github.lengsukq.opaldesk.daemon.platform;

import io.github.lengsukq.opaldesk.daemon.input.DoubleTapWakeDetector;
import io.github.lengsukq.opaldesk.daemon.input.VolumeKeyWakeDetector;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
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
        boolean scBrightOk = true;
        boolean dpcBrightOk = true;
        DisplayBrightnessRestorer.BrightnessSnapshot capturedBrightness =
                new DisplayBrightnessRestorer.BrightnessSnapshot(180, 180 / 255.0f);
        long nowMs = 123456L;

        int surfaceOnCalls;
        int surfaceOffCalls;
        int wakeUpCalls;
        int userActivityCalls;
        int keyEventCalls;
        int captureBrightnessCalls;
        int restoreScBrightnessCalls;
        int refreshDpcBrightnessCalls;
        float lastRestoredBrightness = -1f;
        DisplayBrightnessRestorer.BrightnessSnapshot lastRefreshedSnapshot;

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
        public DisplayBrightnessRestorer.BrightnessSnapshot captureCurrentBrightness() {
            captureBrightnessCalls++;
            return capturedBrightness;
        }

        @Override
        public boolean restoreSurfaceControlBrightness(float brightness) {
            restoreScBrightnessCalls++;
            lastRestoredBrightness = brightness;
            return scBrightOk;
        }

        @Override
        public boolean refreshDisplayPowerControllerBrightness(
                DisplayBrightnessRestorer.BrightnessSnapshot snapshot) {
            refreshDpcBrightnessCalls++;
            lastRefreshedSnapshot = snapshot;
            return dpcBrightOk;
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

    public static final class FakeSurfaceControlApi31To37 {
        static Object lastToken;
        static float lastSdr;
        static float lastSdrNits;
        static float lastDisplay;
        static float lastDisplayNits;

        public static void setDisplayBrightness(
                Object token, float sdrBrightness, float sdrNits, float displayBrightness, float displayNits) {
            lastToken = token;
            lastSdr = sdrBrightness;
            lastSdrNits = sdrNits;
            lastDisplay = displayBrightness;
            lastDisplayNits = displayNits;
        }
    }

    public static final class FakeSurfaceControlApi29To30 {
        static Object lastToken;
        static float lastBrightness;

        public static void setDisplayBrightness(Object token, float brightness) {
            lastToken = token;
            lastBrightness = brightness;
        }
    }

    public static final class ThrowingSurfaceControl {
        public static void setDisplayBrightness(Object token, float brightness) {
            throw new SecurityException("missing BRIGHTNESS permission");
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
        assertEquals(1, backend.captureBrightnessCalls);
        assertEquals(1, backend.restoreScBrightnessCalls);
        assertEquals(1, backend.refreshDpcBrightnessCalls);
        assertEquals(180 / 255.0f, backend.lastRestoredBrightness, 0.0001f);
        assertNotNull(backend.lastRefreshedSnapshot);
        assertEquals(180, backend.lastRefreshedSnapshot.rawSetting);
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
    public void brightnessParsingAndClampingHandlesAospOemFloatAndInvalidInputs() {
        DisplayBrightnessRestorer.BrightnessSnapshot s128 = DisplayBrightnessRestorer.parseBrightnessSetting("128\n");
        assertTrue(s128.hasRawSetting());
        assertEquals(128, s128.rawSetting);
        assertEquals(128 / 255.0f, s128.normalized, 0.0001f);

        DisplayBrightnessRestorer.BrightnessSnapshot sLow = DisplayBrightnessRestorer.parseBrightnessSetting("2");
        assertTrue(sLow.hasRawSetting());
        assertEquals(DisplayBrightnessRestorer.MIN_BRIGHTNESS, sLow.normalized, 0.0001f);

        DisplayBrightnessRestorer.BrightnessSnapshot sOem = DisplayBrightnessRestorer.parseBrightnessSetting("2048");
        assertTrue(sOem.hasRawSetting());
        assertEquals(2048, sOem.rawSetting);
        assertEquals(2048 / 4095.0f, sOem.normalized, 0.0001f);

        DisplayBrightnessRestorer.BrightnessSnapshot sFloat = DisplayBrightnessRestorer.parseBrightnessSetting("0.72");
        assertFalse(sFloat.hasRawSetting());
        assertEquals(0.72f, sFloat.normalized, 0.0001f);

        DisplayBrightnessRestorer.BrightnessSnapshot sInvalid = DisplayBrightnessRestorer.parseBrightnessSetting("null");
        assertFalse(sInvalid.hasRawSetting());
        assertEquals(DisplayBrightnessRestorer.DEFAULT_BRIGHTNESS, sInvalid.normalized, 0.0001f);

        assertEquals(179, DisplayBrightnessRestorer.computeNudgedBrightness(180));
        assertEquals(2, DisplayBrightnessRestorer.computeNudgedBrightness(1));
    }

    @Test
    public void surfaceControlBrightnessReflectionSupportsApi29To37Overloads() {
        Object token = new Object();

        assertTrue(DisplayBrightnessRestorer.invokeSurfaceControlSetDisplayBrightness(
                FakeSurfaceControlApi31To37.class, Object.class, token, 0.65f));
        assertEquals(token, FakeSurfaceControlApi31To37.lastToken);
        assertEquals(0.65f, FakeSurfaceControlApi31To37.lastSdr, 0.0001f);
        assertEquals(-1.0f, FakeSurfaceControlApi31To37.lastSdrNits, 0.0001f);
        assertEquals(0.65f, FakeSurfaceControlApi31To37.lastDisplay, 0.0001f);
        assertEquals(-1.0f, FakeSurfaceControlApi31To37.lastDisplayNits, 0.0001f);

        assertTrue(DisplayBrightnessRestorer.invokeSurfaceControlSetDisplayBrightness(
                FakeSurfaceControlApi29To30.class, Object.class, token, 0.01f));
        assertEquals(token, FakeSurfaceControlApi29To30.lastToken);
        assertEquals(DisplayBrightnessRestorer.MIN_BRIGHTNESS, FakeSurfaceControlApi29To30.lastBrightness, 0.0001f);

        assertFalse(DisplayBrightnessRestorer.invokeSurfaceControlSetDisplayBrightness(
                ThrowingSurfaceControl.class, Object.class, token, 0.5f));
        assertFalse(DisplayBrightnessRestorer.invokeSurfaceControlSetDisplayBrightness(
                FakeSurfaceControlApi31To37.class, Object.class, null, 0.5f));
    }

    @Test
    public void displayPowerControllerRefreshNudgesRawSettingOrFallsBackToCmdDisplay() {
        List<String> executed = new ArrayList<>();
        DisplayBrightnessRestorer.ShellExecutor okShell = (cmd, timeoutMs) -> {
            executed.add(String.join(" ", cmd));
            return new ShellCommand.Result(0, "", false);
        };

        DisplayBrightnessRestorer.BrightnessSnapshot rawSnap =
                new DisplayBrightnessRestorer.BrightnessSnapshot(120, 120 / 255.0f);
        assertTrue(DisplayBrightnessRestorer.refreshDisplayPowerControllerBrightness(rawSnap, okShell));
        assertEquals(2, executed.size());
        assertEquals("settings put system screen_brightness 119", executed.get(0));
        assertEquals("settings put system screen_brightness 120", executed.get(1));

        executed.clear();
        DisplayBrightnessRestorer.BrightnessSnapshot floatSnap =
                new DisplayBrightnessRestorer.BrightnessSnapshot(
                        DisplayBrightnessRestorer.INVALID_RAW_BRIGHTNESS, 0.5f);
        assertTrue(DisplayBrightnessRestorer.refreshDisplayPowerControllerBrightness(floatSnap, okShell));
        assertEquals(1, executed.size());
        assertEquals("/system/bin/cmd display set-brightness 0.500", executed.get(0));

        // When settings put fails, must fall back to cmd display set-brightness
        executed.clear();
        DisplayBrightnessRestorer.ShellExecutor settingsFailShell = (cmd, timeoutMs) -> {
            String joined = String.join(" ", cmd);
            executed.add(joined);
            if (joined.startsWith("settings ")) {
                return new ShellCommand.Result(1, "SecurityException", false);
            }
            return new ShellCommand.Result(0, "", false);
        };
        assertTrue(DisplayBrightnessRestorer.refreshDisplayPowerControllerBrightness(rawSnap, settingsFailShell));
        assertEquals(3, executed.size());
        assertTrue(executed.get(2).startsWith("/system/bin/cmd display set-brightness "));
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
