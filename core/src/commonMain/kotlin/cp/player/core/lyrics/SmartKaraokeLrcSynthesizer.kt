package cp.player.core.lyrics

import cp.player.core.playback.SyncedLyricLine
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 行级歌词 → 逐字（Karaoke）时间戳的合成器。
 *
 * ## 一句话原理
 * 标准 LRC 只给了「这一行什么时候开始唱」，没给字界。合成器做三件事：
 * 1. **切**：把行文本切成歌唱 token（汉字 / 假名 / 英文词 / 促音 / 长音 / 标点），
 *    并按标点注入气口 —— 见 [tokenize]；
 * 2. **分**：算出每个 token 该占多少时间（暗区 + 弹性段 + 收势 + 气口）；
 * 3. **配**：把这一行的时长**整数配平**地分下去，保证不越界、不重叠、不出现零长段。
 *
 * ## 与「真逐字」的关系
 * - 原生带 `words` 的行**一律原样保留**（逐行降级，不是整首放弃）；
 * - 同曲里若已有若干行带真逐字，用它们反解出「每个字平均多少毫秒」，
 *   再拿它给没有逐字的行**钳一个合理语速区间** —— 见 [calibrateMsPerToken]。
 *
 * ## 呈现层不在本文件
 * 三种语言的咬音差异（声母暗区 / 拍等时 / 词尾收势）在 [KaraokeArticulation]；
 * 词表在 [PinyinTimingTable] 与 [KanjiMoraTable]（均由 `scripts/` 生成）。
 */
object SmartKaraokeLrcSynthesizer {

    /** 一个字形至少要亮这么久（更短的会让高亮看起来在闪）。 */
    private const val MIN_VISIBLE_MS = 80L

    /** 再短的窗口也要给足，否则整行会「挤」在一起。 */
    private const val MIN_SINGING_MS = 300L

    /** 行尾必须给下一行留出的安全距离。 */
    private const val SAFE_TAIL_MS = 120L

    /** 末字延长音的上限（再长就不像拖音，像卡住了）。 */
    private const val MAX_TAIL_MS = 1_200L

    /** 行内标点（逗号/顿号/分号/破折号）的微气口。 */
    private const val CAESURA_MS = 110L

    /**
     * 把整首行级歌词升级为逐字歌词。
     *
     * 已经带 `words` 的行原样返回；空白行原样返回；其余按行独立合成。
     */
    fun synthesize(lines: List<SyncedLyricLine>): List<SyncedLyricLine> {
        if (lines.isEmpty()) return emptyList()
        // 快路径：整首都已经是逐字，什么都不用做
        if (lines.all { it.words.isNotEmpty() }) return lines

        val density = globalDensity(lines)
        val anchor = calibrateMsPerToken(lines)
        val tempo = estimateSongTempo(lines)

        return lines.mapIndexed { index, line ->
            if (line.words.isNotEmpty()) line
            else synthesizeLine(line, lines.getOrNull(index + 1), density, anchor, tempo)
        }
    }

    // ============ 单行 ============

