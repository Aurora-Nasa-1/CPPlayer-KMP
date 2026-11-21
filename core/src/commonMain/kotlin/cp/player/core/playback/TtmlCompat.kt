package cp.player.core.playback

/**
 * TTML 兼容层：把「语法合法、但第三方解析器读不动」的 TTML 归一化成规范子集。
 *
 * ### 为什么需要它
 * 歌词库的 `TTMLParser` 只按 Apple Music / AMLL 的产出来写，实测下面这些**真实存在的写法**
 * 会被它静默丢弃（整份歌词 0 行，播放页于是转去在线来源或显示「暂无歌词」）：
 *
 * | 写法 | 库的行为 |
 * |---|---|
 * | `<p begin="1.0">…</p>`（没有 `end`） | `parseSingleLine` 直接返回 null ⇒ 整行丢失 |
 * | `<span>` 只写了 `begin`，`end` 在下一个 span 上 | 该音节被跳过，文字一起消失 |
 * | `<p>` 里只有裸文本（没有 `<span>`） | 空白被压成空串 ⇒ `hello world` 变 `helloworld` |
 * | 时间写 `1500ms` / `00:00:01:15`（帧） | `parseAsTime` 只认 `mm:ss(.xxx)` 与 `h:mm:ss` |
 * | 数字字符引用 `&#39;` / `&#x27;` | 只解命名实体 ⇒ 歌词里原样显示 `&#39;` |
 * | 根节点没写 `xmlns` | `canParse` 要求命名空间，`AutoParser` 不会选它 |
 *
 * 这里的做法不是重写解析器，而是**在解析前补全结构**：
 * 1. 宽容 XML 解析（引号内的 `>`、CDATA、注释、DOCTYPE、自闭合标签）；
 * 2. 按 TTML 的时间语义把 `begin`/`end`/`dur` 解析成绝对毫秒（父节点偏移 + 自身偏移）；
 * 3. 缺 `begin`/`end` 的 `p` / `span` 用相邻节点与父范围补齐；
 * 4. 纯文本行包成「覆盖整行的一个 span」，既保住空格，又保持行级（非逐字）语义；
 * 5. 重新序列化成带命名空间、绝对时间的 TTML。
 *
 * 归一化失败（不是 TTML、结构坏到无法恢复）返回 null，调用方仍可回退到原始文本。
 */
internal object TtmlCompat {

