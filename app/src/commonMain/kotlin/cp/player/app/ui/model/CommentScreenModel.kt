package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.core.BackendResult
import cp.player.core.music.COMMENT_MAX_LENGTH
import cp.player.core.music.Comment
import cp.player.core.music.clipComment
import cp.player.core.music.commentCodePointLength
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 评论排序（取值与上游 `sortType` 一致）。 */
object CommentSort {
    /** 推荐（默认）。未登录时服务端会按热度服务，返回体里会带回实际值。 */
    const val RECOMMEND = 1

    /** 热度。 */
    const val HOT = 2

    /** 最新。 */
    const val LATEST = 3

    val ALL: List<Int> = listOf(RECOMMEND, HOT, LATEST)
}

/**
 * 一个楼层（某条顶层评论下的回复）的展开 / 加载状态。
 *
 * @param parentId 楼层所属的顶层评论 id
 * @param nextTime 下一页游标（原样带回请求）
 * @param expanded 是否展开（折叠时不渲染回复列表，但仍保留已加载数据）
 */
data class CommentFloorUi(
    val parentId: Long,
    val replies: List<Comment> = emptyList(),
    val totalCount: Int = 0,
    val nextTime: Long = 0L,
    val hasMore: Boolean = false,
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /**
     * 是否已从服务端**完整**拉过第一页。
     *
     * 与「[replies] 非空」不是一回事：上游 `showFloorComment.topReplies` 会先内联几条回复
     * 当种子（让楼层入口立刻可见），但那些不是完整的一页，展开时仍要补一次请求。
     */
    val loadedFromServer: Boolean = false,
)

data class CommentUiState(
    val id: String,
    val type: String,
    /** 用户选择的排序。 */
    val sortType: Int = CommentSort.RECOMMEND,
    val comments: List<Comment> = emptyList(),
    val totalCount: Long = 0L,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** 楼层缓存：key = 顶层评论 id。 */
    val floors: Map<Long, CommentFloorUi> = emptyMap(),
    /** 回复目标；null = 发表新的顶层评论。 */
    val replyTarget: Comment? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val sendError: String? = null,
) {
    /** 草稿是否超出长度上限（按码点计）。 */
    val draftTooLong: Boolean get() = commentCodePointLength(draft) > COMMENT_MAX_LENGTH

    /** 能否发送。 */
    val canSend: Boolean get() = draft.isNotBlank() && !draftTooLong && !sending
}

/**
 * 评论区状态机（窄屏评论页与桌面评论弹层共用）。
 *
 * 职责：排序 / 分页 / 楼层展开折叠 / 发表与回复 / 点赞。
 * 所有网络调用都经 [cp.player.app.repository.MusicRepository]，本类不碰 Provider 细节。
 */
class CommentScreenModel(val id: String, val type: String) : ScreenModel {

    private val _state = MutableStateFlow(CommentUiState(id, type))
    val state: StateFlow<CommentUiState> = _state.asStateFlow()

    /** 在途的「顶层列表」请求：用它去重，而不是「loading 且有内容」这种会被空列表绕过的守卫。 */
    private var loadJob: Job? = null

    /** 在途的楼层请求，按楼层 id 去重。 */
    private val floorJobs = mutableMapOf<Long, Job>()

    /** 服务端**实际**使用的排序 —— 翻页必须回传它（见 [CommentSort] 的说明）。 */
    private var serverSortType: Int = CommentSort.RECOMMEND

    /** 下一页 offset（顶层列表）。 */
    private var nextOffset: Int = 0

    init {
        loadComments()
    }

    private fun extractRawId(fullId: String): String {
        return runCatching { cp.player.core.music.CPMediaId.parse(fullId).resourceId }.getOrDefault(fullId)
    }

    /** 重新加载第一页（首次进入 / 下拉刷新）。 */
    fun loadComments() {
        reload()
    }

    /** 切换排序并重新加载。 */
    fun setSort(sortType: Int) {
        if (sortType == _state.value.sortType) return
        serverSortType = sortType
        _state.value = _state.value.copy(
            sortType = sortType,
            comments = emptyList(),
            floors = emptyMap(),
            hasMore = false,
            error = null,
        )
        reload()
    }

    private fun reload() {
        // 已有请求在途就别再发（原来的守卫是「loading && comments 非空」，
        // comments 为空时会被绕过 ⇒ 重复并发加载）。
        if (loadJob?.isActive == true) return

        _state.value = _state.value.copy(loading = true, error = null, loadingMore = false)
        nextOffset = 0
        loadJob = screenModelScope.launch {
            val result = AppModel.musicRepository.getComments(
                rawId = extractRawId(id),
                type = type,
                limit = PAGE_SIZE,
                offset = 0,
                sortType = _state.value.sortType,
            )
            when (result) {
                is BackendResult.Success -> {
                    val page = result.data
                    serverSortType = page.sortType
                    nextOffset = page.comments.size
                    _state.value = _state.value.copy(
                        comments = page.comments,
                        totalCount = page.totalCount,
                        hasMore = page.hasMore,
                        loading = false,
                        // 用上游内联的 topReplies 预置楼层：入口立刻可见，展开不用先转圈。
                        floors = seedFloors(page.comments),
                    )
                }
                is BackendResult.Error -> _state.value = _state.value.copy(error = result.message, loading = false)
                is BackendResult.Unsupported -> _state.value = _state.value.copy(error = result.message, loading = false)
            }
        }
    }

