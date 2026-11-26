package cp.player.app.presentation.search

import cp.player.app.presentation.FakeSettingsStorage
import cp.player.app.repository.HotSearch
import cp.player.app.repository.MusicRepository
import cp.player.core.BackendResult
import cp.player.core.music.AlbumSummary
import cp.player.core.music.ArtistSummary
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.SearchResult
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchPresenterTest {

    private fun fakeProvider(id: String): BackendProvider = object : BackendProvider {
        override val id = id
        override val name = id
        override val version = "1.0.0"
        override val type = ProviderType.SCRIPT
        override val updateUrl: String? = null
        override val targetAppPackage: String? = null
    }

    @Test
    fun `reload hot searches replaces results with the latest source response`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requestCount = AtomicInteger()
        val presenter = SearchPresenter(
            scope = scope,
            settingsProvider = { FakeSettingsStorage() },
            repositoryProvider = { error("测试不应访问真实仓库") },
            activeProviderProvider = { null },
            localItemsProvider = { emptyList() },
            suggestionsLoader = { emptyList() },
            hotSearchesLoader = {
                listOf(HotSearch("source-${requestCount.incrementAndGet()}"))
            },
        )

        try {
            awaitHotSearch(presenter, "source-1")
            presenter.loadHotSearches()
            awaitHotSearch(presenter, "source-2")
            assertEquals(2, requestCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `onProviderChanged clears stale search results and drops in-flight results from previous provider`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var currentProvider = fakeProvider("provider-a")
        val searchGate = CompletableDeferred<Unit>()
        val source = object : UnifiedMusicSource {
            override suspend fun getTrackDetail(mediaId: String) = BackendResult.Error("unused")
            override suspend fun getTrackDetails(mediaIds: List<String>) = BackendResult.Error("unused")
            override suspend fun getSongUrl(mediaId: String, level: String) = BackendResult.Error("unused")
            override suspend fun search(keywords: String, type: Int, providers: List<String>?): BackendResult<SearchResult> {
                searchGate.await()
                return BackendResult.Success(
                    SearchResult(
                        songs = listOf(TrackSummary("provider-a://song/1", "Old Song", "Artist", null, null, 1000L)),
                        playlists = emptyList<PlaylistSummary>(),
                        artists = emptyList<ArtistSummary>(),
                        albums = emptyList<AlbumSummary>(),
                    ),
                )
            }
            override suspend fun getUserPlaylists(providerId: String, uid: Long) = BackendResult.Error("unused")
        }
        val repo = MusicRepository(source)
        val presenter = SearchPresenter(
            scope = scope,
            settingsProvider = { FakeSettingsStorage() },
            repositoryProvider = { repo },
            activeProviderProvider = { currentProvider },
            localItemsProvider = { emptyList() },
            suggestionsLoader = { emptyList() },
            hotSearchesLoader = { listOf(HotSearch("hot-${currentProvider.id}")) },
        )

        try {
            awaitHotSearch(presenter, "hot-provider-a")
            presenter.search("test")
            currentProvider = fakeProvider("provider-b")
            presenter.onProviderChanged()
            searchGate.complete(Unit)
            awaitHotSearch(presenter, "hot-provider-b")
            assertNull(presenter.state.value.result)
            assertEquals(emptyList(), presenter.state.value.suggestions)
        } finally {
            if (!searchGate.isCompleted) searchGate.complete(Unit)
            scope.cancel()
        }
    }

    private suspend fun awaitHotSearch(presenter: SearchPresenter, expected: String) {
        withTimeout(2_000) {
            while (presenter.state.value.hotSearches.firstOrNull()?.keyword != expected) {
                delay(1)
            }
        }
    }
}
