package cp.player.core.lyrics

import kotlin.math.roundToLong

/**
 * 逐字「咬音」策略：把一个 token 展开成时间轴上的**暗区 · 弹性段 · 收势**。
 *
 * ## 为什么是暗区而不是缓动曲线
 * 渲染器（`com.mocharealm.accompanist:lyrics-core`）的 `KaraokeSyllable.progress()` 就是
 * `clamp((now - start) / duration, 0, 1)` —— **线性扫光，没有 easing 参数**。
 * 所以「日语/汉语/英语咬音不同」只能在**时间轴**上表达：
 * - [ArticulatedToken.onsetMs]：字前暗区（声母还没出声，字形不亮）；
 * - [ArticulatedToken.releaseMs]：字尾收势（韵尾/词尾辅音，字形提前停笔）；
 * - [ArticulatedToken.weight]：弹性段权重（元音能拖多长）。
 *
 * 这三件事合起来，就是真实卡拉 OK 最像的那道「暗缝」，而它恰好是线性渲染器能如实画出的。
 *
 * ## 三种语言的差异
 * | | 暗区 | 收势 | 弹性权重 |
 * |---|---|---|---|
 * | 汉语 | 声母按发音部位分 8 档（送气塞音最长、零声母 0） | 无（韵尾已含在韵母里） | 韵母开合 × 声调 × 虚词弱化 |
 * | 日语 | 0（清音起音极短） | 0 | **拍数**（涙=3 拍、木=1 拍）；促音整拍空槽 |
 * | 英语 | 0 | 词尾辅音 25~45ms | 音节重音比（首音节 1.0，其余 0.72） |
 */
internal object KaraokeArticulation {

    /** 汉语声母类 → 辅音耗时基准（ms，正常语速）。索引与 [PinyinTimingTable.initialClass] 对齐。 */
    private val ZH_ONSET_MS = longArrayOf(
        0L,   // 0 零声母：啊/爱/我（y/w 起头）
        45L,  // 1 不送气塞音：b d g
        85L,  // 2 送气塞音：p t k
        65L,  // 3 塞擦音：z c zh ch j q
        75L,  // 4 擦音：f s sh x h
        55L,  // 5 鼻音：m n
        45L,  // 6 边音 / 日：l r
        20L,  // 7 半元音：y w
    )

    /** 汉语韵母类 → 弹性权重基准。索引与 [PinyinTimingTable.finalClass] 对齐。 */
    private val ZH_FINAL_WEIGHT = floatArrayOf(
        1.30f, // 0 大开：a ia ua
        1.15f, // 1 中：o uo e ie
        0.80f, // 2 闭前：i ü
        0.85f, // 3 闭后：u
        1.20f, // 4 前响双元音：ai ei ui
        1.15f, // 5 后响双元音：ao ou iu
        1.05f, // 6 前鼻音：an en in un
        1.10f, // 7 后鼻音：ang eng ing ong
    )

    /**
     * 汉语闭集虚词：歌唱里严重弱化，永远比实词短。
     *
     * 这是**闭集**（几十个语法词），不是「常用字白名单」—— 后者覆盖不到生僻字与繁体，
     * 而语法词是可穷举的。
     */
    private val ZH_WEAK = setOf(
        '的', '地', '得', '了', '着', '过', '在', '是', '有', '和', '与', '跟',
        '就', '都', '也', '还', '又', '把', '被', '给', '让', '使', '这', '那',
        '个', '们', '吗', '呢', '吧', '之', '其', '而', '且', '所', '以', '于',
        '为', '从', '向', '对', '会', '要', '能', '很', '更', '最',
    )

    /** 行末字的延音系数（乐句收束时的自然拉长）。调用方可按歌曲速度覆盖。 */
    private const val LINE_END_LIFT = 1.55f

    /** 英语词尾辅音的收势上限（ms）。 */
    private const val MAX_CODA_MS = 45L

    fun languageOf(text: String): KaraokeLanguage = when {
        text.any { PhoneticPriors.isJapaneseKana(it) } -> KaraokeLanguage.JA
        text.any { it in '\u4E00'..'\u9FFF' } -> KaraokeLanguage.ZH
        else -> KaraokeLanguage.EN
    }

