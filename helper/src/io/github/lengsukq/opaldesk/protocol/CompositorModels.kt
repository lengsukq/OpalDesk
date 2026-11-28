package io.github.lengsukq.opaldesk.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import kotlin.math.max

/**
 * Immutable client-side models, JSON parsers, and always-on-top pin store for the OpalDesk
 * Universal Compositor. Pure geometry math lives in [CompositorGeometry].
 */
data class DaemonStatus(
    val connected: Boolean = false,
    val protocolVersion: Int = 0,
    val daemonUid: Int = -1,
    val injectStatus: Int = BridgeProtocol.InputBackend.STATUS_SYSTEM,
    val inputBackendMode: Int = BridgeProtocol.InputBackend.AUTO,
    val activeVdCount: Int = 0,
    val maxVdCount: Int = 6,
    val windowCount: Int = 0,
    val rootEnhanced: Boolean = false,
    val canvasWidth: Int = 1920,
    val canvasHeight: Int = 1200,
    val canvasDpi: Int = 240,
    val targetFps: Int = 0,
    val windowDpiScalePct: Int = 100,
    val mouseTargetMode: Int = BridgeProtocol.MouseTarget.EXTERNAL,
    val boundMouseCount: Int = 0,
    val connectedMouseCount: Int = 0,
    val mouseRoutedDisplayId: Int = -1,
    val hostScreenOff: Boolean = false,
    val globalCustomDpi: Int = 0,
    val remoteMicEnabled: Boolean = false,
    val remoteCameraEnabled: Boolean = false,
    val audioRouteMode: Int = BridgeProtocol.AudioRoute.SPLIT_BY_APP,
    val windowSupersampleMode: Int = BridgeProtocol.WindowSupersample.COMPAT,
) {
    val injectBackendLabel: String
        get() = when {
            !connected -> "未连接"
            injectStatus == BridgeProtocol.InputBackend.STATUS_UHID -> "Linux /dev/uhid 内核虚拟外设"
            injectStatus == BridgeProtocol.InputBackend.STATUS_SYSTEM -> "AOSP InputManager 系统直通"
            else -> "输入受限（建议切换至 UHID）"
        }

    val privilegeSummary: String
        get() = when {
            !connected -> "守护进程离线"
            rootEnhanced -> "Shell 沙盒 (uid=$daemonUid) · 已启用可选 Root 增强"
            else -> "免 Root 纯净模式 (uid=$daemonUid)"
        }
}

data class RemoteStreamStatus(
    val connected: Boolean = false,
    val sinkType: Int = 0,
    val peerName: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fpsX100: Int = 0,
    val bitrateKbps: Int = 0,
    val wifiEnabled: Boolean = false,
    val wifiPort: Int = 19878,
    val certFingerprint: String = "",
    val activeSessionPermanent: Boolean = false,
    val trustedCount: Int = 0,
    val lanIp: String = "",
    val desktopDisplayId: Int = -1,
    val usbTetherIp: String = "",
    val usbTetherInterface: String = "",
    val tlsBindScope: Int = BridgeProtocol.TlsBindScope.ALL,
) {
    val hasActiveDesktopCast: Boolean
        get() = connected && desktopDisplayId > 0
    val isUsbTetherOnlyMode: Boolean
        get() = wifiEnabled && tlsBindScope == BridgeProtocol.TlsBindScope.USB_TETHER_ONLY
    val isLanShareActive: Boolean
        get() = wifiEnabled && tlsBindScope == BridgeProtocol.TlsBindScope.ALL
    val hasUsbTetherInterface: Boolean
        get() = usbTetherIp.isNotBlank()
    val shortCertFingerprint: String
        get() {
            val clean = certFingerprint.replace(":", "").trim().uppercase()
            if (clean.length < 12) return clean
            return "${clean.substring(0, 4)}-${clean.substring(4, 8)}-${clean.substring(8, 12)}"
        }

    val channelLabel: String
        get() = when (sinkType) {
            2 -> "USB 有线直连"
            4 -> "无线 ADB 隧道"
            3 -> "Wi-Fi TLS 直连"
            6 -> "USB 网络共享直连"
            else -> "无线投屏"
        }
}

