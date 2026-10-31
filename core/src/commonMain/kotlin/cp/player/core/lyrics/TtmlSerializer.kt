package cp.player.core.lyrics

import cp.player.core.playback.SyncedLyricLine

/**
 * 将宿主统一的同步歌词行序列化为标准 AMLL / Apple Music 规范的 TTML 格式。
 *
 * BetterLyrics 的 [LyricsContentParser.Ttml] 原生按照 AMLL TTML 规范解析，
 * 支持逐字时间戳 (`<span begin="..." end="...">字</span>`)、行内翻译 (`ttm:role="x-translation"`)
 * 与罗马音/注音 (`ttm:role="x-roman"`)。
 */
object TtmlSerializer {

    /**
     * 将毫秒时间戳转换为 TTML 时间字符串（`mm:ss.xxx` 或 `hh:mm:ss.xxx`）。
     */
    fun formatTime(ms: Long): String {
        val totalMs = ms.coerceAtLeast(0L)
        val totalSeconds = totalMs / 1000L
        val remMs = totalMs % 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val msStr = remMs.toString().padStart(3, '0')
        val sStr = seconds.toString().padStart(2, '0')
        val mStr = minutes.toString().padStart(2, '0')
        return if (hours > 0) {
            val hStr = hours.toString().padStart(2, '0')
            "$hStr:$mStr:$sStr.$msStr"
        } else {
            "$mStr:$sStr.$msStr"
        }
    }

    /**
     * XML 字符实体转义。
     */
    fun escapeXml(text: String): String = buildString(text.length + 8) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    /**
     * 将歌词列表序列化为 TTML 文本。
     *
     * @param lines 已归一化的同步歌词行
     * @param title 歌曲名（可选，写入 metadata）
     * @param artist 艺术家（可选，写入 metadata）
     */
    fun serialize(
        lines: List<SyncedLyricLine>,
        title: String? = null,
        artist: String? = null,
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions">""")
        appendLine("  <head>")
        appendLine("    <metadata>")
        if (!title.isNullOrBlank()) {
            appendLine("      <ttm:title>${escapeXml(title)}</ttm:title>")
        }
        if (!artist.isNullOrBlank()) {
            appendLine("      <ttm:agent>${escapeXml(artist)}</ttm:agent>")
        }
        appendLine("    </metadata>")
        appendLine("  </head>")
        appendLine("  <body>")
        appendLine("    <div>")

        for (i in lines.indices) {
            val line = lines[i]
            val nextLine = lines.getOrNull(i + 1)
            val beginMs = line.time.coerceAtLeast(0L)
            val endMs = line.endTime
                ?: nextLine?.time?.takeIf { it > beginMs }
                ?: line.words.lastOrNull()?.endTime?.takeIf { it > beginMs }
                ?: (beginMs + 3000L)
            val safeEndMs = maxOf(beginMs, endMs)

            val pBegin = formatTime(beginMs)
            val pEnd = formatTime(safeEndMs)

            append("      <p begin=\"$pBegin\" end=\"$pEnd\">")

            if (line.words.isNotEmpty()) {
                // 逐字歌词行
                for (word in line.words) {
                    val wBegin = formatTime(word.beginTime.coerceAtLeast(0L))
                    val wEnd = formatTime(maxOf(word.beginTime, word.endTime).coerceAtLeast(0L))
                    append("<span begin=\"$wBegin\" end=\"$wEnd\">${escapeXml(word.text)}</span>")
                }
            } else {
                // 普通行级歌词
                append(escapeXml(line.text))
            }

            // 行内辅助轨道：翻译
            if (!line.translation.isNullOrBlank()) {
                append("<span ttm:role=\"x-translation\" xml:lang=\"zh-Hans\">${escapeXml(line.translation)}</span>")
            }

            // 行内辅助轨道：罗马音 / 注音
            if (!line.romanization.isNullOrBlank()) {
                append("<span ttm:role=\"x-roman\">${escapeXml(line.romanization)}</span>")
            }

            appendLine("</p>")
        }

        appendLine("    </div>")
        appendLine("  </body>")
        append("</tt>")
    }

    /**
     * 将歌词列表转换为标准 LRC 文本。
     */
    fun toLrc(lines: List<SyncedLyricLine>): String = buildString {
        for (line in lines) {
            val totalMs = line.time.coerceAtLeast(0L)
            val m = totalMs / 60000L
            val s = (totalMs % 60000L) / 1000L
            val cs = (totalMs % 1000L) / 10L
            val mStr = m.toString().padStart(2, '0')
            val sStr = s.toString().padStart(2, '0')
            val csStr = cs.toString().padStart(2, '0')
            append('[').append(mStr).append(':').append(sStr).append('.').append(csStr).append(']')
            appendLine(line.text)
        }
    }
}
