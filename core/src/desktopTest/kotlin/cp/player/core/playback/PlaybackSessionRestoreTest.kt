package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「保留上次播放」的持久化 / 恢复回归测试。
 *
 * 锁定三件事：
 * 1. 开关打开时，启动恢复**队列与当前曲目**，但**不自动播放**（不装载引擎）；
 * 2. 恢复出来的进度在用户点播放时才交给引擎（「接着听」而非「从头开始」）；
 * 3. 开关关闭 / 清空队列时**不恢复**、且快照被删干净。
 *
 * ⚠️ 用 `Dispatchers.Unconfined`：恢复与落盘都挂在 `scope.launch` 上，
 * Unconfined 会**立即**把它们跑到第一个挂起点，于是构造完即可断言 ——
 * 生产环境的单线程 Main 队列同理（顺序执行），所以这不算「作弊」。
 */
class PlaybackSessionRestoreTest {

    // ============ 测试替身 ============

    private class MapSettings : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() { map.clear() }
    }

    private class FakePlatformPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        /** 每次 load 的 `(url, startPositionMs)` —— 用来验证「从上次进度起播」。 */
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
            BackendResult.Error("unused")

        override suspend fun getUserPlaylists(providerId: String, uid: Long) =
            BackendResult.Error("unused")
    }

    @Suppress("UNCHECKED_CAST")
    private fun throwingApi(): MusicApiService =
        java.lang.reflect.Proxy.newProxyInstance(
            MusicApiService::class.java.classLoader,
            arrayOf(MusicApiService::class.java),
        ) { _, method, _ -> throw UnsupportedOperationException("stub api: ${method.name}") } as MusicApiService

    private fun controller(
        platform: PlatformPlayer,
        source: UnifiedMusicSource,
        settings: SettingsStorage?,
    ): PlaybackControllerImpl = PlaybackControllerImpl(
        platform = platform,
        source = source,
        api = throwingApi(),
        cookieProvider = { null },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        playbackModeSettings = settings,
    )

    /** 直接写一份快照，模拟「上次退出时留下的会话」。 */
    private fun MapSettings.seedSession(
        ids: List<String>,
        index: Int,
        positionMs: Long,
        keepLast: Boolean = true,
    ) {
        putString(PlaybackSessionSettings.KEY_KEEP_LAST_PLAYBACK, keepLast.toString())
        val idsJson = ids.joinToString(",") { "\"$it\"" }
        putString(
            PlaybackSessionSettings.KEY_LAST_SESSION,
            """{"sourceId":null,"index":$index,"positionMs":$positionMs,"ids":[$idsJson]}""",
        )
    }

    // ============ 测试 ============

    @Test
    fun `restores the last queue on startup without auto-playing`() = runBlocking {
        val settings = MapSettings().apply {
            seedSession(listOf(mid(1), mid(2), mid(3)), index = 1, positionMs = 42_000L)
        }
        val platform = FakePlatformPlayer()

        val c = controller(platform, FakeSource(), settings)

        assertEquals(3, c.state.value.queue.size, "应恢复整条队列")
        assertEquals(1, c.state.value.currentIndex)
        assertEquals(mid(2), c.state.value.currentTrack?.id, "当前曲目应被解析出来（小播放器要显示它）")
        assertEquals(42_000L, c.state.value.positionMs, "应停在上次进度")
        assertFalse(c.state.value.isPlaying, "恢复不应自动播放")
        assertTrue(platform.loads.isEmpty(), "恢复不应触发引擎装载，实际=${platform.loads}")
    }

    @Test
    fun `resumes from the saved position when the user presses play`() = runBlocking {
        val settings = MapSettings().apply {
            seedSession(listOf(mid(1), mid(2)), index = 1, positionMs = 30_000L)
        }
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource(), settings)

        c.togglePlayPause()

        assertEquals("https://cdn/2", platform.loads.last().first, "应播放恢复出来的当前曲目")
        assertEquals(30_000L, platform.loads.last().second, "应从上次进度起播，而不是 0")
    }

    @Test
    fun `does not restore when the toggle is off`() = runBlocking {
        val settings = MapSettings().apply {
            seedSession(listOf(mid(1), mid(2)), index = 0, positionMs = 0L, keepLast = false)
        }
        val platform = FakePlatformPlayer()

        val c = controller(platform, FakeSource(), settings)

        assertTrue(c.state.value.queue.isEmpty(), "开关关闭时不应恢复队列")
        assertEquals(-1, c.state.value.currentIndex)
        assertTrue(platform.loads.isEmpty())
    }

    @Test
    fun `persists the queue after playQueue`() = runBlocking {
        val settings = MapSettings()
        val c = controller(FakePlatformPlayer(), FakeSource(), settings)

        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)

        val raw = settings.getString(PlaybackSessionSettings.KEY_LAST_SESSION)
        assertNotNull(raw, "播放后应写入快照")
        assertTrue(
            raw.contains(mid(1)) && raw.contains(mid(2)),
            "快照应包含队列两首歌，实际=$raw",
        )
    }

    @Test
    fun `clears the snapshot when the queue is cleared`() = runBlocking {
        val settings = MapSettings()
        val c = controller(FakePlatformPlayer(), FakeSource(), settings)
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)
        assertNotNull(
            settings.getString(PlaybackSessionSettings.KEY_LAST_SESSION),
            "前置条件：播放后应已有快照",
        )

        c.clearQueue()

        assertNull(
            settings.getString(PlaybackSessionSettings.KEY_LAST_SESSION),
            "清空队列后不应再保留快照（否则下次启动会把已清掉的队列摆回来）",
        )
    }

    @Test
    fun `turning the toggle off clears the existing snapshot`() = runBlocking {
        val settings = MapSettings().apply {
            seedSession(listOf(mid(1)), index = 0, positionMs = 0L)
        }

        // AppModel.setKeepLastPlayback(false) 的等价动作：写键 + 删快照。
        settings.putString(PlaybackSessionSettings.KEY_KEEP_LAST_PLAYBACK, "false")
        settings.remove(PlaybackSessionSettings.KEY_LAST_SESSION)

        val c = controller(FakePlatformPlayer(), FakeSource(), settings)
        assertTrue(c.state.value.queue.isEmpty())
    }
}

private fun mid(n: Int) = "netease://song/$n"

private fun summaryOf(id: String) = TrackSummary(
    id = id,
    name = "曲目 $id",
    artist = "测试歌手",
    album = "测试专辑",
    coverUrl = null,
    durationMs = 180_000L,
)
