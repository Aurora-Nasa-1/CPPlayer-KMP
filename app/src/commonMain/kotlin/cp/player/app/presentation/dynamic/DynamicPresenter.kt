package cp.player.app.presentation.dynamic

import cp.player.app.AppModel
import cp.player.app.model.DynamicPost
import cp.player.app.presentation.UiPresenter
import cp.player.app.repository.DynamicPage
import cp.player.core.BackendResult
import cp.player.core.music.Comment
import cp.player.core.music.MusicResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 动态流的来源：关注流 / 只看自己 / 某个指定用户。 */
sealed interface DynamicFilter {
    data object Following : DynamicFilter
    data class User(val userId: String, val nickname: String) : DynamicFilter
}

data class DynamicUiState(
    val posts: List<DynamicPost> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** 音源未实现动态能力；与 [error] 分开，页面给「不支持」而不是「出错了 / 重试」。 */
    val unsupported: Boolean = false,
    val filter: DynamicFilter = DynamicFilter.Following,
    val hasMore: Boolean = false,
    val cursor: String? = null,
    /** 正在提交点赞 / 转发 / 删除的动态 id，用于禁用对应按钮。 */
    val pendingIds: Set<String> = emptySet(),
    /** 正在评论的动态；null = 评论面板关闭。 */
    val commentTarget: DynamicPost? = null,
    val comments: List<Comment> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentsHasMore: Boolean = false,
    val commentsCursor: String = "",
    val commentsError: String? = null,
    val commentDraft: String = "",
    val commentSending: Boolean = false,
    /** 是否已经跑过一次加载（成功 / 空 / 错误 / 不支持都算）。tab 重建时据此避免重复请求。 */
    val initialized: Boolean = false,
) {
    /** 首屏加载态（骨架）只在「没有数据且正在加载」时成立。 */
    val showSkeleton: Boolean get() = loading && posts.isEmpty() && !unsupported

    /** 空态：真的没有动态，且不在加载 / 不支持 / 出错状态。 */
    val showEmpty: Boolean get() = !loading && !refreshing && posts.isEmpty() && error == null && !unsupported
}

/**
 * 动态页框架无关呈现契约。
 */
interface DynamicContract : UiPresenter<DynamicUiState> {
    fun setFilter(filter: DynamicFilter)
    fun ensureLoaded()
    fun refresh()
    fun loadMore()
    fun showOwnPosts()
    fun toggleLike(postId: String)
    fun forward(postId: String, text: String)
    fun delete(postId: String)
    fun openComments(post: DynamicPost)
    fun closeComments()
    fun setCommentDraft(text: String)
    fun loadComments(reset: Boolean = false)
    fun postComment()
}

/**
 * 「动态」页状态机（框架无关）。
 *
 * 设计要点：
 * - 只有一条数据路径（[cp.player.app.repository.SocialRepository.getFeed]），
 *   不把「关注流」说成上游没有承诺的东西：`getEvents` 的语义就是「当前账号的社交流」；
 * - 音源能力缺失（Unsupported）与网络失败（Error）在 UI 上是两种页面，
 *   前者不给「重试」按钮，因为重试永远不会成功；
 * - 登录态变化 / 切换音源都会作废旧结果（`drop(1)` 跳过启动恢复的那次发射）；
 * - 点赞先乐观更新，失败再回滚并提示，避免每次点赞都等一个来回。
 */
