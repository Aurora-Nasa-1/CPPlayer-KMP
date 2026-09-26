package cp.player.app.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.ui.theme.CpShapes
import cp.player.app.ui.util.resized
import cp.player.core.playback.PlaybackUiState

/**
 * 底部 MiniBar。
 *
 * 视觉：顶圆角 28dp + 底圆角 16dp 的 Card，surfaceContainerHigh 色，2dp 抬升阴影；
 * 内含 48dp 封面 / 标题 / 歌手 / 上一首 + 播放暂停 + 下一首；下方贴底**波形**进度条。
 *
 * Expressive 化的两处：
 * - 进度条从直角 `LinearProgressIndicator` 换成 [CpWavyProgress]（M3 Expressive 的标志性元素）；
 * - 播放/暂停换成 [CpPlayPauseButton]，按下时圆角收缩 + 图标回弹。
 *
 * 点击主体区域 → [onClick]（展开全屏播放页）。
 * 仅当 [PlaybackUiState.currentTrack] 非空时渲染。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.MiniPlayer(
    state: PlaybackUiState,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    val press = rememberPressedScale()

    Surface(
        onClick = onClick,
        interactionSource = press.first,
        shape = CpShapes.miniPlayer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(press.second)
            .sharedBounds(
                sharedContentState = rememberSharedContentState(key = "player-container"),
                animatedVisibilityScope = animatedVisibilityScope
            ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!track.coverUrl.isNullOrBlank()) {
                    // 用 150px 缩略图，省内存与带宽。
                    AsyncImage(
                        model = track.coverUrl.resized(150),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "cover-${track.id}"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        Modifier.size(48.dp).clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote, null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        track.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "title-${track.id}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "artist-${track.id}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSkipPrev, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.SkipPrevious, "Prev", Modifier.size(24.dp))
                    }
                    CpPlayPauseButton(
                        isPlaying = state.isPlaying,
                        onClick = onTogglePlay,
                        size = 40.dp,
                        isLoading = state.isBuffering,
                    )
                    IconButton(onClick = onSkipNext, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.SkipNext, "Next", Modifier.size(24.dp))
                    }
                }
            }
            // 波形进度条：高度必须给够，压成 3dp 就看不出波形了。
            CpWavyProgress(
                progress = progress,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
            )
            val errorText = state.error
            if (!errorText.isNullOrBlank()) {
                Text(
                    errorText,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
