package cp.player.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.MessageNotifyStrings

/**
 * Compose 自绘制的托盘右键菜单卡片界面。
 */
@Composable
internal fun TrayMenuContent(
    title: String,
    subtitle: String?,
    isPlaying: Boolean,
    hasTrack: Boolean,
    isMiniMode: Boolean,
    strings: MessageNotifyStrings,
    onAction: (TrayMenuAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(TrayMenuLayout.CornerRadius.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 1. 曲目信息区
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CpText(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (subtitle == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    CpText(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 2. 媒体控制按钮区
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrayControlButton(
                    icon = Icons.Filled.SkipPrevious,
                    enabled = hasTrack,
                    onClick = { onAction(TrayMenuAction.PREVIOUS) },
                )
                TrayControlButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    enabled = hasTrack,
                    filled = true,
                    onClick = { onAction(TrayMenuAction.PLAY_PAUSE) },
                )
                TrayControlButton(
                    icon = Icons.Filled.SkipNext,
                    enabled = hasTrack,
                    onClick = { onAction(TrayMenuAction.NEXT) },
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            // 3. 菜单项：显示主界面 & 迷你模式
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TrayMenuItem(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    label = strings.trayShowWindow,
                    onClick = { onAction(TrayMenuAction.SHOW_WINDOW) },
                )
                TrayMenuItem(
                    icon = Icons.Outlined.PictureInPictureAlt,
                    label = strings.trayMiniMode,
                    selected = isMiniMode,
                    onClick = { onAction(TrayMenuAction.TOGGLE_MINI_MODE) },
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            // 4. 菜单项：退出
            TrayMenuItem(
                icon = Icons.Outlined.PowerSettingsNew,
                label = strings.trayExit,
                danger = true,
                onClick = { onAction(TrayMenuAction.EXIT) },
            )
        }
    }
}

@Composable
private fun TrayControlButton(
    icon: ImageVector,
    enabled: Boolean,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
        filled && isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        filled -> MaterialTheme.colorScheme.primary
        isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(if (filled) 18.dp else 16.dp),
        )
    }
}

@Composable
private fun TrayMenuItem(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val contentColor = when {
        danger -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val backgroundColor = when {
        danger && isHovered -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        selected && isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        CpText(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
