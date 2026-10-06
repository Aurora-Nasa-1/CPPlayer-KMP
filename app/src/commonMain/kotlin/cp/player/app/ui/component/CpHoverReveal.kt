package cp.player.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** 悬停多久才浮出（毫秒）。太短会在扫过列表时一路闪卡。 */
private const val HOVER_REVEAL_DELAY_MS = 250L

/**
 * 浮层的最大宽度。
 *
 * 裸值而不进 `CpSpacing`：它是**浮层自己的规格**（与页面栅格无关），
 * 且只有这一处消费。留够一行 40 个汉字的宽度，再宽的原文靠换行解决。
 */
private val RevealMaxWidth = 360.dp

/**
 * 悬停揭示：指针停在 [content] 上一段时间后，在它**原位置**浮出 [fullText] 的完整内容。
 *
 * ## 为什么是「就地浮出」而不是 Tooltip
 *
 * material3 的 `TooltipBox` 仍在 alpha 线（本项目钉的是 `1.12.0-alpha03`），延迟与落点
 * 都不可控，观感也对不上 M3 Expressive。自绘 `Popup` 反而更简单：落点贴合原文左上角，
 * 内容**允许换行**（不受调用方 `maxLines` 限制），一屏能看全。
 *
 * ## 为什么不用「悬停就滚」
 *
 * 长文本（比如 80 字的歌单名）滚一轮要好几秒，读不完还伤眼；浮层可以停留、可以看全。
 * 「滚动」只留给焦点位（见 [CpTextReveal.Marquee]）—— 那里用户的目光本来就在。
 *
 * ## 跨平台降级
 *
 * 用 [hoverable] + [collectIsHoveredAsState] 而不是 `onPointerEvent(Enter)`：
 * 后者是 Compose Desktop 的扩展，`commonMain` 里根本不存在
 * （同 `WallPointerZoom` 的 KDoc）。触屏手指按下去**不算** hover，
 * 所以 Android 上本组件自动退化成「什么都不做」，不需要平台判据。
 *
 * ## ⚠️ 调用方只在「真的溢出了」时才挂本组件
 *
 * 未截断的文本挂它只是白交一个 hover 监听 + 一个布局回调。
 */
@Composable
fun CpHoverReveal(
    fullText: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var anchor by remember { mutableStateOf(IntOffset.Zero) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(hovered) {
        if (hovered) {
            delay(HOVER_REVEAL_DELAY_MS)
            visible = true
        } else {
            // 协程随 key 变化被取消 ⇒ 移开得比延迟快时不会闪一下。
            visible = false
        }
    }

    Box(
        modifier
            .hoverable(interaction)
            // 浮层要钉在文本的**窗口坐标**上：Popup 的 offset 是相对窗口的，
            // 而文本可能被包在任意深度的容器里，局部坐标对不上。
            .onGloballyPositioned { coords ->
                val p = coords.positionInWindow()
                anchor = IntOffset(p.x.toInt(), p.y.toInt())
            },
    ) {
        content()
        if (visible) {
            Popup(
                alignment = Alignment.TopStart,
                offset = anchor,
                // clippingEnabled 保持默认（true）：浮层被钳在主窗口内，
                // 靠窗口右/下缘的文本不会把浮层甩到屏幕外面。
                properties = PopupProperties(focusable = false),
            ) {
                RevealCard(text = fullText, style = style)
            }
        }
    }
}

/**
 * 浮层本体：与右键菜单（[CpContextMenu]）同族的一张卡片（同圆角色板与描边），
 * 保证「从界面上浮出来的东西」长得一致。
 *
 * [style] 沿用被截断文本的样式 —— 浮层是那份文字的放大版，不是另一个层级的说明文字。
 */
@Composable
private fun RevealCard(text: String, style: TextStyle) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.widthIn(max = RevealMaxWidth).padding(horizontal = 12.dp, vertical = 8.dp),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
