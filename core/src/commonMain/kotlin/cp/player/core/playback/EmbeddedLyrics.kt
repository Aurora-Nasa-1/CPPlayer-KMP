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
}
