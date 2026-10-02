package cp.player.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 诊断用：Voyager push 覆盖 → pop 返回后，列表的滚动位置是否保留。
 *
 * 结构完全复刻 `App.kt` 的根导航（Navigator + ScreenTransition + AnimatedContent 过渡），
 * 用 ImageComposeScene 离屏逐帧推进，不走真机。
 *
 * ⚠️ Screen 必须是**命名类**：Voyager 默认 key 取 `qualifiedName`，
 * 局部/匿名类直接抛 `Default ScreenKey not found`。
 *
 * 结论落地后可删（若成为回归测试则保留）。
 */

// 测试状态挂钩：文件级，测试开始时重置。
private var capturedListState: LazyListState? = null
private var capturedNav: Navigator? = null
private val scrollTarget = mutableIntStateOf(-1)

private class ListScreen : Screen {
    @Composable
    override fun Content() {
        val st = rememberLazyListState()
        SideEffect { capturedListState = st }
        LaunchedEffect(scrollTarget.intValue) {
            val target = scrollTarget.intValue
            if (target >= 0) st.scrollToItem(target)
        }
        LazyColumn(state = st, modifier = Modifier.fillMaxWidth()) {
            items(300) { i -> Text("item $i", modifier = Modifier.fillMaxWidth()) }
        }
    }
}

private class BlankScreen : Screen {
    @Composable
    override fun Content() {
        Text("blank")
    }
}

class ScrollStateRestoreTest {

    @Test
    fun `push 覆盖再返回，列表滚动位置应保留`() {
        capturedListState = null
        capturedNav = null
        scrollTarget.intValue = -1
        var frameTimeNanos = 0L

        val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            Navigator(ListScreen()) { nav ->
                capturedNav = nav
                ScreenTransition(
                    navigator = nav,
                    transition = {
                        fadeIn(tween(300)) togetherWith fadeOut(tween(220))
                    },
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
            val st = assertNotNull(capturedListState, "首屏未组合")
            scrollTarget.intValue = 80
            pump(1000)
            val before = st.firstVisibleItemIndex
            check(before > 0) { "前置失败：列表没滚下去（index=$before）" }

            capturedNav!!.push(BlankScreen())
            pump(2000)
            capturedNav!!.pop()
            pump(2000)

            val after = st.firstVisibleItemIndex
            assertEquals(before, after, "pop 返回后滚动位置丢失：before=$before after=$after")
        } finally {
            scene.close()
        }
    }
}
