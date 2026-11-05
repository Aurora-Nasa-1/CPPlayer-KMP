package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.util.UiEvents
import cp.player.core.BackendResult
import cp.player.core.music.TrackSummary
import cp.player.core.util.currentTimeMillis
import cp.player.core.util.localDateTimeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 每日推荐一键转歌单 / 收藏对话框。
 *
 * 自动以「每日推荐 · YYYY-MM-DD」作为预填名称，支持编辑，
 * 确认后批量将当日每日推荐歌曲转存为新歌单。
 */
@Composable
fun SaveDailyToPlaylistDialog(
    tracks: List<TrackSummary>,
    isAlreadySaved: Boolean = false,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val s = cpStrings()
    val now = remember { localDateTimeOf(currentTimeMillis()) }
    val defaultName = remember(now) {
        val m = now.month.toString().padStart(2, '0')
        val d = now.day.toString().padStart(2, '0')
        "每日推荐 · ${now.year}-$m-$d"
    }

    var name by remember { mutableStateOf(defaultName) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.BookmarkAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                CpText(
                    s.playlistSheet.saveDailyToPlaylistTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CpText(
                    s.playlistSheet.saveDailyToPlaylistNote(tracks.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { CpText(s.playlistSheet.playlistNamePlaceholder) },
                    singleLine = true,
                    enabled = !busy,
                    shape = MaterialTheme.shapes.large,
                )

                if (isAlreadySaved) {
                    CpText(
                        s.playlistSheet.saveDailyToPlaylistAlreadySaved,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && !busy && tracks.isNotEmpty(),
                shape = MaterialTheme.shapes.large,
                onClick = {
                    val playlistName = name.trim()
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val active = AppModel.activeProvider()
                        if (active != null) {
                            val createResult = runCatching {
                                AppModel.musicRepository.createPlaylistResource(playlistName)
                            }.getOrNull()

                            val newId = (createResult as? BackendResult.Success)?.data
                            if (newId != null) {
                                val rawIds = tracks.map {
                                    cp.player.core.music.CPMediaId.toRawResourceId(it.id)
                                }.filter { it.isNotBlank() }.distinct()

                                var allOk = true
                                val chunks = rawIds.chunked(100)
                                for (chunk in chunks) {
                                    val ok = runCatching {
                                        AppModel.musicRepository.addTracksToPlaylist(newId, chunk)
                                    }.getOrDefault(false)
                                    if (!ok) {
                                        allOk = false
                                        break
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    busy = false
                                    if (allOk) {
                                        UiEvents.notify(s.playlistSheet.saveDailyToPlaylistSuccess(playlistName, rawIds.size))
                                    } else {
                                        UiEvents.notify("歌单「$playlistName」已创建，但部分歌曲添加失败")
                                    }
                                    onSaved(newId)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    busy = false
                                    val msg = (createResult as? BackendResult.Error)?.message
                                        ?: s.playlistSheet.saveDailyToPlaylistFailed
                                    UiEvents.notify(msg)
                                }
                            }
                        } else {
                            val pl = AppModel.localMedia.playlists.createPlaylist(playlistName)
                            withContext(Dispatchers.Main) {
                                busy = false
                                UiEvents.notify(s.playlistSheet.saveDailyToPlaylistSuccess(playlistName, tracks.size))
                                onSaved(pl.id)
                            }
                        }
                    }
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CpLoadingIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    CpText(if (busy) s.playlistSheet.creating else s.playlistSheet.saveDailyToPlaylistConfirm)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                shape = MaterialTheme.shapes.large,
            ) {
                CpText(s.playlistSheet.cancel)
            }
        },
    )
}
