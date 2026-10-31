package cp.player.app.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.util.UiEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 重命名歌单对话框。
 *
 * 与 [CreatePlaylistDialog] 分开而不是加一个 `mode` 参数：两者的成功路径不同
 * （新建要回传 id，重命名要刷新列表），塞进一个组件只会让调用点更难读。
 * 输入框预填当前名称 —— 改名大多是「在原名上加一个字」，从空框开始输反而更慢。
 */
@Composable
fun RenamePlaylistDialog(
    playlistId: String,
    currentName: String,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit,
) {
    val s = cpStrings()
    var name by remember(currentName) { mutableStateOf(currentName) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val trimmed = name.trim()
    val canSubmit = trimmed.isNotEmpty() && trimmed != currentName && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = { CpText(s.library.renamePlaylist, style = MaterialTheme.typography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { CpText(s.library.renamePlaylistPlaceholder) },
                singleLine = true,
                enabled = !busy,
                shape = MaterialTheme.shapes.large,
            )
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                shape = MaterialTheme.shapes.large,
                onClick = {
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                AppModel.musicRepository.renamePlaylist(playlistId, trimmed)
                            }.getOrDefault(false)
                        }
                        busy = false
                        if (ok) {
                            UiEvents.notify(s.library.renamedPlaylist(trimmed))
                            onRenamed()
                        } else {
                            UiEvents.notify(s.library.renamePlaylistFailed)
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
                    CpText(s.common.confirm)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { CpText(s.playlistSheet.cancel) }
        },
    )
}
