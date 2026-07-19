package cp.player.kmp.model

import kotlinx.serialization.Serializable

@Serializable
data class Playlist(
    val id: Long,
    val name: String,
    val coverImgUrl: String? = null,
    val trackCount: Int = 0,
    val creatorName: String? = null,
    val creatorUserId: Long = 0L,
    val subscribed: Boolean = false,
    val description: String? = null,
    val composer: String? = null,
    val totalDurationMs: Long = 0L
)