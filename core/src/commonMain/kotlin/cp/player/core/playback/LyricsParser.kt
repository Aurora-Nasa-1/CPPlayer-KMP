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
     * @return 已按时间升序、去重的歌词行；无可用歌词返回空列表。
     */
    fun parse(json: JsonElement): List<SyncedLyricLine> {
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

        if (lyricRaw.trimStart().startsWith("<tt") || lyricRaw.trimStart().startsWith("<?xml")) {
            val lines = parseTtml(lyricRaw)
            if (lines.isNotEmpty()) return finalize(lines)
        }

        val mainLines = parseEnhancedLrc(lyricRaw)
        if (mainLines.isEmpty()) return emptyList()

        val merged = mergeTranslation(mainLines, tlyricRaw, romalrcRaw)
        return finalize(merged)
    }

    /**
     * 智能识别单文本歌词格式并解析（支持 TTML、YRC、KRC、Enhanced LRC、LRC、Lyricify）。
     */
    fun parseText(raw: String): List<SyncedLyricLine> {
        if (raw.isBlank()) return emptyList()
        val parsed = autoParser.parse(raw)
        return finalize(parsed.toSyncedLyricLines())
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
        return runCatching {
            EnhancedLrcParser.parse(raw).toSyncedLyricLines()
        }.getOrDefault(emptyList())
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
