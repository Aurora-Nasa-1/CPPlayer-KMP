package cp.player.kmp.playback

import kotlinx.serialization.Serializable

/**
 * 统一的同步歌词行（已脱离原始传输格式 YRC/TTML/LRC）。
 *
 * - [time] = 该行起始时间（毫秒）
 * - [endTime] = 该行结束时间（毫秒），无则按下一行起算
 * - [translation] = 翻译（中文/原文之外的第二语言），可选
 * - [romanization] = 罗马音，可选
 * - [words] = 逐字（逐词）时间戳，空表示按行级展示
 */
@Serializable
data class SyncedLyricLine(
    val time: Long,
    val text: String,
    val endTime: Long? = null,
    val translation: String? = null,
    val romanization: String? = null,
    val words: List<SyncedWord> = emptyList(),
) {
    @Serializable
    data class SyncedWord(
        val text: String,
        val beginTime: Long,
        val endTime: Long,
    )
}