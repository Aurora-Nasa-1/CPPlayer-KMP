package cp.player.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.CpMotion

/**
 * 移动端 / 窄屏统一路由顶栏状态声明。
 *
 * 页面进入组合时入栈、离开时出栈（与 [DesktopRouteTitle] 机制一致）。
 * 顶层宿主根据栈顶的声明渲染外置 TopBar，使顶栏在页面转场时保持固定在屏幕顶端，
 * 仅内部的标题与动作按钮发生淡入淡出动画。
 */
class RouteTopBarClaim(
    initialTitle: String,
    initialOnBack: (() -> Unit)?,
    initialActions: List<TopBarAction> = emptyList(),
    initialLeadingIcon: (@Composable () -> Unit)? = null,
) {
    var title by mutableStateOf(initialTitle)
    var onBack by mutableStateOf(initialOnBack)
    var topBarActions by mutableStateOf(initialActions)
    var leadingIcon by mutableStateOf(initialLeadingIcon)
}

val routeTopBarClaims = mutableStateListOf<RouteTopBarClaim>()

val currentRouteTopBarClaim: RouteTopBarClaim? get() = routeTopBarClaims.lastOrNull()

/**
 * 标记当前组合树是否已经有外置的移动端顶栏宿主接管。
 * 如果为 true，[CpRouteScaffold] 不再在内层渲染嵌套的 TopBar。
 */
val LocalRouteTopBarHostActive = compositionLocalOf { false }

/**
 * 移动端 / 窄屏统一路由顶栏宿主。
 *
 * 处于 [ScreenTransition] 外层，位置固定在屏幕顶部：
 * - 顶栏背景与高度全程固定，不随页面滑动；
 * - 标题文字使用微动淡入淡出（符合 Android 16 / Material 3 Expressive 规范）；
 * - 返回按钮平滑渐变显隐；
 * - 右侧操作动作渐变切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileRouteTopBarHost(
    claim: RouteTopBarClaim?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && claim != null,
        enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
        exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (claim != null) {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = claim.title,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 6 }) togetherWith
                                (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 6 })
                        },
                        label = "mobileTopBarTitle",
                    ) { titleText ->
                        CpText(
                            text = titleText,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                navigationIcon = {
                    AnimatedContent(
                        targetState = when {
                            claim.onBack != null -> 0
                            claim.leadingIcon != null -> 1
                            else -> 2
                        },
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(150))
                        },
                        label = "mobileTopBarLeading",
                    ) { mode ->
                        when (mode) {
                            0 -> claim.onBack?.let { onBack ->
                                CpBackButton(
                                    onClick = onBack,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            1 -> claim.leadingIcon?.invoke()
                            else -> Spacer(Modifier.width(0.dp))
                        }
                    }
                },
                actions = {
                    AnimatedContent(
                        targetState = claim.topBarActions,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(150))
                        },
                        label = "mobileTopBarActions",
                    ) { actions ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            actions.forEach { action ->
                                CpTopBarActionButton(action)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                windowInsets = WindowInsets.statusBars,
            )
        }
    }
}
