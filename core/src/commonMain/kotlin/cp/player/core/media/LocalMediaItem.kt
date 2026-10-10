package cp.player.core.media

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
    val lastModified: Long = 0L,
    /**
     * 音频专属的深度元数据（流派 / 年份 / 轨号 / 码率 / 采样率 / 位深 / 编码 / 内嵌封面标记）。
     *
     * 视频条目与未解析成功的音频条目为 null。字段全带默认值，
     * 保证旧版 `index.json`（无此键）反序列化不崩。
     */
    val metadata: LocalTrackMetadata? = null,
) {
    /** 音质短标签（如 `FLAC 24/96`、`320k MP3`）；无元数据时为 null。 */
    val qualityLabel: String? get() = metadata?.qualityLabel

    /** 是否为无损（位深已知即视为无损，有损格式没有位深概念）。 */
    val isLossless: Boolean get() = metadata?.bitDepth != null
}
