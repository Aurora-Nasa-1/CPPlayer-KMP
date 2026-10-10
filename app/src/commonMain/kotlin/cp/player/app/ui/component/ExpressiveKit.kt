@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package cp.player.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.formatTimeMs
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * [CpSeekBar] 的总高。
 *
 * 拆开看：波形 16dp 居中（上下各留 20dp），顶部那 20dp 就是拖动时时间气泡的活动区，
 * 气泡高 18dp，与波形上沿留 2dp 缝。**改这个值必须同步改 [CpSeekBubbleHeight]** ——
 * 两者一变，气泡要么压住波形、要么飘得离波形太远。
 */
private val CpSeekBarHeight = 56.dp

/** 拖动时间气泡的高度。见 [CpSeekBarHeight]。 */
private val CpSeekBubbleHeight = 18.dp

/**
 * M3 Expressive 组件套件。
 *
 * 这些是 material3 1.11 的 Expressive API 在本项目的**唯一入口** —— 页面里不要直接调
 * `LinearWavyProgressIndicator` / `LoadingIndicator` / `ToggleButton`，理由：
 *
 * 1. 它们都带 `@ExperimentalMaterial3ExpressiveApi`，散落各处会让每个文件都要 opt-in；
 * 2. 波形进度条 / 变形加载器的**尺寸约束**很容易踩坑（默认容器高度远大于普通进度条），
 *    统一在这里收口；
 * 3. 观感要能一处调、处处变。
 *
 * ⚠️ 这些 API 来自 material3 `1.11.0-alpha07`（由 `libs.material3` 显式顶上去）。
 * 插件自带的 `compose.material3` 只有 `1.9.0`，里面**没有** `MaterialShapes` /
 * `WavyProgressIndicator` / `LoadingIndicator` / `ToggleButton` —— 若哪天那行依赖被删掉，
 * 这里会整片编译不过，而不是静默降级。
 */

// ---------------------------------------------------------------- 进度指示

/**
 * 直线线性进度条（确定值）—— M3 Expressive 的**非波形**形态。
 *
 * 与 [CpWavyProgress] 的区别只在「画法」：这条是直的、两端圆头、末端带一颗
 * Expressive 的 stop indicator（轨道尽头的小圆点，用来指示「进度到此为止」）。
 * 波形适合**大尺度、可交互**的场景（播放页 seek bar）；但**窄条**上波形会被压扁成
 * 一团抖动的色块（迷你播放器那一条只有 4dp 高），这时候直线更干净也更易读。
 *
 * @param progress 0f..1f。**调用方负责钳制**，这里再钳一次只是兜底。
 */
@Composable
fun CpLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    animated: Boolean = true,
) {
    val safe = progress.coerceIn(0f, 1f)
    val shown by animateFloatAsState(
        targetValue = safe,
        // 与 [CpWavyProgress] 同一条规格：主题 spatial，保证两条进度条动起来是"一家人"。
        // 这条例是**普通** `LinearProgressIndicator`：它内部没有自驱动动画，
        // `progress` 不变就不重绘 —— 与波形条的性能差别见 [CpWavyProgress]。
        animationSpec = if (animated) CpMotion.spatial() else tween(0),
        label = "cpLinearProgress",
    )
    // ⚠️ 必须走 `progress = { }` 这个 lambda 重载：material3 1.11 里传 `Float` 的重载
    // 已废弃（会走 replaceWith 到 lambda 版），而且只有 lambda 版带 strokeCap
    // —— 也就是 Expressive 那颗圆头 / stop indicator。
    LinearProgressIndicator(
        progress = { shown },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
    )
}

