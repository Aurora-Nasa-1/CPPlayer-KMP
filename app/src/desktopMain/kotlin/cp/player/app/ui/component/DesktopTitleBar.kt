package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.platform.JbrWindowChrome
import cp.player.app.platform.WindowMaximizer
import cp.player.app.ui.theme.CpMotion

/** 自绘标题栏高度。留得下 32dp 以上的点击目标，同时不侵占正文。 */
internal val TitleBarHeight = 44.dp

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
 * 仅**无边框模式**使用；JBR 模式的右侧组 = 3 个业务槽 + JBR 原生窗口钮区（运行时取值）。
 */
private val ChromeActionsWidth: Dp = ChromeSlotWidth * 6 + ChromeSeparatorWidth

/**
 * 双击判定窗口（毫秒）。
 *
 * 取系统的「双击间隔」设置（AWT 的 `awt.multiClickInterval`，Windows 上就是
 * `GetDoubleClickTime()`），拿不到再回落到 400ms —— 手感和系统里其它窗口保持一致，
 * 而不是硬编码一个和系统设置打架的值。
 */
private val DoubleClickIntervalMs: Long =
    (java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int)
        ?.toLong()?.coerceIn(200L, 1_000L) ?: 400L

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
 * ## 两种窗口模式（[jbr] 决定）
 *
 * - **无边框模式**（`jbr == null`，运行在普通 JDK / Linux）：本组件画**全部** chrome，
 *   含最小化 / 最大化 / 关闭；拖拽走 `WindowDraggableArea`（AWT MouseListener）；
 *   双击最大化 / 拖动几何由 [WindowMaximizer] 接管。
 * - **JBR 模式**（`jbr != null`，运行在 JBR b1367.22+，见 `JbrWindowChrome`）：窗口在系统
 *   层面**保持有边框**，JBR 把最小化 / 最大化 / 关闭画在客户区右上（`rightInsetPx` 留位，
 *   本组件的三个窗口钮与分隔线**不再渲染**）；拖拽 / 双击最大化 / 右键系统菜单 /
 *   贴边分屏全部由原生 hit-test 承担，本组件只负责按事件回 [JbrWindowChrome.Controller.forceClient]：
 *   可交互控件上回 `true`（客户区），空白处回 `false`（交还原生）。注册表见
 *   [JbrHitTestBridge]。原生窗口钮的明暗随应用主题切换。
 *
 * ⚠️ **本组件是纯展示的**：[title] / [canGoBack] / [onBack] 都由 `Main.kt` 从 Navigator
 * 现算现传（那里同时拿得到 `navigator.size`、`navigator.lastItem` 与 `DesktopShell`）。
 * 它自己不读 `DesktopShell` —— 曾经读的是 `MainScreen` 发布的 `pageCanGoBack`，
 * 而那个值只在「桌面内嵌面板打开」时为真，于是**所有 push 出去的路由页在标题栏上都没有返回键**，
 * 宽窗口下更是一整页没有返回入口（页内顶栏也正好在宽屏分支里被省掉了）。
 *
 * ## 为什么不用系统标题栏
 *
 * 无边框模式下窗口以 `WS_POPUP` 创建（见 `Main.kt` 的 `WindowDecoration.Undecorated`），
 * 系统边框整体消失，标题栏由应用自绘，这样顶栏的配色、圆角、动效才能和正文一致。
 * JBR 模式下系统边框保留但原生标题栏被移除，视觉同样是一整条自绘顶栏。
 * 缩放能力**不归这里管**：无边框模式由 ComposeWindow 内置的 `UndecoratedWindowResizer`
 * 负责，JBR 模式走原生缩放边框。
 *
 * ## ⚠️ 无边框模式的最大化**不用** `WindowState.placement`
 *
 * `placement = Maximized` 会把无边框窗口撑到整个显示器、连任务栏一起盖住；更糟的是 Compose
 * 是用 `setSize()` 与 `setLocation()` **两个独立调用**写回窗口的（javap 核实），于是
 * 「全屏尺寸」会配上「还没更新的旧位置」，产生一个**全屏大小、却飘在半路**的窗口 —— 尺寸恰好
 * 等于输出，Windows 的全屏优化就会把它提升为全屏呈现并切换显示模式（HDR 屏上退出后桌面
 * SDR 内容闪烁）。实测与取舍见 [WindowMaximizer] / `DesktopWindowPlacement` 的说明。
 * 这里改用 [WindowMaximizer]，它用**单次 `setBounds`** 按**工作区**设（尺寸永远小于输出）。
 * JBR 模式没有这个问题：窗口带边框，原生最大化天然尊重任务栏 —— 这正是切到 JBR 的理由之一，
 * 也是「假全屏」的根治。
 *
 * ## 调用位置有硬约束（别挪）
 *
 * 必须由 `App()` 的 `titleBar` 槽位渲染 —— 也就是在 **`CpTheme` 之内、`Navigator` 之上**：
 * - 放在主题外 ⇒ 拿不到 `MaterialTheme.colorScheme`，换主题时这条会变成死色；
 * - 放在 `Navigator` 之下（例如塞进 `MainScreen`）⇒ 一旦 push 到 `AccountScreen` /
 *   `SettingsScreen`，标题栏会被目标页面盖住。窗口已经没有系统边框了，那意味着用户
 *   **既移不动也关不掉窗口**。
 *
 * ## 为什么要显式传 [windowScope]
 *
 * `WindowDraggableArea` 是 `WindowScope` 的扩展，而 `WindowScope` **不是** CompositionLocal
 * —— 树内深层拿不到。Compose 里确实有一个 `androidx.compose.ui.window.LocalWindow`，但它标了
 * `internal`（模块内可见），应用侧用不了。所以只能在 `Main.kt` 的 `Window { }` 里把
 * `FrameWindowScope` 捕获成 [WindowScope] 再沿参数传下来。
 *
 * ## 为什么根节点是 `Box` 而不是 `Surface`，而且必须带 `zIndex`
 *
 * 搜索建议下拉要**溢出到标题栏之外**、盖在正文上。两件事都得满足：
 * 1. 根节点不能裁剪子节点。Compose 的布局默认不裁剪，所以用普通 `Box` + `background`
 *    而不是 Material3 的 `Surface`（后者会按 shape 裁剪内容），下拉才画得出去；
 * 2. 标题栏必须画在正文**之后**。它是 `App()` 里那个 `Column` 的第一个子节点，
 *    不抬 z 就会被后面的正文 `Box` 盖住 —— `zIndex` 只在同级之间比较，这里正好同级。
 *
 * ## 无边框模式：拖拽区必须与可交互控件严格错开
 *
 * [WindowDraggableArea] 走 `StandardMoveHandler`：它给 AWT 窗口挂一个 `MouseListener`，
 * 在 `mouseDragged` 里直接移动窗口。这层在 AWT 上，**Compose 消费不掉**，而且**不认 Compose
 * 的命中测试** —— 只要「按下的那一点」落在拖拽区内，之后整段拖动都会移动窗口。所以：
 * - 返回键、账号、设置、窗口控制必须全部留在拖拽区**外面**；
 * - 标题**可以**放在拖拽区里（纯 `Text` 不吃指针事件）；
 * - 搜索框两侧的空白必须各自是独立拖拽区，与框首尾相接 —— 见 [TitleBarSearch]。
 * （JBR 模式没有这套约束：原生 hit-test 由 [JbrHitTestBridge] 按几何注册表动态回应。）
 */
