package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「无损曲边播边落盘 + 拖动时才切换」的接线回归测试。
 *
 * 钉住五件事：
 * 1. **落盘不阻塞开播** —— 引擎立刻拿到流地址，而不是等整曲下完才出声。
 *    （上一版实现是 `await localize()` 之后才 `load()`，一首 4 分钟无损要等 25–35 MB 下完，
 *    这是它最要命的问题，这条断言就是防止它再回来。）
 * 2. **命中缓存就直接播本地** —— 秒开且立刻可拖，不重复下载。
 * 3. **只对无损档位**落盘 —— 对 MP3/AAC 也落盘是纯粹的损失（本来就能定位）。
 * 4. **落盘失败不影响播放** —— 继续放流（能播但不能拖），绝不因为缓存问题让用户听不了歌。
 * 5. **拖动时才切换到本地副本** —— 桌面引擎定位不了 FLAC over HTTP，只有本地文件拖得动。
 */
class LosslessLocalizationWiringTest {

    // ============ 测试替身 ============

    private class RecordingPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        /** `URL to 起始位置`，按调用顺序记录。 */
        val loads = mutableListOf<Pair<String, Long>>()

        override suspend fun load(
            url: String,
            startPositionMs: Long,
            headers: Map<String, String>,
            metadata: PlaybackMetadata?,
        ) {
            loads += url to startPositionMs
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

    private class FixedSource(private val url: String) : UnifiedMusicSource {
        override suspend fun getTrackDetail(mediaId: String) = BackendResult.Success(summaryOf(mediaId))

        override suspend fun getTrackDetails(mediaIds: List<String>) =
            BackendResult.Success(mediaIds.map { summaryOf(it) })

        override suspend fun getSongUrl(mediaId: String, level: String) =
            BackendResult.Success(SongUrl(url = url, level = level, sizeBytes = null, expireAt = null))

        override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
            BackendResult.Error("not used in this test")

        override suspend fun getUserPlaylists(providerId: String, uid: Long) =
            BackendResult.Error("not used in this test")
    }

    /**
     * 记录调用；[result] 决定落盘成功（返回本地路径）还是失败（返回 null）。
     *
     * [cached] 非空表示「本地已有完整副本」；[gate] 非空时 [localize] 会一直挂到它完成，
     * 用来制造「还在落盘」的窗口。
     */
    private class RecordingLocalizer(
        private val result: String?,
        private val cached: String? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : StreamLocalizer {
        val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
        val cacheQueries = mutableListOf<String>()

        override fun cachedPath(cacheKey: String): String? {
            cacheQueries += cacheKey
            return cached
        }

        override suspend fun localize(
            url: String,
            cacheKey: String,
            headers: Map<String, String>,
        ): String? {
            calls += Triple(url, cacheKey, headers)
            gate?.await()
            return result
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

    private fun controller(platform: PlatformPlayer, source: UnifiedMusicSource, localizer: StreamLocalizer) =
        PlaybackControllerImpl(
            platform = platform,
            source = source,
            api = throwingApi(),
            cookieProvider = { null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            streamLocalizer = localizer,
        )

    // ============ 辅助 ============

    private suspend fun awaitLoads(player: RecordingPlayer, count: Int): List<Pair<String, Long>> {
        val ok = withTimeoutOrNull(5_000L) {
            while (player.loads.size < count) delay(10)
            true
        }
        require(ok == true) { "只观察到 ${player.loads.size} 次 load，期望 $count 次：${player.loads}" }
        return player.loads.toList()
    }

    private suspend fun awaitLoad(player: RecordingPlayer): String = awaitLoads(player, 1).first().first

    private suspend fun awaitNotLocalizing(c: PlaybackControllerImpl) {
        val ok = withTimeoutOrNull(5_000L) {
            while (c.state.value.isLocalizing) delay(10)
            true
        }
        require(ok == true) { "isLocalizing 一直没有收回——UI 会永久禁用进度条" }
    }

    // ============ 用例 ============

    @Test
    fun `lossless starts streaming immediately and localizes in the background`() = runBlocking {
        val player = RecordingPlayer()
        val gate = CompletableDeferred<Unit>()
        val localizer = RecordingLocalizer(result = LOCAL_PATH, gate = gate)
        val c = controller(player, FixedSource(REMOTE_URL), localizer)

        c.setQuality("lossless")
        c.playQueue(listOf(MEDIA_ID), startIndex = 0)

        // 关键：落盘还挂着（gate 未放行），引擎就必须已经拿到流地址开播了。
        assertEquals(
            REMOTE_URL,
            awaitLoad(player),
            "落盘期间必须先放流开播，绝不能等整曲下完才出声",
        )
        assertTrue(c.state.value.isLocalizing, "落盘期间要告诉 UI「正在缓存」，否则进度条被禁用却没理由")

        gate.complete(Unit)
        awaitNotLocalizing(c)

        assertEquals(1, localizer.calls.size, "应恰好落地一次")
        assertEquals(REMOTE_URL, localizer.calls.single().first)
        assertTrue(
            localizer.calls.single().second.contains(MEDIA_ID),
            "缓存键必须含 mediaId，否则不同曲目会互相命中：${localizer.calls.single().second}",
        )
        assertEquals(1, player.loads.size, "落盘完成不该立刻切源（换源会有咔哒声）")
    }

    @Test
    fun `a cached lossless track plays the local file right away`() = runBlocking {
        val player = RecordingPlayer()
        val localizer = RecordingLocalizer(result = null, cached = LOCAL_PATH)
        val c = controller(player, FixedSource(REMOTE_URL), localizer)

        c.setQuality("lossless")
        c.playQueue(listOf(MEDIA_ID), startIndex = 0)

        assertEquals(LOCAL_PATH, awaitLoad(player), "命中缓存就该直接播本地：秒开且立刻可拖")
        assertTrue(localizer.calls.isEmpty(), "命中缓存不该再下载一次")
        assertFalse(c.state.value.isLocalizing, "命中缓存不需要等，不该挂「缓存中」提示")
    }

    @Test
    fun `lossy quality keeps streaming directly and never localizes`() = runBlocking {
        val player = RecordingPlayer()
        val localizer = RecordingLocalizer(result = LOCAL_PATH)
        val c = controller(player, FixedSource(REMOTE_URL), localizer)

        c.setQuality("exhigh")
        c.playQueue(listOf(MEDIA_ID), startIndex = 0)

        assertEquals(REMOTE_URL, awaitLoad(player), "MP3/AAC 流本来就能定位，不该为它多下整曲")
        assertTrue(localizer.calls.isEmpty(), "非无损档位不应触发展盘")
        assertTrue(localizer.cacheQueries.isEmpty(), "非无损档位连缓存都不该查")
    }

    @Test
    fun `localization failure keeps playing the stream`() = runBlocking {
        val player = RecordingPlayer()
        val localizer = RecordingLocalizer(result = null)
        val c = controller(player, FixedSource(REMOTE_URL), localizer)

        c.setQuality("lossless")
        c.playQueue(listOf(MEDIA_ID), startIndex = 0)

        assertEquals(
            REMOTE_URL,
            awaitLoad(player),
            "落盘失败必须继续放流（能播但不能拖），不能因为缓存问题让用户听不了歌",
        )
        awaitNotLocalizing(c)
        assertEquals(1, localizer.calls.size)
        assertEquals(1, player.loads.size, "失败时不该有第二次 load")
    }

    @Test
    fun `the first seek after localization switches the engine to the local file`() = runBlocking {
        val player = RecordingPlayer()
        val localizer = RecordingLocalizer(result = LOCAL_PATH)
        val c = controller(player, FixedSource(REMOTE_URL), localizer)

        c.setQuality("lossless")
        c.playQueue(listOf(MEDIA_ID), startIndex = 0)
        assertEquals(REMOTE_URL, awaitLoad(player))
        awaitNotLocalizing(c)

        // 第一次拖动：本地副本已就绪 ⇒ 切到本地文件并落到目标位置。
        c.seekTo(60_000L)
        val loads = awaitLoads(player, 2)

        assertEquals(
            LOCAL_PATH,
            loads[1].first,
            "本地副本就绪后，拖动必须切到本地文件——桌面引擎定位不了 FLAC over HTTP",
        )
        assertEquals(60_000L, loads[1].second, "切换必须落在用户拖到的位置，而不是回到 0")
    }

    @Test
    fun `a seek before localization completes does not switch to a missing copy`() = runBlocking {
        val player = RecordingPlayer()
        val gate = CompletableDeferred<Unit>()
        val localizer = RecordingLocalizer(result = LOCAL_PATH, gate = gate)
        val c = controller(player, FixedSource(REMOTE_URL), localizer)

        c.setQuality("lossless")
        c.playQueue(listOf(MEDIA_ID), startIndex = 0)
        assertEquals(REMOTE_URL, awaitLoad(player))

        // 还在落盘（本地副本不存在）：不能切，只能把 seek 发给当前的流。
        c.seekTo(60_000L)
        delay(80)
        assertEquals(1, player.loads.size, "本地副本还没就绪时不能切源")
        assertEquals(60_000L, player.positionMs.value, "此时 seek 应直接下发给引擎")

        gate.complete(Unit)
        awaitNotLocalizing(c)
    }

    @Test
    fun `lossless tiers are recognized and lossy ones are not`() {
        val localizer = DesktopStreamLocalizer(cacheDir = java.io.File("build-verify-tmp-unused"))
        assertTrue(localizer.isLocalizing("lossless"))
        assertTrue(localizer.isLocalizing("hires"))
        assertFalse(localizer.isLocalizing("exhigh"))
        assertFalse(localizer.isLocalizing("standard"))
    }

    @Test
    fun `the android and test default never localizes`() {
        val noop = NoOpStreamLocalizer
        assertFalse(noop.isLocalizing("lossless"), "安卓的 actual 必须是空实现（ExoPlayer 能定位 HTTP FLAC）")
        assertEquals(null, noop.cachedPath("key"), "默认实现必须报「没有本地副本」")
        runBlocking {
            assertEquals(null, noop.localize(REMOTE_URL, "key", emptyMap()))
        }
    }

    private companion object {
        const val MEDIA_ID = "cp_api://song/12345"
        const val REMOTE_URL = "https://cdn.example.com/song/12345.flac"
        const val LOCAL_PATH = "/cache/abc.flac"
    }
}

/** 与 [PlaybackSeekResilienceTest] 里的同名辅助保持一致（各自私有，互不影响）。 */
private fun summaryOf(mediaId: String) = TrackSummary(
    id = mediaId,
    name = "t",
    artist = "a",
    album = "b",
    coverUrl = null,
    durationMs = 200_000L,
)
