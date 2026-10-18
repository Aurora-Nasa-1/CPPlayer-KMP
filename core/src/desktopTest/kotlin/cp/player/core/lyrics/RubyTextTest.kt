package cp.player.core.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 注音标记解析的守卫测试。
 *
 * 这里的每一条都对应一种「歌词里真的会出现」的写法；反向用例（普通括注不能被吃掉）
 * 与正向用例同等重要 —— 解析错了会**改坏歌词本身**，比没有注音严重得多。
 */
class RubyTextTest {

    @Test
    fun bracketFormIsParsed() {
        val segs = RubyText.parse("[漢字|かんじ]の歌")
        assertEquals(listOf(RubySegment("漢字", "かんじ"), RubySegment("の歌")), segs)
        assertTrue(RubyText.hasRuby("[漢字|かんじ]"))
        assertEquals("漢字の歌", RubyText.strip("[漢字|かんじ]の歌"))
    }

    @Test
    fun curlyFormIsParsed() {
        assertEquals(
            listOf(RubySegment("東京", "とうきょう")),
            RubyText.parse("{東京|とうきょう}"),
        )
    }

    @Test
    fun angleBracketTakesTrailingKanji() {
        val segs = RubyText.parse("君の《きみの》")
        // 「の」是假名，基准只取它前面的汉字串。
        assertEquals(listOf(RubySegment("君", "きみ"), RubySegment("の")), RubyText.parse("君《きみ》の"))
        assertEquals(1, segs.size)
        assertEquals(RubySegment("君の《きみの》"), segs.first())
    }

    @Test
    fun pipePrefixAllowsKanaInBase() {
        val segs = RubyText.parse("｜今日《きょう》は晴れ")
        assertEquals(listOf(RubySegment("今日", "きょう"), RubySegment("は晴れ")), segs)
    }

    @Test
    fun parenFormOnlyWhenReadingIsKana() {
        // 正向：紧邻汉字 + 括号内纯假名
        assertEquals(
            listOf(RubySegment("今日", "きょう"), RubySegment("の空")),
            RubyText.parse("今日(きょう)の空"),
        )
        // 反向：普通括注不能被当成注音
        assertEquals(listOf(RubySegment("(きみ)")), RubyText.parse("(きみ)"))
        assertEquals(listOf(RubySegment("歌（副歌）")), RubyText.parse("歌（副歌）"))
        assertEquals(listOf(RubySegment("Song (Live ver.)")), RubyText.parse("Song (Live ver.)"))
    }

    @Test
    fun lrcTimestampIsNotRuby() {
        // 时间标签里没有 `|`，不该被吃掉。
        assertEquals(listOf(RubySegment("[00:12.34]歌")), RubyText.parse("[00:12.34]歌"))
        assertFalse(RubyText.hasRuby("[00:12.34]歌"))
    }

    @Test
    fun plainTextIsSingleSegment() {
        assertEquals(listOf(RubySegment("夜に駆ける")), RubyText.parse("夜に駆ける"))
        assertEquals("", RubyText.strip(""))
        assertEquals(emptyList(), RubyText.parse(""))
        assertFalse(RubyText.hasRuby("夜に駆ける"))
    }

    @Test
    fun multipleRubyInOneLine() {
        val segs = RubyText.parse("[空|そら]を[駆|か]ける")
        assertEquals(
            listOf(RubySegment("空", "そら"), RubySegment("を"), RubySegment("駆", "か"), RubySegment("ける")),
            segs,
        )
        assertEquals("空を駆ける", RubyText.strip("[空|そら]を[駆|か]ける"))
    }
}
