package cp.player.core.playback

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import com.mocharealm.accompanist.lyrics.core.parser.EnhancedLrcParser
import com.mocharealm.accompanist.lyrics.core.parser.KugouKrcParser
import com.mocharealm.accompanist.lyrics.core.parser.NeteaseYrcParser
import com.mocharealm.accompanist.lyrics.core.parser.TTMLParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
     * @param durationMs 曲目时长（毫秒），用于判定时间戳方言是否与时长相容。0 = 未知。
     *   只有 [LyricsTimeRepair] 用它 —— 见那里的说明（`[mm:ss:cc]` 被读成 `h:mm:ss`
     *   会让整首歌的歌词都被推到约 60 倍之后，UI 因此整首停在「前奏」）。
     * @return 已按时间升序、去重的歌词行；无可用歌词返回空列表。
     */
    fun parse(json: JsonElement, durationMs: Long = 0L): List<SyncedLyricLine> {
        val obj = json as? JsonObject ?: return emptyList()
        val data = obj["data"] as? JsonObject

        // 0. TTML 格式（支持 data.ttml 或根层 ttml）
        val ttml = ((obj["ttml"] ?: data?.get("ttml")) as? JsonPrimitive)?.contentOrNull
        if (!ttml.isNullOrBlank()) {
            val lines = parseTtml(ttml)
            if (lines.isNotEmpty()) return finalize(lines)
        }

        // 1. 酷狗逐字 KRC（支持明文）
        val krc = ((obj["krc"] as? JsonObject) ?: (data?.get("krc") as? JsonObject))
            ?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }
            ?: (obj["krc"] as? JsonPrimitive)?.contentOrNull
            ?: (data?.get("krc") as? JsonPrimitive)?.contentOrNull
        if (!krc.isNullOrBlank()) {
            val lines = parseKrc(krc)
            if (lines.isNotEmpty()) return finalize(lines)
        }

        // 2. 优先逐字 YRC
        val yrc = ((obj["yrc"] as? JsonObject) ?: (data?.get("yrc") as? JsonObject))
            ?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }
            ?: (obj["yrc"] as? JsonPrimitive)?.contentOrNull
            ?: (data?.get("yrc") as? JsonPrimitive)?.contentOrNull

        val tlyricRaw = ((obj["tlyric"] as? JsonObject) ?: (data?.get("tlyric") as? JsonObject))
            ?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }
            ?: (obj["tlyric"] as? JsonPrimitive)?.contentOrNull
            ?: (data?.get("tlyric") as? JsonPrimitive)?.contentOrNull

        val romalrcRaw = ((obj["romalrc"] as? JsonObject) ?: (data?.get("romalrc") as? JsonObject))
            ?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }
            ?: (obj["romalrc"] as? JsonPrimitive)?.contentOrNull
            ?: (data?.get("romalrc") as? JsonPrimitive)?.contentOrNull

        if (!yrc.isNullOrBlank()) {
            val yrcLines = parseYrc(yrc)
            if (yrcLines.isNotEmpty()) {
                val merged = mergeTranslation(yrcLines, tlyricRaw, romalrcRaw)
                return finalize(merged)
            }
        }

        // 3. 行级 / 增强型 LRC + 翻译
        val lyricRaw = ((obj["lrc"] as? JsonObject) ?: (data?.get("lrc") as? JsonObject))
            ?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }
            ?: (obj["lyric"] as? JsonPrimitive)?.contentOrNull
            ?: (data?.get("lyric") as? JsonPrimitive)?.contentOrNull
            ?: ((obj["klyric"] as? JsonObject) ?: (data?.get("klyric") as? JsonObject))
                ?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }

        if (lyricRaw.isNullOrBlank()) return emptyList()

        // BOM 统一在这里剥掉：带 BOM 时行首时间戳（以及下面的 TTML 前缀判定）都对不上，
        // 症状是**第一行歌词静默丢失**。
        val lyricText = LyricsTimeRepair.stripBom(lyricRaw)

        if (lyricText.trimStart().startsWith("<tt") || lyricText.trimStart().startsWith("<?xml")) {
            val lines = parseTtml(lyricText)
            if (lines.isNotEmpty()) return finalize(lines)
        }

        // 时间戳方言修复：`[mm:ss:cc]` 必须按百分秒读，否则整份歌词被推到约 60 倍之后
        // （UI 表现为「整首都停在前奏」）。翻译 / 罗马音同一份文件、同一种方言，一起修。
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

    // ============ Core 委托解析 ============

    internal fun parseTtml(raw: String): List<SyncedLyricLine> {
        return runCatching {
            TTMLParser().parse(raw).toSyncedLyricLines()
        }.getOrDefault(emptyList())
    }

    internal fun parseYrc(raw: String): List<SyncedLyricLine> {
        return runCatching {
            NeteaseYrcParser.parse(raw).toSyncedLyricLines()
        }.getOrDefault(emptyList())
    }

    internal fun parseKrc(raw: String): List<SyncedLyricLine> {
        return runCatching {
            KugouKrcParser.parse(raw).toSyncedLyricLines()
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
