package io.github.lengsukq.opaldesk.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerCameraGeometryTest {

    @Test
    fun uprightRotationMatchesOfficialAospSpecAcrossAllRotations() {
        // Standard tablet/phone: BACK=90, FRONT=270
        // Portrait (ROTATION_0 = 0°)
        assertEquals(90, PeerCameraGeometry.computeUprightRotationCw(90, isFront = false, displayRotationDeg = 0))
        assertEquals(270, PeerCameraGeometry.computeUprightRotationCw(270, isFront = true, displayRotationDeg = 0))

        // Landscape CCW (ROTATION_90 = 90°)
        assertEquals(0, PeerCameraGeometry.computeUprightRotationCw(90, isFront = false, displayRotationDeg = 90))
        assertEquals(0, PeerCameraGeometry.computeUprightRotationCw(270, isFront = true, displayRotationDeg = 90))

        // Reverse Portrait (ROTATION_180 = 180°)
        assertEquals(270, PeerCameraGeometry.computeUprightRotationCw(90, isFront = false, displayRotationDeg = 180))
        assertEquals(90, PeerCameraGeometry.computeUprightRotationCw(270, isFront = true, displayRotationDeg = 180))

        // Landscape CW (ROTATION_270 = 270°)
        assertEquals(180, PeerCameraGeometry.computeUprightRotationCw(90, isFront = false, displayRotationDeg = 270))
        assertEquals(180, PeerCameraGeometry.computeUprightRotationCw(270, isFront = true, displayRotationDeg = 270))
    }

    @Test
    fun rotateNv21RotatesLumaAndInterleavedVuAccuratelyAndFour90sReturnIdentity() {
        // 4x2 NV21 image: 8 Y bytes + 4 UV bytes (2x1 VU pairs)
        // Y rows:
        //   [1, 2, 3, 4]
        //   [5, 6, 7, 8]
        // VU row (2 pairs): (10, 20), (30, 40)
        val src = byteArrayOf(
            1, 2, 3, 4,
            5, 6, 7, 8,
            10, 20, 30, 40,
        )

        val r90 = PeerCameraGeometry.rotateNv21(src, 4, 2, 90)
        assertEquals(2, r90.width)
        assertEquals(4, r90.height)
        // Expected 2x4 Y:
        //   [5, 1]
        //   [6, 2]
        //   [7, 3]
        //   [8, 4]
        // Expected 1x2 VU: (10, 20), (30, 40)
        assertArrayEquals(
            byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4, 10, 20, 30, 40),
            r90.data,
        )

        val r180 = PeerCameraGeometry.rotateNv21(src, 4, 2, 180)
        assertEquals(4, r180.width)
        assertEquals(2, r180.height)
        assertArrayEquals(
            byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1, 30, 40, 10, 20),
            r180.data,
        )

        val r270 = PeerCameraGeometry.rotateNv21(src, 4, 2, 270)
        assertEquals(2, r270.width)
        assertEquals(4, r270.height)
        assertArrayEquals(
            byteArrayOf(4, 8, 3, 7, 2, 6, 1, 5, 30, 40, 10, 20),
            r270.data,
        )

        // Applying 90° four times must equal the original buffer
        var cur = PeerCameraGeometry.RotatedNv21(src, 4, 2)
        repeat(4) {
            cur = PeerCameraGeometry.rotateNv21(cur.data, cur.width, cur.height, 90)
        }
        assertEquals(4, cur.width)
        assertEquals(2, cur.height)
        assertArrayEquals(src, cur.data)
    }
}
