package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackOfflineFallbackTest {

    private class RecordingPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

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

    private class MockSource(
        var trackDetailResult: BackendResult<TrackSummary> = BackendResult.Error("Offline"),
        var songUrlResult: (mediaId: String, level: String) -> BackendResult<SongUrl> = { _, _ -> BackendResult.Error("Offline") },
    ) : UnifiedMusicSource {
        val requestedLevels = mutableListOf<String>()

        override suspend fun getTrackDetail(mediaId: String): BackendResult<TrackSummary> = trackDetailResult

        override suspend fun getTrackDetails(mediaIds: List<String>): BackendResult<List<TrackSummary>> {
            val single = trackDetailResult.getOrNull()
            return if (single != null) BackendResult.Success(listOf(single)) else BackendResult.Error("Offline")
        }

        override suspend fun getSongUrl(mediaId: String, level: String): BackendResult<SongUrl> {
            requestedLevels += level
            return songUrlResult(mediaId, level)
        }

        override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
            BackendResult.Error("not used")

        override suspend fun getUserPlaylists(providerId: String, uid: Long) =
            BackendResult.Error("not used")
    }

    private class SimpleLocalizer(
        private val cached: String? = null,
    ) : StreamLocalizer {
        override fun cachedPath(cacheKey: String): String? = cached
        override suspend fun localize(
            url: String,
            cacheKey: String,
            headers: Map<String, String>,
            meta: SongCacheMeta?,
        ): String? = null
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun awaitLoads(player: RecordingPlayer, count: Int): List<Pair<String, Long>> {
        val ok = withTimeoutOrNull(5_000L) {
            while (player.loads.size < count) delay(10)
            true
        }
        assertTrue(ok == true, "超时未等到预期数量的 load: actual=${player.loads.size}, expected=$count")
        return player.loads
    }

    @Test
    fun `offline playback uses downloaded file and downloaded track metadata when remote network fails`() = runBlocking {
        val player = RecordingPlayer()
        val source = MockSource(
            trackDetailResult = BackendResult.Error("Network is unreachable"),
            songUrlResult = { _, _ -> BackendResult.Error("Network is unreachable") },
        )
        val downloadedTrack = TrackSummary(
            id = "downloaded-1",
            name = "Offline Song",
            artist = "Offline Artist",
            album = "Offline Album",
            coverUrl = null,
            durationMs = 200_000L,
        )
        val controller = PlaybackControllerImpl(
            platform = player,
            source = source,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            streamLocalizer = SimpleLocalizer(),
            downloadedFileResolver = { id -> if (id.contains("downloaded-1")) "C:/downloads/song1.mp3" else null },
            downloadedTrackResolver = { id -> if (id.contains("downloaded-1")) downloadedTrack else null },
        )

        controller.playQueue(listOf("downloaded-1"), 0)
        val loads = awaitLoads(player, 1)

        assertEquals("C:/downloads/song1.mp3", loads.first().first)
        assertEquals("Offline Song", controller.state.value.currentTrack?.name)
        assertEquals("Offline Artist", controller.state.value.currentTrack?.artist)
    }

    @Test
    fun `weak network automatically downgrades to standard when higher quality fails`() = runBlocking {
        val player = RecordingPlayer()
        val source = MockSource(
            trackDetailResult = BackendResult.Success(
                TrackSummary("online-1", "Online Song", "Online Artist", "Online Album", null, 180_000L)
            ),
            songUrlResult = { _, level ->
                if (level == "lossless") {
                    BackendResult.Error("Connection timeout")
                } else if (level == "standard") {
                    BackendResult.Success(SongUrl("https://cdn/standard.mp3", "standard", null, null))
                } else {
                    BackendResult.Error("Unsupported")
                }
            },
        )
        val controller = PlaybackControllerImpl(
            platform = player,
            source = source,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            streamLocalizer = SimpleLocalizer(),
        )
        controller.setQuality("lossless")
        controller.playQueue(listOf("online-1"), 0)

        val loads = awaitLoads(player, 1)
        assertEquals("https://cdn/standard.mp3", loads.first().first)
        assertTrue(source.requestedLevels.contains("lossless"))
        assertTrue(source.requestedLevels.contains("standard"))
    }

    @Test
    fun `offline playback falls back to stream localizer cache when download is absent`() = runBlocking {
        val player = RecordingPlayer()
        val source = MockSource(
            trackDetailResult = BackendResult.Success(
                TrackSummary("stream-cached-1", "Cached Song", "Cached Artist", "Cached Album", null, 180_000L)
            ),
            songUrlResult = { _, _ -> BackendResult.Error("Network is down") },
        )
        val localizer = SimpleLocalizer(cached = "C:/cache/stream1.mp3")
        val controller = PlaybackControllerImpl(
            platform = player,
            source = source,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            streamLocalizer = localizer,
        )

        controller.playQueue(listOf("stream-cached-1"), 0)
        val loads = awaitLoads(player, 1)

        assertEquals("C:/cache/stream1.mp3", loads.first().first)
    }
}
