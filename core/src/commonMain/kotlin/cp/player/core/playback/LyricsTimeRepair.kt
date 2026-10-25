package cp.player.core.playback

/**
 * 歌词时间戳方言的**修复层**。
 *
 * ### 它解决的两种真实故障（都用真实字节实测过）
 *
 * **① `[mm:ss:cc]` 方言被读成 `h:mm:ss` ⇒ 整首歌都停在「前奏」**
 *
 * 有一部分 LRC 把百分秒的分隔符写成了冒号：`[00:12:34]` 意思是 **12.34 秒**。
 * 而歌词库的 `parseAsTime` 按「冒号数量」分派（`javap -c` 核实）：
 * 1 个冒号 ⇒ `mm:ss(.xxx)`，**2 个冒号 ⇒ `h:mm:ss`**。于是 `[00:12:34]`
 * 被算成 **12 分 34 秒**（754_000ms），整份歌词被整体推到**约 60 倍**之后：
 *
 * ```
 * 输入 [00:12:34] → 期望 12_340ms，实际 754_000ms
 * 输入 [03:45:00] → 期望 225_000ms，实际 13_500_000ms（3 小时 45 分）
 * ```
 *
 * 一首 4 分钟的歌，歌词却从 12 分钟才开始 ⇒ `computeLyricIndex` 永远返回 -1，
 * `KaraokeLyricsView` 永远停在「第一行之前」⇒ 用户看到的就是**整首歌都在前奏里**，
 * 一个字都不高亮。这是本条修复存在的主要理由。
 *
 * **② 时间戳乱序 ⇒ 整份歌词被丢弃**
 *
 * 库的解析器对「后一行的首时间戳小于前一行」会抛 `IllegalArgumentException`
 * （"Failed requirement."）。`LyricsParser` 外层是 `runCatching`，于是**一份歌词直接变空**，
 * 上层再去打别的来源（或显示「暂无歌词」）。实测：
 * `[00:20.00]A` 后面跟 `[00:12.34]B` ⇒ 抛异常、0 行。
 * 这类文件在手工编辑过的 LRC 里并不罕见（把副歌复制到前面忘了排序）。
 *
 * ### 为什么不在解析器里「一律改写」
 * 两种方言**语法上完全同形**：`[00:12:34]` 既可能是 12.34 秒，也可能是 12 分 34 秒
 * （一首 20 分钟的组曲就真的会用后者）。所以只在**有独立证据**时才改写：
 *
 * 1. 决定性证据：同一份文件里出现**第三段 ≥ 60** —— `h:mm:ss` 的秒不可能 ≥ 60，
 *    所以这一定是百分秒；
 * 2. 与**曲目时长**不相容：按 `h:mm:ss` 读出来的**最后一行**都超过了整首歌的长度，
 *    而改按 `mm:ss.cc` 读之后**全部落在时长以内** ⇒ 后者显然才是作者的意思。
 *    时长未知（≤0）时**不猜** —— 宁可保持原样，也不要把一份正常的 `h:mm:ss` 文件改坏。
 */
internal object LyricsTimeRepair {

    /** `[mm:ss:cc]` 形状的标签（第三段是 1~2 位数字）。 */
    private val COLON_TAG = Regex("""\[(\d{1,3}):(\d{1,2}):(\d{1,2})]""")

