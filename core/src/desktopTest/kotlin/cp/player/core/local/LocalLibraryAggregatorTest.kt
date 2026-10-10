package cp.player.core.local

import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalTrackMetadata
import cp.player.core.media.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [LocalLibraryAggregator] 的行为测试。
 *
 * 重点钉住三件容易回归的事：
 * 1. 同名专辑不同艺人**不能**被合并成一张（本地库最常见的聚合事故）
 * 2. 专辑内曲目按「碟号 → 轨号」排，而不是文件扫描顺序
 * 3. 搜索是 AND 语义、命中多个字段
 */
class LocalLibraryAggregatorTest {

    // ============ 专辑聚合 ============

    @Test
    fun `同名专辑不同艺人不会被合并`() {
        val items = listOf(
            song("a1", title = "Yesterday", album = "Greatest Hits", artist = "The Beatles"),
            song("a2", title = "Hey Jude", album = "Greatest Hits", artist = "The Beatles"),
            song("b1", title = "Bohemian Rhapsody", album = "Greatest Hits", artist = "Queen"),
        )
        val albums = LocalLibraryAggregator.albums(items)
        assertEquals(2, albums.size, "同名不同艺人的专辑应当分开")
        assertEquals(setOf(2, 1), albums.map { it.songCount }.toSet())
    }

    @Test
    fun `专辑艺人优先于曲目艺人`() {
        val items = listOf(
            song("a1", album = "合辑", artist = "歌手甲", albumArtist = "Various Artists"),
            song("a2", album = "合辑", artist = "歌手乙", albumArtist = "Various Artists"),
        )
        val albums = LocalLibraryAggregator.albums(items)
        assertEquals(1, albums.size)
        assertEquals("Various Artists", albums.first().artist)
    }

    @Test
    fun `专辑内曲目按碟号与轨号排序`() {
        val items = listOf(
            song("t3", title = "第三首", album = "A", artist = "X", track = 3),
            song("t1", title = "第一首", album = "A", artist = "X", track = 1),
            song("t2", title = "第二首", album = "A", artist = "X", track = 2),
        )
        val album = LocalLibraryAggregator.albums(items).single()
        assertEquals(listOf("t1", "t2", "t3"), album.paths)
    }

    @Test
    fun `无专辑名的曲目归入未知专辑`() {
        val items = listOf(song("n1", title = "散曲", album = null, artist = "X"))
        val album = LocalLibraryAggregator.albums(items).single()
        assertEquals(LocalLibraryAggregator.UNKNOWN_ALBUM, album.name)
    }

    @Test
    fun `视频不参与音乐库聚合`() {
        val items = listOf(
            song("a1", title = "歌", album = "A", artist = "X"),
            LocalMediaItem(path = "v1", title = "视频", mediaType = MediaType.VIDEO),
        )
        assertEquals(1, LocalLibraryAggregator.albums(items).size)
        assertEquals(1, LocalLibraryAggregator.audioOnly(items).size)
    }

    @Test
    fun `专辑总时长是曲目时长之和`() {
        val items = listOf(
            song("a1", album = "A", artist = "X", duration = 60_000),
            song("a2", album = "A", artist = "X", duration = 90_000),
        )
        assertEquals(150_000L, LocalLibraryAggregator.albums(items).single().durationMs)
    }

    // ============ 身份哈希 ============

    @Test
    fun `专辑身份哈希稳定且区分艺人`() {
        val a = LocalLibraryAggregator.albumIdentityId("Greatest Hits", "Queen")
        val b = LocalLibraryAggregator.albumIdentityId("Greatest Hits", "Queen")
        val c = LocalLibraryAggregator.albumIdentityId("greatest hits", "QUEEN")
        val d = LocalLibraryAggregator.albumIdentityId("Greatest Hits", "The Beatles")
        assertEquals(a, b)
        // 大小写与空白不应影响身份
        assertEquals(a, c)
        assertNotEquals(a, d)
        assertTrue(a >= 0, "身份哈希必须是非负数（UI 用作 key）")
    }

