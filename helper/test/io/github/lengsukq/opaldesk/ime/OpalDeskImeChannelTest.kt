package io.github.lengsukq.opaldesk.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpalDeskImeChannelTest {

    private class FakeConnectionAdapter : ImeConnectionAdapter {
        val log = ArrayList<String>()
        var batchDepth = 0
        var maxBatchDepth = 0
        var failCommit = false

        override fun beginBatchEdit(): Boolean {
            batchDepth++
            if (batchDepth > maxBatchDepth) maxBatchDepth = batchDepth
            log.add("BEGIN_BATCH")
            return true
        }

        override fun endBatchEdit(): Boolean {
            batchDepth--
            log.add("END_BATCH")
            return true
        }

        override fun commitText(text: String, newCursorPosition: Int): Boolean {
            if (failCommit) return false
            log.add("COMMIT:$text@$newCursorPosition")
            return true
        }

        override fun setComposingText(text: String, newCursorPosition: Int): Boolean {
            log.add("COMPOSE:$text@$newCursorPosition")
            return true
        }

        override fun finishComposingText(): Boolean {
            log.add("FINISH_COMPOSE")
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            log.add("DEL:$beforeLength,$afterLength")
            return true
        }

        override fun sendEnterKey(): Boolean {
            log.add("ENTER")
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            log.add("ACTION:$editorAction")
            return true
        }
    }

    @Test
    fun `rejects commit when unbound, inactive, or attached to dummy TYPE_NULL window`() {
        val core = OpalDeskImeChannelCore()
        val conn = FakeConnectionAdapter()

        assertFalse(core.commitTextDirect("你好"))

        core.onServiceAttached(conn)
        assertFalse(core.commitTextDirect("你好"))

        // Non-editable window reports inputType=0 and imeOptions=0
        core.onStartInput("com.tencent.mm", inputType = 0, imeOptions = 0, fieldId = 0)
        assertFalse(core.currentEditorSnapshot().acceptingText)
        assertFalse(core.commitTextDirect("你好"))
        assertTrue(conn.log.isEmpty())

        // Terminal emulators (e.g. Termux) set inputType=0 with non-zero imeOptions
        core.onStartInput("com.termux", inputType = 0, imeOptions = 0x02000000, fieldId = 1)
        assertTrue(core.currentEditorSnapshot().acceptingText)
        assertTrue(core.commitTextDirect("ls -la"))
        assertEquals(listOf("COMMIT:ls -la@1"), conn.log)
    }

    @Test
    fun `commits single-line CJK and multi-line text cleanly with single-line vs multi-line handling`() {
        val core = OpalDeskImeChannelCore()
        val conn = FakeConnectionAdapter()
        core.onServiceAttached(conn)

        // Single-line editor: newlines become explicit ENTER key taps inside a batch edit
        core.onStartInput("com.android.chrome", inputType = 0x00000001, imeOptions = 0, fieldId = 2)
        assertTrue(core.commitTextDirect("你好世界"))
        assertEquals(listOf("COMMIT:你好世界@1"), conn.log)

        conn.log.clear()
        assertTrue(core.commitTextDirect("第一行\r\n第二行\n"))
        assertEquals(
            listOf("BEGIN_BATCH", "COMMIT:第一行@1", "ENTER", "COMMIT:第二行@1", "ENTER", "END_BATCH"),
            conn.log,
        )
        assertEquals(0, conn.batchDepth)

        // Multi-line editor: preserves literal '\n' commits inside a batch edit
        conn.log.clear()
        core.onStartInput(
            "com.tencent.mm",
            inputType = 0x00000001 or OpalDeskImeChannelCore.TYPE_TEXT_FLAG_MULTI_LINE,
            imeOptions = 0,
            fieldId = 3,
        )
        assertTrue(core.commitTextDirect("行1\n行2"))
        assertEquals(
            listOf("BEGIN_BATCH", "COMMIT:行1@1", "COMMIT:\n@1", "COMMIT:行2@1", "END_BATCH"),
            conn.log,
        )
    }

    @Test
    fun `enforces self-package loop guard for local commits while permitting remote cast commits`() {
        val core = OpalDeskImeChannelCore()
        val conn = FakeConnectionAdapter()
        core.onServiceAttached(conn)
        core.onStartInput("io.github.lengsukq.opaldesk", inputType = 1, imeOptions = 0, fieldId = 10)

        // Local trackpad / NativeImeBridge path must NOT loop text back into OpalDesk itself
        assertFalse(
            core.commitTextDirect(
                text = "搜索词",
                rejectSelfPackage = true,
                selfPackageName = "io.github.lengsukq.opaldesk",
            ),
        )
        assertTrue(conn.log.isEmpty())

        // Remote cast (Mac/Windows/Web) CAN commit text into OpalDesk's App Drawer search box
        assertTrue(
            core.commitTextDirect(
                text = "微信",
                rejectSelfPackage = false,
                selfPackageName = "io.github.lengsukq.opaldesk",
            ),
        )
        assertEquals(listOf("COMMIT:微信@1"), conn.log)
    }

    @Test
    fun `tracks composing, delete, cursor anchor, and resets cleanly on finish or detach`() {
        val core = OpalDeskImeChannelCore()
        val conn = FakeConnectionAdapter()
        core.onServiceAttached(conn)
        core.onStartInput("com.tencent.mm", inputType = 1, imeOptions = 4, fieldId = 7)

        assertTrue(core.setComposingTextDirect("nihao"))
        assertTrue(core.finishComposingTextDirect())
        assertTrue(core.deleteSurroundingTextDirect(2, 0))
        assertTrue(core.performEditorActionDirect(4))
        assertEquals(
            listOf("COMPOSE:nihao@1", "FINISH_COMPOSE", "DEL:2,0", "ACTION:4"),
            conn.log,
        )

        core.onCursorAnchorUpdated(
            ImeCursorAnchorSnapshot(
                valid = true,
                insertionMarkerHorizontal = 320f,
                insertionMarkerTop = 180f,
                insertionMarkerBaseline = 204f,
                insertionMarkerBottom = 210f,
            ),
        )
        assertTrue(core.currentCursorAnchor().valid)
        assertEquals(320f, core.currentCursorAnchor().insertionMarkerHorizontal, 0.01f)

        core.onFinishInput()
        assertFalse(core.currentEditorSnapshot().inputActive)
        assertFalse(core.currentCursorAnchor().valid)
        assertTrue(core.currentEditorSnapshot().serviceBound)

        core.onServiceDetached(conn)
        assertFalse(core.currentEditorSnapshot().serviceBound)
    }
}