    /** 行首时间戳：`[mm:ss]` / `[mm:ss.xx]` / `[mm:ss:xx]` / `[h:mm:ss]`。 */
    private val LEADING_TAG = Regex("""^\s*\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * 去掉 UTF-8 BOM。
     *
     * 带 BOM 的 `.lrc`（Windows 记事本另存为 UTF-8 的默认产物）会让**第一行**的时间戳
     * 匹配不上（`\uFEFF` 卡在 `[` 前面）⇒ 第一行歌词静默丢失。实测：
     * `\uFEFF[00:12.34]第一行` 只解析出后面那行。放在解析入口统一剥掉即可。
     */
    fun stripBom(raw: String): String = raw.removePrefix("\uFEFF")

    /** 文件里是否存在 `[mm:ss:cc]` 形状的标签。 */
    fun hasColonTag(raw: String): Boolean = COLON_TAG.containsMatchIn(raw)

    /**
     * 是否存在**决定性证据**：某个 `[a:b:c]` 的第三段 ≥ 60。
     *
     * `h:mm:ss` 的秒字段永远 < 60，所以只要出现一次，这份文件就只能是 `mm:ss:cc`。
     */
    fun hasImpossibleSeconds(raw: String): Boolean =
        COLON_TAG.findAll(raw).any { tag ->
            (tag.groupValues[3].toIntOrNull() ?: 0) >= 60
        }

    /**
     * 把 `[mm:ss:cc]` 全部改写成标准的 `[mm:ss.cc]`。
     *
     * 只动**行首方括号里恰好三段数字**的标签；正文里的方括号（`[Chorus]`、`[00:12.34]` 之后的
     * 第二段时间戳）不受影响 —— 后者的 `[` 前面不是行首，正则不匹配。
     */
    fun rewriteColonAsCentiseconds(raw: String): String = COLON_TAG.replace(raw) { m ->
        val mm = m.groupValues[1].padStart(2, '0')
        val ss = m.groupValues[2].padStart(2, '0')
        val cc = m.groupValues[3].padStart(2, '0')
        "[$mm:$ss.$cc]"
    }

    /**
     * 判断这份 LRC 该按哪种方言读，并把要解析的文本返回。
     *
     * @param raw 原始 LRC 文本
     * @param durationMs 曲目时长；≤0 表示未知（此时只认 [hasImpossibleSeconds] 这条硬证据）
     * @param parse 用**当前宿主**的 LRC 解析器解析（注入以便复用 `LyricsParser` 的实现，
     *   也让本模块不依赖歌词库）
     * @return 应当交给解析器的文本：多数情况下就是 [raw] 本身
     */
    fun repairColonDialect(
        raw: String,
        durationMs: Long,
        parse: (String) -> List<SyncedLyricLine>,
    ): String {
        if (!hasColonTag(raw)) return raw
        if (hasImpossibleSeconds(raw)) return rewriteColonAsCentiseconds(raw)
        if (durationMs <= 0L) return raw

        val parsed = parse(raw)
        if (parsed.isEmpty()) return raw
        // 按 h:mm:ss 读，最后一行都还在歌里 ⇒ 这份文件确实就是 h:mm:ss，别动。
        if (parsed.maxOf { it.time } < durationMs) return raw

        val rewritten = rewriteColonAsCentiseconds(raw)
        val alternative = parse(rewritten)
        if (alternative.isEmpty()) return raw
        // 改按 mm:ss.cc 读之后还必须**全部**落在时长以内，否则两边都不可信，保持原样。
        if (alternative.maxOf { it.time } >= durationMs) return raw
        return rewritten
    }

    /**
     * 把打乱时间顺序的 LRC 行按行首时间戳重排；已经有序（或没有可识别的时间戳）时原样返回。
     *
     * 只在**严格解析已经失败**之后作为兜底调用：库遇到逆序会抛异常，整份歌词归零；
     * 重排一次就能救回来，且对正常文件没有任何影响（有序时直接返回原串）。
     *
     * 没有时间戳的行（`[ti:…]` 这类元信息、空行）保持相对顺序、统一排到最前面。
     */
    fun sortLinesByLeadingTimestamp(raw: String): String {
        val lines = raw.split('\n')
        if (lines.size < 2) return raw
        val keys = lines.map(::leadingTimestampMs)
        val tagged = keys.withIndex().filter { it.value != null }
        if (tagged.size < 2) return raw
        val ordered = tagged.mapNotNull { it.value }
        if (ordered.zipWithNext().all { (a, b) -> a <= b }) return raw

        val meta = lines.filterIndexed { i, _ -> keys[i] == null }
        val sorted = tagged.sortedBy { it.value }.map { lines[it.index] }
        return (meta + sorted).joinToString("\n")
    }

    /** 行首时间戳的**排序键**（毫秒）。只用于比较大小，不保证是精确时间。 */
    private fun leadingTimestampMs(line: String): Long? {
        val m = LEADING_TAG.find(line) ?: return null
        val minutes = m.groupValues[1].toLongOrNull() ?: return null
        val seconds = m.groupValues[2].toLongOrNull() ?: return null
        val fractionRaw = m.groupValues[3]
        val fraction = when (fractionRaw.length) {
            0 -> 0L
            1 -> (fractionRaw.toLongOrNull() ?: 0L) * 100
            2 -> (fractionRaw.toLongOrNull() ?: 0L) * 10
            else -> fractionRaw.toLongOrNull() ?: 0L
        }
        return minutes * 60_000L + seconds * 1_000L + fraction
    }
}
