package io.github.lengsukq.opaldesk.cast

import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import java.util.Locale

/**
 * Pure-JVM policy, health-response parsing, and UI state resolution for Beta opt-in
 * USB Tethering (RNDIS / CDC-NCM) screen casting.
 */
object UsbTetherGatewayPolicy {
    const val DEFAULT_CAST_PORT = 19878
    const val BETA_DISCLAIMER = "Beta 测试功能：受各系统 USB 网卡驱动与机型策略差异影响，不保证所有设备均可正常使用。"

    data class UsbTetherCandidate(
        val interfaceName: String,
        val gatewayIp: String,
        val localIp: String = "",
    )

    data class CastHealthInfo(
        val deviceName: String,
        val certShortFp: String,
        val protocolVersion: Int,
    )

    data class DiscoveredUsbTetherPeer(
        val displayName: String,
        val host: String,
        val port: Int,
        val interfaceName: String,
        val shortFingerprint: String = "",
        val fullFingerprint: String = "",
    )

    enum class SenderShareState {
        FEATURE_DISABLED,
        NO_USB_INTERFACE,
        USB_READY_IDLE,
        USB_ONLY_ACTIVE,
        REUSING_LAN_SHARE,
    }

    private val STR_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val BOOL_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*(true|false)""")
    private val INT_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*(-?\d+)""")

    /**
     * Validates that a receiver-side route is a plausible private IPv4 USB tethering gateway
     * (excluding loopback, link-local, VPN tunnels, Wi-Fi, and cellular interfaces).
     */
    fun isLikelyUsbTetherRoute(interfaceName: String?, gatewayIp: String?): Boolean {
        val gw = gatewayIp?.trim().orEmpty()
        if (!isPrivateIpv4(gw)) return false
        val iface = interfaceName?.trim()?.lowercase(Locale.US).orEmpty()
        if (iface.isEmpty()) return true
        if (iface.startsWith("wlan") || iface.startsWith("swlan") || iface.startsWith("ap") ||
            iface.startsWith("rmnet") || iface.startsWith("ccmni") ||
            iface.startsWith("tun") || iface.startsWith("tap") || iface.startsWith("ppp") ||
            iface.startsWith("lo") || iface.startsWith("dummy")
        ) {
            return false
        }
        return iface.startsWith("eth") || iface.startsWith("usb") ||
            iface.startsWith("ncm") || iface.startsWith("rndis") || iface.startsWith("en")
    }

    fun isPrivateIpv4(raw: String?): Boolean {
        val parts = raw?.trim()?.split('.') ?: return false
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return false
            if (v !in 0..255) return false
            octets[i] = v
        }
        if (octets[0] == 10) return true
        if (octets[0] == 172 && octets[1] in 16..31) return true
        if (octets[0] == 192 && octets[1] == 168) return true
        return false
    }

    /**
     * Parses `GET /health` JSON returned by OpalDesk `RemoteStreamServer` (:19878).
     * Returns null unless `ok == true` and `role == "cast"`.
     */
    fun parseCastHealthJson(rawJson: String?): CastHealthInfo? {
        val body = rawJson?.trim().orEmpty()
        if (!body.startsWith("{") || !body.endsWith("}")) return null
        val bools = HashMap<String, Boolean>()
        BOOL_FIELD_REGEX.findAll(body).forEach { m ->
            bools[m.groupValues[1]] = (m.groupValues[2] == "true")
        }
        if (bools["ok"] != true) return null

        val strs = HashMap<String, String>()
        STR_FIELD_REGEX.findAll(body).forEach { m ->
            strs[m.groupValues[1]] = unescapeJsonString(m.groupValues[2])
        }
        if (strs["role"] != "cast") return null

        val ints = HashMap<String, Int>()
        INT_FIELD_REGEX.findAll(body).forEach { m ->
            m.groupValues[2].toIntOrNull()?.let { ints[m.groupValues[1]] = it }
        }
        val devName = strs["deviceName"].orEmpty().trim().ifEmpty { "Android 设备" }
        val shortFp = strs["certShortFp"].orEmpty().trim()
        val protoVer = ints["protocolVersion"] ?: 1
        return CastHealthInfo(deviceName = devName, certShortFp = shortFp, protocolVersion = protoVer)
    }

    fun formatShortFingerprint(hex: String?): String {
        val clean = hex.orEmpty().replace(":", "").trim().uppercase(Locale.US)
        if (clean.length < 12) return clean
        return "${clean.substring(0, 4)}-${clean.substring(4, 8)}-${clean.substring(8, 12)}"
    }

    fun resolveSenderShareState(
        remote: RemoteStreamStatus,
        featureEnabled: Boolean,
    ): SenderShareState {
        if (!featureEnabled) return SenderShareState.FEATURE_DISABLED
        if (!remote.hasUsbTetherInterface) return SenderShareState.NO_USB_INTERFACE
        return when {
            remote.wifiEnabled && remote.tlsBindScope == BridgeProtocol.TlsBindScope.USB_TETHER_ONLY ->
                SenderShareState.USB_ONLY_ACTIVE
            remote.wifiEnabled ->
                SenderShareState.REUSING_LAN_SHARE
            else ->
                SenderShareState.USB_READY_IDLE
        }
    }

    private fun unescapeJsonString(raw: String): String {
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
