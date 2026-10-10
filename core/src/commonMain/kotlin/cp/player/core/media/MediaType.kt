package cp.player.core.media

/**
 * 媒体类型枚举。
 *
 * 用于在下载、本地扫描等通用媒体场景中区分音频 / 视频 / 其他类型，
 * 替代原先仅面向「歌曲」的模型假设。
 */
enum class MediaType {
    /** 音频文件（mp3 / flac / m4a / ogg / wav / aac 等） */
    AUDIO,

    /** 视频文件（mp4 / mkv / mov / webm / avi 等） */
    VIDEO,

    /** 无法识别的其他类型 */
    OTHER;

    companion object {
        /**
         * 音频类扩展名（小写，不含点号）。
         *
         * 覆盖常见有损 / 无损 / Hi-Res 封装。其中 dsf / dff / dts / wv / tta / ape / mpc
         * 等无损格式虽无元数据解析器支持（标题回退文件名），但**必须能被扫描到**——
         * 本地音乐库漏掉整类 Hi-Res 文件比没有标签严重得多。
         */
        private val AUDIO_EXTENSIONS = setOf(
            // 常见有损
            "mp3", "mp2", "aac", "m4a", "m4b", "m4r", "m4p", "ogg", "oga", "opus", "spx",
            "wma", "asf",
            // 常见无损 / PCM
            "flac", "wav", "wave", "aiff", "aif", "aifc", "afc", "alac",
            // Hi-Res 与小众无损
            "ape", "wv", "tta", "mpc", "shn", "dsf", "dff", "dsdiff", "dts", "dtshd",
            // 容器（可能含音频轨，也可能被 filterVideoFiles 这类规则排除）
            "mp4", "mka",
        )

        /** 视频类扩展名（小写，不含点号） */
        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mov", "webm", "avi", "m4v", "mpg", "mpeg", "flv", "wmv", "ts", "m2ts",
        )

        /**
         * 根据文件名（或路径）的扩展名推断媒体类型。
         *
         * 大小写不敏感：
         * - 命中音频白名单 → [AUDIO]
         * - 命中视频白名单 → [VIDEO]
         * - 其余（含无扩展名）→ [OTHER]
         *
         * 注意 `mp4` 归入 [AUDIO]：本地音乐库里的 mp4 绝大多数是 m4a 的误命名或
         * 带封面的音频，按音频处理更符合用户预期；真正的视频走 `m4v` / `mkv` 等。
         *
         * @param path 文件名或完整路径
         * @return 推断出的 [MediaType]
         */
        fun fromFileName(path: String): MediaType {
            val extension = path.substringAfterLast('.', "").substringAfterLast('/', "")
                .lowercase().trim()
            return when {
                extension in AUDIO_EXTENSIONS -> AUDIO
                extension in VIDEO_EXTENSIONS -> VIDEO
                else -> OTHER
            }
        }
    }
}
