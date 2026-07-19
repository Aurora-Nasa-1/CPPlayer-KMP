package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.kmp.playback.QueueItem
import kotlinx.coroutines.launch

/**
 * 队列抽屉（KMP 等效原项目 `QueueBottomSheet`）。
 *
 * ModalBottomSheet 内含 LazyList：每行显示封面/标题/歌手，点击播放该位置；
 * 右侧 More 按钮可从队列移除；长按 Drag handle 拖拽重排（[onMove]）。
 * 底部工具行：定位当前 / 保存为歌单 / 清空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    queue: List<QueueItem>,
    currentIndex: Int,
    onPlayAt: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSaveDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Next up",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${queue.size} 首 · 接下来播放",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(queue, key = { _, it -> it.mediaId }) { i, item ->
                        val isCurrent = i == currentIndex
                        QueueRow(
                            item = item,
                            isCurrent = isCurrent,
                            onPlay = { onPlayAt(i) },
                            onRemove = { onRemove(i) },
                            onDragBy = { dir ->
                                val target = i + dir
                                if (target in queue.indices) onMove(i, target)
                            },
                        )
                    }
                }

                // 底部工具行
                Box(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {
                                scope.launch {
                                    if (currentIndex in queue.indices) listState.animateScrollToItem(currentIndex)
                                }
                            }) {
                                Icon(Icons.Filled.LocationSearching, "定位当前")
                            }
                            IconButton(onClick = { showSaveDialog = true }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "保存为歌单")
                            }
                            IconButton(onClick = onClear) {
                                Icon(Icons.Filled.DeleteSweep, "清空队列")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        CreatePlaylistDialog(
            onDismiss = { showSaveDialog = false },
            onCreated = { newId ->
                showSaveDialog = false
                if (newId != null) {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val ids = queue.mapNotNull {
                            runCatching { cp.player.kmp.music.CPMediaId.parse(it.mediaId).resourceId }.getOrNull()
                        }.filter { it.isNotBlank() }
                        val ok = ids.isNotEmpty() && runCatching {
                            cp.player.app.AppModel.api.addTracksToPlaylist(newId, ids)
                        }.isSuccess
                        if (ok) cp.player.app.ui.util.UiEvents.notify("队列已保存为歌单")
                        else cp.player.app.ui.util.UiEvents.notify("保存歌单失败")
                    }
                }
            },
        )
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onDragBy: (Int) -> Unit,
) {
    val bg = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    var showMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var dragAccum by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }

    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = bg,
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.DragIndicator,
                "长按拖拽重排",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragAccum = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragAccum += dragAmount.y
                                val threshold = with(density) { 72.dp.toPx() }
                                while (dragAccum > threshold) { onDragBy(1); dragAccum -= threshold }
                                while (dragAccum < -threshold) { onDragBy(-1); dragAccum += threshold }
                            },
                            onDragEnd = { dragAccum = 0f },
                            onDragCancel = { dragAccum = 0f },
                        )
                    },
            )
            Spacer(Modifier.width(4.dp))
            Row(
                Modifier.weight(1f).clickable { onPlay() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!item.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                } else {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote, null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, "更多")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("从队列移除") },
                        onClick = {
                            showMenu = false
                            onRemove()
                        },
                    )
                }
            }
            if (isCurrent) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.width(4.dp).height(32.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}