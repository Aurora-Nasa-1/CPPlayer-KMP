package cp.player.app.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import cp.player.app.ui.navigation.PageStack

/**
 * 整窗根栈由 App 提供。宽屏内容宿主需要它来把全屏路由送到正确的栈。
 */
val LocalRootNavigator = staticCompositionLocalOf<PageStack?> { null }

/**
 * 把根 Navigator 弹回**主壳层**（`MainScreen`），返回是否真的需要回退。
 *
 * ## 为什么必须有它（一次真实的静默失败）
 *
 * 「设置 / 消息 / 搜索」在桌面端是 `MainScreen` 的**内嵌面板**，开关是
 * `MainScreen` 的局部状态 `desktopPane`，靠 `LaunchedEffect` 消费 `DesktopShell` 里
 * 的单向指令（`settingsRequested` / `messagesRequested` / `pendingSearchQuery`）。
 *
 * 而 `MainScreen` 是根 Navigator 的**起点**，导航宿主只渲染栈顶 —— 从曲库 push 到
 * `PlaylistDetailScreen` 之后，`MainScreen` 就**离开组合了**（这一点 `Main.kt` 的
 * 标题栏 KDoc 里已经写明：「被 push 出去的页面盖住后会离开组合、不再发布」）。
 *
 * 后果是**两条**，都很难排查：
 * 1. **`LaunchedEffect` 随之取消，指令没人消费** ⇒ 点标题栏「设置」完全没有反应
 *    （用户在歌单页点设置没弹出，就是这一条）；
 * 2. **指令残留成 `true`** ⇒ 等用户自己退回主壳层时，`LaunchedEffect` 重启读到残留值，
 *    设置面板**突然自己弹出来** —— 表现为「我明明没点，它自己开了」。
 *
 * 所以任何走 `DesktopShell` 单向指令的入口，发指令前都必须先确保主壳层在栈顶。
 *
 * @return 是否执行了回退（`false` 表示本来就在主壳层，无需回退）。
 */
fun PageStack?.popToMainShell(): Boolean = this?.popUntilRoot() ?: false
