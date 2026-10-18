package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.core.music.CPMediaId
import cp.player.core.playback.QueueItem
import cp.player.app.platform.shareText
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.util.resized
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
    onOpenSimilar: (() -> Unit)? = null,
) {
    // 统一走 LegacyModalBottomSheet 外壳（形状 / 拖拽手柄 / 容器色与其他弹层一致），
    // 列表型弹层跳过半展开档位。
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSaveSheet by remember { mutableStateOf(false) }
    // 「清空队列」的二次确认：一键即生效的话，误触在底部工具行里太容易发生，
    // 而重建一份刚才的队列没有任何后悔药。
    val confirm = rememberConfirmState()
    val s = cpStrings()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // 保留原始下标，确保点击播放或移除时操作的是正确的队列项
    val indexedQueue = remember(queue) { queue.mapIndexed { index, item -> index to item } }
    val filteredQueue = remember(indexedQueue, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) indexedQueue
        else indexedQueue.filter { (_, item) ->
            item.title.contains(q, ignoreCase = true) ||
            item.artist.contains(q, ignoreCase = true)
        }
    }

    LegacyModalBottomSheet(onDismissRequest = onClose, skipPartiallyExpanded = true) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            // ⚠️ 此前这里是硬编码的英文 "Next up" —— 中文界面也显示英文，
                            // 属于漏译（不是有意的语言自称，那只适用于语言名）。
                            s.player.queueUpNext,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (searchQuery.isNotBlank()) "${filteredQueue.size} / ${queue.size}" else s.player.queueCount(queue.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (queue.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) searchQuery = ""
                            },
                        ) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Filled.Close else androidx.compose.material.icons.Icons.Filled.Search,
                                contentDescription = s.player.searchQueue,
                                tint = if (isSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (isSearchActive) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                singleLine = true,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = s.player.searchQueue,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                    }
                                    innerTextField()
                                },
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyScrollColumn(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(filteredQueue, key = { _, (_, it) -> it.mediaId }) { _, (originalIndex, item) ->
                        val isCurrent = originalIndex == currentIndex
                        QueueRow(
                            item = item,
                            isCurrent = isCurrent,
                            index = originalIndex,
                            queueSize = queue.size,
                            onPlay = { onPlayAt(originalIndex) },
                            onRemove = { onRemove(originalIndex) },
                            onMove = onMove,
                            // 桌面端右键菜单：动作与行内 More 菜单一致并补一个分享入口。
                            contextMenu = buildList {
                                add(CpContextMenuItem(s.player.play, Icons.Filled.PlayArrow, onClick = { onPlayAt(originalIndex) }))
                                add(
                                    CpContextMenuItem(
                                        s.player.removeFromQueue,
                                        Icons.Filled.Close,
                                        onClick = { onRemove(originalIndex) },
                                        danger = true,
                                    ),
                                )
                                add(CpContextMenuItem(s.player.share, Icons.Filled.Share, onClick = {
                                    val rid = runCatching { CPMediaId.parse(item.mediaId).resourceId }.getOrDefault(item.mediaId)
                                    shareText("「${item.title}」 https://music.163.com/#/song?id=$rid")
                                }))
                            },
                            // 拖拽重排 / 移除时的位移与淡出。队列是全应用唯一能真正改变
                            // 顺序的列表，没有这层动画的话每次重排都是一次「集体瞬移」。
                            modifier = Modifier.animateItem(),
                        )
                    }

                    if (onOpenSimilar != null) {
                        item {
                            androidx.compose.material3.Surface(
                                onClick = onOpenSimilar,
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "探索相似歌曲",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            "基于当前播放队列与在播曲目探索更多",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // 底部工具行：居中浮动胶囊条，与播放页视觉语言统一
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CpFloatingToolbar {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (currentIndex in queue.indices) listState.animateScrollToItem(currentIndex)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.LocationSearching,
                                contentDescription = s.player.locateCurrent,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { showSaveSheet = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = s.player.saveAsPlaylist,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            onClick = {
                                if (queue.isEmpty()) return@IconButton
                                confirm.request(
                                    title = s.player.queueClearTitle,
                                    message = s.player.queueClearMessage(queue.size),
                                    confirmLabel = s.player.clearQueue,
                                    onConfirm = onClear,
                                )
                            },
                            enabled = queue.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = s.player.clearQueue,
                                tint = if (queue.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        }
                    }
                }
            }
        }
    }

    // 「另存为歌单」：新建歌单与「追加到已有歌单」是同一个动作的两种落点，
    // 所以直接复用 AddToPlaylistSheet（顶部输入框 = 新建，下面列表 = 追加），
    // 而不是先弹一个只能新建的对话框、再把「加到已有歌单」藏到别处。
    // 队列里的本地条目会在该弹层里被过滤（本地路径不是音源侧的歌曲 id）。
    if (showSaveSheet) {
        AddToPlaylistSheet(
            trackIds = queue.map { it.mediaId },
            onDismiss = { showSaveSheet = false },
            title = s.player.saveAsPlaylist,
        )
    }

    CpConfirmHost(confirm)
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    index: Int,
    queueSize: Int,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 桌面端右键菜单项；null 时不启用。 */
    contextMenu: List<CpContextMenuItem>? = null,
) {
    val isDark = LocalIsDarkTheme.current
    val targetBg = if (isCurrent) {
        if (isDark) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = CpMotion.effects(),
        label = "queueRowBgAnim",
    )
    val rowCorner by animateDpAsState(
        targetValue = if (isCurrent) 24.dp else 16.dp,
        animationSpec = CpMotion.spatial(),
        label = "queueRowCornerMorph",
    )
    val coverCorner by animateDpAsState(
        targetValue = if (isCurrent) 24.dp else 12.dp,
        animationSpec = CpMotion.spatial(),
        label = "queueCoverCornerMorph",
    )
    val coverShape = remember(coverCorner) { RoundedCornerShape(coverCorner) }
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = CpMotion.effects(),
        label = "queueTitleColorAnim",
    )
    var showMenu by remember { mutableStateOf(false) }
    val s = cpStrings()
    val density = LocalDensity.current
    var dragAccum by remember { mutableStateOf(0f) }

    // 拖拽重排用「行自己的当前下标」而不是闭包里的 i：pointerInput(Unit) 的 lambda
    // 只在首组合捕获一次，行被 key(mediaId) 复用换位后 i 是旧值——继续 onMove(i, i±1)
    // 会把别的歌换回来，表现为「无论划多远只换一次相邻位置」。
    // 拖拽期间 pos 由拖拽逻辑本地同步推进（不依赖重组到账），手势结束后与列表对齐。
    val latestMove by rememberUpdatedState(onMove)
    val latestQueueSize by rememberUpdatedState(queueSize)
    var pos by remember { mutableStateOf(index) }
    var dragging by remember { mutableStateOf(false) }
    SideEffect { if (!dragging) pos = index }

    // 右键菜单包在拖拽手柄所在整行之外：光标落在行上任意位置都能弹出，
    // 不与 DragIndicator 的长按拖拽冲突（那只监听左键长按）。
    CpContextMenu(items = contextMenu, modifier = modifier) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = remember(rowCorner) { RoundedCornerShape(rowCorner) },
        color = animatedBg,
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.DragIndicator,
                s.player.queueReorderHint,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragAccum = 0f; dragging = true },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragAccum += dragAmount.y
                                val threshold = with(density) { 72.dp.toPx() }
                                // 每跨过一个行距就真实移动一位：pos 本地即时推进，
                                // 一次手势滑过 N 行就连续 onMove N 次（动画逐格跟手）。
                                while (dragAccum > threshold) {
                                    val target = pos + 1
                                    if (target >= latestQueueSize) { dragAccum = 0f; break }
                                    latestMove(pos, target)
                                    pos = target
                                    dragAccum -= threshold
                                }
                                while (dragAccum < -threshold) {
                                    val target = pos - 1
                                    if (target < 0) { dragAccum = 0f; break }
                                    latestMove(pos, target)
                                    pos = target
                                    dragAccum += threshold
                                }
                            },
                            onDragEnd = { dragAccum = 0f; dragging = false },
                            onDragCancel = { dragAccum = 0f; dragging = false },
                        )
                    },
            )
            Spacer(Modifier.width(4.dp))
            Row(
                Modifier.weight(1f).cpBounceClickable { onPlay() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp)) {
                    if (!item.coverUrl.isNullOrBlank()) {
                        // [Bolt] Fetch smaller (150px) cover images for the queue list to reduce loading time
                        AsyncImage(
                            model = item.coverUrl.resized(150),
                            contentDescription = s.player.coverContentDescription,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                                .clip(coverShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    } else {
                        CpCoverPlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            corner = coverCorner,
                            animated = false,
                        )
                    }
                    if (isCurrent) {
                        CpPlayingEqualizer(
                            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    CpText(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = titleColor,
                        maxLines = 1,
                    )
                    CpText(
                        item.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, s.player.more)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(s.player.removeFromQueue) },
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
}