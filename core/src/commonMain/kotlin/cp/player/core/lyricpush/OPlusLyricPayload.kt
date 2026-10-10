/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/OPlusLyricPayload.kt
 * Changes: Song → LyricPushTrack、LyricLine → SyncedLyricLine（去掉 Halcyon 的
 *          backgroundText/backgroundWords 双轨，本仓库的行模型只有 text + translation +
 *          romanization）；org.json 与 String.format(Locale.US) 换成 LyricPushJson 与
 *          手写补零（commonMain 没有这两者）；时间戳精度规则保持原样 ——
 *          `lyric` 用厘秒 [mm:ss.cc]、`rawLyric` / `translationLyric` 用毫秒 [mm:ss.mmm]，
 *          这是 ColorOS Live Lyrics Bridge 4.0 的实际约定，改了接收端就不认。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import cp.player.core.playback.SyncedLyricLine

/**
 * ColorOS / OPPO 锁屏岛的歌词载荷。
 *
 * **不走广播、不走 AIDL** —— 它写在当前播放项的 `MediaMetadata.extras` 里：
 * - `lyricInfo`  = 本对象 [build] 出来的 JSON（整首 LRC + 可选逐字与翻译）；
 * - `rawLyric`   = 从 `lyricInfo` 里抽出来的逐字 LRC（模块模式才有）。
 *
 * 之所以是「整首」而不是「当前句」：ColorOS 自己按播放进度同步滚动，推送方只需要
 * 在切歌时给一次完整时间轴。
 *
 * 纯 Kotlin、无 Android 依赖 ⇒ 可在 `desktopTest` 直接单测。
 */
internal object OPlusLyricPayload {

    /** MediaMetadata extras 的键名（ColorOS 侧按这个名字找）。 */
    const val LYRIC_INFO_KEY = "lyricInfo"

    /** `lyricInfo` JSON 里逐字 LRC 的字段名；同时也是一个独立的 extras 键。 */
    const val RAW_LYRIC_INFO_KEY = "rawLyric"

    /** `lyricInfo` JSON 里翻译 LRC 的字段名。 */
    const val TRANSLATION_LYRIC_INFO_KEY = "translationLyric"

    /** 前奏 credit（作词/作曲/版权）出现的时间上限。 */
    private const val PREAMBLE_MAX_TIME_MS = 15_000L

    /** 「歌名 - 歌手」形式的标题行判定上限（比 credit 更严格）。 */
    private const val TITLE_CREDIT_MAX_TIME_MS = 5_000L

    private val preambleCreditPattern = Regex(
        """^(?:lyrics?\s+by|lyricist|written\s+by|composed\s+by|composer|produced\s+by|producer|arranged\s+by|arranger|performed\s+by|performer|作词|作曲|编曲|制作人|演唱)\s*[:：]?""",
        RegexOption.IGNORE_CASE,
    )
    private val titleArtistSeparatorPattern = Regex("""\s[-–—]\s""")

    /** 按模式构建；无有效歌词行返回 null（表示「清除」而不是「发一个空的」）。 */
    fun build(track: LyricPushTrack, lyrics: List<SyncedLyricLine>, mode: OPlusLyricMode): String? =
        if (mode == OPlusLyricMode.SYSTEM) buildSystemPayload(track, lyrics)
        else buildModulePayload(track, lyrics)

    /** 系统模式：只给整首行级 LRC，兼容性最好。 */
    fun buildSystemPayload(track: LyricPushTrack, lyrics: List<SyncedLyricLine>): String? {
        val lrc = lyrics.toOplusLrc().takeIf { it.isNotBlank() } ?: return null
        return LyricPushJson.buildObject(
            "songName" to track.title,
            "artist" to track.artist,
            "songId" to track.oplusSongId(),
            "lyric" to lrc,
        )
    }

    /** 模块模式：额外给逐字时间轴与翻译行（需要接收端是 Bridge 4.0 模块）。 */
    fun buildModulePayload(track: LyricPushTrack, lyrics: List<SyncedLyricLine>): String? {
        val moduleLyrics = lyrics.withoutOplusPreamble(track)
        val lrc = moduleLyrics.toOplusLrc().takeIf { it.isNotBlank() } ?: return null
        val rawLyric = moduleLyrics.toOplusRawLyric().takeIf { it.isNotBlank() } ?: lrc
        val translationLyric = moduleLyrics.toOplusTranslationLyric().takeIf { it.isNotBlank() }
        val fields = mutableListOf(
            "songName" to track.title,
            "artist" to track.artist,
            "songId" to track.oplusSongId(),
            "lyric" to lrc,
            RAW_LYRIC_INFO_KEY to rawLyric,
        )
        if (translationLyric != null) {
            fields += TRANSLATION_LYRIC_INFO_KEY to translationLyric
        }
        return LyricPushJson.buildObject(*fields.toTypedArray())
    }

