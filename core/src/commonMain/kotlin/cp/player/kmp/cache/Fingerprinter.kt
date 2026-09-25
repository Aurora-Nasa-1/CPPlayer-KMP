package cp.player.kmp.cache

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 响应"简单数据"指纹提取器。
 *
 * 目的：用极小开销生成稳定签名，比较两次响应的"内容身份"是否变化，
 * 而不用逐字段比对完整（可能很大）的响应。
 *
 * 指纹组成（`|` 分隔）：
 * 1. `code` 原始字符串（缺失记为 `_`）
 * 2. 顶层所有数组字段的 `键=长度`，按字典序
 * 3. 主数据数组中每个对象的 `id` 取值（最多 64 个），去重排序后拼接
 * 4. 顶层 `version`/`updateTime` 等版本位（若存在）
 *
 * 对同一份"内容"指纹稳定；增删/重排条目时指纹变化；改无关字段不影响。
 */
object Fingerprinter {

    /** 计算响应指纹。 */
    fun compute(json: JsonElement): String {
        if (json !is JsonObject) return "scalar|" + json.toString().hashCode()
        val sb = StringBuilder(64)

        // 1. code
        sb.append("code=").append(json["code"]?.let { asString(it) } ?: "_").append('|')

        // 2. 顶层数组长度集合
        val arrayLens = json.entries
            .filter { it.value is JsonArray }
            .map { it.key + "=" + (it.value as JsonArray).size }
            .sorted()
            .joinToString(",")
        sb.append("arr=").append(arrayLens).append('|')

        // 3. 主数据数组的 id 列表（探测常见数据数组键）
        val primaryArray = pickPrimaryArray(json)
        if (primaryArray != null) {
            val ids = primaryArray.mapNotNull { item ->
                if (item is JsonObject) idOf(item) else null
            }
                .distinct()
                .take(64)
                .sorted()
                .joinToString(",")
            sb.append("ids=").append(ids).append('|')
        }

        // 4. 版本位
        val version = json["version"]?.let { asString(it) }
            ?: json["updateTime"]?.let { asString(it) }
            ?: "_"
        sb.append("v=").append(version)

        return sb.toString()
    }

    private fun pickPrimaryArray(obj: JsonObject): JsonArray? {
        // 优先约定主数据数组键
        for (key in PRIMARY_KEYS) {
            obj[key]?.let { if (it is JsonArray) return it }
        }
        // 否则取第一个非空数组
        return obj.values.firstOrNull { it is JsonArray && it.size > 0 } as? JsonArray
    }

    private fun idOf(item: JsonObject): String? {
        // 直接 id
        item["id"]?.let { return asString(it) }
        // 嵌套 song/track 的 id
        for (key in NESTED_ID_KEYS) {
            (item[key] as? JsonObject)?.get("id")?.let { return asString(it) }
        }
        return null
    }

    private fun asString(e: JsonElement): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        else -> null
    }

    private val PRIMARY_KEYS = listOf(
        "songs", "playlist", "playlists", "albums", "artists", "comments",
        "result", "data", "list", "msgs", "ids", "banners", "hotData", "hotAlbums"
    )
    private val NESTED_ID_KEYS = listOf("song", "track", "simpleSong", "mainTrack")
}