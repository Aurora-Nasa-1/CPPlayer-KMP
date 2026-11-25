package cp.player.app.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * 基于 Material 3 Adaptive 的自适应列表-详情多栏容器。
 *
 * 借助 [ListDetailPaneScaffold] 与 [AnimatedPane]，在紧凑屏（Compact）下根据导航栈状态在
 * List 与 Detail 单栏之间自动带转场切换；在展开屏（Expanded）下则智能并排为双栏，支持多端折叠屏、
 * 悬浮铰链姿态与桌面任意窗口尺寸。
 *
 * @param navigator 管理当前选中项、返回栈与窗格展开状态的自适应导航器。
 * @param listPane 列表窗格内容，参数 [isDetailVisible] 声明当前屏幕尺寸下右侧详情栏是否同时可见。
 * @param detailPane 详情窗格内容，参数 [item] 为当前导航器所选中的数据载荷（若无则为空）。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T> CpAdaptiveListDetailScaffold(
    navigator: ThreePaneScaffoldNavigator<T>,
    listPane: @Composable (isDetailVisible: Boolean) -> Unit,
    detailPane: @Composable (item: T?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDetailVisible = navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded

    ListDetailPaneScaffold(
        modifier = modifier.fillMaxSize(),
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane(modifier = Modifier.fillMaxSize()) {
                listPane(isDetailVisible)
            }
        },
        detailPane = {
            AnimatedPane(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalEmbeddedInPane provides isDetailVisible) {
                    detailPane(navigator.currentDestination?.contentKey)
                }
            }
        },
    )
}
