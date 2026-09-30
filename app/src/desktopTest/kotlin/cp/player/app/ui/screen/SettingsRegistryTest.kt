package cp.player.app.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [settingsEntries] 的不变量。
 *
 * 这份注册表是设置树的**唯一声明**（根列表 / 桌面左栏 / 详情渲染全部由它驱动），
 * 所以它坏了不会有编译错误，只会以「设置页少一项 / 多一项 / 标题重复」的形式出现 ——
 * 恰恰是重构前那四份平行结构互相漂移时踩过的坑。
 *
 * 隔离：`settingsEntries()` 只读 `isAndroidPlatform()`（desktopTest 下为 false）
 * 与各 Screen 的构造函数，不碰磁盘、不碰 Compose 运行时。
 */
class SettingsRegistryTest {

    @Test
    fun `entry ids are unique`() {
        val ids = settingsEntries().map { it.id }
        assertEquals(
            ids.size,
            ids.toSet().size,
            "设置项 id 必须唯一（桌面左栏选中态与将来的搜索都靠它）：$ids",
        )
    }

    @Test
    fun `every entry has a title and a subtitle`() {
        settingsEntries().forEach { entry ->
            assertTrue(entry.title.isNotBlank(), "id=${entry.id} 缺标题")
            assertTrue(entry.subtitle.isNotBlank(), "id=${entry.id} 缺副标题")
        }
    }

    @Test
    fun `groups are contiguous`() {
        // 同一分组的项必须连在一起。一旦交错，页面会渲染出两个同名分组标题。
        val seen = mutableSetOf<SettingsGroup>()
        var previous: SettingsGroup? = null
        settingsEntries().forEach { entry ->
            if (entry.group != previous) {
                assertTrue(
                    seen.add(entry.group),
                    "分组 ${entry.group} 被拆成了不连续的两段 —— 会渲染出两个「${entry.group.title}」标题",
                )
                previous = entry.group
            }
        }
    }

    @Test
    fun `no two entries share a destination screen`() {
        val classes = settingsEntries().map { it.screen()::class }
        assertEquals(
            classes.size,
            classes.toSet().size,
            "两个入口指向了同一个页面（合并设置项时最容易漏掉的一步）：$classes",
        )
    }

    @Test
    fun `group contents match the agreed information architecture`() {
        // 快照测试：把「商定好的信息架构」钉死。改这里的失败是在问
        // 「这是一次有意的 IA 变更吗？」—— 而不是在说代码坏了。
        val byGroup = settingsEntries().groupBy { it.group }
        assertEquals(
            listOf("appearance", "playback", "storage"),
            byGroup[SettingsGroup.GENERAL]?.map { it.id },
            "「通用」组的内容或顺序变了",
        )
        assertEquals(
            listOf("account", "providers"),
            byGroup[SettingsGroup.ACCOUNT_AND_PROVIDER]?.map { it.id },
            "「账号与音源」组的内容或顺序变了",
        )
        assertEquals(
            listOf("stream_output", "integration"),
            byGroup[SettingsGroup.CONNECTIVITY]?.map { it.id },
            "「连接与集成」组的内容或顺序变了",
        )
        assertEquals(
            listOf("about", "diagnostics", "render_tuning", "onboarding"),
            byGroup[SettingsGroup.OTHER]?.map { it.id },
            "「其他」组的内容或顺序变了",
        )
    }
}
