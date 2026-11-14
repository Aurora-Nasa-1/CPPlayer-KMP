package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.util.resized

/** Playlist sort order shared by the playlist options sheet and screen models. */
enum class PlaylistSortType { DEFAULT, NAME, ARTIST }

/**
 * 弹层统一边距。与设置页的 16dp、筛选区的 20dp 刻意分开：弹层内容自带圆角容器，
 * 左右 20dp 才能让封面、主胶囊、分段列表的**外缘**落在同一条竖线上。
 */
private val SheetHorizontalPadding = 20.dp

/** 头部缩略图边长。与设置列表的 60dp 接近，但弹层里要压住标题，取 72dp。 */
private val SheetHeroSize = 72.dp

/** 分段列表行高下限。图标块 36dp 加上下内边距后要留出稳定的触控高度。 */
private val SheetRowMinHeight = 58.dp

/**
 * 歌曲「更多」弹层 —— 手机端**唯一**一份。
 *
 * ## 版式（M3 Expressive）
 *
 * ```
 * 封面 + 歌名 / 歌手
 * [ 播放（主胶囊） ] [收藏] [分享]     ← 72dp 等高，主命令独占一半以上
 * ┌ 下一首播放 ┐
 * │ 添加到队列 │
 * │ 下载       │  ← 同一组 segmented list，行高 58dp、缝宽 2dp
 * │ 添加到歌单 │
 * │ 歌曲信息   │
 * └────────────┘
 * [ 移除 / 删除 ]                      ← 独立错误色组
 * ```
 *
 * 动作顺序与 [songContextMenuItems] 的桌面右键菜单逐项对齐；组内行是**同一张**
 * M3E 列表（`legacySegmentShape`），不再用每行一张独立胶囊 —— 那会让 8 个动作
 * 看起来像 5 张互不相干的卡片，滚动时边界不断打断视线。
 *
 * ## ⚠️ 八个动作全部**必填**，别再改回 `= null`
 *
 * 曾经它们都是可选的，调用方「有什么给什么」⇒ 首页只给了 6 项、歌单详情给了 8 项，
 * 于是**同一个「歌曲更多」在不同页面长得不一样**。改成必填后，任何页面想用它，
 * 就得先把分享与歌曲信息也接上 —— 漏了直接编译不过。
 */
@Composable
fun SongOptionsSheet(
    songName: String,
    artistName: String,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToQueue: () -> Unit,
    /** 「下一首播放」：插到当前曲目之后（电台 / 长队列里追加到末尾基本听不到）。 */
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onShowInfo: () -> Unit = {},
    onShare: () -> Unit = {},
    onPlayMv: (() -> Unit)? = null,
    coverUrl: String? = null,
    onAddToLocalPlaylist: (() -> Unit)? = null,
    onOpenInFileManager: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onRemoveFromLibrary: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String? = null,
) {
    val s = cpStrings()
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SheetHorizontalPadding)
                    .padding(top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SheetHeader(
                    title = songName,
                    subtitle = artistName,
                    coverUrl = coverUrl,
                    circleCover = true,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetHeroButton(
                        label = s.player.play,
                        icon = Icons.Filled.PlayArrow,
                        onClick = { onPlay(); onDismiss() },
                        modifier = Modifier.weight(1f),
                    )
                    SheetIconButton(
                        icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        label = if (isFavorite) s.contextMenu.unfavorite else s.contextMenu.favorite,
                        onClick = { onToggleFavorite(); onDismiss() },
                        containerColor = if (isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (isFavorite) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                    SheetIconButton(
                        icon = Icons.Filled.Share,
                        label = s.player.share,
                        onClick = { onShare(); onDismiss() },
                        shape = CircleShape,
                    )
                }

                val actionRows = buildList {
                    add(
                        SheetAction(
                            label = s.contextMenu.playNext,
                            icon = Icons.Filled.SkipNext,
                            onClick = { onPlayNext(); onDismiss() },
                        )
                    )
                    add(
                        SheetAction(
                            label = s.contextMenu.addToQueue,
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            onClick = { onAddToQueue(); onDismiss() },
                        )
                    )
                    add(
                        SheetAction(
                            label = if (isDownloaded) s.player.downloaded else s.player.download,
                            icon = if (isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                            enabled = !isDownloaded,
                            onClick = { if (!isDownloaded) onDownload(); onDismiss() },
                        )
                    )
                    add(
                        SheetAction(
                            label = s.contextMenu.playlist,
                            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                            onClick = { onAddToPlaylist(); onDismiss() },
                        )
                    )
                    add(
                        SheetAction(
                            label = s.player.songInfo,
                            icon = Icons.Filled.Info,
                            onClick = { onShowInfo(); onDismiss() },
                        )
                    )
                    if (onPlayMv != null) {
                        add(
                            SheetAction(
                                label = s.contextMenu.playMv,
                                icon = Icons.Filled.SmartDisplay,
                                onClick = { onPlayMv(); onDismiss() },
                            )
                        )
                    }
                    if (onAddToLocalPlaylist != null) {
                        add(
                            SheetAction(
                                label = s.contextMenu.addToLocalPlaylist,
                                icon = Icons.Filled.AddCircleOutline,
                                onClick = { onAddToLocalPlaylist(); onDismiss() },
                            )
                        )
                    }
                    if (onOpenInFileManager != null) {
                        add(
                            SheetAction(
                                label = s.contextMenu.openInFileManager,
                                icon = Icons.Filled.FolderOpen,
                                onClick = { onOpenInFileManager(); onDismiss() },
                            )
                        )
                    }
                }
                if (actionRows.isNotEmpty()) {
                    SheetActionGroup(actionRows)
                }

                val dangerRows = buildList {
                    val removeAction = onRemoveFromPlaylist ?: onRemoveFromLibrary
                    if (removeAction != null) {
                        add(
                            SheetAction(
                                label = if (onRemoveFromPlaylist != null) {
                                    s.library.removeFromPlaylist
                                } else {
                                    s.contextMenu.removeFromLibrary
                                },
                                icon = Icons.Filled.Close,
                                danger = true,
                                onClick = { removeAction(); onDismiss() },
                            )
                        )
                    }
                    if (onDelete != null) {
                        add(
                            SheetAction(
                                label = deleteLabel ?: s.contextMenu.deleteLocalFile,
                                icon = Icons.Filled.Delete,
                                danger = true,
                                onClick = { onDelete(); onDismiss() },
                            )
                        )
                    }
                }
                if (dangerRows.isNotEmpty()) {
                    SheetActionGroup(dangerRows)
                }
            }
        }
    }
}

