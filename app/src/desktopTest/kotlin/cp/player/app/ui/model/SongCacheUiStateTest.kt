package cp.player.app.ui.model

import cp.player.core.playback.SongCacheEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 歌曲缓存管理页的**纯逻辑**测试（不需要渲染、也不需要后端）。
 *
 * 这一层看起来都是「显示层的小函数」，但它们决定的是用户能不能
 * **找到并删掉**那首歌 —— 搜索匹配、三种空态的区分、四段副标题的格式化，
 * 任何一处错了都会表现成「明明有缓存却搜不到 / 列表一片空白」。
 * 这些不该靠肉眼看图来验。
 */
class SongCacheUiStateTest {

    private fun entry(
        id: String = "a.flac",
        mediaId: String? = "cp_api://song/1",
        level: String? = "lossless",
        title: String? = "夜曲",
        artist: String? = "周杰伦",
        bytes: Long = 1024L,
    ) = SongCacheEntry(
        id = id,
        mediaId = mediaId,
        qualityLevel = level,
        title = title,
        artist = artist,
        bytes = bytes,
        lastAccessMs = 1_000L,
    )

    private fun state(entries: List<SongCacheEntry>, query: String = "") = SongCacheUiState(
        loading = false,
        supported = true,
        entries = entries,
        totalBytes = entries.sumOf { it.bytes },
        capacityBytes = 2L shl 30,
        query = query,
    )

    // ============ 搜索 ============

    @Test
    fun `搜索命中歌名 歌手 音质 与文件名`() {
        val target = entry()
        assertTrue(target.matches("夜曲"))
        assertTrue(target.matches("周杰伦"))
        assertTrue(target.matches("lossless"))
        assertTrue(target.matches("a.flac"))
    }

    @Test
    fun `搜索忽略大小写与首尾空白`() {
        val target = entry(title = "Lose Yourself", artist = "Eminem")
        assertTrue(target.matches("lose yourself"))
        assertTrue(target.matches("  EMINEM  "))
    }

    @Test
    fun `空关键字视为不过滤`() {
        val target = entry()
        assertTrue(target.matches(""))
        assertTrue(target.matches("   "))
    }

    @Test
    fun `老缓存没有元信息时仍可按文件名搜到`() {
        // 升级前落下的文件：只有文件名可用，但它必须仍然可被定位和删除。
        val legacy = entry(id = "cp_api_song_9_lossless-0d41ee.flac", mediaId = null, level = null, title = null, artist = null)
        assertTrue(legacy.matches("0d41ee"))
        assertFalse(legacy.matches("夜曲"))
    }

    @Test
    fun `visibleEntries 按关键字过滤`() {
        val list = listOf(entry(id = "a.flac", title = "夜曲"), entry(id = "b.flac", title = "晴天"))
        assertEquals(2, state(list).visibleEntries.size)
        assertEquals(listOf("晴天"), state(list, query = "晴").visibleEntries.map { it.title })
        assertTrue(state(list, query = "没有这首").visibleEntries.isEmpty())
    }

    // ============ 三种「空」必须分得开 ============

    @Test
    fun `空缓存与搜索无命中是两种状态`() {
        val empty = state(emptyList())
        assertTrue(empty.isEmptyCache, "一条缓存都没有")
        assertFalse(empty.hasNoMatch, "空缓存不能被说成「搜索无命中」")

        val noMatch = state(listOf(entry()), query = "没有这首")
        assertFalse(noMatch.isEmptyCache, "有缓存就不是空缓存")
        assertTrue(noMatch.hasNoMatch, "有缓存但一条都没命中")

        val normal = state(listOf(entry()), query = "夜曲")
        assertFalse(normal.isEmptyCache)
        assertFalse(normal.hasNoMatch)
    }

    @Test
    fun `加载中不算空缓存`() {
        // 否则首帧会先闪一下「暂无缓存」，再跳出真实列表。
        val loading = SongCacheUiState(loading = true)
        assertFalse(loading.isEmptyCache)
        assertFalse(loading.hasNoMatch)
    }

    // ============ 展示格式化 ============

    @Test
    fun `未知曲目只在缺歌名时兜底`() {
        assertEquals("夜曲", entry().displayName)
        assertEquals("未知曲目", entry(title = null).displayName)
        assertEquals("未知曲目", entry(title = "   ").displayName, "空白歌名等同于缺歌名")
    }

