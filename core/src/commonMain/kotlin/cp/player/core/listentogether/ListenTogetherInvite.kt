package cp.player.core.listentogether

/**
 * 一起听邀请的**链接拼装与解析**（纯函数，无副作用，可单测）。
 *
 * ### 为什么邀请要靠链接而不是接口
 * 网易云**没有**「发出邀请」的 API（官方包 / Rust 端口 / HyPlayer / QCloudMusicApi
 * 五个独立实现都只有同样那 9 个端点）。但邀请的本质只是
 * **把 `roomId` + `inviterId` 送达对方**，而官方 App 自己就是这么做的——
 * 它把这两个参数拼进一条分享链接。所以只要复刻这个链接格式，
 * 邀请功能就完整了，**根本不需要那个不存在的接口**。
 *
 * 链接格式取自一个真实项目的实现：
 * `https://st.music.163.com/listen-together/share/?songId=…&roomId=…&inviterId=…`
 *
 * @see ListenTogetherBackend.accept 对方收到参数后用它进房
 */
object ListenTogetherInvite {

    /** 官方分享页基址。刻意保留末尾斜杠 —— 少了它会 302 到别处。 */
    const val SHARE_BASE = "https://st.music.163.com/listen-together/share/"

    /**
     * 拼装分享链接。
     *
     * ⚠️ **不做百分号编码**，因为三个参数的实际取值都是安全字符集：
     * `roomId` = `{32位hex}_{时间戳}`（实测），`inviterId` 是数字，`songId` 是数字。
     * 这不是「懒得编码」，是**不引入一个会改变取值的变换**——
     * 官方 App 解析这条链接时按原样取值，多一层编码反而可能对不上。
     * 若将来 roomId 形态变化（出现非安全字符），必须在这里补编码并同步改解析。
     */
    fun buildShareUrl(roomId: String, inviterId: Long, songId: String? = null): String {
        val parts = buildList {
            if (!songId.isNullOrEmpty()) add("songId=$songId")
            add("roomId=$roomId")
            add("inviterId=$inviterId")
        }
        return SHARE_BASE + "?" + parts.joinToString("&")
    }

    /**
     * 从任意文本里解析邀请参数。
     *
     * 刻意接受**整段文本**而不是只接受 URL：邀请往往被夹在私信正文里
     * （官方那条 `inbox_invite` 就是这么投递的，实测对方端要从 `row.msg` 里正则取值）。
     * 只认「纯 URL」会导致对方把链接贴进聊天框后我们解析不了。
     *
     * 两个参数缺一不可 —— 少一个就进不了房（`accept` 需要成对的 roomId + inviterId），
     * 所以返回 null 而不是给个半成品对象。
     */
    fun parse(text: String): InviteParams? {
        val roomId = extract(text, "roomId") ?: return null
        val inviterId = extract(text, "inviterId")?.toLongOrNull() ?: return null
        if (roomId.isEmpty()) return null
        return InviteParams(
            roomId = roomId,
            inviterId = inviterId,
            songId = extract(text, "songId"),
        )
    }

    /**
     * 取查询参数值。
     *
     * 终止符包含 `"` 与空白：链接常被包在 JSON 或 Markdown 里，
     * 那时它后面跟的是引号而不是 `&`（`row.msg` 就是这种形态）。
     */
    private fun extract(text: String, key: String): String? =
        Regex("""[?&]${Regex.escape(key)}=([^&#\s"']+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.get(1)
}

/** 解析出的邀请参数。 */
data class InviteParams(
    val roomId: String,
    val inviterId: Long,
    val songId: String?,
)
