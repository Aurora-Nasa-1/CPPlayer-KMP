package cp.player.core.music

/**
 * 评论（歌曲 / 歌单 / 专辑等资源的评论区条目）。
 *
 * 从 app 层上收的领域模型：UI 只渲染它，解析集中在
 * [MusicSourceFromApi.parseComments]。[replyCount] 与 [beReplied] 上游缺失时为空。
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
) {
    data class Reply(val userId: Long, val nickname: String, val content: String)
}
