package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.theme.CpMotion

/**
 * 音质档位选择器 —— 两处入口共用同一份「档位表 + 文案表」。
 *
 * 为什么单独成文件：[AppModel.qualityLevels] 是**落盘用的 level**（`standard` /
 * `exhigh` / `lossless` / `hires`），展示名只在 `s.quality.labelOf(level)` 里。
 * 两处入口（桌面迷你播放器的 chip、播放页「更多」弹层的音质卡片）如果各写一遍
 * `when (level) { "lossless" -> "无损" … }`，中英切换时会有一处漏译 —— 那正是
 * `QualityStrings` 存在的理由。这里只做 UI，档位与文案都从既有事实源取。
 *
 * 两处入口改的都是**同一份全局设置**（`AppModel.setPlaybackQuality`）：写盘 + 同步给
 * 播放控制器，对**之后加载**的曲目生效，不打断正在播的这首。
 */

/**
 * 迷你播放器右栏的音质 chip：没有任何底色的胶囊，悬停才浮出一层淡底（M3 Expressive
 * 里次级工具的长相 —— 与 [CpFloatingToolbar] 同一条思路，但更轻）。
 *
 * 点击展开档位菜单。菜单用 `DropdownMenu` 而不是底表：从底栏弹一个整屏宽的面板
 * 去选四个词，视觉上像换了个页面。
 *
 * @param level 当前档位（落盘 level，不是展示名）。
 */
@Composable
fun QualityMenuChip(
    level: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        targetValue = if (hovered || expanded) {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
        } else {
            Color.Transparent
        },
        animationSpec = CpMotion.effectsFast(),
        label = "qualityChipContainer",
    )

    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.height(30.dp).hoverable(interaction),
            shape = CircleShape,
            color = container,
            interactionSource = interaction,
        ) {
            Row(
                modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = s.player.quality,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CpText(
                    text = s.quality.labelOf(level),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppModel.qualityLevels.forEach { candidate ->
                DropdownMenuItem(
                    text = { CpText(s.quality.labelOf(candidate)) },
                    trailingIcon = {
                        if (candidate == level) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (candidate != level) onSelect(candidate)
                    },
                )
            }
        }
    }
}

/**
 * 「更多」弹层里的音质卡片：标题 + 一行说明 + 四个档位胶囊。
 *
 * 用 [CpToggleChip]（选中时形状本身从圆角方形变成胶囊）而不是一排 `RadioButton`：
 * 这是 Expressive 里「选项组」的标准长相，也和设置页的 Segmented / 下拉保持同一族语汇。
 */
@Composable
fun QualitySectionCard(
    level: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    CpText(
                        text = s.player.quality,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    CpText(
                        text = s.player.qualityGlobalNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppModel.qualityLevels.forEach { candidate ->
                    CpToggleChip(
                        checked = candidate == level,
                        onCheckedChange = { checked ->
                            // ToggleButton 语义上可以被「取消选中」，但音质是**必选其一**的
                            // 单选组：只在勾上时提交，取消动作直接忽略（否则会出现「没有音质」）。
                            if (checked && candidate != level) onSelect(candidate)
                        },
                        label = s.quality.labelOf(candidate),
                    )
                }
            }
        }
    }
}
