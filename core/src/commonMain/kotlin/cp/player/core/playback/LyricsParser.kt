package cp.player.core.playback

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import com.mocharealm.accompanist.lyrics.core.parser.EnhancedLrcParser
import com.mocharealm.accompanist.lyrics.core.parser.NeteaseYrcParser
import com.mocharealm.accompanist.lyrics.core.parser.TTMLParser
import cp.player.core.music.TrackLyrics

/**
 * 歌词解析与装配器：
 * 将音源 JSON 负载、多轨流媒体响应及独立文本文件装配解析为统一的 [SyncedLyricLine] 列表。
 *
 * 语法与文本解析全面委托给官方 [com.mocharealm.accompanist.lyrics.core.parser]，
 * 本类负责多轨合并（如网易云 yrc/lrc 与分离的 tlyric/romalrc 缝合）以及业务数据投影。
 */
object LyricsParser {

    private val autoParser = AutoParser()

    /**
     * 直接解析强类型 [TrackLyrics] 数据，避免在 JSON 字符串与对象之间来回序列化。
     */
    fun parseTrackLyrics(lyrics: TrackLyrics, durationMs: Long = 0L): List<SyncedLyricLine> {
        val ttml = lyrics.ttml
        if (!ttml.isNullOrBlank()) {
            val lines = parseTtml(ttml, durationMs)
            if (lines.isNotEmpty()) return finalize(lines)
        }

        val yrc = lyrics.yrc
        val tlyricRaw = lyrics.translation
        val romalrcRaw = lyrics.romanization

        if (!yrc.isNullOrBlank()) {
            val yrcLines = parseYrc(yrc)
            if (yrcLines.isNotEmpty()) {
                val merged = mergeTranslation(yrcLines, tlyricRaw, romalrcRaw)
                return finalize(merged)
            }
        }

        val lyricRaw = lyrics.lrc
        if (lyricRaw.isNullOrBlank()) return emptyList()

        val lyricText = LyricsTimeRepair.stripBom(lyricRaw)

        if (lyricText.trimStart().startsWith("<tt") || lyricText.trimStart().startsWith("<?xml")) {
            val lines = parseTtml(lyricText, durationMs)
            if (lines.isNotEmpty()) return finalize(lines)
        }

        val mainRaw = LyricsTimeRepair.repairColonDialect(
            lyricText,
            durationMs,
            ::parseEnhancedLrc,
        )
        val mainLines = parseEnhancedLrc(mainRaw)
        if (mainLines.isEmpty()) return emptyList()

        val merged = mergeTranslation(
            mainLines,
            tlyricRaw?.let { LyricsTimeRepair.repairColonDialect(LyricsTimeRepair.stripBom(it), durationMs, ::parseEnhancedLrc) },
            romalrcRaw?.let { LyricsTimeRepair.repairColonDialect(LyricsTimeRepair.stripBom(it), durationMs, ::parseEnhancedLrc) },
        )
        return finalize(merged)
    }

    /**
     * 智能识别单文本歌词格式并解析（支持 TTML、YRC、KRC、Enhanced LRC、LRC、Lyricify）。
     *
     * ⚠️ 这条路的 `AutoParser` **只认标准方言**，实测两种常见写法会直接返回 0 行
     * （边车 `.lrc` 于是静默落空、转去在线来源，用户看到的是「本地歌词文件没生效」）：
     * - `[mm:ss]`（**没有百分秒**）—— `AutoParser.canParse` 要求 `\d{2}\.\d{2,3}`；
     * - `[mm:ss:cc]`（百分秒用冒号）—— 同上。
     *
     * 所以这里按代价从低到高兜两刀：
     * 1. 冒号方言且有**硬证据**（第三段 ≥ 60）⇒ 改写成 `mm:ss.cc` 再走 `AutoParser`；
     * 2. 否则退回行级解析器 [parseEnhancedLrc]（它认无百分秒的写法）。
     *    ⚠️ 必须排在 ① 之后：冒号方言它也能解析，但会按 `h:mm:ss` 读成错误的 60 倍时间。
     */
    fun parseText(raw: String): List<SyncedLyricLine> {
        val text = LyricsTimeRepair.stripBom(raw)
        if (text.isBlank()) return emptyList()
        // TTML 先走兼容层：AutoParser 只在正文含 `http://www.w3.org/ns/ttml` 时才选它，
        // 而边车 / 插件导出的 TTML 经常省略命名空间，还会省掉 `p` 的 `end`。
        if (looksLikeTtml(text)) {
            val ttml = parseTtml(text)
            if (ttml.isNotEmpty()) return ttml
        }
        val parsed = finalize(autoParser.parse(text).toSyncedLyricLines())
        if (parsed.isNotEmpty()) return parsed
        if (LyricsTimeRepair.hasImpossibleSeconds(text)) {
            val repaired = finalize(
                autoParser.parse(LyricsTimeRepair.rewriteColonAsCentiseconds(text)).toSyncedLyricLines(),
            )
            if (repaired.isNotEmpty()) return repaired
        }
        return finalize(parseEnhancedLrc(text))
    }

