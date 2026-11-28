package io.github.lengsukq.opalcast.policy

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object FloatingGeometry {
    data class Bounds(val width: Int, val height: Int)
    data class Point(val x: Int, val y: Int)

    private const val TABLET_MIN_SCREEN_DP = 580f

    fun dpToPx(dp: Int, density: Float): Int = (dp * density.coerceAtLeast(0.5f)).roundToInt()

    fun safeCoerceIn(value: Int, minVal: Int, maxVal: Int): Int {
        val safeMax = max(1, maxVal)
        val safeMin = min(minVal, safeMax)
        return value.coerceIn(safeMin, safeMax)
    }

    fun safeCoerceRangeFloor(value: Int, minVal: Int, maxVal: Int): Int {
        val safeMax = max(minVal, maxVal)
        return value.coerceIn(minVal, safeMax)
    }

    fun computeWindowSize(screenW: Int, screenH: Int, density: Float, isLandscape: Boolean): Bounds {
        val safeScreenW = max(1, screenW)
        val safeScreenH = max(1, screenH)
        val safeDensity = density.coerceAtLeast(0.5f)
        val minScreen = min(safeScreenW, safeScreenH)
        val minScreenDp = minScreen / safeDensity
        val maxAllowedW = max(1, (safeScreenW * 0.92f).roundToInt())
        val width: Int
        val height: Int

        if (isLandscape) {
            val targetMaxW = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                min(dpToPx(620, safeDensity), (safeScreenW * 0.72f).roundToInt())
            } else {
                min(dpToPx(580, safeDensity), (safeScreenW * 0.88f).roundToInt())
            }
            width = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                safeCoerceIn(dpToPx(620, safeDensity), dpToPx(240, safeDensity), targetMaxW)
            } else {
                safeCoerceIn((safeScreenW * 0.84f).roundToInt(), dpToPx(320, safeDensity), targetMaxW)
            }.coerceAtMost(maxAllowedW)
            val maxAllowedH = max(1, (safeScreenH * 0.64f).roundToInt())
            height = safeCoerceIn((width * 0.62f).roundToInt(), dpToPx(240, safeDensity), maxAllowedH)
        } else if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
            val maxTabletW = max(1, (safeScreenW * 0.44f).roundToInt())
            width = safeCoerceIn(dpToPx(390, safeDensity), dpToPx(230, safeDensity), maxTabletW)
                .coerceAtMost(maxAllowedW)
            val maxAllowedH = max(1, (safeScreenH * 0.82f).roundToInt())
            height = safeCoerceIn((width * 1.72f).roundToInt(), dpToPx(440, safeDensity), maxAllowedH)
        } else {
            val maxPhoneW = min(dpToPx(330, safeDensity), maxAllowedW)
            width = safeCoerceIn((minScreen * 0.56f).roundToInt(), dpToPx(230, safeDensity), maxPhoneW)
            val maxAllowedH = max(1, (safeScreenH * 0.74f).roundToInt())
            height = safeCoerceIn((width * 1.70f).roundToInt(), dpToPx(340, safeDensity), maxAllowedH)
        }
        return Bounds(width = max(1, width), height = max(1, height))
    }

    fun clampResizeSize(targetW: Int, targetH: Int, screenW: Int, screenH: Int, density: Float): Bounds {
        val safeDensity = density.coerceAtLeast(0.5f)
        val minW = dpToPx(210, safeDensity)
        val maxW = max(1, (max(1, screenW) * 0.92f).roundToInt())
        val minH = dpToPx(260, safeDensity)
        val maxH = max(1, (max(1, screenH) * 0.86f).roundToInt())
        return Bounds(safeCoerceIn(targetW, minW, maxW), safeCoerceIn(targetH, minH, maxH))
    }

    fun clampDragPosition(targetX: Int, targetY: Int, windowW: Int, screenW: Int, screenH: Int, density: Float): Point {
        val safeDensity = density.coerceAtLeast(0.5f)
        val minX = -max(0, windowW) / 2
        val maxX = max(minX, max(1, screenW) - dpToPx(48, safeDensity))
        val minY = dpToPx(16, safeDensity)
        val maxY = max(minY, max(1, screenH) - dpToPx(56, safeDensity))
        return Point(targetX.coerceIn(minX, maxX), targetY.coerceIn(minY, maxY))
    }

    fun clampVisiblePosition(x: Int, y: Int, windowW: Int, windowH: Int, screenW: Int, screenH: Int, density: Float): Point {
        val safeDensity = density.coerceAtLeast(0.5f)
        val padX = dpToPx(8, safeDensity)
        val padY = dpToPx(24, safeDensity)
        return Point(
            x = safeCoerceRangeFloor(x, padX, max(1, screenW) - windowW - padX),
            y = safeCoerceRangeFloor(y, padY, max(1, screenH) - windowH - padY),
        )
    }

    fun clampBubblePosition(targetX: Int, targetY: Int, screenW: Int, screenH: Int, density: Float): Point {
        val safeDensity = density.coerceAtLeast(0.5f)
        val minY = dpToPx(24, safeDensity)
        return Point(
            x = safeCoerceRangeFloor(targetX, 0, max(1, screenW) - dpToPx(52, safeDensity)),
            y = safeCoerceRangeFloor(targetY, minY, max(1, screenH) - dpToPx(64, safeDensity)),
        )
    }

    fun computeAspectMatchedWindowSize(
        videoW: Int,
        videoH: Int,
        chromeHeightPx: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Bounds {
        val safeScreenW = max(1, screenW)
        val safeScreenH = max(1, screenH)
        val safeDensity = density.coerceAtLeast(0.5f)
        val safeChrome = max(0, chromeHeightPx)
        val aspect = (max(2, videoW).toFloat() / max(2, videoH).toFloat()).coerceIn(0.35f, 3.0f)

        val maxW = max(1, (safeScreenW * 0.90f).roundToInt())
        val maxContentH = max(1, (safeScreenH * 0.84f).roundToInt() - safeChrome)
        val minScreenDp = min(safeScreenW, safeScreenH) / safeDensity

        val desiredContentH: Int
        val desiredContentW: Int
        if (aspect < 1f) {
            val capH = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                min(dpToPx(660, safeDensity), maxContentH)
            } else {
                min(dpToPx(540, safeDensity), maxContentH)
            }
            desiredContentH = max(1, capH)
            desiredContentW = max(1, (desiredContentH * aspect).roundToInt())
        } else {
            val capW = if (minScreenDp >= TABLET_MIN_SCREEN_DP) {
                min(dpToPx(680, safeDensity), maxW)
            } else {
                min(dpToPx(560, safeDensity), maxW)
            }
            desiredContentW = max(1, capW)
            desiredContentH = max(1, (desiredContentW / aspect).roundToInt())
        }
        return fitAspectIntoBounds(desiredContentW, desiredContentH, aspect, safeChrome, maxW, maxContentH)
    }

    fun clampAspectLockedResizeSize(
        targetW: Int,
        targetH: Int,
        videoW: Int,
        videoH: Int,
        chromeHeightPx: Int,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Bounds {
        val safeScreenW = max(1, screenW)
        val safeScreenH = max(1, screenH)
        val safeDensity = density.coerceAtLeast(0.5f)
        val safeChrome = max(0, chromeHeightPx)
        val aspect = (max(2, videoW).toFloat() / max(2, videoH).toFloat()).coerceIn(0.35f, 3.0f)

        val maxW = max(1, (safeScreenW * 0.92f).roundToInt())
        val maxContentH = max(1, (safeScreenH * 0.86f).roundToInt() - safeChrome)
        val minW = min(dpToPx(200, safeDensity), maxW)
        val minContentH = min(dpToPx(200, safeDensity), maxContentH)

        val desiredW = max(minW, targetW)
        val desiredContentH = max(minContentH, targetH - safeChrome)
        val fromW = fitAspectIntoBounds(desiredW, max(1, (desiredW / aspect).roundToInt()), aspect, safeChrome, maxW, maxContentH)
        val fromH = fitAspectIntoBounds(max(1, (desiredContentH * aspect).roundToInt()), desiredContentH, aspect, safeChrome, maxW, maxContentH)

        val errW = abs(fromW.width - targetW) + abs(fromW.height - targetH)
        val errH = abs(fromH.width - targetW) + abs(fromH.height - targetH)
        val chosen = if (errW <= errH) fromW else fromH
        val clampedW = max(minW, min(maxW, chosen.width))
        val lockedContentH = max(1, (clampedW / aspect).roundToInt()).coerceAtMost(maxContentH)
        val lockedW = max(1, (lockedContentH * aspect).roundToInt()).coerceAtMost(maxW)
        return Bounds(width = lockedW, height = lockedContentH + safeChrome)
    }

    private fun fitAspectIntoBounds(
        rawW: Int,
        rawContentH: Int,
        aspect: Float,
        chromeHeightPx: Int,
        maxW: Int,
        maxContentH: Int,
    ): Bounds {
        var w = max(1, rawW)
        var h = max(1, rawContentH)
        if (w > maxW) {
            w = maxW
            h = max(1, (w / aspect).roundToInt())
        }
        if (h > maxContentH) {
            h = maxContentH
            w = max(1, (h * aspect).roundToInt())
        }
        if (w > maxW) {
            w = maxW
            h = max(1, (w / aspect).roundToInt())
        }
        return Bounds(width = max(1, w), height = max(1, h) + chromeHeightPx)
    }
}

