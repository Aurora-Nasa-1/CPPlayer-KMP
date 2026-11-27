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

    @Test
    fun testParseCurrentFlatAiReleaseFormat() {
        val body = """
            ## 更新内容 (What's Changed)

            - 新增了社交动态发布入口，并简化动态流展示
            - 新增了私人 FM 的实时队列更新展示
            - 优化了 `OPENAI_BASE_URL` 配置与更新弹窗样式
            - 修复了安卓设置页挂载后可能自动弹出软键盘的问题
            - 调整了桌面迷你模式按钮，将其并入窗口控制区

            ---
            **完整变更记录**: https://github.com/Aurora-Nasa-1/CPPlayer-KMP/compare/v1.12.5...v1.13.0

            ---

            ## 下载

            | 平台 | 文件 | 说明 |
            |---|---|---|
            | Windows | `CPPlayer-*Setup.exe` | 安装版（推荐） |
        """.trimIndent()

        val parsed = parseReleaseChangelog(body)
        assertEquals(1, parsed.blocks.size)
        val block = parsed.blocks.single()
        assertEquals(null, block.version)
        assertEquals(4, block.sections.size)
        assertEquals(ChangelogCategory.FEATURE, block.sections[0].category)
        assertEquals(2, block.sections[0].items.size)
        assertEquals(ChangelogCategory.IMPROVEMENT, block.sections[1].category)
        assertEquals(1, block.sections[1].items.size)
        assertEquals(ChangelogCategory.BUGFIX, block.sections[2].category)
        assertEquals(1, block.sections[2].items.size)
        assertEquals(ChangelogCategory.OTHER, block.sections[3].category)
        assertEquals("调整与变更", block.sections[3].title)
        assertEquals(1, block.sections[3].items.size)
        assertEquals("https://github.com/Aurora-Nasa-1/CPPlayer-KMP/compare/v1.12.5...v1.13.0", parsed.compareUrl)
    }

    @Test
    fun testParseMultiVersionAggregatedChangelog() {
        val aggregated = """
            ### v1.13.0

            - 新增了深色模式下按设置压暗流体背景的效果
            - 优化了更新弹窗样式

            ### v1.12.5

            - 修复了 Linux 版本从 deb 安装包资源目录加载 FlickEngine 失败的问题
        """.trimIndent()

        val parsed = parseReleaseChangelog(aggregated)
        assertEquals(2, parsed.blocks.size)
        assertEquals("v1.13.0", parsed.blocks[0].version)
        assertEquals(2, parsed.blocks[0].sections.size)
        assertEquals("v1.12.5", parsed.blocks[1].version)
        assertEquals(1, parsed.blocks[1].sections.size)
        assertEquals(ChangelogCategory.BUGFIX, parsed.blocks[1].sections[0].category)
    }

    @Test
    fun testParseFallbackGitLogAndTrimNoise() {
        val fallback = """
            ### docs: update architecture and contribution guidance (a103658b)

            内部文档调整。

            ### feat(update): multi-asset update dialog with auto-update opt-out (a0c5d894)

            发布资产列表进入 UpdateResult，弹窗按平台列出可安装文件。

            ### Connect fluid background to modern player (20ec60e)
        """.trimIndent()

        val parsed = parseReleaseChangelog(fallback)
        assertEquals(1, parsed.blocks.size)
        val sections = parsed.blocks[0].sections
        // docs 内部提交被过滤，仅保留 feat 与常规提交
        assertEquals(2, sections.size)
        assertEquals(ChangelogCategory.FEATURE, sections[0].category)
        assertEquals("multi-asset update dialog with auto-update opt-out", sections[0].items[0].text)
        assertTrue(sections[0].items[1].isSubItem)
        assertEquals(ChangelogCategory.OTHER, sections[1].category)
        assertEquals("Connect fluid background to modern player", sections[1].items[0].text)
    }

    @Test
    fun testCleanChangelogLineTrimsMarkdownLinksAndHashes() {
        assertEquals(
            "修复播放状态不同步 (#42)",
            cleanChangelogLine("✨ 修复播放状态不同步 by @author in https://github.com/org/repo/pull/42"),
        )
        assertEquals(
            "支持 查看文档 说明",
            cleanChangelogLine("支持 [查看文档](https://example.com/docs) 说明 (a1b2c3d4)"),
        )
    }
}
