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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.formatTimeMs
import kotlin.math.roundToInt

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
 * 波形线性进度条（确定值）。
 *
 * 这是 M3 Expressive 在「音乐播放器」里最具辨识度的元素，替代原来的直角 `Slider` 轨道。
 *
 * @param progress 0f..1f。**调用方负责钳制** —— 波形指示器不钳制越界值，传 1.4f 会画出界。
 */
@Composable
fun CpWavyProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    animated: Boolean = true,
) {
    val safe = progress.coerceIn(0f, 1f)
    val shown by animateFloatAsState(
        targetValue = safe,
        // 用主题的 spatial 规格而不是 WavyProgressIndicatorDefaults.progressAnimationSpec：
        // 后者在 material3 1.11 里没有对 Kotlin 公开（javap 可见、源码不可见）。
        animationSpec = if (animated) CpMotion.spatial() else tween(0),
        label = "cpWavyProgress",
    )
    LinearWavyProgressIndicator(
        progress = { shown },
        modifier = modifier.height(16.dp),
        color = color,
        trackColor = trackColor,
    )
}

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
 */
@Composable
fun CpSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
) {
    val duration = durationMs.coerceAtLeast(1L).toFloat()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val shown = (if (dragging) dragValue else positionMs.toFloat()).coerceIn(0f, duration)
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
                onSeek(dragValue.toLong().coerceIn(0L, durationMs.coerceAtLeast(0L)))
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
 */
@Composable
fun CpPlayingEqualizer(
    modifier: Modifier = Modifier,
    barColor: Color = Color.White,
    scrimColor: Color = Color.Black.copy(alpha = 0.55f),
) {
    val transition = rememberInfiniteTransition(label = "cpEq")
    Row(
        modifier = modifier
            .background(scrimColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(3) { index ->
            val barHeight by transition.animateFloat(
                initialValue = 3f,
                targetValue = 10f,
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
            Box(
                Modifier.width(2.dp).height(barHeight.dp)
                    .background(barColor, RoundedCornerShape(1.dp))
            )
        }
    }
}

// ---------------------------------------------------------------- 播放控制

/**
 * Expressive 播放 / 暂停按钮。
 *
 * 三处动效叠在一起，缺一个就会「像普通 FilledIconButton」：
 * 1. 按下时容器**圆角收缩**（圆 → 圆角方形），带回弹；
 * 2. 图标随按下轻微缩小（触感反馈）；
 * 3. 播放 ↔ 暂停之间交叉淡入淡出。
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

    val corner by animateDpAsState(
        targetValue = if (pressed) size * 0.30f else size / 2f,
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    )
                )
            ),
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
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
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

// ---------------------------------------------------------------- 标题

/**
 * Expressive 区块标题。
 *
 * 比 `MaterialTheme.typography.titleMedium` 更强调：加粗 + 可选前置图标 + 可点尾部动作。
 * 统一用它，避免各页面自己拼字号字重（此前首页 / 我的 / 设置三处各不相同）。
 */
@Composable
fun ExpressiveSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f)) {
            androidx.compose.foundation.layout.Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
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