    private fun synthesizeLine(
        line: SyncedLyricLine,
        next: SyncedLyricLine?,
        density: Float,
        anchorMsPerToken: Float?,
        tempo: SongTempo,
    ): SyncedLyricLine {
        if (line.text.isBlank()) return line
        val tokens = tokenize(line.text)
        if (tokens.none { it.isPronounced }) return line

        val start = line.time
        val nextStart = next?.time?.takeIf { it > start } ?: (line.endTime ?: (start + 3_800L))
        val span = max(300L, nextStart - start)
        val syllableCount = tokens.count { it.isPronounced }

        // 窗口上限：行尾永远要给下一行留出安全距离（短跨度时宁可挤，也不越界）
        val maxWindow = (span - SAFE_TAIL_MS).coerceAtLeast(120L)
        val plan = planLineDuration(span, syllableCount, density, tempo)
        var window = plan.singMs
        // 同曲真逐字行给出的语速锚点：宁可留白，也不要把一个字拖到两秒
        if (anchorMsPerToken != null) {
            val low = (syllableCount * anchorMsPerToken * 0.55f).toLong()
            val high = (syllableCount * anchorMsPerToken * 1.90f).toLong()
            window = window.coerceIn(min(low, high), max(low, high))
        }
        window = window.coerceIn(min(MIN_SINGING_MS, maxWindow), maxWindow)

        // ⚠️ 只有 [LinePlan.coreMs] 参与「按权重分配」；剩下的那截是**末字的延长音**。
        // 把多出来的时间摊到每个字上，会把整句唱成慢动作；真实的唱法是只有最后一个字被拖长。
        val tailMs = (window - plan.coreMs).coerceIn(0L, MAX_TAIL_MS)
        val coreWindow = window - tailMs

        // 语速系数只压缩辅音与气口，不动元音之间的相对比例
        val rate = (coreWindow.toFloat() / syllableCount / 420f).coerceIn(0.6f, 1.8f)
        val language = KaraokeArticulation.languageOf(line.text)
        var parts = KaraokeArticulation.articulate(tokens, language, rate, lineEndLiftOf(tempo))

        // ---- 预算：固定项（暗区 + 气口）先扣，剩下按权重分 ----
        fun fixedMs() = parts.sumOf { it.onsetMs + it.pauseMs }
        val mins = parts.map { part ->
            if (part.silent || part.weight <= 0f) 0L else MIN_VISIBLE_MS + part.releaseMs
        }
        var elastic = coreWindow - fixedMs()
        if (elastic < mins.sum()) {
            // 固定项吃光了窗口（超快 rap / 一行全是标点）：按比例压暗区与气口，元件不动
            val target = (coreWindow - mins.sum()).coerceAtLeast(0L)
            val fixed = fixedMs()
            val k = if (fixed > 0) target.toFloat() / fixed else 1f
            parts = parts.map {
                it.copy(onsetMs = (it.onsetMs * k).toLong(), pauseMs = (it.pauseMs * k).toLong())
            }
            elastic = coreWindow - fixedMs()
        }
        val durations = apportion(elastic.coerceAtLeast(0L), parts.map { it.weight }, mins)

        // ---- 落轴 ----
        val words = ArrayList<SyncedLyricLine.SyncedWord>(parts.size)
        var cursor = start
        for (i in parts.indices) {
            val part = parts[i]
            cursor += part.onsetMs
            val allocated = durations[i]
            when {
                part.silent -> {
                    // 促音：整拍静默。字形只占最后 1ms（渲染器不允许零长段），亮起即接下一字的辅音
                    cursor += allocated
                    if (part.text.isNotBlank()) {
                        val end = max(cursor, start)
                        val begin = max(beginOfLast(words, start), end - 1L)
                        words += SyncedLyricLine.SyncedWord(part.text, begin, max(end, begin))
                    }
                }

                part.weight > 0f -> {
                    val begin = cursor
                    val visibleEnd = max(begin + MIN_VISIBLE_MS, cursor + allocated - part.releaseMs)
                    words += SyncedLyricLine.SyncedWord(part.text, begin, visibleEnd)
                    cursor += allocated
                }

                else -> {
                    // 行首标点这类没有时值的段：给 1ms 占位，保证文本不丢
                    cursor += allocated
                    if (part.text.isNotBlank()) {
                        val begin = max(cursor, beginOfLast(words, start))
                        words += SyncedLyricLine.SyncedWord(part.text, begin, begin + 1L)
                    }
                }
            }
            cursor += part.pauseMs
        }

        // 末字延长音：把「本该留白前的那截」全部给最后一个有字的段，
        // 视觉上就是那个字一直亮着被拖长，而不是整句均匀变慢。
        if (tailMs > 0L && words.isNotEmpty()) {
            val lastIndex = words.lastIndex
            val last = words[lastIndex]
            words[lastIndex] = last.copy(endTime = last.endTime + tailMs)
        }

        // 兜底钳制：末字绝不允许压到下一行头上。只允许收缩 end，不许把 begin 往前挪
        // （往前挪会破坏 begin 的单调性，进而让渲染器整行错位）。
        if (words.isNotEmpty()) {
            val limit = nextStart - 40L
            val last = words.last()
            if (last.endTime > limit) {
                words[words.lastIndex] = last.copy(endTime = max(last.beginTime + 1L, limit))
            }
        }

        return line.copy(words = words, endTime = line.endTime ?: (start + span))
    }

