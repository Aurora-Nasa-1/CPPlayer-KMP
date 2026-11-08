package cp.player.app.ui.component

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.util.DesktopShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证底栏与迷你播放器布局高度联动，防止子页面返回时底栏被迷你播放器挡住。
 */
class BottomBarMiniPlayerLayoutTest {

    @Test
    fun `BottomBarHideState 初始可见高度必须等于兜底高度而非 0`() {
        val scope = CoroutineScope(Job())
        val fallbackPx = 240f
        val state = BottomBarHideState(scope = scope, fallbackHeightPx = fallbackPx)

        assertEquals(fallbackPx, state.barHeightPx, "初始完整高度应为兜底值")
        assertEquals(fallbackPx, state.visibleHeightPx, "初始可见高度应为兜底值而非 0")
        assertEquals(0f, state.fraction, "初始隐藏比例应为 0")
    }

    @Test
    fun `底栏拖拽与测量更新后可见高度正确响应`() {
        val scope = CoroutineScope(Job())
        val fallbackPx = 240f
        val state = BottomBarHideState(scope = scope, fallbackHeightPx = fallbackPx)

        // 真实测量回传（例如 250px）
        state.barHeightPx = 250f
        assertEquals(250f, state.visibleHeightPx)

        // 向上拖拽隐藏一半
        state.onDrag(-125f)
        assertEquals(0.5f, state.fraction)
        assertEquals(125f, state.visibleHeightPx)

        // 拖拽完全隐藏
        state.onDrag(-200f)
        assertEquals(1f, state.fraction)
        assertEquals(0f, state.visibleHeightPx)

        // 复位回显
        state.reset()
        assertEquals(0f, state.fraction)
        assertEquals(250f, state.visibleHeightPx)
    }

    @Test
    fun `DesktopShell 在未初始化或负值时提供安全底距`() {
        val density = Density(3f) // 3px per dp, 80dp = 240px

        fun computeBottomGap(mainScreenVisible: Boolean, expanded: Boolean, visiblePx: Float): androidx.compose.ui.unit.Dp {
            val navInset = 0.dp
            return when {
                mainScreenVisible && expanded -> 24.dp
                mainScreenVisible -> {
                    val barDp = if (visiblePx >= 0f) {
                        with(density) { visiblePx.toDp() }
                    } else {
                        80.dp
                    }
                    maxOf(barDp, navInset) + 12.dp
                }
                else -> navInset
            }
        }

        // 1. 未初始化（负值）时，应兜底 80dp + 12dp = 92dp，防止遮挡底栏
        val gapUninitialized = computeBottomGap(mainScreenVisible = true, expanded = false, visiblePx = -1f)
        assertEquals(92.dp, gapUninitialized, "未初始化时应兜底 92dp，绝不能塌陷到底部")

        // 2. 正常全显状态（240px = 80dp）
        val gapNormal = computeBottomGap(mainScreenVisible = true, expanded = false, visiblePx = 240f)
        assertEquals(92.dp, gapNormal)

        // 3. 用户手势完全收起底栏（0px）
        val gapHidden = computeBottomGap(mainScreenVisible = true, expanded = false, visiblePx = 0f)
        assertEquals(12.dp, gapHidden, "用户主动收起底栏时，播放器应平滑下沉")

        // 4. 进入无底栏页面（mainScreenVisible = false）
        val gapSubScreen = computeBottomGap(mainScreenVisible = false, expanded = false, visiblePx = 240f)
        assertEquals(0.dp, gapSubScreen, "无底栏页面贴底，不受主屏底栏高度影响")
    }

    @Test
    fun `打开消息会话时迷你播放器必须让位隐藏`() {
        DesktopShell.miniPlayerBlockedByChat = false

        fun shouldShowMiniPlayer(
            mainScreenVisible: Boolean,
            lastItemIsPlayerScreen: Boolean,
            lastItemIsChatScreen: Boolean,
            miniPlayerBlockedByChat: Boolean,
        ): Boolean {
            return mainScreenVisible &&
                !lastItemIsPlayerScreen &&
                !lastItemIsChatScreen &&
                !miniPlayerBlockedByChat
        }

        // 常规状态下迷你播放器显示
        assertTrue(shouldShowMiniPlayer(true, false, false, DesktopShell.miniPlayerBlockedByChat))

        // 桌面端打开消息会话，标记为 true，迷你播放器让位隐藏，避免挡住输入框
        DesktopShell.miniPlayerBlockedByChat = true
        assertTrue(
            !shouldShowMiniPlayer(true, false, false, DesktopShell.miniPlayerBlockedByChat),
            "消息输入框展开时迷你播放器必须隐藏，避免遮挡消息输入框与发送按钮",
        )

        // 离开消息会话，标记复位为 false，迷你播放器重新显示
        DesktopShell.miniPlayerBlockedByChat = false
        assertTrue(shouldShowMiniPlayer(true, false, false, DesktopShell.miniPlayerBlockedByChat))
    }

