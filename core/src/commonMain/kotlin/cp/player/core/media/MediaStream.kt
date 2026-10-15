package cp.player.core.media

/**
 * 媒体清晰度 / 音质档位选项。
 *
 * 统一适用于：
 * - 视频清晰度：例如 "1080" ("1080P 高清"), "720" ("720P 高清"), "480" ("480P 标清")
 * - 音频音质：例如 "standard", "exhigh", "lossless", "hires"
 */
data class MediaQualityOption(
    val id: String,
    val label: String,
    val resolution: Int = 0,
    val bitrate: Long = 0L,
    val sizeBytes: Long = 0L,
) : cp.player.core.util.JavaSerializable

/**
 * 视频分辨率与宽高比。
 */
data class VideoResolution(
    val width: Int,
    val height: Int,
) : cp.player.core.util.JavaSerializable {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height else 16f / 9f
}

/**
 * 视频画幅缩放与填充模式。
 */
enum class VideoScalingMode {
    /** 适应屏幕（保持原始宽高比，完整显示画面，可能有黑边） */
    FIT,
    /** 裁剪填充（保持原始宽高比，填满整个容器，超出部分被裁剪） */
    CROP,
    /** 拉伸铺满（不保持宽高比，直接填满容器） */
    STRETCH,
}

/**
 * 统一媒体播放流信息。
 *
 * 由统一流解析器（[cp.player.core.media.UnifiedMediaResolver]）产生，
 * 封装了真实播放地址、防盗链请求头、当前档位及可选清晰度列表。
 */
data class MediaStream(
    val mediaId: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val currentQuality: MediaQualityOption? = null,
    val availableQualities: List<MediaQualityOption> = emptyList(),
    val mediaType: MediaType = MediaType.AUDIO,
    val title: String? = null,
    val artist: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
) : cp.player.core.util.JavaSerializable
