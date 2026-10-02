package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import cp.player.core.music.AlbumSummary

/**
 * 专辑列表项（搜索结果 / 歌手主页 / 新碟上架）。
 *
 * 与 [PlaylistItem] 长得**刻意接近但不相同**：都是「方封面 + 标题 + 副标题 + 行尾按钮」，
 * 区别在图标与副标题语义。两者不能合并成一个组件 —— 专辑没有「创建者」，
 * 歌单没有「发行年份」，硬塞一个 `isAlbum: Boolean` 会让内部分支比两个组件加起来还长。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumItem(
    album: AlbumSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOptionsClick: (() -> Unit)? = null,
) {
    // 缩略图圆角取 `shapes.medium`(12dp)。写成 Dp 常量是因为要同时喂给 clip 与占位块。
    val thumbCorner = 12.dp
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumCoverThumb(
                coverUrl = album.coverUrl,
                corner = thumbCorner,
                modifier = Modifier.size(60.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    album.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 副标题可能全空（歌手、曲目数、发行年份都没有）。那时**不画这一行** ——
                // 画一个空 Text 会在标题下面留一条与行高同高的空白，整行看着像没对齐。
                val subtitle = albumSubtitle(album)
                if (subtitle.isNotEmpty()) {
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
            if (onOptionsClick != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onOptionsClick) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 专辑封面卡片（横向列表 / 桌面栅格）。
 *
 * [fillWidth] 语义与 [PlaylistCoverCard] 一致：false = 固定 160dp 正方形，true = 铺满给定宽度。
 */
@Composable
fun AlbumCoverCard(
    album: AlbumSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    Column(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(160.dp))
            .clickable(onClick = onClick),
    ) {
        AlbumCoverThumb(
            coverUrl = album.coverUrl,
            corner = 24.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        // 标题放在封面**下方**而不是压在图上：专辑名普遍比歌单名长（常带版本后缀），
        // 压在图里两行截断会把关键信息吃掉。
        Text(
            album.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // 先存进局部变量再判空：`artistName` 是**跨模块的公开属性**，
        // Kotlin 不会对它做 smart cast（其它模块可能改成 var）。
        val artistName = album.artistName
        if (!artistName.isNullOrBlank()) {
            Text(
                artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 方形专辑封面缩略图。
 *
 * **占位块常驻最底层**（而不是「没有封面才画」）：URL 有值但图还在路上 / 已 404 时，
 * 只画 AsyncImage 会留下一块空白，看起来像渲染坏了。铺在底层三种情况都成立。
 */
@Composable
fun AlbumCoverThumb(
    coverUrl: String?,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl.resized(300),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** 专辑副标题：`歌手 · N 首 · yyyy`，缺项自动省略。 */
internal fun albumSubtitle(album: AlbumSummary): String = buildList {
    album.artistName?.takeIf { it.isNotBlank() }?.let(::add)
    if (album.trackCount > 0) add("${album.trackCount} 首")
    albumYear(album)?.let(::add)
}.joinToString(" · ")

/**
 * 发行年份。
 *
 * 上游给的是毫秒时间戳，缺失或 <=0 时返回 null（**不能显示 1970** —— 那是「不知道」，
 * 不是「1970 年发的」）。
 *
 * ⚠️ 走 `cp.player.core.util.localDateTimeOf`（expect/actual）而**不是 kotlinx-datetime**：
 * 后者在本工程运行时解析到 0.7.x、编译期是 0.6.x，`kotlinx.datetime.Instant` 这个类
 * 在 0.7 里已不存在（变成 typealias），调用即 `NoClassDefFoundError`，
 * 被 `runCatching` 吞掉后表现为「年份永远不显示」。
 */
internal fun epochMillisToYear(ms: Long): String? {
    if (ms <= 0L) return null
    return runCatching { cp.player.core.util.localDateTimeOf(ms).year.toString() }.getOrNull()
}

internal fun albumYear(album: AlbumSummary): String? = album.publishTimeMs?.let(::epochMillisToYear)
