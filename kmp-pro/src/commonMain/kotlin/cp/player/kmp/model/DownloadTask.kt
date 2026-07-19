package cp.player.kmp.model

import kotlinx.serialization.Serializable

enum class DownloadStatus {
    PENDING, DOWNLOADING, COMPLETED, FAILED
}

@Serializable
data class DownloadTask(
    val song: Song,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Float = 0f,
    val downloadId: Long = -1,
    val localUri: String? = null
)