package io.github.lengsukq.opaldesk.daemon.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class CastEfficiencyPolicyTest {
    private static final int MAX_KBPS = 28_000;

    @Test
    public void honorsExplicitClientBitrateWithoutUpwardOverride() {
        // Explicit low-power or standalone floating window bitrates must NOT be overridden upward.
        assertEquals(6_000, DisplayGeometryUtil.resolveSessionBitrateKbps(
                6_000, 2560, 1600, 120f, true, MAX_KBPS));
        assertEquals(9_000, DisplayGeometryUtil.resolveSessionBitrateKbps(
                9_000, 1920, 1080, 60f, true, MAX_KBPS));
        assertEquals(16_000, DisplayGeometryUtil.resolveSessionBitrateKbps(
                16_000, 2560, 1440, 120f, false, MAX_KBPS));

        // Still clamps extreme values to [2500, 28000].
        assertEquals(2_500, DisplayGeometryUtil.resolveSessionBitrateKbps(
                500, 1920, 1080, 60f, true, MAX_KBPS));
        assertEquals(28_000, DisplayGeometryUtil.resolveSessionBitrateKbps(
                60_000, 3840, 2160, 120f, false, MAX_KBPS));
    }

    @Test
    public void autoBitrateScalesDownForHevcVersusH264() {
        int h264Fhd60 = DisplayGeometryUtil.resolveSessionBitrateKbps(
                0, 1920, 1080, 60f, false, MAX_KBPS);
        int hevcFhd60 = DisplayGeometryUtil.resolveSessionBitrateKbps(
                0, 1920, 1080, 60f, true, MAX_KBPS);

        assertEquals(11_000, h264Fhd60);
        assertEquals(8_250, hevcFhd60);
        assertTrue(hevcFhd60 < h264Fhd60);

        int h264Uhd120 = DisplayGeometryUtil.resolveSessionBitrateKbps(
                -1, 3840, 2160, 120f, false, MAX_KBPS);
        int hevcUhd120 = DisplayGeometryUtil.resolveSessionBitrateKbps(
                -1, 3840, 2160, 120f, true, MAX_KBPS);

        assertEquals(28_000, h264Uhd120);
        assertEquals(21_000, hevcUhd120);
    }

    @Test
    public void rawTransportReusesHeaderBufferCleanlyAcrossMultipleFrames() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpalCastFrameTransport writer = OpalCastFrameTransport.raw(null, out);

        byte[] p1 = new byte[]{1, 2, 3, 4, 5};
        byte[] p2 = new byte[0];
        byte[] p3 = new byte[]{0x7F, (byte) 0x80, (byte) 0xFF};

        writer.writeFrame(OpalCastProtocol.TYPE_VIDEO_FRAME, OpalCastProtocol.FLAG_VIDEO_KEYFRAME, 111_222L, p1, 0, p1.length);
        writer.writeFrame(OpalCastProtocol.TYPE_PING, 0, 333_444L, p2, 0, 0);
        writer.writeFrame(OpalCastProtocol.TYPE_AUDIO_FRAME, 0, 555_666L, p3, 1, 2);
        writer.flush();

        OpalCastFrameTransport reader = OpalCastFrameTransport.raw(
                new ByteArrayInputStream(out.toByteArray()), null);

        OpalCastProtocol.Frame f1 = reader.readFrame();
        assertNotNull(f1);
        assertEquals(OpalCastProtocol.TYPE_VIDEO_FRAME, f1.type);
        assertEquals(OpalCastProtocol.FLAG_VIDEO_KEYFRAME, f1.flags);
        assertEquals(111_222L, f1.timestampUs);
        assertArrayEquals(p1, f1.payload);

        OpalCastProtocol.Frame f2 = reader.readFrame();
        assertNotNull(f2);
        assertEquals(OpalCastProtocol.TYPE_PING, f2.type);
        assertEquals(0, f2.flags);
        assertEquals(333_444L, f2.timestampUs);
        assertEquals(0, f2.payload.length);

        OpalCastProtocol.Frame f3 = reader.readFrame();
        assertNotNull(f3);
        assertEquals(OpalCastProtocol.TYPE_AUDIO_FRAME, f3.type);
        assertEquals(0, f3.flags);
        assertEquals(555_666L, f3.timestampUs);
        assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0xFF}, f3.payload);
    }

    @Test
    public void fitsOversizedPortraitAndLandscapeDimensionsToEncoderBounds() {
        // Phone 1080x2400 -> mid-tier tablet encoder capped at 1920x1920 (e.g. Lenovo TB331FC).
        int[] portrait = DisplayGeometryUtil.fitDimensionsToMaxBounds(1080, 2400, 1920, 1920, 16, 16);
        assertEquals(864, portrait[0]);
        assertEquals(1920, portrait[1]);
        assertEquals(0, portrait[0] % 16);
        assertEquals(0, portrait[1] % 16);

        // Already within bounds stays unchanged (8-aligned).
        int[] inBounds = DisplayGeometryUtil.fitDimensionsToMaxBounds(1920, 1200, 3840, 2160, 8, 8);
        assertArrayEquals(new int[]{1920, 1200}, inBounds);

        // Unknown bounds (0) passes through aligned.
        int[] unknown = DisplayGeometryUtil.fitDimensionsToMaxBounds(1080, 2400, 0, 0, 8, 8);
        assertArrayEquals(new int[]{1080, 2400}, unknown);
    }

    @Test
    public void buildsDescendingEncoderFpsFallbacksAndStatusBarInsetSdkGate() {
        assertArrayEquals(new float[]{60f, 30f}, DisplayGeometryUtil.buildEncoderFpsCandidates(120f, 60f), 0.01f);
        assertArrayEquals(new float[]{120f, 60f, 30f}, DisplayGeometryUtil.buildEncoderFpsCandidates(120f, 0f), 0.01f);
        assertArrayEquals(new float[]{90f, 60f, 30f}, DisplayGeometryUtil.buildEncoderFpsCandidates(90f, 120f), 0.01f);
        assertArrayEquals(new float[]{30f}, DisplayGeometryUtil.buildEncoderFpsCandidates(30f, 60f), 0.01f);

        // Android 11-13 (API 30-33) skip shell TYPE_STATUS_BAR inset window; Android 14+ (API 34+) enabled.
        assertFalse(DisplayGeometryUtil.isVirtualDisplayStatusBarInsetSupported(31));
        assertFalse(DisplayGeometryUtil.isVirtualDisplayStatusBarInsetSupported(33));
        assertTrue(DisplayGeometryUtil.isVirtualDisplayStatusBarInsetSupported(34));
        assertTrue(DisplayGeometryUtil.isVirtualDisplayStatusBarInsetSupported(36));

        // DeviceState serializes optional fullscreenWindowTitle for merged receiver top-center pill.
        OpalCastProtocol.DeviceState ds = new OpalCastProtocol.DeviceState(
                "Phone", 3, OpalCastProtocol.DISPLAY_MODE_DEX,
                1920, 1200, 240, 60.0, 0, 12_000, 100, false, true,
                1, false, 0, "DEX", "", "",
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                "Bilibili");
        String json = new String(OpalCastProtocol.encodeDeviceState(ds), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"fullscreenWindowTitle\":\"Bilibili\""));
    }

    @Test
    public void encoderThermalAndQualityPoliciesSelectOptimalProfileCbrAndKeepalive() {
        // Static repeat-frame keepalive: 10Hz only for Android high-refresh peers (>60Hz), 1Hz elsewhere.
        assertEquals(DisplayGeometryUtil.AGGRESSIVE_REPEAT_FRAME_AFTER_US,
                DisplayGeometryUtil.resolveRepeatPreviousFrameAfterUs("android", 120f));
        assertEquals(DisplayGeometryUtil.AGGRESSIVE_REPEAT_FRAME_AFTER_US,
                DisplayGeometryUtil.resolveRepeatPreviousFrameAfterUs("ANDROID", 90f));
        assertEquals(DisplayGeometryUtil.DEFAULT_REPEAT_FRAME_AFTER_US,
                DisplayGeometryUtil.resolveRepeatPreviousFrameAfterUs("android", 60f));
        assertEquals(DisplayGeometryUtil.DEFAULT_REPEAT_FRAME_AFTER_US,
                DisplayGeometryUtil.resolveRepeatPreviousFrameAfterUs("macos", 120f));
        assertEquals(DisplayGeometryUtil.DEFAULT_REPEAT_FRAME_AFTER_US,
                DisplayGeometryUtil.resolveRepeatPreviousFrameAfterUs("windows", 120f));
        assertEquals(DisplayGeometryUtil.DEFAULT_REPEAT_FRAME_AFTER_US,
                DisplayGeometryUtil.resolveRepeatPreviousFrameAfterUs("", 120f));

        // Qualcomm & unknown encoders stay on CBR to keep zero-lookahead; MTK/Exynos/HiSilicon unlock VBR.
        assertTrue(DisplayGeometryUtil.shouldPreferCbrMode("c2.qti.hevc.encoder"));
        assertTrue(DisplayGeometryUtil.shouldPreferCbrMode("OMX.qcom.video.encoder.avc"));
        assertTrue(DisplayGeometryUtil.shouldPreferCbrMode(null));
        assertFalse(DisplayGeometryUtil.shouldPreferCbrMode("c2.mtk.hevc.encoder"));
        assertFalse(DisplayGeometryUtil.shouldPreferCbrMode("c2.exynos.h264.encoder"));
        assertFalse(DisplayGeometryUtil.shouldPreferCbrMode("c2.hisi.hevc.encoder"));

        // H.264 prefers High Profile over Main/Baseline with highest supported level.
        assertArrayEquals(
                new int[]{DisplayGeometryUtil.AVC_PROFILE_HIGH, 65536},
                DisplayGeometryUtil.selectPreferredProfileLevel(
                        "video/avc",
                        new int[]{1, 2, 8, 8},
                        new int[]{32768, 32768, 16384, 65536}));
        // H.264 falls back to Main if High is absent.
        assertArrayEquals(
                new int[]{DisplayGeometryUtil.AVC_PROFILE_MAIN, 32768},
                DisplayGeometryUtil.selectPreferredProfileLevel(
                        "video/avc",
                        new int[]{1, 2},
                        new int[]{65536, 32768}));
        // HEVC picks Main profile with highest supported level.
        assertArrayEquals(
                new int[]{DisplayGeometryUtil.HEVC_PROFILE_MAIN, 16384},
                DisplayGeometryUtil.selectPreferredProfileLevel(
                        "video/hevc",
                        new int[]{1, 2, 1},
                        new int[]{4096, 32768, 16384}));
    }

    @Test
    public void transportAwareBitrateCapsAndZeroFlickerReconfigureFastPaths() throws IOException {
        assertTrue(DisplayGeometryUtil.isWiredSinkType(DisplaySink.SINK_REMOTE_USB));
        assertTrue(DisplayGeometryUtil.isWiredSinkType(DisplaySink.SINK_REMOTE_USB_TETHER));
        assertFalse(DisplayGeometryUtil.isWiredSinkType(DisplaySink.SINK_REMOTE_WIFI));
        assertFalse(DisplayGeometryUtil.isWiredSinkType(DisplaySink.SINK_REMOTE_WIFI_ADB));

        int wiredCap = DisplayGeometryUtil.resolveMaxBitrateKbpsForSink(DisplaySink.SINK_REMOTE_USB);
        int tetherCap = DisplayGeometryUtil.resolveMaxBitrateKbpsForSink(DisplaySink.SINK_REMOTE_USB_TETHER);
        int wifiCap = DisplayGeometryUtil.resolveMaxBitrateKbpsForSink(DisplaySink.SINK_REMOTE_WIFI);
        assertEquals(DisplayGeometryUtil.WIRED_MAX_BITRATE_KBPS, wiredCap);
        assertEquals(DisplayGeometryUtil.WIRED_MAX_BITRATE_KBPS, tetherCap);
        assertEquals(DisplayGeometryUtil.WIRELESS_MAX_BITRATE_KBPS, wifiCap);

        // Wired USB allows up to 100 Mbps and clamps extreme custom requests (e.g. 10,000 Mbps);
        // wireless allows up to 80 Mbps.
        assertEquals(45_000, DisplayGeometryUtil.resolveSessionBitrateKbps(
                45_000, 3840, 2160, 60f, true, wiredCap));
        assertEquals(100_000, DisplayGeometryUtil.resolveSessionBitrateKbps(
                100_000, 3840, 2160, 60f, true, wiredCap));
        assertEquals(100_000, DisplayGeometryUtil.resolveSessionBitrateKbps(
                10_000_000, 3840, 2160, 60f, true, wiredCap));
        assertEquals(80_000, DisplayGeometryUtil.resolveSessionBitrateKbps(
                100_000, 3840, 2160, 60f, true, wifiCap));

        // Reconfigure fast-path classifier: NO_OP, BITRATE_ONLY, DPI_ONLY, FULL_RECONFIGURE.
        assertEquals(DisplayGeometryUtil.ReconfigureAction.NO_OP,
                DisplayGeometryUtil.planReconfigureAction(
                        2560, 1440, 240, 60f, 16_000, 0, 0,
                        2560, 1440, 240, 60f, 16_000, 0, 0));
        assertEquals(DisplayGeometryUtil.ReconfigureAction.BITRATE_ONLY,
                DisplayGeometryUtil.planReconfigureAction(
                        2560, 1440, 240, 60f, 16_000, 0, 0,
                        2560, 1440, 240, 60f, 28_000, 0, 0));
        assertEquals(DisplayGeometryUtil.ReconfigureAction.DPI_ONLY,
                DisplayGeometryUtil.planReconfigureAction(
                        2560, 1440, 240, 60f, 16_000, 0, 0,
                        2560, 1440, 280, 60f, 22_000, 0, 0));
        assertEquals(DisplayGeometryUtil.ReconfigureAction.FULL_RECONFIGURE,
                DisplayGeometryUtil.planReconfigureAction(
                        2560, 1440, 240, 60f, 16_000, 0, 0,
                        2560, 1440, 240, 120f, 16_000, 0, 0));
        assertEquals(DisplayGeometryUtil.ReconfigureAction.FULL_RECONFIGURE,
                DisplayGeometryUtil.planReconfigureAction(
                        2560, 1440, 240, 60f, 16_000, 0, 0,
                        1920, 1080, 240, 60f, 16_000, 0, 0));

        // HELLO parser extracts optional clientPlatform and usb_tether transportHint cleanly.
        byte[] helloJson = ("{\"protocolVersion\":1,\"clientName\":\"Pad\",\"clientId\":\"c1\","
                + "\"transportHint\":\"usb_tether\",\"clientPlatform\":\"Android\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OpalCastProtocol.HelloMsg parsed = OpalCastProtocol.decodeHello(helloJson);
        assertEquals("usb_tether", parsed.transportHint);
        assertEquals("android", parsed.clientPlatform);
    }

    @Test
    public void gradedWindowSupersamplingBalancesSharpnessAndGpuLoadAndPersistsConfig() throws Exception {
        // 1280x800 desktop window on a 1920x1080 canvas hosted by a 560dpi flagship phone.
        DisplayGeometryUtil.IntRect win = new DisplayGeometryUtil.IntRect(100, 100, 1380, 900);
        int[] compat = DisplayGeometryUtil.resolveWindowBufferSpec(
                win, 240, false, WindowProfile.DESKTOP, 100, 560, 0, DisplayGeometryUtil.WINDOW_SUPERSAMPLE_COMPAT);
        int[] balanced = DisplayGeometryUtil.resolveWindowBufferSpec(
                win, 240, false, WindowProfile.DESKTOP, 100, 560, 0, DisplayGeometryUtil.WINDOW_SUPERSAMPLE_BALANCED);
        int[] native1x = DisplayGeometryUtil.resolveWindowBufferSpec(
                win, 240, false, WindowProfile.DESKTOP, 100, 560, 0, DisplayGeometryUtil.WINDOW_SUPERSAMPLE_NATIVE_1X);

        long compatPixels = (long) compat[0] * compat[1];
        long balancedPixels = (long) balanced[0] * balanced[1];
        long nativePixels = (long) native1x[0] * native1x[1];

        assertTrue("NATIVE_1X must match exact window pixel width (1280), got " + native1x[0], native1x[0] == 1280);
        assertTrue("BALANCED must reduce GPU pixel area vs COMPAT", balancedPixels < compatPixels);
        assertTrue("NATIVE_1X must have smallest GPU pixel area", nativePixels < balancedPixels);
        assertTrue("BALANCED long edge capped at 1920", Math.max(balanced[0], balanced[1]) <= 1920);

        // Verify DaemonConfig persists windowSupersampleMode across reload.
        File dir = Files.createTempDirectory("opal_ss_cfg").toFile();
        File conf = new File(dir, "v2.conf");
        File prof = new File(dir, "prof.conf");
        try {
            DaemonConfig cfg1 = new DaemonConfig(conf, prof);
            cfg1.updateDisplayPrefs(120, 100, DisplayGeometryUtil.WINDOW_SUPERSAMPLE_NATIVE_1X);
            DaemonConfig cfg2 = new DaemonConfig(conf, prof);
            cfg2.loadAll();
            assertEquals(120, cfg2.getTargetFps());
            assertEquals(DisplayGeometryUtil.WINDOW_SUPERSAMPLE_NATIVE_1X, cfg2.getWindowSupersampleMode());
        } finally {
            conf.delete();
            prof.delete();
            dir.delete();
        }
    }

    @Test
    public void videoFrameBufferPoolReusesBucketsAndAvoidsOversizeRetention() {
        VideoFrameBufferPool pool = new VideoFrameBufferPool();
        byte[] a = pool.acquire(10_000);
        assertEquals(16 * 1024, a.length);
        pool.release(a);
        assertEquals(1, pool.pooledCountForSize(10_000));
        byte[] reused = pool.acquire(12_000);
        assertTrue("Expected pooled byte[] instance to be reused without reallocation", reused == a);
        assertEquals(0, pool.pooledCountForSize(10_000));

        // Oversized (>512 KiB) frames allocate exact length and are not retained in buckets.
        byte[] huge = pool.acquire(700_000);
        assertEquals(700_000, huge.length);
        pool.release(huge);
        assertEquals(0, pool.pooledCountForSize(700_000));

        // Clearing releases all cached references.
        pool.release(reused);
        pool.clear();
        assertEquals(0, pool.pooledCountForSize(10_000));
    }
}
