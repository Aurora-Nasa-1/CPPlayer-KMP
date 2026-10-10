package cp.player.core.local

import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalTrackMetadata
import cp.player.core.media.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 扫描过滤规则测试。
 *
 * 这里最容易出的事故是「过滤把不该滤的滤掉了」——比如把时长解析失败的曲目
 * 当成短音频整批剔除，或者把 SAF 的 `content://` 条目按本地路径规则误伤。
 * 所以下面几条边界用例比正常路径更重要。
 */
class LocalScanSettingsTest {

    @Test
    fun `默认设置不做任何过滤`() {
        val settings = LocalScanSettings()
        assertTrue(settings.isDefault)
        val items = listOf(audio("a"), audio("b"))
        assertEquals(items, settings.apply(items))
    }

    @Test
    fun `最短时长过滤掉短音频`() {
        val settings = LocalScanSettings(minDurationSeconds = 30)
        val items = listOf(
            audio("short", duration = 5_000),
            audio("ok", duration = 60_000),
        )
        assertEquals(listOf("ok"), settings.apply(items).map { it.path })
    }

    @Test
    fun `时长未知的条目必须保留`() {
        // duration=0 表示「解析失败 / 未知」，不是「时长为 0 的音频」。
        // 按 < 阈值 滤掉会让所有无法解析的文件整体从曲库消失。
        val settings = LocalScanSettings(minDurationSeconds = 30)
        val items = listOf(audio("unknown", duration = 0), audio("ok", duration = 60_000))
        assertEquals(listOf("unknown", "ok"), settings.apply(items).map { it.path })
    }

    @Test
    fun `过滤视频只在开关打开时生效`() {
        val items = listOf(
            audio("song"),
            LocalMediaItem(path = "movie", title = "电影", mediaType = MediaType.VIDEO),
        )
        assertEquals(2, LocalScanSettings().apply(items).size)
        assertEquals(1, LocalScanSettings(filterVideoFiles = true).apply(items).size)
    }

    @Test
    fun `排除目录会连带排除其子目录`() {
        val settings = LocalScanSettings(
            excludeFolders = listOf("/music/Recordings"),
        )
        val items = listOf(
            audio("/music/Recordings/take1.mp3"),
            audio("/music/Recordings/2024/take2.mp3"),
            audio("/music/Album/song.mp3"),
        )
        assertEquals(listOf("/music/Album/song.mp3"), settings.apply(items).map { it.path })
    }

    @Test
    fun `目录匹配忽略大小写并兼容反斜杠`() {
        val settings = LocalScanSettings(excludeFolders = listOf("/Music/Podcasts"))
        val items = listOf(
            audio("/music/podcasts/ep1.mp3"),
            audio("\\music\\PODCASTS\\ep2.mp3"),
            audio("/music/podcasts2/ep3.mp3"), // 前缀相似但不同目录，不应被排除
        )
        assertEquals(listOf("/music/podcasts2/ep3.mp3"), settings.apply(items).map { it.path })
    }

    @Test
    fun `前缀相似的目录不会被误伤`() {
        // `/Music2/x.mp3` 不应因为「以 /Music 开头」而被排除
        val settings = LocalScanSettings(excludeFolders = listOf("/Music"))
        val items = listOf(audio("/Music2/song.mp3"), audio("/Music/song.mp3"))
        assertEquals(listOf("/Music2/song.mp3"), settings.apply(items).map { it.path })
    }

    @Test
    fun `content__ 条目不受目录规则影响`() {
        // SAF 树 URI 与本地路径不是同一套「包含于目录」语义，不能按前缀匹配
        val settings = LocalScanSettings(excludeFolders = listOf("/music"))
        val items = listOf(
            audio("content://com.android.externalstorage.documents/tree/primary%3Amusic/song.mp3"),
        )
        assertEquals(1, settings.apply(items).size)
    }

    @Test
    fun `仅包含目录时只保留其下的条目`() {
        val settings = LocalScanSettings(includeOnlyFolders = listOf("/music/Wanted"))
        val items = listOf(
            audio("/music/Wanted/a.mp3"),
            audio("/music/Wanted/sub/b.mp3"),
            audio("/music/Other/c.mp3"),
        )
        assertEquals(
            listOf("/music/Wanted/a.mp3", "/music/Wanted/sub/b.mp3"),
            settings.apply(items).map { it.path },
        )
    }

    @Test
    fun `生效规则条数用于 UI 角标`() {
        assertEquals(0, LocalScanSettings().activeRuleCount)
        assertEquals(
            3,
            LocalScanSettings(
                minDurationSeconds = 10,
                filterVideoFiles = true,
                excludeFolders = listOf("/a"),
            ).activeRuleCount,
        )
    }

    @Test
    fun `空目录项不会匹配到所有路径`() {
        // 空白项若不跳过，isUnderRoot 的空字符串语义可能把整库滤没
        val settings = LocalScanSettings(excludeFolders = listOf("", "   "))
        assertFalse(settings.isDefault)
        val items = listOf(audio("/music/a.mp3"))
        assertEquals(1, settings.apply(items).size)
    }

    private fun audio(path: String, duration: Long = 60_000) = LocalMediaItem(
        path = path,
        title = path.substringAfterLast('/'),
        durationMs = duration,
        mediaType = MediaType.AUDIO,
        metadata = LocalTrackMetadata(),
    )
}
