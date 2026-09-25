package cp.player.kmp.model

import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0
)