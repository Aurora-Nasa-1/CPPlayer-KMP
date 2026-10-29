package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「上一曲」必须是**纯索引运算**：从真实当前曲目往前退一位，与播放进度无关。
 *
 * ### 被钉住的两个错位方向
 * 旧实现读 \`platform.positionMs.value\` 判「3 秒内回上一首、否则回本曲开头」。
 * 引擎位置是一条与索引**互不同步**的时间线：seek 是乐观写入 \`_state.positionMs\`，
 * 引擎要等 200ms 轮询（或装载窗口结束）才追上；装载窗口内它甚至还是上一首的值。
 * 于是同一个手势有两个失败方向：
 * - 引擎值**偏大**（拖到 2s 但引擎仍报 5s）⇒ 误判「播了很久」⇒ 本曲归零；
 * - 引擎值**偏小**（拖到 40s 但引擎仍报 1s）⇒ 误判「刚开头」⇒ 跳到上一首。
 *
 * 两个方向都覆盖，才能保证「上一曲不看进度」是**真的**不看，而不是碰巧对了一边。
 */
class PrevTrackProgressSourceTest {

    private class FakePlatformPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        val loadedUrls = mutableListOf<String>()
        val seekCalls = mutableListOf<Long>()

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
        override fun seekTo(positionMs: Long) {
            seekCalls += positionMs
            this.positionMs.value = positionMs
        }
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

    @Suppress("UNCHECKED_CAST")
    private fun throwingApi(): MusicApiService =
        java.lang.reflect.Proxy.newProxyInstance(
            MusicApiService::class.java.classLoader,
            arrayOf(MusicApiService::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("stub api: ${method.name}")
        } as MusicApiService

    private fun controller(platform: PlatformPlayer): PlaybackControllerImpl = PlaybackControllerImpl(
        platform = platform,
        source = FakeSource(),
        api = throwingApi(),
        cookieProvider = { null },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    /**
     * 两段「引擎位置与权威进度互相错位」的用例，方向相反 —— 这正是旧实现的病灶。
     *
     * 旧实现读 `platform.positionMs` 判 3 秒；引擎位置是一条**独立**的时间线：
     * - 拖到 2s 时它可能还停在 5s（更大）⇒ 误判「播了很久」⇒ 本曲归零（用户说的那个 bug）；
     * - 拖到 40s 时它可能还停在 1s（更小）⇒ 误判「刚开头」⇒ 跳到上一首（进度白拖）。
     *
     * 两个方向都覆盖，才能保证「上一曲不看进度」是**真的**不看，而不是碰巧对了一个方向。
     */
    @Test
    fun `引擎位置比权威进度大时上一曲仍按索引退`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform)
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 1)

        c.seekTo(2_000L)
        // 引擎还没追上乐观值：仍上报拖之前的 5 秒（比权威值大）。
        platform.positionMs.value = 5_000L

        c.skipPrevious()

        assertEquals(0, c.state.value.currentIndex, "索引必须退到上一首，不能被引擎位置劫持")
        assertEquals(listOf(2_000L), platform.seekCalls, "上一曲不应额外下发 seek")
    }

    @Test
    fun `引擎位置比权威进度小时上一曲仍按索引退`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform)
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 1)

        c.seekTo(40_000L)
        // 引擎尚未追上：仍上报拖之前的 1 秒（比权威值小）。
        platform.positionMs.value = 1_000L

        c.skipPrevious()

        assertEquals(0, c.state.value.currentIndex, "已拖到中段也不能让上一曲变成重播本曲")
        assertEquals(listOf(40_000L), platform.seekCalls, "上一曲不应额外下发 seek")
    }

    /** seek 本身绝不能改动曲目索引 —— 这是 P1 的另一半（写位置 ≠ 写索引）。 */
    @Test
    fun `seek 不改动曲目索引`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform)
        c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 1)

        repeat(20) { i -> c.seekTo((i * 3_000L).toLong()) }

        assertEquals(1, c.state.value.currentIndex, "seek 不得移动当前曲目索引")
        assertEquals("https://cdn/2", platform.loadedUrls.last(), "seek 不得重新装载相邻曲目")
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