/**
 * 波形线性进度条（确定值）。
 *
 * 这是 M3 Expressive 在「音乐播放器」里最具辨识度的元素。
 *
 * ⚠️ **性能纪律 —— 波纹流动（[waveFlowing]）只允许在「正在播放」的场景开启**。
 * `LinearWavyProgressIndicator` 的 `waveSpeed` 非 0 时，内部起一条无限协程逐帧把
 * `waveOffset` 写进 `MutableFloatState`，而这个状态在**绘制阶段**被读取。于是只要
 * 这条进度条在树上，**它所在的那一层合成每帧都会重绘，并把整棵歌词子树一起拖进
 * 逐帧重绘**。
 *
 * 实测（离屏 `ImageComposeScene` 帧钟，60 帧窗口）：
 * - 静止页面 + 流动波形 ⇒ **60/60 帧重绘**
 * - 静止页面 + 静止波形（waveSpeed = 0） ⇒ **0/60 帧重绘**
 *
 * 因此这里**默认静止**（0，`updateOffsetAnimation()` 走 else 分支、不起协程）；
 * 调用方只有在「位置本来就在推进」的场景才传 `waveFlowing = true` —— 播放中进度
 * 每 200ms 推进、歌词逐字动画本来就在逐帧跑，流动的成本被吸收；而暂停 / 静止页面
 * 开流动，等于把整个窗口钉在 60fps 空转。历史教训：播放页曾因常驻流动波形整体帧率
 * 被拉低、逐字动画一格一格跳。
 *
 * 流动速度取 M3 规格：`waveSpeed = wavelength`，即**每秒流过一个波长**
 * （`LinearWavyProgressIndicator` 的默认行为，见其文档），与库默认观感一致。
 *
 * @param progress 0f..1f。**调用方负责钳制** —— 波形指示器不钳制越界值，传 1.4f 会画出界。
 * @param waveFlowing 波纹是否横向流动。只在「正在播放」时传 true（见性能纪律）。
 */
@Composable
fun CpWavyProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    animated: Boolean = true,
    waveFlowing: Boolean = false,
) {
    val safe = progress.coerceIn(0f, 1f)
    val shown by animateFloatAsState(
        targetValue = safe,
        // M3 自己的 `WavyProgressIndicatorDefaults.progressAnimationSpec` 是
        // `tween(500, LinearCubicBezier)` —— 语义上比主题的 spatial 弹簧更贴（有限时长、
        // 不来回弹）。但主题 spatial 与 [CpLinearProgress] 一致，两条进度条动起来是"一家人"，
        // 这里两种都只是数值收敛，**不是**帧率问题的来源（自驱动的是波形偏移，
        // 不是这个 targetValue 动画）。保持主题规格以求观感统一。
        animationSpec = if (animated) CpMotion.spatial() else tween(0),
        label = "cpWavyProgress",
    )
    LinearWavyProgressIndicator(
        progress = { shown },
        modifier = modifier.height(16.dp),
        color = color,
        trackColor = trackColor,
        waveSpeed = if (waveFlowing) CpWaveFlowSpeed else CpWaveStaticSpeed,
    )
}

/**
 * 波纹静止速度。`waveSpeed = 0` 时库内**不起**偏移动画协程（见 [CpWavyProgress] 性能纪律）。
 *
 * 抽成常量而不是散写 `0.dp`：静止是默认态，得有一个能被搜索到的名字。
 */
private val CpWaveStaticSpeed = 0.dp

/**
 * 波纹流动速度：M3 规格 —— `waveSpeed = wavelength`（每秒流过一个波长），与
 * `LinearWavyProgressIndicator` 默认值同源。流动**只在播放中**开启（见 [CpWavyProgress]）。
 */
private val CpWaveFlowSpeed = WavyProgressIndicatorDefaults.LinearDeterminateWavelength

/** 波形线性进度条（不确定值 / 缓冲中）。 */
@Composable
fun CpWavyProgressIndeterminate(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    LinearWavyProgressIndicator(
        modifier = modifier.height(16.dp),
        color = color,
        trackColor = trackColor,
        // ⚠️ 这里**不**传 0：不确定态本来就没有进度可推进，波纹流动是它**唯一**的状态信号。
        // 而且它只在「缓冲中」这一小段窗口挂载，不是一个常驻的逐帧重绘源。
    )
}

/**
 * 变形加载指示器（MaterialShapes 在多个形状之间来回变形）。
 *
 * 替代 `CircularProgressIndicator` —— 后者是「转圈」，前者是 Expressive 的标志性观感。
 */
@Composable
fun CpLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(modifier = modifier, color = color)
}

