package cp.player.app.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.LocalAppFontFamilies
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.theme.buildAppStyledText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * **歌词逐 run 字体路由的回归断言**（编译与普通单测都量不到字形，只能比像素）。
 *
 * 被验证的行为：日语歌词里的**拉丁字母 / 数字 / 半角标点**必须落回 Google Sans Flex，
 * 不能跟着整行一起变成 Noto Sans JP 的字形。
 *
 * 背景：歌词库只收一个 `TextStyle`，改造前 `LyricContent` 是整行
 * `copy(fontFamily = Noto Sans JP)` 的 —— 于是 `Tokyo` / `03:45` / `Go!`
 * 全被 Noto 的拉丁字形覆盖。现在由 `ScriptFontLyricsProfile` 在库内部按脚本切 span。
 *
 * 三条断言：
 *
 * 1. **日英混排**：「整行钉到日文字体」与「逐 run 路由」必须**不同**（拉丁真的换走了）。
 * 2. **纯拉丁**：套 span 与不套 span 必须**逐字节相同**（`TextScript.LATIN` 的语义是
 *    「沿用样式的默认家族」，绝不能偷偷替换）。
 * 3. 出对照图肉眼可核对（`app/build-scriptfont2/routing/`）。
 *
 * ⚠️ 断言比较的是**编码后的像素字节**：字体不同必然像素不同（同一台机器、同一套 Skia、
 * 无动画与时间因素）。渲染两次是为了让桌面端的异步字体加载落地。
 */
class ScriptFontRoutingPixelTest {

    private val mixedSample = "Tokyo の夜 03:45 Go!"
    private val latinSample = "Now Playing 01:23 / 04:56"

    /**
     * 渲染一段文本。
     *
     * @param pinJapanese true = 改造前的做法（整行 `fontFamily = 日文字体`，不套 span）；
     *   false = 逐 run 路由（拉丁不套 span，假名 / 汉字套日文字体）。
     */
    private fun renderBytes(
        text: String,
        height: Int,
        pinJapanese: Boolean,
        fontSize: Int = 40,
    ): ByteArray {
        val scene = ImageComposeScene(width = 760, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    val families = LocalAppFontFamilies.current
                    val measurer = rememberTextMeasurer(cacheSize = 0)
                    val base = TextStyle(fontSize = fontSize.sp, color = Color.Black)
                    val style =
                        if (pinJapanese) base.copy(fontFamily = families.japanese) else base
                    val annotated =
                        if (pinJapanese) AnnotatedString(text) else buildAppStyledText(text, families)
                    val layout = measurer.measure(annotated, style, softWrap = false)
                    // 与 `DefaultLyricsProfile.draw` 的做法一致：直接 drawText(layout)。
                    Canvas(Modifier.fillMaxSize()) { drawText(layout, topLeft = Offset(12f, 12f)) }
                }
            }
        }
        return try {
            scene.render()
            scene.render().encodeToData()?.bytes ?: error("encode failed")
        } finally {
            scene.close()
        }
    }

    @Test
    fun latinInsideJapaneseLineIsNotNotoSansJp() {
        val wholeJapanese = renderBytes(mixedSample, height = 120, pinJapanese = true)
        val routed = renderBytes(mixedSample, height = 120, pinJapanese = false)
        assertNotEquals(
            wholeJapanese.toList(),
            routed.toList(),
            "逐 run 路由没有改变像素：拉丁 / 数字仍被日文字体覆盖（回退成了整行换家族）",
        )
    }

    @Test
    fun latinOnlyTextIsUnaffectedBySpans() {
        // 同一段纯拉丁：套 span 与不套 span 必须逐字节相同。
        val noSpan = renderLatin(fontSize = 36, withSpans = false)
        val withSpan = renderLatin(fontSize = 36, withSpans = true)
        assertEquals(
            noSpan.toList(),
            withSpan.toList(),
            "纯拉丁文本被 span 改动了：LATIN 不应做任何家族替换",
        )
    }

    private fun renderLatin(fontSize: Int, withSpans: Boolean): ByteArray {
        val scene = ImageComposeScene(width = 760, height = 100, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    val families = LocalAppFontFamilies.current
                    val measurer = rememberTextMeasurer(cacheSize = 0)
                    val style = TextStyle(fontSize = fontSize.sp, color = Color.Black)
                    val annotated =
                        if (withSpans) buildAppStyledText(latinSample, families)
                        else AnnotatedString(latinSample)
                    val layout = measurer.measure(annotated, style, softWrap = false)
                    Canvas(Modifier.fillMaxSize()) { drawText(layout, topLeft = Offset(12f, 12f)) }
                }
            }
        }
        return try {
            scene.render()
            scene.render().encodeToData()?.bytes ?: error("encode failed")
        } finally {
            scene.close()
        }
    }

    /** 出对照图：上 = 整行日文字体（改造前），下 = 逐 run 路由（改造后）。 */
    @Test
    fun renderComparisonBoard() {
        val out = File("build-scriptfont2/routing").apply { mkdirs() }
        File(out, "routing-whole-noto.png").writeBytes(
            renderBytes(mixedSample, height = 120, pinJapanese = true)
        )
        File(out, "routing-per-run.png").writeBytes(
            renderBytes(mixedSample, height = 120, pinJapanese = false)
        )
    }

    /** 顺带把「日文字体可用」这个前提显式断言出来，避免字体缺位时上面两条静默失效。 */
    @Test
    fun japaneseFamilyIsBundledOnDesktop() {
        var available: FontFamily? = null
        val scene = ImageComposeScene(width = 64, height = 64, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                available = LocalAppFontFamilies.current.japanese
                Box(Modifier.fillMaxSize())
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        kotlin.test.assertNotNull(
            available,
            "桌面端日文字体没有加载：本页重定向的 buildDirectory 可能没带上 desktopMain/resources/fonts",
        )
    }
}