    private fun beginOfLast(words: List<SyncedLyricLine.SyncedWord>, fallback: Long): Long =
        words.lastOrNull()?.endTime ?: fallback

    // ============ 时长规划 ============

    /**
     * 一行的时长规划结果。
     *
     * @param coreMs 按权重均匀分配的那部分
     * @param singMs 整行实际占用（= coreMs + 末字延长）
     */
    private data class LinePlan(val coreMs: Long, val singMs: Long)

    /**
     * 这一行到底唱多久。
     *
     * ## 为什么不能直接用「下一行时间 − 这一行时间」
     * 行跨度里混着三种东西：**唱的、换气的、伴奏的**。副歌前那段 8 秒过门会把
     * 「三个字」的跨度撑到 8 秒 —— 按跨度均分，这三个字就变成每个两秒的慢动作。
     *
     * ## 为什么也不能拿「典型语速」去截（上一版的教训）
     * 真实演唱的语速波动远比想象大：慢速乐句能到全曲典型值的 1.5~2 倍，
     * 拿「典型 × 1.05 / 1.35」当上限，结果就是**每句都比真实唱提前结束** —— 高亮停了，
     * 歌还在唱。截断阈值必须比「典型」高得多，高到只有**物理上不可能**是唱的跨度才会触发。
     *
     * ## 现在的模型：物理上限内尽量填满
     * ```
     * 上限 cap   = 音节数 × r_max        // r_max ≈ 全曲 70 分位语速 × 1.5，钳在 300~900ms
     * 歌唱时长   = min(跨度 − 换气, cap)
     * ```
     * - 正常行 / 慢速行：跨度 ≤ cap ⇒ **唱满**（扣一点换气），不再提前结束；
     * - 间奏 / 过门：跨度 > cap ⇒ 唱到 cap 为止，剩下的留白。
     *
     * 多出来的时间怎么分：一半让**整句匀速变慢**（真实演唱里整句拖慢很常见），
     * 一半给**末字延长**（上限 1.2s）—— 全摊开是慢动作，全给末字像卡住。
     */
    private fun planLineDuration(
        span: Long,
        syllableCount: Int,
        density: Float,
        tempo: SongTempo,
    ): LinePlan {
        val typical = tempo.typicalMs
            ?: return legacySingingDuration(span, syllableCount, density).let { LinePlan(it, it) }
        val maxRate = tempo.maxMs ?: return legacySingingDuration(span, syllableCount, density)
            .let { LinePlan(it, it) }

        val breath = breathFor(span)
        val allow = max(120L, span - breath)
        val cap = (syllableCount * maxRate).toLong()
        val singing = min(allow, cap).coerceAtLeast(min(MIN_SINGING_MS, allow))

        val excess = singing - (syllableCount * typical).toLong()
        val tail = (excess * 0.5f).toLong().coerceIn(0L, MAX_TAIL_MS)
        return LinePlan(singing - tail, singing)
    }

    /** 行间换气：按跨度比例取，短行少喘、长行多喘，但都有限。 */
    private fun breathFor(span: Long): Long = (span * 0.07f).toLong().coerceIn(150L, 450L)

    /**
     * 慢歌末字拖得更长、快歌收得更干脆 —— 逐字渲染里最容易被听出「假」的地方。
     */
    private fun lineEndLiftOf(tempo: SongTempo): Float {
        val rate = tempo.typicalMs ?: return 1.55f
        return when {
            rate >= 480f -> 1.75f
            rate >= 300f -> 1.55f
            else -> 1.35f
        }
    }

