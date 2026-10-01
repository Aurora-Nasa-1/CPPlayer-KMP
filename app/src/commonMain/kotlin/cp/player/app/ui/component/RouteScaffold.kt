package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.app.ui.util.DesktopRouteTitle

/**
 * **路由页的唯一外壳。**
 *
 * 取代了原先并存的两套写法，以及由它们派生出的第三种、第四种：
 *
 * | 旧写法 | 症状 |
 * |--------|------|
 * | `LegacyPageScaffold(title, navigationIcon = { IconButton { Icon(ArrowBack) } })` | 10 个调用点各自手写一遍返回键，样式与 `AppScaffold` 自带的那个**不一样**（裸图标 vs 填充圆钮） |
 * | `if (expanded) body(Modifier.fillMaxWidth()) else LegacyPageScaffold(…)` | **宽屏下整页没有返回入口** —— 桌面默认就是宽屏（初始 1320×860，最小 900×640 ≥ 840 断点），从标题栏点「账号」进去之后只剩 Esc，而 Esc 那时也没有任何处理器 |
 * | 各页自绘 `PageTitleBar` / `FilledIconButton` | 又两套外观 |
 *
 * 现在的形态由**三选一**的判据决定，且判据只此一份：
 *
 * 1. [embedded]（或 [LocalEmbeddedInPane] 为真）—— 被渲染成某个双栏布局的详情栏。
 *    宿主已经画了标题与返回，这里只出正文。⚠️ **不要**顺手在这里画返回键：
 *    双栏详情栏的返回归窗口 chrome / 左栏的选中态。
 * 2. `LocalWindowChromeActive` —— 桌面自绘标题栏接管。标题与返回都在窗口 chrome 上
 *    （见 [DesktopRouteTitle] 与 `DesktopTitleBar`），页面**不再自绘顶栏**，
 *    否则窗口顶部会叠两条 chrome（44dp 标题栏 + 64dp 顶栏）。[topBarActions] 改在正文顶部
 *    渲染一行紧凑按钮，不会丢。
 * 3. 其余（安卓 / 未来可能出现的「回退到系统标题栏」模式）—— 自绘 [AppScaffold] +
 *    [CpBackButton]。**窄屏与宽屏同一套**：宽屏不再有"裸奔"分支。
 *
 * @param title 页面标题。宽屏（判据 3）会显示在顶栏上；判据 2 会发布给窗口标题栏。
 * @param onBack 返回动作。传 `null` 表示这一页没有返回（例如作为根页被复用）。
 *   判据 2 下由窗口标题栏承担，这里仍要传 —— 否则窄窗口（<840dp，走判据 3）就没有返回键。
 * @param content 正文。**必须使用传进来的 `Modifier`**（各分支给的是「填满剩余空间」的约束）。
 */
@Composable
fun CpRouteScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    topBarActions: List<TopBarAction> = emptyList(),
    floatingActionButton: @Composable () -> Unit = {},
    embedded: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    if (embedded || LocalEmbeddedInPane.current) {
        content(modifier.fillMaxSize())
        return
    }

    if (LocalWindowChromeActive.current) {
        DesktopRouteTitle(title)
        Box(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (topBarActions.isNotEmpty()) {
                    // 桌面端没有页内顶栏，动作按钮挪到正文顶部右侧。宽度与页面正文同宽
                    // （正文自己收口，这里只保证不贴到窗口边缘）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        topBarActions.forEach { CpTopBarActionButton(it) }
                    }
                }
                content(Modifier.weight(1f))
            }
            // `floatingActionButton` 为空 lambda 时这里不产生任何绘制，无需判空
            // （`@Composable () -> Unit` 没法判空）。
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                floatingActionButton()
            }
        }
        return
    }

    AppScaffold(
        title = title,
        onBackPressed = onBack,
        topBarActions = topBarActions,
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.background,
    ) { _ ->
        content(Modifier.fillMaxSize())
    }
}
