package cp.player.app.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CommentPane
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.model.CommentScreenModel
import cp.player.app.ui.util.popOrNotify

/**
 * 评论页（窄屏整页形态）。
 *
 * 列表 / 楼层 / 回复输入条全部在共享组件 [CommentPane] 里 —— 桌面评论弹层复用同一份，
 * 本类只负责「外壳」：标题、返回、路由标题发布。
 */
class CommentScreen(val id: String, val type: String = "music") : Screen {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { CommentScreenModel(id, type) }
        val navigator = LocalNavigator.current
        val s = cpStrings()
        val playbackState by AppModel.playback.state.collectAsState()
        val currentTrack = playbackState.currentTrack
        val titleText = if (type == "music" && currentTrack != null && currentTrack.id == id) {
            "${currentTrack.name} - ${currentTrack.artist}"
        } else {
            s.social.comment.title
        }
        // 桌面自绘标题栏的标题（页内顶栏在桌面端整体让位，见 CpRouteScaffold 的 KDoc）。
        cp.player.app.ui.util.DesktopRouteTitle(titleText)

        // ⚠️ 必须走 `CpRouteScaffold`，不要退回 `AppScaffold`。
        //
        // 本页原先直接用 `AppScaffold`，是收敛返回键外观时**唯一漏掉**的一页：
        // `AppScaffold` 的返回键判据里带了 `!LocalWindowChromeActive`，桌面端于是既不画
        // 页内返回键、也不发布路由标题 —— 而它的 `onBackPressed` 又只有窄屏走得到。
        // 结果是宽屏窗口下从播放页三页页签点进评论后，**整页没有任何返回入口**。
        // `CpRouteScaffold` 把「桌面发布标题 / 窄屏自绘顶栏 / 双栏右栏只出正文」三选一
        // 收在一处，本页只需要声明标题与返回动作。
        CpRouteScaffold(
            title = titleText,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            CommentPane(model = model, modifier = pageModifier)
        }
    }
}
