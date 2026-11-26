package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AccessAlarm
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.BackHandler
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.LyricContent
import cp.player.app.ui.component.PlayerArtworkStage
import cp.player.app.ui.component.PlayerSecondarySheet
import cp.player.app.ui.component.PlayerSheet
import cp.player.app.ui.component.PlayerMoreSheetState
import cp.player.app.ui.component.PlayerMoreSheets
import cp.player.app.ui.component.PlayerTimeline
import cp.player.app.ui.component.PlayerTrackInfoBlock
import cp.player.app.ui.component.playerDismissDragGesture
import cp.player.app.ui.component.TrackArtistText
import cp.player.app.ui.component.cpFluidBackground
import cp.player.app.ui.component.cpPressScale
import cp.player.app.ui.component.modernPlayerFallbackColors
import cp.player.app.ui.component.rememberActiveProviderSupports
import cp.player.app.ui.component.rememberPlayerMoreSheetState
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.theme.CpMotion
import cp.player.core.music.ArtistSummary
import cp.player.core.playback.PlaybackUiState
import cp.player.core.provider.ProviderCapability
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 平板端全屏播放页宿主。
 *
 * 区分 Medium（600–839dp，竖屏/分屏）与 Expanded（>=840dp，横屏/大屏）：
 * - 补齐系统栏与刘海安全区 (`systemBars union displayCutout`)；
 * - 全部触控目标 >= 48dp，支持顶部/封面下拉关闭；
 * - 横屏以「设备投放 / 睡眠定时 / 相似推荐 / 音效 / 更多」触控快捷行取代桌面常驻音量条；
 * - 横屏右栏固定显示歌词，评论和队列使用独立弹层。
 */
@Composable
fun TabletPlayerScreen(
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
    transitionRunning: Boolean = false,
    containerModifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    artistModifier: Modifier = Modifier,
) {
    val moreSheets = rememberPlayerMoreSheetState()
    TabletPlayerLayout(
        state = state,
        positionFlow = positionFlow,
        initialPositionMs = initialPositionMs,
        moreSheets = moreSheets,
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
        transitionRunning = transitionRunning,
        containerModifier = containerModifier,
        artworkModifier = artworkModifier,
        titleModifier = titleModifier,
        artistModifier = artistModifier,
    )
    PlayerMoreSheets(state = state, sheets = moreSheets)
}

/**
 * 平板端播放页版式本体（无 `AppModel.playback` 依赖，可用于 `ImageComposeScene` 离屏测试）。
 */
