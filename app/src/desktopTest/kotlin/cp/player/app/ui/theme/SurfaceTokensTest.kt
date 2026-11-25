package cp.player.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 表面层级令牌的回归测试 —— 钉住 **M3E 分组模型**。
 *
 * 规则只有一条：**卡片恒定浮起**。深浅两色下卡片都比页面亮，内嵌槽比卡片暗，
 * 浮层不暗于卡片。这与「普通内容流的单调容器阶梯」是两套模型，别混。
 *
 * 踩过的坑：
 * 1. 把分组模型误判成「阶梯倒置」改成白底灰卡 ⇒ 浅色卡片糊在页面上；
 * 2. 写死 `surfaceContainerLowest` 做卡片 ⇒ 深色下它是 #000，变成黑卡；
 * 3. 纯黑模式只压最低两级，卡片停在绝对值上 ⇒ 黑底上一块灰板。
 */
@OptIn(ExperimentalTestApi::class)
class SurfaceTokensTest {

    @Test
    fun `cover color transition settles and stops invalidating the scene`() {
        val seed = mutableStateOf(Color(0xffd54c53))
        var primary = Color.Unspecified
        val scene = ImageComposeScene(width = 80, height = 80, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK, colorSource = ColorSource.COVER, coverSeed = seed.value) {
                primary = MaterialTheme.colorScheme.primary
                Box(Modifier.fillMaxSize().background(primary))
            }
        }
        try {
            var time = 0L
            fun renderFrames(count: Int) {
                repeat(count) {
                    scene.render(time).close()
                    time += 16_666_667L
                }
            }
            renderFrames(90)
            val initial = primary
            seed.value = Color(0xff3d7bdd)
            renderFrames(90)
            val settled = primary
            assertNotEquals(initial, settled, "换封面后主题应切换到新颜色")
            assertTrue(!scene.hasInvalidations(), "颜色过渡结束后不应继续请求重组")
            renderFrames(10)
            assertEquals(settled, primary)
        } finally {
            scene.close()
        }
    }

    private class Layers(
        val page: Color,
        val card: Color,
        val nested: Color,
        val overlay: Color,
        val overlayInner: Color,
    )

    private fun layersOf(
        themeMode: ThemeMode,
        pureBlack: Boolean = false,
    ): Layers {
        var captured: Layers? = null
        runComposeUiTest {
            setContent {
                CpTheme(
                    themeMode = themeMode,
                    colorSource = ColorSource.FIXED,
                    pureBlack = pureBlack,
                ) {
                    captured = Layers(
                        page = cpPageSurface(),
                        card = cpCardSurface(),
                        nested = cpNestedSurface(),
                        overlay = cpSheetSurface(),
                        overlayInner = cpSheetInnerSurface(),
                    )
                }
            }
            waitForIdle()
        }
        return requireNotNull(captured) { "主题没有产出层级快照" }
    }

    private fun assertLighter(inner: Color, outer: Color, label: String, minDelta: Float = 0f) {
        val delta = inner.luminance() - outer.luminance()
        assertTrue(
            delta >= minDelta,
            "$label：卡片模型要求前者更亮，实际 Δ亮度=$delta " +
                "(inner=${inner.luminance()} outer=${outer.luminance()})",
        )
    }

    @Test
    fun `浅色下卡片浮于染色背景之上`() {
        val l = layersOf(ThemeMode.LIGHT)
        assertLighter(l.card, l.page, "浅色：卡片 vs 页面", minDelta = 0.008f)
        // 内嵌槽是白卡里的凹陷，比卡片暗
        assertTrue(
            l.nested.luminance() < l.card.luminance(),
            "浅色内嵌槽应比白卡暗（凹陷感），实际槽=${l.nested.luminance()} 卡=${l.card.luminance()}",
        )
        // 浅色浮层与卡片同为最高阶白，靠 scrim + 阴影区分，不暗于卡片
        assertTrue(
            l.overlay.luminance() >= l.card.luminance(),
            "浅色浮层不应暗于卡片，实际浮层=${l.overlay.luminance()} 卡=${l.card.luminance()}",
        )
        assertLighter(l.overlay, l.overlayInner, "浅色：浮层 vs 浮层内块", minDelta = 0.05f)
    }

    @Test
    fun `深色下卡片浮于深色背景之上`() {
        val l = layersOf(ThemeMode.DARK)
        assertLighter(l.card, l.page, "深色：卡片 vs 页面", minDelta = 0.008f)
        assertTrue(
            l.nested.luminance() < l.card.luminance(),
            "深色内嵌槽应比亮卡暗（凹陷感）",
        )
        assertLighter(l.overlay, l.card, "深色：浮层 vs 卡片")
        assertLighter(l.overlay, l.overlayInner, "深色：浮层 vs 浮层内块", minDelta = 0.008f)
    }

    @Test
    fun `卡片与背景差得开`() {
        // 用相对亮度而非 8/255：深色低亮度端 luminance 被强烈压缩，
        // L*6→L*18（12 个明度点，肉眼很明显）的 luminance 差只有 4.6/255。
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val l = layersOf(mode)
            val delta = l.card.luminance() - l.page.luminance()
            assertTrue(
                delta >= 0.008f,
                "$mode 下卡片只比页面亮 ${String.format("%.4f", delta)} —— 低于肉眼可辨的层级差",
            )
        }
    }

    @Test
    fun `深色页面不是纯黑`() {
        val l = layersOf(ThemeMode.DARK)
        assertTrue(
            l.page.luminance() >= 0.006f,
            "深色页面亮度 ${l.page.luminance()} 太低 —— 普通深色要回到经典 M3 的 tone 6，" +
                "OLED 熄屏是 pureBlack 开关的事",
        )
    }

    @Test
    fun `纯黑模式页面是纯黑但卡片仍浮得起来`() {
        val l = layersOf(ThemeMode.DARK, pureBlack = true)
        assertTrue(
            l.page.luminance() == 0f,
            "纯黑模式的页面就该是 #000（用户开这个开关的唯一目的就是熄屏）",
        )
        assertLighter(l.card, l.page, "纯黑：卡片 vs 页面", minDelta = 0.002f)
        assertLighter(l.overlay, l.card, "纯黑：浮层 vs 卡片")
        assertLighter(l.overlay, l.overlayInner, "纯黑：浮层 vs 浮层内块", minDelta = 0.005f)
    }
}
