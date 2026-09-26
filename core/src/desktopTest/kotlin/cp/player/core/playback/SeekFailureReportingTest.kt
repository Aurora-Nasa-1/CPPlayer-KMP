package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * seek **失败上报**的接线测试。
 *
 * ### 为什么需要它
 * [PendingSeekTracker.Tick.GaveUp] 曾经是一个**无人消费**的失败信号。于是「引擎真的定位不了」
 * 只能表现为「进度条自己弹回原位」——用户无法区分「我拖错了」和「这个音源根本不能定位」，
 * 也就是最初的「拖了没反应」。这里钉住「平台层 → 控制器 → 前端」这条链路不断。
 *
 * 单测覆盖的是**转发**这一段（确定性可测）。真正把 `Tick.GaveUp` 变成事件的那一步在
 * [AudioPlayerImpl] / 安卓 `Media3PlatformPlayer` 的轮询里，需要不可定位的音源才能触发，
 * 由 `AudioPlayerImplSeekE2ETest` 那类端到端测试负责。
 */
class SeekFailureReportingTest {

    // ============ 测试替身 ============

    /**
     * 最小平台实现：**刻意不声明** [PlatformPlayer.seekFailures]，
     * 以便验证接口的默认实现可用（测试替身与 `SilentOutputPlayer` 都依赖它，
     * 否则每个实现者都被迫写一遍这个与它无关的属性）。
     */
    private open class MinimalPlatformPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        override suspend fun load(
            url: String,
            startPositionMs: Long,
            headers: Map<String, String>,
            metadata: PlaybackMetadata?,
        ) = Unit

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun stop() = Unit
        override fun release() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun getVolume(): Float = 1f
    }

    /** 会上报 seek 失败的平台。 */
    private class ReportingPlatformPlayer : MinimalPlatformPlayer() {
        private val channel = MutableSharedFlow<SeekFailure>(extraBufferCapacity = 4)
        override val seekFailures: SharedFlow<SeekFailure> = channel.asSharedFlow()

        fun reportFailure(targetMs: Long, actualMs: Long) {
            channel.tryEmit(SeekFailure(targetMs, actualMs))
        }
    }

    private class StubSource : UnifiedMusicSource {
        override suspend fun getTrackDetail(mediaId: String) = BackendResult.Success(summaryOf(mediaId))

        override suspend fun getTrackDetails(mediaIds: List<String>) =
            BackendResult.Success(mediaIds.map { summaryOf(it) })

        override suspend fun getSongUrl(mediaId: String, level: String) =
            BackendResult.Error("not used in this test")

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
        source = StubSource(),
        api = throwingApi(),
        cookieProvider = { null },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    // ============ 1. 平台上报的失败必须传到前端 ============

    @Test
    fun `a platform seek failure reaches the controller flow`() = runBlocking {
        val platform = ReportingPlatformPlayer()
        val controller = controller(platform)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val received = mutableListOf<SeekFailure>()
        // SharedFlow 无重放，必须先订阅再上报。
        val job = controller.seekFailures.onEach { received += it }.launchIn(scope)
        yield()

        platform.reportFailure(targetMs = 90_000L, actualMs = 5_000L)
        yield()
        job.cancel()

        assertEquals(
            listOf(SeekFailure(targetMs = 90_000L, actualMs = 5_000L)),
            received,
            "平台的 seek 失败必须原样转发给前端——否则进度条只会静默弹回，用户得不到任何解释",
        )
    }

    // ============ 2. 目标位置与实际位置都要带上 ============

    @Test
    fun `the failure carries both the requested and the actual position`() = runBlocking {
        val platform = ReportingPlatformPlayer()
        val controller = controller(platform)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val received = mutableListOf<SeekFailure>()
        val job = controller.seekFailures.onEach { received += it }.launchIn(scope)
        yield()
        platform.reportFailure(targetMs = 12_000L, actualMs = 340_000L)
        yield()
        job.cancel()

        // 前端要能说清「想拖到哪儿、最后停在哪儿」，所以两个值都得有。
        assertEquals(12_000L, received.single().targetMs, "必须带上用户拖到的目标位置")
        assertEquals(340_000L, received.single().actualMs, "必须带上引擎实际停留的位置")
    }

    // ============ 3. 不覆盖该属性的实现也要能用 ============

    @Test
    fun `an implementation that does not report failures still exposes a usable flow`() {
        val platform = MinimalPlatformPlayer()

        // 必须是**稳定单例**：若默认实现里现场 `MutableSharedFlow()`，
        // 每次读属性都会新建一个对象，订阅方永远收不到任何东西。
        assertSame(
            platform.seekFailures,
            platform.seekFailures,
            "接口默认实现必须返回同一个实例，不能每次读属性都新建空流",
        )
        assertTrue(
            platform.seekFailures is SharedFlow<SeekFailure>,
            "默认值必须是可收集的 SharedFlow",
        )
    }
}

private fun summaryOf(id: String) = TrackSummary(
    id = id,
    name = "曲目 $id",
    artist = "测试歌手",
    album = "测试专辑",
    coverUrl = null,
    durationMs = 180_000L,
)
