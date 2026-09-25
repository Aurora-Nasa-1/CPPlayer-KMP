package cp.player.kmp.model

import cp.player.kmp.media.MediaType
import kotlinx.serialization.Serializable

/**
 * 下载任务状态。
 */
enum class DownloadStatus {
    /** 等待下载 */
    PENDING,

    /** 下载中 */
    DOWNLOADING,

    /** 已暂停（可恢复） */
    PAUSED,

    /** 已完成 */
    COMPLETED,

    /** 下载失败 */
    FAILED,

    /** 已取消 */
    CANCELLED
}

/**
 * 通用媒体下载任务。
 *
 * 不再绑定「歌曲」概念，可同时承载音频 / 视频等任意媒体类型，
 * 由 [mediaType] 区分。下载完成后 [localPath] 指向本地文件，
 * 对应本地条目的 mediaId 规则为 `local://{audio|video}/{path}`。
 */
@Serializable
data class DownloadTask(
    /** 任务唯一 ID */
    val id: String,
    /** 媒体 ID（CPMediaId 字符串形式，如 netease://song/12345） */
    val mediaId: String,
    /** 媒体来源 Provider ID（如 netease / local） */
    val providerId: String,
    /** 媒体标题 */
    val title: String,
    /** 艺术家（可空） */
    val artist: String? = null,
    /** 封面 URL（可空） */
    val coverUrl: String? = null,
    /** 媒体类型，默认音频 */
    val mediaType: MediaType = MediaType.AUDIO,
    /** 音质 / 质量档位（如 exhigh / hq / sq），默认 exhigh */
    val qualityLevel: String = "exhigh",
    /** 当前任务状态 */
    val status: DownloadStatus = DownloadStatus.PENDING,
    /** 下载进度，范围 0f..1f */
    val progress: Float = 0f,
    /** 文件总字节数（未知时为 null） */
    val totalBytes: Long? = null,
    /** 已下载字节数 */
    val downloadedBytes: Long = 0L,
    /** 下载完成后的本地文件路径 */
    val localPath: String? = null,
    /** 失败时的错误信息 */
    val error: String? = null,
    /** 任务创建时间戳（毫秒） */
    val createdAt: Long
)
