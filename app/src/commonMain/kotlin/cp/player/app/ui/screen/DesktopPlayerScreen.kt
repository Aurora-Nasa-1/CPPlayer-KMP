package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
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
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.resized
import cp.player.kmp.playback.PlaybackUiState
import cp.player.kmp.playback.RepeatMode
import kotlinx.coroutines.launch

/** Desktop/tablet player: artwork and controls stay balanced while the queue remains visible. */
@Composable
fun DesktopPlayerScreen(
    state: PlaybackUiState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onLike: () -> Unit,
    onPlayAt: (Int) -> Unit,
) {
    val track = state.currentTrack ?: return
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(1) }
    val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    val background = Brush.radialGradient(
        colors = listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.background),
        radius = 1200f,
    )

    Box(Modifier.fillMaxSize().background(background).padding(28.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Text("×", style = MaterialTheme.typography.headlineMedium) }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("正在播放", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("音乐", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { /* reserved for player options */ }) { Icon(Icons.Filled.MoreHoriz, "更多") }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Surface(
                    Modifier.weight(1.15f).fillMaxHeight(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                ) {
                    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Artwork(track.coverUrl, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)))
                            Spacer(Modifier.height(22.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(track.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = onLike) {
                                    Icon(if (state.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏", tint = if (state.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Slider(value = progress.coerceIn(0f, 1f), onValueChange = { onSeek((it * state.durationMs).toLong()) }, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTimeMs(state.positionMs), style = MaterialTheme.typography.labelSmall)
                                Text(formatTimeMs(state.durationMs), style = MaterialTheme.typography.labelSmall)
                            }
                            PlayerControls(state, onTogglePlay, onSkipNext, onSkipPrev, onRepeat, onShuffle)
                        }
                    }
                }
                Surface(
                    Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {
                    Column(Modifier.fillMaxSize().padding(22.dp)) {
                        TabRow(selectedTab, containerColor = Color.Transparent) {
                            listOf("队列", "歌词", "评论").forEachIndexed { index, title ->
                                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        when (selectedTab) {
                            0 -> QueueContent(state, scope, onPlayAt)
                            1 -> DesktopLyricsContent(state, onSeek, onRepeat, onLike)
                            else -> DesktopCommentContent(track.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueContent(state: PlaybackUiState, scope: kotlinx.coroutines.CoroutineScope, onPlayAt: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(state.queue) { index, item ->
            val selected = index == state.currentIndex
            Surface(onClick = { scope.launch { onPlayAt(index) } }, shape = RoundedCornerShape(14.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                    Artwork(item.coverUrl, Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Artwork(url: String?, modifier: Modifier) {
    if (!url.isNullOrBlank()) AsyncImage(model = url.resized(900), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("♪", style = MaterialTheme.typography.displayLarge) }
}

@Composable
private fun PlayerControls(state: PlaybackUiState, onTogglePlay: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onRepeat: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onShuffle) { Icon(Icons.Filled.Shuffle, if (state.shuffleEnabled) "关闭随机播放" else "开启随机播放", tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.SkipPrevious, "上一首", Modifier.size(30.dp)) }
        IconButton(onClick = onTogglePlay, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.isPlaying) "暂停" else "播放", Modifier.size(32.dp)) }
        IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.SkipNext, "下一首", Modifier.size(30.dp)) }
        IconButton(onClick = onRepeat) { Icon(if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, when (state.repeatMode) { RepeatMode.ONE -> "单曲循环"; RepeatMode.ALL -> "列表循环"; else -> "顺序播放" }, tint = if (state.repeatMode == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) }
    }
}
