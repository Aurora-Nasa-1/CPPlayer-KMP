package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.platform.shareText
import cp.player.app.ui.component.BentoActionCard
import cp.player.app.ui.component.BentoCard
import cp.player.app.ui.component.BentoGap
import cp.player.app.ui.component.BentoHeroCard
import cp.player.app.ui.component.BentoMiniTile
import cp.player.app.ui.component.BentoPill
import cp.player.app.ui.component.BentoStatCard
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.component.PlaylistOptionsSheet
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.app.ui.model.DownloadsUiState
import cp.player.app.ui.model.LibraryScreenModel
import cp.player.app.ui.model.LibraryUiState
import cp.player.core.music.PlaylistSummary

/**
 * 「我的」页：Material 3 Expressive 仪表盘 + 曲库列表。
 *
 * 版面自上而下：
 * 1. **Bento 仪表盘** —— 问候区 / 聆听统计 / 主行动卡 / 快捷入口 / 偏好设置 / 关于。
 *    Expanded（≥840dp）走 2:1:1 的四列栅格，与设计稿一致；窄屏折叠成单列 + 两列并排。
 * 2. **曲库** —— 分段控件切歌单 / 云盘 / 下载，列表直接铺在同一个滚动容器里
 *    （不再用 HorizontalPager，否则仪表盘只能单独占一个滚动视口）。
 */
class LibraryScreen(private val initialPlaylistId: Long? = null) : Screen {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { LibraryScreenModel() }
        LaunchedEffect(initialPlaylistId) {
            if (initialPlaylistId != null) model.selectPlaylist(initialPlaylistId)
        }
        LibraryScreenContent(model)
    }
}

private data class FilterTab(val label: String, val icon: ImageVector)

private val LibraryFilters = listOf(
    FilterTab("歌单", Icons.AutoMirrored.Filled.QueueMusic),
    FilterTab("云盘", Icons.Filled.CloudQueue),
    FilterTab("下载", Icons.Filled.Download),
)

