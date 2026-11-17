package cp.player.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 页面 / 卡片层级的回归测试。
 *
 * 钉住两件**实际发生过**的事：
 * 1. 深色页面层曾经取 `surfaceContainerLowest`，在纯黑模式里被压成 `#000`，
 *    整窗看起来「背景是纯黑的」；
 * 2. 页面与卡片只差一级时，浅色下卡片完全糊在页面上（历史 #FBF8FF 那次）。
 */
@OptIn(ExperimentalTestApi::class)
class SurfaceTokensTest {

    @Test
    fun `深色页面层不是纯黑且明显亮于卡片层`() = runComposeUiTest {
        var page: Color? = null
        var card: Color? = null
        setContent {
            CpTheme(themeMode = ThemeMode.DARK, colorSource = ColorSource.FIXED) {
                page = cpPageSurface()
                card = cpCardSurface()
            }
        }
        waitForIdle()

        val pageColor = requireNotNull(page)
        assertTrue(
            pageColor.luminance() > 0.005f,
            "深色页面层亮度 ${pageColor.luminance()} 太低 —— 背景看起来会是纯黑的",
        )
        assertTrue(
            requireNotNull(card).luminance() > pageColor.luminance(),
            "深色下卡片必须比页面亮，否则层级只能靠描边",
        )
    }

    @Test
    fun `纯黑模式下页面层仍然可见且卡片更亮`() = runComposeUiTest {
        var page: Color? = null
        var card: Color? = null
        setContent {
            CpTheme(
                themeMode = ThemeMode.DARK,
                colorSource = ColorSource.FIXED,
                pureBlack = true,
            ) {
                page = cpPageSurface()
                card = cpCardSurface()
            }
        }
        waitForIdle()

        val pageColor = requireNotNull(page)
        assertTrue(
            pageColor.luminance() > 0.002f,
            "纯黑模式下页面层也不该被压成 #000（那是 surface 的角色，不是页面层）",
        )
        assertTrue(
            requireNotNull(card).luminance() > pageColor.luminance(),
            "纯黑模式下卡片仍要比页面亮一档",
        )
    }

    @Test
    fun `浅色页面与卡片至少差三个色阶`() = runComposeUiTest {
        var page: Color? = null
        var card: Color? = null
        setContent {
            CpTheme(themeMode = ThemeMode.LIGHT, colorSource = ColorSource.FIXED) {
                page = cpPageSurface()
                card = cpCardSurface()
            }
        }
        waitForIdle()

        val pageColor = requireNotNull(page)
        val cardColor = requireNotNull(card)
        val delta = (cardColor.luminance() - pageColor.luminance()) * 255f
        assertTrue(
            delta >= 8f,
            "浅色下卡片比页面只亮 $delta/255 —— 低于肉眼可辨的层级差，卡片会糊在页面上",
        )
    }
}
