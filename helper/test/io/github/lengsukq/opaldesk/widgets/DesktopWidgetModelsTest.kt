package io.github.lengsukq.opaldesk.widgets

import io.github.lengsukq.opaldesk.widgets.renderers.QuickNotesConfig
import io.github.lengsukq.opaldesk.widgets.renderers.TodoChecklistConfig
import io.github.lengsukq.opaldesk.widgets.renderers.TodoItemConfig
import io.github.lengsukq.opaldesk.widgets.renderers.decodeQuickNotesConfig
import io.github.lengsukq.opaldesk.widgets.renderers.decodeTodoChecklistConfig
import io.github.lengsukq.opaldesk.widgets.renderers.encodeQuickNotesConfig
import io.github.lengsukq.opaldesk.widgets.renderers.encodeTodoChecklistConfig
import io.github.lengsukq.opaldesk.widgets.renderers.extractOtpCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWidgetModelsTest {

    @Test
    fun catalogCoversAllSevenWidgetTypesWithValidSpansAndBilingualCopy() {
        assertEquals(8, DesktopWidgetType.entries.size)
        assertEquals(8, DesktopWidgetCatalog.specs.size)
        assertEquals(8, DesktopWidgetCatalog.specs.map { it.type }.toSet().size)

        for (type in DesktopWidgetType.entries) {
            val spec = DesktopWidgetCatalog.specOf(type)
            assertEquals(type, spec.type)
            assertTrue("titleZh blank for $type", spec.titleZh.isNotBlank())
            assertTrue("titleEn blank for $type", spec.titleEn.isNotBlank())
            assertTrue("subtitleZh blank for $type", spec.subtitleZh.isNotBlank())
            assertTrue("subtitleEn blank for $type", spec.subtitleEn.isNotBlank())

            assertTrue("minColSpan < 1 for $type", spec.minColSpan >= 1)
            assertTrue("minRowSpan < 1 for $type", spec.minRowSpan >= 1)
            assertTrue(
                "colSpan order invalid for $type: ${spec.minColSpan}/${spec.defaultColSpan}/${spec.maxColSpan}",
                spec.minColSpan <= spec.defaultColSpan && spec.defaultColSpan <= spec.maxColSpan,
            )
            assertTrue(
                "rowSpan order invalid for $type: ${spec.minRowSpan}/${spec.defaultRowSpan}/${spec.maxRowSpan}",
                spec.minRowSpan <= spec.defaultRowSpan && spec.defaultRowSpan <= spec.maxRowSpan,
            )
            assertTrue(
                "maxColSpan exceeds grid for $type",
                spec.maxColSpan <= DesktopWidgetGridMath.DEFAULT_GRID_COLS,
            )
            assertTrue(
                "maxRowSpan exceeds grid for $type",
                spec.maxRowSpan <= DesktopWidgetGridMath.DEFAULT_GRID_ROWS,
            )
        }

        assertEquals(DesktopWidgetCatalog.specs, DesktopWidgetCatalog.byCategory(null))
        for (category in DesktopWidgetCategory.entries) {
            val filtered = DesktopWidgetCatalog.byCategory(category)
            assertTrue("Category $category should have at least one widget", filtered.isNotEmpty())
            assertTrue(filtered.all { it.category == category })
        }
    }

    @Test
    fun desktopHubSpecAndDefaultLayoutHaveNoOverlap() {
        val hubSpec = DesktopWidgetCatalog.specOf(DesktopWidgetType.DESKTOP_HUB)
        assertEquals(DesktopWidgetCategory.SYSTEM, hubSpec.category)
        assertEquals(6, hubSpec.defaultColSpan)
        assertEquals(3, hubSpec.defaultRowSpan)
        assertEquals(4, hubSpec.minColSpan)
        assertEquals(2, hubSpec.minRowSpan)
        assertEquals(8, hubSpec.maxColSpan)
        assertEquals(4, hubSpec.maxRowSpan)

        val defaults = DesktopWidgetStore.defaultInstances()
        assertTrue(defaults.any { it.type == DesktopWidgetType.DESKTOP_HUB })
        for (i in defaults.indices) {
            for (j in i + 1 until defaults.size) {
                val a = defaults[i]
                val b = defaults[j]
                assertFalse("Default widgets ${a.id} and ${b.id} overlap", overlapsAny(listOf(a), b.col, b.row, b.colSpan, b.rowSpan))
            }
        }
    }

    @Test
    fun clampInstanceEnforcesSpecSpansAndGridBounds() {
        val mediaSpec = DesktopWidgetCatalog.specOf(DesktopWidgetType.MEDIA_PLAYER)
        val underflow = DesktopWidgetGridMath.clampInstance(
            DesktopWidgetInstance(
                id = "w1",
                type = DesktopWidgetType.MEDIA_PLAYER,
                col = -3,
                row = -2,
                colSpan = 1,
                rowSpan = 1,
            ),
        )
        assertEquals(0, underflow.col)
        assertEquals(0, underflow.row)
        assertEquals(mediaSpec.minColSpan, underflow.colSpan)
        assertEquals(mediaSpec.minRowSpan, underflow.rowSpan)

        val overflow = DesktopWidgetGridMath.clampInstance(
            DesktopWidgetInstance(
                id = "w2",
                type = DesktopWidgetType.MEDIA_PLAYER,
                col = 99,
                row = 99,
                colSpan = 20,
                rowSpan = 20,
            ),
        )
        assertEquals(mediaSpec.maxColSpan, overflow.colSpan)
        assertEquals(mediaSpec.maxRowSpan, overflow.rowSpan)
        assertEquals(DesktopWidgetGridMath.DEFAULT_GRID_COLS - mediaSpec.maxColSpan, overflow.col)
        assertEquals(DesktopWidgetGridMath.DEFAULT_GRID_ROWS - mediaSpec.maxRowSpan, overflow.row)
    }

    @Test
    fun snapToGridRoundsAndClampsSafely() {
        assertEquals(
            3 to 2,
            DesktopWidgetGridMath.snapToGrid(
                pixelX = 260f,
                pixelY = 175f,
                cellWidthPx = 100f,
                cellHeightPx = 100f,
                colSpan = 4,
                rowSpan = 2,
            ),
        )
        assertEquals(
            0 to 0,
            DesktopWidgetGridMath.snapToGrid(
                pixelX = -120f,
                pixelY = -50f,
                cellWidthPx = 100f,
                cellHeightPx = 100f,
                colSpan = 4,
                rowSpan = 2,
            ),
        )
        assertEquals(
            8 to 5,
            DesktopWidgetGridMath.snapToGrid(
                pixelX = 5000f,
                pixelY = 5000f,
                cellWidthPx = 100f,
                cellHeightPx = 100f,
                colSpan = 4,
                rowSpan = 2,
            ),
        )
        assertEquals(
            0 to 0,
            DesktopWidgetGridMath.snapToGrid(
                pixelX = 300f,
                pixelY = 300f,
                cellWidthPx = 0f,
                cellHeightPx = -10f,
                colSpan = 4,
                rowSpan = 2,
            ),
        )
    }

    @Test
    fun computeSizeClassMatchesCompactMediumExpandedThresholds() {
        assertEquals(WidgetSizeClass.COMPACT, DesktopWidgetGridMath.computeSizeClass(2, 2))
        assertEquals(WidgetSizeClass.COMPACT, DesktopWidgetGridMath.computeSizeClass(2, 4))
        assertEquals(WidgetSizeClass.COMPACT, DesktopWidgetGridMath.computeSizeClass(5, 1))

        assertEquals(WidgetSizeClass.MEDIUM, DesktopWidgetGridMath.computeSizeClass(3, 2))
        assertEquals(WidgetSizeClass.MEDIUM, DesktopWidgetGridMath.computeSizeClass(4, 2))
        assertEquals(WidgetSizeClass.MEDIUM, DesktopWidgetGridMath.computeSizeClass(3, 3))

        assertEquals(WidgetSizeClass.EXPANDED, DesktopWidgetGridMath.computeSizeClass(4, 3))
        assertEquals(WidgetSizeClass.EXPANDED, DesktopWidgetGridMath.computeSizeClass(6, 2))
        assertEquals(WidgetSizeClass.EXPANDED, DesktopWidgetGridMath.computeSizeClass(6, 4))
    }

    @Test
    fun findNextAvailableSlotAvoidsOccupiedRegionsAndPrefersNonZeroColumn() {
        // Empty grid leaves column 0 free for desktop shortcuts when space allows
        assertEquals(1 to 0, DesktopWidgetGridMath.findNextAvailableSlot(emptyList(), 4, 2))

        val existing = listOf(
            DesktopWidgetInstance("a", DesktopWidgetType.CLOCK_TIMER, col = 1, row = 0, colSpan = 4, rowSpan = 2),
            DesktopWidgetInstance("b", DesktopWidgetType.MEDIA_PLAYER, col = 5, row = 0, colSpan = 4, rowSpan = 2),
        )
        val nextSlot = DesktopWidgetGridMath.findNextAvailableSlot(existing, 4, 2)
        // Columns 1..8 on rows 0..1 are occupied; col 9..12 overflows 12 cols, so wraps to row 2, col 1
        assertEquals(1 to 2, nextSlot)
        assertFalse(overlapsAny(existing, nextSlot.first, nextSlot.second, 4, 2))

        // When all cells are occupied, falls back safely to (0, 0)
        val full = listOf(
            DesktopWidgetInstance("full", DesktopWidgetType.QUICK_NOTES, col = 0, row = 0, colSpan = 12, rowSpan = 7),
        )
        assertEquals(0 to 0, DesktopWidgetGridMath.findNextAvailableSlot(full, 4, 2))
    }

    @Test
    fun serializeAndDeserializeInstancesPreservesEscapedAndMultilineConfigJson() {
        val noteConfig = encodeQuickNotesConfig(
            QuickNotesConfig(
                noteText = "Line 1: \"Quoted\" & C:\\Users\\Opal\nLine 2:\tTab & 中文便签",
                tintIndex = 2,
            ),
        )
        val todoConfig = encodeTodoChecklistConfig(
            TodoChecklistConfig(
                items = listOf(
                    TodoItemConfig("t1", "Review \"PR #42\" \\ build", done = true),
                    TodoItemConfig("t2", "Ship v1.0\nwith tests", done = false),
                ),
            ),
        )
        val original = listOf(
            DesktopWidgetInstance(
                id = "note-1",
                type = DesktopWidgetType.QUICK_NOTES,
                col = 1,
                row = 0,
                colSpan = 4,
                rowSpan = 3,
                configJson = noteConfig,
            ),
            DesktopWidgetInstance(
                id = "todo-1",
                type = DesktopWidgetType.TODO_CHECKLIST,
                col = 5,
                row = 2,
                colSpan = 4,
                rowSpan = 3,
                configJson = todoConfig,
            ),
        )

        val serialized = DesktopWidgetGridMath.serializeInstances(original)
        val restored = DesktopWidgetGridMath.deserializeInstances(serialized)

        assertEquals(original, restored)
        assertEquals(
            "Line 1: \"Quoted\" & C:\\Users\\Opal\nLine 2:\tTab & 中文便签",
            decodeQuickNotesConfig(restored[0].configJson).noteText,
        )
        assertEquals(2, decodeQuickNotesConfig(restored[0].configJson).tintIndex)
        assertEquals(2, decodeTodoChecklistConfig(restored[1].configJson).items.size)
        assertEquals(emptyList<DesktopWidgetInstance>(), DesktopWidgetGridMath.deserializeInstances(""))
        assertEquals(emptyList<DesktopWidgetInstance>(), DesktopWidgetGridMath.deserializeInstances("not-json"))
    }

    @Test
    fun extractOtpCodeHandlesChineseEnglishAndIgnoresIrrelevantNumbers() {
        // Chinese OTPs (4 to 8 digits, keyword before & after)
        assertEquals("482910", extractOtpCode("【云服务】您的验证码是 482910，5分钟内有效。"))
        assertEquals("9041", extractOtpCode("【某银行】9041 为您的动态码，请勿告知他人。"))
        assertEquals("88776655", extractOtpCode("登录安全码：88776655，切勿泄露"))
        assertEquals("13579", extractOtpCode("本次认证码 13579 用于绑定设备"))

        // English OTPs (4 to 8 digits)
        assertEquals("654321", extractOtpCode("Your verification code is 654321. It expires in 10 minutes."))
        assertEquals("123456", extractOtpCode("123456 is your Google security code"))
        assertEquals("9876", extractOtpCode("Use OTP: 9876 to sign in"))
        assertEquals("44556677", extractOtpCode("Your one-time passcode is 44556677"))

        // Irrelevant numbers should not trigger false positives
        assertNull(extractOtpCode(""))
        assertNull(extractOtpCode("订单号 20261128 已发货，预计明日送达"))
        assertNull(extractOtpCode("明天下午 14:30 在 302 会议室开会"))
        assertNull(extractOtpCode("您本次消费 1280 元，当前余额 5432 元"))
        assertNull(extractOtpCode("2026 年度工作总结文档已同步"))
        assertNull(extractOtpCode("验证码已过期，2026 年请重新获取"))
    }

    @Test
    fun quickNotesAndTodoChecklistConfigsRoundTripCleanly() {
        val noteInput = QuickNotesConfig(
            noteText = "第一行\n第二行 \"引号\" \\反斜杠\t制表符",
            tintIndex = 99,
        )
        val noteDecoded = decodeQuickNotesConfig(encodeQuickNotesConfig(noteInput))
        assertEquals(noteInput.noteText, noteDecoded.noteText)
        assertEquals(3, noteDecoded.tintIndex)
        assertEquals(QuickNotesConfig(), decodeQuickNotesConfig(""))

        val todoInput = TodoChecklistConfig(
            items = listOf(
                TodoItemConfig(id = "item_1", text = "完成 \"单元测试\" & {校验}", done = true),
                TodoItemConfig(id = "item_2", text = "多行任务\n含路径 C:\\temp", done = false),
            ),
        )
        val todoDecoded = decodeTodoChecklistConfig(encodeTodoChecklistConfig(todoInput))
        assertEquals(todoInput, todoDecoded)
        assertEquals(TodoChecklistConfig(), decodeTodoChecklistConfig(""))
    }

    private fun overlapsAny(
        existing: List<DesktopWidgetInstance>,
        col: Int,
        row: Int,
        colSpan: Int,
        rowSpan: Int,
    ): Boolean = existing.any { item ->
        col < item.col + item.colSpan &&
            col + colSpan > item.col &&
            row < item.row + item.rowSpan &&
            row + rowSpan > item.row
    }
}
