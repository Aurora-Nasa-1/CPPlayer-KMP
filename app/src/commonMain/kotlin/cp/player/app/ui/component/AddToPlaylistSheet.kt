package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cp.player.app.ui.util.UiEvents
import cp.player.kmp.music.PlaylistSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * "添加到歌单"底部弹层（1:1 移植旧项目 `AddToPlaylistBottomSheet` 界面）。
 *
 * 展示当前账号创建的歌单列表（封面行样式），点击即把 [trackIds] 加入对应歌单；
 * 顶部支持输入名称一键新建歌单并加入。结果通过 [UiEvents] 全局提示反馈。
 */
@Composable
fun AddToPlaylistSheet(
    trackIds: List<String>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<PlaylistSummary>?>(null) }
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        playlists = withContext(Dispatchers.IO) {
            runCatching { fetchOwnedPlaylists() }.getOrDefault(emptyList())
        }
    }

    fun extractRawId(fullId: String): String {
        return runCatching { cp.player.kmp.music.CPMediaId.parse(fullId).resourceId }.getOrDefault(fullId)
    }

    fun addTo(playlistId: Long) {
        if (busy) return
        busy = true
        val ids = trackIds.map(::extractRawId)
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                AppModel.api.addTracksToPlaylist(playlistId, ids)
            }.isSuccess
            withContext(Dispatchers.Main) {
                busy = false
                if (ok) {
                    UiEvents.notify("已添加 ${ids.size} 首歌曲")
                    onDismiss()
                } else {
                    UiEvents.notify("加入歌单失败")
                }
            }
        }
    }

    fun createAndAdd() {
        val name = newName.trim()
        if (name.isEmpty() || busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val newId = runCatching {
                val json = AppModel.api.createPlaylist(name)
                val root = json as? JsonObject
                ((root?.get("id") ?: (root?.get("playlist") as? JsonObject)?.get("id"))
                        as? JsonPrimitive)?.longOrNull
            }.getOrNull()
            withContext(Dispatchers.Main) {
                busy = false
                if (newId != null) {
                    addTo(newId)
                } else {
                    UiEvents.notify("新建歌单失败")
                }
            }
        }
    }

    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "添加到歌单",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            // 新建歌单行
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("新建歌单名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(percent = 50),
                )
                TextButton(onClick = { createAndAdd() }, enabled = newName.isNotBlank() && !busy) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("新建")
                }
            }

            when {
                playlists == null -> Row(
                    Modifier.fillMaxWidth().height(96.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { CircularProgressIndicator() }
                playlists!!.isEmpty() -> Text(
                    "还没有歌单，先在上方新建一个吧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(playlists!!, key = { _, p -> p.id }) { _, playlist ->
                        PlaylistPickerRow(
                            playlist = playlist,
                            onClick = { addTo(playlist.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 单歌曲便捷重载，保持既有调用点兼容。 */
@Composable
fun AddToPlaylistSheet(
    trackId: String,
    onDismiss: () -> Unit,
) {
    AddToPlaylistSheet(trackIds = listOf(trackId), onDismiss = onDismiss)
}
