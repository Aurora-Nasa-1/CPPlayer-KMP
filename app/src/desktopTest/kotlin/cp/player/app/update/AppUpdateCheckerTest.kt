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

    @Test
    fun `extractUpdateNotes keeps only changelog body from actual release format`() {
        val body = """
            ## 更新内容 (What's Changed)

            - 新增了社交动态发布入口
            - 修复了更新日志包含非更新内容段落的问题

            ---
            **完整变更记录**: https://github.com/Aurora-Nasa-1/CPPlayer-KMP/compare/v1.12.5...v1.13.0

            ---

            ## 下载

            在本页面下方的 **Assets** 中按平台下载：

            | 平台 | 文件 | 说明 |
            |---|---|---|
            | Windows | `CPPlayer-*Setup.exe` | 安装版（推荐） |
        """.trimIndent()

        val extracted = AppUpdateChecker.extractUpdateNotes(body)
        assertEquals(
            "- 新增了社交动态发布入口\n- 修复了更新日志包含非更新内容段落的问题",
            extracted,
        )
    }

    @Test
    fun `extractUpdateNotes returns blank for empty release notes`() {
        val emptyBody = """
            ## 更新内容 (What's Changed)


            ---
            **完整变更记录**: https://github.com/Aurora-Nasa-1/CPPlayer-KMP/compare/v1.12.3...v1.12.2
        """.trimIndent()

        assertEquals("", AppUpdateChecker.extractUpdateNotes(emptyBody))
    }

}
