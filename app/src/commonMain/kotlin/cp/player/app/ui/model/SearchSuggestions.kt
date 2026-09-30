package cp.player.app.ui.model

import cp.player.app.AppModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 搜索建议的防抖时长。标题栏与搜索页共用同一档，否则两处手感会不一致。 */
internal const val SEARCH_SUGGEST_DEBOUNCE_MS = 250L

/** 最多展示多少条建议。 */
internal const val MAX_SEARCH_SUGGESTIONS = 8

/**
 * 取一次搜索建议（含防抖），失败返回 `null`。
 *
 * **为什么抽出来**：标题栏的全局搜索框和搜索页都要这条链路，而各 provider 返回的
 * JSON 结构并不统一（`allMatch` / `suggestions` / `data` 三种都见过）。解析逻辑一旦
 * 各写一份，改一个 provider 就得记得改两处 —— 迟早只改到一处。
 *
 * ⚠️ **`CancellationException` 必须原样抛出**：调用方靠「取消上一个 job」来做
 * 「最后一次请求胜出」。若像普通异常一样吞成 `null`，上一次请求的结果会在协程已被取消后
 * 继续往下走，覆盖掉这一次的结果 —— 表现为「建议列表和输入框对不上」。
 */
internal suspend fun loadSearchSuggestions(query: String): List<String>? {
    delay(SEARCH_SUGGEST_DEBOUNCE_MS)
    return try {
        AppModel.musicRepository.getSearchSuggestions(query).parseSearchSuggestions(exclude = query)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
}

/**
 * 解析音源返回的搜索建议。
 *
 * @param exclude 需要剔除的关键词（通常是当前输入本身），比较时忽略大小写。
 */
internal fun JsonElement.parseSearchSuggestions(exclude: String = ""): List<String> {
    val root = this as? JsonObject ?: return emptyList()
    val result = root["result"] as? JsonObject ?: root
    val array = (result["allMatch"] ?: result["suggestions"] ?: result["data"]) as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull
            is JsonObject -> (item["keyword"] ?: item["name"] ?: item["word"])
                ?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot { exclude.isNotBlank() && it.equals(exclude, ignoreCase = true) }
        .distinct()
        .take(MAX_SEARCH_SUGGESTIONS)
}
