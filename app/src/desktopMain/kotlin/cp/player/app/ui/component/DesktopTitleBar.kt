package cp.player.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.theme.CpMotion
import cp.player.app.version.AppVersion
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.windowDragArea

/**
 * 自绘标题栏高度。留得下 32dp 以上的点击目标，同时不侵占正文。
 *
 * 数值本身定义在 `commonMain` 的 [CpTitleBarHeight] —— `App` 要按同一个数给内容让位
 * （播放页沉浸态时让它动画收缩到 0），两个模块各写一个 44 迟早会漂。
 */
internal val TitleBarHeight: Dp = CpTitleBarHeight

/** 标题栏里每个可点区块的宽度。窗口控制与账号 / 设置共用同一档，保证点击目标一致。 */
private val ChromeSlotWidth = 44.dp

/** 分隔线占用的横向宽度：左右各 8dp 内缩 + 1dp 的线本身。 */
private val ChromeSeparatorWidth = 8.dp * 2 + 1.dp

/**
 * 右侧按钮组的总宽度：账号 + 消息 + 设置 + 分隔线 + 最小化 / 最大化 / 关闭。
 *
 * ⚠️ **左侧区域刻意取同样宽**：这样中间的搜索框才是在**整条标题栏里**居中，而不是被右侧
 * 这 281dp 挤偏（第一版就是这样，看着别扭）。两侧等宽 ⇒ 搜索框真正居中，也更接近标准桌面应用。
 *
 * ⚠️ 这个值必须与 [DesktopTitleBar] 里 actions 区**实际渲染的槽位数**严格对齐：
 * 少算一格右侧就宽出去 44dp、搜索框整体左偏 22dp；多算一格则反过来。改动按钮组时
 * 数一遍 —— 这里曾经因为「消息」搬进来而差点漏掉（5 槽 → 6 槽）。
 *
 * Tao 后端没有原生窗口钮，右侧组永远是「业务槽 + 分隔线 + 三颗自绘窗口钮」。
 */
private val ChromeActionsWidth: Dp = ChromeSlotWidth * 7 + ChromeSeparatorWidth

