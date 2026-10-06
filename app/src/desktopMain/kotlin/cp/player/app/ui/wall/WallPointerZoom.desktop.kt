package cp.player.app.ui.wall

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlin.math.exp

/**
 * 一格滚轮对应的焦距倍率。
 *
 * 用**指数**而不是线性：焦距本身是 `[0,1]` 的归一化值，线性加减会让"在 Z0 附近滚一格"
 * 和"在 Z3 附近滚一格"走完全不同的视觉距离。指数映射让每一格的手感一致 ——
 * 与 `WallLayoutEngine` 里单位格边长按对数增长是同一个理由。
 *
 * 0.14 的取值：一格（`deltaY = ±1`）约 ±15%，从 Z1 走到 Z2 大约 4–5 格。
 */
private const val WheelZoomRate = 0.14f

/** 滚轮 delta 的绝对值上限，防止触控板惯性滚动一次性把焦距甩到底。 */
private const val MaxWheelDelta = 12f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.wallPointerZoom(
    onZoomFactor: (factor: Float, position: Offset) -> Unit,
    onHover: (position: Offset) -> Unit,
): Modifier {
    // rememberUpdatedState：这两个 lambda 每次重组都是新实例，直接 key 在 pointerInput 上
    // 会让监听器反复重建（拖动中途被拆掉就会丢事件）。
    val zoom by rememberUpdatedState(onZoomFactor)
    val hover by rememberUpdatedState(onHover)
    return this
        .onPointerEvent(PointerEventType.Move) { event ->
            event.changes.firstOrNull()?.position?.let(hover)
        }
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val change = event.changes.firstOrNull() ?: return@onPointerEvent
            val dy = change.scrollDelta.y
            if (dy != 0f) {
                val clamped = dy.coerceIn(-MaxWheelDelta, MaxWheelDelta)
                zoom(exp(-clamped * WheelZoomRate), change.position)
                change.consume()
            }
        }
}