@Composable
private fun LibraryScreenContent(model: LibraryScreenModel) {
    val state by model.state.collectAsState()
    var selectedPlaylist by remember { mutableStateOf<PlaylistSummary?>(null) }
    var confirmDelete by remember { mutableStateOf<PlaylistSummary?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    val navigator = LocalNavigator.currentOrThrow
    val downloadsModel = remember { DownloadsScreenModel() }
    val downloadsState by downloadsModel.state.collectAsState()
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val profile by AppModel.userProfileFlow.collectAsState()
    val expanded = LocalIsExpanded.current

    LaunchedEffect(state.selectedPlaylistId, state.playlists) {
        val target = state.selectedPlaylistId ?: return@LaunchedEffect
        state.playlists.firstOrNull { it.id == target }?.let { navigator.push(PlaylistDetailScreen(it)) }
    }
    // 云盘是懒加载的：切到该分段才拉一次（loadCloud 自带去重，重复调用无副作用）。
    LaunchedEffect(state.selectedTab) {
        if (state.selectedTab == 1) model.loadCloud()
    }

    // 页面级刷新入口：桌面 = 空白处右键「刷新」；Android = 下拉刷新。
    // 静默刷新（已有歌单时不置 loading）走模型单独的 refreshing 标记驱动指示器。
    val refreshing by model.refreshing.collectAsState()
    cp.player.app.ui.component.CpRefreshablePage(
        isRefreshing = refreshing,
        onRefresh = { model.refresh() },
    ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyScrollColumn(
            // 用全局统一的内容宽度：以前这里写死 1360、首页写死 1480–1840，
            // 于是切 Tab 时正文宽度会整体跳一下 —— 大屏上非常刺眼。
            modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxHeight(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                top = 8.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(BentoGap),
        ) {
            item {
                LibraryDashboard(
                    expanded = expanded,
                    title = if (expanded) "我的音乐" else (profile?.nickname ?: "我的音乐"),
                    subtitle = "共 ${state.playlists.size} 个歌单 · 收藏 ${likedIds.size} 首",
                    stats = listOf(
                        state.playlists.size.toString() to "歌单",
                        likedIds.size.toString() to "收藏",
                        downloadsState.downloadedItems.size.toString() to "下载",
                    ),
                    playlistCount = state.playlists.size,
                    onCreatePlaylist = { showCreateDialog = true },
                    onRecentPlays = { navigator.push(RecentPlaysScreen()) },
                    onDownloads = { navigator.push(DownloadsScreen()) },
                    onPlaylists = { model.selectTab(0) },
                    onCloud = { model.selectTab(1) },
                    onStorage = { navigator.push(StorageSettingsScreen()) },
                    onAppearance = { navigator.push(AppearanceSettingsScreen()) },
                    onPlayback = { navigator.push(PlaybackSettingsScreen()) },
                    onProviders = { navigator.push(ProviderManagementScreen()) },
                    onAbout = { navigator.push(AboutScreen()) },
                )
            }

            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    SectionHeader(
                        title = "曲库",
                        supportingText = when (state.selectedTab) {
                            0 -> "${state.playlists.size} 个歌单"
                            1 -> "${state.cloudSongs.size} 首云盘歌曲"
                            else -> "离线与本地内容"
                        },
                        action = {
                            // 放在标题行右侧而不是分段控件尾部：320dp 窄屏下三个分段 + 按钮会横向溢出。
                            FilledTonalIconButton(
                                onClick = { showCreateDialog = true },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            ) {
                                Icon(Icons.Filled.Add, "新建歌单")
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    LibrarySegmentedTabs(
                        filters = LibraryFilters,
                        selectedIndex = state.selectedTab,
                        onSelect = model::selectTab,
                    )
                }
            }

            when (state.selectedTab) {
                0 -> playlistsSection(
                    state = state,
                    onRetry = model::refresh,
                    isOwner = model::isOwner,
                    onPlaylistClick = { navigator.push(PlaylistDetailScreen(it)) },
                    onPlaylistOptions = { selectedPlaylist = it },
                )
                1 -> cloudSection(
                    state = state,
                    onRetry = { model.loadCloud(force = true) },
                    onSongClick = model::playCloud,
                )
                else -> item {
                    DownloadsSection(
                        state = downloadsState,
                        onOpen = { navigator.push(DownloadsScreen()) },
                    )
                }
            }
        }
    }
    } // CpRefreshablePage

    selectedPlaylist?.let { playlist ->
        PlaylistOptionsSheet(
            playlistName = playlist.name,
            isOwner = model.isOwner(playlist),
            onDismiss = { selectedPlaylist = null },
            onPlay = { model.play(playlist) },
            onAddToQueue = { model.play(playlist, addOnly = true) },
            onDelete = { confirmDelete = playlist },
            coverUrl = playlist.coverUrl,
            // 媒体库中的歌单均为已收藏/自建；非 owner 时复用 confirmDelete 确认弹窗（文案按 owner 区分）
            isFavorite = true,
            onToggleFavorite = { confirmDelete = playlist },
        )
    }

    confirmDelete?.let { playlist ->
        val owner = model.isOwner(playlist)
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (owner) "删除歌单" else "取消收藏") },
            text = {
                Text(
                    if (owner) "确定删除「${playlist.name}」吗？此操作不可恢复。"
                    else "确定取消收藏「${playlist.name}」吗？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.deleteOrUnsubscribe(playlist)
                        confirmDelete = null
                    },
                ) { Text("确定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }

    if (showCreateDialog) {
        cp.player.app.ui.component.CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreated = { created ->
                showCreateDialog = false
                if (created != null) model.refresh()
            },
        )
    }
}

// ============================================================
// 仪表盘
// ============================================================

/**
 * Bento 仪表盘。
 *
 * Expanded 下每行是 `2 : 1 : 1` 的四列栅格（主卡占半宽，右侧两张窄卡各占 1/4），
 * 与设计稿一致；窄屏把同一组卡片折叠成「整宽 → 两列并排 → 整宽」的纵向节奏。
 */
@Composable
private fun LibraryDashboard(
    expanded: Boolean,
    title: String,
    subtitle: String,
    stats: List<Pair<String, String>>,
    playlistCount: Int,
    onCreatePlaylist: () -> Unit,
    onRecentPlays: () -> Unit,
    onDownloads: () -> Unit,
    onPlaylists: () -> Unit,
    onCloud: () -> Unit,
    onStorage: () -> Unit,
    onAppearance: () -> Unit,
    onPlayback: () -> Unit,
    onProviders: () -> Unit,
    onAbout: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BentoGap)) {
        // ── 行 1：问候区 + 聆听统计 ──
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().height(132.dp),
                horizontalArrangement = Arrangement.spacedBy(BentoGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryGreeting(title, subtitle, Modifier.weight(2f))
                BentoStatCard("聆听统计", stats, Modifier.weight(2f).fillMaxHeight())
            }
        } else {
            // 手机端：顶栏标题已是 tab 名（「我的」），昵称由这里的问候区承担 —— 不重复。
            LibraryGreeting(title, subtitle, Modifier.fillMaxWidth())
            BentoStatCard("聆听统计", stats, Modifier.fillMaxWidth().height(112.dp))
        }

        // ── 行 2：主行动卡 + 两个高频入口 ──
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().height(168.dp),
                horizontalArrangement = Arrangement.spacedBy(BentoGap),
            ) {
                BentoHeroCard(
                    title = "创建歌单",
                    subtitle = "把喜欢的音乐整理成册",
                    icon = Icons.Filled.LibraryAdd,
                    onClick = onCreatePlaylist,
                    modifier = Modifier.weight(2f).fillMaxHeight(),
                )
                BentoActionCard(
                    title = "最近播放",
                    subtitle = "接着上次的节奏",
                    icon = Icons.Filled.History,
                    onClick = onRecentPlays,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
                BentoActionCard(
                    title = "离线下载",
                    subtitle = "管理离线内容",
                    icon = Icons.Filled.Download,
                    onClick = onDownloads,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                )
            }
        } else {
            BentoHeroCard(
                title = "创建歌单",
                subtitle = "把喜欢的音乐整理成册",
                icon = Icons.Filled.LibraryAdd,
                onClick = onCreatePlaylist,
                modifier = Modifier.fillMaxWidth().height(152.dp),
            )
            Row(
                Modifier.fillMaxWidth().height(124.dp),
                horizontalArrangement = Arrangement.spacedBy(BentoGap),
            ) {
                BentoActionCard(
                    title = "最近播放",
                    subtitle = "接着上次的节奏",
                    icon = Icons.Filled.History,
                    onClick = onRecentPlays,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
                BentoActionCard(
                    title = "离线下载",
                    subtitle = "管理离线内容",
                    icon = Icons.Filled.Download,
                    onClick = onDownloads,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                )
            }
        }

        // ── 行 3：偏好设置 + 曲库/云盘入口 ──
        // 高度和行 2 对齐（168dp）：以前写的是 176 / 180，肉眼分不出差别，
        // 却让两行卡片的下边缘差 4dp —— 正是这种「说不清哪里歪」的错位在拖观感。
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().height(168.dp),
                horizontalArrangement = Arrangement.spacedBy(BentoGap),
            ) {
                LibraryPreferenceCard(
                    onAppearance = onAppearance,
                    onPlayback = onPlayback,
                    onProviders = onProviders,
                    modifier = Modifier.weight(2f).fillMaxHeight(),
                )
                BentoActionCard(
                    title = "我的歌单",
                    subtitle = "$playlistCount 个歌单",
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = onPlaylists,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = neutral,
                )
                // 「存储管理」下沉到页脚胶囊行，这一列换成「云盘」——
                // 桌面端原本**完全没有**云盘入口（onCloud 传进来却没用），
                // 是窄屏有、宽屏反而没有的功能缺口。
                BentoActionCard(
                    title = "云盘",
                    subtitle = "在线曲库",
                    icon = Icons.Filled.CloudQueue,
                    onClick = onCloud,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = neutral,
                )
            }
        } else {
            LibraryPreferenceCard(
                onAppearance = onAppearance,
                onPlayback = onPlayback,
                onProviders = onProviders,
                modifier = Modifier.fillMaxWidth().height(164.dp),
            )
            Row(
                Modifier.fillMaxWidth().height(124.dp),
                horizontalArrangement = Arrangement.spacedBy(BentoGap),
            ) {
                BentoActionCard(
                    title = "我的歌单",
                    subtitle = "$playlistCount 个歌单",
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = onPlaylists,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = neutral,
                )
                BentoActionCard(
                    title = "云盘",
                    subtitle = "在线曲库",
                    icon = Icons.Filled.CloudQueue,
                    onClick = onCloud,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = neutral,
                )
            }
            BentoActionCard(
                title = "存储管理",
                subtitle = "缓存与日志",
                icon = Icons.Filled.Storage,
                onClick = onStorage,
                // 整宽窄卡：96dp 装不下「图标行 + 标题 + 副标题」三行，会把副标题裁掉。
                modifier = Modifier.fillMaxWidth().height(116.dp),
                containerColor = neutral,
            )
        }

        // ── 行 4：页脚胶囊行（低权重工具入口）──
        // 一枚孤零零的「关于」飘在版面下方，看起来像忘了排版；和「存储管理」并成一条
        // 居中页脚行，版面才算真正收住。
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(BentoGap)) {
                // 窄屏把「存储管理」留在栅格里当整宽卡，桌面端才下沉到这里。
                if (expanded) {
                    BentoPill("存储管理", Icons.Filled.Storage, onStorage)
                }
                BentoPill("关于 CPPlayer", Icons.Filled.Info, onAbout)
            }
        }
    }
}

