package cp.player.app.ui.component

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Pure UI song action sheet matching the Android app's expressive action layout. */
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
    onAddToPlaylist: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onShowInfo: (() -> Unit)? = null,
) {
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(songName, artistName)
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
            }
            ActionPill(
                label = "添加到队列",
                icon = Icons.Filled.QueueMusic,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = { onAddToQueue(); onDismiss() },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                onDownload?.let { download ->
                    ActionPill(
                        label = if (isDownloaded) "已下载" else "下载",
                        icon = if (isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                        modifier = Modifier.weight(1f),
                        onClick = { if (!isDownloaded) download(); onDismiss() },
                    )
                }
                onAddToPlaylist?.let { add ->
                    ActionPill(
                        label = "歌单",
                        icon = Icons.Filled.AddCircleOutline,
                        modifier = Modifier.weight(1f),
                        onClick = { add(); onDismiss() },
                    )
                }
                onShowInfo?.let { show ->
                    CircleAction(Icons.Filled.Info, "歌曲信息") { show(); onDismiss() }
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
    onDelete: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(playlistName, "歌单")
            ActionTile(
                label = "播放全部",
                icon = Icons.Filled.PlayArrow,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = { onPlay(); onDismiss() },
            )
            ActionPill(
                label = "添加到队列",
                icon = Icons.Filled.PlaylistAdd,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = { onAddToQueue(); onDismiss() },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                onShare?.let { share ->
                    ActionPill(
                        label = "分享",
                        icon = Icons.Filled.Share,
                        modifier = Modifier.weight(1f),
                        onClick = { share(); onDismiss() },
                    )
                }
                onDelete?.let { delete ->
                    ActionPill(
                        label = if (isOwner) "删除歌单" else "取消收藏",
                        icon = Icons.Filled.DeleteOutline,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = { delete(); onDismiss() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
        modifier = modifier.fillMaxWidth().height(88.dp),
        shape = RoundedCornerShape(24.dp),
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
private fun CircleAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(88.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(30.dp))
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
        modifier = modifier.height(64.dp),
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
