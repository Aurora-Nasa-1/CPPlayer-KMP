package cp.player.app.ui.screen

import cp.player.app.ui.component.CpText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.AppModel
import cp.player.app.ui.component.BentoActionCard
import cp.player.app.ui.component.BentoCard
import cp.player.app.ui.component.BentoGap
import cp.player.app.ui.component.BentoHeroCard
import cp.player.app.ui.component.BentoMiniTile
import cp.player.app.ui.component.BentoPill
import cp.player.app.ui.component.BentoStatCard
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpSongRowSkeleton
import cp.player.app.ui.component.CpBreakpoints
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.platform.shareText
import cp.player.app.ui.component.PlaylistCoverCard
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.component.PlaylistOptionsSheet
import cp.player.app.ui.component.ProviderFeatureGate
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.playlistShareText
import cp.player.app.ui.component.rememberActiveProviderSupports
import cp.player.app.ui.component.AddToLocalPlaylistDialog
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.CpConfirmDialog
import cp.player.app.ui.component.CpWavyProgressIndeterminate
import cp.player.app.ui.component.CpCircularWavyProgressIndeterminate
import cp.player.app.ui.component.SongDetailInfoDialog
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.cpPressScale
import cp.player.app.ui.util.UiEvents
import cp.player.core.local.LocalFolderTree
import cp.player.core.local.LocalGridSort
import cp.player.core.local.LocalLibraryAggregator
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalMediaOrigin
import cp.player.core.music.TrackSummary
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.app.ui.model.DownloadsUiState
import cp.player.app.ui.model.LibraryScreenModel
import cp.player.app.ui.model.LibraryUiState
import cp.player.app.ui.wall.PosterWallIcon
import cp.player.core.music.PlaylistSummary
import cp.player.core.provider.ProviderCapability
import kotlinx.coroutines.launch

/**
 * 「我的」页：桌面采用 [DesktopLibraryLayout]，窄屏保留轻量问候与曲库列表。
 *
 * 版面自上而下：
 * 1. **桌面** —— 标题与新建操作、紧凑快捷入口、曲库筛选与网格 / 列表切换。
 *    歌单只展示一次，更多菜单沿用下方共享的重命名与删除确认流程。
 * 2. **窄屏** —— 轻量问候与快捷入口，继续使用下述快速歌单与曲库分段。
 * **我的歌单（快速查看）** —— 歌单封面卡片栅格，紧跟问候区。歌单是这个页面上
 *    被查看频率最高的内容，不能要求用户先滚过整面仪表盘再开一个分段才能看到。
 *    完整清单（含增删改）仍在下方「曲库 → 歌单」。
 * **曲库** —— 分段控件切歌单 / 本地，列表直接铺在同一个滚动容器里
 *    （不再用 HorizontalPager，否则仪表盘只能单独占一个滚动视口）。
 *
 * 云盘不再属于这里的分段：它有几十上百首、加载态与空态都和歌单完全不同源，
 * 挤在同一个滚动容器里只会互相干扰（滚动位置共享、入口点了没反应）。
 * 现在云盘是独立路由页 [CloudDriveScreen]，仪表盘上的「云盘」卡直接跳转。
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

/**
 * ⚠️ 刻意做成**函数**而不是 `private val LibraryFilters = listOf(...)`：
 * 顶层 `val` 在类加载时求值，那时还没有语言状态 —— 写死的话标签页永远是一种语言。
 * 代价是每帧重建两个元素（微不足道），换来的是切换语言立即生效。
 */
private fun libraryFilters(s: CpStrings): List<FilterTab> = listOf(
    FilterTab(s.library.tabPlaylists, Icons.AutoMirrored.Filled.QueueMusic),
    FilterTab(s.downloads.tabLocalLibrary, Icons.Filled.FolderOpen),
)

