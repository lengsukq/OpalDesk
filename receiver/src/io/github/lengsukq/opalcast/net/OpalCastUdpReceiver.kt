package io.github.lengsukq.opalcast.net

import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lightweight pure-Kotlin UDP + AES-128-GCM + Reed-Solomon GF(2^8) (poly `0x11D`) FEC receiver
 * for `:receiver` (zero AndroidX, Android 5.0–17 compatible), aligned with `:bridge` `OPU1`
 * 20-byte Big-Endian framing.
 *
 * Wire header (20 bytes, Big-Endian):
 * - `0..3`:   magic = `0x4F505531` (`"OPU1"`)
 * - `4..7`:   sessionId (int32)
 * - `8..11`:  frameSeq (int32)
 * - `12`:     mediaType (`0x02`=video, `0x04`=audio, `0x7F`=punch/keepalive)
 * - `13`:     flags (`bit0`=keyframe, `bit1`=parity, `bit2`=config_inline)
 * - `14`:     shardIndex (uint8)
 * - `15`:     dataShards (uint8, 1..255)
 * - `16`:     parityShards (uint8, 0..255)
 * - `17..18`: payloadLen (uint16)
 * - `19`:     reserved
 *
 * Whole-frame encryption layout (100% aligned with `:bridge` `OpalCastUdpMediaChannel`):
 * - Reassembled RS data shards carry `[cipherAndTagLen(4B BE) || cipherAndTag || zeroPad]`
 * - AES-128-GCM with 12-byte IV = `sessionId(4B BE) || frameSeq(4B BE) || mediaType(1B) || 0,0,0`
 * - Decrypted plaintext = `[ptsUs(8B BE) || mediaFlags(1B) || rawPayload]`
 * - RS Cauchy coefficient: `gfInv(parityRow xor (parityShards + dataCol))` over GF(2^8) poly `0x11D`.
 */
