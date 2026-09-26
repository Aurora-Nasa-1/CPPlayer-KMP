package cp.player.app.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 安卓端不占滚动条槽位：系统自带边缘滚动反馈，额外留白只会浪费本来就紧张的宽度。
 */
actual val desktopScrollbarGutter: Dp = 0.dp

@Composable
actual fun DesktopVerticalScrollbar(state: ScrollState, modifier: Modifier) = Unit

@Composable
actual fun DesktopVerticalScrollbar(state: LazyListState, modifier: Modifier) = Unit

@Composable
actual fun DesktopHorizontalScrollbar(state: LazyListState, modifier: Modifier) = Unit
