package cp.player.app.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.TransformOrigin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventState
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.ui.screen.HomeGeneratedPlaylistScreen
import cp.player.app.ui.screen.MainScreen
import cp.player.app.ui.screen.PlayerScreen
import cp.player.app.ui.screen.PlaylistDetailScreen
import kotlin.math.roundToInt

/** A window or content pane owns one stack. Every visit has a distinct saveable key. */
class PageStack(start: RoutePage) {
    private var nextId = 0L
    private val stack = mutableStateListOf(entry(start))
    // The content display can leave composition while its parent route is covered.
    // Keep its models with the stack so returning does not reload the active page.
    internal val models = ViewModelStoreProvider(parentStore = null)
    internal val entries: List<PageEntry> get() = stack
    val size: Int get() = stack.size
    val firstItem: RoutePage get() = stack.first().page
    val lastItem: RoutePage get() = stack.last().page

    private fun entry(page: RoutePage) = PageEntry(nextId++, page)

    fun push(page: RoutePage) {
        stack.add(entry(page))
    }

    fun replace(page: RoutePage) {
        stack[stack.lastIndex] = entry(page)
    }

    fun replaceAll(page: RoutePage) {
        Snapshot.withMutableSnapshot {
            stack.clear()
            stack.add(entry(page))
        }
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popUntilRoot(): Boolean {
        if (stack.size <= 1) return false
        stack.subList(1, stack.size).clear()
        return true
    }

    internal fun discardHiddenDetails() {
        for (index in 1 until stack.size) models.clearKey(stack[index].id)
        popUntilRoot()
    }
}

internal class PageEntry(val id: Long, val page: RoutePage)

private class StackModel(val stack: PageStack) : ViewModel() {
    override fun onCleared() {
        stack.models.clearAllKeys()
    }
}

@Composable
internal fun rememberPageStack(start: RoutePage, key: String): PageStack {
    val owner = LocalViewModelStoreOwner.current
    return if (owner != null) {
        viewModel<StackModel>(viewModelStoreOwner = owner, key = key) { StackModel(PageStack(start)) }.stack
    } else {
        val stack = remember(key) { PageStack(start) }
        DisposableEffect(stack) { onDispose { stack.models.clearAllKeys() } }
        stack
    }
}

internal enum class PageMotion { Default, Main, Player, Playlist }

private const val PAGE_MOTION = "cp.player.page.motion"
private val motionMetadata = PageMotion.entries.associateWith { mapOf(PAGE_MOTION to it) }

private fun Scene<PageEntry>.pageMotion(): PageMotion = metadata[PAGE_MOTION] as? PageMotion ?: PageMotion.Default

private fun RoutePage.motion(): PageMotion = when (this) {
    is MainScreen -> PageMotion.Main
    is PlayerScreen -> PageMotion.Player
    is PlaylistDetailScreen, is HomeGeneratedPlaylistScreen -> PageMotion.Playlist
    else -> PageMotion.Default
}

internal fun cpPredictivePopTransition(swipeEdge: Int): ContentTransform {
    val edgeSign = if (swipeEdge == NavigationEvent.EDGE_RIGHT) -1f else 1f
    val enter = scaleIn(
        animationSpec = tween(260, easing = LinearEasing),
        initialScale = 0.96f,
    ) + slideInHorizontally(
        animationSpec = tween(260, easing = LinearEasing),
    ) { -(it * 0.06f * edgeSign).roundToInt() } +
        fadeIn(tween(260, easing = LinearEasing), initialAlpha = 0.72f)
    val exit = scaleOut(
        animationSpec = tween(260, easing = LinearEasing),
        targetScale = 0.90f,
        transformOrigin = if (edgeSign > 0f) TransformOrigin(0.82f, 0.5f) else TransformOrigin(0.18f, 0.5f),
    ) + slideOutHorizontally(
        animationSpec = tween(260, easing = LinearEasing),
    ) { (it * 0.14f * edgeSign).roundToInt() } +
        fadeOut(tween(260, easing = LinearEasing), targetAlpha = 0.35f)
    return (enter togetherWith exit).apply {
        targetContentZIndex = -1f
    }
}

@Composable
internal fun RouteDisplay(
    stack: PageStack,
    backEnabled: Boolean = true,
    predictiveBackEnabled: Boolean = true,
    transition: (from: PageMotion, to: PageMotion, isPop: Boolean) -> ContentTransform =
        { _, _, _ -> EnterTransition.None togetherWith ExitTransition.None },
    predictiveTransition: ((from: PageMotion, to: PageMotion, swipeEdge: Int) -> ContentTransform)? = null,
    content: @Composable AnimatedContentScope.(RoutePage) -> Unit = { it.Content() },
) {
    val render = rememberUpdatedState(content)
    val backEnabledState = rememberUpdatedState(backEnabled)
    val predictiveEnabledState = rememberUpdatedState(predictiveBackEnabled)
    val entryProvider = remember(stack) {
        { entry: PageEntry ->
            NavEntry(entry, contentKey = entry.id, metadata = motionMetadata.getValue(entry.page.motion())) {
                // 预测性返回关闭时在 NavDisplay 与页面内部 BackHandler 之间插一层非预测性拦截器：
                // 既跳过 NavDisplay 的跟手进度缩放，又不盖掉页面自身的多选/搜索 BackHandler。
                val isActiveTop = !predictiveEnabledState.value &&
                    backEnabledState.value &&
                    stack.size > 1 &&
                    stack.entries.lastOrNull()?.id == it.id
                if (isActiveTop) {
                    val nonPredictiveState = rememberNavigationEventState(NavigationEventInfo.None)
                    NavigationBackHandler(
                        state = nonPredictiveState,
                        isBackEnabled = true,
                        onBackCompleted = { stack.pop() },
                    )
                }
                CompositionLocalProvider(LocalDisplayedRoutePage provides it.page) {
                    render.value(LocalNavAnimatedContentScope.current, it.page)
                }
            }
        }
    }
    val saveable = rememberSaveableStateHolderNavEntryDecorator<PageEntry>()
    val models = rememberViewModelStoreNavEntryDecorator<PageEntry>(stack.models)
    val decorators = remember(saveable, models) { listOf(saveable, models) }
    // 桌面 Esc 由 DesktopBackDispatcher 处理。每个 NavDisplay 独立持有 dispatcher，
    // 避免切换迷你播放器时，根组合先释放仍在子组合中的 dispatcher，导致子组合再次释放它。
    val parentDispatcherOwner = if (isAndroidPlatform()) LocalNavigationEventDispatcherOwner.current else null
    val dispatcher = rememberNavigationEventDispatcherOwner(
        enabled = backEnabled,
        parent = parentDispatcherOwner,
    )
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dispatcher) {
        NavDisplay(
            backStack = stack.entries,
            onBack = { stack.pop() },
            entryDecorators = decorators,
            transitionSpec = { transition(initialState.pageMotion(), targetState.pageMotion(), false) },
            popTransitionSpec = { transition(initialState.pageMotion(), targetState.pageMotion(), true) },
            predictivePopTransitionSpec = { edge ->
                if (predictiveBackEnabled && predictiveTransition != null) {
                    predictiveTransition(initialState.pageMotion(), targetState.pageMotion(), edge)
                } else {
                    transition(initialState.pageMotion(), targetState.pageMotion(), true)
                }
            },
            entryProvider = entryProvider,
        )
    }
}
