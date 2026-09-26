package cp.player.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive「Bento」卡片族。
 *
 * 给「我的」页提供一组可自由拼装的仪表盘积木：大圆角、色调化容器、按下微缩回弹。
 * 所有容器色都取 [MaterialTheme.colorScheme]，因此完整跟随动态取色与深浅模式，
 * **不写死任何品牌色**。
 *
 * ⚠️ 中性卡片（`surfaceContainerHigh` 一类）在「纯黑」模式下会被 [cp.player.app.ui.theme.CpTheme]
 * 压成 `#000`，与背景同色而丢掉边界。所以这里统一叠一层 [bentoOutline]：
 * 普通模式下几乎看不见，纯黑模式下正好用来勾出卡片轮廓。
 */

/** Bento 卡片统一圆角。比 `Shapes.extraLarge`(32dp) 收敛 4dp，多卡并排时不至于太「软」。 */
val BentoShape = RoundedCornerShape(28.dp)

/** 卡片行/列之间的标准间距。 */
val BentoGap = 12.dp

/** 极淡描边，用于在「纯黑」模式下保住中性卡片的轮廓。 */
@Composable
fun bentoOutline(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

/** [contentColorFor] 只认语义角色，拿到未知色会返回 Unspecified，这里兜底成 onSurface。 */
@Composable
private fun bentoContentColor(container: Color): Color {
    val mapped = contentColorFor(container)
    return if (mapped == Color.Unspecified) MaterialTheme.colorScheme.onSurface else mapped
}

/**
 * Bento 基础卡片：大圆角 + 按下微缩 + 内容铺满。
 *
 * 高度由调用方决定（`Modifier.height(…)` / `fillMaxHeight()`），卡片内部按
 * `SpaceBetween` 排布，因此同一行里的卡片天然等高。
 *
 * @param onClick 为 null 时退化成静态容器（例如内部每个小方格各自可点的「偏好设置」卡）。
 * @param fillHeight 内容是否撑满卡片高度。
 *   ⚠️ **内容高度由自己决定的卡片必须传 false**：`fillMaxHeight()` 会把父容器的
 *   `maxHeight` 当成目标高度，所以只要卡片被放进一个**高度有界**的容器
 *   （普通 `Column`、固定高度的 `Row`），它就会一路撑满并让 `SpaceBetween`
 *   把内容撕开。放在 `LazyColumn` 的 item 里碰巧没事（那边高度是无界的），
 *   但这是巧合而不是保证 —— 别依赖它。
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = bentoContentColor(containerColor),
    border: BorderStroke? = bentoOutline(),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    fillHeight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val (interactionSource, pressModifier) = rememberPressedScale()
    val body: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .padding(contentPadding),
            verticalArrangement = if (fillHeight) Arrangement.SpaceBetween else Arrangement.Top,
            content = content,
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.then(pressModifier),
            shape = BentoShape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
            interactionSource = interactionSource,
        ) { body() }
    } else {
        Surface(
            modifier = modifier,
            shape = BentoShape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
        ) { body() }
    }
}

/**
 * 主行动卡（对应截图左上那块大面积强调色卡片）。
 *
 * 布局：标题 + 副标题靠左上，圆形箭头按钮钉在左下，右侧一枚大号图标色块做视觉锚点。
 */
@Composable
fun BentoHeroCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val contentColor = bentoContentColor(containerColor)
    val (interactionSource, pressModifier) = rememberPressedScale()
    Surface(
        onClick = onClick,
        modifier = modifier.then(pressModifier),
        shape = BentoShape,
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
                    .size(96.dp)
                    .clip(RoundedCornerShape(34.dp))
                    .background(contentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = contentColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(46.dp),
                )
            }
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.62f),
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = contentColor,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward, null,
                            tint = containerColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 次级行动卡：图标在左上、箭头在右上、标题与副标题压在底部。
 * 高度较小时会自动收敛字号，保证 1:1 窄卡也不换行溢出。
 */
@Composable
fun BentoActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    BentoCard(
        onClick = onClick,
        modifier = modifier,
        containerColor = containerColor,
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, null, modifier = Modifier.size(26.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward, null,
                modifier = Modifier.size(20.dp),
                tint = LocalContentColor.current.copy(alpha = 0.72f),
            )
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 数据卡：顶部一行说明，底部一排大号数字。
 *
 * @param stats 值 → 说明 的有序对，建议 2–3 组；组间用一枚星芒分隔。
 */
@Composable
fun BentoStatCard(
    label: String,
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val contentColor = bentoContentColor(containerColor)
    Surface(
        modifier = modifier,
        shape = BentoShape,
        color = containerColor,
        contentColor = contentColor,
        border = bentoOutline(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                stats.forEachIndexed { index, (value, caption) ->
                    if (index > 0) {
                        Icon(
                            Icons.Filled.AutoAwesome, null,
                            tint = contentColor.copy(alpha = 0.38f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            value,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            caption,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 偏好设置卡里的小方格：图标在上、标签在下，居中。 */
@Composable
fun BentoMiniTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (interactionSource, pressModifier) = rememberPressedScale()
    Surface(
        onClick = onClick,
        modifier = modifier.then(pressModifier),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = bentoOutline(),
        interactionSource = interactionSource,
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 底部居中的胶囊入口（「关于」这类低权重跳转）。 */
@Composable
fun BentoPill(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (interactionSource, pressModifier) = rememberPressedScale()
    Surface(
        onClick = onClick,
        modifier = modifier.then(pressModifier),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = bentoOutline(),
        interactionSource = interactionSource,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
