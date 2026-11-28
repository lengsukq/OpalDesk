package io.github.lengsukq.opalcast

import io.github.lengsukq.opalcast.net.OpalCastClient
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverMultiUserProfileTest {

    @Test
    fun formatAppKeyAndNormalizeProfileType_keepPrimaryCompatAndIsolateClones() {
        assertEquals("com.tencent.mm", ReceiverPolicy.formatAppKey("com.tencent.mm", 0))
        assertEquals("com.tencent.mm#u999", ReceiverPolicy.formatAppKey("com.tencent.mm", 999))
        assertEquals("", ReceiverPolicy.normalizeProfileType("clone", 0))
        assertEquals(ReceiverPolicy.PROFILE_CLONE, ReceiverPolicy.normalizeProfileType("", 999))
        assertEquals(ReceiverPolicy.PROFILE_CLONE, ReceiverPolicy.normalizeProfileType("", 95))
        assertEquals(ReceiverPolicy.PROFILE_WORK, ReceiverPolicy.normalizeProfileType("work", 10))
        assertEquals(ReceiverPolicy.PROFILE_GENERIC, ReceiverPolicy.normalizeProfileType("", 12))
    }

    @Test
    fun remoteAppEntry_exposesSecondaryProfileMetadataAndSearchKeywords() {
        val primary = OpalCastClient.RemoteAppEntry(pkg = "com.tencent.mm", cls = ".ui.LauncherUI", label = "微信")
        val clone = OpalCastClient.RemoteAppEntry(
            pkg = "com.tencent.mm",
            cls = ".ui.LauncherUI",
            label = "微信",
            userId = 999,
            profileType = ReceiverPolicy.PROFILE_CLONE,
        )
        assertFalse(primary.isSecondaryProfile)
        assertTrue(clone.isSecondaryProfile)
        assertEquals("com.tencent.mm", primary.appKey)
        assertEquals("com.tencent.mm#u999", clone.appKey)

        val cloneKeywords = ReceiverPolicy.profileSearchKeywords(999, ReceiverPolicy.PROFILE_CLONE)
        assertTrue(cloneKeywords.contains("分身"))
        assertTrue(cloneKeywords.contains("clone"))
        assertTrue(cloneKeywords.contains("u999"))
        assertTrue(ReceiverPolicy.profileSearchKeywords(0, "").isEmpty())
    }
}
