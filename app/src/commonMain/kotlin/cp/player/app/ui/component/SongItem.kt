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
import androidx.compose.material3.Checkbox
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
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightSource
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.resized
import cp.player.core.music.TrackSummary

/**
 * M3 Expressive 风格歌曲列表项。
 *
 * 封面 52dp + 歌名/歌手 + 右侧 MoreVert 圆形按钮。
 * 点击主体区域触发 [onClick]，点击 MoreVert 触发 [onOptionsClick]。
 * [selectionMode] 为 true 时以 Checkbox 替代封面、隐藏 MoreVert，用于多选场景。
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
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
) {
    val shape = MaterialTheme.shapes.medium
    LegacyListItem(
        index = index.coerceAtLeast(0),
        total = total.coerceAtLeast(1),
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        containerColor = when {
            selectionMode && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            isCurrentlyPlaying -> MaterialTheme.colorScheme.primaryContainer
            // ⚠️ 读 LocalIsDarkTheme（已解析的明暗），不要读 isSystemInDarkTheme() ——
            // 用户显式选深色而系统是浅色时后者会给出错的色板，行与背景撞色。
            // 浅色分支取 surfaceContainerLow 而非 surface：后者与页面背景同色，整列会糊成一片。
            else -> if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHighest
            else MaterialTheme.colorScheme.surfaceContainerLow
        },
        leadingContent = {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                )
            } else {
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
                    Modifier.size(52.dp).clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .coverFlightSource(CoverFlight.trackKey(track.id), 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!track.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track.coverUrl.resized(180),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Filled.MusicNote,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // 正在播放：封面上浮一个跳动的均衡器。它比「标题变主题色 + 加粗」
                    // 强得多 —— 后者要逐字读文字才知道，前者余光就能扫到。
                    if (isCurrentlyPlaying) {
                        CpPlayingEqualizer(
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                        )
                    }
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
        trailingContent = if (!selectionMode && onOptionsClick != null) {{
            IconButton(
                onClick = onOptionsClick,
                colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
