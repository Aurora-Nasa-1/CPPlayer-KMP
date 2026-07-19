package cp.player.kmp.cache

import cp.player.kmp.monitor.HealthMonitor
import kotlinx.serialization.json.JsonElement

/**
 * 缓存返回结果的契约。
 *
 * - [Cached]：来自缓存（同时返回是否过期、健康等级与警告）
 * - [Fresh]：来自网络的最新数据（与缓存不同时异步回传）
 * - [Error]：请求失败，可能附带 [fallback] 降级数据
 * - [NoChange]：后台比对发现与缓存一致，无需替换（仅作为可选信号）
 */
sealed class CacheResult<out T> {
    /** 数据来源标记 */
    enum class Source { CACHE, NETWORK, FALLBACK }

    data class Cached<T>(
        val data: T,
        val ageMs: Long,
        val isStale: Boolean,
        val source: Source = Source.CACHE,
        val level: HealthMonitor.HealthLevel = HealthMonitor.HealthLevel.OK,
        val warnings: List<HealthMonitor.ResponseWarning> = emptyList()
    ) : CacheResult<T>()

    data class Fresh<T>(
        val data: T,
        val source: Source = Source.NETWORK,
        val level: HealthMonitor.HealthLevel = HealthMonitor.HealthLevel.OK,
        val warnings: List<HealthMonitor.ResponseWarning> = emptyList()
    ) : CacheResult<T>()

    data class Error(
        val message: String,
        val code: Int? = null,
        val fallback: JsonElement? = null,
        val level: HealthMonitor.HealthLevel = HealthMonitor.HealthLevel.ERROR,
        val source: Source = Source.NETWORK
    ) : CacheResult<Nothing>()

    /** 后台比对发现与缓存一致（可选信号，调用方可忽略）。 */
    object NoChange : CacheResult<Nothing>()
}