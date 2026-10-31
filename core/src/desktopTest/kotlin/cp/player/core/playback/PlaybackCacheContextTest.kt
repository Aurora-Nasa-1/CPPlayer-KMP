package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.cache.stableHash64
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCacheContextTest {
    private class Fixture {
        var namespace = "account-a"
        val requestedLevels = mutableListOf<String>()
        val loadedUrls = mutableListOf<String>()
        val metadata = mutableListOf<PlaybackMetadata?>()
        val playedUrls = mutableListOf<String>()
        val localizedKeys = mutableListOf<String>()
        var onLoad: suspend () -> Unit = {}
        var resolveUrl: suspend (String, String) -> SongUrl = { id, level ->
            SongUrl("https://cdn/$namespace/$id", level, null, null)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = object : UnifiedMusicSource {
            override suspend fun getTrackDetail(mediaId: String) = BackendResult.Success(
                TrackSummary(mediaId, "Track", "Artist", "Album", null, 180000L),
            )

            override suspend fun getTrackDetails(mediaIds: List<String>) =
                BackendResult.Success(mediaIds.map { getTrackDetail(it).getOrNull()!! })

            override suspend fun getSongUrl(mediaId: String, level: String): BackendResult<SongUrl> {
                requestedLevels += level
                return BackendResult.Success(resolveUrl(mediaId, level))
            }

            override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
                BackendResult.Error("not used")

            override suspend fun getUserPlaylists(providerId: String, uid: Long) =
                BackendResult.Error("not used")
        }
        val platform = object : PlatformPlayer {
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
                loadedUrls += url
                this@Fixture.metadata += metadata
                onLoad()
            }

            override fun play() {
                playedUrls += loadedUrls.last()
                state.value = PlatformPlaybackState.Playing
            }

            override fun pause() { state.value = PlatformPlaybackState.Paused }
            override fun stop() { state.value = PlatformPlaybackState.Idle }
            override fun seekTo(positionMs: Long) {}
            override fun release() {}
            override fun setVolume(volume: Float) {}
            override fun getVolume() = 1f
        }
        private val localizer = object : StreamLocalizer {
            override suspend fun localize(
                url: String,
                cacheKey: String,
                headers: Map<String, String>,
                meta: SongCacheMeta?,
            ): String? {
                localizedKeys += cacheKey
                return null
            }
        }
        @Suppress("UNCHECKED_CAST")
        private val api = java.lang.reflect.Proxy.newProxyInstance(
            MusicApiService::class.java.classLoader, arrayOf(MusicApiService::class.java),
        ) { _, method, _ -> error("unused: ${method.name}") } as MusicApiService
        val controller = PlaybackControllerImpl(
            platform = platform,
            source = source,
            api = api,
            cookieProvider = { namespace },
            scope = scope,
            streamLocalizer = localizer,
            cacheNamespace = { namespace },
        ).also {
            it.setPreloadNext(false)
            it.setQuality("lossless")
        }

        fun close() {
            controller.release()
            scope.cancel()
        }

        fun key(level: String = "lossless") = "$MEDIA_ID@$level#${stableHash64(namespace)}"
    }

    @Test
    fun `account changes during URL resolution discard the old response`() = runBlocking {
        val f = Fixture()
        val firstResponse = CompletableDeferred<Unit>()
        f.resolveUrl = { id, level ->
            val account = f.namespace
            if (f.requestedLevels.size == 1) firstResponse.await()
            SongUrl("https://cdn/$account/$id", level, null, null)
        }
        try {
            f.controller.playQueue(listOf(MEDIA_ID), 0)
            assertTrue(f.loadedUrls.isEmpty())
            f.namespace = "account-b"
            firstResponse.complete(Unit)

            assertEquals(listOf("lossless", "lossless"), f.requestedLevels)
            assertEquals(listOf("https://cdn/account-b/$MEDIA_ID"), f.playedUrls)
            assertEquals(listOf(f.key()), f.metadata.map { it?.cacheKey })
            assertEquals(listOf(f.key()), f.localizedKeys)
        } finally {
            f.close()
        }
    }

    @Test
    fun `account changes while loading do not start the old media`() = runBlocking {
        val f = Fixture()
        f.onLoad = { f.namespace = "account-b" }
        try {
            f.controller.playQueue(listOf(MEDIA_ID), 0)

            assertEquals(2, f.loadedUrls.size)
            assertEquals(listOf("https://cdn/account-b/$MEDIA_ID"), f.playedUrls)
            assertEquals(listOf(f.key()), f.localizedKeys)
        } finally {
            f.close()
        }
    }

    @Test
    fun `fallback responses cannot use a new account cache identity`() = runBlocking {
        val f = Fixture()
        f.onLoad = {
            if (f.loadedUrls.size == 1) error("unsupported codec")
        }
        f.resolveUrl = { id, level ->
            val account = f.namespace
            if (level == "standard") f.namespace = "account-b"
            SongUrl("https://cdn/$account/$id", level, null, null)
        }
        try {
            f.controller.playQueue(listOf(MEDIA_ID), 0)

            assertEquals(listOf("lossless", "standard", "lossless"), f.requestedLevels)
            assertEquals(listOf("https://cdn/account-b/$MEDIA_ID"), f.playedUrls)
            assertEquals(f.key(), f.metadata.last()?.cacheKey)
        } finally {
            f.close()
        }
    }

    @Test
    fun `actual quality determines caching when provider downgrades the request`() = runBlocking {
        val f = Fixture()
        f.resolveUrl = { id, _ -> SongUrl("https://cdn/$id", "standard", null, null) }
        try {
            f.controller.playQueue(listOf(MEDIA_ID), 0)

            assertEquals(listOf("lossless"), f.requestedLevels)
            assertEquals(f.key("standard"), f.metadata.single()?.cacheKey)
            assertEquals("standard", f.controller.state.value.qualityLevel)
            assertTrue(f.localizedKeys.isEmpty())
            assertFalse(f.controller.state.value.isLocalizing)
        } finally {
            f.close()
        }
    }

    @Test
    fun `preload response from a changed account is never localized`() = runBlocking {
        val f = Fixture()
        f.resolveUrl = { id, level ->
            val account = f.namespace
            if (id != MEDIA_ID) f.namespace = "account-b"
            SongUrl("https://cdn/$account/$id", level, null, null)
        }
        try {
            f.controller.playQueue(listOf(MEDIA_ID, "nas://song/next"), 0)
            f.controller.setPreloadNext(true)
            delay(1800)

            assertEquals(2, f.requestedLevels.size)
            assertEquals(listOf("$MEDIA_ID@lossless#${stableHash64("account-a")}"), f.localizedKeys)
        } finally {
            f.close()
        }
    }

    private companion object {
        const val MEDIA_ID = "nas://song/001"
    }
}
