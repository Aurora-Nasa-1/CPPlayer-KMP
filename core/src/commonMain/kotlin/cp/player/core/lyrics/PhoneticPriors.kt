package cp.player.core.lyrics

/**
 * 声学先验常数库：只放**没有语料也能站得住**的那部分。
 *
 * 历史说明：本文件前身叫 `OpenUtauTimingProfile`，注释里写着「整合 MFA / OpenUtau /
 * Synthesizer V / CASIA / ESD / MASC / JSUT」，但实际代码是一张手挑的几百字白名单 ——
 * 那种命名会误导后来人以为真的接了口气数据库。现在按职责拆开：
 * - 汉语的声母/韵母/声调 → 生成的 [PinyinTimingTable]（Unihan 全量，不再是白名单）；
 * - 日语汉字拍数 → 生成的 [KanjiMoraTable]（Unihan 全量）；
 * - 只剩「英语音节与高频词重音」「假名判定」这类真·手写先验留在这里。
 *
 * 英语这张表是**高频歌词词表**，不是 CMUdict 全量：未命中的词走元音组估算。
 * 若哪天要接 CMUdict，换掉 [POP_ENGLISH_LEXICON] 即可，接口不用动。
 */
internal object PhoneticPriors {

    /**
     * 高频英文歌词词 → (音节数 to 相对时值权重)。
     *
     * 权重是「重读音节 ≈ 非重读的 1.8~2.2 倍」这一条规律的具体化，不是逐词测量值。
     */
    private val POP_ENGLISH_LEXICON: Map<String, Pair<Int, Float>> = mapOf(
        "love" to (1 to 1.40f), "heart" to (1 to 1.45f), "night" to (1 to 1.50f), "light" to (1 to 1.45f),
        "time" to (1 to 1.40f), "life" to (1 to 1.40f), "dream" to (1 to 1.45f), "eyes" to (1 to 1.45f),
        "soul" to (1 to 1.45f), "hold" to (1 to 1.35f), "feel" to (1 to 1.40f), "stay" to (1 to 1.40f),
        "day" to (1 to 1.35f), "way" to (1 to 1.35f), "say" to (1 to 1.30f), "fly" to (1 to 1.40f),
        "cry" to (1 to 1.40f), "sky" to (1 to 1.40f), "home" to (1 to 1.35f), "hope" to (1 to 1.35f),
        "pain" to (1 to 1.45f), "rain" to (1 to 1.40f), "tears" to (1 to 1.40f), "lost" to (1 to 1.35f),
        "fall" to (1 to 1.35f), "call" to (1 to 1.30f), "shine" to (1 to 1.40f), "free" to (1 to 1.40f),
        "world" to (1 to 1.40f), "mind" to (1 to 1.40f), "find" to (1 to 1.35f), "burn" to (1 to 1.40f),
        "dark" to (1 to 1.35f), "deep" to (1 to 1.40f), "keep" to (1 to 1.35f), "breathe" to (1 to 1.40f),
        "fire" to (2 to 1.75f), "desire" to (3 to 2.45f),

        "baby" to (2 to 1.90f), "never" to (2 to 1.65f), "forever" to (3 to 2.80f), "together" to (3 to 2.70f),
        "always" to (2 to 1.80f), "remember" to (3 to 2.60f), "beautiful" to (3 to 2.55f), "somebody" to (3 to 2.45f),
        "everybody" to (4 to 3.15f), "without" to (2 to 1.70f), "waiting" to (2 to 1.75f), "falling" to (2 to 1.75f),
        "calling" to (2 to 1.75f), "broken" to (2 to 1.65f), "lonely" to (2 to 1.70f), "shadow" to (2 to 1.60f),
        "memories" to (3 to 2.50f), "believing" to (3 to 2.55f), "reason" to (2 to 1.65f), "season" to (2 to 1.65f),
        "silence" to (2 to 1.70f), "promise" to (2 to 1.65f), "heaven" to (2 to 1.70f), "angel" to (2 to 1.70f),

        "yeah" to (1 to 1.75f), "yea" to (1 to 1.65f), "oh" to (1 to 1.70f), "ooh" to (1 to 1.80f),
        "ah" to (1 to 1.65f), "ahh" to (1 to 1.75f), "whoa" to (1 to 1.75f), "woah" to (1 to 1.75f),
        "hey" to (1 to 1.55f), "la" to (1 to 1.45f), "na" to (1 to 1.45f), "uh" to (1 to 1.30f),

        "the" to (1 to 0.55f), "a" to (1 to 0.50f), "an" to (1 to 0.50f), "to" to (1 to 0.58f),
        "in" to (1 to 0.65f), "of" to (1 to 0.60f), "at" to (1 to 0.60f), "by" to (1 to 0.65f),
        "for" to (1 to 0.65f), "on" to (1 to 0.65f), "with" to (1 to 0.70f), "and" to (1 to 0.65f),
        "but" to (1 to 0.65f), "or" to (1 to 0.60f), "is" to (1 to 0.60f), "am" to (1 to 0.60f),
        "are" to (1 to 0.65f), "was" to (1 to 0.65f), "were" to (1 to 0.65f), "it" to (1 to 0.60f),
        "its" to (1 to 0.65f), "that" to (1 to 0.75f), "this" to (1 to 0.75f), "my" to (1 to 0.85f),
        "your" to (1 to 0.85f), "me" to (1 to 0.85f), "you" to (1 to 0.95f), "we" to (1 to 0.90f),
        "they" to (1 to 0.90f), "so" to (1 to 0.85f), "just" to (1 to 0.90f), "now" to (1 to 1.05f),
    )

    /** 该英文词在词典里的权重；未命中返回 null。 */
    fun lexiconWeight(word: String): Float? {
        val clean = word.lowercase().filter { it in 'a'..'z' }
        return POP_ENGLISH_LEXICON[clean]?.second
    }

    /**
     * 音节数：词典优先，否则数元音组（词尾不发音的 `e` 扣掉，`-le` 除外）。
     */
    fun estimateEnglishSyllables(word: String): Int {
        val clean = word.lowercase().filter { it in 'a'..'z' }
        POP_ENGLISH_LEXICON[clean]?.let { return it.first }
        if (clean.length <= 3) return 1

        var count = 0
        var prevIsVowel = false
        for (c in clean) {
            val isVowel = c in "aeiouy"
            if (isVowel && !prevIsVowel) count++
            prevIsVowel = isVowel
        }
        if (clean.endsWith("e") && !clean.endsWith("le") && !clean.endsWith("ee") && count > 1) count--
        return count.coerceAtLeast(1)
    }

    fun isJapaneseKana(c: Char): Boolean = (c in '\u3040'..'\u309F') || (c in '\u30A0'..'\u30FF')

    fun isSmallKana(c: Char): Boolean = c in "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"
}
