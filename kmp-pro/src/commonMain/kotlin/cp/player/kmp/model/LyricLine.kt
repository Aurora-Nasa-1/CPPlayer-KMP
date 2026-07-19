package cp.player.kmp.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricLine(
    val time: Long,
    val text: String,
    val translation: String? = null,
    val romanization: String? = null,
    val secondary: String? = null,
    val endTime: Long? = null,
    val words: List<Word>? = null
) {
    @Serializable
    data class Word(val text: String, val beginTime: Long, val endTime: Long)
}