package cp.player.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptFontLyricsRoutingTest {

    private val dummyJapanese = FontFamily.Serif
    private val dummyMiSans = FontFamily.Cursive
    private val testFamilies = AppFontFamilies(
        japanese = dummyJapanese,
        misans = dummyMiSans,
    )

    @Test
    fun `cjkFaceFor respects isJapanese override`() {
        // 无假名汉字，默认归属中文 MiSans
        assertEquals(AppFontFace.MISANS, cjkFaceFor("世界", testFamilies))
        // 显式指定 isJapanese = true 时，即使纯汉字也归属 Noto Sans JP
        assertEquals(AppFontFace.NOTO_SANS_JP, cjkFaceFor("世界", testFamilies, isJapanese = true))
        // 显式指定 isJapanese = false 时，即使含假名也归属 MiSans
        assertEquals(AppFontFace.MISANS, cjkFaceFor("世界で", testFamilies, isJapanese = false))
        // 自动判定：含假名归属 Noto Sans JP
        assertEquals(AppFontFace.NOTO_SANS_JP, cjkFaceFor("世界で", testFamilies))
    }

    @Test
    fun `buildAppStyledText routes kanji in japanese lyrics to japanese font`() {
        // 在日文歌词环境下，单独的日文汉字词（无假名）必须使用 Noto Sans JP，绝不能变成 MiSans
        val styled = buildAppStyledText("運命", testFamilies, isJapanese = true)
        assertEquals(1, styled.spanStyles.size)
        assertEquals(dummyJapanese, styled.spanStyles[0].item.fontFamily)
        assertEquals(0, styled.spanStyles[0].start)
        assertEquals(2, styled.spanStyles[0].end)
    }

    @Test
    fun `buildAppStyledText keeps latin unstyled while routing kanji to japanese`() {
        // 日英混排：拉丁字母不套 span（沿用样式的默认家族 Google Sans Flex），汉字套 Noto Sans JP
        val styled = buildAppStyledText("Tokyo 東京 03:45", testFamilies, isJapanese = true)
        // 只有「東京」两字被套上日文字体
        assertEquals(1, styled.spanStyles.size)
        assertEquals(dummyJapanese, styled.spanStyles[0].item.fontFamily)
        assertEquals(6, styled.spanStyles[0].start)
        assertEquals(8, styled.spanStyles[0].end)
    }

    @Test
    fun `buildAppStyledText routes chinese text to misans`() {
        // 中文文本：汉字套 MiSans
        val styled = buildAppStyledText("世界", testFamilies, isJapanese = false)
        assertEquals(1, styled.spanStyles.size)
        assertEquals(dummyMiSans, styled.spanStyles[0].item.fontFamily)
    }

    @Test
    fun `scriptFontLyricsProfile matches syllables with kanji or kana`() {
        val profile = cp.player.app.ui.component.ScriptFontLyricsProfile(testFamilies, isJapaneseLyrics = true)
        // 汉字音节应被接管
        assertTrue(profile.matches(KaraokeSyllable(content = "君", start = 0, end = 1000, phonetic = "")))
        // 假名音节应被接管
        assertTrue(profile.matches(KaraokeSyllable(content = "が", start = 1000, end = 2000, phonetic = "")))
    }
}
