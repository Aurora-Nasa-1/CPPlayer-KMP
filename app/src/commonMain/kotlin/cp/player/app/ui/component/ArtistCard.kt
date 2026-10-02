package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.ui.util.resized
import cp.player.core.music.ArtistSummary

/**
 * 歌手 / 用户列表项（搜索结果、热门歌手、关注与粉丝）。
 *
 * 头像是**圆形**、缩略图尺寸与本仓库其它列表项统一在 56dp —— 这一条是刻意的：
 * 方形封面 = 「内容（歌单 / 专辑）」，圆形头像 = 「人」。用户在列表里扫一眼就能分辨
 * 点下去是进内容页还是进主页，不必读文字。
 */
@Composable
fun ArtistItem(
    artist: ArtistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    LegacyListItem(
        index = 0,
        total = 1,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        leadingContent = { ArtistAvatar(artist.avatarUrl, 56.dp) },
        headlineContent = {
            Text(
                artist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                subtitle ?: "歌手",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CpIconSize.list),
            )
        },
    )
}

/**
 * 圆形头像。
 *
 * [size] 为 null 时铺满调用方给的约束；否则固定边长（列表项用）。
 * 占位块同样**常驻最底层** —— 头像 URL 有值但图没到 / 404 时，
 * 只有 `Person` 图标可看，比一块空白可读得多。
 */
@Composable
fun ArtistAvatar(
    url: String?,
    size: Dp?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .then(if (size != null) Modifier.size(size) else Modifier.fillMaxSize())
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.45f),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url.resized(200),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