    /** 判断一份已发布的 JSON 是不是当前模式需要的形状（模式切换后要重发）。 */
    fun matchesMode(rawJson: String?, mode: OPlusLyricMode): Boolean {
        if (rawJson.isNullOrBlank()) return false
        val hasRawLyric = rawLyric(rawJson) != null
        val hasTranslation = LyricPushJson.stringField(rawJson, TRANSLATION_LYRIC_INFO_KEY)
            ?.isNotBlank() == true
        return if (mode == OPlusLyricMode.SYSTEM) {
            !hasRawLyric && !hasTranslation
        } else {
            hasRawLyric
        }
    }

    /** 判断一份已发布的 JSON 是不是属于这首歌。 */
    fun matchesSong(rawJson: String?, track: LyricPushTrack): Boolean {
        if (rawJson.isNullOrBlank()) return false
        val songId = LyricPushJson.stringField(rawJson, "songId")?.takeIf { it.isNotBlank() }
        return when {
            songId != null -> songId == track.oplusSongId()
            else -> LyricPushJson.stringField(rawJson, "songName") == track.title &&
                LyricPushJson.stringField(rawJson, "artist") == track.artist
        }
    }

    /** 从 `lyricInfo` JSON 里抽出逐字 LRC（供 `rawLyric` 这个独立 extras 键使用）。 */
    fun rawLyric(rawJson: String?): String? =
        rawJson?.let { LyricPushJson.stringField(it, RAW_LYRIC_INFO_KEY) }?.takeIf { it.isNotBlank() }

    /** 是否带翻译行（决定要不要给 MediaSession 挂「翻译」按钮）。 */
    fun hasTranslation(rawJson: String?): Boolean =
        rawJson?.let { LyricPushJson.stringField(it, TRANSLATION_LYRIC_INFO_KEY) }
            ?.isNotBlank() == true

    // ============ 前奏过滤 ============

    /**
     * 去掉开头那些「作词 / 作曲 / 版权所有 / 歌名 - 歌手」行。
     *
     * 这些行不是歌词，投到锁屏岛会出现「岛里第一行永远是『作词：xxx』」。
     * 过滤后如果一行不剩（极端情况：整首都是 credit），**返回原列表**而不是空列表 ——
     * 空列表会让调用方认为「这首歌没有歌词」，比多几行 credit 更糟。
     */
    private fun List<SyncedLyricLine>.withoutOplusPreamble(track: LyricPushTrack): List<SyncedLyricLine> {
        val filtered = filterNot { it.isOplusPreambleLine(track) }
        return filtered.takeIf { it.isNotEmpty() } ?: this
    }

    private fun SyncedLyricLine.isOplusPreambleLine(track: LyricPushTrack): Boolean {
        val primary = lineTextOrNull() ?: return false
        val startMs = rawLyricStartMs()
        if (startMs > PREAMBLE_MAX_TIME_MS) return false

        val normalized = primary.trim()
        val lower = normalized.lowercase()
        if (preambleCreditPattern.containsMatchIn(normalized)) return true
        if (
            lower.contains("copyright") ||
            lower.contains("all rights reserved") ||
            normalized.contains("版权所有") ||
            normalized.contains("著作权") ||
            normalized.contains("未经许可") ||
            normalized.contains("未经授权")
        ) {
            return true
        }

        if (startMs > TITLE_CREDIT_MAX_TIME_MS || !titleArtistSeparatorPattern.containsMatchIn(normalized)) {
            return false
        }
        val lineKey = normalized.oplusIdentityKey()
        val titleKey = track.title.oplusIdentityKey()
        val artistKey = track.artist.oplusIdentityKey()
        return titleKey.length >= 2 &&
            artistKey.length >= 2 &&
            lineKey.contains(titleKey) &&
            lineKey.contains(artistKey)
    }

    private fun String.oplusIdentityKey(): String = lowercase().filter { it.isLetterOrDigit() }

    // ============ LRC 生成 ============

    /** 行级 LRC（厘秒精度）。 */
    private fun List<SyncedLyricLine>.toOplusLrc(): String =
        mapNotNull { line ->
            val primaryText = line.lineTextOrNull() ?: return@mapNotNull null
            line.time.coerceAtLeast(0L) to primaryText
        }
            .sortedBy { it.first }
            .joinToString("\n") { (timeMs, text) ->
                "${timeMs.toOplusTimestamp(TimestampPrecision.Centi)}$text"
            }

