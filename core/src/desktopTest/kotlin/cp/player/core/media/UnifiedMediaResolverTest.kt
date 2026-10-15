package cp.player.core.media

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.local.LocalFavorites
import cp.player.core.local.LocalMediaSource
import cp.player.core.local.LocalScanSettingsStore
import cp.player.core.local.ScanProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedMediaResolverTest {

    private class FakeLocalMediaSource(
        initialItems: List<LocalMediaItem> = emptyList(),
    ) : LocalMediaSource {
        private val _items = MutableStateFlow(initialItems)
        override suspend fun scan(): Flow<ScanProgress> = emptyFlow()
        override suspend fun importFolder(uri: String): Int = 0
        override fun removeItem(item: LocalMediaItem) {}
        override fun items(): StateFlow<List<LocalMediaItem>> = _items
        override val isScanningFlow: StateFlow<Boolean> = MutableStateFlow(false)
        override fun addExternalItems(items: List<LocalMediaItem>) {}
        override val favorites: LocalFavorites = LocalFavorites("")
        override val scanSettings: LocalScanSettingsStore = LocalScanSettingsStore("")
    }

    private fun mockMusicApi(
        mvDetailJson: JsonElement,
        mvUrlJson: JsonElement,
    ): MusicApiService {
        val proxy = Proxy.newProxyInstance(
            MusicApiService::class.java.classLoader,
            arrayOf(MusicApiService::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("Mock未实现方法: ${method.name}")
        } as MusicApiService

        return object : MusicApiService by proxy {
            override suspend fun getMvDetail(mvId: String): JsonElement = mvDetailJson
            override suspend fun getMvUrl(mvId: String, resolution: Int): JsonElement = mvUrlJson
        }
    }

    @Test
    fun `本地视频必须成功解析流与原画档位`() = runBlocking {
        val localVideo = LocalMediaItem(
            path = "D:/Videos/concert_live.mp4",
            title = "Concert Live 2026",
            artist = "Band",
            durationMs = 120_000L,
            mediaType = MediaType.VIDEO,
            sizeBytes = 100_000_000L,
        )
        val resolver = UnifiedMediaResolverImpl(
            localMediaSource = FakeLocalMediaSource(listOf(localVideo)),
            musicApiService = mockMusicApi(Json.parseToJsonElement("{}"), Json.parseToJsonElement("{}")),
        )

        val result = resolver.resolveStream("local://video/D:/Videos/concert_live.mp4")
        assertTrue(result is BackendResult.Success, "解析应成功，实际: $result")
        val stream = result.data
        assertEquals("D:/Videos/concert_live.mp4", stream.url)
        assertEquals(MediaType.VIDEO, stream.mediaType)
        assertEquals("Concert Live 2026", stream.title)
        assertEquals(120_000L, stream.durationMs)
        assertEquals("original", stream.currentQuality?.id)
    }

    @Test
    fun `未登记的本地路径必须返回明确错误而非崩溃`() = runBlocking {
        val resolver = UnifiedMediaResolverImpl(
            localMediaSource = FakeLocalMediaSource(emptyList()),
            musicApiService = mockMusicApi(Json.parseToJsonElement("{}"), Json.parseToJsonElement("{}")),
        )

        val result = resolver.resolveStream("local://video/ghost_movie.mp4")
        assertTrue(result is BackendResult.Error, "未登记条目必须返回 Error，实际: $result")
    }

    @Test
    fun `云端 MV 应正确解析详情与多清晰度档位`() = runBlocking {
        val mvDetailJson = Json.parseToJsonElement("""
            {
                "data": {
                    "id": 142857,
                    "name": "Never Gonna Give You Up",
                    "artistName": "Rick Astley",
                    "duration": 213000,
                    "cover": "https://p1.music.126.net/cover.jpg",
                    "brs": {
                        "1080": "https://vod.music.126.net/1080.mp4",
                        "720": "https://vod.music.126.net/720.mp4",
                        "480": "https://vod.music.126.net/480.mp4"
                    }
                }
            }
        """.trimIndent())

        val mvUrlJson = Json.parseToJsonElement("""
            {
                "data": {
                    "id": 142857,
                    "url": "https://vod.music.126.net/1080.mp4",
                    "r": 1080,
                    "size": 52428800
                }
            }
        """.trimIndent())

        val resolver = UnifiedMediaResolverImpl(
            localMediaSource = FakeLocalMediaSource(),
            musicApiService = mockMusicApi(mvDetailJson, mvUrlJson),
        )

        val result = resolver.resolveStream("netease://mv/142857", preferredQuality = "1080")
        assertTrue(result is BackendResult.Success, "MV 流解析应成功: $result")
        val stream = result.data
        assertEquals("https://vod.music.126.net/1080.mp4", stream.url)
        assertEquals(MediaType.VIDEO, stream.mediaType)
        assertEquals("Never Gonna Give You Up", stream.title)
        assertEquals("Rick Astley", stream.artist)
        assertEquals(3, stream.availableQualities.size)
        assertEquals(1080, stream.currentQuality?.resolution)
        assertTrue(stream.headers.containsKey("Referer"))
    }
}
