package cp.player.app.ui.component.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cp.player.core.media.VideoScalingMode
import cp.player.core.playback.video.PlatformVideoPlayer

@Composable
actual fun VideoSurface(
    player: PlatformVideoPlayer,
    modifier: Modifier,
    scalingMode: VideoScalingMode,
) {
    val resolution by player.videoResolution.collectAsState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val aspect = resolution?.aspectRatio ?: (16f / 9f)
        val aspectModifier = if (scalingMode == VideoScalingMode.FIT && aspect > 0f) {
            Modifier.aspectRatio(aspect)
        } else {
            Modifier.fillMaxSize()
        }

        // 桌面端视口容器：使用纯 Compose 轻量组件，彻底消除重量级 AWT SwingPanel 对所有界面的纯黑遮挡 (Airspace Issue)
        Box(modifier = aspectModifier)
    }

    DisposableEffect(player) {
        onDispose {
            player.attachSurface(null)
        }
    }
}
