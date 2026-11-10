package cp.player.app.repository

import cp.player.core.BackendResult
import cp.player.core.model.Contact
import cp.player.core.model.Message
import cp.player.core.music.MusicResult
import cp.player.core.music.UnifiedMusicSource
import cp.player.sdk.Bytes
import cp.player.sdk.Conversation
import cp.player.sdk.CursorRequest
import cp.player.sdk.MessageHistoryRequest
import cp.player.sdk.PageRequest
import cp.player.sdk.SendMessageRequest
import cp.player.sdk.SocialUser
import cp.player.sdk.UserRequest

/**
 * 私信 / 联系人的应用侧门面。
 *
 * ### 为什么走 [UnifiedMusicSource] 而不是 `MusicApiService`
 * 这一组能力（`messages.*`）在 typed 层是**一等能力**：请求/响应都有强类型，
 * 且随 SDK 会话一起切换账号。此前这里调的是 JSON 投影 `MusicApiService`，而投影
 * 从来没有 `MESSAGE_*` 分支 ⇒ 每次都拿到 `code:-1`，再被 `runCatching` 吞成
 * 「未读 0 / 联系人空 / 发送失败」。整条链路看着在、实际一次都没成功过。
 *
 * 现在失败会如实变成 [BackendResult.Unsupported]（音源没实现）或
 * [BackendResult.Error]（网络/上游错误），页面能分别给出「不支持」与「出错了」。
 *
 * 保留 [Contact] / [Message] 这两个展示模型不动：私信只在 app 侧被消费，
 * 没必要把 SDK 的 `SocialUser` / `PrivateMessage` 直接漏进 UI 层。
 */
class SocialRepository(private val source: UnifiedMusicSource) {

    /** 未读私信数。取不到时返回 0 —— 角标不该让整页变错误态。 */
    suspend fun getUnreadCount(): Int =
        (source.getUnreadCount(Bytes(ByteArray(0))) as? BackendResult.Success)
            ?.data?.privateMessages?.coerceAtLeast(0) ?: 0

    /**
     * 最近联系人。
     *
     * 会话列表（`messages.conversations`）优先：它同时带最后一条消息与未读数，
     * 正是列表要显示的三样东西。为空再退到 `messages.contacts`（只有用户资料）。
     */
    suspend fun getContacts(): MusicResult<List<Contact>> {
        val conversations = source.getPrivateMessages(CursorRequest())
        if (conversations is BackendResult.Success && conversations.data.items.isNotEmpty()) {
            return BackendResult.Success(conversations.data.items.mapNotNull { it.toContact() })
        }
        val recent = source.getRecentContacts(PageRequest())
        if (recent is BackendResult.Success) {
            return BackendResult.Success(recent.data.items.mapNotNull { it.toContact() })
        }
        // 两条路都失败：交回主路径（会话列表）的失败原因。
        return conversations.failureOrUnsupported<List<Contact>>()
            ?: BackendResult.Unsupported("messages are not supported")
    }

    /** 与某个用户的私信历史（按时间正序，最新的在最后）。 */
    suspend fun getMessages(uid: Long, myUid: Long): MusicResult<List<Message>> {
        val result = source.getMessageHistory(MessageHistoryRequest(userId = uid.toString(), limit = 50))
        result.failureOrUnsupported<List<Message>>()?.let { return it }
        val page = (result as BackendResult.Success).data
        // 上游按「新 → 旧」给，聊天页要自上而下读。
        return BackendResult.Success(page.items.mapNotNull { it.toMessage(myUid) }.reversed())
    }

    /** 发送文本私信，返回服务端是否受理。 */
    suspend fun sendMessage(uid: Long, text: String): Boolean =
        (source.sendMessage(SendMessageRequest(listOf(uid.toString()), text)) as? BackendResult.Success)
            ?.data?.success == true

    /** 标记与某人的私信已读（失败静默 —— 这是个旁路动作，不该打断聊天）。 */
    suspend fun markRead(uid: Long) {
        runCatching { source.markMessageAsRead(UserRequest(uid.toString())) }
    }

    // ======================== 映射 ========================

    private fun Conversation.toContact(): Contact? {
        val userId = user.id.toLongOrNull() ?: return null
        return Contact(
            userId = userId,
            nickname = user.nickname,
            avatarUrl = user.avatarUrl.orEmpty(),
            lastMessage = lastMessage?.text,
            lastMessageTime = lastMessage?.sentAtEpochMs,
            unreadCount = unreadCount,
        )
    }

    private fun SocialUser.toContact(): Contact? {
        val userId = id.toLongOrNull() ?: return null
        return Contact(userId = userId, nickname = nickname, avatarUrl = avatarUrl.orEmpty())
    }

    private fun cp.player.sdk.PrivateMessage.toMessage(myUid: Long): Message? {
        val fromUserId = sender.id.toLongOrNull() ?: return null
        return Message(
            id = id.toLongOrNull() ?: return null,
            fromUserId = fromUserId,
            fromNickname = sender.nickname,
            fromAvatarUrl = sender.avatarUrl.orEmpty(),
            text = text,
            time = sentAtEpochMs,
            isMe = myUid != 0L && sender.id == myUid.toString(),
        )
    }

    /** 成功时返回 null；失败原样转成目标类型（便于 `?:` / `let` 提前返回）。 */
    private inline fun <T> BackendResult<*>.failureOrUnsupported(): MusicResult<T>? = when (this) {
        is BackendResult.Error -> BackendResult.Error(message, code, cause)
        is BackendResult.Unsupported -> BackendResult.Unsupported(message)
        is BackendResult.Success -> null
    }
}
