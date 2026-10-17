package cp.player.core.util

/**
 * 音乐检索关键词与元数据清洗器。
 *
 * 用于解决本地/下载歌曲在向 AMLL、音源 API 或第三方插件检索歌词/封面时，
 * 因标题含有文件名序号、音频格式标记（[FLAC], 320k）、版本修饰词（Live, Remastered）
 * 而导致线上精确或模糊检索 0 命中的问题。
 */
object MusicSearchSanitizer {

    private val EXTENSION_REGEX = Regex("""\.(mp3|flac|m4a|wav|ogg|opus|aac|ape|wma|alac|dff|dsf)$""", RegexOption.IGNORE_CASE)
    private val TRACK_NUMBER_PREFIX_REGEX = Regex("""^(\d{1,3}[\s._\-]+)+""")
    private val BRACKET_TAGS_REGEX = Regex(
        """[\[\(【（](?:flac|hi-?res|hq|sq|lossless|无损|320k(?:bps)?|128k(?:bps)?|192k(?:bps)?|24bit|16bit|master|remaster(?:ed)?|live|现场|伴奏|instrumental|inst|cover|翻唱)(?:[^\]\)】）]*)[\]\)】）]""",
        RegexOption.IGNORE_CASE
    )
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    data class SanitizedInfo(
        val title: String,
        val artist: String,
        val cleanKeyword: String,
    )

    fun cleanTitle(rawTitle: String): String = sanitize(rawTitle).title

    fun cleanArtist(rawArtist: String): String = sanitize("", rawArtist).artist

    /**
     * 清洗歌曲名与歌手名。
     *
     * @param rawTitle 原始歌曲名（可能为文件名）
     * @param rawArtist 原始歌手名（可能为 null 或 "Unknown"）
     */
    fun sanitize(rawTitle: String, rawArtist: String? = null): SanitizedInfo {
        var t = rawTitle.trim()
        var a = rawArtist?.trim().orEmpty()

        if (isUnknownArtist(a)) {
            a = ""
        }

        // 1. 去除文件扩展名
        t = EXTENSION_REGEX.replace(t, "")

        // 2. 去除开头的音轨序号，如 "01. ", "02 - "
        t = TRACK_NUMBER_PREFIX_REGEX.replace(t, "")

        // 3. 若 artist 为空，且标题中包含 " - "，尝试拆分 "歌手 - 歌名"
        if (a.isBlank() && t.contains(" - ")) {
            val parts = t.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                a = parts[0].trim()
                t = parts[1].trim()
            }
        }

        // 4. 去除各种方括号/圆括号内的音频质量和格式标记
        val cleanedTitle = BRACKET_TAGS_REGEX.replace(t, "").trim()
        if (cleanedTitle.isNotBlank()) {
            t = cleanedTitle
        }

        // 5. 净化歌手字段：若有多个歌手，提取主要歌手
        if (a.isNotBlank()) {
            val splitArtist = a.split(Regex("""[/、&,]|\sfeat\.?\s""", RegexOption.IGNORE_CASE))
                .firstOrNull { it.isNotBlank() }
                ?.trim()
            if (!splitArtist.isNullOrBlank()) {
                a = splitArtist
            }
        }

        // 6. 整理空白字符
        t = MULTI_SPACE_REGEX.replace(t, " ").trim()
        a = MULTI_SPACE_REGEX.replace(a, " ").trim()

        val keyword = listOf(t, a).filter { it.isNotBlank() }.joinToString(" ")
        return SanitizedInfo(
            title = t.ifBlank { rawTitle.trim() },
            artist = a,
            cleanKeyword = keyword.ifBlank { rawTitle.trim() }
        )
    }

    private fun isUnknownArtist(artist: String): Boolean {
        val trimmed = artist.trim().lowercase()
        return trimmed.isEmpty() ||
            trimmed == "<unknown>" ||
            trimmed == "unknown" ||
            trimmed == "unknown artist" ||
            trimmed == "未知" ||
            trimmed == "未知歌手" ||
            trimmed == "群星" ||
            trimmed == "various artists"
    }
}
