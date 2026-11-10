package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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
 * 3. 其余（安卓 / 窄屏 / 移动端）—— 自绘 [AppScaffold] + [CpBackButton]。
 *    顶栏通过 `sharedBounds` 在页面转场时保持居顶淡入淡出、不随页面横移，
 *    同时完整保留 `LargeTopAppBar` 随列表滚动的动态展开与收缩功能。
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
        // 双栏右栏：宿主已画标题与返回，这里只出正文。
        // 但 [floatingActionButton] 仍要照常叠加 —— 这个分支此前直接 `content(...); return`，
        // 于是在桌面宽屏（设置页右栏，「音源与歌词管理」就走这里）丢掉了唯一的主入口：
        // 列表为空时连列表项都没有，整页只剩一句「点击右下角 +」，而右下角什么都没有。
        Box(modifier.fillMaxSize()) {
            content(Modifier.fillMaxSize())
            RouteFabSlot(floatingActionButton, Modifier.align(Alignment.BottomEnd))
        }
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
                // 桌面端整页空白处右键 → 「返回上一级」。
                //
                // 用 `passive = true`：页面里自己的右键菜单（歌曲行、卡片）与点击手势
                // 优先，只有**没有任何子级接手**的空白 / 纯文本区域才弹这一层
                // （默认的 Initial 模式会让父子同时弹出两个菜单）。
                //
                // `onBack` 为 null（根页被复用时）就完全不包一层 —— 不摆一个点了没反应的菜单。
                val backItem = onBack
                if (backItem == null) {
                    content(Modifier.weight(1f))
                } else {
                    CpContextMenu(
                        items = listOf(
                            CpContextMenuItem(
                                label = "返回上一级",
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = backItem,
                            ),
                        ),
                        modifier = Modifier.weight(1f),
                        passive = true,
                    ) {
                        content(Modifier.fillMaxSize())
                    }
                }
            }
            // `floatingActionButton` 为空 lambda 时这里不产生任何绘制，无需判空
            // （`@Composable () -> Unit` 没法判空）。
            RouteFabSlot(floatingActionButton, Modifier.align(Alignment.BottomEnd))
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

/**
 * 页面右下角的 FAB 槽位 —— [CpRouteScaffold] 的两个自绘分支共用（embedded 与窗口 chrome）。
 *
 * 底部让位要叠加 [LocalMiniPlayerTailSpace]：小播放器**浮在页面内容之上**
 * （`GlobalMiniPlayerHost` 是 `Box { pageContent(); MiniPlayer(align = BottomCenter) }`），
 * 只留 16dp 的话 FAB 会被它压住半截、点不到。没有播放条时该值是 `0.dp`，位置与之前一致。
 *
 * ⚠️ 之前两个分支各写了一份 `Box(Modifier.align(BottomEnd).padding(16.dp))`，其中 embedded
 * 那个连 FAB 都没画（见上）。合成一个函数，免得再各改一份。
 */
@Composable
private fun RouteFabSlot(
    fab: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tail = LocalMiniPlayerTailSpace.current.coerceAtLeast(0.dp)
    Box(
        modifier = modifier.padding(end = 16.dp, bottom = 16.dp + tail),
        contentAlignment = Alignment.Center,
    ) {
        fab()
    }
}
