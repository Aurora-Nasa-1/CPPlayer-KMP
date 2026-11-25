package cp.player.app.presentation.search

import cp.player.app.presentation.FakeSettingsStorage
import cp.player.app.repository.HotSearch
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

class SearchPresenterTest {

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

    private suspend fun awaitHotSearch(presenter: SearchPresenter, expected: String) {
        withTimeout(2_000) {
            while (presenter.state.value.hotSearches.firstOrNull()?.keyword != expected) {
                delay(1)
            }
        }
    }
}
