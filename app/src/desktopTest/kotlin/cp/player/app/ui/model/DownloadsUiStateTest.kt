package cp.player.app.ui.model

import cp.player.core.media.MediaType
import cp.player.core.model.DownloadStatus
import cp.player.core.model.DownloadTask
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsUiStateTest {

    private fun createTask(
        id: String,
        status: DownloadStatus,
        createdAt: Long,
        title: String = id,
    ) = DownloadTask(
        id = id,
        mediaId = "cp_api://song/$id",
        providerId = "netease",
        title = title,
        mediaType = MediaType.AUDIO,
        status = status,
        createdAt = createdAt,
    )

    @Test
    fun activeTasks_areSortedByDownloadOrderFromTopToBottom() {
        // Enqueued at different times and in different statuses
        val task1 = createTask("task1", DownloadStatus.PENDING, createdAt = 100L)
        val task2 = createTask("task2", DownloadStatus.DOWNLOADING, createdAt = 200L)
        val task3 = createTask("task3", DownloadStatus.PENDING, createdAt = 300L)
        val task4 = createTask("task4", DownloadStatus.PAUSED, createdAt = 50L)
        val task5 = createTask("task5", DownloadStatus.FAILED, createdAt = 10L)
        val completed = createTask("completed", DownloadStatus.COMPLETED, createdAt = 20L)

        val state = DownloadsUiState(
            tasks = listOf(task1, task2, task3, task4, task5, completed)
        )

        // Expected order:
        // 1. DOWNLOADING (task2)
        // 2. PENDING (task1, createdAt=100) -> (task3, createdAt=300)
        // 3. PAUSED (task4, createdAt=50)
        // 4. FAILED (task5, createdAt=10)
        // completed is excluded from activeTasks
        val activeIds = state.activeTasks.map { it.id }
        assertEquals(listOf("task2", "task1", "task3", "task4", "task5"), activeIds)
    }

    @Test
    fun completedTasks_areSortedByCreatedAtAscending_downloadOrder() {
        val completedOldest = createTask("c1", DownloadStatus.COMPLETED, createdAt = 100L)
        val completedMiddle = createTask("c2", DownloadStatus.COMPLETED, createdAt = 200L)
        val completedNewest = createTask("c3", DownloadStatus.COMPLETED, createdAt = 300L)
        val active = createTask("active", DownloadStatus.DOWNLOADING, createdAt = 150L)

        // Shuffled input
        val state = DownloadsUiState(
            tasks = listOf(completedNewest, active, completedOldest, completedMiddle)
        )

        val completedIds = state.completedTasks.map { it.id }
        // Download order from top to bottom (earliest downloaded first)
        assertEquals(listOf("c1", "c2", "c3"), completedIds)
    }
}
