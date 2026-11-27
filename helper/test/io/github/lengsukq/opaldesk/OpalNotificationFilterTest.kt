package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.notifications.DesktopNotificationItem
import io.github.lengsukq.opaldesk.notifications.OpalNotificationFilter
import io.github.lengsukq.opaldesk.notifications.OpalNotificationJsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpalNotificationFilterTest {

    @Test
    fun `allows normal messaging app notifications`() {
        assertTrue(
            OpalNotificationFilter.shouldInclude(
                packageName = "com.tencent.mm",
                title = "微信",
                text = "你好，收到一条新消息",
                isOngoing = false,
                isGroupSummary = false,
                isForegroundService = false,
            ),
        )
    }

    @Test
    fun `filters self package systemui ongoing and group summary notifications`() {
        assertFalse(
            OpalNotificationFilter.shouldInclude(
                packageName = "io.github.lengsukq.opaldesk",
                title = "OpalDesk",
                text = "运行中",
                isOngoing = false,
                isGroupSummary = false,
                isForegroundService = false,
            ),
        )
        assertFalse(
            OpalNotificationFilter.shouldInclude(
                packageName = "com.tencent.mm",
                title = "微信",
                text = "3 条未读",
                isOngoing = false,
                isGroupSummary = true,
                isForegroundService = false,
            ),
        )
        assertFalse(
            OpalNotificationFilter.shouldInclude(
                packageName = "com.netease.cloudmusic",
                title = "正在播放",
                text = "歌曲",
                isOngoing = true,
                isGroupSummary = false,
                isForegroundService = false,
            ),
        )
        assertFalse(
            OpalNotificationFilter.shouldInclude(
                packageName = "com.tencent.mm",
                title = "",
                text = "",
                isOngoing = false,
                isGroupSummary = false,
                isForegroundService = false,
            ),
        )
    }

    @Test
    fun `encodes OPD1 v1_2 notification snapshot posted and removed json cleanly`() {
        val item = DesktopNotificationItem(
            key = "0|com.tencent.mm|7|tag|1000",
            packageName = "com.tencent.mm",
            appLabel = "微信",
            title = "张三 \"VIP\"",
            text = "第一行\n验证码 123456",
            postTimeMs = 1730000000000L,
            clearable = true,
            isOngoing = false,
            canReply = true,
            replyLabel = "快速回复",
            otpCode = "123456",
        )
        val postedJson = OpalNotificationJsonCodec.encodePosted(item)
        assertTrue(postedJson.contains("\"action\":\"posted\""))
        assertTrue(postedJson.contains("\"pkg\":\"com.tencent.mm\""))
        assertTrue(postedJson.contains("\"title\":\"张三 \\\"VIP\\\"\""))
        assertTrue(postedJson.contains("\"text\":\"第一行\\n验证码 123456\""))
        assertTrue(postedJson.contains("\"canReply\":true"))
        assertTrue(postedJson.contains("\"replyLabel\":\"快速回复\""))
        assertTrue(postedJson.contains("\"otpCode\":\"123456\""))

        val snapshotJson = OpalNotificationJsonCodec.encodeSnapshot(listOf(item))
        assertTrue(snapshotJson.startsWith("{\"action\":\"snapshot\",\"items\":["))

        val removedJson = OpalNotificationJsonCodec.encodeRemoved("0|com.tencent.mm|7|tag|1000")
        assertEquals("{\"action\":\"removed\",\"key\":\"0|com.tencent.mm|7|tag|1000\"}", removedJson)
    }
}