    /**
     * 只看跨度与音节数的分档启发式：**样本不足时**的兜底（[estimateSongTempo] 返回 null）。
     *
     * - 超短行（≤4 字）：防拖沓，尾字延音有上限，余下留白给伴奏；
     * - 密集行（≥12 字）：高语速模式，气口压到 160~250ms；
     * - 常规行（5~11 字）：唱满，只扣生理换气；
     * - 超长跨度（>9s）：延展后截断，剩下的时间留白。
     */
    private fun legacySingingDuration(span: Long, syllableCount: Int, density: Float): Long {
        if (syllableCount <= 4) {
            val cap = max(2_200L, syllableCount * 700L + 1_200L)
            return min(max(400L, span - 400L), cap)
        }
        if (syllableCount >= 12) {
            val breath = if (span <= 3_500L) 160L else 250L
            return max(600L, span - breath)
        }
        return when {
            span <= 2_000L -> max(300L, span - 180L)
            span <= 7_000L -> {
                val baseBreath = when {
                    span <= 3_800L -> 280L
                    span <= 5_400L -> 360L
                    else -> 460L
                }
                // 越快气口越短
                val fastFactor = (density - 3f).coerceIn(-1f, 1f) * 0.15f
                val breath = (baseBreath * (1f - fastFactor)).toLong().coerceIn(200L, 550L)
                max(500L, span - breath)
            }
            span <= 9_000L ->
                if (syllableCount >= 8) span - 550L
                else min(span - 800L, max(4_000L, syllableCount * 560L + 1_300L))
            else -> min(span - 1_500L, max(4_500L, syllableCount * 600L + 1_600L))
        }
    }

    /**
     * 全曲语速的两个统计量。
     *
     * 行跨度只可能被「额外的空白」抬高，绝不会被压低 —— 误差是**单侧**的，所以：
     * - [typicalMs]（50 分位）代表「这句典型唱多快」，用来算末字延长的分摊；
     * - [maxMs]（70 分位 × 1.5，钳 300~900ms）是**物理上限**：一个字再拖也就这个量级，
     *   跨度超过 `音节数 × maxMs` 的部分只可能是伴奏，而不是唱得慢。
     *   上一版拿「典型 × 1.05 / 1.35」当上限，等于假设歌手永远匀速 —— 结果每句都提前唱完。
     *
     * 少于 5 行样本时不下结论（两个字段都是 null，让 [legacySingingDuration] 兜底）：
     * 两行歌词足够构造出任何离谱的「语速」。
     */
    private fun estimateSongTempo(lines: List<SyncedLyricLine>): SongTempo {
        val perSyllable = ArrayList<Float>(lines.size)
        for (index in lines.indices) {
            val next = lines.getOrNull(index + 1) ?: continue
            val span = next.time - lines[index].time
            if (span < 400L || span > 30_000L) continue
            val syllables = tokenize(lines[index].text).count { it.isPronounced }
            if (syllables < 2) continue
            perSyllable += (span - breathFor(span)).coerceAtLeast(200L).toFloat() / syllables
        }
        if (perSyllable.size < 5) return SongTempo(null, null)
        val sorted = perSyllable.sorted()
        val typical = sorted[(sorted.size * 0.50f).toInt().coerceIn(0, sorted.size - 1)]
        val upper = sorted[(sorted.size * 0.70f).toInt().coerceIn(0, sorted.size - 1)]
        return SongTempo(
            typicalMs = typical.coerceIn(140f, 900f),
            maxMs = (upper * 1.5f).coerceIn(300f, 900f),
        )
    }

    /** 全曲语速；字段为 `null` 表示样本不足，不要拿它做判断。 */
    private data class SongTempo(val typicalMs: Float?, val maxMs: Float?)

