package io.github.lengsukq.opaldesk.cast

import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.protocol.AppProfileKey
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
 * Wire framing codec & JSON control-message dispatcher for [OpalCastPeerClient].
 */
internal class OpalCastPeerFrameCodec(
    private val tag: String = "OpalPeerClient",
) {
    companion object {
        const val HEADER_SIZE = 18
        const val MAX_PAYLOAD_LEN = 16 * 1024 * 1024
        const val TYPE_VIDEO_FRAME = 0x02
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
        if (len == 0) {
            return OpalCastRxFrame(type, flags, tsUs, ByteArray(0), 0)
        }
        if (type == TYPE_VIDEO_FRAME) {
            if (rxVideoScratch.size < len) {
                val nextSize = maxOf(len, rxVideoScratch.size * 2).coerceAtMost(MAX_PAYLOAD_LEN)
                rxVideoScratch = ByteArray(nextSize)
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

    fun encodeSystemAction(action: Int, arg: Int): ByteArray =
        ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put((action and 0xFF).toByte())
            .putInt(arg)
            .array()

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    fun dispatchAuthResult(
        payload: ByteArray,
        host: String,
        sessionRole: String,
        peerFp: String,
        listener: OpalCastPeerClient.Listener,
        onUdpNegotiated: (port: Int, sessionId: Int, keyBase64: String, fecPct: Int) -> Unit = { _, _, _, _ -> },
        onRejected: () -> Unit,
    ) {
        if (payload.isEmpty()) return
        val ok = payload[0].toInt() != 0
        val jsonText = if (payload.size > 1) {
            String(payload, 1, payload.size - 1, StandardCharsets.UTF_8)
        } else {
            "{}"
        }
        val obj = try { JSONObject(jsonText) } catch (_: Throwable) { JSONObject() }
        if (ok) {
            val peerName = obj.optString("deviceName", host)
            val token = obj.optString("authToken", "")
            val permanent = obj.optBoolean("permanent", token.isNotEmpty())
            val fp = obj.optString("certFingerprint", peerFp)
            OpalLogStore.i(tag, "Authenticated role=$sessionRole peer=$peerName permanent=$permanent")
            val udpPort = obj.optInt("udpMediaPort", 0)
            val udpSid = obj.optInt("udpSessionId", 0)
            val udpKey = obj.optString("udpKeyBase64", "")
            val udpFec = obj.optInt("udpFecPct", OpalCastUdpReceiver.DEFAULT_FEC_PCT)
            if (udpPort > 0 && udpKey.isNotBlank()) {
                onUdpNegotiated(udpPort, udpSid, udpKey, udpFec)
            }
            listener.onAuthenticated(peerName, token, permanent, fp)
        } else {
            val err = obj.optString("message", "对方拒绝了连接请求")
            OpalLogStore.w(tag, "Authentication rejected role=$sessionRole: $err")
            listener.onError(err)
            onRejected()
        }
    }

    fun dispatchDeviceState(payload: ByteArray, listener: OpalCastPeerClient.Listener) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            if (obj.has("blackout")) {
                listener.onRemoteBlackoutChanged(obj.optBoolean("blackout", false))
            }
            if (obj.has("fullscreenWindowTitle")) {
                listener.onRemoteFullscreenChanged(obj.optString("fullscreenWindowTitle", "").trim())
            }
            if (obj.has("displayMode")) {
                val mode = obj.optInt("displayMode", OpalCastPeerClient.DISPLAY_MODE_DEX)
                if (mode == OpalCastPeerClient.DISPLAY_MODE_DEX || mode == OpalCastPeerClient.DISPLAY_MODE_MIRROR) {
                    listener.onDisplayModeChanged(mode)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun dispatchAppList(payload: ByteArray, listener: OpalCastPeerClient.Listener) {
        if (payload.isEmpty()) return
        try {
            val root = JSONObject(String(payload, StandardCharsets.UTF_8))
            val arr = root.optJSONArray("apps")
            val out = ArrayList<OpalCastPeerClient.RemoteAppEntry>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val pkg = o.optString("pkg", "").trim()
                    if (pkg.isEmpty()) continue
                    val uid = o.optInt("userId", 0).coerceAtLeast(0)
                    out.add(
                        OpalCastPeerClient.RemoteAppEntry(
                            pkg = pkg,
                            cls = o.optString("cls", ""),
                            label = o.optString("label", pkg).ifBlank { pkg },
                            openCount = o.optInt("openCount", 0).coerceAtLeast(0),
                            userId = uid,
                            profileType = AppProfileKey.normalizeProfileType(
                                o.optString("profileType", ""),
                                uid,
                            ),
                        ),
                    )
                }
            }
            listener.onAppListReceived(out)
        } catch (t: Throwable) {
            OpalLogStore.w(tag, "Failed to parse APP_LIST", t)
        }
    }

    fun dispatchImeRequest(payload: ByteArray, listener: OpalCastPeerClient.Listener) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            val show = obj.optBoolean("show", true)
            val inputType = obj.optInt("inputType", 0)
            OpalLogStore.i(tag, "RX IME_REQUEST show=$show inputType=0x${inputType.toString(16)}")
            listener.onRemoteImeRequest(show, inputType)
        } catch (t: Throwable) {
            OpalLogStore.w(tag, "Failed to parse IME_REQUEST", t)
        }
    }

    fun dispatchMicState(payload: ByteArray, listener: OpalCastPeerClient.Listener) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            val active = obj.optBoolean("active", false)
            val supported = obj.optBoolean("supported", false)
            val message = obj.optString("message", "")
            OpalLogStore.i(tag, "RX MIC_STATE active=$active supported=$supported msg=$message")
            listener.onRemoteMicState(active, supported, message)
        } catch (t: Throwable) {
            OpalLogStore.w(tag, "Failed to parse MIC_STATE", t)
        }
    }

    fun dispatchCameraState(payload: ByteArray, listener: OpalCastPeerClient.Listener) {
        if (payload.isEmpty()) return
        try {
            val obj = JSONObject(String(payload, StandardCharsets.UTF_8))
            val active = obj.optBoolean("active", false)
            val supported = obj.optBoolean("supported", false)
            val rootRequired = obj.optBoolean("rootRequired", true)
            val message = obj.optString("message", "")
            val facing = obj.optString("facing", "")
            OpalLogStore.i(tag, "RX CAMERA_STATE active=$active supported=$supported rootReq=$rootRequired facing=$facing msg=$message")
            listener.onRemoteCameraState(active, supported, rootRequired, message, facing)
        } catch (t: Throwable) {
            OpalLogStore.w(tag, "Failed to parse CAMERA_STATE", t)
        }
    }
}
