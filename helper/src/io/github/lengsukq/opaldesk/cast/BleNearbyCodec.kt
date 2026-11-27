package io.github.lengsukq.opaldesk.cast

import io.github.lengsukq.opaldesk.notifications.OpalNotificationJsonCodec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure JVM-testable BLE Nearby advertisement payload codec matching `BleNearbyProtocol.java`
 * and Swift `BleNearbyProtocol.swift` byte-for-byte.
 */
object BleNearbyAdvertisementBuilder {
    // Valid RFC 4122 128-bit (8-4-4-4-12) BLE UUIDs encoding ASCII "opaldesk" (6F70-616C-6465-736B) + 00000001
    const val SERVICE_UUID_STRING = "7A4F0001-6F70-616C-6465-736B00000001"
    const val CHAR_CHALLENGE_UUID_STRING = "7A4F0002-6F70-616C-6465-736B00000001"
    const val CHAR_COMMAND_UUID_STRING = "7A4F0003-6F70-616C-6465-736B00000001"
    const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805F9B34FB"

    const val MANUFACTURER_ID = 0x504F
    const val PROTOCOL_VERSION = 1
    const val MANUFACTURER_DATA_LENGTH = 12
    const val FP_PREFIX_BYTES = 8
    const val NONCE_BYTE_LENGTH = 16
    const val UNKNOWN_BATTERY_BYTE = 0xFF

    const val CAP_LAN_SHARE = 0x01
    const val CAP_HOTSPOT_ACTIVE = 0x02
    const val CAP_HOTSPOT_TRIGGER = 0x04

    val SERVICE_UUID: UUID = UUID.fromString(SERVICE_UUID_STRING)
    val CHAR_CHALLENGE_UUID: UUID = UUID.fromString(CHAR_CHALLENGE_UUID_STRING)
    val CHAR_COMMAND_UUID: UUID = UUID.fromString(CHAR_COMMAND_UUID_STRING)
    val CCCD_UUID: UUID = UUID.fromString(CCCD_UUID_STRING)

    data class DecodedAdvertisement(
        val version: Int,
        val capFlags: Int,
        val certFingerprintPrefixHex: String,
        val wifiPort: Int,
        val batteryPct: Int, // 0..100 or -1 when unknown (0xFF)
    ) {
        val isLanShareActive: Boolean get() = (capFlags and CAP_LAN_SHARE) != 0
        val isHotspotActive: Boolean get() = (capFlags and CAP_HOTSPOT_ACTIVE) != 0
        val supportsHotspotTrigger: Boolean get() = (capFlags and CAP_HOTSPOT_TRIGGER) != 0
    }

    fun computeCapFlags(
        lanShareActive: Boolean,
        hotspotActive: Boolean,
        hotspotTriggerSupported: Boolean,
    ): Int {
        var flags = 0
        if (lanShareActive) flags = flags or CAP_LAN_SHARE
        if (hotspotActive) flags = flags or CAP_HOTSPOT_ACTIVE
        if (hotspotTriggerSupported) flags = flags or CAP_HOTSPOT_TRIGGER
        return flags and 0x0F
    }

    /**
     * Encodes the exact 12-byte Manufacturer Specific Data payload (Android prepends the 2-byte
     * little-endian company ID `0x504F` automatically when calling `addManufacturerData`):
     * - byte 0: `((PROTOCOL_VERSION & 0x0F) << 4) | (capFlags & 0x0F)`
     * - bytes 1..8: first 8 raw bytes of normalized cert SHA-256 hex fingerprint (zero-padded)
     * - bytes 9..10: `wifiPort` Big-Endian uint16
     * - byte 11: `batteryPct` (`0..100`, else `0xFF`)
     */
    fun encodeManufacturerData(
        capFlags: Int,
        certFingerprintHex: String,
        wifiPort: Int,
        batteryPct: Int = -1,
    ): ByteArray {
        val out = ByteArray(MANUFACTURER_DATA_LENGTH)
        out[0] = (((PROTOCOL_VERSION and 0x0F) shl 4) or (capFlags and 0x0F)).toByte()

        val fpPrefix = parseHexPrefixBytes(certFingerprintHex, FP_PREFIX_BYTES)
        System.arraycopy(fpPrefix, 0, out, 1, FP_PREFIX_BYTES)

        val clampedPort = wifiPort.coerceIn(0, 0xFFFF)
        out[9] = ((clampedPort ushr 8) and 0xFF).toByte()
        out[10] = (clampedPort and 0xFF).toByte()
        out[11] = if (batteryPct in 0..100) batteryPct.toByte() else UNKNOWN_BATTERY_BYTE.toByte()
        return out
    }

