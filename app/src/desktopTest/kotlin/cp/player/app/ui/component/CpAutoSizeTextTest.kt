package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.PngImage
import cp.player.app.readPng
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CpAutoSizeText] 的离屏版式探针。
 *
 * 编译与单测都量不到「最终用了多大字号」，只能在真实布局回调里读
 * [TextLayoutResult.layoutInput]。这里盯两件事：长标题必须缩到不溢出，
 * 短标题必须保持原字号（不能把短文本反向放大）。
 */
class CpAutoSizeTextTest {

    private val longTitle = "这是一个非常非常长的歌曲标题，需要在固定宽度内自动缩小字号而不是直接省略"
    private val shortTitle = "短标题"

    private fun scene(
        text: String,
        width: Int = 360,
        minFontSize: TextUnit = 8.sp,
        onLayout: (TextLayoutResult) -> Unit,
    ): ImageComposeScene = ImageComposeScene(width = width, height = 100, density = Density(1f)) {
        CpTheme(themeMode = ThemeMode.DARK) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
            ) {
                CpAutoSizeText(
                    text = text,
                    style = TextStyle(fontSize = 32.sp, lineHeight = 40.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    minFontSize = minFontSize,
                    maxLines = 1,
                    onTextLayout = onLayout,
                )
            }
        }
    }

    private fun ImageComposeScene.renderFrames() {
        repeat(4) { index -> render(index * 16_000_000L) }
    }

    private fun ImageComposeScene.renderPng(name: String): PngImage {
        renderFrames()
        val bytes = render(64_000_000L).encodeToData()?.bytes ?: error("PNG encode failed")
        val outDir = File("build/preview-autosize").apply { mkdirs() }
        File(outDir, "$name.png").writeBytes(bytes)
        return readPng(bytes)
    }

    /** 相对背景色有明显差异的像素包围盒：[minX, minY, maxX, maxY]。 */
    private fun PngImage.inkBounds(background: Int): IntArray {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = getRGB(x, y)
                val distance =
                    abs(((color shr 16) and 0xFF) - ((background shr 16) and 0xFF)) +
                        abs(((color shr 8) and 0xFF) - ((background shr 8) and 0xFF)) +
                        abs((color and 0xFF) - (background and 0xFF))
                if (distance > 24) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        check(maxX >= 0) { "离屏画面里没有文字墨迹" }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun longTextShrinksToFitTheSlot() {
        var result: TextLayoutResult? = null
        val scene = scene(longTitle) { result = it }
        try {
            scene.renderFrames()
            val layout = checkNotNull(result) { "onTextLayout 没有被调用" }
            assertFalse(layout.hasVisualOverflow, "缩字后仍然溢出")
            assertEquals(1, layout.lineCount)
            assertTrue(
                layout.layoutInput.style.fontSize.value < 32f,
                "长标题没有缩小字号：${layout.layoutInput.style.fontSize}",
            )
            assertTrue(
                layout.layoutInput.style.fontSize.value >= 8f,
                "字号缩到了下限以下：${layout.layoutInput.style.fontSize}",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun shortTextKeepsTheConfiguredFontSize() {
        var result: TextLayoutResult? = null
        val scene = scene(shortTitle) { result = it }
        try {
            scene.renderFrames()
            val layout = checkNotNull(result) { "onTextLayout 没有被调用" }
            assertFalse(layout.hasVisualOverflow)
            assertEquals(32f, layout.layoutInput.style.fontSize.value)
        } finally {
            scene.close()
        }
    }

    @Test
    fun textStillOverflowingAtMinFontFallsBackToEllipsis() {
        var result: TextLayoutResult? = null
        val scene = scene(text = longTitle, width = 60, minFontSize = 24.sp) { result = it }
        try {
            scene.renderFrames()
            val layout = checkNotNull(result) { "onTextLayout 没有被调用" }
            assertEquals(
                24f,
                layout.layoutInput.style.fontSize.value,
                "缩到下限仍放不下时不应继续缩字",
            )
            assertTrue(
                layout.hasVisualOverflow,
                "极小槽位应保留省略号兜底，而不是假装放得下",
            )
            assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
        } finally {
            scene.close()
        }
    }

    @Test
    fun offscreenRenderShrinksGlyphsInsideTheSlot() {
        val longScene = scene(longTitle) {}
        val shortScene = scene(shortTitle) {}
        try {
            val longImage = longScene.renderPng("autosize-long")
            val shortImage = shortScene.renderPng("autosize-short")
            val background = longImage.getRGB(2, 2)
            val longInk = longImage.inkBounds(background)
            val shortInk = shortImage.inkBounds(background)

            val longWidth = longInk[2] - longInk[0] + 1
            val longHeight = longInk[3] - longInk[1] + 1
            val shortHeight = shortInk[3] - shortInk[1] + 1

            assertTrue(longWidth > 240, "长标题应铺满槽位，实际墨迹宽 $longWidth")
            assertTrue(
                shortHeight > longHeight * 3 / 2,
                "缩字后字高应明显低于短标题：long=$longHeight short=$shortHeight",
            )
            assertTrue(
                longInk[0] >= 8 && longInk[2] <= 354,
                "文字越出内边距：${longInk.toList()}",
            )
        } finally {
            longScene.close()
            shortScene.close()
        }
    }
}
