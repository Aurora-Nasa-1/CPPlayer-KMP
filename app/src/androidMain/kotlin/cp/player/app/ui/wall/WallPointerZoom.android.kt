package cp.player.app.ui.wall

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * 安卓端：滚轮与 hover 都不存在，缩放由**双指捏合**承担（见 `AlbumWall` 的
 * `detectTransformGestures`），因此这里是空实现。
 *
 * ⚠️ 不要为了"平板接鼠标也能用"在这里补滚轮：`onPointerEvent` 在 Android 上不可用，
 * 真要做要改走 `pointerInput { awaitPointerEventScope { … } }` 读 `scrollDelta`，
 * 那需要单独验证一遍事件消费顺序 —— 留到 P4 与设置页一起做。
 */
@Composable
actual fun Modifier.wallPointerZoom(
    onZoomFactor: (factor: Float, position: Offset) -> Unit,
    onHover: (position: Offset) -> Unit,
): Modifier = this
