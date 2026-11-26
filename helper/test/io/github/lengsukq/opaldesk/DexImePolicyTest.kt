package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.DexImeStateParser
import io.github.lengsukq.opaldesk.ui.DexImeCatalog
import io.github.lengsukq.opaldesk.ui.DexImeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexImePolicyTest {

    @Test
    fun `parses daemon system IME runtime JSON cleanly`() {
        val json = """
            {
              "currentImeId":"com.tencent.wetype/.plugin.hld.WxHldService",
              "originalImeId":"com.sohu.inputmethod.sogou/.SogouIME",
              "lockedImeId":"com.tencent.wetype/.plugin.hld.WxHldService",
              "bridgeImeId":"io.github.lengsukq.opaldesk/.ime.OpalDeskImeService",
              "bridgeImeActive":false,
              "autoBridgeImeOnCast":true,
              "imeLockApplied":true,
              "hardKbdOverrideApplied":true,
              "dexSessionActive":true,
              "dexImeDisplayMode":1,
              "muteHostImeOnCast":true,
              "showImeWithHardKeyboard":0,
              "effectiveWindowImePolicy":0,
              "physicalKeyboardDetected":true,
              "enabledImeIds":[
                "com.sohu.inputmethod.sogou/.SogouIME",
                "com.tencent.wetype/.plugin.hld.WxHldService",
                "com.google.android.inputmethod.latin/com.google.android.inputmethod.latin.LatinIME"
              ]
            }
        """.trimIndent()

        val state = DexImeStateParser.parse(json)
        assertTrue(state.synced)
        assertEquals("com.tencent.wetype/.plugin.hld.WxHldService", state.currentImeId)
        assertEquals("com.sohu.inputmethod.sogou/.SogouIME", state.originalImeId)
        assertEquals("com.tencent.wetype/.plugin.hld.WxHldService", state.lockedImeId)
        assertEquals(BridgeProtocol.OPALDESK_BRIDGE_IME_ID, state.bridgeImeId)
        assertFalse(state.bridgeImeActive)
        assertTrue(state.autoBridgeImeOnCast)
        assertTrue(state.imeLockApplied)
        assertTrue(state.hardKbdOverrideApplied)
        assertTrue(state.dexSessionActive)
        assertEquals(BridgeProtocol.DexImeDisplayMode.ALWAYS_LOCAL, state.dexImeDisplayMode)
        assertTrue(state.muteHostImeOnCast)
        assertEquals(BridgeProtocol.HardKbdSoftIme.HIDE, state.showImeWithHardKeyboard)
        assertEquals(BridgeProtocol.DisplayImePolicy.LOCAL, state.effectiveWindowImePolicy)
        assertTrue(state.physicalKeyboardDetected)
        assertEquals(3, state.enabledImeIds.size)
        assertEquals(
            "com.google.android.inputmethod.latin/com.google.android.inputmethod.latin.LatinIME",
            state.enabledImeIds[2],
        )

        // Handles empty / malformed JSON gracefully
        val empty = DexImeStateParser.parse("")
        assertFalse(empty.synced)
        assertEquals(BridgeProtocol.HardKbdSoftIme.SYSTEM, empty.showImeWithHardKeyboard)
    }

    @Test
    fun `merges local localized IME options with daemon enabled IDs and flags disabled locked IME`() {
        val local = listOf(
            DexImeOption(
                id = "com.tencent.wetype/.plugin.hld.WxHldService",
                label = "微信输入法",
                packageName = "com.tencent.wetype",
            ),
        )
        val merged = DexImeCatalog.mergeOptions(
            localOptions = local,
            daemonEnabledIds = listOf(
                "com.tencent.wetype/.plugin.hld.WxHldService",
                "com.google.android.inputmethod.latin/com.google.android.inputmethod.latin.LatinIME",
            ),
            lockedImeId = "com.removed.ime/.OldService",
            currentSystemImeId = "com.tencent.wetype/.plugin.hld.WxHldService",
        )

        assertEquals(4, merged.size)
        // Always surfaces OpalDesk's built-in invisible channel IME first
        assertEquals(BridgeProtocol.OPALDESK_BRIDGE_IME_ID, merged[0].id)
        assertTrue(merged[0].isBuiltInBridge)
        assertTrue(merged[0].enabledInSystem)
        // Preserves localized label from local InputMethodManager
        assertEquals("微信输入法", merged[1].label)
        assertTrue(merged[1].enabledInSystem)
        // Adds daemon-discovered IME not returned by local query
        assertEquals("com.google.android.inputmethod.latin", merged[2].packageName)
        assertTrue(merged[2].enabledInSystem)
        // Retains unavailable locked IME with enabledInSystem=false so user sees warning
        assertEquals("com.removed.ime/.OldService", merged[3].id)
        assertFalse(merged[3].enabledInSystem)
    }

    @Test
    fun `formats DeX IME subtitles and maps hard-keyboard soft-IME indices accurately`() {
        val opts = listOf(
            DexImeOption("com.tencent.wetype/.plugin.hld.WxHldService", "微信输入法", "com.tencent.wetype"),
        )
        assertEquals(
            "始终在 DeX 内 · 锁定 微信输入法",
            DexImeCatalog.formatCompactEntrySubtitle(
                BridgeProtocol.DexImeDisplayMode.ALWAYS_LOCAL,
                "com.tencent.wetype/.plugin.hld.WxHldService",
                opts,
            ),
        )
        assertEquals(
            "智能自动 · 跟随系统",
            DexImeCatalog.formatCompactEntrySubtitle(
                BridgeProtocol.DexImeDisplayMode.AUTO,
                "",
                opts,
            ),
        )

        assertEquals(0, DexImeCatalog.hardKbdModeToIndex(BridgeProtocol.HardKbdSoftIme.SYSTEM))
        assertEquals(1, DexImeCatalog.hardKbdModeToIndex(BridgeProtocol.HardKbdSoftIme.HIDE))
        assertEquals(2, DexImeCatalog.hardKbdModeToIndex(BridgeProtocol.HardKbdSoftIme.SHOW))
        assertEquals(BridgeProtocol.HardKbdSoftIme.SYSTEM, DexImeCatalog.indexToHardKbdMode(0))
        assertEquals(BridgeProtocol.HardKbdSoftIme.HIDE, DexImeCatalog.indexToHardKbdMode(1))
        assertEquals(BridgeProtocol.HardKbdSoftIme.SHOW, DexImeCatalog.indexToHardKbdMode(2))
    }
}
