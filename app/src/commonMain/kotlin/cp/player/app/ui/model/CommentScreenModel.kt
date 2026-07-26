package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

data class Comment(
    val id: Long,
    val content: String,
    val user: String,
    val avatar: String,
    val time: String,
    val likedCount: Int,
    val liked: Boolean,
    val replyCount: Int = 0,
    val beReplied: List<Reply>? = null
) {
    data class Reply(val userId: Long, val nickname: String, val content: String)
}

data class CommentUiState(
    val id: String,
    val type: String,
    val comments: List<Comment> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

// ========= 自动解析所需的 DTO 结构 (替代 Gson) =========
@Serializable
data class CommentResponseDto(
    val data: CommentDataDto? = null,
    val comments: List<CommentDto>? = null,
    val hotComments: List<CommentDto>? = null
)

@Serializable
data class CommentDataDto(
    val comments: List<CommentDto>? = null,
    val hotComments: List<CommentDto>? = null
)

@Serializable
data class CommentDto(
    val commentId: Long? = null,
    val id: Long? = null,
    val content: String? = null,
    val timeStr: String? = null,
    val time: Long? = null,
    val likedCount: Int? = null,
    val liked: Boolean? = null,
    val user: CommentUserDto? = null,
    val author: CommentUserDto? = null
)

@Serializable
data class CommentUserDto(
    val nickname: String? = null,
    val avatarUrl: String? = null
)
// ===================================================

class CommentScreenModel(val id: String, val type: String) : ScreenModel {
    private val _state = MutableStateFlow(CommentUiState(id, type))
    val state: StateFlow<CommentUiState> = _state.asStateFlow()

    init {
        loadComments()
    }

    private val jsonDecoder = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private fun extractRawId(fullId: String): String {
        return runCatching { cp.player.kmp.music.CPMediaId.parse(fullId).resourceId }.getOrDefault(fullId)
    }

    fun loadComments() {
        if (_state.value.loading && _state.value.comments.isNotEmpty()) return
        
        _state.value = _state.value.copy(loading = true, error = null)
        screenModelScope.launch {
            runCatching {
                val rawJsonElement = AppModel.api.getComments(extractRawId(id), type)
                
                // 使用 kotlinx.serialization 自动解析为对象，就和 Gson 的 fromJson 一样
                val response = jsonDecoder.decodeFromJsonElement<CommentResponseDto>(rawJsonElement)
                
                val dtos = response.data?.comments 
                    ?: response.comments 
                    ?: response.data?.hotComments 
                    ?: response.hotComments 
                    ?: emptyList()
                    
                val commentList = dtos.map { dto ->
                    val userDto = dto.user ?: dto.author
                    Comment(
                        id = dto.commentId ?: dto.id ?: 0L,
                        content = dto.content ?: "",
                        user = userDto?.nickname ?: "Unknown",
                        avatar = userDto?.avatarUrl ?: "",
                        time = dto.timeStr ?: dto.time?.toString() ?: "",
                        likedCount = dto.likedCount ?: 0,
                        liked = dto.liked ?: false
                    )
                }
                
                _state.value = _state.value.copy(comments = commentList, loading = false)
            }.onFailure {
                _state.value = _state.value.copy(error = it.message, loading = false)
            }
        }
    }

    /** 点赞/取消点赞评论（乐观更新，失败回滚）。 */
    fun toggleLike(comment: Comment) {
        val target = !comment.liked
        updateComment(comment.copy(liked = target, likedCount = comment.likedCount + if (target) 1 else -1))
        screenModelScope.launch {
            val ok = runCatching {
                AppModel.api.likeComment(extractRawId(id), comment.id, type, target)
                true
            }.getOrDefault(false)
            if (!ok) updateComment(comment)
        }
    }

    private fun updateComment(updated: Comment) {
        _state.value = _state.value.copy(
            comments = _state.value.comments.map { if (it.id == updated.id) updated else it }
        )
    }
}