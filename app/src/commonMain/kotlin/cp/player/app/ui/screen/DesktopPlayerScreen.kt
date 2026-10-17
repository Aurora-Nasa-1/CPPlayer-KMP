package cp.player.app.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import cafe.adriel.voyager.navigator.LocalNavigator
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
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.MorphingShape
import cp.player.app.ui.component.SimilarSongsPanel
import cp.player.app.ui.component.TrackArtistText
import cp.player.app.ui.component.cpFluidBackground
import cp.player.app.ui.component.PlayerMoreSheets
import cp.player.app.ui.component.rememberPlayerMoreSheetState
// 这两个是本包的顶层函数（PlayerScreen.kt）：不用 import（同包），
// 这里写注释而不是 import 是为了防止后来者以为漏了导入又加一条多余的 import。
import cp.player.app.ui.component.PlaybackControls
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
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
    // 「没歌词 → 换源」。与点歌手同一契约：拿不到 Navigator 就不显示入口。
    val onChangeLyricsSource: (() -> Unit)? = remember(navigator) {
        navigator?.let { nav ->
            { nav.pushOrNotify(cp.player.app.ui.screen.LyricsPluginSettingsScreen()) }
        }
    }
    // 「更多」弹层的开关状态 + 共享宿主（动作实现唯一一份，见 PlayerMoreSheets）。
    // 本页与窄屏布局互斥切换，各持一份状态。
    val moreSheets = rememberPlayerMoreSheetState()
    var selectedTab by remember { mutableIntStateOf(1) }
    var showTranslation by remember { mutableStateOf(true) }
    var showSimilarOverlay by remember { mutableStateOf(false) }
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
    val isDark = LocalIsDarkTheme.current
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
        targetValue = if (state.isPlaying) 20.dp else 28.dp,
        animationSpec = CpMotion.spatialSlow(),
        label = "desktopArtCorner",
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .cpFluidBackground(enabled = fluidBackground, fallback = background)
            .padding(24.dp)
    ) {
        val isLargeWindow = maxWidth >= 1000.dp && maxHeight >= 720.dp
        // 可用高度（扣除外层内边距、顶栏与间距等）
        val availableHeight = maxHeight - 48.dp - 40.dp - 16.dp
        // 大尺寸窗口固定封面大小为 360dp，较小窗口自适应收缩避免溢出
        val coverSize = if (isLargeWindow) 360.dp else (availableHeight - 210.dp).coerceIn(240.dp, 360.dp)
        Column(Modifier.fillMaxSize()) {
            // 顶栏：左侧收起 + 专辑，右侧音质标识 + 音量控制胶囊 + 更多菜单
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Surface(
                        onClick = onBack,
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.18f else 0.32f),
                        ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "收起播放页",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("正在播放", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            state.formatInfo?.qualityLabel?.let { qLabel ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                ) {
                                    Text(
                                        text = qLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            val isFlickEngine = AppModel.audioEngineFlow.collectAsState().value == cp.player.core.playback.PlaybackSessionSettings.ENGINE_FLICK_HIRES
                            if (isFlickEngine) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    modifier = Modifier.clickable {
                                        navigator?.push(AudioEngineSettingsScreen())
                                    },
                                ) {
                                    Text(
                                        text = "Flick Hi-Res · 独占",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        CpText(
                            text = track.album?.takeIf { it.isNotBlank() } ?: track.artist,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 歌词翻译按钮：与手机端对齐，在歌词面板时呈现
                    if (selectedTab == 1 && !showSimilarOverlay) {
                        Surface(
                            onClick = { showTranslation = !showTranslation },
                            modifier = Modifier.size(40.dp),
                            shape = MaterialTheme.shapes.large,
                            color = if (showTranslation) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.18f else 0.32f),
                            ),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Translate,
                                    contentDescription = "歌词翻译",
                                    modifier = Modifier.size(19.dp),
                                    tint = if (showTranslation) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // 相似歌曲快捷入口
                    Surface(
                        onClick = { showSimilarOverlay = !showSimilarOverlay },
                        modifier = Modifier.size(40.dp),
                        shape = MaterialTheme.shapes.large,
                        color = if (showSimilarOverlay) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.18f else 0.32f),
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = if (showSimilarOverlay) "关闭相似推荐" else "相似歌曲推荐",
                                modifier = Modifier.size(19.dp),
                                tint = if (showSimilarOverlay) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // 桌面端专属音量调节胶囊
                    DesktopVolumeControl()

                    Surface(
                        onClick = { moreSheets.showMoreMenu = true },
                        modifier = Modifier.size(40.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.18f else 0.32f),
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 核心工作区：左侧固定尺寸/位置封面与播放控制，右侧队列/歌词/评论（歌词界面拉伸有限度）
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                // 左侧播放控制区（封面与控件垂直对齐，大尺寸窗口固定大小与位置）
                Column(
                    modifier = Modifier
                        .width(coverSize)
                        .padding(start = 8.dp, top = if (isLargeWindow) 16.dp else 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 封面保持 1:1，大尺寸窗口固定大小和相对位置
                    Artwork(
                        track.coverUrl,
                        Modifier
                            .size(coverSize)
                            .clip(RoundedCornerShape(artCorner)),
                    )

                    // 歌曲标题与艺术家
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            CpText(
                                text = track.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                emphasized = true,
                            )
                            TrackArtistText(
                                track = track,
                                onArtistClick = onArtistClick,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            track.mvId?.takeIf { it > 0 }?.let { mvId ->
                                val nav = LocalNavigator.current
                                IconButton(
                                    onClick = {
                                        nav?.push(VideoPlayerScreen("netease://mv/$mvId"))
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.SmartDisplay,
                                        contentDescription = "播放 MV",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            IconButton(onClick = onLike) {
                                Icon(
                                    if (state.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    "收藏",
                                    tint = if (state.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 波形进度条
                    CpSeekBar(
                        positionMs = state.positionMs,
                        durationMs = duration,
                        onSeek = onSeek,
                        enabled = seekable,
                        waveFlowing = state.isPlaying,
                        trackKey = track.id,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 时间行与禁用原因
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                    SeekAvailability.disabledReason(duration, state.isLocalizing)?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // M3E 播放控制组件
                    PlayerControls(state, onTogglePlay, onSkipNext, onSkipPrev, onRepeat, onShuffle)
                }

                // 右侧展示区（队列、歌词、评论，内容限宽居中）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = if (isLargeWindow) 16.dp else 0.dp, end = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // M3E 表现力胶囊页签切换组件（保留三个核心主栏：队列、歌词、评论）+ 右侧歌词翻译快捷按钮
                    Box(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        DesktopTabSelector(
                            selectedTab = if (showSimilarOverlay) -1 else selectedTab,
                            onTabSelect = {
                                selectedTab = it
                                showSimilarOverlay = false
                            },
                        )

                        if (!showSimilarOverlay && selectedTab == 1) {
                            Surface(
                                onClick = { showTranslation = !showTranslation },
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (showTranslation) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                modifier = Modifier.align(Alignment.CenterEnd).size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Translate,
                                        contentDescription = "歌词翻译",
                                        tint = if (showTranslation) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showSimilarOverlay) {
                            Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                                SimilarSongsPanel(
                                    seedTrackId = track.id,
                                    onBack = { showSimilarOverlay = false },
                                )
                            }
                        } else {
                            when (selectedTab) {
                                0 -> Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                                    QueueContent(
                                        state = state,
                                        scope = scope,
                                        onPlayAt = onPlayAt,
                                        onOpenSimilar = { showSimilarOverlay = true },
                                    )
                                }
                                1 -> DesktopLyricsContent(
                                    state = state,
                                    onSeek = onSeek,
                                    showTranslation = showTranslation,
                                    onRepeat = onRepeat,
                                    onLikeClick = onLike,
                                    onChangeSource = onChangeLyricsSource,
                                )
                                else -> Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                                    DesktopCommentContent(track.id)
                                }
                            }
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
private fun DesktopTabSelector(
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDarkTheme.current
    val tabs = remember { listOf("队列", "歌词", "评论") }

    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (isDark) 0.35f else 0.45f),
    ) {
        Row(
            Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = selectedTab == index
                val containerColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else androidx.compose.ui.graphics.Color.Transparent,
                    animationSpec = CpMotion.effects(),
                    label = "tabContainerColor",
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = CpMotion.effects(),
                    label = "tabContentColor",
                )
                Surface(
                    onClick = { onTabSelect(index) },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = containerColor,
                    contentColor = contentColor,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueContent(
    state: PlaybackUiState,
    scope: kotlinx.coroutines.CoroutineScope,
    onPlayAt: (Int) -> Unit,
    onOpenSimilar: (() -> Unit)? = null,
) {
    val isDark = LocalIsDarkTheme.current
    LazyScrollColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(state.queue) { index, item ->
            val selected = index == state.currentIndex
            Surface(
                onClick = { scope.launch { onPlayAt(index) } },
                shape = MaterialTheme.shapes.large,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else Color.Transparent,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "正在播放",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp),
                        )
                    }
                    Artwork(item.coverUrl, Modifier.size(42.dp).clip(MaterialTheme.shapes.small))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            item.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            item.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (onOpenSimilar != null) {
            item {
                Surface(
                    onClick = onOpenSimilar,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.50f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.15f else 0.25f),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "基于在播曲目探索相似歌曲",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
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
private fun DesktopVolumeControl(modifier: Modifier = Modifier) {
    val playback = AppModel.playback
    var volume by remember { mutableStateOf(playback.currentVolume) }
    var lastNonZeroVolume by remember { mutableStateOf(if (volume > 0.05f) volume else 1f) }
    val isMuted = volume <= 0.001f
    val isDark = LocalIsDarkTheme.current
    Surface(
        modifier = modifier.height(40.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.18f else 0.32f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = {
                    if (isMuted) {
                        volume = lastNonZeroVolume
                        playback.setVolume(lastNonZeroVolume)
                    } else {
                        lastNonZeroVolume = volume
                        volume = 0f
                        playback.setVolume(0f)
                    }
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = when {
                        isMuted -> Icons.AutoMirrored.Filled.VolumeOff
                        volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeMute
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    },
                    contentDescription = if (isMuted) "取消静音" else "静音",
                    modifier = Modifier.size(18.dp),
                    tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    playback.setVolume(it)
                },
                valueRange = 0f..1f,
                modifier = Modifier.width(96.dp).height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
            Text(
                text = "${(volume * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlaybackUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 480.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 随机播放
        CpModeToggle(
            active = state.shuffleEnabled,
            onClick = onShuffle,
            icon = Icons.Filled.Shuffle,
            label = "随机播放",
        )

        // 中央 M3E 表现力胶囊控件：上一首 / 播放暂停 / 下一首
        PlaybackControls(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            onPlayPause = onTogglePlay,
            onSkipNext = onNext,
            onSkipPrevious = onPrev,
            modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 8.dp),
            sideButtonModifier = Modifier.height(56.dp),
            centerButtonModifier = Modifier.height(56.dp),
            sideWeight = 1f,
            centerWeight = 1.25f,
            sideIconSize = 28.dp,
            centerIconSize = 32.dp,
        )

        // 循环模式
        CpModeToggle(
            active = state.repeatMode != RepeatMode.OFF,
            onClick = onRepeat,
            icon = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            label = "循环",
        )
    }
}
