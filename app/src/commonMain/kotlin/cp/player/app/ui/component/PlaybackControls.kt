package cp.player.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.CpMotion

/**
 * 主控件行：上一首 / 播放·暂停 / 下一首。
 *
 * **Expressive 化的三处**（都靠 [CpMotion]，跟着主题的 `MotionScheme` 走）：
 *
 * 1. 中央按钮按下时**圆角从 40dp 收到 22dp** —— 从胶囊向圆角方形过渡。这是 M3 Expressive
 *    表达「按下」的方式，比单纯变暗/缩放更有辨识度。
 * 2. 图标随按下轻微缩小（`0.90`），松手用 spatial 回弹弹回。
 * 3. 缓冲态用**变形加载指示器**（[CpLoadingIndicator]）而不是转圈的
 *    `CircularProgressIndicator` —— 后者在 M3 Expressive 里已被前者取代。
 *
 * ⚠️ 中央按钮的**尺寸由调用方通过 [centerButtonModifier] 决定**（播放页给的是
 * `weight(1.2f).height(72.dp)`，即一个宽胶囊）。所以这里只动画圆角、不硬编码尺寸 ——
 * 换成固定直径的圆按钮会让 72dp 的宽胶囊塌成一个小圆，整行比例垮掉。
 */
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
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipButton(Icons.Filled.SkipPrevious, "上一首", onSkipPrevious, sideButtonModifier, sideIconSize)

            PlayPauseSurface(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                onClick = onPlayPause,
                modifier = centerButtonModifier,
                iconSize = centerIconSize,
            )

            SkipButton(Icons.Filled.SkipNext, "下一首", onSkipNext, sideButtonModifier, sideIconSize)
        }
    }
}

@Composable
private fun SkipButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    iconSize: Dp,
) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.then(Modifier.cpPressScale(interaction)),
        interactionSource = interaction,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PlayPauseSurface(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    iconSize: Dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 胶囊(40dp) ↔ 圆角方形(22dp)。40dp 是「高度 72dp 的一半略多」，视觉上仍是胶囊。
    val corner by animateDpAsState(
        targetValue = if (pressed) 22.dp else 40.dp,
        animationSpec = CpMotion.spatialFast(),
        label = "playPauseCorner",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = CpMotion.spatialFast(),
        label = "playPauseIconScale",
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interaction,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isBuffering) {
                CpLoadingIndicator(
                    modifier = Modifier.size(iconSize * 0.9f),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
                    label = "playPauseIcon",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        modifier = Modifier
                            .size(iconSize)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            },
                    )
                }
            }
        }
    }
}
