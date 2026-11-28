package io.github.lengsukq.opaldesk.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class OpalCastUdpReceiverTest {
    @Test
    fun punchPacket_matchesOpu1HeaderSpec() {
        val pkt = OpalCastUdpReceiver.buildPunchPacket(0x12345678)
        assertEquals(OpalCastUdpReceiver.HEADER_BYTES, pkt.size)
        val bb = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        assertEquals(OpalCastUdpReceiver.MAGIC_OPU1, bb.int)
        assertEquals(0x12345678, bb.int)
        assertEquals(0, bb.int)
        assertEquals(OpalCastUdpReceiver.MEDIA_PUNCH, bb.get().toInt() and 0xFF)
    }

    @Test
    fun rsGf256_recoversMissingDataShardFromCauchyParity() {
        val dataShards = 4
        val parityShards = 2
        val shardSize = 16
        val originals = Array(dataShards) { idx ->
            ByteArray(shardSize) { b -> ((idx * 37 + b * 13) and 0xFF).toByte() }
        }
        val parity0 = ByteArray(shardSize)
        val parity1 = ByteArray(shardSize)
        for (b in 0 until shardSize) {
            var acc0 = 0
            var acc1 = 0
            for (c in 0 until dataShards) {
                val v = originals[c][b].toInt() and 0xFF
                acc0 = acc0 xor OpalCastUdpReceiver.RsGf256.mul(OpalCastUdpReceiver.RsGf256.inv(0 xor (parityShards + c)), v)
                acc1 = acc1 xor OpalCastUdpReceiver.RsGf256.mul(OpalCastUdpReceiver.RsGf256.inv(1 xor (parityShards + c)), v)
            }
            parity0[b] = acc0.toByte()
            parity1[b] = acc1.toByte()
        }
        // Drop data shards 1 and 3, keep data 0, 2 and parity 4, 5
        val received = mapOf(
            0 to originals[0],
            2 to originals[2],
            4 to parity0,
            5 to parity1,
        )
        val recovered = OpalCastUdpReceiver.RsGf256.recoverDataShards(dataShards, parityShards, received, shardSize)
        assertNotNull(recovered)
        for (i in 0 until dataShards) {
            assertArrayEquals(originals[i], recovered!![i])
        }
    }

    @Test
    fun aesGcmDecryptAndPlaintextFraming_roundTrips() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val sid = 0x0A0B0C0D
        val seq = 42
        val media = OpalCastUdpReceiver.MEDIA_VIDEO
        val rawPayload = "opalcast-hevc-nalu".toByteArray()
        val ptsUs = 123_456_789L
        val plain = ByteBuffer.allocate(9 + rawPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putLong(ptsUs)
            .put(OpalCastUdpReceiver.FLAG_KEYFRAME.toByte())
            .put(rawPayload)
            .array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, OpalCastUdpReceiver.buildIv(sid, seq, media)),
        )
        val encrypted = cipher.doFinal(plain)
        val framed = ByteBuffer.allocate(4 + encrypted.size + 6).order(ByteOrder.BIG_ENDIAN)
            .putInt(encrypted.size)
            .put(encrypted)
            .array()

        val unframed = OpalCastUdpReceiver.extractCipherFromFramed(framed)
        assertNotNull(unframed)
        val decrypted = OpalCastUdpReceiver.decryptFrame(key, sid, seq, media, unframed!!)
        assertNotNull(decrypted)
        val parsed = OpalCastUdpReceiver.extractPlaintextFrame(decrypted!!)
        assertNotNull(parsed)
        assertEquals(ptsUs, parsed!!.first)
        assertEquals(OpalCastUdpReceiver.FLAG_KEYFRAME, parsed.second)
        assertArrayEquals(rawPayload, parsed.third)

        val b64 = java.util.Base64.getEncoder().encodeToString(key)
        assertArrayEquals(key, OpalCastUdpReceiver.decodeBase64(b64))
    }
}