data class PendingPairingRequest(
    val requestId: String,
    val clientId: String,
    val clientName: String,
    val peerIp: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

data class TrustedPeerEntry(
    val clientId: String,
    val clientName: String,
    val createdAtMs: Long,
)

data class DexImeRuntimeState(
    val synced: Boolean = false,
    val currentImeId: String = "",
    val originalImeId: String = "",
    val lockedImeId: String = "",
    val bridgeImeId: String = BridgeProtocol.OPALDESK_BRIDGE_IME_ID,
    val pluginImeId: String = BridgeProtocol.OPALDESK_IME_PLUGIN_ID,
    val pluginImeInstalled: Boolean = false,
    val pluginImeActive: Boolean = false,
    val bridgeImeActive: Boolean = false,
    val autoBridgeImeOnCast: Boolean = true,
    val imeLockApplied: Boolean = false,
    val yieldedToDisplay0: Boolean = false,
    val hardKbdOverrideApplied: Boolean = false,
    val dexSessionActive: Boolean = false,
    val dexImeDisplayMode: Int = BridgeProtocol.DexImeDisplayMode.AUTO,
    val muteHostImeOnCast: Boolean = false,
    val showImeWithHardKeyboard: Int = BridgeProtocol.HardKbdSoftIme.SYSTEM,
    val effectiveWindowImePolicy: Int = BridgeProtocol.DisplayImePolicy.FALLBACK_DISPLAY,
    val physicalKeyboardDetected: Boolean = false,
    val enabledImeIds: List<String> = emptyList(),
)

object DexImeStateParser {
    private val strFieldRegex = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val boolFieldRegex = Regex(""""([^"\\]+)"\s*:\s*(true|false)""")
    private val numFieldRegex = Regex(""""([^"\\]+)"\s*:\s*(-?\d+)""")
    private val arrayFieldRegex = Regex(""""enabledImeIds"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val quotedTokenRegex = Regex(""""((?:\\.|[^"\\])*)"""")

    fun parse(json: String?): DexImeRuntimeState {
        if (json.isNullOrBlank() || !json.trim().startsWith("{")) return DexImeRuntimeState()
        val strMap = HashMap<String, String>()
        strFieldRegex.findAll(json).forEach { m ->
            strMap[m.groupValues[1]] = unescape(m.groupValues[2])
        }
        val boolMap = HashMap<String, Boolean>()
        boolFieldRegex.findAll(json).forEach { m ->
            boolMap[m.groupValues[1]] = m.groupValues[2] == "true"
        }
        val numMap = HashMap<String, Int>()
        numFieldRegex.findAll(json).forEach { m ->
            m.groupValues[2].toIntOrNull()?.let { numMap[m.groupValues[1]] = it }
        }
        val enabled = ArrayList<String>()
        val arrMatch = arrayFieldRegex.find(json)
        if (arrMatch != null) {
            quotedTokenRegex.findAll(arrMatch.groupValues[1]).forEach { tok ->
                val id = unescape(tok.groupValues[1]).trim()
                if (id.isNotEmpty() && !enabled.contains(id)) {
                    enabled.add(id)
                }
            }
        }
        return DexImeRuntimeState(
            synced = true,
            currentImeId = strMap["currentImeId"].orEmpty(),
            originalImeId = strMap["originalImeId"].orEmpty(),
            lockedImeId = strMap["lockedImeId"].orEmpty(),
            bridgeImeId = strMap["bridgeImeId"].orEmpty().ifEmpty { BridgeProtocol.OPALDESK_BRIDGE_IME_ID },
            pluginImeId = strMap["pluginImeId"].orEmpty().ifEmpty { BridgeProtocol.OPALDESK_IME_PLUGIN_ID },
            pluginImeInstalled = boolMap["pluginImeInstalled"] == true,
            pluginImeActive = boolMap["pluginImeActive"] == true,
            bridgeImeActive = boolMap["bridgeImeActive"] == true,
            autoBridgeImeOnCast = boolMap["autoBridgeImeOnCast"] ?: true,
            imeLockApplied = boolMap["imeLockApplied"] == true,
            yieldedToDisplay0 = boolMap["yieldedToDisplay0"] == true,
            hardKbdOverrideApplied = boolMap["hardKbdOverrideApplied"] == true,
            dexSessionActive = boolMap["dexSessionActive"] == true,
            dexImeDisplayMode = numMap["dexImeDisplayMode"] ?: BridgeProtocol.DexImeDisplayMode.AUTO,
            muteHostImeOnCast = boolMap["muteHostImeOnCast"] == true,
            showImeWithHardKeyboard = numMap["showImeWithHardKeyboard"] ?: BridgeProtocol.HardKbdSoftIme.SYSTEM,
            effectiveWindowImePolicy = numMap["effectiveWindowImePolicy"] ?: BridgeProtocol.DisplayImePolicy.FALLBACK_DISPLAY,
            physicalKeyboardDetected = boolMap["physicalKeyboardDetected"] == true,
            enabledImeIds = enabled,
        )
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

object PairingJsonParser {
    private val strFieldRegex = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val numFieldRegex = Regex(""""([^"\\]+)"\s*:\s*(-?\d+)""")

    private fun extractFlatObjects(json: String): List<String> {
        val out = ArrayList<String>()
        var inStr = false
        var escape = false
        var depth = 0
        var start = -1
        for (i in json.indices) {
            val ch = json[i]
            if (escape) {
                escape = false
                continue
            }
            if (inStr) {
                if (ch == '\\') escape = true
                else if (ch == '"') inStr = false
                continue
            }
            when (ch) {
                '"' -> inStr = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            out.add(json.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return out
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
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(next)
                    }
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

    private fun extractStrings(obj: String): Map<String, String> {
        val map = HashMap<String, String>()
        strFieldRegex.findAll(obj).forEach { m ->
            map[m.groupValues[1]] = unescapeJson(m.groupValues[2])
        }
        return map
    }

    private fun extractLongs(obj: String): Map<String, Long> {
        val map = HashMap<String, Long>()
        numFieldRegex.findAll(obj).forEach { m ->
            m.groupValues[2].toLongOrNull()?.let { map[m.groupValues[1]] = it }
        }
        return map
    }

    fun parsePending(json: String?): PendingPairingRequest? {
        if (json.isNullOrBlank()) return null
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return null
        val strs = extractStrings(trimmed)
        val reqId = strs["requestId"].orEmpty().trim()
        if (reqId.isEmpty()) return null
        val nums = extractLongs(trimmed)
        return PendingPairingRequest(
            requestId = reqId,
            clientId = strs["clientId"].orEmpty(),
            clientName = strs["clientName"].orEmpty().ifBlank { "未知设备" },
            peerIp = strs["peerIp"].orEmpty(),
            createdAtMs = nums["createdAtMs"] ?: 0L,
            expiresAtMs = nums["expiresAtMs"] ?: 0L,
        )
    }

    fun parseTrustedPeers(json: String?): List<TrustedPeerEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val out = ArrayList<TrustedPeerEntry>()
        for (rawObj in extractFlatObjects(json)) {
            val strs = extractStrings(rawObj)
            val cid = strs["clientId"].orEmpty().trim()
            if (cid.isEmpty()) continue
            val nums = extractLongs(rawObj)
            out.add(
                TrustedPeerEntry(
                    clientId = cid,
                    clientName = strs["clientName"].orEmpty().ifBlank { cid },
                    createdAtMs = nums["createdAtMs"] ?: 0L,
                ),
            )
        }
        return out
    }
}

data class WindowRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = max(0, right - left)
    val height: Int get() = max(0, bottom - top)
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

data class CompositorWindow(
    val windowId: Int,
    val pkg: String,
    val activityClass: String,
    val title: String,
    val profileName: String,
    val profileCode: Int,
    val state: String,
    val presentation: String,
    val frame: WindowRect,
    val contentWidth: Int,
    val contentHeight: Int,
    val contentDpi: Int,
    val displayId: Int,
    val taskId: Int,
    val zOrder: Int,
    val focused: Boolean,
    val snapshotVersion: Long,
    val hasSnapshot: Boolean,
    val userId: Int = 0,
    val profileType: String = "",
) {
    val isLive: Boolean get() = state == BridgeProtocol.WinState.LIVE && displayId > 0
    val isHibernated: Boolean get() = state == BridgeProtocol.WinState.HIBERNATED
    val isMinimized: Boolean get() = state == BridgeProtocol.WinState.MINIMIZED
    val isMaximized: Boolean get() = presentation == BridgeProtocol.Presentation.MAXIMIZED
    val isFullscreen: Boolean get() = presentation == BridgeProtocol.Presentation.FULLSCREEN
    val isPortraitProfile: Boolean get() = profileCode == BridgeProtocol.Profile.PORTRAIT
    val isSecondaryProfile: Boolean get() = userId > 0
    val appKey: String get() = AppProfileKey.format(pkg, userId)
}

data class CompositorScene(
    val seq: Long = 0L,
    val canvasWidth: Int = 1920,
    val canvasHeight: Int = 1200,
    val canvasDpi: Int = 240,
    val activeVdCount: Int = 0,
    val maxVdCount: Int = 6,
    val keyboardRoutedDisplayId: Int = -1,
    val comboKeyboardOnOuterDisplay: Boolean = false,
    val windows: List<CompositorWindow> = emptyList(),
) {
    val visibleWindows: List<CompositorWindow>
        get() = windows.filter { !it.isMinimized }.sortedBy { it.zOrder }

    val focusedWindow: CompositorWindow?
        get() = visibleWindows.lastOrNull { it.focused } ?: visibleWindows.lastOrNull()
}

enum class ResizeEdge {
    N, S, E, W, NW, NE, SW, SE
}

/**
 * Common desktop multi-window tiling presets for wide tablets and 2K/4K external monitors.
 */
enum class WindowTilingLayout(
    val label: String,
    val shortLabel: String,
    val maxSlots: Int,
) {
    SPLIT_2_COLS("左右二分屏", "左右二分", 2),
    SPLIT_2_MAIN_SIDE("主副分屏 (2:1)", "主副 2:1", 2),
    SPLIT_3_COLS("三列分屏", "三列分屏", 3),
    SPLIT_3_MAIN_STACK("一主两副", "一主两副", 3),
    SPLIT_4_COLS("四列分屏", "四列分屏", 4),
    SPLIT_4_GRID("四宫格 (2×2)", "四宫格", 4),
    SPLIT_5_COLS("五列并排 (5屏)", "五列分屏", 5),
    SPLIT_6_GRID("六宫格 (3×2)", "六宫格", 6),
    SPLIT_8_GRID("八宫格 (4×2)", "八宫格", 8),
    CASCADE("层叠平铺", "层叠平铺", 8),
}

object CompositorSceneParser {
    fun parse(json: String?): CompositorScene {
        if (json.isNullOrBlank()) return CompositorScene()
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("windows")
            val list = ArrayList<CompositorWindow>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val winId = o.optInt("windowId", -1)
                    if (winId <= 0) continue
                    list.add(
                        CompositorWindow(
                            windowId = winId,
                            pkg = o.optString("pkg", ""),
                            activityClass = o.optString("cls", ""),
                            title = o.optString("title", ""),
                            profileName = o.optString("profile", "DESKTOP"),
                            profileCode = o.optInt("profileCode", BridgeProtocol.Profile.DESKTOP),
                            state = o.optString("state", BridgeProtocol.WinState.HIBERNATED),
                            presentation = o.optString("presentation", BridgeProtocol.Presentation.FLOATING),
                            frame = WindowRect(
                                left = o.optInt("left", 100),
                                top = o.optInt("top", 80),
                                right = o.optInt("right", 960),
                                bottom = o.optInt("bottom", 680),
                            ),
                            contentWidth = o.optInt("contentW", 860),
                            contentHeight = o.optInt("contentH", 560),
                            contentDpi = o.optInt("contentDpi", 200),
                            displayId = o.optInt("displayId", -1),
                            taskId = o.optInt("taskId", -1),
                            zOrder = o.optInt("zOrder", 0),
                            focused = o.optBoolean("focused", false),
                            snapshotVersion = o.optLong("snapshotVer", 0L),
                            hasSnapshot = o.optBoolean("hasSnapshot", false),
                            userId = o.optInt("userId", 0).coerceAtLeast(0),
                            profileType = AppProfileKey.normalizeProfileType(
                                o.optString("profileType", ""),
                                o.optInt("userId", 0).coerceAtLeast(0),
                            ),
                        ),
                    )
                }
            }
            list.sortBy { it.zOrder }
            CompositorScene(
                seq = root.optLong("seq", 0L),
                canvasWidth = root.optInt("canvasW", 1920),
                canvasHeight = root.optInt("canvasH", 1200),
                canvasDpi = root.optInt("canvasDpi", 240),
                activeVdCount = root.optInt("activeVdCount", 0),
                maxVdCount = root.optInt("maxVdCount", 6),
                keyboardRoutedDisplayId = root.optInt("kbdRoutedDisplayId", -1),
                comboKeyboardOnOuterDisplay = root.optBoolean("comboKbdOuter", false),
                windows = list,
            )
        } catch (_: Throwable) {
            CompositorScene()
        }
    }
}

/**
 * Tracks user-pinned (Always-on-Top) DeX window IDs so pinned windows stay visually and
 * hit-testably above normal floating windows even while typing in a background window.
 */
object OpalWindowPinStore {
    private val _pinnedIdsFlow = MutableStateFlow<Set<Int>>(emptySet())
    val pinnedIdsFlow: StateFlow<Set<Int>> = _pinnedIdsFlow.asStateFlow()

    fun currentIds(): Set<Int> = _pinnedIdsFlow.value

    fun isPinned(windowId: Int): Boolean = _pinnedIdsFlow.value.contains(windowId)

    fun toggle(windowId: Int): Boolean {
        var nowPinned = false
        _pinnedIdsFlow.update { cur ->
            if (cur.contains(windowId)) {
                nowPinned = false
                cur - windowId
            } else {
                nowPinned = true
                cur + windowId
            }
        }
        return nowPinned
    }

    fun prune(aliveIds: Set<Int>) {
        _pinnedIdsFlow.update { cur -> cur.intersect(aliveIds) }
    }
}
