package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.presentation.comment.CommentContract
import cp.player.app.presentation.comment.CommentPresenter
import cp.player.core.music.Comment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// 兼容现有 UI 引用的类型别名
typealias CommentSort = cp.player.app.presentation.comment.CommentSort
typealias CommentFloorUi = cp.player.app.presentation.comment.CommentFloorUi
typealias CommentUiState = cp.player.app.presentation.comment.CommentUiState

/**
 * 评论区的 Voyager 适配层（Thin Adapter）。
 *
 * 全部状态机与业务逻辑位于框架中立的
 * [cp.player.app.presentation.comment.CommentPresenter]。
 */
class CommentScreenModel(
    id: String,
    type: String,
) : ScreenModel, CommentContract {

    private val presenter = CommentPresenter(
        id = id,
        type = type,
        scope = screenModelScope,
    )

    override val state: StateFlow<CommentUiState> get() = presenter.state
    override val scope: CoroutineScope get() = presenter.scope

    override fun loadComments() = presenter.loadComments()

    override fun setSort(sortType: Int) = presenter.setSort(sortType)

    override fun loadMore() = presenter.loadMore()

    override fun toggleFloor(comment: Comment) = presenter.toggleFloor(comment)

    override fun loadMoreFloor(comment: Comment) = presenter.loadMoreFloor(comment)

    override fun startReply(comment: Comment) = presenter.startReply(comment)

    override fun cancelReply() = presenter.cancelReply()

    override fun updateDraft(text: String) = presenter.updateDraft(text)

    override fun clipDraft() = presenter.clipDraft()

    override fun send() = presenter.send()

    override fun toggleLike(comment: Comment) = presenter.toggleLike(comment)
}
