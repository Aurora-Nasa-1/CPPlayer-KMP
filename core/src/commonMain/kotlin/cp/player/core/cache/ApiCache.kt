package cp.player.core.cache

import cp.player.core.util.currentTimeMillis
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

    /**
     * 删除前缀匹配的所有条目，返回删除数量。
     *
     * 写操作失效读缓存靠的就是它：歌单详情 / 全部曲目共用 `playlist/` 这一段，
     * 逐个 key 枚举既做不到（键里带参数）也枚举不全。
     */
    fun removeByPrefix(prefix: String): Int

    fun clear()
    fun size(): Int
}

/**
 * 进程内 LRU 缓存（KMP 通用）。
 *
 * 超过 [maxEntries] 时淘汰最久未使用的条目。线程安全（粗粒度锁）。
 * 适合"先返回缓存，再后台比对"场景的临时存储；如需跨重启保留请提供平台持久化实现。
 */
class InMemoryApiCache(private val maxEntries: Int = 64) : ApiCache {
    private val store = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    override fun get(key: String): CacheEntry? = store[key]

    @Synchronized
    override fun put(key: String, entry: CacheEntry) {
        store[key] = entry
    }

    @Synchronized
    override fun remove(key: String) { store.remove(key) }

    @Synchronized
    override fun removeByPrefix(prefix: String): Int {
        // 先收集再删：直接遍历 keys 边迭代边删会触发 ConcurrentModificationException。
        val doomed = store.keys.filter { it.startsWith(prefix) }
        doomed.forEach { store.remove(it) }
        return doomed.size
    }

    @Synchronized
    override fun clear() { store.clear() }

    @Synchronized
    override fun size(): Int = store.size
}

/**
 * 稳定的 64 位 FNV-1a，输出定长十六进制串。
 *
 * **不用 `String.hashCode`**：它只有 32 位、碰撞率高，且用在缓存键里意味着
 * 「两个不同账号的 cookie 撞成同一个键 → B 账号读到 A 账号的歌单」。
 * 与 `DesktopStreamLocalizer.sanitize` 共用同一实现，避免同一规则写两份。
 */
fun stableHash64(value: String): String {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64 位偏移基准
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16)
}

/**
 * 缓存键内组件的转义：`#`、`&`、`=`、`%` 是键的结构分隔符，不转义就会撞键。
 *
 * 例：`{a: "1&b=2"}` 与 `{a: "1", b: "2"}` 不转义时都会拼成 `a=1&b=2` ——
 * 两个完全不同的请求共用一个缓存槽。
 */
private fun escapeComponent(value: String): String = buildString(value.length) {
    for (c in value) {
        when (c) {
            '#', '&', '=', '%' -> {
                append('%')
                append(c.code.toString(16))
            }
            else -> append(c)
        }
    }
}

/**
 * 生成缓存键：`providerId#method#sortedParams#cookieHash`。
 *
 * - 参数按 key 排序 ⇒ 传参顺序不影响键；
 * - 参数值转义 ⇒ 分隔符不会撞键；
 * - [cookie] 参与键 ⇒ 同机多账号必须隔离，否则 B 账号会读到 A 账号的歌单。
 */
fun cacheKey(
    providerId: String,
    method: String,
    params: Map<String, String>,
    cookie: String? = null
): String {
    val sortedParams = params.entries.sortedBy { it.key }
        .joinToString("&") { (k, v) -> "${escapeComponent(k)}=${escapeComponent(v)}" }
    // 无 cookie 时用固定占位：null 与「空 cookie」必须落在同一个键上，
    // 否则登录态为空的那次读永远命中不了。前缀分域，避免哈希恰好等于占位值。
    val ck = if (cookie.isNullOrEmpty()) "anon" else "h${stableHash64(cookie)}"
    return "${escapeComponent(providerId)}#${escapeComponent(method)}#$sortedParams#$ck"
}

/** 便捷封装：存入完整数据时自动计算指纹并打时间戳。 */
fun ApiCache.putData(key: String, data: JsonElement, now: Long = currentTimeMillis()) {
    put(key, CacheEntry(data = data, fingerprint = Fingerprinter.compute(data), timestamp = now))
}
