package io.github.lengsukq.opaldesk.protocol

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure client-side compositor geometry helpers (unit-testable with zero Android dependencies):
 * drag clamping, 8-way resize, edge/corner snap detection, multi-window tiling layouts,
 * Z-order occlusion, anchored context menu placement, and mouse side-button filters.
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
        val rawMinW = min(dpToPx(MIN_WIN_W_DP, density), max(220, canvasW - 12))
        val rawMinH = min(dpToPx(MIN_WIN_H_DP, density), max(220, canvasH - dpToPx(TOP_BAR_DP + 20f, density)))
        val minW = max(220, rawMinW and 1.inv())
        val minH = max(220, rawMinH and 1.inv())
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
