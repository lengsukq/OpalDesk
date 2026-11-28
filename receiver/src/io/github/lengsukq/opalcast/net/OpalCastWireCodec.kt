package io.github.lengsukq.opalcast.net

import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class OpalCastRxFrame(
    val type: Int,
    val flags: Int,
    val timestampUs: Long,
    val payload: ByteArray,
    val length: Int = payload.size,
)

/**
 * Binary frame framing, payload codec, and downlink JSON message router for [OpalCastClient].
 */
internal class OpalCastWireCodec {
    companion object {
        const val HEADER_SIZE = 18
        const val MAX_PAYLOAD_LEN = 16 * 1024 * 1024

        const val TYPE_VIDEO_CONFIG = 0x01
        const val TYPE_VIDEO_FRAME = 0x02
        const val TYPE_AUDIO_CONFIG = 0x03
        const val TYPE_AUDIO_FRAME = 0x04
        const val TYPE_DEVICE_STATE = 0x10
        const val TYPE_AUTH_RESULT = 0x12
        const val TYPE_APP_LIST = 0x15
        const val TYPE_IME_REQUEST = 0x16
        const val TYPE_MIC_STATE = 0x17
        const val TYPE_CAMERA_STATE = 0x18
        const val TYPE_PONG = 0x1F

        const val TYPE_HELLO = 0x20
        const val TYPE_RESIZE_CONFIG = 0x21
        const val TYPE_REQUEST_IDR = 0x22
        const val TYPE_INPUT_MOUSE = 0x23
        const val TYPE_INPUT_TOUCH = 0x24
        const val TYPE_INPUT_KEY = 0x25
        const val TYPE_INPUT_TEXT = 0x26
        const val TYPE_SYSTEM_ACTION = 0x27
        const val TYPE_LIST_APPS = 0x2A
        const val TYPE_MIC_CONFIG = 0x2C
        const val TYPE_MIC_FRAME = 0x2D
        const val TYPE_CAMERA_CONFIG = 0x2E
        const val TYPE_PING = 0x2F
        const val TYPE_CAMERA_FRAME = 0x30

        fun encodeSystemAction(action: Int, arg: Int): ByteArray =
            ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
                .put((action and 0xFF).toByte())
                .putInt(arg)
                .array()

        fun encodeKeyEvent(action: Int, keyCode: Int, metaState: Int, repeat: Int): ByteArray =
            ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
                .put((action and 0xFF).toByte())
                .putInt(keyCode)
                .putInt(metaState)
                .putInt(repeat)
                .array()

        fun encodeTextEvent(text: String, seq: Long, commitPaste: Boolean): ByteArray {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            return ByteBuffer.allocate(9 + bytes.size).order(ByteOrder.BIG_ENDIAN)
                .put(if (commitPaste) 0.toByte() else 1.toByte())
                .putLong(seq)
                .put(bytes)
                .array()
        }

        fun encodeResizeConfig(width: Int, height: Int, wireDpi: Int, fps: Int, bitrateKbps: Int): ByteArray =
            ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
                .putShort(width.toShort())
                .putShort(height.toShort())
                .putShort(wireDpi.toShort())
                .putShort((fps.coerceIn(30, 120) * 100).toShort())
                .putInt(bitrateKbps)
                .put(0xFF.toByte())
                .put(0xFF.toByte())
                .array()

        fun encodeMouseEvent(
            mouseAction: Int,
            rx: Float,
            ry: Float,
            normButtons: Int,
            actBtn: Int,
            hScroll: Float,
            vScroll: Float,
        ): ByteArray = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
            .put((mouseAction and 0xFF).toByte())
            .putFloat(rx)
            .putFloat(ry)
            .putInt(normButtons)
            .putInt(actBtn)
            .putFloat(hScroll)
            .putFloat(vScroll)
            .array()

        fun writePingId(dst: ByteArray, id: Long) {
            dst[0] = ((id ushr 56) and 0xFF).toByte()
            dst[1] = ((id ushr 48) and 0xFF).toByte()
            dst[2] = ((id ushr 40) and 0xFF).toByte()
            dst[3] = ((id ushr 32) and 0xFF).toByte()
            dst[4] = ((id ushr 24) and 0xFF).toByte()
            dst[5] = ((id ushr 16) and 0xFF).toByte()
            dst[6] = ((id ushr 8) and 0xFF).toByte()
            dst[7] = (id and 0xFF).toByte()
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append("0123456789abcdef"[v ushr 4])
                sb.append("0123456789abcdef"[v and 0x0F])
            }
            return sb.toString()
        }