@Composable
private fun LibraryGreeting(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    // 顶栏标题是 tab 名（「我的」），昵称靠这里露出，因此默认显示大标题。
    showTitle: Boolean = true,
) {
    Column(modifier) {
        if (showTitle) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showTitle) {
            Spacer(Modifier.height(6.dp))
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 「偏好设置」卡：标题 + 一排小方格。容器比普通卡片浅一档，方格再浅一档形成内嵌层次。 */
@Composable
private fun LibraryPreferenceCard(
    onAppearance: () -> Unit,
    onPlayback: () -> Unit,
    onProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BentoCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(18.dp),
    ) {
        Text(
            "偏好设置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BentoMiniTile("外观", Icons.Filled.Palette, onAppearance, Modifier.weight(1f).fillMaxHeight())
            BentoMiniTile("播放", Icons.Filled.GraphicEq, onPlayback, Modifier.weight(1f).fillMaxHeight())
            BentoMiniTile("音源", Icons.Filled.LibraryMusic, onProviders, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

// ============================================================
// 曲库
// ============================================================

/** 分段控件：外层一个胶囊底槽，选中段用 secondaryContainer 高亮。 */
@Composable
private fun LibrarySegmentedTabs(
    filters: List<FilterTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filters.forEachIndexed { index, filter ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(filter.icon, null, modifier = Modifier.size(18.dp))
                    Text(filter.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

private fun LazyListScope.playlistsSection(
    state: LibraryUiState,
    onRetry: () -> Unit,
    isOwner: (PlaylistSummary) -> Boolean,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onPlaylistOptions: (PlaylistSummary) -> Unit,
) {
    when {
        state.loading -> item {
            StateSurface { ContentState(title = "正在同步媒体库", message = "正在加载你的歌单", loading = true) }
        }
        state.error != null -> item {
            StateSurface {
                ContentState(
                    title = "媒体库加载失败",
                    message = state.error,
                    error = true,
                    actionLabel = "重试",
                    onAction = onRetry,
                )
            }
        }
        state.playlists.isEmpty() -> item {
            StateSurface {
                ContentState(title = "这里还没有歌单", message = "登录账号后即可同步收藏与创建的歌单")
            }
        }
        else -> items(state.playlists) { playlist ->
            PlaylistItem(
                playlist = playlist,
                isOwner = isOwner(playlist),
                onClick = { onPlaylistClick(playlist) },
                onOptionsClick = { onPlaylistOptions(playlist) },
            )
        }
    }
}

private fun LazyListScope.cloudSection(
    state: LibraryUiState,
    onRetry: () -> Unit,
    onSongClick: (Int) -> Unit,
) {
    val songs = state.cloudSongs
    when {
        state.cloudLoading -> item {
            StateSurface { ContentState(title = "正在加载云盘", message = "正在同步云盘歌曲", loading = true) }
        }
        state.cloudError != null -> item {
            StateSurface {
                ContentState(
                    title = "云盘加载失败",
                    message = state.cloudError,
                    error = true,
                    actionLabel = "重试",
                    onAction = onRetry,
                )
            }
        }
        songs.isEmpty() -> item {
            StateSurface {
                ContentState(
                    title = "云盘空空如也",
                    message = if (state.cloudLoaded) "把歌曲上传到云盘后会显示在这里" else "登录后可查看云盘歌曲",
                )
            }
        }
        else -> items(songs.size) { index ->
            SongItem(
                track = songs[index],
                index = index,
                total = songs.size,
                onClick = { onSongClick(index) },
                // 桌面端右键菜单。云盘歌曲的 id 不是标准网易云歌曲 id，
                // 加入队列的 mediaId 拼法不通用，这里只提供播放与分享两个安全动作。
                contextMenu = songContextMenuItems(
                    SongMenuActions(
                        onPlay = { onSongClick(index) },
                        onShare = { shareText(songShareText(songs[index])) },
                    )
                ),
            )
        }
    }
}

/**
 * 「下载」分段的内联摘要。
 *
 * 完整清单在 [DownloadsScreen]（下载中 / 已完成 / 本地媒体库三档），
 * 这里只做概览 + 最近完成，避免在同一个滚动容器里再嵌一层 LazyColumn。
 */
@Composable
private fun DownloadsSection(
    state: DownloadsUiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val completed = state.completedTasks
    val active = state.activeTasks
    BentoCard(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(20.dp),
        // 内容行数不固定（0–4 条已完成）⇒ 高度由内容决定，不能让卡片去撑满父容器。
        fillHeight = false,
    ) {
        // 指标行与完成列表都收一个上限宽度：桌面端这张卡最宽能到 1360dp，
        // 不限宽的话三个数字会被 SpaceEvenly 摊到整行、艺术家被甩到最右边，整张卡显得散。
        Row(
            Modifier.widthIn(max = 560.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadMetric("下载中", active.size.toString(), Modifier.weight(1f))
            DownloadMetric("已完成", completed.size.toString(), Modifier.weight(1f))
            DownloadMetric("本地媒体", state.downloadedItems.size.toString(), Modifier.weight(1f))
        }
        if (completed.isEmpty() && active.isEmpty()) {
            Text(
                "还没有下载任务。在歌曲菜单或歌单页点「下载」，任务会显示在这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp).widthIn(max = 760.dp),
            )
        } else {
            Column(
                Modifier.widthIn(max = 760.dp).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                completed.take(4).forEach { task ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Download, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            task.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            task.artist ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "打开下载管理",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DownloadMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
