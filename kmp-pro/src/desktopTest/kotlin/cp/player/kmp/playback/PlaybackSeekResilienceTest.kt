package cp.player.kmp.playback

import cp.player.kmp.BackendResult
import cp.player.kmp.api.MusicApiService
import cp.player.kmp.music.SongUrl
import cp.player.kmp.music.TrackSummary
import cp.player.kmp.music.UnifiedMusicSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * seek 韧性回归测试：锁死「seek 与流媒体 / 缓存 / 未装载内容冲突导致 seek 用不了」这一组 bug。
 *
 * 对应的三条真实故障链：
 *
 * 1. **时长被清成 0**：引擎在装载/缓冲期上报 0（ExoPlayer 的 `TIME_UNSET`、rodio 取不到时长），
 *    控制器无条件把它写进 UI 状态，于是进度条 `valueRange` 从 `0..durationMs` 塌成 `0..1`，
 *    拖动只能得到 0/1 毫秒 —— 用户视角就是「seek 完全没用」。
 * 2. **装载窗口内的 seek 被吞**：`playCurrent` 解析播放地址 → `load(startPositionMs = 0)`
 *    这段窗口里引擎装的还是上一首，期间的 seek 既落错对象、又被随后的 0 覆盖。
 * 3. **越界 / 无曲目 seek**：目标没钳制到时长、队列为空时也照样下发给引擎。
 */
class PlaybackSeekResilienceTest {

    // ============ 测试替身 ============

    private class FakePlatformPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        val seekCalls = mutableListOf<Long>()
        val loadStartPositions = mutableListOf<Long>()
        val loadedCacheKeys = mutableListOf<String?>()

        override suspend fun load(
            url: String,
            startPositionMs: Long,
            headers: Map<String, String>,
            metadata: PlaybackMetadata?,
        ) {
            loadStartPositions += startPositionMs
            loadedCacheKeys += metadata?.cacheKey
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

    /** 取详情立即返回，但 `getSongUrl` 会卡在 [gate] 上——用来复现「播放地址还在解析」的装载窗口。 */
    private class GatedSource(private val gate: CompletableDeferred<Unit>?) : UnifiedMusicSource {
        override suspend fun getTrackDetail(mediaId: String) = BackendResult.Success(summaryOf(mediaId))

        override suspend fun getTrackDetails(mediaIds: List<String>) =
            BackendResult.Success(mediaIds.map { summaryOf(it) })

        override suspend fun getSongUrl(mediaId: String, level: String): BackendResult<SongUrl> {
            gate?.await()
            return BackendResult.Success(
                SongUrl(
                    url = "https://cdn/${mediaId.substringAfterLast('/')}",
                    level = level,
                    sizeBytes = null,
                    expireAt = null,
                ),
            )
        }

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

    private fun controller(
        platform: PlatformPlayer,
        source: UnifiedMusicSource,
    ): PlaybackControllerImpl = PlaybackControllerImpl(
        platform = platform,
        source = source,
        api = throwingApi(),
        cookieProvider = { null },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    // ============ 1. 时长不被引擎的 0 覆盖 ============

    @Test
    fun `engine reporting zero duration does not wipe the known duration`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, GatedSource(gate = null))

        c.playQueue(listOf(mid(1)), startIndex = 0)

        assertEquals(
            TRACK_DURATION_MS,
            c.state.value.durationMs,
            "曲目元信息里的时长必须留下——被清成 0 会让进度条范围塌成 0..1，seek 直接失效",
        )

        // 引擎给出有效时长 → 以引擎为准。
        platform.durationMs.value = 205_000L
        assertEquals(205_000L, c.state.value.durationMs)

        // 换段/缓冲时引擎又回落到 0：绝不能把已知时长清掉。
        platform.durationMs.value = 0L
        assertEquals(205_000L, c.state.value.durationMs, "引擎回落到 0 时不得覆盖已知时长")
    }

    // ============ 2. 装载窗口内的 seek ============

    @Test
    fun `seek while the url is still resolving is applied as the load start position`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val platform = FakePlatformPlayer()
        val c = controller(platform, GatedSource(gate))

        c.playQueue(listOf(mid(1)), startIndex = 0)

        // 此刻播放地址还没解析出来，引擎里没有本曲 —— 正是用户拖动进度条的窗口。
        assertTrue(platform.loadStartPositions.isEmpty(), "前置条件：load 尚未发生")

        c.seekTo(60_000L)

        assertEquals(
            60_000L,
            c.state.value.positionMs,
            "装载窗口内的 seek 必须乐观回写，否则滑条松手即回弹",
        )

        gate.complete(Unit)

        assertEquals(
            listOf(60_000L),
            platform.loadStartPositions,
            "装载窗口内的 seek 必须作为起始位置交给引擎，否则会被 load(0) 吞掉",
        )
        assertTrue(platform.seekCalls.isEmpty(), "已并入起始位置，无需额外补发 seek")
    }