/**
 * 桌面端自绘标题栏。
 *
 * 布局自左至右：**返回?** · 页面标题 + 拖拽区（宽度 = 右侧按钮组） · 全局搜索框（居中） ·
 * 账号 · 消息 · 设置 · 分隔线 · 最小化 / 最大化 / 关闭。
 *
 * ## 它承担了三件事
 *
 * 1. **窗口操作**：拖拽、双击最大化 / 还原、最小化 / 最大化 / 关闭（系统边框已经没有了）；
 * 2. **页面标题与返回**：这是**桌面端唯一的返回入口** —— `MainScreen` 的 `AppTopBar`
 *    在桌面端整体让位（见 `LocalWindowChromeActive`），`AppScaffold` 与 `CpRouteScaffold`
 *    也不再自绘返回键。否则窗口顶部会叠两条 chrome（44dp 标题栏 + 64dp 顶栏），
 *    同一屏还会出现两个位置、外观都不同的返回键；
 * 3. **全局入口**：搜索、消息、账号、设置。四个都是「跟当前页面无关」的动作，
 *    放在窗口 chrome 上才够得到任意页面 —— 消息从侧栏搬过来就是这条理由
 *    （侧栏会被 push 出去的路由页盖住，而私信角标是不该被盖住的）。
 *
 * ## 播放页沉浸态（[LocalWindowImmersive]）
 *
 * 当窗口停在全屏播放页时，`App` 把本组件的占位高度动画收到 0，播放页的流体背景于是
 * 铺到窗口顶边；本组件同时把**除最小化 / 最大化 / 关闭三颗窗口钮以外**的一切
 * （返回、标题、搜索、账号、消息、迷你模式、设置、底色、分隔线）淡到全透明。
 *
 * 三条硬约束，改动时都会踩到：
 * 1. **高度不变**，只淡颜色。顶栏一旦跟着变矮，三颗窗口钮就会跟着窗口顶边跑；
 * 2. **宽度不变**：淡出期间元素仍占着原来的位置（搜索框是这一排唯一的 `weight(1f)`），
 *    淡完立刻换成**等宽留白**。直接摘掉会让窗口钮左移，一直留着则见第 3 条；
 * 3. **淡完必须真的把节点摘掉**，不能只靠 `alpha = 0` + 消费指针（第一版就是这样，
 *    用户当场发现「只是隐藏，鼠标指针还在」）：hover 小手、搜索框的 I 形文本光标、
 *    键盘焦点都跟着**节点**走，`graphicsLayer(alpha = 0)` 全都挡不住，
 *    甚至 `Initial` pass 上消费指针也挡不住光标（`pointerHoverIcon` 是独立链路）。
 *
 * 播放页自己会用 `LocalPlayerTopInset` 把让出的高度补成内边距，内容位置全程不动 ——
 * 那一条正是窗口钮所在的位置，内容要是跟着顶上去就会撞在一起。
 *
 * ## 窗口后端：Tao
 *
 * 窗口由 Nucleus Tao 创建，没有 `java.awt.Window`。本组件是**唯一**的窗口 chrome：
 * 拖拽、双击最大化 / 还原、最小化 / 最大化 / 关闭全部由这里驱动。
 *
 * - 拖拽走 [windowDragArea]（Compose 指针事件 → `TaoWindow.dragWindow()`，双击 →
 *   `ToggleMaximize`）。它不依赖 AWT MouseListener，也不吃原生 hit-test。
 * - 最大化状态直接读 `windowState.placement`；按钮写同一个状态，Tao 负责把
 *   `Maximized` 映射到尊重任务栏的原生最大化。
 * - 窗口最小尺寸与 `resizable` 由 `DecoratedWindow` 的参数下发；本组件不再命令式
 *   `setMinimumSize` / `setResizable`。
 *
 * ⚠️ **本组件是纯展示的**：[title] / [canGoBack] / [onBack] 都由 `Main.kt` 从 Navigator
 * 现算现传（那里同时拿得到 `navigator.size`、`navigator.lastItem` 与 `DesktopShell`）。
 * 它自己不读 `DesktopShell` —— 曾经读的是 `MainScreen` 发布的 `pageCanGoBack`，
 * 而那个值只在「桌面内嵌面板打开」时为真，于是**所有 push 出去的路由页在标题栏上都没有返回键**，
 * 宽窗口下更是一整页没有返回入口（页内顶栏也正好在宽屏分支里被省掉了）。
 *
 * ## 窗口框架
 *
 * Windows / Linux 上 Nucleus 为 Tao 窗口画 CSD 边框与阴影，客户区从 (0,0) 开始；
 * 缩放边框由 Tao 原生窗口负责。`undecorated = true` 时连 CSD 边框与阴影一起去掉，
 * 但本组件的拖拽、窗控与布局完全不变。
 *
 * ## 调用位置有硬约束（别挪）
 *
 * 必须由 `App()` 的 `titleBar` 槽位渲染 —— 也就是在 **`CpTheme` 之内、`Navigator` 之上**：
 * - 放在主题外 ⇒ 拿不到 `MaterialTheme.colorScheme`，换主题时这条会变成死色；
 * - 放在 `Navigator` 之下（例如塞进 `MainScreen`）⇒ 一旦 push 到 `AccountScreen` /
 *   `SettingsScreen`，标题栏会被目标页面盖住。窗口已经没有系统边框了，那意味着用户
 *   **既移不动也关不掉窗口**。
 *
 * ## 为什么要显式传 [taoWindow]
 *
 * `windowDragArea` 可以读 `LocalTaoWindow`，但这个组件也会被塞进别的窗口场景（例如
 * 迷你播放器 / 手机预览），显式传句柄能保证拖拽永远落在**本窗口**，不会因为
 * CompositionLocal 被父场景桥接而驱动错窗口。
 *
 * ## 为什么根节点是 `Box` 而不是 `Surface`，而且必须带 `zIndex`
 *
 * 搜索建议下拉要**溢出到标题栏之外**、盖在正文上。两件事都得满足：
 * 1. 根节点不能裁剪子节点。Compose 的布局默认不裁剪，所以用普通 `Box` + `background`
 *    而不是 Material3 的 `Surface`（后者会按 shape 裁剪内容），下拉才画得出去；
 * 2. 标题栏必须画在正文**之后**。它是 `App()` 里那个 `Column` 的第一个子节点，
 *    不抬 z 就会被后面的正文 `Box` 盖住 —— `zIndex` 只在同级之间比较，这里正好同级。
 *
 * ## 拖拽区必须与可交互控件严格错开
 *
 * [windowDragArea] 在 `PointerEventPass.Final` 上武装拖拽：可交互控件在 `Main` pass 消费
 * Press 后就自动退出拖拽。所以：
 * - 返回键、账号、设置、窗口控制必须留在拖拽区**外面**；
 * - 标题可以放在拖拽区里（纯 `Text` 不吃指针事件）；
 * - 搜索框两侧的空白必须各自是独立拖拽区，与框首尾相接 —— 见 [TitleBarSearch]。
 * - 只在「指针移动后才接管」的控件（滑条、滚动条）必须加 `Modifier.noWindowDrag()`，
 *   否则按下的一瞬间会先拖动窗口。
 */