    private fun looksLikeTtml(text: String): Boolean {
        val head = text.trimStart().take(600)
        return head.startsWith("<?xml") && head.contains("<tt") ||
            head.startsWith("<tt") || head.startsWith("<tt:") ||
            head.contains("<tt ") || head.contains("<tt:tt")
    }

    // ============ Core 委托解析 ============

    /**
     * 解析 TTML。
     *
     * ### 为什么先过 [TtmlCompat]
     * 歌词库的 `TTMLParser` 只认 Apple Music / AMLL 的标准产出，实测这些**语法合法**的写法
     * 会整份返回 0 行（播放页于是显示「暂无歌词」）：
     * - `<p begin="1.0">`（省略 `end`，靠下一行推导）；
     * - `<p>` 里只有裸文本，没有 `<span>`；
     * - 时间写成 `1500ms`；
     * - 数字字符引用 `&#39;`（会原样显示）。
     *
     * [TtmlCompat.normalize] 只补结构、不改语义；归一化失败时回退原始文本，
     * 保证原本能解析的文件行为不变。
     */
    internal fun parseTtml(raw: String, durationMs: Long = 0L): List<SyncedLyricLine> {
        val text = LyricsTimeRepair.stripBom(raw)
        if (text.isBlank()) return emptyList()

        // ⚠️ 归一化必须排在「直接解析」之前：库能把 `1500ms` 解析成 0ms 并返回非空结果，
        // 若先信直接解析，兼容层永远不会被执行。归一化失败（不是 TTML / 结构损坏）时才回退原文。
        val normalized = TtmlCompat.normalize(text, durationMs)
        if (normalized != null) {
            val normalizedLines = runCatching {
                TTMLParser().parse(normalized).toSyncedLyricLines()
            }.getOrDefault(emptyList())
            if (normalizedLines.isNotEmpty()) {
                return TtmlCompat.decodeNumericEntities(normalizedLines)
            }
        }

        // 兼容层改坏了某些标准文件的边缘情况时，仍保留原行为；数字实体由后处理补解。
        val direct = runCatching { TTMLParser().parse(text).toSyncedLyricLines() }.getOrDefault(emptyList())
        return TtmlCompat.decodeNumericEntities(direct)
    }

    internal fun parseYrc(raw: String): List<SyncedLyricLine> {
        return runCatching {
            NeteaseYrcParser.parse(raw).toSyncedLyricLines()
        }.getOrDefault(emptyList())
    }

    internal fun parseEnhancedLrc(raw: String): List<SyncedLyricLine> {
        val text = LyricsTimeRepair.stripBom(raw)
        val parsed = runCatching { EnhancedLrcParser.parse(text).toSyncedLyricLines() }
            .getOrDefault(emptyList())
        if (parsed.isNotEmpty()) return parsed
        // 兜底：库遇到**逆序**时间戳会抛异常（"Failed requirement."），而外层 runCatching
        // 会把整份歌词吞成空列表 —— 上层于是以为「没有歌词」，转去打别的来源。
        // 按行首时间戳重排一次通常就能救回来；已经有序时它原样返回，不做第二次解析。
        val sorted = LyricsTimeRepair.sortLinesByLeadingTimestamp(text)
        if (sorted == text) return emptyList()
        return runCatching { EnhancedLrcParser.parse(sorted).toSyncedLyricLines() }
            .getOrDefault(emptyList())
    }