object UsbTetherPolicy {
    const val DEFAULT_CAST_PORT = ReceiverPolicy.DEFAULT_CAST_PORT
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

    private val STR_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val BOOL_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*(true|false)""")
    private val INT_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*(-?\d+)""")

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

    fun parseCastHealthJson(rawJson: String?): CastHealthInfo? {
        val body = rawJson?.trim().orEmpty()
        if (!body.startsWith("{") || !body.endsWith("}")) return null
        val bools = HashMap<String, Boolean>()
        BOOL_FIELD_REGEX.findAll(body).forEach { m -> bools[m.groupValues[1]] = (m.groupValues[2] == "true") }
        if (bools["ok"] != true) return null

        val strs = HashMap<String, String>()
        STR_FIELD_REGEX.findAll(body).forEach { m -> strs[m.groupValues[1]] = unescapeJson(m.groupValues[2]) }
        if (strs["role"] != "cast") return null

        val ints = HashMap<String, Int>()
        INT_FIELD_REGEX.findAll(body).forEach { m -> m.groupValues[2].toIntOrNull()?.let { ints[m.groupValues[1]] = it } }
        val devName = strs["deviceName"].orEmpty().trim().ifEmpty { "Android" }
        val shortFp = strs["certShortFp"].orEmpty().trim()
        val protoVer = ints["protocolVersion"] ?: 1
        return CastHealthInfo(devName, shortFp, protoVer)
    }

    fun formatShortFingerprint(hex: String?): String {
        val clean = hex.orEmpty().replace(":", "").trim().uppercase(Locale.US)
        if (clean.length < 12) return clean
        return "${clean.substring(0, 4)}-${clean.substring(4, 8)}-${clean.substring(8, 12)}"
    }

    private fun unescapeJson(raw: String): String {
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

object CameraGeometry {
    data class RotatedNv21(val data: ByteArray, val width: Int, val height: Int)

    fun normalizeFacing(facing: String?): String = when (facing?.trim()?.lowercase(Locale.US)) {
        "back", "rear", "environment" -> "back"
        else -> "front"
    }

    fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        1 -> 90
        2 -> 180
        3 -> 270
        else -> 0
    }

    fun computeUprightRotationCw(sensorOrientation: Int, isFront: Boolean, displayRotationDeg: Int): Int {
        val sensor = ((sensorOrientation % 360) + 360) % 360
        val disp = ((displayRotationDeg % 360) + 360) % 360
        return if (isFront) (sensor + disp) % 360 else (sensor - disp + 360) % 360
    }

    fun rotateNv21(src: ByteArray, width: Int, height: Int, cwDeg: Int): RotatedNv21 {
        val norm = ((cwDeg % 360) + 360) % 360
        if (norm == 0 || width <= 0 || height <= 0 || (width and 1) != 0 || (height and 1) != 0) {
            return RotatedNv21(src, width, height)
        }
        val ySize = width * height
        val expectedLen = ySize + (ySize / 2)
        if (src.size < expectedLen) return RotatedNv21(src, width, height)

        val dst = ByteArray(expectedLen)
        val uvW = width / 2
        val uvH = height / 2
        return when (norm) {
            90 -> {
                val dstW = height
                for (y in 0 until height) {
                    val rowBase = y * width
                    val dstX = height - 1 - y
                    for (x in 0 until width) dst[x * dstW + dstX] = src[rowBase + x]
                }
                for (uy in 0 until uvH) {
                    val srcRow = ySize + uy * width
                    val dstUx = uvH - 1 - uy
                    for (ux in 0 until uvW) {
                        val sIdx = srcRow + ux * 2
                        val dIdx = ySize + (ux * uvH + dstUx) * 2
                        dst[dIdx] = src[sIdx]
                        dst[dIdx + 1] = src[sIdx + 1]
                    }
                }
                RotatedNv21(dst, dstW, width)
            }
            180 -> {
                for (y in 0 until height) {
                    val srcRow = y * width
                    val dstRow = (height - 1 - y) * width
                    for (x in 0 until width) dst[dstRow + (width - 1 - x)] = src[srcRow + x]
                }
                for (uy in 0 until uvH) {
                    val srcRow = ySize + uy * width
                    val dstRow = ySize + (uvH - 1 - uy) * width
                    for (ux in 0 until uvW) {
                        val sIdx = srcRow + ux * 2
                        val dIdx = dstRow + (uvW - 1 - ux) * 2
                        dst[dIdx] = src[sIdx]
                        dst[dIdx + 1] = src[sIdx + 1]
                    }
                }
                RotatedNv21(dst, width, height)
            }
            270 -> {
                val dstW = height
                for (y in 0 until height) {
                    val rowBase = y * width
                    for (x in 0 until width) dst[(width - 1 - x) * dstW + y] = src[rowBase + x]
                }
                for (uy in 0 until uvH) {
                    val srcRow = ySize + uy * width
                    for (ux in 0 until uvW) {
                        val sIdx = srcRow + ux * 2
                        val dIdx = ySize + ((uvW - 1 - ux) * uvH + uy) * 2
                        dst[dIdx] = src[sIdx]
                        dst[dIdx + 1] = src[sIdx + 1]
                    }
                }
                RotatedNv21(dst, dstW, width)
            }
            else -> RotatedNv21(src, width, height)
        }
    }
}

