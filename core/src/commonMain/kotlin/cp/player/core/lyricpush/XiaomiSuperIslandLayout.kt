/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/XiaomiSuperIslandLyricLayout.kt
 * Changes: `Character.UnicodeBlock.of()` 换成手写的码点区间判定（commonMain 没有 Java 的
 *          UnicodeBlock，而且按 Block 判定会把 CJK 扩展区 B 判成代理对宽度 1）；
 *          宽度计算改为按**码点**迭代，emoji / 生僻字不再被算成两个半角字符；
 *          放在 commonMain 是为了能在 desktopTest 里直接验证断句结果 —— 这是整条
 *          超级岛链路里唯一「错了也编译得过、只有肉眼看得见」的部分。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

/**
 * 超级岛大字区的断句与权重计算。
 *
 * 岛里的左右两列是**固定宽度**的，塞不下就得自己算：中文一个字占两格、拉丁字母占一格。
 * 按「字符数」切会把中英混排的句子切得一边挤一边空。
 */
object XiaomiSuperIslandLayout {

    /** 一次断句结果。 */
    data class Split(val left: String, val right: String)

    /** 按权重取前若干内容（用于标准布局的标题行）。 */
    fun takeByWeight(text: String, maxWeight: Int): String {
        val normalized = text.trim()
        if (normalized.isEmpty()) return ""
        var weight = 0
        var end = 0
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            val width = codePointWidth(codePoint)
            if (weight + width > maxWeight) break
            weight += width
            index += charCount(codePoint)
            end = index
        }
        return normalized.substring(0, end).trim()
    }

    /**
     * 把一整句歌词切成左右两列。
     *
     * 打分规则（与上游一致）：优先让「左列的视觉宽度」接近右列，其次让两列都尽量填满，
     * 最后才考虑用掉多少字符（没用完的字符按 30 分/字 罚）。所以「宁可少放两个字也要
     * 左右平衡」是刻意的 —— 岛里的观感主要由平衡决定，不是由信息量决定。
     */
    fun splitFullLyric(
        text: String,
        showLeftCover: Boolean,
        leftMaxWeight: Int,
        rightMaxWeight: Int,
    ): Split {
        val normalized = text.trim()
        if (normalized.isEmpty()) return Split("", "")

        // 左侧有封面时视觉上更窄，所以要按比例修正后再比较平衡度。
        val leftVisualNumerator = if (showLeftCover) 6 else 5
        val leftVisualDenominator = if (showLeftCover) 5 else 6

        val boundaries = codePointBoundaries(normalized)
        val size = boundaries.size - 1
        var best: Candidate? = null

        for (endIndex in size downTo 2) {
            for (splitIndex in 1 until endIndex) {
                val left = normalized.substring(boundaries[0], boundaries[splitIndex]).trim()
                val right = normalized.substring(boundaries[splitIndex], boundaries[endIndex]).trim()
                val leftWeight = weightOf(left)
                val rightWeight = weightOf(right)
                if (leftWeight > leftMaxWeight || rightWeight > rightMaxWeight) continue
                val leftVisualWeight =
                    (leftWeight * leftVisualNumerator + leftVisualDenominator / 2) / leftVisualDenominator
                val score = abs(leftVisualWeight - rightWeight) * 10 +
                    (leftMaxWeight + rightMaxWeight - leftWeight - rightWeight) +
                    if (endIndex == size) 0 else (size - endIndex) * 30
                if (best == null || score < best.score) {
                    best = Candidate(splitIndex, endIndex, score)
                }
            }
        }

        val candidate = best
        if (candidate == null) {
            // 一句都塞不下（例如长度超过两列总和）：退化成「先塞满左列，剩下的塞右列」。
            val left = takeByWeight(normalized, leftMaxWeight)
            val right = takeByWeight(normalized.drop(left.length).trimStart(), rightMaxWeight)
            return Split(left, right)
        }
        return Split(
            left = normalized.substring(boundaries[0], boundaries[candidate.splitIndex]).trim(),
            right = normalized.substring(boundaries[candidate.splitIndex], boundaries[candidate.endIndex]).trim(),
        )
    }

    /** 设置页里的「N 个字符」换算成权重（一个字按 2 格算）。 */
    fun weightForCharacters(characters: Int): Int = characters.coerceAtLeast(1) * 2

    private data class Candidate(val splitIndex: Int, val endIndex: Int, val score: Int)

    private fun weightOf(text: String): Int {
        var total = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            total += codePointWidth(codePoint)
            index += charCount(codePoint)
        }
        return total
    }

    /** 「全角宽度 2、半角宽度 1」的近似判定；空白不计宽。 */
    private fun codePointWidth(codePoint: Int): Int = when {
        codePoint == ' '.code || codePoint == '\t'.code || codePoint == '\n'.code -> 0
        isWideCodePoint(codePoint) -> 2
        else -> 1
    }

    // 注：Kotlin 的 when 分支不支持用逗号并列多个 `in` 范围（那是常量分支的语法），
    // 只能写成 `||` 链。原先的 `when { in a..b, in c..d -> }` 无法通过编译。
    private fun isWideCodePoint(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F ||   // Hangul Jamo 初声
            codePoint in 0x2E80..0x303E ||   // CJK 部首补充 / 标点
            codePoint in 0x3041..0x33FF ||   // 平假名 / 片假名 / 注音 / CJK 兼容
            codePoint in 0x3400..0x4DBF ||   // CJK 扩展 A
            codePoint in 0x4E00..0x9FFF ||   // CJK 统一表意
            codePoint in 0xA000..0xA4CF ||   // 彝文
            codePoint in 0xAC00..0xD7A3 ||   // Hangul 音节
            codePoint in 0xF900..0xFAFF ||   // CJK 兼容表意
            codePoint in 0xFE30..0xFE6F ||   // CJK 兼容形式
            codePoint in 0xFF00..0xFF60 ||   // 全角形式
            codePoint in 0xFFE0..0xFFE6 ||   // 全角符号
            codePoint in 0x1F300..0x1FAFF || // emoji
            codePoint in 0x20000..0x3FFFD    // CJK 扩展 B 及以后

    private fun charCount(codePoint: Int): Int = if (codePoint > 0xFFFF) 2 else 1

    private fun String.codePointAt(index: Int): Int {
        val high = this[index]
        if (!high.isHighSurrogate() || index + 1 >= length) return high.code
        val low = this[index + 1]
        if (!low.isLowSurrogate()) return high.code
        return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }

    /** 每个**码点**边界的下标；末尾附带 [length]。 */
    private fun codePointBoundaries(text: String): IntArray {
        val result = ArrayList<Int>(text.length + 1)
        var index = 0
        while (index < text.length) {
            result += index
            index += charCount(text.codePointAt(index))
        }
        result += text.length
        return result.toIntArray()
    }

    private fun abs(value: Int): Int = if (value < 0) -value else value
}
