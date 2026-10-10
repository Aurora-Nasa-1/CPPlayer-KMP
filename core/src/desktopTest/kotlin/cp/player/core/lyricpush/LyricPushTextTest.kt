package cp.player.core.lyricpush

import cp.player.core.playback.SyncedLyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 歌词文本处理（[LyricPushText]）与超级岛断句（[XiaomiSuperIslandLayout]）的回归守卫。
 *
 * 这两块是整条投放链路里**唯一「错了也编译得过、只有肉眼看得见」**的部分：
 * 逐字窗口差一个词、断行位置偏一格，本机单测不查就没有任何信号，
 * 要到真机上看状态栏 / 岛上才发现。
 */
class LyricPushTextTest {

    private fun line(
        time: Long,
        text: String,
        endTime: Long? = null,
        translation: String? = null,
        romanization: String? = null,
        words: List<SyncedLyricLine.SyncedWord> = emptyList(),
    ) = SyncedLyricLine(time, text, endTime, translation, romanization, words)

    private fun word(text: String, begin: Long, end: Long) =
        SyncedLyricLine.SyncedWord(text, begin, end)

    // =======================================================================
    // 1. 行文本归一化
    // =======================================================================

    @Test
    fun `line text is flattened to a single line`() {
        // TTML / 增强 LRC 的原文常常带换行；不归一化会把通知撑成三行、后面全被截断。
        assertEquals("hello world", line(0, "  hello \n world ").lineTextOrNull())
        assertEquals("a b c", line(0, "a\n\n\nb\n c").lineTextOrNull())
        assertNull(line(0, "   \n  ").lineTextOrNull(), "全空白行视为没有文本")
    }

    @Test
    fun `secondary text follows the configured mode`() {
        val target = line(0, "原文", translation = "translation", romanization = "romaji")

        assertNull(target.secondaryText(LyricSecondaryMode.OFF))
        assertEquals("translation", target.secondaryText(LyricSecondaryMode.TRANSLATION))
        assertEquals("romaji", target.secondaryText(LyricSecondaryMode.PRONUNCIATION))
    }

    @Test
    fun `missing secondary does not fall back to the other kind`() {
        // 翻译找不到时**不能**退化成注音：混着给会让接收端「上一句翻译、下一句罗马音」。
        val onlyRoman = line(0, "原文", romanization = "romaji")
        assertNull(onlyRoman.secondaryText(LyricSecondaryMode.TRANSLATION))

        val onlyTranslation = line(0, "原文", translation = "translation")
        assertNull(onlyTranslation.secondaryText(LyricSecondaryMode.PRONUNCIATION))
    }

    @Test
    fun `primary text falls back to the original when the requested language is missing`() {
        val target = line(0, "原文")
        // 主歌词不能为空，所以这里必须回退原文而不是返回空串。
        assertEquals("原文", target.contentText(LyricContentMode.TRANSLATION))
        assertEquals("原文", target.contentText(LyricContentMode.PRONUNCIATION))
        assertEquals("原文", target.contentText(LyricContentMode.ORIGINAL))
    }

    @Test
    fun `music symbol only lines are recognized`() {
        assertTrue("♪".isMusicSymbolOnly())
        assertTrue("  ♪ ♫  ".isMusicSymbolOnly())
        assertFalse("♪ 你好".isMusicSymbolOnly())
        assertFalse("hello".isMusicSymbolOnly())
    }

    // =======================================================================
    // 2. 逐字补齐空白
    // =======================================================================

    @Test
    fun `with line spacing restores the separator the word timeline dropped`() {
        val words = listOf(word("Hello", 0, 500), word("world", 500, 1_000))
        val spaced = words.withLineSpacing("Hello world")
        assertEquals(listOf("Hello ", "world"), spaced.map { it.text })
        // 时间轴必须原样保留 —— 这里只补文本，不动时间。
        assertEquals(listOf(0L, 500L), spaced.map { it.beginTime })
    }

