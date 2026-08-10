package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.extractUidFromLoginStatus
import cp.player.app.ui.util.UiEvents
import cp.player.kmp.BackendResult
import cp.player.kmp.music.MusicSourceFromApi
import cp.player.kmp.music.PlaylistSummary
import cp.player.kmp.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 弹层内的歌单行（1:1 移植旧项目 `PlaylistItem` 弹层样式）。
 *
 * 56dp 封面（12dp 圆角）+ 歌单名 + "创建者 · N 首" 副标题，整行可点击。
 */
@Composable
fun PlaylistPickerRow(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!playlist.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = playlist.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    if (!playlist.creatorName.isNullOrBlank()) append(playlist.creatorName)
                    if (playlist.trackCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${playlist.trackCount} 首")
                    }
                }
                Text(
                    subtitle.ifEmpty { "歌单" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 拉取当前用户"自己创建"的歌单（对应旧项目 `!subscribed` 过滤）。 */
internal suspend fun fetchOwnedPlaylists(): List<PlaylistSummary> {
    val uid = extractUidFromLoginStatus(AppModel.api.getLoginStatus())
    if (uid == null) {
        withContext(Dispatchers.Main) { UiEvents.notify("未登录或登录已过期") }
        return emptyList()
    }
    val all = (MusicSourceFromApi.parseUserPlaylists(AppModel.api.getUserPlaylists(uid))
            as? BackendResult.Success)?.data.orEmpty()
    val nickname = AppModel.userProfileFlow.value?.nickname
    return all.filter { nickname != null && it.creatorName == nickname }
}

/**
 * 歌单选择器弹层（移植旧项目 `AddToPlaylistBottomSheet`）。
 *
 * 展示当前账号创建的歌单列表供点选；[excludePlaylistId] 用于排除当前歌单
 * （"从歌单导入"场景），[title] 自定义标题（"添加到歌单" / "从歌单导入"）。
 */
@Composable
fun PlaylistPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onSelected: (PlaylistSummary) -> Unit,
    excludePlaylistId: Long? = null,
) {
    var playlists by remember { mutableStateOf<List<PlaylistSummary>?>(null) }

    LaunchedEffect(Unit) {
        playlists = withContext(Dispatchers.IO) {
            runCatching { fetchOwnedPlaylists() }.getOrDefault(emptyList())
        }
    }

    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            when {
                playlists == null -> Box(
                    Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                playlists!!.isEmpty() -> Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无可用歌单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(
                        playlists!!.filter { it.id != excludePlaylistId },
                        key = { _, p -> p.id },
                    ) { _, playlist ->
                        PlaylistPickerRow(
                            playlist = playlist,
                            onClick = { onSelected(playlist) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * "添加歌曲"来源选项弹层（移植旧项目歌单详情页的添加来源选择）。
 *
 * 两个大按钮：从歌单导入（primaryContainer）/ 从播放队列添加（secondaryContainer）。
 */
@Composable
fun AddSongsOptionsSheet(
    onDismiss: () -> Unit,
    onImportFromPlaylist: () -> Unit,
    onAddFromQueue: () -> Unit,
) {
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "添加歌曲",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // 从其他歌单导入
            Surface(
                onClick = onImportFromPlaylist,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "从歌单导入",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            // 从当前播放队列添加
            Surface(
                onClick = onAddFromQueue,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic, null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "从播放队列添加",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * 来源歌曲多选弹层（移植旧项目 `SourceSongsSelectionBottomSheet`）。
 *
 * 标题"选择歌曲 · 来自 {sourceName}"，支持全选/取消全选；
 * 歌曲由 [initialSongs] 直接提供（如播放队列）或通过 [fetchSongs] 异步拉取（如歌单）。
 * 选中后底部出现"添加 N 首歌曲"按钮，回调 [onAddSelected] 返回所选曲目。
 */
@Composable
fun SourceSongsSelectionSheet(
    sourceName: String,
    onDismiss: () -> Unit,
    onAddSelected: (List<TrackSummary>) -> Unit,
    initialSongs: List<TrackSummary>? = null,
    fetchSongs: (suspend () -> List<TrackSummary>)? = null,
) {
    var songs by remember { mutableStateOf<List<TrackSummary>?>(initialSongs) }
    var isLoading by remember { mutableStateOf(initialSongs == null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        if (initialSongs == null && fetchSongs != null) {
            isLoading = true
            try {
                songs = fetchSongs()
            } catch (_: Exception) {
                songs = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "选择歌曲",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "来自 $sourceName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (songs?.isNotEmpty() == true) {
                    val allSelected = selectedIds.size == songs!!.size
                    TextButton(onClick = {
                        selectedIds = if (allSelected) emptySet()
                        else songs!!.mapTo(LinkedHashSet()) { it.id }
                    }) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                }
            }

            when {
                isLoading -> Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                songs.isNullOrEmpty() -> Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "未找到歌曲",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(songs!!, key = { _, s -> s.id }) { index, track ->
                        val isSelected = track.id in selectedIds
                        SongItem(
                            track = track,
                            index = index,
                            total = songs!!.size,
                            selectionMode = true,
                            isSelected = isSelected,
                            onClick = {
                                selectedIds = if (isSelected) selectedIds - track.id
                                else selectedIds + track.id
                            },
                        )
                    }
                }
            }

            if (selectedIds.isNotEmpty()) {
                Surface(
                    onClick = {
                        val picked = songs.orEmpty().filter { it.id in selectedIds }
                        onAddSelected(picked)
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).height(52.dp),
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "添加 ${selectedIds.size} 首歌曲",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
