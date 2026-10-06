package cp.player.app.ui.screen

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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
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
import androidx.compose.runtime.rememberCoroutineScope
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
import cp.player.app.ui.component.CpBreakpoints
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.PlaylistCoverCard
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.component.PlaylistOptionsSheet
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.app.ui.model.DownloadsUiState
import cp.player.app.ui.model.LibraryScreenModel
import cp.player.app.ui.model.LibraryUiState
import cp.player.core.music.PlaylistSummary
import kotlinx.coroutines.launch

/**
 * 「我的」页：Material 3 Expressive 仪表盘 + 曲库列表。
 *
 * 版面自上而下：
 * 1. **Bento 仪表盘** —— 问候区 / 聆听统计 / 主行动卡 / 快捷入口 / 偏好设置 / 关于。
 *    Expanded（≥840dp）走 2:1:1 的四列栅格，与设计稿一致；窄屏折叠成单列 + 两列并排。
 * 2. **我的歌单（快速查看）** —— 歌单封面卡片栅格，紧跟仪表盘。歌单是这个页面上
 *    被查看频率最高的内容，不能要求用户先滚过整面仪表盘再开一个分段才能看到。
 *    完整清单（含增删改）仍在下方「曲库 → 歌单」。
 * 3. **曲库** —— 分段控件切歌单 / 下载，列表直接铺在同一个滚动容器里
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
    FilterTab(s.library.tabDownloads, Icons.Filled.Download),
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
    val navigator = LocalNavigator.currentOrThrow
    val downloadsModel = remember { DownloadsScreenModel() }
    val downloadsState by downloadsModel.state.collectAsState()
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val profile by AppModel.userProfileFlow.collectAsState()
    val expanded = LocalIsExpanded.current

    // 滚动状态要自己持有：仪表盘上的入口（原「我的歌单」卡、快速区的「查看全部」）
    // 点击后必须**滚动**到曲库区。此前它们只做 `selectTab(0)` —— 而 tab 默认就是 0，
    // 曲库又在仪表盘之下约 500dp，点了之后屏幕上什么都不发生，看起来像坏掉。
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrollToLibrary: () -> Unit = {
        scope.launch { listState.animateScrollToItem(LIBRARY_SECTION_INDEX) }
    }

    LaunchedEffect(state.selectedPlaylistId, state.playlists) {
        val target = state.selectedPlaylistId ?: return@LaunchedEffect
        state.playlists.firstOrNull { it.id == target }?.let { navigator.push(PlaylistDetailScreen(it)) }
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
            state = listState,
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
                    title = if (expanded) s.library.myMusic
                    else profile?.nickname?.let { s.library.greeting(it) } ?: s.library.myMusic,
                    subtitle = s.library.librarySubtitle(state.playlists.size, likedIds.size),
                    stats = listOf(
                        s.library.countPlaylist(state.playlists.size.toString()),
                        s.library.countLiked(likedIds.size.toString()),
                        s.library.countDownload(downloadsState.downloadedItems.size.toString()),
                    ),
                    onCreatePlaylist = { showCreateDialog = true },
                    onRecentPlays = { navigator.push(InsightsScreen(InsightsScreen.TAB_RECENT)) },
                    // 墙是整页路由（覆盖底栏），与「模式」的语义一致 —— 见 AlbumWallScreen 的 KDoc。
                    onOpenWall = { navigator.push(AlbumWallScreen()) },
                    // 宽屏仪表盘上的「聆听统计」卡：进报告页的概览（日历墙那一屏）。
                    onOpenInsights = { navigator.push(InsightsScreen(InsightsScreen.TAB_OVERVIEW)) },
                    onDownloads = { navigator.push(DownloadsScreen()) },
                    onCloud = { navigator.push(CloudDriveScreen()) },
                    onStorage = { navigator.push(StorageSettingsScreen()) },
                    onAppearance = { navigator.push(AppearanceSettingsScreen()) },
                    onPlayback = { navigator.push(PlaybackSettingsScreen()) },
                    onProviders = { navigator.push(ProviderManagementScreen()) },
                    onAbout = { navigator.push(AboutScreen()) },
                )
            }

            // 「我的歌单」快速查看区：歌单是这个页面被查看频率最高的内容，
            // 给它一个仪表盘之后立刻可见的封面卡片栅格（手机 2 列 / 宽屏按内容宽度换算）。
            // 点「查看全部」滚动到曲库区 —— 不是切一个看不见的分段。
            item {
                PlaylistQuickGrid(
                    playlists = state.playlists,
                    loading = state.loading,
                    error = state.error,
                    onPlaylistClick = { navigator.push(PlaylistDetailScreen(it)) },
                    onSeeAll = {
                        model.selectTab(0)
                        scrollToLibrary()
                    },
                )
            }

            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    SectionHeader(
                        title = s.library.libraryTitle,
                        supportingText = when (state.selectedTab) {
                            0 -> s.library.libraryPlaylistsCount(state.playlists.size)
                            else -> s.library.libraryOffline
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
                                Icon(Icons.Filled.Add, s.library.newPlaylist)
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    LibrarySegmentedTabs(
                        filters = libraryFilters(s),
                        // 兜底 coerce：云端曾存过 selectedTab=1（旧版云盘档），
                        // 恢复出的越界值不能再喂给两档分段控件。
                        selectedIndex = state.selectedTab.coerceIn(0, libraryFilters(s).lastIndex),
                        onSelect = model::selectTab,
                    )
                }
            }

            when (state.selectedTab) {
                0 -> playlistsSection(
                    s = s,
                    state = state,
                    onRetry = model::refresh,
                    isOwner = model::isOwner,
                    onPlaylistClick = { navigator.push(PlaylistDetailScreen(it)) },
                    onPlaylistOptions = { selectedPlaylist = it },
                )
                else -> item {
                    DownloadsSection(
                        s = s,
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
            onDelete = { askDeleteOrUnsubscribe(playlist, model, confirm, s) },
            coverUrl = playlist.coverUrl,
            // 媒体库中的歌单均为已收藏/自建；非 owner 时复用同一个确认弹窗（文案按 owner 区分）
            isFavorite = true,
            onToggleFavorite = { askDeleteOrUnsubscribe(playlist, model, confirm, s) },
        )
    }

    cp.player.app.ui.component.CpConfirmHost(confirm)

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
    onOpenWall: () -> Unit,
    onOpenInsights: () -> Unit,
    onDownloads: () -> Unit,
    onCloud: () -> Unit,
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
            // 手机端：一排快捷圆形入口（最近播放 / 云盘 / 下载）。
            // 「创建歌单」hero 不再保留 —— 曲库标题行的 [+] 就是这个动作，窄屏不再重复占 152dp。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BentoGap),
            ) {
                LibraryQuickEntry(s.library.recentPlays, Icons.Filled.History, onRecentPlays, Modifier.weight(1f))
                LibraryQuickEntry(s.wall.entryLabel, Icons.Filled.GridView, onOpenWall, Modifier.weight(1f))
                LibraryQuickEntry(s.library.cloudDrive, Icons.Filled.CloudQueue, onCloud, Modifier.weight(1f))
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
                BentoActionCard(
                    title = s.library.cloudDrive,
                    subtitle = s.library.cloudDriveNote,
                    icon = Icons.Filled.CloudQueue,
                    onClick = onCloud,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = neutral,
                )
                BentoActionCard(
                    title = s.library.storageManage,
                    subtitle = s.library.storageManageNote,
                    icon = Icons.Filled.Storage,
                    onClick = onStorage,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = neutral,
                )
            }
        }

        // ── 行 4：页脚胶囊行（低权重工具入口，仅 Expanded）──
        // 「存储管理」已回到行 3；窄屏连「关于」也一并移除（关于页从设置可达）。
        if (expanded) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
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
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
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
        Text(
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
                    Text(filter.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
) {
    when {
        state.loading -> item {
            StateSurface { ContentState(title = s.library.syncingLibrary, message = s.library.syncingLibraryNote, loading = true) }
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
                ContentState(title = s.library.noPlaylists, message = s.library.noPlaylistsNote)
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
                    TextButton(onClick = onSeeAll) { Text(s.library.seeAll) }
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
                ContentState(title = s.library.noPlaylists, message = s.library.noPlaylistsNote)
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
 * 「下载」分段的内联摘要。
 *
 * 完整清单在 [DownloadsScreen]（下载中 / 已完成 / 本地媒体库三档），
 * 这里只做概览 + 最近完成，避免在同一个滚动容器里再嵌一层 LazyColumn。
 */
@Composable
private fun DownloadsSection(
    s: CpStrings,
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
            DownloadMetric(s.library.downloading, active.size.toString(), Modifier.weight(1f))
            DownloadMetric(s.library.completed, completed.size.toString(), Modifier.weight(1f))
            DownloadMetric(s.library.localMedia, state.downloadedItems.size.toString(), Modifier.weight(1f))
        }
        if (completed.isEmpty() && active.isEmpty()) {
            Text(
                s.library.noDownloads,
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
                    s.library.openDownloads,
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
