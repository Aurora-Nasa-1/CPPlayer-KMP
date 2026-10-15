package cp.player.app.ui.component.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cp.player.core.media.VideoScalingMode
import cp.player.core.playback.video.PlatformVideoPlayer

/**
 * 跨平台视频画面渲染宿主。
 *
 * 挂载平台特定的视频渲染容器（Android SurfaceView / Desktop Canvas / HWND），
 * 并根据 [scalingMode] 动态适应视频宽高比。
 */
@Composable
expect fun VideoSurface(
    player: PlatformVideoPlayer,
    modifier: Modifier = Modifier,
    scalingMode: VideoScalingMode = VideoScalingMode.FIT,
)
