package cp.player.app.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import cp.player.app.ui.component.CpPlayPauseButton
import cp.player.app.ui.component.CpSeekBar
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.MorphingShape
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.SeekAvailability
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.resized
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.RepeatMode
import kotlinx.coroutines.launch

/**
 * 桌面 / 平板播放页：左侧封面 + 控件，右侧队列 / 歌词 / 评论。
 *
 * Expressive 化的四处：
 * 1. 进度条换成 [CpSeekBar]（波形）；
 * 2. 播放按钮换成 [CpPlayPauseButton]（按下时圆角收缩 + 图标回弹 + 缓冲态用变形加载器）；
 * 3. 右侧三个页签从 `TabRow` 换成 [CpToggleChip]（选中时**形状**变化，而不是只有下划线）；
 * 4. 封面圆角随播放状态「呼吸」，走主题 spatial 动效。
 */
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
    val duration = state.durationMs.coerceAtLeast(0L)
    // 时长未知（流媒体元信息还没到、直播流）时滑条位置无法换算成绝对时间，
    // 拖出来必然是错的——保持禁用。但**必须说明原因**：静默失效会让用户
    // 分不清「我拖错了」和「这个音源拖不了」，观感就是「拖了没反应」。
    // 判定与标签统一走 SeekAvailability，不再各处各写一遍。
    // 无损曲后台落盘期间同样禁用：此时引擎放的是不可定位的流，拖了也不会动。
    val seekable = SeekAvailability.isSeekable(duration, state.isLocalizing)
    val background = Brush.radialGradient(
        colors = listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.background),
        radius = 1200f,
    )
    // 封面「呼吸」：播放时收紧圆角，暂停时松开。
    val artCorner by animateDpAsState(
        targetValue = if (state.isPlaying) 18.dp else 26.dp,
        animationSpec = CpMotion.spatialSlow(),
        label = "desktopArtCorner",
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
                            // 封面必须保持 1:1。旧版写的是 `fillMaxWidth().weight(1f)`，
                            // 那是「把剩余高度全吃掉」——面板越扁封面越扁（1400×900 实测
                            // 502×420），被拉伸的专辑封面一眼就看出不对。
                            // `aspectRatio` 在受限容器里会自动取**能放下的最大正方形**。
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Artwork(
                                    track.coverUrl,
                                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(artCorner)),
                                )
                            }
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
                            // 波形进度条（内部叠了透明 Slider 接手势，松手才 seek）。
                            CpSeekBar(
                                positionMs = state.positionMs,
                                durationMs = duration,
                                onSeek = onSeek,
                                enabled = seekable,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTimeMs(state.positionMs), style = MaterialTheme.typography.labelSmall)
                                Text(SeekAvailability.durationLabel(duration), style = MaterialTheme.typography.labelSmall)
                            }
                            // 禁用滑条必须给出原因：静默失效的观感就是「拖了没反应」。
                            // 两种原因互斥呈现（落盘优先），由 SeekAvailability 统一决定。
                            SeekAvailability.disabledReason(duration, state.isLocalizing)?.let { reason ->
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                        // 用 Expressive 的 ToggleButton 代替 TabRow：选中态由**形状**表达
                        // （圆角方形 ↔ 胶囊），而不是一条下划线 —— 这是 M3 Expressive 与
                        // M3 基础版在「分段选择」上最直观的差别。
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("队列", "歌词", "评论").forEachIndexed { index, title ->
                                CpToggleChip(
                                    checked = selectedTab == index,
                                    onCheckedChange = { selectedTab = index },
                                    label = title,
                                    modifier = Modifier.weight(1f),
                                )
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
    LazyScrollColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    if (!url.isNullOrBlank()) {
        AsyncImage(model = url.resized(900), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        // 无封面时放一个**持续变形**的 MaterialShapes 形状，而不是一个「♪」字符 ——
        // 空状态是 Expressive 最容易出彩、也最容易被忽略的地方。
        // 底色用 primary→tertiary 容器的斜向渐变，而不是一块死灰：一块纯 surfaceVariant
        // 的大方块在浅色主题下看着像「图加载失败」，渐变才读得出是刻意的占位。
        Box(
            modifier = modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    )
                )
            ),
            contentAlignment = Alignment.Center,
        ) {
            MorphingShape(
                modifier = Modifier.fillMaxSize(0.42f),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun PlayerControls(state: PlaybackUiState, onTogglePlay: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onRepeat: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onShuffle) { Icon(Icons.Filled.Shuffle, "随机播放", tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.SkipPrevious, "上一首", Modifier.size(30.dp)) }
        CpPlayPauseButton(
            isPlaying = state.isPlaying,
            onClick = onTogglePlay,
            size = 64.dp,
            isLoading = state.isBuffering,
        )
        IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.SkipNext, "下一首", Modifier.size(30.dp)) }
        IconButton(onClick = onRepeat) { Icon(if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "循环", tint = if (state.repeatMode == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) }
    }
}
