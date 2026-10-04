package cp.player.app.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cp.player.app.platform.dispatchPageBack
import cp.player.app.platform.hasPageBackHandler
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.ui.util.DesktopShell
import cp.player.app.ui.util.popOrNotify

/**
 * 「返回上一级」右键菜单项（桌面端）。
 *
 * ## 为什么右键菜单里要有它
 *
 * 桌面端的返回入口只在窗口标题栏上（`LocalWindowChromeActive` 成立时页面一律不自绘返回键），
 * 而用户在内容上右键时，直觉是「这里能不能退回去」。补上这一项之后，
 * 「空白处右键」与「标题栏返回键 / Esc」走的是同一条链路。
 *
 * ## 判据与动作都必须与标题栏**逐字同链**
 *
 * 桌面的返回有**四条**来源：
 * 1. 当前 Navigator 能出栈（push 出去的路由页）；
 * 2. 主壳层的内容区**内嵌** Navigator 能出栈（宽屏下的详情页）；
 * 3. 主壳层的**内嵌面板**开着（可收起）；
 * 4. **页面自己注册的处理器**（播放页展开态、歌单多选 —— 既没 push 路由也没开面板）。
 *
 * 四条全不成立时返回 `null`：**不显示这一项**，而不是摆一个点了没反应的灰项。
 * 少算一条就会出现「标题栏有返回键、右键菜单里没有这一项」的漂移
 * （本仓库已经因为「同一个动作两条链路」踩过两次）。
 *
 * ## 放在哪里
 *
 * - 页面自己就有「空白处右键」容器的（[CpRefreshablePage]），必须由那个容器把它并进菜单 ——
 *   外面再包一层会被内层先消费（子级先于父级收到 Main 阶段的 Press），外层永远弹不出来。
 * - 其余页面（设置面板、歌单详情、播放页…）由 `App.kt` 的**整窗兜底菜单**负责，
 *   见 `App.kt` 里那段 `CpContextMenu` 的说明。
 *
 * 安卓端恒返回 `null`：那里没有鼠标右键语义，返回也已由系统键承担。
 *
 * 返回值经 `remember` 稳定：调用方可以安全地把它放进 `remember(items)` 的 key 里。
 */
@Composable
fun rememberBackContextMenuItem(): CpContextMenuItem? {
    val navigator = LocalNavigator.current
    val isDesktop = !isAndroidPlatform()
    // 四个读数都是快照读（Navigator 的栈、壳层面板状态、页面处理器数量），
    // push / pop / 开面板 / 展开播放页都会驱动这一项出现或消失。
    val canPop = isDesktop && (navigator?.size ?: 1) > 1
    val shellCanGoBack = isDesktop && DesktopShell.pageCanGoBack
    val pageCanGoBack = isDesktop && hasPageBackHandler()
    return remember(navigator, canPop, shellCanGoBack, pageCanGoBack) {
        if (!canPop && !shellCanGoBack && !pageCanGoBack) {
            null
        } else {
            CpContextMenuItem(
                label = "返回上一级",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = { goBackOneLevel(navigator, canPop, shellCanGoBack) },
            )
        }
    }
}

/**
 * 返回一层。**顺序与 `Main.kt` 标题栏返回键完全一致**：
 * 先让页面自己的处理器拿（播放页展开态、歌单多选），没人接手才退栈，最后才收内嵌面板。
 *
 * 两处各写一套判据的话，「Esc 能退、点返回键不能退」这类漂移迟早出现 ——
 * 所以宁可让这段逻辑短一点，也别让它分叉。
 */
private fun goBackOneLevel(navigator: Navigator?, canPop: Boolean, shellCanGoBack: Boolean) {
    if (dispatchPageBack()) return
    if (canPop) {
        navigator?.popOrNotify()
    } else if (shellCanGoBack) {
        DesktopShell.backRequested = true
    }
}
