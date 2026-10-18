package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpSeekBar
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.MorphingShape
import cp.player.app.ui.component.PlayerMoreSheets
import cp.player.app.ui.component.SimilarSongsPanel
import cp.player.app.ui.component.TrackArtistText
import cp.player.app.ui.component.cpFluidBackground
import cp.player.app.ui.component.cpPressScale
import cp.player.app.ui.component.rememberPlayerMoreSheetState
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.SeekAvailability
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.pushOrNotify
import cp.player.app.ui.util.resized
import cp.player.core.music.ArtistSummary
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.RepeatMode

/**
 * 桌面 / 平板播放页：左侧封面 + 控件，右侧队列 / 歌词 / 评论。
 *
 * 版式按 Apple Music 的「全屏播放页」重排：
 * 1. 顶栏不再有标题文字与关闭按钮 —— 顶端中央是一条**抓手**，悬停时两根圆角条旋转成 ✕，
 *    点击收起（取代原来左上角的 X）；
 * 2. 队列 / 歌词 / 评论的页签从右侧面板顶部提到**整页顶部居中**，右侧只留翻译与「更多」；
 * 3. 左侧自上而下是封面 → 曲目信息 → 进度 → 走带（随机/上一首/播放/下一首/循环）→ 音量。
 *    走带与音量都**定宽排布**，不再靠 `weight` 抢空间 —— 之前循环键被挤扁就是
 *    随机 + 走带 + 循环三者总宽超过封面列宽、`SpaceBetween` 把中间那颗压没了。
 *
 * 「更多」入口在右上（`MoreVert`）；弹层与动作实现由
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
    onMove: (Int, Int) -> Unit,
) {
    // 音量真值在引擎里，UI 只持一份镜像（与旧实现一致：拖动即写回）。
    // 收在这一层是为了让 [DesktopPlayerLayout] 保持无 AppModel 依赖，能离屏渲染核对版式。
    val playback = AppModel.playback
    var volume by remember { mutableStateOf(playback.currentVolume) }
    DesktopPlayerLayout(
        state = state,
        volume = volume,
        onVolumeChange = { v ->
            volume = v
            playback.setVolume(v)
        },
        onBack = onBack,
        onTogglePlay = onTogglePlay,
        onSeek = onSeek,
        onSkipNext = onSkipNext,
        onSkipPrev = onSkipPrev,
        onRepeat = onRepeat,
        onShuffle = onShuffle,
        onLike = onLike,
        onPlayAt = onPlayAt,
        onMove = onMove,
    )
}

/**
 * 桌面播放页的**版式刻度**：一套尺寸全由窗口可用宽高推出来。
 *
 * 为什么不再写死：桌面窗口从 840dp 的窄窗到 3840px 的全屏都能出现，
 * 固定 `1200dp 内容 + 384dp 封面列` 在 1440 上刚好，在 4K 全屏上就成了一小块
 * 飘在中间的卡片（封面 384 对 2160 的高度），在 840×500 的矮窗上又会把
 * 底部控件挤出可视区。所以「封面多大 / 内容多宽 / 间距多松 / 按钮多大」这四件事
 * 必须一起随尺寸走。
 *
 * 三条曲线（都是线性、无跳变，只在极端处夹住）：
 * - `contentWidth` ≈ 高度的 1.34 倍，夹在 1100–1600。全屏时内容跟着变大，
 *   但不会无限拉伸（队列行超过 ~1000dp 就散了）。
 * - `leftColumnWidth` ≈ 高度的 0.48 倍，夹在 280–640，**再被内容宽度的 44% 夹一次** ——
 *   竖长窗口里封面可以当主角，但绝不允许把右栏挤到没法读（840dp 宽时右栏仍有 ~400dp）。
 * - `transportScale` = 左列宽 / 384，夹在 0.9–1.18：封面变大时走带按键跟着变大，
 *   否则大封面上挂一排小按钮，比例会失衡。
 */
