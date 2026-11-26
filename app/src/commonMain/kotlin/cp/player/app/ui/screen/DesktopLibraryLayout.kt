package cp.player.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.theme.cpCardSurface
import cp.player.app.ui.theme.cpNestedSurface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightSource
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpSongRowSkeleton
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpSplitButton
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.CpConnectedChoice
import cp.player.app.ui.component.CpContextMenu
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.CpAnchoredMenu
import cp.player.app.ui.component.cpPressScale
import cp.player.app.ui.component.legacySegmentGridShape
import cp.player.app.ui.component.ProvideCpTextFullDisplay
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LocalWindowChromeActive
import cp.player.app.ui.component.PageHeader
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.component.PlaylistMenuActions
import cp.player.app.ui.component.playlistContextMenuItems
import cp.player.app.ui.component.desktopScrollbarGutter
import cp.player.app.ui.model.LibraryUiState
import cp.player.app.ui.util.rememberCoverImageRequest
import cp.player.app.ui.wall.PosterWallIcon
import cp.player.core.music.PlaylistSummary

internal class DesktopLibraryActions(
    val onSelectTab: (Int) -> Unit,
    val onCreatePlaylist: () -> Unit,
    val onSearch: () -> Unit,
    val onImport: () -> Unit,
    val onRecentPlays: () -> Unit,
    val onInsights: () -> Unit,
    val onDownloads: () -> Unit,
    val onLocal: () -> Unit,
    val onCloud: () -> Unit,
    val onAlbumWall: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenPlaylist: (PlaylistSummary) -> Unit,
    val onPlaylistOptions: (PlaylistSummary) -> Unit,
    val isOwner: (PlaylistSummary) -> Boolean,
    val menuActionsOf: (PlaylistSummary) -> PlaylistMenuActions?,
)

/**
 * One collection, not a dashboard followed by two copies of the same playlists.
 * The local/offline overview is a slot so this layout never owns backend state.
 */
