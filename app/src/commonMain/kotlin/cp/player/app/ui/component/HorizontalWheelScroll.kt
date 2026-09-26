package cp.player.app.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 桌面端鼠标滚轮支持：把**垂直滚轮**映射成横向滚动。
 *
 * 鼠标普遍只有垂直滚轮，而 LazyRow 只消费水平滚轮分量，于是悬停在横向卡片行上滚轮时，
 * 事件会冒泡给外层纵向滚动容器 —— 结果是「想横着翻卡片，页面却上下跳」。
 * 这里拦截垂直滚轮并驱动横向滚动；只有真的还能朝该方向滚时才消费事件，
 * 滚到尽头就把滚轮还给外层，页面可以继续往下滚。Android 端为空实现。
 */
@Composable
expect fun Modifier.desktopHorizontalWheelScroll(state: LazyListState): Modifier
