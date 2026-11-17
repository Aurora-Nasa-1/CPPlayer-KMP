package cp.player.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateCheckerTest {

    @Test
    fun testCompareVersions() {
        assertEquals(0, AppUpdateChecker.compareVersions("1.5.8", "1.5.8"))
        assertTrue(AppUpdateChecker.compareVersions("1.5.9", "1.5.8") > 0)
        assertTrue(AppUpdateChecker.compareVersions("1.5.8", "1.5.9") < 0)
        assertTrue(AppUpdateChecker.compareVersions("1.6.0", "1.5.9") > 0)
        assertTrue(AppUpdateChecker.compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(AppUpdateChecker.compareVersions("1.5.9", "v1.5.8") > 0)
        assertTrue(AppUpdateChecker.compareVersions("v1.5.9", "1.5.9") == 0)
    }

    @Test
    fun testCompareVersionsWithPreRelease() {
        assertTrue(AppUpdateChecker.compareVersions("1.5.9", "1.5.9-beta.1") > 0)
        assertTrue(AppUpdateChecker.compareVersions("1.5.9-beta.2", "1.5.9-beta.1") > 0)
        assertTrue(AppUpdateChecker.compareVersions("1.5.9-beta.1", "1.5.9-alpha.1") > 0)
    }

    @Test
    fun `destructive policy matches only old to target version range`() {
        val policy = AppUpdateChecker.DestructiveUpdatePolicy(
            id = "legacy-provider-v1-v3-1.8.8",
            fromVersionAtMost = "1.8.5",
            toVersionAtLeast = "1.8.8",
            title = "Important update",
            message = "Legacy provider files will be listed before deletion.",
            removeLegacyProviders = true,
        )
        assertTrue(AppUpdateChecker.matchesDestructivePolicy(policy, "1.8.5", "1.8.8"))
        assertTrue(AppUpdateChecker.matchesDestructivePolicy(policy, "1.8.4", "1.9.0"))
        assertFalse(AppUpdateChecker.matchesDestructivePolicy(policy, "1.8.6", "1.8.8"))
        assertFalse(AppUpdateChecker.matchesDestructivePolicy(policy, "1.8.5", "1.8.7"))
    }

}
