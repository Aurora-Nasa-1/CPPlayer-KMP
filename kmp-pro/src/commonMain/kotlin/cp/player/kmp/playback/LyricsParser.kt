package cp.player.kmp.playback

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * 歌词解析器：把后端 `lyric/new` 返回的 JSON 转成统一的 [SyncedLyricLine] 列表。
 *
 * 覆盖网易云常见的三种结构：
 * 1. 行级 LRC：`lrc.lyric` 时间标签 `[mm:ss.xxx]` + 文本。
 * 2. 翻译：`tlyric.lyric` 同样的时间标签体系，按时间对齐合并到主歌词。
 * 3. 逐字（YRC / yrc）：`yrc.lyric`，含 `[start,offset]` 词级时间戳。
 *
 * 解析失败或无歌词时返回空列表，由控制器映射为 [LyricsState.NoLyrics]。
 */
object LyricsParser {

    /**
     * @return 已按时间升序、去重的歌词行；无可用歌词返回空列表。
     */
    fun parse(json: JsonElement): List<SyncedLyricLine> {
        val obj = json as? JsonObject ?: return emptyList()

        // 1. 优先逐字 YRC
        val yrc = (obj["yrc"] as? JsonObject)?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }
        if (!yrc.isNullOrBlank()) {
            val lines = parseYrc(yrc)
            if (lines.isNotEmpty()) return finalize(lines)
        }

        // 2. 行级 LRC + 翻译
        val lyricRaw = (obj["lrc"] as? JsonObject)?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }
            ?: (obj["lyric"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["klyric"] as? JsonObject)?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }

        if (lyricRaw.isNullOrBlank()) return emptyList()

        val mainLines = parseLrc(lyricRaw)
        if (mainLines.isEmpty()) return emptyList()

        val tlyricRaw = (obj["tlyric"] as? JsonObject)?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }
        val romalrcRaw = (obj["romalrc"] as? JsonObject)?.let { (it["lyric"] as? JsonPrimitive)?.contentOrNull }

        val merged = mergeTranslation(mainLines, tlyricRaw, romalrcRaw)
        return finalize(merged)
    }

    // ============ LRC 行级 ============

    private val LRC_LINE_TAG = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    internal fun parseLrc(raw: String): List<SyncedLyricLine> {
        val out = mutableListOf<SyncedLyricLine>()
        raw.lineSequence().forEach { line ->
            // 一行可能有多个时间标签（如 [00:01][00:30]同一句）
            val tags = LRC_LINE_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = line.substring(tags.last().range.last + 1).trim()
            tags.forEach { m ->
                val min = m.groupValues[1].toIntOrNull() ?: 0
                val sec = m.groupValues[2].toIntOrNull() ?: 0
                val msPart = m.groupValues[3]
                val ms = when {
                    msPart.isBlank() -> 0
                    msPart.length == 1 -> msPart.toInt() * 100
                    msPart.length == 2 -> msPart.toInt() * 10
                    else -> msPart.take(3).toInt()
                }
                val time = (min * 60_000L) + (sec * 1_000L) + ms
                out.add(SyncedLyricLine(time = time, text = text))
            }
        }
        return out
    }

    /** 按时间戳把翻译/罗马音合并到主歌词（时间近似相等即合并）。 */
    private fun mergeTranslation(
        main: List<SyncedLyricLine>,
        tlyricRaw: String?,
        romalrcRaw: String?,
    ): List<SyncedLyricLine> {
        if (tlyricRaw.isNullOrBlank() && romalrcRaw.isNullOrBlank()) return main
        val trans = if (!tlyricRaw.isNullOrBlank()) parseLrc(tlyricRaw) else emptyList()
        val roma = if (!romalrcRaw.isNullOrBlank()) parseLrc(romalrcRaw) else emptyList()
        return main.map { line ->
            line.copy(
                translation = trans.firstOrNull { kotlin.math.abs(it.time - line.time) <= 500 }?.text,
                romanization = roma.firstOrNull { kotlin.math.abs(it.time - line.time) <= 500 }?.text,
            )
        }
    }

    // ============ YRC 逐字 ============

    // 行头形如 [开始ms,持续ms]  词级 [开始-ms,结束-ms,0]词
    private val YRC_HEADER = Regex("""\[(\d+),(\d+)\]""")
    private val YRC_WORD = Regex("""\((\d+),(\d+),\d+\)([^\(\)\[\]]+)""")

    internal fun parseYrc(raw: String): List<SyncedLyricLine> {
        val out = mutableListOf<SyncedLyricLine>()
        raw.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val header = YRC_HEADER.find(line) ?: return@forEach
            val lineStart = header.groupValues[1].toLong()
            val lineDur = (header.groupValues[2].toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val words = mutableListOf<SyncedLyricLine.SyncedWord>()
            val plainText = StringBuilder()
            var lastEnd = lineStart
            for (m in YRC_WORD.findAll(line)) {
                val ws = m.groupValues[1].toLong()
                val we = m.groupValues[2].toLong()
                val wText = m.groupValues[3]
                if (wText.isBlank()) continue
                words.add(SyncedLyricLine.SyncedWord(wText, ws, we))
                plainText.append(wText)
                lastEnd = we
            }
            if (words.isEmpty()) return@forEach
            out.add(
                SyncedLyricLine(
                    time = lineStart,
                    text = plainText.toString(),
                    endTime = maxOf(lineStart + lineDur, lastEnd),
                    words = words,
                )
            )
        }
        return out
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

    private fun JsonPrimitive.contentOrBlank(): String = contentOrNull ?: ""
}