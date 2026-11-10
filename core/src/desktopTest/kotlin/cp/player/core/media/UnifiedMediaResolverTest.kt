package cp.player.core.media

import cp.player.core.BackendResult
import cp.player.core.local.LocalMediaSource
import cp.player.core.music.*
import cp.player.core.provider.SdkEmptyLocal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class UnifiedMediaResolverTest {
    private class Local(items: List<LocalMediaItem> = emptyList()) : LocalMediaSource by SdkEmptyLocal() {
        private val state = MutableStateFlow(items)
        override fun items() = state
    }

    @Test
    fun `registered local media works and resource types cannot be confused`() {
        runBlocking {
            val local = Local(listOf(LocalMediaItem(
                "D:/concert.mp4", "Concert", mediaType = MediaType.VIDEO, durationMs = 9_500,
            )))
            val resolver = UnifiedMediaResolverImpl(local, UnifiedMusicSourceImpl(local))
            val stream = assertNotNull(resolver.resolveStream("local://video/D:/concert.mp4").getOrNull())
            assertEquals("Concert", stream.title)
            assertEquals(9_500L, stream.durationMs)
            assertEquals("original", stream.currentQuality?.id)
            assertIs<BackendResult.Error>(resolver.resolveStream("local://audio/D:/concert.mp4"))
            assertIs<BackendResult.Error>(resolver.resolveStream("local://video/D:/missing.mp4"))
            assertIs<BackendResult.Error>(resolver.getAvailableQualities("local://video/D:/missing.mp4"))
        }
    }

    @Test
    fun `audio resolver uses unified source exactly once and forwards only explicit headers`() {
        runBlocking {
            val local = Local()
            var calls = 0
            val source = object : UnifiedMusicSource by UnifiedMusicSourceImpl(local) {
                override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> {
                    calls++
                    assertEquals("test://song/001", mediaId)
                    assertEquals("hires", level)
                    return BackendResult.Success(SongUrl(
                        "https://audio.example/stream", "lossless", 123L, null,
                        headers = mapOf("Authorization" to "resource-token"), qualityKey = "flac",
                    ))
                }
            }
            val resolver = UnifiedMediaResolverImpl(local, source)
            val stream = assertNotNull(resolver.resolveStream("test://song/001", "hires").getOrNull())
            assertEquals(1, calls)
            assertEquals(mapOf("Authorization" to "resource-token"), stream.headers)
            assertEquals("flac", stream.currentQuality?.id)
            assertEquals(1, stream.availableQualities.size)
            // Video now has a typed route, but this fixture has no active provider session.
            assertIs<BackendResult.Error>(resolver.resolveStream("test://mv/001"))
            assertIs<BackendResult.Error>(resolver.getAvailableQualities("test://song/001"))
            assertEquals(1, calls)
        }
    }

    @Test
    fun `resolver preserves cancellation from SDK`() {
        runBlocking {
            val local = Local()
            val source = object : UnifiedMusicSource by UnifiedMusicSourceImpl(local) {
                override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> =
                    throw CancellationException("cancelled")
            }
            assertFailsWith<CancellationException> {
                UnifiedMediaResolverImpl(local, source).resolveStream("test://song/001")
            }
        }
    }
}
