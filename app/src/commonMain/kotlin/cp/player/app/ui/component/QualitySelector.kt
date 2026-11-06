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
import androidx.compose.runtime.collectAsState
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
 * 现已支持当前音源自定义音质选项（由 [AppModel.currentQualityOptions] 提供）。
 * 用户选中的是具体音源档位，底层映射并持久化其原生 targetQuality（如 "hires"），
 * 在切换音源时由系统自动寻找对应映射档位或平滑降级。
 */

/**
 * 迷你播放器右栏的音质 chip。
 *
 * @param level 当前档位（原生 targetQuality 标识，如 standard / exhigh / lossless / hires）。
 */
@Composable
fun QualityMenuChip(
    level: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val gen by AppModel.sourceGeneration.collectAsState()
    val options = remember(gen) { AppModel.currentQualityOptions() }
    val activeOption = options.firstOrNull { it.targetQuality == level }
    val displayLabel = activeOption?.customLabel ?: s.quality.labelOf(level)

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
                    text = displayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { candidate ->
                val optionLabel = candidate.customLabel ?: s.quality.labelOf(candidate.targetQuality)
                val isSelected = candidate.targetQuality == level
                DropdownMenuItem(
                    text = { CpText(optionLabel) },
                    trailingIcon = {
                        if (isSelected) {
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
                        if (!isSelected) onSelect(candidate.targetQuality)
                    },
                )
            }
        }
    }
}

/**
 * 「更多」弹层里的音质卡片：标题 + 一行说明 + 档位胶囊。
 */
@Composable
fun QualitySectionCard(
    level: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val gen by AppModel.sourceGeneration.collectAsState()
    val options = remember(gen) { AppModel.currentQualityOptions() }

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
                options.forEach { candidate ->
                    val isChecked = candidate.targetQuality == level
                    val optionLabel = candidate.customLabel ?: s.quality.labelOf(candidate.targetQuality)
                    CpToggleChip(
                        checked = isChecked,
                        onCheckedChange = { checked ->
                            if (checked && !isChecked) onSelect(candidate.targetQuality)
                        },
                        label = optionLabel,
                    )
                }
            }
        }
    }
}
