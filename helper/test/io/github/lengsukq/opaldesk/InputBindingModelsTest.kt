package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.input.CapturedInputEvent
import io.github.lengsukq.opaldesk.input.InputBindingAction
import io.github.lengsukq.opaldesk.input.InputBindingCodec
import io.github.lengsukq.opaldesk.input.InputBindingRule
import io.github.lengsukq.opaldesk.input.LocalInputBindingExecutor
import io.github.lengsukq.opaldesk.input.extractMouseButtonCode
import io.github.lengsukq.opaldesk.input.findConflictingBinding
import io.github.lengsukq.opaldesk.input.findMatchingKeyBinding
import io.github.lengsukq.opaldesk.input.findMatchingMouseBinding
import io.github.lengsukq.opaldesk.input.formatTriggerBadge
import io.github.lengsukq.opaldesk.input.getReservedShortcutWarning
import io.github.lengsukq.opaldesk.input.hasEnabledKeyBinding
import io.github.lengsukq.opaldesk.input.isReservedSystemShortcut
import io.github.lengsukq.opaldesk.input.normalizeAndroidMetaState
import io.github.lengsukq.opaldesk.input.upsertInputBindingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputBindingModelsTest {

    @Test
    fun jsonRoundTripPreservesRulesAndFiltersInvalidEntries() {
        val rules = listOf(
            InputBindingRule(
                id = "side_ext_1",
                enabled = true,
                triggerType = InputBindingRule.TRIGGER_MOUSE,
                code = 0x20,
                metaMask = InputBindingRule.META_NONE,
                actionId = InputBindingAction.SNAP_LEFT.actionId,
                label = "Mouse 0x20 -> Snap Left",
            ),
            InputBindingRule(
                id = "side_ext_2",
                enabled = false,
                triggerType = InputBindingRule.TRIGGER_MOUSE,
                code = 0x40,
                metaMask = InputBindingRule.META_CTRL or InputBindingRule.META_ALT,
                actionId = InputBindingAction.TILE_2_SPLIT.actionId,
                label = "Ctrl+Alt+Mouse 0x40 -> 2-Column Split",
            ),
            InputBindingRule(
                id = "key_f4",
                enabled = true,
                triggerType = InputBindingRule.TRIGGER_KEY,
                code = 134, // KEYCODE_F4
                metaMask = InputBindingRule.META_ALT,
                actionId = InputBindingAction.CLOSE_WINDOW.actionId,
                label = "Alt+F4 -> Close Window",
            ),
        )

        val json = InputBindingCodec.encodeRules(rules)
        val decoded = InputBindingCodec.decodeRules(json)
        assertEquals(rules, decoded)

        assertTrue(InputBindingCodec.decodeRules("[]").isEmpty())
        assertTrue(InputBindingCodec.decodeRules("").isEmpty())
    }

    @Test
    fun extendedMouseButtons0x20And0x40FormatAndMatchAccurately() {
        assertEquals("Mouse 0x20", formatTriggerBadge(InputBindingRule.TRIGGER_MOUSE, 0x20, 0))
        assertEquals("Mouse 0x40", formatTriggerBadge(InputBindingRule.TRIGGER_MOUSE, 0x40, 0))
        assertEquals(
            "Ctrl+Mouse 0x20",
            formatTriggerBadge(InputBindingRule.TRIGGER_MOUSE, 0x20, InputBindingRule.META_CTRL),
        )
        assertEquals("Mouse Back", formatTriggerBadge(InputBindingRule.TRIGGER_MOUSE, 0x08, 0))
        assertEquals("Mouse Forward", formatTriggerBadge(InputBindingRule.TRIGGER_MOUSE, 0x10, 0))

        val rules = listOf(
            InputBindingRule(
                id = "r_20",
                enabled = true,
                triggerType = InputBindingRule.TRIGGER_MOUSE,
                code = 0x20,
                metaMask = InputBindingRule.META_NONE,
                actionId = InputBindingAction.TOGGLE_MAXIMIZE.actionId,
            ),
            InputBindingRule(
                id = "r_40_disabled",
                enabled = false,
                triggerType = InputBindingRule.TRIGGER_MOUSE,
                code = 0x40,
                metaMask = InputBindingRule.META_NONE,
                actionId = InputBindingAction.SPOTLIGHT.actionId,
            ),
            InputBindingRule(
                id = "r_40_ctrl",
                enabled = true,
                triggerType = InputBindingRule.TRIGGER_MOUSE,
                code = 0x40,
                metaMask = InputBindingRule.META_CTRL,
                actionId = InputBindingAction.MINIMIZE_WINDOW.actionId,
            ),
        )

        val matched20 = findMatchingMouseBinding(rules, 0x20, 0)
        assertNotNull(matched20)
        assertEquals("toggle_maximize", matched20?.actionId)

        assertNull(findMatchingMouseBinding(rules, 0x40, 0))

        val matched40Ctrl = findMatchingMouseBinding(rules, 0x40, 0x1000)
        assertNotNull(matched40Ctrl)
        assertEquals("minimize_window", matched40Ctrl?.actionId)

        val matchedBitFallback = findMatchingMouseBinding(rules, 0x01 or 0x20, 0)
        assertEquals("toggle_maximize", matchedBitFallback?.actionId)
    }

    @Test
    fun capturedEventCodecAndKeyMatchingWorkWithNormalizedModifiers() {
        val capturedJson = """{"triggerType":"mouse","code":32,"metaMask":0,"label":"Mouse 0x20","timestampMs":1700000000123}"""
        val captured = InputBindingCodec.decodeCapturedEvent(capturedJson)
        assertNotNull(captured)
        assertEquals(InputBindingRule.TRIGGER_MOUSE, captured?.triggerType)
        assertEquals(0x20, captured?.code)
        assertEquals("0x20", captured?.hexCode)
        assertEquals("Mouse 0x20", captured?.displayLabel)
        assertEquals(1700000000123L, captured?.timestampMs)

        val roundTrip = InputBindingCodec.decodeCapturedEvent(
            InputBindingCodec.encodeCapturedEvent(
                CapturedInputEvent("mouse", 0x40, InputBindingRule.META_SHIFT, "", 42L),
            ),
        )
        assertEquals("Shift+Mouse 0x40", roundTrip?.displayLabel)

        assertEquals(
            InputBindingRule.META_ALT or InputBindingRule.META_CTRL,
            normalizeAndroidMetaState(0x02 or 0x1000),
        )
        assertEquals(
            InputBindingRule.META_SHIFT or InputBindingRule.META_SUPER,
            normalizeAndroidMetaState(0x40 or 0x20000),
        )

        val keyRule = InputBindingRule(
            id = "k1",
            enabled = true,
            triggerType = InputBindingRule.TRIGGER_KEY,
            code = 135, // F5
            metaMask = InputBindingRule.META_SUPER,
            actionId = InputBindingAction.SWITCH_WINDOW.actionId,
        )
        assertEquals(keyRule, findMatchingKeyBinding(listOf(keyRule), 135, 0x10000))
        assertNull(findMatchingKeyBinding(listOf(keyRule), 135, 0))
        assertEquals(0x20, extractMouseButtonCode(actionButton = 0, buttonState = 0x20, previousButtonState = 0))
        assertEquals(0x40, extractMouseButtonCode(actionButton = 0x40, buttonState = 0x41, previousButtonState = 0x01))
        assertEquals(10, InputBindingAction.entries.size)
    }

    @Test
    fun conflictLookupAndUpsertAutoReplacePreserveOrderAndEditingState() {
        val r1 = InputBindingRule(
            id = "rule_back",
            enabled = true,
            triggerType = InputBindingRule.TRIGGER_MOUSE,
            code = InputBindingRule.MOUSE_BUTTON_BACK,
            metaMask = InputBindingRule.META_NONE,
            actionId = InputBindingAction.BACK.actionId,
        )
        val r2 = InputBindingRule(
            id = "rule_fwd",
            enabled = false,
            triggerType = InputBindingRule.TRIGGER_MOUSE,
            code = InputBindingRule.MOUSE_BUTTON_FORWARD,
            metaMask = InputBindingRule.META_NONE,
            actionId = InputBindingAction.SNAP_LEFT.actionId,
        )
        val r3 = InputBindingRule(
            id = "rule_ext",
            enabled = true,
            triggerType = InputBindingRule.TRIGGER_MOUSE,
            code = 0x20,
            metaMask = InputBindingRule.META_NONE,
            actionId = InputBindingAction.SPOTLIGHT.actionId,
        )
        val rules = listOf(r1, r2, r3)

        // Direct conflict detection and self-exclusion during edit
        assertEquals(r1, findConflictingBinding(rules, InputBindingRule.TRIGGER_MOUSE, 0x08, 0))
        assertNull(findConflictingBinding(rules, InputBindingRule.TRIGGER_MOUSE, 0x08, 0, excludeRuleId = "rule_back"))
        assertNull(findConflictingBinding(rules, InputBindingRule.TRIGGER_MOUSE, 0x08, InputBindingRule.META_CTRL))

        // Adding a new rule with conflicting trigger replaces the old one
        val newReplacement = InputBindingRule(
            id = "rule_new_back",
            enabled = true,
            triggerType = InputBindingRule.TRIGGER_MOUSE,
            code = InputBindingRule.MOUSE_BUTTON_BACK,
            metaMask = InputBindingRule.META_NONE,
            actionId = InputBindingAction.TOGGLE_MAXIMIZE.actionId,
        )
        val afterAdd = upsertInputBindingRule(rules, newReplacement)
        assertEquals(3, afterAdd.size)
        assertEquals(listOf("rule_fwd", "rule_ext", "rule_new_back"), afterAdd.map { it.id })
        assertEquals(InputBindingAction.TOGGLE_MAXIMIZE.actionId, afterAdd.last().actionId)

        // Editing rule_fwd in-place keeps its id, enabled=false state, and position while replacing conflicting rule_back
        val editedFwd = r2.copy(
            code = InputBindingRule.MOUSE_BUTTON_BACK,
            actionId = InputBindingAction.CLOSE_WINDOW.actionId,
        )
        val afterEdit = upsertInputBindingRule(rules, editedFwd, editingRuleId = "rule_fwd")
        assertEquals(2, afterEdit.size)
        assertEquals(listOf("rule_fwd", "rule_ext"), afterEdit.map { it.id })
        assertFalse(afterEdit[0].enabled)
        assertEquals(InputBindingRule.MOUSE_BUTTON_BACK, afterEdit[0].code)
        assertEquals(InputBindingAction.CLOSE_WINDOW.actionId, afterEdit[0].actionId)
    }

    @Test
    fun reservedSystemAndBrowserShortcutDetectionMatchesCommonCombosOnly() {
        // Alt+Tab, Alt+Shift+Tab, Super+Tab
        assertTrue(isReservedSystemShortcut(InputBindingRule.TRIGGER_KEY, 61, InputBindingRule.META_ALT))
        assertTrue(
            isReservedSystemShortcut(
                InputBindingRule.TRIGGER_KEY,
                61,
                InputBindingRule.META_ALT or InputBindingRule.META_SHIFT,
            ),
        )
        assertTrue(isReservedSystemShortcut(InputBindingRule.TRIGGER_KEY, 61, InputBindingRule.META_SUPER))

        // Ctrl+W, Ctrl+T, Super+Space, Super+Q
        assertTrue(isReservedSystemShortcut(InputBindingRule.TRIGGER_KEY, 51, InputBindingRule.META_CTRL))
        assertTrue(isReservedSystemShortcut(InputBindingRule.TRIGGER_KEY, 48, InputBindingRule.META_CTRL))
        assertTrue(isReservedSystemShortcut(InputBindingRule.TRIGGER_KEY, 62, InputBindingRule.META_SUPER))
        assertTrue(isReservedSystemShortcut(InputBindingRule.TRIGGER_KEY, 45, InputBindingRule.META_SUPER))

        val zhWarn = getReservedShortcutWarning(InputBindingRule.TRIGGER_KEY, 61, InputBindingRule.META_ALT, isChinese = true)
        assertNotNull(zhWarn)
        assertTrue(zhWarn!!.contains("Alt+Tab"))
        val enWarn = getReservedShortcutWarning(InputBindingRule.TRIGGER_KEY, 51, InputBindingRule.META_CTRL, isChinese = false)
        assertNotNull(enWarn)
        assertTrue(enWarn!!.contains("Ctrl+W"))

        // Safe keyboard combos and all mouse buttons should not trigger warning
        assertFalse(isReservedSystemShortcut(InputBindingRule.TRIGGER_KEY, 134, InputBindingRule.META_ALT)) // Alt+F4
        assertFalse(
            isReservedSystemShortcut(
                InputBindingRule.TRIGGER_KEY,
                51,
                InputBindingRule.META_CTRL or InputBindingRule.META_ALT,
            ),
        )
        assertFalse(isReservedSystemShortcut(InputBindingRule.TRIGGER_MOUSE, InputBindingRule.MOUSE_BUTTON_BACK, 0))
        assertNull(getReservedShortcutWarning(InputBindingRule.TRIGGER_MOUSE, 0x20, InputBindingRule.META_CTRL))
    }

    @Test
    fun keyBindingPresenceAndDesktopFallbackMouseButtonDetectionWorkAccurately() {
        assertFalse(hasEnabledKeyBinding(InputBindingRule.defaultRules()))
        val disabledKey = InputBindingRule(
            id = "k1",
            enabled = false,
            triggerType = InputBindingRule.TRIGGER_KEY,
            code = 131,
            metaMask = InputBindingRule.META_NONE,
            actionId = InputBindingAction.SPOTLIGHT.actionId,
        )
        assertFalse(hasEnabledKeyBinding(listOf(disabledKey)))
        assertTrue(hasEnabledKeyBinding(listOf(disabledKey.copy(enabled = true))))

        assertFalse(LocalInputBindingExecutor.isDesktopFallbackMouseButton(0))
        assertFalse(LocalInputBindingExecutor.isDesktopFallbackMouseButton(InputBindingRule.MOUSE_BUTTON_PRIMARY))
        assertFalse(LocalInputBindingExecutor.isDesktopFallbackMouseButton(InputBindingRule.MOUSE_BUTTON_SECONDARY))
        assertTrue(LocalInputBindingExecutor.isDesktopFallbackMouseButton(InputBindingRule.MOUSE_BUTTON_TERTIARY))
        assertTrue(LocalInputBindingExecutor.isDesktopFallbackMouseButton(InputBindingRule.MOUSE_BUTTON_BACK))
        assertTrue(LocalInputBindingExecutor.isDesktopFallbackMouseButton(InputBindingRule.MOUSE_BUTTON_FORWARD))
        assertTrue(LocalInputBindingExecutor.isDesktopFallbackMouseButton(0x20))
    }
}
