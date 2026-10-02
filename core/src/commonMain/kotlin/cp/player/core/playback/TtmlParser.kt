package cp.player.core.playback

import kotlin.math.roundToInt

/**
 * TTML 歌词解析器（AMLL TTML DataBase 的 ttml 格式 → 统一的 [SyncedLyricLine]）。
 *
 * 不引第三方 XML 库（commonMain 没有轻量的多平台 XML 解析器），手写标签扫描：
 * - `<p begin="..." end="...">` 行级时间；`<span begin="..." end="...">` 词级时间
 * - `ttm:role="x-translation"` / `ttm:role="x-roman"` 识别翻译与罗马音
 *   （这两个 span 通常不带 begin/end，只取文本）
 * - 时间格式兼容 TTML clock（`0.115` 秒 / `3:05.800` 分:秒 / `1:03:05.8` 时:分:秒）
 *   与 metric 后缀（`ms` / `s` / `m` / `h`），小数分隔符兼容 `,`
 * - span 之间的裸文本节点（词间的空格）原样并入行文本
 *
 * 已知限制（相对 accompanist-lyrics 的 AutoParser）：
 * - `ttm:role="x-bg"` 背景人声整段跳过——统一的 [SyncedLyricLine] 没有背景轨概念
 *
 * 解析结果经 [finalize] 排序并补齐 endTime，约定与 [LyricsParser] 一致。
 * 解析失败或无可用行返回空列表。
 */
object TtmlParser {

    fun parse(raw: String): List<SyncedLyricLine> {
        if (raw.isBlank()) return emptyList()
        val body = raw.replace(COMMENT_REGEX, " ")
        val out = mutableListOf<SyncedLyricLine>()
        var idx = 0
        while (true) {
            val start = body.indexOf("<p", idx)
            if (start < 0) break
            // 只认 <p 后跟 空白 / '>' / '/' 的标签，避免误命中 <plain... 这类同前缀标签
            val next = body.getOrNull(start + 2)
            if (next != null && !next.isWhitespace() && next != '>' && next != '/') {
                idx = start + 2
                continue
            }
            val tagEnd = body.indexOf('>', start)
            if (tagEnd < 0) break
            if (body[tagEnd - 1] == '/') {
                // 自闭合 <p .../>：无内容的间奏占位，直接跳过
                idx = tagEnd + 1
                continue
            }
            val close = body.indexOf("</p>", tagEnd)
            if (close < 0) break
            val attrs = parseAttrs(body.substring(start + 2, tagEnd))
            val inner = body.substring(tagEnd + 1, close)
            parseParagraph(attrs, inner)?.let { out.add(it) }
            idx = close + 4
        }
        return finalize(out)
    }

    // ============ 段落内扫描 ============

    /** 一个开着的 span 栈帧：role + 词级时间 + 文本累积。 */
    private class Frame(
        val role: String?,
        val begin: Long?,
        val end: Long?,
        val buf: StringBuilder,
    )

