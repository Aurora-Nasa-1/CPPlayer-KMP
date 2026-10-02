package cp.player.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 桌面自绘标题栏 → 主壳层的一条**单向指令**通道。
 *
 * ## 为什么需要它
 *
 * 桌面自绘标题栏渲染在 `Navigator` **之上**（见 [cp.player.app.App] 的 KDoc），而「打开设置」
 * 在桌面是**右侧内嵌面板**（`MainScreen` 的 `DesktopPane.Settings`），它的开关
 * `desktopSettingsOpen` 是 `MainScreen` 的局部 `remember`。两者隔着 Navigator，
 * 既够不到、也传不了参，所以把这条指令收敛到全局。
 *
 * ## 协议（消费方必须遵守）
 *
 * 写入方只置 `true`；`MainScreen` 消费后**必须立刻置回 `false`**，否则从设置面板切回
 * 其它面板时会被残留的 `true` 再次弹回设置页。
 *
 * 与 [UiEvents] 同一思路：这是**一次性指令**，不是需要双向同步的状态。刻意不做成
 * `SharedFlow` —— 标题栏按下按钮时若没有订阅者（比如应用刚启动），事件会丢；
 * 而「打开设置」这种指令，晚一拍被消费掉是正确的，丢掉才是错的。
 */
object DesktopShell {

    /** 请求桌面壳层打开「设置」内嵌面板。 */
    var settingsRequested by mutableStateOf(false)

    /**
     * 请求桌面壳层打开「消息」内嵌面板。
     *
     * 与 [settingsRequested] **逐字同一个来路与协议**：入口在窗口标题栏上（`Navigator` 之上），
     * 而面板的开关是 `MainScreen` 的局部状态（`DesktopPane`）。写入方只置 `true`，
     * `MainScreen` 消费后立刻置回 `false` —— 否则从消息面板切到别的面板时会被残留的
     * `true` 再弹回去。
     *
     * ⚠️ 刻意**不**复用 [settingsRequested]（例如加个枚举参数）：两者的消费动作不同
     * （一个开设置、一个开消息并刷新未读数），共用一个字段就得在里面塞一个"下一次开哪个"
     * 的附加状态，那才是真正的复杂来源。
     */
    var messagesRequested by mutableStateOf(false)

    /**
     * 请求主壳层打开「账号」内容页。
     *
     * 与 [settingsRequested] / [messagesRequested] 同一条单向指令通道（入口在窗口标题栏上，
     * 写入方只置 `true`、消费方立刻置回 `false`），但**消费动作不同**：设置 / 消息切换
     * `MainScreen` 的内嵌面板（`desktopPane`），而「账号」是**内容区路由页** ——
     * 消费方把 `AccountScreen` push 进内容区的内嵌 Navigator，左侧导航栏保留、
     * 返回还能回到原处（账号页内部的「我的主页 / 消息 / 切换音源」也继续落在同一条栈上）。
     */
    var accountRequested by mutableStateOf(false)

    /**
     * 请求主壳层切到「搜索」tab 并执行这个关键词。
     *
     * 与 [settingsRequested] 同一个来路（标题栏在 `Navigator` 之上），但消费方不同：
     * - `MainScreen` **只切 tab、不消费** —— 它只负责把搜索页显示出来；
     * - `SearchScreen` 负责消费（喂给 ScreenModel 后置回 null）。
     *
     * 刻意只有一个真正的消费者：两个地方都置 null 会互相抢，谁先跑谁把值吃掉。
     */
    var pendingSearchQuery by mutableStateOf<String?>(null)

    /**
     * 主壳层当前页面的标题，由 `MainScreen` 发布给桌面标题栏显示。
     *
     * 方向是**从下往上**（页面 → 窗口 chrome），所以只能走全局：标题栏在 `Navigator` 之上，
     * 拿不到 `MainScreen` 的局部状态。`MainScreen` 用 `SideEffect` 发布 —— 同值写入
     * `mutableStateOf` 不触发重组，所以每次重组都写一遍没有额外开销。
     */
    var pageTitle by mutableStateOf("")

    /**
     * 主壳层当前页面是否有「返回」。
     *
     * 现在有两种真来源，都由 `MainScreen` 发布：内嵌面板开着（可收起），或内容区的
     * **内嵌 Navigator** 有详情页（可出栈）。标题栏 / Esc 的兜底返回都读它。
     */
    var pageCanGoBack by mutableStateOf(false)

    /** 请求主壳层收起当前内嵌面板（标题栏上的返回键）。与 [settingsRequested] 同一协议。 */
    var backRequested by mutableStateOf(false)

    // —— 路由页标题（push 出去的页面）——

    /**
     * 标题栏要显示的标题：**最上层路由页的声明优先，没有则回落到 [pageTitle]**。
     *
     * [pageTitle] 是 `MainScreen` 发布的「主壳层当前面板」标题。`MainScreen` 被 push 出去的
     * 页面盖住后会离开组合、不再发布，于是标题会**停在旧值** —— 从标题栏点账号进
     * `AccountScreen`，窗口标题却还写着「首页」。路由页用 [DesktopRouteTitle] 声明自己的标题来盖住它。
     *
     * 用**声明栈**而不是单个字段：push A 再 push B 时，A 仍在栈里但已不在最上层，
     * 直接写同一个字段会让「B 退出后 A 的标题没恢复」。栈按**注册先后**排序，
     * 而注册先后天然等于 push 先后（A 先于 B 进入组合），所以取栈顶就是当前路由。
     */
    val routeTitle: String? get() = routeTitleClaims.lastOrNull()?.title
}

/**
 * 一条标题声明。**必须是有身份语义的类**：`SnapshotStateList.remove` 走 `equals`，
 * 若直接用 `String`，两个标题相同的路由页会互相把对方的声明删掉。
 */
private class RouteTitleClaim {
    var title by mutableStateOf("")
}

private val routeTitleClaims = mutableStateListOf<RouteTitleClaim>()

/**
 * 声明「当前这个路由页的标题」，进入组合时入栈、离开时出栈。
 *
 * 只在**桌面窗口 chrome 接管**时才需要调用 —— 由 `CpRouteScaffold` 统一调用，
 * 少数自绘 chrome 的页面（播放页 / 歌单详情 / 下载管理 …）自己调一次。
 * 安卓端调了也无害（标题栏不存在，没人读这个值）。
 *
 * 初始值在 `remember` 里就地写入，而不是等 [SideEffect]：否则第一帧标题栏会闪一个空标题
 * （`routeTitle` 非 null 但为空串，回落到 [DesktopShell.pageTitle] 的分支就不会走）。
 */
@Composable
fun DesktopRouteTitle(title: String) {
    val claim = remember { RouteTitleClaim().also { it.title = title } }
    SideEffect { claim.title = title }
    DisposableEffect(claim) {
        routeTitleClaims.add(claim)
        onDispose { routeTitleClaims.remove(claim) }
    }
}
