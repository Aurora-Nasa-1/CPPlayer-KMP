package cp.player.core.lyrics

import cp.player.core.playback.SyncedLyricLine
import cp.player.core.playback.SyncedLyricLine.SyncedWord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartKaraokeLrcSynthesizerTest {

    // ============ 基本契约 ============

    @Test
    fun `lines already containing words are preserved as-is`() {
        val existingWords = listOf(
            SyncedWord("Hello", 1000L, 1500L),
            SyncedWord("World", 1500L, 2000L),
        )
        val input = listOf(SyncedLyricLine(time = 1000L, text = "Hello World", words = existingWords))
        assertEquals(input, SmartKaraokeLrcSynthesizer.synthesize(input), "原生逐字歌词应原样返回，不被算法覆盖")
    }

    @Test
    fun `mixed song keeps word level lines and synthesizes the rest`() {
        // 逐行降级：混源歌词（部分行有真逐字）不该整首放弃
        val real = listOf(SyncedWord("Hello", 1000L, 1400L), SyncedWord("World", 1400L, 1900L))
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "Hello World", words = real),
            SyncedLyricLine(time = 4000L, text = "海风吹拂"),
            SyncedLyricLine(time = 7000L, text = "下一句"),
        )
        val result = SmartKaraokeLrcSynthesizer.synthesize(input)
        assertEquals(real, result[0].words, "已有逐字的行必须逐字段不变")
        assertTrue(result[1].words.isNotEmpty(), "缺逐字的行要被补上")
    }

    @Test
    fun `empty input or blank line returns gracefully`() {
        assertTrue(SmartKaraokeLrcSynthesizer.synthesize(emptyList()).isEmpty())
        val blank = SmartKaraokeLrcSynthesizer.synthesize(listOf(SyncedLyricLine(time = 1000L, text = "   ")))
        assertEquals("   ", blank[0].text)
        assertTrue(blank[0].words.isEmpty())
    }

    // ============ 汉语 ============

    @Test
    fun `chinese line generates monotonic words with phrase final lengthening`() {
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "海风吹拂着脸庞"),
            SyncedLyricLine(time = 4000L, text = "下一句歌词"),
        )
        val firstLine = SmartKaraokeLrcSynthesizer.synthesize(input)[0]

        assertEquals(7, firstLine.words.size)
        assertEquals("海", firstLine.words[0].text)
        assertEquals("庞", firstLine.words[6].text)

        var prevBegin = 1000L
        for (word in firstLine.words) {
            assertTrue(word.beginTime >= prevBegin, "开始时间应单调递增")
            assertTrue(word.endTime > word.beginTime, "结束时间应大于开始时间")
            prevBegin = word.beginTime
        }
        assertTrue(firstLine.words.last().endTime <= 4000L, "第一句结束不应超过下一句开始")

        val zhe = firstLine.words[4].let { it.endTime - it.beginTime } // 着（轻声虚词）
        val pang = firstLine.words[6].let { it.endTime - it.beginTime } // 庞（行末延音）
        assertTrue(pang > zhe, "行末字延音应长于轻声虚词: 庞=$pang 着=$zhe")
    }

    @Test
    fun `chinese onset dark gap follows pinyin initial class`() {
        fun onset(text: String): Long {
            val tokens = SmartKaraokeLrcSynthesizer.tokenize(text)
            return KaraokeArticulation.articulate(tokens, KaraokeLanguage.ZH, 1f).single().onsetMs
        }
        // 怕(p) 是送气塞音，辅音最长；啊(a) 是零声母，字前不该有暗区
        assertEquals(0L, onset("啊"), "零声母不应有字前暗区")
        assertTrue(onset("怕") >= 80L, "送气塞音的暗区应接近 85ms，实际 ${onset("怕")}")
        assertTrue(onset("怕") > onset("妈"), "送气塞音应长于鼻音")
        assertTrue(onset("妈") > 0L, "鼻音也有起音耗时")
    }

    @Test
    fun `closed vowels get less time than open ones`() {
        // 全是行内字（行末字有 1.55 倍延音，会盖掉韵母差异）
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "光雨风吹"),
            SyncedLyricLine(time = 6000L, text = "下一句"),
        )
        val words = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words
        val guang = words[0].let { it.endTime - it.beginTime } // uang：后鼻音大开
        val yu = words[1].let { it.endTime - it.beginTime }    // ü：闭前
        assertTrue(guang > yu, "uang(开) 应长于 ü(闭): $guang vs $yu")
    }

    // ============ 日语 ============

    @Test
    fun `japanese kana handles compound small kana as single syllable`() {
        val texts = SmartKaraokeLrcSynthesizer.tokenize("今日きゃぷたー").map { it.text }
        assertTrue(texts.contains("きゃ"), "小假名拗音应与前假名结合成单一音节: $texts")
        assertTrue(texts.contains("ー"), "长音符应单独成 token: $texts")
    }

    @Test
    fun `kanji mora table decodes common lyric characters`() {
        assertEquals(3, KanjiMoraTable.kunMora('涙'), "涙 = なみだ = 3 拍")
        assertEquals(1, KanjiMoraTable.kunMora('木'), "木 = き = 1 拍")
        assertEquals(2, KanjiMoraTable.onMora('感'), "感 = カン = 2 拍")
        assertTrue(!KanjiMoraTable.contains('の'), "假名不在表内")
        // 上下文形态决定读音：后接假名 / 孤字 ⇒ 训读，汉字连写 ⇒ 音读
        assertEquals(3, KanjiMoraTable.moraFor('涙', followedByKana = true, inKanjiRun = false))
        assertEquals(2, KanjiMoraTable.moraFor('感', followedByKana = false, inKanjiRun = true))
    }

    @Test
    fun `japanese line weights kanji by mora count`() {
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "涙が溢れた"),
            SyncedLyricLine(time = 6000L, text = "次の歌"),
        )
        val words = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words
        val namida = words.first { it.text.startsWith("涙") }
        val ga = words.first { it.text.startsWith("が") }
        val namidaDur = namida.endTime - namida.beginTime
        val gaDur = ga.endTime - ga.beginTime
        assertTrue(namidaDur > gaDur * 2, "涙(3拍) 应显著长于 が(1拍): $namidaDur vs $gaDur")
    }

    @Test
    fun `japanese sokuon consumes a mora without lighting up`() {
        // がっこう：っ 占一拍但是静默。静默落在「が」与「っ」之间 —— っ 的字形
        // 只在那一拍的最后一毫秒亮起，紧接着就是 こ 的辅音，正是发声上的真实顺序。
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "がっこう"),
            SyncedLyricLine(time = 4000L, text = "次の歌"),
        )
        val words = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words
        val ga = words.first { it.text.startsWith("が") }
        val sokuon = words.first { it.text.startsWith("っ") }
        val ko = words.first { it.text.startsWith("こ") }

        assertTrue(
            sokuon.endTime - sokuon.beginTime <= 2L,
            "促音自身的亮区应接近 0（实际 ${sokuon.endTime - sokuon.beginTime}ms）",
        )
        assertTrue(sokuon.beginTime - ga.endTime >= 100L, "促音那一拍应是静默（实际 ${sokuon.beginTime - ga.endTime}ms）")
        assertTrue(ko.beginTime >= sokuon.endTime, "促音之后才是下一个音")
    }

    // ============ 英语 ============

    @Test
    fun `english words are split into syllables that concatenate back`() {
        assertEquals(listOf("fo", "re", "ver"), KaraokeArticulation.splitEnglishPieces("forever"))
        assertEquals(listOf("beau", "ti", "ful"), KaraokeArticulation.splitEnglishPieces("beautiful"))
        assertEquals(listOf("are"), KaraokeArticulation.splitEnglishPieces("are"), "词尾不发音的 e 不单独成音节")
        assertEquals(listOf("sky"), KaraokeArticulation.splitEnglishPieces("sky"))
        for (word in listOf("forever", "beautiful", "baby", "together", "way")) {
            assertEquals(word, KaraokeArticulation.splitEnglishPieces(word).joinToString(""), "切分拼回必须等于原文")
        }
    }

    @Test
    fun `english multi syllable words get more time than weak function words`() {
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "baby you are my forever dream"),
            SyncedLyricLine(time = 6000L, text = "next"),
        )
        val words = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words
        assertEquals(
            "baby you are my forever dream",
            words.joinToString("") { it.text },
            "拆分音节后拼回来必须还是原文",
        )
        val spans = wordSpans(words)
        val are = spans.first { it.first == "are" }.second
        val forever = spans.first { it.first == "forever" }.second
        assertTrue(forever > are * 2, "三音节核心词 forever($forever) 应显著长于弱动词 are($are)")
    }

    @Test
    fun `english line preserves word trailing space`() {
        val input = listOf(
            SyncedLyricLine(time = 2000L, text = "I want it that way"),
            SyncedLyricLine(time = 5000L, text = "Next line"),
        )
        val words = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words
        assertEquals(5, words.size)
        assertEquals("I ", words[0].text, "词尾空格必须保留，否则渲染会粘连")
        assertEquals("want ", words[1].text)
        assertEquals("way", words[4].text)

        val itDuration = words[2].endTime - words[2].beginTime
        val wayDuration = words[4].endTime - words[4].beginTime
        assertTrue(wayDuration > itDuration, "弱读虚词 it 应短于行末词 way")
    }

    // ============ 时长规划 ============

    @Test
    fun `regular line extends singing smoothly without premature termination`() {
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "海风吹拂着脸庞"),
            SyncedLyricLine(time = 5500L, text = "下一句起唱"),
        )
        val lastWord = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words.last()
        val breathGap = 5500L - lastWord.endTime
        assertTrue(breathGap in 200L..550L, "常规行应唱满仅留换气气口（实际 $breathGap ms）")
    }

    @Test
    fun `completed line stays focused until next line starts without extending word highlight`() {
        val input = listOf(
            SyncedLyricLine(time = 1_000L, endTime = 2_000L, text = "说走就走"),
            SyncedLyricLine(time = 6_000L, endTime = 8_000L, text = "下一句"),
        )

        val first = SmartKaraokeLrcSynthesizer.synthesize(input)[0]

        assertEquals(6_000L, first.endTime, "行焦点应保持到下一行真正起唱")
        assertTrue(first.words.last().endTime < first.endTime!!, "最后一字应先结束，演唱空隙不能继续扫光")
    }

    @Test
    fun `long interlude line truncates singing duration reasonably`() {
        val input = listOf(
            SyncedLyricLine(time = 10_000L, text = "说走就走"),
            SyncedLyricLine(time = 20_000L, text = "后来发现"),
        )
        val lastWord = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words.last()
        val span = lastWord.endTime - 10_000L
        assertTrue(span < 6000L, "4 个字的有效歌唱时长不应超过 6000ms（实际 $span）")
    }

    @Test
    fun `short line does not stretch unnaturally when inter-line gap is long`() {
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "我不配"),
            SyncedLyricLine(time = 7000L, text = "下一句"),
        )
        val words = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words
        val span = words.last().endTime - 1000L
        assertTrue(span in 1800L..4200L, "3 字在长跨度下应限长防拖沓（实际 $span）")
    }

    @Test
    fun `dense rap line compresses consonants and maintains rapid delivery`() {
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "快使用双截棍哼哼哈兮快使用双截棍哼哼哈兮"),
            SyncedLyricLine(time = 4200L, text = "习武之人切记"),
        )
        val words = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words
        assertEquals(20, words.size)
        val average = (words.last().endTime - 1000L) / words.size
        assertTrue(average in 90L..200L, "高语速密集行单字平均耗时应紧凑（实际 $average）")
    }

    @Test
    fun `real word anchors pull synthesized duration toward observed singing rate`() {
        val anchors = listOf(
            SyncedLyricLine(
                time = 1_000L,
                text = "海风吹拂",
                words = listOf(
                    SyncedWord("海", 1_000L, 1_250L), SyncedWord("风", 1_250L, 1_500L),
                    SyncedWord("吹", 1_500L, 1_750L), SyncedWord("拂", 1_750L, 2_000L),
                ),
            ),
            SyncedLyricLine(
                time = 3_000L,
                text = "灯火阑珊",
                words = listOf(
                    SyncedWord("灯", 3_000L, 3_250L), SyncedWord("火", 3_250L, 3_500L),
                    SyncedWord("阑", 3_500L, 3_750L), SyncedWord("珊", 3_750L, 4_000L),
                ),
            ),
            SyncedLyricLine(
                time = 5_000L,
                text = "月光如水",
                words = listOf(
                    SyncedWord("月", 5_000L, 5_250L), SyncedWord("光", 5_250L, 5_500L),
                    SyncedWord("如", 5_500L, 5_750L), SyncedWord("水", 5_750L, 6_000L),
                ),
            ),
            SyncedLyricLine(time = 7_000L, text = "梦里花开"),
            SyncedLyricLine(time = 13_000L, text = "下一句"),
        )

        val synthesized = SmartKaraokeLrcSynthesizer.synthesize(anchors)[3].words
        val duration = synthesized.last().endTime - synthesized.first().beginTime
        assertTrue(duration < 3_500L, "真逐字锚点应阻止四字句吸收六秒跨度（实际 $duration ms）")
        assertEquals("梦里花开", synthesized.joinToString("") { it.text })
    }

    @Test
    fun `sparse signals preserve articulation instead of flattening every syllable`() {
        val result = SmartKaraokeLrcSynthesizer.synthesize(
            listOf(
                SyncedLyricLine(time = 1_000L, text = "光雨风吹"),
                SyncedLyricLine(time = 101_000L, text = "下一句"),
            ),
        )[0].words
        val durations = result.map { it.endTime - it.beginTime }
        assertTrue(durations.distinct().size > 2, "证据稀疏也应保留韵母、虚词与句尾节奏差异：$durations")
        assertTrue(durations[0] > durations[1], "开口韵光应长于闭口韵雨：$durations")
    }

    // ============ 气口 / 切分守恒 ============

    @Test
    fun `tokenize is lossless for spaces and punctuation`() {
        val samples = listOf(
            "海风吹拂着脸庞",
            "  行首有两个空格",
            "行尾有三个空格   ",
            "海风吹拂着，我的脸庞",
            "I want it that way",
            "baby, I love you…",
            "今日きゃぷたー",
            "涙が溢れた、",
            "",
        )
        for (sample in samples) {
            assertEquals(sample, SmartKaraokeLrcSynthesizer.tokenize(sample).joinToString("") { it.text }, "切分必须守恒：[$sample]")
        }
    }

    @Test
    fun `single word space creates a subtle gap and repeated spaces create a phrase break`() {
        fun gap(text: String): Long {
            val words = SmartKaraokeLrcSynthesizer.synthesize(
                listOf(
                    SyncedLyricLine(time = 1_000L, text = text),
                    SyncedLyricLine(time = 5_000L, text = "next"),
                ),
            )[0].words
            val boundary = words.indexOfFirst { it.text.lastOrNull()?.isWhitespace() == true }
            return words[boundary + 1].beginTime - words[boundary].endTime
        }

        val single = gap("hello world")
        val repeated = gap("hello  world")
        assertTrue(single in 8L..160L, "英文单空格应有轻微分词气口（实际 ${single}ms，tokens=${SmartKaraokeLrcSynthesizer.tokenize("hello world")}）")
        assertTrue(repeated >= single + 80L, "连续空格应表达更强的乐句停顿：单空格=$single，双空格=$repeated")
    }

    @Test
    fun `intra-line punctuation creates a real gap`() {
        val input = listOf(
            SyncedLyricLine(time = 1000L, text = "海风吹拂着，我的脸庞"),
            SyncedLyricLine(time = 5000L, text = "下一句"),
        )
        val words = SmartKaraokeLrcSynthesizer.synthesize(input)[0].words
        val commaIndex = words.indexOfFirst { it.text.contains('，') }
        assertTrue(commaIndex >= 0, "标点应贴在字上而不是单独成段")
        val gap = words[commaIndex + 1].beginTime - words[commaIndex].endTime
        assertTrue(gap >= 60L, "标点之后应有一道暗缝（实际 ${gap}ms）")
    }

    @Test
    fun `interlude gap is not absorbed into the singing rate`() {
        // 关键回归：行跨度里混着「唱的 + 换气的 + 伴奏的」。第八行前面有 14 秒过门，
        // 若把这 14 秒当成「这句就该唱这么慢」，七个字会各拖两秒。
        val lines = buildList {
            var t = 1_000L
            repeat(4) {
                add(SyncedLyricLine(time = t, text = "海风吹过脸庞"))
                t += 3_600L
            }
            add(SyncedLyricLine(time = t, text = "我们说好的永远")) // ← 这句之后是 14 秒过门
            t += 14_000L
            add(SyncedLyricLine(time = t, text = "怎么就散了")); t += 3_600L
            add(SyncedLyricLine(time = t, text = "只剩下雨声")); t += 3_600L
            add(SyncedLyricLine(time = t, text = "落在耳边"))
        }
        val result = SmartKaraokeLrcSynthesizer.synthesize(lines)
        val interlude = result[4]
        val span = lines[5].time - lines[4].time
        val sung = interlude.words.last().endTime - lines[4].time

        assertTrue(sung < span * 0.5, "跨度 ${span}ms 里只该唱一小段（实际唱了 ${sung}ms）")
        assertTrue(sung > 1_500L, "也不能因为截断而唱得过短（实际 ${sung}ms）")
        // 除末字外，没有哪个字被拖成慢动作（末字承载「延长音」，允许更长，上限 1.2s）
        for (word in interlude.words.dropLast(1)) {
            val duration = word.endTime - word.beginTime
            assertTrue(duration <= 900L, "过门行里出现了 ${duration}ms 的字：${word.text}")
        }
        // 末字承载「延长音」与行末延音系数，允许比同句的字长，但不能无限拖（上限 2.4s）
        val held = interlude.words.last().let { it.endTime - it.beginTime }
        assertTrue(held <= 2_400L, "末字延长音过长（${held}ms），看起来会像卡住")
    }

    // ============ 不变量 ============

    @Test
    fun `fuzz invariants hold for random lines`() {
        val random = Random(20261025)
        val alphabet = "海风吹拂着脸庞我不配说走就走在的了一是泪光梦见你爱我".toList()
        repeat(400) { round ->
            val count = 1 + random.nextInt(24)
            val text = buildString { repeat(count) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val span = 300L + random.nextLong(20_000L)
            val input = listOf(
                SyncedLyricLine(time = 1000L, text = text),
                SyncedLyricLine(time = 1000L + span, text = "下一句"),
            )
            val line = SmartKaraokeLrcSynthesizer.synthesize(input)[0]
            val words = line.words
            assertTrue(words.isNotEmpty(), "第 $round 轮：$text 应生成逐字")
            assertEquals(text, words.joinToString("") { it.text }, "第 $round 轮：切分必须守恒")
            var prevBegin = 1000L
            for (word in words) {
                assertTrue(word.beginTime >= prevBegin, "第 $round 轮 [$text]：begin 必须单调")
                assertTrue(word.endTime > word.beginTime, "第 $round 轮 [$text]：不能有零长/负长段")
                assertTrue(word.endTime - word.beginTime >= 1L, "第 $round 轮 [$text]：亮区至少 1ms")
                prevBegin = word.beginTime
            }
            assertTrue(
                words.last().endTime <= 1000L + span,
                "第 $round 轮 [$text]：末字 ${words.last().endTime} 越过了下一行 ${1000L + span}",
            )
        }
    }

    // ============ 辅助 ============

    /** 把按音节切开的 words 按「尾随空格」重组回词，返回 (词, 亮区总长)。 */
    private fun wordSpans(words: List<SyncedWord>): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        val text = StringBuilder()
        var begin = 0L
        var end = 0L
        for (word in words) {
            if (text.isEmpty()) begin = word.beginTime
            text.append(word.text)
            end = word.endTime
            if (word.text.endsWith(" ")) {
                out += text.toString().trim() to (end - begin)
                text.clear()
            }
        }
        if (text.isNotEmpty()) out += text.toString().trim() to (end - begin)
        return out
    }
}
