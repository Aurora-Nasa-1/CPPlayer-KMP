package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.video.VideoPlayerView

/**
 * 跨平台独立视频 / MV 播放路由页面。
 *
 * 接入点：
 * 1. 歌曲播放大页中的 "MV" 徽标或操作入口（mediaId 如 `netease://mv/{mvId}`）；
 * 2. 本地曲库与下载管理中的本地视频条目（mediaId 如 `local://video/{path}`）。
 *
 * 生命周期联动：
 * - 页面进入并初始化：视频播放控制器拉取媒体流开播，音频播放器自动无缝暂停；
 * - 页面退出销毁：视频播放控制器停止并释放画面渲染句柄。
 */
data class VideoPlayerScreen(
    val mediaId: String,
    val title: String? = null,
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val videoController = AppModel.backend.videoController
        var isFullscreen by remember { mutableStateOf(false) }

        LaunchedEffect(mediaId) {
            videoController.play(mediaId)
        }

        DisposableEffect(mediaId) {
            onDispose {
                videoController.stop()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            VideoPlayerView(
                controller = videoController,
                title = title,
                isFullscreen = isFullscreen,
                onBackClick = { navigator.pop() },
                onToggleFullscreen = { isFullscreen = !isFullscreen },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