@Composable
fun DesktopTitleBar(
    windowScope: WindowScope,
    windowState: WindowState,
    jbr: JbrWindowChrome.Controller?,
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = windowScope.window
    val useJbr = jbr != null
    // JBR 模式的命中注册表：可交互控件把自己的窗口内矩形登记进来，桥接层据此决定
    // 每个鼠标事件回「客户区」还是「标题栏」。随 controller 重建（重装 / 换 DPI）。
    val bridge = remember(jbr) { jbr?.let { JbrHitTestBridge(it) } }

    val maximizer = remember(window) { WindowMaximizer(window) }
    // JBR 模式下最大化走原生 extendedState，WindowMaximizer 的「按工作区设 bounds」
    // 那一套**绝不能**插手 —— 会跟系统的最大化状态打架。
    if (!useJbr) {
        DisposableEffect(maximizer) {
            val uninstall = maximizer.install()
            onDispose { uninstall() }
        }
    }
    val maximized = maximizer.isMaximized
    val toggleMaximize: () -> Unit = { maximizer.toggle() }

    // JBR 在客户区右上画原生窗口钮，右侧要给它们留位（px → dp，随 DPI 变化重取）。
    val density = LocalDensity.current
    val nativeControlsWidth = jbr?.let { with(density) { it.rightInsetPx.toDp() } } ?: 0.dp
    // 右侧按钮组总宽：JBR 模式 = 账号 + 消息 + 设置 + 原生窗口钮区；无边框模式 = 全自绘 6 槽。
    val rightGroupWidth = if (useJbr) ChromeSlotWidth * 3 + nativeControlsWidth else ChromeActionsWidth

    // 原生窗口钮的明暗跟随应用主题。判据用 surface 亮度而不是 isSystemInDarkTheme()：
    // 后者只反映系统设置，追不上应用内的主题覆盖（深色模式 / 纯黑等）。
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    LaunchedEffect(jbr, isDarkTheme) {
        jbr?.setControlsDark(isDarkTheme)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .zIndex(1f)
            .jbrHitTest(bridge),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 返回键：**窗口 chrome 是桌面端唯一的返回入口** —— 页面内一律不自绘
                // （见 `AppScaffold` / `CpRouteScaffold`）。是否可返回由调用方（`Main.kt`）
                // 依据 Navigator 栈与内嵌面板状态算好，这里只负责画。
                // 必须在拖拽区**外面**（JBR 模式由注册表豁免，无边框模式见下面的说明）。
                if (canGoBack) {
                    ChromeSlot(onClick = onBack, modifier = Modifier.jbrClientZone(bridge, "back")) {
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
                // JBR 模式：这块空白不需要任何拖拽代码 —— 命中桥接对未注册的区域回
                // `forceClient(false)`，原生侧自动获得拖拽 / 双击最大化 / 右键系统菜单。
                if (useJbr) {
                    Box(
                        Modifier
                            .width(rightGroupWidth - if (canGoBack) ChromeSlotWidth else 0.dp)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = CpSpacing.pageHorizontal),
                        )
                    }
                } else {
                    WindowDragRegion(
                        windowScope = windowScope,
                        enabled = !maximized,
                        modifier = Modifier
                            .width(ChromeActionsWidth - if (canGoBack) ChromeSlotWidth else 0.dp)
                            .fillMaxHeight()
                            .onTitleBarDoubleClick(toggleMaximize),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = CpSpacing.pageHorizontal),
                        )
                    }
                }

                // 搜索框占满剩余宽度，内部再居中一个上限 420dp 的框 ⇒ 落在整条标题栏的正中。
                //
                // ⚠️ 为什么不是「给搜索框一个更宽的槽位、让它自己在里面居中」：槽位比框宽出来的
                // 那部分如果不属于任何拖拽区，就成了**拖不动的死区**（第一版就是这样）。
                // 所以空白必须拆成两块、与框首尾相接、互不重叠 —— 见 [TitleBarSearch]。
                // JBR 模式下 `dragArea` 退化为普通 Box（原生拖拽覆盖），搜索框本体注册成客户区。
                TitleBarSearch(
                    onSearch = onSearch,
                    fieldModifier = Modifier.jbrClientZone(bridge, "search"),
                    dragArea = { searchDragModifier ->
                        if (useJbr) {
                            Box(searchDragModifier)
                        } else {
                            WindowDragRegion(
                                windowScope = windowScope,
                                enabled = !maximized,
                                modifier = searchDragModifier,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )

                // 账号 / 消息 / 设置：从 `MainScreen` 的 AppTopBar 与 DesktopSidebar 迁来。
                //
                // 「消息」是 2026-10-02 从侧栏搬进来的：消息是**全局**入口（不该被 push 出去的
                // 路由页盖住），而侧栏只在 MainScreen 里。与「设置」同一处境。
                //
                // ⚠️ 顺序（2026-10-02 调整）：**消息排在设置左边** —— 越靠右离窗口控制越近，
                // 而「设置」是最常点的一个，紧贴分隔线比被夹在中间更好找。窗口控制组
                // 永远固定在最右，别往它们左边塞业务入口（JBR 模式下窗口控制组是 JBR 画的
                // 原生钮，位于 `nativeControlsWidth` 那格 spacer 的右边）。
                AccountSlot(onClick = onOpenAccount, modifier = Modifier.jbrClientZone(bridge, "account"))
                MessageSlot(onClick = onOpenMessages, modifier = Modifier.jbrClientZone(bridge, "messages"))
                ChromeSlot(
                    onClick = onOpenSettings,
                    modifier = Modifier.jbrClientZone(bridge, "settings"),
                ) {
                    Icon(Icons.Filled.Settings, "设置", modifier = Modifier.size(18.dp))
                }

                if (useJbr) {
                    // 给 JBR 画的「最小化 / 最大化 / 关闭」留位。点击 / 悬停 / Snap Layouts
                    // 浮层全部由 JBR 原生处理，本组件不画、不拦。
                    Spacer(Modifier.width(nativeControlsWidth))
                } else {
                    ChromeSeparator()

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
            }
            // 标题栏与正文在浅色主题下都接近白色，靠 1dp 分隔线划出层次。
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * JBR 模式的 hit-test 注册表：谁可点（客户区）、谁可拖（交还原生）。
 *
 * 可交互控件通过 [Modifier.jbrClientZone] 把自己的矩形登记进 [clientRects]（键隔离，
 * 控件离开组合时注销）；桥接层在每个鼠标事件上把「光标点是否落在任一已登记矩形内」
 * 回给 [JbrWindowChrome.Controller.forceClient]。全部坐标都在**组合根**坐标系里比较，
 * 换算关系：`根坐标 = 根节点 positionInRoot + 事件局部坐标`。
 *
 * 纯 HashMap + 普通读写即可：登记（布局回调）与查询（指针回调）都发生在 EDT。
 */
private class JbrHitTestBridge(val controller: JbrWindowChrome.Controller) {
    var rootOrigin: Offset = Offset.Zero
    val clientRects = HashMap<String, Rect>()

    fun isClient(posInRoot: Offset): Boolean =
        clientRects.values.any { it.contains(posInRoot) }
}

/**
 * 把调用方标记成「客户区」：光标落在这个矩形里时，原生侧不抢事件（按钮可点、
 * 文本可选中）。JBR 模式专用；`bridge == null`（无边框模式）时原样返回。
 *
 * ⚠️ 必须挂在**可交互控件本身**的 modifier 链上，宽高即点击边界 —— 不要挂在整条
 * 标题栏上，否则空白区也会被判成客户区、原生拖拽就没了。
 */
@Composable
private fun Modifier.jbrClientZone(bridge: JbrHitTestBridge?, key: String): Modifier {
    if (bridge == null) return this
    // 控件离开组合（例如返回键消失）时注销，否则那块区域永远点不动窗口。
    DisposableEffect(key) {
        onDispose { bridge.clientRects.remove(key) }
    }
    return onGloballyPositioned { coords ->
        bridge.clientRects[key] = coords.boundsInRoot()
    }
}

/**
 * JBR 模式的 hit-test 桥接：按官方约定，除 Exit 与 Scroll 外**每个**鼠标事件都要回一次
 * hit-test（原生侧用的是上一次的值）。挂在整个标题栏根节点上；具体回 `true` 还是 `false`
 * 由光标点是否落在 [JbrHitTestBridge.clientRects] 内决定。
 *
 * `bridge == null`（无边框模式）时是空操作。
 */
private fun Modifier.jbrHitTest(bridge: JbrHitTestBridge?): Modifier {
    if (bridge == null) return this
    return onGloballyPositioned { coords ->
        bridge.rootOrigin = coords.positionInRoot()
    }.pointerInput(bridge) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.type == PointerEventType.Exit || event.type == PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val posInRoot = bridge.rootOrigin + change.position
                bridge.controller.forceClient(bridge.isClient(posInRoot))
            }
        }
    }
}