private data class PlayerMetrics(
    val contentWidth: Dp,
    val leftColumnWidth: Dp,
    val controlsWidth: Dp,
    val maxArtworkSize: Dp,
    /** 左列与右栏之间的间隙。窄窗收窄一档，把宽度让给队列。 */
    val columnGap: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val tabRowHeight: Dp,
    /** 页签行到内容区的间距。 */
    val tabToContent: Dp,
    /** 左右双列统一的顶部对齐基准内边距（确保歌词首行与封面顶部严格齐平，绝不凸起高出）。 */
    val contentTopPadding: Dp,
    val artToTitle: Dp,
    val titleToSeek: Dp,
    val seekToTransport: Dp,
    val transportToVolume: Dp,
    /** 音质 / Flick 标识：极矮窗口里先砍它（信息量最低、占地最多的一行）。 */
    val showMetaChips: Boolean,
    val transportScale: Float,
    val isShort: Boolean,
)

private fun playerMetrics(maxWidth: Dp, maxHeight: Dp): PlayerMetrics {
    val isVeryShort = maxHeight < 580.dp
    val isShort = maxHeight < 700.dp
    val isUltraWide = maxWidth >= 2200.dp
    val isWide = maxWidth >= 1600.dp
    val isMedium = maxWidth >= 1100.dp

    val horizontalPadding = when {
        isUltraWide -> 56.dp
        isWide -> 44.dp
        isMedium -> 32.dp
        else -> 20.dp
    }
    val verticalPadding = when {
        isVeryShort -> 8.dp
        isShort -> 12.dp
        isWide -> 20.dp
        else -> 16.dp
    }

    val availableWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(300.dp)
    val availableHeight = (maxHeight - verticalPadding * 2).coerceAtLeast(300.dp)

    val contentMaxWidth = when {
        isUltraWide -> 1920.dp
        isWide -> 1680.dp
        isMedium -> 1400.dp
        else -> availableWidth
    }
    val contentWidth = minOf(availableWidth, contentMaxWidth)

    // 左列宽度与内容宽度和可用高度成比例
    val leftColumnWidth = when {
        isVeryShort -> (availableHeight * 0.52f).coerceIn(240.dp, 360.dp)
        isShort -> (contentWidth * 0.38f).coerceIn(280.dp, 400.dp)
        isWide -> (contentWidth * 0.32f).coerceIn(420.dp, 520.dp)
        else -> (contentWidth * 0.36f).coerceIn(320.dp, 440.dp)
    }

    val columnGap = when {
        isWide -> 48.dp
        isMedium -> 32.dp
        else -> 20.dp
    }

    val transportScale = when {
        isVeryShort -> 0.86f
        isShort -> 0.92f
        leftColumnWidth >= 460.dp -> 1.12f
        leftColumnWidth >= 400.dp -> 1.04f
        else -> 0.96f
    }

    val tabRowHeight = if (isShort) 36.dp else 42.dp
    val tabToContent = if (isShort) 8.dp else 14.dp

    // 垂直预算推导封面最大尺寸：包含 M3 标准音量滑条高度（约 40dp）
    val gapsBudget = if (isVeryShort) 28.dp else if (isShort) 38.dp else 52.dp
    val controlsHeightBudget = 52.dp + 44.dp + (58.dp * transportScale) + 40.dp + gapsBudget
    val topChromeBudget = 20.dp + tabRowHeight + tabToContent
    val maxArtByHeight = (availableHeight - topChromeBudget - controlsHeightBudget).coerceAtLeast(140.dp)

    val maxArtworkSize = minOf(leftColumnWidth, maxArtByHeight, if (isWide) 500.dp else 420.dp)
    val controlsWidth = minOf(leftColumnWidth, 380.dp * transportScale)

    val artToTitle = when {
        isVeryShort -> 8.dp
        isShort -> 10.dp
        else -> 16.dp
    }
    val titleToSeek = when {
        isVeryShort -> 6.dp
        isShort -> 8.dp
        else -> 12.dp
    }
    val seekToTransport = when {
        isVeryShort -> 8.dp
        isShort -> 10.dp
        else -> 14.dp
    }
    val transportToVolume = when {
        isVeryShort -> 8.dp
        isShort -> 10.dp
        else -> 14.dp
    }
    val showMetaChips = !isShort && (maxArtByHeight > 220.dp)

    // 左列内容总高估算，以此推导左右统一的顶部下压内边距 contentTopPadding，杜绝歌词高于左边
    val leftContentHeight = maxArtworkSize + artToTitle + 54.dp +
        (if (showMetaChips) 32.dp else 0.dp) +
        titleToSeek + 44.dp +
        seekToTransport + (58.dp * transportScale) +
        transportToVolume + 40.dp

    val remainingHeight = (availableHeight - topChromeBudget - leftContentHeight).coerceAtLeast(0.dp)
    val contentTopPadding = when {
        isVeryShort -> (remainingHeight / 2).coerceIn(4.dp, 12.dp)
        isShort -> (remainingHeight / 2).coerceIn(8.dp, 20.dp)
        isWide -> (remainingHeight / 2).coerceIn(16.dp, 56.dp)
        else -> (remainingHeight / 2).coerceIn(12.dp, 40.dp)
    }

    return PlayerMetrics(
        contentWidth = contentWidth,
        leftColumnWidth = leftColumnWidth,
        controlsWidth = controlsWidth,
        maxArtworkSize = maxArtworkSize,
        columnGap = columnGap,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        tabRowHeight = tabRowHeight,
        tabToContent = tabToContent,
        contentTopPadding = contentTopPadding,
        artToTitle = artToTitle,
        titleToSeek = titleToSeek,
        seekToTransport = seekToTransport,
        transportToVolume = transportToVolume,
        showMetaChips = showMetaChips,
        transportScale = transportScale,
        isShort = isShort,
    )
}

