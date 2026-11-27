package io.github.lengsukq.opaldesk.cast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelUuid
import android.provider.Settings
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.notifications.OpalNotificationJsonCodec
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
        val now = clockMs()
        if (now > entry.expiresAtMs) return false
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

    private val strFieldRegex = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val intFieldRegex = Regex(""""([^"\\]+)"\s*:\s*(\d+)""")

    fun parseCommand(rawUtf8: ByteArray?): BleHotspotCommand? {
        if (rawUtf8 == null || rawUtf8.size < 2 || rawUtf8.size > 2048) return null
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

data class BleNearbyRuntimeState(
    val enabled: Boolean = false,
    val supported: Boolean = true,
    val hasPermissions: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val advertising: Boolean = false,
    val gattRunning: Boolean = false,
    val statusMessage: String = "",
)

/**
 * Opt-in BLE Nearby Advertising + Authenticated GATT Instant Hotspot trigger service.
 *
 * Strictly gated behind `AppPrefs.isBleNearbyEnabled(ctx)` and runtime BLE permissions
 * (`BLUETOOTH_ADVERTISE` + `BLUETOOTH_CONNECT` on API 31+), and fails safely on emulators or
 * hardware without BLE peripheral support.
 */
object BleNearbyService {
    private const val TAG = "OpalBleNearby"
    private const val HOTSPOT_NOTIF_CHANNEL_ID = "opaldesk_ble_hotspot"
    private const val HOTSPOT_NOTIF_ID = 3928
    private const val MAX_PREPARED_WRITE_BYTES = 2048
    private const val DEFAULT_MTU = 23

    private val lock = Any()
    private val bleExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalBleNearby").apply { isDaemon = true }
    }

    private val _stateFlow = MutableStateFlow(BleNearbyRuntimeState())
    val stateFlow: StateFlow<BleNearbyRuntimeState> = _stateFlow.asStateFlow()

    private val nonceStore = BleChallengeNonceStore()
    private val mtuByAddress = ConcurrentHashMap<String, Int>()
    private val subscribedAddresses = ConcurrentHashMap.newKeySet<String>()
    private val preparedWritesByAddress = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val lastResponseByAddress = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastAdvertisedPayload: ByteArray? = null

    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeAdvertiseCallback: AdvertiseCallback? = null
    private var gattServer: BluetoothGattServer? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var btReceiverRegistered = false

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val appCtx = context?.applicationContext ?: appContext ?: return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) {
                bleExec.execute { syncEnabledStateLocked(appCtx) }
            } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                bleExec.execute {
                    synchronized(lock) {
                        stopBleHardwareLocked()
                        updateRuntimeStateLocked(appCtx, advertising = false, gattRunning = false, msg = "蓝牙已关闭")
                    }
                }
            }
        }
    }

    fun requiredRuntimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            emptyArray()
        }

    fun hasRequiredPermissions(ctx: Context?): Boolean {
        val c = ctx ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return requiredRuntimePermissions().all { perm ->
            runCatching { c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        }
    }

    fun isBleSupported(ctx: Context?): Boolean {
        val c = ctx?.applicationContext ?: return false
        return runCatching {
            c.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        }.getOrDefault(false)
    }

    fun isBluetoothEnabled(ctx: Context?): Boolean {
        val c = ctx?.applicationContext ?: return false
        return runCatching {
            val bm = c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.isEnabled == true
        }.getOrDefault(false)
    }

    fun refreshState(ctx: Context?) {
        val appCtx = ctx?.applicationContext ?: return
        synchronized(lock) {
            appContext = appCtx
            updateRuntimeStateLocked(
                ctx = appCtx,
                advertising = activeAdvertiseCallback != null,
                gattRunning = gattServer != null,
            )
        }
    }

    fun syncEnabledState(ctx: Context?) {
        val appCtx = ctx?.applicationContext ?: return
        bleExec.execute {
            syncEnabledStateLocked(appCtx)
        }
    }

    fun start(ctx: Context) {
        val appCtx = ctx.applicationContext
        bleExec.execute {
            syncEnabledStateLocked(appCtx)
        }
    }

    fun stop() {
        bleExec.execute {
            synchronized(lock) {
                unregisterBtStateReceiverLocked()
                stopBleHardwareLocked()
                val ctx = appContext
                if (ctx != null) {
                    updateRuntimeStateLocked(ctx, advertising = false, gattRunning = false, msg = "")
                } else {
                    _stateFlow.value = BleNearbyRuntimeState(enabled = false)
                }
            }
        }
    }

    fun onRemoteStatusUpdated(remote: RemoteStreamStatus) {
        val appCtx = appContext ?: return
        if (!AppPrefs.isBleNearbyEnabled(appCtx)) return
        bleExec.execute {
            synchronized(lock) {
                if (!AppPrefs.isBleNearbyEnabled(appCtx)) return@execute
                if (activeAdvertiseCallback == null || gattServer == null) {
                    syncEnabledStateLocked(appCtx)
                    return@execute
                }
                val nextPayload = buildCurrentManufacturerPayload(appCtx, remote)
                val prev = lastAdvertisedPayload
                if (prev == null || !prev.contentEquals(nextPayload)) {
                    restartAdvertisingLocked(appCtx, nextPayload)
                }
            }
        }
    }

    private fun syncEnabledStateLocked(appCtx: Context) {
        synchronized(lock) {
            appContext = appCtx
            val enabled = AppPrefs.isBleNearbyEnabled(appCtx)
            if (!enabled) {
                unregisterBtStateReceiverLocked()
                stopBleHardwareLocked()
                updateRuntimeStateLocked(appCtx, advertising = false, gattRunning = false, msg = "")
                return
            }
            if (!isBleSupported(appCtx)) {
                stopBleHardwareLocked()
                updateRuntimeStateLocked(appCtx, advertising = false, gattRunning = false, msg = "设备不支持低功耗蓝牙")
                return
            }
            if (!hasRequiredPermissions(appCtx)) {
                stopBleHardwareLocked()
                updateRuntimeStateLocked(appCtx, advertising = false, gattRunning = false, msg = "缺少蓝牙广播与连接权限")
                return
            }
            registerBtStateReceiverLocked(appCtx)
            val bm = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter
            if (adapter == null || !adapter.isEnabled) {
                stopBleHardwareLocked()
                updateRuntimeStateLocked(appCtx, advertising = false, gattRunning = false, msg = "请先开启系统蓝牙")
                return
            }
            val leAdv = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull()
            if (leAdv == null) {
                stopBleHardwareLocked()
                updateRuntimeStateLocked(appCtx, advertising = false, gattRunning = false, msg = "硬件不支持 BLE 外设广播")
                return
            }
            if (gattServer == null) {
                startGattServerLocked(appCtx, bm)
            }
            val payload = buildCurrentManufacturerPayload(appCtx, BridgeClient.remoteStatusFlow.value)
            val prev = lastAdvertisedPayload
            if (activeAdvertiseCallback == null || prev == null || !prev.contentEquals(payload)) {
                restartAdvertisingLocked(appCtx, payload)
            } else {
                updateRuntimeStateLocked(
                    appCtx,
                    advertising = activeAdvertiseCallback != null,
                    gattRunning = gattServer != null,
                    msg = "广播中",
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServerLocked(appCtx: Context, bm: BluetoothManager) {
        if (!hasRequiredPermissions(appCtx)) return
        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val addr = runCatching { device.address }.getOrNull().orEmpty()
                if (newState == BluetoothProfile.STATE_DISCONNECTED && addr.isNotEmpty()) {
                    nonceStore.clearDevice(addr)
                    mtuByAddress.remove(addr)
                    subscribedAddresses.remove(addr)
                    preparedWritesByAddress.remove(addr)
                    lastResponseByAddress.remove(addr)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                val addr = runCatching { device.address }.getOrNull().orEmpty()
                if (addr.isNotEmpty()) {
                    mtuByAddress[addr] = mtu.coerceAtLeast(DEFAULT_MTU)
                }
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                val srv = gattServer ?: return
                val addr = runCatching { device.address }.getOrNull().orEmpty()
                val value = if (descriptor.uuid == BleNearbyAdvertisementBuilder.CCCD_UUID) {
                    if (addr in subscribedAddresses) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
                } else {
                    ByteArray(0)
                }
                val slice = if (offset in 0..value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                runCatching { srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice) }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val srv = gattServer ?: return
                val addr = runCatching { device.address }.getOrNull().orEmpty()
                if (descriptor.uuid == BleNearbyAdvertisementBuilder.CCCD_UUID && addr.isNotEmpty()) {
                    val enable = value != null && value.size >= 2 &&
                        (value[0].toInt() != 0 || value[1].toInt() != 0)
                    if (enable) subscribedAddresses.add(addr) else subscribedAddresses.remove(addr)
                }
                if (responseNeeded) {
                    runCatching { srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val srv = gattServer ?: return
                val addr = runCatching { device.address }.getOrNull().orEmpty()
                when (characteristic.uuid) {
                    BleNearbyAdvertisementBuilder.CHAR_CHALLENGE_UUID -> {
                        val payload = if (offset == 0) {
                            nonceStore.issueNonce(addr)
                        } else {
                            ByteArray(0)
                        }
                        runCatching {
                            srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
                        }
                    }
                    BleNearbyAdvertisementBuilder.CHAR_COMMAND_UUID -> {
                        val cached = lastResponseByAddress[addr] ?: ByteArray(0)
                        val slice = if (offset in 0..cached.size) {
                            cached.copyOfRange(offset, cached.size)
                        } else {
                            ByteArray(0)
                        }
                        runCatching {
                            srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                        }
                    }
                    else -> {
                        runCatching {
                            srv.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                        }
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val srv = gattServer ?: return
                if (characteristic.uuid != BleNearbyAdvertisementBuilder.CHAR_COMMAND_UUID) {
                    if (responseNeeded) {
                        runCatching {
                            srv.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                        }
                    }
                    return
                }
                val addr = runCatching { device.address }.getOrNull().orEmpty()
                val chunk = value ?: ByteArray(0)
                if (preparedWrite) {
                    val buf = preparedWritesByAddress.computeIfAbsent(addr) { ByteArrayOutputStream(256) }
                    if (buf.size() + chunk.size <= MAX_PREPARED_WRITE_BYTES) {
                        buf.write(chunk)
                    }
                    if (responseNeeded) {
                        runCatching { srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk) }
                    }
                    return
                }
                if (responseNeeded) {
                    runCatching { srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null) }
                }
                val payloadCopy = chunk.copyOf()
                bleExec.execute { processCommandWrite(appCtx, device, addr, payloadCopy) }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                val srv = gattServer ?: return
                val addr = runCatching { device.address }.getOrNull().orEmpty()
                val assembled = preparedWritesByAddress.remove(addr)?.toByteArray()
                runCatching { srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
                if (execute && assembled != null && assembled.isNotEmpty()) {
                    bleExec.execute { processCommandWrite(appCtx, device, addr, assembled) }
                }
            }
        }

        val server = runCatching { bm.openGattServer(appCtx, callback) }.getOrNull() ?: return
        val service = BluetoothGattService(
            BleNearbyAdvertisementBuilder.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val challengeChar = BluetoothGattCharacteristic(
            BleNearbyAdvertisementBuilder.CHAR_CHALLENGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val commandChar = BluetoothGattCharacteristic(
            BleNearbyAdvertisementBuilder.CHAR_COMMAND_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or
                BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            BleNearbyAdvertisementBuilder.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        commandChar.addDescriptor(cccd)
        service.addCharacteristic(challengeChar)
        service.addCharacteristic(commandChar)

        val added = runCatching { server.addService(service) }.getOrDefault(false)
        if (!added) {
            runCatching { server.close() }
            OpalLogStore.w(TAG, "Failed to register BLE GATT service")
            return
        }
        gattServer = server
        commandCharacteristic = commandChar
        OpalLogStore.i(TAG, "BLE GATT server started (${BleNearbyAdvertisementBuilder.SERVICE_UUID_STRING})")
    }

    @SuppressLint("MissingPermission")
    private fun processCommandWrite(
        appCtx: Context,
        device: BluetoothDevice,
        deviceAddr: String,
        payload: ByteArray,
    ) {
        val cmd = BleHotspotCommandParser.parseCommand(payload)
        val responseJson = if (cmd == null) {
            BleHotspotCommandParser.buildResponseJson(
                status = BleHotspotCommandParser.STATUS_ERROR,
                message = "无效的蓝牙热点指令格式",
            )
        } else if (!nonceStore.consumeAndVerify(deviceAddr, cmd.nonceHex)) {
            OpalLogStore.w(TAG, "Rejected BLE hotspot trigger: invalid or expired nonce ($deviceAddr)")
            BleHotspotCommandParser.buildResponseJson(
                status = BleHotspotCommandParser.STATUS_UNAUTHORIZED,
                message = "挑战随机数无效或已过期，请重试",
            )
        } else {
            val res = BridgeClient.triggerHotspotSync(cmd.clientId, cmd.nonceHex, cmd.hmacHex)
            val status = BleHotspotCommandParser.extractResponseStatus(res)
            OpalLogStore.i(TAG, "BLE hotspot trigger completed for ${cmd.clientId}: status=$status")
            if (status == BleHotspotCommandParser.STATUS_NEEDS_USER_TOGGLE) {
                promptUserHotspotToggle(appCtx)
            }
            res
        }

        val fullBytes = responseJson.toByteArray(StandardCharsets.UTF_8)
        if (deviceAddr.isNotEmpty()) {
            lastResponseByAddress[deviceAddr] = fullBytes
        }

        val srv = synchronized(lock) { gattServer } ?: return
        val cmdChar = synchronized(lock) { commandCharacteristic } ?: return
        if (!hasRequiredPermissions(appCtx)) return

        val negotiatedMtu = mtuByAddress[deviceAddr] ?: DEFAULT_MTU
        val maxNotifyLen = (negotiatedMtu - 3).coerceAtLeast(20)
        val notifyBytes = BleHotspotCommandParser.fitResponseForNotification(responseJson, maxNotifyLen)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                srv.notifyCharacteristicChanged(device, cmdChar, false, notifyBytes)
            } else {
                @Suppress("DEPRECATION")
                cmdChar.value = notifyBytes
                @Suppress("DEPRECATION")
                srv.notifyCharacteristicChanged(device, cmdChar, false)
            }
        }.onFailure { t ->
            OpalLogStore.w(TAG, "BLE GATT notify failed: ${t.message}")
        }
    }

    private fun promptUserHotspotToggle(appCtx: Context) {
        val settingsIntent = buildHotspotSettingsIntent(appCtx)
        postHotspotPromptNotification(appCtx, settingsIntent)
        runCatching { appCtx.startActivity(settingsIntent) }
    }

    private fun buildHotspotSettingsIntent(appCtx: Context): Intent {
        val candidates = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        val pm = appCtx.packageManager
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = runCatching { candidate.resolveActivity(pm) }.getOrNull()
            if (resolved != null) return candidate
        }
        return Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun postHotspotPromptNotification(appCtx: Context, settingsIntent: Intent) {
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val ch = NotificationChannel(
            HOTSPOT_NOTIF_CHANNEL_ID,
            "蓝牙一键个人热点提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "当附近已信任的 Mac 通过蓝牙请求开启个人热点时提醒您一键打开热点开关"
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)

        val pi = PendingIntent.getActivity(
            appCtx,
            HOTSPOT_NOTIF_ID,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(appCtx, HOTSPOT_NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_opaldesk)
            .setContentTitle("收到 Mac 一键热点请求")
            .setContentText("点击打开个人热点设置，开启后 Mac 将自动连接")
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000L)
            .build()
        runCatching { nm.notify(HOTSPOT_NOTIF_ID, notif) }
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvertisingLocked(appCtx: Context, manufacturerPayload: ByteArray) {
        stopAdvertisingLocked()
        if (!hasRequiredPermissions(appCtx)) return
        val bm = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val leAdv = runCatching { bm.adapter?.bluetoothLeAdvertiser }.getOrNull() ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Primary Adv packet carries ONLY the 128-bit Service UUID (<= 21 bytes, guaranteed <= 31B limit).
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleNearbyAdvertisementBuilder.SERVICE_UUID))
            .build()

        // Scan Response carries Manufacturer ID 0x504F + 12-byte OpalDesk payload (16 bytes <= 31B limit).
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(BleNearbyAdvertisementBuilder.MANUFACTURER_ID, manufacturerPayload)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                synchronized(lock) {
                    lastAdvertisedPayload = manufacturerPayload.copyOf()
                    updateRuntimeStateLocked(
                        appCtx,
                        advertising = true,
                        gattRunning = gattServer != null,
                        msg = "近场蓝牙广播中",
                    )
                }
                OpalLogStore.i(TAG, "BLE Nearby advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                synchronized(lock) {
                    activeAdvertiseCallback = null
                    lastAdvertisedPayload = null
                    updateRuntimeStateLocked(
                        appCtx,
                        advertising = false,
                        gattRunning = gattServer != null,
                        msg = "BLE 广播启动失败 (code=$errorCode)",
                    )
                }
                OpalLogStore.w(TAG, "BLE Nearby advertising failed code=$errorCode")
            }
        }

        val started = runCatching {
            leAdv.startAdvertising(settings, advData, scanResponse, cb)
            advertiser = leAdv
            activeAdvertiseCallback = cb
            lastAdvertisedPayload = manufacturerPayload.copyOf()
            true
        }.getOrDefault(false)
        if (!started) {
            activeAdvertiseCallback = null
            lastAdvertisedPayload = null
            updateRuntimeStateLocked(
                appCtx,
                advertising = false,
                gattRunning = gattServer != null,
                msg = "无法启动 BLE 广播",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingLocked() {
        val cb = activeAdvertiseCallback
        val adv = advertiser
        if (cb != null && adv != null) {
            runCatching { adv.stopAdvertising(cb) }
        }
        activeAdvertiseCallback = null
        advertiser = null
        lastAdvertisedPayload = null
    }

    @SuppressLint("MissingPermission")
    private fun stopBleHardwareLocked() {
        stopAdvertisingLocked()
        gattServer?.let { srv ->
            runCatching { srv.clearServices() }
            runCatching { srv.close() }
        }
        gattServer = null
        commandCharacteristic = null
        nonceStore.clearAll()
        mtuByAddress.clear()
        subscribedAddresses.clear()
        preparedWritesByAddress.clear()
        lastResponseByAddress.clear()
    }

    private fun registerBtStateReceiverLocked(appCtx: Context) {
        if (btReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appCtx.registerReceiver(btStateReceiver, filter)
            }
            true
        }.getOrDefault(false)
        btReceiverRegistered = ok
    }

    private fun unregisterBtStateReceiverLocked() {
        if (!btReceiverRegistered) return
        val ctx = appContext
        if (ctx != null) {
            runCatching { ctx.unregisterReceiver(btStateReceiver) }
        }
        btReceiverRegistered = false
    }

    private fun buildCurrentManufacturerPayload(appCtx: Context, remote: RemoteStreamStatus): ByteArray {
        val capFlags = BleNearbyAdvertisementBuilder.computeCapFlags(
            lanShareActive = remote.isLanShareActive,
            hotspotActive = detectLocalHotspotInterfaceActive(),
            hotspotTriggerSupported = true,
        )
        val port = if (remote.wifiPort > 0) remote.wifiPort else 19878
        val batteryPct = readBatteryPercent(appCtx)
        return BleNearbyAdvertisementBuilder.encodeManufacturerData(
            capFlags = capFlags,
            certFingerprintHex = remote.certFingerprint,
            wifiPort = port,
            batteryPct = batteryPct,
        )
    }

    private fun readBatteryPercent(ctx: Context): Int {
        return runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 0..100) pct else -1
        }.getOrDefault(-1)
    }

    private fun detectLocalHotspotInterfaceActive(): Boolean {
        return runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name?.lowercase(Locale.ROOT).orEmpty()
                val isApName = name.startsWith("ap") || name.startsWith("swlan") ||
                    name.startsWith("softap") || name == "wlan1" || name == "wlan2"
                if (!isApName) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return@runCatching true
                    }
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun updateRuntimeStateLocked(
        ctx: Context,
        advertising: Boolean,
        gattRunning: Boolean,
        msg: String = _stateFlow.value.statusMessage,
    ) {
        _stateFlow.value = BleNearbyRuntimeState(
            enabled = AppPrefs.isBleNearbyEnabled(ctx),
            supported = isBleSupported(ctx),
            hasPermissions = hasRequiredPermissions(ctx),
            bluetoothEnabled = isBluetoothEnabled(ctx),
            advertising = advertising,
            gattRunning = gattRunning,
            statusMessage = msg,
        )
    }
}
