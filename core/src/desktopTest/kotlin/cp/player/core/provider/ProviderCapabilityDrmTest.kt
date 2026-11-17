package cp.player.core.provider

import cp.player.sdk.ExtendedCapability
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ProviderCapabilityDrmTest {
    @Test
    fun drm_label_requires_both_operations() {
        val both = ProviderCapability.sdkLabels(
            listOf(ExtendedCapability.RESOLVEDRM, ExtendedCapability.EXCHANGELICENSE),
        )
        assertContains(both, ProviderCapability.DRM_PLAYBACK)

        val one = ProviderCapability.sdkLabels(listOf(ExtendedCapability.RESOLVEDRM))
        assertFalse(ProviderCapability.DRM_PLAYBACK in one)
    }
}
