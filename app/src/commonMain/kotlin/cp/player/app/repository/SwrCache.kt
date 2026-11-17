package cp.player.app.repository

import cp.player.core.BackendResult
import cp.player.core.music.MusicResult
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 通用「读缓存」：fresh 窗口内直接命中；过期但仍在 stale 窗口内时先回旧数据、
 * 后台换新；未命中或太老则同步拉取。
 *
 * 同一 key 的并发拉取合并为一次网络请求。失效会清除对应缓存与在途请求，并推进
 * generation；在失效前开始的响应不能回写，避免旧结果把刚清掉的缓存重新弄脏。
 */
internal class SwrCache<T : Any>(
    private val freshTtlMs: Long,
    private val staleTtlMs: Long,
    private val maxEntries: Int = 64,
    private val nowMs: () -> Long = { currentTimeMillis() },
) {

    private class Entry<T>(val value: T, val fetchedAt: Long)

    private val mutex = Mutex()
    // Reinsert on reads/writes to keep the eldest entry as the least recently used.
    private val entries = LinkedHashMap<String, Entry<T>>()
    private val inflight = HashMap<String, CompletableDeferred<MusicResult<T>>>()
    private var generation = 0

    init {
        require(freshTtlMs >= 0) { "freshTtlMs must be non-negative" }
        require(staleTtlMs >= freshTtlMs) { "staleTtlMs must be >= freshTtlMs" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /**
     * 读 [key]：fresh 命中直接返回；stale 命中先返回旧值并用 [scope] 后台换新；
     * 否则同步 [fetch]（同 key 并发合并为一次）。
     */
    suspend fun get(
        key: String,
        scope: CoroutineScope,
        shouldCache: (T) -> Boolean = { true },
        onRefresh: ((MusicResult<T>) -> Unit)? = null,
        fetch: suspend () -> MusicResult<T>,
    ): MusicResult<T> {
        val snapshot = mutex.withLock {
            val entry = entries.remove(key)
            if (entry != null) entries[key] = entry
            entry to generation
        }
        val cached = snapshot.first
        if (cached != null) {
            val age = nowMs() - cached.fetchedAt
            if (age < freshTtlMs) return BackendResult.Success(cached.value)
            if (age < staleTtlMs) {
                // 旧数据先上屏；若失效恰好发生在 launch 前，expectedGeneration 会阻止旧刷新启动。
                scope.launch {
                    val result = try {
                        fetchAndStore(key, fetch, shouldCache, expectedGeneration = snapshot.second)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                    if (result != null) {
                        mutex.withLock {
                            if (generation == snapshot.second) {
                                runCatching { onRefresh?.invoke(result) }
                            }
                        }
                    }
                }
                return BackendResult.Success(cached.value)
            }
        }
        return fetchAndStore(key, fetch, shouldCache)
    }

    /** 失效 [key]（null 表示全部）。 */
    suspend fun invalidate(key: String? = null) {
        invalidateMatching { key == null || it == key }
    }

    /** 失效所有满足 [predicate] 的 key，例如某个歌单的所有分页和排序。 */
    suspend fun invalidateMatching(predicate: (String) -> Boolean) {
        mutex.withLock {
            generation++
            entries.keys.filter(predicate).forEach(entries::remove)
            inflight.keys.filter(predicate).forEach(inflight::remove)
        }
    }

    private suspend fun fetchAndStore(
        key: String,
        fetch: suspend () -> MusicResult<T>,
        shouldCache: (T) -> Boolean,
        expectedGeneration: Int? = null,
    ): MusicResult<T> {
        class Plan(val deferred: CompletableDeferred<MusicResult<T>>, val isLeader: Boolean, val gen: Int)

        val plan = mutex.withLock {
            if (expectedGeneration != null && generation != expectedGeneration) return@withLock null
            val running = inflight[key]
            if (running != null) {
                Plan(running, isLeader = false, gen = generation)
            } else {
                val created = CompletableDeferred<MusicResult<T>>()
                inflight[key] = created
                Plan(created, isLeader = true, gen = generation)
            }
        } ?: return BackendResult.Error("缓存已失效")

        // 跟随者：直接等先行者的同一个请求。
        if (!plan.isLeader) return plan.deferred.await()

        val result = try {
            fetch()
        } catch (cancelled: CancellationException) {
            val error = BackendResult.Error(cancelled.message ?: "请求已取消", cause = cancelled)
            plan.deferred.complete(error)
            mutex.withLock { if (inflight[key] === plan.deferred) inflight.remove(key) }
            throw cancelled
        } catch (t: Throwable) {
            BackendResult.Error(t.message ?: "请求失败", cause = t)
        }

        // 只有发起时的代际未变才入库；失败响应不会覆盖旧的有效值。
        if (result is BackendResult.Success && shouldCache(result.data)) {
            mutex.withLock {
                if (generation == plan.gen) {
                    entries.remove(key)
                    entries[key] = Entry(result.data, nowMs())
                    while (entries.size > maxEntries) entries.remove(entries.keys.first())
                }
            }
        }
        plan.deferred.complete(result)
        mutex.withLock { if (inflight[key] === plan.deferred) inflight.remove(key) }
        return result
    }
}