    // ============ 艺术家 ============

    @Test
    fun `艺术家汇总曲目数与专辑数`() {
        val items = listOf(
            song("a1", album = "A", artist = "X"),
            song("a2", album = "A", artist = "X"),
            song("a3", album = "B", artist = "X"),
        )
        val artist = LocalLibraryAggregator.artists(items).single()
        assertEquals("X", artist.name)
        assertEquals(3, artist.songCount)
        assertEquals(2, artist.albumCount)
    }

    // ============ 搜索 ============

    @Test
    fun `搜索命中标题 艺术家 专辑 与文件名`() {
        val items = listOf(
            song("path/七里香.mp3", title = "七里香", album = "七里香", artist = "周杰伦"),
            song("path/other.mp3", title = "晴天", album = "叶惠美", artist = "周杰伦"),
        )
        assertEquals(1, LocalLibraryAggregator.search(items, "晴天").size)
        assertEquals(2, LocalLibraryAggregator.search(items, "周杰伦").size)
        assertEquals(1, LocalLibraryAggregator.search(items, "叶惠美").size)
        // 命中文件名
        assertEquals(1, LocalLibraryAggregator.search(items, "other").size)
    }

    @Test
    fun `搜索是 AND 语义且大小写不敏感`() {
        val items = listOf(
            song("p1", title = "Seven Years", album = "Album", artist = "Norah Jones"),
            song("p2", title = "Sunrise", album = "Feels Like Home", artist = "Norah Jones"),
        )
        assertEquals(1, LocalLibraryAggregator.search(items, "norah seven").size)
        assertEquals(2, LocalLibraryAggregator.search(items, "NORAH").size)
        assertEquals(0, LocalLibraryAggregator.search(items, "norah 不存在的词").size)
    }

    @Test
    fun `空搜索词返回全部`() {
        val items = listOf(song("p1"), song("p2"))
        assertEquals(2, LocalLibraryAggregator.search(items, "   ").size)
    }

    // ============ 排序 ============

    @Test
    fun `按标题排序且降序可切换`() {
        val items = listOf(
            song("p1", title = "Banana"),
            song("p2", title = "apple"),
            song("p3", title = "Cherry"),
        )
        assertEquals(
            listOf("p2", "p1", "p3"),
            LocalLibraryAggregator.sortSongs(items, LocalLibrarySort.TITLE).map { it.path },
        )
        assertEquals(
            listOf("p3", "p1", "p2"),
            LocalLibraryAggregator.sortSongs(items, LocalLibrarySort.TITLE, descending = true).map { it.path },
        )
    }

    @Test
    fun `按时长排序`() {
        val items = listOf(
            song("p1", duration = 100),
            song("p2", duration = 300),
            song("p3", duration = 200),
        )
        assertEquals(
            listOf("p1", "p3", "p2"),
            LocalLibraryAggregator.sortSongs(items, LocalLibrarySort.DURATION).map { it.path },
        )
    }

    // ============ 流派 ============

    @Test
    fun `流派按曲目数降序`() {
        val items = listOf(
            song("p1", genre = "Rock"),
            song("p2", genre = "Rock"),
            song("p3", genre = "Jazz"),
        )
        assertEquals(listOf("Rock" to 2, "Jazz" to 1), LocalLibraryAggregator.genres(items))
    }

    // ============ 重复检测 ============

    @Test
    fun `重复歌曲按标题与专辑分组`() {
        val items = listOf(
            song("a/1.mp3", title = "Yesterday", album = "A", artist = "X"),
            song("b/1.mp3", title = "Yesterday", album = "A", artist = "X"),
            song("c/1.mp3", title = "Green", album = "A", artist = "X"),
        )
        val dup = LocalLibraryAggregator.duplicates(items)
        assertEquals(2, dup.size)
        assertTrue(dup.all { it.title == "Yesterday" })
    }

    @Test
    fun `同名不同专辑不算重复`() {
        val items = listOf(
            song("a/1.mp3", title = "Intro", album = "A"),
            song("b/1.mp3", title = "Intro", album = "B"),
        )
        assertTrue(LocalLibraryAggregator.duplicates(items).isEmpty())
    }