    @Test
    fun `with line spacing is a no-op when there is nothing to restore`() {
        val words = listOf(word("你", 0, 200), word("好", 200, 400))
        // 行文本没有空白 ⇒ 直接返回原列表（连对象都不重建）。
        assertTrue(words.withLineSpacing("你好") === words)
        assertTrue(words.withLineSpacing("") === words)
    }

    @Test
    fun `with line spacing leaves unmatched words untouched`() {
        // 匹配不上时不做部分修补 —— 半个修好的行比没修的行更难排查。
        // （实现上仍会返回一个新列表，所以这里比内容而不是比引用。）
        val words = listOf(word("Hello", 0, 500), word("XX", 500, 1_000))
        val spaced = words.withLineSpacing("Hello world")
        assertEquals(listOf("Hello", "XX"), spaced.map { it.text })
    }

    // =======================================================================
    // 3. 逐字定位
    // =======================================================================

    @Test
    fun `current word index is inclusive of begin and exclusive of end`() {
        val words = listOf(word("a", 0, 500), word("b", 500, 1_000))
        assertEquals(-1, currentWordIndex(words, -1))
        assertEquals(0, currentWordIndex(words, 0))
        assertEquals(0, currentWordIndex(words, 499))
        assertEquals(1, currentWordIndex(words, 500))
        // 区间右开：恰好到结束时间就已经不算这个词了。
        assertEquals(-1, currentWordIndex(words, 1_000))
    }

    @Test
    fun `overlapping words resolve to the most recently started one`() {
        val words = listOf(word("a", 0, 600), word("b", 500, 1_000))
        // 视觉焦点在最新起唱的词上；间隙仍然正确地返回 -1（不能被误判成「唱了一个新词」）。
        assertEquals(1, currentWordIndex(words, 550))
        assertEquals(0, currentWordIndex(words, 200))
    }

    @Test
    fun `nearest word index falls back to the previous then the next word`() {
        val words = listOf(word("a", 1_000, 1_500), word("b", 1_500, 2_000))
        // 唱完之后：取已唱完的最后一个。
        assertEquals(1, nearestWordIndex(words, 2_400))
        // 起唱之前：取还没唱的第一个。
        assertEquals(0, nearestWordIndex(words, 400))
    }

    // =======================================================================
    // 4. 逐字窗口
    // =======================================================================

    @Test
    fun `word window always keeps the current word`() {
        val tokens = listOf("一", "二", "三", "四", "五", "六", "七")
        val window = buildWordWindow(tokens, 3, maxCodePoints = 5)
        assertTrue("四" in window, "当前词必须保留：$window")
        // 省略号本身也占码点预算（与上游一致），所以 5 个码点装的是「…三四五…」。
        // 注意窗口是**不对称**扩的：当前词在窗口里偏左，因为向两侧扩张时先撞到预算上限。
        assertEquals("…三四五…", window)
        assertTrue(codePointCount(window) <= 5, "窗口不得超出码点预算：$window")
    }

    @Test
    fun `word window marks the truncated side with an ellipsis`() {
        val tokens = listOf("一", "二", "三", "四", "五", "六", "七")
        val window = buildWordWindow(tokens, 0, maxCodePoints = 3)
        // 从第一个词开始，左侧没有可省略的内容 ⇒ 只有右侧省略号。
        assertTrue(window.endsWith("…"), "右侧被截断时应有省略号：$window")
        assertFalse(window.startsWith("…"), "左侧没有内容时不该出现省略号：$window")
        assertEquals("一二…", window)
    }

    @Test
    fun `word window inserts separators between latin tokens but not between CJK ones`() {
        // TTML 的逐字 token 不含空格，直接拼会得到 "Helloworld"。
        assertEquals("Hello world", buildWordWindow(listOf("Hello", "world"), 0, maxCodePoints = 40))
        // 中文词之间不能加空格，否则会变成「你 好 世 界」。
        assertEquals("你好世界", buildWordWindow(listOf("你", "好", "世", "界"), 0, maxCodePoints = 40))
        // 标点前后不加空格。
        assertEquals("Hello, world", buildWordWindow(listOf("Hello", ",", "world"), 0, maxCodePoints = 40))
        // 撇号 / 连字符两侧不加。
        assertEquals("don't well-known", buildWordWindow(listOf("don", "'", "t", "well", "-", "known"), 0, maxCodePoints = 40))
    }

