package cp.player.core.playback

import cp.player.core.model.LyricsInfo
import cp.player.core.util.PlatformSupport

/**
 * 内嵌歌词加载器：从音频文件自身的元数据（ID3v2 USLT/SYLT, Vorbis LYRICS, MP4 ©lyr）读取歌词。
 */
internal object EmbeddedLyricLoader {

    fun load(audioPath: String): Pair<List<SyncedLyricLine>, LyricsInfo>? {
        if (audioPath.isBlank() || audioPath.contains("://")) return null
        val raw = PlatformSupport.readEmbeddedLyrics(audioPath) ?: return null
        return parseLyricsText(raw)
    }

    /**
     * 解析歌词文本（支持带时间戳的 LRC / 增强 LRC，或无时间戳的静态文本）。
     */
    fun parseLyricsText(raw: String): Pair<List<SyncedLyricLine>, LyricsInfo>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // 0. 内嵌 TTML（AMLL / Apple Music 的逐字歌词常整份塞进 USLT 或 ©lyr）。
        //    必须先于 LRC 分支判断：TTML 正文里也可能出现看起来像时间戳的文本。
        if (isTtml(trimmed)) {
            val ttmlLines = LyricsParser.parseTtml(trimmed)
            if (ttmlLines.isNotEmpty()) {
                return ttmlLines to LyricsInfo(
                    source = "Embedded lyrics",
                    format = "TTML",
                    hasWordLevel = ttmlLines.any { it.words.isNotEmpty() },
                    hasTranslation = ttmlLines.any { !it.translation.isNullOrBlank() },
                    hasPhonetic = ttmlLines.any { !it.romanization.isNullOrBlank() },
                )
            }
        }

        // 1. 若内嵌文本包含 LRC 时间戳，使用 parseEnhancedLrc 提取逐行/逐字歌词
        val synced = LyricsParser.parseEnhancedLrc(trimmed)
        if (synced.isNotEmpty()) {
            return synced to LyricsInfo(
                source = "Embedded lyrics",
                format = "LRC",
                hasWordLevel = synced.any { it.words.isNotEmpty() },
                hasTranslation = synced.any { !it.translation.isNullOrBlank() },
                hasPhonetic = synced.any { !it.romanization.isNullOrBlank() },
            )
        }

        // 2. 若内嵌歌词为未同步纯文本（如普通 USLT），切分为静态行展示
        val textLines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> SyncedLyricLine(time = 0L, text = line) }
            .toList()

        if (textLines.isEmpty()) return null

        return textLines to LyricsInfo(
            source = "Embedded lyrics",
            format = "TXT",
            hasWordLevel = false,
            hasTranslation = false,
            hasPhonetic = false,
        )
    }

    private fun isTtml(text: String): Boolean {
        val head = text.take(512)
        return head.contains("<tt ") || head.contains("<tt>") ||
            head.contains(":tt ") || head.contains(":tt>")
    }
}
