package io.github.lengsukq.opaldesk.daemon;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DisplayGeometryUtilMirrorTest {

    @Test
    public void resolveTargetDisplayModeSupportsExplicitAndToggleArgs() {
        assertEquals(DisplayGeometryUtil.DISPLAY_MODE_MIRROR,
                DisplayGeometryUtil.resolveTargetDisplayMode(DisplayGeometryUtil.DISPLAY_MODE_DEX, 1));
        assertEquals(DisplayGeometryUtil.DISPLAY_MODE_DEX,
                DisplayGeometryUtil.resolveTargetDisplayMode(DisplayGeometryUtil.DISPLAY_MODE_MIRROR, 0));
        // Idempotent explicit mode requests
        assertEquals(DisplayGeometryUtil.DISPLAY_MODE_DEX,
                DisplayGeometryUtil.resolveTargetDisplayMode(DisplayGeometryUtil.DISPLAY_MODE_DEX, 0));
        assertEquals(DisplayGeometryUtil.DISPLAY_MODE_MIRROR,
                DisplayGeometryUtil.resolveTargetDisplayMode(DisplayGeometryUtil.DISPLAY_MODE_MIRROR, 1));
        // Pure toggle sentinel (-1 or 0xFF)
        assertEquals(DisplayGeometryUtil.DISPLAY_MODE_MIRROR,
                DisplayGeometryUtil.resolveTargetDisplayMode(DisplayGeometryUtil.DISPLAY_MODE_DEX, -1));
        assertEquals(DisplayGeometryUtil.DISPLAY_MODE_DEX,
                DisplayGeometryUtil.resolveTargetDisplayMode(DisplayGeometryUtil.DISPLAY_MODE_MIRROR, 0xFF));
    }

    @Test
    public void computeMirrorStreamSpecPreservesPortraitAndLandscapeAspectRatio() {
        // 1440x3200 portrait phone mirrored to 2560x1600 tablet -> capped at 2400 -> exact 1080x2400
        int[] portrait = DisplayGeometryUtil.computeMirrorStreamSpec(1440, 3200, 560, 2560, 8);
        assertEquals(1080, portrait[0]);
        assertEquals(2400, portrait[1]);
        assertEquals(420, portrait[2]);
        assertEquals(0, portrait[0] % 8);
        assertEquals(0, portrait[1] % 8);

        // Same phone rotated landscape (3200x1440) -> exact 2400x1080
        int[] landscape = DisplayGeometryUtil.computeMirrorStreamSpec(3200, 1440, 560, 2560, 8);
        assertEquals(2400, landscape[0]);
        assertEquals(1080, landscape[1]);
        assertEquals(420, landscape[2]);

        // 1440x3200 phone mirrored to 1920x1080 receiver -> 864x1920
        int[] fhd = DisplayGeometryUtil.computeMirrorStreamSpec(1440, 3200, 560, 1920, 8);
        assertEquals(864, fhd[0]);
        assertEquals(1920, fhd[1]);

        // 1080x2400 phone does not upscale
        int[] nativePhone = DisplayGeometryUtil.computeMirrorStreamSpec(1080, 2400, 440, 2560, 8);
        assertEquals(1080, nativePhone[0]);
        assertEquals(2400, nativePhone[1]);
        assertEquals(440, nativePhone[2]);
    }

    @Test
    public void mapRemotePointToTargetDisplayScalesProportionallyAndClamps() {
        // Identity in DeX mode
        float[] dexPt = DisplayGeometryUtil.mapRemotePointToTargetDisplay(1280f, 800f, 2560, 1600, 2560, 1600);
        assertEquals(1280f, dexPt[0], 0.01f);
        assertEquals(800f, dexPt[1], 0.01f);

        // Portrait mirror: 1080x2400 stream -> 1440x3200 Display 0
        float[] center = DisplayGeometryUtil.mapRemotePointToTargetDisplay(540f, 1200f, 1080, 2400, 1440, 3200);
        assertEquals(720f, center[0], 0.01f);
        assertEquals(1600f, center[1], 0.01f);

        // Landscape mirror: 2400x1080 stream -> 3200x1440 Display 0
        float[] land = DisplayGeometryUtil.mapRemotePointToTargetDisplay(1200f, 540f, 2400, 1080, 3200, 1440);
        assertEquals(1600f, land[0], 0.01f);
        assertEquals(720f, land[1], 0.01f);

        // Out-of-bounds input clamps cleanly inside target bounds
        float[] clamped = DisplayGeometryUtil.mapRemotePointToTargetDisplay(9999f, -50f, 1080, 2400, 1440, 3200);
        assertTrue(clamped[0] >= 0f && clamped[0] <= 1439f);
        assertEquals(0f, clamped[1], 0.01f);
    }
}
