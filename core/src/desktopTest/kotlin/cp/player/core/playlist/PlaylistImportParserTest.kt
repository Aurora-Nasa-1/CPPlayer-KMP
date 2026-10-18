package cp.player.core.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 外部歌单解析测试。
 *
 * 这些用例全部来自真实分享文案的形状：带 `#/` 的链接、带 `userid` 的链接、
 * 行首编号、`歌名 - 歌手` 与制表符分隔。解析层一旦放宽/收紧，先在这里断。
 */
class PlaylistImportParserTest {

    @Test
    fun `识别带井号的歌单链接`() {
        val parsed = PlaylistImportParser.parse("https://music.163.com/#/playlist?id=3778678&userid=1")
        assertEquals(3778678L, parsed.playlistLinkId)
        assertEquals(1, parsed.entries.size)
        assertTrue(parsed.entries.single() is PlaylistImportEntry.PlaylistLink)
    }

    @Test
    fun `识别路径式歌单链接与短域名`() {
        assertEquals(123L, PlaylistImportParser.parse("music.163.com/playlist/123").playlistLinkId)
        assertEquals(
            456L,
            PlaylistImportParser.parse("https://y.music.163.com/m/playlist?id=456&userid=9").playlistLinkId,
        )
    }

    @Test
    fun `一首一行单曲链接`() {
        val parsed = PlaylistImportParser.parse(
            """
            https://music.163.com/song?id=111
            https://music.163.com/#/song?id=222
            """.trimIndent(),
        )
        assertNull(parsed.playlistLinkId)
        assertEquals(listOf("111", "222"), parsed.entries.map { (it as PlaylistImportEntry.SongLink).songId })
    }

    @Test
    fun `文本行拆歌名与歌手`() {
        val parsed = PlaylistImportParser.parse(
            """
            1. 晴天 - 周杰伦
            2、七里香 — 周杰伦
            3) 稻香	周杰伦
            夜曲，周杰伦
            """.trimIndent(),
        )
        val queries = parsed.entries.filterIsInstance<PlaylistImportEntry.TextQuery>()
        assertEquals(4, queries.size)
        assertEquals("晴天" to "周杰伦", queries[0].title to queries[0].artist)
        assertEquals("七里香" to "周杰伦", queries[1].title to queries[1].artist)
        assertEquals("稻香" to "周杰伦", queries[2].title to queries[2].artist)
        assertEquals("夜曲" to "周杰伦", queries[3].title to queries[3].artist)
    }

    @Test
    fun `只有歌名时不硬拆`() {
        val parsed = PlaylistImportParser.parse("7 Rings")
        val query = parsed.entries.single() as PlaylistImportEntry.TextQuery
        assertEquals("7 Rings", query.title)
        assertNull(query.artist)
    }

    @Test
    fun `歌单名与噪声行`() {
        val parsed = PlaylistImportParser.parse(
            """
            歌单：深夜车库
            共 3 首
            ------
            晴天 - 周杰伦
            """.trimIndent(),
        )
        assertEquals("深夜车库", parsed.nameHint)
        assertEquals(1, parsed.entries.size)
        assertEquals(2, parsed.ignoredLines)
    }

    @Test
    fun `去重且保留首次出现的顺序`() {
        val parsed = PlaylistImportParser.parse(
            """
            晴天 - 周杰伦
            晴天 - 周杰伦
            https://music.163.com/song?id=111
            https://music.163.com/song?id=111
            """.trimIndent(),
        )
        assertEquals(2, parsed.entries.size)
        assertTrue(parsed.entries[0] is PlaylistImportEntry.TextQuery)
        assertTrue(parsed.entries[1] is PlaylistImportEntry.SongLink)
    }

    @Test
    fun `不支持的链接只计数`() {
        val parsed = PlaylistImportParser.parse(
            """
            https://163cn.tv/abc123
            https://i.y.qq.com/n2/m/share/details/taoge.html?id=1
            """.trimIndent(),
        )
        assertEquals(2, parsed.unsupportedLinks)
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun `混合输入里歌单链接优先`() {
        val parsed = PlaylistImportParser.parse(
            """
            分享歌单：通勤
            https://music.163.com/playlist?id=777
            附加：晴天 - 周杰伦
            """.trimIndent(),
        )
        assertEquals(777L, parsed.playlistLinkId)
        assertEquals(2, parsed.entries.size)
        assertEquals(1, parsed.queryCount)
    }

    @Test
    fun `空输入不炸`() {
        assertTrue(PlaylistImportParser.parse("").isEmpty)
        assertTrue(PlaylistImportParser.parse("   \n \n").isEmpty)
    }
}
