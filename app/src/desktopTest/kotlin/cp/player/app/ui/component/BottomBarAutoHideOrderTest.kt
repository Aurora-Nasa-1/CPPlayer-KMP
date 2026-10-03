package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 回归：MainScreen 窄屏 Scaffold 上那两个 `Modifier.nestedScroll` 的**顺序**。
 *
 * pre-scroll 是**从外到内**派发的（`NestedScrollNode.onPreScroll` 先调 parent、再调
 * 自己），而顶栏那条约 `exitUntilCollapsedScrollBehavior` 会把「大标题收成 64dp」
 * 的一段增量吃掉（这里用 [AppBarLikeProbe] 复刻：实测吃掉约 88px）。于是：
 *
 * - **顶栏连接在外、底栏在内**（改造前的写法）⇒ 底栏只拿到**剩余**增量，短促上滑
 *   时不过半 ⇒ 吸附时弹回 ⇒ 端上表现就是「怎么滑底栏都不收」；
 * - **底栏连接在外**（现在的写法）⇒ 先拿原始手势，正常收起。它只观察不消费
 *   （返回 `Zero`），顶栏照旧拿得到完整增量。
 *
 * 谁「顺手整理」把顺序调回去，这两条会一起红。驱动用真实指针拖拽 ——
 * `dispatchRawDelta` 不派发嵌套滚动，量不出任何东西。
 */
private class AppBarLikeProbe(private val consumeUpToPx: Float) : NestedScrollConnection {
    var preNotches = 0
    private var consumed = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        preNotches++
        if (available.y >= 0f) return Offset.Zero
        val room = (consumeUpToPx - consumed).coerceAtLeast(0f)
        val take = (-available.y).coerceAtMost(room)
        consumed += take
        return Offset(0f, -take)
    }
}

/** 只观察、不消费（等价于只记一笔）。 */
private class ObserverProbe : NestedScrollConnection {
    var preNotches = 0

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        preNotches++
        return Offset.Zero
    }
}

class BottomBarAutoHideOrderTest {

    /** 对照组：单个 nestedScroll（官方写法）必须收到嵌套滚动事件。 */
    @Test
    fun `对照组 单个 nestedScroll 收得到事件`() {
        val probe = ObserverProbe()
        val state = LazyListState()
        val scene = ImageComposeScene(width = 600, height = 800, density = Density(1f)) {
            Box(Modifier.fillMaxSize().nestedScroll(probe)) { ListBody(state) }
        }
        try {
            dragUp(scene, 300f)
        } finally {
            scene.close()
        }
        println("[order][对照] notches=${probe.preNotches} idx=${state.firstVisibleItemIndex}")
        assertTrue(state.firstVisibleItemIndex > 0, "列表没滚动，驱动无效")
        assertTrue(probe.preNotches > 0, "对照组没收到嵌套滚动")
    }

    /** 现在的写法：底栏在外侧、顶栏在内侧 —— 短促上滑也能收起。 */
    @Test
    fun `底栏连接在外侧时 短促上滑能自动收起`() {
        val fraction = shortDragFraction(bottomBarOutside = true)
        assertTrue(fraction > 0.5f, "底栏没收起（fraction=$fraction）")
    }

    /** 顺序反了的代价：底栏在内侧、顶栏先吃掉前 88px —— 短促上滑纹丝不动。 */
    @Test
    fun `底栏连接在内侧时 短促上滑收不起来`() {
        val fraction = shortDragFraction(bottomBarOutside = false)
        assertTrue(fraction < 0.5f, "顺序反了却仍然收起（fraction=$fraction），用例不再有判别力")
    }

    /**
     * 跑一次「顶栏先吃掉 88px + 用户只上滑 100px」的短促手势，返回底栏的隐藏比例。
     *
     * @param bottomBarOutside true = 底栏连接在外侧（现写法）
     */
    private fun shortDragFraction(bottomBarOutside: Boolean): Float {
        val appBar = AppBarLikeProbe(consumeUpToPx = 88f)
        val state = LazyListState()
        var hide: BottomBarHideState? = null
        val scene = ImageComposeScene(width = 600, height = 800, density = Density(1f)) {
            val scope = rememberCoroutineScope()
            val h = remember { BottomBarHideState(scope) }
            // 无头场景里没有 NavigationBar 回传高度，直接给一个等效值（80px 宽高比 1:1）。
            SideEffect { h.barHeightPx = 80f }
            SideEffect { hide = h }
            val base = Modifier.fillMaxSize()
            Scaffold(
                modifier = if (bottomBarOutside) base.nestedScroll(h).nestedScroll(appBar)
                else base.nestedScroll(appBar).nestedScroll(h),
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { ListBody(state) }
            }
        }
        try {
            dragUp(scene, 100f)
            val h = requireNotNull(hide)
            println(
                "[order] bottomBarOutside=$bottomBarOutside appBar=$appBar " +
                    "idx=${state.firstVisibleItemIndex} fraction=${h.fraction} barHeightPx=${h.barHeightPx}"
            )
            return h.fraction
        } finally {
            scene.close()
        }
    }
}

@androidx.compose.runtime.Composable
private fun ListBody(state: LazyListState) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(300) { i -> Text("row $i") }
    }
}

/** 触摸拖拽 [distancePx]（真实指针事件 ⇒ 会派发嵌套滚动）。 */
private fun dragUp(scene: ImageComposeScene, distancePx: Float) {
    var t = 0L
    repeat(5) {
        scene.render(t)
        t += 16_000_000L
    }
    val x = 300f
    val fromY = 700f
    scene.sendPointerEvent(PointerEventType.Press, Offset(x, fromY), Offset.Zero, 0L, PointerType.Touch)
    val steps = 20
    repeat(steps) { i ->
        scene.sendPointerEvent(
            PointerEventType.Move,
            Offset(x, fromY - distancePx * (i + 1) / steps),
            Offset.Zero,
            16L * (i + 1),
            PointerType.Touch,
        )
        scene.render(t)
        t += 16_000_000L
    }
    scene.sendPointerEvent(PointerEventType.Release, Offset(x, fromY - distancePx), Offset.Zero, 400L, PointerType.Touch)
    repeat(5) {
        scene.render(t)
        t += 16_000_000L
    }
}
