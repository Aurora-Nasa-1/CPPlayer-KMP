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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.core.music.PlaylistSummary
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightSource
import cp.player.app.ui.util.resized

/**
 * 媒体库歌单列表项（Library 页面使用）。
 *
 * 独立圆角卡片（surfaceContainerHigh），封面 60dp（12dp 圆角）+ 歌单名 +
 * "创建的歌单/收藏 · 创建者 · N 首" 副标题 + 右侧 MoreVert 圆形按钮。
 * 外层列表负责提供 12dp 水平 / 4dp 垂直间距。
 *
 * ⚠️ 2026-10-01 订正：原文写「独立 24dp 圆角卡片 / 封面 56dp（12dp 圆角）」，
 * 与当时代码（20dp 卡片 / 60dp 封面 / 14dp 圆角）**早就对不上**了 ——
 * 这类"顺手写进 KDoc 的数字"最容易漂。现在卡片走 `shapes.largeIncreased`、
 * 封面圆角走 `thumbCorner` 常量，**不再在注释里写死尺寸**。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaylistItem(
    playlist: PlaylistSummary,
    isOwner: Boolean,
    onClick: () -> Unit,
    onOptionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 缩略图圆角。`coverFlightSource` 要的是 **Dp**（不是 Shape），所以这里必须留一个
    // Dp 常量给两处共用；值等于 `MaterialTheme.shapes.medium`(12dp)，改刻度时一起改。
    // ⚠️ 两处必须同值 —— 不一致的话封面飞行起止圆角会对不上，飞行途中会看到角"跳"一下。
    val thumbCorner = 12.dp
    Surface(
        onClick = {
            // 在卡片自身触发：无论哪条调用路径（库列表 / 搜索 / 快捷入口）都覆盖。
            CoverFlight.openPlaylist(playlist.id, playlist.coverUrl)
            onClick()
        },
        modifier = modifier.fillMaxWidth(),
        // 卡片圆角取 `shapes.largeIncreased`(20dp) —— "次级卡片"那一档：
        // 比列表行（large=16）大、比主卡片（extraLarge=28）小。
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(thumbCorner))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .coverFlightSource(CoverFlight.playlistKey(playlist.id), thumbCorner),
                contentAlignment = Alignment.Center,
            ) {
                if (!playlist.coverUrl.isNullOrBlank()) {
                    // [Bolt] Fetch smaller (300px) covers for playlist cards to drastically reduce list payload
                    AsyncImage(
                        model = playlist.coverUrl.resized(300),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.QueueMusic, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // CpText 而不是 Text：歌单名（尤其是「我喜欢的音乐 2026 年 10 月第 3 周合集」
                // 这种）截断后仍可读全。静态观感不变，只在溢出时才挂悬停浮层。
                CpText(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                val ownerStr = if (isOwner) "创建的歌单" else "收藏 · ${playlist.creatorName ?: "未知"}"
                val countStr = if (playlist.trackCount > 0) "${playlist.trackCount} 首" else ""
                CpText(
                    text = listOf(ownerStr, countStr).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onOptionsClick) {
                Surface(
                    shape = CircleShape,
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

/**
 * M3 Expressive 歌单卡片（HorizontalPager / LazyRow / 桌面栅格使用）。
 *
 * 正方形封面 + 渐变叠加层 + 底部歌单名。
 *
 * @param fillWidth 默认 false = 固定 160dp 正方形（横向列表用）；
 *   true = 铺满调用方给定的宽度并保持正方形 —— 桌面栅格里配合 `Modifier.weight(1f)` 用，
 *   这样每行卡片能**正好铺满**而不会在右端留一条空隙。
 */
@Composable
fun PlaylistCoverCard(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    val overImage = !playlist.coverUrl.isNullOrBlank()
    Column(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(160.dp))
            .clickable {
                CoverFlight.openPlaylist(playlist.id, playlist.coverUrl)
                onClick()
            },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .coverFlightSource(CoverFlight.playlistKey(playlist.id), 24.dp),
        ) {
            // 占位块**常驻最底层**，而不是「没有封面时才画」。
            //
            // 只按「coverUrl 是否非空」决定画不画占位块，会漏掉最常见的一种中间态：
            // URL 有值、图还在路上（或已经 404）。那时 AsyncImage 什么都没画出来，
            // 只剩底部那条压暗渐变悬在卡片底色上 —— 整块磁贴看起来像渲染坏了。
            // 放在底层就三种情况都成立：图到位被盖住，图挂了/没到就露出占位块。
            Box(
                Modifier.fillMaxSize()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
            if (overImage) {
                // [Bolt] Limit playlist thumbnail downloads to 300px to avoid large memory footprints
                AsyncImage(
                    model = playlist.coverUrl.resized(300),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraLarge),
                    contentScale = ContentScale.Crop,
                )
                // 底部压暗遮罩，只为了让压在图上的白字可读。
                //
                // ⚠️ 这里必须用**分数** colorStops，不能写 `startY = 200f`：
                // 封面边长是 160dp，`startY=200` 已经越过底边，而 `endY` 默认无穷大
                // 会被 Compose 替换成 size.height ⇒ 渐变方向被翻转、整块被 clamp 到末色，
                // 结果是「整张封面被均匀压暗 40%」而不是「只有底部变暗」。
                Box(
                    modifier = Modifier.fillMaxSize()
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f),
                            )
                        ),
                )
            }
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // 没有封面时白字落在浅色占位块上几乎不可读，回落到主题色。
                color = if (overImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
    }
}
