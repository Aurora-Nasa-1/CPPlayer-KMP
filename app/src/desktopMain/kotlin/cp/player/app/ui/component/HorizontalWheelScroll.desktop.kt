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
 * 桌面端鼠标滚轮支持：把**垂直滚轮**映射成横向滚动。
 *
 * 鼠标普遍只有垂直滚轮，而 `LazyRow` 只消费水平滚轮分量，于是悬停在横向卡片行上滚轮时，
 * 事件会冒泡给外层纵向容器 —— 表现为「横向列表一动不动，页面却在上下滚」。
 *
 * ## 事件阶段为什么必须是 [PointerEventPass.Initial]
 *
 * 这是**本函数唯一一处容易写错的参数**，收敛前用的是默认的 `Main` pass，症状是
 * 「首页顶部横幅能横向滚、其余的横向卡片行不能」随机出现。原因是同一层上并存着
 * 两个消费者：
 *
 * - 外层纵向滚动容器的 `MouseWheelScrollingLogic`（`ScrollableNode` 里）；
 * - `LazyRow` 自己的横向 `MouseWheelScrollingLogic`。
 *
 * `Main` pass 是**子节点优先**：`LazyRow` 内部的滚动逻辑先拿到事件，它只认水平分量、
 * 对纯垂直滚轮返回「不消费」，然后事件**继续冒泡到外层纵向容器**并被它吃掉。
 * 我们的 modifier 挂在 `LazyRow` **外面**，等到它跑的时候 `change.isConsumed` 已经为真。
 *
 * `Initial` pass 是**父节点优先**：我们比 `LazyRow` 与外层容器都先看到事件，
 * 可以在它被任何滚动逻辑消费之前决定「接管还是放行」。这正是嵌套滚轮映射该用的阶段。
 *
 * ## 只在「还能朝该方向滚」时才消费
 *
 * 滚到尽头就把滚轮**还给外层**纵向容器，页面能继续往下滚 —— 否则横向列表会成为
 * 鼠标滚轮的「死区」（悬停在它上面时页面永远滚不动）。判定用
 * `canScrollForward` / `canScrollBackward`，与 `LazyListState` 在动画中的实际状态同源。
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
    return this.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        // 已经有人处理过（例如水平滚轮 / Shift+滚轮被 LazyRow 自己接走了）就别抢。
        if (change.isConsumed) return@onPointerEvent
        val delta = change.scrollDelta
        // 水平滚轮（含 Shift+滚轮在多数驱动上的形态）交给 LazyRow 原生处理。
        if (delta.x != 0f || delta.y == 0f) return@onPointerEvent
        // 滚到尽头就不消费，把滚轮还给外层纵向容器，页面能继续往下滚。
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
