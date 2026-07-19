package cp.player.kmp.cache

import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement

/**
 * 缓存条目：保存完整数据与对应的"简单数据指纹"。
 *
 * 指纹是响应的轻量签名（见 [Fingerprinter]），用于后台比对：
 * 仅当新响应的指纹与缓存指纹不同时，才视为有"不同的较大数据"需要回传。
 *
 * @property data       完整响应 JSON
 * @property fingerprint 简单数据指纹
 * @property timestamp  存入时间（epoch ms）
 */
data class CacheEntry(
    val data: JsonElement,
    val fingerprint: String,
    val timestamp: Long
) {
    fun age(now: Long = Clock.System.now().toEpochMilliseconds()): Long = now - timestamp
}