@Composable
internal fun TabletPlayerLayout(
    state: PlaybackUiState,
    positionFlow: Flow<Long>? = null,
    initialPositionMs: Long = state.positionMs,
    moreSheets: PlayerMoreSheetState = rememberPlayerMoreSheetState(),
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
    transitionRunning: Boolean = false,
    containerModifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    artistModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val s = cpStrings()
    val haptics = LocalCpHaptics.current
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()
    val config by AppModel.mobilePlayerCustomConfigFlow.collectAsState()
    val fluidEnabled by AppModel.fluidBackgroundFlow.collectAsState()
    val supportsComments = rememberActiveProviderSupports(ProviderCapability.COMMENTS)

    var showTranslation by remember(config.showTranslation) { mutableStateOf(config.showTranslation) }
    var lyricsExpanded by rememberSaveable { mutableStateOf(false) }
    var playerSheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }
    var isImmersiveActive by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableLongStateOf(currentTimeMillis()) }

    val onOpenQueue = {
        haptics.perform(CpHaptic.Tick)
        playerSheet = PlayerSheet.Queue
    }
    val onOpenComments = {
        haptics.perform(CpHaptic.Tick)
        playerSheet = PlayerSheet.Comments
    }

    val anySheetOpen = playerSheet != null || moreSheets.showMoreMenu || moreSheets.showSimilarSongs ||
        moreSheets.showAddToPlaylist || moreSheets.showSleepTimer ||
        moreSheets.showSongInfo || moreSheets.showDevicePicker ||
        moreSheets.showLyricOffset || moreSheets.showDjDesk

    val isImmersive = isAndroidPlatform() && isImmersiveActive && !anySheetOpen

    val fallbackColors = remember(
        colors.surfaceContainerHigh,
        colors.surfaceContainerLow,
        colors.surface,
    ) {
        modernPlayerFallbackColors(colors)
    }
    val fallbackBrush = remember(fallbackColors) { Brush.verticalGradient(fallbackColors) }

    val onArtistClick: ((ArtistSummary) -> Unit)? = remember(navigator) {
        { artist -> navigator.push(AppRoute.UserProfile(artist.id, artist.name, artist.resourceId)) }
    }
    val onChangeLyricsSource: (() -> Unit)? = remember(navigator) {
        { navigator.push(AppRoute.LyricsPluginSettings) }
    }
    val onToggleTranslation: () -> Unit = {
        val next = !showTranslation
        showTranslation = next
        AppModel.updateMobilePlayerCustomConfig { it.copy(showTranslation = next) }
    }

    val dismissHandler = cp.player.app.ui.component.LocalPlayerDismissHandler.current
    val currentBack by rememberUpdatedState(onBack)
    val dragOffset = remember { Animatable(0f) }
    val dragThreshold = with(LocalDensity.current) { 100.dp.toPx() }
    val resetMotion = CpMotion.spatial<Float>()
    val dismissGesture = if (dismissHandler != null) {
        Modifier.playerDismissDragGesture(dismissHandler)
    } else {
        Modifier.pointerInput(dragThreshold) {
            detectVerticalDragGestures(
                onVerticalDrag = { change, amount ->
                    if (amount > 0f || dragOffset.value > 0f) {
                        change.consume()
                        scope.launch {
                            dragOffset.snapTo((dragOffset.value + amount).coerceIn(0f, dragThreshold * 2f))
                        }
                    }
                },
                onDragEnd = {
                    if (dragOffset.value >= dragThreshold) {
                        haptics.perform(CpHaptic.Confirm)
                        currentBack()
                    }
                    scope.launch { dragOffset.animateTo(0f, resetMotion) }
                },
                onDragCancel = { scope.launch { dragOffset.animateTo(0f, resetMotion) } },
            )
        }
    }

    val immersiveTapRestore = if (isImmersive) {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    lastInteractionMs = currentTimeMillis()
                    isImmersiveActive = false
                    event.changes.forEach { it.consume() }
                }
            }
        }
    } else Modifier

    BackHandler(enabled = isImmersive) {
        isImmersiveActive = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (dismissHandler == null && dragOffset.value > 0f) {
                    Modifier.graphicsLayer { translationY = dragOffset.value }
                } else Modifier
            )
            .then(containerModifier)
            .cpFluidBackground(
                enabled = fluidEnabled,
                fallback = fallbackBrush,
                pauseShaderAnimation = transitionRunning,
                coverUrl = state.currentTrack?.coverUrl,
                playing = state.isPlaying,
            )
            .then(immersiveTapRestore),
    ) {
        val track = state.currentTrack
        if (track == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
                contentAlignment = Alignment.Center,
            ) {
                if (!state.error.isNullOrBlank()) {
                    CpText(
                        text = state.error.orEmpty(),
                        color = colors.error,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                } else {
                    CpLoadingIndicator(Modifier.size(36.dp))
                }
            }
            return@Box
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            contentAlignment = Alignment.TopCenter,
        ) {
            val isLandscapeSplit = maxWidth >= 840.dp && maxWidth > maxHeight

            // 只有竖屏主动展开歌词后才启用自动沉浸。
            LaunchedEffect(
                isLandscapeSplit, lyricsExpanded, anySheetOpen, isImmersiveActive, lastInteractionMs,
                config.lyricsImmersiveEnabled, config.lyricsImmersiveTimeoutSeconds,
            ) {
                if (isLandscapeSplit || !lyricsExpanded || anySheetOpen || isImmersiveActive ||
                    !config.lyricsImmersiveEnabled || !isAndroidPlatform()
                ) return@LaunchedEffect
                val timeoutMs = config.lyricsImmersiveTimeoutSeconds.coerceIn(2, 15) * 1000L
                delay((timeoutMs - (currentTimeMillis() - lastInteractionMs)).coerceAtLeast(0L))
                isImmersiveActive = true
            }

            BackHandler(enabled = !isLandscapeSplit && !isImmersive && lyricsExpanded && !anySheetOpen) {
                lyricsExpanded = false
                isImmersiveActive = false
            }

            if (isLandscapeSplit) {
                TabletLandscapeContent(
                    state = state,
                    track = track,
                    positionFlow = positionFlow,
                    initialPositionMs = initialPositionMs,
                    supportsComments = supportsComments,
                    onOpenQueue = onOpenQueue,
                    onOpenComments = onOpenComments,
                    showTranslation = showTranslation,
                    onToggleTranslation = onToggleTranslation,
                    isImmersive = isImmersive,
                    onToggleImmersive = {
                        haptics.perform(CpHaptic.Tick)
                        isImmersiveActive = !isImmersiveActive
                    },
                    onBack = onBack,
                    dismissGesture = dismissGesture,
                    onTogglePlay = onTogglePlay,
                    onSeek = onSeek,
                    onSkipNext = onSkipNext,
                    onSkipPrev = onSkipPrev,
                    onRepeat = onRepeat,
                    onShuffle = onShuffle,
                    onLike = onLike,
                    onArtistClick = onArtistClick,
                    onOpenMv = { mvId -> navigator.push(AppRoute.VideoPlayer("netease://mv/$mvId")) },
                    onOpenAudioEngine = { navigator.push(AppRoute.AudioEngineSettings) },
                    onOpenAudioEffects = { navigator.push(AppRoute.AudioEffectSettings) },
                    onChangeLyricsSource = onChangeLyricsSource,
                    moreSheets = moreSheets,
                    artworkModifier = artworkModifier,
                    titleModifier = titleModifier,
                    artistModifier = artistModifier,
                )
            } else {
                TabletPortraitContent(
                    state = state,
                    track = track,
                    positionFlow = positionFlow,
                    initialPositionMs = initialPositionMs,
                    lyricsExpanded = lyricsExpanded,
                    onToggleLyrics = {
                        haptics.perform(CpHaptic.Tick)
                        lastInteractionMs = currentTimeMillis()
                        lyricsExpanded = !lyricsExpanded
                        if (!lyricsExpanded) isImmersiveActive = false
                    },
                    onCollapseLyrics = {
                        lyricsExpanded = false
                        isImmersiveActive = false
                    },
                    supportsComments = supportsComments,
                    onOpenQueue = onOpenQueue,
                    onOpenComments = onOpenComments,
                    showTranslation = showTranslation,
                    onToggleTranslation = onToggleTranslation,
                    isImmersive = isImmersive && lyricsExpanded,
                    onToggleImmersive = {
                        haptics.perform(CpHaptic.Tick)
                        lastInteractionMs = currentTimeMillis()
                        isImmersiveActive = !isImmersiveActive
                    },
                    onBack = onBack,
                    dismissGesture = dismissGesture,
                    onTogglePlay = onTogglePlay,
                    onSeek = onSeek,
                    onSkipNext = onSkipNext,
                    onSkipPrev = onSkipPrev,
                    onRepeat = onRepeat,
                    onShuffle = onShuffle,
                    onLike = onLike,
                    onArtistClick = onArtistClick,
                    onOpenMv = { mvId -> navigator.push(AppRoute.VideoPlayer("netease://mv/$mvId")) },
                    onOpenAudioEngine = { navigator.push(AppRoute.AudioEngineSettings) },
                    onChangeLyricsSource = onChangeLyricsSource,
                    moreSheets = moreSheets,
                    artworkModifier = artworkModifier,
                    titleModifier = titleModifier,
                    artistModifier = artistModifier,
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
        onClose = {
            playerSheet = null
            lastInteractionMs = currentTimeMillis()
        },
    )
}

@Composable
private fun TabletLandscapeContent(
    state: PlaybackUiState,
    track: cp.player.core.music.TrackSummary,
    positionFlow: Flow<Long>?,
    initialPositionMs: Long,
    supportsComments: Boolean,
    onOpenQueue: () -> Unit,
    onOpenComments: () -> Unit,
    showTranslation: Boolean,
    onToggleTranslation: () -> Unit,
    isImmersive: Boolean,
    onToggleImmersive: () -> Unit,
    onBack: () -> Unit,
    dismissGesture: Modifier,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onLike: () -> Unit,
    onArtistClick: ((ArtistSummary) -> Unit)?,
    onOpenMv: (Long) -> Unit,
    onOpenAudioEngine: () -> Unit,
    onOpenAudioEffects: () -> Unit,
    onChangeLyricsSource: (() -> Unit)?,
    moreSheets: PlayerMoreSheetState,
    artworkModifier: Modifier,
    titleModifier: Modifier,
    artistModifier: Modifier,
) {
    val s = cpStrings()
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val contentWidth = minOf(maxWidth, 1360.dp)
        val columnGap = if (contentWidth >= 1040.dp) 32.dp else 20.dp
        val leftWidth = (contentWidth * 0.40f).coerceIn(280.dp, 420.dp)
        val bodyHeight = maxHeight - (if (isImmersive) 0.dp else CpSpacing.touchTarget)
        val showMetaChips = !isImmersive && bodyHeight >= 540.dp
        val showQuickDock = !isImmersive && bodyHeight >= 460.dp
        val transportScale = (leftWidth / 380.dp).coerceIn(0.90f, 1.08f)
        val artGap = if (bodyHeight < 460.dp) 8.dp else 16.dp
        val controlsBudget = 124.dp + 62.dp * transportScale + artGap +
            (if (showMetaChips) 28.dp else 0.dp) + (if (showQuickDock) 56.dp else 0.dp)
        val artSize = minOf(leftWidth, (bodyHeight - controlsBudget).coerceAtLeast(72.dp), 400.dp)

        Column(Modifier.width(contentWidth).fillMaxHeight()) {
            // 顶部触控折叠区（支持点按与下拉关闭）
            AnimatedVisibility(
                visible = !isImmersive,
                enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
                exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(CpSpacing.touchTarget)
                        .then(dismissGesture),
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.CenterStart).size(CpSpacing.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = s.player.collapsePlayer,
                            tint = colors.onSurface,
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(width = 72.dp, height = CpSpacing.touchTarget)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = s.player.collapse,
                                onClick = onBack,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(colors.onSurface.copy(alpha = 0.38f)),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(columnGap),
                verticalAlignment = Alignment.Top,
            ) {
                // 左栏：舞台封面 + 信息 + 进度 + 走带 + 触控快捷坞
                Column(
                    modifier = Modifier.width(leftWidth).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    PlayerArtworkStage(
                        track = track,
                        isPlaying = state.isPlaying,
                        size = artSize,
                        artworkModifier = artworkModifier.then(dismissGesture),
                        pinTopEdge = true,
                    )

                    Spacer(Modifier.height(artGap))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlayerTrackInfoBlock(
                            state = state,
                            track = track,
                            titleStyle = if (leftWidth >= 360.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                            artistStyle = MaterialTheme.typography.titleMedium,
                            onArtistClick = onArtistClick,
                            onLike = onLike,
                            onOpenMv = onOpenMv,
                            onOpenAudioEngine = onOpenAudioEngine,
                            showMetaChips = showMetaChips,
                            touchFriendly = true,
                            outlinedQualityBadge = true,
                            titleModifier = titleModifier,
                            artistModifier = artistModifier,
                        )

                        Spacer(Modifier.height(10.dp))

                        PlayerTimeline(
                            state = state,
                            positionFlow = positionFlow,
                            initialPositionMs = initialPositionMs,
                            onSeek = onSeek,
                        )

                        Spacer(Modifier.height(8.dp))

                        PlayerTransportRow(
                            state = state,
                            onTogglePlay = onTogglePlay,
                            onSkipNext = onSkipNext,
                            onSkipPrev = onSkipPrev,
                            onRepeat = onRepeat,
                            onShuffle = onShuffle,
                            scale = transportScale,
                            subtle = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (showQuickDock) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TabletDockIconButton(
                                    icon = Icons.Rounded.Cast,
                                    label = s.player.audioOutputDevice,
                                    onClick = { moreSheets.showDevicePicker = true },
                                )
                                TabletDockIconButton(
                                    icon = Icons.Rounded.AccessAlarm,
                                    label = s.player.sleepTimer,
                                    active = (state.sleepTimerRemainingMs ?: 0L) > 0L || state.sleepAfterTrack,
                                    onClick = { moreSheets.showSleepTimer = true },
                                )
                                TabletDockIconButton(
                                    icon = Icons.Rounded.GraphicEq,
                                    label = s.audioEffect.screenTitle,
                                    onClick = onOpenAudioEffects,
                                )
                            }
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedVisibility(
                        visible = !isImmersive,
                        enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
                        exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isAndroidPlatform()) {
                                TabletDockIconButton(
                                    icon = Icons.Rounded.Fullscreen,
                                    label = s.player.immersive,
                                    onClick = onToggleImmersive,
                                )
                            }
                            if (supportsComments) {
                                TabletDockIconButton(Icons.AutoMirrored.Rounded.Comment, s.player.songComments, onOpenComments)
                            }
                            TabletDockIconButton(Icons.AutoMirrored.Rounded.QueueMusic, s.player.queue, onOpenQueue)
                            TabletDockIconButton(Icons.Rounded.MoreHoriz, s.player.more, { moreSheets.showMoreMenu = true })
                        }
                    }

                    TabletLyricsPane(
                        state = state,
                        positionFlow = positionFlow,
                        initialPositionMs = initialPositionMs,
                        showTranslation = showTranslation,
                        onToggleTranslation = onToggleTranslation,
                        onSeek = onSeek,
                        lineFontSize = if (contentWidth >= 1100.dp) 32.sp else 28.sp,
                        onChangeSource = onChangeLyricsSource,
                        isImmersive = isImmersive,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private val TabletPortraitControlsWidth = 440.dp

@Composable
private fun TabletPortraitContent(
    state: PlaybackUiState,
    track: cp.player.core.music.TrackSummary,
    positionFlow: Flow<Long>?,
    initialPositionMs: Long,
    lyricsExpanded: Boolean,
    onToggleLyrics: () -> Unit,
    onCollapseLyrics: () -> Unit,
    supportsComments: Boolean,
    onOpenQueue: () -> Unit,
    onOpenComments: () -> Unit,
    showTranslation: Boolean,
    onToggleTranslation: () -> Unit,
    isImmersive: Boolean,
    onToggleImmersive: () -> Unit,
    onBack: () -> Unit,
    dismissGesture: Modifier,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onLike: () -> Unit,
    onArtistClick: ((ArtistSummary) -> Unit)?,
    onOpenMv: (Long) -> Unit,
    onOpenAudioEngine: () -> Unit,
    onChangeLyricsSource: (() -> Unit)?,
    moreSheets: PlayerMoreSheetState,
    artworkModifier: Modifier,
    titleModifier: Modifier,
    artistModifier: Modifier,
) {
    val s = cpStrings()
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .padding(horizontal = CpSpacing.pageHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = !isImmersive,
            enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
            exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(CpSpacing.touchTarget)
                    .then(dismissGesture),
            ) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(width = 72.dp, height = CpSpacing.touchTarget)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = s.player.collapse,
                            onClick = onBack,
                        )
                        .semantics { contentDescription = s.player.collapse },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(colors.onSurface.copy(alpha = 0.40f)),
                    )
                }
            }
        }

        val enterSlideSpec = CpMotion.spatial<IntOffset>()
        val exitSlideSpec = CpMotion.spatialFast<IntOffset>()
        val enterFadeSpec = CpMotion.effects<Float>()
        val exitFadeSpec = CpMotion.effectsFast<Float>()
        val enterScaleSpec = CpMotion.spatial<Float>()
        val exitScaleSpec = CpMotion.spatialFast<Float>()

        AnimatedContent(
            targetState = lyricsExpanded,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val expanding = targetState && !initialState
                val collapsing = !targetState && initialState
                if (expanding || collapsing) {
                    val enter = fadeIn(enterFadeSpec) +
                        slideInVertically(enterSlideSpec) { h -> if (expanding) h / 5 else -h / 5 } +
                        scaleIn(
                            animationSpec = enterScaleSpec,
                            initialScale = 0.97f,
                            transformOrigin = TransformOrigin(0.5f, if (expanding) 0.35f else 0.75f),
                        )
                    val exit = fadeOut(exitFadeSpec) +
                        slideOutVertically(exitSlideSpec) { h -> if (expanding) -h / 6 else h / 6 } +
                        scaleOut(
                            animationSpec = exitScaleSpec,
                            targetScale = 1.015f,
                            transformOrigin = TransformOrigin(0.5f, if (expanding) 0.75f else 0.35f),
                        )
                    (enter togetherWith exit using SizeTransform(clip = false)).apply {
                        targetContentZIndex = if (expanding) 1f else -1f
                    }
                } else {
                    fadeIn(enterFadeSpec) togetherWith fadeOut(exitFadeSpec)
                }
            },
            label = "tabletPortraitFocus",
        ) { expanded ->
            if (!expanded) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val artSize = minOf(maxWidth, (maxHeight - 124.dp).coerceAtLeast(72.dp), 420.dp)
                    Column(
                        modifier = Modifier.widthIn(max = TabletPortraitControlsWidth).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlayerArtworkStage(
                            track = track,
                            isPlaying = state.isPlaying,
                            size = artSize,
                            artworkModifier = artworkModifier.then(dismissGesture),
                        )
                        Spacer(Modifier.height(16.dp))
                        PlayerTrackInfoBlock(
                            state = state,
                            track = track,
                            titleStyle = MaterialTheme.typography.headlineSmall,
                            artistStyle = MaterialTheme.typography.titleMedium,
                            onArtistClick = onArtistClick,
                            onLike = onLike,
                            onOpenMv = onOpenMv,
                            onOpenAudioEngine = onOpenAudioEngine,
                            showMetaChips = true,
                            touchFriendly = true,
                            outlinedQualityBadge = true,
                            titleModifier = titleModifier,
                            artistModifier = artistModifier,
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = !isImmersive,
                        enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
                        exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayerArtworkStage(
                                track = track,
                                isPlaying = state.isPlaying,
                                size = CpSpacing.touchTarget,
                                playingCorner = 12.dp,
                                pausedCorner = 14.dp,
                                showAmbientGlow = false,
                                modifier = Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = s.player.backToPlayer,
                                    onClick = onCollapseLyrics,
                                ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(onClick = onCollapseLyrics),
                            ) {
                                CpText(
                                    text = track.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    marqueeEnabled = state.isPlaying,
                                )
                                TrackArtistText(
                                    track = track,
                                    onArtistClick = onArtistClick,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            if (isAndroidPlatform()) {
                                TabletDockIconButton(
                                    icon = Icons.Rounded.Fullscreen,
                                    label = s.player.immersive,
                                    onClick = onToggleImmersive,
                                )
                            }
                        }
                    }

                    TabletLyricsPane(
                        state = state,
                        positionFlow = positionFlow,
                        initialPositionMs = initialPositionMs,
                        showTranslation = showTranslation,
                        onToggleTranslation = onToggleTranslation,
                        onSeek = onSeek,
                        lineFontSize = 30.sp,
                        onChangeSource = onChangeLyricsSource,
                        isImmersive = isImmersive,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }

        // 底部稳态控制区：进度条、走带与独立快捷操作。
        AnimatedVisibility(
            visible = !isImmersive,
            enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
            exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
        ) {
            Column(
                modifier = Modifier.widthIn(max = TabletPortraitControlsWidth).fillMaxWidth().padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlayerTimeline(
                    state = state,
                    positionFlow = positionFlow,
                    initialPositionMs = initialPositionMs,
                    onSeek = onSeek,
                )
                Spacer(Modifier.height(10.dp))
                PlayerTransportRow(
                    state = state,
                    onTogglePlay = onTogglePlay,
                    onSkipNext = onSkipNext,
                    onSkipPrev = onSkipPrev,
                    onRepeat = onRepeat,
                    onShuffle = onShuffle,
                    scale = 1.06f,
                    subtle = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabletDockIconButton(
                        icon = Icons.Rounded.Lyrics,
                        label = if (lyricsExpanded) s.player.lyricsCollapse else s.player.lyricsExpand,
                        active = lyricsExpanded,
                        onClick = onToggleLyrics,
                    )
                    if (supportsComments) {
                        TabletDockIconButton(Icons.AutoMirrored.Rounded.Comment, s.player.songComments, onOpenComments)
                    }
                    TabletDockIconButton(
                        icon = Icons.Rounded.Cast,
                        label = s.player.audioOutputDevice,
                        onClick = { moreSheets.showDevicePicker = true },
                    )
                    TabletDockIconButton(Icons.AutoMirrored.Rounded.QueueMusic, s.player.queue, onOpenQueue)
                    TabletDockIconButton(Icons.Rounded.MoreHoriz, s.player.more, { moreSheets.showMoreMenu = true })
                }
            }
        }
    }
}

@Composable
private fun TabletLyricsPane(
    state: PlaybackUiState,
    positionFlow: Flow<Long>?,
    initialPositionMs: Long,
    showTranslation: Boolean,
    onToggleTranslation: () -> Unit,
    onSeek: (Long) -> Unit,
    lineFontSize: TextUnit,
    onChangeSource: (() -> Unit)?,
    isImmersive: Boolean,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    Box(modifier) {
        LyricContent(
            state = state,
            positionFlow = positionFlow,
            initialPositionMs = initialPositionMs,
            showTranslation = showTranslation,
            onSeek = onSeek,
            lineFontSize = lineFontSize,
            onChangeSource = onChangeSource,
            focusedPaneStyle = true,
            modifier = Modifier.fillMaxSize(),
        )
        if (!isImmersive) {
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp)) {
                TabletDockIconButton(
                    icon = Icons.Rounded.Translate,
                    label = if (showTranslation) s.player.hideLyricsTranslation else s.player.showLyricsTranslation,
                    active = showTranslation,
                    onClick = onToggleTranslation,
                )
            }
        }
    }
}

@Composable
private fun TabletDockIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean? = null,
) {
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(
        targetValue = if (active == true) colors.primary else colors.onSurface,
        animationSpec = CpMotion.effectsFast(),
        label = "tabletActionTint",
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(CpSpacing.touchTarget)
            .then(if (active == null) Modifier else Modifier.semantics { selected = active })
            .cpPressScale(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = tint,
        )
    }
}
