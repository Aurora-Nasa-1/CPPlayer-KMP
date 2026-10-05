package cp.player.app.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightTarget
import cp.player.app.ui.theme.CpShapes
import cp.player.app.ui.util.resized
import cp.player.core.playback.PlaybackUiState

/**
 * 底部 MiniBar。
 *
 * 视觉：顶圆角 28dp + 底圆角 16dp 的 Card，surfaceContainerHigh 色，2dp 抬升阴影；
 * 内含 48dp 封面 / 标题 / 歌手 / 上一首 + 播放暂停 + 下一首；下方贴底一条**直线**进度条。
 *
 * Expressive 化的两处：
 * - 进度条用 [CpLinearProgress]（M3 Expressive 的非波形形态：圆头 + 末端 stop indicator）。
 *   ⚠️ **这里刻意不用 [CpWavyProgress]**：波形要靠"高度"才能看出起伏，压到 4dp 的窄条上
 *   只剩一团抖动的色块，而迷你播放器是**余光扫一眼**的地方 —— 直线更易读也更安静。
 *   波形留给桌面播放页那条大尺度、可拖动的 [CpSeekBar]
 *   （移动端播放页已改用旧版同款直线进度条 [CpPlainSeekBar]）。
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
    // 封面飞行落点：飞行未落位时把自己的封面藏起来，由飞行器顶替显示。
    val hideCover = CoverFlight.isFlyingTo(CoverFlight.TARGET_MINI)
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    val press = rememberPressedScale()
    val s = cpStrings()

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
            // 「一起听」条：只在已经在房间里时绘制（见 ListenTogetherStrip 的 KDoc）。
            // 放在小播放器**内部**而不是宿主上：两个宿主共用本组件，一处改动即覆盖
            // 三个 tab 与所有路由页；而且尾留白量的是本组件的实测高度，加在这里
            // 留白会自动跟着变，不会出现「条把列表最后一行压住」。
            ListenTogetherStrip()

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).graphicsLayer { alpha = if (hideCover) 0f else 1f },
                ) {
                    if (!track.coverUrl.isNullOrBlank()) {
                        // 用 150px 缩略图，省内存与带宽。
                        AsyncImage(
                            model = track.coverUrl.resized(150),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                                .coverFlightTarget(CoverFlight.TARGET_MINI, 12.dp)
                                .sharedBounds(
                                    sharedContentState = rememberSharedContentState(key = "cover-${track.id}"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                                .clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        // 空封面走全应用统一的 Expressive 占位（渐变 + 形状），
                        // 而不是本地再画一个灰底 MusicNote —— 同一个「没有封面」
                        // 不该在迷你播放器和播放页长得不一样。
                        CpCoverPlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            corner = 16.dp,
                            // 48dp 下变形看不出来，关掉省一条常驻动画。
                            animated = false,
                        )
                    }
                    // 「正在响」标记：与列表项里那颗均衡器**完全同款**。
                    // 有它在，用户余光扫到底栏就知道还在放，不必去读播放/暂停按钮的图标。
                    if (state.isPlaying) {
                        CpPlayingEqualizer(
                            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
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
                        // 与全屏播放页的主控件行同族（Filled）—— 之前这里是 Outlined、
                        // 全屏页是 Filled，同一个「上一首」在同一个应用里长两个样子。
                        Icon(Icons.Filled.SkipPrevious, s.player.previousTrack, Modifier.size(24.dp))
                    }
                    CpPlayPauseButton(
                        isPlaying = state.isPlaying,
                        onClick = onTogglePlay,
                        size = 40.dp,
                        isLoading = state.isBuffering,
                    )
                    IconButton(onClick = onSkipNext, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.SkipNext, s.player.nextTrack, Modifier.size(24.dp))
                    }
                }
            }
            // 直线进度条：左右留 12dp 与文字对齐，底部留 8dp 让它"浮"在卡片里而不是贴边。
            CpLinearProgress(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
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
