package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRetryTest {
    private class FakePlatformPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        val loadedUrls = CopyOnWriteArrayList<String>()
        var failFirstLoadWithNetworkError = false

        override suspend fun load(
            url: String,
            startPositionMs: Long,
            headers: Map<String, String>,
            metadata: PlaybackMetadata?,
        ) {
            loadedUrls += url
            if (failFirstLoadWithNetworkError && loadedUrls.size == 1) {
                state.value = PlatformPlaybackState.Error("Network connection timeout")
            } else {
                state.value = PlatformPlaybackState.Playing
            }
        }

        override fun play() {
            if (state.value !is PlatformPlaybackState.Error) state.value = PlatformPlaybackState.Playing
        }
        override fun pause() { state.value = PlatformPlaybackState.Paused }
        override fun seekTo(positionMs: Long) { this.positionMs.value = positionMs }
        override fun stop() { state.value = PlatformPlaybackState.Idle }
        override fun release() {}
        override fun setVolume(volume: Float) {}
        override fun getVolume(): Float = 1f
    }

    private class FakeSource(
        var detailFailures: Int = 0,
        var urlFailures: Int = 0,
    ) : UnifiedMusicSource {
        var detailCalls = 0
        var batchDetailCalls = 0
        var urlCalls = 0
        var preloadGate: CompletableDeferred<Unit>? = null
        val urlRequestMediaIds = CopyOnWriteArrayList<String>()
        val preloadRequests = CopyOnWriteArrayList<String>()

        override suspend fun getTrackDetail(mediaId: String): BackendResult<TrackSummary> {
            detailCalls++
            if (detailCalls <= detailFailures) return BackendResult.Error("Provider TIMEOUT")
            return BackendResult.Success(fakeSummary(mediaId))
        }

        override suspend fun getTrackDetails(mediaIds: List<String>): BackendResult<List<TrackSummary>> {
            batchDetailCalls++
            return BackendResult.Success(mediaIds.map(::fakeSummary))
        }

        override suspend fun getSongUrl(mediaId: String, level: String): BackendResult<SongUrl> {
            urlCalls++
            urlRequestMediaIds += mediaId
            if (urlCalls <= urlFailures) return BackendResult.Error("Connection timeout")
            return BackendResult.Success(SongUrl("https://cdn/${mediaId.substringAfterLast('/')}", level, null, null))
        }

        override suspend fun preloadTrack(mediaId: String, level: String): BackendResult<SongUrl> {
            preloadRequests += mediaId
            preloadGate?.await()
            return BackendResult.Success(SongUrl("https://preloaded/${mediaId.substringAfterLast('/')}", level, null, null))
        }

        override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
            BackendResult.Error("not used")

        override suspend fun getUserPlaylists(providerId: String, uid: Long) =
            BackendResult.Error("not used")
    }

    private fun controller(platform: FakePlatformPlayer, source: FakeSource) = PlaybackControllerImpl(
        platform = platform,
        source = source,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(6_000L) {
            while (!condition()) delay(10L)
        }
    }

    @Test
    fun `transient track detail timeouts retry with backoff and do not duplicate the selected batch request`() = runBlocking {
        val platform = FakePlatformPlayer()
        val source = FakeSource(detailFailures = 2)
        val controller = controller(platform, source)

        controller.playQueue(listOf("test://song/1"), 0)
        awaitCondition { platform.loadedUrls.isNotEmpty() }

        assertEquals(3, source.detailCalls, "详情调用最多三次，超时后应按退避重试")
        assertEquals(0, source.batchDetailCalls, "当前曲目详情由播放加载器解析，不应再同时批量请求")
        assertEquals("https://cdn/1", platform.loadedUrls.single())
        assertEquals(listOf("test://song/1"), controller.state.value.queue.map { it.mediaId })
        controller.release()
    }

    @Test
    fun `transient playback URL failures retry and a normal cache miss still plays the remote stream`() = runBlocking {
        val platform = FakePlatformPlayer()
        val source = FakeSource(urlFailures = 2)
        val controller = controller(platform, source)
        controller.setQuality("standard")

        controller.playQueue(listOf("test://song/2"), 0)
        awaitCondition { platform.loadedUrls.isNotEmpty() }

        assertEquals(3, source.urlCalls, "URL 解析应最多三次，短暂超时后自动恢复")
        assertEquals("https://cdn/2", platform.loadedUrls.single(), "无本地缓存时应正常走远程流")
        assertNull(controller.state.value.error, "正常 cache miss 不应显示播放错误")
        assertEquals(listOf("test://song/2"), controller.state.value.queue.map { it.mediaId })
        controller.release()
    }

    @Test
    fun `asynchronous player network error retries automatically`() = runBlocking {
        val platform = FakePlatformPlayer().apply { failFirstLoadWithNetworkError = true }
        val source = FakeSource()
        val controller = controller(platform, source)

        controller.playQueue(listOf("test://song/3"), 0)
        awaitCondition { platform.loadedUrls.size >= 2 && platform.state.value == PlatformPlaybackState.Playing }

        assertEquals(listOf("https://cdn/3", "https://cdn/3"), platform.loadedUrls)
        assertNull(controller.state.value.error, "重试成功后应清除 mini player 的旧错误")
        controller.release()
    }

    @Test
    fun `navigating away cancels a pending player retry for the old track`() = runBlocking {
        val platform = FakePlatformPlayer().apply { failFirstLoadWithNetworkError = true }
        val controller = controller(platform, FakeSource())

        controller.playQueue(listOf("test://song/4", "test://song/5"), 0)
        awaitCondition { platform.state.value is PlatformPlaybackState.Error }
        controller.playAt(1)
        awaitCondition { platform.loadedUrls.any { it == "https://cdn/5" } }
        delay(500L)

        assertEquals(listOf("https://cdn/4", "https://cdn/5"), platform.loadedUrls,
            "过期的上一首重试不得覆盖用户已经切换的曲目")
        assertTrue(controller.state.value.currentIndex == 1)
        controller.release()
    }

    @Test
    fun `automatic next track consumes an in-flight preload URL instead of cancelling and resolving it again`() = runBlocking {
        val platform = FakePlatformPlayer()
        val source = FakeSource().apply { preloadGate = CompletableDeferred() }
        val controller = controller(platform, source)

        controller.playQueue(listOf("test://song/6", "test://song/7"), 0)
        awaitCondition { platform.loadedUrls.contains("https://cdn/6") }
        awaitCondition { source.preloadRequests.contains("test://song/7") }

        platform.state.value = PlatformPlaybackState.Ended
        awaitCondition { controller.state.value.currentIndex == 1 }
        source.preloadGate?.complete(Unit)
        awaitCondition { platform.loadedUrls.contains("https://preloaded/7") }

        assertEquals(1, source.preloadRequests.count { it == "test://song/7" })
        assertTrue("test://song/7" !in source.urlRequestMediaIds,
            "同一首下一曲的 URL 已预加载，不应再向 Provider 重复解析")
        assertEquals("https://preloaded/7", platform.loadedUrls.last())
        controller.release()
    }

}

private fun fakeSummary(mediaId: String) = TrackSummary(
    id = mediaId,
    name = "Track ${mediaId.substringAfterLast('/')}",
    artist = "Test Artist",
    album = "Test Album",
    coverUrl = null,
    durationMs = 180_000L,
)