object InputGuardPolicy {
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1
    private const val ACTION_MOVE = 2
    private const val ACTION_CANCEL = 3
    private const val BUTTON_BACK = 8
    private const val BUTTON_FORWARD = 16
    private const val SIDE_NAV_MASK = BUTTON_BACK or BUTTON_FORWARD

    private const val KEYCODE_BACK = 4
    private const val KEYCODE_HOME = 3
    private const val KEYCODE_VOLUME_UP = 24
    private const val KEYCODE_VOLUME_DOWN = 25
    private const val KEYCODE_POWER = 26
    private const val KEYCODE_VOLUME_MUTE = 164
    private const val KEYCODE_APP_SWITCH = 187
    private const val KEYCODE_SLEEP = 223
    private const val KEYCODE_WAKEUP = 224

    fun isForwardableKey(keyCode: Int): Boolean = when (keyCode) {
        KEYCODE_VOLUME_UP, KEYCODE_VOLUME_DOWN, KEYCODE_VOLUME_MUTE,
        KEYCODE_POWER, KEYCODE_SLEEP, KEYCODE_WAKEUP,
        KEYCODE_HOME, KEYCODE_APP_SWITCH -> false
        else -> true
    }

    /** Suppresses synthetic left-click touch events emitted by Android alongside mouse side-button presses. */
    class SideNavigationTouchFilter {
        private var suppressing = false

