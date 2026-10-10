/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference:
 *   - app/src/main/java/com/ella/music/player/LyriconBridge.kt（`withLineSpacing`，256-284 行）
 *   - app/src/main/java/com/ella/music/player/LiveLyricNotificationText.kt（词窗口与紧凑文本）
 * Changes: Halcyon 在 Lyricon / SuperLyric / OPlus 三个 bridge 里各存了一份**逐字相同的**
 *          withLineSpacing —— 这里提成一份共用（三份必然漂移，而漂移点是「英文歌词词尾空格
 *          该不该补进 word.text」，直接影响接收端高亮是否连续）；模型由 LyricWord 换成
 *          SyncedLyricLine.SyncedWord；副行由两个互斥布尔合成 LyricSecondaryMode。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import cp.player.core.playback.SyncedLyricLine

/** 紧凑 chip / 状态栏歌词的最大码点数（Halcyon 实测值）。 */
internal const val COMPACT_MAX_CODE_POINTS = 7

/** 通知标题类渠道的最大码点数。 */
internal const val TITLE_MAX_CODE_POINTS = 40

/**
 * 把换行、连续空白归一化成单空格，并去掉空行后拼回一行。
 *
 * 外部渠道（状态栏 / 通知 / 岛）都只有**一到两行**的显示空间，而 TTML 与增强 LRC
 * 里的原文常常带换行；不归一化会出现「一句歌词把通知撑成三行、后面全被截断」。
 */
internal fun SyncedLyricLine.lineTextOrNull(): String? =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }

/**
 * 按 [LyricSecondaryMode] 取副行内容。
 *
 * 翻译找不到时**不**退化成注音：这两个是不同用途的文本，混着给会让接收端
 * 「上一句是翻译、下一句是罗马音」。找不到就返回 null，由调用方决定要不要放空。
 */
internal fun SyncedLyricLine.secondaryText(mode: LyricSecondaryMode): String? = when (mode) {
    LyricSecondaryMode.OFF -> null
    LyricSecondaryMode.TRANSLATION -> translation?.takeIf { it.isNotBlank() }
    LyricSecondaryMode.PRONUNCIATION -> romanization?.takeIf { it.isNotBlank() }
}

/** 按 [LyricContentMode] 取主歌词内容（找不到时回退原文，主歌词不能为空）。 */
internal fun SyncedLyricLine.contentText(mode: LyricContentMode): String =
    when (mode) {
        LyricContentMode.ORIGINAL -> lineTextOrNull()
        LyricContentMode.TRANSLATION -> translation?.takeIf { it.isNotBlank() }
        LyricContentMode.PRONUNCIATION -> romanization?.takeIf { it.isNotBlank() }
    } ?: lineTextOrNull() ?: ""

/**
 * 把整行文本里被切成 word 时丢掉的**间隔**补回每个 word。
 *
 * 逐字时间轴的 word.text 通常是不含空格的（"Hello" "world"），接收端按 word 拼回
 * 显示时会变成 "Helloworld"。这里按行文本里的实际位置把两个词之间的空格 / 标点
 * 追加到前一个 word 上，保证拼回来与原文一致。
 *
 * 匹配不上（行文本被二次加工过）时**原样返回**，不做部分修补 —— 半个修好的行
 * 比没修的行更难排查。
 */
internal fun List<SyncedLyricLine.SyncedWord>.withLineSpacing(lineText: String): List<SyncedLyricLine.SyncedWord> {
    if (isEmpty() || lineText.isBlank() || !lineText.any { it.isWhitespace() }) return this

    val result = mutableListOf<SyncedLyricLine.SyncedWord>()
    var cursor = 0

    forEachIndexed { index, word ->
        val start = lineText.indexOf(word.text, startIndex = cursor)
        if (start < 0) {
            result += word
            return@forEachIndexed
        }
        val end = start + word.text.length
        val nextText = getOrNull(index + 1)?.text
        val nextStart = if (nextText != null) lineText.indexOf(nextText, startIndex = end) else -1
        val suffix = when {
            nextStart > end -> lineText.substring(end, nextStart)
            nextText == null && end < lineText.length -> lineText.substring(end)
            else -> ""
        }
        result += word.copy(text = word.text + suffix)
        cursor = end + suffix.length
    }

    return result.takeIf { it.size == size } ?: this
}

