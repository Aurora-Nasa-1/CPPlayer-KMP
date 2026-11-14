package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.PngImage
import cp.player.app.readPng
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [CpExpandableText] 的离屏版式探针。
 *
 * 这条测试盯的是一次真实回归：溢出判据写成 `layout.lineCount > collapsedMaxLines`
 * 时**永远不成立**（被 `maxLines` 截断后行数就等于上限），按钮从不出现，长正文
 * 就退化成静默截断 —— 而这正是这个组件存在的理由。判据只能走像素：
 * 折叠态点「查看全部」之后，画出来的文字行数必须变多。
 */
class CpExpandableTextLayoutTest {

    private val longText = buildString {
        repeat(12) { index ->
            append("第${index + 1}段：这是一段足够长的歌单简介，用来把折叠行数撑满。")
        }
    }

    private fun scene(content: @androidx.compose.runtime.Composable () -> Unit): ImageComposeScene =
        ImageComposeScene(width = 280, height = 420, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) { content() }
            }
        }

    /** 亮像素行号：正文与按钮都落在这些行里，行数直接反映「画出了多少内容」。 */
    private fun brightRows(image: PngImage): List<Int> = (0 until image.height).filter { y ->
        (0 until image.width).any { x ->
            val argb = image.getRGB(x, y)
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            (r + g + b) / 3 > 90
        }
    }

    private fun ImageComposeScene.shot(timeNanos: Long): Pair<PngImage, List<Int>> {
        repeat(4) { step -> render(timeNanos + step * 16_000_000L) }
        val bytes = render().encodeToData()?.bytes ?: error("PNG encoding failed")
        val image = readPng(bytes)
        return image to brightRows(image)
    }

    private fun ImageComposeScene.click(x: Float, y: Float) {
        sendPointerEvent(PointerEventType.Press, Offset(x, y), Offset.Zero, 0L, PointerType.Touch)
        sendPointerEvent(PointerEventType.Release, Offset(x, y), Offset.Zero, 0L, PointerType.Touch)
    }

    @Test
    fun longTextCollapsesThenExpandsAndCanCollapseAgain() {
        val scene = scene {
            CpExpandableText(
                text = longText,
                collapsedMaxLines = 4,
                style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                color = Color.White,
            )
        }
        try {
            val (collapsedImage, collapsedRows) = scene.shot(0L)
            assertTrue(collapsedRows.isNotEmpty(), "折叠态什么都没画出来")
            // 按钮在最后一行：点它。
            scene.click(x = 20f, y = collapsedRows.last().toFloat())
            val (_, expandedRows) = scene.shot(200_000_000L)
            assertTrue(
                expandedRows.size > collapsedRows.size + 20,
                "点了「查看全部」没有多出内容：折叠 ${collapsedRows.size} 行 → 展开 ${expandedRows.size} 行",
            )
            // 展开后按钮变成「收起」，再点一次应当回到折叠高度。
            scene.click(x = 20f, y = expandedRows.last().toFloat())
            val (_, recollapsedRows) = scene.shot(400_000_000L)
            assertTrue(
                recollapsedRows.size < expandedRows.size,
                "「收起」没有生效：展开 ${expandedRows.size} 行 → 收起 ${recollapsedRows.size} 行",
            )
            assertTrue(
                collapsedImage.height == 420,
                "离屏画布尺寸被改写：${collapsedImage.height}",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun shortTextDoesNotRenderAffordance() {
        val short = "短简介"
        val scene = scene {
            CpExpandableText(
                text = short,
                collapsedMaxLines = 4,
                style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                color = Color.White,
            )
        }
        try {
            val (_, rows) = scene.shot(0L)
            assertTrue(rows.isNotEmpty(), "短文本没画出来")
            // 一行正文（行高 24sp）远小于「正文 + 一行按钮」。
            assertTrue(rows.size < 40, "短文本不应出现折叠按钮，实际占 ${rows.size} 行像素")
        } finally {
            scene.close()
        }
    }
}
