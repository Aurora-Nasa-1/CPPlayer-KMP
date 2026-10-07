package cp.player.app.ui.screen

import cp.player.app.version.AppVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [settingsEntries] 的不变量。
 *
 * 这份注册表是设置树的**唯一声明**（根列表 / 桌面左栏 / 详情渲染全部由它驱动），
 * 所以它坏了不会有编译错误，只会以「设置页少一项 / 多一项 / 标题重复」的形式出现 ——
 * 恰恰是重构前那四份平行结构互相漂移时踩过的坑。
 *
 * 隔离：`settingsEntries()` 只读 `isAndroidPlatform()`（desktopTest 下为 false）、
 * `AppVersion.releaseChannel`（本文件内显式设置并恢复，不碰磁盘）
 * 与各 Screen 的构造函数，不碰 Compose 运行时。
 */
class SettingsRegistryTest {

    /** 临时切换发布渠道（debugOnly 入口的可见性由它决定），结束后原样恢复。 */
    private fun withReleaseChannel(channel: String, block: () -> Unit) {
        val saved = AppVersion.releaseChannel
        AppVersion.releaseChannel = channel
        try {
            block()
        } finally {
            AppVersion.releaseChannel = saved
        }
    }

    @Test
    fun `entry ids are unique`() {
        withReleaseChannel("debug") {
            val ids = settingsEntries().map { it.id }
            assertEquals(
                ids.size,
                ids.toSet().size,
                "设置项 id 必须唯一（桌面左栏选中态与将来的搜索都靠它）：$ids",
            )
        }
    }

    @Test
    fun `every entry is searchable`() {
        //
        // 这里原来是「缺标题 / 缺副标题」检查。**已删除，不要加回来**：
        // `titleOf` / `subtitleOf` 指向 [CpStrings] 的成员，而两份实现都实现同一个接口 ——
        // 漏一条、《英文》那份就编译不过。已经是编译期保证的事，不用再买运行时保险。
        //
        // 保留的是**编译期兜不住**的那一项：`keywords` 是手写的 list，漏写不会报错、
        // 只会让设置页搜索在这个入口上永远搜不到。
        //
        withReleaseChannel("debug") {
            settingsEntries().forEach { entry ->
                assertTrue(
                    entry.keywords.isNotEmpty(),
                    "id=${entry.id} 缺搜索关键词 —— 设置页搜索在这个入口上永远搜不到",
                )
            }
        }
    }

    @Test
    fun `groups are contiguous`() {
        // 同一分组的项必须连在一起。一旦交错，页面会渲染出两个同名分组标题。
        withReleaseChannel("debug") {
            val seen = mutableSetOf<SettingsGroup>()
            var previous: SettingsGroup? = null
            settingsEntries().forEach { entry ->
                if (entry.group != previous) {
                    assertTrue(
                        seen.add(entry.group),
                        "分组 ${entry.group} 被拆成了不连续的两段 —— 会渲染出两个同名分组标题",
                    )
                    previous = entry.group
                }
            }
        }
    }

    @Test
    fun `no two entries share a destination screen`() {
        withReleaseChannel("debug") {
            val classes = settingsEntries().map { it.screen()::class }
            assertEquals(
                classes.size,
                classes.toSet().size,
                "两个入口指向了同一个页面（合并设置项时最容易漏掉的一步）：$classes",
            )
        }
    }

    @Test
    fun `group contents match the agreed information architecture`() {
        // 快照测试：把「商定好的信息架构」钉死（在 debug 渠道下看全量）。
        // 改这里的失败是在问「这是一次有意的 IA 变更吗？」—— 而不是在说代码坏了。
        withReleaseChannel("debug") {
            val byGroup = settingsEntries().groupBy { it.group }
            assertEquals(
                // 「语言」排第一：它是唯一一个「改变整棵树读到的东西」的设置，
                // 也常常是用户在看不懂界面时要找的第一个入口。
                //
                // 两者是同一主题的两个层次 —— 音质决定「拿到什么」，
                // 音效决定「听起来怎么样」，用户找前者时常顺手看后者。
                // 它**不是** desktopOnly：桌面端底层没有音效能力，但那一页会
                // 明示禁用并解释原因（入口消失会让用户以为桌面版是残缺的）。
                byGroup[SettingsGroup.GENERAL]?.map { it.id },
                "「通用」组的内容或顺序变了",
            )
            assertEquals(
                // `lyrics_plugins`（歌词源插件）与 `providers`（音源管理）同组：
                // 两者都是「内容 / 数据来源」，用户找它们时想的是同一件事
                // ——「我从哪儿拿内容」，且都支持导入 zip，放在一起心智一致。
                listOf("account", "providers", "lyrics_plugins"),
                byGroup[SettingsGroup.ACCOUNT_AND_PROVIDER]?.map { it.id },
                "「账号与音源」组的内容或顺序变了",
            )
            assertEquals(
                // ⚠️ `standby`（局域网设备）是后来加的入口，这一行当时没跟着更新，
                // 于是快照测试一直挂在红灯上 —— 本次迁移顺手补回来。
                // `msg_notify`（私信通知）同理：它与 `standby` 是同一件事的两面
                // （桌面端只有常驻托盘才收得到通知），所以紧挨着它。
                listOf("stream_output", "integration", "standby", "msg_notify"),
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

    /**
     * debugOnly 入口只在 debug 构建出现。
     *
     * 正式渠道（stable）的普通用户不该看到排查向入口；同时也要保证
     * stable 列表仍然是连续、去重、指向不同页面的（上面的不变量在 debug 下已覆盖全量）。
     */
    @Test
    fun `debug only entries are hidden in stable and shown in debug`() {
        withReleaseChannel("stable") {
            val ids = settingsEntries().map { it.id }
            assertFalse("render_tuning" in ids, "stable 构建不应出现「渲染后端」入口")
            assertFalse("onboarding" in ids, "stable 构建不应出现「重看新手引导」入口")
            assertTrue("diagnostics" in ids, "诊断不是 debugOnly，stable 下仍应可见")
        }
        withReleaseChannel("debug") {
            val ids = settingsEntries().map { it.id }
            assertTrue("render_tuning" in ids, "debug 构建应出现「渲染后端」入口")
            assertTrue("onboarding" in ids, "debug 构建应出现「重看新手引导」入口")
        }
    }
}
