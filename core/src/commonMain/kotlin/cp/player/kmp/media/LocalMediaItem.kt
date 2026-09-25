package cp.player.kmp.media

import kotlinx.serialization.Serializable

/**
 * 本地媒体条目的来源。
 */
enum class LocalMediaOrigin {
    /** 由应用内下载产生 */
    DOWNLOADED,

    /** 用户导入 / 扫描发现的外部文件 */
    IMPORTED
}

/**
 * 通用本地媒体条目。
 *
 * 不再绑定「歌曲」概念，可同时表示本地音频 / 视频文件，
 * 由 [mediaType] 区分。对应 mediaId 规则为 `local://{audio|video}/{path}`。
 */
@Serializable
data class LocalMediaItem(
    /** 文件绝对路径，或 Android SAF 的 content:// uri */
    val path: String,
    /** 媒体标题 */
    val title: String,
    /** 艺术家（可空） */
    val artist: String? = null,
    /** 专辑（可空） */
    val album: String? = null,
    /** 时长（毫秒） */
    val durationMs: Long = 0L,
    /** 文件大小（字节） */
    val sizeBytes: Long = 0L,
    /** 媒体类型，默认音频 */
    val mediaType: MediaType = MediaType.AUDIO,
    /** 封面 URI（可空） */
    val coverUri: String? = null,
    /** 条目来源（下载 / 导入） */
    val source: LocalMediaOrigin = LocalMediaOrigin.IMPORTED,
    /** 文件最后修改时间戳（毫秒） */
    val lastModified: Long = 0L
)