/**
 * 可拖动的**波形**进度条 —— M3 Expressive 在播放器里最具辨识度的元素。
 *
 * 实现方式是「波形进度条 + 全透明 Slider 叠在上面」：
 * 波形负责**画**（波形本身随位置推进，拖动时波峰就是游标），Slider 只负责**接手势**。
 * 这样既拿到波形观感，又不用自己实现拖拽/无障碍/键盘支持。
 *
 * 拖动期间顶部会浮出一个**时间气泡**，跟着手指横向移动、显示松手后会跳到的时刻 ——
 * 这是「我到底拖到了第几秒」的唯一答案，只靠波形边界去数是数不出来的。
 *
 * ⚠️ 三个坑：
 * 1. Slider 的触摸目标高 40dp 以上，外层 Box 必须给够高度，否则手势被裁掉；
 * 2. 拖动期间必须把 `animated` 关掉，否则动画在追手，手感发飘；
 * 3. 外层高度由 [CpSeekBarHeight] 决定：波形在正中（16dp），顶部 20dp 是气泡的活动区，
 *    两者之间必须留 2dp 缝隙 —— 气泡压住波形边界就等于把刚拖出来的位置挡住了。
 *
 * @param onSeek 松手时才回调（拖动期间只更新视觉），避免每帧 seek。
 * @param waveFlowing 波纹是否横向流动。**只在播放中传 true**（桌面播放页传 `state.isPlaying`）：
 *     暂停时波形静止，既是「画面随声音停住」的状态语义，也是性能纪律
 *     —— 流动波形会把所在层拖进逐帧重绘（见 [CpWavyProgress]）。
 */