/**
 * 播放页版式本体（无 AppModel 依赖，便于 `ImageComposeScene` 离屏核对）。
 */
@Composable
internal fun DesktopPlayerLayout(
    state: PlaybackUiState,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onLike: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    val navigator = LocalNavigator.current
    val onArtistClick: ((ArtistSummary) -> Unit)? = remember(navigator) {
        navigator?.let { nav ->
            { artist -> nav.pushOrNotify(UserProfileScreen(artist.id, artist.name)) }
        }
    }
    val onChangeLyricsSource: (() -> Unit)? = remember(navigator) {
        navigator?.let { nav ->
            { nav.pushOrNotify(cp.player.app.ui.screen.LyricsPluginSettingsScreen()) }
        }
    }
    val moreSheets = rememberPlayerMoreSheetState()
    var selectedTab by remember { mutableIntStateOf(1) }
    var showTranslation by remember { mutableStateOf(true) }
    var showSimilarOverlay by remember { mutableStateOf(false) }
    val duration = state.durationMs.coerceAtLeast(0L)
    val seekable = SeekAvailability.isSeekable(duration, state.isLocalizing)

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
    // 注：20dp 等价于 MaterialTheme.shapes 刻度的 largeIncreased / extraLarge 槽位，
    // 28dp 等价于 extraLargeIncreased。此处因需结合 animateDpAsState 生成平滑动画数值，以 Dp 驱动。
    val artCorner by animateDpAsState(
        targetValue = if (state.isPlaying) 20.dp else 28.dp,
        animationSpec = CpMotion.spatialSlow(),
        label = "desktopArtCorner",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .cpFluidBackground(enabled = fluidBackground, fallback = background)
    ) {
        // 版式刻度：全屏 / 窄窗 / 矮窗都从这一处推，自适应各种屏幕规格（见 playerMetrics）。
        val metrics = remember(maxWidth, maxHeight) { playerMetrics(maxWidth, maxHeight) }
        val leftColumnWidth = metrics.leftColumnWidth

        // 标题与歌手字号自适应
        val titleStyle = when {
            metrics.isShort -> MaterialTheme.typography.titleLarge
            leftColumnWidth >= 480.dp -> MaterialTheme.typography.headlineMedium
            leftColumnWidth >= 360.dp -> MaterialTheme.typography.headlineSmall
            else -> MaterialTheme.typography.titleLarge
        }
        val artistStyle = when {
            metrics.isShort -> MaterialTheme.typography.bodyMedium
            leftColumnWidth >= 480.dp -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.horizontalPadding, vertical = metrics.verticalPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .width(metrics.contentWidth)
                    .fillMaxHeight(),
            ) {
                // ── 顶部抓手：Apple Music 式轻盈小横条，悬停旋成 ✕，点击收起 ──
                Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                    PlayerSheetGrabber(onClick = onBack)
                }

                Spacer(Modifier.height(if (metrics.isShort) 2.dp else 4.dp))

                // ── 顶部页签行：左侧返回/收起，右侧页签选择与动作工具 ──
                Row(
                    modifier = Modifier.fillMaxWidth().height(metrics.tabRowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧区域留空（宽度与左列一致），保持右侧页签居中对齐
                    Spacer(Modifier.width(leftColumnWidth))

                    Spacer(Modifier.width(metrics.columnGap))

                    // 右侧区域（宽度与右栏一致）：页签居中，工具靠右
                    Row(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            DesktopTabSelector(
                                selectedTab = if (showSimilarOverlay) -1 else selectedTab,
                                onTabSelect = {
                                    selectedTab = it
                                    showSimilarOverlay = false
                                },
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (!showSimilarOverlay && selectedTab == 1) {
                                PlayerTopToolButton(
                                    icon = Icons.Filled.Translate,
                                    label = if (showTranslation) "关闭歌词翻译" else "歌词翻译",
                                    active = showTranslation,
                                    onClick = { showTranslation = !showTranslation },
                                )
                            }
                            PlayerTopToolButton(
                                icon = Icons.Filled.MoreVert,
                                label = "更多",
                                active = false,
                                onClick = { moreSheets.showMoreMenu = true },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(metrics.tabToContent))

                // ── 核心工作区：左侧封面与控件，右侧展示面板 ──
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(metrics.columnGap),
                ) {
                    // 左列：封面 + 控件（以统一 contentTopPadding 与右侧对齐顶部基准线）
                    Column(
                        modifier = Modifier.width(leftColumnWidth).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top,
                    ) {
                        Spacer(Modifier.height(metrics.contentTopPadding))

                        // 1. 封面（1:1，限制在 maxArtworkSize 并不超列宽）
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val artSize = minOf(metrics.maxArtworkSize, leftColumnWidth)
                            val artShape = RoundedCornerShape(artCorner)
                            Artwork(
                                url = track.coverUrl,
                                modifier = Modifier
                                    .size(artSize)
                                    .shadow(
                                        elevation = if (state.isPlaying) 22.dp else 10.dp,
                                        shape = artShape,
                                        clip = false,
                                        ambientColor = Color.Black.copy(alpha = 0.22f),
                                        spotColor = Color.Black.copy(alpha = 0.28f),
                                    )
                                    .clip(artShape),
                            )
                        }

                        Spacer(Modifier.height(metrics.artToTitle))

                        // 2. 控件块：统一宽度为 metrics.controlsWidth，确保与走带、音量、进度条各元素完全对齐
                        Column(
                            modifier = Modifier.width(metrics.controlsWidth),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // 曲名 + 歌手 + 收藏 / MV
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    CpText(
                                        text = track.name,
                                        style = titleStyle,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        emphasized = true,
                                        marqueeEnabled = state.isPlaying,
                                    )
                                    TrackArtistText(
                                        track = track,
                                        onArtistClick = onArtistClick,
                                        style = artistStyle.copy(textAlign = TextAlign.Start),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    track.mvId?.takeIf { it > 0 }?.let { mvId ->
                                        PlayerInlineAction(
                                            icon = Icons.Filled.SmartDisplay,
                                            label = "播放 MV",
                                            onClick = {
                                                navigator?.push(VideoPlayerScreen("netease://mv/$mvId"))
                                            },
                                        )
                                    }
                                    PlayerInlineAction(
                                        icon = if (state.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                        label = if (state.isFavorite) "取消收藏" else "收藏",
                                        active = state.isFavorite,
                                        onClick = onLike,
                                    )
                                }
                            }

                            // 音质 / 引擎标识
                            val qualityLabel = state.formatInfo?.qualityLabel?.takeIf { it.isNotBlank() }
                            val isFlickEngine = AppModel.audioEngineFlow.collectAsState().value ==
                                cp.player.core.playback.PlaybackSessionSettings.ENGINE_FLICK_HIRES
                            if (metrics.showMetaChips && (qualityLabel != null || isFlickEngine)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    qualityLabel?.let {
                                        PlayerMetaChip(
                                            text = it,
                                            container = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                                            content = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isFlickEngine) {
                                        PlayerMetaChip(
                                            text = "Flick Hi-Res · 独占",
                                            container = MaterialTheme.colorScheme.tertiaryContainer,
                                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                                            onClick = { navigator?.push(AudioEngineSettingsScreen()) },
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(metrics.titleToSeek))

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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            Spacer(Modifier.height(metrics.seekToTransport))

                            // 走带：随机 / 上一首 / 播放 / 下一首 / 循环 —— 占满 controlsWidth，SpaceBetween 完美居中对齐
                            PlayerTransportRow(
                                state = state,
                                onTogglePlay = onTogglePlay,
                                onSkipNext = onSkipNext,
                                onSkipPrev = onSkipPrev,
                                onRepeat = onRepeat,
                                onShuffle = onShuffle,
                                scale = metrics.transportScale,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(Modifier.height(metrics.transportToVolume))

                            // 音量：Apple Music 风格对称设计，左右对齐上方走带与进度条
                            DesktopVolumeControl(
                                volume = volume,
                                onVolumeChange = onVolumeChange,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // 右侧展示区（队列、歌词、评论）：与左侧封面顶部完全对齐，绝不高过左侧
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(top = metrics.contentTopPadding),
                    ) {
                        if (showSimilarOverlay) {
                            Box(Modifier.fillMaxSize()) {
                                SimilarSongsPanel(
                                    seedTrackId = track.id,
                                    onBack = { showSimilarOverlay = false },
                                )
                            }
                        } else {
                            when (selectedTab) {
                                0 -> Box(Modifier.fillMaxSize()) {
                                    QueueContent(
                                        state = state,
                                        onPlayAt = onPlayAt,
                                        onMove = onMove,
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
                                else -> Box(Modifier.fillMaxSize()) {
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

/**
 * 顶部抓手：一根 36×3.5 的圆角横条。
 *
 * 悬停时两根**重叠的**横条各自旋到 ±45°、同时缩短到 13dp ⇒ 读起来就是「横条变成 ✕」，
 * 而不是横条淡出、✕ 淡入（后者在 120ms 内会有一帧两者都半透明，看着像抖）。
 * 收起动作与旧版左上角 X 一致（`onBack`）。
 */
@Composable
private fun PlayerSheetGrabber(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val morph by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = CpMotion.spatialFast(),
        label = "playerGrabberMorph",
    )
    val color = if (hovered) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(
        modifier = modifier
            .width(56.dp)
            .height(20.dp)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "收起播放页",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val barLength = lerp(34.dp.toPx(), 15.dp.toPx(), morph)
            val thickness = 3.dp.toPx()
            val angle = 45f * morph
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - barLength / 2f, center.y - thickness / 2f)
            val barSize = Size(barLength, thickness)
            val corner = CornerRadius(thickness / 2f)
            rotate(angle, center) {
                drawRoundRect(color, topLeft, barSize, corner)
            }
            rotate(-angle, center) {
                drawRoundRect(color, topLeft, barSize, corner)
            }
        }
    }
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
        shape = CircleShape,
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
                    else Color.Transparent,
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
                    shape = CircleShape,
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

/** 顶栏右侧的圆形工具按钮（翻译 / 更多）。桌面端补一层悬停底色。 */
@Composable
private fun PlayerTopToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f)
            else -> Color.Transparent
        },
        animationSpec = CpMotion.effectsFast(),
        label = "topToolContainer",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier.size(36.dp).hoverable(interaction),
        shape = CircleShape,
        color = container,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(19.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 曲名右侧的小动作（MV / 收藏）：32dp 触达区，悬停才出底。 */
@Composable
private fun PlayerInlineAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        targetValue = if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
        else Color.Transparent,
        animationSpec = CpMotion.effectsFast(),
        label = "inlineActionContainer",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier.size(32.dp).hoverable(interaction),
        shape = CircleShape,
        color = container,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(19.dp),
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 音质 / 引擎的小胶囊。 */
@Composable
private fun PlayerMetaChip(
    text: String,
    container: Color,
    content: Color,
    onClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.small
    val label: @Composable () -> Unit = {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = container) { label() }
    } else {
        Surface(shape = shape, color = container) { label() }
    }
}

/**
 * 走带行：随机 / 上一首 / 播放·暂停 / 下一首 / 循环。
 *
 * 每颗按钮都是**定尺寸**，行内用 `SpaceBetween` 摊开剩余宽度，占满父级 `controlsWidth`，
 * 与上方的进度条和下方的音量控制形成完全一致的边界对齐。
 */
@Composable
private fun PlayerTransportRow(
    state: PlaybackUiState,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    /** 按键缩放：封面变大时整排跟着变大（见 `PlayerMetrics.transportScale`）。 */
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerModeButton(
            icon = Icons.Filled.Shuffle,
            label = "随机播放",
            active = state.shuffleEnabled,
            onClick = onShuffle,
            size = 40.dp * scale,
        )
        PlayerSkipButton(
            icon = Icons.Filled.SkipPrevious,
            label = "上一首",
            onClick = onSkipPrev,
            size = 48.dp * scale,
        )
        PlayerPlayPauseButton(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            onClick = onTogglePlay,
            size = 62.dp * scale,
        )
        PlayerSkipButton(
            icon = Icons.Filled.SkipNext,
            label = "下一首",
            onClick = onSkipNext,
            size = 48.dp * scale,
        )
        PlayerModeButton(
            icon = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            label = when (state.repeatMode) {
                RepeatMode.ONE -> "单曲循环"
                RepeatMode.ALL -> "列表循环"
                RepeatMode.OFF -> "循环"
            },
            active = state.repeatMode != RepeatMode.OFF,
            onClick = onRepeat,
            size = 40.dp * scale,
        )
    }
}

/** 随机 / 循环：40dp 圆钮（随走带缩放），激活时填充 `primaryContainer`（不改形状，避免行内跳动）。 */
@Composable
private fun PlayerModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    size: Dp = 40.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)
            else -> Color.Transparent
        },
        animationSpec = CpMotion.effectsFast(),
        label = "modeButtonContainer",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size).hoverable(interaction),
        shape = CircleShape,
        color = container,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(size * 0.5f),
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlayerSkipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp = 48.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size).cpPressScale(interaction),
        interactionSource = interaction,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(size * 0.625f),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 中央播放键：62dp 主色圆钮（随走带缩放），按下时圆角收紧 + 图标回弹。 */
@Composable
private fun PlayerPlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    size: Dp = 62.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) size * 0.32f else size / 2,
        animationSpec = CpMotion.spatialFast(),
        label = "desktopPlayCorner",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = CpMotion.spatialFast(),
        label = "desktopPlayIconScale",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size).cpPressScale(interaction, pressedScale = 0.96f),
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interaction,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isBuffering) {
                CpLoadingIndicator(
                    modifier = Modifier.size(size * 0.42f),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
                    label = "desktopPlayIcon",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        modifier = Modifier
                            .size(size * 0.52f)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            },
                    )
                }
            }
        }
    }
}