    /**
     * 把 token 流展开成可上时间轴的段。
     *
     * @param rate 语速系数（1.0 = 正常，越大越快）：只压缩辅音与气口，不动元音权重。
     * @param lineEndLift 行末字的延音倍数。慢歌给 1.75（尾音拖得更长），快歌给 1.35（收得干脆）——
     *   三种语言共用同一套规则，只是系数随 [cp.player.core.lyrics.SmartKaraokeLrcSynthesizer]
     *   估出来的全曲语速变。
     */
    fun articulate(
        tokens: List<LyricToken>,
        language: KaraokeLanguage,
        rate: Float,
        lineEndLift: Float = LINE_END_LIFT,
    ): List<ArticulatedToken> {
        val out = mutableListOf<ArticulatedToken>()

        for ((idx, token) in tokens.withIndex()) {
            val pause = (token.pauseAfterMs * rate).roundToLong()
            when {
                !token.isPronounced ->
                    out += ArticulatedToken(token.text, 0L, 0L, 0f, pause)
                token.kind == TokenKind.SOKUON ->
                    // 促音是**一拍静默**：占时间但不点亮（渲染器上表现为 1ms 字形 + 整拍空槽）
                    out += ArticulatedToken(token.text, 0L, 0L, 1f, pause, silent = true)
                token.kind == TokenKind.LATIN ->
                    // 行内夹杂的英文词：按音节切开，词尾辅音留收势
                    out += englishWord(token.text, pause)
                language == KaraokeLanguage.JA && token.kind == TokenKind.HAN ->
                    out += ArticulatedToken(token.text, 0L, 0L, japaneseKanjiWeight(tokens, idx), pause)
                language == KaraokeLanguage.JA ->
                    // 假名 / 长音符：一拍，不改暗区
                    out += ArticulatedToken(token.text, 0L, 0L, 1f, pause)
                language == KaraokeLanguage.ZH && token.kind == TokenKind.HAN ->
                    out += chineseHan(token.text, pause)
                else ->
                    out += ArticulatedToken(token.text, 0L, 0L, 1f, pause)
            }
        }

        // 行末延音：三种语言统一在这里加，避免各分支各自实现导致行为漂移
        val lastVoiced = out.indexOfLast { it.weight > 0f && !it.silent }
        if (lastVoiced >= 0 && lineEndLift != 1f) {
            val tail = out[lastVoiced]
            out[lastVoiced] = tail.copy(weight = tail.weight * lineEndLift)
        }
        return out
    }

    // ============ 汉语 ============

    private fun chineseHan(token: String, pause: Long): ArticulatedToken {
        val ch = token.first()
        val onset = ZH_ONSET_MS[PinyinTimingTable.initialClass(ch)]
        var weight = ZH_FINAL_WEIGHT[PinyinTimingTable.finalClass(ch)]
        // 三声最长（低平调可拖），四声略短（降调收得快），轻声最弱
        weight *= when (PinyinTimingTable.tone(ch)) {
            3 -> 1.10f
            4 -> 1.05f
            0 -> 0.85f
            else -> 1f
        }
        if (ch in ZH_WEAK) weight *= 0.65f
        return ArticulatedToken(token, onset, 0L, weight, pause)
    }

    // ============ 日语 ============

    /**
     * 汉字拍数：后接假名 / 孤字 ⇒ 训读，汉字连写 ⇒ 音读（见 [KanjiMoraTable.moraFor]）。
     * 最长的「長音」与「促音」由 tokenizer 单独成 token，不走这里。
     */
    private fun japaneseKanjiWeight(tokens: List<LyricToken>, idx: Int): Float {
        val token = tokens[idx]
        if (token.text.length != 1) return 1f
        val ch = token.text[0]
        if (!KanjiMoraTable.contains(ch)) return 1f
        val next = tokens.getOrNull(idx + 1)?.text?.firstOrNull()
        val prev = tokens.getOrNull(idx - 1)?.text?.lastOrNull()
        val followedByKana = next != null && PhoneticPriors.isJapaneseKana(next)
        val inRun = (prev != null && isKanji(prev)) || (next != null && isKanji(next))
        return KanjiMoraTable.moraFor(ch, followedByKana, inRun).toFloat()
    }

    private fun isKanji(c: Char): Boolean = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'

    // ============ 英语 ============