/**
 * 歌单「更多」弹层 —— 手机 / 触屏形态。
 *
 * 与 [SongOptionsSheet] 共用同一套构件：头部（封面 + 名称 + 状态徽章）、
 * 主命令行（播放 + 队列 / 分享圆钮）、分段动作组、排序组、危险动作组。
 * 桌面端的对应物是 `playlistContextMenuItems`，两边的动作集合与顺序一致。
 */
@Composable
fun PlaylistOptionsSheet(
    playlistName: String,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    /** 仅 owner 提供：打开重命名对话框。 */
    onRename: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    coverUrl: String? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    currentSort: PlaylistSortType? = null,
    onSortChange: ((PlaylistSortType) -> Unit)? = null,
    onConvertToPlaylist: (() -> Unit)? = null,
) {
    val s = cpStrings()
    val statusText = when {
        isOwner -> s.contextMenu.ownedPlaylist
        onConvertToPlaylist != null ->
            if (isFavorite) s.contextMenu.convertedPlaylist else s.contextMenu.dailyRecommendation
        isFavorite -> s.contextMenu.favoritedPlaylist
        else -> s.contextMenu.publicPlaylist
    }
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SheetHorizontalPadding)
                    .padding(top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SheetHeader(
                    title = playlistName,
                    subtitle = statusText,
                    coverUrl = coverUrl,
                    circleCover = false,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetHeroButton(
                        label = s.library.playAll,
                        icon = Icons.Filled.PlayArrow,
                        onClick = { onPlay(); onDismiss() },
                        modifier = Modifier.weight(1f),
                    )
                    SheetIconButton(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        label = s.library.addToQueue,
                        onClick = { onAddToQueue(); onDismiss() },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (onShare != null) {
                        SheetIconButton(
                            icon = Icons.Filled.Share,
                            label = s.contextMenu.sharePlaylist,
                            onClick = { onShare(); onDismiss() },
                            shape = CircleShape,
                        )
                    }
                }

                val actionRows = buildList {
                    if (onRefresh != null) {
                        add(
                            SheetAction(
                                label = s.contextMenu.refreshPlaylist,
                                icon = Icons.Filled.Refresh,
                                onClick = { onRefresh(); onDismiss() },
                            )
                        )
                    }
                    if (onDownload != null) {
                        add(
                            SheetAction(
                                label = s.library.downloadAll,
                                icon = Icons.Filled.Download,
                                onClick = { onDownload(); onDismiss() },
                            )
                        )
                    }
                    if (onRename != null) {
                        add(
                            SheetAction(
                                label = s.library.renamePlaylist,
                                icon = Icons.Filled.Edit,
                                onClick = { onRename(); onDismiss() },
                            )
                        )
                    }
                }
                if (actionRows.isNotEmpty()) {
                    SheetActionGroup(actionRows)
                }

                if (currentSort != null) {
                    SheetSectionLabel(s.contextMenu.sortBy)
                    SheetActionGroup(
                        listOf(
                            SheetAction(
                                label = s.contextMenu.sortDefaultShort,
                                icon = Icons.AutoMirrored.Filled.List,
                                selected = currentSort == PlaylistSortType.DEFAULT,
                                onClick = { onSortChange?.invoke(PlaylistSortType.DEFAULT) },
                            ),
                            SheetAction(
                                label = s.library.sortByName,
                                icon = Icons.Filled.SortByAlpha,
                                selected = currentSort == PlaylistSortType.NAME,
                                onClick = { onSortChange?.invoke(PlaylistSortType.NAME) },
                            ),
                            SheetAction(
                                label = s.library.sortByArtist,
                                icon = Icons.Filled.Person,
                                selected = currentSort == PlaylistSortType.ARTIST,
                                onClick = { onSortChange?.invoke(PlaylistSortType.ARTIST) },
                            ),
                        )
                    )
                }

                val dangerRows = buildList {
                    if (onConvertToPlaylist != null) {
                        add(
                            SheetAction(
                                label = if (isFavorite) s.contextMenu.alreadyConverted else s.contextMenu.convertToPlaylist,
                                icon = if (isFavorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkAdd,
                                onClick = { onConvertToPlaylist(); onDismiss() },
                            )
                        )
                    } else if (onToggleFavorite != null) {
                        add(
                            SheetAction(
                                label = if (isFavorite) s.library.unfavoritePlaylist else s.library.favoritePlaylist,
                                icon = if (isFavorite) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdd,
                                danger = isFavorite,
                                onClick = { onToggleFavorite(); onDismiss() },
                            )
                        )
                    }
                    if (isOwner && onDelete != null) {
                        add(
                            SheetAction(
                                label = s.library.deletePlaylist,
                                icon = Icons.Filled.Delete,
                                danger = true,
                                onClick = { onDelete(); onDismiss() },
                            )
                        )
                    }
                }
                if (dangerRows.isNotEmpty()) {
                    SheetActionGroup(dangerRows)
                }
            }
        }
    }
}

