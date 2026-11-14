package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.LocationSearching
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.shareText
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.resized
import cp.player.core.music.CPMediaId
import cp.player.core.music.TrackSummary
import cp.player.core.playback.QueueItem
import cp.player.core.playback.RepeatMode
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
    shuffleEnabled: Boolean = false,
    repeatMode: RepeatMode = RepeatMode.OFF,
    onShuffle: (() -> Unit)? = null,
    onRepeat: (() -> Unit)? = null,
) {
    // 统一走 LegacyModalBottomSheet 外壳（形状 / 拖拽手柄 / 容器色与其他弹层一致），
    // 列表型弹层跳过半展开档位。
    LegacyModalBottomSheet(onDismissRequest = onClose, skipPartiallyExpanded = true) {
        QueueContent(
            queue = queue,
            currentIndex = currentIndex,
            onPlayAt = onPlayAt,
            onRemove = onRemove,
            onMove = onMove,
            onClear = onClear,
            onClose = onClose,
            onOpenSimilar = onOpenSimilar,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            onShuffle = onShuffle,
            onRepeat = onRepeat,
        )
    }
}

/**
 * 播放队列内容本体：提供可复用的列表、搜索、定位与清空交互。
 *
 * 在移动端由 [QueueBottomSheet] 作为底部抽屉承载；
 * 在桌面宽屏播放页中作为悬浮在歌词上方的浮动双栏面板承载。
 */