    /**
     * 一个英文词 → 若干音节段。
     *
     * 切分：先找元音核（连续元音算一个核，词首 `y` 与夹在辅音间的 `y` 也算元音），
     * 在每个核之后切开，再把纯辅音碎片并回前一段（`forever` → `fo|re|ver`）。
     * **切分结果拼回去必须等于原文**，否则整体退化成一段。
     */
    private fun englishWord(raw: String, pause: Long): List<ArticulatedToken> {
        // 词典命中用词典权重（重读/弱读差一倍以上），否则退回音节数估算
        val total = PhoneticPriors.lexiconWeight(raw)
            ?: maxOf(PhoneticPriors.estimateEnglishSyllables(raw).toFloat() * 1.05f, 1f)
        val pieces = splitEnglishPieces(raw)
        if (pieces.size <= 1) {
            return listOf(ArticulatedToken(raw, 0L, codaMs(raw), total, pause))
        }
        // 词总权重由音节数给出，再按「首音节重读」分配
        val ratios = List(pieces.size) { if (it == 0) 1f else 0.72f }
        val ratioSum = ratios.sum()
        val coda = codaMs(raw)
        return pieces.mapIndexed { i, piece ->
            val last = i == pieces.lastIndex
            ArticulatedToken(
                text = piece,
                onsetMs = 0L,
                releaseMs = if (last) coda else 0L,
                weight = total * ratios[i] / ratioSum,
                pauseMs = if (last) pause else 0L,
            )
        }
    }

    /** 词尾辅音（收势）：以辅音结尾的词，最后一段字形提前停笔。 */
    private fun codaMs(raw: String): Long {
        val letters = raw.trim().lowercase().filter { it in 'a'..'z' }
        if (letters.isEmpty()) return 0L
        val last = letters.last()
        return if (last in "aeiou" || last == 'y' || last == 'w') 0L else MAX_CODA_MS
    }

    internal fun splitEnglishPieces(raw: String): List<String> {
        val body = raw.trimEnd()
        val tail = raw.substring(body.length)
        if (body.length <= 2) return listOf(raw)
        val isVowel = { c: Char -> c.lowercaseChar() in "aeiou" }
        val nuclei = mutableListOf<IntRange>()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            val vowel = isVowel(c) ||
                (c.lowercaseChar() == 'y' && (i == 0 || !isVowel(body[i - 1])))
            if (!vowel) {
                i++
                continue
            }
            var j = i
            while (j + 1 < body.length && isVowel(body[j + 1])) j++
            nuclei += i..j
            i = j + 1
        }
        // 词尾不发音的 e（are / time / love）不是一个音节
        if (nuclei.size > 1) {
            val lastNucleus = nuclei.last()
            if (lastNucleus.first == lastNucleus.last &&
                body[lastNucleus.first].lowercaseChar() == 'e' &&
                lastNucleus.last == body.lastIndex
            ) {
                nuclei.removeAt(nuclei.lastIndex)
            }
        }
        if (nuclei.size <= 1) return listOf(raw)

        val pieces = mutableListOf<String>()
        var from = 0
        for (nucleus in nuclei) {
            val cut = nucleus.last + 1
            if (cut <= from) continue
            pieces += body.substring(from, cut)
            from = cut
        }
        if (from < body.length) pieces += body.substring(from)
        // 纯辅音碎片（如 forever 尾部的 r）并回前一段
        val merged = mutableListOf<String>()
        for (piece in pieces) {
            val consonantOnly = piece.none { isVowel(it) || it.lowercaseChar() == 'y' }
            if (merged.isNotEmpty() && consonantOnly) {
                merged[merged.lastIndex] = merged.last() + piece
            } else {
                merged += piece
            }
        }
        if (merged.isEmpty() || merged.size > 6) return listOf(raw)
        if (merged.joinToString("") != body) return listOf(raw)
        merged[merged.lastIndex] = merged.last() + tail
        return merged
    }
}

/** 逐字段：暗区 + 弹性 + 收势 + 气口。 */
internal data class ArticulatedToken(
    val text: String,
    val onsetMs: Long,
    val releaseMs: Long,
    val weight: Float,
    val pauseMs: Long,
    /** 促音这类「占时间但不点亮」的段。 */
    val silent: Boolean = false,
)

internal enum class KaraokeLanguage { ZH, JA, EN }
