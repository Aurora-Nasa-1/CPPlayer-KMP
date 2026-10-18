package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.LyricOffsetManager
import cp.player.core.music.TrackSummary

/**
 * 歌词时间轴微调弹窗。
 *
 * 允许用户针对当前曲目手动调节歌词时间轴提前或滞后的时间差（毫秒）。
 * 每次调节即时生效并持久化记忆在该曲目专属的设置项中。
 */
@Composable
fun LyricOffsetDialog(
    track: TrackSummary,
    onDismiss: () -> Unit,
) {
    val s = cpStrings()
    val isDark = LocalIsDarkTheme.current
    var offsetMs by remember(track.id) {
        mutableLongStateOf(LyricOffsetManager.getOffset(track.id))
    }

    val updateOffset: (Long) -> Unit = { newOffset ->
        offsetMs = newOffset
        LyricOffsetManager.setOffset(track.id, newOffset)
    }

    val adjustBy: (Long) -> Unit = { delta ->
        val updated = offsetMs + delta
        updateOffset(updated)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Lyrics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = s.player.lyricOffsetTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                CpText(
                    text = "${track.name} - ${track.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 核心数字徽章展示
                val badgeContainerColor by animateColorAsState(
                    targetValue = when {
                        offsetMs > 0 -> MaterialTheme.colorScheme.primaryContainer
                        offsetMs < 0 -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    animationSpec = CpMotion.effects(),
                    label = "offsetBadgeBg",
                )
                val badgeContentColor by animateColorAsState(
                    targetValue = when {
                        offsetMs > 0 -> MaterialTheme.colorScheme.onPrimaryContainer
                        offsetMs < 0 -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = CpMotion.effects(),
                    label = "offsetBadgeContent",
                )

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = badgeContainerColor,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.15f else 0.3f),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp),
                    ) {
                        val offsetSeconds = offsetMs / 1000f
                        val sign = if (offsetSeconds > 0) "+" else ""
                        Text(
                            text = "$sign${offsetSeconds}s",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeContentColor,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = s.player.lyricOffsetSubtitle(offsetMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeContentColor.copy(alpha = 0.85f),
                        )
                    }
                }

                // 微调快捷按钮组（-0.5s / +0.5s 及 -0.1s / +0.1s）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 大步调（0.5s）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { adjustBy(-500L) },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.FastRewind, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("-0.5s")
                        }
                        FilledTonalButton(
                            onClick = { adjustBy(500L) },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("+0.5s")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.FastForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }

                    // 微步调（0.1s）与重置
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { adjustBy(-100L) },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("-0.1s")
                        }
                        OutlinedButton(
                            onClick = { adjustBy(100L) },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("+0.1s")
                        }
                        if (offsetMs != 0L) {
                            FilledTonalIconButton(
                                onClick = { updateOffset(0L) },
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RestartAlt,
                                    contentDescription = s.player.lyricOffsetReset,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                // 说明提示
                Text(
                    text = s.player.lyricOffsetHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = "完成",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}
