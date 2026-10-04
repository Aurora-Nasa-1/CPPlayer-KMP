package cp.player.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * 守卫：**滚动容器不能嵌进另一个滚动容器的 item**。
 *
 * 真实 bug（2026-10-04 修）：听歌报告的「最近」tab 写成
 * `item { RecentPlaysScreen(embedded = true).Content() }` —— 把一整页
 * （自带 `LazyColumn` + `fillMaxSize`）塞进外层 `LazyColumn` 的一个 item。
 * `LazyColumn` 的 item 高度**无界**，内层滚动容器拿到 `maxHeight = Infinity`，
 * Compose 直接抛
 * `IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints` ⇒ 点开「最近」tab 整页崩，桌面与安卓同一份代码。
 *
 * 正解是把「行」铺进外层列表（`recentPlaysRows`），滚动容器只有一层。
 *
 * 本测试钉的是**结构约束**：反例必须抛、正例必须能出内容。
 * 它不验具体页面的版式 —— 那由离屏出图负责。
 */
class NestedScrollGuardTest {

    private val rows = (1..30).map { "第 $it 首" }

    @Test
    fun nestingWholePageInItemIsIllegal() {
        val error = runCatching { render { antiPattern() } }.exceptionOrNull()
        val msg = error?.message.orEmpty()
        println("反例结果 -> ${error?.let { it::class.simpleName } ?: "无异常"}: ${msg.take(160)}")
        // 反例的报错文案由Compose 决定，宽松匹配两个关键词即可。
        // 万一不再抛，说明该结构变合法了 —— 那时要重新评估是否还有此约束。
        kotlin.test.assertTrue(
            msg.contains("infinity maximum height") || msg.contains("Vertically scrollable"),
            "把滚动容器嵌进滚动容器的 item 应当抛「infinity maximum height」；" +
                "若不再抛异常，说明该结构已变合法，请更新本测试与相关文档。实际：$error",
        )
    }

    @Test
    fun flatteningRowsIntoOuterListWorks() {
        val nonBlank = render { correctPattern() }
        println("正例渲染非背景像素数 = $nonBlank")
        kotlin.test.assertTrue(nonBlank > 1000, "扁平结构应渲染出内容，实际非背景像素 $nonBlank")
    }

    @Composable
    private fun antiPattern() {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxSize()) {
                    Text("内层标题")
                    LazyColumn(Modifier.fillMaxSize()) { items(rows) { Text(it) } }
                }
            }
        }
    }

    @Composable
    private fun correctPattern() {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text("段标题") }
            items(rows) { Text(it) }
        }
    }

    /**
     * 渲染一帧，返回**与首像素不同**的像素数。
     *
     * 用「非背景像素数」而不是几何尺寸：`ImageComposeScene` 不暴露尺寸，
     * 而「画没画出东西」这件事像素检测更直接。
     */
    private fun render(content: @Composable () -> Unit): Int {
        val scene = ImageComposeScene(
            width = 400,
            height = 800,
            density = Density(1f),
            content = content,
        )
        return try {
            val bytes = scene.render().encodeToData()?.bytes ?: error("encode failed")
            val png = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("decode failed")
            val argb = IntArray(png.width * png.height)
            png.getRGB(0, 0, png.width, png.height, argb, 0, png.width)
            val bg = argb[0]
            argb.count { it != bg }
        } finally {
            scene.close()
        }
    }
}