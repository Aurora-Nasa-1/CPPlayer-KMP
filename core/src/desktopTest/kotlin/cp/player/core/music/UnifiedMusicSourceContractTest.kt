package cp.player.core.music

import cp.player.core.BackendResult
import cp.player.core.local.LocalMediaSource
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.MediaType
import cp.player.core.provider.*
import cp.player.core.provider.typed.*
import cp.player.sdk.*
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class UnifiedMusicSourceContractTest {
    private class Local(items: List<LocalMediaItem> = emptyList()) : LocalMediaSource by SdkEmptyLocal() {
        private val state = MutableStateFlow(items)
        override fun items() = state
    }

    private fun provider(onGet: (String) -> Unit = {}) = object : MusicProvider {
        override suspend fun capabilities() = Capabilities(listOf(Capability.SEARCH, Capability.GET, Capability.RESOLVE))
        override suspend fun search(request: SearchRequest) = TrackPage(
            listOf(Track("001", "Short", listOf(Artist("artist-A", "Artist")), 9_500)), null, null,
        )
        override suspend fun get(request: IdRequest): Track {
            onGet(request.id)
            if (request.id == "missing") throw ProviderException(Failure(FailureCategory.NOT_FOUND, "Missing"))
            return Track(request.id, request.id, durationMs = 9_500)
        }
        override suspend fun resolve(request: ResolveRequest) = MediaResource(
            "https://audio.example/track", "actual-quality", listOf(Header("X-Track", request.id)),
            format = null, bitrate = null,
        )
    }

    private fun host(provider: MusicProvider = provider()) =
        ProviderHost(ProviderCallContext(ProviderSessionKey("test", "default", 0)), provider)

    @Test
    fun `invalid IDs return errors and local lookup never creates an SDK session`() {
        runBlocking {
            val item = LocalMediaItem("/audio/short.wav", "Short", durationMs = 9_500, mediaType = MediaType.AUDIO)
            val source = UnifiedMusicSourceImpl(Local(listOf(item)), { error("Local operation opened a provider") })
            assertIs<BackendResult.Error>(source.getTrackDetail("bad"))
            assertIs<BackendResult.Error>(source.getSongUrl("test://song"))
            assertIs<BackendResult.Error>(source.getTrackDetail("local://song/"))
            assertIs<BackendResult.Error>(source.getSongUrl("local://audio//not-registered"))
            assertEquals(9_500L, source.getTrackDetail("local://audio//audio/short.wav").getOrNull()?.durationMs)
            assertEquals("/audio/short.wav", source.preloadTrack("local://audio//audio/short.wav").getOrNull()?.url)
            assertEquals(emptyList(), source.getTrackDetails(emptyList()).getOrNull())
            assertEquals(1, source.getTrackDetails(listOf("bad", "local://audio//audio/short.wav")).getOrNull()?.size)
        }
    }

    @Test
    fun `mixed batches preserve order duplicates and case without querying a foreign provider`() {
        runBlocking {
            val calls = mutableListOf<String>()
            val host = host(provider { calls += it })
            val local = Local(listOf(LocalMediaItem("/audio/local.wav", "Local", mediaType = MediaType.AUDIO)))
            val source = UnifiedMusicSourceImpl(local, { host })
            val ids = listOf("bad", "local://audio//audio/local.wav", "other://song/A",
                "test://song/A", "test://song/a", "test://song/missing", "test://song/A")
            try {
                val outcomes = assertNotNull(source.getTrackOutcomes(ids).getOrNull())
                assertEquals(ids, outcomes.map { it.mediaId })
                assertEquals(listOf("InvalidId", null, "Unsupported", null, null, "NotFound", null), outcomes.map { it.error })
                assertEquals(listOf("A", "a", "missing", "A"), calls)
                assertEquals("A", outcomes[3].track?.name)
                assertEquals("a", outcomes[4].track?.name)
                assertEquals(9_500L, outcomes[3].track?.durationMs)
            } finally { host.close() }
        }
    }

    @Test
    fun `local items survive missing provider and oversized IDs in a batch`() {
        runBlocking {
            val local = Local(listOf(LocalMediaItem("/short.wav", "Local", mediaType = MediaType.AUDIO)))
            val source = UnifiedMusicSourceImpl(local)
            val ids = listOf("test://song/${"x".repeat(1025)}", "test://song/A", "local://audio//short.wav")
            val outcomes = assertNotNull(source.getTrackOutcomes(ids).getOrNull())
            assertEquals(listOf("InvalidId", "NoProvider", null), outcomes.map { it.error })
            assertEquals("Local", outcomes.last().track?.name)
            val brokenSession = UnifiedMusicSourceImpl(local, { error("Factory is broken") })
            val partial = assertNotNull(brokenSession.getTrackOutcomes(ids).getOrNull())
            assertEquals(listOf("InvalidId", "InvalidResponse", null), partial.map { it.error })
            assertEquals("Local", partial.last().track?.name)
        }
    }

    @Test
    fun `downloaded originals remain available without activating their provider`() {
        val file = Files.createTempFile("sdk-offline-", ".flac").toFile()
        try {
            runBlocking {
                val local = Local(listOf(LocalMediaItem(
                    file.absolutePath, "Offline", durationMs = 9_500, mediaType = MediaType.AUDIO,
                    originalMediaId = "old-provider://song/001",
                )))
                val source = UnifiedMusicSourceImpl(local, { error("Offline request opened a provider") })
                assertEquals("Offline", source.getTrackDetail("old-provider://song/001").getOrNull()?.name)
                assertEquals(file.absolutePath, source.getSongUrl("old-provider://song/001").getOrNull()?.url)
                assertEquals(2, source.getTrackDetails(List(2) { "old-provider://song/001" }).getOrNull()?.size)
            }
        } finally { file.delete() }
    }

    @Test
    fun `search pagination IDs duration and unknown metadata are not guessed`() {
        runBlocking {
            val host = host()
            val source = UnifiedMusicSourceImpl(Local(), { host })
            try {
                val page = assertNotNull(source.searchPage("q", 1, 30, 3).getOrNull())
                assertEquals("test://song/001", page.songs.single().id)
                assertEquals(9_500L, page.songs.single().durationMs)
                assertEquals("artist-A", page.songs.single().artists.single().resourceId)
                assertNull(page.hasMore)
                assertNull(page.total)
                assertIs<BackendResult.Error>(source.search("q", providers = emptyList()))
                assertIs<BackendResult.Unsupported>(source.search("q", providers = listOf("test", "other")))
                assertIs<BackendResult.Unsupported>(source.search("q", type = 10))
                assertIs<BackendResult.Unsupported>(source.getTrackDetail("other://song/001"))
                assertIs<BackendResult.Unsupported>(source.getLyric("test://song/001"))
                val resource = assertNotNull(source.getSongUrl("test://song/001", "lossless").getOrNull())
                assertEquals("actual-quality", resource.level)
                assertEquals(mapOf("X-Track" to "001"), resource.headers)
                assertNull(resource.codec)
                assertNull(resource.bitrate)
                assertNull(resource.cookie)
            } finally { host.close() }
        }
    }

    @Test
    fun `cancellation is not converted into an empty batch success`() {
        runBlocking {
            val host = host(provider { throw CancellationException("cancelled") })
            val source = UnifiedMusicSourceImpl(Local(), { host })
            try {
                assertFailsWith<CancellationException> { source.getTrackDetails(listOf("test://song/001")) }
            } finally { host.close() }
        }
    }
}
