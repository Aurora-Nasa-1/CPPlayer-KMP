package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccessAlarm
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.BackHandler
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.component.CpContextMenu
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.CpContextMenuSeparator
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpSeekBar
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.PlayerArtworkStage
import cp.player.app.ui.component.PlayerInlineActionButton
import cp.player.app.ui.component.PlayerMoreSheets
import cp.player.app.ui.component.PlayerSecondarySheet
import cp.player.app.ui.component.PlayerSheet
import cp.player.app.ui.component.PlayerTrackInfoBlock
import cp.player.app.ui.component.cpFluidBackground
import cp.player.app.ui.component.cpPressScale
import cp.player.app.ui.component.miniPlayerMouseWheel
import cp.player.app.ui.component.miniPlayerWheelVolume
import cp.player.app.ui.component.rememberActiveProviderSupports
import cp.player.app.ui.component.rememberPlayerMoreSheetState
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.theme.cpNestedSurface
import cp.player.app.ui.util.SeekAvailability
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.rememberCoverImageRequest
import cp.player.core.music.ArtistSummary
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.RepeatMode
import cp.player.core.provider.ProviderCapability
import kotlinx.coroutines.flow.Flow

/**
 * 桌面播放页：左侧封面与播放控件，右侧歌词。队列和评论由独立入口打开。
 *
 * 「更多」入口在右栏顶栏右上；弹层与动作实现由
 * [cp.player.app.ui.component.PlayerMoreSheets] 与移动/平板播放页共享。
 */
@Composable
fun DesktopPlayerScreen(
    state: PlaybackUiState,
    positionFlow: Flow<Long>? = null,
    initialPositionMs: Long = state.positionMs,
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
    onRemoveQueue: (Int) -> Unit = {},
    onClearQueue: () -> Unit = {},
    /** 页面转场是否还在进行；转场期间冻结流体背景以省下这段着色器开销。 */
    transitionRunning: Boolean = false,
    containerModifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    artistModifier: Modifier = Modifier,
) {
    val playback = AppModel.playback
    DesktopPlayerLayout(
        state = state,
        positionFlow = positionFlow,
        initialPositionMs = initialPositionMs,
        volume = state.volume,
        onVolumeChange = { v -> playback.setVolume(v) },
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
        onRemoveQueue = onRemoveQueue,
        onClearQueue = onClearQueue,
        topChromeInset = cp.player.app.ui.component.LocalPlayerTopInset.current,
        transitionRunning = transitionRunning,
        containerModifier = containerModifier,
        artworkModifier = artworkModifier,
        titleModifier = titleModifier,
        artistModifier = artistModifier,
    )
}

/**
 * 桌面播放页的版式刻度：一套尺寸全由窗口可用宽高推导。
 */
private data class PlayerMetrics(
    val contentWidth: Dp,
    val leftColumnWidth: Dp,
    val controlsWidth: Dp,
    val maxArtworkSize: Dp,
    val columnGap: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val artToTitle: Dp,
    val titleToSeek: Dp,
    val seekToTransport: Dp,
    val transportToVolume: Dp,
    val showMetaChips: Boolean,
    val transportScale: Float,
    val isShort: Boolean,
)

// The desktop columns start below the 24 dp grabber, which sits 8 dp from the top.
private val DesktopColumnsTopPadding = 36.dp

