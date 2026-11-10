package cp.player.app.repository

import cp.player.core.BackendResult
import cp.player.core.music.MusicResult
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 通用「读缓存」：fresh 窗口内直接命中；过期但仍在 stale 窗口内时先回旧数据、
 * 后台换新（stale-while-revalidate）；未命中或太老则同步拉取。
 *
 * 背景：迁移到 kmp-SDK 时旧的 `CachedMusicApiService` 读穿缓存被整体移除，
 * 歌单列表退化成「每次上屏都打网络」—— 首页侧栏、媒体库、歌单详情页、
 * 收藏判断、歌单选择弹窗并发各拉一份，既慢又重复。
 *
 * 两个关键设计：
 *
 * 1. **并发去重**（[inflight]）：同一 key 的并发拉取合并为一次网络请求，
 *    后到者 await 先行者的结果。
 * 2. **失效防回写**（[generation]）：[invalidate] 递增代际并丢弃在途请求，
 *    在途拉取只有「发起早于失效点且代际未变」才允许入库 —— 否则
 *    「变更前发出、变更后才返回」的旧响应会把刚清空的缓存再次写脏。
 */
internal class SwrCache<T : Any>(
    private val freshTtlMs: Long,
    private val staleTtlMs: Long,
) {

    private class Entry<T>(val value: T, val fetchedAt: Long)

    private val mutex = Mutex()
    private val entries = HashMap<String, Entry<T>>()
    private val inflight = HashMap<String, CompletableDeferred<MusicResult<T>>>()
    private var generation = 0

    /**
     * 读 [key]：fresh 命中直接返回；stale 命中先返回旧值并用 [scope] 后台换新；
     * 否则同步 [fetch]（同 key 并发合并为一次）。
     */
    suspend fun get(
        key: String,
        scope: CoroutineScope,
        fetch: suspend () -> MusicResult<T>,
    ): MusicResult<T> {
        val now = currentTimeMillis()
        val cached = mutex.withLock { entries[key] }
        if (cached != null) {
            val age = now - cached.fetchedAt
            if (age < freshTtlMs) return BackendResult.Success(cached.value)
            if (age < staleTtlMs) {
                // 旧数据先上屏，后台换新；fetchAndStore 自带去重，多订阅者也只拉一次。
                scope.launch { runCatching { fetchAndStore(key, fetch) } }
                return BackendResult.Success(cached.value)
            }
        }
        return fetchAndStore(key, fetch)
    }

    /**
     * 失效：清掉 [key]（null 表示全部）的缓存与在途请求，并递增代际。
     * 歌单发生创建/删除/收藏/改名/曲目增减后调用，保证下一次读拉新数据。
     */
    suspend fun invalidate(key: String? = null) {
        mutex.withLock {
            generation++
            if (key == null) {
                entries.clear()
                inflight.clear()
            } else {
                entries.remove(key)
                inflight.remove(key)
            }
        }
    }

    private suspend fun fetchAndStore(key: String, fetch: suspend () -> MusicResult<T>): MusicResult<T> {
        class Plan(val deferred: CompletableDeferred<MusicResult<T>>, val isLeader: Boolean, val gen: Int)

        val plan = mutex.withLock {
            val running = inflight[key]
            if (running != null) {
                Plan(running, isLeader = false, gen = generation)
            } else {
                val created = CompletableDeferred<MusicResult<T>>()
                inflight[key] = created
                Plan(created, isLeader = true, gen = generation)
            }
        }
        // 跟随者：直接等先行者的同一个请求。
        if (!plan.isLeader) return plan.deferred.await()

        val result = try {
            fetch()
        } catch (t: Throwable) {
            BackendResult.Error(t.message ?: "请求失败", cause = t)
        }
        // 只有发起时的代际未变才入库：失效后返回的旧响应不能写回缓存。
        if (result is BackendResult.Success) {
            mutex.withLock {
                if (generation == plan.gen) entries[key] = Entry(result.data, currentTimeMillis())
            }
        }
        plan.deferred.complete(result)
        mutex.withLock { if (inflight[key] === plan.deferred) inflight.remove(key) }
        return result
    }
}
