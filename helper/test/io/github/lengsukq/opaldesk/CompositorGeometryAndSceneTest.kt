package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.IpcTrustVerifier
import io.github.lengsukq.opaldesk.protocol.ResizeEdge
import io.github.lengsukq.opaldesk.protocol.WindowRect
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositorGeometryAndSceneTest {

    @Test
    fun clampMoveKeepsHeaderReachableWithinCanvasBounds() {
        val base = WindowRect(left = 200, top = 120, right = 1000, bottom = 720)
        // Dragging far off-screen top-left clamps top below TopBar and keeps 96dp horizontal grip
        val clampedTl = CompositorGeometry.clampMove(base, dx = -5000, dy = -5000, canvasW = 1920, canvasH = 1200, density = 2f)
        assertEquals(800, clampedTl.width)
        assertEquals(600, clampedTl.height)
        assertEquals(CompositorGeometry.dpToPx(CompositorGeometry.TOP_BAR_DP, 2f), clampedTl.top)
        assertTrue(clampedTl.right >= CompositorGeometry.dpToPx(96f, 2f))

        // Dragging far bottom-right keeps header visible above bottom edge
        val clampedBr = CompositorGeometry.clampMove(base, dx = 5000, dy = 5000, canvasW = 1920, canvasH = 1200, density = 2f)
        assertTrue(clampedBr.left <= 1920 - CompositorGeometry.dpToPx(96f, 2f))
        assertTrue(clampedBr.top < 1200)
    }

    @Test
    fun computeResizeEnforcesMinimumSizeAndEvenDimensionsAcrossAllEdges() {
        val base = WindowRect(left = 300, top = 160, right = 1100, bottom = 760)
        val grownSe = CompositorGeometry.computeResize(
            base = base,
            edge = ResizeEdge.SE,
            dx = 151,
            dy = 83,
            canvasW = 1920,
            canvasH = 1200,
            density = 1.5f,
        )
        assertEquals(0, grownSe.width % 2)
        assertEquals(0, grownSe.height % 2)
        assertEquals(300, grownSe.left)
        assertEquals(160, grownSe.top)
        assertTrue(grownSe.width >= 950)
        assertTrue(grownSe.height >= 682)

        // Extreme inward shrink from NW clamps cleanly at MIN_WIN_W_DP / MIN_WIN_H_DP
        val shrunkNw = CompositorGeometry.computeResize(
            base = base,
            edge = ResizeEdge.NW,
            dx = 2000,
            dy = 2000,
            canvasW = 1920,
            canvasH = 1200,
            density = 2f,
        )
        assertEquals(CompositorGeometry.dpToPx(CompositorGeometry.MIN_WIN_W_DP, 2f), shrunkNw.width)
        assertEquals(CompositorGeometry.dpToPx(CompositorGeometry.MIN_WIN_H_DP, 2f), shrunkNw.height)
    }

    @Test
    fun computeResizeAndCascadeDoNotCrashWhenWindowNearBottomOrCanvasCompact() {
        // Reproduces OPALDESK-8: dragged near bottom on compact display so (base.top + minH) > maxBottom
        val density = 2.75f
        val canvasW = 1080
        val canvasH = 714
        val movedNearBottom = CompositorGeometry.clampMove(
            WindowRect(left = 100, top = 200, right = 900, bottom = 680),
            dx = 0,
            dy = 5000,
            canvasW = canvasW,
            canvasH = canvasH,
            density = density,
        )
        for (edge in listOf(ResizeEdge.S, ResizeEdge.SE, ResizeEdge.SW)) {
            val resized = CompositorGeometry.computeResize(
                base = movedNearBottom,
                edge = edge,
                dx = -40,
                dy = 120,
                canvasW = canvasW,
                canvasH = canvasH,
                density = density,
            )
            assertEquals(0, resized.width % 2)
            assertEquals(0, resized.height % 2)
            assertTrue(resized.bottom > resized.top)
        }

        // Window partially dragged off-screen left keeps its anchor/min width without throwing
        val offscreenLeft = CompositorGeometry.clampMove(
            WindowRect(left = 100, top = 160, right = 700, bottom = 600),
            dx = -500,
            dy = 0,
            canvasW = canvasW,
            canvasH = 1200,
            density = 2f,
        )
        for (edge in listOf(ResizeEdge.W, ResizeEdge.NW, ResizeEdge.SW)) {
            val resized = CompositorGeometry.computeResize(
                base = offscreenLeft,
                edge = edge,
                dx = -30,
                dy = 0,
                canvasW = canvasW,
                canvasH = 1200,
                density = 2f,
            )
            assertEquals(0, resized.width % 2)
            assertEquals(0, resized.height % 2)
            assertTrue(resized.right > resized.left)
        }

        // Compact canvas must not crash CASCADE coerceIn when workW < 280 or workH < 260
        val compactCascade = CompositorGeometry.computeTilingFrames(
            WindowTilingLayout.CASCADE,
            windowCount = 3,
            canvasW = 240,
            canvasH = 300,
            density = 2f,
        )
        assertEquals(3, compactCascade.size)
        compactCascade.forEach { rect ->
            assertEquals(0, rect.width % 2)
            assertEquals(0, rect.height % 2)
            assertTrue(rect.right > rect.left)
            assertTrue(rect.bottom > rect.top)
        }
    }

    @Test
    fun detectSnapPresentationIdentifiesEdgesCornersAndTopMaximize() {
        val w = 1920
        val h = 1200
        val d = 1.5f

        assertEquals(BridgeProtocol.Presentation.SNAP_TL, CompositorGeometry.detectSnapPresentation(8, 20, w, h, d))
        assertEquals(BridgeProtocol.Presentation.SNAP_BL, CompositorGeometry.detectSnapPresentation(8, h - 20, w, h, d))
        assertEquals(BridgeProtocol.Presentation.SNAP_TR, CompositorGeometry.detectSnapPresentation(w - 8, 20, w, h, d))
        assertEquals(BridgeProtocol.Presentation.SNAP_BR, CompositorGeometry.detectSnapPresentation(w - 8, h - 20, w, h, d))
        assertEquals(BridgeProtocol.Presentation.SNAP_LEFT, CompositorGeometry.detectSnapPresentation(10, 600, w, h, d))
        assertEquals(BridgeProtocol.Presentation.SNAP_RIGHT, CompositorGeometry.detectSnapPresentation(w - 10, 600, w, h, d))
        assertEquals(BridgeProtocol.Presentation.MAXIMIZED, CompositorGeometry.detectSnapPresentation(960, 12, w, h, d))
        assertNull(CompositorGeometry.detectSnapPresentation(960, 600, w, h, d))
    }

    @Test
    fun mapViewToDisplayCoordScalesAndClampsAccurately() {
        assertEquals(0f, CompositorGeometry.mapViewToDisplayCoord(-25f, 800, 1600), 0.01f)
        assertEquals(800f, CompositorGeometry.mapViewToDisplayCoord(400f, 800, 1600), 0.01f)
        assertEquals(1599f, CompositorGeometry.mapViewToDisplayCoord(9999f, 800, 1600), 0.01f)
    }

    @Test
    fun computeTilingFramesProducesNonOverlappingEvenAlignedLayouts() {
        val w = 2560
        val h = 1440
        val d = 1.4f

        // 3-column split
        val cols3 = CompositorGeometry.computeTilingFrames(WindowTilingLayout.SPLIT_3_COLS, 3, w, h, d)
        assertEquals(3, cols3.size)
        for (i in cols3.indices) {
            assertEquals(0, cols3[i].width % 2)
            assertEquals(0, cols3[i].height % 2)
            assertTrue(cols3[i].width > 750)
            if (i > 0) assertTrue("Column $i overlaps previous", cols3[i].left > cols3[i - 1].right)
        }

        // 4-column split on 2K monitor
        val cols4 = CompositorGeometry.computeTilingFrames(WindowTilingLayout.SPLIT_4_COLS, 4, w, h, d)
        assertEquals(4, cols4.size)
        for (i in cols4.indices) {
            assertEquals(0, cols4[i].width % 2)
            assertTrue(cols4[i].width > 550)
            if (i > 0) assertTrue("Column $i overlaps previous", cols4[i].left > cols4[i - 1].right)
        }

        // Main + 2-stack (1 left + 2 stacked on right)
        val stack3 = CompositorGeometry.computeTilingFrames(WindowTilingLayout.SPLIT_3_MAIN_STACK, 3, w, h, d)
        assertEquals(3, stack3.size)
        assertTrue(stack3[0].width > stack3[1].width)
        assertTrue(stack3[1].left > stack3[0].right)
        assertTrue(stack3[2].top > stack3[1].bottom)

        // 2x2 grid
        val grid4 = CompositorGeometry.computeTilingFrames(WindowTilingLayout.SPLIT_4_GRID, 4, w, h, d)
        assertEquals(4, grid4.size)
        assertTrue(grid4[1].left > grid4[0].right)
        assertTrue(grid4[2].top > grid4[0].bottom)
        assertTrue(grid4[3].left > grid4[2].right)

        // 5-column side-by-side split
        val cols5 = CompositorGeometry.computeTilingFrames(WindowTilingLayout.SPLIT_5_COLS, 5, w, h, d)
        assertEquals(5, cols5.size)
        for (i in cols5.indices) {
            assertEquals(0, cols5[i].width % 2)
            assertEquals(0, cols5[i].height % 2)
            assertTrue(cols5[i].right <= w)
            if (i > 0) assertTrue("Column $i overlaps previous", cols5[i].left > cols5[i - 1].right)
        }

        // 6-pane 3x2 grid (3 top, 3 bottom)
        val grid6 = CompositorGeometry.computeTilingFrames(WindowTilingLayout.SPLIT_6_GRID, 6, w, h, d)
        assertEquals(6, grid6.size)
        assertTrue(grid6[1].left > grid6[0].right)
        assertTrue(grid6[2].left > grid6[1].right)
        assertTrue(grid6[3].top > grid6[0].bottom)
        assertTrue(grid6[4].left > grid6[3].right)
        assertTrue(grid6[5].left > grid6[4].right)
        assertTrue(grid6.all { it.width % 2 == 0 && it.height % 2 == 0 && it.right <= w && it.bottom <= h })

        // 8-pane 4x2 grid (4 top, 4 bottom)
        val grid8 = CompositorGeometry.computeTilingFrames(WindowTilingLayout.SPLIT_8_GRID, 8, w, h, d)
        assertEquals(8, grid8.size)
        for (c in 1..3) {
            assertTrue(grid8[c].left > grid8[c - 1].right)
            assertTrue(grid8[4 + c].left > grid8[4 + c - 1].right)
        }
        assertTrue(grid8[4].top > grid8[0].bottom)
        assertTrue(grid8.all { it.width % 2 == 0 && it.height % 2 == 0 && it.right <= w && it.bottom <= h })

        // Fewer windows than layout maxSlots degrades cleanly
        val partial = CompositorGeometry.computeTilingFrames(WindowTilingLayout.SPLIT_4_COLS, 2, w, h, d)
        assertEquals(2, partial.size)
        assertTrue(partial[1].left > partial[0].right)

        // Portrait phone canvas stacks vertically cleanly without overlapping
        val pw = 1080
        val ph = 2400
        val pd = 2.75f
        val portrait2 = CompositorGeometry.computeTilingFrames(WindowTilingLayout.SPLIT_2_COLS, 2, pw, ph, pd)
        assertEquals(2, portrait2.size)
        assertTrue("Portrait 2-split should stack vertically", portrait2[1].top > portrait2[0].bottom)
        assertEquals(0, portrait2[0].width % 2)
        assertEquals(0, portrait2[0].height % 2)

        val portraitResize = CompositorGeometry.computeResize(
            base = WindowRect(20, 120, 1000, 1400),
            edge = ResizeEdge.SE,
            dx = -900,
            dy = -1200,
            canvasW = pw,
            canvasH = ph,
            density = pd,
        )
        assertTrue(portraitResize.width in 240..pw)
        assertTrue(portraitResize.height in 240..ph)
    }

    @Test
    fun pointOccludedByHigherWindowPreventsClickThrough() {
        val back = sampleWin(id = 1, state = BridgeProtocol.WinState.LIVE, z = 2, focused = false)
            .copy(frame = WindowRect(100, 100, 900, 700))
        val front = sampleWin(id = 2, state = BridgeProtocol.WinState.LIVE, z = 5, focused = true)
            .copy(frame = WindowRect(400, 200, 1200, 800))
        val visible = listOf(back, front)

        // Inside overlap -> back window IS occluded by front; front is NOT occluded
        assertTrue(CompositorGeometry.isPointOccludedByHigherWindow(back, 550f, 350f, visible))
        assertFalse(CompositorGeometry.isPointOccludedByHigherWindow(front, 550f, 350f, visible))

        // Inside back window's exposed non-overlapping area -> NOT occluded
        assertFalse(CompositorGeometry.isPointOccludedByHigherWindow(back, 200f, 350f, visible))
    }

    @Test
    fun anchoredContextMenuFollowsCursorAndFlipsNearEdges() {
        val w = 2560
        val h = 1440
        val d = 1.4f
        val menuW = 340
        val menuH = 400

        // Center click -> opens just bottom-right of cursor
        val (cx, cy) = CompositorGeometry.computeAnchoredMenuPosition(800, 500, menuW, menuH, w, h, d)
        assertTrue(cx in 801..820)
        assertTrue(cy in 501..520)

        // Bottom Dock click -> flips UP above cursor instead of clipping off bottom
        val (dx, dy) = CompositorGeometry.computeAnchoredMenuPosition(1200, 1385, menuW, menuH, w, h, d)
        assertTrue(dx in 1201..1220)
        assertTrue("Expected menu above dock cursor, got $dy", dy < 1385 - menuH)

        // Far right edge click -> flips LEFT of cursor
        val (rx, _) = CompositorGeometry.computeAnchoredMenuPosition(2500, 400, menuW, menuH, w, h, d)
        assertTrue("Expected menu left of right-edge cursor, got $rx", rx < 2500 - menuW)
    }

    @Test
    fun compositorSceneZOrderFilteringAndIpcTrustVerification() {
        val wMin = sampleWin(id = 1, state = BridgeProtocol.WinState.MINIMIZED, z = 10, focused = false)
        val wBack = sampleWin(id = 2, state = BridgeProtocol.WinState.HIBERNATED, z = 2, focused = false)
        val wFront = sampleWin(id = 3, state = BridgeProtocol.WinState.LIVE, z = 5, focused = true)

        val scene = CompositorScene(seq = 7L, windows = listOf(wFront, wMin, wBack))
        assertEquals(listOf(2, 3), scene.visibleWindows.map { it.windowId })
        assertEquals(3, scene.focusedWindow?.windowId)

        assertTrue(IpcTrustVerifier.isTrustedDaemonUid(0))
        assertTrue(IpcTrustVerifier.isTrustedDaemonUid(1000))
        assertTrue(IpcTrustVerifier.isTrustedDaemonUid(2000))
        assertFalse(IpcTrustVerifier.isTrustedDaemonUid(10245))
        assertTrue(IpcTrustVerifier.isTrustedCallerUid(10245, selfUid = 10245))

        val st = DaemonStatus(connected = true, daemonUid = 2000, injectStatus = BridgeProtocol.InputBackend.STATUS_UHID)
        assertTrue(st.injectBackendLabel.contains("uhid"))
        assertTrue(st.privilegeSummary.contains("2000"))
    }

    @Test
    fun surfaceGenerationGatePreventsCrossHolderDetachClobberAndStaleAttach() {
        val gate = io.github.lengsukq.opaldesk.compositor.WindowSurfaceGenerationGate()

        // Holder 1 attaches window 42, then Holder 2 remounts and attaches window 42 before Holder 1 disposes
        val gen1 = gate.onAttachRequested(windowId = 42, holderToken = 101L)
        assertTrue(gate.isAttachStillValid(windowId = 42, holderToken = 101L, opGen = gen1))

        val gen2 = gate.onAttachRequested(windowId = 42, holderToken = 102L)
        assertFalse("Superseded holder attach must be invalidated", gate.isAttachStillValid(42, 101L, gen1))
        assertTrue(gate.isAttachStillValid(42, 102L, gen2))

        // Holder 1's delayed onTextureDestroyed & onDispose MUST NOT detach Holder 2's live surface!
        assertFalse(gate.onTextureDestroyed(windowId = 42, holderToken = 101L))
        assertTrue(gate.isAttachStillValid(42, 102L, gen2))
        assertFalse("Stale holder onDispose must not detach active holder", gate.onHolderDisposed(42, 101L))
        assertTrue(gate.isAttachStillValid(42, 102L, gen2))

        // Normal unmount when onTextureDestroyed fires BEFORE onDispose:
        // onTextureDestroyed claims the single daemon detach (before st.release()), and subsequent
        // onHolderDisposed returns false so daemon detach is never sent twice!
        assertTrue("onTextureDestroyed must detach producer before st.release()", gate.onTextureDestroyed(42, 102L))
        assertFalse(gate.isAttachStillValid(42, 102L, gen2))
        assertFalse("onHolderDisposed must not duplicate detach after onTextureDestroyed", gate.onHolderDisposed(42, 102L))

        // Unmount when onHolderDisposed fires BEFORE onTextureDestroyed:
        val gen3 = gate.onAttachRequested(windowId = 42, holderToken = 103L)
        assertTrue(gate.isAttachStillValid(42, 103L, gen3))
        assertTrue("onHolderDisposed must detach when texture not yet destroyed", gate.onHolderDisposed(42, 103L))
        assertFalse("Subsequent onTextureDestroyed must not duplicate detach", gate.onTextureDestroyed(42, 103L))
    }

    @Test
    fun sideNavigationMouseTouchFilterSuppressesSideButtonClickSequenceOnly() {
        val filter = CompositorGeometry.SideNavigationTouchFilter()

        // Mouse BUTTON_BACK press-drag-release sequence is suppressed across DOWN -> MOVE -> UP
        assertTrue(filter.shouldSuppress(CompositorGeometry.MOUSE_ACTION_DOWN, true, CompositorGeometry.MOUSE_BUTTON_BACK))
        assertTrue(filter.isSuppressing)
        assertTrue(filter.shouldSuppress(CompositorGeometry.MOUSE_ACTION_MOVE, true, CompositorGeometry.MOUSE_BUTTON_BACK))
        assertTrue(filter.shouldSuppress(CompositorGeometry.MOUSE_ACTION_UP, true, 0))
        assertFalse(filter.isSuppressing)

        // Mouse BUTTON_FORWARD followed by CANCEL resets cleanly
        assertTrue(filter.shouldSuppress(CompositorGeometry.MOUSE_ACTION_DOWN, true, CompositorGeometry.MOUSE_BUTTON_FORWARD))
        assertTrue(filter.shouldSuppress(CompositorGeometry.MOUSE_ACTION_CANCEL, true, 0))
        assertFalse(filter.isSuppressing)

        // Hover/scroll events pass through without starting suppression; primary & secondary clicks stay live
        assertFalse(filter.shouldSuppress(7 /* ACTION_HOVER_MOVE */, true, CompositorGeometry.MOUSE_BUTTON_BACK))
        assertFalse(filter.shouldSuppress(8 /* ACTION_SCROLL */, true, 0))
        assertFalse(filter.shouldSuppress(CompositorGeometry.MOUSE_ACTION_DOWN, true, 1 /* BUTTON_PRIMARY */))
        assertFalse(filter.shouldSuppress(CompositorGeometry.MOUSE_ACTION_DOWN, true, 2 /* BUTTON_SECONDARY */))
        assertFalse(filter.shouldSuppress(CompositorGeometry.MOUSE_ACTION_DOWN, true, 1 or CompositorGeometry.MOUSE_BUTTON_BACK))
        assertFalse(filter.shouldSuppress(CompositorGeometry.MOUSE_ACTION_DOWN, false, CompositorGeometry.MOUSE_BUTTON_BACK))
    }

    private fun sampleWin(id: Int, state: String, z: Int, focused: Boolean) = CompositorWindow(
        windowId = id,
        pkg = "com.example.w$id",
        activityClass = "",
        title = "Win $id",
        profileName = "DESKTOP",
        profileCode = BridgeProtocol.Profile.DESKTOP,
        state = state,
        presentation = BridgeProtocol.Presentation.FLOATING,
        frame = WindowRect(100, 100, 900, 700),
        contentWidth = 800,
        contentHeight = 560,
        contentDpi = 200,
        displayId = if (state == BridgeProtocol.WinState.LIVE) 10 + id else -1,
        taskId = 100 + id,
        zOrder = z,
        focused = focused,
        snapshotVersion = 1L,
        hasSnapshot = state != BridgeProtocol.WinState.LIVE,
    )
}
