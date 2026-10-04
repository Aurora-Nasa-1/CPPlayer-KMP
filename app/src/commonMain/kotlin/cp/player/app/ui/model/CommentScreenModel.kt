package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.core.BackendResult
import cp.player.core.music.Comment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommentUiState(
    val id: String,
    val type: String,
    val comments: List<Comment> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

class CommentScreenModel(val id: String, val type: String) : ScreenModel {
    private val _state = MutableStateFlow(CommentUiState(id, type))
    val state: StateFlow<CommentUiState> = _state.asStateFlow()

    /** 在途的加载协程：用它去重，而不是「loading 且有内容」这种会被空列表绕过的守卫。 */
    private var loadJob: Job? = null

    init {
        loadComments()
    }

    private fun extractRawId(fullId: String): String {
        return runCatching { cp.player.core.music.CPMediaId.parse(fullId).resourceId }.getOrDefault(fullId)
    }

    fun loadComments() {
        // 已有请求在途就别再发（原来的守卫是「loading && comments 非空」，
        // comments 为空时会被绕过 ⇒ 重复并发加载）。
        if (loadJob?.isActive == true) return

        _state.value = _state.value.copy(loading = true, error = null)
        loadJob = screenModelScope.launch {
            when (val result = AppModel.musicRepository.getComments(extractRawId(id), type)) {
                is BackendResult.Success -> _state.value = _state.value.copy(
                    comments = result.data,
                    loading = false,
                )
                is BackendResult.Error -> _state.value = _state.value.copy(
                    error = result.message,
                    loading = false,
                )
                is BackendResult.Unsupported -> _state.value = _state.value.copy(
                    error = result.message,
                    loading = false,
                )
            }
        }
    }

    /** 点赞/取消点赞评论（乐观更新，失败回滚）。 */
    fun toggleLike(comment: Comment) {
        // ⚠️ 以**当前 state** 里的这条评论为准，而非调用方传入的快照：快速连点或列表刷新后，
        // 传入对象可能已过时，基于它翻转会算错计数，失败回滚也会把过时状态写回去。
        val current = _state.value.comments.firstOrNull { it.id == comment.id } ?: comment
        val target = !current.liked
        updateComment(current.copy(liked = target, likedCount = current.likedCount + if (target) 1 else -1))
        screenModelScope.launch {
            val ok = runCatching {
                AppModel.musicRepository.likeComment(extractRawId(id), comment.id, type, target)
            }.getOrDefault(false)
            if (!ok) updateComment(current)
        }
    }

    private fun updateComment(updated: Comment) {
        _state.value = _state.value.copy(
            comments = _state.value.comments.map { if (it.id == updated.id) updated else it }
        )
    }
}
