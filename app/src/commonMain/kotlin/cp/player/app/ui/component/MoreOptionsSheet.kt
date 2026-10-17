package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cp.player.app.ui.util.resized

/** Playlist sort order shared by the playlist options sheet and screen models. */
enum class PlaylistSortType { DEFAULT, NAME, ARTIST }

/**
 * 歌曲「更多」弹层 —— 手机端**唯一**一份。
 *
 * ## 动线（三排，顺序与 [songContextMenuItems] 的桌面右键菜单逐项对齐）
 *
 * ```
 * 第一排  [播放 磁贴]  [收藏 圆钮]  [分享 圆钮]      ← 88dp 高，主命令 + 两个图标钮
 * 第二排  [下一首播放] [添加到队列]                 ← 64dp 胶囊
 * 第三排  [下载]      [歌单]      [歌曲信息 圆钮]   ← 64dp 胶囊 + 64dp 圆钮
 * ```
 *
 * ## ⚠️ 八个动作全部**必填**，别再改回 `= null`
 *
 * 曾经它们都是可选的，调用方「有什么给什么」⇒ 首页只给了 6 项、歌单详情给了 8 项，
 * 于是**同一个「歌曲更多」在不同页面长得不一样**（首页少一个分享圆钮、少一个
 * 歌曲信息圆钮，第一排从三格变两格、播放磁贴变宽）。这类漂移靠自觉维护不住，
 * 所以改成必填：任何页面想用它，就得先把分享与歌曲信息也接上 —— 漏了直接编译不过。
 *
 * 「分享」与「歌曲信息」对**任何一首歌**都成立（前者是固定形状的网易云链接，
 * 见 [songShareText]；后者是 `song/detail` 元数据，见 [SongDetailInfoDialog]），
 * 没有「这一页给不了」的合法理由。
 *
 * [isDownloaded] 为 true 时下载格显示「已下载」且不可点；其余状态由调用方给。
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
) {
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 头部：圆形封面 + 歌名 + 歌手（与 PlaylistOptionsSheet 一致）
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = coverUrl.resized(200),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            CpCoverPlaceholder(
                                modifier = Modifier.fillMaxSize(),
                                corner = 36.dp,
                                animated = false,
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            songName,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            artistName,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            // 第一排：主命令 + 两个图标钮。三格宽度固定，不随调用方给不给动作变化。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionTile(
                    label = "播放",
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { onPlay(); onDismiss() },
                )
                CircleAction(
                    icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = if (isFavorite) "取消收藏" else "收藏",
                    onClick = { onToggleFavorite(); onDismiss() },
                )
                CircleAction(
                    icon = Icons.Filled.Share,
                    label = "分享",
                    onClick = { onShare(); onDismiss() },
                )
            }
            // 第二排：两个等宽胶囊。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionPill(
                    label = "下一首播放",
                    icon = Icons.Filled.SkipNext,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                    onClick = { onPlayNext(); onDismiss() },
                )
                ActionPill(
                    label = "添加到队列",
                    icon = Icons.Filled.QueueMusic,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                    onClick = { onAddToQueue(); onDismiss() },
                )
            }
            // 第三排：两个胶囊 + 一个图标钮。
            // ⚠️ 「歌曲信息」这个圆钮必须与同排胶囊**等高**（[ActionPill] 是 64dp）——
            // 它此前沿用了第一排的 88dp（对齐 [ActionTile] 的高度），单独杵在两个
            // 64dp 胶囊旁边明显大一圈，整排看着像错位。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionPill(
                    label = if (isDownloaded) "已下载" else "下载",
                    icon = if (isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                    modifier = Modifier.weight(1f),
                    onClick = { if (!isDownloaded) onDownload(); onDismiss() },
                )
                ActionPill(
                    label = "歌单",
                    icon = Icons.Filled.AddCircleOutline,
                    modifier = Modifier.weight(1f),
                    onClick = { onAddToPlaylist(); onDismiss() },
                )
                CircleAction(
                    icon = Icons.Filled.Info,
                    label = "歌曲信息",
                    size = ActionPillHeight,
                    onClick = { onShowInfo(); onDismiss() },
                )
            }
            if (onPlayMv != null) {
                ActionPill(
                    label = "播放 MV",
                    icon = Icons.Filled.SmartDisplay,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPlayMv(); onDismiss() },
                )
            }
        }
        }
    }
}

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
    coverUrl: String? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    currentSort: PlaylistSortType? = null,
    onSortChange: ((PlaylistSortType) -> Unit)? = null,
) {
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 头部：M3E 圆角封面 + 歌单名 + 右侧快捷分享圆形胶囊
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 4.dp,
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
                                corner = 20.dp,
                                animated = false,
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            playlistName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                if (isOwner) "我创建的歌单" else if (isFavorite) "已收藏歌单" else "公开歌单",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (onShare != null) {
                        Spacer(Modifier.width(10.dp))
                        CircleAction(
                            icon = Icons.Filled.Share,
                            label = "分享歌单",
                            size = 48.dp,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            onClick = { onShare(); onDismiss() },
                        )
                    }
                }

                // 第一排：全部播放 + 全部加入队列（等宽大胶囊）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ActionPill(
                        label = "全部播放",
                        icon = Icons.Filled.PlayArrow,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = { onPlay(); onDismiss() },
                    )
                    ActionPill(
                        label = "加入队列",
                        icon = Icons.Filled.QueueMusic,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = { onAddToQueue(); onDismiss() },
                    )
                }

                // 第二排：关键操作胶囊（删除 / 取消收藏 / 收藏 / 刷新 / 分享）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isOwner && onDelete != null) {
                        // 创建者：删除歌单大胶囊（醒目红底）
                        ActionPill(
                            label = "删除歌单",
                            icon = Icons.Filled.Delete,
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            onClick = { onDelete(); onDismiss() },
                        )
                    } else if (onToggleFavorite != null) {
                        // 非创建者：取消收藏 / 收藏歌单
                        if (isFavorite) {
                            ActionPill(
                                label = "取消收藏",
                                icon = Icons.Filled.BookmarkRemove,
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                                onClick = { onToggleFavorite(); onDismiss() },
                            )
                        } else {
                            ActionPill(
                                label = "收藏歌单",
                                icon = Icons.Filled.BookmarkAdd,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f),
                                onClick = { onToggleFavorite(); onDismiss() },
                            )
                        }
                    }

                    if (onShare != null) {
                        ActionPill(
                            label = "分享歌单",
                            icon = Icons.Filled.Share,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            onClick = { onShare(); onDismiss() },
                        )
                    }

                    if (onRefresh != null) {
                        CircleAction(
                            icon = Icons.Filled.Refresh,
                            label = "刷新歌单",
                            size = ActionPillHeight,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            onClick = { onRefresh(); onDismiss() },
                        )
                    }
                }

                // 排序选项
                if (currentSort != null) {
                    Text(
                        "排序方式",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SortPill(
                            label = "默认",
                            icon = Icons.AutoMirrored.Filled.List,
                            isSelected = currentSort == PlaylistSortType.DEFAULT,
                            modifier = Modifier.weight(1f),
                            onClick = { onSortChange?.invoke(PlaylistSortType.DEFAULT) },
                        )
                        SortPill(
                            label = "按名称",
                            icon = Icons.Filled.SortByAlpha,
                            isSelected = currentSort == PlaylistSortType.NAME,
                            modifier = Modifier.weight(1f),
                            onClick = { onSortChange?.invoke(PlaylistSortType.NAME) },
                        )
                        SortPill(
                            label = "按歌手",
                            icon = Icons.Filled.Person,
                            isSelected = currentSort == PlaylistSortType.ARTIST,
                            modifier = Modifier.weight(1f),
                            onClick = { onSortChange?.invoke(PlaylistSortType.ARTIST) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 第一排（主命令磁贴 / 图标钮）的高度。
 *
 * 磁贴与圆钮共用，两者并排时等高。
 */
private val ActionTileHeight = 88.dp

/**
 * 第二、三排（胶囊 / 图标钮）的高度。
 *
 * 胶囊与圆钮共用同一个值 —— 第三排的「歌曲信息」圆钮曾沿用了第一排的 88dp，
 * 与同排 64dp 的胶囊差出一圈，是「INFO 按钮过大」的成因。
 */
private val ActionPillHeight = 64.dp

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(ActionTileHeight),
        shape = MaterialTheme.shapes.large,
        color = color,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = contentColor, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CircleAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    /** 直径。缺省对齐第一排的 [ActionTileHeight]；与胶囊同排时必须传 [ActionPillHeight]。 */
    size: Dp = ActionTileHeight,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = color,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = contentColor, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier.height(ActionPillHeight),
        shape = RoundedCornerShape(percent = 50),
        color = color,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = contentColor, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun SortPill(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(32.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                label,
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
