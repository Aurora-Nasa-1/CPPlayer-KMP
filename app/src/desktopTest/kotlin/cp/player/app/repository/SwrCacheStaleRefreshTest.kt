package cp.player.app.repository

import cp.player.core.BackendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class SwrCacheStaleRefreshTest {
    @Test
    fun `stale hit returns cached value and publishes refreshed value asynchronously`() = runBlocking {
        var now = 0L
        val cache = SwrCache<String>(freshTtlMs = 10, staleTtlMs = 100, nowMs = { now })
        assertEquals("cached", cache.get("key", this) { BackendResult.Success("cached") }.getOrNull())

        now = 11L
        val refreshed = CompletableDeferred<String?>()
        val stale = cache.get(
            key = "key",
            scope = this,
            onRefresh = { result ->
                refreshed.complete((result as? BackendResult.Success)?.data)
            },
        ) { BackendResult.Success("fresh") }

        assertEquals("cached", stale.getOrNull())
        assertEquals("fresh", withTimeout(1_000) { refreshed.await() })
        assertEquals("fresh", cache.get("key", this) { BackendResult.Success("unexpected") }.getOrNull())
    }
}
