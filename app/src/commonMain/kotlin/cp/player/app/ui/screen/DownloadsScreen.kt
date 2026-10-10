package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.desktopPagerMouseControl
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.app.ui.model.DownloadsUiState
import cp.player.app.ui.theme.CpShapes
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.util.resized
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalMediaOrigin
import cp.player.core.media.MediaType
import cp.player.core.model.DownloadStatus
import cp.player.core.model.DownloadTask
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

@Composable
internal fun DownloadsLibraryTab(model: DownloadsScreenModel) {
    DownloadsScreenContent(model)
}

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
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = pagerState.currentPage == index
                    Surface(
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        shape = RoundedCornerShape(percent = 50),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    ) {
                        Row(
                            Modifier.padding(
                                horizontal = if (isSelected) 20.dp else 14.dp,
                                vertical = 10.dp,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                tab.icon, null,
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            if (tab.count > 0) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(percent = 50),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                ) {
                                    Text(
                                        if (tab.count > 99) "99+" else tab.count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(
            Modifier.fillMaxSize(),
            // 同 LegacyScaffold：底部弹窗形状统一取 `CpShapes.sheet`。
            shape = CpShapes.sheet,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
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
            items(tasks, key = { it.id }) { task ->
                ActiveTaskCard(task = task, model = model)
            }
        }
    }
}

/**
 * 空态：与列表同宽居中，否则宽屏下空态卡片会贴着窗口最左、和上面的 Tab 行错开。
 *
 * ⚠️ 这里原先写 16dp、列表写 12dp —— 注释说的是「与列表同宽」，实际差 4dp。
 * 两者现在都取 [CpSpacing.pageHorizontal]（栅格页标准），注释才成立。
 */
private fun emptyStateModifier(): Modifier =
    Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth().padding(CpSpacing.pageHorizontal)

@Composable
private fun ActiveTaskCard(task: DownloadTask, model: DownloadsScreenModel) {
    val s = cpStrings()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TaskCover(task.coverUrl, task.mediaType, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        task.artist ?: s.downloads.unknownArtist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TaskActions(task = task, model = model)
            }
            Spacer(Modifier.height(10.dp))
            val fraction = taskFraction(task)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (task.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                statusText(task),
                style = MaterialTheme.typography.bodySmall,
                color = if (task.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TaskActions(task: DownloadTask, model: DownloadsScreenModel) {
    val s = cpStrings()
    when (task.status) {
        DownloadStatus.DOWNLOADING -> {
            IconButton(onClick = { model.pause(task) }) {
                Icon(Icons.Filled.Pause, contentDescription = s.downloads.pause)
            }
            IconButton(onClick = { model.cancel(task, s) }) {
                Icon(Icons.Filled.Close, contentDescription = s.common.dismiss)
            }
        }
        DownloadStatus.PENDING -> {
            IconButton(onClick = { model.cancel(task, s) }) {
                Icon(Icons.Filled.Close, contentDescription = s.common.dismiss)
            }
        }
        DownloadStatus.PAUSED -> {
            IconButton(onClick = { model.resume(task) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = s.downloads.resume)
            }
            IconButton(onClick = { model.cancel(task, s) }) {
                Icon(Icons.Filled.Close, contentDescription = s.common.dismiss)
            }
        }
        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
            IconButton(onClick = { model.retry(task) }) {
                Icon(Icons.Filled.Replay, contentDescription = s.player.retry)
            }
            IconButton(onClick = { model.remove(task, deleteFile = false, s) }) {
                Icon(Icons.Filled.Delete, contentDescription = s.downloads.deleteRecord)
            }
        }
        DownloadStatus.COMPLETED -> Unit
    }
}

// ============ Tab 2：已完成 ============

@Composable
private fun CompletedDownloadsTab(state: DownloadsUiState, model: DownloadsScreenModel) {
    val tasks = state.completedTasks
    // 「删除文件与记录」的二次确认：删掉的是已下载的本体文件，重新下要花流量与时间。
    // 挂起态放在 tab 层而不是每张卡片一份 —— 卡片可能有几十张。
    var confirmDeleteTarget by remember { mutableStateOf<DownloadTask?>(null) }
    val s = cpStrings()
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
    // 宽屏两列：卡片内容很短（封面 + 标题 + 一行副标题），拉成整行后右端的删除按钮
    // 离标题 1000dp 远，视觉上完全脱钩。
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val columns = if (maxWidth >= 900.dp) 2 else 1
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
            items(rows, key = { it.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { task ->
                        CompletedTaskCard(
                            task = task,
                            model = model,
                            strings = s,
                            modifier = Modifier.weight(1f),
                            onDelete = { confirmDeleteTarget = task },
                        )
                    }
                    // 最后一行为奇数时补一个等宽空位，否则那条卡片会被拉满整行。
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    confirmDeleteTarget?.let { task ->
        cp.player.app.ui.component.CpConfirmDialog(
            title = s.downloads.deleteConfirmTitle,
            message = s.downloads.deleteConfirmMessage(task.title),
            confirmLabel = s.downloads.delete,
            onConfirm = { model.remove(task, deleteFile = true, s) },
            onDismiss = { confirmDeleteTarget = null },
        )
    }
}

@Composable
private fun CompletedTaskCard(
    task: DownloadTask,
    model: DownloadsScreenModel,
    /** 提示语的语言：协程里读不到 CompositionLocal，由宿主传进来。 */
    strings: CpStrings,
    modifier: Modifier = Modifier,
    /** 请求删除（文件 + 记录）；由宿主弹二次确认后再真正执行。 */
    onDelete: () -> Unit = { model.remove(task, deleteFile = true, strings) },
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskCover(task.coverUrl, task.mediaType, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 副标题刻意不显示本地绝对路径：那串字符对用户没有信息量，
                // 而歌手 + 文件大小是能扫读的。
                Text(
                    buildString {
                        append(task.artist ?: "未知艺术家")
                        val size = task.totalBytes?.takeIf { it > 0 } ?: task.downloadedBytes
                        if (size > 0) append(" · ").append(formatBytes(size))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 删除：文件 + 记录（先经宿主的二次确认）
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除文件与记录",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ============ 通用小组件与工具 ============

/** 任务封面：有封面 URL 显示图片，否则按媒体类型显示图标占位。 */
@Composable
private fun TaskCover(coverUrl: String?, mediaType: MediaType, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl.resized(120),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (mediaType == MediaType.VIDEO) Icons.Filled.VideoLibrary
                        else Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
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

/** 任务状态文案。 */
private fun statusText(task: DownloadTask): String {
    val percent = (taskFraction(task) * 100).roundToInt()
    val sizeHint = task.totalBytes?.takeIf { it > 0 }?.let {
        "${formatBytes(task.downloadedBytes)} / ${formatBytes(it)}"
    } ?: formatBytes(task.downloadedBytes)
    return when (task.status) {
        DownloadStatus.PENDING -> "等待下载"
        DownloadStatus.DOWNLOADING -> "下载中 $percent% · $sizeHint"
        DownloadStatus.PAUSED -> "已暂停 · $sizeHint"
        DownloadStatus.FAILED -> "下载失败：${task.error ?: "未知错误"}"
        DownloadStatus.CANCELLED -> "已取消"
        DownloadStatus.COMPLETED -> "已完成"
    }
}

/** 字节数可读格式（commonMain 无 String.format，手工实现）。 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.roundToInt()} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).roundToInt() / 10.0} MB"
    val gb = mb / 1024.0
    return "${(gb * 100).roundToInt() / 100.0} GB"
}
