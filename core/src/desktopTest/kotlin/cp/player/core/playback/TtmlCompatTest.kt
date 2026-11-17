package cp.player.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TTML 兼容层测试。
 *
 * 用例都取自真实词库里出现过的写法，而不是「理论上可能」的语法：
 * 缺 `end`、裸文本、单位时间、数字实体、`x-translation` / `x-bg`。
 */
class TtmlCompatTest {

    @Test
    fun `缺 end 的 p 由下一行起点补齐`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="1.0">First line</p>
                <p begin="3.0">Second line</p>
              </div></body>
            </tt>
        """.trimIndent()

        val lines = LyricsParser.parseTtml(ttml)
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].time)
        assertEquals(3_000L, lines[0].endTime)
        assertEquals("First line", lines[0].text)
        assertEquals(3_000L, lines[1].time)
    }

    @Test
    fun `缺 begin 的 p 接在上一条结束时间之后`() {
        val ttml = """
            <tt><body><div>
              <p begin="1.0" end="2.5">First</p>
              <p end="4.0">Second</p>
            </div></body></tt>
        """.trimIndent()

        val lines = LyricsParser.parseTtml(ttml)
        assertEquals(2, lines.size)
        assertEquals(2_500L, lines[1].time)
        assertEquals(4_000L, lines[1].endTime)
    }

    @Test
    fun `纯文本行保留行内空格`() {
        val ttml = """
            <tt><body><div>
              <p begin="0" end="2">hello, world</p>
            </div></body></tt>
        """.trimIndent()

        val lines = LyricsParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals("hello, world", lines[0].text)
        assertTrue(lines[0].words.isEmpty(), "整行包成单个 span 时不应误判成逐字歌词")
    }

    @Test
    fun `单位时间按相对偏移解析`() {
        val ttml = """
            <tt><body begin="1s"><div>
              <p begin="1500ms" end="2.5s">Line</p>
            </div></body></tt>
        """.trimIndent()

        val lines = LyricsParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals(2_500L, lines[0].time, "body 1s + p 1500ms")
        assertEquals(3_500L, lines[0].endTime, "body 1s + 2.5s")
    }

    @Test
    fun `数字实体与命名实体都只解码一次`() {
        val ttml = """
            <tt><body><div>
              <p begin="0" end="2">It&#39;s &amp; &quot;fine&quot;</p>
            </div></body></tt>
        """.trimIndent()

        val lines = LyricsParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals("It's & \"fine\"", lines[0].text)
    }

    @Test
    fun `逐字 span 保留时间与 x-translation`() {
        val ttml = """
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
              <p begin="1.0" end="2.0">
                <span begin="1.0" end="1.5">Hel</span><span begin="1.5" end="2.0">lo</span>
                <span ttm:role="x-translation" xml:lang="zh-CN">你好</span>
                <span ttm:role="x-bg" begin="1.2" end="1.8">
                  <span begin="1.2" end="1.8">(bg)</span>
                </span>
              </p>
            </div></body></tt>
        """.trimIndent()

        val lines = LyricsParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals("Hello", lines[0].text)
        assertEquals("你好", lines[0].translation)
        assertEquals(2, lines[0].words.size)
        assertEquals(1_000L, lines[0].words[0].beginTime)
        assertEquals(2_000L, lines[0].words[1].endTime)
    }

    @Test
    fun `tt 前缀根节点也能识别`() {
        val ttml = """
            <tt:tt xmlns:tt="http://www.w3.org/ns/ttml"><tt:body><tt:div>
              <tt:p begin="0" end="1">prefixed</tt:p>
            </tt:div></tt:body></tt:tt>
        """.trimIndent()

        val normalized = TtmlCompat.normalize(ttml)
        assertNotNull(normalized)
        assertTrue(normalized.contains("<tt "))
        assertEquals("prefixed", LyricsParser.parseTtml(ttml).single().text)
    }

    @Test
    fun `数字实体在后处理阶段也能解码`() {
        val lines = listOf(SyncedLyricLine(time = 0, text = "It&#x27;s"))
        assertEquals("It's", TtmlCompat.decodeNumericEntities(lines).single().text)
    }
}
