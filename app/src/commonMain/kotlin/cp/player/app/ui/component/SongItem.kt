package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.kmp.music.TrackSummary

/**
 * M3 Expressive 风格歌曲列表项。
 *
 * 封面 52dp + 歌名/歌手 + 右侧 MoreVert 圆形按钮。
 * 点击主体区域触发 [onClick]，点击 MoreVert 触发 [onOptionsClick]。
 */
@Composable
fun SongItem(
    track: TrackSummary,
    onClick: () -> Unit,
    onOptionsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showIndex: Boolean = false,
    index: Int = 0,
    total: Int = 0,
    isCurrentlyPlaying: Boolean = false,
) {
    val shape = MaterialTheme.shapes.medium
    LegacyListItem(
        index = index.coerceAtLeast(0),
        total = total.coerceAtLeast(1),
        onClick = onClick,
        modifier = modifier,
        containerColor = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showIndex) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Box(
                    Modifier.size(56.dp).clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!track.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track.coverUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        headlineContent = {
            Text(
                track.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                buildString {
                    append(track.artist)
                    if (!track.album.isNullOrBlank()) {
                        append(" · ")
                        append(track.album)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = if (onOptionsClick != null) {{
            IconButton(
                onClick = onOptionsClick,
                colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }} else null,
    )
}
