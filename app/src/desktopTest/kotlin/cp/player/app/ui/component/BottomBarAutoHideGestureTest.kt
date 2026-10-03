package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归：窄屏底栏的「上滑自动隐藏」由**内容区上的指针观察**驱动
 * （`Modifier.observeBottomBarDrag`），**不走嵌套滚动**。
 *
 * 之所以换成指针观察：嵌套滚动要穿过「页面滚动容器 → 下拉刷新 → 内容区 → Scaffold」
 * 整条链，安卓端实测收不到增量（`PullToRefreshBox` / 顶栏 scrollBehavior 都会截断），
 * 底栏于是「怎么滑都不收」。所以下面的用例全部用真实指针事件驱动 ——
 * ⚠️ `LazyListState.dispatchRawDelta` 不派发指针事件，用它驱动什么都量不出。
 */
class BottomBarAutoHideGestureTest {

    /** 上滑 ⇒ 收起；且内容照常滚动（观察器不消费事件）。 */
    @Test
    fun `内容区上滑拖拽 底栏收起且内容仍能滚动`() {
        val r = runCase(dx = 0f, dy = -300f, postFrames = 20)
        println("[gesture] 上滑 fraction=${r.fraction} 渲染高度=${r.rendered} 原高=${r.fullHeight} idx=${r.listIndex}")
        assertTrue(r.fraction > 0.5f, "底栏没收起（fraction=${r.fraction}）")
        assertTrue(r.rendered < r.fullHeight, "比例动了但底栏渲染高度没收缩（${r.rendered} vs ${r.fullHeight}）")
        assertTrue(r.listIndex > 0, "内容没滚动 —— 观察器把事件吃掉了")
    }

    /** 横向为主的拖拽整段忽略（翻页 / 侧滑不该把底栏收走）。 */
    @Test
    fun `横向拖拽不参与底栏隐藏`() {
        val r = runCase(dx = -300f, dy = 0f, postFrames = 20)
        println("[gesture] 横滑 fraction=${r.fraction} idx=${r.listIndex}")
        assertEquals(0f, r.fraction, "横向拖拽不该触发底栏隐藏")
    }

    /** 短促上滑不足一半 ⇒ 松手后吸附回原位（不该「划一下就没了」）。 */
    @Test
    fun `短促上滑不过半 松手后回弹`() {
        val r = runCase(dx = 0f, dy = -20f, postFrames = 60)
        println("[gesture] 短促上滑 fraction=${r.fraction}")
        assertEquals(0f, r.fraction, "短促上滑不该把底栏留在收起态")
    }
}

private class CaseResult(val fraction: Float, val rendered: Int, val fullHeight: Int, val listIndex: Int)

/** 跑一次纵向/横向拖拽，返回底栏比例、渲染高度、内容滚动位置。 */
private fun runCase(dx: Float, dy: Float, postFrames: Int): CaseResult {
    val listState = LazyListState()
    var hide: BottomBarHideState? = null
    val rendered = intArrayOf(-1)
    val scene = ImageComposeScene(width = 600, height = 800, density = Density(1f)) {
        val scope = rememberCoroutineScope()
        val state = remember { BottomBarHideState(scope, fallbackHeightPx = 80f) }
        SideEffect { hide = state }
        Scaffold(
            bottomBar = {
                // 外层只负责量「收缩后」的高度：`onSizeChanged` 报的是它**内侧**的尺寸，
                // 想量 `Modifier.layout{}` 收缩后的结果必须包一层。
                Box(Modifier.onSizeChanged { rendered[0] = it.height }) {
                    TestBottomBar(
                        hideFraction = state.fraction,
                        onFullHeight = { state.barHeightPx = it.toFloat() },
                    )
                }
            },
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding)
                    .observeBottomBarDrag(state),
            ) { ListBody(listState) }
        }
    }
    try {
        var t = 0L
        repeat(5) {
            scene.render(t)
            t += 16_000_000L
        }
        val start = Offset(300f, 600f)
        val steps = 20
        scene.sendPointerEvent(PointerEventType.Press, start, Offset.Zero, 0L, PointerType.Touch)
        repeat(steps) { i ->
            scene.sendPointerEvent(
                PointerEventType.Move,
                Offset(start.x + dx * (i + 1) / steps, start.y + dy * (i + 1) / steps),
                Offset.Zero,
                16L * (i + 1),
                PointerType.Touch,
            )
            scene.render(t)
            t += 16_000_000L
        }
        scene.sendPointerEvent(
            PointerEventType.Release,
            Offset(start.x + dx, start.y + dy),
            Offset.Zero,
            400L,
            PointerType.Touch,
        )
        repeat(postFrames) {
            scene.render(t)
            t += 16_000_000L
        }
        val s = requireNotNull(hide)
        return CaseResult(s.fraction, rendered[0], s.barHeightPx.roundToInt(), listState.firstVisibleItemIndex)
    } finally {
        scene.close()
    }
}

/** `MainScreen.AppNavigationBar` 的收缩逻辑副本（原函数 file-private，测试拿不到）。 */
@Composable
private fun TestBottomBar(hideFraction: Float, onFullHeight: (Int) -> Unit) {
    Box(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val visible = (placeable.height * (1f - hideFraction.coerceIn(0f, 1f)))
                    .roundToInt()
                    .coerceAtLeast(0)
                layout(placeable.width, visible) {
                    placeable.placeRelative(0, visible - placeable.height)
                }
            }
            .clipToBounds(),
    ) {
        Box(
            Modifier.fillMaxWidth().height(80.dp)
                .onSizeChanged { onFullHeight(it.height) },
        )
    }
}

@Composable
private fun ListBody(state: LazyListState) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(300) { i -> Text("row $i") }
    }
}
