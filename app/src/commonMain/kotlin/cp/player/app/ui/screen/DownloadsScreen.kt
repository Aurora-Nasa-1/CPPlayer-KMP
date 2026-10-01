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
import androidx.compose.runtime.rememberCoroutineScope
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
import coil3.compose.AsyncImage
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.app.ui.model.DownloadsUiState
import cp.player.app.ui.theme.CpShapes
import cp.player.app.ui.util.resized
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalMediaOrigin
import cp.player.core.media.MediaType
import cp.player.core.model.DownloadStatus
import cp.player.core.model.DownloadTask
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 下载管理页（主导航第 4 Tab，也可经设置页 push 进入）。 */
class DownloadsScreen : Screen {
    @Composable
    override fun Content() {
        DownloadsScreenContent(rememberScreenModel { DownloadsScreenModel() })
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
private fun DownloadsScreenContent(model: DownloadsScreenModel) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 桌面窗口标题栏的标题。本页没有页内顶栏（无论 push 还是作为桌面面板），
    // 所以窗口 chrome 是它唯一的标题与返回入口。
    cp.player.app.ui.util.DesktopRouteTitle("下载管理")

    // Tab 上带计数：不切页也能看到「下载中还有几个 / 已完成多少」，
    // 这是下载管理最常被问的一件事，藏进分页里就得逐个点开数。
    val tabs = listOf(
        DownloadsTab("下载中", Icons.Filled.Download, state.activeTasks.size),
        DownloadsTab("已完成", Icons.Filled.DownloadDone, state.completedTasks.size),
        DownloadsTab("本地媒体库", Icons.Filled.FolderOpen, state.localItems.size),
    )
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabs.size })

    Column(Modifier.fillMaxSize()) {
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
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> ActiveDownloadsTab(state = state, model = model)
                    1 -> CompletedDownloadsTab(state = state, model = model)
                    2 -> LocalLibraryTab(state = state, model = model)
                }
            }
        }
    }
}

// ============ Tab 1：下载中 ============

