package cp.player.kmp.cache

/**
 * 缓存与回退配置。
 *
 * @property freshTtlMs 缓存"新鲜"阈值（超过视为 stale，但仍先返回）
 * @property maxEntries 内存缓存最大条目数
 * @property enableFallback 是否在 ERROR 时启用多 Provider 容灾
 * @property enableCache 总开关；false 时所有请求直通底层
 */
data class CacheConfig(
    val freshTtlMs: Long = 5 * 60 * 1000L,
    val maxEntries: Int = 64,
    val enableFallback: Boolean = true,
    val enableCache: Boolean = true
)