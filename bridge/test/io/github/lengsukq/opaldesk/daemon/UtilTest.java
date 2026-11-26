package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;
import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UtilTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testCryptoHexVectors() {
        assertEquals("", CryptoHex.toHex(null));
        assertEquals("", CryptoHex.toHex(new byte[0]));
        assertEquals("000f10ff", CryptoHex.toHex(new byte[]{0x00, 0x0F, 0x10, (byte) 0xFF}));

        // NIST FIPS 180-4 SHA-256 test vectors
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                CryptoHex.sha256Hex(""));
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CryptoHex.sha256Hex("abc"));
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CryptoHex.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testAtomicFilesWriteOverwriteAndPermissions() throws Exception {
        File dir = tmpDir.newFolder("atomic");
        File target = new File(new File(dir, "nested"), "secret.conf");

        AtomicFiles.writeAtomicUtf8(target, "line1\n", AtomicFiles.MODE_OWNER_RW);
        assertTrue(target.isFile());
        assertEquals("line1\n", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target.toPath());
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));

        // Overwrite existing atomically and leave zero temp files behind
        AtomicFiles.writeAtomicUtf8(target, "line2-updated\n", AtomicFiles.MODE_OWNER_RW);
        assertEquals("line2-updated\n", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        File[] remaining = target.getParentFile().listFiles();
        assertEquals(1, remaining != null ? remaining.length : 0);
    }

    @Test
    public void testAtomicFilesPropagatesIoFailure() throws Exception {
        File dir = tmpDir.newFolder("atomic_err");
        // Attempting to write to an existing non-empty directory must fail honestly
        File nonEmptyDir = new File(dir, "occupied");
        assertTrue(nonEmptyDir.mkdirs());
        assertTrue(new File(nonEmptyDir, "child.txt").createNewFile());

        try {
            AtomicFiles.writeAtomicUtf8(nonEmptyDir, "data", AtomicFiles.MODE_OWNER_RW);
            fail("Expected IOException when target is a non-empty directory");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
        // Temp file must be cleaned up on failure
        File[] files = dir.listFiles((d, name) -> name.endsWith(".tmp"));
        assertEquals(0, files != null ? files.length : 0);
    }

    @Test
    public void testShellCommandSuccessNonZeroAndTimeout() {
        ShellCommand.Result ok = ShellCommand.runWithTimeout(new String[]{"sh", "-c", "echo hello-opaldesk"}, 2000L);
        assertTrue(ok.isSuccess());
        assertEquals(0, ok.exitCode);
        assertFalse(ok.timedOut);
        assertEquals("hello-opaldesk", ok.output.trim());

        ShellCommand.Result failRes = ShellCommand.runWithTimeout(new String[]{"sh", "-c", "exit 7"}, 2000L);
        assertFalse(failRes.isSuccess());
        assertEquals(7, failRes.exitCode);
        assertFalse(failRes.timedOut);

        long startMs = System.currentTimeMillis();
        ShellCommand.Result timedOut = ShellCommand.runWithTimeout(new String[]{"sh", "-c", "sleep 5"}, 150L);
        long elapsedMs = System.currentTimeMillis() - startMs;
        assertFalse(timedOut.isSuccess());
        assertTrue("Command should report timedOut=true", timedOut.timedOut);
        assertTrue("Timeout should kill child promptly (was " + elapsedMs + "ms)", elapsedMs < 2000L);
    }

    @Test
    public void testPortraitPhoneWorkAreaAndInitialWindowFrame() {
        int canvasW = 1080;
        int canvasH = 2400;
        int dpi = 440;
        DisplayGeometryUtil.IntRect work = DisplayGeometryUtil.computeWorkArea(canvasW, canvasH, dpi);
        assertTrue(work.left >= 0 && work.right <= canvasW);
        assertTrue(work.bottom > work.top);
        assertTrue(work.height() > work.width());

        DisplayGeometryUtil.IntRect frame = DisplayGeometryUtil.computeInitialWindowFrame(
                WindowProfile.DESKTOP, 0, canvasW, canvasH, dpi);
        int fw = frame.width();
        int fh = frame.height();
        assertEquals(0, fw % 2);
        assertEquals(0, fh % 2);
        assertTrue("Portrait initial frame must fit inside phone width", frame.left >= 0 && frame.right <= canvasW);
        assertTrue("Portrait initial frame must fit inside work height", frame.top >= work.top && frame.bottom <= work.bottom);
    }

    @Test
    public void testStandaloneAppDpiAdaptsToHostDeviceDpi() {
        // Phone host: Xiaomi 14 Pro 1440x3200 @ 560dpi -> logical short edge = 411.4dp
        // Floating portrait stream 744x1280 should keep ~411dp width (~289dpi)
        int phonePortraitDpi = DisplayGeometryUtil.computeStandaloneAppDpi(744, 1280, 1440, 3200, 560, 100);
        assertTrue("Expected ~289dpi for 744px wide on 411dp phone, got " + phonePortraitDpi,
                phonePortraitDpi >= 280 && phonePortraitDpi <= 298);

        // Resizing narrower to 560x1280 on same phone host scales DPI proportionally down (~218dpi)
        int phoneNarrowDpi = DisplayGeometryUtil.computeStandaloneAppDpi(560, 1280, 1440, 3200, 560, 100);
        assertTrue("Narrower stream must reduce DPI proportionally, got " + phoneNarrowDpi,
                phoneNarrowDpi >= 210 && phoneNarrowDpi <= 226);

        // Tablet host: Xiaomi Pad 8 Pro 3040x1904 @ 440dpi -> logical short edge = 692.4dp
        // Landscape floating window 1280x792 should be lower than phone landscape while staying readable
        int tabletLandDpi = DisplayGeometryUtil.computeStandaloneAppDpi(1280, 792, 3040, 1904, 440, 100);
        int phoneLandDpi = DisplayGeometryUtil.computeStandaloneAppDpi(1280, 792, 1440, 3200, 560, 100);
        assertTrue("Tablet host landscape DPI (" + tabletLandDpi + ") must be lower than phone host (" + phoneLandDpi + ")",
                tabletLandDpi < phoneLandDpi - 50);

        // Receiver-computed 1:1 readable spec (Phone 560dpi -> Tablet 390dp floating window, 904x1440 @ 371dpi)
        // must supersample proportionally toward 560dpi while preserving exact ~390dp logical width!
        int[] spec = DisplayGeometryUtil.resolveStandaloneStreamSpec(904, 1440, 371, 1440, 3200, 560, 100);
        assertTrue("Supersampled DPI should approach host 560dpi for compat apps, got " + spec[2], spec[2] >= 520);
        float logicalW = spec[0] * 160f / spec[2];
        assertTrue("Logical width must stay ~390dp for 1:1 readable text, got " + logicalW,
                logicalW >= 382f && logicalW <= 398f);
        // Compat app pinned at 560dpi sees spec[0] / 3.5 >= 370dp (never zoomed/clipped!)
        float compatW = spec[0] * 160f / 560f;
        assertTrue("Compat 560dpi app must see >= 370dp logical width, got " + compatW, compatW >= 370f);
    }

    @Test
    public void testShrunkCompositorWindowSupersamplesToHostDpiForWeChatCompat() {
        // Shrunk portrait window (480x700px) on a 1920x1080 cast canvas hosted by a 560dpi phone
        DisplayGeometryUtil.IntRect shrunk = new DisplayGeometryUtil.IntRect(100, 100, 580, 800);
        int[] spec = DisplayGeometryUtil.resolveWindowBufferSpec(
                shrunk, 240, false, WindowProfile.PORTRAIT, 100, 560);
        assertTrue("Shrunk window VD DPI must supersample near host 560dpi, got " + spec[2], spec[2] >= 520);
        float normalLogicalW = spec[0] * 160f / spec[2];
        float wechatCompatW = spec[0] * 160f / 560f;
        assertTrue("Normal app logical width must remain ~412dp, got " + normalLogicalW,
                normalLogicalW >= 400f && normalLogicalW <= 425f);
        assertTrue("WeChat compat=560dpi logical width must stay >= 385dp (never giant!), got " + wechatCompatW,
                wechatCompatW >= 385f);

        // Tablet host (440dpi) shrunk window must match 440dpi compat 1:1
        int[] tabSpec = DisplayGeometryUtil.resolveWindowBufferSpec(
                shrunk, 240, false, WindowProfile.PORTRAIT, 100, 440);
        assertTrue("Tablet shrunk window VD DPI must match 440dpi, got " + tabSpec[2],
                tabSpec[2] >= 432 && tabSpec[2] <= 448);

        // Auto DPI fallback must never collapse to tiny 120dpi for 792x1280 stream
        int autoDpi = DisplayGeometryUtil.computeAutoDpi(792, 1280);
        assertTrue("computeAutoDpi(792, 1280) must be readable (~308dpi), got " + autoDpi, autoDpi >= 280);
    }

    @Test
    public void testDaemonConfigPersistsAppLaunchCounts() throws Exception {
        File dir = tmpDir.newFolder("cfg_counts");
        File conf = new File(dir, "main.conf");
        File prof = new File(dir, "profiles.conf");
        DaemonConfig cfg1 = new DaemonConfig(conf, prof);
        assertEquals(0, cfg1.getAppLaunchCount("com.tencent.mm"));
        assertEquals(1, cfg1.recordAppLaunch("com.tencent.mm"));
        assertEquals(2, cfg1.recordAppLaunch("com.tencent.mm"));
        assertEquals(1, cfg1.recordAppLaunch("tv.danmaku.bili"));

        DaemonConfig cfg2 = new DaemonConfig(conf, prof);
        cfg2.loadAll();
        assertEquals(2, cfg2.getAppLaunchCount("com.tencent.mm"));
        assertEquals(1, cfg2.getAppLaunchCount("tv.danmaku.bili"));
    }

    @Test
    public void testAlignExternalForcedSizeLandscapePortraitAndClamping() {
        assertNull(DisplayGeometryUtil.alignExternalForcedSize(0, 1080));
        assertNull(DisplayGeometryUtil.alignExternalForcedSize(1920, -10));

        // 21:9 ultrawide preserved & even-aligned
        int[] uw = DisplayGeometryUtil.alignExternalForcedSize(3441, 1441);
        assertNotNull(uw);
        assertEquals(3440, uw[0]);
        assertEquals(1440, uw[1]);

        // 4K landscape preserved
        int[] uhd = DisplayGeometryUtil.alignExternalForcedSize(3840, 2160);
        assertNotNull(uhd);
        assertEquals(3840, uhd[0]);
        assertEquals(2160, uhd[1]);

        // Portrait external monitor preserved (not squashed to landscape!)
        int[] port = DisplayGeometryUtil.alignExternalForcedSize(1080, 1920);
        assertNotNull(port);
        assertEquals(1080, port[0]);
        assertEquals(1920, port[1]);

        // Oversized input scaled down proportionally within 4096x2560
        int[] huge = DisplayGeometryUtil.alignExternalForcedSize(5120, 2880);
        assertNotNull(huge);
        assertTrue(huge[0] <= 4096 && huge[1] <= 2560);
        assertEquals(0, huge[0] & 1);
        assertEquals(0, huge[1] & 1);
    }

    @Test
    public void testSelectBestEdidModeIndexAndRefreshRateFallback() {
        int[] ws = new int[]{3840, 3840, 2560, 2560, 1920, 1920};
        int[] hs = new int[]{2160, 2160, 1440, 1440, 1080, 1080};
        float[] rs = new float[]{30f, 60f, 60f, 120f, 60f, 144f};

        // Exact resolution + target refresh rate
        assertEquals(3, DisplayGeometryUtil.selectBestEdidModeIndex(ws, hs, rs, 2560, 1440, 120f));
        // Exact resolution + auto refresh rate (0f) picks highest Hz
        assertEquals(5, DisplayGeometryUtil.selectBestEdidModeIndex(ws, hs, rs, 1920, 1080, 0f));
        // Custom 21:9 ultrawide not in EDID returns -1
        assertEquals(-1, DisplayGeometryUtil.selectBestEdidModeIndex(ws, hs, rs, 3440, 1440, 60f));

        // When forcing custom 3440x1440 @ 120Hz while monitor is currently at 2560x1440, picks 2560x1440@120
        assertEquals(3, DisplayGeometryUtil.selectBestEdidModeForRefreshRate(ws, hs, rs, 2560, 1440, 120f));
        // When requesting 60Hz at 3840x2160, picks 3840x2160@60
        assertEquals(1, DisplayGeometryUtil.selectBestEdidModeForRefreshRate(ws, hs, rs, 3840, 2160, 60f));

        // Rotated EDID panel fallback when exact orientation is absent, preferring exact when present
        int[] rotWs = new int[]{1080, 1920};
        int[] rotHs = new int[]{1920, 1080};
        float[] rotRs = new float[]{60f, 60f};
        assertEquals(1, DisplayGeometryUtil.selectBestEdidModeIndex(rotWs, rotHs, rotRs, 1920, 1080, 60f));
        assertEquals(1, DisplayGeometryUtil.selectBestEdidModeIndex(
                new int[]{1080, 1080}, new int[]{1920, 1920}, new float[]{60f, 120f}, 1920, 1080, 120f));
    }

    @Test
    public void testDaemonConfigConcurrentSaveMainConfPreservesTargetFpsWithoutRenameErrors() throws Exception {
        java.io.File tmpDir = java.nio.file.Files.createTempDirectory("opaldesk_cfg_test").toFile();
        java.io.File conf = new java.io.File(tmpDir, "opaldesk_v2.conf");
        java.io.File prof = new java.io.File(tmpDir, "opaldesk_profiles.conf");
        DaemonConfig cfg = new DaemonConfig(conf, prof);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<?>> futs = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final int fps = (i % 2 == 0) ? 90 : 120;
                futs.add(pool.submit(() -> cfg.updateDisplayPrefs(fps, 100)));
            }
            for (java.util.concurrent.Future<?> f : futs) {
                f.get();
            }
            cfg.updateDisplayPrefs(120, 115);
            DaemonConfig reloaded = new DaemonConfig(conf, prof);
            reloaded.loadAll();
            assertEquals(120, reloaded.getTargetFps());
            assertEquals(115, reloaded.getWindowDpiScalePct());
        } finally {
            pool.shutdownNow();
            conf.delete();
            prof.delete();
            tmpDir.delete();
        }
    }
}
