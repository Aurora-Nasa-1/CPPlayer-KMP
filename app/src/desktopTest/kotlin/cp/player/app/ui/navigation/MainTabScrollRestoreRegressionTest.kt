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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
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
 * 回归测试：MainScreen 结构（会话级稳定的 tab 实例 + saveable visitedTabs）下，
 * 「滚 A → 滚 B → push/pop 两轮 → 切回 A」后各 tab 滚动位置必须全部保留。
 *
 * 修复前（visitedTabs 用非 saveable 的 remember + tab 实例随组合重建）：
 * 第一轮 push/pop 后 visitedTabs 重置为只剩当前 tab，另一个 tab 的滚动快照
 * 悬挂在 SaveableStateProvider 的子注册表里；第二轮 push 时子树不含它，
 * provider 退出重存会把这份未消费的快照**冲掉** —— 切回去就是顶部。
 * 这正是「各种界面来回切换后不保留滑动位置」的复现序列。
 */

private var navR: Navigator? = null
private val capturedR = mutableMapOf<String, LazyListState>()
private val scrollR = mutableStateMapOf<String, Int>()
private var selectHookR: ((Int) -> Unit)? = null

/** 模拟按 Screen 实例记住的 ScreenModel（实例稳定 ⇒ 只加载一次，数据常驻）。 */
private class FakeModelR {
    var loaded by mutableStateOf(false)
        private set

    fun load() {
        loaded = true
    }
}

private class StableScreenR(private val tag: String) : Screen {
    @Composable
    override fun Content() {
        val model = remember { FakeModelR() }
        LaunchedEffect(Unit) { model.load() }
        if (!model.loaded) {
            Text("$tag loading…")
        } else {
            val st = rememberLazyListState()
            SideEffect { capturedR[tag] = st }
            val target = scrollR[tag] ?: -1
            LaunchedEffect(target) {
                if (target >= 0) st.scrollToItem(target)
            }
            LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
                items(300) { i -> Text("$tag $i", modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** 与 MainScreen 的 MAIN_TABS 同款：会话级稳定，只建一次。 */
private val STABLE_TABS_R = listOf(StableScreenR("A"), StableScreenR("B"))

private class BlankScreenR : Screen {
    @Composable
    override fun Content() {
        Text("blank")
    }
}

/** 修复后的 tab 宿主：visitedTabs saveable + 稳定实例。 */
private class FixedTabHostR : Screen {
    @Composable
    override fun Content() {
        val selectedIndexState = rememberSaveable { mutableIntStateOf(0) }
        val selectedIndex by selectedIndexState
        val visitedTabs = rememberSaveable(
            saver = listSaver(
                save = { it.toList() },
                restore = { mutableStateListOf<Int>().apply { addAll(it) } },
            ),
        ) { mutableStateListOf(selectedIndex) }
        selectHookR = { index ->
            if (index !in visitedTabs) visitedTabs.add(index)
            selectedIndexState.intValue = index
        }
        val retained = visitedTabs.sorted()
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                retained.forEach { index ->
                    Box(Modifier.fillMaxSize()) { STABLE_TABS_R[index].Content() }
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

class MainTabScrollRestoreRegressionTest {

    @Test
    fun `两轮 push pop 后切回各 tab，滚动位置全部保留`() {
        capturedR.clear(); scrollR.clear(); capturedR.clear()
        navR = null; selectHookR = null
        var frameTimeNanos = 0L

        val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            Navigator(FixedTabHostR()) { nav ->
                navR = nav
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
            // 1. 滚 A
            scrollR["A"] = 80
            pump(1000)
            val aBefore = assertNotNull(capturedR["A"], "A 未组合").firstVisibleItemIndex
            check(aBefore > 0) { "前置失败：A 没滚下去" }

            // 2. 切到 B，滚 B
            selectHookR!!(1)
            pump(1000)
            scrollR["B"] = 120
            pump(1000)
            val bBefore = assertNotNull(capturedR["B"], "B 未组合").firstVisibleItemIndex
            check(bBefore > 0) { "前置失败：B 没滚下去" }

            // 3. 两轮 push → pop（模拟「在各种界面来回切换」）
            repeat(2) {
                navR!!.push(BlankScreenR())
                pump(2000)
                navR!!.pop()
                pump(2000)
            }

            // 4. 切回 A：滚动必须还在（修复前这里会回顶）
            selectHookR!!(0)
            pump(1000)
            assertEquals(aBefore, assertNotNull(capturedR["A"], "切回后 A 未组合").firstVisibleItemIndex, "两轮 push/pop 后 A 的滚动位置丢失")

            // 5. 再切回 B：同样必须保留
            selectHookR!!(1)
            pump(1000)
            assertEquals(bBefore, assertNotNull(capturedR["B"], "切回后 B 未组合").firstVisibleItemIndex, "两轮 push/pop 后 B 的滚动位置丢失")
        } finally {
            scene.close()
        }
    }
}