@Composable
private fun ActiveDownloadsTab(state: DownloadsUiState, model: DownloadsScreenModel) {
    val tasks = state.activeTasks
    if (tasks.isEmpty()) {
        DownloadsPageBox {
            StateSurface(emptyStateModifier()) {
                ContentState(
                    title = "没有进行中的下载",
                    message = "在歌曲更多菜单或歌单页点击「下载」，任务会显示在这里",
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
                        task.artist ?: "未知艺术家",
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
    when (task.status) {
        DownloadStatus.DOWNLOADING -> {
            IconButton(onClick = { model.pause(task) }) {
                Icon(Icons.Filled.Pause, contentDescription = "暂停")
            }
            IconButton(onClick = { model.cancel(task) }) {
                Icon(Icons.Filled.Close, contentDescription = "取消")
            }
        }
        DownloadStatus.PENDING -> {
            IconButton(onClick = { model.cancel(task) }) {
                Icon(Icons.Filled.Close, contentDescription = "取消")
            }
        }
        DownloadStatus.PAUSED -> {
            IconButton(onClick = { model.resume(task) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "继续")
            }
            IconButton(onClick = { model.cancel(task) }) {
                Icon(Icons.Filled.Close, contentDescription = "取消")
            }
        }
        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
            IconButton(onClick = { model.retry(task) }) {
                Icon(Icons.Filled.Replay, contentDescription = "重试")
            }
            IconButton(onClick = { model.remove(task, deleteFile = false) }) {
                Icon(Icons.Filled.Delete, contentDescription = "删除记录")
            }
        }
        DownloadStatus.COMPLETED -> Unit
    }
}

// ============ Tab 2：已完成 ============

@Composable
private fun CompletedDownloadsTab(state: DownloadsUiState, model: DownloadsScreenModel) {
    val tasks = state.completedTasks
    if (tasks.isEmpty()) {
        DownloadsPageBox {
            StateSurface(emptyStateModifier()) {
                ContentState(
                    title = "还没有下载完成的内容",
                    message = "下载完成的音频与视频会保存在这里",
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
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 最后一行为奇数时补一个等宽空位，否则那条卡片会被拉满整行。
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CompletedTaskCard(
    task: DownloadTask,
    model: DownloadsScreenModel,
    modifier: Modifier = Modifier,
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
            // 删除：文件 + 记录
            IconButton(onClick = { model.remove(task, deleteFile = true) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除文件与记录",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ============ Tab 3：本地媒体库 ============

@Composable
private fun LocalLibraryTab(state: DownloadsUiState, model: DownloadsScreenModel) {
    val pickFolder = cp.player.app.platform.rememberDirectoryPicker { path ->
        if (path != null) model.importFolder(path)
    }

    // 宽屏收口：内容居中、宽度封顶（等价另外两个 Tab 的 DownloadsPageBox）。
    // 这里用 Column 的 horizontalAlignment 而不是再套一层 Box —— 本页顶部还有两个
    // 常驻按钮，再套一层会让「按钮行 / 列表」两组对齐规则分家。
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部操作按钮：扫描设备 / 导入文件夹
        Row(
            Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = { model.startScan() },
                enabled = !state.scanning,
                modifier = Modifier.weight(1f),
            ) {
                if (state.scanning) {
                    cp.player.app.ui.component.CpLoadingIndicator(
                        Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.scanProgress?.let { "扫描中 · 已发现 ${it.scanned} 项" } ?: "扫描中…",
                        maxLines = 1,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫描设备", maxLines = 1)
                }
            }
            FilledTonalButton(
                onClick = { pickFolder() },
                enabled = !state.importing,
                modifier = Modifier.weight(1f),
            ) {
                if (state.importing) {
                    cp.player.app.ui.component.CpLoadingIndicator(
                        Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("导入中…", maxLines = 1)
                } else {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导入文件夹", maxLines = 1)
                }
            }
        }

        val downloaded = state.downloadedItems
        val imported = state.importedItems
        if (downloaded.isEmpty() && imported.isEmpty()) {
            StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                ContentState(
                    title = "本地媒体库还是空的",
                    message = "下载歌曲，或扫描设备、导入本地文件夹后会显示在这里",
                )
            }
            return@Column
        }

        LazyScrollColumn(
            Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize().weight(1f),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                bottom = 96.dp,
            ),
            // 行距取刻度值。原先写 2dp —— 不在刻度上，而且行按下时圆角会从 4dp 撑到 20dp，
            // 2dp 的缝太窄、变形后与邻行粘连（与 SettingsKit 那边同一条理由）。
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            if (downloaded.isNotEmpty()) {
                item(key = "__group_downloaded__") {
                    LibraryGroupHeader(
                        label = "已下载",
                        count = downloaded.size,
                        icon = Icons.Filled.DownloadDone,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                items(downloaded, key = { "dl:${it.path}" }) { item ->
                    LocalMediaRow(item = item, model = model)
                }
            }
            if (imported.isNotEmpty()) {
                item(key = "__group_imported__") {
                    LibraryGroupHeader(
                        label = "本地导入",
                        count = imported.size,
                        icon = Icons.Filled.FolderOpen,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                items(imported, key = { "im:${it.path}" }) { item ->
                    LocalMediaRow(item = item, model = model)
                }
            }
        }
    }
}

@Composable
private fun LibraryGroupHeader(
    label: String,
    count: Int,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(shape = MaterialTheme.shapes.small, color = containerColor) {
            Icon(
                icon, null,
                tint = contentColor,
                modifier = Modifier.padding(4.dp).size(16.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "$count 项",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalMediaRow(item: LocalMediaItem, model: DownloadsScreenModel) {
    Surface(
        onClick = { model.play(item) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
                    .padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    Modifier.fillMaxSize(),
                    shape = MaterialTheme.shapes.small,
                    color = if (item.mediaType == MediaType.VIDEO)
                        MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            if (item.mediaType == MediaType.VIDEO) Icons.Filled.VideoLibrary
                            else Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = if (item.mediaType == MediaType.VIDEO)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(item.artist ?: "未知艺术家")
                        if (item.sizeBytes > 0) append(" · ").append(formatBytes(item.sizeBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 从库中移除（不删除文件）
            IconButton(onClick = { model.removeLocalItem(item) }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "从库中移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
