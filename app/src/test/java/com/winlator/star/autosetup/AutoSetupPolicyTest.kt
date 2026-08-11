package com.winlator.star.autosetup

import com.winlator.star.contents.ContentProfile
import org.junit.Assert.*
import org.junit.Test

class AutoSetupPolicyTest {
    @Test fun `safe policy selects pinned proton`() {
        val selected = AutoSetupPolicy.choose(listOf(profile(ContentProfile.ContentType.CONTENT_TYPE_PROTON, "proton-11.0-1-arm64ec-unixlibs-sdk35"), profile(ContentProfile.ContentType.CONTENT_TYPE_PROTON, "proton-10.0-4-arm64ec-unixlibs-sdk28")), ContentProfile.ContentType.CONTENT_TYPE_PROTON)
        assertEquals("proton-10.0-4-arm64ec-unixlibs-sdk28", selected?.verName)
    }
    @Test fun `community proton matches compatible subbuild`() {
        val selected = AutoSetupPolicy.choose(listOf(profile(ContentProfile.ContentType.CONTENT_TYPE_PROTON, "proton-9.0-arm64ec"), profile(ContentProfile.ContentType.CONTENT_TYPE_PROTON, "proton-10.0-4-arm64ec-unixlibs-sdk28")), ContentProfile.ContentType.CONTENT_TYPE_PROTON, "proton-10.0-arm64ec")
        assertEquals("proton-10.0-4-arm64ec-unixlibs-sdk28", selected?.verName)
    }
    @Test fun `state machine rejects skipping validation`() {
        assertTrue(AutoSetupTransitions.canTransition(AutoSetupStage.LAUNCHING, AutoSetupStage.VALIDATING)); assertTrue(AutoSetupTransitions.canTransition(AutoSetupStage.VALIDATING, AutoSetupStage.READY)); assertFalse(AutoSetupTransitions.canTransition(AutoSetupStage.LAUNCHING, AutoSetupStage.READY))
    }
    @Test fun `unknown community version falls back safely`() {
        val selected = AutoSetupPolicy.choose(listOf(profile(ContentProfile.ContentType.CONTENT_TYPE_DXVK, "dxvk-arm64ec-2.7.1"), profile(ContentProfile.ContentType.CONTENT_TYPE_DXVK, "dxvk-arm64ec-3.0-experimental")), ContentProfile.ContentType.CONTENT_TYPE_DXVK, "missing-community-build")
        assertEquals("dxvk-arm64ec-2.7.1", selected?.verName)
    }
    @Test fun `status payload round trips`() {
        val original = AutoSetupStatus("steam:123", "Test Game", AutoSetupStage.VALIDATING, "Waiting", 42L)
        assertEquals(original, AutoSetupStatus.fromJson(original.toJson()))
    }
    @Test fun `remote placeholder is not installed`() {
        val remote = profile(ContentProfile.ContentType.CONTENT_TYPE_DXVK, "2.7.1").apply { remoteUrl = "https://example.invalid/dxvk.wcp" }
        assertFalse(AutoSetupPolicy.isInstalled(remote)); remote.remoteUrl = null; assertTrue(AutoSetupPolicy.isInstalled(remote)); assertFalse(AutoSetupPolicy.isInstalled(null))
    }
    private fun profile(type: ContentProfile.ContentType, name: String) = ContentProfile().apply { this.type = type; verName = name; verCode = 0 }
}