        fun shouldSuppress(actionMasked: Int, isMouse: Boolean, buttonState: Int): Boolean {
            if (!isMouse) return false
            val hasSideNav = (buttonState and SIDE_NAV_MASK) != 0
            if (actionMasked == ACTION_DOWN && hasSideNav) {
                suppressing = true
                return true
            }
            if (suppressing) {
                if (actionMasked == ACTION_UP || actionMasked == ACTION_CANCEL) {
                    suppressing = false
                }
                return true
            }
            return hasSideNav && actionMasked == ACTION_MOVE
        }
    }

    /** Deduplicates mouse side-back `ACTION_BUTTON_PRESS(BUTTON_BACK)` vs synthetic `KEYCODE_BACK`. */
    class SideBackKeyDeduplicator(private val dedupWindowMs: Long = 160L) {
        private var lastClaimMs = -1L
        private var lastKeyForwardMs = -1L
        private var suppressUp = false

        @Synchronized
        fun tryClaimSideButtonBack(uptimeMs: Long): Boolean {
            if (lastKeyForwardMs >= 0L && uptimeMs - lastKeyForwardMs in 0L..dedupWindowMs) {
                return false
            }
            lastClaimMs = uptimeMs
            suppressUp = true
            return true
        }

        @Synchronized
        fun recordBackKeyForwarded(uptimeMs: Long) {
            lastKeyForwardMs = uptimeMs
        }

        @Synchronized
        fun shouldSuppressDuplicateBackKey(keyCode: Int, action: Int, uptimeMs: Long): Boolean {
            if (keyCode != KEYCODE_BACK) return false
            if (action == ACTION_DOWN) {
                val dup = lastClaimMs >= 0L && uptimeMs - lastClaimMs in 0L..dedupWindowMs
                suppressUp = dup
                return dup
            }
            if (action == ACTION_UP && suppressUp) {
                suppressUp = false
                return true
            }
            return false
        }
    }
}