    /**
     * 用上游内联的 `topReplies` 预置楼层缓存。
     *
     * 两个目的：① 有些音源只给内联回复、不给 `replyCount` —— 预置后楼层入口才会出现；
     * ② 展开时立刻就有内容，而不是先转圈。`loadedFromServer` 保持 false，
     * 展开时仍会补一次「完整第一页」的请求。
     */
    private fun seedFloors(comments: List<Comment>): Map<Long, CommentFloorUi> =
        comments
            .filter { it.topReplies.isNotEmpty() }
            .associate { it.id to CommentFloorUi(parentId = it.id, replies = it.topReplies) }

    /** 加载下一页顶层评论（滚动到底部触发）。 */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        if (loadJob?.isActive == true) return

        _state.value = s.copy(loadingMore = true)
        loadJob = screenModelScope.launch {
            val result = AppModel.musicRepository.getComments(
                rawId = extractRawId(id),
                type = type,
                limit = PAGE_SIZE,
                offset = nextOffset,
                sortType = serverSortType,
            )
            when (result) {
                is BackendResult.Success -> {
                    val page = result.data
                    serverSortType = page.sortType
                    nextOffset += page.comments.size
                    _state.value = _state.value.copy(
                        comments = _state.value.comments + page.comments,
                        hasMore = page.hasMore && page.comments.isNotEmpty(),
                        loadingMore = false,
                    )
                }
                is BackendResult.Error -> _state.value = _state.value.copy(loadingMore = false, error = result.message)
                is BackendResult.Unsupported -> _state.value = _state.value.copy(loadingMore = false, error = result.message)
            }
        }
    }

    // ============================== 楼层 ==============================

    /** 展开 / 折叠楼层；首次展开时拉第一页。 */
    fun toggleFloor(comment: Comment) {
        val floorId = comment.id
        val current = _state.value.floors[floorId]
        if (current?.expanded == true) {
            updateFloor(floorId) { it.copy(expanded = false) }
            return
        }
        val opened = (current ?: CommentFloorUi(parentId = floorId)).copy(expanded = true)
        _state.value = _state.value.copy(floors = _state.value.floors + (floorId to opened))
        // 内联种子（topReplies）不算「拉过完整一页」，所以仍要补一次请求；已拉过就不再拉。
        if (!opened.loadedFromServer && !opened.loading) loadFloorPage(floorId, time = 0L)
    }

    /** 楼层内「查看更多回复」。 */
    fun loadMoreFloor(comment: Comment) {
        val floorId = comment.id
        val current = _state.value.floors[floorId] ?: return
        if (current.loading || !current.hasMore) return
        loadFloorPage(floorId, time = current.nextTime)
    }

    private fun loadFloorPage(floorId: Long, time: Long) {
        if (floorJobs[floorId]?.isActive == true) return
        updateFloor(floorId) { it.copy(loading = true, error = null, expanded = true) }
        floorJobs[floorId] = screenModelScope.launch {
            val result = AppModel.musicRepository.getFloorComments(
                rawId = extractRawId(id),
                parentCommentId = floorId,
                type = type,
                limit = PAGE_SIZE,
                time = time,
            )
            when (result) {
                is BackendResult.Success -> {
                    val page = result.data
                    updateFloor(floorId) { floor ->
                        val merged = if (time == 0L) page.replies else floor.replies + page.replies
                        floor.copy(
                            replies = merged,
                            totalCount = if (page.totalCount > 0) page.totalCount else merged.size,
                            nextTime = page.nextTime,
                            hasMore = page.hasMore && page.replies.isNotEmpty(),
                            loading = false,
                            loadedFromServer = true,
                        )
                    }
                }
                is BackendResult.Error -> updateFloor(floorId) { it.copy(loading = false, error = result.message) }
                is BackendResult.Unsupported -> updateFloor(floorId) { it.copy(loading = false, error = result.message) }
            }
        }
    }

    private fun updateFloor(floorId: Long, transform: (CommentFloorUi) -> CommentFloorUi) {
        val floors = _state.value.floors
        val current = floors[floorId] ?: CommentFloorUi(parentId = floorId)
        _state.value = _state.value.copy(floors = floors + (floorId to transform(current)))
    }

    // ============================== 输入条 ==============================

    /** 开始回复某条评论（顶层评论或其楼层里的回复）。 */
    fun startReply(comment: Comment) {
        _state.value = _state.value.copy(replyTarget = comment, sendError = null)
    }

    /** 取消回复，回到「发表新评论」。 */
    fun cancelReply() {
        _state.value = _state.value.copy(replyTarget = null)
    }

    fun updateDraft(text: String) {
        _state.value = _state.value.copy(draft = text, sendError = null)
    }

    /** 粘贴超长文本时截到上限（按码点，不切断代理对）。 */
    fun clipDraft() {
        val clipped = clipComment(_state.value.draft)
        if (clipped != _state.value.draft) updateDraft(clipped)
    }

    /**
     * 发送（顶层评论或回复）。
     *
     * 成功后**就地插入**返回的评论（顶层插到最前、楼层追加到该楼层），失败则保留草稿并给出原因。
     */
    fun send() {
        val s = _state.value
        val content = s.draft.trim()
        if (s.sending || content.isEmpty() || commentCodePointLength(content) > COMMENT_MAX_LENGTH) return

        val target = s.replyTarget
        // 回复楼层里的回复 → 落在**同一楼层**；直接回复顶层评论 → 该顶层评论就是楼层。
        val floorId = target?.let { if (it.parentCommentId > 0L) it.parentCommentId else it.id } ?: 0L

        _state.value = s.copy(sending = true, sendError = null)
        screenModelScope.launch {
            val result = AppModel.musicRepository.postComment(
                rawId = extractRawId(id),
                type = type,
                content = content,
                replyToCommentId = target?.id,
                parentCommentId = floorId,
            )
            when (result) {
                is BackendResult.Success -> {
                    val created = result.data
                    _state.value = _state.value.copy(
                        sending = false,
                        draft = "",
                        replyTarget = null,
                        sendError = null,
                    )
                    if (created != null) {
                        insertCreated(created, floorId)
                    } else {
                        // 服务端没回体：无法就地插入，重新拉一页保证能看到自己的评论。
                        reload()
                    }
                }
                is BackendResult.Error -> _state.value = _state.value.copy(sending = false, sendError = result.message)
                is BackendResult.Unsupported -> _state.value = _state.value.copy(sending = false, sendError = result.message)
            }
        }
    }

    private fun insertCreated(created: Comment, floorId: Long) {
        if (floorId == 0L) {
            _state.value = _state.value.copy(
                comments = listOf(created) + _state.value.comments,
                totalCount = _state.value.totalCount + 1,
            )
            return
        }
        // 先把顶层评论的回复计数 +1（此刻 floors 还没动，两者互不干扰）。
        val nextComments = _state.value.comments.map {
            if (it.id == floorId) it.copy(replyCount = it.replyCount + 1) else it
        }
        updateFloor(floorId) { floor ->
            floor.copy(
                replies = floor.replies + created,
                totalCount = floor.totalCount + 1,
                expanded = true,
            )
        }
        _state.value = _state.value.copy(comments = nextComments)
    }

    // ============================== 点赞 ==============================

    /** 点赞 / 取消点赞评论（乐观更新，失败回滚）。顶层评论与楼层回复都能点赞。 */
    fun toggleLike(comment: Comment) {
        // ⚠️ 以**当前 state** 里的这条评论为准，而非调用方传入的快照：快速连点或列表刷新后，
        // 传入对象可能已过时，基于它翻转会算错计数，失败回滚也会把过时状态写回去。
        val current = findComment(comment.id) ?: comment
        val target = !current.liked
        applyToComment(
            current.copy(
                liked = target,
                likedCount = (current.likedCount + if (target) 1 else -1).coerceAtLeast(0),
            )
        )
        screenModelScope.launch {
            val ok = runCatching {
                AppModel.musicRepository.likeComment(extractRawId(id), comment.id, type, target)
            }.getOrDefault(false)
            if (!ok) applyToComment(current)
        }
    }

    private fun findComment(cid: Long): Comment? =
        _state.value.comments.firstOrNull { it.id == cid }
            ?: _state.value.floors.values.firstNotNullOfOrNull { floor ->
                floor.replies.firstOrNull { it.id == cid }
            }

    /** 把一条评论的更新同时写回顶层列表、所有楼层、以及当前回复目标。 */
    private fun applyToComment(updated: Comment) {
        val state = _state.value
        val comments = state.comments.map { if (it.id == updated.id) updated else it }
        val floors = state.floors.mapValues { (_, floor) ->
            if (floor.replies.none { it.id == updated.id }) floor
            else floor.copy(replies = floor.replies.map { if (it.id == updated.id) updated else it })
        }
        val replyTarget = state.replyTarget?.let { if (it.id == updated.id) updated else it }
        _state.value = state.copy(comments = comments, floors = floors, replyTarget = replyTarget)
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