internal class OpalCastUdpReceiver(
    private val socket: DatagramSocket,
    private val remoteHost: String,
    private val remotePort: Int,
    private val sessionId: Int,
    private val aesKey: ByteArray,
    private val onVideoFrame: (payload: ByteArray, length: Int, flags: Int, tsUs: Long) -> Unit,
    private val onAudioFrame: (payload: ByteArray, tsUs: Long) -> Unit,
) {
    companion object {
        private const val TAG = "OpalCastUdpRx"
        const val MAGIC_OPU1 = 0x4F505531
        const val HEADER_BYTES = 20
        const val MEDIA_VIDEO = 0x02
        const val MEDIA_AUDIO = 0x04
        const val MEDIA_PUNCH = 0x7F
        const val FLAG_KEYFRAME = 0x01
        const val FLAG_PARITY = 0x02
        const val FLAG_CONFIG_INLINE = 0x04
        const val DEFAULT_FEC_PCT = 20
        private const val MAX_PACKET_BYTES = 2048
        private const val MAX_PENDING_FRAMES = 8
        private const val FRAME_STALE_MS = 180L
        private const val PUNCH_KEEPALIVE_MS = 4_500L

        fun openLocalSocket(targetNetwork: Network? = null): DatagramSocket? = runCatching {
            DatagramSocket(0).apply {
                try { receiveBufferSize = 1024 * 1024 } catch (_: Throwable) {}
                try { soTimeout = 1_000 } catch (_: Throwable) {}
                if (targetNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    runCatching { targetNetwork.bindSocket(this) }
                }
            }
        }.getOrNull()

        fun buildPunchPacket(sessionId: Int): ByteArray = ByteArray(HEADER_BYTES).apply {
            writeIntBe(this, 0, MAGIC_OPU1)
            writeIntBe(this, 4, sessionId)
            writeIntBe(this, 8, 0)
            this[12] = MEDIA_PUNCH.toByte()
            this[13] = 0
            this[14] = 0
            this[15] = 1
            this[16] = 0
            this[17] = 0
            this[18] = 0
            this[19] = 0
        }

        fun buildIv(sessionId: Int, frameSeq: Int, mediaType: Int): ByteArray = ByteArray(12).apply {
            writeIntBe(this, 0, sessionId)
            writeIntBe(this, 4, frameSeq)
            this[8] = (mediaType and 0xFF).toByte()
            this[9] = 0
            this[10] = 0
            this[11] = 0
        }

        fun decryptFrame(aesKey: ByteArray, sessionId: Int, frameSeq: Int, mediaType: Int, cipherBytes: ByteArray): ByteArray? {
            if (aesKey.size != 16 || cipherBytes.size < 16) return null
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, buildIv(sessionId, frameSeq, mediaType)))
                cipher.doFinal(cipherBytes)
            } catch (_: Throwable) {
                null
            }
        }

        fun extractCipherFromFramed(framed: ByteArray): ByteArray? {
            if (framed.size < 20) return null
            val cipherLen = readIntBe(framed, 0)
            if (cipherLen < 16 || 4 + cipherLen > framed.size) return null
            return framed.copyOfRange(4, 4 + cipherLen)
        }

        fun extractPlaintextFrame(plaintext: ByteArray): Triple<Long, Int, ByteArray>? {
            if (plaintext.size < 9) return null
            val ptsUs = readLongBe(plaintext, 0)
            val mediaFlags = plaintext[8].toInt() and 0xFF
            return Triple(ptsUs, mediaFlags, plaintext.copyOfRange(9, plaintext.size))
        }

        fun decodeBase64(raw: String): ByteArray? {
            val clean = raw.trim().replace("\r", "").replace("\n", "")
            if (clean.isEmpty() || clean.length % 4 != 0) return null
            val out = ByteArray((clean.length / 4) * 3)
            var outPos = 0
            var i = 0
            while (i < clean.length) {
                val a = b64Val(clean[i]); val b = b64Val(clean[i + 1])
                val c = if (clean[i + 2] == '=') -2 else b64Val(clean[i + 2])
                val d = if (clean[i + 3] == '=') -2 else b64Val(clean[i + 3])
                if (a < 0 || b < 0 || c == -1 || d == -1) return null
                val n = (a shl 18) or (b shl 12) or ((if (c >= 0) c else 0) shl 6) or (if (d >= 0) d else 0)
                out[outPos++] = ((n ushr 16) and 0xFF).toByte()
                if (c >= 0) out[outPos++] = ((n ushr 8) and 0xFF).toByte()
                if (d >= 0) out[outPos++] = (n and 0xFF).toByte()
                i += 4
            }
            return if (outPos == out.size) out else out.copyOf(outPos)
        }

        private fun b64Val(ch: Char): Int = when (ch) {
            in 'A'..'Z' -> ch.code - 'A'.code
            in 'a'..'z' -> ch.code - 'a'.code + 26
            in '0'..'9' -> ch.code - '0'.code + 52
            '+' -> 62
            '/' -> 63
            else -> -1
        }

        private fun writeIntBe(dst: ByteArray, off: Int, v: Int) {
            dst[off] = ((v ushr 24) and 0xFF).toByte()
            dst[off + 1] = ((v ushr 16) and 0xFF).toByte()
            dst[off + 2] = ((v ushr 8) and 0xFF).toByte()
            dst[off + 3] = (v and 0xFF).toByte()
        }

        private fun readIntBe(src: ByteArray, off: Int): Int =
            ((src[off].toInt() and 0xFF) shl 24) or ((src[off + 1].toInt() and 0xFF) shl 16) or
                ((src[off + 2].toInt() and 0xFF) shl 8) or (src[off + 3].toInt() and 0xFF)

        private fun readLongBe(src: ByteArray, off: Int): Long =
            ((readIntBe(src, off).toLong() and 0xFFFFFFFFL) shl 32) or (readIntBe(src, off + 4).toLong() and 0xFFFFFFFFL)
    }

    /** GF(2^8) arithmetic with primitive polynomial `0x11D` + systematic Cauchy RS decoder. */
    internal object RsGf256 {
        private val exp = IntArray(512)
        private val log = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                exp[i] = x
                log[x] = i
                x = x shl 1
                if ((x and 0x100) != 0) x = x xor 0x11D
            }
            for (i in 255 until 512) exp[i] = exp[i - 255]
        }

        fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a and 0xFF] + log[b and 0xFF]]
        fun inv(a: Int): Int = if (a == 0) 0 else exp[255 - log[a and 0xFF]]

        fun recoverDataShards(dataShards: Int, parityShards: Int, received: Map<Int, ByteArray>, shardSize: Int): Array<ByteArray>? {
            if (dataShards <= 0 || dataShards + parityShards > 255 || received.size < dataShards) return null
            val fast = Array(dataShards) { idx -> received[idx] }
            if (fast.all { it != null }) {
                return Array(dataShards) { idx -> fast[idx]!! }
            }
            val indices = received.keys.sorted().take(dataShards)
            val mat = Array(dataShards) { r ->
                val idx = indices[r]
                IntArray(dataShards) { c ->
                    if (idx < dataShards) {
                        if (idx == c) 1 else 0
                    } else {
                        inv((idx - dataShards) xor (parityShards + c))
                    }
                }
            }
            val rhs = Array(dataShards) { r ->
                val raw = received[indices[r]] ?: return null
                if (raw.size == shardSize) raw.copyOf() else raw.copyOf(shardSize)
            }
            for (col in 0 until dataShards) {
                var pivot = col
                while (pivot < dataShards && mat[pivot][col] == 0) pivot++
                if (pivot == dataShards) return null
                if (pivot != col) {
                    val tmpM = mat[col]; mat[col] = mat[pivot]; mat[pivot] = tmpM
                    val tmpR = rhs[col]; rhs[col] = rhs[pivot]; rhs[pivot] = tmpR
                }
                val invPiv = inv(mat[col][col])
                if (invPiv != 1) {
                    for (j in col until dataShards) mat[col][j] = mul(mat[col][j], invPiv)
                    val rowRhs = rhs[col]
                    for (b in 0 until shardSize) rowRhs[b] = mul(rowRhs[b].toInt() and 0xFF, invPiv).toByte()
                }
                for (r in 0 until dataShards) {
                    if (r == col) continue
                    val factor = mat[r][col]
                    if (factor == 0) continue
                    for (j in col until dataShards) mat[r][j] = mat[r][j] xor mul(factor, mat[col][j])
                    val dst = rhs[r]; val src = rhs[col]
                    for (b in 0 until shardSize) {
                        dst[b] = ((dst[b].toInt() and 0xFF) xor mul(factor, src[b].toInt() and 0xFF)).toByte()
                    }
                }
            }
            return rhs
        }
    }

    private class FrameBucket(
        val frameSeq: Int,
        val mediaType: Int,
        val flags: Int,
        val dataShards: Int,
        val parityShards: Int,
        val createdMs: Long,
    ) {
        val shards = HashMap<Int, ByteArray>(dataShards + parityShards)
        var maxShardLen = 0
    }

    private val active = AtomicBoolean(false)
    private val buckets = LinkedHashMap<Long, FrameBucket>(16)
    private var rxThread: Thread? = null
    private var lastDeliveredVideoSeq = -1

    fun start() {
        if (!active.compareAndSet(false, true)) return
        rxThread = Thread(::receiveLoop, "OpalCastUdpRx").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) {
            runCatching { socket.close() }
            return
        }
        runCatching { socket.close() }
        rxThread?.interrupt()
        rxThread = null
    }

    private fun receiveLoop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        try {
            val remoteAddr = InetAddress.getByName(remoteHost)
            val punch = buildPunchPacket(sessionId)
            repeat(3) {
                runCatching { socket.send(DatagramPacket(punch, punch.size, remoteAddr, remotePort)) }
            }
            var lastPunchMs = SystemClock.uptimeMillis()
            val buf = ByteArray(MAX_PACKET_BYTES)
            val pkt = DatagramPacket(buf, buf.size)
            Log.i(TAG, "UDP+FEC listening local=${socket.localPort} remote=$remoteHost:$remotePort sid=$sessionId")
            while (active.get() && !socket.isClosed) {
                try {
                    pkt.length = buf.size
                    socket.receive(pkt)
                    handlePacket(buf, pkt.length)
                } catch (_: java.net.SocketTimeoutException) {
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastPunchMs >= PUNCH_KEEPALIVE_MS) {
                    lastPunchMs = now
                    runCatching { socket.send(DatagramPacket(punch, punch.size, remoteAddr, remotePort)) }
                    evictStaleBuckets(now)
                }
            }
        } catch (t: Throwable) {
            if (active.get()) Log.w(TAG, "UDP receiver stopped, TCP/TLS remains active", t)
        } finally {
            active.set(false)
            runCatching { socket.close() }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        if (len < HEADER_BYTES) return
        if (readIntBe(buf, 0) != MAGIC_OPU1) return
        if (readIntBe(buf, 4) != sessionId) return
        val frameSeq = readIntBe(buf, 8)
        val mediaType = buf[12].toInt() and 0xFF
        if (mediaType != MEDIA_VIDEO && mediaType != MEDIA_AUDIO) return
        val flags = buf[13].toInt() and 0xFF
        val shardIdx = buf[14].toInt() and 0xFF
        val dataShards = buf[15].toInt() and 0xFF
        val parityShards = buf[16].toInt() and 0xFF
        val payloadLen = ((buf[17].toInt() and 0xFF) shl 8) or (buf[18].toInt() and 0xFF)
        if (dataShards <= 0 || shardIdx >= dataShards + parityShards || HEADER_BYTES + payloadLen > len) return
        if (mediaType == MEDIA_VIDEO && lastDeliveredVideoSeq >= 0 && frameSeq <= lastDeliveredVideoSeq) return

        val key = (mediaType.toLong() shl 32) or (frameSeq.toLong() and 0xFFFFFFFFL)
        val now = SystemClock.uptimeMillis()
        val bucket = buckets.getOrPut(key) {
            if (buckets.size >= MAX_PENDING_FRAMES) evictStaleBuckets(now, forceOne = true)
            FrameBucket(frameSeq, mediaType, flags and FLAG_PARITY.inv(), dataShards, parityShards, now)
        }
        if (!bucket.shards.containsKey(shardIdx)) {
            bucket.shards[shardIdx] = buf.copyOfRange(HEADER_BYTES, HEADER_BYTES + payloadLen)
            if (payloadLen > bucket.maxShardLen) bucket.maxShardLen = payloadLen
        }
        if (bucket.shards.size >= bucket.dataShards) {
            buckets.remove(key)
            val recovered = RsGf256.recoverDataShards(bucket.dataShards, bucket.parityShards, bucket.shards, bucket.maxShardLen) ?: return
            val totalLen = recovered.sumOf { it.size }
            val framed = ByteArray(totalLen)
            var pos = 0
            for (s in recovered) { System.arraycopy(s, 0, framed, pos, s.size); pos += s.size }
            val cipherBytes = extractCipherFromFramed(framed) ?: return
            val plain = decryptFrame(aesKey, sessionId, frameSeq, mediaType, cipherBytes) ?: return
            val (tsUs, mediaFlags, raw) = extractPlaintextFrame(plain) ?: return
            if (mediaType == MEDIA_VIDEO) {
                lastDeliveredVideoSeq = frameSeq
                onVideoFrame(raw, raw.size, mediaFlags, tsUs)
            } else {
                onAudioFrame(raw, tsUs)
            }
        }
    }

    private fun evictStaleBuckets(now: Long, forceOne: Boolean = false) {
        val it = buckets.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.createdMs >= FRAME_STALE_MS || forceOne) {
                it.remove()
                if (forceOne) break
            }
        }
    }
}
