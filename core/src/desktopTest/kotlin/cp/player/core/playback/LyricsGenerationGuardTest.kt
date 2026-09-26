package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.testing.ManualDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 歌词请求的**世代守卫**回归测试。
 *
 * ### 修复的问题
 * `playCurrent` 早就有 `loadGeneration` 世代守卫，但 `refreshLyrics` 没有：
 * 切歌时 `lyricsJob?.cancel()` 取消旧请求，旧协程在 `catch (e: Throwable)` 里
 * **把 `CancellationException` 也当成失败**，于是把 `LyricsState.Error` 写下去 ——
 * 而此时新曲目的 `Loading` 刚写完，表现为切歌瞬间闪一下「歌词获取失败」。
 *
 * ### 为什么不能只看最终状态
 * 该产物是**瞬时**的：旧请求的 `Error` 一定排在 `B` 的 `Loading` 之前（取消是同步入队的），
 * 最终值仍是 `Loading`。所以本测试**记录出现过的每一个歌词状态**，断言 `Error` 从未出现。
 *
 * 用 [ManualDispatcher] 精确复现生产环境 `scope` 挂在 `Dispatchers.Main`（单线程队列）的行为。
 */
class LyricsGenerationGuardTest {

    // ============ 测试替身 ============

    private class FakePlatformPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        override suspend fun load(
            url: String,
            startPositionMs: Long,
            headers: Map<String, String>,
            metadata: PlaybackMetadata?,
        ) {
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

    private class FakeSource : UnifiedMusicSource {
        override suspend fun getTrackDetail(mediaId: String) = BackendResult.Success(summaryOf(mediaId))
        override suspend fun getTrackDetails(mediaIds: List<String>) =
            BackendResult.Success(mediaIds.map { summaryOf(it) })

        override suspend fun getSongUrl(mediaId: String, level: String) = BackendResult.Success(
            SongUrl(
                url = "https://cdn/${mediaId.substringAfterLast('/')}",
                level = level,
                sizeBytes = null,
                expireAt = null,
            ),
        )

        override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
            BackendResult.Error("not used in this test")

        override suspend fun getUserPlaylists(providerId: String, uid: Long) =
            BackendResult.Error("not used in this test")
    }

    /**
     * 歌词接口可控挂起：每个 songId 一个闸门，测试不放行就永远停在 `await()`。
     *
     * 用 `by StubApi` 拿到其余 120+ 个方法的「调用即抛」默认实现。
     * 委托基类必须放在**本嵌套类自己的 companion** 里 —— 嵌套类默认不是 `inner`，
     * 引用外层类的成员会报 `Outer class of non-inner class cannot be used as receiver`。
     */
    private class GatedLyricApi(
        private val gates: Map<String, CompletableDeferred<JsonElement>>,
    ) : MusicApiService by StubApi {
        override suspend fun getLyric(songId: String): JsonElement =
            gates[songId]?.await()
                ?: throw UnsupportedOperationException("测试未为 songId=$songId 准备闸门")

        private companion object {
            @Suppress("UNCHECKED_CAST")
            val StubApi: MusicApiService = java.lang.reflect.Proxy.newProxyInstance(
                MusicApiService::class.java.classLoader,
                arrayOf(MusicApiService::class.java),
            ) { _, method, _ ->
                throw UnsupportedOperationException("stub api: ${method.name}")
            } as MusicApiService
        }
    }

    // ============ 测试 ============

    @Test
    fun `切歌时被取消的歌词请求不会把新曲目的歌词写成错误`() {
        val platform = FakePlatformPlayer()
        val dispatcher = ManualDispatcher()
        val gates = mapOf(
            "1" to CompletableDeferred<JsonElement>(),
            "2" to CompletableDeferred<JsonElement>(),
        )
        val controller = PlaybackControllerImpl(
            platform = platform,
            source = FakeSource(),
            api = GatedLyricApi(gates),
            cookieProvider = { null },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        // 记录出现过的每一个歌词状态。用 Unconfined：状态一写入就同步回调，
        // 不会因为调度延迟而漏掉瞬时值。
        val seen = mutableListOf<LyricsState>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            controller.state.collect { seen += it.lyrics }
        }

        try {
            runBlocking { controller.playQueue(listOf(mid(1), mid(2)), startIndex = 0) }
            dispatcher.drain()
            assertEquals(
                LyricsState.Loading,
                controller.state.value.lyrics,
                "第 1 首的歌词应停在等待上游返回",
            )

            // 切到第 2 首：第 1 首的歌词请求会被 cancel
            runBlocking { controller.playAt(1) }
            dispatcher.drain()

            assertFalse(
                seen.any { it is LyricsState.Error },
                "被取消的歌词请求不得写入 Error —— 修复前这里会闪一下「歌词获取失败」。实际序列=$seen",
            )
            assertEquals(
                LyricsState.Loading,
                controller.state.value.lyrics,
                "应停在第 2 首的 Loading（闸门未放行）",
            )
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `同曲目重复刷新歌词时旧请求也不会写成错误`() {
        val platform = FakePlatformPlayer()
        val dispatcher = ManualDispatcher()
        val gates = mapOf("1" to CompletableDeferred<JsonElement>())
        val controller = PlaybackControllerImpl(
            platform = platform,
            source = FakeSource(),
            api = GatedLyricApi(gates),
            cookieProvider = { null },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        val seen = mutableListOf<LyricsState>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            controller.state.collect { seen += it.lyrics }
        }

        try {
            runBlocking { controller.playQueue(listOf(mid(1)), startIndex = 0) }
            dispatcher.drain()
            // 同一曲目连续刷新：第一次被取消，不能污染第二次
            runBlocking { controller.refreshLyrics() }
            dispatcher.drain()

            assertFalse(
                seen.any { it is LyricsState.Error },
                "被取消的旧请求不得写入 Error。实际序列=$seen",
            )
        } finally {
            watcher.cancel()
        }
    }
}

private fun mid(n: Int) = "netease://song/$n"

private fun summaryOf(id: String) = TrackSummary(
    id = id,
    name = "曲目 ${id.substringAfterLast('/')}",
    artist = "测试歌手",
    album = "测试专辑",
    coverUrl = null,
    durationMs = 200_000L,
)