/**
 * 双击标题栏空白区切换最大化 / 还原（仅无边框模式；JBR 模式由原生标题栏行为承担）。
 *
 * 为什么必须自己做：[WindowDraggableArea] **不提供**这个行为（两种 handler 都不提供）——
 * 双击标题栏最大化是 Windows 给**原生标题栏**的行为，而无边框模式没有原生标题栏。
 *
 * 为什么用 `PointerEventPass.Final`：同一块区域上并存着两套事件机制 —— AWT 层的
 * `MouseListener`（拖动）和 Compose 的 `awaitFirstDown`（给拖动上膛）。这里必须
 * **只观察、不消费**，否则会和拖拽抢事件。Final 是最后一个 pass，事件已经派发完毕，
 * 读它不会改变任何人的消费结果。
 */
@Composable
private fun Modifier.onTitleBarDoubleClick(onDoubleClick: () -> Unit): Modifier {
    val current by rememberUpdatedState(onDoubleClick)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            var lastDownAt = 0L
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                val now = down.uptimeMillis
                if (lastDownAt != 0L && now - lastDownAt <= DoubleClickIntervalMs) {
                    lastDownAt = 0L
                    current()
                } else {
                    lastDownAt = now
                }
            }
        }
    }
}

/**
 * 一块可拖拽区域（内部内容左对齐垂直居中）。**无边框模式专用** —— JBR 模式的拖拽
 * 由原生 hit-test 承担，不走这里。
 *
 * [enabled] 为 false 时退化成普通 `Box` —— 最大化状态下必须禁用拖拽：
 * `WindowDraggableArea` 没有「拖出即还原」的语义，放任拖动只会让最大化窗口被平移。
 * JBR 模式没有这个限制：拖最大化窗口是 Windows 原生行为（拖出即还原并跟随光标），
 * 当年那条「不建议手写」的 TODO 由切换 JBR 路径一并解决。
 *
 * 之所以抽成一个函数而不是各处内联：拖拽区与可交互控件必须**严格错开**（见 [DesktopTitleBar]
 * 的说明），把「怎么拖」收敛到一处，改的时候才不会漏掉某一个调用点。
 */
@Composable
private fun WindowDragRegion(
    windowScope: WindowScope,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    if (enabled) {
        with(windowScope) {
            WindowDraggableArea(modifier = modifier) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { content() }
            }
        }
    } else {
        Box(modifier, contentAlignment = Alignment.CenterStart) { content() }
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
                modifier = Modifier.size(22.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Filled.Person, "账号", modifier = Modifier.size(18.dp))
        }
    }
}

/** 账号 / 设置与窗口控制之间的竖向分隔线，避免两组按钮在视觉上连成一排。 */
@Composable
private fun ChromeSeparator() {
    Box(
        Modifier.fillMaxHeight().width(ChromeSeparatorWidth),
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

/** 窗口控制按钮（最小化 / 最大化 / 关闭）。仅无边框模式渲染；JBR 模式由原生窗口钮接管。 */
@Composable
private fun WindowControlButton(
    icon: ImageVector,
    contentDescription: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) = ChromeSlot(danger = danger, onClick = onClick) {
    Icon(icon, contentDescription, modifier = Modifier.size(15.dp))
}