    /**
     * 把 [total] 毫秒**整数**分配给各权重，保证：
     * ① 每段 ≥ 自己的 [mins]；② 总和**严格等于** total。
     *
     * 旧的 `max(35L, …)` 兜底正是「末字越过下一行」的根因：缩放后逐条抬到 35ms
     * 会让总和超过窗口，而末尾断点仍按窗口长度写死，于是最后一个字反向穿过上一行。
     * 这里改用最大余数法（Hamilton）：先扣保底，剩余按精确比例向下取整，
     * 再把剩下的毫秒逐条发给小数部分最大的那几个 —— 总和恒等，不会溢出。
     */
    private fun apportion(total: Long, weights: List<Float>, mins: List<Long>): LongArray {
        val n = weights.size
        val out = LongArray(n)
        if (n == 0) return out
        val reserved = mins.sum()
        if (total <= reserved) {
            // 窗口比保底还短（超密集 rap / 极短跨度）：等比压低保底，保证总和仍然恒等，
            // 而不是把每段抬到 80ms 把整行顶出下一行 —— 老代码就是这么越界的。
            val scale = if (reserved > 0L) total.toDouble() / reserved else 0.0
            var assigned = 0L
            for (i in 0 until n) {
                out[i] = floor(mins[i] * scale).toLong()
                assigned += out[i]
            }
            var rest = total - assigned
            var i = 0
            while (rest > 0 && n > 0) {
                out[i % n]++
                rest--
                i++
            }
            return out
        }
        val free = total - reserved
        var weightSum = 0.0
        for (w in weights) if (w > 0f) weightSum += w
        if (weightSum <= 0.0) {
            for (i in 0 until n) out[i] = mins[i]
            return out
        }

        val fraction = DoubleArray(n) { -1.0 }
        var assigned = 0L
        for (i in 0 until n) {
            if (weights[i] <= 0f) {
                out[i] = mins[i]
                continue
            }
            val exact = free.toDouble() * weights[i] / weightSum
            val whole = floor(exact).toLong()
            out[i] = mins[i] + whole
            fraction[i] = exact - whole
            assigned += whole
        }
        var rest = free - assigned
        while (rest > 0) {
            var best = -1
            var bestFraction = -1.0
            for (i in 0 until n) {
                if (fraction[i] > bestFraction) {
                    bestFraction = fraction[i]
                    best = i
                }
            }
            if (best < 0) break
            out[best]++
            fraction[best] = -1.0
            rest--
        }
        return out
    }

    // ============ 全曲统计 ============

    /** 全曲音节密度（音节/秒）：用来判断「这是快歌还是慢歌」。 */
    private fun globalDensity(lines: List<SyncedLyricLine>): Float {
        if (lines.isEmpty()) return 2.5f
        val total = lines.sumOf { line -> tokenize(line.text).count { it.isPronounced } }
        val first = lines.first().time
        val last = lines.last().time.coerceAtLeast(first + 10_000L)
        return total.toFloat() / ((last - first) / 1000f).coerceAtLeast(1f)
    }

    /**
     * 从**同一首歌里已经带真逐字的行**反解「每个字平均多少毫秒」。
     *
     * 这是本合成器唯一会「看数据」的地方：真逐字行的字时长是歌手唱出来的，
     * 拿它的中位数当锚点，比任何写死的常数都更能贴合这一首的速度。
     * 少于 3 行样本时返回 null（不猜）。
     */
    private fun calibrateMsPerToken(lines: List<SyncedLyricLine>): Float? {
        val samples = lines.mapNotNull { line ->
            val words = line.words
            if (words.size < 2) return@mapNotNull null
            val span = (words.last().endTime - words.first().beginTime).toFloat()
            if (span <= 0f) null else span / words.size
        }
        if (samples.size < 3) return null
        val sorted = samples.sorted()
        return sorted[sorted.size / 2].coerceIn(120f, 900f)
    }

    // ============ 切分 ============

    /** 行内微气口的触发字符（句末停顿交给行跨度，不在这里算）。 */
    private val CAESURA_PUNCT = setOf('，', ',', '、', '；', ';', '：', ':', '…', '—', '－', '-')

