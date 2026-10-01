package cp.player.app.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import cp.player.app.ui.theme.CpMotion

/** 分组列表的**外**圆角：分组首/末行朝外的那两个角，也是按下时整行撑到的目标值。 */
private val SegmentOuterRadius = 20.dp

/** 分组列表的**内**圆角：组内行朝里的角、以及中间行的全部四角。 */
private val SegmentInnerRadius = 4.dp

/**
 * Pure UI counterpart of the segmented list rows used by the Android app.
 *
 * 按下时**圆角会变形**：组内行静止时是 4dp 的细圆角（与邻行拼成一张分段卡片），
 * 按下瞬间四角一起撑到 20dp —— 于是"我抓住了这一行"除了水波纹之外还有一个
 * 明确的形状信号。做法借鉴 Kazumi 的 `SplitListRow`
 * （`reference/Kazumi/lib/bean/widget/split_list_row.dart`，那边是 4dp → 24dp）。
 *
 * 形状变形是 M3 Expressive 区别于 M3 的核心表达手段（`ToggleButton` 的选中态同理），
 * 而**成本极低**：只多一条 `animateFloatAsState`，不需要改任何布局。
 *
 * ⚠️ 用 `CpMotion.spatial()` 而不是手写 spring：圆角属 spatial（可回弹）。
 * 颜色/透明度才是 effects（不可回弹），两者混用会"看着不对"。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LegacyListItem(
    index: Int,
    total: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    leadingContent: (@Composable () -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val press = rememberPressedScale()
    val pressed by press.first.collectIsPressedAsState()
    // 只在**真的可点**时才做形状变形：不可点的行按下没反应，圆角却撑开，
    // 会给出"这里能点"的错误暗示。
    val shape = rememberSegmentShape(index, total, pressed = pressed && onClick != null)

    // Surface 自带 onClick 不支持长按，需要长按时改用 combinedClickable
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            leadingContent?.invoke()
            Column(Modifier.weight(1f)) {
                headlineContent()
                supportingContent?.invoke()
            }
            trailingContent?.invoke(this)
        }
    }
    if (onLongClick != null) {
        Surface(
            modifier = modifier.fillMaxWidth()
                .combinedClickable(
                    interactionSource = press.first,
                    indication = LocalIndication.current,
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick,
                ),
            shape = shape,
            color = containerColor,
            content = content,
        )
    } else {
        Surface(
            onClick = onClick ?: {},
            enabled = onClick != null,
            interactionSource = press.first,
            modifier = modifier.fillMaxWidth().then(if (onClick != null) press.second else Modifier),
            shape = shape,
            color = containerColor,
            content = content,
        )
    }
}

/**
 * 静止态的分段圆角（**静态**，不随按压变化）。
 *
 * 需要"按下变形"时用 [rememberSegmentShape]；这个函数保留给**没有按压态**的
 * 调用点（例如 SettingsKit 里由外层 `Switch` 承担交互的行）。
 */
fun legacySegmentShape(index: Int, total: Int): Shape {
    val c = segmentCorners(index, total)
    return RoundedCornerShape(c[0], c[1], c[2], c[3])
}

/**
 * 会随按压**变形**的分段圆角。
 *
 * 实现是"四角分别向 [SegmentOuterRadius] 插值"，用**一条** `animateFloatAsState`
 * 驱动（而不是四个 `animateDpAsState`）—— 四角共用同一个进度值，变形过程才不会
 * 出现四个角各走各的节奏。
 *
 * ⚠️ 不可点的行不要传 `pressed = true`：那会做出"能点"的假象。
 */
@Composable
fun rememberSegmentShape(index: Int, total: Int, pressed: Boolean): Shape {
    val morph by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = CpMotion.spatial(),
        label = "segmentShapeMorph",
    )
    val c = segmentCorners(index, total)
    return RoundedCornerShape(
        topStart = lerp(c[0], SegmentOuterRadius, morph),
        topEnd = lerp(c[1], SegmentOuterRadius, morph),
        bottomEnd = lerp(c[2], SegmentOuterRadius, morph),
        bottomStart = lerp(c[3], SegmentOuterRadius, morph),
    )
}

/**
 * 四角半径，顺序与 `RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)` 一致。
 *
 * 分段规则：首行圆上两角、末行圆下两角、中间行四角都收细 —— 这样整组拼起来
 * 才是一张连续的卡片，而不是几个独立圆角块。
 */
private fun segmentCorners(index: Int, total: Int): List<Dp> = when {
    total <= 1 -> listOf(SegmentOuterRadius, SegmentOuterRadius, SegmentOuterRadius, SegmentOuterRadius)
    index <= 0 -> listOf(SegmentOuterRadius, SegmentOuterRadius, SegmentInnerRadius, SegmentInnerRadius)
    index >= total - 1 -> listOf(SegmentInnerRadius, SegmentInnerRadius, SegmentOuterRadius, SegmentOuterRadius)
    else -> listOf(SegmentInnerRadius, SegmentInnerRadius, SegmentInnerRadius, SegmentInnerRadius)
}