    fun decodeManufacturerData(data: ByteArray?): DecodedAdvertisement? {
        if (data == null || data.size < MANUFACTURER_DATA_LENGTH) return null
        val header = data[0].toInt() and 0xFF
        val version = (header ushr 4) and 0x0F
        val capFlags = header and 0x0F
        val fpBytes = data.copyOfRange(1, 1 + FP_PREFIX_BYTES)
        val wifiPort = ((data[9].toInt() and 0xFF) shl 8) or (data[10].toInt() and 0xFF)
        val rawBattery = data[11].toInt() and 0xFF
        val batteryPct = if (rawBattery <= 100) rawBattery else -1
        return DecodedAdvertisement(
            version = version,
            capFlags = capFlags,
            certFingerprintPrefixHex = bytesToHexLower(fpBytes),
            wifiPort = wifiPort,
            batteryPct = batteryPct,
        )
    }

    fun bytesToHexLower(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }

    private fun parseHexPrefixBytes(rawHex: String?, numBytes: Int): ByteArray {
        val out = ByteArray(numBytes)
        if (rawHex.isNullOrEmpty()) return out
        val cleaned = StringBuilder(numBytes * 2)
        for (i in rawHex.indices) {
            if (cleaned.length >= numBytes * 2) break
            val c = rawHex[i]
            if (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') {
                cleaned.append(c)
            }
        }
        val pairCount = cleaned.length / 2
        for (i in 0 until pairCount) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            if (hi >= 0 && lo >= 0) {
                out[i] = ((hi shl 4) or lo).toByte()
            }
        }
        return out
    }
}

/**
 * Pure JVM-testable single-use per-device challenge nonce store with strict TTL expiration
 * and constant-time verification to prevent GATT hotspot command replay attacks.
 */
class BleChallengeNonceStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val randomFill: (ByteArray) -> Unit = { buf -> SecureRandom().nextBytes(buf) },
) {
    companion object {
        const val DEFAULT_TTL_MS = 30_000L
        private const val NONCE_HEX_LEN = BleNearbyAdvertisementBuilder.NONCE_BYTE_LENGTH * 2
    }

    private data class NonceEntry(
        val nonceHexLower: String,
        val expiresAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, NonceEntry>()

    fun issueNonce(deviceKey: String): ByteArray {
        val now = clockMs()
        pruneExpired(now)
        val raw = ByteArray(BleNearbyAdvertisementBuilder.NONCE_BYTE_LENGTH)
        randomFill(raw)
        val cleanKey = deviceKey.trim()
        if (cleanKey.isNotEmpty()) {
            entries[cleanKey] = NonceEntry(
                nonceHexLower = BleNearbyAdvertisementBuilder.bytesToHexLower(raw),
                expiresAtMs = now + ttlMs.coerceAtLeast(1_000L),
            )
        }
        return raw
    }

    /**
     * Atomically removes and validates the issued nonce for [deviceKey].
     * Any verification attempt consumes the issued nonce (single-use).
     */
    fun consumeAndVerify(deviceKey: String, providedNonceHex: String?): Boolean {
        val cleanKey = deviceKey.trim()
        if (cleanKey.isEmpty() || providedNonceHex.isNullOrBlank()) return false
        val entry = entries.remove(cleanKey) ?: return false
        if (clockMs() > entry.expiresAtMs) return false
        val provided = providedNonceHex.trim().lowercase(Locale.ROOT)
        if (provided.length != NONCE_HEX_LEN || entry.nonceHexLower.length != NONCE_HEX_LEN) {
            return false
        }
        return MessageDigest.isEqual(
            entry.nonceHexLower.toByteArray(StandardCharsets.UTF_8),
            provided.toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun clearDevice(deviceKey: String) {
        val cleanKey = deviceKey.trim()
        if (cleanKey.isNotEmpty()) {
            entries.remove(cleanKey)
        }
    }

    fun clearAll() {
        entries.clear()
    }

    fun activeCount(): Int {
        pruneExpired(clockMs())
        return entries.size
    }

    private fun pruneExpired(nowMs: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (nowMs > it.next().value.expiresAtMs) {
                it.remove()
            }
        }
    }
}

data class BleHotspotCommand(
    val op: String,
    val clientId: String,
    val nonceHex: String,
    val hmacHex: String,
)

/**
 * Pure JVM-testable JSON parser & response compacting helper for BLE GATT command payloads.
 */
object BleHotspotCommandParser {
    const val OP_TRIGGER_HOTSPOT = "trigger_hotspot"
    const val STATUS_NEEDS_USER_TOGGLE = "needs_user_toggle"
    const val STATUS_UNAUTHORIZED = "unauthorized"
    const val STATUS_ERROR = "error"

    private const val MAX_COMMAND_BYTES = 2048
    private val strFieldRegex = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val intFieldRegex = Regex(""""([^"\\]+)"\s*:\s*(\d+)""")

    fun parseCommand(rawUtf8: ByteArray?): BleHotspotCommand? {
        if (rawUtf8 == null || rawUtf8.size < 2 || rawUtf8.size > MAX_COMMAND_BYTES) return null
        val text = rawUtf8.toString(StandardCharsets.UTF_8).trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val fields = extractStrings(text)
        val op = fields["op"].orEmpty().trim().lowercase(Locale.ROOT)
        val clientId = fields["clientId"].orEmpty().trim()
        val nonceHex = fields["nonceHex"].orEmpty().trim().lowercase(Locale.ROOT)
        val hmacHex = fields["hmacHex"].orEmpty().trim().lowercase(Locale.ROOT)
        if (op != OP_TRIGGER_HOTSPOT || clientId.isEmpty() || nonceHex.isEmpty() || hmacHex.isEmpty()) {
            return null
        }
        return BleHotspotCommand(op = op, clientId = clientId, nonceHex = nonceHex, hmacHex = hmacHex)
    }

    fun extractResponseStatus(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return extractStrings(json)["status"].orEmpty().trim().lowercase(Locale.ROOT)
    }

    fun buildResponseJson(
        status: String,
        ssid: String = "",
        gatewayIp: String = "",
        port: Int = 0,
        message: String = "",
    ): String {
        val sb = StringBuilder(140)
        sb.append("{\"status\":")
        OpalNotificationJsonCodec.appendJsonString(sb, status)
        sb.append(",\"ssid\":")
        OpalNotificationJsonCodec.appendJsonString(sb, ssid)
        sb.append(",\"gatewayIp\":")
        OpalNotificationJsonCodec.appendJsonString(sb, gatewayIp)
        sb.append(",\"port\":").append(port.coerceAtLeast(0))
        sb.append(",\"message\":")
        OpalNotificationJsonCodec.appendJsonString(sb, message)
        sb.append('}')
        return sb.toString()
    }

    /**
     * Ensures a GATT notification payload fits inside `maxPayloadBytes` (`mtu - 3`) while preserving
     * valid JSON whenever possible (stripping verbose `message` text if needed).
     */
    fun fitResponseForNotification(fullJson: String, maxPayloadBytes: Int): ByteArray {
        val fullBytes = fullJson.toByteArray(StandardCharsets.UTF_8)
        if (maxPayloadBytes <= 0 || fullBytes.size <= maxPayloadBytes) {
            return fullBytes
        }
        val fields = extractStrings(fullJson)
        val port = intFieldRegex.findAll(fullJson)
            .firstOrNull { it.groupValues[1] == "port" }
            ?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val compact = buildResponseJson(
            status = fields["status"].orEmpty().ifEmpty { STATUS_ERROR },
            ssid = fields["ssid"].orEmpty(),
            gatewayIp = fields["gatewayIp"].orEmpty(),
            port = port,
            message = "",
        ).toByteArray(StandardCharsets.UTF_8)
        if (compact.size <= maxPayloadBytes) {
            return compact
        }
        val minimal = buildResponseJson(
            status = fields["status"].orEmpty().ifEmpty { STATUS_ERROR },
            ssid = "",
            gatewayIp = fields["gatewayIp"].orEmpty(),
            port = port,
            message = "",
        ).toByteArray(StandardCharsets.UTF_8)
        return if (minimal.size <= maxPayloadBytes) minimal else fullBytes.copyOf(maxPayloadBytes)
    }

    private fun extractStrings(json: String): Map<String, String> {
        val map = HashMap<String, String>()
        strFieldRegex.findAll(json).forEach { m ->
            map[m.groupValues[1]] = unescape(m.groupValues[2])
        }
        return map
    }

    private fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '"', '\\', '/' -> sb.append(next)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