// ============================================================
// M3E 动作弹层构件
// ============================================================

/** 分段动作行的数据。只描述「长什么样 + 点了做什么」，不持有 Sheet 状态。 */
private data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val selected: Boolean = false,
)

@Composable
private fun SheetHeader(
    title: String,
    subtitle: String,
    coverUrl: String?,
    circleCover: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(SheetHeroSize),
            shape = if (circleCover) CircleShape else RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl.resized(220),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                CpCoverPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    corner = if (circleCover) 36.dp else 18.dp,
                    animated = false,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CpText(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            CpText(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 主命令胶囊：与右侧两个方形 / 圆形图标钮同高（[SheetHeroSize]）。 */
@Composable
private fun SheetHeroButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(SheetHeroSize),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            CpText(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SheetIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = RoundedCornerShape(24.dp),
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(SheetHeroSize),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, label, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    CpText(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/**
 * 一组 M3E segmented list：外圈 20dp 圆角、组内相邻角 4dp、缝宽 2dp。
 * 直接把 `Arrangement.spacedBy(2.dp)` 交给列容器，行的圆角由 [legacySegmentShape]
 * 按 index/total 计算 —— 与设置页的分组列表同一套读数。
 */
@Composable
private fun SheetActionGroup(actions: List<SheetAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        actions.forEachIndexed { index, action ->
            SheetActionRow(action, index, actions.size)
        }
    }
}

@Composable
private fun SheetActionRow(action: SheetAction, index: Int, total: Int) {
    val contentColor = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        action.danger -> MaterialTheme.colorScheme.error
        action.selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = SheetRowMinHeight),
        shape = legacySegmentShape(index, total),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (action.danger) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    action.icon,
                    null,
                    tint = if (action.danger) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            CpText(
                action.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (action.selected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (action.selected) {
                Icon(
                    Icons.Filled.Check,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
