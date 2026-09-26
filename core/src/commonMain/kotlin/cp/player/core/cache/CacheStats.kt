package cp.player.core.cache

/**
 * 读透缓存的可观测计数（`backend.cachedApi.stats`）。
 *
 * 这层是「看不见的」：失效了不报错、只是每次都打网络；过度生效则是用户刚改完
 * 歌单却看不到变化。两者都只能靠计数发现，所以计数本身是产品能力的一部分。
 *
 * @property hits        命中且未超过 `freshTtlMs`，未发起网络请求
 * @property misses      未命中或已过期（含「有过期条目但需回源」）
 * @property stores      回源成功并写回缓存的次数
 * @property staleServed 网络/上游失败时降级返回旧缓存的次数（用户不会知道，只会觉得慢）
 * @property invalidated 写操作主动删除的条目数
 */
data class CacheStats(
    val hits: Long = 0L,
    val misses: Long = 0L,
    val stores: Long = 0L,
    val staleServed: Long = 0L,
    val invalidated: Long = 0L,
)
