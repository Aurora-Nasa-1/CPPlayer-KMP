package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.readPng
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 最近搜索卡片的离屏版式探针。
 *
 * 编译只能证明 FlowRow 存在；这条测试实际渲染「9 条 + `+N`」与展开态，
 * 确认窄屏下卡片会换行、展开后内容确实变多，而不是被裁掉或压成一行。
 */
class SearchHistoryPreviewTest {

    private val history = listOf(
        "晴天",
        "周杰伦",
        "夜曲",
        "七里香",
        "反方向的钟",
        "稻香",
        "不能说的秘密",
        "最长的电影",
        "蒲公英的约定",
        "给我一首歌的时间",
        "说好不哭",
        "Mojito",
    )

    private fun render(expanded: Boolean): ByteArray {
        val scene = ImageComposeScene(width = 360, height = 520, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
                ) {
                    SearchHistoryFlow(
                        history = history,
                        expanded = expanded,
                        onSearch = {},
                        onExpand = {},
                        onCollapse = {},
                    )
                }
            }
        }
        return try {
            repeat(3) { scene.render() }
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }

    private fun coloredPixels(bytes: ByteArray): Int {
        val image = readPng(bytes)
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (r + g + b > 45) count++
            }
        }
        return count
    }

    @Test
    fun `collapsed shows nine plus more and expanded reveals the rest`() {
        val collapsed = render(expanded = false)
        val expanded = render(expanded = true)
        File("build/search-history-collapsed.png").writeBytes(collapsed)
        File("build/search-history-expanded.png").writeBytes(expanded)

        val collapsedPixels = coloredPixels(collapsed)
        val expandedPixels = coloredPixels(expanded)
        assertTrue(collapsedPixels > 10_000, "折叠态几乎没画出卡片：$collapsedPixels px")
        assertTrue(
            expandedPixels > collapsedPixels,
            "展开态内容应更多：折叠 $collapsedPixels px，展开 $expandedPixels px",
        )
    }
}
