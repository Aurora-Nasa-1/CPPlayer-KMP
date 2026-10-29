package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.BackHandler
import cp.player.app.ui.component.AddToLocalPlaylistDialog
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpAnchoredMenu
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpContextMenu
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpLinearProgress
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.desktopPagerMouseControl
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsRowContainer
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.app.ui.model.DownloadsUiState
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.CpShapes
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.formatBytes
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.util.resized
import cp.player.core.media.MediaType
import cp.player.core.model.DownloadStatus
import cp.player.core.model.DownloadTask
import cp.player.core.music.CPMediaId
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 下载管理页。两种宿主形态，外壳判据沿用 [CpRouteScaffold] 那一套：
 *
 * - **路由页**（默认）：从「我的」页的下载入口，或设置 → 存储 push 出来。窄屏（安卓手机）
 *   必须自绘顶栏 —— 那里没有窗口 chrome，页内再不自绘就是**整页既没有标题也没有返回键**
 *   （此前本页确实如此：`DownloadsScreenContent` 只发布 `DesktopRouteTitle` 就直接出正文，
 *   而 `DesktopRouteTitle` 只有桌面窗口标题栏会读）。
 * - **桌面面板**：[embedded] 为真，由 `MainScreen` 的 `DesktopPane.Downloads` 盖在内容层上。
 *   标题与返回归外壳（窗口 chrome，或宽屏平板壳层自己的顶栏），页内再画一条就是双顶栏。
 */
class DownloadsScreen(private val embedded: Boolean = false) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        // rememberScreenModel 是 Screen 的扩展函数，只能在 Content() 里调用（见 AGENTS.md）。
        val model = rememberScreenModel { DownloadsScreenModel() }
        CpRouteScaffold(
            title = cpStrings().downloads.screenTitle,
            onBack = { navigator?.popOrNotify() },
            embedded = embedded,
        ) { pageModifier ->
            DownloadsScreenContent(model, pageModifier)
        }
    }
}

private data class DownloadsTab(val label: String, val icon: ImageVector, val count: Int)

/**
 * 宽屏收口：正文用全局统一最大宽度并居中（与首页 / 曲库一致）。
 *
 * 不收口的话，2560 宽的显示器上任务卡片会被拉到和窗口一样宽，
 * 「标题 + 进度 + 大小」三者在一条 2400dp 的线上，扫读非常累。
 */
@Composable
private fun DownloadsPageBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        content()
    }
}

/** 与 [DownloadsPageBox] 配套：正文容器，宽度收在 [CpSpacing.pageMaxWidth] 内。 */
private fun pageContentModifier(): Modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize()

@Composable
private fun DownloadsScreenContent(model: DownloadsScreenModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val s = cpStrings()

    // Tab 上带计数：不切页也能看到「下载中还有几个 / 已完成多少」，
    // 这是下载管理最常被问的一件事，藏进分页里就得逐个点开数。
    // 「下载中 / 已完成」复用媒体库那两个词（同一条文案不翻两种）。
    val tabs = listOf(
        DownloadsTab(s.library.downloading, Icons.Filled.Download, state.activeTasks.size),
        DownloadsTab(s.library.completed, Icons.Filled.DownloadDone, state.completedTasks.size),
        DownloadsTab(s.downloads.tabLocalLibrary, Icons.Filled.FolderOpen, state.localItems.size),
    )
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabs.size })

    Column(modifier.fillMaxSize()) {
        // 顶部 Tab 切换（样式与媒体库页一致）。与正文同宽居中 ——
        // 否则宽屏下正文居中、tab 行贴着窗口最左，两段明显错位。
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()
                    .padding(horizontal = CpSpacing.pageHorizontal, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = pagerState.currentPage == index
                    // 计数为 0 时不带括号尾巴 —— 「下载中 (0)」比「下载中」更像出错。
                    val countLabel = if (tab.count > 0) "${if (tab.count > 99) "99+" else tab.count}" else null
                    CpToggleChip(
                        checked = isSelected,
                        onCheckedChange = { if (it) scope.launch { pagerState.animateScrollToPage(index) } },
                        label = tab.label,
                        icon = tab.icon,
                        badge = countLabel,
                    )
                }
            }
        }

        Surface(
            Modifier.fillMaxSize(),
            // 同 LegacyScaffold：底部弹窗形状统一取 `CpShapes.sheet`。
            shape = CpShapes.sheet,
            // ⚠️ 底色必须是**页面底色**（`background`）而不是 `surfaceContainerLow`。
            // 卡片取 `settingsRowContainer()`，它在浅色下正是 `surfaceContainerLow` ——
            // 面板若也用同一档，浅色主题里卡片与面板**完全同色**，整列任务糊成一片
            // （离屏渲图实测：卡片边界只剩进度条那一根线）。深色下两边恰好不同（Highest vs Low），
            // 所以这个缺陷只在浅色下出现，单看深色截图是发现不了的。
            color = MaterialTheme.colorScheme.background,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    // 鼠标滚轮翻页（见 desktopPagerMouseControl 的 KDoc）。
                    // 三个页签在桌面端此前只能点顶部的胶囊按钮切 —— 滚轮落在页面上毫无反应。
                    .desktopPagerMouseControl(
                        onScrollLeft = {
                            val target = (pagerState.currentPage - 1).coerceAtLeast(0)
                            if (target != pagerState.currentPage) {
                                scope.launch { pagerState.animateScrollToPage(target) }
                            }
                        },
                        onScrollRight = {
                            val target = (pagerState.currentPage + 1).coerceAtMost(tabs.lastIndex)
                            if (target != pagerState.currentPage) {
                                scope.launch { pagerState.animateScrollToPage(target) }
                            }
                        },
                        pageCount = pagerState.pageCount,
                    ),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> ActiveDownloadsTab(state = state, model = model)
                    1 -> CompletedDownloadsTab(state = state, model = model)
                    2 -> LocalLibraryTab(state = state, model = model, strings = s)
                }
            }
        }
    }
}

