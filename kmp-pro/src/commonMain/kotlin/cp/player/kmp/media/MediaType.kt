package cp.player.kmp.media

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
        /** 音频类扩展名（小写，不含点号） */
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "ogg", "wav", "aac")

        /** 视频类扩展名（小写，不含点号） */
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "avi")

        /**
         * 根据文件名（或路径）的扩展名推断媒体类型。
         *
         * 大小写不敏感：
         * - mp3 / flac / m4a / ogg / wav / aac → [AUDIO]
         * - mp4 / mkv / mov / webm / avi → [VIDEO]
         * - 其余（含无扩展名）→ [OTHER]
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