    /** 翻译行 LRC（毫秒精度；与原文相同的行会被跳过）。 */
    private fun List<SyncedLyricLine>.toOplusTranslationLyric(): String =
        mapNotNull { line ->
            val primary = line.lineTextOrNull()
            val translation = line.translation
                ?.stripInlineTimestamps(trim = true)
                ?.takeIf { it.isNotBlank() && it != primary }
                ?: return@mapNotNull null
            line.rawLyricStartMs() to translation
        }
            .sortedBy { it.first }
            .joinToString("\n") { (timeMs, text) ->
                "${timeMs.toOplusTimestamp(TimestampPrecision.Milli)}$text"
            }

    /** 逐字 LRC（毫秒精度；每个词一个时间戳，行尾补一个结束时间戳）。 */
    private fun List<SyncedLyricLine>.toOplusRawLyric(): String =
        mapNotNull { line -> line.toOplusRawMainLine() }
            .joinToString("\n")

    private fun SyncedLyricLine.toOplusRawMainLine(): String? {
        val primaryText = lineTextOrNull() ?: return null
        val rawWords = words.withLineSpacing(primaryText)
            .asSequence()
            .mapNotNull { word ->
                val text = word.text.stripInlineTimestamps(trim = false).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val startMs = word.beginTime.coerceAtLeast(0L)
                val endMs = word.endTime.coerceAtLeast(startMs + 1L)
                TimedText(text = text, startMs = startMs, endMs = endMs)
            }
            .sortedBy { it.startMs }
            .toList()

        // 没有逐字数据时退化成行级（毫秒精度），而不是空 —— 模块模式至少要给得出东西。
        if (rawWords.isEmpty()) {
            return "${time.coerceAtLeast(0L).toOplusTimestamp(TimestampPrecision.Milli)}$primaryText"
        }

        val builder = StringBuilder(primaryText.length + rawWords.size * 14)
        rawWords.forEach { word ->
            builder
                .append(word.startMs.toOplusTimestamp(TimestampPrecision.Milli))
                .append(word.text)
        }
        val lineEndMs = listOfNotNull(endTime, rawWords.maxOfOrNull { it.endMs })
            .maxOrNull()
            ?.takeIf { it > rawWords.last().startMs }
        if (lineEndMs != null) {
            builder.append(lineEndMs.toOplusTimestamp(TimestampPrecision.Milli))
        }
        return builder.toString()
    }

    /** 有逐字时以第一个词的起点为准（整行时间戳常常比第一个词早一点点）。 */
    private fun SyncedLyricLine.rawLyricStartMs(): Long =
        words.minOfOrNull { it.beginTime }?.coerceAtLeast(0L) ?: time.coerceAtLeast(0L)

    /**
     * 去掉文本里已有的内联时间戳（`[00:12.34]` / `<00:12.345>`）。
     *
     * 增强 LRC 与部分 TTML 的文本里就带着时间戳，不去掉的话拼进新 LRC 会出现
     * 双重时间戳，接收端直接解析失败。**只去掉长得像时间戳的**，其它方括号内容保留。
     */
    private fun String.stripInlineTimestamps(trim: Boolean): String {
        val stripped = replace(Regex("""\[([^\]]+)]|<([^>]+)>""")) { match ->
            val marker = match.groupValues.getOrNull(1).orEmpty()
                .ifBlank { match.groupValues.getOrNull(2).orEmpty() }
                .trim()
                .replace(',', '.')
            if (marker.matches(Regex("""\d{1,3}:\d{1,2}(?:[.:]\d{1,6})?"""))) "" else match.value
        }.replace(Regex("""[ \t\r\n]+"""), " ")
        return if (trim) stripped.trim() else stripped
    }

    private fun Long.toOplusTimestamp(precision: TimestampPrecision): String {
        val safeMs = coerceAtLeast(0L)
        val minutes = safeMs / 60_000L
        val seconds = (safeMs % 60_000L) / 1_000L
        val fraction = safeMs % 1_000L
        return when (precision) {
            TimestampPrecision.Centi -> "[${minutes.padded(2)}:${seconds.padded(2)}.${(fraction / 10L).padded(2)}]"
            TimestampPrecision.Milli -> "[${minutes.padded(2)}:${seconds.padded(2)}.${fraction.padded(3)}]"
        }
    }

    private fun Long.padded(width: Int): String {
        val raw = toString()
        return if (raw.length >= width) raw else "0".repeat(width - raw.length) + raw
    }

    /** 接收端身份：音源 + 音源内 id 最稳，退化到曲名 + 歌手 + 专辑。 */
    private fun LyricPushTrack.oplusSongId(): String = when {
        !sourceId.isNullOrBlank() && id.isNotBlank() -> "$sourceId:$id"
        id.isNotBlank() -> id
        else -> "$title|$artist|$album"
    }

    private enum class TimestampPrecision { Centi, Milli }

    private data class TimedText(val text: String, val startMs: Long, val endMs: Long)
}