@Composable
fun DesktopTitleBar(
    taoWindow: TaoWindow,
    windowState: WindowState,
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: (String) -> Unit,
    onToggleMiniMode: () -> Unit = { cp.player.app.ui.util.DesktopShell.isMiniMode = true },
    onToggleMobileMode: () -> Unit = { cp.player.app.ui.util.DesktopShell.isMobileMode = true },
    modifier: Modifier = Modifier,
) {
    // 播放页沉浸态：背景让给页面自己的流体渐变，除窗口钮外全部淡出（见 LocalWindowImmersive）。
    val immersive = LocalWindowImmersive.current
    val chromeAlpha by animateFloatAsState(
        targetValue = if (immersive) 0f else 1f,
        // ⚠️ 必须钉在 [CpMotion.PlayerExpandMs] 上（播放页淡入淡出的节拍）：三个动画同拍
        // 才是「一次交接」。第一版用 `effects()` 的默认时长（约 200ms），页面要 400ms 才
        // 长出来 —— 中间那 200ms 顶栏是空的，正是用户点名的「时序不太对」。
        animationSpec = tween(durationMillis = CpMotion.PlayerExpandMs),
        label = "titleBarChromeAlpha",
    )
    // 淡完之后**把内容从组合里摘掉**（结构见下面 Row 的 else 分支）。
    // 判据用「不是沉浸态」或「alpha 还没走到 0」：摘得太早会看不到淡出，摘得太晚
    // 会留下一排看不见但仍可悬停 / 可点的控件（光标与小手的来源）。
    val showChrome = !immersive || chromeAlpha > 0.01f
    val maximized = windowState.placement == WindowPlacement.Maximized
    val toggleMaximize: () -> Unit = {
        windowState.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }

    // 右侧按钮组总宽：账号 + 消息 + (仅 desktopRun/debug: 手机预览) + 迷你模式 + 设置
    // + 分隔线 + 三颗窗口钮。
    val showMobilePreview = AppVersion.isDesktopRunOrDebug
    val slotsCount = if (showMobilePreview) 5 else 4
    val rightGroupWidth = ChromeActionsWidth

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            // 沉浸态把底色淡到全透明：窗口顶边露出的是播放页的流体渐变，而不是一块死板的面板色。
            // ⚠️ 这里只淡**颜色**，高度始终是 TitleBarHeight —— 顶栏一旦跟着变矮，
            // 右侧窗口钮就会跟着窗口顶边移动，而它们必须是全程钉死的。
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = chromeAlpha))
            .zIndex(1f),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (showChrome) {
                    val animatedBackSlotWidth by animateDpAsState(
                        targetValue = if (canGoBack) ChromeSlotWidth else 0.dp,
                        animationSpec = tween(durationMillis = 200),
                        label = "backSlotWidth",
                    )

                    // 返回键：**窗口 chrome 是桌面端唯一的返回入口** —— 页面内一律不自绘
                    // （见 `AppScaffold` / `CpRouteScaffold`）。是否可返回由调用方（`Main.kt`）
                    // 依据 Navigator 栈与内嵌面板状态算好，这里只负责画。
                    // 必须在拖拽区**外面**（可交互控件会在 Main pass 消费 Press）。
                    AnimatedVisibility(
                        visible = canGoBack,
                        enter = fadeIn(tween(180)) + expandHorizontally(tween(200)),
                        exit = fadeOut(tween(150)) + shrinkHorizontally(tween(200)),
                    ) {
                        ChromeSlot(
                            onClick = onBack,
                            modifier = Modifier.chromeFade(chromeAlpha, interactive = !immersive),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // 标题 + 左侧拖拽空白。
                    //
                    // 宽度 = 右侧按钮组宽度（若显示了返回键就减掉它占的那格）—— 两侧等宽，中间的
                    // 搜索框才是**真正居中**。标题是纯 Text，不吃指针事件，可以待在拖拽区里。
                    //
                    WindowDragRegion(
                        taoWindow = taoWindow,
                        modifier = Modifier
                            .width((rightGroupWidth - animatedBackSlotWidth).coerceAtLeast(0.dp))
                            .fillMaxHeight()
                            // 拖拽区跟随淡出：沉浸态下节点会被摘掉，只留可视的窗口钮。
                            .chromeFade(chromeAlpha, interactive = !immersive),
                    ) {
                        AnimatedContent(
                            targetState = title,
                            transitionSpec = {
                                (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 8 }) togetherWith
                                    (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 8 })
                            },
                            label = "desktopTitleBarText",
                        ) { currentTitle ->
                            CpText(
                                text = currentTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.padding(start = CpSpacing.pageHorizontal),
                            )
                        }
                    }

                    // 搜索框占满剩余宽度，内部再居中一个上限 420dp 的框 ⇒ 落在整条标题栏的正中。
                    //
                    // ⚠️ 为什么不是「给搜索框一个更宽的槽位、让它自己在里面居中」：槽位比框宽出来的
                    // 那部分如果不属于任何拖拽区，就成了**拖不动的死区**（第一版就是这样）。
                    // 所以空白必须拆成两块、与框首尾相接、互不重叠 —— 见 [TitleBarSearch]。
                    TitleBarSearch(
                        onSearch = onSearch,
                        fieldModifier = Modifier,
                        dragArea = { searchDragModifier ->
                            WindowDragRegion(taoWindow = taoWindow, modifier = searchDragModifier)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            // 搜索框本体跟着淡出，但**宽度不动**：它是这一排里唯一的 weight(1f)，
                            // 一旦摘掉，右边的账号 / 消息 / 设置与三颗窗口钮会整体左移。
                            .chromeFade(chromeAlpha, interactive = !immersive),
                    )

                    // 账号 / 消息 / 设置：从 `MainScreen` 的 AppTopBar 与 DesktopSidebar 迁来。
                    //
                    // 「消息」是 2026-10-02 从侧栏搬进来的：消息是**全局**入口（不该被 push 出去的
                    // 路由页盖住），而侧栏只在 MainScreen 里。与「设置」同一处境。
                    //
                    // ⚠️ 顺序（2026-10-02 调整）：**消息排在设置左边** —— 越靠右离窗口控制越近，
                    // 而「设置」是最常点的一个，紧贴分隔线比被夹在中间更好找。窗口控制组
                    AccountSlot(
                        onClick = onOpenAccount,
                        modifier = Modifier.chromeFade(chromeAlpha, interactive = !immersive),
                    )
                    MessageSlot(
                        onClick = onOpenMessages,
                        modifier = Modifier.chromeFade(chromeAlpha, interactive = !immersive),
                    )
                    if (showMobilePreview) {
                        ChromeSlot(
                            onClick = onToggleMobileMode,
                            modifier = Modifier.chromeFade(chromeAlpha, interactive = !immersive),
                        ) {
                            Icon(Icons.Filled.PhoneAndroid, "手机版界面预览", modifier = Modifier.size(18.dp))
                        }
                    }
                    ChromeSlot(
                        onClick = onToggleMiniMode,
                        modifier = Modifier.chromeFade(chromeAlpha, interactive = !immersive),
                    ) {
                        Icon(Icons.Filled.PictureInPictureAlt, "迷你模式", modifier = Modifier.size(18.dp))
                    }
                    ChromeSlot(
                        onClick = onOpenSettings,
                        modifier = Modifier.chromeFade(chromeAlpha, interactive = !immersive),
                    ) {
                        Icon(Icons.Filled.Settings, "设置", modifier = Modifier.size(18.dp))
                    }
                } else {
                    // 沉浸态兜底：**把内容从组合里摘掉**，换成等宽留白。
                    //
                    // ⚠️ 只把 alpha 淡到 0 是不够的（第一版就这样，用户当场看出来：
                    // 「好像只是隐藏，还有鼠标指针呢」）：看不见的账号 / 消息 / 设置
                    // 仍然是可点目标 —— 悬停出小手与 hover 高亮；更烦的是搜索框，
                    // 它的 I 形文本光标照样出现，还能点进去打字。
                    // `graphicsLayer(alpha = 0)` 挡不住、在 Initial pass 消费指针也挡不住
                    // （hover 光标走 `pointerHoverIcon` 这条独立链路，不认消费）。
                    // 所以淡完之后直接把节点摘掉：命中测试、光标、焦点随之一起消失。
                    //
                    // 摘掉就必须自己把宽度补回来 —— 这一段决定了右侧三颗窗口钮的横向位置。
                    // 左侧块 = 右侧按钮组宽度，中间是原来的 weight(1f)，
                    // 右侧业务槽按 slotsCount 补。沉浸态下窗口顶边仍然可以拖。
                    WindowDragRegion(
                        taoWindow = taoWindow,
                        modifier = Modifier.width(rightGroupWidth).fillMaxHeight(),
                    )
                    WindowDragRegion(
                        taoWindow = taoWindow,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    Spacer(Modifier.width(ChromeSlotWidth * slotsCount))
                }

                // 分隔线跟着一起淡：它划的是「业务入口 ↔ 窗口钮」的分界，业务入口没了，
                // 只剩一条孤零零的竖线压在窗口钮左边，比没有更显脏。
                ChromeSeparator(Modifier.chromeFade(chromeAlpha, interactive = true))

                // ⚠️ 刻意用 `Remove`（居中横线）而不是 `Minimize`：后者的横线贴在该字形框
                // 底部，与相邻的 CropSquare / Close 不在同一条水平线上（实测低了约 5px，
                // 肉眼扫过去只是「有点歪」，量像素才看得出来）。
                WindowControlButton(Icons.Filled.Remove, "最小化") { windowState.isMinimized = true }
                WindowControlButton(
                    icon = if (maximized) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
                    contentDescription = if (maximized) "向下还原" else "最大化",
                    onClick = toggleMaximize,
                )
                WindowControlButton(Icons.Filled.Close, "关闭", danger = true, onClick = onClose)
            }
            // 标题栏与正文在浅色主题下都接近白色，靠 1dp 分隔线划出层次。
            // 沉浸态跟着底栏一起淡掉：留一条灰线压在播放页的渐变上，比什么都没有更显脏。
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f * chromeAlpha)),
            )
        }
    }
}

