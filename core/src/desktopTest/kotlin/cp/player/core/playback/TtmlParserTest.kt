package cp.player.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TtmlParser] 的单元测试。
 *
 * 样本取自 AMLL TTML DataBase 真实歌词（ncmMusicId=1361348080）的关键片段，
 * 覆盖：词级 span、行间空格、x-translation / x-roman、clock 时间（M:SS.mmm /
 * H:MM:SS / 纯秒）、XML 实体、空 p（间奏占位）、x-bg 背景人声。
 */
class TtmlParserTest {

    private val sample = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word"><body dur="3:05.800"><div begin="0.115" end="3:05.800"><p begin="0.115" end="2.813" itunes:key="L1" ttm:agent="v1"><span begin="0.115" end="0.237">I</span> <span begin="0.277" end="0.594">pro&#109;ise</span><span ttm:role="x-translation" xml:lang="zh-CN">我保证你再也找不到像我这样的人</span></p><p begin="3.470" end="5.920" itunes:key="L2" ttm:agent="v1"><span begin="3.470" end="3.566">I</span> <span begin="3.611" end="3.766">know</span> <span begin="3.766" end="3.912">that</span><span ttm:role="x-roman">I know that I&apos;m a handful</span></p><p begin="1:00.000" end="1:02.000" itunes:key="L3">plain &amp; simple</p><p begin="1:30.000" end="1:31.000"></p><p begin="2:00.000" end="2:03.500" ttm:agent="v2"><span ttm:role="x-bg" begin="2:00.000" end="2:03.500"><span begin="2:00.000" end="2:01.000">(background)</span></span></p><p begin="2:30.000" end="2:33.000" itunes:key="L4"><span begin="2:30.000" end="2:30.200">full</span><span begin="2:30.200" end="2:30.400">line</span><span begin="2:30.400" end="2:33.000">only</span></p></div></body></tt>
    """.trimIndent()

    @Test
    fun parsesRealSampleStructure() {
        val lines = TtmlParser.parse(sample)
        // L1 / L2 / L3 / L4 保留；空 p 与 x-bg 行被跳过
        assertEquals(4, lines.size)
    }

    @Test
    fun wordLevelLineKeepsWordsAndTranslation() {
        val line = TtmlParser.parse(sample).first()
        assertEquals(115L, line.time)
        assertEquals(2813L, line.endTime)
        assertEquals("I promise", line.text)
        assertEquals("我保证你再也找不到像我这样的人", line.translation)
        assertEquals(2, line.words.size)
        assertEquals("promise", line.words[1].text)
        assertEquals(277L, line.words[1].beginTime)
        assertEquals(594L, line.words[1].endTime)
    }

    @Test
    fun romanizationAndEntitiesDecoded() {
        val line = TtmlParser.parse(sample)[1]
        assertEquals(3470L, line.time)
        assertEquals("I know that", line.text)
        assertEquals("I know that I'm a handful", line.romanization)
    }

    @Test
    fun plainLineWithoutSpans() {
        val line = TtmlParser.parse(sample)[2]
        assertEquals(60_000L, line.time)
        assertEquals(62_000L, line.endTime)
        assertEquals("plain & simple", line.text)
        assertTrue(line.words.isEmpty())
    }

    @Test
    fun threeWordSpansStayWordLevel() {
        // 三个「词」时间上连续覆盖整行 ⇒ 保持词级
        val line = TtmlParser.parse(sample)[3]
        assertEquals(150_000L, line.time)
        assertEquals(3, line.words.size)
    }

    @Test
    fun missingEndTimeFilledByNextLine() {
        val raw = """
            <tt><body><div><p begin="1.000"><span begin="1.000">a</span></p><p begin="4.000" end="5.000"><span begin="4.000">b</span></p></div></body></tt>
        """.trimIndent()
        val lines = TtmlParser.parse(raw)
        assertEquals(2, lines.size)
        // 第一行缺 end ⇒ 用下一行起始补齐；第二行有自己的 end
        assertEquals(4_000L, lines[0].endTime)
        assertEquals(5_000L, lines[1].endTime)
    }

    @Test
    fun timeFormats() {
        assertEquals(115L, TtmlParser.parseTime("0.115"))
        assertEquals(500L, TtmlParser.parseTime("0,5"))
        assertEquals(185_800L, TtmlParser.parseTime("3:05.800"))
        assertEquals(3_785_500L, TtmlParser.parseTime("1:03:05.5"))
        assertEquals(300L, TtmlParser.parseTime("300ms"))
        assertEquals(1_500L, TtmlParser.parseTime("1.5s"))
        assertEquals(120_000L, TtmlParser.parseTime("2m"))
        assertEquals(3_600_000L, TtmlParser.parseTime("1h"))
        assertNull(TtmlParser.parseTime("abc"))
        assertNull(TtmlParser.parseTime(""))
    }

    @Test
    fun entities() {
        assertEquals("&<>\"'AB", TtmlParser.decodeEntities("&amp;&lt;&gt;&quot;&apos;&#65;&#x42;"))
        assertEquals("&unknown;", TtmlParser.decodeEntities("&unknown;"))
    }

    @Test
    fun emptyAndGarbageInput() {
        assertTrue(TtmlParser.parse("").isEmpty())
        assertTrue(TtmlParser.parse("not xml at all").isEmpty())
        // 自闭合 p（间奏占位）不产生行
        assertTrue(TtmlParser.parse("""<tt><div><p begin="1.000" end="2.000"/></div></tt>""").isEmpty())
    }
}
