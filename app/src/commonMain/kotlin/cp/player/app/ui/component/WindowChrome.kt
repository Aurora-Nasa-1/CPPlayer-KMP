package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 桌面自绘标题栏的高度。**唯一事实源**。
 *
 * 桌面端（`desktopMain` 的 `DesktopTitleBar`）拿它当标题栏高度，`commonMain` 的
 * `App` 拿它当「给内容让位」的基准值 —— 播放页沉浸态时这个让位量动画收缩到 0，
 * 播放页的流体背景因此铺到窗口顶边。两处必须是同一个数，所以常量只能有一份。
 *
 * 安卓端没有标题栏，这个值不参与任何布局。
 */
val CpTitleBarHeight: Dp = 44.dp

/**
 * 桌面窗口顶栏是否处于「播放页沉浸态」。
 *
 * 为真时标题栏把背景让给播放页的流体渐变，并且**除最小化 / 最大化 / 关闭三颗窗口钮以外**
 * 的内容（返回、标题、搜索、账号、消息、迷你模式、设置）整体淡出。
 *
 * 由 `App` 依据三件事一起判定后提供（缺一不可）：
 * 1. 注入了自绘标题栏（`titleBar` 槽位非空 ⇒ 桌面端，且不是手机版预览）；
 * 2. 窗口宽度处于 Expanded（`LocalIsExpanded`）—— 窄窗走的是手机版播放页，
 *    它自带顶栏与收起键，让窗口顶栏一起淡出反而会和页内顶栏打架；
 * 3. **当前路由是播放页**（`navigator.lastItem is PlayerScreen`）。
 *
 * 判据刻意挂在「路由 + 宽度」上而不是由播放页自己注册：Voyager 的转场里退场页
 * 会**多停留一整个转场时长**，那一刻它已经不再是最上层页面了。用注册制的话，
 * 用户点了收起之后，背景要等转场结束才肯落下来，中间那 300ms 里新页面是**顶在上面**
 * 被压掉 44dp 的 —— 看着像「页面先往上跳一下、再掉回来」。跟随栈顶则两个方向都即时翻转。
 */
val LocalWindowImmersive = staticCompositionLocalOf { false }

/**
 * 播放页为了避开窗口顶栏，需要**额外**补出来的顶部内边距。
 *
 * ## 为什么需要它（而不是让 `App` 直接把内容顶上去）
 *
 * 沉浸态的做法是：`App` 里标题栏的占位高度 A 从 [CpTitleBarHeight] 动画收到 0
 * ⇒ 页面区域整体长高 44dp、流体系背景铺到窗口顶边。但**内容不能跟着顶上去** ——
 * 顶上去的那一条正是 JBR 画最小化 / 最大化 / 关闭的位置，播放页的「更多 / 翻译」
 * 浮岛会正好被压在窗口钮底下。
 *
 * 所以两边各让一步：`App` 把 A 收到的量通过这个 local 告诉播放页，播放页再把它补成
 * 自己的顶部内边距。两者之和恒等于 [CpTitleBarHeight] ⇒ **内容位置全程不动，
 * 只有背景在往上长**；动画过程中也不会出现内容抖动（两个值出自同一次动画，不是各动画各的）。
 *
 * 安卓端 / 没有标题栏时是默认值 0（没人提供，也不该补）。
 */
val LocalPlayerTopInset = compositionLocalOf { 0.dp }

/**
 * 「桌面播放页正在显示」的登记表（见 [DesktopPlayerStage]）。
 *
 * 用**列表 + 身份对象**而不是一个布尔量：进出播放页时新旧两份内容会同时存在一整个转场
 * （`AnimatedContent` 的退场方要留到淡出结束），单个布尔量会被「后进先出」的 dispose 顺序
 * 冲掉 —— 新内容已经置 true，旧内容 dispose 又把它置回 false，标题栏就永远回不来。
 * 列表按「还有没有人登记」回答，天然不受顺序影响。
 *
 * 为什么不是 `stateIn` / `SharedFlow`：这是**同步的组合期状态**，`App` 要在同一帧读到它
 * 才能决定标题栏占位高度，异步流会晚一帧、动画就会看到跳变。
 */
private val playerStageClaims = mutableStateListOf<Any>()

/** 当前是否有桌面播放页在显示（[DesktopPlayerStage] 的登记结果）。 */
val isPlayerStageActive: Boolean
    get() = playerStageClaims.isNotEmpty()

/**
 * 声明「桌面播放页正在显示」，进入窗口顶栏的沉浸态（见 [LocalWindowImmersive]）。
 *
 * ## 调用点有讲究：**谁决定显示播放页，谁登记 —— 不是播放页自己**
 *
 * 桌面播放页有两个渲染位置：
 * 1. `MainScreen` 的展开覆盖层（`isPlayerExpanded`，用户点底栏小播放器，最常用）；
 * 2. 根栈上的 `PlayerScreen` 路由（主壳层之外的页面里点小播放器 / 首页播放按钮）。
 *
 * 两条路对应两种登记：
 * - **壳层**：在 `AnimatedContent` **外面**跟着 `isPlayerExpanded && expanded` 登记
 *   （见 `MainScreen`）—— 挂在开关上，点击的那一帧登记 / 注销同时发生；
 * - **路由**：`App` 直接判 `navigator.lastItem is PlayerScreen`，push / pop 都在那一帧翻转。
 *
 * ⚠️ **不能登记在播放页本体**（`DesktopPlayerScreen`）上：退场方要在转场里多活
 * 整整一个节拍（淡出结束）才被摘掉 —— 那样窗口顶栏就得等播放页淡完才开始回落，
 * 主界面先被顶高 44dp、再滑下来，这正是「进入 / 退出全屏播放器时序不太对」的那一下。
 *
 * 窄窗（<840dp）走手机版播放页，**不登记** —— 它自己带顶栏与收起键，
 * 让窗口顶栏一起淡出反而会和页内顶栏打架。
 */
@Composable
fun DesktopPlayerStage() {
    val claim = remember { Any() }
    DisposableEffect(claim) {
        playerStageClaims.add(claim)
        onDispose { playerStageClaims.remove(claim) }
    }
}

/**
 * 窗口级顶栏（桌面自绘标题栏）是否已经接管了「账号 / 设置」两个入口。
 *
 * 由 [cp.player.app.App] 依据 `titleBar` 槽位**是否被注入**来提供：桌面端注入自绘标题栏后为
 * `true`，Android 端恒为 `false`。`MainScreen` 据此决定要不要在自己的 `AppTopBar` /
 * `DesktopSidebar` 里重复渲染这两个入口。
 *
 * ## 为什么判据是「槽位是否被注入」而不是「是不是桌面」
 *
 * 两个理由，都是踩过的坑：
 * - `MainScreen` 的展开态分支条件是**宽度 ≥840dp**，不是平台 —— 宽屏 Android 平板走的是同一套
 *   `AppTopBar` 与 `DesktopSidebar`。拿平台去判断会让平板丢掉这两个入口。
 * - 将来若给无边框加一个「回退到系统标题栏」的开关（无边框在个别机器上出问题时的自救通道），
 *   那时桌面端仍然 `isDesktop == true`，但**并没有标题栏**，两个入口会凭空消失。
 *
 * 用 `staticCompositionLocalOf` 而非 `compositionLocalOf`：这个值在一次组合里是常量，
 * 不会中途变化；static 版本读起来没有快照订阅开销，正是它该用的场景。
 */
val LocalWindowChromeActive = staticCompositionLocalOf { false }
