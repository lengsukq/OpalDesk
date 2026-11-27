package io.github.lengsukq.opaldesk

import android.telephony.TelephonyManager
import io.github.lengsukq.opaldesk.cast.BleChallengeNonceStore
import io.github.lengsukq.opaldesk.cast.BleHotspotCommandParser
import io.github.lengsukq.opaldesk.cast.BleNearbyAdvertisementBuilder
import io.github.lengsukq.opaldesk.notifications.CallControlPolicy
import io.github.lengsukq.opaldesk.notifications.CallSnapshot
import io.github.lengsukq.opaldesk.notifications.DesktopNotificationItem
import io.github.lengsukq.opaldesk.notifications.NotificationActionButton
import io.github.lengsukq.opaldesk.notifications.OpalNotificationActionPolicy
import io.github.lengsukq.opaldesk.notifications.OpalNotificationStore
import io.github.lengsukq.opaldesk.notifications.RawNotificationAction
import io.github.lengsukq.opaldesk.search.ConsoleSearchCatalog
import io.github.lengsukq.opaldesk.search.ConsoleSpotlightState
import io.github.lengsukq.opaldesk.search.OpalSpotlightEngine
import io.github.lengsukq.opaldesk.ui.ConsoleSectionKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class CompanionCallAndBleTest {

    @Test
    fun `filters clickable non-reply notification actions up to 3 and serializes actions json`() {
        val raw = listOf(
            RawNotificationAction(label = "回复", hasActionIntent = true, hasRemoteInputs = true),
            RawNotificationAction(label = "  标记已读  ", hasActionIntent = true, hasRemoteInputs = false),
            RawNotificationAction(label = "   ", hasActionIntent = true, hasRemoteInputs = false),
            RawNotificationAction(label = "无跳转按钮", hasActionIntent = false, hasRemoteInputs = false),
            RawNotificationAction(label = "删除", hasActionIntent = true, hasRemoteInputs = false),
            RawNotificationAction(label = "归档", hasActionIntent = true, hasRemoteInputs = false),
            RawNotificationAction(label = "第四个应截断", hasActionIntent = true, hasRemoteInputs = false),
        )
        val selected = OpalNotificationActionPolicy.selectClickableNonReplyIndices(raw)
        assertEquals(3, selected.size)
        assertEquals(1, selected[0].first)
        assertEquals(NotificationActionButton(0, "标记已读"), selected[0].second)
        assertEquals(4, selected[1].first)
        assertEquals(NotificationActionButton(1, "删除"), selected[1].second)
        assertEquals(5, selected[2].first)
        assertEquals(NotificationActionButton(2, "归档"), selected[2].second)

        val item = DesktopNotificationItem(
            key = "0|com.tencent.mm|10|null|1000",
            packageName = "com.tencent.mm",
            appLabel = "微信",
            title = "测试消息",
            text = "点击按钮执行",
            postTimeMs = 1730000000123L,
            clearable = true,
            isOngoing = false,
            canReply = true,
            replyLabel = "回复",
            otpCode = "",
            actions = selected.map { it.second },
        )
        val json = OpalNotificationStore.toBridgeJson(listOf(item))
        assertTrue(json.contains("\"actions\":[{\"index\":0,\"label\":\"标记已读\"},{\"index\":1,\"label\":\"删除\"},{\"index\":2,\"label\":\"归档\"}]"))
    }

    @Test
    fun `serializes CallSnapshot json contract and validates dial sanitization and state transitions`() {
        val ringing = CallControlPolicy.transitionTelephonyState(
            prev = CallSnapshot(),
            telephonyState = TelephonyManager.CALL_STATE_RINGING,
            incomingNumber = "+86 138-0013-8000",
            contactName = "张三 \"VIP\"",
            nowMs = 1730000010000L,
            isMuted = false,
            isSpeakerOn = false,
            canAnswerPerm = true,
            canDial = true,
        )
        assertEquals(CallControlPolicy.STATE_RINGING, ringing.state)
        assertTrue(ringing.canAnswer)
        assertTrue(ringing.canReject)
        assertEquals(1730000010000L, ringing.startedAtMs)

        val json = ringing.toBridgeJson()
        assertTrue(json.contains("\"state\":\"ringing\""))
        assertTrue(json.contains("\"number\":\"+86 138-0013-8000\""))
        assertTrue(json.contains("\"contactName\":\"张三 \\\"VIP\\\"\""))
        assertTrue(json.contains("\"startedAtMs\":1730000010000"))
        assertTrue(json.contains("\"isMuted\":false"))
        assertTrue(json.contains("\"isSpeakerOn\":false"))
        assertTrue(json.contains("\"canAnswer\":true"))
        assertTrue(json.contains("\"canReject\":true"))
        assertTrue(json.contains("\"canDial\":true"))
        assertTrue(json.contains("\"source\":\"telephony\""))

        // Transition ringing -> offhook preserves caller identity even when TelephonyCallback omits number
        val offhook = CallControlPolicy.transitionTelephonyState(
            prev = ringing,
            telephonyState = TelephonyManager.CALL_STATE_OFFHOOK,
            incomingNumber = "",
            contactName = "",
            nowMs = 1730000015000L,
            isMuted = true,
            isSpeakerOn = true,
            canAnswerPerm = true,
        )
        assertEquals(CallControlPolicy.STATE_OFFHOOK, offhook.state)
        assertEquals("+86 138-0013-8000", offhook.number)
        assertEquals("张三 \"VIP\"", offhook.contactName)
        assertEquals(1730000015000L, offhook.startedAtMs)
        assertFalse(offhook.canAnswer)
        assertTrue(offhook.canReject)
        assertTrue(offhook.isMuted)
        assertTrue(offhook.isSpeakerOn)

        // Transition offhook -> idle clears active call identity
        val idle = CallControlPolicy.transitionTelephonyState(
            prev = offhook,
            telephonyState = TelephonyManager.CALL_STATE_IDLE,
            incomingNumber = "",
            contactName = "",
            nowMs = 1730000099000L,
            isMuted = false,
            isSpeakerOn = false,
            canAnswerPerm = true,
        )
        assertEquals(CallControlPolicy.STATE_IDLE, idle.state)
        assertFalse(idle.isActive)
        assertEquals("", idle.number)
        assertEquals("", idle.contactName)
        assertEquals(0L, idle.startedAtMs)

        // Dial number sanitization
        assertEquals("+86 138-0013-8000", CallControlPolicy.sanitizeDialNumber("tel:+86 138-0013-8000"))
        assertEquals("(415) 555-0199", CallControlPolicy.sanitizeDialNumber("(415) 555-0199;rm -rf /"))
        assertEquals("*#06#", CallControlPolicy.sanitizeDialNumber("  *#06#  "))
        assertEquals("", CallControlPolicy.sanitizeDialNumber("abc---()"))
        assertEquals("", CallControlPolicy.sanitizeDialNumber("   "))

        // Notification CATEGORY_CALL fallback inference
        val notifRinging = CallControlPolicy.buildNotificationFallbackSnapshot(
            prev = CallSnapshot(),
            title = "李四",
            text = "来电响铃中",
            postTimeMs = 1730000100000L,
            hasAnswerAction = true,
            isOngoing = true,
            isMuted = false,
            isSpeakerOn = false,
            canAnswerPerm = false,
        )
        assertEquals(CallControlPolicy.STATE_RINGING, notifRinging.state)
        assertEquals("李四", notifRinging.contactName)
        assertEquals(CallControlPolicy.SOURCE_NOTIFICATION, notifRinging.source)
        assertFalse(notifRinging.canAnswer)
    }

    @Test
    fun `encodes exact 12-byte BLE manufacturer payload matching bridge and swift wire test vectors`() {
        // Verify all BLE GATT UUIDs are valid 128-bit RFC 4122 UUIDs
        assertEquals(BleNearbyAdvertisementBuilder.SERVICE_UUID_STRING.lowercase(), BleNearbyAdvertisementBuilder.SERVICE_UUID.toString())
        assertEquals(BleNearbyAdvertisementBuilder.CHAR_CHALLENGE_UUID_STRING.lowercase(), BleNearbyAdvertisementBuilder.CHAR_CHALLENGE_UUID.toString())
        assertEquals(BleNearbyAdvertisementBuilder.CHAR_COMMAND_UUID_STRING.lowercase(), BleNearbyAdvertisementBuilder.CHAR_COMMAND_UUID.toString())
        assertEquals(0x504F, BleNearbyAdvertisementBuilder.MANUFACTURER_ID)

        val capFlags = BleNearbyAdvertisementBuilder.computeCapFlags(
            lanShareActive = true,
            hotspotActive = false,
            hotspotTriggerSupported = true,
        )
        assertEquals(0x05, capFlags)

        // Known test vector: version=1, capFlags=0x05 -> 0x15
        // fingerprint prefix: AA:BB:CC:DD:EE:FF:01:23 -> 8 raw bytes
        // port: 19878 = 0x4DA6 -> Big-Endian [0x4D, 0xA6]
        // battery: 85 = 0x55
        val encoded = BleNearbyAdvertisementBuilder.encodeManufacturerData(
            capFlags = capFlags,
            certFingerprintHex = "AA:BB:CC:DD:EE:FF:01:23:45:67:89:AB:CD:EF",
            wifiPort = 19878,
            batteryPct = 85,
        )
        val expected = byteArrayOf(
            0x15.toByte(),
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
            0xEE.toByte(), 0xFF.toByte(), 0x01.toByte(), 0x23.toByte(),
            0x4D.toByte(), 0xA6.toByte(),
            0x55.toByte(),
        )
        assertEquals(12, encoded.size)
        assertArrayEquals(expected, encoded)

        val decoded = BleNearbyAdvertisementBuilder.decodeManufacturerData(encoded)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.version)
        assertEquals(0x05, decoded.capFlags)
        assertTrue(decoded.isLanShareActive)
        assertFalse(decoded.isHotspotActive)
        assertTrue(decoded.supportsHotspotTrigger)
        assertEquals("aabbccddeeff0123", decoded.certFingerprintPrefixHex)
        assertEquals(19878, decoded.wifiPort)
        assertEquals(85, decoded.batteryPct)

        // Unknown / out-of-range battery maps strictly to 0xFF and decodes back to -1
        val unknownBattery = BleNearbyAdvertisementBuilder.encodeManufacturerData(
            capFlags = BleNearbyAdvertisementBuilder.CAP_HOTSPOT_ACTIVE,
            certFingerprintHex = "010203",
            wifiPort = 19878,
            batteryPct = -1,
        )
        assertEquals(0x12, unknownBattery[0].toInt() and 0xFF)
        assertEquals(0x01, unknownBattery[1].toInt() and 0xFF)
        assertEquals(0x02, unknownBattery[2].toInt() and 0xFF)
        assertEquals(0x03, unknownBattery[3].toInt() and 0xFF)
        assertEquals(0x00, unknownBattery[4].toInt() and 0xFF) // zero-padded short fingerprint
        assertEquals(0xFF, unknownBattery[11].toInt() and 0xFF)
        assertEquals(-1, BleNearbyAdvertisementBuilder.decodeManufacturerData(unknownBattery)!!.batteryPct)
    }

    @Test
    fun `enforces single-use and 30s TTL consumption on BLE challenge nonces`() {
        var nowMs = 1_000_000L
        var seq = 0
        val store = BleChallengeNonceStore(
            ttlMs = 30_000L,
            clockMs = { nowMs },
            randomFill = { buf ->
                seq++
                for (i in buf.indices) {
                    buf[i] = ((seq * 17 + i) and 0xFF).toByte()
                }
            },
        )

        val nonce1 = store.issueNonce("AA:BB:CC:DD:EE:01")
        assertEquals(16, nonce1.size)
        val nonce1Hex = BleNearbyAdvertisementBuilder.bytesToHexLower(nonce1)

        // Valid within TTL succeeds once
        nowMs += 10_000L
        assertTrue(store.consumeAndVerify("AA:BB:CC:DD:EE:01", nonce1Hex.uppercase()))
        // Replay with same nonce MUST fail immediately
        assertFalse(store.consumeAndVerify("AA:BB:CC:DD:EE:01", nonce1Hex))

        // Expired nonce (> 30s TTL) MUST fail
        val nonce2 = store.issueNonce("AA:BB:CC:DD:EE:02")
        val nonce2Hex = BleNearbyAdvertisementBuilder.bytesToHexLower(nonce2)
        nowMs += 30_001L
        assertFalse(store.consumeAndVerify("AA:BB:CC:DD:EE:02", nonce2Hex))

        // Wrong nonce rejects and invalidates current challenge
        val nonce3 = store.issueNonce("AA:BB:CC:DD:EE:03")
        val nonce3Hex = BleNearbyAdvertisementBuilder.bytesToHexLower(nonce3)
        assertFalse(store.consumeAndVerify("AA:BB:CC:DD:EE:03", "00".repeat(16)))
        assertFalse(store.consumeAndVerify("AA:BB:CC:DD:EE:03", nonce3Hex))
    }

    @Test
    fun `parses BLE hotspot command JSON and compacts oversized notifications cleanly`() {
        val raw = """{"op":"trigger_hotspot","clientId":"mac-pro-1","nonceHex":"${"ab".repeat(16)}","hmacHex":"${"cd".repeat(32)}"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val parsed = BleHotspotCommandParser.parseCommand(raw)
        assertNotNull(parsed)
        assertEquals("trigger_hotspot", parsed!!.op)
        assertEquals("mac-pro-1", parsed.clientId)
        assertEquals("ab".repeat(16), parsed.nonceHex)
        assertEquals("cd".repeat(32), parsed.hmacHex)

        assertNull(BleHotspotCommandParser.parseCommand("""{"op":"unknown"}""".toByteArray(StandardCharsets.UTF_8)))

        val respJson = """{"status":"needs_user_toggle","ssid":"","gatewayIp":"","port":19878,"message":"ROM 限制后台自动开热点，请在手机端确认开启"}"""
        assertEquals("needs_user_toggle", BleHotspotCommandParser.extractResponseStatus(respJson))

        // Compacting to 90 bytes drops verbose message while keeping valid JSON status + port
        val compacted = BleHotspotCommandParser.fitResponseForNotification(respJson, 90)
            .toString(StandardCharsets.UTF_8)
        assertTrue(compacted.toByteArray(StandardCharsets.UTF_8).size <= 90)
        assertEquals("needs_user_toggle", BleHotspotCommandParser.extractResponseStatus(compacted))
        assertTrue(compacted.contains("\"port\":19878"))
    }

    @Test
    fun `spotlight catalog reflects live call control and BLE nearby toggle badges`() {
        val offItems = ConsoleSearchCatalog.buildItems(
            ConsoleSpotlightState(notifAccessGranted = false, callControlEnabled = false, bleNearbyEnabled = false),
        )
        for (query in listOf("来电", "电话", "蓝牙", "热点")) {
            val top = OpalSpotlightEngine.search(offItems, query).first().item
            assertEquals("Query '$query' should resolve to NOTIFICATIONS_COMPANION", ConsoleSectionKeys.NOTIFICATIONS_COMPANION, top.sectionKey)
            assertEquals("未开启", top.badge)
            assertTrue(top.subtitle.contains("来电控制未开启"))
            assertTrue(top.subtitle.contains("蓝牙近场未开启"))
        }

        val onItems = ConsoleSearchCatalog.buildItems(
            ConsoleSpotlightState(notifAccessGranted = true, callControlEnabled = true, bleNearbyEnabled = true),
        )
        for (query in listOf("来电", "电话", "蓝牙", "热点")) {
            val top = OpalSpotlightEngine.search(onItems, query).first().item
            assertEquals(ConsoleSectionKeys.NOTIFICATIONS_COMPANION, top.sectionKey)
            assertTrue("Expected live '已开启' badge for '$query', got ${top.badge}", top.badge.contains("已开启"))
            assertTrue(top.subtitle.contains("来电控制已开启"))
            assertTrue(top.subtitle.contains("蓝牙近场已开启"))
        }
    }
}
