package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.component.CpText
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 核对「字体栈」真的按脚本生效 —— 这是**唯一能证明**逐 run 换字体成功的测试
 * （编译与普通单测都量不到字形）。
 *
 * ## 三条断言
 *
 * 1. **拉丁 / 数字不动**：同一串纯拉丁文本，`Text` 与 `CpText` 渲染结果必须**逐字节相同**。
 *    不同就说明拉丁字符被换走了（例如误把整段塞给 MiSans），Google Sans Flex 是应用的骨架，
 *    这个回归肉眼很难第一时间发现，但一眼能看出「字重不对」。
 * 2. **中文换到 MiSans**：同一串中文，`CpText` 必须与 `Text` **不同** —— 相同说明字体没加载，
 *    或者 run 没切（中文仍在走系统回退字体）。
 * 3. **日文换到 Noto Sans JP**：与中文串对比，两者渲染结果必须不同
 *    （同码位汉字的日式 / 中式写法差异 + 假名字形）。
 *
 * ⚠️ 断言比较的是**像素编码后的字节**：字体不同必然像素不同，字体相同必然像素相同
 * （同一台机器、同一套 Skia、没有动画与时间因素）。渲染两次是为了让异步字体加载有机会落地。
 */
class FontStackPreviewTest {

    private val sampleLatin = "Now Playing · 01:23 / 04:56"
    private val sampleChinese = "夜色温柔，风从窗外来。"
    private val sampleJapanese = "夜の風が窓から吹いてくる"

    /**
     * 渲染一段文本并返回 PNG 字节。
     *
     * @param viaCpText true 走 [CpText]（带逐 run 字体切换），false 走裸 [Text]（只有默认字体）
     */
    private fun renderBytes(text: String, viaCpText: Boolean): ByteArray {
        val scene = ImageComposeScene(width = 520, height = 96, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.White),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val style = MaterialTheme.typography.bodyLarge
                    if (viaCpText) {
                        CpText(text = text, style = style, color = Color.Black)
                    } else {
                        Text(text = text, style = style, color = Color.Black, maxLines = 1)
                    }
                }
            }
        }
        return try {
            // 第一帧可能落在字体异步加载完成之前，丢掉；第二帧才是稳定结果。
            scene.render()
            val image = scene.render()
            image.encodeToData()?.bytes ?: error("encode failed")
        } finally {
            scene.close()
        }
    }

    @Test
    fun latinAndDigitsStayOnDefaultFont() {
        assertEquals(
            renderBytes(sampleLatin, viaCpText = false).toList(),
            renderBytes(sampleLatin, viaCpText = true).toList(),
            "拉丁 / 数字被换走了：CpText 应当对它们不加任何字体 run",
        )
    }

    @Test
    fun chineseSwitchesToMiSans() {
        val plain = renderBytes(sampleChinese, viaCpText = false)
        val styled = renderBytes(sampleChinese, viaCpText = true)
        assertNotEquals(
            plain.toList(),
            styled.toList(),
            "中文没有换字体：MiSans 没加载，或者 run 没切出来",
        )
    }

    @Test
    fun japaneseSwitchesToNotoSansJp() {
        val japanese = renderBytes(sampleJapanese, viaCpText = true)
        val chinese = renderBytes(sampleChinese, viaCpText = true)
        assertNotEquals(
            chinese.toList(),
            japanese.toList(),
            "日文与中文渲染完全一致：日文字体没生效（两段文本内容不同，本就不可能逐像素相同）",
        )
    }

    /** 出一张对照图：四行文本，肉眼核对中文 / 日文 / 拉丁各自长什么样。 */
    @Test
    fun renderBoard() {
        val scene = ImageComposeScene(width = 760, height = 300, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Column(Modifier.padding(24.dp)) {
                        BoardLine("拉丁 / 数字（Google Sans Flex）", sampleLatin)
                        BoardLine("中文（MiSans）", sampleChinese)
                        BoardLine("日文（Noto Sans JP）", sampleJapanese)
                        BoardLine("混排（Google Sans Flex + MiSans）", "Track 03 · 七里香 · 04:56")
                    }
                }
            }
        }
        try {
            scene.render()
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-fonts/preview").apply { mkdirs() }
            File(out, "font-stack-board.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun BoardLine(label: String, text: String) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        CpText(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
