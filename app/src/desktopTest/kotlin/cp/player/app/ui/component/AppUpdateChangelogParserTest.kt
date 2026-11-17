package cp.player.app.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppUpdateChangelogParserTest {

    @Test
    fun testParseFormalAiGeneratedChangelog() {
        val markdown = """
            ## 更新内容 (What's Changed)

            ### 功能新增
            - 支持全新的 **Material 3 Expressive** 更新弹窗设计 (#101)
            - 增加 `快捷键` 导航与音量滚轮控制

            ### 问题修复
            - 修复弱网环境下封面图加载失败的问题
            - 解决桌面端窗口关闭时潜在的资源未释放问题

            ### 性能优化
            - 提升列表滚动帧率，优化组件重组开销
            - 降低本地缓存空间占用

            ### 界面与交互
            - 规范播放控制条圆角与按压动效

            ---
            **完整变更记录**: https://github.com/Aurora-Nasa-1/CPPlayer-KMP/compare/v1.8.8...v1.9.0
        """.trimIndent()

        val parsed = parseChangelog(markdown)

        assertEquals(4, parsed.sections.size)

        // Section 0: Features
        val sec0 = parsed.sections[0]
        assertEquals("功能新增", sec0.title)
        assertEquals(ChangelogCategory.FEATURE, sec0.category)
        assertEquals(2, sec0.items.size)
        assertTrue(sec0.items[0].text.contains("Material 3 Expressive"))

        // Section 1: Bug fixes
        val sec1 = parsed.sections[1]
        assertEquals("问题修复", sec1.title)
        assertEquals(ChangelogCategory.BUGFIX, sec1.category)
        assertEquals(2, sec1.items.size)

        // Section 2: Performance
        val sec2 = parsed.sections[2]
        assertEquals("性能优化", sec2.title)
        assertEquals(ChangelogCategory.PERFORMANCE, sec2.category)
        assertEquals(2, sec2.items.size)

        // Section 3: UI
        val sec3 = parsed.sections[3]
        assertEquals("界面与交互", sec3.title)
        assertEquals(ChangelogCategory.UI, sec3.category)
        assertEquals(1, sec3.items.size)

        // Compare URL
        assertNotNull(parsed.compareUrl)
        assertEquals("https://github.com/Aurora-Nasa-1/CPPlayer-KMP/compare/v1.8.8...v1.9.0", parsed.compareUrl)
    }

    @Test
    fun testEmojiStrippingWhenParsing() {
        val markdownWithEmojis = """
            ### ✨ 新功能
            - 🚀 支持新架构
            ### 🐛 Bug 修复
            - 🐞 修复闪退
        """.trimIndent()

        val parsed = parseChangelog(markdownWithEmojis)
        assertEquals(2, parsed.sections.size)
        assertEquals("功能新增", parsed.sections[0].title)
        assertEquals("支持新架构", parsed.sections[0].items[0].text)
        assertEquals("问题修复", parsed.sections[1].title)
        assertEquals("修复闪退", parsed.sections[1].items[0].text)
    }

    @Test
    fun testLegacyChangelogFallback() {
        val legacy = """
            - 修复若干已知问题
            - 提升稳定性
        """.trimIndent()

        val parsed = parseChangelog(legacy)
        assertTrue(parsed.sections.isEmpty())
        assertEquals(2, parsed.uncategorized.size)
        assertEquals("修复若干已知问题", parsed.uncategorized[0].text)
        assertEquals("提升稳定性", parsed.uncategorized[1].text)
    }

    @Test
    fun testEmptyChangelog() {
        val parsed = parseChangelog("")
        assertTrue(parsed.sections.isEmpty())
        assertTrue(parsed.uncategorized.isEmpty())
    }

    @Test
    fun testCategoryClassification() {
        assertEquals(ChangelogCategory.FEATURE, classifyCategory("功能新增"))
        assertEquals(ChangelogCategory.FEATURE, classifyCategory("新特性"))
        assertEquals(ChangelogCategory.FEATURE, classifyCategory("Features"))
        assertEquals(ChangelogCategory.BUGFIX, classifyCategory("问题修复"))
        assertEquals(ChangelogCategory.BUGFIX, classifyCategory("缺陷修复"))
        assertEquals(ChangelogCategory.BUGFIX, classifyCategory("Fixes"))
        assertEquals(ChangelogCategory.PERFORMANCE, classifyCategory("性能优化"))
        assertEquals(ChangelogCategory.UI, classifyCategory("界面与交互"))
        assertEquals(ChangelogCategory.WARNING, classifyCategory("重要说明"))
        assertEquals(ChangelogCategory.OTHER, classifyCategory("其他改进"))
        assertEquals(ChangelogCategory.GENERAL, classifyCategory("常规说明"))
    }
}
