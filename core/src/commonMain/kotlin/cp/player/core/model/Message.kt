package cp.player.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val fromUserId: Long,
    val fromNickname: String,
    val fromAvatarUrl: String,
    val text: String,
    val time: Long,
    val isMe: Boolean = false
)