/**
 * 当前唱到的词下标；不在任何词的时间区间内（间隙 / 前奏 / 尾奏）返回 -1。
 *
 * 词重叠时取**开始最晚**的那个：读者的视觉焦点在最新起唱的词上，而间隙仍然
 * 正确地返回 -1（不能被误判成「一个新的已唱词」）。
 */
internal fun currentWordIndex(words: List<SyncedLyricLine.SyncedWord>, positionMs: Long): Int =
    words.withIndex()
        .asSequence()
        .filter { (_, word) -> positionMs >= word.beginTime && positionMs < word.endTime }
        .maxByOrNull { (_, word) -> word.beginTime }
        ?.index
        ?: -1

/** 间隙时用的「最近的词」：优先取已唱完的最后一个，否则取还没开始唱的第一个。 */
internal fun nearestWordIndex(words: List<SyncedLyricLine.SyncedWord>, positionMs: Long): Int {
    val previous = words.indexOfLast { positionMs >= it.endTime }
    if (previous >= 0) return previous
    return words.indexOfFirst { positionMs < it.beginTime }
}

/**
 * 以 [currentWordIndex] 为中心，向两侧扩展到不超过 [maxCodePoints]。
 *
 * 当前词**永远保留**；越界的上下文才用省略号替代。两侧都放得下时让当前词尽量居中
 * —— 这也让首词 / 尾词自然地把窗口偏向有内容的那一侧。
 */
internal fun buildWordWindow(
    words: List<String>,
    currentWordIndex: Int,
    maxCodePoints: Int = TITLE_MAX_CODE_POINTS,
): String {
    val tokens = words.map { normalizeInline(it) }.filter { it.isNotBlank() }
    if (tokens.isEmpty()) return ""
    if (currentWordIndex !in tokens.indices) return joinWords(tokens)

    val maxLength = maxCodePoints.coerceAtLeast(1)
    var start = currentWordIndex
    var end = currentWordIndex

    while (true) {
        val leftText = if (start > 0) formatWindow(tokens, start - 1, end) else null
        val rightText = if (end < tokens.lastIndex) formatWindow(tokens, start, end + 1) else null
        val leftFits = leftText != null && codePointCount(leftText) <= maxLength
        val rightFits = rightText != null && codePointCount(rightText) <= maxLength
        if (!leftFits && !rightFits) break

        when {
            leftFits && !rightFits -> start--
            rightFits && !leftFits -> end++
            currentWordIndex - start <= end - currentWordIndex -> start--
            else -> end++
        }
    }

    return formatWindow(tokens, start, end)
}

/**
 * 截断到 [COMPACT_MAX_CODE_POINTS]，用省略号结尾。
 *
 * [preserveLongToken]：整行没有空白（典型是日文 / 中文整句没有分词）时保留原样 ——
 * 那种情况下截成 7 个字反而读不出内容，宁可让接收端自己裁。
 */
internal fun compactText(
    text: String,
    preserveLongToken: Boolean = false,
    maxCodePoints: Int = COMPACT_MAX_CODE_POINTS,
): String {
    val normalized = normalizeInline(text)
    if (normalized.isBlank()) return ""
    if (codePointCount(normalized) <= maxCodePoints) return normalized
    if (preserveLongToken && normalized.none { it.isWhitespace() }) return normalized
    val visible = (maxCodePoints - 1).coerceAtLeast(1)
    // 截断点常常落在一个空格后面（"ab cd …"），把尾部空白去掉再补省略号，
    // 否则 chip 里会出现「空格 + 省略号」这种看起来像 bug 的间距。
    val head = takeCodePoints(normalized, visible).trimEnd()
    return head + "…"
}

/** 纯音乐符号的行（"♪" 之类）——这类行投到状态栏只会显示一个符号，没有意义。 */
internal fun String.isMusicSymbolOnly(): Boolean = all { char ->
    char.isWhitespace() ||
        char in setOf('♪', '♫', '♬', '♩', '♭', '♯', '♮')
}

