package cp.player.core.lyrics

/**
 * 歌词里的一段文本：[base] 是正文，[reading] 是它上方的注音（假名）。
 *
 * `reading == null` 表示这段没有注音，就是普通文字。
 */
data class RubySegment(
    val base: String,
    val reading: String? = null,
)

/**
 * 歌词注音（ruby / 振り仮名）标记的解析。
 *
 * ## 为什么要自己解析
 *
 * 假名读音没法从汉字「算」出来（同字多音，且没有词典就不能确定），所以注音只能**由歌词
 * 文本自带**。这里支持的写法都是歌词站 / 打轴工具里实际会出现的：
 *
 * | 写法 | 例子 | 说明 |
 * | --- | --- | --- |
 * | 方括号 | `[漢字\|かんじ]` / `{漢字\|かんじ}` | 最明确，正文里出现 `\|` 时优先用它 |
 * | 书名号 | `漢字《かんじ》` | 日文排版惯例 |
 * | 竖线引导 | `｜漢字《かんじ》` | 上面那条的「基准文字」不止汉字时（含假名）用竖线明确起止 |
 * | 括注 | `漢字(かんじ)` / `漢字（かんじ）` | 只在**紧邻汉字**且括号内**全是假名**时才认，避免把歌词里的普通括注吃掉 |
 *
 * ## 判据刻意保守
 *
 * 括注形式最容易误伤（`(Live ver.)` / `（副歌）` 之类），所以它要求：
 * 括号前必须是汉字、括号内必须是纯假名（可含 `ー` `・`）、正文与读音都不过长。
 * 任何一条不满足就原样保留 —— **宁可少注一个音，也不能把歌词本身改坏**。
 *
 * ## 解析是纯函数
 *
 * 不依赖 Compose / 平台，桌面与安卓共用，单测直接跑（见 `RubyTextTest`）。
 */
object RubyText {

    /** 括号内读音的最大长度（假名字数）。超过多半不是读音。 */
    private const val MAX_READING_LEN = 24

    /** 括注形式里「基准汉字」的最大长度。 */
    private const val MAX_BASE_LEN = 12

    /** 这段文本里有没有注音标记。 */
    fun hasRuby(text: String): Boolean = parse(text).any { it.reading != null }

    /** 去掉注音标记后的纯正文（用于测量 / 显示兜底）。 */
    fun strip(text: String): String {
        if (text.isEmpty()) return text
        return parse(text).joinToString("") { it.base }
    }

    /**
     * 解析成「正文 + 读音」的分段序列。
     *
     * 相邻的无注音字符合并成一段（保证结果段数 ≈ 注音处数 + 1，而不是逐字一段）。
     * 没有注音标记时返回**单段**（`reading = null`），调用方可以据此走原来的渲染路径。
     */
    fun parse(text: String): List<RubySegment> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<RubySegment>(4)
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.add(RubySegment(plain.toString()))
                plain.setLength(0)
            }
        }

        fun addRuby(base: String, reading: String) {
            flushPlain()
            out.add(RubySegment(base, reading))
        }

        while (i < text.length) {
            val ch = text[i]

            // ① [base|reading] / {base|reading}
            if (ch == '[' || ch == '{') {
                val close = if (ch == '[') ']' else '}'
                val end = text.indexOf(close, i + 1)
                if (end > i + 1) {
                    val inner = text.substring(i + 1, end)
                    val bar = inner.indexOf('|')
                    if (bar > 0 && bar < inner.length - 1) {
                        val base = inner.substring(0, bar)
                        val reading = inner.substring(bar + 1)
                        if (isValidRuby(base, reading, requireKana = false)) {
                            addRuby(base, reading)
                            i = end + 1
                            continue
                        }
                    }
                }
            }

            // ② 《reading》 —— 基准是前面紧邻的汉字串
            if (ch == '《' || ch == '〈') {
                val close = if (ch == '《') '》' else '〉'
                val end = text.indexOf(close, i + 1)
                if (end > i + 1) {
                    val reading = text.substring(i + 1, end)
                    val base = trailingKanji(plain)
                    if (base.isNotEmpty() && isValidRuby(base, reading, requireKana = true)) {
                        plain.setLength(plain.length - base.length)
                        addRuby(base, reading)
                        i = end + 1
                        continue
                    }
                }
            }

            // ③ ｜base《reading》 —— 竖线明确标出基准起点（基准可以含假名）
            if (ch == '｜' || ch == '|') {
                val end = text.indexOf('》', i + 1)
                val open = if (end > 0) text.lastIndexOf('《', end) else -1
                if (open > i + 1) {
                    val base = text.substring(i + 1, open)
                    val reading = text.substring(open + 1, end)
                    if (isValidRuby(base, reading, requireKana = true)) {
                        addRuby(base, reading)
                        i = end + 1
                        continue
                    }
                }
            }

            // ④ 漢字(かんじ) / 漢字（かんじ）
            if (ch == '(' || ch == '（') {
                val close = if (ch == '(') ')' else '）'
                val end = text.indexOf(close, i + 1)
                if (end > i + 1) {
                    val reading = text.substring(i + 1, end)
                    val base = trailingKanji(plain)
                    if (base.isNotEmpty() && isValidRuby(base, reading, requireKana = true)) {
                        plain.setLength(plain.length - base.length)
                        addRuby(base, reading)
                        i = end + 1
                        continue
                    }
                }
            }

            plain.append(ch)
            i++
        }
        flushPlain()
        return out
    }

    /**
     * 取 [buf] 末尾连续的一串汉字（从后往前扫）。
     *
     * 只认汉字本身：`の` / `を` 之类的假名不会被当成基准 —— 这也是括注形式
     * 不会误吃 `今日(きょう)の歌` 里多余字符的原因（基准止于 `日`）。
     */
    private fun trailingKanji(buf: StringBuilder): String {
        var start = buf.length
        while (start > 0 && isKanji(buf[start - 1])) start--
        if (buf.length - start > MAX_BASE_LEN) return ""
        return buf.substring(start)
    }

    private fun isValidRuby(base: String, reading: String, requireKana: Boolean): Boolean {
        if (base.isEmpty() || reading.isEmpty()) return false
        if (base.length > MAX_BASE_LEN || reading.length > MAX_READING_LEN) return false
        if (base.any { it == '[' || it == ']' || it == '{' || it == '}' || it == '《' || it == '》' }) {
            return false
        }
        if (!requireKana) return true
        // 读音必须是纯假名：含汉字 / 拉丁 / 标点的一律不认（那是普通括注）。
        return reading.all { isKana(it) }
    }

    private fun isKanji(c: Char): Boolean = when (c) {
        in '\u4E00'..'\u9FFF', in '\u3400'..'\u4DBF' -> true
        '々', '〆', 'ヶ', 'ヵ', '〇' -> true
        else -> false
    }

    private fun isKana(c: Char): Boolean = when (c) {
        in '\u3041'..'\u3096', in '\u30A1'..'\u30FA' -> true
        // 长音符 / 中点 / 叠字符：读音里合法，但不单独构成读音。
        'ー', '・', 'ヽ', 'ヾ', 'ゝ', 'ゞ', '　' -> true
        else -> false
    }
}
