package cp.player.app.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cp.player.kmp.playback.LyricsState
import cp.player.kmp.playback.PlaybackUiState

/**
 * 同步歌词逐行展示（KMP 等效原项目 `LyricContent`）。
 *
 * 行级高亮 + 翻译可选；点击任意行可跳到该行起始时间。
 * 切歌/换行时自动滚动到活动行（仅当活动行不在可视区时）。
 */
@Composable
fun LyricContent(
    state: PlaybackUiState,
    showTranslation: Boolean = true,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 60.dp, horizontal = 8.dp),
) {
    val lyricState = state.lyrics
    val lines = (lyricState as? LyricsState.Success)?.lines.orEmpty()
    val active = state.activeLyricIndex
    val listState = rememberLazyListState()

    if (lines.isEmpty()) {
        val label = when (lyricState) {
            LyricsState.Loading -> "歌词加载中…"
            LyricsState.NoLyrics -> "暂无歌词"
            is LyricsState.Error -> "歌词获取失败：${lyricState.message}"
            else -> "等待曲目开始播放后展示歌词"
        }
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    // 自动滚动到活动行
    LaunchedEffect(active) {
        if (active < 0) return@LaunchedEffect
        val firstVisible = listState.firstVisibleItemIndex
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
        if (active < firstVisible || active > lastVisible) {
            listState.animateScrollToItem((active - 1).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(items = lines, key = { _, l -> l.time }) { i, line ->
            val isActive = i == active
            val alpha by animateFloatAsState(
                targetValue = if (isActive) 1f else 0.45f,
                label = "LyricAlpha_$i",
            )
            Column(
                Modifier.fillMaxWidth().clickable { onSeek(line.time) }.padding(vertical = 10.dp),
            ) {
                Text(
                    line.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().alpha(alpha),
                )
                val trans = line.translation
                if (showTranslation && !trans.isNullOrBlank()) {
                    Text(
                        trans,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().alpha(alpha),
                    )
                }
            }
        }
    }
}