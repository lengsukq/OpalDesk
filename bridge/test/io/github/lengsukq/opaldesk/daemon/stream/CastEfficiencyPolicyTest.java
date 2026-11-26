package io.github.lengsukq.opaldesk.daemon.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
}
