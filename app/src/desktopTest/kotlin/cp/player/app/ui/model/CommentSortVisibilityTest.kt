package cp.player.app.ui.model

import cp.player.core.music.Comment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommentSortVisibilityTest {
    private val alwaysVisibleSorts = listOf(CommentSort.HOT, CommentSort.LATEST)
    private val allSorts = listOf(CommentSort.RECOMMEND, CommentSort.HOT, CommentSort.LATEST)

    private fun state() = CommentUiState(id = "song-1", type = "song")

    private fun comment(id: Long = 1L) = Comment(
        id = id,
        content = "评论 $id",
        user = "用户",
        avatar = "",
        time = "",
        likedCount = 0,
        liked = false,
    )

    @Test
    fun `recommend is hidden while the initial request is loading`() {
        val loading = state().copy(loading = true)

        assertEquals(alwaysVisibleSorts, loading.visibleSortTypes)
    }

    @Test
    fun `recommend is hidden when its successful result is empty`() {
        val empty = state().withSortResults(CommentSort.RECOMMEND, emptyList())

        assertFalse(empty.recommendHasResults)
        assertEquals(alwaysVisibleSorts, empty.visibleSortTypes)
    }

    @Test
    fun `recommend is hidden after an initial request failure`() {
        val failed = state().copy(loading = false, error = "加载失败")

        assertEquals(alwaysVisibleSorts, failed.visibleSortTypes)
    }

    @Test
    fun `recommend is shown after a non-empty recommendation result`() {
        val loaded = state().withSortResults(CommentSort.RECOMMEND, listOf(comment()))

        assertTrue(loaded.recommendHasResults)
        assertEquals(allSorts, loaded.visibleSortTypes)
    }

    @Test
    fun `switching sorts preserves recommendation availability and an empty refresh hides it`() {
        val recommendationLoaded = state()
            .withSortResults(CommentSort.RECOMMEND, listOf(comment()))
            .copy(comments = listOf(comment()))

        val hot = recommendationLoaded.forSortSelection(CommentSort.HOT)
        assertEquals(CommentSort.HOT, hot.sortType)
        assertTrue(hot.comments.isEmpty(), "切换排序时应清除旧列表")
        assertEquals(allSorts, hot.visibleSortTypes)

        val latest = hot
            .withSortResults(CommentSort.LATEST, listOf(comment(2L)))
            .forSortSelection(CommentSort.LATEST)
        assertEquals(CommentSort.LATEST, latest.sortType)
        assertEquals(allSorts, latest.visibleSortTypes, "非推荐结果不应改变推荐项可用性")

        val refreshedEmpty = latest
            .forSortSelection(CommentSort.RECOMMEND)
            .withSortResults(CommentSort.RECOMMEND, emptyList())
        assertEquals(alwaysVisibleSorts, refreshedEmpty.visibleSortTypes)
    }
}
