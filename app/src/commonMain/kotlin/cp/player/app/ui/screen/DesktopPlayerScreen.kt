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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.component.CpModeToggle
import cp.player.app.ui.component.CpPlayPauseButton
import cp.player.app.ui.component.CpSeekBar
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.MorphingShape
import cp.player.app.ui.component.SimilarSongsPanel
import cp.player.app.ui.component.TrackArtistText
import cp.player.app.ui.component.cpFluidBackground
import cp.player.app.ui.component.PlayerMoreSheets
import cp.player.app.ui.component.rememberPlayerMoreSheetState
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.SeekAvailability
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.pushOrNotify
import cp.player.app.ui.util.resized
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.RepeatMode
import kotlinx.coroutines.launch

/**
 * 桌面 / 平板播放页：左侧封面 + 控件，右侧队列 / 歌词 / 评论 / 相似歌曲。
 *
 * Expressive 化的四处：
 * 1. 进度条换成 [CpSeekBar]（波形；波纹随 `state.isPlaying` 流动 / 静止）；
 * 2. 播放按钮换成 [CpPlayPauseButton]（按下时圆角收缩 + 图标回弹 + 缓冲态用变形加载器）；
 * 3. 右侧页签从 `TabRow` 换成 [CpToggleChip]（选中时**形状**变化，而不是只有下划线）；
 * 4. 封面圆角随播放状态「呼吸」，走主题 spatial 动效。
 *
 * 「更多」入口在顶栏右侧（`MoreVert`）；弹层与动作实现由
 * [cp.player.app.ui.component.PlayerMoreSheets] 与窄屏播放页共享，本页只持开关状态。
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
    // 点歌手 → 歌手主页。多歌手各自分段可点（见 TrackArtistText 的 KDoc）。
    // ⚠️ 拿不到 Navigator 时**不挂点击区**（传 null 走纯文本分支）：与其点了只弹一句
    // 「打不开」，不如一开始就不长得像能点。
    val navigator = LocalNavigator.current
    val onArtistClick: ((cp.player.core.music.ArtistSummary) -> Unit)? = remember(navigator) {
        navigator?.let { nav ->
            { artist -> nav.pushOrNotify(UserProfileScreen(artist.id, artist.name)) }
        }
    }
    // 「更多」弹层的开关状态 + 共享宿主（动作实现唯一一份，见 PlayerMoreSheets）。
    // 本页与窄屏布局互斥切换，各持一份状态。
    val moreSheets = rememberPlayerMoreSheetState()
    var selectedTab by remember { mutableIntStateOf(1) }
    val duration = state.durationMs.coerceAtLeast(0L)
    // 时长未知（流媒体元信息还没到、直播流）时滑条位置无法换算成绝对时间，
    // 拖出来必然是错的——保持禁用。但**必须说明原因**：静默失效会让用户
    // 分不清「我拖错了」和「这个音源拖不了」，观感就是「拖了没反应」。
    // 判定与标签统一走 SeekAvailability，不再各处各写一遍。
    // 无损曲后台落盘期间同样禁用：此时引擎放的是不可定位的流，拖了也不会动。
    val seekable = SeekAvailability.isSeekable(duration, state.isLocalizing)
    // ⚠️ 必须 remember：本页订阅的是整个 `PlaybackUiState`，位置每 200ms 更新一次
    // ⇒ 本函数每 200ms 重组一次。Gradient/RadialGradient 的构造不算便宜（要算色标、
    // 建对象），不 remember 就是每秒 5 次无谓分配 + 一次背景重绘。颜色是唯一变量，
    // 故以颜色为 key —— 主题切换时才重建。
    //
    // 这支径向渐变现在有**两个身份**：`enabled = false`（用户在「外观」里关了流体背景）
    // 时的唯一背景，以及流体背景在 Android 12 及以下（没有 RuntimeShader）时的实际外观。
    // 两套播放页（本页与窄屏 PlayerScreen）共用同一个 `cpFluidBackground` 组件，
    // 参数也共用 `CpFluidBackgroundDefaults` —— 别在这里另起一套速度 / 尺度。
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val background0 = MaterialTheme.colorScheme.background
    val background = remember(surfaceHigh, background0) {
        Brush.radialGradient(
            colors = listOf(surfaceHigh, background0),
            radius = 1200f,
        )
    }
    val fluidBackground by AppModel.fluidBackgroundFlow.collectAsState()
    // 封面「呼吸」：播放时收紧圆角，暂停时松开。
    val artCorner by animateDpAsState(
        targetValue = if (state.isPlaying) 18.dp else 26.dp,
        animationSpec = CpMotion.spatialSlow(),
        label = "desktopArtCorner",
    )

    Box(Modifier.fillMaxSize().cpFluidBackground(enabled = fluidBackground, fallback = background).padding(28.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.Close, "收起播放页") }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("正在播放", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    // 副标题写**专辑**（没有就退到歌手）。原先这里恒为「音乐」——
                    // 一个对任何曲目都成立、也就等于什么都没说的字符串。
                    // 同一屏下方已经有大字歌名了，重复它同样没有收益。
                    Text(
                        track.album?.takeIf { it.isNotBlank() } ?: track.artist,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 此前这里只有一个 `onClick = { /* reserved */ }` 的假按钮，被以
                // 「假可供性比缺入口更糟」为由删掉 —— 删得对，但连带把「更多」这组功能
                // （加入歌单 / 下载 / 睡眠定时 / 不感兴趣 / 分享 / 歌曲信息）从宽屏整个
                // 取消了，而宽屏是桌面默认形态（1320×860 ≥ 840 断点）。
                // 现在补回**真**入口：弹层宿主是共享组件 PlayerMoreSheets（本页末尾挂载），
                // 与窄屏布局复用同一份动作实现。外观与窄屏顶栏的「队列」按钮同款
                // （FilledIconButton + surfaceContainerHighest），不另造一套。
                FilledIconButton(
                    onClick = { moreSheets.showMoreMenu = true },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Surface(
                    Modifier.weight(1.15f).fillMaxHeight(),
                    shape = MaterialTheme.shapes.extraLarge,
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
                                    TrackArtistText(
                                        track = track,
                                        onArtistClick = onArtistClick,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                IconButton(onClick = onLike) {
                                    Icon(if (state.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏", tint = if (state.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            // 波形进度条（内部叠了透明 Slider 接手势，松手才 seek）。
                            // 波纹流动跟随播放状态：播放中以 M3 规格流动（每秒一个波长），
                            // 暂停时静止 —— 「画面随声音停住」，暂停态也回到零逐帧重绘。
                            CpSeekBar(
                                positionMs = state.positionMs,
                                durationMs = duration,
                                onSeek = onSeek,
                                enabled = seekable,
                                waveFlowing = state.isPlaying,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                // 与紧凑版播放页的时间行**同款**（labelMedium + onSurfaceVariant）。
                                // 之前这里是 labelSmall 且没给颜色 ⇒ 继承 onSurface（全亮），
                                // 同一个「已播 / 总时长」在桌面端比手机端更抢眼、字还更小。
                                val formattedPosition = remember(state.positionMs / 1000) { formatTimeMs(state.positionMs) }
                                Text(
                                    formattedPosition,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    SeekAvailability.durationLabel(duration),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {
                    Column(Modifier.fillMaxSize().padding(22.dp)) {
                        // 用 Expressive 的 ToggleButton 代替 TabRow：选中态由**形状**表达
                        // （圆角方形 ↔ 胶囊），而不是一条下划线 —— 这是 M3 Expressive 与
                        // M3 基础版在「分段选择」上最直观的差别。
                        // 四个页签（队列 / 歌词 / 评论 / 相似）。相似歌曲以**当前在播曲目**
                        // 为种子，种子变化由 SimilarSongsPanel 内部处理（切歌自动重新拉取）。
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("队列", "歌词", "评论", "相似").forEachIndexed { index, title ->
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
                            2 -> DesktopCommentContent(track.id)
                            else -> SimilarSongsPanel(track.id)
                        }
                    }
                }
            }
        }
    }

    // 「更多」弹层 + 二级弹窗宿主：与窄屏播放页共用同一组件（PlayerMoreSheets）。
    PlayerMoreSheets(state = state, sheets = moreSheets)
}

@Composable
private fun QueueContent(state: PlaybackUiState, scope: kotlinx.coroutines.CoroutineScope, onPlayAt: (Int) -> Unit) {
    LazyScrollColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(state.queue) { index, item ->
            val selected = index == state.currentIndex
            Surface(onClick = { scope.launch { onPlayAt(index) } }, shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                    Artwork(item.coverUrl, Modifier.size(42.dp).clip(MaterialTheme.shapes.small))
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 随机 / 循环改用 [CpModeToggle]，与紧凑版播放页**同款**：
        // 选中态由「容器长出来 + 形状从圆角方变正圆」两层表达，而不是只把图标换个颜色
        // —— 后者和「禁用态」几乎分不清，是这套 UI 里最弱的一种状态表达，
        // 而桌面端恰恰是这三颗按钮被点得最多的地方。
        CpModeToggle(
            active = state.shuffleEnabled,
            onClick = onShuffle,
            icon = Icons.Filled.Shuffle,
            label = "随机播放",
        )
        IconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.SkipPrevious, "上一首", Modifier.size(30.dp)) }
        CpPlayPauseButton(
            isPlaying = state.isPlaying,
            onClick = onTogglePlay,
            size = 64.dp,
            isLoading = state.isBuffering,
        )
        IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.SkipNext, "下一首", Modifier.size(30.dp)) }
        CpModeToggle(
            active = state.repeatMode != RepeatMode.OFF,
            onClick = onRepeat,
            icon = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            label = "循环",
        )
    }
}