    @Test
    fun `seek is forwarded to the engine directly once the track is loaded`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, GatedSource(gate = null))

        c.playQueue(listOf(mid(1)), startIndex = 0)
        c.seekTo(30_000L)

        assertEquals(listOf(30_000L), platform.seekCalls, "装载完成后 seek 应直接下发")
        assertEquals(listOf(0L), platform.loadStartPositions, "装载本身仍从 0 开始")
    }

    @Test
    fun `a seek issued during a load is not carried over to the next track`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val platform = FakePlatformPlayer()
        val c = controller(platform, GatedSource(gate))

        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)
        c.seekTo(60_000L) // 记在第 1 首名下
        c.skipNext()      // 用户随即切到第 2 首

        gate.complete(Unit)

        assertEquals(
            listOf(0L),
            platform.loadStartPositions,
            "为第 1 首暂存的 seek 不得落到第 2 首头上（否则切歌后莫名其妙跳到 1:00）",
        )
    }

    // ============ 3. 钳制与守卫 ============

    @Test
    fun `seek beyond the duration is clamped to the duration`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, GatedSource(gate = null))

        c.playQueue(listOf(mid(1)), startIndex = 0)
        c.seekTo(TRACK_DURATION_MS + 90_000L)

        assertEquals(
            listOf(TRACK_DURATION_MS),
            platform.seekCalls,
            "越界目标必须钳制到时长，而不是原样丢给引擎",
        )
    }

    @Test
    fun `negative seek targets are clamped to zero`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, GatedSource(gate = null))

        c.playQueue(listOf(mid(1)), startIndex = 0)
        c.seekTo(-5_000L)

        assertEquals(listOf(0L), platform.seekCalls)
    }

    @Test
    fun `seek with an empty queue is ignored`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, GatedSource(gate = null))

        c.seekTo(12_345L)

        assertTrue(platform.seekCalls.isEmpty(), "没有当前曲目时不应把无效目标下发给引擎")
        assertEquals(0L, c.state.value.positionMs)
    }

    // ============ 4. 缓存键 ============

    @Test
    fun `load passes a url independent cache key derived from media id and quality`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, GatedSource(gate = null))

        c.playQueue(listOf(mid(1)), startIndex = 0)

        assertEquals(
            listOf<String?>("${mid(1)}@exhigh"),
            platform.loadedCacheKeys,
            "缓存键必须稳定（mediaId@音质），不能跟着会过期的 URL 走",
        )
        assertTrue(
            platform.loadedCacheKeys.single()?.contains("cdn") != true,
            "缓存键里不允许出现 URL 片段",
        )
    }
}

private fun mid(n: Int) = "netease://song/$n"

private const val TRACK_DURATION_MS = 180_000L

private fun summaryOf(id: String) = TrackSummary(
    id = id,
    name = "曲目 $id",
    artist = "测试歌手",
    album = "测试专辑",
    coverUrl = null,
    durationMs = TRACK_DURATION_MS,
)
