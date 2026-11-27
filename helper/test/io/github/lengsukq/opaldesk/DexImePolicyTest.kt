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
    fun `parses daemon system IME runtime JSON including optional Yuyan plugin fields`() {
        val json = """
            {
              "currentImeId":"io.github.lengsukq.opaldesk.ime/.OpalYuyanImeService",
              "originalImeId":"com.sohu.inputmethod.sogou/.SogouIME",
              "lockedImeId":"",
              "bridgeImeId":"io.github.lengsukq.opaldesk/.ime.OpalDeskImeService",
              "pluginImeId":"io.github.lengsukq.opaldesk.ime/.OpalYuyanImeService",
              "pluginImeInstalled":true,
              "pluginImeActive":true,
              "bridgeImeActive":true,
              "yieldedToDisplay0":false,
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
                "io.github.lengsukq.opaldesk.ime/.OpalYuyanImeService",
                "com.google.android.inputmethod.latin/com.google.android.inputmethod.latin.LatinIME"
              ]
            }
        """.trimIndent()

        val state = DexImeStateParser.parse(json)
        assertTrue(state.synced)
        assertEquals(BridgeProtocol.OPALDESK_IME_PLUGIN_ID, state.currentImeId)
        assertEquals("com.sohu.inputmethod.sogou/.SogouIME", state.originalImeId)
        assertEquals(BridgeProtocol.OPALDESK_BRIDGE_IME_ID, state.bridgeImeId)
        assertEquals(BridgeProtocol.OPALDESK_IME_PLUGIN_ID, state.pluginImeId)
        assertTrue(state.pluginImeInstalled)
        assertTrue(state.pluginImeActive)
        assertTrue(state.bridgeImeActive)
        assertFalse(state.yieldedToDisplay0)
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

        // Handles empty / malformed JSON gracefully
        val empty = DexImeStateParser.parse("")
        assertFalse(empty.synced)
        assertFalse(empty.pluginImeInstalled)
        assertEquals(BridgeProtocol.HardKbdSoftIme.SYSTEM, empty.showImeWithHardKeyboard)
    }

    @Test
    fun `merges local localized IME options with optional Yuyan plugin and stock Yuyan recognition`() {
        val local = listOf(
            DexImeOption(
                id = "com.tencent.wetype/.plugin.hld.WxHldService",
                label = "微信输入法",
                packageName = "com.tencent.wetype",
            ),
            DexImeOption(
                id = "com.yuyan.pinyin.offline.release/.ImeService",
                label = "语燕输入法",
                packageName = "com.yuyan.pinyin.offline.release",
            ),
        )

        // Uninstalled plugin -> does NOT clutter picker with uninstalled plugin row
        val mergedWithoutPlugin = DexImeCatalog.mergeOptions(
            localOptions = local,
            daemonEnabledIds = listOf(
                "com.tencent.wetype/.plugin.hld.WxHldService",
                "com.google.android.inputmethod.latin/com.google.android.inputmethod.latin.LatinIME",
            ),
            lockedImeId = "com.removed.ime/.OldService",
            currentSystemImeId = "com.tencent.wetype/.plugin.hld.WxHldService",
            pluginImeInstalled = false,
        )
        assertEquals(5, mergedWithoutPlugin.size)
        assertEquals(BridgeProtocol.OPALDESK_BRIDGE_IME_ID, mergedWithoutPlugin[0].id)
        assertTrue(mergedWithoutPlugin[0].isBuiltInBridge)
        assertEquals("微信输入法", mergedWithoutPlugin[1].label)
        assertTrue(mergedWithoutPlugin[2].isStockYuyan)
        assertEquals("com.removed.ime/.OldService", mergedWithoutPlugin[4].id)
        assertFalse(mergedWithoutPlugin[4].enabledInSystem)

        // Installed plugin -> surfaces Yuyan companion plugin first!
        val mergedWithPlugin = DexImeCatalog.mergeOptions(
            localOptions = local,
            daemonEnabledIds = emptyList(),
            lockedImeId = "",
            currentSystemImeId = BridgeProtocol.OPALDESK_IME_PLUGIN_ID,
            pluginImeInstalled = true,
        )
        assertEquals(BridgeProtocol.OPALDESK_IME_PLUGIN_ID, mergedWithPlugin[0].id)
        assertTrue(mergedWithPlugin[0].isYuyanPlugin)
        assertTrue(mergedWithPlugin[0].enabledInSystem)
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

        // Release download URL and user opt-in/opt-out helper for local DeX
        assertEquals(
            "https://github.com/lengsukq?tab=repositories",
            DexImeCatalog.YUYAN_PLUGIN_RELEASE_URL,
        )
        assertFalse(
            DexImeCatalog.isYuyanPluginEnabledForDex(
                pluginInstalled = false,
                lockedImeId = "",
                autoBridgeOnCast = true,
                displayMode = BridgeProtocol.DexImeDisplayMode.AUTO,
            ),
        )
        assertTrue(
            DexImeCatalog.isYuyanPluginEnabledForDex(
                pluginInstalled = true,
                lockedImeId = BridgeProtocol.OPALDESK_IME_PLUGIN_ID,
                autoBridgeOnCast = false,
                displayMode = BridgeProtocol.DexImeDisplayMode.AUTO,
            ),
        )
        assertFalse(
            DexImeCatalog.isYuyanPluginEnabledForDex(
                pluginInstalled = true,
                lockedImeId = "",
                autoBridgeOnCast = false,
                displayMode = BridgeProtocol.DexImeDisplayMode.AUTO,
            ),
        )
    }
}
