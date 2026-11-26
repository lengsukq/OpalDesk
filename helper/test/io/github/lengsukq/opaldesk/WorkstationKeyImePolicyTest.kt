package io.github.lengsukq.opaldesk

import android.view.InputDevice
import android.view.KeyEvent
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.GlobalShortcutAction
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import io.github.lengsukq.opaldesk.ui.WorkstationShortcutCatalog
import io.github.lengsukq.opaldesk.ui.WorkstationShortcutId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkstationKeyImePolicyTest {

    @Test
    fun `recognizes dedicated IME language switch keys`() {
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_LANGUAGE_SWITCH, 0))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SWITCH_CHARSET, 0))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, 0))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_KANA, 0))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_EISU, 0))
    }

    @Test
    fun `recognizes standard Ctrl Shift Meta plus Space IME switch chords`() {
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_META_ON))
    }

    @Test
    fun `does not misclassify plain space or Alt Space or normal letters`() {
        assertFalse(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, 0))
        assertFalse(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON))
        assertFalse(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON))
        assertFalse(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_ENTER, 0))
    }

    @Test
    fun `distinguishes real physical keyboards from virtual and OpalDesk UHID keyboards`() {
        assertTrue(
            WorkstationKeyDispatcher.isPhysicalKeyboardDevice(
                deviceId = 14,
                name = "Keychron K3 Pro",
                isVirtual = false,
                isExternal = true,
                keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            ),
        )
        assertFalse(
            WorkstationKeyDispatcher.isPhysicalKeyboardDevice(
                deviceId = -1,
                name = "Virtual",
                isVirtual = true,
                isExternal = false,
                keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            ),
        )
        assertFalse(
            WorkstationKeyDispatcher.isPhysicalKeyboardDevice(
                deviceId = 19,
                name = "OpalDesk Virtual Keyboard 5",
                isVirtual = false,
                isExternal = true,
                keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `forwards physical IME switch shortcuts to focused window when host text box and soft IME are inactive`() {
        assertTrue(
            WorkstationKeyDispatcher.shouldForwardImeSwitchToWindow(
                localComposeTextFieldFocused = false,
                hostSoftImeVisible = false,
                isPhysicalKeyEvent = true,
            ),
        )
        assertFalse(
            WorkstationKeyDispatcher.shouldForwardImeSwitchToWindow(
                localComposeTextFieldFocused = true,
                hostSoftImeVisible = false,
                isPhysicalKeyEvent = true,
            ),
        )
        assertFalse(
            WorkstationKeyDispatcher.shouldForwardImeSwitchToWindow(
                localComposeTextFieldFocused = false,
                hostSoftImeVisible = true,
                isPhysicalKeyEvent = true,
            ),
        )
        assertFalse(
            WorkstationKeyDispatcher.shouldForwardImeSwitchToWindow(
                localComposeTextFieldFocused = false,
                hostSoftImeVisible = false,
                isPhysicalKeyEvent = false,
            ),
        )
    }

    @Test
    fun `resolves all workstation shortcuts when enabled and respects master off switch`() {
        assertEquals(
            GlobalShortcutAction.SwitchNextWindow,
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_TAB,
                metaState = KeyEvent.META_ALT_ON,
                hasFocusedWindow = false,
                focusedIsFullscreen = false,
                globalEnabled = true,
                disabledIds = emptySet(),
            ),
        )
        assertEquals(
            GlobalShortcutAction.CloseFocusedWindow,
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_W,
                metaState = KeyEvent.META_CTRL_ON,
                hasFocusedWindow = true,
                focusedIsFullscreen = false,
                globalEnabled = true,
                disabledIds = emptySet(),
            ),
        )
        assertEquals(
            GlobalShortcutAction.ToggleFullscreen,
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_ENTER,
                metaState = KeyEvent.META_ALT_ON,
                hasFocusedWindow = true,
                focusedIsFullscreen = false,
                globalEnabled = true,
                disabledIds = emptySet(),
            ),
        )
        assertEquals(
            GlobalShortcutAction.ExitFullscreen,
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_ESCAPE,
                metaState = 0,
                hasFocusedWindow = true,
                focusedIsFullscreen = true,
                globalEnabled = true,
                disabledIds = emptySet(),
            ),
        )
        assertNull(
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_ESCAPE,
                metaState = 0,
                hasFocusedWindow = true,
                focusedIsFullscreen = false,
                globalEnabled = true,
                disabledIds = emptySet(),
            ),
        )
        assertEquals(
            GlobalShortcutAction.ApplyTiling(WindowTilingLayout.SPLIT_4_GRID),
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_G,
                metaState = KeyEvent.META_ALT_ON,
                hasFocusedWindow = true,
                focusedIsFullscreen = false,
                globalEnabled = true,
                disabledIds = emptySet(),
            ),
        )

        // Master switch OFF disables every global shortcut so keys flow straight to the focused app.
        assertNull(
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_TAB,
                metaState = KeyEvent.META_ALT_ON,
                hasFocusedWindow = true,
                focusedIsFullscreen = true,
                globalEnabled = false,
                disabledIds = emptySet(),
            ),
        )
        assertNull(
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_W,
                metaState = KeyEvent.META_CTRL_ON,
                hasFocusedWindow = true,
                focusedIsFullscreen = true,
                globalEnabled = false,
                disabledIds = emptySet(),
            ),
        )
        assertNull(
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_2,
                metaState = KeyEvent.META_ALT_ON,
                hasFocusedWindow = true,
                focusedIsFullscreen = true,
                globalEnabled = false,
                disabledIds = emptySet(),
            ),
        )
        assertNull(
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_ESCAPE,
                metaState = 0,
                hasFocusedWindow = true,
                focusedIsFullscreen = true,
                globalEnabled = false,
                disabledIds = emptySet(),
            ),
        )
    }

    @Test
    fun `disabling individual shortcuts keeps other shortcuts active and serializes cleanly`() {
        val disabled = setOf(
            WorkstationShortcutId.CLOSE_WINDOW.prefKey,
            WorkstationShortcutId.WINDOW_TILING.prefKey,
        )
        assertNull(
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_W,
                metaState = KeyEvent.META_CTRL_ON,
                hasFocusedWindow = true,
                focusedIsFullscreen = false,
                globalEnabled = true,
                disabledIds = disabled,
            ),
        )
        assertNull(
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_3,
                metaState = KeyEvent.META_ALT_ON,
                hasFocusedWindow = true,
                focusedIsFullscreen = false,
                globalEnabled = true,
                disabledIds = disabled,
            ),
        )
        assertEquals(
            GlobalShortcutAction.SwitchNextWindow,
            WorkstationShortcutCatalog.resolveGlobalShortcutAction(
                keyCode = KeyEvent.KEYCODE_TAB,
                metaState = KeyEvent.META_ALT_ON,
                hasFocusedWindow = true,
                focusedIsFullscreen = false,
                globalEnabled = true,
                disabledIds = disabled,
            ),
        )
        assertEquals(11, WorkstationShortcutCatalog.totalGlobalShortcutCount)
        assertEquals(3, WorkstationShortcutCatalog.countActiveGlobalShortcuts(true, disabled))
        assertEquals(0, WorkstationShortcutCatalog.countActiveGlobalShortcuts(false, emptySet()))
        assertTrue(WorkstationShortcutCatalog.hasCustomizations(true, disabled))
        assertFalse(WorkstationShortcutCatalog.hasCustomizations(true, emptySet()))

        val serialized = WorkstationShortcutCatalog.serializeDisabledIds(disabled + "unknown_id")
        assertEquals(disabled, WorkstationShortcutCatalog.parseDisabledIds(serialized))
        assertEquals(emptySet<String>(), WorkstationShortcutCatalog.parseDisabledIds(" , invalid "))
    }
}
