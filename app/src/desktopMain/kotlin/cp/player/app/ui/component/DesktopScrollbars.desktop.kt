package cp.player.app.ui.component

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 桌面端留出滚动条槽位。 */
actual val desktopScrollbarGutter: Dp = 10.dp

/**
 * 可见比例达到该值就不再画滚动条。
 *
 * 滑块长度 = `viewport / content`。只差最后几个像素时滑块会几乎占满整条轨道，
 * 看起来像一条贴着右边缘的竖线，比「干脆没有滚动条」更难看。
 * 0.95 ≈ 一屏能看到 95% 的内容 —— 此时用户拖不拖意义都不大。
 */
private const val AlmostVisibleFraction = 0.95

/**
 * 内容是否「几乎完整可见」（可见比例 ≥ [AlmostVisibleFraction]）。
 *
 * 用 `derivedStateOf` 包一层：`contentSize` / `viewportSize` 都是快照状态，
 * 滚动时每帧都会变。直接读会让外层容器跟着每帧重组；派生成一个 Boolean 之后，
 * 只有**判定真正翻转**的那一次才会触发重组。
 *
 * 尺寸未知（`contentSize <= 0` / `viewportSize <= 0`，首帧或空内容）一律按「隐藏」处理，
 * 免得先画出来再消失闪一下。
 */
@Composable
private fun rememberAlmostFullyVisible(adapter: ScrollbarAdapter): Boolean =
    remember(adapter) {
        derivedStateOf {
            val content = adapter.contentSize
            val viewport = adapter.viewportSize
            content <= 0.0 || viewport <= 0.0 || viewport >= content * AlmostVisibleFraction
        }
    }.value

/**
 * 主题感知的滚动条样式。
 *
 * Compose 默认样式是黑色半透明，在深色主题下几乎看不见；这里改成跟随
 * `onSurface`，浅色/深色主题都能看清，同时保持「未悬停时很淡」的桌面习惯。
 */
@Composable
private fun rememberCpScrollbarStyle(): ScrollbarStyle {
    val thumb = MaterialTheme.colorScheme.onSurface
    return remember(thumb) {
        ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = desktopScrollbarGutter,
            shape = RoundedCornerShape(percent = 50),
            hoverDurationMillis = 220,
            unhoverColor = thumb.copy(alpha = 0.22f),
            hoverColor = thumb.copy(alpha = 0.55f),
        )
    }
}

/**
 * 滚条与窗口边缘之间留出的距离。
 *
 * ⚠️ 必须和 `Main.kt` 里 `WindowDecoration.Undecorated(6.dp)` 的抓手厚度一致：
 * 无边框窗口的缩放抓手（`UndecoratedWindowResizer`）压在最外圈那 6dp 上，滚条又是贴着右边缘摆的
 * （`Alignment.CenterEnd`）。两者重叠时，**拖滚条会变成缩放窗口**。这里把滚条整体内缩同样的
 * 距离，让它完全落在抓手带之外。改一边就必须改另一边。
 */
private val ScrollbarEdgeInset = 6.dp

@Composable
actual fun DesktopVerticalScrollbar(state: ScrollState, modifier: Modifier) {
    val adapter = rememberScrollbarAdapter(state)
    if (rememberAlmostFullyVisible(adapter)) return
    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier.padding(end = ScrollbarEdgeInset),
        style = rememberCpScrollbarStyle(),
    )
}

@Composable
actual fun DesktopVerticalScrollbar(state: LazyListState, modifier: Modifier) {
    val adapter = rememberScrollbarAdapter(state)
    if (rememberAlmostFullyVisible(adapter)) return
    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier.padding(end = ScrollbarEdgeInset),
        style = rememberCpScrollbarStyle(),
    )
}

@Composable
actual fun DesktopHorizontalScrollbar(state: LazyListState, modifier: Modifier) {
    val adapter = rememberScrollbarAdapter(state)
    if (rememberAlmostFullyVisible(adapter)) return
    HorizontalScrollbar(
        adapter = adapter,
        modifier = modifier,
        style = rememberCpScrollbarStyle(),
    )
}
