package cp.player.app.ui.component

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 一格滚轮对应的横向位移。约等于一次「明显但不过冲」的翻动。 */
private val WheelStep = 72.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.desktopHorizontalWheelScroll(state: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { WheelStep.toPx() }
    return this.onPointerEvent(PointerEventType.Scroll) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        // 水平滚轮（含 Shift+滚轮）已由 LazyRow 自己处理，别抢
        if (change.isConsumed) return@onPointerEvent
        val delta = change.scrollDelta
        if (delta.x != 0f || delta.y == 0f) return@onPointerEvent
        // 滚到尽头就不消费，把滚轮还给外层纵向容器，页面能继续往下滚
        val canScroll = if (delta.y > 0f) state.canScrollForward else state.canScrollBackward
        if (!canScroll) return@onPointerEvent
        scope.launch { state.scrollBy(delta.y * stepPx) }
        event.changes.forEach { it.consume() }
    }
}
