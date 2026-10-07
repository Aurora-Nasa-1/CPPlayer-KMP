package cp.player.core.playback

import cp.player.core.model.LyricsInfo
import cp.player.core.util.PlatformSupport

/**
 * 边车歌词（sidecar lyrics）：与音频文件**同名同目录**的歌词文件。
 *
 * 支持的扩展名：`.lrc` / `.ttml` / `.elrc`（大小写都试）。例如：
 * ```
 * Music/
 *   ├── Song.flac
 *   ├── Song.lrc        ← 优先
 *   └── Song.ttml
 * ```
 *
 * ### 为什么它排在所有在线来源之前
 * 用户把歌词文件放在音频旁边，是一个**明确的、本地优先的意图** —— 它比任何在线匹配都更
 * 可信（尤其是冷门曲 / 现场版 / 自制翻译）。因此本地歌曲一旦命中边车歌词就直接采用，
 * 不再去打 AMLL 或音源接口。
 *
 * ### 为什么 content:// 要跳过
 * Android SAF 导入的文件只有 `content://` URI，没有可拼接的目录路径，
 * 无法定位「同目录同名文件」。这类条目仍会走在线歌词来源。
 */
internal object SidecarLyrics {

    private val EXTENSIONS = listOf("lrc", "ttml", "elrc")

    /**
     * 尝试从音频文件同目录加载边车歌词。
     *
     * @param audioPath 音频文件绝对路径（即 `CPMediaId.resourceId`）
     * @return 命中的歌词与来源信息；没有边车文件或解析不出内容时返回 null
     */
    fun load(audioPath: String): Pair<LyricsState, LyricsInfo>? {
        if (audioPath.isBlank()) return null
        // content:// / 其他带 scheme 的 URI：没有可拼接的目录路径。
        if (audioPath.contains("://")) return null

        val separator = maxOf(audioPath.lastIndexOf('/'), audioPath.lastIndexOf('\\'))
        val dot = audioPath.lastIndexOf('.')
        // 没有扩展名，或点在目录名里（`/a.b/Song`）：无法推出基名。
        if (dot <= separator + 1) return null
        val base = audioPath.substring(0, dot)

        for (extension in EXTENSIONS) {
            // 大小写敏感文件系统（Linux）下 `.LRC` 与 `.lrc` 是两个文件，两种都试。
            for (candidate in listOf("$base.$extension", "$base.${extension.uppercase()}")) {
                if (!PlatformSupport.exists(candidate)) continue
                val raw = PlatformSupport.readTextFile(candidate) ?: continue
                // .lrc 也可能是增强 LRC（带 <mm:ss.xx> 词级标签）：parseEnhancedLrc 在
                // 没有词级标签时会退化成行级，与 parseLrc 结果一致，因此统一走它。
                val lines = if (extension == "ttml") TtmlParser.parse(raw) else LyricsParser.parseEnhancedLrc(raw)
                if (lines.isEmpty()) continue
                return LyricsState.Success(lines) to LyricsInfo(
                    source = "Sidecar .$extension",
                    format = extension.uppercase(),
                    hasWordLevel = lines.any { it.words.isNotEmpty() },
                    hasTranslation = lines.any { !it.translation.isNullOrBlank() },
                    hasPhonetic = lines.any { !it.romanization.isNullOrBlank() },
                )
            }
        }
        return null
    }
}
