package cp.player.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricsInfo(
    val source: String = "Unknown",
    val format: String = "Unknown",
    val hasWordLevel: Boolean = false,
    val hasTranslation: Boolean = false,
    val hasPhonetic: Boolean = false
)