private fun playerMetrics(maxWidth: Dp, maxHeight: Dp, desktop: Boolean, showVolume: Boolean): PlayerMetrics {
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
        isVeryShort -> 10.dp
        isShort -> 14.dp
        isWide -> 24.dp
        else -> 18.dp
    }

    val availableWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(300.dp)
    val topPadding = if (desktop) DesktopColumnsTopPadding else verticalPadding
    val availableHeight = (maxHeight - topPadding - verticalPadding).coerceAtLeast(300.dp)

    val contentMaxWidth = when {
        isUltraWide -> 1920.dp
        isWide -> 1680.dp
        isMedium -> 1400.dp
        else -> availableWidth
    }
    val contentWidth = minOf(availableWidth, contentMaxWidth)

    val leftColumnWidth = when {
        isVeryShort -> (availableHeight * 0.52f).coerceIn(240.dp, 360.dp)
        isShort -> (contentWidth * 0.38f).coerceIn(280.dp, 400.dp)
        isWide -> (contentWidth * 0.32f).coerceIn(420.dp, 520.dp)
        else -> (contentWidth * 0.36f).coerceIn(320.dp, 440.dp)
    }

    val columnGap = when {
        isWide -> 48.dp
        isMedium -> 32.dp
        else -> 24.dp
    }

    val transportScale = when {
        isVeryShort -> 0.86f
        isShort -> 0.92f
        leftColumnWidth >= 460.dp -> 1.12f
        leftColumnWidth >= 400.dp -> 1.04f
        else -> 0.96f
    }

    val gapsBudget = if (isVeryShort) 24.dp else if (isShort) 34.dp else 48.dp
    val controlsHeightBudget = 74.dp + 48.dp + (62.dp * transportScale) +
        (if (showVolume) 40.dp else 0.dp) + gapsBudget
    val maxArtByHeight = (availableHeight - controlsHeightBudget).coerceAtLeast(140.dp)

    val maxArtworkSize = minOf(leftColumnWidth, maxArtByHeight, if (isWide) 500.dp else 420.dp)
    val controlsWidth = minOf(leftColumnWidth, 380.dp * transportScale)

    val artToTitle = when {
        isVeryShort -> 8.dp
        isShort -> 12.dp
        else -> 18.dp
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

    return PlayerMetrics(
        contentWidth = contentWidth,
        leftColumnWidth = leftColumnWidth,
        controlsWidth = controlsWidth,
        maxArtworkSize = maxArtworkSize,
        columnGap = columnGap,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
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
 * 桌面播放页版式本体（无 `AppModel.playback` 依赖，便于 `ImageComposeScene` 离屏核对）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopPlayerLayout(
    state: PlaybackUiState,
    positionFlow: Flow<Long>? = null,
    initialPositionMs: Long = state.positionMs,
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
    onRemoveQueue: (Int) -> Unit = {},
    onClearQueue: () -> Unit = {},
    topChromeInset: Dp = 0.dp,
    transitionRunning: Boolean = false,
    containerModifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    artistModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    val navigator = LocalAppNavigator.current
    val isDark = LocalIsDarkTheme.current
    val desktop = !cp.player.app.platform.isAndroidPlatform()
    val onArtistClick: ((ArtistSummary) -> Unit)? = remember(navigator) {
        { artist -> navigator.push(AppRoute.UserProfile(artist.id, artist.name, artist.resourceId)) }
    }
    val onChangeLyricsSource: (() -> Unit)? = remember(navigator) {
        { navigator.push(AppRoute.LyricsPluginSettings) }
    }
    val moreSheets = rememberPlayerMoreSheetState()
    var playerSheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var isZenMode by remember { mutableStateOf(false) }
    val desktopVolumeVisible by AppModel.desktopPlayerVolumeVisibleFlow.collectAsState()
    val showVolume = !isZenMode && (!desktop || desktopVolumeVisible)
    val supportsComments = rememberActiveProviderSupports(ProviderCapability.COMMENTS)

    val playerConfig by AppModel.mobilePlayerCustomConfigFlow.collectAsState()
    var showTranslation by remember(playerConfig.showTranslation) { mutableStateOf(playerConfig.showTranslation) }
    val toggleTranslation = {
        val next = !showTranslation
        showTranslation = next
        AppModel.updateMobilePlayerCustomConfig { it.copy(showTranslation = next) }
    }
    val duration = state.durationMs.coerceAtLeast(0L)
    val seekable = SeekAvailability.isSeekable(duration, state.isLocalizing)

    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val background0 = MaterialTheme.colorScheme.surface
    val background = remember(surfaceHigh, background0) {
        Brush.radialGradient(
            colors = listOf(surfaceHigh, background0),
            radius = 1200f,
        )
    }
    val fluidBackground by AppModel.fluidBackgroundFlow.collectAsState()

    BackHandler(enabled = isZenMode) {
        isZenMode = false
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .cpFluidBackground(
                enabled = fluidBackground,
                fallback = background,
                pauseShaderAnimation = transitionRunning,
                coverUrl = track.coverUrl,
                playing = state.isPlaying,
            )
            .then(containerModifier),
    ) {
        val effectiveHeight = (maxHeight - topChromeInset).coerceAtLeast(200.dp)
        val metrics = remember(maxWidth, effectiveHeight, desktop, showVolume) {
            playerMetrics(maxWidth, effectiveHeight, desktop, showVolume)
        }
        val leftColumnWidth = metrics.leftColumnWidth
        val columnsTopPadding = if (desktop) DesktopColumnsTopPadding else metrics.verticalPadding

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
        val lyricFontSize = when {
            metrics.isShort -> 24.sp
            leftColumnWidth >= 480.dp -> 34.sp
            leftColumnWidth >= 400.dp -> 30.sp
            else -> 28.sp
        }

        val s = cpStrings()

        val contextMenuItems = remember(
            state.isPlaying,
            state.isFavorite,
            state.shuffleEnabled,
            state.repeatMode,
            showTranslation,
            isZenMode,
            supportsComments,
            track,
            s,
        ) {
            buildList {
                add(
                    CpContextMenuItem(
                        label = s.player.playOrPause(state.isPlaying),
                        icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        onClick = onTogglePlay,
                    ),
                )
                add(
                    CpContextMenuItem(
                        label = s.player.nextTrack,
                        icon = Icons.Rounded.SkipNext,
                        onClick = onSkipNext,
                    ),
                )
                add(
                    CpContextMenuItem(
                        label = s.player.previousTrack,
                        icon = Icons.Rounded.SkipPrevious,
                        onClick = onSkipPrev,
                    ),
                )
                add(
                    CpContextMenuItem(
                        label = if (state.isFavorite) s.player.unfavoriteTrack else s.player.favoriteTrack,
                        icon = if (state.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        isSelected = state.isFavorite,
                        onClick = onLike,
                    ),
                )
                add(
                    CpContextMenuItem(
                        label = s.player.shuffle,
                        icon = Icons.Rounded.Shuffle,
                        isSelected = state.shuffleEnabled,
                        onClick = onShuffle,
                    ),
                )
                add(
                    CpContextMenuItem(
                        label = when (state.repeatMode) {
                            RepeatMode.ONE -> s.player.repeatOne
                            RepeatMode.ALL -> s.player.repeatAll
                            RepeatMode.OFF -> s.player.repeatOff
                        },
                        icon = if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        isSelected = state.repeatMode != RepeatMode.OFF,
                        onClick = onRepeat,
                    ),
                )

                add(CpContextMenuSeparator)

                add(
                    CpContextMenuItem(
                        label = s.player.showQueue,
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        onClick = { playerSheet = PlayerSheet.Queue },
                    ),
                )
                if (supportsComments) {
                    add(
                        CpContextMenuItem(
                            label = s.player.showComments,
                            icon = Icons.AutoMirrored.Rounded.Comment,
                            onClick = { playerSheet = PlayerSheet.Comments },
                        ),
                    )
                }
                add(
                    CpContextMenuItem(
                        label = if (showTranslation) s.player.hideLyricsTranslation else s.player.showLyricsTranslation,
                        icon = Icons.Rounded.Translate,
                        isSelected = showTranslation,
                        onClick = toggleTranslation,
                    ),
                )
                if (cp.player.app.platform.isAndroidPlatform()) {
                    add(
                        CpContextMenuItem(
                            label = if (isZenMode) s.player.exitImmersive else s.player.immersive,
                            icon = if (isZenMode) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            isSelected = isZenMode,
                            onClick = { isZenMode = !isZenMode },
                        ),
                    )
                }
                onChangeLyricsSource?.let { changeSource ->
                    add(
                        CpContextMenuItem(
                            label = s.player.lyricsPluginSettings,
                            icon = Icons.Rounded.AutoAwesome,
                            onClick = changeSource,
                        ),
                    )
                }
                track.mvId?.takeIf { it > 0 }?.let { mvId ->
                    add(
                        CpContextMenuItem(
                            label = s.player.playMv,
                            icon = Icons.Rounded.SmartDisplay,
                            onClick = { navigator.push(AppRoute.VideoPlayer("netease://mv/$mvId")) },
                        ),
                    )
                }

                add(CpContextMenuSeparator)

                add(
                    CpContextMenuItem(
                        label = s.player.addToPlaylistMenu,
                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        onClick = { moreSheets.showAddToPlaylist = true },
                    ),
                )
                add(
                    CpContextMenuItem(
                        label = s.player.songDetailsMenu,
                        icon = Icons.Rounded.Info,
                        onClick = { moreSheets.showSongInfo = true },
                    ),
                )
                add(
                    CpContextMenuItem(
                        label = s.player.transferDeviceMenu,
                        icon = Icons.Rounded.Cast,
                        onClick = { moreSheets.showDevicePicker = true },
                    ),
                )
                add(
                    CpContextMenuItem(
                        label = s.player.sleepTimerMenu,
                        icon = Icons.Rounded.AccessAlarm,
                        onClick = { moreSheets.showSleepTimer = true },
                    ),
                )
                add(
                    CpContextMenuItem(
                        label = s.player.copyTrackInfo,
                        icon = Icons.Rounded.ContentCopy,
                        onClick = {
                            val info = "${track.name} - ${track.artist}"
                            cp.player.app.platform.shareText(info)
                        },
                    ),
                )

                add(CpContextMenuSeparator)

                add(
                    CpContextMenuItem(
                        label = s.player.collapsePlayer,
                        icon = Icons.Rounded.KeyboardArrowDown,
                        onClick = onBack,
                    ),
                )
            }
        }

        CpContextMenu(
            items = contextMenuItems,
            modifier = Modifier.fillMaxSize(),
            passive = true,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = topChromeInset + columnsTopPadding,
                        bottom = metrics.verticalPadding,
                        start = metrics.horizontalPadding,
                        end = metrics.horizontalPadding,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Row(
                    modifier = Modifier
                        .width(metrics.contentWidth)
                        .fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(metrics.columnGap),
                ) {
                    // 桌面封面与右栏工具共用同一条顶线。
                    Column(
                        modifier = Modifier.width(leftColumnWidth).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = if (desktop) Arrangement.Top else Arrangement.Center,
                    ) {
                        val artHoverInteraction = remember { MutableInteractionSource() }
                        val artHovered by artHoverInteraction.collectIsHoveredAsState()
                        val artSize = minOf(metrics.maxArtworkSize, leftColumnWidth)

                        PlayerArtworkStage(
                            track = track,
                            isPlaying = state.isPlaying,
                            size = artSize,
                            modifier = Modifier.hoverable(artHoverInteraction),
                            artworkModifier = artworkModifier,
                            pinTopEdge = desktop,
                            overlay = {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = cp.player.app.platform.isAndroidPlatform() && (artHovered || isZenMode),
                                    enter = fadeIn(CpMotion.effectsFast()),
                                    exit = fadeOut(CpMotion.effectsFast()),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(12.dp),
                                ) {
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                                        tooltip = {
                                            PlainTooltip {
                                                CpText(if (isZenMode) s.player.exitImmersive else s.player.immersive)
                                            }
                                        },
                                        state = rememberTooltipState(),
                                    ) {
                                        Surface(
                                            onClick = { isZenMode = !isZenMode },
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = if (isDark) 0.45f else 0.36f),
                                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.24f)),
                                            modifier = Modifier.size(34.dp).cpPressScale(),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = if (isZenMode) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                                    contentDescription = if (isZenMode) s.player.exitImmersive else s.player.immersive,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = Color.White,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                        )

                        Spacer(Modifier.height(metrics.artToTitle))

                        Column(
                            modifier = Modifier.width(metrics.controlsWidth),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PlayerTrackInfoBlock(
                                state = state,
                                track = track,
                                titleStyle = titleStyle,
                                artistStyle = artistStyle,
                                onArtistClick = onArtistClick,
                                onLike = onLike,
                                onOpenMv = { mvId -> navigator.push(AppRoute.VideoPlayer("netease://mv/$mvId")) },
                                onOpenAudioEngine = { navigator.push(AppRoute.AudioEngineSettings) },
                                showMetaChips = metrics.showMetaChips && !isZenMode,
                                touchFriendly = false,
                                outlinedQualityBadge = desktop,
                                titleModifier = titleModifier,
                                artistModifier = artistModifier,
                            )

                            Spacer(Modifier.height(metrics.titleToSeek))

                            DesktopProgressSection(
                                state = state,
                                positionFlow = positionFlow,
                                initialPositionMs = initialPositionMs,
                                duration = duration,
                                seekable = seekable,
                                trackKey = track.id,
                                onSeek = onSeek,
                            )

                            Spacer(Modifier.height(metrics.seekToTransport))

                            PlayerTransportRow(
                                state = state,
                                onTogglePlay = onTogglePlay,
                                onSkipNext = onSkipNext,
                                onSkipPrev = onSkipPrev,
                                onRepeat = onRepeat,
                                onShuffle = onShuffle,
                                scale = metrics.transportScale,
                                modifier = Modifier.fillMaxWidth(),
                                subtle = desktop,
                            )

                            AnimatedVisibility(
                                visible = showVolume,
                                enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
                                exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
                            ) {
                                Column {
                                    Spacer(Modifier.height(metrics.transportToVolume))
                                    DesktopVolumeControl(
                                        volume = volume,
                                        onVolumeChange = onVolumeChange,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }

                    // 队列与评论沿用手机端弹层；右栏工具悬浮在歌词上，歌词占满整栏高度。
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        DesktopLyricsContent(
                            state = state,
                            positionFlow = positionFlow,
                            initialPositionMs = initialPositionMs,
                            onSeek = onSeek,
                            showTranslation = showTranslation,
                            onRepeat = onRepeat,
                            onLikeClick = onLike,
                            onChangeSource = onChangeLyricsSource,
                            lineFontSize = lyricFontSize,
                            focusedPaneStyle = desktop,
                            contentPadding = PaddingValues(top = 12.dp, bottom = 36.dp, start = 12.dp, end = 12.dp),
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !isZenMode,
                            enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
                            exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Row(
                                modifier = Modifier.padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (supportsComments) {
                                    PlayerInlineActionButton(
                                        icon = Icons.AutoMirrored.Rounded.Comment,
                                        label = s.player.songComments,
                                        onClick = { playerSheet = PlayerSheet.Comments },
                                    )
                                }
                                PlayerInlineActionButton(
                                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                    label = s.player.queue,
                                    onClick = { playerSheet = PlayerSheet.Queue },
                                )
                                PlayerInlineActionButton(
                                    icon = Icons.Rounded.MoreHoriz,
                                    label = s.player.more,
                                    onClick = { moreSheets.showMoreMenu = true },
                                )
                            }
                        }
                        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                            PlayerInlineActionButton(
                                icon = Icons.Rounded.Translate,
                                label = if (showTranslation) s.player.hideLyricsTranslation else s.player.showLyricsTranslation,
                                active = showTranslation,
                                onClick = toggleTranslation,
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = !isZenMode,
                enter = fadeIn(CpMotion.effectsFast()),
                exit = fadeOut(CpMotion.effectsFast()),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp + topChromeInset),
            ) {
                PlayerSheetGrabber(onClick = onBack)
            }

            AnimatedVisibility(
                visible = isZenMode,
                enter = fadeIn(CpMotion.effectsFast()),
                exit = fadeOut(CpMotion.effectsFast()),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp + topChromeInset, end = metrics.horizontalPadding),
            ) {
                PlayerInlineActionButton(
                    icon = Icons.Rounded.FullscreenExit,
                    label = s.player.exitImmersive,
                    active = true,
                    size = 36.dp,
                    onClick = { isZenMode = false },
                )
            }
        }
    }

    PlayerSecondarySheet(
        sheet = playerSheet,
        state = state,
        supportsComments = supportsComments,
        onPlayAt = onPlayAt,
        onMove = onMove,
        onRemoveQueue = onRemoveQueue,
        onClearQueue = onClearQueue,
        onShuffle = onShuffle,
        onRepeat = onRepeat,
        onClose = { playerSheet = null },
    )
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
    val s = cpStrings()
    // hover 只驱动「横条变 ✕」这个行为；视觉反馈统一交给 M3 自带 ripple。
    // 不再改图标颜色 —— 以前自定义色 + ripple 两层叠在一起，且颜色动画用弹簧
    // 会先冲到深色再回落到浅色，就是用户说的「先闪深色再保持浅色」。
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val morph by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = CpMotion.spatialFast(),
        label = "playerGrabberMorph",
    )
    val color = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .width(68.dp)
            .height(24.dp)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                onClickLabel = s.player.collapsePlayer,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val barLength = lerp(38.dp.toPx(), 16.dp.toPx(), morph)
            val thickness = 3.5.dp.toPx()
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

/**
 * 走带行：随机 / 上一首 / 播放·暂停 / 下一首 / 循环。
 *
 * 每颗按钮都是**定尺寸**，行内用 `SpaceBetween` 摊开剩余宽度，占满父级 `controlsWidth`，
 * 与上方的进度条和下方的音量控制形成完全一致的边界对齐。
 */
@Composable
private fun DesktopProgressSection(
    state: PlaybackUiState,
    positionFlow: Flow<Long>?,
    initialPositionMs: Long,
    duration: Long,
    seekable: Boolean,
    trackKey: String,
    onSeek: (Long) -> Unit,
) {
    // Keep the 200ms position tick inside this small subtree. The surrounding desktop
    // player owns artwork, metadata, queue and transport controls and remains skippable.
    val positionMs = positionFlow?.collectAsState(initial = initialPositionMs)?.value
        ?: state.positionMs
    CpSeekBar(
        positionMs = positionMs,
        durationMs = duration,
        onSeek = onSeek,
        enabled = seekable,
        waveFlowing = state.isPlaying,
        trackKey = trackKey,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val formattedPosition = remember(positionMs / 1000) { formatTimeMs(positionMs) }
        CpText(
            formattedPosition,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CpText(
            SeekAvailability.durationLabel(duration),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SeekAvailability.disabledReason(duration, state.isLocalizing)?.let { reason ->
        CpText(
            reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun PlayerTransportRow(
    state: PlaybackUiState,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    /** 按键缩放：封面变大时整排跟着变大（见 `PlayerMetrics.transportScale`）。 */
    scale: Float = 1f,
    modifier: Modifier = Modifier,
    subtle: Boolean = false,
) {
    val s = cpStrings()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerModeButton(
            icon = Icons.Rounded.Shuffle,
            label = s.player.shuffle,
            active = state.shuffleEnabled,
            onClick = onShuffle,
            size = 40.dp * scale,
            subtle = subtle,
        )
        PlayerSkipButton(
            icon = Icons.Rounded.SkipPrevious,
            label = s.player.previousTrack,
            onClick = onSkipPrev,
            size = (if (subtle) 44.dp else 48.dp) * scale,
        )
        PlayerPlayPauseButton(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            onClick = onTogglePlay,
            size = 62.dp * scale,
            subtle = subtle,
        )
        PlayerSkipButton(
            icon = Icons.Rounded.SkipNext,
            label = s.player.nextTrack,
            onClick = onSkipNext,
            size = (if (subtle) 44.dp else 48.dp) * scale,
        )
        PlayerModeButton(
            icon = if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            label = when (state.repeatMode) {
                RepeatMode.ONE -> s.player.repeatOne
                RepeatMode.ALL -> s.player.repeatAll
                RepeatMode.OFF -> s.player.repeat
            },
            active = state.repeatMode != RepeatMode.OFF,
            onClick = onRepeat,
            size = 40.dp * scale,
            subtle = subtle,
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
    subtle: Boolean = false,
) {
    // 只保留激活态；hover / press 反馈统一交给 Surface 自带的 M3 ripple。
    val container by animateColorAsState(
        targetValue = if (active && !subtle) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = CpMotion.effectsFast(),
        label = "modeButtonContainer",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = container,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(size * 0.5f),
                tint = when {
                    active && subtle -> MaterialTheme.colorScheme.primary
                    active -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
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
    subtle: Boolean = false,
) {
    val s = cpStrings()
    val colors = MaterialTheme.colorScheme
    val iconColor = if (subtle) colors.onSurface else colors.onPrimary
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
        color = if (subtle) colors.onSurface.copy(alpha = 0.09f) else colors.primary,
        contentColor = iconColor,
        interactionSource = interaction,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isBuffering) {
                CpLoadingIndicator(
                    modifier = Modifier.size(size * 0.42f),
                    color = iconColor,
                )
            } else {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
                    label = "desktopPlayIcon",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = s.player.playOrPause(playing),
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopVolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    var lastNonZero by remember { mutableStateOf(if (volume > 0.05f) volume else 1f) }
    SideEffect { if (volume > 0.05f) lastNonZero = volume }
    val isMuted = volume <= 0.001f

    // 支持鼠标滚轮微调音量，两端按钮带 Tooltip 提示。
    Row(
        modifier = modifier.miniPlayerMouseWheel { deltaY ->
            onVolumeChange(miniPlayerWheelVolume(volume, deltaY))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { CpText(if (isMuted) s.player.unmute else s.player.mute) } },
            state = rememberTooltipState(),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(),
                        onClickLabel = if (isMuted) s.player.unmute else s.player.mute,
                    ) {
                        if (isMuted) onVolumeChange(lastNonZero) else onVolumeChange(0f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        isMuted -> Icons.AutoMirrored.Rounded.VolumeOff
                        volume < 0.35f -> Icons.AutoMirrored.Rounded.VolumeMute
                        else -> Icons.AutoMirrored.Rounded.VolumeDown
                    },
                    contentDescription = if (isMuted) s.player.unmute else s.player.mute,
                    modifier = Modifier.size(16.dp),
                    tint = if (isMuted) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = cpNestedSurface(),
            ),
        )

        Spacer(Modifier.width(6.dp))

        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { CpText(s.player.maxVolume) } },
            state = rememberTooltipState(),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(),
                        onClickLabel = s.player.maxVolume,
                    ) {
                        onVolumeChange(1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = s.player.maxVolume,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun Artwork(
    url: String?,
    modifier: Modifier,
    targetSize: Dp = 420.dp,
) {
    val targetPx = with(LocalDensity.current) { targetSize.toPx() }
        .toInt()
        .coerceIn(160, 1024)
    cp.player.app.ui.component.CpArtworkImage(
        request = rememberCoverImageRequest(url, targetPx = targetPx, placeholderBucketPx = 256),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholderCorner = 24.dp,
        modifier = modifier,
    )
}
