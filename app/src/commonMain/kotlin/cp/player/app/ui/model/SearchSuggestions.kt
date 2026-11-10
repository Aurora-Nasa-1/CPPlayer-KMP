package cp.player.app.ui.model

import cp.player.app.AppModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 搜索建议的防抖时长。标题栏与搜索页共用同一档，否则两处手感会不一致。 */
internal const val SEARCH_SUGGEST_DEBOUNCE_MS = 250L

/** 最多展示多少条建议。 */
internal const val MAX_SEARCH_SUGGESTIONS = 8

/**
 * 取一次搜索建议（含防抖），失败返回 `null`。
 *
 * **为什么抽出来**：标题栏的全局搜索框和搜索页都要这条链路，各 provider 返回的
 * 结果在强类型仓库层（[cp.player.app.repository.MusicRepository.getSearchSuggestions]）统一归一化。
 *
 * ⚠️ **`CancellationException` 必须原样抛出**：调用方靠「取消上一个 job」来做
 * 「最后一次请求胜出」。若像普通异常一样吞成 `null`，上一次请求的结果会在协程已被取消后
 * 继续往下走，覆盖掉这一次的结果 —— 表现为「建议列表和输入框对不上」。
 */
internal suspend fun loadSearchSuggestions(query: String): List<String>? {
    delay(SEARCH_SUGGEST_DEBOUNCE_MS)
    return try {
        AppModel.musicRepository.getSearchSuggestions(query, exclude = query)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
}
