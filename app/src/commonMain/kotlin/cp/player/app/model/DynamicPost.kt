package cp.player.app.model

/**
 * 「动态」页的展示模型。
 *
 * 为什么不在 UI 里直接用 SDK 的 `SocialEvent`：
 * - 页面需要「谁发的 / 几点了 / 是不是转发」这些**展示派生值**，而 SDK 模型只承诺原始字段；
 * - 转发层是 `EventOrigin`（非递归快照），与主条目的 `SocialEvent` 是两个类型，
 *   UI 不该为这点差异写两套渲染；
 * - 头像、图片、资源卡在多个音源之间语义相同，映射集中在
 *   [cp.player.app.repository.SocialRepository]。
 */
data class DynamicPost(
    val id: String,
    val userId: String,
    val userName: String,
    val avatarUrl: String,
    val text: String,
    val publishedAtEpochMs: Long,
    val imageUrls: List<String> = emptyList(),
    val resourceId: String? = null,
    val resourceType: String? = null,
    val resourceTitle: String? = null,
    val resourceCoverUrl: String? = null,
    val resourceSubtitle: String? = null,
    val liked: Boolean = false,
    val likedCount: Int = 0,
    val commentCount: Int = 0,
    val forwardCount: Int = 0,
    val forwardedFrom: DynamicOrigin? = null,
    val location: String? = null,
) {
    /** 是否有可播放 / 可打开的关联资源。 */
    val hasResource: Boolean get() = !resourceId.isNullOrBlank() && !resourceType.isNullOrBlank()
}

/**
 * 转发条的「原动态」快照。
 *
 * 与 [DynamicPost] 的唯一区别是**没有** [DynamicPost.forwardedFrom] —— 上游的转发链只给一层，
 * 递归模型会把生成代码变成无限大小（Rust 侧直接编译失败）。
 */
data class DynamicOrigin(
    val id: String,
    val userId: String,
    val userName: String,
    val avatarUrl: String,
    val text: String,
    val publishedAtEpochMs: Long,
    val imageUrls: List<String> = emptyList(),
    val resourceId: String? = null,
    val resourceType: String? = null,
    val resourceTitle: String? = null,
    val resourceCoverUrl: String? = null,
    val resourceSubtitle: String? = null,
    val liked: Boolean = false,
    val likedCount: Int = 0,
    val commentCount: Int = 0,
    val forwardCount: Int = 0,
    val location: String? = null,
)
