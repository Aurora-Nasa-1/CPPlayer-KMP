package cp.player.app.ui.component

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * Desktop 端实现：把**垂直滚轮**映射为翻页。
 *
 * `HorizontalPager` 原生只消费水平滚轮分量（多数鼠标没有水平滚轮），而垂直滚轮会
 * 冒泡给外层纵向滚动容器，导致鼠标无法翻页。这里把垂直滚轮映射成翻页并消费事件。
 *
 * ## 事件阶段：与 [desktopHorizontalWheelScroll] 同样取 [PointerEventPass.Initial]
 *
 * Pager 内部的横向滚动逻辑与外层纵向容器的滚轮逻辑都会在 `Main` pass 抢事件，
 * 且**子节点优先** ⇒ 挂在 Pager 外面的本 modifier 在 `Main` pass 拿到时
 * `change.isConsumed` 常常已经是 true，滚动被静默吞掉。
 * 取 `Initial`（父节点优先）才能稳定地在它们之前决定接管。
 *
 * ## 边界处的取舍
 *
 * 翻到首 / 末页后，本函数**仍然消费**滚轮事件（不还给外层纵向容器）。
 *
 * 理由：Pager 是整页切换语义。若在边界处放行，滚轮会突然变成「整页上下滚动」，
 * 用户会觉得 Pager 那一瞬间失效了 —— 而正确的预期是「这一屏已经到头」。
 * 边界上的视觉反馈由页面自己决定（例如 `PagerIndicator` 停在首 / 末段）。
 *
 * @param onScrollLeft 向左翻一页
 * @param onScrollRight 向右翻一页
 * @param pageCount 总页数；`<= 1` 时直接返回原 modifier（单页没有翻页语义）
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.desktopPagerMouseControl(
    onScrollLeft: () -> Unit,
    onScrollRight: () -> Unit,
    pageCount: Int,
): Modifier = if (pageCount <= 1) {
    this
} else {
    this.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        // Pager 内部已消费（水平滚轮）则不处理
        if (change.isConsumed) return@onPointerEvent
        val delta = change.scrollDelta
        // 只接管纯垂直滚轮；水平分量交给 Pager 原生处理。
        //
        // ⚠️ 这里的 `delta.y` 方向约定：**向下滚（y > 0）应看后面的内容**。
        // 对纵向列表是「向下滚动 = 内容上移」，对横向 Pager 则应映射成「向右翻页」。
        // 反过来会让滚轮方向与用户的直觉相反，是本函数最容易写反的一处。
        if (delta.x == 0f && delta.y != 0f) {
            if (delta.y > 0f) onScrollRight() else onScrollLeft()
            event.changes.forEach { it.consume() }
        }
    }
}