@Composable
fun QueueContent(
    queue: List<QueueItem>,
    currentIndex: Int,
    onPlayAt: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onClose: (() -> Unit)? = null,
    onOpenSimilar: (() -> Unit)? = null,
    shuffleEnabled: Boolean = false,
    repeatMode: RepeatMode = RepeatMode.OFF,
    onShuffle: (() -> Unit)? = null,
    onRepeat: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSaveSheet by remember { mutableStateOf(false) }
    var showLocalSaveDialog by remember { mutableStateOf(false) }
    var addToPlaylistTarget by remember { mutableStateOf<QueueItem?>(null) }
    var addToLocalPlaylistTarget by remember { mutableStateOf<QueueItem?>(null) }
    var detailTarget by remember { mutableStateOf<QueueItem?>(null) }
    var optionsTarget by remember { mutableStateOf<Pair<Int, QueueItem>?>(null) }
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

    // 为队列项生成稳定且唯一的 key：杜绝重复 key 异常的同时，
    // 坚决不能在 key 中混入当前下标（例如 "$idx:${it.mediaId}"）。
    // 混入下标会导致拖拽换位时 key 立即改变，Compose 误判为旧项销毁+新项插入，
    // 进而强行打断 pointerInput 手势协程，表现为「拖动一格就卡住/无法连续拖拽」。
    val stableKeys = remember(queue) {
        val counts = mutableMapOf<String, Int>()
        queue.map { item ->
            val count = counts.getOrElse(item.mediaId) { 0 }
            counts[item.mediaId] = count + 1
            if (count == 0) item.mediaId else "${item.mediaId}#$count"
        }
    }

    // 拖拽换位与长列表边缘自动滚动联动：拖到视口边缘时自动滚动，支持跨屏长距离重排
    val onMoveAndScroll: (Int, Int) -> Unit = { from, to ->
        onMove(from, to)
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val firstVisible = visibleItems.firstOrNull()?.index ?: 0
        val lastVisible = visibleItems.lastOrNull()?.index ?: 0
        if (to <= firstVisible + 1 && to > 0) {
            scope.launch { listState.animateScrollToItem((to - 1).coerceAtLeast(0)) }
        } else if (to >= lastVisible - 1 && to < queue.size - 1) {
            scope.launch { listState.animateScrollToItem((to + 1).coerceAtMost(queue.lastIndex)) }
        }
    }

    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    CpText(
                        s.player.queueUpNext,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    CpText(
                        if (searchQuery.isNotBlank()) "${filteredQueue.size} / ${queue.size}" else s.player.queueCount(queue.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (queue.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) searchQuery = ""
                            },
                        ) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = s.player.searchQueue,
                                tint = if (isSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                            imageVector = Icons.Rounded.Search,
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
                                    CpText(
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
                                    imageVector = Icons.Rounded.Close,
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
                Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 16.dp),
                state = listState,
                // 工具条是悬浮层：队尾内容必须能滚到它上方，否则最后几首会被永久遮住。
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 空队列不能是**纯空白**：清空后列表区只剩头顶计数，用户会以为弹层没加载完。
                // 与「搜索无命中」区分开：两种状态的下一步动作不同（等加歌 vs 改关键词）。
                if (filteredQueue.isEmpty()) {
                    item {
                        cp.player.app.ui.component.CpText(
                            if (searchQuery.isBlank()) s.player.queueEmptyHint else s.player.queueNoMatchHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                val canDrag = searchQuery.isBlank() && queue.size > 1
                itemsIndexed(
                    filteredQueue,
                    key = { _, (originalIndex, it) -> stableKeys.getOrNull(originalIndex) ?: it.mediaId },
                ) { _, (originalIndex, item) ->
                    val isCurrent = originalIndex == currentIndex
                    QueueRow(
                        item = item,
                        isCurrent = isCurrent,
                        index = originalIndex,
                        queueSize = queue.size,
                        canDrag = canDrag,
                        onPlay = { onPlayAt(originalIndex) },
                        onRemove = { onRemove(originalIndex) },
                        onOptions = { optionsTarget = originalIndex to item },
                        onMove = onMoveAndScroll,
                        // 桌面端右键菜单：动作与行内 More 菜单一致并补一个分享入口。
                        contextMenu = buildList {
                            add(CpContextMenuItem(s.player.play, Icons.Rounded.PlayArrow, onClick = { onPlayAt(originalIndex) }))
                            add(
                                CpContextMenuItem(
                                    s.player.removeFromQueue,
                                    Icons.Rounded.Close,
                                    onClick = { onRemove(originalIndex) },
                                    danger = true,
                                ),
                            )
                            add(CpContextMenuItem(s.player.share, Icons.Rounded.Share, onClick = {
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
                                    Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    CpText(
                                        "探索相似歌曲",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    CpText(
                                        "基于当前播放队列与在播曲目探索更多",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowForwardIos,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }

        }
        // 悬浮工具条：不占布局行，覆盖在播放列表之上（M3E 次级工具悬浮于主视觉流之上）。
        // 滚动时列表从它下方穿过；留白由上面的 contentPadding 提供。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            QueueFloatingToolbar {
                if (onShuffle != null) {
                    QueueToolButton(
                        icon = Icons.Rounded.Shuffle,
                        label = s.player.shuffle,
                        onClick = onShuffle,
                        selected = shuffleEnabled,
                    )
                }
                if (onRepeat != null) {
                    QueueToolButton(
                        icon = if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        label = s.player.repeat,
                        onClick = onRepeat,
                        selected = repeatMode != RepeatMode.OFF,
                    )
                }
                if (onShuffle != null || onRepeat != null) {
                    // 分组竖线：两侧各留 6dp、线本身更实，避免被相邻的选中圆吞掉，
                    // 也不会被误看成圆形高亮的边缘。
                    Box(
                        Modifier
                            .padding(horizontal = 6.dp)
                            .size(width = 2.dp, height = 24.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)),
                    )
                }
                QueueToolButton(
                    icon = Icons.Rounded.LocationSearching,
                    label = s.player.locateCurrent,
                    onClick = {
                        scope.launch {
                            if (currentIndex in queue.indices) listState.animateScrollToItem(currentIndex)
                        }
                    },
                )
                QueueToolButton(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    label = s.player.saveAsPlaylist,
                    enabled = queue.isNotEmpty(),
                    onClick = {
                        val isAllLocal = queue.all { it.mediaId.startsWith("local://") }
                        if (isAllLocal) showLocalSaveDialog = true else showSaveSheet = true
                    },
                )
                QueueToolButton(
                    icon = Icons.Rounded.DeleteSweep,
                    label = s.player.clearQueue,
                    enabled = queue.isNotEmpty(),
                    danger = true,
                    onClick = {
                        confirm.request(
                            title = s.player.queueClearTitle,
                            message = s.player.queueClearMessage(queue.size),
                            confirmLabel = s.player.clearQueue,
                            onConfirm = onClear,
                        )
                    },
                )
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

    if (showLocalSaveDialog) {
        val paths = remember(queue) {
            queue.map { item ->
                runCatching { CPMediaId.parse(item.mediaId).resourceId }
                    .getOrDefault(item.mediaId.removePrefix("local://audio/").removePrefix("local://video/"))
            }.filter { it.isNotBlank() }
        }
        AddToLocalPlaylistDialog(
            trackPaths = paths,
            onDismiss = { showLocalSaveDialog = false },
        )
    }

    optionsTarget?.let { (index, item) ->
        // 后端只在真实启动时初始化：把收藏态订阅收敛到弹层展开分支，
        // 否则预览 / 离屏出图环境组合 QueueContent 会抛 MusicBackend.init() not called。
        val likedIds by cp.player.app.AppModel.playback.likedIds.collectAsState()
        val track = item.toTrackSummary()
        SongOptionsSheet(
            songName = item.title,
            artistName = item.artist,
            isFavorite = track.id in likedIds,
            isDownloaded = cp.player.app.AppModel.isDownloaded(track.id),
            onDismiss = { optionsTarget = null },
            onPlay = { onPlayAt(index) },
            onToggleFavorite = {
                val target = track.id !in likedIds
                scope.launch {
                    runCatching { cp.player.app.AppModel.musicRepository.likeSong(track.id, target) }
                }
            },
            onAddToQueue = {
                scope.launch {
                    cp.player.app.AppModel.playback.addToQueue(item.mediaId)
                }
            },
            onPlayNext = {
                scope.launch {
                    cp.player.app.AppModel.playback.addNextToQueue(item.mediaId)
                }
            },
            onAddToPlaylist = {
                if (item.mediaId.startsWith("local://")) {
                    addToLocalPlaylistTarget = item
                } else {
                    addToPlaylistTarget = item
                }
            },
            onDownload = { cp.player.app.AppModel.downloadTrack(track) },
            onShowInfo = { detailTarget = item },
            onShare = {
                val rid = runCatching { CPMediaId.parse(item.mediaId).resourceId }.getOrDefault(item.mediaId)
                shareText("「${item.title}」 https://music.163.com/#/song?id=$rid")
            },
            coverUrl = item.coverUrl,
        )
    }

    addToPlaylistTarget?.let { item ->
        AddToPlaylistSheet(
            trackId = item.mediaId,
            onDismiss = { addToPlaylistTarget = null },
        )
    }

    addToLocalPlaylistTarget?.let { item ->
        val path = runCatching { CPMediaId.parse(item.mediaId).resourceId }
            .getOrDefault(item.mediaId.removePrefix("local://audio/").removePrefix("local://video/"))
        AddToLocalPlaylistDialog(
            trackPaths = listOf(path),
            onDismiss = { addToLocalPlaylistTarget = null },
        )
    }

    detailTarget?.let { item ->
        SongDetailInfoDialog(
            track = item.toTrackSummary(),
            onDismiss = { detailTarget = null },
        )
    }

    CpConfirmHost(confirm)
}

/**
 * 队列专用悬浮胶囊。
 *
 * 不复用 [CpFloatingToolbar]：官方 `HorizontalFloatingToolbar` 的 content 槽位间距极小，
 * 实测按钮会从左侧依次紧排、两个 44dp 选中圆直接相切，1dp 的分组竖线也几乎看不见，
 * 整组按钮在胶囊里视觉重心偏左。这里改用 wrapContent 的 Surface + 间距明确的 Row，
 * 由外层 Box 的 Center 保证胶囊在画布中居中，左右内边距严格对称。
 */
@Composable
private fun QueueFloatingToolbar(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.height(56.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * 队列悬浮工具条的统一按钮。
 *
 * 选中态（随机 / 循环已开启）用 `primaryContainer` 圆形底 + `onPrimaryContainer` 图标，
 * 危险动作（清空）用 error 色，禁用态沿用 M3 的 38% 不透明度约定。
 */
@Composable
private fun QueueToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        selected -> colors.onPrimaryContainer
        danger -> colors.error
        else -> colors.onSurface
    }
    Box(
        modifier = modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 选中底比槽位小 4dp：配合 Row 的 4dp 间距，两个相邻的选中圆之间会留出
        // 8dp 容器底色，不会再连成一整块。
        if (selected && enabled) {
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(colors.primaryContainer),
            )
        }
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(44.dp).clip(CircleShape),
            colors = IconButtonDefaults.iconButtonColors(contentColor = contentColor),
        ) {
            Icon(icon, label, Modifier.size(22.dp))
        }
    }
}

private fun QueueItem.toTrackSummary(): TrackSummary {
    val resourceId = cp.player.core.music.CPMediaId.toRawResourceId(mediaId).ifBlank { mediaId }
    return TrackSummary(
        id = resourceId,
        name = title,
        artist = artist,
        album = album,
        coverUrl = coverUrl,
        durationMs = durationMs,
    )
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    index: Int,
    queueSize: Int,
    canDrag: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onOptions: () -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 桌面端右键菜单项；null 时不启用。 */
    contextMenu: List<CpContextMenuItem>? = null,
) {
    val isDark = LocalIsDarkTheme.current
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

    val targetBg = if (dragging) {
        if (isDark) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f)
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
    } else if (isCurrent) {
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
        targetValue = if (dragging) 20.dp else if (isCurrent) 24.dp else 16.dp,
        animationSpec = CpMotion.spatial(),
        label = "queueRowCornerMorph",
    )
    val coverCorner by animateDpAsState(
        targetValue = if (isCurrent) 24.dp else 12.dp,
        animationSpec = CpMotion.spatial(),
        label = "queueCoverCornerMorph",
    )
    val coverShape = remember(coverCorner) { RoundedCornerShape(coverCorner) }
    val scale by animateFloatAsState(
        targetValue = if (dragging) 1.02f else 1f,
        animationSpec = CpMotion.spatial(),
        label = "queueRowScaleAnim",
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (dragging) 8.dp else 0.dp,
        animationSpec = CpMotion.spatial(),
        label = "queueRowShadowAnim",
    )
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = CpMotion.effects(),
        label = "queueTitleColorAnim",
    )
    val s = cpStrings()
    val density = LocalDensity.current

    // 右键菜单包在拖拽手柄所在整行之外：光标落在行上任意位置都能弹出。
    // 拖拽中提升整行 zIndex，避免被相邻兄弟组件遮挡。
    CpContextMenu(items = contextMenu, modifier = modifier.zIndex(if (dragging) 2f else 0f)) {
        Surface(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.shadowElevation = shadowElevation.toPx()
                    shape = RoundedCornerShape(rowCorner)
                    clip = false
                },
            shape = remember(rowCorner) { RoundedCornerShape(rowCorner) },
            color = animatedBg,
        ) {
            Row(
                Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dragModifier = if (canDrag) {
                    Modifier
                        .padding(horizontal = 4.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    dragAccum = 0f
                                    dragging = true
                                },
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
                        }
                } else {
                    Modifier.padding(horizontal = 4.dp)
                }

                Icon(
                    Icons.Rounded.DragIndicator,
                    if (canDrag) s.player.queueReorderHint else null,
                    tint = if (canDrag) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = dragModifier,
                )
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier.weight(1f).cpBounceClickable { onPlay() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Box(Modifier.size(48.dp).aspectRatio(1f)) {
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
            IconButton(onClick = onOptions) {
                Icon(Icons.Rounded.MoreVert, s.player.more)
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