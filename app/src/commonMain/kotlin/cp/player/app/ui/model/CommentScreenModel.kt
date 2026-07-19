package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class Comment(
    val id: Long,
    val content: String,
    val user: String,
    val avatar: String,
    val time: String,
    val likedCount: Int,
    val liked: Boolean
)

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

    init {
        loadComments()
    }

    fun loadComments() {
        screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val json = AppModel.api.getComments(id, type)
                val commentList = mutableListOf<Comment>()
                
                // 简单解析，实际需根据后端结构
                val commentsArray = json.jsonObject["data"]?.jsonObject?.get("comments")?.jsonArray
                    ?: json.jsonObject["comments"]?.jsonArray
                    
                commentsArray?.forEach {
                    val obj = it.jsonObject
                    commentList.add(Comment(
                        id = obj["commentId"]?.jsonPrimitive?.long ?: 0L,
                        content = obj["content"]?.jsonPrimitive?.content ?: "",
                        user = obj["user"]?.jsonObject?.get("nickname")?.jsonPrimitive?.content ?: "Unknown",
                        avatar = obj["user"]?.jsonObject?.get("avatarUrl")?.jsonPrimitive?.content ?: "",
                        time = obj["timeStr"]?.jsonPrimitive?.content ?: "",
                        likedCount = obj["likedCount"]?.jsonPrimitive?.int ?: 0,
                        liked = obj["liked"]?.jsonPrimitive?.boolean ?: false
                    ))
                }
                _state.value = _state.value.copy(comments = commentList, loading = false)
            }.onFailure {
                _state.value = _state.value.copy(error = it.message, loading = false)
            }
        }
    }
}
