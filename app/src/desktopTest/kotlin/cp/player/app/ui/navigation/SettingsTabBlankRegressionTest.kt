package cp.player.app.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertTrue

private var placedScreens = mutableSetOf<String>()

private class TrackedScreen(val name: String) : Screen {
    @Composable
    override fun Content() {
        placedScreens.add(name)
        Text("Tracked: $name")
    }
}

private val TEST_TABS = listOf(
    TrackedScreen("Home"),
    TrackedScreen("Search"),
    TrackedScreen("Library"),
)

private class TabSwitchState(initialAlpha: Float) {
    val alpha = Animatable(initialAlpha)
    val slide = Animatable(0f)
}

@Composable
private fun TestTabContent(
    tabs: List<TrackedScreen>,
    visitedTabs: List<Int>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    // 关键防御：必须把 selectedIndex 补进 retainedIndices，避免状态不同步时页面被判定为未放置而变空白
    val retainedIndices = (visitedTabs + selectedIndex).distinct().sorted()
    val switchStates = remember { mutableStateMapOf<Int, TabSwitchState>() }
    var lastSelected by remember { mutableStateOf(selectedIndex) }
    val slideDistance = with(LocalDensity.current) { 32.dp.toPx() }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex == lastSelected) return@LaunchedEffect
        val direction = if (selectedIndex > lastSelected) 1f else -1f
        val leaving = switchStates[lastSelected]
        lastSelected = selectedIndex
        val entering = switchStates.getOrPut(selectedIndex) { TabSwitchState(initialAlpha = 0f) }
        entering.slide.snapTo(direction)
        entering.alpha.snapTo(0f)
        coroutineScope {
            launch { entering.alpha.animateTo(1f, tween(100)) }
            launch { entering.slide.animateTo(0f, tween(100)) }
            leaving?.let { launch { it.alpha.animateTo(0f, tween(100)) } }
        }
    }

    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            retainedIndices.forEach { index ->
                val state = switchStates.getOrPut(index) {
                    TabSwitchState(initialAlpha = if (index == selectedIndex) 1f else 0f)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer {
                            alpha = state.alpha.value
                            translationX = state.slide.value * slideDistance
                        },
                ) { tabs[index].Content() }
            }
        },
    ) { measurables, constraints ->
        val zeroConstraints = Constraints.fixed(0, 0)
        val placeables = measurables.mapIndexed { i, m ->
            val index = retainedIndices[i]
            val alpha = switchStates[index]?.alpha?.value ?: 0f
            if (index == selectedIndex || alpha > 0f) m.measure(constraints)
            else m.measure(zeroConstraints)
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val selectedPos = retainedIndices.indexOf(selectedIndex)
            val background = retainedIndices.indices.filter { it != selectedPos }
                .filter { pos -> (switchStates[retainedIndices[pos]]?.alpha?.value ?: 0f) > 0f }
            (background + if (selectedPos >= 0) listOf(selectedPos) else emptyList())
                .forEach { pos -> placeables[pos].placeRelative(0, 0) }
        }
    }
}

private val LocalDesktopTabIndexTest = compositionLocalOf { 0 }
private val LocalDesktopVisitedTabsTest = compositionLocalOf<List<Int>> { listOf(0) }

private object DesktopContentRootScreenTest : Screen {
    @Composable
    override fun Content() {
        val selectedIndex = LocalDesktopTabIndexTest.current
        val visitedTabs = LocalDesktopVisitedTabsTest.current
        TestTabContent(TEST_TABS, visitedTabs, selectedIndex, Modifier.fillMaxSize())
    }
}

private var testRootNavigator: Navigator? = null
private var selectTabHook: ((Int) -> Unit)? = null

private class TestMainScreen : Screen {
    @Composable
    override fun Content() {
        val selectedIndexState = rememberSaveable { mutableIntStateOf(0) }
        var selectedIndex by selectedIndexState
        val visitedTabs = rememberSaveable(
            saver = listSaver(
                save = { it.toList() },
                restore = { mutableStateListOf<Int>().apply { addAll(it) } },
            ),
        ) { mutableStateListOf(selectedIndex) }

        selectTabHook = { index ->
            if (index !in visitedTabs) visitedTabs.add(index)
            selectedIndex = index
        }

        CompositionLocalProvider(
            LocalDesktopTabIndexTest provides selectedIndex,
            LocalDesktopVisitedTabsTest provides visitedTabs.toList(),
        ) {
            Navigator(DesktopContentRootScreenTest)
        }
    }
}

private class DummyScreen(val name: String) : Screen {
    @Composable
    override fun Content() {
        Text("Dummy: $name")
    }
}

class SettingsTabBlankRegressionTest {
    @Test
    fun testSettingsMultiLevelReturnAndTabSwitchNotBlank() {
        testRootNavigator = null
        selectTabHook = null
        placedScreens.clear()
        var frameTimeNanos = 0L

        val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            Navigator(TestMainScreen()) { nav ->
                testRootNavigator = nav
                nav.lastItem.Content()
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

            pump(200)
            assertTrue(placedScreens.contains("Home"), "Home should be placed initially")

            // 模拟进入设置并点击多个深层层级
            testRootNavigator!!.push(DummyScreen("Settings Sub Screen"))
            pump(200)

            testRootNavigator!!.push(DummyScreen("Settings Sub-Sub Screen"))
            pump(200)

            testRootNavigator!!.push(DummyScreen("Settings Level 3"))
            pump(200)

            // 逐级返回
            testRootNavigator!!.pop()
            pump(200)

            testRootNavigator!!.pop()
            pump(200)

            testRootNavigator!!.pop()
            pump(200)

            // 返回后切换到搜索 Tab (index 1)
            placedScreens.clear()
            selectTabHook!!.invoke(1)
            pump(500)

            assertTrue(placedScreens.contains("Search"), "Search should be placed after return from deep settings")

            // 切换到我的 Tab (index 2)
            placedScreens.clear()
            selectTabHook!!.invoke(2)
            pump(500)

            assertTrue(placedScreens.contains("Library"), "Library should be placed after return from deep settings")
        } finally {
            scene.close()
        }
    }
}