@Composable
internal fun DesktopLibraryLayout(
    state: LibraryUiState,
    supportsCloud: Boolean,
    isLocalMode: Boolean,
    actions: DesktopLibraryActions,
    gridView: Boolean,
    onGridViewChange: (Boolean) -> Unit,
    compact: Boolean = false,
    localContent: @Composable () -> Unit,
) {
    val s = cpStrings()
    val listState = rememberLazyListState()
    val playlistList = !gridView && state.selectedTab == 0
    val playlistGap = CpSpacing.playlistRowGap
    val sectionTail = if (playlistList) CpSpacing.section - playlistGap else 0.dp
    LaunchedEffect(state.selectedTab, gridView) { listState.scrollToItem(0) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize()) {
            val columns = CpSpacing.gridColumns(
                maxWidth - CpSpacing.pageHorizontal * 2 - desktopScrollbarGutter,
                min = if (compact) 2 else 3,
            )
            LazyScrollColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    top = CpSpacing.pageTop,
                    bottom = CpSpacing.pageTop,
                ),
                verticalArrangement = Arrangement.spacedBy(
                    if (playlistList) playlistGap else CpSpacing.section,
                ),
            ) {
                // 「共 N 个歌单 · 收藏 M 首」页头统计行已移除：手机端三个动作按钮
                // 下移到「歌单 / 本地媒体库」分段右侧；桌面端在这里独占一行放
                // 「新建 / 导入」分体按钮组 + 搜索按钮。
                if (!compact) {
                    item(key = "heading") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CpSplitButton(
                                onLeadingClick = actions.onCreatePlaylist,
                                leadingContent = {
                                    Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    CpText(s.library.newPlaylist)
                                },
                                onTrailingClick = actions.onImport,
                                trailingContent = { Icon(Icons.Filled.FileUpload, s.library.importPlaylist) },
                                modifier = Modifier.width(236.dp).height(CpSpacing.touchTarget),
                            )
                            DesktopLibraryActionButton(s.library.searchLibrary, Icons.Filled.Search, actions.onSearch)
                        }
                    }
                }
                item(key = "shortcuts") {
                    val shortcuts = buildList {
                        add(Triple(s.library.recentPlays, Icons.Filled.History, actions.onRecentPlays))
                        add(Triple(s.downloads.tabLocalLibrary, Icons.Filled.FolderOpen, actions.onLocal))
                        add(Triple(s.library.tabDownloads, Icons.Filled.Download, actions.onDownloads))
                        if (supportsCloud) add(Triple(s.library.cloudDrive, Icons.Filled.CloudQueue, actions.onCloud))
                        add(Triple(s.wall.screenTitle, PosterWallIcon, actions.onAlbumWall))
                        add(Triple(s.library.insights, Icons.Filled.Insights, actions.onInsights))
                    }
                    Column(
                        Modifier.padding(bottom = sectionTail),
                        verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
                    ) {
                        val rows = shortcuts.chunked(if (compact) 3 else shortcuts.size)
                        rows.forEachIndexed { rowIndex, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap)) {
                                row.forEachIndexed { columnIndex, (label, icon, onClick) ->
                                    DesktopLibraryShortcut(
                                        label, icon, onClick, Modifier.weight(1f),
                                        // 手机端 2×3 网格里强调一格会像"选中态"，六格统一底色；
                                        // 桌面单行仍保留「最近播放」作为唯一表达焦点。
                                        prominent = !compact && icon == Icons.Filled.History,
                                        // 手机端按 M3E list 拼接：外圈 20dp，相邻内角 4dp。
                                        shape = if (compact) {
                                            legacySegmentGridShape(
                                                row = rowIndex,
                                                rowCount = rows.size,
                                                column = columnIndex,
                                                rowSize = row.size,
                                            )
                                        } else {
                                            MaterialTheme.shapes.large
                                        },
                                    )
                                }
                                if (compact) repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                item(key = "collection-toolbar") {
                    Column(
                        modifier = Modifier.padding(bottom = sectionTail),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column {
                            CpText(
                                s.library.libraryTitle,
                                style = MaterialTheme.typography.titleLarge,
                                // 手机端标题降一档字重：这一行与下方分段控件同处一屏，
                                // 过粗会压过分段；桌面保持 Bold 维持页头分量。
                                fontWeight = if (compact) FontWeight.SemiBold else FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (state.selectedTab != 0) {
                                CpText(
                                    s.library.libraryOffline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CpConnectedChoice(
                                selectedIndex = state.selectedTab.coerceIn(0, 1),
                                labels = listOf(s.library.tabPlaylists, s.downloads.tabLocalLibrary),
                                onSelect = actions.onSelectTab,
                            )
                            Spacer(Modifier.weight(1f))
                            if (compact) {
                                // 手机端：三个动作按钮回到分段右侧，沿用之前的行内位置。
                                DesktopLibraryActionButton(s.library.searchLibrary, Icons.Filled.Search, actions.onSearch)
                                DesktopLibraryActionButton(s.library.importPlaylist, Icons.Filled.FileUpload, actions.onImport)
                                DesktopLibraryActionButton(s.library.newPlaylist, Icons.Filled.Add, actions.onCreatePlaylist)
                            } else if (state.selectedTab == 0) {
                                DesktopLibraryViewToggle(gridView, onGridViewChange)
                            }
                        }
                    }
                }
                when {
                    state.selectedTab != 0 -> item(key = "local") { localContent() }
                    state.loading && state.playlists.isEmpty() -> item(key = "loading") {
                        CpSongRowSkeleton(rows = 4)
                    }
                    state.playlists.isEmpty() -> item(key = "empty") {
                        ProvideCpTextFullDisplay {
                        ContentState(
                            title = when {
                                state.error != null -> s.library.libraryLoadFailed
                                isLocalMode -> s.downloads.tabLocalLibrary
                                else -> s.library.noPlaylists
                            },
                            message = state.error ?: if (isLocalMode) s.library.libraryOffline else s.library.noPlaylistsNote,
                            error = state.error != null,
                            actionLabel = when {
                                state.error != null -> s.library.retry
                                isLocalMode -> s.downloads.tabLocalLibrary
                                else -> s.library.newPlaylist
                            },
                            onAction = when {
                                state.error != null -> actions.onRetry
                                isLocalMode -> actions.onLocal
                                else -> actions.onCreatePlaylist
                            },
                        )
                        }
                    }
                    else -> {
                        if (state.error != null) {
                            item(key = "refresh-error") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 刷新失败的原因（后端返回体 / 网络异常）是用户唯一线索，
                                    // 单行会把「HTTP 500 …」之后的内容全裁掉。
                                    CpText(
                                        state.error,
                                        Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 3,
                                    )
                                    TextButton(onClick = actions.onRetry) { CpText(s.library.retry) }
                                }
                            }
                        }
                        if (gridView) {
                            items(state.playlists.chunked(columns), key = { "grid-${it.first().resourceId}" }) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(CpSpacing.pageHorizontal)) {
                                    row.forEach { playlist ->
                                        DesktopLibraryPlaylist(
                                            playlist = playlist,
                                            onOpen = { actions.onOpenPlaylist(playlist) },
                                            onOptions = { actions.onPlaylistOptions(playlist) },
                                            menuActions = actions.menuActionsOf(playlist),
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        } else {
                            items(state.playlists, key = { "list-${it.resourceId}" }) { playlist ->
                                PlaylistItem(
                                    playlist = playlist,
                                    isOwner = actions.isOwner(playlist),
                                    onClick = { actions.onOpenPlaylist(playlist) },
                                    onOptionsClick = { actions.onPlaylistOptions(playlist) },
                                    menuActions = actions.menuActionsOf(playlist),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopLibraryShortcut(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    shape: Shape = MaterialTheme.shapes.large,
) {
    val isDark = LocalIsDarkTheme.current
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.16f else 0.10f)
    } else {
        cp.player.app.ui.theme.cpCardSurface()
    }
    Surface(
        onClick = onClick,
        modifier = modifier.cpPressScale(),
        shape = shape,
        color = containerColor,
    ) {
        Column(
            Modifier.height(CpSpacing.musicShortcutHeight).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            CpText(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                textAlign = TextAlign.Center,
                color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun DesktopLibraryPlaylist(
    playlist: PlaylistSummary,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
    menuActions: PlaylistMenuActions? = null,
) {
    val s = cpStrings()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    val desktopMenu = menuActions != null && LocalWindowChromeActive.current
    val resolvedItems = menuActions?.let { playlistContextMenuItems(it) }
        ?: listOf(CpContextMenuItem(s.player.more, Icons.Filled.MoreVert, onClick = onOptions))
    val moreButton: @Composable () -> Unit = {
        DesktopLibraryIcon(s.player.more, Icons.Filled.MoreVert) {
            if (desktopMenu) menuExpanded = true else onOptions()
        }
    }
    CpContextMenu(
        items = resolvedItems,
        modifier = modifier.hoverable(interaction),
    ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)
                .clickable {
                    CoverFlight.openPlaylist(playlist.resourceId, playlist.coverUrl)
                    onOpen()
                }
                // Matches the theme's large corner; flight API takes a Dp.
                .coverFlightSource(CoverFlight.playlistKey(playlist.resourceId), 16.dp),
        ) {
            CpCoverPlaceholder(Modifier.fillMaxSize(), corner = 16.dp, animated = false)
            if (!playlist.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = rememberCoverImageRequest(playlist.coverUrl, targetPx = 512),
                    contentDescription = playlist.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (hovered) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    shape = MaterialTheme.shapes.large,
                    color = cpNestedSurface(),
                ) {
                    Icon(Icons.Filled.ChevronRight, null, Modifier.padding(8.dp).size(24.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                CpText(
                    playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                CpText(
                    s.account.songCount(playlist.trackCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (desktopMenu) {
                CpAnchoredMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    items = resolvedItems,
                ) { moreButton() }
            } else {
                moreButton()
            }
        }
    }
    }
}

@Composable
internal fun DesktopLibraryViewToggle(gridView: Boolean, onChange: (Boolean) -> Unit) {
    val s = cpStrings()
    CpConnectedChoice(
        selectedIndex = if (gridView) 0 else 1,
        labels = listOf(s.library.gridView, s.library.listView),
        icons = listOf(Icons.Filled.GridView, Icons.AutoMirrored.Filled.ViewList),
        iconOnly = true,
        onSelect = { onChange(it == 0) },
    )
}

@Composable
private fun DesktopLibraryActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DesktopLibraryTooltip(label) {
        FilledIconButton(
            onClick = onClick,
            modifier = modifier.size(40.dp).cpPressScale(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = cpCardSurface(),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DesktopLibraryIcon(label: String, icon: ImageVector, onClick: () -> Unit) {
    DesktopLibraryTooltip(label) {
        IconButton(onClick = onClick) {
            Icon(icon, label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopLibraryTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { CpText(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}
