package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.kmp.music.PlaylistSummary

/**
 * 快速访问区域：HorizontalPager 滑动切换 FM 快捷入口 / 用户歌单预览。
 *
 * 对应原项目 MainScreen 中的 QuickAccessCards 区域。
 */
@Composable
fun QuickAccessSection(
    fmOnRecommendClick: () -> Unit,
    fmOnPersonalFmClick: () -> Unit,
    userPlaylists: List<PlaylistSummary>,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val quickAccessItems = buildList {
        add(QuickAccessItemType.Fm)
        userPlaylists.take(5).forEach { playlist ->
            add(QuickAccessItemType.PlaylistPreview(playlist))
        }
    }
    if (quickAccessItems.isEmpty()) return

    val initialPage = if (quickAccessItems.size > 1) 1 else 0
    val pagerState = rememberPagerState(initialPage = initialPage) { quickAccessItems.size }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 32.dp),
            pageSpacing = 16.dp,
        ) { page ->
            val isCurrent = page == pagerState.currentPage
            val alpha by animateFloatAsState(
                targetValue = if (isCurrent) 1f else 0.7f,
                animationSpec = tween(300),
                label = "pageAlpha",
            )
            Box(Modifier.graphicsLayer { this.alpha = alpha }) {
                QuickAccessCard(
                    item = quickAccessItems[page],
                    onFmRecommendClick = fmOnRecommendClick,
                    onFmPersonalFmClick = fmOnPersonalFmClick,
                    onPlaylistClick = onPlaylistClick,
                    onArrowClick = {
                        val item = quickAccessItems[page]
                        when (item) {
                            is QuickAccessItemType.PlaylistPreview -> onPlaylistClick(item.playlist)
                            else -> fmOnPersonalFmClick()
                        }
                    },
                )
            }
        }

        if (quickAccessItems.size > 1) {
            Box(
                modifier = Modifier.height(48.dp).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    quickAccessItems.forEachIndexed { index, item ->
                        val isSelected = index == pagerState.currentPage
                        val indicatorColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            animationSpec = tween(300),
                            label = "indicatorColor",
                        )
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 36.dp else 8.dp)
                                .clip(CircleShape)
                                .background(indicatorColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                when (item) {
                                    is QuickAccessItemType.Fm -> {
                                        Icon(
                                            Icons.Filled.Radio, null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    is QuickAccessItemType.PlaylistPreview -> {
                                        if (!item.playlist.coverUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = item.playlist.coverUrl,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed class QuickAccessItemType {
    data object Fm : QuickAccessItemType()
    data class PlaylistPreview(val playlist: PlaylistSummary) : QuickAccessItemType()
}

@Composable
private fun QuickAccessCard(
    item: QuickAccessItemType,
    onFmRecommendClick: () -> Unit,
    onFmPersonalFmClick: () -> Unit,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onArrowClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(160.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onArrowClick,
    ) {
        when (item) {
            is QuickAccessItemType.Fm -> {
                FmQuickAccessContent(
                    onRecommendClick = onFmRecommendClick,
                    onFmClick = onFmPersonalFmClick,
                )
            }
            is QuickAccessItemType.PlaylistPreview -> {
                PlaylistPreviewCard(
                    playlist = item.playlist,
                    onClick = { onPlaylistClick(item.playlist) },
                    onArrowClick = onArrowClick,
                )
            }
        }
    }
}

@Composable
private fun FmQuickAccessContent(
    onRecommendClick: () -> Unit,
    onFmClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FmCard(
            onClick = onRecommendClick,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            gradientColors = listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f),
            ),
            icon = { Icon(Icons.Filled.AutoGraph, null, tint = Color.White, modifier = Modifier.size(20.dp)) },
            title = "为你推荐",
            subtitle = "基于你的口味推荐歌曲",
        )
        FmCard(
            onClick = onFmClick,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            gradientColors = listOf(
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            ),
            icon = { Icon(Icons.Filled.Radio, null, tint = Color.White, modifier = Modifier.size(20.dp)) },
            title = "私人FM",
            subtitle = "属于你的专属电台",
        )
    }
}

@Composable
private fun FmCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradientColors: List<Color>,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(Brush.verticalGradient(gradientColors))
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier.size(70.dp).offset(x = 40.dp, y = (-15).dp)
                .background(Color.White.copy(alpha = 0.08f), CircleShape),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PlaylistPreviewCard(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    onArrowClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(72.dp).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!playlist.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Filled.Radio, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "歌单",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${playlist.trackCount} 首 · ${playlist.creatorName ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            onClick = onArrowClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Radio, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