    @Test
    fun `音质档位覆盖界面可选四档之外的档位`() {
        assertEquals("标准", qualityLabel("standard"))
        assertEquals("极高", qualityLabel("exhigh"))
        assertEquals("无损", qualityLabel("lossless"))
        assertEquals("Hi-Res", qualityLabel("hires"))
        // 这两档界面选不到，但音源或旧版本会写进缓存 —— 不能显示成空白。
        assertEquals("母带", qualityLabel("jymaster"))
        assertEquals("沉浸声", qualityLabel("sky"))
        // 完全不认识的档位：原样显示，至少还能看出个所以然。
        assertEquals("something-new", qualityLabel("something-new"))
    }

    @Test
    fun `相对时间分档正确`() {
        val now = 1_000_000_000_000L
        val minute = 60_000L
        assertEquals("刚刚", relativeTime(now - minute / 2, now))
        assertEquals("5 分钟前", relativeTime(now - 5 * minute, now))
        assertEquals("3 小时前", relativeTime(now - 3 * 60 * minute, now))
        assertEquals("2 天前", relativeTime(now - 2 * 24 * 60 * minute, now))
        assertEquals("2 个月前", relativeTime(now - 61 * 24 * 60 * minute, now))
    }

    @Test
    fun `时间戳缺失时如实显示未知而不是刚刚`() {
        // 老缓存的 lastModified 读不出来会是 0；显示成「刚刚」会让用户以为它刚被播过。
        assertEquals("时间未知", relativeTime(0L, 1_000_000L))
    }

    @Test
    fun `时钟回拨不会算出负数分钟`() {
        assertEquals("刚刚", relativeTime(2_000L, 1_000L))
    }

    // ============ 接口缓存命中率 ============

    @Test
    fun `没有样本时命中率是 null 而不是 0`() {
        // 0% 是「一查一个准地没命中」（该去查白名单/键），和「还没查过」是两件事。
        assertNull(StorageUiState().apiCacheHitRate)
        assertEquals(0L, StorageUiState().apiCacheSamples)
    }

    @Test
    fun `命中率按命中除总数计算`() {
        val s = StorageUiState(apiCacheHits = 3, apiCacheMisses = 1)
        assertEquals(0.75f, s.apiCacheHitRate)
        assertEquals(4L, s.apiCacheSamples)
    }

    @Test
    fun `歌曲缓存能力判据来自容量而不是平台`() {
        assertFalse(StorageUiState(songCacheCapacityBytes = 0L).songCacheSupported, "安卓：不落盘")
        assertTrue(StorageUiState(songCacheCapacityBytes = 1L).songCacheSupported, "桌面：有上限即支持")
    }

    // ============ 容量档位 ============

    @Test
    fun `容量档位精确匹配优先`() {
        assertEquals(0, songCacheCapacityIndex(512L * 1024 * 1024))
        assertEquals(1, songCacheCapacityIndex(1L * 1024 * 1024 * 1024))
        assertEquals(2, songCacheCapacityIndex(2L * 1024 * 1024 * 1024), "2 GiB 是默认档")
        assertEquals(3, songCacheCapacityIndex(4L * 1024 * 1024 * 1024))
    }

    @Test
    fun `认不出的上限退到不超过它的最大档`() {
        // 老版本 / 将来新增档位写下的值：不能兜底到第一档，否则界面会谎报一个更小的上限。
        assertEquals(0, songCacheCapacityIndex(700L * 1024 * 1024), "700MB 落在 512MB 与 1GB 之间")
        assertEquals(2, songCacheCapacityIndex(3L * 1024 * 1024 * 1024))
        assertEquals(3, songCacheCapacityIndex(64L * 1024 * 1024 * 1024), "比最大档还大 ⇒ 取最大档")
        assertEquals(0, songCacheCapacityIndex(0L), "未知 / 加载中不能越界")
        assertEquals(0, songCacheCapacityIndex(1L))
    }

    @Test
    fun `容量档位选项本身是升序且不重复`() {
        val bytes = SONG_CACHE_CAPACITY_OPTIONS.map { it.first }
        assertEquals(bytes.sorted(), bytes, "档位必须升序 —— 退档逻辑依赖顺序")
        assertEquals(bytes.distinct(), bytes, "档位不能重复，否则 selectedIndex 会指向第一个")
        assertTrue(bytes.all { it > 0L })
        assertTrue(SONG_CACHE_CAPACITY_OPTIONS.all { it.second.isNotBlank() }, "每档都要有展示文案")
    }
}
