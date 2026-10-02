package cp.player.app.repository

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.model.Contact
import cp.player.core.model.Message
import cp.player.core.music.MusicResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 私信 / 联系人的应用侧门面。
 *
 * 单独成类而不是塞进 [MusicRepository]：那一个的职责是「音乐内容」，而这一组端点
 * （`msg/...`、`send/text`、`pl/count`）全是**社交**语义，混在一起会让「音乐仓储」
 * 变成什么都装的杂物间。
 *
 * ### 上游的两个坑（都在这里收敛）
 * 1. **`msg` 字段是「字符串化的 JSON」**：真正的正文在 `Json.parse(msg).msg` 里。
 *    当普通字符串读会得到 `{"msg":"你好","type":0}` 这种原始串，直接渲染出来很难看。
 * 2. **`msg/recentcontact` 不是所有 Provider 都实现**。空结果要回落到 `msg/private`
 *    （私信会话列表），两条路的条目形状不同，但都能映射到同一个 [Contact]。
 *
 * ⚠️ 写路径时别在这份 KDoc 里出现 `msg/` 紧跟一个星号 —— 那会被 Kotlin 当成
 * **嵌套块注释**的开始（本仓库遇到过整文件 `Unclosed comment`）。
 */
class SocialRepository(private val api: MusicApiService) {

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 未读私信数。解析不出来时返回 0（而不是报错 —— 角标不该让整页变错误态）。 */
    suspend fun getUnreadCount(): Int {
        val json = runCatching { api.getUnreadCount() }.getOrNull() ?: return 0
        var root = json as? JsonObject ?: return 0
        (root["data"] as? JsonObject)?.let { root = it }
        val count = (root["msg"] as? JsonPrimitive)?.intOrNull
            ?: (root["privateMsg"] as? JsonPrimitive)?.intOrNull
            ?: (root["msgPrivateCount"] as? JsonPrimitive)?.intOrNull
            ?: 0
        // 这个端点在某些 Provider 上返回的是**未读数**也可能是**消息总数**，
        // 负数没有意义，夹掉。
        return count.coerceAtLeast(0)
    }

    /**
     * 最近联系人。
     *
     * 先打 `msg/recentcontact`；结果为空就回落到 `msg/private`。
     * 「空」才是回落条件 —— 请求本身失败（Provider 没实现）同样回落。
     */
    suspend fun getContacts(): MusicResult<List<Contact>> {
        val recent = runCatching { api.getRecentContacts() }.getOrNull()
        val fromRecent = recent?.let { parseContacts(it) }.orEmpty()
        if (fromRecent.isNotEmpty()) return BackendResult.Success(fromRecent)

        val fallback = runCatching { api.getPrivateMessages() }.getOrNull()
            ?: return BackendResult.Success(emptyList())
        return BackendResult.Success(parseContacts(fallback))
    }

    /** 与某个用户的私信历史（按时间正序，最新的在最后）。 */
    suspend fun getMessages(uid: Long, myUid: Long): MusicResult<List<Message>> {
        val json = runCatching { api.getMessageHistory(uid) }.getOrNull()
            ?: return BackendResult.Error("读取私信失败")
        val root = json as? JsonObject ?: return BackendResult.Error("私信响应格式异常")
        val array = (root["msgs"] as? JsonArray)
            ?: (root["data"] as? JsonObject)?.get("msgs") as? JsonArray
            ?: JsonArray(emptyList())
        val messages = array.mapNotNull { parseMessage(it, myUid) }
            // 上游按**新→旧**返回，聊天页要从上往下读，所以翻转一次。
            .reversed()
        return BackendResult.Success(messages)
    }

    /** 发送文本私信，返回服务端是否受理。 */
    suspend fun sendMessage(uid: Long, text: String): Boolean =
        runCatching { isOk(api.sendMessage(uid, text)) }.getOrDefault(false)

    /** 标记与某人的私信已读（失败静默 —— 这是个旁路动作，不该打断聊天）。 */
    suspend fun markRead(uid: Long) {
        runCatching { api.markMessageAsRead(uid) }
    }

    // ======================== 解析 ========================

    private fun parseContacts(json: JsonElement): List<Contact> {
        val root = json as? JsonObject ?: return emptyList()
        val array = (root["recentcontacts"] as? JsonArray)
            ?: (root["msgs"] as? JsonArray)
            ?: (root["data"] as? JsonObject)?.let {
                (it["recentcontacts"] as? JsonArray) ?: (it["msgs"] as? JsonArray)
            }
            ?: (root["data"] as? JsonArray)
            ?: return emptyList()
        return array.mapNotNull { parseContact(it) }.distinctBy { it.userId }
    }

    private fun parseContact(el: JsonElement): Contact? {
        val obj = el as? JsonObject ?: return null
        // 联系人信息可能挂在 fromUser / from / user / profile 下，逐个回退；
        // 最后一个 `?: obj` 兜底，让它成为**非空**类型 —— 否则下面每个字段都要写 `?.`。
        val user = (obj["fromUser"] as? JsonObject)
            ?: (obj["from"] as? JsonObject)
            ?: (obj["user"] as? JsonObject)
            ?: (obj["profile"] as? JsonObject)
            ?: obj
        val userId = (user["userId"] as? JsonPrimitive)?.longOrNull
            ?: (user["id"] as? JsonPrimitive)?.longOrNull
            ?: return null
        val nickname = (user["nickname"] as? JsonPrimitive)?.contentOrNull
            ?: (user["userName"] as? JsonPrimitive)?.contentOrNull
            ?: "未知用户"
        return Contact(
            userId = userId,
            nickname = nickname,
            avatarUrl = (user["avatarUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            lastMessage = extractMessageText(obj),
            lastMessageTime = (obj["lastMsgTime"] as? JsonPrimitive)?.longOrNull
                ?: (obj["time"] as? JsonPrimitive)?.longOrNull,
            unreadCount = (obj["newMsgCount"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    private fun parseMessage(el: JsonElement, myUid: Long): Message? {
        val obj = el as? JsonObject ?: return null
        val fromUser = obj["fromUser"] as? JsonObject ?: return null
        val userId = (fromUser["userId"] as? JsonPrimitive)?.longOrNull
            ?: (fromUser["id"] as? JsonPrimitive)?.longOrNull
            ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.longOrNull
            ?: (obj["msgId"] as? JsonPrimitive)?.longOrNull
            ?: return null
        return Message(
            id = id,
            fromUserId = userId,
            fromNickname = (fromUser["nickname"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            fromAvatarUrl = (fromUser["avatarUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            text = extractMessageText(obj),
            time = (obj["time"] as? JsonPrimitive)?.longOrNull ?: 0L,
            isMe = myUid != 0L && userId == myUid,
        )
    }

    /**
     * 取出正文。
     *
     * `msg` 是**字符串化的 JSON**（`{"msg":"...","type":0}`），少数 Provider 直接给纯文本，
     * 两种都认：先按 JSON 解一次，解不出来就原样当文本。
     */
    private fun extractMessageText(obj: JsonObject): String {
        val raw = (obj["msg"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["lastMsg"] as? JsonPrimitive)?.contentOrNull
            ?: return ""
        if (!raw.trimStart().startsWith("{")) return raw
        return runCatching {
            (parser.parseToJsonElement(raw) as? JsonObject)
                ?.get("msg")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull() ?: raw
    }

    private fun isOk(json: JsonElement): Boolean {
        val code = ((json as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
            ?: ((json as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull
        return code == null || code == 200 || code == 0
    }
}