    @Test
    fun `空标题不参与重复判定`() {
        // 解析失败的曲目标题都是空串，若参与判定会把一整批残缺文件报成「重复」
        val items = listOf(
            song("a/1.mp3", title = ""),
            song("b/1.mp3", title = ""),
            song("c/1.mp3", title = "   "),
        )
        assertTrue(LocalLibraryAggregator.duplicates(items).isEmpty())
    }

    // ============ 浏览期筛选 ============

    @Test
    fun `默认筛选不做任何过滤`() {
        val items = listOf(song("a/1.mp3"), song("b/2.mp3"))
        val filter = LocalLibraryFilter()
        assertTrue(filter.isDefault)
        assertEquals(items, LocalLibraryAggregator.applyFilter(items, filter))
    }

    @Test
    fun `只看无损`() {
        val items = listOf(flacSong("a/1.flac"), mp3Song("b/1.mp3"))
        val filtered = LocalLibraryAggregator.applyFilter(items, LocalLibraryFilter(losslessOnly = true))
        assertEquals(listOf("a/1.flac"), filtered.map { it.path })
    }

    @Test
    fun `只看缺少封面`() {
        val items = listOf(
            flacSong("a/1.flac").copy(coverUri = "file:///cover.jpg"),
            flacSong("b/2.flac"),
        )
        val filtered = LocalLibraryAggregator.applyFilter(items, LocalLibraryFilter(missingCoverOnly = true))
        assertEquals(listOf("b/2.flac"), filtered.map { it.path })
    }

    @Test
    fun `只看标签残缺`() {
        val items = listOf(
            song("a/1.mp3", title = "T", album = "A", artist = "X", genre = "Rock"),
            song("b/2.mp3", title = "T", album = null, artist = "X", genre = "Rock"),
        )
        val filtered = LocalLibraryAggregator.applyFilter(items, LocalLibraryFilter(missingTagsOnly = true))
        assertEquals(listOf("b/2.mp3"), filtered.map { it.path })
    }

    @Test
    fun `只看重复曲目`() {
        val items = listOf(
            song("a/1.mp3", title = "Dup", album = "A"),
            song("b/1.mp3", title = "Dup", album = "A"),
            song("c/1.mp3", title = "Solo", album = "A"),
        )
        val filtered = LocalLibraryAggregator.applyFilter(items, LocalLibraryFilter(duplicatesOnly = true))
        assertEquals(listOf("a/1.mp3", "b/1.mp3"), filtered.map { it.path }.sorted())
    }

    @Test
    fun `筛选条数用于 UI 角标`() {
        assertEquals(0, LocalLibraryFilter().activeCount)
        assertEquals(2, LocalLibraryFilter(losslessOnly = true, duplicatesOnly = true).activeCount)
        assertFalse(LocalLibraryFilter(losslessOnly = true).isDefault)
    }

    // ============ 测试数据构造 ============

    private fun flacSong(path: String) = song(path, title = "F", album = "A", artist = "X").copy(
        metadata = LocalTrackMetadata(bitDepth = 16, sampleRateHz = 44_100, codec = "FLAC"),
    )

    private fun mp3Song(path: String) = song(path, title = "M", album = "A", artist = "X").copy(
        metadata = LocalTrackMetadata(bitrateKbps = 320, codec = "MP3"),
    )

    private fun song(
        path: String,
        title: String = "T",
        album: String? = null,
        artist: String? = null,
        albumArtist: String? = null,
        track: Int? = null,
        disc: Int? = null,
        duration: Long = 0L,
        genre: String? = null,
        year: Int? = null,
        lastModified: Long = 0L,
    ) = LocalMediaItem(
        path = path,
        title = title,
        artist = artist,
        album = album,
        durationMs = duration,
        mediaType = MediaType.AUDIO,
        lastModified = lastModified,
        metadata = LocalTrackMetadata(
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = track,
            discNumber = disc,
        ),
    )
}