/**
 * 音量：Apple Music 风格对称设计（静音/低音量图标 + 细滑轨 + 高音量图标），放在走带**下方**。
 *
 * 两端图标均为 24dp 交互尺寸（16dp 图标），中间为纤细滑轨，与上方的随机/循环按钮及进度条两端
 * 保持严格的水平基准线对齐，解决视觉重心右移与单侧不平衡问题。
 */
@Composable
private fun DesktopVolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastNonZero by remember { mutableStateOf(if (volume > 0.05f) volume else 1f) }
    SideEffect { if (volume > 0.05f) lastNonZero = volume }
    val isMuted = volume <= 0.001f

    val leftInteraction = remember { MutableInteractionSource() }
    val rightInteraction = remember { MutableInteractionSource() }
    val leftHovered by leftInteraction.collectIsHoveredAsState()
    val rightHovered by rightInteraction.collectIsHoveredAsState()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧静音/恢复按钮：尺寸 24dp，与右侧对称，确保滑条居中平衡
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .hoverable(leftInteraction)
                .clickable(
                    interactionSource = leftInteraction,
                    indication = null,
                    onClickLabel = if (isMuted) "恢复音量" else "静音",
                ) {
                    if (isMuted) onVolumeChange(lastNonZero) else onVolumeChange(0f)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    isMuted -> Icons.AutoMirrored.Filled.VolumeOff
                    volume < 0.35f -> Icons.AutoMirrored.Filled.VolumeMute
                    else -> Icons.AutoMirrored.Filled.VolumeDown
                },
                contentDescription = if (isMuted) "恢复音量" else "静音",
                modifier = Modifier.size(16.dp),
                tint = when {
                    isMuted -> MaterialTheme.colorScheme.error
                    leftHovered -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(Modifier.width(8.dp))

        // 中央采用 Material 3 原生标准的 Slider
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )

        Spacer(Modifier.width(8.dp))

        // 右侧最大音量按钮：与左侧 24dp 按钮形成完美视觉对称与对齐
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .hoverable(rightInteraction)
                .clickable(
                    interactionSource = rightInteraction,
                    indication = null,
                    onClickLabel = "最大音量",
                ) {
                    onVolumeChange(1f)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "最大音量",
                modifier = Modifier.size(16.dp),
                tint = if (rightHovered) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueueContent(
    state: PlaybackUiState,
    onPlayAt: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onOpenSimilar: (() -> Unit)? = null,
) {
    val isDark = LocalIsDarkTheme.current
    LazyScrollColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(state.queue, key = { _, item -> item.mediaId }) { index, item ->
            DesktopQueueRow(
                item = item,
                index = index,
                queueSize = state.queue.size,
                isCurrent = index == state.currentIndex,
                onPlay = { onPlayAt(index) },
                onMove = onMove,
                // 拖拽重排 / 切歌时的位移与淡出。队列是桌面播放页里唯一能真正
                // 改变顺序的列表，没有这层动画的话每次重排都是一次「集体瞬移」。
                modifier = Modifier.animateItem(),
            )
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

/**
 * 桌面队列行：悬停时序号位置换成拖拽把手，按住把手上下拖动即可**自由换位**。
 *
 * 与队列弹层（[cp.player.app.ui.component.QueueBottomSheet]）同一套「跨过一行就真移一位」
 * 的算法：`pos` 是本地即时推进的下标，`onMove` 每跨一行发一次 —— 这样拖动过程中
 * 列表是逐格跟手的，松手时无需再补一次最终位置。
 *
 * ⚠️ 不要用闭包里的 [index] 做移动基准：行按 `mediaId` 复用后 index 是旧值，
 * 会出现「拖多远都只换一格」。必须用 `pos` + `rememberUpdatedState`。
 */
@Composable
private fun DesktopQueueRow(
    item: cp.player.core.playback.QueueItem,
    index: Int,
    queueSize: Int,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val density = LocalDensity.current
    val latestMove by rememberUpdatedState(onMove)
    val latestSize by rememberUpdatedState(queueSize)
    var dragging by remember { mutableStateOf(false) }
    var dragAccum by remember { mutableStateOf(0f) }
    var pos by remember { mutableStateOf(index) }
    SideEffect { if (!dragging) pos = index }

    val background by animateColorAsState(
        targetValue = when {
            isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            hovered || dragging -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)
            else -> Color.Transparent
        },
        animationSpec = CpMotion.effectsFast(),
        label = "desktopQueueRowBg",
    )

    Surface(
        onClick = onPlay,
        shape = MaterialTheme.shapes.large,
        color = background,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 序号 / 拖拽把手共用同一个 24dp 槽位：不悬停时是序号，悬停时换成把手，
            // 这样既不给每行常驻一根把手（列表会很吵），也不会因为出现把手而让行内容位移。
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(44.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragAccum = 0f
                                dragging = true
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragAccum += dragAmount.y
                                // 一行 = 封面 44 + 上下内边距 12 + 行距 6 ≈ 62dp。
                                val threshold = with(density) { 62.dp.toPx() }
                                while (dragAccum > threshold) {
                                    val target = pos + 1
                                    if (target >= latestSize) {
                                        dragAccum = 0f
                                        break
                                    }
                                    latestMove(pos, target)
                                    pos = target
                                    dragAccum -= threshold
                                }
                                while (dragAccum < -threshold) {
                                    val target = pos - 1
                                    if (target < 0) {
                                        dragAccum = 0f
                                        break
                                    }
                                    latestMove(pos, target)
                                    pos = target
                                    dragAccum += threshold
                                }
                            },
                            onDragEnd = {
                                dragAccum = 0f
                                dragging = false
                            },
                            onDragCancel = {
                                dragAccum = 0f
                                dragging = false
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    hovered || dragging -> Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = "拖动排序",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    isCurrent -> Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = "正在播放",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    else -> Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Artwork(
                item.coverUrl,
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                CpText(
                    item.title,
                    maxLines = 1,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                CpText(
                    item.artist,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
