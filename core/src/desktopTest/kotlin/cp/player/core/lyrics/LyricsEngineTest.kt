package cp.player.core.lyrics

import cp.player.core.music.CPMediaId
import cp.player.core.playback.SyncedLyricLine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 歌词引擎的守卫测试。
 *
 * 钉死三件事：
 * 1. **顺序即优先级** —— 首个命中即胜出，后面的来源**不被调用**（不是「调了但不采用」）；
 * 2. **未命中继续** —— 返回 null 或抛异常都要能走到下一条，不能一条坏来源拖垮整次取词；
 * 3. **能力门槛** —— 没声明 `GET_LYRICS` 的来源根本不该被调用。
 */
class LyricsEngineTest {

    private fun line(text: String, time: Long = 0L) = SyncedLyricLine(time = time, text = text)

    private fun request() = LyricsRequest(
        mediaId = CPMediaId("netease", "song", "123"),
        title = "Song",
        artist = "Artist",
        album = "Album",
    )

    /** 记录调用次数、可控返回值的假来源。 */
    private class FakeSource(
        override val id: String,
        private val result: LyricsSourceResult?,
        override val bundled: Boolean = false,
        override val capabilities: Set<LyricsCapability> = setOf(LyricsCapability.GET_LYRICS),
        private val throws: Boolean = false,
    ) : LyricsSource {
        override val name = id
        var calls = 0
            private set

        override suspend fun fetch(request: LyricsRequest): LyricsSourceResult? {
            calls++
            if (throws) error("boom")
            return result
        }
    }

    private class FakeRegistry(private val sources: List<LyricsSource>) : LyricsSourceRegistry {
        override suspend fun orderedSources() = sources
        override suspend fun allSources() = emptyList<LyricsSourceEntry>()
        override suspend fun setOrder(orderedIds: List<String>) = Unit
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun reload() = Unit
        override fun displayNameOf(source: LyricsSource) = source.name
    }

    @Test
    fun `first hit wins and later sources are not called`() = runBlocking {
        val a = FakeSource("a", LyricsSourceResult(lines = listOf(line("A"))))
        val b = FakeSource("b", LyricsSourceResult(lines = listOf(line("B"))))
        val engine = LyricsEngine(FakeRegistry(listOf(a, b)))

        val hit = engine.resolve(request())

        assertEquals("A", hit?.lines?.first()?.text)
        assertEquals(1, a.calls)
        assertEquals(0, b.calls, "首位命中后不应再问后面的来源")
    }

    @Test
    fun `miss falls through to the next source`() = runBlocking {
        val miss = FakeSource("miss", null)
        val hit = FakeSource("hit", LyricsSourceResult(lines = listOf(line("H"))))
        val engine = LyricsEngine(FakeRegistry(listOf(miss, hit)))

        val result = engine.resolve(request())

        assertEquals("H", result?.lines?.first()?.text)
        assertEquals(1, miss.calls)
        assertEquals(1, hit.calls)
    }

    @Test
    fun `throwing source does not abort the lookup`() = runBlocking {
        val boom = FakeSource("boom", null, throws = true)
        val hit = FakeSource("hit", LyricsSourceResult(lines = listOf(line("H"))))
        val engine = LyricsEngine(FakeRegistry(listOf(boom, hit)))

        val result = engine.resolve(request())

        assertEquals("H", result?.lines?.first()?.text, "一条来源抛异常不该中断整次取词")
    }

    @Test
    fun `empty lines count as a miss`() = runBlocking {
        val empty = FakeSource("empty", LyricsSourceResult(lines = emptyList()))
        val hit = FakeSource("hit", LyricsSourceResult(lines = listOf(line("H"))))
        val engine = LyricsEngine(FakeRegistry(listOf(empty, hit)))

        assertEquals("H", engine.resolve(request())?.lines?.first()?.text)
    }

    @Test
    fun `source without GET_LYRICS is never called`() = runBlocking {
        val noCap = FakeSource(
            "nocap",
            LyricsSourceResult(lines = listOf(line("X"))),
            capabilities = setOf(LyricsCapability.SEARCH_SONGS),
        )
        val hit = FakeSource("hit", LyricsSourceResult(lines = listOf(line("H"))))
        val engine = LyricsEngine(FakeRegistry(listOf(noCap, hit)))

        val result = engine.resolve(request())

        assertEquals("H", result?.lines?.first()?.text)
        assertEquals(0, noCap.calls, "未声明 GET_LYRICS 的来源不该被调用")
    }

    @Test
    fun `engine stamps source identity`() = runBlocking {
        val a = FakeSource("my-source", LyricsSourceResult(lines = listOf(line("A"))))
        val engine = LyricsEngine(FakeRegistry(listOf(a)))

        val hit = engine.resolve(request())

        assertEquals("my-source", hit?.sourceId)
        assertEquals("my-source", hit?.sourceName)
    }

    @Test
    fun `all miss yields null`() = runBlocking {
        val engine = LyricsEngine(
            FakeRegistry(listOf(FakeSource("a", null), FakeSource("b", null))),
        )
        assertNull(engine.resolve(request()))
    }

    @Test
    fun `blank title short-circuits without touching any source`() = runBlocking {
        val a = FakeSource("a", LyricsSourceResult(lines = listOf(line("A"))))
        val engine = LyricsEngine(FakeRegistry(listOf(a)))

        assertNull(engine.resolve(request().copy(title = "")))
        assertEquals(0, a.calls, "没有标题就没有可检索的关键词，不该浪费一次网络请求")
    }

    @Test
    fun `resolveToState maps to Success with display info`() = runBlocking {
        val source = object : LyricsSource {
            override val id = BuiltinLyricsSourceIds.AMLL
            override val name = "AMLL TTML"
            override val bundled = true
            override val capabilities = setOf(LyricsCapability.GET_LYRICS, LyricsCapability.LOOKUP_BY_ID)
            override suspend fun fetch(request: LyricsRequest) = LyricsSourceResult(
                lines = listOf(
                    SyncedLyricLine(
                        time = 0, text = "hello", translation = "你好",
                        words = listOf(SyncedLyricLine.SyncedWord("hello", 0, 100)),
                    ),
                ),
            )
        }
        val engine = LyricsEngine(FakeRegistry(listOf(source)))

        val (state, info) = engine.resolveToState(request())

        assertTrue(state is cp.player.core.playback.LyricsState.Success)
        assertEquals("AMLL TTML", info?.source)
        assertEquals("TTML (Karaoke)", info?.format)
        assertEquals(true, info?.hasWordLevel)
        assertEquals(true, info?.hasTranslation)
    }

    @Test
    fun `resolveToState yields NoLyrics with null info when nothing hits`() = runBlocking {
        val engine = LyricsEngine(FakeRegistry(listOf(FakeSource("a", null))))
        val (state, info) = engine.resolveToState(request())
        assertTrue(state is cp.player.core.playback.LyricsState.NoLyrics)
        assertNull(info)
    }
}
