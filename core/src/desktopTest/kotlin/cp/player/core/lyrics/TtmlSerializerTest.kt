package cp.player.core.lyrics

import cp.player.core.playback.SyncedLyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtmlSerializerTest {

    @Test
    fun `formatTime 格式化毫秒与小时正确`() {
        assertEquals("00:00.000", TtmlSerializer.formatTime(0L))
        assertEquals("00:01.234", TtmlSerializer.formatTime(1234L))
        assertEquals("01:05.080", TtmlSerializer.formatTime(65080L))
        assertEquals("01:00:05.100", TtmlSerializer.formatTime(3605100L))
    }

    @Test
    fun `escapeXml 转义 XML 特殊字符`() {
        assertEquals("&lt;rock &amp; roll&gt; &quot;yes&quot; &apos;no&apos;", TtmlSerializer.escapeXml("<rock & roll> \"yes\" 'no'"))
    }

    @Test
    fun `serialize 生成合法的逐字歌词 TTML`() {
        val lines = listOf(
            SyncedLyricLine(
                time = 1000L,
                text = "你好世界",
                endTime = 4000L,
                translation = "Hello World",
                romanization = "ni hao shi jie",
                words = listOf(
                    SyncedLyricLine.SyncedWord("你", 1000L, 1500L),
                    SyncedLyricLine.SyncedWord("好", 1500L, 2000L),
                    SyncedLyricLine.SyncedWord("世界", 2000L, 4000L),
                ),
            ),
            SyncedLyricLine(
                time = 5000L,
                text = "下一句",
                endTime = 8000L,
            ),
        )

        val ttml = TtmlSerializer.serialize(lines, title = "晴天", artist = "周杰伦")

        assertTrue(ttml.contains("<ttm:title>晴天</ttm:title>"))
        assertTrue(ttml.contains("<ttm:agent>周杰伦</ttm:agent>"))
        assertTrue(ttml.contains("<p begin=\"00:01.000\" end=\"00:04.000\">"))
        assertTrue(ttml.contains("<span begin=\"00:01.000\" end=\"00:01.500\">你</span>"))
        assertTrue(ttml.contains("<span begin=\"00:01.500\" end=\"00:02.000\">好</span>"))
        assertTrue(ttml.contains("<span begin=\"00:02.000\" end=\"00:04.000\">世界</span>"))
        assertTrue(ttml.contains("<span ttm:role=\"x-translation\" xml:lang=\"zh-Hans\">Hello World</span>"))
        assertTrue(ttml.contains("<span ttm:role=\"x-roman\">ni hao shi jie</span>"))
        assertTrue(ttml.contains("<p begin=\"00:05.000\" end=\"00:08.000\">下一句</p>"))
    }

    @Test
    fun `toLrc 输出标准行级 LRC`() {
        val lines = listOf(
            SyncedLyricLine(time = 1250L, text = "第一行"),
            SyncedLyricLine(time = 65000L, text = "第二行"),
        )
        val lrc = TtmlSerializer.toLrc(lines)
        assertTrue(lrc.contains("[00:01.25]第一行"))
        assertTrue(lrc.contains("[01:05.00]第二行"))
    }
}