@Composable
fun CpSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    waveFlowing: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    /**
     * 曲目标识。变化时清掉「松手保持」的目标 —— 换歌后位置跳回 0，不清的话滑条会
     * 举着上一首的秒数停最多 5 秒。null（默认）= 调用方不关心（不换曲的场景）。
     */
    trackKey: Any? = null,
) {
    val duration = durationMs.coerceAtLeast(1L).toFloat()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val held = rememberHeldSeekTarget(positionMs, durationMs, trackKey)
    val shown = (when {
        dragging -> dragValue
        // 松手后**按住**目标位置，等播放追上来 —— 见 [rememberHeldSeekTarget]。
        held.targetMs >= 0L -> held.targetMs.toFloat()
        else -> positionMs.toFloat()
    }).coerceIn(0f, duration)
    val haptics = cp.player.app.ui.feedback.LocalCpHaptics.current
    // 拖动时每跨过一整秒给一次轻点 —— 这是「我在逐秒定位」的唯一反馈，
    // 否则手指在波形上滑动完全是盲的。记住上一次打点的秒数，避免每帧触发。
    var lastTickSecond by remember { mutableStateOf(-1L) }

    // 气泡定位用的像素宽：trackWidth 来自外层，pillWidth 来自气泡自身的 onSizeChanged。
    // 两者都要 —— 气泡要以拖动点为中心，就必须知道自己的宽度才能把中心对过去。
    var trackWidthPx by remember { mutableStateOf(0) }
    var pillWidthPx by remember { mutableStateOf(0) }

    Box(
        modifier = modifier.height(CpSeekBarHeight).onSizeChanged { trackWidthPx = it.width },
        contentAlignment = Alignment.Center,
    ) {
        CpWavyProgress(
            progress = shown / duration,
            modifier = Modifier.fillMaxWidth(),
            color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant,
            trackColor = trackColor,
            animated = !dragging,
            waveFlowing = waveFlowing,
        )
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
                val second = (it / 1000f).toLong()
                if (second != lastTickSecond) {
                    lastTickSecond = second
                    haptics.perform(CpHaptic.Tick)
                }
            },
            onValueChangeFinished = {
                dragging = false
                lastTickSecond = -1L
                haptics.perform(CpHaptic.Confirm)
                val target = dragValue.toLong().coerceIn(0L, durationMs.coerceAtLeast(0L))
                // 先「按住目标」再发 seek：即便 onSeek 同步生效、引擎位置立刻回退，
                // 也不会闪一帧原位。
                held.hold(target)
                onSeek(target)
            },
            valueRange = 0f..duration,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) thumbColor else MaterialTheme.colorScheme.onSurfaceVariant,
                // 轨道全部透明：视觉完全交给下面的波形，Slider 只留一个「珠子」当抓手。
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // 时间气泡：只在拖动时出现。静止时不出现 —— 那个位置下面的 time 行已经写着了，
        // 再浮一个只会和它打架。
        if (dragging && enabled) {
            Box(
                Modifier.fillMaxWidth().height(CpSeekBubbleHeight).align(Alignment.TopCenter),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .onSizeChanged { pillWidthPx = it.width }
                        .offset {
                            // 以拖动点为中心：先算出相对容器中心的位移，再夹住不让气泡跑出边界。
                            val dx = ((shown / duration) - 0.5f) * trackWidthPx
                            val maxDx = ((trackWidthPx - pillWidthPx) / 2f).coerceAtLeast(0f)
                            IntOffset(dx.coerceIn(-maxDx, maxDx).roundToInt(), 0)
                        },
                ) {
                    Text(
                        text = formatTimeMs(shown.toLong()),
                        modifier = Modifier.padding(horizontal = 7.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 滑条：松手保持目标

/**
 * 「松手后保持目标」的滑条状态。
 *
 * ## 为什么需要它
 *
 * seek 之后引擎要过几百毫秒才真的跳到新位置。这期间若继续读引擎位置，滑条会
 * **先弹回原位、再追上来** —— 这一下弹回比不做任何平滑还难看，也是「seek 不跟手」
 * 的唯一来源。所以松手时把目标**按住**，直到权威位置追平、或安全网超时。
 *
 * 判据与 PixelPlayer 的 `targetSeekFraction` 同源：**追平容差 = 4% 时长**
 * （短曲目按 500ms 兜底），**安全网 5s**（引擎卡住 / seek 失败时不至于永远举着）。
 *
 * ⚠️ 用**权威位置**判追平，不用外推位置：外推值自己就在前进，拿它判会立刻自我
 * 满足，等于没按住。
 *
 * ## 为什么这里**不做**逐帧插值（一个刻意的取舍）
 *
 * 引擎每 200ms 给一次权威位置，理论上可以像歌词那样外推成逐帧前进的值。但滑条
 * 与歌词有一个本质差别：**歌词的位置是在绘制阶段读的（lambda provider，每帧只重绘），
 * 而 `Slider` 的 `value` 是组合期参数** —— 喂逐帧变化的值就是让整条滑条（含 M3
 * `Slider` 自身的交互层）**每秒重组 60 次**。
 *
 * 而收益是：每 200ms 的位移 = `200ms / 曲长 × 轨道像素宽`。240s 的歌在 600px 轨道上
 * 是 **0.5px**（亚像素，看不见）；只有**很短的曲目**（30s 级）才会到 ~4px 而可见。
 * 波形那一路本来就有 `animateFloatAsState` 弹簧（见 [CpWavyProgress]），已经是连续的。
 *
 * ⇒ 为一个「多数曲目下不可见、少数曲目下是 4px 台阶」的收益，换每秒 60 次重组，
 * **不划算**。将来若真的在短曲目上看到台阶，正确做法是把拇指改成自绘 + 手势自接
 * （那时位置可以在绘制阶段读），而不是给 `Slider` 喂逐帧值。
 */
internal class HeldSeekTarget {
    /** 按住的目标（毫秒）；-1 = 没有未完成的 seek。 */
    var targetMs by mutableStateOf(-1L)
        private set

    internal fun hold(targetMs: Long) {
        this.targetMs = targetMs
    }

    internal fun release() {
        targetMs = -1L
    }
}

/** 追平容差占时长的比例。 */
private const val HeldSeekToleranceFraction = 0.04f

/** 追平容差的绝对下限（短曲目用），毫秒。 */
private const val HeldSeekMinToleranceMs = 500L

/** 安全网：无论位置有没有更新，这么久之后一定放手。 */
private const val HeldSeekMaxHoldMs = 5_000L

/**
 * @param trackKey 曲目标识；变化时立刻放手（新曲目的位置与旧目标无关）。
 */
@Composable
internal fun rememberHeldSeekTarget(
    positionMs: Long,
    durationMs: Long,
    trackKey: Any?,
): HeldSeekTarget {
    val state = remember { HeldSeekTarget() }

    // 换曲：立刻放手。
    LaunchedEffect(trackKey) { state.release() }

    // 追平即放手。用权威位置判定（见 KDoc）。positionMs 每 200ms 变一次 ⇒ 本效果
    // 每 200ms 重启一次，代价只是读两个字段 + 一次比较。
    LaunchedEffect(positionMs, state.targetMs, durationMs) {
        val target = state.targetMs
        if (target < 0L) return@LaunchedEffect
        val tolerance = (durationMs * HeldSeekToleranceFraction).toLong()
            .coerceAtLeast(HeldSeekMinToleranceMs)
        if (abs(positionMs - target) <= tolerance) state.release()
    }

    // 安全网：与位置是否更新无关，保证一定会放手。
    LaunchedEffect(state.targetMs) {
        if (state.targetMs < 0L) return@LaunchedEffect
        delay(HeldSeekMaxHoldMs)
        state.release()
    }

    return state
}

/**
 * 旧版同款**直线**进度条 —— 1:1 移植自 `reference/cp-player-legacy` 的
 * `ui/component/ProgressSection.kt`（移动端播放页用，桌面播放页保留 [CpSeekBar] 波形）。
 *
 * 视觉完全交给 M3 `Slider` 的默认 Expressive 观感（粗轨道 + 竖向拇指 + 末端留白），
 * **不传 `colors`** —— 「符合 M3」最直接的做法就是用库默认值，而不是自己调色。
 * 时间行（已播 / 剩余）由调用方按旧版 ProgressSection 的布局自行摆放。
 *
 * 与旧版**唯一**的行为差异：seek 在**松手时**回调，而不是旧版那样在拖动中每帧回调。
 * 旧版 `onValueChange` 里直接 `onSeek`，对网络流等于拖一次发一串 range 请求；
 * KMP 端 `PlaybackController.seekTo` 的既有约定就是松手才 seek（[CpSeekBar] 同）。
 * 代价是需要本地 dragValue 在拖动期间顶住视觉 —— 引擎位置每 200ms 推进，
 * 不顶住的话滑条会被拽回去。
 *
 * 旧版没有的东西也不加：无拖动时间气泡、无逐秒触感打点 —— 「旧版同款」按字面执行。
 *
 * ⚠️ **唯一的例外**：松手后滑条会「按住」目标位置，等播放追上来（见 [HeldSeekTarget]）。
 * 这不是新增视觉，而是**修一个缺陷** —— 旧版在这里会先弹回原位再追上，看起来像
 * 「seek 没生效」。保留原有视觉的底线是「外观逐像素一致」，不包括复刻这个抖动。
 *
 * @param trackKey 曲目标识；变化时清掉未完成的「保持目标」。见 [HeldSeekTarget]。
 */
@Composable
fun CpPlainSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackKey: Any? = null,
) {
    val duration = durationMs.coerceAtLeast(1L).toFloat()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val held = rememberHeldSeekTarget(positionMs, durationMs, trackKey)
    val shown = (when {
        dragging -> dragValue
        held.targetMs >= 0L -> held.targetMs.toFloat()
        else -> positionMs.toFloat()
    }).coerceIn(0f, duration)
    Slider(
        value = shown,
        onValueChange = {
            dragging = true
            dragValue = it
        },
        onValueChangeFinished = {
            dragging = false
            val target = dragValue.toLong().coerceIn(0L, durationMs.coerceAtLeast(0L))
            // 先按住再 seek（同 CpSeekBar）。
            held.hold(target)
            onSeek(target)
        },
        valueRange = 0f..duration,
        enabled = enabled,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- 形状

/**
 * 持续变形的装饰形状。
 *
 * 用途：空状态、加载占位、封面缺省图、播放页的呼吸背景。**纯装饰**，不要拿它承载信息。
 *
 * @param from 起始形状；默认 `Cookie9Sided`（九边饼干）
 * @param to 目标形状；默认 `Clover4Leaf`（四叶草）—— 两者顶点数接近，变形过程不会打结
 */
@Composable
fun MorphingShape(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    from: RoundedPolygon = MaterialShapes.Cookie9Sided,
    to: RoundedPolygon = MaterialShapes.Clover4Leaf,
    periodMillis: Int = 3200,
) {
    // Morph 的构造要做特征匹配，比较贵 —— 必须 remember。
    val morph = remember(from, to) { Morph(from, to) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(from, to, periodMillis) {
        while (true) {
            progress.animateTo(1f, tween(periodMillis, easing = LinearEasing))
            progress.animateTo(0f, tween(periodMillis, easing = LinearEasing))
        }
    }
    Canvas(modifier) {
        // RoundedPolygon 的坐标大致落在 [-1, 1]，要自己缩放 + 平移到画布中心。
        val path = morph.toPath(progress.value, Path())
        val matrix = Matrix().apply {
            scale(size.width / 2f, size.height / 2f)
            translate(1f, 1f)
        }
        path.transform(matrix)
        drawPath(path, color)
    }
}

// ---------------------------------------------------------------- 状态标记

/**
 * 「正在播放」均衡器 —— 三根循环跳动的短棒。
 *
 * 这是音乐播放器里最省空间的状态标记：**一行文字都不用**，也不依赖颜色，
 * 用户扫一眼就知道哪首在放。放在封面上时自带一层半透明黑底，
 * 因为封面是任意图片，白棒压在浅色封面上会直接消失。
 *
 * 三根棒用不同的时长 + 起始偏移，节奏错开才像在跳；同步起落会像一个整体在缩放。
 *
 * **暂停时塌成三个点**：单看一个静止的图标分不出「暂停」和「还没开始」，
 * 而三根棒收成三个点读起来就是「音乐停住了」—— 形状比颜色多一个信息维度。
 *
 * ⚠️ 性能：这是全应用**调用点最多**的常驻无限动画（迷你播放器 + 每个列表行 +
 * 队列弹层都可能同时挂着一份）。三条纪律：
 * 1. **动画值只写进 `graphicsLayer`，不写进 `height`**。旧写法把每帧变化的
 *    `barHeight.dp` 直接喂给 `Modifier.height(...)`，等于每帧触发一次**重新测量
 *    + 重新布局**；而换算到 `scaleY` 后每帧只重绘，布局完全不动。
 * 2. **暂停时本组件自己就不挂 `rememberInfiniteTransition`**（按 [isPlaying] 分支）——
 *    暂停态是纯静态的三个点，零动画开销，调用方不必再自己判。
 * 3. 调用方仍只在**真的需要这个标记**时才渲染它（列表里只有当前那一首）：
 *    多挂一份就多一份绘制，与本组件内部省不省动画无关。
 *
 * @param isPlaying 播放中 → 三根棒跳动；暂停 → 塌成三个点（静态）。
 */
@Composable
fun CpPlayingEqualizer(
    modifier: Modifier = Modifier,
    barColor: Color = Color.White,
    scrimColor: Color = Color.Black.copy(alpha = 0.55f),
    isPlaying: Boolean = true,
) {
    Row(
        modifier = modifier
            .background(scrimColor, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isPlaying) {
            val transition = rememberInfiniteTransition(label = "cpEq")
            repeat(3) { index ->
                // ⚠️ 不要写成 `by`：这里刻意保留 `State` 本体，让 `.value` 只在
                // 下面 `graphicsLayer` 的**绘制 lambda** 里被读 —— 每帧只重绘、不重组。
                val scale = transition.animateFloat(
                    initialValue = CpEqualizerBarLowFraction,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        // 时长错开 + 反向播放 + 起始偏移：三根棒三种节奏，
                        // 这样才像三根独立的棒而不是一个块在伸缩。
                        animation = tween(
                            durationMillis = 480 + index * 140,
                            easing = FastOutSlowInEasing,
                        ),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(index * 160),
                    ),
                    label = "cpEqBar$index",
                )
                CpEqualizerBar(scale, barColor)
            }
        } else {
            // 暂停：三根棒收成三个点。一个常量 State 就够，不挂任何动画。
            val dotScale = remember { mutableStateOf(CpEqualizerDotFraction) }
            repeat(3) { CpEqualizerBar(dotScale, barColor) }
        }
    }
}

/** 单根棒。`scaleY` 由外部传入的 [State] 提供，**只在绘制阶段读取**（见上方性能纪律）。 */
@Composable
private fun CpEqualizerBar(
    scale: androidx.compose.runtime.State<Float>,
    barColor: Color,
) {
    Box(
        Modifier
            .width(2.dp)
            // 布局尺寸恒定在**最大**高度（10dp），动画只缩不涨 ——
            // 这样 Row 的高度在播放期间是个常量，不会每帧重新测量。
            .height(CpEqualizerBarMaxHeight)
            .graphicsLayer {
                scaleY = scale.value
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .background(barColor, RoundedCornerShape(1.dp))
    )
}

/** 均衡器单根棒的**布局**高度（dp）。动画只在它之上做 `scaleY` 缩放，不改布局。 */
private val CpEqualizerBarMaxHeight = 10.dp

/** 均衡器最短态占最长态的比例（3dp / 10dp），保持与原观感一致。 */
private const val CpEqualizerBarLowFraction = 3f / 10f

/** 暂停时三根棒收成的「点」占最长态的比例（棒宽 2dp / 最长 10dp）⇒ 正方形小点。 */
private const val CpEqualizerDotFraction = 2f / 10f


// ---------------------------------------------------------------- 播放控制

/**
 * Expressive 播放 / 暂停按钮。
 *
 * 四处动效叠在一起，缺一个就会「像普通 FilledIconButton」：
 * 1. **形状表达状态**：播放中是胶囊（正圆），暂停是圆角方形 —— 单看一个静止的图标
 *    分不出「在放」和「停住了」，形状比颜色多一个信息维度；
 * 2. 按下时容器**圆角再收紧一档**，带回弹；
 * 3. 图标随按下轻微缩小（触感反馈）；
 * 4. 播放 ↔ 暂停之间交叉淡入淡出。
 */
@Composable
fun CpPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = cp.player.app.ui.feedback.LocalCpHaptics.current

    // 静止圆角由播放状态决定：播放中 size/2（正圆 / 胶囊），暂停 size*0.36（圆角方形）。
    // 按下时无论哪种状态都收到 0.30 —— 「按下去」始终是同一个方向。
    val idleCorner = if (isPlaying) size / 2f else size * 0.36f
    val corner by animateDpAsState(
        targetValue = if (pressed) size * 0.30f else idleCorner,
        animationSpec = CpMotion.spatialFast(),
        label = "cpPlayCorner",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = CpMotion.spatialFast(),
        label = "cpPlayIconScale",
    )

    Surface(
        onClick = {
            // 播放/暂停是整个应用最高频的动作，必须有触感确认 ——
            // 没有它的话，图标淡入淡出期间用户会怀疑「到底点上没有」而连点两下。
            haptics.perform(CpHaptic.Confirm)
            onClick()
        },
        modifier = modifier.size(size),
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interaction,
    ) {
        Box(Modifier.fillMaxWidth().height(size), contentAlignment = Alignment.Center) {
            if (isLoading) {
                // 缓冲态用变形加载指示器顶替图标，而不是另套一个 CircularProgressIndicator
                // —— 后者在 M3 Expressive 里已被前者取代，混用会像「新旧两套 UI 拼在一起」。
                CpLoadingIndicator(
                    modifier = Modifier.size(size * 0.52f),
                    color = contentColor,
                )
            } else {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                    },
                    label = "cpPlayPauseIcon",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        modifier = Modifier
                            .size(size * 0.46f)
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

// ---------------------------------------------------------------- 占位 / 工具条

/**
 * 空封面占位 —— 全应用**唯一**的空封面画法。
 *
 * `primaryContainer → tertiaryContainer` 斜向渐变 + 持续变形的 MaterialShapes 形状。
 *
 * **为什么必须统一**：一块纯 `surfaceVariant` 的灰方块在浅色主题下读起来像
 * 「图加载失败」，而不是「这张专辑本来就没有封面」；渐变才说明它是**刻意的**占位。
 * 此前只有桌面播放页这么做，播放页 / 迷你播放器 / 列表项各自写了一个灰底 `MusicNote`
 * —— 同一个「没有封面」在四处长得不一样，一眼就能看出哪块是后补的。
 *
 * @param corner 占位块圆角，应与调用处真实封面的圆角一致
 * @param animated 形状是否持续变形。**小于约 72dp 时关掉** —— 那个尺寸下变形根本看不出来，
 *   却要一直跑一条 `Animatable`；小尺寸退化成静态图标反而更清楚。
 */
@Composable
fun CpCoverPlaceholder(
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    animated: Boolean = true,
) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    // ⚠️ Brush 必须 remember：占位块常出现在**每 200ms 重组一次**的列表行 / 迷你播放器里
    // （它们订阅整个 `PlaybackUiState`）。不 remember 就是每次重组重建一次渐变对象。
    // 颜色是唯一输入，以颜色为 key。
    val gradientTop = MaterialTheme.colorScheme.primaryContainer
    val gradientBottom = MaterialTheme.colorScheme.tertiaryContainer
    val backgroundBrush = remember(gradientTop, gradientBottom) {
        Brush.linearGradient(listOf(gradientTop, gradientBottom))
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(backgroundBrush),
        contentAlignment = Alignment.Center,
    ) {
        if (animated) {
            MorphingShape(
                modifier = Modifier.fillMaxSize(0.42f),
                color = ink.copy(alpha = 0.45f),
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = ink.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(0.40f),
            )
        }
    }
}

/**
 * Expressive 浮动工具条：**按内容宽度**、居中、带抬升的一枚胶囊。
 *
 * 与「整宽 `Surface(CircleShape)`」的差别就是它**不长满一行**。M3 Expressive 里
 * 次级工具（随机 / 循环 / 睡眠 / 更多）是一枚**浮在内容之上**的胶囊，而不是又一条工具栏。
 *
 * 此前播放页有两枚**等宽**全宽胶囊上下叠着（主控件 + 工具行），看起来像两条工具栏，
 * 层级全丢；而歌词页那枚只有两个按钮却铺满整宽，`SpaceEvenly` 把两者推到 1/4 与 3/4 处，
 * 中间空出一大块，像「少了两个按钮」。
 *
 * 用法：`CpFloatingToolbar(Modifier.align(Alignment.CenterHorizontally)) { … }`
 */
@Composable
fun CpFloatingToolbar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // 层级靠**色阶**（surfaceContainerHigh 比页面背景高一档）表达，不靠阴影。
        // 此前 tonal + shadow 各 3dp 同时拉满：tonal 把容器提亮、shadow 再压一圈黑边，
        // 两者叠加的结果是边缘发脏 —— 阴影只在"真的浮在内容之上"时才有意义，
        // 而工具条本身已经有更高的容器色阶了。
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

// ---------------------------------------------------------------- 选择控件

/**
 * Expressive 切换按钮（带形状变形的 ToggleButton）。
 *
 * 与 `FilterChip` 的区别：选中时**形状本身会变**（圆角方形 → 胶囊），这正是 Expressive
 * 的表达方式。用于设置项开关、搜索类型筛选、播放页的循环模式等。
 */
@Composable
fun CpToggleChip(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        shapes = ToggleButtonDefaults.shapes(),
    ) {
        ChipContent(icon = icon, label = label)
    }
}

@Composable
private fun RowScope.ChipContent(icon: ImageVector?, label: String) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
}

// ---------------------------------------------------------------- Modifier

/**
 * 按下时轻微缩小的 Expressive 反馈。
 *
 * 与 `rememberPressedScale`（固定 0.98 + MediumBouncy）的区别是这里用**主题的** spatial
 * 动效规格，会跟着 `MotionScheme` 走。
 */
@Composable
fun Modifier.cpPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = CpMotion.spatialFast(),
        label = "cpPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 给任意容器套一个「Expressive 大圆角」的裁剪。 */
@Composable
fun Modifier.cpExpressiveClip(radius: Dp = 28.dp): Modifier =
    this.clip(RoundedCornerShape(radius))

// ---------------------------------------------------------------- 页面刷新

/**
 * 页面级「刷新」容器 —— 首页 / 媒体库等主 tab 的统一刷新入口。
 *
 * 按平台分流：
 * - **桌面**：包一层 [CpContextMenu]（passive = true）—— 空白处右键弹「返回上一级 / 刷新」。
 *   passive 让歌曲行自己的右键菜单与卡片点击手势优先，页面菜单只在没有任何
 *   子级接手的区域弹出。「返回上一级」由 [rememberBackContextMenuItem] 提供，
 *   退无可退时**不出现**（不会摆一个点了没反应的灰项）。
 * - **Android（触屏）**：material3 的 [PullToRefreshBox] 下拉刷新。桌面不开下拉：
 *   鼠标滚轮在列表顶部继续上滚的 overscroll 也会被下拉刷新的 nested scroll 吃掉，
 *   「想在顶部再滚一下」会莫名其妙拽出刷新指示器 —— 桌面的刷新入口是右键菜单。
 *
 * material3 的实验 API 在这里收口（与 [CpLoadingIndicator] 等同一约定），
 * 页面不要直接 import `pulltorefresh`。
 *
 * @param isRefreshing 刷新在途标记（来自 ScreenModel，**不是**页面的全屏加载态）。
 * @param onRefresh 用户触发刷新（下拉到位释放 / 点右键菜单「刷新」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpRefreshablePage(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (cp.player.app.platform.isAndroidPlatform()) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier,
        ) {
            content()
        }
    } else {
        // ⚠️ 返回项必须在这里（而不是在页面外面再包一层菜单）：
        // 本容器自己已经在处理空白处的右键，外面再包一层会被这里的 `passive` 抢先消费掉
        // （子级先于父级收到 Main 阶段的 Press），外层菜单**永远弹不出来**。
        val backItem = rememberBackContextMenuItem()
        val menuItems = remember(onRefresh, backItem) {
            buildList {
                if (backItem != null) add(backItem)
                add(CpContextMenuItem("刷新", Icons.Filled.Refresh, onClick = onRefresh))
            }
        }
        CpContextMenu(items = menuItems, modifier = modifier, passive = true, content = content)
    }
}
