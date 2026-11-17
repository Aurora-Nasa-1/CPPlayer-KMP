package cp.player.app.presentation

import cp.player.app.presentation.search.SearchPresenter
import cp.player.app.repository.SearchType
import cp.player.app.presentation.comment.CommentPresenter
import cp.player.app.presentation.dynamic.DynamicFilter
import cp.player.app.presentation.dynamic.DynamicPresenter
import cp.player.app.presentation.home.HomePresenter
import cp.player.app.presentation.playlistdetail.PlaylistDetailPresenter
import cp.player.app.presentation.playlistdetail.PlaylistSortType
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeSettingsStorage : SettingsStorage {
    private val map = mutableMapOf<String, String?>()
    override fun getString(key: String, default: String?): String? = map[key] ?: default
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun remove(key: String) { map.remove(key) }
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun clear() { map.clear() }
}

class HeadlessUiBridgeTest {

    @Test
    fun `search presenter operates without compose or voyager runtime`() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fakeSettings = FakeSettingsStorage()

        try {
            val presenter = SearchPresenter(
                initialQuery = "",
                scope = testScope,
                settingsProvider = { fakeSettings },
                repositoryProvider = { error("测试不应访问真实仓库") },
                activeProviderProvider = { null },
                localItemsProvider = { emptyList() },
                suggestionsLoader = { emptyList() },
            )

            assertEquals("", presenter.state.value.query)
            assertEquals(SearchType.SONG, presenter.state.value.searchType)

            presenter.selectSearchType(SearchType.ALBUM)
            assertEquals(SearchType.ALBUM, presenter.state.value.searchType)

            presenter.setQuery("海阔天空")
            assertEquals("海阔天空", presenter.state.value.query)

            presenter.clear()
            assertEquals("", presenter.state.value.query)
            assertNull(presenter.state.value.result)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `headless bridge factory creates configured presenters`() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = HeadlessUiBridge()

        try {
            val presenter = bridge.createSearchPresenter(
                initialQuery = "Test",
                scope = testScope,
                activeProviderProvider = { null },
                localItemsProvider = { emptyList() },
                suggestionsLoader = { emptyList() },
            )

            assertEquals("Test", presenter.state.value.query)

            val libraryPresenter = bridge.createLibraryPresenter(
                initialPlaylistId = 12345L,
                scope = testScope,
            )
            assertEquals("12345", libraryPresenter.state.value.selectedPlaylistId)
            libraryPresenter.selectTab(2)
            assertEquals(2, libraryPresenter.state.value.selectedTab)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `navigation routes decouple from voyager screen types`() {
        val searchRoute = cp.player.app.presentation.navigation.AppRoute.Search("Rock")
        val playlistRoute = cp.player.app.presentation.navigation.AppRoute.PlaylistDetail(
            PlaylistSummary(
                id = 100L,
                name = "My Playlist",
                coverUrl = null,
                trackCount = 0,
                creatorName = null,
            )
        )
        val libraryRoute = cp.player.app.presentation.navigation.AppRoute.Library(999L)

        // 验证路由为纯强类型数据结构，不需要 Voyager 运行时即可实例化和比对
        assertEquals("Rock", (searchRoute as cp.player.app.presentation.navigation.AppRoute.Search).initialQuery)
        assertEquals(100L, (playlistRoute as cp.player.app.presentation.navigation.AppRoute.PlaylistDetail).playlist.id)
        assertEquals("My Playlist", playlistRoute.playlist.name)
        assertEquals(999L, (libraryRoute as cp.player.app.presentation.navigation.AppRoute.Library).initialPlaylistId)
    }

    @Test
    fun `entity presenters run without compose or voyager runtime`() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val playlistPresenter = PlaylistDetailPresenter(scope = testScope)
            assertEquals(true, playlistPresenter.state.value.loading)
            playlistPresenter.setSort(PlaylistSortType.NAME)
            assertEquals(PlaylistSortType.NAME, playlistPresenter.state.value.sortType)

            val commentPresenter = CommentPresenter(id = "1", type = "song", scope = testScope)
            assertEquals("1", commentPresenter.state.value.id)
            commentPresenter.updateDraft("  hello  ")
            assertEquals("  hello  ", commentPresenter.state.value.draft)
            assertEquals(true, commentPresenter.state.value.canSend)

            val dynamicPresenter = DynamicPresenter(scope = testScope)
            assertEquals(DynamicFilter.Following, dynamicPresenter.state.value.filter)
            assertEquals(false, dynamicPresenter.state.value.showSkeleton)

            val homePresenter = HomePresenter(scope = testScope, loadDiscovery = false)
            assertEquals(emptyList<TrackSummary>(), homePresenter.state.value.dailySongs)
            assertEquals(
                cp.player.app.presentation.home.PlaylistSource.Recommended,
                homePresenter.state.value.playlistSource,
            )
        } finally {
            testScope.cancel()
        }
    }
}