    /**
     * 数字字符引用（`&#39;` / `&#x27;`）解码。
     *
     * 歌词库的 `decodeXmlEntities` **只解命名实体**（`&amp;` `&apos;` …），
     * 而 AMLL 词库里确有作者用 `&#39;` 写撇号 —— 原本会原样显示成 `It&#39;s`。
     */
    fun decodeNumericEntities(lines: List<SyncedLyricLine>): List<SyncedLyricLine> {
        if (lines.isEmpty()) return lines
        fun needsDecode(raw: String?): Boolean = raw?.contains("&#") == true
        if (lines.none {
                needsDecode(it.text) || needsDecode(it.translation) ||
                    needsDecode(it.romanization) || it.words.any { word -> needsDecode(word.text) }
            }
        ) return lines

        fun decode(raw: String?): String? = raw?.let { text ->
            NUMERIC_ENTITY.replace(text) { match ->
                val body = match.groupValues[1]
                when {
                    body.startsWith("#x", ignoreCase = true) ->
                        body.substring(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value
                    body.startsWith("#") ->
                        body.substring(1).toIntOrNull()?.let(::codePointToString) ?: match.value
                    else -> match.value
                }
            }
        }
        return lines.map { line ->
            line.copy(
                text = decode(line.text).orEmpty(),
                translation = decode(line.translation),
                romanization = decode(line.romanization),
                words = line.words.map { it.copy(text = decode(it.text).orEmpty()) },
            )
        }
    }

    /**
     * @param raw 原始 TTML 文本（可带 BOM / XML 声明 / 前置垃圾）
     * @param durationMs 曲目时长，仅在文件自身没有任何结束时间可用时兜底；0 表示未知
     * @return 规范化后的 TTML；无法识别时返回 null
     */
    fun normalize(raw: String, durationMs: Long = 0L): String? {
        val text = sanitize(raw)
        if (!looksLikeTtml(text)) return null
        val root = runCatching { XmlParser(text).parse() }.getOrNull() ?: return null
        val tt = if (root.localName() == "tt") root else root.firstDescendant("tt") ?: return null
        val body = tt.firstChild("body") ?: return null

        val frameRate = tt.attrAny("frameRate", "ttp:frameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 30.0
        val bodyBegin = timeAttr(body, "begin", 0L, Clock(frameRate)) ?: 0L
        // `end` 是相对**父节点**的时间偏移，不是相对自身 `begin`。
        // 例如 body begin=1s、p begin=1500ms、p end=2.5s：p 的终点是 1s+2.5s=3.5s，
        // 而不是 p 起点 2.5s 再叠 2.5s。
        val bodyEnd = timeAttr(body, "end", 0L, Clock(frameRate))
            ?: durationAttr(body, "dur", frameRate)?.let { bodyBegin + it }
            ?: 0L
        val globalEnd = listOf(durationMs, bodyEnd).maxOrNull()?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val clock = Clock(frameRate, durationMs)

        val rawLines = mutableListOf<RawLine>()
        collectLines(body, bodyBegin, bodyEnd.takeIf { it > bodyBegin } ?: globalEnd, clock, rawLines)
        if (rawLines.isEmpty()) return null

        // 顺序推导：缺 begin 的行接在上一条的 **end** 之后。
        // 不能只记上一条的 begin —— 那会让连续字幕整体重叠。
        var previousEnd = bodyBegin
        for (i in rawLines.indices) {
            val line = rawLines[i]
            val explicitBegin = timeAttr(line.node, "begin", line.containerBegin, clock)
                ?: firstChildBegin(line.node, line.containerBegin, clock)
            val begin = (explicitBegin ?: previousEnd).coerceAtLeast(0L)
            line.begin = begin
            // end 优先级：显式 end → dur → 最后一个子 span 的 end → 下一行 begin → 容器/曲目末尾。
            val explicitEnd = timeAttr(line.node, "end", line.containerBegin, clock)
            val dur = durationAttr(line.node, "dur", frameRate)
            val childEnd = lastChildEnd(line.node, begin, clock)
            val nextBegin = rawLines.getOrNull(i + 1)
                ?.let { timeAttr(it.node, "begin", it.containerBegin, clock) }
                ?.takeIf { it > begin }
            val containerEnd = line.containerEnd.takeIf { it > begin && it != Long.MAX_VALUE }
            val end = explicitEnd
                ?: dur?.let { begin + it }
                ?: childEnd
                ?: nextBegin
                ?: containerEnd
                ?: begin + DEFAULT_LINE_MS
            val clamped = if (globalEnd > begin && end > globalEnd) globalEnd else end
            line.end = if (clamped > begin) clamped else begin + DEFAULT_LINE_MS
            previousEnd = line.end
        }

        for (line in rawLines) {
            normalizeLine(line, clock)
        }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<tt xmlns=\"http://www.w3.org/ns/ttml\"")
            append(" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"")
            append(" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"")
            append(" xmlns:amll=\"http://www.example.com/ns/amll\">\n")
            tt.firstChild("head")?.let { head ->
                append("  ")
                appendNode(head)
                append('\n')
            }
            append("  <body dur=\"").append(formatTime(rawLines.maxOf { it.end })).append("\">\n")
            append("    <div begin=\"").append(formatTime(rawLines.minOf { it.begin }))
            append("\" end=\"").append(formatTime(rawLines.maxOf { it.end })).append("\">\n")
            for (line in rawLines) {
                append("      ")
                appendLine(line)
                append('\n')
            }
            append("    </div>\n")
            append("  </body>\n")
            append("</tt>")
        }
    }

    // ============ 行/span 归一化 ============

    private fun collectLines(
        node: Node,
        containerBegin: Long,
        containerEnd: Long,
        clock: Clock,
        out: MutableList<RawLine>,
    ) {
        for (child in node.childNodes()) {
            when (child.localName()) {
                "div" -> {
                    val begin = timeAttr(child, "begin", containerBegin, clock) ?: containerBegin
                    val end = timeAttr(child, "end", containerBegin, clock)
                        ?: durationAttr(child, "dur", clock.frameRate)?.let { begin + it }
                        ?: containerEnd
                    collectLines(child, begin, end, clock, out)
                }
                "p" -> out += RawLine(child, containerBegin, containerEnd)
                else -> Unit
            }
        }
    }

    private fun normalizeLine(line: RawLine, clock: Clock) {
        val node = line.node
        val spans = node.childNodes().filter { it.localName() == "span" }
        // 没有任何结构（或只有不带时间的 span）：整行作为**一行**输出。
        // 包成单个覆盖全行的 span，库才会走 syllable 分支，从而不把空白压掉；
        // 单个 syllable 覆盖整行时，转换层会把它还原成行级歌词（不误开逐字样式）。
        if (spans.none { !isAnnotation(it) && (it.hasTimingAttribute() || roleOf(it) != null) }) {
            val text = node.allText(exclude = ::isAnnotation).trim()
            // 整行包一个 span 会丢掉行内标点间的空格（`hello, world` → `hello,world`）；
            // 把空白折成单空格即可保留可读性，且不影响行级语义。
            val visible = text.replace(WHITESPACE, " ")
            val span = Node("span").apply {
                attrs += "begin" to formatTime(line.begin)
                attrs += "end" to formatTime(line.end)
                if (visible.isNotEmpty()) children += visible
            }
            line.node.children.clear()
            line.node.children += span
            line.node.children.addAll(spans.filter(::isAnnotation))
            line.spanTimings[span] = longArrayOf(line.begin, line.end)
            return
        }

        // 混合内容：把裸文本并进前一个 span（与库「span 后的文本节点并入该 span」一致），
        // 行首/行尾的文本单独包 span，避免被丢掉。
        val rebuilt = mutableListOf<Any>()
        var lastSpan: Node? = null
        for (child in node.children) {
            if (child is String) {
                if (child.isEmpty()) continue
                if (child.isBlank()) {
                    val target = lastSpan
                    if (target != null && roleOf(target) == null) {
                        target.children += child
                    }
                    continue
                }
                val target = lastSpan
                if (target != null && roleOf(target) == null) {
                    target.children += child
                } else {
                    val begin = target?.let { line.spanTimings[it]?.get(1) } ?: line.begin
                    val span = Node("span").apply {
                        attrs += "begin" to formatTime(begin)
                        attrs += "end" to formatTime(line.end)
                        children += child
                    }
                    rebuilt += span
                    line.spanTimings[span] = longArrayOf(begin, line.end)
                    lastSpan = span
                }
            } else if (child is Node) {
                rebuilt += child
                if (child.localName() == "span") lastSpan = child
            }
        }
        node.children.clear()
        node.children.addAll(rebuilt)

        // 给每个 span 定绝对时间；嵌套（x-bg）按父 span 的范围继续。
        val directSpans = node.childNodes().filter { it.localName() == "span" }
        var previousEnd = line.begin
        for ((index, span) in directSpans.withIndex()) {
            val role = roleOf(span)
            val isPrimary = role == null || role == "x-bg"
            val begin = if (role == "x-translation" || role == "x-roman") {
                line.begin
            } else {
                timeAttr(span, "begin", line.begin, clock) ?: previousEnd
            }
            val nextBegin = directSpans.drop(index + 1)
                .firstOrNull { roleOf(it) == null || roleOf(it) == "x-bg" }
                ?.let { timeAttr(it, "begin", line.begin, clock) }
                ?.takeIf { it > begin }
            val end = if (role == "x-translation" || role == "x-roman") {
                line.end
            } else {
                timeAttr(span, "end", line.begin, clock)
                    ?: durationAttr(span, "dur", clock.frameRate)?.let { begin + it }
                    ?: nestedLastEnd(span, begin, clock)
                    ?: nextBegin
                    ?: line.end
            }
            val safeEnd = if (end > begin) end else line.end.takeIf { it > begin } ?: (begin + DEFAULT_LINE_MS)
            line.spanTimings[span] = longArrayOf(begin, safeEnd)
            if (isPrimary) previousEnd = safeEnd
            normalizeNestedSpans(span, begin, safeEnd, clock, line)
        }
    }

    /** `x-bg` 这类嵌套 span：子 span 的 `begin` 相对外层 span 的起点。 */
    private fun normalizeNestedSpans(
        parent: Node,
        parentBegin: Long,
        parentEnd: Long,
        clock: Clock,
        line: RawLine,
    ) {
        val children = parent.childNodes().filter { it.localName() == "span" }
        if (children.isEmpty()) return
        var previousEnd = parentBegin
        for ((index, span) in children.withIndex()) {
            if (roleOf(span) != null) continue
            val begin = timeAttr(span, "begin", parentBegin, clock) ?: previousEnd
            val nextBegin = children.drop(index + 1)
                .firstOrNull { roleOf(it) == null }
                ?.let { timeAttr(it, "begin", parentBegin, clock) }
                ?.takeIf { it > begin }
            val end = timeAttr(span, "end", parentBegin, clock)
                ?: durationAttr(span, "dur", clock.frameRate)?.let { begin + it }
                ?: nextBegin
                ?: parentEnd
            val safeEnd = if (end > begin) end else parentEnd
            line.spanTimings[span] = longArrayOf(begin, safeEnd)
            previousEnd = safeEnd
            normalizeNestedSpans(span, begin, safeEnd, clock, line)
        }
    }

    private fun lastChildEnd(node: Node, parentBegin: Long, clock: Clock): Long? {
        var max = Long.MIN_VALUE
        for (span in node.childNodes().filter { it.localName() == "span" && !isAnnotation(it) }) {
            val begin = timeAttr(span, "begin", parentBegin, clock)
            val end = timeAttr(span, "end", parentBegin, clock)
                ?: durationAttr(span, "dur", clock.frameRate)?.let { (begin ?: parentBegin) + it }
            if (end != null && end > max) max = end
            nestedLastEnd(span, begin ?: parentBegin, clock)?.let { if (it > max) max = it }
        }
        return max.takeIf { it != Long.MIN_VALUE }
    }

    /** 行本身缺 `begin` 时，用第一个带时间的子 span 推断（真实歌词文件常见）。 */
    private fun firstChildBegin(node: Node, parentBegin: Long, clock: Clock): Long? =
        node.childNodes()
            .asSequence()
            .filter { it.localName() == "span" && roleOf(it) == null }
            .firstNotNullOfOrNull { timeAttr(it, "begin", parentBegin, clock) }

    private fun nestedLastEnd(node: Node, parentBegin: Long, clock: Clock): Long? {
        var max = Long.MIN_VALUE
        for (span in node.childNodes().filter { it.localName() == "span" && !isAnnotation(it) }) {
            val begin = timeAttr(span, "begin", parentBegin, clock) ?: parentBegin
            val end = timeAttr(span, "end", parentBegin, clock)
                ?: durationAttr(span, "dur", clock.frameRate)?.let { begin + it }
            if (end != null && end > max) max = end
            nestedLastEnd(span, begin, clock)?.let { if (it > max) max = it }
        }
        return max.takeIf { it != Long.MIN_VALUE }
    }

    // ============ 序列化 ============

    private fun StringBuilder.appendLine(line: RawLine) {
        append("<p")
        appendTimeAttrs(line.begin, line.end)
        appendOtherAttrs(line.node)
        append('>')
        appendChildren(line.node, line)
        append("</p>")
    }

    private fun StringBuilder.appendChildren(node: Node, line: RawLine) {
        for (child in node.children) {
            if (child is String) {
                append(escape(child))
                continue
            }
            child as Node
            append('<').append(child.name)
            val timing = line.spanTimings[child]
            if (timing != null) appendTimeAttrs(timing[0], timing[1])
            appendOtherAttrs(child)
            append('>')
            appendChildren(child, line)
            append("</").append(child.name).append('>')
        }
    }

    private fun StringBuilder.appendNode(node: Node) {
        append('<').append(node.name)
        for ((name, value) in node.attrs) {
            append(' ').append(name).append("=\"").append(escape(value)).append('"')
        }
        append('>')
        for (child in node.children) {
            if (child is String) append(escape(child)) else appendNode(child as Node)
        }
        append("</").append(node.name).append('>')
    }

    private fun StringBuilder.appendTimeAttrs(begin: Long, end: Long) {
        append(" begin=\"").append(formatTime(begin)).append("\" end=\"").append(formatTime(end)).append('"')
    }

    private fun StringBuilder.appendOtherAttrs(node: Node) {
        for ((name, value) in node.attrs) {
            val local = name.substringAfterLast(':')
            if (local == "begin" || local == "end" || local == "dur") continue
            append(' ').append(name).append("=\"").append(escape(value)).append('"')
        }
    }

    // ============ 时间 ============

    private fun timeAttr(node: Node, name: String, parentBegin: Long, clock: Clock): Long? {
        val raw = node.attr(name) ?: return null
        val parsed = parseTime(raw, clock.frameRate) ?: return null
        return if (parsed.absolute) parsed.ms else parentBegin + parsed.ms
    }

    private fun durationAttr(node: Node, name: String, frameRate: Double): Long? {
        val raw = node.attr(name) ?: return null
        val parsed = parseTime(raw, frameRate) ?: return null
        return parsed.ms
    }

    private class Clock(val frameRate: Double, val durationMs: Long = 0L)

    private class ParsedTime(val absolute: Boolean, val ms: Long)

    /**
     * TTML 时间表达式：
     * - 带冒号的是**绝对时钟**（`mm:ss` / `h:mm:ss` / `h:mm:ss:ff`）；
     * - 带单位的是**相对偏移**（`500ms` / `1.5s` / `2m` / `1h` / `15f`）；
     * - 裸数字按**绝对秒**处理。
     *
     * ⚠️ 裸数字不能按偏移处理：AMLL / Apple Music 的真实文件里
     * `<span begin="11.357">` 是绝对 11.357 秒，若叠加父节点起点会整体错位
     * （一行 8.7 秒开始，子 span 会被算到 20 秒）。库的 `parseAsTime` 也按绝对秒读。
     */
    private fun parseTime(raw: String, frameRate: Double): ParsedTime? {
        val s = raw.trim().replace(',', '.')
        if (s.isEmpty()) return null
        val lower = s.lowercase()
        fun offset(scale: Double): ParsedTime? =
            lower.dropLast(1).toDoubleOrNull()?.let { ParsedTime(false, (it * scale).toLong()) }

        when {
            lower.endsWith("ms") -> return lower.dropLast(2).toDoubleOrNull()?.let { ParsedTime(false, it.toLong()) }
            lower.endsWith("s") -> return offset(1_000.0)
            lower.endsWith("h") -> return offset(3_600_000.0)
            lower.endsWith("m") -> return offset(60_000.0)
            lower.endsWith("f") -> return offset(1_000.0 / frameRate)
        }
        if (':' in s) return parseClock(s, frameRate)?.let { ParsedTime(true, it) }
        return s.toDoubleOrNull()?.let { ParsedTime(true, (it * 1_000.0).toLong()) }
    }

    private fun parseClock(raw: String, frameRate: Double): Long? {
        val parts = raw.split(':')
        if (parts.size !in 2..4) return null
        val numbers = parts.map { it.toDoubleOrNull() ?: return null }
        val hours: Double
        val minutes: Double
        val seconds: Double
        var frames = 0.0
        when (parts.size) {
            2 -> { hours = 0.0; minutes = numbers[0]; seconds = numbers[1] }
            3 -> { hours = numbers[0]; minutes = numbers[1]; seconds = numbers[2] }
            else -> {
                hours = numbers[0]; minutes = numbers[1]; seconds = numbers[2]
                frames = numbers[3]
            }
        }
        val ms = (hours * 3_600_000.0 + minutes * 60_000.0 + seconds * 1_000.0 + frames / frameRate * 1_000.0)
        return ms.toLong().takeIf { it >= 0 }
    }

    private fun formatTime(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val totalSeconds = total / 1_000L
        val millis = total % 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        val h = if (hours > 0) hours.toString().padStart(2, '0') + ":" else ""
        return h + minutes.toString().padStart(2, '0') + ":" +
            seconds.toString().padStart(2, '0') + "." +
            millis.toString().padStart(3, '0')
    }

    // ============ XML ============

    private fun sanitize(raw: String): String {
        var text = raw.removePrefix("\uFEFF").replace("\u0000", "")
        val start = TT_OPEN.find(text)?.range?.first ?: return text.trim()
        if (start > 0) text = text.substring(start)
        return text.trim()
    }

    private fun looksLikeTtml(text: String): Boolean = TT_OPEN.containsMatchIn(text)

    private class RawLine(
        val node: Node,
        val containerBegin: Long,
        val containerEnd: Long,
    ) {
        var begin: Long = 0L
        var end: Long = 0L
        val spanTimings: MutableMap<Node, LongArray> = mutableMapOf()
    }

    private class Node(val name: String) {
        val attrs = mutableListOf<Pair<String, String>>()
        /** 子节点：`String`（文本）或 [Node]。 */
        val children = mutableListOf<Any>()

        fun localName(): String = name.substringAfterLast(':')

        fun childNodes(): List<Node> = children.filterIsInstance<Node>()

        fun firstChild(local: String): Node? =
            childNodes().firstOrNull { it.localName() == local }

        fun firstDescendant(local: String): Node? {
            for (child in childNodes()) {
                if (child.localName() == local) return child
                child.firstDescendant(local)?.let { return it }
            }
            return null
        }

        fun attr(name: String): String? = attrs.firstOrNull { it.first == name }?.second

        fun attrAny(vararg names: String): String? =
            names.firstNotNullOfOrNull { attr(it) }

        fun hasTimingAttribute(): Boolean =
            attrs.any { it.first.substringAfterLast(':') in setOf("begin", "end", "dur") }

        fun allText(exclude: (Node) -> Boolean): String = buildString {
            for (child in children) {
                if (child is String) append(child)
                else if (child is Node && !exclude(child)) append(child.allText(exclude))
            }
        }
    }

    /**
     * 宽容 XML 解析器。
     *
     * 只做「拿到元素树」这件事，不做命名空间校验与 DTD 处理 —— TTML 是纯数据文档，
     * 这里的容错度换来的正是那些不规范但能播的文件。
     */
    private class XmlParser(private val input: String) {

        fun parse(): Node? {
            val stack = ArrayDeque<Node>()
            var root: Node? = null
            val text = StringBuilder()
            var i = 0

            fun flushText() {
                if (text.isEmpty()) return
                // 文本节点里的 `&apos;` / `&#39;` 必须在这里解码一次；
                // 序列化时再统一转义，否则会变成 `&amp;apos;`，最终原样显示给用户。
                stack.lastOrNull()?.let { it.children += decodeEntities(text.toString()) }
                text.clear()
            }

            while (i < input.length) {
                val lt = input.indexOf('<', i)
                if (lt < 0) {
                    text.append(input, i, input.length)
                    break
                }
                if (lt > i) text.append(input, i, lt)
                when {
                    input.startsWith("<!--", lt) -> {
                        flushText()
                        val end = input.indexOf("-->", lt + 4)
                        i = if (end < 0) input.length else end + 3
                    }
                    input.startsWith("<![CDATA[", lt) -> {
                        flushText()
                        val end = input.indexOf("]]>", lt + 9)
                        val stop = if (end < 0) input.length else end
                        text.append(input, lt + 9, stop)
                        i = if (end < 0) input.length else end + 3
                    }
                    input.startsWith("<?", lt) -> {
                        flushText()
                        val end = input.indexOf("?>", lt + 2)
                        i = if (end < 0) input.length else end + 2
                    }
                    input.startsWith("<!", lt) -> {
                        flushText()
                        i = tagEnd(input, lt + 2)?.plus(1) ?: input.length
                    }
                    input.startsWith("</", lt) -> {
                        flushText()
                        val gt = input.indexOf('>', lt + 2)
                        if (gt < 0) break
                        val name = input.substring(lt + 2, gt).trim().substringBefore(' ')
                        while (stack.isNotEmpty()) {
                            val popped = stack.removeLast()
                            if (popped.name == name || popped.localName() == name.substringAfterLast(':')) break
                        }
                        i = gt + 1
                    }
                    else -> {
                        flushText()
                        val gt = tagEnd(input, lt + 1) ?: break
                        val rawTag = input.substring(lt + 1, gt)
                        val selfClosing = rawTag.endsWith("/")
                        val content = if (selfClosing) rawTag.dropLast(1) else rawTag
                        val node = parseTag(content) ?: run { i = gt + 1; continue }
                        if (stack.isEmpty()) {
                            if (root == null) root = node
                        } else {
                            stack.last().children += node
                        }
                        if (!selfClosing) stack.addLast(node)
                        i = gt + 1
                    }
                }
            }
            return root
        }

        private fun parseTag(raw: String): Node? {
            var p = 0
            while (p < raw.length && raw[p].isWhitespace()) p++
            val nameStart = p
            while (p < raw.length && !raw[p].isWhitespace()) p++
            if (p <= nameStart) return null
            val node = Node(raw.substring(nameStart, p))
            while (p < raw.length) {
                while (p < raw.length && raw[p].isWhitespace()) p++
                if (p >= raw.length) break
                val attrStart = p
                while (p < raw.length && raw[p] != '=' && !raw[p].isWhitespace()) p++
                if (p <= attrStart) { p++; continue }
                val name = raw.substring(attrStart, p)
                while (p < raw.length && raw[p].isWhitespace()) p++
                if (p >= raw.length || raw[p] != '=') {
                    node.attrs += name to ""
                    continue
                }
                p++
                while (p < raw.length && raw[p].isWhitespace()) p++
                if (p >= raw.length) { node.attrs += name to ""; break }
                val quote = raw[p]
                val value = if (quote == '"' || quote == '\'') {
                    p++
                    val end = raw.indexOf(quote, p)
                    val stop = if (end < 0) raw.length else end
                    val v = raw.substring(p, stop)
                    p = if (end < 0) raw.length else end + 1
                    v
                } else {
                    val start = p
                    while (p < raw.length && !raw[p].isWhitespace()) p++
                    raw.substring(start, p)
                }
                node.attrs += name to decodeEntities(value)
            }
            return node
        }
    }

    /** 找到标签结束的 `>`，跳过引号内的 `>`。 */
    private fun tagEnd(input: String, from: Int): Int? {
        var quote: Char? = null
        var i = from
        while (i < input.length) {
            val c = input[i]
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == '>' -> return i
            }
            i++
        }
        return null
    }

    /** 根节点可能是 `<tt>`、`<tt:tt>`，也可能紧跟属性（`<tt xmlns=...>`）。 */
    private val TT_OPEN = Regex("""<(?:(\w+):)?tt(?=[\s>])""")
    private val ENTITY = Regex("""&(#x[0-9A-Fa-f]+|#\d+|[A-Za-z][A-Za-z0-9]{1,8});""")
    private val NUMERIC_ENTITY = Regex("""&(#x[0-9A-Fa-f]+|#\d+);""")
    private val WHITESPACE = Regex("""\s+""")

    private fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        return ENTITY.replace(raw) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.substring(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let(::codePointToString) ?: match.value
                else -> when (body.lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> match.value
                }
            }
        }
    }

    private fun codePointToString(codePoint: Int): String {
        if (codePoint <= 0xFFFF) return codePoint.toChar().toString()
        val v = codePoint - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    private fun escape(raw: String): String = buildString(raw.length + 8) {
        for (c in raw) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private fun roleOf(node: Node): String? =
        node.attrs.firstOrNull { it.first == "ttm:role" || it.first == "role" || it.first.endsWith(":role") }?.second

    private fun isAnnotation(node: Node): Boolean =
        when (roleOf(node)) {
            "x-translation", "x-roman" -> true
            else -> false
        }

    private const val DEFAULT_LINE_MS = 4_000L
}
