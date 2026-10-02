package cp.player.app.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import cafe.adriel.voyager.navigator.Navigator

/**
 * **根** Navigator（`App.kt` 里那一条），由 `App` 在整棵树外层 provide。
 *
 * 桌面宽屏的内容区里有**内嵌 Navigator**（`MainScreen` 的 `DesktopContentRootScreen`），
 * 页面里读 `LocalNavigator.current` 拿到的是**最近的**那一个 —— 详情页 push 全落内容区，
 * 左侧导航栏因此常驻。这是大多数页面的正确行为，但**全屏体验**是例外：播放页这类
 * 要盖住整个窗口（含侧栏）的页面，必须显式 push 到根 Navigator，否则会被塞进内容区、
 * 贴着侧栏渲染。调用点极少（`HomeScreen` 的「打开播放页」、全局 MiniPlayer），统一改读本 local。
 *
 * 平台上，内嵌 Navigator 不存在，本 local 与 `LocalNavigator.current` 是同一个 Navigator，
 * 两条写法行为一致。
 */
val LocalRootNavigator = staticCompositionLocalOf<Navigator?> { null }

/**
 * 从任意页面**安全地**返回上一页。
 *
 * ## 为什么需要它
 *
 * 仓里原先有 14 个页面各自写 `navigator.pop()`，其中 11 个用
 * `LocalNavigator.currentOrThrow`、3 个用 `LocalNavigator.current`（可空）。
 * 两种写法都有问题：
 *
 * 1. **`currentOrThrow` 会直接抛异常**。页面在 `Screen.Content()` 里读它是安全的
 *    （Voyager 一定在 Navigator 作用域内组合），但一旦有人把这个引用**捕获进 lambda**
 *    再在别的时机调用（比如 `rememberCoroutineScope` 的协程里、对话框的 `onDismiss`），
 *    就可能落在 Navigator 已出栈之后 —— 那时是 `NavigatorDisposedException`，
 *    表现为「点一下按钮整个应用崩溃」。
 * 2. **`current`（可空）的 `?.pop()` 会静默什么都不做**。用户点了「返回」，
 *    界面纹丝不动、也没有任何提示，他会以为按钮坏了，于是反复点。
 *    这是「按下按钮没有回馈」最典型的形态。
 *
 * 这个扩展把两种情况收敛成一条契约：**要么真的退了一页，要么用 Snackbar 明确告知为什么退不了。**
 * 绝不静默失败，也绝不把 `Navigator` 的生命周期问题变成崩溃。
 *
 * @param fallbackMessage 栈底无法再出栈时的提示文案。默认文案刻意说清「已经在最外层」，
 *   而不是笼统的「无法返回」—— 后者读起来像出了错。
 * @return 是否真的执行了出栈。
 */
fun Navigator?.popOrNotify(fallbackMessage: String = "已经在最外层了"): Boolean {
    // 判据是 `size > 1` 而不是「调用 pop() 看它有没有抛」：
    // 栈底 `pop()` 在 Voyager 里的行为是**静默 no-op**（不抛、也不退），
    // 靠返回值区分不出来，只能预先判栈深。
    if (this == null) {
        UiEvents.notify(fallbackMessage)
        return false
    }
    if (size <= 1) {
        UiEvents.notify(fallbackMessage)
        return false
    }
    return runCatching {
        pop()
        true
    }.getOrElse {
        // 走到这里说明 Navigator 已经 dispose 掉了（页面离开组合后回调才触发）。
        // 这不该崩 —— 用户要的是「返回」，而当前位置本来就退不了，给一句提示即可。
        UiEvents.notify(fallbackMessage)
        false
    }
}

/**
 * 从任意页面**安全地**打开一个新路由页。
 *
 * 与 [popOrNotify] 同一契约：**要么真的push 了，要么明确告知失败**。
 *
 * 为什么不做成「什么都不发生」：`navigator?.push(...)` 在 `navigator` 为 null 时
 * 完全静默 —— 用户点了「关于」看着像是没反应。这类「按钮点了没动静」的观感
 * 比报错更糟：报错至少说明系统知道发生了什么。
 *
 * @return 是否真的打开了新页。
 */
fun Navigator?.pushOrNotify(
    screen: cafe.adriel.voyager.core.screen.Screen,
    fallbackMessage: String = "暂时无法打开这个页面",
): Boolean {
    if (this == null) {
        UiEvents.notify(fallbackMessage)
        return false
    }
    return runCatching {
        push(screen)
        true
    }.getOrElse {
        UiEvents.notify(fallbackMessage)
        false
    }
}

/**
 * 把根 Navigator 弹回**主壳层**（`MainScreen`），返回是否真的需要回退。
 *
 * ## 为什么必须有它（一次真实的静默失败）
 *
 * 「设置 / 消息 / 搜索」在桌面端是 `MainScreen` 的**内嵌面板**，开关是
 * `MainScreen` 的局部状态 `desktopPane`，靠 `LaunchedEffect` 消费 `DesktopShell` 里
 * 的单向指令（`settingsRequested` / `messagesRequested` / `pendingSearchQuery`）。
 *
 * 而 `MainScreen` 是根 Navigator 的**起点**，Voyager 只渲染栈顶 —— 从曲库 push 到
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
 * ## 为什么用 `popUntilRoot()` 而不是循环 `pop()`
 *
 * `popUntilRoot()` 是 Voyager 提供的原子操作（已 `javap` 核实签名），
 * 一次调用清空到只剩根页；循环 `pop()` 每调用一次都会触发一帧重组，
 * 深栈时会看到中间的页面一闪而过。
 *
 * @return 是否执行了回退（`false` 表示本来就在主壳层，无需回退）。
 */
fun Navigator?.popToMainShell(): Boolean {
    if (this == null) return false
    if (size <= 1) return false // 已经在根页（通常就是 MainScreen）
    return runCatching {
        popUntilRoot()
        true
    }.getOrElse {
        // Navigator 已 dispose：没法保证主壳层可见，但也不该崩。
        false
    }
}
