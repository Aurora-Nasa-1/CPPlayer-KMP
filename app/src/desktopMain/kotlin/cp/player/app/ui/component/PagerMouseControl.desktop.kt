package cp.player.app.ui.component

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * Desktop 端实现：拦截垂直滚轮事件并映射为翻页。
 *
 * HorizontalPager 原生只消费水平滚轮分量（多数鼠标没有水平滚轮），
 * 垂直滚轮会冒泡给外层纵向滚动容器，导致无法用鼠标翻页。
 * 这里将垂直滚轮映射为翻页并消费事件，水平滚轮（含 Shift+滚轮）
 * 仍交给 Pager 原生处理。
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.desktopPagerMouseControl(
    onScrollLeft: () -> Unit,
    onScrollRight: () -> Unit,
    pageCount: Int,
): Modifier = if (pageCount <= 1) {
    this
} else {
    this.onPointerEvent(PointerEventType.Scroll) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        // Pager 内部已消费（水平滚轮）则不处理
        if (change.isConsumed) return@onPointerEvent
        val delta = change.scrollDelta
        if (delta.x == 0f && delta.y != 0f) {
            if (delta.y > 0f) onScrollRight() else onScrollLeft()
            event.changes.forEach { it.consume() }
        }
    }
}
