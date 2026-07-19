package cp.player.app.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.kmp.playback.PlaybackUiState
import cp.player.app.ui.theme.CpShapes

/**
 * 底部 MiniBar（KMP 等效原项目 `BottomPlaybackBar`）。
 *
 * 视觉：顶圆角 28dp + 底圆角 12dp 的 Card，surfaceContainerHigh 色，4dp 抬升阴影；
 * 内含 48dp 圆角封面 / 标题 / 歌手 / 上一首+播放暂停(FilledIconButton)+下一首；
 * 下方贴底 3dp LinearProgressIndicator 反映进度。
 *
 * 点击主体区域 → [onClick]（展开全屏播放页）。
 * 仅当 [PlaybackUiState.currentTrack] 非空时渲染。
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun androidx.compose.animation.SharedTransitionScope.MiniPlayer(
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
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(200),
        label = "miniProgress",
    )
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
                    AsyncImage(
                        model = track.coverUrl,
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
                    FilledIconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        if (state.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    IconButton(onClick = onSkipNext, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.SkipNext, "Next", Modifier.size(24.dp))
                    }
                }
            }
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
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
