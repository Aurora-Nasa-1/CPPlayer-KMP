package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.model.DynamicPost
import cp.player.app.presentation.dynamic.DynamicContract
import cp.player.app.presentation.dynamic.DynamicFilter
import cp.player.app.presentation.dynamic.DynamicPresenter
import cp.player.app.ui.util.UiEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// 兼容现有 UI 引用的类型别名
typealias DynamicUiState = cp.player.app.presentation.dynamic.DynamicUiState

/**
 * 动态页的 Voyager 适配层（Thin Adapter）。
 *
 * 全部状态机与业务逻辑位于框架中立的 [DynamicPresenter]。
 */
class DynamicScreenModel : ScreenModel, DynamicContract {

    private val presenter = DynamicPresenter(
        scope = screenModelScope,
        notify = UiEvents::notify,
    )

    override val state: StateFlow<DynamicUiState> get() = presenter.state
    override val scope: CoroutineScope get() = presenter.scope

    override fun setFilter(filter: DynamicFilter) = presenter.setFilter(filter)

    override fun ensureLoaded() = presenter.ensureLoaded()

    override fun refresh() = presenter.refresh()

    override fun loadMore() = presenter.loadMore()

    override fun showOwnPosts() = presenter.showOwnPosts()

    override fun toggleLike(postId: String) = presenter.toggleLike(postId)

    override fun forward(postId: String, text: String) = presenter.forward(postId, text)

    override fun delete(postId: String) = presenter.delete(postId)

    override fun openComments(post: DynamicPost) = presenter.openComments(post)

    override fun closeComments() = presenter.closeComments()

    override fun setCommentDraft(text: String) = presenter.setCommentDraft(text)

    override fun loadComments(reset: Boolean) = presenter.loadComments(reset)

    override fun postComment() = presenter.postComment()
}
