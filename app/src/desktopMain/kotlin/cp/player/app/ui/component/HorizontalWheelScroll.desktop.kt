package cp.player.app.ui.component

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 一格滚轮对应的横向位移。约等于一次「明显但不过冲」的翻动。 */
private val WheelStep = 72.dp

/**
 * 桌面端滚轮兜底：把**垂直滚轮**映射成 `LazyRow` 的横向滚动。
 *
 * `LazyRow` 只消费水平滚轮分量，纯垂直滚轮会冒泡给外层纵向容器。本 modifier 是给
 * 「外层已经滚到顶 / 底」时准备的兜底：此时垂直滚轮本来无处可去，拿它来横向翻卡片行。
 *
 * ## 为什么必须是 [PointerEventPass.Final]、且先看 `isConsumed`
 *
 * 早期实现挂在 `Initial` pass 上**抢先**消费（父节点优先），结果鼠标只要悬停在首页任一
 * 横向卡片行上，垂直滚轮就被这一行独占，外层纵向列表一步也滚不动。更糟的是两个方向
 * **不对称**：卡片行停在最左端时 `canScrollBackward == false`（放行，页面能往上滚），
 * 而 `canScrollForward == true`（消费，页面往下滚不动）—— 用户看到的就是
 * 「滚轮只能往上滚，往下滚没反应」。首页 / 搜索 / 个人页都有 `LazyScrollRow`，鼠标落在
 * 卡片行上的概率很高。
 *
 * `Main` pass 不能用来「等别人先消费」：它是**子节点优先**，而本 modifier 挂在外层纵向
 * 滚动容器的内层，轮到它时外层还没看到事件，`isConsumed` 恒为 false，等于没改。
 * 只有 `Final` pass 晚于所有 `Initial` / `Main` 消费者，才能准确判断这次滚轮是否已被页面吃掉：
 *
 * - 页面还能朝该方向滚 ⇒ `MouseWheelScrollingLogic` 在 `Main` 消费 ⇒ 这里放行，滚轮归页面；
 * - 页面滚到顶 / 底 ⇒ 没人消费 ⇒ 这里才接管，转成横向位移。
 *
 * 这样上下两个方向行为一致，又不丢掉「无水平滚轮时用垂直滚轮翻卡片行」的能力。
 * Shift+滚轮 / 水平滚轮在事件层就是 `delta.x != 0`，由 `LazyRow` 原生消费，与本 modifier 无关。
 *
 * ## 只在「还能朝该方向滚」时才消费
 *
 * 横向行也到尽头时不消费，滚轮继续冒泡（虽然 `Final` 之后已没有消费者，但保持语义清晰）。
 * 判定用 `canScrollForward` / `canScrollBackward`，与 `LazyListState` 在动画中的实际状态同源。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.desktopHorizontalWheelScroll(state: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { WheelStep.toPx() }
    // 滚动手感是长期的：用 rememberUpdatedState 让 pointerInput 的 key 保持稳定，
    // 避免每次重组都重建 pointer 节点（重建会丢掉正在进行的滚动惯性）。
    // ⚠️ 必须写 `by ... ` 取 `.value`，不能 `val latestState = rememberUpdatedState(state)`
    // 之后直接 `.canScrollForward` —— 拿到的是 `State<LazyListState>` 本身，不是 state。
    val latestState by rememberUpdatedState(state)
    return this.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Final) { event ->
        // 外层纵向滚动容器已消费 ⇒ 滚轮归页面，横向行不抢。
        if (event.changes.any { it.isConsumed }) return@onPointerEvent
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        val delta = change.scrollDelta
        // 水平滚轮（含 Shift+滚轮在多数驱动上的形态）交给 LazyRow 原生处理。
        if (delta.x != 0f || delta.y == 0f) return@onPointerEvent
        val canScroll = if (delta.y > 0f) {
            latestState.canScrollForward
        } else {
            latestState.canScrollBackward
        }
        if (!canScroll) return@onPointerEvent
        scope.launch { latestState.scrollBy(delta.y * stepPx) }
        event.changes.forEach { it.consume() }
    }
}