/**
 * 一块可拖拽区域（内部内容左对齐垂直居中）。
 *
 * [Modifier.windowDragArea] 在 Final pass 上观察未被消费的 Press，移动后调用
 * `TaoWindow.dragWindow()`；双击由 Nucleus 的 `ToggleMaximize` 处理。最大化状态下
 * **不禁用**拖拽：Windows 原生行为就是「拖出即还原并跟随光标」。
 *
 * 之所以抽成一个函数而不是各处内联：拖拽区与可交互控件必须**严格错开**（见 [DesktopTitleBar]
 * 的说明），把「怎么拖」收敛到一处，改的时候才不会漏掉某一个调用点。
 */
@Composable
private fun WindowDragRegion(
    taoWindow: TaoWindow,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier.windowDragArea(taoWindow),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

/**
 * 消息入口。
 *
 * ⚠️ **刻意不带未读角标**（2026-10-02 按用户要求去掉）。此前这里挂了一个
 * `BadgedBox` + `Badge`，在标题栏这种"安静"的条上，那个红点比它承载的信息显眼得多 ——
 * 用户每次扫过窗口顶部都会被拽一下注意力，而私信未读并不是需要随时被提醒的事。
 * `AppModel.unreadMessagesFlow` 那套数据链路**全部保留**（消息页仍在读它），
 * 只是不再在这里显示。
 */
@Composable
private fun MessageSlot(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChromeSlot(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.AutoMirrored.Filled.Message,
            contentDescription = "消息",
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 账号入口。有头像显示头像，没有则回落到人形图标。
 *
 * 与 `MainScreen` 里那份旧实现保持同一套回退规则：头像 URL 可能是空串（已登录但资料没拉到），
 * 所以判据是 `isNullOrBlank()` 而不是 `!= null`。
 */
@Composable
private fun AccountSlot(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val profile by AppModel.userProfileFlow.collectAsState()
    val avatarUrl = profile?.avatarUrl
    ChromeSlot(onClick = onClick, modifier = modifier) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = "账号",
                modifier = Modifier.size(24.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Filled.Person, "账号", modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * 标题栏里「沉浸播放时淡出」的通用处理：**只动 alpha 与命中测试，绝对不动尺寸**。
 *
 * 尺寸由调用方保证（宽度原样留着）；**节点本身在淡完之后会被摘掉**（见 Row 里的 else 分支），
 * 那一步才是解决「看不见的按钮还能悬停、搜索框还有 I 形光标」的地方 —— 这里只能让
 * 淡出那一两百毫秒里点不动、也弹不出 hover 高亮。
 *
 * ## 为什么不能把这几个区块提前从布局里摘掉
 *
 * 标题栏的横向定位靠的是「左右两侧等宽」：左侧（返回键 + 标题 + 两段拖拽空白）刻意
 * 与右侧按钮组取同一个宽度 [ChromeActionsWidth]，中间的搜索框才因此在**整条标题栏**
 * 里居中（见 [ChromeActionsWidth] 的说明）。一旦把左边收掉，Row 后段的账号 / 消息 /
 * 设置与三颗窗口钮会整体左移 —— 而窗口钮必须是全程钉死的（用户靠肌肉记忆点它，
 * 位置一跳就会点空）。
 *
 * ## 为什么还要在 `Initial` pass 上吃指针
 *
 * 淡出过程中元素仍在布局里，[interactive] 为假时把指针事件全部吃掉：`clickable` /
 * 输入框都走 `Main` pass 且要求事件未被消费，所以这 200ms 里的误触不会真的点下去。
 * （它**挡不住**光标形状，那要等节点被摘掉。）
 *
 * ⚠️ `windowDragArea` 会在 Final pass 上看到这里已经消费掉的 Press，所以淡出的
 * 那几百毫秒内拖不动；淡完之后节点被摘掉，[DesktopTitleBar] 的兜底分支会换成
 * 不带 `chromeFade` 的纯拖拽区，窗口顶边又能拖了。
 */
private fun Modifier.chromeFade(alpha: Float, interactive: Boolean): Modifier {
    val faded = graphicsLayer { this.alpha = alpha }
    if (interactive) return faded
    return faded.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
}

/** 账号 / 设置与窗口控制之间的竖向分隔线，避免两组按钮在视觉上连成一排。 */
@Composable
private fun ChromeSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxHeight().width(ChromeSeparatorWidth),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 1.dp, height = 18.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/**
 * 标题栏里一个可点区块的通用外壳：方形 hover 高亮 + 无涟漪点击。
 *
 * 刻意不用 `IconButton`：它带 48dp 最小触控目标与圆形涟漪，在标题栏里既撑高又不像窗口按钮。
 * 关闭键 hover 时整块变 `error`，同时把内容色切到 `onError`，所以内容只需要读
 * `LocalContentColor`（`Icon` / `Text` 默认就吃它），不必层层传 tint。
 *
 * ⚠️ [content] 必须留在参数表**最后** —— 调用点用的是尾随 lambda，一旦后面再挂一个
 * `Boolean` 之类的参数，lambda 会被静默改绑到那个参数上，而报错指向调用点、看不出真因。
 */
@Composable
private fun ChromeSlot(
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme

    val background by animateColorAsState(
        targetValue = when {
            hovered && danger -> colors.error
            hovered -> colors.surfaceContainerHighest
            else -> Color.Transparent
        },
        // hover 是高频反馈，走 effects（颜色类）而不是 spatial —— spatial 带回弹，
        // 用在颜色上会看到底色来回抖。
        animationSpec = CpMotion.effectsFast(),
        label = "chromeSlotBackground",
    )
    val contentColor = when {
        hovered && danger -> colors.onError
        hovered -> colors.onSurface
        else -> colors.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(ChromeSlotWidth)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** 窗口控制按钮（最小化 / 最大化 / 关闭）。Tao 后端全部自绘。 */
@Composable
private fun WindowControlButton(
    icon: ImageVector,
    contentDescription: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) = ChromeSlot(danger = danger, onClick = onClick) {
    Icon(icon, contentDescription, modifier = Modifier.size(15.dp))
}
