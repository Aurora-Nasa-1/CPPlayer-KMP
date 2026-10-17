package cp.player.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
