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
import androidx.compose.runtime.mutableStateOf
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
 * 诊断用：复刻「MainScreen 离开组合后 tab Screen 实例重建 + 每实例模型重载（结构翻转）」
 * 场景，验证 push/pop 后滚动位置是否还能恢复。
 *
 * 真实 app 中：`MainScreen.tabs = remember { listOf(HomeScreen(), …) }`，MainScreen 被
 * push 覆盖再返回时 remember 槽位全部丢失 → 新的 Screen 实例 → `rememberScreenModel`
 * 按**新实例**建新模型 → 页面先渲染 loading 再出数据。本测试用最小结构模拟这条链路。
 *
 * 结论落地后可删（若成为回归测试则保留）。
 */

private val captured3 = mutableMapOf<String, LazyListState>()
private var capturedNav3: Navigator? = null
private val scrollTarget3 = androidx.compose.runtime.mutableStateMapOf<String, Int>()
private val selectHooks = mutableMapOf<String, MutableList<(Int) -> Unit>>()

/** 模拟「按 Screen 实例记住的 ScreenModel」：数据异步加载，加载前页面渲染 loading。 */
private class FakeModel {
    var loaded by mutableStateOf(false)
        private set

    fun load() {
        loaded = true // 简化：下一帧即出数据（真实 app 是异步，效果同为结构翻转）
    }
}

private class ModelScreen(private val tag: String) : Screen {
    @Composable
    override fun Content() {
        // ⚠️ 按**实例** remember（等价 rememberScreenModel 的 per-instance store）
        val model = remember { FakeModel() }
        LaunchedEffect(Unit) { model.load() }
        if (!model.loaded) {
            Text("$tag loading…")
        } else {
            val st = rememberLazyListState()
            SideEffect { captured3[tag] = st }
            val target = scrollTarget3[tag] ?: -1
            LaunchedEffect(target) {
                if (target >= 0) st.scrollToItem(target)
            }
            LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
                items(300) { i -> Text("$tag $i", modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

private class BlankScreen3 : Screen {
    @Composable
    override fun Content() {
        Text("blank")
    }
}

/** 复刻 MainScreen + TabContent：tabs 用 remember（离开组合即丢），visitedTabs 同样。 */
private class TabHostScreen3(private val selectHook: (String, (Int) -> Unit) -> Unit) : Screen {
    @Composable
    override fun Content() {
        val selectedIndexState = rememberSaveable { mutableIntStateOf(0) }
        val selectedIndex by selectedIndexState
        val visitedTabs = remember { mutableStateListOf(selectedIndex) }
        selectHook("host") { index ->
            if (index !in visitedTabs) visitedTabs.add(index)
            selectedIndexState.intValue = index
        }
        // ⚠️ 与 MainScreen 完全一致：remember 持有 Screen 实例，离开组合即重建。
        val tabs = remember { listOf(ModelScreen("A"), ModelScreen("B")) }
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

class ModelReloadScrollRestoreTest {

    @Test
    fun `实例重建加模型重载后 push 返回滚动应保留`() {
        captured3.clear(); scrollTarget3.clear(); capturedNav3 = null
        var frameTimeNanos = 0L

        val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            Navigator(TabHostScreen3 { key, hook -> selectHooks.getOrPut(key) { mutableListOf() }.add(hook) }) { nav ->
                capturedNav3 = nav
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
            val stA = assertNotNull(captured3["A"], "tab A 未组合")
            scrollTarget3["A"] = 80
            pump(1000)
            val aBefore = stA.firstVisibleItemIndex
            check(aBefore > 0) { "前置失败：A 没滚下去" }

            // 切到 B（经宿主 hook）并滚 B
            val hook = selectHooks["host"]?.lastOrNull() ?: error("selectTab hook 未注入")
            hook(1)
            pump(1000)
            val stB = assertNotNull(captured3["B"], "tab B 未组合")
            scrollTarget3["B"] = 120
            pump(1000)
            val bBefore = stB.firstVisibleItemIndex
            check(bBefore > 0) { "前置失败：B 没滚下去" }

            // push 覆盖 → pop 返回
            capturedNav3!!.push(BlankScreen3())
            pump(2000)
            capturedNav3!!.pop()
            pump(2000)

            // B 是选中 tab：等模型重载完（结构翻转后列表回来），滚动应恢复
            pump(1000)
            val stB2 = assertNotNull(captured3["B"], "返回后 B 未组合")
            assertEquals(bBefore, stB2.firstVisibleItemIndex, "pop 返回后 B 滚动丢失（实例重建+模型重载破坏恢复）")
        } finally {
            scene.close()
        }
    }
}