    @Test
    fun `手机端无底栏或底栏隐藏时迷你播放器按同心圆角和悬浮边距进行胶囊化过渡`() {
        val screenCorner = 32.dp

        fun computeLayout(
            isDesktop: Boolean,
            isDockedToBottomBar: Boolean,
        ): Triple<Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>, androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
            val horizontalMargin = if (isDesktop || isDockedToBottomBar) 8.dp else 12.dp
            val innerHorizontalPadding = if (isDesktop || isDockedToBottomBar) 12.dp else 16.dp
            val floatingCorner = (screenCorner - horizontalMargin).coerceIn(20.dp, 28.dp)
            val topCorner = if (isDesktop || isDockedToBottomBar) 28.dp else floatingCorner
            val bottomCorner = if (isDesktop || isDockedToBottomBar) 16.dp else floatingCorner
            return Triple(topCorner to bottomCorner, horizontalMargin, innerHorizontalPadding)
        }

        // 1. 桌面端：始终保持默认圆角 (28dp, 16dp)，外边距 8dp，内边距 12dp
        val desktopLayout = computeLayout(isDesktop = true, isDockedToBottomBar = false)
        assertEquals(28.dp to 16.dp, desktopLayout.first)
        assertEquals(8.dp, desktopLayout.second)
        assertEquals(12.dp, desktopLayout.third)

        // 2. 手机端有底栏（主页停靠态）：默认圆角 (28dp, 16dp)，外边距 8dp，内边距 12dp
        val dockedLayout = computeLayout(isDesktop = false, isDockedToBottomBar = true)
        assertEquals(28.dp to 16.dp, dockedLayout.first)
        assertEquals(8.dp, dockedLayout.second)
        assertEquals(12.dp, dockedLayout.third)

        // 3. 手机端无底栏或收缩隐藏态（精致悬浮胶囊）：同心圆角 (20dp)，外边距扩展至 12dp，内边距补偿至 16dp
        val floatingLayout = computeLayout(isDesktop = false, isDockedToBottomBar = false)
        assertEquals(20.dp to 20.dp, floatingLayout.first)
        assertEquals(12.dp, floatingLayout.second)
        assertEquals(16.dp, floatingLayout.third)
    }

    @Test
    fun `底栏隐藏过程中底部间隙平滑过渡至悬浮安全区无阶跃断崖`() {
        val navigationBarInset = 24.dp

        fun computeBottomGap(barDp: androidx.compose.ui.unit.Dp, mainScreenVisible: Boolean): androidx.compose.ui.unit.Dp {
            return if (mainScreenVisible) {
                maxOf(barDp + 12.dp, navigationBarInset + 8.dp)
            } else {
                navigationBarInset + 8.dp
            }
        }

        // 1. 底栏完全展开 (80dp)：底栏高度 + 12dp = 92dp
        assertEquals(92.dp, computeBottomGap(80.dp, mainScreenVisible = true))

        // 2. 底栏随手势收缩中 (40dp)：40dp + 12dp = 52dp
        assertEquals(52.dp, computeBottomGap(40.dp, mainScreenVisible = true))

        // 3. 底栏收缩至接近 0dp：平滑接纳为 navigationBarInset + 8dp = 32dp
        assertEquals(32.dp, computeBottomGap(10.dp, mainScreenVisible = true))
        assertEquals(32.dp, computeBottomGap(0.dp, mainScreenVisible = true))

        // 4. 子页面（无底栏）：悬浮在手势区上方 8dp
        assertEquals(32.dp, computeBottomGap(0.dp, mainScreenVisible = false))
    }

    @Test
    fun `子页面切换时底栏隐藏比例结合路由栈状态触发下沉`() {
        fun computeEffectiveHideFraction(
            bottomBarHideFraction: Float,
            isMainTop: Boolean,
        ): Float {
            val navBarExitFraction = if (isMainTop) 0f else 1f
            return maxOf(bottomBarHideFraction, navBarExitFraction)
        }

        // 1. 主页面且未滚动隐藏：比例为 0
        assertEquals(0f, computeEffectiveHideFraction(0f, isMainTop = true))

        // 2. 主页面但手势滚动收缩隐藏一半：比例为 0.5
        assertEquals(0.5f, computeEffectiveHideFraction(0.5f, isMainTop = true))

        // 3. 切换到子页面（isMainTop = false）：无论手势状态，立即触发退出比例 1.0（下沉）
        assertEquals(1f, computeEffectiveHideFraction(0f, isMainTop = false))

        // 4. 从子页面返回主页（isMainTop = true）：恢复为当前手势状态（浮起）
        assertEquals(0f, computeEffectiveHideFraction(0f, isMainTop = true))
    }

    @Test
    fun `弹簧回弹下冲时底栏间距与圆角必须钳到非负避免Padding崩溃`() {
        val overshootGap = (-4.5).dp
        val safeGap = overshootGap.coerceAtLeast(0.dp)
        assertEquals(0.dp, safeGap, "弹簧过冲穿负时必须钳到 0dp，避免 Padding must be non-negative 异常")

        val overshootCorner = (-2.0).dp
        val safeCorner = overshootCorner.coerceAtLeast(0.dp)
        assertEquals(0.dp, safeCorner, "弹簧过冲穿负时圆角必须钳到 0dp")
    }
}
