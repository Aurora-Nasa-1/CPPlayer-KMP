package cp.player.app.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

data class TopBarAction(
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit
)

/**
 * 顶栏右侧动作按钮的**统一样式**。
 *
 * 抽出来的原因：桌面端页面不自绘顶栏（标题与返回归窗口 chrome），但 `topBarActions`
 * 得有地方放 —— `CpRouteScaffold` 会在正文顶部渲染同一批按钮。样式若各写一遍，
 * 「同一个动作在窄屏是填充圆钮、在宽屏变成裸图标」这种漂移迟早出现。
 */
@Composable
fun CpTopBarActionButton(
    action: TopBarAction,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = action.onClick,
        modifier = modifier.size(40.dp).cpPressScale(),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        action.icon()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AppScaffold(
    title: @Composable () -> Unit,
    onBackPressed: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    topBarActions: List<TopBarAction> = emptyList(),
    actions: (@Composable RowScope.() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    isLoading: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    containerColor: Color = Color.Transparent,
    topBarContainerColor: Color = Color.Unspecified,
    content: @Composable (PaddingValues) -> Unit
) {
    val actualScrollBehavior = scrollBehavior ?: TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // If topBarContainerColor is Unspecified or Transparent, make it match the unscrolled background
    // with 0 alpha. We intentionally avoid Color.Transparent (which is transparent black Color(0x00000000)),
    // because lerping from transparent black to scrolledContainerColor produces a muddy dark-gray
    // midpoint that makes the top bar look darker during transition than after collapse.
    val actualTopBarContainerColor = when {
        topBarContainerColor != Color.Unspecified && topBarContainerColor != Color.Transparent -> topBarContainerColor
        containerColor != Color.Transparent && containerColor != Color.Unspecified -> containerColor
        else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f)
    }

    val sharedScope = cp.player.app.ui.anim.LocalSharedTransitionScope.current
    val navScope = cp.player.app.ui.anim.LocalNavAnimatedVisibilityScope.current
    val topBarModifier = if (sharedScope != null && navScope != null && !LocalWindowChromeActive.current) {
        with(sharedScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "universal_app_scaffold_topbar"),
                animatedVisibilityScope = navScope,
            )
        }
    } else {
        Modifier
    }

    Scaffold(
        modifier = Modifier.nestedScroll(actualScrollBehavior.nestedScrollConnection),
        containerColor = containerColor,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        topBar = {
            Box(topBarModifier) {
                LargeTopAppBar(
                    title = title,
                    navigationIcon = navigationIcon ?: {
                        // 桌面端（窗口 chrome 接管时）**不自绘返回键**：返回入口统一由自绘标题栏
                        // 承担（见 `DesktopTitleBar` 与 `DesktopShell`），否则同一屏会出现两个返回键
                        // —— 一个在 44dp 标题栏里、一个在页面顶栏里，位置和外观都不一样。
                        // 判据是 `LocalWindowChromeActive`（槽位是否被注入）而不是平台，理由见它的 KDoc。
                        if (onBackPressed != null && !LocalWindowChromeActive.current) {
                            CpBackButton(
                                onClick = onBackPressed,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    },
                    actions = actions ?: {
                        topBarActions.forEachIndexed { index, action ->
                            if (index > 0) Spacer(Modifier.width(4.dp))
                            CpTopBarActionButton(action)
                        }
                        if (topBarActions.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                        }
                    },
                    scrollBehavior = actualScrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = actualTopBarContainerColor,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            content(PaddingValues(0.dp))
            if (isLoading) {
                // 变形加载指示器（MaterialShapes），不是转圈的 CircularProgressIndicator。
                CpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBackPressed: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    topBarActions: List<TopBarAction> = emptyList(),
    actions: (@Composable RowScope.() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    isLoading: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    containerColor: Color = Color.Transparent,
    topBarContainerColor: Color = Color.Unspecified,
    content: @Composable (PaddingValues) -> Unit
) {
    AppScaffold(
        title = {
            CpText(
                text = title,
                maxLines = 1)
        },
        onBackPressed = onBackPressed,
        navigationIcon = navigationIcon,
        topBarActions = topBarActions,
        actions = actions,
        floatingActionButton = floatingActionButton,
        isLoading = isLoading,
        scrollBehavior = scrollBehavior,
        bottomBar = bottomBar,
        containerColor = containerColor,
        topBarContainerColor = topBarContainerColor,
        content = content
    )
}