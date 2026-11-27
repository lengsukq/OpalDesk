package io.github.lengsukq.opaldesk.daemon.platform;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TaskLaunchPolicyTest {

    static final class FakeRect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        FakeRect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    static final class FakeWindowConfiguration {
        private final int windowingMode;
        public final FakeRect mBounds;

        FakeWindowConfiguration(int windowingMode, FakeRect bounds) {
            this.windowingMode = windowingMode;
            this.mBounds = bounds;
        }

        public int getWindowingMode() {
            return windowingMode;
        }

        public FakeRect getBounds() {
            return mBounds;
        }
    }

    static final class FakeConfiguration {
        public final FakeWindowConfiguration windowConfiguration;

        FakeConfiguration(int windowingMode, FakeRect bounds) {
            this.windowConfiguration = new FakeWindowConfiguration(windowingMode, bounds);
        }
    }

    static final class FakeAppCompatTaskInfo {
        public boolean topActivityBoundsLetterboxed;
        public boolean topActivityInSizeCompat;
        public FakeRect topActivityLetterboxBounds;
        public FakeRect topActivityAppBounds;
    }

    static final class FakeTaskInfo {
        public boolean isEmbedded;
        public FakeConfiguration configuration;
        public FakeAppCompatTaskInfo appCompatTaskInfo;
        // Android 12-14 direct fields
        public boolean topActivityBoundsLetterboxed;
        public boolean topActivityInSizeCompat;
        public FakeRect topActivityLetterboxBounds;
        public FakeRect topActivityAppBounds;
    }

    @Test
    public void cleanFullscreenTaskNeedsNoCleanLaunch() {
        FakeTaskInfo info = new FakeTaskInfo();
        info.isEmbedded = false;
        info.configuration = new FakeConfiguration(
                TaskLaunchPolicy.WINDOWING_MODE_FULLSCREEN, new FakeRect(0, 0, 3040, 1904));
        info.appCompatTaskInfo = new FakeAppCompatTaskInfo();
        info.appCompatTaskInfo.topActivityAppBounds = new FakeRect(0, 0, 3040, 1904);
        info.appCompatTaskInfo.topActivityLetterboxBounds = null;

        assertNull(TaskLaunchPolicy.describeCleanLaunchReason(info));
    }

    @Test
    public void phoneNotchAndVerticalSystemBarsDoNotFalsePositive() {
        // Landscape phone (2400x1080) with 120px left display cutout and vertical status/nav bars
        FakeTaskInfo landscapePhone = new FakeTaskInfo();
        landscapePhone.configuration = new FakeConfiguration(
                TaskLaunchPolicy.WINDOWING_MODE_FULLSCREEN, new FakeRect(0, 0, 2400, 1080));
        landscapePhone.appCompatTaskInfo = new FakeAppCompatTaskInfo();
        landscapePhone.appCompatTaskInfo.topActivityAppBounds = new FakeRect(120, 48, 2400, 1032);
        assertNull(TaskLaunchPolicy.describeCleanLaunchReason(landscapePhone));

        // Portrait phone (1080x2400) with status bar and gesture nav bar insets
        FakeTaskInfo portraitPhone = new FakeTaskInfo();
        portraitPhone.configuration = new FakeConfiguration(
                TaskLaunchPolicy.WINDOWING_MODE_FULLSCREEN, new FakeRect(0, 0, 1080, 2400));
        portraitPhone.appCompatTaskInfo = new FakeAppCompatTaskInfo();
        portraitPhone.appCompatTaskInfo.topActivityAppBounds = new FakeRect(0, 110, 1080, 2260);
        assertNull(TaskLaunchPolicy.describeCleanLaunchReason(portraitPhone));
    }

    @Test
    public void detectsEmbeddedTaskFlag() {
        FakeTaskInfo info = new FakeTaskInfo();
        info.isEmbedded = true;
        info.configuration = new FakeConfiguration(
                TaskLaunchPolicy.WINDOWING_MODE_FULLSCREEN, new FakeRect(0, 0, 3040, 1904));

        assertEquals("embedded", TaskLaunchPolicy.describeCleanLaunchReason(info));
    }

    @Test
    public void detectsNonFullscreenWindowingMode() {
        FakeTaskInfo multiWindow = new FakeTaskInfo();
        multiWindow.configuration = new FakeConfiguration(6, new FakeRect(0, 0, 3040, 1904));
        assertEquals("windowingMode=6", TaskLaunchPolicy.describeCleanLaunchReason(multiWindow));

        FakeTaskInfo vendorMode = new FakeTaskInfo();
        vendorMode.configuration = new FakeConfiguration(8, new FakeRect(0, 0, 3040, 1904));
        assertEquals("windowingMode=8", TaskLaunchPolicy.describeCleanLaunchReason(vendorMode));
    }

    @Test
    public void detectsAppCompatLetterboxSizeCompatAndInsetAppBounds() {
        // Explicit boundsLetterboxed flag
        FakeTaskInfo letterboxed = new FakeTaskInfo();
        letterboxed.appCompatTaskInfo = new FakeAppCompatTaskInfo();
        letterboxed.appCompatTaskInfo.topActivityBoundsLetterboxed = true;
        assertEquals("boundsLetterboxed", TaskLaunchPolicy.describeCleanLaunchReason(letterboxed));

        // Size-compat flag
        FakeTaskInfo sizeCompat = new FakeTaskInfo();
        sizeCompat.appCompatTaskInfo = new FakeAppCompatTaskInfo();
        sizeCompat.appCompatTaskInfo.topActivityInSizeCompat = true;
        assertEquals("sizeCompat", TaskLaunchPolicy.describeCleanLaunchReason(sizeCompat));

        // Non-null empty topActivityLetterboxBounds (observed on Android 17 embedded/letterboxed tasks)
        FakeTaskInfo letterboxRect = new FakeTaskInfo();
        letterboxRect.appCompatTaskInfo = new FakeAppCompatTaskInfo();
        letterboxRect.appCompatTaskInfo.topActivityLetterboxBounds = new FakeRect(0, 0, 0, 0);
        assertEquals("letterboxBounds", TaskLaunchPolicy.describeCleanLaunchReason(letterboxRect));

        // Centered Display-0 pillarbox: Rect(760, 0 - 2280, 1904) inside 3040x1904
        FakeTaskInfo centeredInset = new FakeTaskInfo();
        centeredInset.configuration = new FakeConfiguration(
                TaskLaunchPolicy.WINDOWING_MODE_FULLSCREEN, new FakeRect(0, 0, 3040, 1904));
        centeredInset.appCompatTaskInfo = new FakeAppCompatTaskInfo();
        centeredInset.appCompatTaskInfo.topActivityAppBounds = new FakeRect(760, 0, 2280, 1904);
        String reason = TaskLaunchPolicy.describeCleanLaunchReason(centeredInset);
        assertNotNull(reason);
        assertTrue(reason.startsWith("insetAppBounds("));

        // Left-side embedded split pane: Rect(0, 0 - 1520, 1904) inside 3040x1904
        FakeTaskInfo leftPane = new FakeTaskInfo();
        leftPane.configuration = new FakeConfiguration(
                TaskLaunchPolicy.WINDOWING_MODE_FULLSCREEN, new FakeRect(0, 0, 3040, 1904));
        leftPane.appCompatTaskInfo = new FakeAppCompatTaskInfo();
        leftPane.appCompatTaskInfo.topActivityAppBounds = new FakeRect(0, 0, 1520, 1904);
        String leftReason = TaskLaunchPolicy.describeCleanLaunchReason(leftPane);
        assertNotNull(leftReason);
        assertTrue(leftReason.startsWith("insetAppBounds("));
    }

    @Test
    public void detectsLegacyAndroid12To14DirectTaskInfoFields() {
        FakeTaskInfo legacy = new FakeTaskInfo();
        legacy.topActivityInSizeCompat = true;
        assertEquals("sizeCompat", TaskLaunchPolicy.describeCleanLaunchReason(legacy));
    }

    @Test
    public void cleanLaunchBeforeReparentAppliesOnlyToStaleDisplay0Tasks() {
        // Fresh app open with an embedded Display-0 candidate -> clean launch
        assertTrue(TaskLaunchPolicy.shouldCleanLaunchBeforeReparent(
                -1, false, 2429, -1, 0, "embedded"));

        // Waking a hibernated OpalDesk window (existingTaskId > 0) -> preserve task state
        assertFalse(TaskLaunchPolicy.shouldCleanLaunchBeforeReparent(
                2429, false, 2429, -1, 0, "embedded"));

        // Task already lives on an OpalDesk VirtualDisplay (displayId > 0) -> do not wipe
        assertFalse(TaskLaunchPolicy.shouldCleanLaunchBeforeReparent(
                -1, false, 2429, -1, 88, "embedded"));

        // Explicit sub-activity launch -> do not touch unrelated package task
        assertFalse(TaskLaunchPolicy.shouldCleanLaunchBeforeReparent(
                -1, true, 2429, -1, 0, "embedded"));

        // Clean Display-0 task (cleanReason == null) -> allow normal reparenting
        assertFalse(TaskLaunchPolicy.shouldCleanLaunchBeforeReparent(
                -1, false, 2433, -1, 0, null));
    }

    @Test
    public void cleanLaunchAfterRecycleOnlyFiresWhenDisplay0TaskWasRecycled() {
        // Recycled Display-0 task still landed with embedded/letterbox state -> clean relaunch once
        assertTrue(TaskLaunchPolicy.shouldCleanLaunchAfterRecycle(
                false, -1, 2429, 0, 2429, "embedded"));

        // Brand-new task created on VirtualDisplay (different taskId) -> never loop
        assertFalse(TaskLaunchPolicy.shouldCleanLaunchAfterRecycle(
                false, -1, 2429, 0, 2450, "embedded"));

        // Already cleaned before launch -> never repeat
        assertFalse(TaskLaunchPolicy.shouldCleanLaunchAfterRecycle(
                true, -1, 2429, 0, 2429, "embedded"));

        // Hibernated wake (existingTaskId > 0) -> never wipe
        assertFalse(TaskLaunchPolicy.shouldCleanLaunchAfterRecycle(
                false, 2429, 2429, 0, 2429, "embedded"));
    }

    @Test
    public void buildsAmStartFallbackCommandWithExtras() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("targetDisplayId", 2);
        extras.put("preferPresentation", false);
        extras.put("shellLaunched", true);
        extras.put("label", "HDMI");

        String[] cmd = TaskLaunchPolicy.buildAmStartArgs(
                "io.github.lengsukq.opaldesk/.ExternalDesktopActivity",
                2,
                TaskLaunchPolicy.WINDOWING_MODE_FULLSCREEN,
                0x30018000,
                extras);

        assertEquals(
                Arrays.asList(
                        "am", "start",
                        "--display", "2",
                        "--windowingMode", "1",
                        "-f", "0x30018000",
                        "-n", "io.github.lengsukq.opaldesk/.ExternalDesktopActivity",
                        "--ei", "targetDisplayId", "2",
                        "--ez", "preferPresentation", "false",
                        "--ez", "shellLaunched", "true",
                        "--es", "label", "HDMI"),
                Arrays.asList(cmd));
    }

    @Test
    public void amStartResultClassificationRejectsSecurityExceptionsNonZeroExitAndTimeout() {
        // Normal successful am start output
        assertTrue(TaskLaunchPolicy.isAmStartResultSuccessful(
                0,
                false,
                "Starting: Intent { flg=0x34030000 cmp=com.example/.HostActivity }\n"));

        // Harmless singleTop delivery warning is still a successful launch
        assertTrue(TaskLaunchPolicy.isAmStartResultSuccessful(
                0,
                false,
                "Starting: Intent { cmp=com.example/.HostActivity }\n"
                        + "Warning: Activity not started, intent has been delivered to currently running top-most instance.\n"));

        // Regression: Permission Denial / SecurityException on restricted external displays (no "Error" token)
        String secExcOutput = "Starting: Intent { cmp=com.example/.HostActivity }\n"
                + "Exception occurred while executing 'start':\n"
                + "java.lang.SecurityException: Permission Denial: starting Intent { cmp=com.example/.HostActivity } "
                + "from null (pid=1234, uid=2000) with launchDisplayId=7\n"
                + "\tat android.os.Parcel.createExceptionOrNull(Parcel.java:3200)\n";
        assertFalse(TaskLaunchPolicy.isAmStartResultSuccessful(255, false, secExcOutput));
        // Even if an OEM wrapper returns exit code 0 alongside SecurityException output
        assertFalse(TaskLaunchPolicy.isAmStartResultSuccessful(0, false, secExcOutput));

        // Standard Error: output with exit 0 or non-zero
        assertFalse(TaskLaunchPolicy.isAmStartResultSuccessful(
                0,
                false,
                "Error type 3\nError: Activity class {com.example/.Missing} does not exist.\n"));

        // Non-zero exit code with empty output and timeout must both fail
        assertFalse(TaskLaunchPolicy.isAmStartResultSuccessful(1, false, ""));
        assertFalse(TaskLaunchPolicy.isAmStartResultSuccessful(0, true, "Starting: Intent {}"));

        // Summary collapses multiline stack traces while keeping the exception line
        String summary = TaskLaunchPolicy.summarizeAmStartOutput(secExcOutput);
        assertTrue(summary.contains("Exception occurred while executing 'start':"));
        assertTrue(summary.contains("SecurityException"));
        assertFalse(summary.contains("\n"));
    }
}