    private fun parseParagraph(attrs: Map<String, String>, inner: String): SyncedLyricLine? {
        val lineBegin = attrs["begin"]?.let(::parseTime) ?: return null
        val lineEnd = attrs["end"]?.let(::parseTime)

        val words = mutableListOf<SyncedLyricLine.SyncedWord>()
        val lineText = StringBuilder()
        var translation: String? = null
        var romanization: String? = null

        val stack = ArrayDeque<Frame>()
        // x-bg（背景人声）开始时的栈深：其内所有词都不进主歌词
        var bgDepth = -1

        fun appendText(chunk: String) {
            val decoded = decodeEntities(chunk)
            val top = stack.lastOrNull()
            if (top == null) {
                lineText.append(decoded)
            } else {
                top.buf.append(decoded)
            }
        }

        var cursor = 0
        while (true) {
            val m = SPAN_TAG.find(inner, cursor) ?: break
            if (m.range.first > cursor) appendText(inner.substring(cursor, m.range.first))
            cursor = m.range.last + 1

            if (m.groupValues[1] == "/") {
                // </span>：弹出栈顶
                val frame = stack.removeLastOrNull() ?: continue
                val text = frame.buf.toString()
                if (frame.role == "x-translation") {
                    if (translation.isNullOrBlank() && text.isNotBlank()) translation = text.trim()
                } else if (frame.role == "x-roman") {
                    if (romanization.isNullOrBlank() && text.isNotBlank()) romanization = text.trim()
                } else if (frame.begin != null && bgDepth < 0) {
                    // 词级 span：文本并入行文本 + 记词级时间戳
                    lineText.append(text)
                    if (text.isNotBlank()) {
                        words.add(
                            SyncedLyricLine.SyncedWord(
                                text = text,
                                beginTime = frame.begin,
                                endTime = frame.end ?: frame.begin,
                            )
                        )
                    }
                }
                if (bgDepth == stack.size + 1) bgDepth = -1
            } else {
                val spanAttrs = parseAttrs(m.groupValues[2])
                val role = spanAttrs["ttm:role"]
                val begin = spanAttrs["begin"]?.let(::parseTime)
                val end = spanAttrs["end"]?.let(::parseTime)
                stack.addLast(Frame(role, begin, end, StringBuilder()))
                if (role == "x-bg" && bgDepth < 0) bgDepth = stack.size
            }
        }
        if (cursor < inner.length) appendText(inner.substring(cursor))

        var text = lineText.toString().replace(WHITESPACE_REGEX, " ").trim()
        var lineWords: List<SyncedLyricLine.SyncedWord> = words
        if (lineWords.size == 1) {
            // 整行只有一个「词」且时间覆盖整行 ⇒ 这是行级 span，不是逐字歌词
            lineWords = emptyList()
        }
        if (text.isEmpty() && lineWords.isEmpty()) return null
        // 无词级时间的纯文本行：文本不该带多余空格
        if (lineWords.isEmpty()) text = text.trim()
        return SyncedLyricLine(
            time = lineBegin,
            text = text,
            endTime = lineEnd,
            translation = translation,
            romanization = romanization,
            words = lineWords,
        )
    }

    // ============ 属性 / 时间 / 实体 ============

    private val SPAN_TAG = Regex("<(/?)span\\b([^>]*)>")
    private val COMMENT_REGEX = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val ATTR_REGEX = Regex("([\\w:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val METRIC_TIME = Regex("^([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)$")

    private fun parseAttrs(raw: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ATTR_REGEX.findAll(raw).forEach { m ->
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
            out[m.groupValues[1]] = value
        }
        return out
    }

    /** TTML 时间 → 毫秒。无法解析返回 null。 */
    internal fun parseTime(raw: String): Long? {
        val s = raw.trim().replace(',', '.')
        if (s.isEmpty()) return null
        if (s.contains(':')) {
            var total = 0.0
            for (part in s.split(':')) {
                val v = part.toDoubleOrNull() ?: return null
                total = total * 60.0 + v
            }
            return (total * 1000.0).roundToInt().toLong()
        }
        METRIC_TIME.find(s)?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            return when (m.groupValues[2]) {
                "ms" -> v.roundToInt().toLong()
                "s" -> (v * 1000.0).roundToInt().toLong()
                "m" -> (v * 60_000.0).roundToInt().toLong()
                else -> (v * 3_600_000.0).roundToInt().toLong()
            }
        }
        val sec = s.toDoubleOrNull() ?: return null
        return (sec * 1000.0).roundToInt().toLong()
    }

    internal fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = raw.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) {
                sb.append(c)
                i++
                continue
            }
            val name = raw.substring(i + 1, semi)
            val replacement: String? = when (name) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    if (name.length > 1 && name[0] == '#') {
                        val code = if (name[1] == 'x' || name[1] == 'X') {
                            name.substring(2).toIntOrNull(16)
                        } else {
                            name.substring(1).toIntOrNull()
                        }
                        code?.takeIf { it > 0 }?.toChar()?.toString()
                    } else {
                        null
                    }
                }
            }
            if (replacement != null) {
                sb.append(replacement)
                i = semi + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // ============ 后处理 ============

    /** 与 [LyricsParser] 同一约定：按时间升序，缺 endTime 的用下一行起始补齐。 */
    private fun finalize(lines: List<SyncedLyricLine>): List<SyncedLyricLine> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.time }
        return sorted.mapIndexed { i, line ->
            if (line.endTime != null) {
                line
            } else {
                val next = sorted.getOrNull(i + 1)?.time ?: (line.time + 4_000L)
                line.copy(endTime = next)
            }
        }
    }
}
