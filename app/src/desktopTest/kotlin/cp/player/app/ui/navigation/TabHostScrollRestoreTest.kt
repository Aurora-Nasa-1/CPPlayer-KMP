package cp.player.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 诊断用：复刻 `MainScreen` 的 tab 宿主结构（saveable selectedIndex + **非 saveable**
 * visitedTabs + 只摆放当前 tab 的自定义 Layout），验证「push 详情页 → pop 返回 →
 * 切回另一个 tab」后，那个 tab 的滚动位置是否还能恢复。
 *
 * 结论落地后可删（若成为回归测试则保留）。
 */

// 测试状态挂钩：文件级，测试开始时重置。
private var stateA: LazyListState? = null
private var stateB: LazyListState? = null
private var capturedNav2: Navigator? = null
private val scrollTargetA = mutableIntStateOf(-1)
private val scrollTargetB = mutableIntStateOf(-1)

private class ListScreenA : Screen {
    @Composable
    override fun Content() {
        val st = rememberLazyListState()
        SideEffect { stateA = st }
        LaunchedEffect(scrollTargetA.intValue) {
            if (scrollTargetA.intValue >= 0) st.scrollToItem(scrollTargetA.intValue)
        }
        LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
            items(300) { i -> Text("A $i", modifier = Modifier.fillMaxWidth()) }
        }
    }
}

private class ListScreenB : Screen {
    @Composable
    override fun Content() {
        val st = rememberLazyListState()
        SideEffect { stateB = st }
        LaunchedEffect(scrollTargetB.intValue) {
            if (scrollTargetB.intValue >= 0) st.scrollToItem(scrollTargetB.intValue)
        }
        LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
            items(300) { i -> Text("B $i", modifier = Modifier.fillMaxWidth()) }
        }
    }
}

private class BlankScreen2 : Screen {
    @Composable
    override fun Content() {
        Text("blank")
    }
}

/** 复刻 MainScreen + TabContent 的 tab 宿主。 */
private class TabHostScreen(
    private val tabs: List<Screen>,
    private val onSelectorReady: ((Int) -> Unit) -> Unit,
) : Screen {
    @Composable
    override fun Content() {
        val selectedIndexState = rememberSaveable { mutableIntStateOf(0) }
        val selectedIndex by selectedIndexState
        // ⚠️ 与 MainScreen 一致：非 saveable 的 remember。
        val visitedTabs = remember { mutableStateListOf(selectedIndex) }
        onSelectorReady { index ->
            if (index !in visitedTabs) visitedTabs.add(index)
            selectedIndexState.intValue = index
        }
        val retained = visitedTabs.sorted()
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                retained.forEach { index ->
                    Box(Modifier.fillMaxSize()) { tabs[index].Content() }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.getOrNull(retained.indexOf(selectedIndex))?.placeRelative(0, 0)
            }
        }
    }
}

class TabHostScrollRestoreTest {

    @Test
    fun `push 返回后切回另一个 tab，其滚动位置应保留`() {
        stateA = null; stateB = null; capturedNav2 = null
        scrollTargetA.intValue = -1; scrollTargetB.intValue = -1
        var frameTimeNanos = 0L
        var selectTab: (Int) -> Unit = {}

        val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            Navigator(TabHostScreen(listOf(ListScreenA(), ListScreenB())) { selectTab = it }) { nav ->
                capturedNav2 = nav
                ScreenTransition(
                    navigator = nav,
                    transition = { fadeIn(tween(300)) togetherWith fadeOut(tween(220)) },
                )
            }
        }
        try {
            fun pump(ms: Long) {
                var rendered = 0L
                while (rendered < ms) {
                    frameTimeNanos += 16_000_000
                    scene.render(frameTimeNanos)
                    rendered += 16
                }
            }

            pump(2000)
            val stA = assertNotNull(stateA, "tab A 未组合")
            // 1. 滚 A
            scrollTargetA.intValue = 80
            pump(1000)
            val aBefore = stA.firstVisibleItemIndex
            check(aBefore > 0) { "前置失败：A 没滚下去" }

            // 2. 切到 B，滚 B（此时 A、B 都在组合里）
            selectTab(1)
            pump(500)
            val stB = assertNotNull(stateB, "tab B 未组合")
            scrollTargetB.intValue = 120
            pump(1000)
            val bBefore = stB.firstVisibleItemIndex
            check(bBefore > 0) { "前置失败：B 没滚下去" }

            // 3. push 覆盖整个 tab 宿主 → pop 返回
            capturedNav2!!.push(BlankScreen2())
            pump(2000)
            capturedNav2!!.pop()
            pump(2000)

            // 4. 返回后选中的是 B（saveable 恢复）：B 的滚动应保留
            assertEquals(bBefore, stB.firstVisibleItemIndex, "pop 返回后 B 的滚动位置丢失")

            // 5. 切回 A（visitedTabs 已重置，A 此刻才重新组合）：A 的滚动应保留
            selectTab(0)
            pump(500)
            val aAfter = assertNotNull(stateA, "切回后 A 未组合")
            assertEquals(aBefore, aAfter.firstVisibleItemIndex, "切回 A 后滚动位置丢失（visitedTabs 重置导致 A 重新组合，快照未懒恢复）")
        } finally {
            scene.close()
        }
    }
}
