package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主控件行（KMP 等效原项目 `PlaybackControls`）。
 *
 * 三按钮：上一首 / 播放·暂停 / 下一首，外层用圆形 Surface 套 surfaceVariant+0.5 透明、内层填 default padding。
 * 中央按钮使用 primary 色、圆角 24dp、白色 onPrimary 内部 icon。
 * 缓冲态显示 CircularProgressIndicator 取代播放/暂停图标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    sideButtonModifier: Modifier = Modifier,
    centerButtonModifier: Modifier = Modifier,
    sideIconSize: Dp = 28.dp,
    centerIconSize: Dp = 40.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.SpaceEvenly,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onSkipPrevious,
                shape = CircleShape,
                modifier = sideButtonModifier,
                color = Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(sideIconSize),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Surface(
                onClick = onPlayPause,
                shape = RoundedCornerShape(24.dp),
                modifier = centerButtonModifier,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(centerIconSize),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            Surface(
                onClick = onSkipNext,
                shape = CircleShape,
                modifier = sideButtonModifier,
                color = Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(sideIconSize),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}