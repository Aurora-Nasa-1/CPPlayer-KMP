package cp.player.app.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 骨架屏（shimmer）。
 *
 * ## 为什么需要它
 *
 * 治理前全应用的加载态只有一种样子：`ContentState(loading = true)` 里居中一个
 * 变形加载器。列表场景下这等于「先空一屏，再整屏跳出来」—— 用户**感知到**的等待
 * 比真实等待更长。骨架屏给的是「内容即将出现在这些位置」：结构先到位、数据后填，
 * 页面看起来是在**长出来**而不是**重画一遍**。
 *
 * ## 三条纪律
 *
 * 1. **颜色必须走容器色阶**（`surfaceContainerHigh` → `surfaceContainerHighest`），
 *    与 `ContentState` / 卡片同源。写死灰色的话，纯黑模式下会整片发亮，
 *    浅色下又会与页面背景撞色。
 * 2. **只在真的在加载时挂载**。这是无限动画，与 [CpPlayingEqualizer] /
 *    [CpWavyProgress] 同一条纪律 —— 常驻就是白烧帧。
 * 3. **骨架的形状与尺寸要跟真内容对齐**（封面块用真封面的圆角与边长），
 *    否则数据到位时会有一次明显的「跳一下」，比直接显示加载器还差。
 */

/** 扫光一个周期。1000ms：慢到不抢注意力，快到不像卡住。 */
private const val ShimmerPeriodMillis = 1000

/** 周期之间的停顿，避免整屏扫光看起来像频闪。 */
private const val ShimmerDelayMillis = 200

/** 高光相对底色的不透明度。太低看不出在动，太高像在闪。 */
private const val ShimmerHighlightAlpha = 0.55f

/** 扫光带的宽度占容器的比例。 */
private const val ShimmerSweepFraction = 0.6f

/**
 * 给任意块套上骨架扫光。
 *
 * @param shape 应与真内容的形状一致（例如封面用 `MaterialTheme.shapes.medium`）。
 */
@Composable
fun Modifier.cpShimmer(shape: Shape = MaterialTheme.shapes.small): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    val transition = rememberInfiniteTransition(label = "cpShimmer")
    // ⚠️ 不要写成 `by` 后在外面读：这里刻意保留 `State` 本体，让 `.value` 只在下面
    // `drawBehind` 的**绘制 lambda** 里被读 —— 每帧只重绘、不重组。
    val sweep = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = ShimmerPeriodMillis,
                delayMillis = ShimmerDelayMillis,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cpShimmerSweep",
    )
    return this
        .clip(shape)
        .background(base)
        .drawBehind {
            val width = size.width
            if (width <= 0f) return@drawBehind
            val band = width * ShimmerSweepFraction
            // 从「完全在左边界之外」扫到「完全在右边界之外」。
            val startX = -band + (width + 2f * band) * sweep.value
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        highlight.copy(alpha = ShimmerHighlightAlpha),
                        Color.Transparent,
                    ),
                    start = Offset(startX, 0f),
                    end = Offset(startX + band, 0f),
                ),
            )
        }
}

/** 骨架块 —— [Modifier.cpShimmer] 的具名包装，读起来更像「一个占位块」。 */
@Composable
fun CpShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Box(modifier.cpShimmer(shape))
}

/**
 * 歌曲列表首屏的骨架：封面块 + 两行文字块。
 *
 * 尺寸刻意与 `SongItem` 对齐（48dp 封面 / 12dp 圆角），这样数据到位时不会「跳」。
 *
 * ⚠️ **只加纵向内边距**：本仓库的列表容器一律已经在 `contentPadding` 里给了
 * `CpSpacing.pageHorizontal`，这里再加横向内边距会变成双重缩进（左边缘比真内容多 16dp）。
 * 需要额外横向留白的调用方自己包一层。
 */
@Composable
fun CpSongRowSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 6,
    coverSize: Dp = 48.dp,
    coverCorner: Dp = 12.dp,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CpShimmerBox(
                    modifier = Modifier.size(coverSize),
                    shape = RoundedCornerShape(coverCorner),
                )
                Column(
                    Modifier.weight(1f).padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 两条文字块宽度**不同** —— 等宽的两条看起来像表格，不像文字。
                    CpShimmerBox(
                        modifier = Modifier.fillMaxWidth(0.62f).height(14.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    CpShimmerBox(
                        modifier = Modifier.fillMaxWidth(0.38f).height(11.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                }
            }
        }
    }
}