        fun routeDeviceState(payload: ByteArray, listener: OpalCastClient.Listener) {
            if (payload.isEmpty()) return
            try {
                val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
                if (obj.has("blackout")) listener.onRemoteBlackoutChanged(obj.optBoolean("blackout", false))
                if (obj.has("fullscreenWindowTitle")) {
                    listener.onRemoteFullscreenChanged(obj.optString("fullscreenWindowTitle", "").trim())
                }
                if (obj.has("displayMode")) {
                    val mode = obj.optInt("displayMode", OpalCastClient.DISPLAY_MODE_DEX)
                    if (mode == OpalCastClient.DISPLAY_MODE_DEX || mode == OpalCastClient.DISPLAY_MODE_MIRROR) {
                        listener.onDisplayModeChanged(mode)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        fun routeAppList(payload: ByteArray, listener: OpalCastClient.Listener) {
            if (payload.isEmpty()) return
            try {
                val root = JSONObject(String(payload, StandardCharsets.UTF_8))
                val arr = root.optJSONArray("apps")
                val out = ArrayList<OpalCastClient.RemoteAppEntry>(arr?.length() ?: 0)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val pkg = o.optString("pkg", "").trim()
                        if (pkg.isEmpty()) continue
                        val userId = o.optInt("userId", 0).coerceAtLeast(0)
                        val profileType = ReceiverPolicy.normalizeProfileType(o.optString("profileType", ""), userId)
                        out.add(
                            OpalCastClient.RemoteAppEntry(
                                pkg = pkg,
                                cls = o.optString("cls", ""),
                                label = o.optString("label", pkg).ifBlank { pkg },
                                openCount = o.optInt("openCount", 0).coerceAtLeast(0),
                                userId = userId,
                                profileType = profileType,
                            ),
                        )
                    }
                }
                listener.onAppListReceived(out)
            } catch (_: Throwable) {
            }
        }

        fun routeImeRequest(payload: ByteArray, listener: OpalCastClient.Listener) {
            if (payload.isEmpty()) return
            try {
                val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
                listener.onRemoteImeRequest(obj.optBoolean("show", true), obj.optInt("inputType", 0))
            } catch (_: Throwable) {
            }
        }

        fun routeMicState(payload: ByteArray, listener: OpalCastClient.Listener) {
            if (payload.isEmpty()) return
            try {
                val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
                listener.onRemoteMicState(
                    obj.optBoolean("active", false),
                    obj.optBoolean("supported", false),
                    obj.optString("message", ""),
                )
            } catch (_: Throwable) {
            }
        }

        fun routeCameraState(payload: ByteArray, listener: OpalCastClient.Listener) {
            if (payload.isEmpty()) return
            try {
                val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
                listener.onRemoteCameraState(
                    obj.optBoolean("active", false),
                    obj.optBoolean("supported", false),
                    obj.optBoolean("rootRequired", true),
                    obj.optString("message", ""),
                    obj.optString("facing", ""),
                )
            } catch (_: Throwable) {
            }
        }
    }

    private val txHeaderBuf = ByteArray(HEADER_SIZE)
    private val rxHeaderBuf = ByteArray(HEADER_SIZE)
    private var rxVideoScratch = ByteArray(64 * 1024)

    fun writeFrame(out: OutputStream, type: Int, flags: Int, tsUs: Long, payload: ByteArray) {
        val hdr = txHeaderBuf
        hdr[0] = 0x4F
        hdr[1] = 0x50
        hdr[2] = 0x44
        hdr[3] = 0x31
        hdr[4] = (type and 0xFF).toByte()
        hdr[5] = (flags and 0xFF).toByte()
        val len = payload.size
        hdr[6] = ((len ushr 24) and 0xFF).toByte()
        hdr[7] = ((len ushr 16) and 0xFF).toByte()
        hdr[8] = ((len ushr 8) and 0xFF).toByte()
        hdr[9] = (len and 0xFF).toByte()
        hdr[10] = ((tsUs ushr 56) and 0xFF).toByte()
        hdr[11] = ((tsUs ushr 48) and 0xFF).toByte()
        hdr[12] = ((tsUs ushr 40) and 0xFF).toByte()
        hdr[13] = ((tsUs ushr 32) and 0xFF).toByte()
        hdr[14] = ((tsUs ushr 24) and 0xFF).toByte()
        hdr[15] = ((tsUs ushr 16) and 0xFF).toByte()
        hdr[16] = ((tsUs ushr 8) and 0xFF).toByte()
        hdr[17] = (tsUs and 0xFF).toByte()
        out.write(hdr)
        if (len > 0) out.write(payload)
    }

    fun readFrame(input: InputStream): OpalCastRxFrame? {
        val hdr = rxHeaderBuf
        val first = input.read()
        if (first < 0) return null
        hdr[0] = first.toByte()
        readFully(input, hdr, 1, HEADER_SIZE - 1)
        if (hdr[0] != 0x4F.toByte() || hdr[1] != 0x50.toByte() ||
            hdr[2] != 0x44.toByte() || hdr[3] != 0x31.toByte()
        ) {
            throw IOException("Invalid OpalCast frame magic")
        }
        val type = hdr[4].toInt() and 0xFF
        val flags = hdr[5].toInt() and 0xFF
        val len = ((hdr[6].toInt() and 0xFF) shl 24) or
            ((hdr[7].toInt() and 0xFF) shl 16) or
            ((hdr[8].toInt() and 0xFF) shl 8) or
            (hdr[9].toInt() and 0xFF)
        if (len < 0 || len > MAX_PAYLOAD_LEN) {
            throw IOException("Invalid OpalCast payload length: $len")
        }
        val tsUs = ((hdr[10].toLong() and 0xFF) shl 56) or
            ((hdr[11].toLong() and 0xFF) shl 48) or
            ((hdr[12].toLong() and 0xFF) shl 40) or
            ((hdr[13].toLong() and 0xFF) shl 32) or
            ((hdr[14].toLong() and 0xFF) shl 24) or
            ((hdr[15].toLong() and 0xFF) shl 16) or
            ((hdr[16].toLong() and 0xFF) shl 8) or
            (hdr[17].toLong() and 0xFF)
        if (len == 0) return OpalCastRxFrame(type, flags, tsUs, ByteArray(0), 0)
        if (type == TYPE_VIDEO_FRAME) {
            if (rxVideoScratch.size < len) {
                rxVideoScratch = ByteArray(maxOf(len, rxVideoScratch.size * 2).coerceAtMost(MAX_PAYLOAD_LEN))
            }
            readFully(input, rxVideoScratch, 0, len)
            return OpalCastRxFrame(type, flags, tsUs, rxVideoScratch, len)
        }
        val payload = ByteArray(len)
        readFully(input, payload, 0, len)
        return OpalCastRxFrame(type, flags, tsUs, payload, len)
    }

    private fun readFully(input: InputStream, dst: ByteArray, off: Int, len: Int) {
        var pos = off
        var rem = len
        while (rem > 0) {
            val n = input.read(dst, pos, rem)
            if (n < 0) throw EOFException("Unexpected EOF in OpalCast stream")
            pos += n
            rem -= n
        }
    }
}
