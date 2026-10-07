package cp.player.core.music

/**
 * 评论（歌曲 / 歌单 / 专辑等资源的评论区条目）。
 *
 * 从 app 层上收的领域模型：UI 只渲染它，解析集中在
 * [MusicSourceFromApi.parseComments] / [MusicSourceFromApi.parseFloorComments]。
 *
 * ### 顶层评论 vs 楼层回复
 * - **顶层评论**：[parentCommentId] == 0，挂在资源下；[replyCount] 是它的楼层回复数。
 * - **楼层回复**：[parentCommentId] > 0，属于某个楼层；[beReplied] 是「回复 @谁」的目标。
 *
 * 上游字段缺失时一律按默认值兜底（0 / 空串），UI 不显示对应行，而不是显示一个 0。
 */
data class Comment(
    val id: Long,
    val content: String,
    val user: String,
    val avatar: String,
    val time: String,
    val likedCount: Int,
    val liked: Boolean,
    val replyCount: Int = 0,
    val beReplied: List<Reply>? = null,
    /** 评论者用户 id（用于点进用户主页）。上游缺失为 0。 */
    val userId: Long = 0L,
    /** 发表时间的毫秒时间戳。上游缺失为 0。 */
    val timeMs: Long = 0L,
    /** 所属楼层 id：0 = 顶层评论，> 0 = 某条顶层评论下的回复。 */
    val parentCommentId: Long = 0L,
    /** IP 属地（如「江苏」）。上游缺失为空串。 */
    val ipLocation: String = "",
    /** 是否来自「热门评论」。 */
    val isHot: Boolean = false,
    /**
     * 上游**内联**给出的前几条楼层回复（`showFloorComment.topReplies`）。
     *
     * 有了它，楼层不必等第二次请求就能显示出来 —— 这也是「某些音源不给 `replyCount`，
     * 楼层入口就永远不出现」的兜底：只要内联回复非空，UI 就认为这条评论有楼。
     */
    val topReplies: List<Comment> = emptyList(),
) {
    /** 是否为楼层内的回复（而非顶层评论）。 */
    val isFloorReply: Boolean get() = parentCommentId > 0L

    /** 「回复 @x」的目标昵称；没有目标时为 null。 */
    val replyToNickname: String?
        get() = beReplied?.firstOrNull()?.nickname?.takeIf { it.isNotBlank() }

    data class Reply(val userId: Long, val nickname: String, val content: String)
}

/**
 * 一页**顶层**评论。
 *
 * @param totalCount 该资源评论总数（服务端给多少信多少，缺失为 0）
 * @param cursor 服务端翻页游标（`comment/new` 系）；offset 系分页时为空
 * @param sortType 服务端**实际**使用的排序 —— 下一页必须用它：
 *   未登录时「推荐」会被服务端按「热度」服务，再用「推荐」请求下一页会 400
 */
data class CommentPage(
    val comments: List<Comment>,
    val totalCount: Long = 0L,
    val hasMore: Boolean = false,
    val cursor: String = "",
    val sortType: Int = 1,
)

/**
 * 一页**楼层**（某条顶层评论下的回复）。
 *
 * @param nextTime 下一页游标（`comment/floor` 的 `time`）—— 原样带回
 */
data class CommentFloorPage(
    val replies: List<Comment>,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val nextTime: Long = 0L,
)

/**
 * 评论长度上限（与网易云一致）。
 *
 * ⚠️ 按**码点**计，不是按 `String.length`：一个 emoji（代理对）占 2 个 Char 但只算 1 个
 * 字符。用 `length` 判定会让「打 70 个 emoji 就超长」这种反直觉的行为出现。
 */
const val COMMENT_MAX_LENGTH: Int = 140

/**
 * 按码点统计字符串长度（代理对算 1）。
 *
 * Kotlin common 没有 `String.codePointCount`（那是 JVM 专有），所以这里手写：
 * 遇到高代理且后随低代理时前进 2 个 Char，否则前进 1 个。
 */
fun commentCodePointLength(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        i += if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/**
 * 按码点截断到 [max]，**不切断代理对**（否则会留下半个 emoji，渲染成乱码）。
 *
 * 用于「粘贴超长文本时截到上限」，而不是拒绝输入。
 */
fun clipComment(text: String, max: Int = COMMENT_MAX_LENGTH): String {
    if (commentCodePointLength(text) <= max) return text
    var count = 0
    var i = 0
    while (i < text.length && count < max) {
        val c = text[i]
        i += if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return text.substring(0, i)
}
