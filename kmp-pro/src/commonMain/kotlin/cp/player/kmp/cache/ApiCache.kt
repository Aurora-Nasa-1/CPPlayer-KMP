package cp.player.kmp.cache

import cp.player.kmp.util.currentTimeMillis
import kotlinx.serialization.json.JsonElement

/**
 * API 响应缓存抽象。
 *
 * 默认实现 [InMemoryApiCache] 为进程内 LRU；平台可提供持久化 actual。
 */
interface ApiCache {
    /** 读取缓存条目，可选 freshnessTtl > 0 时不命中过期条目。 */
    fun get(key: String): CacheEntry?
    fun put(key: String, entry: CacheEntry)
    fun remove(key: String)
    fun clear()
    fun size(): Int
}

/**
 * 进程内 LRU 缓存（KMP 通用）。
 *
 * 超过 [maxEntries] 时淘汰最旧条目。线程安全（粗粒度锁）。
 * 适合"先返回缓存，再后台比对"场景的临时存储；如需跨重启保留请提供平台持久化实现。
 */
class InMemoryApiCache(private val maxEntries: Int = 64) : ApiCache {
    private val store = LinkedHashMap<String, CacheEntry>()

    @Synchronized
    override fun get(key: String): CacheEntry? {
        val entry = store.remove(key) ?: return null
        store[key] = entry
        return entry
    }

    @Synchronized
    override fun put(key: String, entry: CacheEntry) {
        store.remove(key)
        store[key] = entry
        if (store.size > maxEntries) {
            val eldestKey = store.keys.firstOrNull()
            if (eldestKey != null) {
                store.remove(eldestKey)
            }
        }
    }

    @Synchronized
    override fun remove(key: String) { store.remove(key) }

    @Synchronized
    override fun clear() { store.clear() }

    @Synchronized
    override fun size(): Int = store.size
}

/** 生成缓存键：`providerId#method#sortedParams#cookieHash`。 */
fun cacheKey(
    providerId: String,
    method: String,
    params: Map<String, String>,
    cookie: String? = null
): String {
    val sortedParams = params.entries.sortedBy { it.key }
        .joinToString("&") { (k, v) -> "$k=$v" }
    val ck = (cookie?.hashCode() ?: 0)
    return "$providerId#$method#$sortedParams#$ck"
}

/** 便捷封装：存入完整数据时自动计算指纹并打时间戳。 */
fun ApiCache.putData(key: String, data: JsonElement, now: Long = currentTimeMillis()) {
    put(key, CacheEntry(data = data, fingerprint = Fingerprinter.compute(data), timestamp = now))
}