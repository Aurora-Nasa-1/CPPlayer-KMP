package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.theme.CpMotion

/**
 * M3 Expressive 收藏按钮：点击时回弹放大，颜色渐变切换。
 *
 * 动效**必须**从 [CpMotion] 取（`spatialFast`）—— 以前这里手写
 * `spring(DampingRatioHighBouncy)`，它不跟着主题的 `MotionScheme` 走：
 * 换成「无动效」辅助功能设置或别的 motion scheme 时，只有这颗按钮还在自顾自地弹。
 */
@Composable
fun ExpressiveLikeButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val haptics = cp.player.app.ui.feedback.LocalCpHaptics.current
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 1.35f
            isFavorite -> 1.1f
            else -> 1f
        },
        animationSpec = CpMotion.spatialFast(),
        label = "likeScale",
        finishedListener = { pressed = false },
    )
    val tint by animateColorAsState(
        targetValue = if (isFavorite) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "likeTint",
    )
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = {
            if (busy) return@IconButton
            pressed = true
            busy = true
            // 收藏是「带结果的动作」，给 Success 级触感 —— 比播放键的 Confirm 更重一档，
            // 让「点错了 / 点对了」在手指上就能分辨，不必去看图标变没变红。
            haptics.perform(if (!isFavorite) CpHaptic.Success else CpHaptic.Tick)
            scope.launch {
                try {
                    onClick()
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isFavorite) "取消收藏" else "收藏",
            tint = tint,
            modifier = Modifier.scale(scale),
        )
    }
}
