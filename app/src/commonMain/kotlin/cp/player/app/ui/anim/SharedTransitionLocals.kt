package cp.player.app.ui.anim

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.SharedTransitionScope

/**
 * App 根部 [androidx.compose.animation.SharedTransitionLayout] 的 scope。
 *
 * 由 `App.kt` 在根部提供，覆盖整棵根 Navigator（含路由页与全局 MiniPlayer）。
 * 路由页（如 [cp.player.app.ui.screen.PlayerScreen]）的共享元素必须用它，
 * 才能与全局 MiniPlayer 的同名 sharedBounds 配对 —— 此前路由页各自新建
 * SharedTransitionLayout，两个 scope 之间永远配不上对，「从歌单点 MiniPlayer
 * 展开播放页没有动画」就是这么来的。
 *
 * 注意：`MainScreen` 内部还有**自己的** SharedTransitionLayout（内嵌播放页与
 * 壳层 MiniPlayer 那一对），内层优先 —— 两套互不串扰。
 */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * 根 Navigator 页面转场的 [AnimatedVisibilityScope]。
 *
 * `Voyager ScreenTransition` 内部是 AnimatedContent，每个（进 / 退场）页面内容
 * 各持一个 scope；在这里按页面提供，路由页的 sharedBounds 才有 animatedVisibilityScope
 * 可挂 —— 共享元素的形变时长与页面转场因此是同一条时间线。
 */
val LocalNavAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