    /**
     * 把行文本切成歌唱 token。
     *
     * 三条硬约束：
     * 1. **守恒**：`tokenize(s).joinToString("") { it.text } == s`
     *    （空格、标点、行首空白一个都不能丢 —— 旧实现会吃掉行首空格与连续空格）；
     * 2. 标点**不单独成段**，贴在上一字末尾（否则渲染时那一段没有字形），
     *    但它携带的 [LyricToken.pauseAfterMs] 会变成字后的暗缝；
     * 3. 促音 / 长音单独成 token：它们各占一拍，但一个是静默、一个是延音。
     */
    internal fun tokenize(text: String): List<LyricToken> {
        val out = mutableListOf<LyricToken>()
        val pending = StringBuilder()
        var i = 0
        val len = text.length

        fun emit(token: LyricToken) {
            if (pending.isEmpty()) {
                out += token
            } else {
                out += token.copy(text = pending.toString() + token.text)
                pending.clear()
            }
        }

        fun attachToPrevious(char: Char, pauseMs: Long = 0L) {
            if (out.isEmpty()) {
                emit(LyricToken(char.toString(), TokenKind.PUNCT, pauseMs))
                return
            }
            val last = out.removeAt(out.lastIndex)
            out += last.copy(
                text = last.text + char,
                pauseAfterMs = max(last.pauseAfterMs, pauseMs),
            )
        }

        while (i < len) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    if (out.isEmpty()) {
                        pending.append(c)
                    } else {
                        val last = out.removeAt(out.lastIndex)
                        out += last.copy(text = last.text + c)
                    }
                    i++
                }

                c.isLetterOrDigit() && c.code < 0x3000 -> {
                    val from = i
                    while (i < len && text[i].code < 0x3000 &&
                        (text[i].isLetterOrDigit() || text[i] == '\'' || text[i] == '’')
                    ) {
                        i++
                    }
                    emit(LyricToken(text.substring(from, i), TokenKind.LATIN))
                }

                c == 'っ' || c == 'ッ' -> {
                    emit(LyricToken(c.toString(), TokenKind.SOKUON))
                    i++
                }

                c == 'ー' || c == '~' || c == '～' -> {
                    emit(LyricToken(c.toString(), TokenKind.CHOON))
                    i++
                }

                PhoneticPriors.isJapaneseKana(c) -> {
                    if (i + 1 < len && PhoneticPriors.isSmallKana(text[i + 1])) {
                        emit(LyricToken(text.substring(i, i + 2), TokenKind.KANA))
                        i += 2
                    } else {
                        emit(LyricToken(c.toString(), TokenKind.KANA))
                        i++
                    }
                }

                (c in '\u4E00'..'\u9FFF') || (c in '\u3400'..'\u4DBF') || (c in '\uAC00'..'\uD7A3') -> {
                    emit(LyricToken(c.toString(), TokenKind.HAN))
                    i++
                }

                c in CAESURA_PUNCT -> {
                    attachToPrevious(c, CAESURA_MS)
                    i++
                }

                else -> {
                    attachToPrevious(c)
                    i++
                }
            }
        }
        if (pending.isNotEmpty()) out += LyricToken(pending.toString(), TokenKind.PUNCT)
        return out
    }
}

/** 歌唱 token 的类别：决定时间怎么算，不影响渲染。 */
internal enum class TokenKind {
    /** 汉字（含韩文汉字） */
    HAN,

    /** 假名（拗音已合并） */
    KANA,

    /** 拉丁字母 / 数字词 */
    LATIN,

    /** 促音 っ / ッ：占一拍但静默 */
    SOKUON,

    /** 长音符 ー / ～：占一拍且是延音 */
    CHOON,

    /** 标点等不发音的字符（贴在上一字末尾） */
    PUNCT,
}

/**
 * 一个歌唱 token。
 *
 * @param pauseAfterMs 该 token 之后的气口（标点注入），会把字形与下一字拉开一道暗缝。
 */
internal data class LyricToken(
    val text: String,
    val kind: TokenKind,
    val pauseAfterMs: Long = 0L,
) {
    val isPronounced: Boolean get() = kind != TokenKind.PUNCT
}
