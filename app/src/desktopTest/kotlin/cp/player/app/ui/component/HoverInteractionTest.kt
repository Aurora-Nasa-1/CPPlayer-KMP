package cp.player.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 悬停事件的**单一来源**回归测试。
 *
 * ### 背景：桌面端「悬停色闪烁」
 *
 * 桌面播放页的几个圆钮曾经同时写了：
 * ```kotlin
 * Surface(onClick = ..., interactionSource = interaction)   // clickable，自己就会发 hover
 *     .hoverable(interaction)                               // 又手动挂了一个 hoverable
 * ```
 * 同一个 `MutableInteractionSource` 上挂两个 hover provider，指针在节点内部移动时
 * 两者的 Enter/Exit 时机不同 ⇒ `collectIsHoveredAsState()` 在 true/false 之间反复翻，
 * 悬停底色跟着在两个颜色之间闪。
 *
 * ### 这里钉住什么
 *
 * `Surface(onClick = ..., interactionSource = src)` **自己**就会把悬停事件发到 `src`。
 * 所以调用点不需要（也不允许）再叠 `.hoverable(src)`。这条一旦被 Compose 改掉，
 * 测试会先红，而不是等用户看到「悬停色不见了」。
 *
 * 全站已统一为「M3 自带 ripple 是唯一的悬停/按压视觉反馈」，不再用自定义 hover 底色
 * （那会和 ripple 状态层叠成两种样式，弹簧颜色动画还会先深后浅闪一下）。因此下面也钉住
 * 换成 M3 `ripple()` 之后 hover 事件仍正常派发。
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3ExpressiveApi::class)
class HoverInteractionTest {

    @Test
    fun `Surface onClick 会把悬停事件发到同一个 source`() = runComposeUiTest {
        var hovered = false
        setContent {
            CpTheme(themeMode = ThemeMode.DARK) {
                val source = remember { MutableInteractionSource() }
                val isHovered by source.collectIsHoveredAsState()
                hovered = isHovered
                Surface(
                    onClick = {},
                    modifier = Modifier.size(44.dp).testTag("btn"),
                    shape = CircleShape,
                    interactionSource = source,
                ) {}
            }
        }
        waitForIdle()
        assertFalse(hovered, "指针还没进去就报告悬停")

        onNodeWithTag("btn").performMouseInput { moveTo(center) }
        waitForIdle()
        assertTrue(hovered, "Surface(onClick) 没有把悬停事件发给传入的 interactionSource")
    }

    @Test
    fun `clickable 会把悬停事件发到同一个 source`() = runComposeUiTest {
        var hovered = false
        setContent {
            CpTheme(themeMode = ThemeMode.DARK) {
                val source = remember { MutableInteractionSource() }
                val isHovered by source.collectIsHoveredAsState()
                hovered = isHovered
                Box(
                    Modifier
                        .size(44.dp)
                        .testTag("box")
                        .clickable(interactionSource = source, indication = null) {},
                )
            }
        }
        waitForIdle()
        assertFalse(hovered)

        onNodeWithTag("box").performMouseInput { moveTo(center) }
        waitForIdle()
        assertTrue(hovered, "clickable 没有把悬停事件发给传入的 interactionSource")
    }

    @Test
    fun `换成 M3 ripple 后 clickable 仍派发悬停事件`() = runComposeUiTest {
        var hovered = false
        setContent {
            CpTheme(themeMode = ThemeMode.DARK) {
                val source = remember { MutableInteractionSource() }
                val isHovered by source.collectIsHoveredAsState()
                hovered = isHovered
                Box(
                    Modifier
                        .size(44.dp)
                        .testTag("rippleBox")
                        .clickable(
                            interactionSource = source,
                            indication = ripple(),
                            onClick = {},
                        ),
                )
            }
        }
        waitForIdle()
        assertFalse(hovered)

        onNodeWithTag("rippleBox").performMouseInput { moveTo(center) }
        waitForIdle()
        assertTrue(hovered, "M3 ripple 接入后 hover 事件不应丢失")
    }
}