// ============ 内部小工具 ============

private val whitespace = Regex("\\s+")

private fun normalizeInline(value: String): String = whitespace.replace(value.trim(), " ")

/**
 * 把逐字 token 拼回可读文本。
 *
 * ⚠️ **不能直接 `joinToString("")`**：TTML / 增强 LRC 的逐字 token 是不含空格的
 * （`"Hello"` + `"world"`），直接拼会得到 `Helloworld`。加空格又要小心 —— 中文词之间
 * 不能加（会变成「你 好 世 界」），标点前后也不能加。规则与上游一致：
 *
 * - 两侧任一侧是 CJK ⇒ 不加空格；
 * - 下一个 token 以收尾标点开头，或上一个 token 以起始标点结尾 ⇒ 不加；
 * - 撇号 / 连字符两侧不加（`don't`、`well-known`）。
 *
 * 与上游的差别：上游用 `Character.getType()` 判标点类别，`commonMain` 没有这个 API，
 * 这里换成**显式字符集**。覆盖面比 Unicode 类别窄（罕见标点会退化成加空格），
 * 但常见中英标点都覆盖到了，且行为在任何平台上完全一致。
 */
private fun joinWords(tokens: List<String>): String = buildString {
    tokens.forEach { raw ->
        val token = normalizeInline(raw)
        if (token.isBlank()) return@forEach
        if (isNotEmpty() && needsSeparator(lastCodePoint(), token.firstCodePoint())) append(' ')
        append(token)
    }
}

/** 窗口首尾越界时补省略号，中间按 [joinWords] 拼。 */
private fun formatWindow(tokens: List<String>, start: Int, end: Int): String = buildString {
    if (start > 0) append("…")
    append(joinWords(tokens.subList(start, end + 1)))
    if (end < tokens.lastIndex) append("…")
}

private fun String.firstCodePoint(): Int = codePointAt(0)

private fun StringBuilder.lastCodePoint(): Int {
    if (isEmpty()) return 0
    val last = this[length - 1]
    if (!last.isLowSurrogate() || length < 2) return last.code
    val high = this[length - 2]
    if (!high.isHighSurrogate()) return last.code
    return 0x10000 + ((high.code - 0xD800) shl 10) + (last.code - 0xDC00)
}

private fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (!high.isHighSurrogate() || index + 1 >= length) return high.code
    val low = this[index + 1]
    if (!low.isLowSurrogate()) return high.code
    return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
}

private fun needsSeparator(previous: Int, next: Int): Boolean {
    if (previous == 0 || next == 0) return false
    if (isCjkCodePoint(previous) || isCjkCodePoint(next)) return false
    if (next.toChar() in CLOSING_PUNCTUATION || previous.toChar() in OPENING_PUNCTUATION) return false
    if (previous == '\''.code || next == '\''.code) return false
    if (previous == '-'.code || next == '-'.code) return false
    return true
}

private fun isCjkCodePoint(codePoint: Int): Boolean =
    codePoint in 0x2E80..0x9FFF ||
        codePoint in 0xAC00..0xD7AF ||
        codePoint in 0x3040..0x30FF ||
        codePoint in 0x20000..0x323AF

/** 收尾标点：后面不该再插空格。 */
private const val CLOSING_PUNCTUATION = ",.;:!?)]}%…，。；：！？、）》」』】·”"

/** 起始标点：前面不该再插空格。 */
private const val OPENING_PUNCTUATION = "([{（《「『【“‘"

internal fun codePointCount(value: String): Int = value.sumOf { if (it.isHighSurrogate()) 0 else 1 }

/**
 * 按**码点**而不是 Char 取前 [count] 个，避免把代理对（emoji / 部分生僻字）劈成两半。
 */
internal fun takeCodePoints(value: String, count: Int): String {
    if (count <= 0) return ""
    var taken = 0
    var index = 0
    while (index < value.length && taken < count) {
        val char = value[index]
        val width = if (char.isHighSurrogate()) 2 else 1
        index += width
        taken++
    }
    return value.substring(0, index.coerceAtMost(value.length))
}