@Composable
private fun LibraryScreenContent(model: LibraryScreenModel) {
    val s = cpStrings()
    val state by model.state.collectAsState()
    var selectedPlaylist by remember { mutableStateOf<PlaylistSummary?>(null) }
    // 「删除歌单 / 取消收藏」的二次确认。此前本页自己写了一份 AlertDialog，
    // 现在收敛到全应用统一的 [CpConfirmHost] —— 与歌单详情页、侧栏、用户主页同一份文案。
    val confirm = cp.player.app.ui.component.rememberConfirmState()
    var showCreateDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cp.player.app.ui.util.UiEvents.createPlaylistEvents.collect {
            showCreateDialog = true
        }
    }
    // 重命名目标（只有 owner 能看到入口）。与「删除 / 取消收藏」共用同一个选项弹层，
    // 但重命名不是破坏性操作，走独立的输入框而不是 CpConfirmHost。
    var renameTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    val navigator = LocalNavigator.currentOrThrow
    val downloadsModel = remember { DownloadsScreenModel() }
    val downloadsState by downloadsModel.state.collectAsState()
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val profile by AppModel.userProfileFlow.collectAsState()
    val expanded = LocalIsExpanded.current
    var gridView by rememberSaveable { mutableStateOf(true) }
    val supportsCloud = rememberActiveProviderSupports(ProviderCapability.CLOUD_DRIVE)

    LaunchedEffect(state.selectedPlaylistId, state.playlists) {
        val target = state.selectedPlaylistId ?: return@LaunchedEffect
        state.playlists.firstOrNull { it.resourceId == target }?.let { navigator.push(PlaylistDetailScreen(it)) }
    }

    // 页面级刷新入口：桌面 = 空白处右键「刷新」；Android = 下拉刷新。
    // 静默刷新（已有歌单时不置 loading）走模型单独的 refreshing 标记驱动指示器。
    val refreshing by model.refreshing.collectAsState()
    cp.player.app.ui.component.CpRefreshablePage(
        isRefreshing = refreshing,
        onRefresh = { model.refresh() },
    ) {
        DesktopLibraryLayout(
            state = state,
            subtitle = s.library.librarySubtitle(state.playlists.size, likedIds.size),
            supportsCloud = supportsCloud,
            isLocalMode = AppModel.activeProvider() == null,
            // Mobile defaults to a dense list for faster scanning and one-handed actions.
            // The desktop preference remains independent and is remembered across visits.
            gridView = if (expanded) gridView else false,
            onGridViewChange = { gridView = it },
            compact = !expanded,
            greeting = if (!expanded) profile?.nickname?.let(s.library::greeting) else null,
            actions = DesktopLibraryActions(
                onSelectTab = model::selectTab,
                onCreatePlaylist = { showCreateDialog = true },
                onSearch = { navigator.push(LibrarySearchScreen()) },
                onImport = { navigator.push(ImportPlaylistScreen()) },
                onRecentPlays = { navigator.push(InsightsScreen(InsightsScreen.TAB_RECENT)) },
                onInsights = { navigator.push(InsightsScreen(InsightsScreen.TAB_OVERVIEW)) },
                onDownloads = { navigator.push(DownloadsScreen()) },
                onLocal = { navigator.push(LocalLibraryScreen()) },
                onCloud = { navigator.push(CloudDriveScreen()) },
                onAlbumWall = { navigator.push(AlbumWallScreen()) },
                onRetry = model::refresh,
                onOpenPlaylist = { navigator.push(PlaylistDetailScreen(it)) },
                onPlaylistOptions = { selectedPlaylist = it },
                isOwner = model::isOwner,
            ),
        ) {
            LocalLibrarySection(
                s = s,
                state = downloadsState,
                model = downloadsModel,
                onOpenLocal = { initialTab -> navigator.push(LocalLibraryScreen(initialTab = initialTab)) },
                onOpenDownloads = { navigator.push(DownloadsScreen()) },
            )
        }
    } // CpRefreshablePage

    selectedPlaylist?.let { playlist ->
        PlaylistOptionsSheet(
            playlistName = playlist.name,
            isOwner = model.isOwner(playlist),
            onDismiss = { selectedPlaylist = null },
            onPlay = { model.play(playlist) },
            onAddToQueue = { model.play(playlist, addOnly = true) },
            onShare = { shareText(playlistShareText(playlist.resourceId, playlist.name)) },
            onDelete = { askDeleteOrUnsubscribe(playlist, model, confirm, s) },
            onRename = if (model.isOwner(playlist)) {
                { renameTarget = playlist }
            } else {
                null
            },
            coverUrl = playlist.coverUrl,
            // 媒体库中的歌单均为已收藏/自建；非 owner 时复用同一个确认弹窗（文案按 owner 区分）
            isFavorite = true,
            onToggleFavorite = { askDeleteOrUnsubscribe(playlist, model, confirm, s) },
        )
    }

    cp.player.app.ui.component.CpConfirmHost(confirm)

    renameTarget?.let { target ->
        cp.player.app.ui.component.RenamePlaylistDialog(
            playlistId = target.resourceId,
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onRenamed = {
                renameTarget = null
                model.refresh()
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

/**
 * 发起「删除歌单 / 取消收藏」的二次确认。
 *
 * 抽出来是因为同一个确认框被两个入口触发（owner 的「删除歌单」、非 owner 的
 * 「取消收藏」）—— 文案只写一处，两边不会漂。
 */
/** 非 composable（确认框的请求构造）：[CpStrings] 由组合内的调用方传入。 */
private fun askDeleteOrUnsubscribe(
    playlist: PlaylistSummary,
    model: LibraryScreenModel,
    confirm: cp.player.app.ui.component.CpConfirmState,
    s: CpStrings,
) {
    val owner = model.isOwner(playlist)
    confirm.request(
        title = if (owner) s.library.deletePlaylist else s.library.unfavoritePlaylist,
        message = if (owner) {
            s.library.deletePlaylistMessage(playlist.name)
        } else {
            s.library.unfavoritePlaylistMessage(playlist.name)
        },
        confirmLabel = if (owner) s.common.confirm else s.library.unfavoritePlaylist,
        destructive = owner,
        onConfirm = { model.deleteOrUnsubscribe(playlist) },
    )
}

// ============================================================
// 仪表盘
// ============================================================

/**
 * Bento 仪表盘。
 *
 * Expanded 下每行是 `2 : 1 : 1` 的四列栅格（主卡占半宽，右侧两张窄卡各占 1/4），
 * 与设计稿一致；窄屏把同一组卡片折叠成「整宽 → 两列并排 → 整宽」的纵向节奏。
 *
 * 「我的歌单」卡已移除：歌单封面栅格就铺在仪表盘正下方（[PlaylistQuickGrid]），
 * 再放一张只做跳转的卡是同一个目的占两个版面坑位。
 *
 * ## 窄屏（手机）2026-10-04 重排：首屏即内容
 *
 * 旧的窄屏分支照搬桌面 bento（统计大卡 / 创建歌单 hero / 偏好设置 / 云盘+存储 /
 * 关于 pill），整面仪表盘约 5 屏高，歌单内容全在首屏之外；且「创建歌单」与曲库区
 * 的 `[+]` 重复、「离线下载」与曲库「下载」分段重复、偏好/存储/关于属于设置类入口
 * （顶栏已有）。现在窄屏只有两块：
 *
 * 1. **轻量问候行**（昵称 + 一句话统计，删掉「聆听统计」大卡 —— 那三个数字
 *    问候语里本来就有）；
 * 2. **一排快捷圆形入口**：最近播放 / 云盘 / 下载（三个高频跳转，一排放得下）。
 *
 * 「创建歌单」hero 删除 —— 曲库标题行右侧的 `[+]` 就是这个动作；
 * 偏好/存储/关于删除 —— 全部在顶栏设置可达。桌面 expanded 保持 bento 不动。
 */
@Composable
private fun LibraryDashboard(
    expanded: Boolean,
    title: String,
    subtitle: String,
    stats: List<Pair<String, String>>,
    onCreatePlaylist: () -> Unit,
    onRecentPlays: () -> Unit,
    onOpenInsights: () -> Unit,
    onDownloads: () -> Unit,
    onLocal: () -> Unit,
    onCloud: () -> Unit,
    onAlbumWall: () -> Unit = {},
    onStorage: () -> Unit,
    onAppearance: () -> Unit,
    onPlayback: () -> Unit,
    onProviders: () -> Unit,
    onAbout: () -> Unit,
) {
    val s = cpStrings()
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
                BentoStatCard(
                    s.library.insights, stats,
                    // 原先这两行数字是**死的** —— 看得到「歌单 12 / 收藏 80」却点不进去。
                    // 现在整张卡可点，进「听歌报告 → 概览」。
                    Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .clip(MaterialTheme.shapes.extraLarge)
                        .clickable { onOpenInsights() },
                )
            }
        } else {
            // 手机端：顶栏标题已是 tab 名（「我的」），这里承担昵称露出 + 统计一句话。
            // 「聆听统计」大卡删除 —— 那三个数字问候语的 subtitle 里本来就有。
            LibraryGreeting(title, subtitle, Modifier.fillMaxWidth())
        }

        // ── 行 2：主行动卡 + 两个高频入口 ──
        val supportsCloud = rememberActiveProviderSupports(ProviderCapability.CLOUD_DRIVE)
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().height(168.dp),
                horizontalArrangement = Arrangement.spacedBy(BentoGap),
            ) {
                BentoHeroCard(
                    title = s.library.createPlaylist,
                    subtitle = s.library.createPlaylistNote,
                    icon = Icons.Filled.LibraryAdd,
                    onClick = onCreatePlaylist,
                    modifier = Modifier.weight(2f).fillMaxHeight(),
                )
                BentoActionCard(
                    title = s.library.recentPlays,
                    subtitle = s.library.recentPlaysNote,
                    icon = Icons.Filled.History,
                    onClick = onRecentPlays,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
                BentoActionCard(
                    title = s.library.offlineDownloads,
                    subtitle = s.library.offlineDownloadsNote,
                    icon = Icons.Filled.Download,
                    onClick = onDownloads,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                )
            }
        } else {
            // 手机端：一排快捷圆形入口（本地曲库 / 最近播放 / 云盘（按需） / 下载）。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LibraryQuickEntry(s.downloads.tabLocalLibrary, Icons.Filled.FolderOpen, onLocal, Modifier.weight(1f))
                LibraryQuickEntry(s.library.recentPlays, Icons.Filled.History, onRecentPlays, Modifier.weight(1f))
                LibraryQuickEntry(s.wall.screenTitle, PosterWallIcon, onAlbumWall, Modifier.weight(1f))
                ProviderFeatureGate(ProviderCapability.CLOUD_DRIVE) {
                    LibraryQuickEntry(s.library.cloudDrive, Icons.Filled.CloudQueue, onCloud, Modifier.weight(1f))
                }
                LibraryQuickEntry(s.library.tabDownloads, Icons.Filled.Download, onDownloads, Modifier.weight(1f))
            }
        }

        // ── 行 3：偏好设置 + 云盘 + 存储管理（仅 Expanded）──
        // 高度和行 2 对齐（168dp）：以前写的是 176 / 180，肉眼分不出差别，
        // 却让两行卡片的下边缘差 4dp —— 正是这种「说不清哪里歪」的错位在拖观感。
        // 云盘卡 = 真跳转（push [CloudDriveScreen]）——旧版只做 selectTab，而曲库在
        // 仪表盘之下，点完屏幕上什么也不变，等于一张假按钮。
        // 窄屏已整行移除：偏好/存储属于设置类入口（顶栏可达），云盘进了快捷入口行。
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
                ProviderFeatureGate(ProviderCapability.CLOUD_DRIVE) {
                    BentoActionCard(
                        title = s.library.cloudDrive,
                        subtitle = s.library.cloudDriveNote,
                        icon = Icons.Filled.CloudQueue,
                        onClick = onCloud,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        containerColor = neutral,
                    )
                }
                BentoActionCard(
                    title = s.library.storageManage,
                    subtitle = s.library.storageManageNote,
                    icon = Icons.Filled.Storage,
                    onClick = onStorage,
                    modifier = Modifier.weight(if (supportsCloud) 1f else 1.5f).fillMaxHeight(),
                    containerColor = neutral,
                )
            }
        }

        // ── 行 4：页脚胶囊行（低权重工具入口，仅 Expanded）──
        // 「存储管理」已回到行 3；窄屏连「关于」也一并移除（关于页从设置可达）。
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                BentoPill(s.wall.screenTitle, PosterWallIcon, onAlbumWall)
                BentoPill(s.library.aboutCpPlayer, Icons.Filled.Info, onAbout)
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
            CpText(
                title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (showTitle) {
            Spacer(Modifier.height(6.dp))
        }
        CpText(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/** 手机端快捷圆形入口：圆底图标 + 标签，一行放三个高频跳转（最近播放 / 云盘 / 下载）。 */
@Composable
private fun LibraryQuickEntry(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        CpText(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.padding(top = 4.dp),
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
    val s = cpStrings()
    BentoCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(18.dp),
    ) {
        CpText(
            s.library.preferences,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BentoMiniTile(s.library.appearance, Icons.Filled.Palette, onAppearance, Modifier.weight(1f).fillMaxHeight())
            BentoMiniTile(s.library.playback, Icons.Filled.GraphicEq, onPlayback, Modifier.weight(1f).fillMaxHeight())
            BentoMiniTile(s.library.providers, Icons.Filled.LibraryMusic, onProviders, Modifier.weight(1f).fillMaxHeight())
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
                    CpText(filter.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/**
 * `LazyListScope` 扩展，**不是** `@Composable` ⇒ 读不到 `cpStrings()`。
 * 状态与文案都由 `@Composable` 宿主当参数传进来（同 `recentPlaysRows` 的约定，
 * 见 AGENTS.md 关于 `LazyScrollColumn` content lambda 的坑）。
 */
private fun LazyListScope.playlistsSection(
    s: CpStrings,
    state: LibraryUiState,
    onRetry: () -> Unit,
    isOwner: (PlaylistSummary) -> Boolean,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onPlaylistOptions: (PlaylistSummary) -> Unit,
    onGoLocal: () -> Unit,
) {
    when {
        state.loading -> item {
            // 骨架屏而不是「一张写着『正在同步』的卡」：结构先到位、数据后填，
            // 页面看起来是在**长出来**而不是重画一遍（见 CpSongRowSkeleton）。
            // 文案保留一行 —— 用户仍然知道在等什么，而不是对着会动的灰块猜。
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                CpText(
                    text = s.library.syncingLibraryNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                CpSongRowSkeleton(rows = 6)
            }
        }
        state.error != null -> item {
            StateSurface {
                ContentState(
                    title = s.library.libraryLoadFailed,
                    message = state.error,
                    error = true,
                    actionLabel = s.library.retry,
                    onAction = onRetry,
                )
            }
        }
        state.playlists.isEmpty() -> item {
            StateSurface {
                if (AppModel.activeProvider() == null) {
                    ContentState(
                        title = "本地音乐模式",
                        message = "当前未导入在线音源插件。您可以在「本地与离线」中扫描或管理本地设备中的音乐文件。",
                        actionLabel = "前往本地与离线",
                        onAction = onGoLocal,
                    )
                } else {
                    ContentState(title = s.library.noPlaylists, message = s.library.noPlaylistsNote)
                }
            }
        }
        else -> items(
            items = state.playlists,
            key = { playlist -> "playlist:${playlist.resourceId}:${playlist.id}" },
        ) { playlist ->
            PlaylistItem(
                playlist = playlist,
                isOwner = isOwner(playlist),
                onClick = { onPlaylistClick(playlist) },
                onOptionsClick = { onPlaylistOptions(playlist) },
            )
        }
    }
}

/** [LazyScrollColumn] 里「曲库」区块的 item 序号：0=仪表盘、1=歌单快速区、2=曲库标题。 */
private const val LIBRARY_SECTION_INDEX = 2

/** 歌单快速区展示的**行数**（列数随宽度变化，总个数 = 列数 × 行数）。 */
private const val QUICK_GRID_ROWS = 2

/**
 * 「我的歌单」快速查看区 —— 仪表盘正下方的歌单封面卡片栅格。
 *
 * 推定依据：进入「我的」页的用户大概率想快速查看歌单，而旧版面里歌单列表埋在
 * 整面仪表盘 + 分段控件之下（首屏外 600dp 开外）。这里用与首页一致的
 * [PlaylistCoverCard] 栅格把歌单提前到第二屏；手机 2 列起，宽屏按内容宽度
 * 走 [CpSpacing.gridColumns] 换算。完整清单（含增删改与更多菜单）仍在
 * 「曲库 → 歌单」，由「查看全部」滚动可达。
 *
 * ⚠️ 快速区**刻意不带**右键/更多菜单 —— 它是预览不是管理界面，动作入口
 * （播放整单 / 加入队列 / 下载 / 分享）都在歌单详情页与曲库列表行上，
 * 两处菜单并存反而让「同一张卡片在不同位置行为不同」。
 */
@Composable
private fun PlaylistQuickGrid(
    playlists: List<PlaylistSummary>,
    loading: Boolean,
    error: String?,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onSeeAll: () -> Unit,
    onGoLocal: () -> Unit,
) {
    val s = cpStrings()
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            title = s.library.myPlaylists,
            supportingText = when {
                loading && playlists.isEmpty() -> s.library.syncingLibrary
                else -> s.library.myPlaylistsCount(playlists.size)
            },
            modifier = Modifier.padding(top = 8.dp),
            action = {
                if (playlists.isNotEmpty()) {
                    TextButton(onClick = onSeeAll) { CpText(s.library.seeAll) }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        when {
            loading && playlists.isEmpty() -> StateSurface {
                ContentState(title = s.library.syncingLibrary, message = s.library.syncingLibraryNote, loading = true)
            }
            error != null && playlists.isEmpty() -> StateSurface {
                ContentState(title = s.library.libraryLoadFailed, message = error, error = true)
            }
            playlists.isEmpty() -> StateSurface {
                if (AppModel.activeProvider() == null) {
                    ContentState(
                        title = "本地音乐模式",
                        message = "未接入音源插件，可切换到本地曲库浏览或扫描本地设备中的歌曲",
                        actionLabel = "管理本地曲库",
                        onAction = onGoLocal,
                    )
                } else {
                    ContentState(title = s.library.noPlaylists, message = s.library.noPlaylistsNote)
                }
            }
            else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 窄屏固定 2 列（单列封面浪费、3 列在 360dp 手机上封面只有 ~100dp）；
                // 宽屏按**本容器实际宽度**换算 —— 窗口宽度含两侧留白与滚动条槽，
                // 直接拿去算会多出 1–2 列把卡片压窄。
                val columns = if (maxWidth >= CpBreakpoints.medium) {
                    CpSpacing.gridColumns(maxWidth)
                } else {
                    2
                }
                val shown = playlists.take(columns * QUICK_GRID_ROWS)
                Column(verticalArrangement = Arrangement.spacedBy(BentoGap)) {
                    shown.chunked(columns).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(BentoGap),
                        ) {
                            row.forEach { playlist ->
                                PlaylistCoverCard(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist) },
                                    fillWidth = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // 尾行不满时补空位：weight 相同才能保持列宽一致。
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「本地音乐」分段的内联概览（M3 Expressive 风格）。
 *
 * 完整清单在 [LocalLibraryScreen]（歌曲 / 专辑 / 艺术家 / 收藏 / 文件夹）。
 * 这里提供本地曲库的直达控制台、分类磁贴导航与最近添加歌曲速览，将下载管理作为配套辅助入口保留。
 */
@Composable
private fun LocalLibrarySection(
    s: CpStrings,
    state: DownloadsUiState,
    model: DownloadsScreenModel,
    onOpenLocal: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val localCount = state.localAudioItems.size
    val losslessCount = state.losslessCount
    val completedCount = state.completedTasks.size
    val activeCount = state.activeTasks.size
    val playbackState by AppModel.playback.state.collectAsState()
    val currentPlayingTrackId = playbackState.currentTrack?.id
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val confirm = cp.player.app.ui.component.rememberConfirmState()

    var optionsTargetSong by remember { mutableStateOf<LocalMediaItem?>(null) }
    var showInfoTrack by remember { mutableStateOf<TrackSummary?>(null) }
    var addToCloudPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }
    var addToLocalPlaylistPath by remember { mutableStateOf<String?>(null) }
    var deleteTargetSong by remember { mutableStateOf<LocalMediaItem?>(null) }
    var removeTargetSong by remember { mutableStateOf<LocalMediaItem?>(null) }

    val albumsCount = remember(state.localAudioItems) {
        LocalLibraryAggregator.albums(state.localAudioItems, LocalGridSort.NAME).size
    }
    val artistsCount = remember(state.localAudioItems) {
        LocalLibraryAggregator.artists(state.localAudioItems, LocalGridSort.NAME).size
    }
    val foldersCount = remember(state.localAudioItems) {
        LocalFolderTree.build(state.localAudioItems).size
    }
    val favoritesCount = state.favoritePaths.size
    val playlistsCount = state.localPlaylists.size

    val toggleCloudFav: (String) -> Unit = remember(likedIds) {
        { cloudId: String ->
            scope.launch {
                val cloudFavId = runCatching {
                    cp.player.core.music.CPMediaId.parse(cloudId).resourceId
                }.getOrDefault(cloudId)
                val target = cloudFavId !in likedIds
                AppModel.playback.toggleFavoriteFor(cloudId)
                UiEvents.notify(s.downloads.favoriteCloudDone(target))
            }
        }
    }
    val toggleLocalFav: (String) -> Unit = {
        val now = AppModel.localMedia.favorites.toggle(it)
        UiEvents.notify(if (now) s.downloads.addedToFavorites else s.downloads.removedFromFavorites)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ── 1. M3E 核心控制台卡片 ──
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        CpText(
                            s.downloads.tabLocalLibrary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        CpText(
                            if (localCount > 0) {
                                buildString {
                                    append(s.downloads.localSongCount(localCount))
                                    if (losslessCount > 0) {
                                        append(" · ")
                                        append(s.downloads.losslessCount(losslessCount))
                                    }
                                }
                            } else {
                                s.downloads.emptyLocalTitle
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onOpenLocal(0) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        CpText(s.downloads.browseLibrary, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp))
                    }
                }

                // 扫描动态状态条
                if (state.scanning) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CpCircularWavyProgressIndeterminate(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(10.dp))
                                CpText(
                                    state.scanProgress?.let { s.downloads.scanningFound(it.scanned) }
                                        ?: s.downloads.scanning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            CpWavyProgressIndeterminate(
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // 快捷主动作组 (随机漫游 / 顺序播放 / 扫描设备)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (localCount > 0) {
                        FilledTonalButton(
                            onClick = { model.shuffleAll(state.localAudioItems) },
                            shape = MaterialTheme.shapes.large,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).cpPressScale(),
                        ) {
                            Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            CpText(s.downloads.quickShuffle, maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = { model.playAll(state.localAudioItems) },
                            shape = MaterialTheme.shapes.large,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).cpPressScale(),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            CpText(s.downloads.quickPlay, maxLines = 1)
                        }
                        FilledTonalIconButton(
                            onClick = { model.startScan(s) },
                            enabled = !state.scanning,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.cpPressScale(),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = s.downloads.scanDevice, Modifier.size(18.dp))
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { model.startScan(s) },
                            enabled = !state.scanning,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().cpPressScale(),
                        ) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            CpText(s.downloads.scanDevice)
                        }
                    }
                }
            }
        }

        // ── 2. M3E 分类磁贴导航 (2x3 网格) ──
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalCategoryPill(
                    title = s.downloads.tabSongs,
                    count = localCount,
                    icon = Icons.Filled.MusicNote,
                    onClick = { onOpenLocal(0) },
                    modifier = Modifier.weight(1f),
                )
                LocalCategoryPill(
                    title = s.downloads.tabAlbums,
                    count = albumsCount,
                    icon = Icons.Filled.Album,
                    onClick = { onOpenLocal(1) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalCategoryPill(
                    title = s.downloads.tabArtists,
                    count = artistsCount,
                    icon = Icons.Filled.Person,
                    onClick = { onOpenLocal(2) },
                    modifier = Modifier.weight(1f),
                )
                LocalCategoryPill(
                    title = s.downloads.tabLocalFavorites,
                    count = favoritesCount,
                    icon = Icons.Filled.Favorite,
                    onClick = { onOpenLocal(3) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalCategoryPill(
                    title = s.downloads.tabLocalPlaylists,
                    count = playlistsCount,
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = { onOpenLocal(4) },
                    modifier = Modifier.weight(1f),
                )
                LocalCategoryPill(
                    title = s.downloads.tabFolders,
                    count = foldersCount,
                    icon = Icons.Filled.FolderOpen,
                    onClick = { onOpenLocal(5) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── 3. 最近添加歌曲速览 ──
        if (state.recentAudioItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    CpText(
                        s.downloads.recentAdded,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { onOpenLocal(0) }) {
                        CpText(s.downloads.viewAll, style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp))
                    }
                }
                state.recentAudioItems.forEachIndexed { index, song ->
                    val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                    val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                    LocalSongRow(
                        song = song,
                        isCurrentlyPlaying = currentPlayingTrackId == mediaId,
                        likedIds = likedIds,
                        favoritePaths = state.favoritePaths,
                        model = model,
                        strings = s,
                        onPlay = {
                            val allIdx = state.localAudioItems.indexOfFirst { it.path == song.path }.coerceAtLeast(0)
                            model.playAll(state.localAudioItems, allIdx)
                        },
                        onToggleCloudFav = toggleCloudFav,
                        onToggleLocalFav = toggleLocalFav,
                        onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                        onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                        onRemoveSong = { removeTargetSong = it },
                        onDeleteSong = { deleteTargetSong = it },
                        onShowInfo = { showInfoTrack = it },
                        onOptionsClick = { optionsTargetSong = it },
                        index = index,
                        total = state.recentAudioItems.size,
                    )
                }
            }
        }

        // ── 4. 离线下载管理卡片 ──
        BentoCard(
            onClick = onOpenDownloads,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentPadding = PaddingValues(16.dp),
            fillHeight = false,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    CpText(
                        s.library.offlineDownloads,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (activeCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            CpText(
                                s.downloads.downloadingCount(activeCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpText(
                        s.downloads.downloadedCount(completedCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    optionsTargetSong?.let { song ->
        val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
        val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
        val isDownloaded = song.source == LocalMediaOrigin.DOWNLOADED || !song.originalMediaId.isNullOrBlank()
        val cloudMediaId = song.originalMediaId
        val cloudFavId = cloudMediaId?.let { runCatching { cp.player.core.music.CPMediaId.parse(it).resourceId }.getOrDefault(it) }
        val isCloudFav = cloudFavId != null && cloudFavId in likedIds
        val isLocalFav = song.path in state.favoritePaths
        val isFav = if (isDownloaded && cloudMediaId != null) isCloudFav else isLocalFav
        val trackSummary = remember(song) {
            TrackSummary(
                id = mediaId,
                name = song.title,
                artist = song.artist ?: s.downloads.unknownArtist,
                album = song.album,
                coverUrl = song.coverUri,
                durationMs = song.durationMs,
                originalMediaId = song.originalMediaId,
            )
        }

        SongOptionsSheet(
            songName = song.title,
            artistName = song.artist ?: s.downloads.unknownArtist,
            isFavorite = isFav,
            isDownloaded = true,
            onDismiss = { optionsTargetSong = null },
            onPlay = {
                val allIdx = state.localAudioItems.indexOfFirst { it.path == song.path }.coerceAtLeast(0)
                model.playAll(state.localAudioItems, allIdx)
            },
            onToggleFavorite = {
                if (isDownloaded && cloudMediaId != null) {
                    toggleCloudFav(cloudMediaId)
                } else {
                    toggleLocalFav(song.path)
                }
            },
            onAddToQueue = {
                if (!isVideo) {
                    scope.launch {
                        AppModel.playback.addToQueue(mediaId)
                        UiEvents.notify(s.library.queuedToPlay)
                    }
                }
            },
            onPlayNext = {
                if (!isVideo) {
                    scope.launch {
                        AppModel.playback.addNextToQueue(mediaId)
                        UiEvents.notify(s.library.playNext)
                    }
                }
            },
            onAddToPlaylist = {
                if (isDownloaded && cloudMediaId != null) {
                    addToCloudPlaylistTrack = trackSummary
                } else {
                    addToLocalPlaylistPath = song.path
                }
            },
            onAddToLocalPlaylist = if (isDownloaded && cloudMediaId != null) {
                { addToLocalPlaylistPath = song.path }
            } else null,
            onDownload = {},
            onShowInfo = {
                showInfoTrack = trackSummary
            },
            onShare = {
                if (!song.originalMediaId.isNullOrBlank()) {
                    val share = cp.player.app.ui.component.songShareText(trackSummary)
                    cp.player.app.platform.shareText(share)
                } else {
                    cp.player.app.platform.shareText("${song.title} - ${song.artist ?: s.downloads.unknownArtist}")
                }
            },
            coverUrl = song.coverUri,
            onOpenInFileManager = {
                cp.player.app.platform.openInFileManager(song.path)
            },
            onRemoveFromLibrary = {
                removeTargetSong = song
            },
            onDelete = {
                deleteTargetSong = song
            },
            deleteLabel = s.downloads.deleteLocalFile,
        )
    }

    cp.player.app.ui.component.CpConfirmHost(confirm)

    addToCloudPlaylistTrack?.let { track ->
        val cloudId = track.originalMediaId ?: track.id
        AddToPlaylistSheet(
            trackIds = listOf(cloudId),
            onDismiss = { addToCloudPlaylistTrack = null },
        )
    }

    addToLocalPlaylistPath?.let { path ->
        AddToLocalPlaylistDialog(
            trackPaths = listOf(path),
            onDismiss = { addToLocalPlaylistPath = null },
        )
    }

    removeTargetSong?.let { song ->
        CpConfirmDialog(
            title = s.downloads.removeConfirmTitle,
            message = s.downloads.removeConfirmMessage(song.title),
            confirmLabel = s.downloads.removeFromLibrary,
            destructive = false,
            onConfirm = {
                model.removeLocalItem(song, s)
                removeTargetSong = null
            },
            onDismiss = { removeTargetSong = null },
        )
    }

    deleteTargetSong?.let { song ->
        CpConfirmDialog(
            title = s.downloads.deleteLocalFile,
            message = s.downloads.deleteLocalFileMessage(song.title),
            confirmLabel = s.downloads.delete,
            destructive = true,
            onConfirm = {
                model.deleteLocalFile(song, s)
                deleteTargetSong = null
            },
            onDismiss = { deleteTargetSong = null },
        )
    }

    showInfoTrack?.let { track ->
        SongDetailInfoDialog(
            track = track,
            onDismiss = { showInfoTrack = null },
        )
    }
}

@Composable
private fun LocalCategoryPill(
    title: String,
    count: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .cpPressScale()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            CpText(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (count > 0) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    CpText(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
