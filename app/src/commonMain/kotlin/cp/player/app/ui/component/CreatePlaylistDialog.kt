package cp.player.app.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cp.player.app.AppModel
import cp.player.app.ui.util.UiEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 新建歌单对话框。
 *
 * 创建成功后 [onCreated] 回传新歌单 ID（失败回传 null 并全局提示）。
 */
@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreated: (Long?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("歌单名称") },
                singleLine = true,
                enabled = !busy,
                shape = RoundedCornerShape(percent = 50),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    val playlistName = name.trim()
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val newId = runCatching {
                            val json = AppModel.api.createPlaylist(playlistName)
                            val root = json as? JsonObject
                            ((root?.get("id") ?: (root?.get("playlist") as? JsonObject)?.get("id"))
                                    as? JsonPrimitive)?.longOrNull
                        }.getOrNull()
                        withContext(Dispatchers.Main) {
                            busy = false
                            if (newId != null) {
                                UiEvents.notify("已创建「$playlistName」")
                            } else {
                                UiEvents.notify("创建歌单失败")
                            }
                            onCreated(newId)
                        }
                    }
                },
            ) { Text(if (busy) "创建中…" else "创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
