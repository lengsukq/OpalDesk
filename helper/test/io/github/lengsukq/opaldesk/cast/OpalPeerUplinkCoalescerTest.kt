package io.github.lengsukq.opaldesk.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpalPeerUplinkCoalescerTest {

    private fun makeTouch(action: Int, pointerCount: Int, marker: Byte): OpalPeerUplinkFrame {
        val payload = ByteArray(4)
        payload[0] = (action and 0xFF).toByte()
        payload[1] = 0
        payload[2] = (pointerCount and 0xFF).toByte()
        payload[3] = marker
        return OpalPeerUplinkFrame(OpalPeerUplinkCoalescer.TYPE_INPUT_TOUCH, 0, 1L, payload)
    }

    private fun makeMouse(action: Int, buttons: Int, x: Float): OpalPeerUplinkFrame {
        val bb = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
        bb.put((action and 0xFF).toByte())
        bb.putFloat(x)
        bb.putFloat(0.5f)
        bb.putInt(buttons)
        bb.putFloat(0f)
        return OpalPeerUplinkFrame(OpalPeerUplinkCoalescer.TYPE_INPUT_MOUSE, 0, 2L, bb.array())
    }

    @Test
    fun resolvesDecoderOperatingRatesWithoutShortMaxValueOverclock() {
        assertArrayEquals(
            intArrayOf(60, 0),
            OpalPeerUplinkCoalescer.resolveDecoderOperatingRates(60, supportsLowLatency = true),
        )
        assertArrayEquals(
            intArrayOf(120, 0),
            OpalPeerUplinkCoalescer.resolveDecoderOperatingRates(120, supportsLowLatency = true),
        )
        // Clamps low/high bounds cleanly.
        assertArrayEquals(
            intArrayOf(60, 0),
            OpalPeerUplinkCoalescer.resolveDecoderOperatingRates(30, supportsLowLatency = true),
        )
        assertArrayEquals(
            intArrayOf(165, 0),
            OpalPeerUplinkCoalescer.resolveDecoderOperatingRates(240, supportsLowLatency = true),
        )
        // Without FEATURE_LowLatency, uses 120 fps floor (still far below Short.MAX_VALUE = 32767).
        assertArrayEquals(
            intArrayOf(120, 0),
            OpalPeerUplinkCoalescer.resolveDecoderOperatingRates(60, supportsLowLatency = false),
        )
        assertArrayEquals(
            intArrayOf(144, 0),
            OpalPeerUplinkCoalescer.resolveDecoderOperatingRates(144, supportsLowLatency = false),
        )
    }

    @Test
    fun coalescesAdjacentTouchMovesWithMatchingPointerCountOnly() {
        val down = makeTouch(action = 0, pointerCount = 1, marker = 1)
        val move1 = makeTouch(action = OpalPeerUplinkCoalescer.TOUCH_MOVE, pointerCount = 1, marker = 2)
        val move2 = makeTouch(action = OpalPeerUplinkCoalescer.TOUCH_MOVE, pointerCount = 1, marker = 3)
        val move2FingerA = makeTouch(action = OpalPeerUplinkCoalescer.TOUCH_MOVE, pointerCount = 2, marker = 4)
        val move2FingerB = makeTouch(action = OpalPeerUplinkCoalescer.TOUCH_MOVE, pointerCount = 2, marker = 5)
        val up = makeTouch(action = 1, pointerCount = 1, marker = 6)

        val out = ArrayList<OpalPeerUplinkFrame>()
        OpalPeerUplinkCoalescer.coalesceBatch(
            listOf(down, move1, move2, move2FingerA, move2FingerB, up),
            out,
        )

        assertEquals(4, out.size)
        assertEquals(1.toByte(), out[0].payload[3]) // DOWN preserved
        assertEquals(3.toByte(), out[1].payload[3]) // latest 1-finger MOVE kept
        assertEquals(5.toByte(), out[2].payload[3]) // latest 2-finger MOVE kept
        assertEquals(6.toByte(), out[3].payload[3]) // UP preserved
    }

    @Test
    fun coalescesAdjacentMouseMovesWithMatchingButtonsAndPreservesDiscreteEvents() {
        val m1 = makeMouse(OpalPeerUplinkCoalescer.MOUSE_MOVE_ABS, buttons = 0, x = 0.1f)
        val m2 = makeMouse(OpalPeerUplinkCoalescer.MOUSE_MOVE_ABS, buttons = 0, x = 0.2f)
        val drag1 = makeMouse(OpalPeerUplinkCoalescer.MOUSE_MOVE_ABS, buttons = 1, x = 0.3f)
        val drag2 = makeMouse(OpalPeerUplinkCoalescer.MOUSE_MOVE_ABS, buttons = 1, x = 0.4f)
        val scroll1 = makeMouse(action = 4, buttons = 0, x = 0.4f)
        val scroll2 = makeMouse(action = 4, buttons = 0, x = 0.4f)
        val key = OpalPeerUplinkFrame(0x22, 0, 3L, byteArrayOf(1, 2, 3))

        val out = ArrayList<OpalPeerUplinkFrame>()
        OpalPeerUplinkCoalescer.coalesceBatch(
            listOf(m1, m2, drag1, drag2, scroll1, scroll2, key),
            out,
        )

        assertEquals(5, out.size)
        assertEquals(m2, out[0])      // hover move coalesced to latest
        assertEquals(drag2, out[1])   // drag move (button=1) separated & coalesced
        assertEquals(scroll1, out[2]) // discrete scroll never dropped
        assertEquals(scroll2, out[3])
        assertEquals(key, out[4])     // key event never dropped
    }

    @Test
    fun buildsMultiTierDecoderFallbackPlansEndingWithBareMinimalConfig() {
        val lowLatPlans = OpalPeerUplinkCoalescer.buildDecoderConfigPlans(90, supportsLowLatency = true)
        assertEquals(4, lowLatPlans.size)
        assertEquals(OpalPeerUplinkCoalescer.DecoderConfigPlan(true, true, 90), lowLatPlans[0])
        assertEquals(OpalPeerUplinkCoalescer.DecoderConfigPlan(true, true, 0), lowLatPlans[1])
        assertEquals(OpalPeerUplinkCoalescer.DecoderConfigPlan(false, true, 0), lowLatPlans[2])
        assertEquals(OpalPeerUplinkCoalescer.DecoderConfigPlan(false, false, 0), lowLatPlans[3])

        val stdPlans = OpalPeerUplinkCoalescer.buildDecoderConfigPlans(90, supportsLowLatency = false)
        assertEquals(3, stdPlans.size)
        assertEquals(OpalPeerUplinkCoalescer.DecoderConfigPlan(false, true, 120), stdPlans[0])
        assertEquals(OpalPeerUplinkCoalescer.DecoderConfigPlan(false, true, 0), stdPlans[1])
        assertEquals(OpalPeerUplinkCoalescer.DecoderConfigPlan(false, false, 0), stdPlans[2])
    }
}
