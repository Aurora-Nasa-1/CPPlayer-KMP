package cp.player.app.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 安卓端触摸滑动本身就是横向手势，无需映射滚轮。 */
@Composable
actual fun Modifier.desktopHorizontalWheelScroll(state: LazyListState): Modifier = this
