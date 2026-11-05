package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.cpStrings
import cp.player.core.playback.AutoPlayCustomType
import cp.player.core.playback.PlaybackUiState

/**
 * 从用户输入的文本或分享链接中提取资源 ID（歌单 ID 或单曲 ID）。
 */
fun extractAutoPlayTargetId(input: String): String {
    val trimmed = input.trim()
    val idParamMatch = Regex("""[?&]id=([0-9a-zA-Z_-]+)""").find(trimmed)
    if (idParamMatch != null) return idParamMatch.groupValues[1]
    val pathMatch = Regex("""(?:playlist|song)/([0-9a-zA-Z_-]+)""").find(trimmed)
    if (pathMatch != null) return pathMatch.groupValues[1]
    return trimmed
}

/**
 * 自动播放指定目标（歌单或单曲）配置弹窗。
 */
@Composable
fun AutoPlayCustomTargetDialog(
    currentType: AutoPlayCustomType,
    currentId: String,
    currentTitle: String,
    playbackState: PlaybackUiState,
    onDismiss: () -> Unit,
    onPickFromPlaylists: () -> Unit,
    onSetTarget: (AutoPlayCustomType, String, String) -> Unit,
    onClearTarget: () -> Unit,
) {
    val s = cpStrings()
    var selectedType by remember { mutableStateOf(currentType) }
    var inputId by remember { mutableStateOf(currentId) }
    var inputTitle by remember { mutableStateOf(currentTitle) }

    val currentTrack = playbackState.currentTrack
    val currentSourceId = playbackState.sourceId?.takeIf { it.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            CpText(
                s.playback.autoPlayCustomDialogTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 快捷选项：从我的歌单选择
                OutlinedButton(
                    onClick = onPickFromPlaylists,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    CpText(s.playback.autoPlayPickFromPlaylists)
                }

                // 快捷选项：使用当前正在播放的单曲
                if (currentTrack != null) {
                    val trackTitle = "${currentTrack.name} - ${currentTrack.artist}"
                    Surface(
                        onClick = {
                            onSetTarget(AutoPlayCustomType.TRACK, currentTrack.id, trackTitle)
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                CpText(
                                    s.playback.autoPlayUseCurrentSong,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                CpText(
                                    trackTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                // 快捷选项：使用当前正在播放的歌单
                if (currentSourceId != null) {
                    Surface(
                        onClick = {
                            onSetTarget(
                                AutoPlayCustomType.PLAYLIST,
                                currentSourceId,
                                "歌单 $currentSourceId",
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                CpText(
                                    s.playback.autoPlayUseCurrentPlaylist,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                                CpText(
                                    "ID: $currentSourceId",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                CpText(
                    s.playback.autoPlayManualInput,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 类型切换：歌单 / 单曲
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = selectedType == AutoPlayCustomType.PLAYLIST,
                        onClick = { selectedType = AutoPlayCustomType.PLAYLIST },
                        label = { CpText(s.playback.autoPlayTypePlaylist) },
                    )
                    FilterChip(
                        selected = selectedType == AutoPlayCustomType.TRACK,
                        onClick = { selectedType = AutoPlayCustomType.TRACK },
                        label = { CpText(s.playback.autoPlayTypeTrack) },
                    )
                }

                // ID 或 链接输入框
                OutlinedTextField(
                    value = inputId,
                    onValueChange = { inputId = it },
                    label = { CpText(s.playback.autoPlayInputIdPlaceholder) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 名称输入框（可选）
                OutlinedTextField(
                    value = inputTitle,
                    onValueChange = { inputTitle = it },
                    label = { CpText(s.playback.autoPlayInputTitlePlaceholder) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalId = extractAutoPlayTargetId(inputId)
                    if (finalId.isNotBlank()) {
                        onSetTarget(selectedType, finalId, inputTitle.trim())
                    }
                },
                enabled = extractAutoPlayTargetId(inputId).isNotBlank(),
                shape = MaterialTheme.shapes.large,
            ) {
                CpText(s.common.confirm)
            }
        },
        dismissButton = {
            Row {
                if (currentId.isNotBlank()) {
                    TextButton(onClick = onClearTarget) {
                        CpText(
                            s.playback.autoPlayClearTarget,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    CpText(s.common.dismiss)
                }
            }
        },
    )
}
