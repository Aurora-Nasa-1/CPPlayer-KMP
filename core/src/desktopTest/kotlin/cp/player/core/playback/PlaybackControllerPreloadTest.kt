package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackControllerPreloadTest {

    private class FakePlatformPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        val loadedUrls = mutableListOf<String>()

        override suspend fun load(
            url: String,
            startPositionMs: Long,
            headers: Map<String, String>,
            metadata: PlaybackMetadata?,
        ) {
            loadedUrls += url
            state.value = PlatformPlaybackState.Playing
        }

        override fun play() { state.value = PlatformPlaybackState.Playing }
        override fun pause() { state.value = PlatformPlaybackState.Paused }
        override fun seekTo(positionMs: Long) { this.positionMs.value = positionMs }
        override fun stop() { state.value = PlatformPlaybackState.Idle }
        override fun release() {}
        override fun setVolume(volume: Float) {}
        override fun getVolume(): Float = 1f
    }

    private class FakePreloadSource : UnifiedMusicSource {
        val preloadedMediaIds = mutableListOf<String>()
        val requestedUrls = mutableListOf<String>()

        override suspend fun getTrackDetail(mediaId: String) =
            BackendResult.Success(TrackSummary(id = mediaId, name = "Track $mediaId", artist = "Artist", album = "Album", coverUrl = null, durationMs = 180000L))

        override suspend fun getTrackDetails(mediaIds: List<String>) =
            BackendResult.Success(mediaIds.map { getTrackDetail(it).getOrNull()!! })

        override suspend fun getSongUrl(mediaId: String, level: String): BackendResult<SongUrl> {
            requestedUrls += mediaId
            return BackendResult.Success(SongUrl("https://cdn/$mediaId", level, null, null))
        }

        override suspend fun preloadTrack(mediaId: String, level: String): BackendResult<SongUrl> {
            preloadedMediaIds += mediaId
            return getSongUrl(mediaId, level)
        }

        override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
            BackendResult.Error("not used")

        override suspend fun getUserPlaylists(providerId: String, uid: Long) =
            BackendResult.Error("not used")
    }

    private class FakeLocalizer : StreamLocalizer {
        val preloadedKeys = mutableListOf<String>()
        val cached = mutableMapOf<String, String>()

        override fun isLocalizing(qualityLevel: String): Boolean = qualityLevel in StreamLocalizer.LOSSLESS_LEVELS

        override fun cachedPath(cacheKey: String): String? = cached[cacheKey]

        override suspend fun localize(
            url: String,
            cacheKey: String,
            headers: Map<String, String>,
            meta: SongCacheMeta?,
        ): String? {
            val path = "/local/$cacheKey"
            cached[cacheKey] = path
            return path
        }

        override suspend fun preload(
            url: String,
            cacheKey: String,
            headers: Map<String, String>,
            meta: SongCacheMeta?,
        ): String? {
            preloadedKeys += cacheKey
            return localize(url, cacheKey, headers, meta)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun throwingApi(): MusicApiService =
        java.lang.reflect.Proxy.newProxyInstance(
            MusicApiService::class.java.classLoader,
            arrayOf(MusicApiService::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("stub api: ${method.name}")
        } as MusicApiService

    @Test
    fun `playing current track triggers preload for next track after debounce`() = runBlocking {
        val platform = FakePlatformPlayer()
        val source = FakePreloadSource()
        val localizer = FakeLocalizer()

        val c = PlaybackControllerImpl(
            platform = platform,
            source = source,
            api = throwingApi(),
            cookieProvider = { null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            streamLocalizer = localizer,
        )

        c.setQuality("lossless")
        c.playQueue(listOf("netease://song/1", "netease://song/2", "netease://song/3"), startIndex = 0)

        // 等待防抖（1200ms）及后台任务执行
        delay(2000)

        // 验证下一首 "netease://song/2" 触发了预加载
        assertTrue(source.preloadedMediaIds.contains("netease://song/2"), "下一首应被预加载")
        assertTrue(localizer.preloadedKeys.any { it.startsWith("netease://song/2@lossless") }, "无损下一首应触发本地预落盘")

        c.release()
    }

    @Test
    fun `disabling preloadNext stops preloading`() = runBlocking {
        val platform = FakePlatformPlayer()
        val source = FakePreloadSource()
        val localizer = FakeLocalizer()

        val c = PlaybackControllerImpl(
            platform = platform,
            source = source,
            api = throwingApi(),
            cookieProvider = { null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            streamLocalizer = localizer,
        )

        c.setPreloadNext(false)
        c.playQueue(listOf("netease://song/1", "netease://song/2"), startIndex = 0)

        delay(1800)

        assertFalse(source.preloadedMediaIds.contains("netease://song/2"), "禁用后不应预加载")

        // 手动开启后应自动触发预加载
        c.setPreloadNext(true)
        delay(1800)

        assertTrue(source.preloadedMediaIds.contains("netease://song/2"), "启用后应触发预加载")

        c.release()
    }

    @Test
    fun `addNextToQueue updates next preload target`() = runBlocking {
        val platform = FakePlatformPlayer()
        val source = FakePreloadSource()

        val c = PlaybackControllerImpl(
            platform = platform,
            source = source,
            api = throwingApi(),
            cookieProvider = { null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        c.playQueue(listOf("netease://song/1", "netease://song/2"), startIndex = 0)

        // 插入新歌曲作为下一首
        c.addNextToQueue("netease://song/inserted")

        delay(1800)

        assertTrue(source.preloadedMediaIds.contains("netease://song/inserted"), "插入的下一首应被预加载")

        c.release()
    }
}