    internal fun parseLrc(raw: String): List<SyncedLyricLine> {
        return parseEnhancedLrc(raw)
    }

    // ============ 多轨合并与辅助 ============

    /** 按时间戳把翻译/罗马音合并到主歌词（时间近似相等即合并）。 */
    private fun mergeTranslation(
        main: List<SyncedLyricLine>,
        tlyricRaw: String?,
        romalrcRaw: String?,
    ): List<SyncedLyricLine> {
        if (tlyricRaw.isNullOrBlank() && romalrcRaw.isNullOrBlank()) return main
        val trans = if (!tlyricRaw.isNullOrBlank()) parseEnhancedLrc(tlyricRaw) else emptyList()
        val roma = if (!romalrcRaw.isNullOrBlank()) parseEnhancedLrc(romalrcRaw) else emptyList()
        return main.map { line ->
            line.copy(
                translation = line.translation ?: trans.firstOrNull { kotlin.math.abs(it.time - line.time) <= 500 }?.text,
                romanization = line.romanization ?: roma.firstOrNull { kotlin.math.abs(it.time - line.time) <= 500 }?.text,
            )
        }
    }

    // ============ 后处理 ============

    private fun finalize(lines: List<SyncedLyricLine>): List<SyncedLyricLine> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.time }
        // 补齐 endTime
        return sorted.mapIndexed { i, line ->
            if (line.endTime != null) line
            else {
                val next = sorted.getOrNull(i + 1)?.time ?: (line.time + 4_000L)
                line.copy(endTime = next)
            }
        }
    }
}

/** 将 Core 的 [SyncedLyrics] 映射为播放器统一的 [SyncedLyricLine] 列表。 */
fun SyncedLyrics.toSyncedLyricLines(): List<SyncedLyricLine> {
    return lines.mapNotNull { it.toSyncedLyricLine() }
}

/** 将 Core 的单行 [ISyncedLine] 转换为 [SyncedLyricLine]。 */
fun ISyncedLine.toSyncedLyricLine(): SyncedLyricLine? {
    return when (this) {
        is KaraokeLine.MainKaraokeLine -> {
            val isSingleCoveringWholeLine = syllables.size == 1 &&
                syllables.first().start == start &&
                syllables.first().end == end
            val words = if (isSingleCoveringWholeLine) {
                emptyList()
            } else {
                syllables.map {
                    SyncedLyricLine.SyncedWord(
                        text = it.content,
                        beginTime = it.start.toLong(),
                        endTime = it.end.toLong(),
                    )
                }
            }
            val lineText = syllables.joinToString("") { it.content }
            SyncedLyricLine(
                time = start.toLong(),
                endTime = end.toLong(),
                text = lineText,
                words = words,
                translation = translation?.takeIf { it.isNotBlank() },
                romanization = phonetic?.takeIf { it.isNotBlank() },
            )
        }
        is SyncedLine -> {
            SyncedLyricLine(
                time = start.toLong(),
                endTime = end.toLong(),
                text = content,
                words = emptyList(),
                translation = translation?.takeIf { it.isNotBlank() },
                romanization = phonetic?.takeIf { it.isNotBlank() },
            )
        }
        is KaraokeLine.AccompanimentKaraokeLine -> {
            val words = syllables.map {
                SyncedLyricLine.SyncedWord(
                    text = it.content,
                    beginTime = it.start.toLong(),
                    endTime = it.end.toLong(),
                )
            }
            SyncedLyricLine(
                time = start.toLong(),
                endTime = end.toLong(),
                text = syllables.joinToString("") { it.content },
                words = words,
                translation = null,
                romanization = null,
            )
        }
        else -> null
    }
}