    @Test
    fun `word window handles an out of range index by joining everything`() {
        val tokens = listOf("一", "二", "三")
        assertEquals("一二三", buildWordWindow(tokens, -1, 40))
        assertEquals("", buildWordWindow(emptyList(), 0, 40))
    }

    // =======================================================================
    // 5. 紧凑文本（状态栏 chip）
    // =======================================================================

    @Test
    fun `compact text truncates with an ellipsis`() {
        assertEquals("abcdef…", compactText("abcdefgh"))
        assertEquals("短句", compactText("短句"), "本来就够短就不该动它")
        assertEquals("a b", compactText("  a\n b "))
    }

    @Test
    fun `compact text can preserve a whitespace-free long token`() {
        // 中文 / 日文整句没有空白，截成 7 个字反而读不出内容 —— 宁可让接收端自己裁。
        assertEquals("一二三四五六七八", compactText("一二三四五六七八", preserveLongToken = true))
        // 有关白时 preserveLongToken 不生效，仍然照常截断（并去掉截断点后的空格）。
        assertEquals("ab cd…", compactText("ab cd ef gh", preserveLongToken = true))
    }

    @Test
    fun `code point helpers never split a surrogate pair`() {
        // emoji 是代理对（2 个 Char / 1 个码点）。按 Char 截会把最后一个字劈成乱码。
        val emoji = "\uD83D\uDE00"
        assertEquals(1, codePointCount(emoji))
        assertEquals(emoji, takeCodePoints(emoji + emoji, 1))
        assertEquals(emoji + emoji, takeCodePoints(emoji + emoji, 2))
    }

    // =======================================================================
    // 6. 超级岛断句
    // =======================================================================

    @Test
    fun `take by weight counts CJK as two columns`() {
        assertEquals("ABCD", XiaomiSuperIslandLayout.takeByWeight("ABCDEFG", 4))
        // 中文一个字占两格。
        assertEquals("你好", XiaomiSuperIslandLayout.takeByWeight("你好世界", 4))
        // 放不下第二个字时只取一个，而不是硬塞。
        assertEquals("你", XiaomiSuperIslandLayout.takeByWeight("你好世界", 3))
        assertEquals("", XiaomiSuperIslandLayout.takeByWeight("   ", 4))
    }

    @Test
    fun `weight for characters doubles with a floor of one`() {
        assertEquals(14, XiaomiSuperIslandLayout.weightForCharacters(7))
        assertEquals(2, XiaomiSuperIslandLayout.weightForCharacters(0))
    }

    @Test
    fun `full lyric splits into balanced halves`() {
        val split = XiaomiSuperIslandLayout.splitFullLyric(
            text = "一二三四五六",
            showLeftCover = false,
            leftMaxWeight = 6,
            rightMaxWeight = 6,
        )
        assertEquals("一二三", split.left)
        assertEquals("四五六", split.right)
    }

    @Test
    fun `full lyric drops the tail when the line does not fit at all`() {
        // 故意保留的行为（与上游一致）：两列都塞不下时，宁可少显示也不把一列撑爆。
        // 岛里的列宽是固定的，撑爆的后果是整行被系统截断且左右错位。
        val split = XiaomiSuperIslandLayout.splitFullLyric(
            text = "一二三四五六七八",
            showLeftCover = false,
            leftMaxWeight = 4,
            rightMaxWeight = 4,
        )
        assertEquals("一二", split.left)
        assertEquals("三四", split.right)
    }

    @Test
    fun `full lyric on empty input yields two empty halves`() {
        val split = XiaomiSuperIslandLayout.splitFullLyric("   ", false, 12, 12)
        assertEquals("", split.left)
        assertEquals("", split.right)
    }

    @Test
    fun `take by weight keeps emoji intact`() {
        // 代理对被按码点处理，不会只取到半个。
        val emoji = "\uD83D\uDE00"
        assertEquals(emoji, XiaomiSuperIslandLayout.takeByWeight(emoji, 2))
    }
}
