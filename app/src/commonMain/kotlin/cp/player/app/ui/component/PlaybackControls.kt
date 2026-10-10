package cp.player.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.theme.CpMotion
import kotlinx.coroutines.delay

/** 主控件行的三个槽位 —— 记住「刚按下的是哪一个」，用来驱动权重联动。 */
private enum class ControlSlot { PREV, PLAY, NEXT }

/**
 * 按下后维持「胀开/收窄」的时长。
 *
 * 比动画本身略长一点：动画走 `spatialFast`（百毫秒量级），松手瞬间就回落会让
 * 整排的「呼吸」只闪一下看不清；维持到动作已经发出去之后再回落，读起来才是一整下。
 */
private const val ControlWeightHoldMillis = 260L

/**
 * 主控件行：上一首 / 播放·暂停 / 下一首。
 *
 * **Expressive 化的四处**（都靠 [CpMotion]，跟着主题的 `MotionScheme` 走）：
 *
 * 1. **权重联动**：按下的那一颗胀到 [pressedExpansion] 倍、另外两颗同时收到
 *    [pressedCompression] 倍 —— 整排像被捏了一下。形状没变、颜色没变，但整行的
 *    「重量」在动。这是本组件里感知最强、成本最低的一处反馈。
 * 2. 中央按钮按下时**圆角从 40dp 收到 22dp** —— 从胶囊向圆角方形过渡。这是 M3 Expressive
 *    表达「按下」的方式，比单纯变暗/缩放更有辨识度。
 * 3. 图标随按下轻微缩小（`0.90`），松手用 spatial 回弹弹回。
 * 4. 缓冲态用**变形加载指示器**（[CpLoadingIndicator]）而不是转圈的
 *    `CircularProgressIndicator` —— 后者在 M3 Expressive 里已被前者取代。
 *
 * ⚠️ 中央按钮的**尺寸由调用方通过 [centerButtonModifier] 决定**（播放页给的是
 * `.height(72.dp)`，配合 [centerWeight] = 1.2 得到一个略宽的胶囊）。所以这里只动画
 * 权重与圆角、不硬编码尺寸 —— 换成固定直径的圆按钮会让 72dp 的宽胶囊塌成一个小圆，
 * 整行比例垮掉。
 *
 * ⚠️ **[sideButtonModifier] / [centerButtonModifier] 里不要再写 `weight`**：
 * 权重现在由本组件按 [sideWeight] / [centerWeight] 加联动系数算出来，
 * 传进来的 `weight` 会被 `Modifier.weight` 的**后写覆盖**规则吃掉一半，行为不可预期。
 *
 * @param pressedExpansion 按下者相对自身基准权重的倍率。
 * @param pressedCompression 其余两颗相对自身基准权重的倍率（< 1 ⇒ 收窄）。
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
    sideWeight: Float = 1f,
    centerWeight: Float = 1.2f,
    pressedExpansion: Float = 1.1f,
    pressedCompression: Float = 0.65f,
    sideIconSize: Dp = 28.dp,
    centerIconSize: Dp = 40.dp,
) {
    var lastPressed by remember { mutableStateOf<ControlSlot?>(null) }
    LaunchedEffect(lastPressed) {
        if (lastPressed != null) {
            delay(ControlWeightHoldMillis)
            lastPressed = null
        }
    }

    // 倍率是**乘在各自基准权重上**的，不是绝对值 —— 这样 1 : 1.2 的「中央略宽」
    // 在联动期间也保持住，否则按下侧键时中央会被拉成和侧键一样宽，比例会跳一下。
    fun weightFor(slot: ControlSlot, base: Float): Float = when (lastPressed) {
        slot -> base * pressedExpansion
        null -> base
        else -> base * pressedCompression
    }

    val prevWeight by animateFloatAsState(
        targetValue = weightFor(ControlSlot.PREV, sideWeight),
        animationSpec = CpMotion.spatialFast(),
        label = "prevWeight",
    )
    val playWeight by animateFloatAsState(
        targetValue = weightFor(ControlSlot.PLAY, centerWeight),
        animationSpec = CpMotion.spatialFast(),
        label = "playWeight",
    )
    val nextWeight by animateFloatAsState(
        targetValue = weightFor(ControlSlot.NEXT, sideWeight),
        animationSpec = CpMotion.spatialFast(),
        label = "nextWeight",
    )

    Surface(
        modifier = modifier,
        shape = CircleShape,
        // 用**不透明**的 surfaceContainerHigh，而不是 surfaceContainerHighest 再压 50% 透明：
        // 播放页背景是一条竖向渐变，半透明容器透出来的底色随位置变化，
        // 同一颗按钮在页面上下两侧看起来是两种颜色。
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val s = cpStrings()
            Box(Modifier.weight(prevWeight).then(sideButtonModifier)) {
                SkipButton(
                    icon = Icons.Filled.SkipPrevious,
                    label = s.player.previousTrack,
                    onClick = onSkipPrevious,
                    onPressed = { lastPressed = ControlSlot.PREV },
                    modifier = Modifier.fillMaxSize(),
                    iconSize = sideIconSize,
                )
            }

            Box(Modifier.weight(playWeight).then(centerButtonModifier)) {
                PlayPauseSurface(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onClick = onPlayPause,
                    onPressed = { lastPressed = ControlSlot.PLAY },
                    modifier = Modifier.fillMaxSize(),
                    iconSize = centerIconSize,
                )
            }

            Box(Modifier.weight(nextWeight).then(sideButtonModifier)) {
                SkipButton(
                    icon = Icons.Filled.SkipNext,
                    label = s.player.nextTrack,
                    onClick = onSkipNext,
                    onPressed = { lastPressed = ControlSlot.NEXT },
                    modifier = Modifier.fillMaxSize(),
                    iconSize = sideIconSize,
                )
            }
        }
    }
}

@Composable
private fun SkipButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onPressed: () -> Unit,
    modifier: Modifier,
    iconSize: Dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = cp.player.app.ui.feedback.LocalCpHaptics.current
    IconButton(
        onClick = {
            // 先点亮「我按的是这一颗」，再发动作 —— 权重联动立刻开始，
            // 而动作本身要等下一帧，观感上是「按下去 → 整排动 → 才切歌」。
            onPressed()
            // 切歌与播放/暂停同级，都是「我按了，必须马上知道」的动作。
            haptics.perform(cp.player.app.ui.feedback.CpHaptic.Confirm)
            onClick()
        },
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
    onPressed: () -> Unit,
    modifier: Modifier,
    iconSize: Dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = cp.player.app.ui.feedback.LocalCpHaptics.current
    val s = cpStrings()

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
        onClick = {
            onPressed()
            haptics.perform(cp.player.app.ui.feedback.CpHaptic.Confirm)
            onClick()
        },
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
                        contentDescription = s.player.playOrPause(playing),
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

/**
 * 播放模式开关（随机 / 单曲循环 / 睡眠定时…）—— 带**选中态绽放**的 Expressive 按钮。
 *
 * 以前这些开关的激活反馈只有「图标换个颜色」，和普通禁用态几乎分不清，
 * 手指按上去也没有边界感。这里改成两层叠加，缺一层就发闷：
 *
 * 1. **容器**：未选中是透明的，选中时 `secondaryContainer` 圆底**长出来**；
 * 2. **形状**：`12dp 圆角方 → 24dp 正圆`，跟着容器一起变 —— 这正是 M3 Expressive
 *    用形状表达状态切换的方式，比单纯换色多一个维度的信息。
 *
 * 两层都走主题的 `spatialFast`，所以切开关时容器是「弹」出来的而不是淡出来的。
 */
@Composable
fun CpModeToggle(
    active: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    activeTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val haptics = cp.player.app.ui.feedback.LocalCpHaptics.current
    val container by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.secondaryContainer
        else androidx.compose.ui.graphics.Color.Transparent,
        // ⚠️ 颜色必须走 **effects** 通道。以前这里挂的是 spatialFast()，那是「位移/尺寸」的
        // 规格、**带回弹** —— 用在颜色上会让底色在到达前先过冲再回落，看着像颜色在抖。
        animationSpec = CpMotion.effectsFast(),
        label = "modeToggleContainer",
    )
    // 48dp 见方的按钮：12dp = 圆角方，24dp = 正圆。
    val corner by animateDpAsState(
        targetValue = if (active) 24.dp else 12.dp,
        animationSpec = CpMotion.spatialFast(),
        label = "modeToggleCorner",
    )

    Surface(
        onClick = {
            // 开关态切换给 Tick：比播放键的 Confirm 轻一档，因为它不是「主命令」。
            haptics.perform(cp.player.app.ui.feedback.CpHaptic.Tick)
            onClick()
        },
        shape = RoundedCornerShape(corner),
        color = container,
        modifier = modifier.size(48.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = if (active) activeTint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
