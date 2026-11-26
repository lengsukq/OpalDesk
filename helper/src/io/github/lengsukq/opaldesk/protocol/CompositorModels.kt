package io.github.lengsukq.opaldesk.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Immutable client-side models, JSON parser, and pure geometry math (hit-testing, 8-way resize,
 * edge snap detection) for the OpalDesk Universal Compositor.
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
    val bridgeImeActive: Boolean = false,
    val autoBridgeImeOnCast: Boolean = true,
    val imeLockApplied: Boolean = false,
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
            bridgeImeActive = boolMap["bridgeImeActive"] == true,
            autoBridgeImeOnCast = boolMap["autoBridgeImeOnCast"] ?: true,
            imeLockApplied = boolMap["imeLockApplied"] == true,
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
) {
    val isLive: Boolean get() = state == BridgeProtocol.WinState.LIVE && displayId > 0
    val isHibernated: Boolean get() = state == BridgeProtocol.WinState.HIBERNATED
    val isMinimized: Boolean get() = state == BridgeProtocol.WinState.MINIMIZED
    val isMaximized: Boolean get() = presentation == BridgeProtocol.Presentation.MAXIMIZED
    val isFullscreen: Boolean get() = presentation == BridgeProtocol.Presentation.FULLSCREEN
    val isPortraitProfile: Boolean get() = profileCode == BridgeProtocol.Profile.PORTRAIT
}

