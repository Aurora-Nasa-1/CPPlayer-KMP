package cp.player.app.repository

import cp.player.core.BackendResult
import cp.player.core.music.CatalogPage
import cp.player.core.music.MusicResult
import cp.player.core.music.PlaylistDetail
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaylistCacheTest {

    private val baseKey = PlaylistCacheKey(
        kind = "tracks",
        providerId = "provider-a",
        accountId = "user-1",
        sessionId = "source-2:account-3:cookie-4",
        playlistId = "provider-a://playlist/list-1",
        limit = 100,
        offset = 0,
        sortKey = "DEFAULT",
    )

    @Test
    fun `playlist cache key isolates provider account session page and sort`() {
        val encoded = baseKey.encode()
        val variants = listOf(
            baseKey.copy(providerId = "provider-b"),
            baseKey.copy(accountId = "user-2"),
            baseKey.copy(sessionId = "source-2:account-4:cookie-4"),
            baseKey.copy(limit = 50),
            baseKey.copy(offset = 100),
            baseKey.copy(sortKey = "NAME"),
            baseKey.copy(playlistId = "provider-a://playlist/list-2"),
            baseKey.copy(kind = "detail"),
        )
        variants.forEach { assertNotEquals(encoded, it.encode()) }
        assertTrue(encoded.startsWith(PlaylistCacheKey.playlistPrefix(baseKey.playlistId)))
    }

    @Test
    fun `same-key concurrent reads share one in-flight fetch`() = runBlocking {
        val cache = SwrCache<String>(freshTtlMs = 1_000, staleTtlMs = 5_000)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<MusicResult<String>>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.get("same", this@runBlocking) {
                calls++
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.get("same", this@runBlocking) {
                calls++
                BackendResult.Success("unexpected duplicate")
            }
        }

        assertEquals(1, calls)
        release.complete(BackendResult.Success("shared"))
        assertEquals("shared", first.await().getOrNull())
        assertEquals("shared", second.await().getOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun `matching playlist invalidation clears every page and sorting variant`() = runBlocking {
        val cache = SwrCache<String>(freshTtlMs = 1_000, staleTtlMs = 5_000)
        val detailKey = PlaylistCacheKey(
            kind = "detail", providerId = "p", accountId = "u", sessionId = "s",
            playlistId = "p://playlist/42", sortKey = "DEFAULT",
        )
        val firstPage = baseKey.copy(playlistId = "p://playlist/42", offset = 0)
        val secondPage = firstPage.copy(offset = 100, sortKey = "NAME")
        var reads = 0

        suspend fun read(key: PlaylistCacheKey): String =
            cache.get(key.encode(), this@runBlocking) {
                reads++
                BackendResult.Success("value-$reads")
            }.getOrNull()!!

        assertEquals("value-1", read(detailKey))
        assertEquals("value-2", read(firstPage))
        assertEquals("value-3", read(secondPage))
        cache.invalidateMatching { it.startsWith(PlaylistCacheKey.playlistPrefix("p://playlist/42")) }
        assertEquals("value-4", read(detailKey))
        assertEquals("value-5", read(firstPage))
        assertEquals("value-6", read(secondPage))
    }

    @Test
    fun `invalidation prevents an older in-flight response from repopulating cache`() = runBlocking {
        val cache = SwrCache<String>(freshTtlMs = 1_000, staleTtlMs = 5_000)
        val started = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<MusicResult<String>>()
        val oldRead = async(start = CoroutineStart.UNDISPATCHED) {
            cache.get("playlist", this@runBlocking) {
                started.complete(Unit)
                releaseOld.await()
            }
        }
        started.await()

        cache.invalidate("playlist")
        assertEquals("new", cache.get("playlist", this) { BackendResult.Success("new") }.getOrNull())
        releaseOld.complete(BackendResult.Success("old"))
        assertEquals("old", oldRead.await().getOrNull())
        assertEquals("new", cache.get("playlist", this) { BackendResult.Success("unexpected refetch") }.getOrNull())
    }

    @Test
    fun `failed stale refresh keeps the last valid value`() = runBlocking {
        var now = 0L
        val cache = SwrCache<String>(freshTtlMs = 10, staleTtlMs = 100, nowMs = { now })
        assertEquals("cached", cache.get("k", this) { BackendResult.Success("cached") }.getOrNull())
        now = 11L

        assertEquals("cached", cache.get("k", this) { BackendResult.Error("offline") }.getOrNull())
        // A stale hit still returns the prior successful value after the background failure.
        assertEquals("cached", cache.get("k", this) { BackendResult.Error("offline again") }.getOrNull())
    }

    @Test
    fun `incomplete successful refresh does not replace a complete cached value`() = runBlocking {
        var now = 0L
        val cache = SwrCache<String>(freshTtlMs = 10, staleTtlMs = 100, nowMs = { now })
        cache.get("k", this) { BackendResult.Success("complete") }
        now = 11L
        val refreshFetched = CompletableDeferred<Unit>()

        assertEquals(
            "complete",
            cache.get("k", this, shouldCache = { it == "complete" }) {
                refreshFetched.complete(Unit)
                BackendResult.Success("partial")
            }.getOrNull(),
        )
        refreshFetched.await()
        assertEquals("complete", cache.get("k", this) { BackendResult.Success("unexpected") }.getOrNull())
    }

    @Test
    fun `default cache capacity evicts oldest entry after sixty-four keys`() = runBlocking {
        val cache = SwrCache<String>(freshTtlMs = 1_000, staleTtlMs = 5_000)
        val calls = mutableMapOf<String, Int>()
        suspend fun read(key: String): String = cache.get(key, this@runBlocking) {
            calls[key] = (calls[key] ?: 0) + 1
            BackendResult.Success(key)
        }.getOrNull()!!

        repeat(65) { read("k$it") }
        read("k0")
        assertEquals(2, calls["k0"])
        assertEquals(1, calls["k64"])
    }

    @Test
    fun `repository shares detail fallback page and invalidates it after successful mutation`() = runBlocking {
        val track = TrackSummary("track-1", "Track", "Artist", null, null, 10_000L)
        val detail = PlaylistDetail(
            summary = PlaylistSummary(1L, "List", null, 1, "Owner", "list-shared"),
            tracks = emptyList(),
            description = null,
        )
        var pageCalls = 0
        @Suppress("UNCHECKED_CAST")
        val source = java.lang.reflect.Proxy.newProxyInstance(
            cp.player.core.music.UnifiedMusicSource::class.java.classLoader,
            arrayOf(cp.player.core.music.UnifiedMusicSource::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlaylistDetail" -> BackendResult.Success(detail)
                "getPlaylistTracks" -> {
                    pageCalls++
                    BackendResult.Success(CatalogPage(listOf(track), 1, false))
                }
                "renamePlaylist" -> BackendResult.Success(true)
                else -> BackendResult.Unsupported("unused")
            }
        } as cp.player.core.music.UnifiedMusicSource
        val repository = MusicRepository(source)

        assertEquals(listOf(track), repository.getPlaylistDetail("list-shared").getOrNull()?.tracks)
        assertEquals(listOf(track), repository.getPlaylistTracks("list-shared", 300, 0).getOrNull()?.tracks)
        assertEquals(1, pageCalls, "detail fallback and first page should share one cached fetch")

        assertTrue(repository.renamePlaylist("list-shared", "Renamed"))
        assertEquals(listOf(track), repository.getPlaylistTracks("list-shared", 300, 0).getOrNull()?.tracks)
        assertEquals(2, pageCalls, "successful write should invalidate the playlist page")
    }

    @Test
    fun `reopening a playlist reuses the source repository and its cached first page`() = runBlocking {
        val track = TrackSummary("track-1", "Track", "Artist", null, null, 10_000L)
        var pageCalls = 0
        fun source() = java.lang.reflect.Proxy.newProxyInstance(
            cp.player.core.music.UnifiedMusicSource::class.java.classLoader,
            arrayOf(cp.player.core.music.UnifiedMusicSource::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlaylistTracks" -> {
                    pageCalls++
                    BackendResult.Success(CatalogPage(listOf(track), 1, false))
                }
                else -> BackendResult.Unsupported("unused")
            }
        } as cp.player.core.music.UnifiedMusicSource

        var activeSource = source()
        val repositories = MusicRepositoryProvider { activeSource }
        val firstOpenRepository = repositories.get()
        val firstPage = firstOpenRepository.getPlaylistTracks("list-1", 300, 0)
        val secondOpenRepository = repositories.get()
        val secondPage = secondOpenRepository.getPlaylistTracks("list-1", 300, 0)

        assertSame(firstOpenRepository, secondOpenRepository)
        assertEquals(listOf(track), firstPage.getOrNull()?.tracks)
        assertEquals(listOf(track), secondPage.getOrNull()?.tracks)
        assertEquals(1, pageCalls, "reopening the same playlist should hit the first page cache")

        activeSource = source()
        val reboundRepository = repositories.get()
        assertNotSame(firstOpenRepository, reboundRepository)
        assertEquals(listOf(track), reboundRepository.getPlaylistTracks("list-1", 300, 0).getOrNull()?.tracks)
        assertEquals(2, pageCalls, "a replaced UnifiedMusicSource must not reuse the old source cache")
    }
}