// ============ Tab 1：下载中 ============

@Composable
private fun ActiveDownloadsTab(state: DownloadsUiState, model: DownloadsScreenModel) {
    val tasks = state.activeTasks
    val s = cpStrings()
    // 破坏性操作（取消任务会丢掉未完成的临时文件）只认 CpConfirmHost：
    // 单条取消与「全部取消」共用同一个挂起态，文案不会漂成两套（见 AGENTS.md §6）。
    val confirm = rememberConfirmState()

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(tasks) {
        if (tasks.isEmpty()) {
            selectionMode = false
            selectedIds = emptySet()
        } else {
            selectedIds = selectedIds.filter { id -> tasks.any { it.id == id } }.toSet()
        }
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    if (tasks.isEmpty()) {
        DownloadsPageBox {
            StateSurface(emptyStateModifier()) {
                ContentState(
                    title = s.downloads.emptyActiveTitle,
                    message = s.downloads.emptyActiveNote,
                )
            }
        }
        return
    }

    val hasRunning = tasks.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
    val hasPaused = tasks.any { it.status == DownloadStatus.PAUSED }

    DownloadsPageBox {
        LazyScrollColumn(
            pageContentModifier(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                top = CpSpacing.formVertical,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "batch_controls") {
                if (selectionMode) {
                    val selectedTasks = tasks.filter { it.id in selectedIds }
                    SelectionControlBar(
                        selectedCount = selectedIds.size,
                        allSelected = tasks.isNotEmpty() && selectedIds.size == tasks.size,
                        onToggleAll = {
                            selectedIds = if (selectedIds.size == tasks.size) emptySet() else tasks.map { it.id }.toSet()
                        },
                        onExit = {
                            selectionMode = false
                            selectedIds = emptySet()
                        },
                        canPause = selectedTasks.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING },
                        onPause = { model.pauseTasks(selectedTasks) },
                        canResume = selectedTasks.any { it.status == DownloadStatus.PAUSED },
                        onResume = { model.resumeTasks(selectedTasks) },
                        canDelete = selectedTasks.isNotEmpty(),
                        onDelete = {
                            confirm.request(
                                title = s.downloads.deleteSelectedConfirmTitle,
                                message = s.downloads.deleteSelectedActiveConfirmMessage(selectedTasks.size),
                                confirmLabel = s.downloads.delete,
                                destructive = true,
                                onConfirm = {
                                    model.removeTasks(selectedTasks, deleteFiles = true, s)
                                    selectionMode = false
                                    selectedIds = emptySet()
                                },
                            )
                        },
                    )
                } else {
                    ActiveBatchControlBar(
                        total = tasks.size,
                        hasRunning = hasRunning,
                        hasPaused = hasPaused,
                        onPauseAll = { model.pauseAll() },
                        onResumeAll = { model.resumeAll() },
                        onSelectMode = { selectionMode = true },
                        onDeleteAll = {
                            confirm.request(
                                title = s.downloads.deleteAllActiveConfirmTitle,
                                message = s.downloads.deleteAllActiveConfirmMessage,
                                confirmLabel = s.downloads.deleteAll,
                                destructive = true,
                                onConfirm = { model.removeAllActiveTasks(s) },
                            )
                        },
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                // animateItem：任务完成 / 取消后卡片是**淡出并让下一条补位**，
                // 而不是整列瞬间重排（后者看起来像列表被重新加载了一次）。
                Box(Modifier.fillMaxWidth().animateItem()) {
                    ActiveTaskCard(
                        task = task,
                        model = model,
                        selectionMode = selectionMode,
                        isSelected = task.id in selectedIds,
                        onToggleSelect = {
                            selectedIds = if (task.id in selectedIds) selectedIds - task.id else selectedIds + task.id
                        },
                        onLongClick = {
                            selectionMode = true
                            selectedIds = setOf(task.id)
                        },
                        onCancelRequest = {
                            confirm.request(
                                title = s.downloads.cancelConfirmTitle,
                                message = s.downloads.cancelConfirmMessage(task.title),
                                confirmLabel = s.downloads.cancel,
                                destructive = true,
                                onConfirm = { model.cancel(task, s) },
                            )
                        },
                    )
                }
            }
        }
    }

    CpConfirmHost(confirm)
}

/**
 * 空态：与列表同宽居中，否则宽屏下空态卡片会贴着窗口最左、和上面的 Tab 行错开。
 *
 * ⚠️ 这里原先写 16dp、列表写 12dp —— 注释说的是「与列表同宽」，实际差 4dp。
 * 两者现在都取 [CpSpacing.pageHorizontal]（栅格页标准），注释才成立。
 */
private fun emptyStateModifier(): Modifier =
    Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth().padding(CpSpacing.pageHorizontal)

/**
 * 进行中下载的批量控制条。
 */
@Composable
private fun ActiveBatchControlBar(
    total: Int,
    hasRunning: Boolean,
    hasPaused: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onSelectMode: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsRowContainer(),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CpText(
                s.downloads.queueTasks(total),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (hasRunning) {
                    BatchButton(
                        icon = Icons.Filled.Pause,
                        label = s.downloads.pauseAll,
                        onClick = onPauseAll,
                    )
                }
                if (hasPaused) {
                    BatchButton(
                        icon = Icons.Filled.PlayArrow,
                        label = s.downloads.resumeAll,
                        onClick = onResumeAll,
                    )
                }
                BatchButton(
                    icon = Icons.Filled.SelectAll,
                    label = s.downloads.selectMode,
                    onClick = onSelectMode,
                )
                TextButton(
                    onClick = onDeleteAll,
                    shape = CpShapes.full,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                    CpText(
                        s.downloads.deleteAll,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 已完成下载的批量控制条。
 */
@Composable
private fun CompletedBatchControlBar(
    total: Int,
    onSelectMode: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsRowContainer(),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CpText(
                s.downloads.completedTasks(total),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BatchButton(
                    icon = Icons.Filled.SelectAll,
                    label = s.downloads.selectMode,
                    onClick = onSelectMode,
                )
                TextButton(
                    onClick = onDeleteAll,
                    shape = CpShapes.full,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                    CpText(
                        s.downloads.deleteAll,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 多选模式悬浮/操作条。
 */
@Composable
private fun SelectionControlBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onExit: () -> Unit,
    canPause: Boolean = false,
    onPause: (() -> Unit)? = null,
    canResume: Boolean = false,
    onResume: (() -> Unit)? = null,
    canDelete: Boolean = false,
    onDelete: () -> Unit,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                IconButton(onClick = onExit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = s.library.exitSelection,
                        modifier = Modifier.size(18.dp),
                    )
                }
                CpText(
                    s.library.selectedCount(selectedCount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    onClick = onToggleAll,
                    shape = CpShapes.full,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    CpText(
                        if (allSelected) s.library.deselectAll else s.library.selectAll,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (canPause && onPause != null) {
                    BatchButton(
                        icon = Icons.Filled.Pause,
                        label = s.downloads.pauseSelected,
                        onClick = onPause,
                    )
                }
                if (canResume && onResume != null) {
                    BatchButton(
                        icon = Icons.Filled.PlayArrow,
                        label = s.downloads.resumeSelected,
                        onClick = onResume,
                    )
                }
                TextButton(
                    onClick = onDelete,
                    enabled = canDelete,
                    shape = CpShapes.full,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Spacer(Modifier.width(4.dp))
                    CpText(
                        s.downloads.deleteSelected,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
        }
    }
}

/** 批量操作条里的胶囊按钮（tonal，图标 + 文字）。 */
@Composable
private fun BatchButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        shape = CpShapes.full,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        CpText(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 任务状态 Expressive 徽标。
 */
@Composable
private fun TaskStatusBadge(task: DownloadTask) {
    val s = cpStrings()
    val (badgeText, badgeContainerColor, badgeContentColor) = when (task.status) {
        DownloadStatus.DOWNLOADING -> Triple(
            s.library.downloading,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        DownloadStatus.PENDING -> Triple(
            s.downloads.statusPending,
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadStatus.PAUSED -> Triple(
            s.downloads.pause,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        DownloadStatus.FAILED -> Triple(
            s.player.retry,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        DownloadStatus.CANCELLED -> Triple(
            s.downloads.statusCancelled,
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadStatus.COMPLETED -> Triple(
            s.library.completed,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = badgeContainerColor,
        contentColor = badgeContentColor,
    ) {
        CpText(
            badgeText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 进行中任务卡片。
 *
 * 视觉与交互规范：
 * 1. 进度条采用 [CpLinearProgress]（M3 Expressive 圆头 + stop indicator）。
 * 2. 状态徽标采用 M3 Expressive 彩色角标，清晰区分下载中、等待中、已暂停、失败状态。
 * 3. 支持多选态与单选勾选，长按进入多选模式。
 * 4. 等待中的任务同样支持手动暂停。
 */
@Composable
private fun ActiveTaskCard(
    task: DownloadTask,
    model: DownloadsScreenModel,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongClick: () -> Unit = {},
    /** 请求取消：由宿主弹二次确认后再真正执行（见 [ActiveDownloadsTab]）。 */
    onCancelRequest: () -> Unit,
) {
    val s = cpStrings()
    val failed = task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED
    val fraction = taskFraction(task)

    val targetColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else settingsRowContainer()
    val animatedColor by animateColorAsState(targetColor, animationSpec = CpMotion.effects())

    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = animatedColor,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelect()
                },
                onLongClick = {
                    if (!selectionMode) onLongClick()
                },
            ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                TaskCover(task.coverUrl, task.mediaType, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    CpText(
                        task.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        emphasized = task.status == DownloadStatus.DOWNLOADING,
                        marqueeEnabled = task.status == DownloadStatus.DOWNLOADING,
                    )
                    CpText(
                        task.artist ?: s.downloads.unknownArtist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                AnimatedVisibility(visible = !selectionMode) {
                    TaskActions(task = task, model = model, onCancelRequest = onCancelRequest)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!failed && task.status != DownloadStatus.PENDING) {
                CpLinearProgress(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TaskStatusBadge(task)
                CpText(
                    statusText(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (!failed && task.status != DownloadStatus.PENDING) {
                    CpText(
                        "${(fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskActions(
    task: DownloadTask,
    model: DownloadsScreenModel,
    onCancelRequest: () -> Unit,
) {
    val s = cpStrings()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (task.status) {
            DownloadStatus.DOWNLOADING -> {
                IconButton(
                    onClick = { model.pause(task) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = s.downloads.pause)
                }
                IconButton(onClick = onCancelRequest) {
                    Icon(Icons.Filled.Close, contentDescription = s.downloads.cancel)
                }
            }
            DownloadStatus.PENDING -> {
                IconButton(
                    onClick = { model.pause(task) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = s.downloads.pause)
                }
                IconButton(onClick = onCancelRequest) {
                    Icon(Icons.Filled.Close, contentDescription = s.downloads.cancel)
                }
            }
            DownloadStatus.PAUSED -> {
                IconButton(
                    onClick = { model.resume(task) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = s.downloads.resume)
                }
                IconButton(onClick = onCancelRequest) {
                    Icon(Icons.Filled.Close, contentDescription = s.downloads.cancel)
                }
            }
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                IconButton(
                    onClick = { model.retry(task) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(Icons.Filled.Replay, contentDescription = s.player.retry)
                }
                IconButton(onClick = { model.remove(task, deleteFile = false, s) }) {
                    Icon(Icons.Filled.Delete, contentDescription = s.downloads.deleteRecord)
                }
            }
            DownloadStatus.COMPLETED -> Unit
        }
    }
}

// ============ Tab 2：已完成 ============

@Composable
private fun CompletedDownloadsTab(state: DownloadsUiState, model: DownloadsScreenModel) {
    val tasks = state.completedTasks
    // 「删除文件与记录」的二次确认：删掉的是已下载的本体文件，重新下要花流量与时间。
    // 挂起态放在 tab 层而不是每张卡片一份 —— 卡片可能有几十张。
    val confirm = rememberConfirmState()
    var addToCloudTarget by remember { mutableStateOf<String?>(null) }
    var addToLocalPathTarget by remember { mutableStateOf<String?>(null) }
    val s = cpStrings()

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(tasks) {
        if (tasks.isEmpty()) {
            selectionMode = false
            selectedIds = emptySet()
        } else {
            selectedIds = selectedIds.filter { id -> tasks.any { it.id == id } }.toSet()
        }
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    if (tasks.isEmpty()) {
        DownloadsPageBox {
            StateSurface(emptyStateModifier()) {
                ContentState(
                    title = s.downloads.emptyCompletedTitle,
                    message = s.downloads.emptyCompletedNote,
                )
            }
        }
        return
    }
    // 多列栅格：卡片内容很短（封面 + 标题 + 一行副标题），拉成整行后右端的删除按钮
    // 离标题 1000dp 远，视觉上完全脱钩。
    //
    // 列数按**收口后的内容宽度**换算（不是窗口宽度）：正文被 `pageContentModifier`
    // 钳在 1400dp 内，按窗口宽度算会在超宽屏上多出一列、右侧那条卡片反而被压窄。
    // ⚠️ 阈值与列数**必须自洽**：450dp 一列的话，1400dp 的内容宽正好 3 列
    // （`1/2/3` 三档分别落在 <900 / 900–1349 / ≥1350）。
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val contentWidth = maxWidth.coerceAtMost(CpSpacing.pageMaxWidth)
        val columns = when {
            contentWidth < 900.dp -> 1
            contentWidth < 1350.dp -> 2
            else -> 3
        }
        val rows = if (columns == 1) tasks.map { listOf(it) } else tasks.chunked(columns)
        LazyScrollColumn(
            pageContentModifier(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                top = CpSpacing.formVertical,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "completed_batch_controls") {
                if (selectionMode) {
                    val selectedTasks = tasks.filter { it.id in selectedIds }
                    SelectionControlBar(
                        selectedCount = selectedIds.size,
                        allSelected = tasks.isNotEmpty() && selectedIds.size == tasks.size,
                        onToggleAll = {
                            selectedIds = if (selectedIds.size == tasks.size) emptySet() else tasks.map { it.id }.toSet()
                        },
                        onExit = {
                            selectionMode = false
                            selectedIds = emptySet()
                        },
                        canDelete = selectedTasks.isNotEmpty(),
                        onDelete = {
                            confirm.request(
                                title = s.downloads.deleteSelectedConfirmTitle,
                                message = s.downloads.deleteSelectedCompletedConfirmMessage(selectedTasks.size),
                                confirmLabel = s.downloads.delete,
                                destructive = true,
                                onConfirm = {
                                    model.removeTasks(selectedTasks, deleteFiles = true, s)
                                    selectionMode = false
                                    selectedIds = emptySet()
                                },
                            )
                        },
                    )
                } else {
                    CompletedBatchControlBar(
                        total = tasks.size,
                        onSelectMode = { selectionMode = true },
                        onDeleteAll = {
                            confirm.request(
                                title = s.downloads.deleteAllCompletedConfirmTitle,
                                message = s.downloads.deleteAllCompletedConfirmMessage,
                                confirmLabel = s.downloads.deleteAll,
                                destructive = true,
                                onConfirm = { model.removeAllCompletedTasks(s) },
                            )
                        },
                    )
                }
            }
            items(rows, key = { it.first().id }) { row ->
                Row(
                    Modifier.fillMaxWidth().animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { task ->
                        CompletedTaskCard(
                            task = task,
                            model = model,
                            strings = s,
                            selectionMode = selectionMode,
                            isSelected = task.id in selectedIds,
                            onToggleSelect = {
                                selectedIds = if (task.id in selectedIds) selectedIds - task.id else selectedIds + task.id
                            },
                            onLongClick = {
                                selectionMode = true
                                selectedIds = setOf(task.id)
                            },
                            modifier = Modifier.weight(1f),
                            onDelete = {
                                confirm.request(
                                    title = s.downloads.deleteConfirmTitle,
                                    message = s.downloads.deleteConfirmMessage(task.title),
                                    confirmLabel = s.downloads.delete,
                                    destructive = true,
                                    onConfirm = { model.remove(task, deleteFile = true, s) },
                                )
                            },
                            onAddToCloudPlaylist = { addToCloudTarget = it },
                            onAddToLocalPlaylist = { addToLocalPathTarget = it },
                        )
                    }
                    // 最后一行为奇数时补等宽空位，否则那条卡片会被拉满整行。
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    addToCloudTarget?.let { cloudId ->
        AddToPlaylistSheet(
            trackIds = listOf(cloudId),
            onDismiss = { addToCloudTarget = null },
        )
    }

    addToLocalPathTarget?.let { path ->
        AddToLocalPlaylistDialog(
            trackPaths = listOf(path),
            onDismiss = { addToLocalPathTarget = null },
        )
    }

    CpConfirmHost(confirm)
}

/**
 * 已完成任务卡片。
 *
 * 视觉与 [ActiveTaskCard] 同一套（`shapes.largeIncreased` + [settingsRowContainer]），
 * 只把「进度区」换成「歌手 · 大小」一行 —— 两张卡片在页签之间切换时读起来是同一族。
 *
 * 支持多选选中状态与长按进入多选。
 */
@Composable
private fun CompletedTaskCard(
    task: DownloadTask,
    model: DownloadsScreenModel,
    /** 提示语的语言：协程里读不到 CompositionLocal，由宿主传进来。 */
    strings: CpStrings,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** 请求删除（文件 + 记录）；由宿主弹二次确认后再真正执行。 */
    onDelete: () -> Unit = { model.remove(task, deleteFile = true, strings) },
    onAddToCloudPlaylist: (String) -> Unit = {},
    onAddToLocalPlaylist: (String) -> Unit = {},
) {
    val navigator = LocalNavigator.current
    var menuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val s = strings.downloads
    val isVideo = task.mediaType == MediaType.VIDEO
    val mediaId = task.localPath?.let { path ->
        if (isVideo) "local://video/$path" else "local://audio/$path"
    }

    val likedIds by AppModel.playback.likedIds.collectAsState()
    val cloudFavId = remember(task.mediaId) {
        runCatching { CPMediaId.parse(task.mediaId).resourceId }.getOrDefault(task.mediaId)
    }
    val isCloudFav = cloudFavId in likedIds

    val handlePlay: () -> Unit = {
        if (isVideo) {
            task.localPath?.let { path ->
                navigator?.push(VideoPlayerScreen("local://video/$path", title = task.title))
            }
        } else {
            mediaId?.let { id ->
                AppModel.playTrackClicked(id) {
                    AppModel.playback.playQueue(listOf(id), startIndex = 0)
                }
            }
        }
    }

    val contextMenu = songContextMenuItems(
        SongMenuActions(
            onPlay = handlePlay,
            isFavorite = isCloudFav,
            onToggleFavorite = {
                scope.launch {
                    val target = cloudFavId !in likedIds
                    AppModel.playback.toggleFavoriteFor(task.mediaId)
                    UiEvents.notify(s.favoriteCloudDone(target))
                }
            },
            favoriteLabel = if (isCloudFav) s.unfavoriteCloud else s.favoriteCloud,
            onAddToQueue = if (!isVideo && mediaId != null) {
                {
                    scope.launch {
                        AppModel.playback.addToQueue(mediaId)
                        UiEvents.notify(strings.library.queuedToPlay)
                    }
                }
            } else null,
            onPlayNext = if (!isVideo && mediaId != null) {
                {
                    scope.launch {
                        AppModel.playback.addNextToQueue(mediaId)
                        UiEvents.notify(strings.library.playNext)
                    }
                }
            } else null,
            onAddToPlaylist = {
                onAddToCloudPlaylist(task.mediaId)
            },
            onAddToLocalPlaylist = task.localPath?.let { path ->
                { onAddToLocalPlaylist(path) }
            },
            onOpenInFileManager = task.localPath?.let { path ->
                { cp.player.app.platform.openInFileManager(path) }
            },
            onDelete = onDelete,
            deleteLabel = s.deleteFileAndRecord,
        )
    )

    val targetColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else settingsRowContainer()
    val animatedColor by animateColorAsState(targetColor, animationSpec = CpMotion.effects())

    CpContextMenu(items = contextMenu, modifier = modifier) {
        Surface(
            shape = MaterialTheme.shapes.largeIncreased,
            color = animatedColor,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (selectionMode) onToggleSelect() else handlePlay()
                    },
                    onLongClick = {
                        if (!selectionMode) onLongClick()
                    },
                ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                TaskCover(task.coverUrl, task.mediaType, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    CpText(
                        task.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    // 副标题刻意不显示本地绝对路径：那串字符对用户没有信息量，
                    // 而歌手 + 文件大小是能扫读的。
                    CpText(
                        buildString {
                            append(task.artist ?: s.unknownArtist)
                            val size = task.totalBytes?.takeIf { it > 0 } ?: task.downloadedBytes
                            if (size > 0) append(" · ").append(formatBytes(size))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                AnimatedVisibility(visible = !selectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isVideo) {
                            IconButton(
                                onClick = handlePlay,
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = s.playVideo)
                            }
                        } else if (mediaId != null) {
                            IconButton(onClick = {
                                scope.launch {
                                    AppModel.playback.addNextToQueue(mediaId)
                                    UiEvents.notify(strings.library.playNext)
                                }
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = s.playNext,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        CpAnchoredMenu(
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            items = contextMenu,
                        ) {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = strings.library.moreOptions,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============ 通用小组件与工具 ============

/**
 * 任务封面：有封面 URL 显示图片，否则按媒体类型显示图标占位。
 *
 * ⚠️ `aspectRatio(1f)` 必须留在**调用方传进来的 modifier 之后**、且自身不能被
 * 尺寸修饰符压过：任务卡片的封面是正方形，列表行给的是固定 `size(52.dp)`。
 */
@Composable
private fun TaskCover(coverUrl: String?, mediaType: MediaType, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(1f).clip(MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl.resized(120),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else if (mediaType == MediaType.VIDEO) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                CpCoverPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    corner = 12.dp,
                    animated = false,
                )
            }
        }
    }
}

/** 任务进度（0f..1f）：优先 progress，其次按已下载字节估算。 */
private fun taskFraction(task: DownloadTask): Float {
    if (task.progress > 0f) return task.progress.coerceIn(0f, 1f)
    val total = task.totalBytes
    if (total != null && total > 0) {
        return (task.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
    }
    return 0f
}

/**
 * 任务状态文案。
 *
 * 文案取 `DownloadStrings`（`statusPending` / `statusDownloadingSize` / …）而不是就地拼中文：
 * 接口里那一批成员早就存在（`I18N.md` 批次 6 的待迁移项），页面这边一直写死字面量，
 * 于是英文界面里整页状态都是中文。
 *
 * ⚠️ 「下载中」走 [cp.player.app.i18n.DownloadStrings.statusDownloadingSize]（**只带体积**）：
 * 卡片右端已经有独立的百分比了，状态行再带一次就是同一个数字显示两遍。
 *
 * ⚠️ `@Composable`：要读 `cpStrings()`（那是组合上下文的取值），所以只能被组合调用。
 * 别把它挪进非组合的工具函数里 —— 那样语言切换不会重新求值。
 */
@Composable
private fun statusText(task: DownloadTask): String {
    val s = cpStrings().downloads
    val sizeHint = task.totalBytes?.takeIf { it > 0 }?.let {
        "${formatBytes(task.downloadedBytes)} / ${formatBytes(it)}"
    } ?: formatBytes(task.downloadedBytes)
    return when (task.status) {
        DownloadStatus.PENDING -> s.statusPending
        DownloadStatus.DOWNLOADING -> s.statusDownloadingSize(sizeHint)
        DownloadStatus.PAUSED -> s.statusPaused(sizeHint)
        DownloadStatus.FAILED -> s.statusFailed(task.error ?: s.unknownError)
        DownloadStatus.CANCELLED -> s.statusCancelled
        DownloadStatus.COMPLETED -> cpStrings().library.completed
    }
}