data class CompositorScene(
    val seq: Long = 0L,
    val canvasWidth: Int = 1920,
    val canvasHeight: Int = 1200,
    val canvasDpi: Int = 240,
    val activeVdCount: Int = 0,
    val maxVdCount: Int = 6,
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

/**
 * Pure client-side compositor geometry helpers (unit-testable with zero Android dependencies).
 */
object CompositorGeometry {
    const val TOP_BAR_DP = 28f
    const val HEADER_HEIGHT_DP = 36f
    const val DOCK_INSET_DP = 70f
    const val MIN_WIN_W_DP = 250f
    const val MIN_WIN_H_DP = 240f
    const val SNAP_TRIGGER_PX = 28

    fun dpToPx(dp: Float, density: Float): Int = max(1, (dp * density).roundToInt())

    fun clampMove(
        base: WindowRect,
        dx: Int,
        dy: Int,
        canvasW: Int,
        canvasH: Int,
        density: Float,
    ): WindowRect {
        val w = base.width
        val h = base.height
        val minTop = dpToPx(TOP_BAR_DP, density)
        val maxTop = max(minTop, canvasH - dpToPx(HEADER_HEIGHT_DP + 24f, density))
        val minVisX = dpToPx(96f, density)
        val nextLeft = (base.left + dx).coerceIn(-w + minVisX, max(0, canvasW - minVisX))
        val nextTop = (base.top + dy).coerceIn(minTop, maxTop)
        return WindowRect(nextLeft, nextTop, nextLeft + w, nextTop + h)
    }

    fun computeResize(
        base: WindowRect,
        edge: ResizeEdge,
        dx: Int,
        dy: Int,
        canvasW: Int,
        canvasH: Int,
        density: Float,
    ): WindowRect {
        val minW = min(dpToPx(MIN_WIN_W_DP, density), max(220, canvasW - 12))
        val minH = min(dpToPx(MIN_WIN_H_DP, density), max(220, canvasH - dpToPx(TOP_BAR_DP + 20f, density)))
        val minTop = dpToPx(TOP_BAR_DP, density)
        val maxBottom = max(minTop + minH, canvasH - dpToPx(12f, density))

        var l = base.left
        var t = base.top
        var r = base.right
        var b = base.bottom

        when (edge) {
            ResizeEdge.E, ResizeEdge.NE, ResizeEdge.SE -> {
                r = (base.right + dx).coerceIn(l + minW, max(l + minW, canvasW - 4))
            }
            ResizeEdge.W, ResizeEdge.NW, ResizeEdge.SW -> {
                val minLeft = min(4, base.left)
                l = (base.left + dx).coerceIn(minLeft, max(minLeft, r - minW))
            }
            else -> Unit
        }
        when (edge) {
            ResizeEdge.S, ResizeEdge.SE, ResizeEdge.SW -> {
                b = (base.bottom + dy).coerceIn(t + minH, max(t + minH, maxBottom))
            }
            ResizeEdge.N, ResizeEdge.NE, ResizeEdge.NW -> {
                t = (base.top + dy).coerceIn(minTop, max(minTop, b - minH))
            }
            else -> Unit
        }
        val evenW = (r - l) and 1.inv()
        val evenH = (b - t) and 1.inv()
        return WindowRect(l, t, l + max(minW, evenW), t + max(minH, evenH))
    }

    /**
     * Detects edge/corner snap zones when dragging a window header near desktop borders.
     */
    fun detectSnapPresentation(pointerX: Int, pointerY: Int, canvasW: Int, canvasH: Int, density: Float): String? {
        if (canvasW <= 0 || canvasH <= 0) return null
        val cornerZone = dpToPx(90f, density)
        val topTrigger = dpToPx(TOP_BAR_DP + 14f, density)
        val nearLeft = pointerX <= SNAP_TRIGGER_PX
        val nearRight = pointerX >= canvasW - SNAP_TRIGGER_PX
        val nearTop = pointerY <= topTrigger
        val nearBottom = pointerY >= canvasH - SNAP_TRIGGER_PX

        return when {
            nearLeft && pointerY <= cornerZone -> BridgeProtocol.Presentation.SNAP_TL
            nearLeft && pointerY >= canvasH - cornerZone -> BridgeProtocol.Presentation.SNAP_BL
            nearRight && pointerY <= cornerZone -> BridgeProtocol.Presentation.SNAP_TR
            nearRight && pointerY >= canvasH - cornerZone -> BridgeProtocol.Presentation.SNAP_BR
            nearLeft -> BridgeProtocol.Presentation.SNAP_LEFT
            nearRight -> BridgeProtocol.Presentation.SNAP_RIGHT
            nearTop -> BridgeProtocol.Presentation.MAXIMIZED
            nearBottom -> null
            else -> null
        }
    }

    fun computeSnapPreviewRect(
        presentation: String,
        canvasW: Int,
        canvasH: Int,
        density: Float,
        dockInsetDp: Float = DOCK_INSET_DP,
    ): WindowRect {
        val topInset = dpToPx(TOP_BAR_DP + 4f, density)
        val botInset = dpToPx(dockInsetDp.coerceAtLeast(4f), density)
        val pad = dpToPx(6f, density)
        val wl = pad
        val wr = max(wl + 200, canvasW - pad)
        val wt = topInset
        val wb = max(wt + 200, canvasH - botInset)
        val midX = (wl + wr) / 2
        val midY = (wt + wb) / 2
        val halfGap = dpToPx(4f, density)

        return when (presentation) {
            BridgeProtocol.Presentation.SNAP_LEFT -> WindowRect(wl, wt, midX - halfGap, wb)
            BridgeProtocol.Presentation.SNAP_RIGHT -> WindowRect(midX + halfGap, wt, wr, wb)
            BridgeProtocol.Presentation.SNAP_TL -> WindowRect(wl, wt, midX - halfGap, midY - halfGap)
            BridgeProtocol.Presentation.SNAP_TR -> WindowRect(midX + halfGap, wt, wr, midY - halfGap)
            BridgeProtocol.Presentation.SNAP_BL -> WindowRect(wl, midY + halfGap, midX - halfGap, wb)
            BridgeProtocol.Presentation.SNAP_BR -> WindowRect(midX + halfGap, midY + halfGap, wr, wb)
            else -> WindowRect(wl, wt, wr, wb)
        }
    }

    /**
     * Computes deterministic, non-overlapping, even-aligned outer window frames for pro desktop
     * multi-window tiling layouts (2-col, 2:1 main+side, 3-col, 1-main+2-stack, 4-col, 2×2 grid, cascade).
     */
    fun computeTilingFrames(
        layout: WindowTilingLayout,
        windowCount: Int,
        canvasW: Int,
        canvasH: Int,
        density: Float,
        dockInsetDp: Float = DOCK_INSET_DP,
    ): List<WindowRect> {
        if (windowCount <= 0 || canvasW <= 0 || canvasH <= 0) return emptyList()
        val slots = min(windowCount, layout.maxSlots)
        val topInset = dpToPx(TOP_BAR_DP + 4f, density)
        val botInset = dpToPx(dockInsetDp.coerceAtLeast(4f), density)
        val pad = dpToPx(6f, density)
        val gap = dpToPx(8f, density)
        val wl = pad
        val wr = max(wl + 260, canvasW - pad)
        val wt = topInset
        val wb = max(wt + 240, canvasH - botInset)
        val workW = wr - wl
        val workH = wb - wt
        val isPortraitCanvas = workH > workW

        fun alignedRect(left: Int, top: Int, right: Int, bottom: Int): WindowRect {
            val w = max(200, (right - left) and 1.inv())
            val h = max(180, (bottom - top) and 1.inv())
            return WindowRect(left, top, left + w, top + h)
        }

        fun splitColumns(colCount: Int): List<WindowRect> {
            val totalGaps = gap * (colCount - 1)
            val colW = max(200, ((workW - totalGaps) / colCount) and 1.inv())
            val out = ArrayList<WindowRect>(slots)
            for (i in 0 until slots) {
                val l = wl + i * (colW + gap)
                val r = if (i == colCount - 1) wr else min(wr, l + colW)
                out.add(alignedRect(l, wt, r, wb))
            }
            return out
        }

        fun splitRows(rowCount: Int): List<WindowRect> {
            val totalGaps = gap * (rowCount - 1)
            val rowH = max(180, ((workH - totalGaps) / rowCount) and 1.inv())
            val out = ArrayList<WindowRect>(slots)
            for (i in 0 until slots) {
                val t = wt + i * (rowH + gap)
                val b = if (i == rowCount - 1) wb else min(wb, t + rowH)
                out.add(alignedRect(wl, t, wr, b))
            }
            return out
        }

        fun splitGrid(cols: Int, rows: Int): List<WindowRect> {
            val totalGapX = gap * (cols - 1)
            val totalGapY = gap * (rows - 1)
            val cellW = max(200, ((workW - totalGapX) / cols) and 1.inv())
            val cellH = max(180, ((workH - totalGapY) / rows) and 1.inv())
            val out = ArrayList<WindowRect>(slots)
            for (i in 0 until slots) {
                val r = i / cols
                val c = i % cols
                val l = wl + c * (cellW + gap)
                val right = if (c == cols - 1) wr else min(wr, l + cellW)
                val t = wt + r * (cellH + gap)
                val bottom = if (r == rows - 1) wb else min(wb, t + cellH)
                out.add(alignedRect(l, t, right, bottom))
            }
            return out
        }

        return when (layout) {
            WindowTilingLayout.SPLIT_2_COLS -> if (isPortraitCanvas) splitRows(2) else splitColumns(2)
            WindowTilingLayout.SPLIT_3_COLS -> if (isPortraitCanvas) splitRows(3) else splitColumns(3)
            WindowTilingLayout.SPLIT_4_COLS -> if (isPortraitCanvas) {
                // 2x2 grid is far more usable than 4 skinny vertical strips on a portrait phone
                val midX = wl + (workW - gap) / 2
                val midY = wt + (workH - gap) / 2
                listOf(
                    alignedRect(wl, wt, midX, midY),
                    alignedRect(midX + gap, wt, wr, midY),
                    alignedRect(wl, midY + gap, midX, wb),
                    alignedRect(midX + gap, midY + gap, wr, wb),
                ).take(slots)
            } else {
                splitColumns(4)
            }
            WindowTilingLayout.SPLIT_2_MAIN_SIDE -> {
                if (isPortraitCanvas) {
                    val mainH = (((workH - gap) * 0.62f).roundToInt() and 1.inv()).coerceIn(240, max(240, workH - gap - 220))
                    val r1 = alignedRect(wl, wt, wr, wt + mainH)
                    if (slots == 1) listOf(r1)
                    else listOf(r1, alignedRect(wl, wt + mainH + gap, wr, wb))
                } else {
                    val mainW = (((workW - gap) * 0.65f).roundToInt() and 1.inv()).coerceIn(260, max(260, workW - gap - 260))
                    val r1 = alignedRect(wl, wt, wl + mainW, wb)
                    if (slots == 1) listOf(r1)
                    else listOf(r1, alignedRect(wl + mainW + gap, wt, wr, wb))
                }
            }
            WindowTilingLayout.SPLIT_3_MAIN_STACK -> {
                if (isPortraitCanvas) {
                    val mainH = (((workH - gap) * 0.54f).roundToInt() and 1.inv()).coerceIn(240, max(240, workH - gap - 220))
                    val midX = wl + (workW - gap) / 2
                    val out = ArrayList<WindowRect>(slots)
                    out.add(alignedRect(wl, wt, wr, wt + mainH))
                    if (slots >= 2) out.add(alignedRect(wl, wt + mainH + gap, midX, wb))
                    if (slots >= 3) out.add(alignedRect(midX + gap, wt + mainH + gap, wr, wb))
                    out
                } else {
                    val mainW = (((workW - gap) * 0.56f).roundToInt() and 1.inv()).coerceIn(260, max(260, workW - gap - 260))
                    val midY = wt + (workH - gap) / 2
                    val out = ArrayList<WindowRect>(slots)
                    out.add(alignedRect(wl, wt, wl + mainW, wb))
                    if (slots >= 2) out.add(alignedRect(wl + mainW + gap, wt, wr, midY))
                    if (slots >= 3) out.add(alignedRect(wl + mainW + gap, midY + gap, wr, wb))
                    out
                }
            }
            WindowTilingLayout.SPLIT_4_GRID -> splitGrid(cols = 2, rows = 2)
            WindowTilingLayout.SPLIT_5_COLS -> if (isPortraitCanvas) {
                val midY = wt + (workH - gap) / 2
                val topW = max(200, ((workW - gap) / 2) and 1.inv())
                val botW = max(200, ((workW - gap * 2) / 3) and 1.inv())
                val all = listOf(
                    alignedRect(wl, wt, min(wr, wl + topW), midY),
                    alignedRect(wl + topW + gap, wt, wr, midY),
                    alignedRect(wl, midY + gap, min(wr, wl + botW), wb),
                    alignedRect(wl + botW + gap, midY + gap, min(wr, wl + botW * 2 + gap), wb),
                    alignedRect(wl + (botW + gap) * 2, midY + gap, wr, wb),
                )
                all.take(slots)
            } else {
                splitColumns(5)
            }
            WindowTilingLayout.SPLIT_6_GRID -> if (isPortraitCanvas) splitGrid(cols = 2, rows = 3) else splitGrid(cols = 3, rows = 2)
            WindowTilingLayout.SPLIT_8_GRID -> if (isPortraitCanvas) splitGrid(cols = 2, rows = 4) else splitGrid(cols = 4, rows = 2)
            WindowTilingLayout.CASCADE -> {
                val winW = ((workW * (if (isPortraitCanvas) 0.90f else 0.62f)).roundToInt() and 1.inv()).coerceIn(280, max(280, workW))
                val winH = ((workH * (if (isPortraitCanvas) 0.56f else 0.74f)).roundToInt() and 1.inv()).coerceIn(260, max(260, workH))
                val step = dpToPx(if (isPortraitCanvas) 20f else 32f, density)
                val baseL = wl + max(0, (workW - winW - (if (isPortraitCanvas) 0 else step * max(0, slots - 1))) / 2)
                val baseT = wt + max(0, (workH - winH - step * max(0, slots - 1)) / (if (isPortraitCanvas) 3 else 2))
                val out = ArrayList<WindowRect>(slots)
                for (i in 0 until slots) {
                    val l = min(max(wl, baseL + (if (isPortraitCanvas) 0 else i * step)), max(wl, wr - winW))
                    val t = min(max(wt, baseT + i * step), max(wt, wb - winH))
                    out.add(alignedRect(l, t, l + winW, t + winH))
                }
                out
            }
        }
    }

    /**
     * Maps a touch/mouse coordinate inside a window's TextureView [viewW × viewH] to the isolated
     * VirtualDisplay pixel space [contentW × contentH].
     */
    fun mapViewToDisplayCoord(coord: Float, viewSize: Int, contentSize: Int): Float {
        if (viewSize <= 1 || contentSize <= 1) return 0f
        val scaled = coord * (contentSize.toFloat() / viewSize.toFloat())
        return min((contentSize - 1).toFloat(), max(0f, scaled))
    }

    /**
     * Geometric Z-order occlusion guard preventing click-through to a lower floating window
     * whenever another visible window with strictly higher [CompositorWindow.zOrder] covers
     * the desktop canvas coordinate ([canvasX], [canvasY]).
     */
    const val PINNED_Z_BOOST = 5000

    fun effectiveZOrder(
        window: CompositorWindow,
        pinnedIds: Set<Int> = OpalWindowPinStore.currentIds(),
    ): Int = if (pinnedIds.contains(window.windowId)) window.zOrder + PINNED_Z_BOOST else window.zOrder

    fun isPointOccludedByHigherWindow(
        target: CompositorWindow,
        canvasX: Float,
        canvasY: Float,
        visibleWindows: List<CompositorWindow>,
    ): Boolean {
        val pinnedIds = OpalWindowPinStore.currentIds()
        val targetZ = effectiveZOrder(target, pinnedIds)
        for (other in visibleWindows) {
            if (other.windowId == target.windowId) continue
            val otherZ = effectiveZOrder(other, pinnedIds)
            if (otherZ > targetZ ||
                (otherZ == targetZ && other.focused && !target.focused)
            ) {
                val f = other.frame
                if (canvasX >= f.left && canvasX <= f.right && canvasY >= f.top && canvasY <= f.bottom) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Smart macOS-style cursor-anchored context menu placement:
     * opens just bottom-right of ([anchorX], [anchorY]), automatically flipping left/up when near
     * right or bottom screen edges (e.g. Dock icons) and clamping cleanly inside the canvas.
     */
    fun computeAnchoredMenuPosition(
        anchorX: Int,
        anchorY: Int,
        menuW: Int,
        menuH: Int,
        canvasW: Int,
        canvasH: Int,
        density: Float,
    ): Pair<Int, Int> {
        val margin = dpToPx(10f, density)
        val topMin = dpToPx(TOP_BAR_DP + 6f, density)
        val cursorPad = dpToPx(4f, density)
        val maxX = max(margin, canvasW - menuW - margin)
        val maxY = max(topMin, canvasH - menuH - margin)

        val preferredX = if (anchorX + cursorPad + menuW + margin <= canvasW) {
            anchorX + cursorPad
        } else {
            anchorX - menuW - cursorPad
        }
        val preferredY = if (anchorY + cursorPad + menuH + margin <= canvasH) {
            anchorY + cursorPad
        } else {
            anchorY - menuH - cursorPad
        }
        return preferredX.coerceIn(margin, maxX) to preferredY.coerceIn(topMin, maxY)
    }

    const val MOUSE_ACTION_DOWN = 0
    const val MOUSE_ACTION_UP = 1
    const val MOUSE_ACTION_MOVE = 2
    const val MOUSE_ACTION_CANCEL = 3
    const val MOUSE_BUTTON_BACK = 8
    const val MOUSE_BUTTON_FORWARD = 16

    /**
     * Returns true when [buttonState] consists solely of auxiliary mouse side-navigation buttons
     * ([MOUSE_BUTTON_BACK] / [MOUSE_BUTTON_FORWARD]) without primary, secondary, tertiary, or
     * other pointer buttons held.
     */
    fun isSideNavigationOnlyMouseButton(buttonState: Int): Boolean {
        val sideNavMask = MOUSE_BUTTON_BACK or MOUSE_BUTTON_FORWARD
        return (buttonState and sideNavMask) != 0 && (buttonState and sideNavMask.inv()) == 0
    }

    /**
     * Decides whether a mouse touch event sequence (`ACTION_DOWN` .. `ACTION_UP/CANCEL`) originated
     * from a side navigation button and must be suppressed from VirtualDisplay / PeerCast pointer
     * injection because side-button navigation is already handled once via `ACTION_BUTTON_PRESS`.
     */
    fun shouldSuppressSideNavigationMouseTouch(
        actionMasked: Int,
        isMouseSource: Boolean,
        buttonState: Int,
        activeSuppression: Boolean,
    ): Boolean {
        if (!isMouseSource) return false
        if (actionMasked == MOUSE_ACTION_DOWN) {
            return isSideNavigationOnlyMouseButton(buttonState)
        }
        if (!activeSuppression) return false
        return actionMasked == MOUSE_ACTION_MOVE ||
            actionMasked == MOUSE_ACTION_UP ||
            actionMasked == MOUSE_ACTION_CANCEL
    }

    /**
     * Per-surface stateful tracker wrapping [shouldSuppressSideNavigationMouseTouch] so DeX window
     * hosts and PeerCast clients share identical side-button touch suppression without duplicating
     * `DOWN -> MOVE -> UP/CANCEL` transition logic.
     */
    class SideNavigationTouchFilter {
        var isSuppressing: Boolean = false
            private set

        fun shouldSuppress(
            actionMasked: Int,
            isMouseSource: Boolean,
            buttonState: Int,
        ): Boolean {
            if (shouldSuppressSideNavigationMouseTouch(actionMasked, isMouseSource, buttonState, isSuppressing)) {
                isSuppressing = when (actionMasked) {
                    MOUSE_ACTION_UP, MOUSE_ACTION_CANCEL -> false
                    else -> true
                }
                return true
            }
            if (actionMasked == MOUSE_ACTION_DOWN ||
                actionMasked == MOUSE_ACTION_UP ||
                actionMasked == MOUSE_ACTION_CANCEL
            ) {
                isSuppressing = false
            }
            return false
        }

        fun reset() {
            isSuppressing = false
        }
    }

    const val KEYCODE_BACK = 4
    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1
    const val SIDE_BACK_KEY_DEDUP_WINDOW_MS = 220L
    const val SIDE_BACK_BUTTON_DEDUP_WINDOW_MS = 80L

    /**
     * Pure-JVM bidirectional deduplicator preventing double-Back when a Bluetooth/USB mouse side
     * button emits both `MotionEvent.ACTION_BUTTON_PRESS(BUTTON_BACK)` and a companion
     * `KeyEvent.KEYCODE_BACK` (Android 16 / ColorOS / HID combo mice), while preserving
     * back-key-only mice and normal physical keyboard Back/Esc presses.
     */
    class SideBackKeyDeduplicator(
        private val keyAfterButtonWindowMs: Long = SIDE_BACK_KEY_DEDUP_WINDOW_MS,
        private val buttonAfterKeyWindowMs: Long = SIDE_BACK_BUTTON_DEDUP_WINDOW_MS,
    ) {
        private var lastSideButtonUptimeMs: Long = -1L
        private var lastBackKeyUptimeMs: Long = -1L
        private var suppressingBackKeyUp: Boolean = false

        @Synchronized
        fun tryClaimSideButtonBack(uptimeMs: Long): Boolean {
            if (lastBackKeyUptimeMs >= 0L && (uptimeMs - lastBackKeyUptimeMs) in 0L..buttonAfterKeyWindowMs) {
                return false
            }
            lastSideButtonUptimeMs = uptimeMs
            return true
        }

        @Synchronized
        fun shouldSuppressDuplicateBackKey(keyCode: Int, action: Int, uptimeMs: Long): Boolean {
            if (keyCode != KEYCODE_BACK) return false
            val withinWindow = lastSideButtonUptimeMs >= 0L &&
                (uptimeMs - lastSideButtonUptimeMs) in 0L..keyAfterButtonWindowMs
            return when (action) {
                KEY_ACTION_DOWN -> {
                    if (withinWindow) {
                        suppressingBackKeyUp = true
                        true
                    } else {
                        suppressingBackKeyUp = false
                        false
                    }
                }
                KEY_ACTION_UP -> {
                    if (suppressingBackKeyUp || withinWindow) {
                        suppressingBackKeyUp = false
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        @Synchronized
        fun recordBackKeyForwarded(uptimeMs: Long) {
            lastBackKeyUptimeMs = uptimeMs
        }

        @Synchronized
        fun reset() {
            lastSideButtonUptimeMs = -1L
            lastBackKeyUptimeMs = -1L
            suppressingBackKeyUp = false
        }
    }
}
