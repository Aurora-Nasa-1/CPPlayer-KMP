package cp.player.app.ui.screen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归：**用户 / 歌手主页**的热门歌曲行尾「更多」必须打开歌曲动作弹层。
 *
 * ## 为什么是静态断言，而不是渲染后点一下
 *
 * `SongItem` 在组合期直接读 `AppModel.playback.state`，而桌面测试进程**从不初始化**
 * `MusicBackend`（`Main.kt` 的 `ensureBackendInitialized` 只在真实启动时跑）——
 * 组合它就抛 `MusicBackend.init() not called`。这不是本测试引入的问题：
 * HEAD 上 `CpTextPreviewTest.realComponents` 就是因为同一原因失败的。
 *
 * 所以这里钉的是**接线本身**：这一页的 `onOptionsClick` 必须把目标交给弹层宿主，
 * 而不能再就地做某个动作。原先它被接成「切换收藏」，用户点「更多」时既没有菜单、
 * 也没有任何可见变化（未登录时收藏请求还会整条回滚），表现就是**点按无反应**。
 *
 * 一旦有人把这段改回「就地执行动作」，本测试立刻变红，并指向具体行号。
 */
class UserProfileSongOptionsWiringTest {

    private val source: String by lazy {
        val relative = "src/commonMain/kotlin/cp/player/app/ui/screen/UserProfileScreen.kt"
        // 测试工作目录通常是模块根（app/）；从仓库根 / 别处跑也要能找到。
        // 找不到**直接失败**（不是空转）：扫描不到文件的接线测试比没有更糟 —— 它会给出假的安全感。
        var dir: File? = File("").absoluteFile
        val found = buildList {
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { add(it) }
                val underApp = File(File(dir, "app"), relative)
                if (underApp.isFile) add(underApp)
                dir = dir.parentFile
            }
        }.firstOrNull()
        check(found != null) { "找不到 UserProfileScreen.kt（cwd=${File("").absolutePath}）" }
        found.readText()
    }

    /** 取出「热门歌曲 SongItem」那一段（从驱动它那行的 `onClick` 到 `contextMenu` 之前）。 */
    private fun songRowBlock(): String {
        val start = source.indexOf("isCurrentlyPlaying = track.id == currentTrackId")
        assertTrue(start >= 0, "找不到热门歌曲行（isCurrentlyPlaying = track.id == currentTrackId）")
        val end = source.indexOf("contextMenu = songContextMenuItems(", start)
        assertTrue(end > start, "热门歌曲行里找不到 contextMenu 调用")
        return source.substring(start, end)
    }

    @Test
    fun `热门歌曲的更多按钮把目标交给弹层宿主`() {
        val block = songRowBlock()
        assertTrue(
            Regex("""onOptionsClick\s*=\s*\{\s*songOptionsTarget\s*=\s*track\s*}""").containsMatchIn(block),
            "歌手主页的「更多」必须写 `onOptionsClick = { songOptionsTarget = track }`，" +
                "由页面级弹层宿主呈现动作；当前是：\n$block",
        )
    }

    /** 「更多」不能就地切换收藏 —— 那正是本轮 bug 的形态（点了没反应）。 */
    @Test
    fun `热门歌曲的更多按钮不再就地切换收藏`() {
        val block = songRowBlock()
        assertFalse(
            "toggleFavoriteFor" in block,
            "「更多」按钮里又出现了 toggleFavoriteFor：点它会直接改收藏、且不开菜单。\n$block",
        )
        assertFalse("UiEvents.notify" in block, "「更多」按钮里又出现了提示调用（说明动作被就地执行了）。")
    }

    /** 页面级必须真的挂上弹层宿主 + 两个二级弹层的目标持有者。 */
    @Test
    fun `页面挂载歌曲动作弹层与二级弹层目标`() {
        assertTrue(
            "songOptionsTarget by remember { mutableStateOf<TrackSummary?>(null) }" in source,
            "缺少 songOptionsTarget 状态：弹层不会出现",
        )
        assertTrue("SongOptionsSheet(" in source, "缺少 SongOptionsSheet 宿主")
        assertTrue("AddToPlaylistSheet(trackId = track.id" in source, "「歌单」二级弹层的目标没有落点")
        assertTrue("SongDetailInfoDialog(track = track" in source, "「歌曲信息」二级弹层的目标没有落点")
    }
}
