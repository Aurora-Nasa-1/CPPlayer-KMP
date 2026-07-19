package cp.player.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import cp.player.app.ui.screen.MainScreen
import cp.player.app.ui.screen.SetupScreen
import cp.player.app.ui.theme.CpTheme
import cp.player.kmp.BackendState
import cp.player.kmp.MusicBackend

/**
 * 应用根 Composable。
 *
 * 假定平台入口已在调用前完成 [MusicBackend.init]（由 androidMain/desktopMain 执行）。
 * 负责主题应用与 Voyager 根 Navigator 的初始路由判定：
 * - [BackendState.NoProvider]（含未初始化/错误回退）→ [SetupScreen]
 * - [BackendState.Ready] → [MainScreen]
 *
 * 通过观察 [MusicBackend.stateFlow] 响应 Provider 增删导致的瞬态切换，
 * 但根 Navigator 仅在启动时决定起点——后续切换由各 Screen 自行 push/replace。
 */
@Composable
fun App() {
    val themeMode by AppModel.themeModeFlow.collectAsState()
    val dynamic by AppModel.dynamicColorFlow.collectAsState()
    val pureBlack by AppModel.pureBlackFlow.collectAsState()

    CpTheme(themeMode = themeMode, dynamicColor = dynamic, pureBlack = pureBlack) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val state by AppModel.backend.stateFlow.collectAsState()
            val start = remember(state) {
                when (state) {
                    is BackendState.Ready -> MainScreen()
                    else -> SetupScreen()
                }
            }
            Navigator(start) { navigator ->
                SlideTransition(navigator)
            }
        }
    }
}
