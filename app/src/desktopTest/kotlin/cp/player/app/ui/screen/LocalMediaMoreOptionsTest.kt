package cp.player.app.ui.screen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证本地媒体库与本地搜索的「更多」界面统一使用标准选项弹层，而不是机械套用桌面端右键菜单。
 */
class LocalMediaMoreOptionsTest {

    private fun readSource(relative: String): String {
        var dir: File? = File("").absoluteFile
        val found = buildList {
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { add(it) }
                val underApp = File(File(dir, "app"), relative)
                if (underApp.isFile) add(underApp)
                dir = dir.parentFile
            }
        }.firstOrNull()
        check(found != null) { "找不到 $relative（cwd=${File("").absolutePath}）" }
        return found.readText()
    }

    @Test
    fun `MoreOptionsSheet 包含本地媒体库专属动作能力`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/component/MoreOptionsSheet.kt")
        assertTrue("onAddToLocalPlaylist" in source, "SongOptionsSheet 应支持 onAddToLocalPlaylist")
        assertTrue("onOpenInFileManager" in source, "SongOptionsSheet 应支持 onOpenInFileManager")
        assertTrue("onRemoveFromPlaylist" in source, "SongOptionsSheet 应支持 onRemoveFromPlaylist")
        assertTrue("onRemoveFromLibrary" in source, "SongOptionsSheet 应支持 onRemoveFromLibrary")
        assertTrue("onDelete: (() -> Unit)?" in source, "SongOptionsSheet 应支持 onDelete")
    }

    @Test
    fun `LocalLibraryPane 本地歌曲和歌单使用标准 OptionsSheet 而非桌面右键菜单`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/screen/LocalLibraryPane.kt")

        // 1. LocalSongRow 支持并转发 onOptionsClick
        assertTrue(
            "onOptionsClick: ((LocalMediaItem) -> Unit)?" in source,
            "LocalSongRow 必须暴露 onOptionsClick 参数",
        )
        assertTrue(
            "onOptionsClick = if (onOptionsClick != null)" in source || "onOptionsClick = onOptionsClick?.let" in source || "onOptionsClick = { onOptionsClick" in source,
            "LocalSongRow 必须向底层 SongItem 转发 onOptionsClick",
        )

        // 2. LocalPlaylistCell 使用 onOptionsClick 且不直接弹桌面 DropdownMenu
        assertTrue(
            "onOptionsClick: () -> Unit" in source || "onOptionsClick: (LocalPlaylist) -> Unit" in source,
            "LocalPlaylistCell 应暴露 onOptionsClick 回调",
        )
        val playlistCellBlock = source.substringAfter("private fun LocalPlaylistCell(").substringBefore("\nprivate fun ")
        assertFalse(
            "DropdownMenu(" in playlistCellBlock,
            "LocalPlaylistCell 不应直接自绘桌面 DropdownMenu，应交给标准弹层",
        )

        // 3. 根部挂载 SongOptionsSheet 和 PlaylistOptionsSheet
        assertTrue(
            "optionsTargetSong by remember" in source,
            "LocalLibraryTab 必须持有 optionsTargetSong 状态",
        )
        assertTrue(
            "optionsTargetPlaylist by remember" in source,
            "LocalLibraryTab 必须持有 optionsTargetPlaylist 状态",
        )
        assertTrue(
            "SongOptionsSheet(" in source,
            "LocalLibraryTab 必须挂载 SongOptionsSheet",
        )
        assertTrue(
            "PlaylistOptionsSheet(" in source,
            "LocalLibraryTab 必须挂载 PlaylistOptionsSheet",
        )
    }

    @Test
    fun `LibrarySearchScreen 挂载 SongOptionsSheet 并连接本地歌曲`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/screen/LibrarySearchScreen.kt")
        assertTrue(
            "optionsTargetSong" in source,
            "LibrarySearchScreen 必须持有 optionsTargetSong 状态",
        )
        assertTrue(
            "SongOptionsSheet(" in source,
            "LibrarySearchScreen 必须挂载 SongOptionsSheet",
        )
    }
}