class DynamicPresenter(
    override val scope: CoroutineScope,
    private val notify: (String) -> Unit = {},
) : DynamicContract {
    private val _state = MutableStateFlow(DynamicUiState())
    override val state: StateFlow<DynamicUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadGen = 0

    init {
        // 不在 init 里发请求：本页既是常驻 tab、又是可 push 的路由页，
        // 真正的首次加载由 `ensureLoaded()` 在进入组合时触发。
        scope.launch {
            AppModel.accountGeneration.drop(1).collect { refresh() }
        }
        scope.launch {
            AppModel.sourceGeneration.drop(1).collect { refresh() }
        }
    }

    /** 切到关注流 / 指定用户。 */
    override fun setFilter(filter: DynamicFilter) {
        if (_state.value.filter == filter) return
        _state.update {
            it.copy(
                filter = filter,
                posts = emptyList(),
                initialized = false,
                cursor = null,
                hasMore = false,
                error = null,
                unsupported = false,
            )
        }
        refresh()
    }

    /** 首次进入 tab 时懒加载：已加载过（含空态 / 不支持态）就不再自动重打。 */
    override fun ensureLoaded() {
        val s = _state.value
        if (s.loading || s.refreshing || s.posts.isNotEmpty() || s.unsupported) return
        if (s.error != null) return
        if (!s.initialized) refresh()
    }

    /** 下拉刷新 / 首屏加载。已有数据时保持列表可见，只置 [DynamicUiState.refreshing]。 */
    override fun refresh() {
        loadJob?.cancel()
        val gen = ++loadGen
        val current = _state.value
        loadJob = scope.launch {
            val silent = current.posts.isNotEmpty()
            _state.update {
                it.copy(
                    loading = !silent,
                    refreshing = silent,
                    error = null,
                    unsupported = false,
                    loadingMore = false,
                )
            }
            val result = fetchPage(current.filter, cursor = null)
            if (gen != loadGen) return@launch
            when (result) {
                is BackendResult.Success -> {
                    val page = result.data
                    _state.update {
                        it.copy(
                            posts = page.items,
                            initialized = true,
                            loading = false,
                            refreshing = false,
                            hasMore = page.hasMore,
                            cursor = page.cursor,
                            error = null,
                            unsupported = false,
                        )
                    }
                }
                is BackendResult.Unsupported -> _state.update {
                    it.copy(
                        posts = emptyList(),
                        initialized = true,
                        loading = false,
                        refreshing = false,
                        unsupported = true,
                        hasMore = false,
                        cursor = null,
                        error = null,
                    )
                }
                is BackendResult.Error -> _state.update {
                    it.copy(
                        initialized = true,
                        loading = false,
                        refreshing = false,
                        error = result.message,
                    )
                }
            }
        }
    }

    /** 触底加载下一页。没有游标 / 没有更多 / 已在加载时直接返回。 */
    override fun loadMore() {
        val s = _state.value
        if (s.loading || s.refreshing || s.loadingMore || !s.hasMore) return
        val cursor = s.cursor ?: return
        val gen = loadGen
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            val result = fetchPage(s.filter, cursor = cursor)
            if (gen != loadGen) return@launch
            when (result) {
                is BackendResult.Success -> {
                    val page = result.data
                    _state.update {
                        it.copy(
                            posts = (it.posts + page.items).distinctBy { post -> post.id },
                            loadingMore = false,
                            hasMore = page.hasMore,
                            cursor = page.cursor,
                        )
                    }
                }
                is BackendResult.Unsupported -> _state.update {
                    it.copy(loadingMore = false, unsupported = true, hasMore = false)
                }
                is BackendResult.Error -> _state.update {
                    it.copy(loadingMore = false, error = result.message)
                }
            }
        }
    }

    private suspend fun fetchPage(filter: DynamicFilter, cursor: String?): MusicResult<DynamicPage> =
        runCatching {
            when (filter) {
                DynamicFilter.Following -> AppModel.socialRepository.getFeed(cursor = cursor)
                is DynamicFilter.User -> AppModel.socialRepository.getUserFeed(
                    userId = filter.userId,
                    cursor = cursor,
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            BackendResult.Error(error.message ?: "无法加载动态")
        }

    /** 在关注流与「我的动态」之间切换。 */
    override fun showOwnPosts() {
        val selfId = AppModel.userProfileFlow.value?.uid?.toString().orEmpty()
        if (selfId.isBlank()) {
            notify("请先登录后再查看自己的动态")
            return
        }
        // 从「关注」或「TA 的动态」点进来都应落到自己的动态；只有已经在自己动态时
        // 再点一次才切回关注。否则在用户主页点「我的动态」会意外回到关注流。
        val current = _state.value.filter
        if (current is DynamicFilter.User && current.userId == selfId) {
            setFilter(DynamicFilter.Following)
        } else {
            setFilter(DynamicFilter.User(selfId, "我的动态"))
        }
    }

    // ======================== 互动 ========================

    /**
     * 点赞 / 取消点赞（乐观更新）。
     *
     * 先改本地态让按钮立刻响应，再发请求；失败回滚并发出提示。
     * 同一条动态的重复点击被 [DynamicUiState.pendingIds] 拦住。
     */
    override fun toggleLike(postId: String) {
        val post = _state.value.posts.firstOrNull { it.id == postId } ?: return
        if (postId in _state.value.pendingIds) return
        val target = !post.liked
        _state.update { s ->
            s.copy(
                posts = s.posts.map {
                    if (it.id == postId) {
                        it.copy(
                            liked = target,
                            likedCount = (it.likedCount + if (target) 1 else -1).coerceAtLeast(0),
                        )
                    } else {
                        it
                    }
                },
                pendingIds = s.pendingIds + postId,
            )
        }
        scope.launch {
            val ok = runCatching { AppModel.socialRepository.likeEvent(postId, target) }
                .getOrDefault(false)
            _state.update { s ->
                s.copy(
                    posts = if (ok) {
                        s.posts
                    } else {
                        s.posts.map {
                            if (it.id == postId) {
                                it.copy(liked = post.liked, likedCount = post.likedCount)
                            } else {
                                it
                            }
                        }
                    },
                    pendingIds = s.pendingIds - postId,
                )
            }
            if (!ok) notify("点赞没有生效，请稍后再试")
        }
    }

    /** 转发动态；成功后本地转发数 +1。 */
    override fun forward(postId: String, text: String) {
        if (postId in _state.value.pendingIds) return
        val userId = AppModel.userProfileFlow.value?.uid?.toString().orEmpty()
        if (userId.isBlank()) {
            notify("请先登录后再转发")
            return
        }
        _state.update { it.copy(pendingIds = it.pendingIds + postId) }
        scope.launch {
            val ok = runCatching { AppModel.socialRepository.forwardEvent(postId, userId, text) }
                .getOrDefault(false)
            _state.update { s ->
                s.copy(
                    posts = if (ok) {
                        s.posts.map {
                            if (it.id == postId) it.copy(forwardCount = it.forwardCount + 1) else it
                        }
                    } else {
                        s.posts
                    },
                    pendingIds = s.pendingIds - postId,
                )
            }
            notify(if (ok) "已转发" else "转发失败，请稍后再试")
        }
    }

    /** 删除自己发的动态；成功后从列表移除。 */
    override fun delete(postId: String) {
        if (postId in _state.value.pendingIds) return
        _state.update { it.copy(pendingIds = it.pendingIds + postId) }
        scope.launch {
            val ok = runCatching { AppModel.socialRepository.deleteEvent(postId) }
                .getOrDefault(false)
            _state.update { s ->
                s.copy(
                    posts = if (ok) s.posts.filterNot { it.id == postId } else s.posts,
                    pendingIds = s.pendingIds - postId,
                )
            }
            notify(if (ok) "已删除" else "删除失败，请稍后再试")
        }
    }

    // ======================== 评论 ========================

    /** 打开某条动态的评论面板并拉第一页。 */
    override fun openComments(post: DynamicPost) {
        _state.update {
            it.copy(
                commentTarget = post,
                comments = emptyList(),
                commentsCursor = "",
                commentsHasMore = false,
                commentsError = null,
                commentDraft = "",
            )
        }
        loadComments(reset = true)
    }

    override fun closeComments() {
        _state.update {
            it.copy(
                commentTarget = null,
                comments = emptyList(),
                commentsError = null,
                commentDraft = "",
            )
        }
    }

    override fun setCommentDraft(text: String) {
        _state.update { it.copy(commentDraft = text) }
    }

    override fun loadComments(reset: Boolean) {
        val target = _state.value.commentTarget ?: return
        if (_state.value.commentsLoading) return
        if (!reset && !_state.value.commentsHasMore) return
        val cursor = if (reset) "" else _state.value.commentsCursor
        _state.update { it.copy(commentsLoading = true, commentsError = null) }
        scope.launch {
            val result = runCatching {
                AppModel.musicRepository.getComments(
                    rawId = target.id,
                    type = "event",
                    limit = 20,
                    cursor = cursor.ifBlank { null },
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                BackendResult.Error(error.message ?: "无法加载评论")
            }
            when (result) {
                is BackendResult.Success -> {
                    val page = result.data
                    _state.update {
                        it.copy(
                            comments = if (reset) page.comments else (it.comments + page.comments).distinctBy { c -> c.id },
                            commentsLoading = false,
                            commentsHasMore = page.hasMore,
                            commentsCursor = page.cursor,
                        )
                    }
                }
                is BackendResult.Unsupported -> _state.update {
                    it.copy(commentsLoading = false, commentsError = "当前音源不支持动态评论")
                }
                is BackendResult.Error -> _state.update {
                    it.copy(commentsLoading = false, commentsError = result.message)
                }
            }
        }
    }

    /** 发表评论；成功后重新拉第一页（上游不保证返回完整条目）。 */
    override fun postComment() {
        val s = _state.value
        val target = s.commentTarget ?: return
        val text = s.commentDraft.trim()
        if (text.isBlank() || s.commentSending) return
        _state.update { it.copy(commentSending = true) }
        scope.launch {
            val result = runCatching {
                AppModel.musicRepository.postComment(
                    rawId = target.id,
                    type = "event",
                    content = text,
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                BackendResult.Error(error.message ?: "评论发送失败")
            }
            when (result) {
                is BackendResult.Success -> {
                    _state.update {
                        it.copy(
                            commentSending = false,
                            commentDraft = "",
                            posts = it.posts.map { p ->
                                if (p.id == target.id) p.copy(commentCount = p.commentCount + 1) else p
                            },
                        )
                    }
                    loadComments(reset = true)
                }
                is BackendResult.Unsupported -> {
                    _state.update {
                        it.copy(commentSending = false, commentsError = "当前音源不支持发表动态评论")
                    }
                }
                is BackendResult.Error -> {
                    _state.update { it.copy(commentSending = false, commentsError = result.message) }
                }
            }
        }
    }
}
