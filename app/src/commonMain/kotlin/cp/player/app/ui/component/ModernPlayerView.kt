package cp.player.app.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.BackHandler
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.model.CommentScreenModel
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.cpNestedSurface
import cp.player.core.music.ArtistSummary
import cp.player.core.playback.PlaybackUiState
import cp.player.core.provider.ProviderCapability
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

internal fun modernPlayerFallbackColors(colorScheme: ColorScheme): List<Color> =
    listOf(colorScheme.surfaceContainerHigh, colorScheme.surfaceContainerLow, colorScheme.surface)

/** Mobile player host. Playback, sheets and navigation stay outside the layout. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ModernPlayerScreenContent(
    state: PlaybackUiState,
    positionFlow: Flow<Long>? = null,
    initialPositionMs: Long = state.positionMs,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onRemoveQueue: (Int) -> Unit,
    onMoveQueue: (Int, Int) -> Unit,
) {
    val navigator = LocalAppNavigator.current
    val config by AppModel.mobilePlayerCustomConfigFlow.collectAsState()
    val haptics = LocalCpHaptics.current
    val scope = rememberCoroutineScope()
    val supportsComments = rememberActiveProviderSupports(ProviderCapability.COMMENTS)
    var lyricsExpanded by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    val more = rememberPlayerMoreSheetState()
    var isImmersiveActive by remember { mutableStateOf(false) }

    val anySheetOpen = showQueue || showComments || more.showMoreMenu || more.showSimilarSongs ||
        more.showAddToPlaylist || more.showSleepTimer || more.showSongInfo || more.showDevicePicker || more.showLyricOffset
    val isImmersive = isAndroidPlatform() && lyricsExpanded && config.lyricsImmersiveEnabled && isImmersiveActive && !anySheetOpen

    BackHandler(enabled = isImmersive) {
        isImmersiveActive = false
    }

    BackHandler(enabled = !isImmersive && lyricsExpanded && !showQueue && !showComments) {
        lyricsExpanded = false
    }

    val dismissHandler = LocalPlayerDismissHandler.current
    val allowSharedBounds = LocalPlayerAllowSharedBounds.current
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
                    if (amount > 0 || dragOffset.value > 0) {
                        change.consume()
                        scope.launch {
                            dragOffset.snapTo((dragOffset.value + amount).coerceIn(0f, dragThreshold * 2))
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
    val onArtist: ((ArtistSummary) -> Unit)? = remember(navigator) {
        { artist -> navigator.push(AppRoute.UserProfile(artist.id, artist.name, artist.resourceId)) }
    }
    val onChangeSource: (() -> Unit)? = remember(navigator) {
        { navigator.push(AppRoute.LyricsPluginSettings) }
    }

    val currentTrack = state.currentTrack
    val containerSharedModifier = if (allowSharedBounds && LocalPlayerShareContainer.current) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState("player-container"),
            animatedVisibilityScope = animatedVisibilityScope,
            zIndexInOverlay = 0f,
        )
    } else Modifier

    val artworkSharedModifier = if (allowSharedBounds && currentTrack != null) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState("cover-${currentTrack.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
            zIndexInOverlay = 1f,
            clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.medium),
        )
    } else Modifier

    val titleModifier = if (allowSharedBounds && currentTrack != null) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState("title-${currentTrack.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.CenterStart),
            zIndexInOverlay = 1f,
        )
    } else Modifier

    val artistModifier = if (allowSharedBounds && currentTrack != null) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState("artist-${currentTrack.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.CenterStart),
            zIndexInOverlay = 1f,
        )
    } else Modifier

    val colorScheme = MaterialTheme.colorScheme
    val fallbackColors = remember(
        colorScheme.surfaceContainerHigh,
        colorScheme.surfaceContainerLow,
        colorScheme.surface,
    ) {
        modernPlayerFallbackColors(colorScheme)
    }
    val backgroundBrush = remember(fallbackColors) { Brush.verticalGradient(fallbackColors) }
    val fluidBackground by AppModel.fluidBackgroundFlow.collectAsState()

    Box(
        Modifier.fillMaxSize()
            .then(
                if (dismissHandler == null && dragOffset.value > 0f) {
                    Modifier.graphicsLayer { translationY = dragOffset.value }
                } else Modifier
            )
            .then(containerSharedModifier)
            .cpFluidBackground(
                enabled = fluidBackground,
                fallback = backgroundBrush,
                pauseShaderAnimation = shouldPauseFluidBackgroundDuringNavigation(
                    animatedVisibilityScope.transition.isRunning,
                ),
                coverUrl = state.currentTrack?.coverUrl,
            ),
    ) {
        MobileNowPlayingLayout(
            state = state,
            positionFlow = positionFlow,
            initialPositionMs = initialPositionMs,
            config = config,
            lyricsExpanded = lyricsExpanded,
            supportsComments = supportsComments,
            onBack = onBack,
            onToggleLyrics = {
                haptics.perform(CpHaptic.Tick)
                val target = !lyricsExpanded
                lyricsExpanded = target
                if (!target) {
                    isImmersiveActive = false
                }
            },
            onToggleTranslation = {
                AppModel.updateMobilePlayerCustomConfig { it.copy(showTranslation = !it.showTranslation) }
            },
            onTogglePlay = onTogglePlay,
            onSeek = onSeek,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrev,
            onLike = { AppModel.toggleFavoriteOf(state.currentTrack?.id) },
            onArtistClick = onArtist,
            onQueue = { showQueue = true },
            onComments = { showComments = true },
            onMore = { more.showMoreMenu = true },
            onCast = { more.showDevicePicker = true },
            modifier = Modifier.fillMaxSize(),
            contentModifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            dismissGesture = dismissGesture,
            artworkModifier = artworkSharedModifier,
            titleModifier = titleModifier,
            artistModifier = artistModifier,
            isImmersive = isImmersive,
            onToggleImmersive = {
                haptics.perform(CpHaptic.Tick)
                isImmersiveActive = !isImmersiveActive
            },
            onImmersiveChange = { active ->
                isImmersiveActive = active
            },
            isTransitionRunning = animatedVisibilityScope.transition.isRunning,
        ) { modifier ->
            LyricContent(
                state = state,
                positionFlow = positionFlow,
                initialPositionMs = initialPositionMs,
                showTranslation = config.showTranslation,
                onSeek = onSeek,
                onChangeSource = onChangeSource,
                modifier = modifier,
                contentPadding = PaddingValues(vertical = 32.dp),
            )
        }
    }

    if (showComments && supportsComments) {
        state.currentTrack?.let { track ->
            ModernCommentBottomSheet(track.id) { showComments = false }
        }
    }
    if (showQueue) {
        QueueBottomSheet(
            queue = state.queue,
            currentIndex = state.currentIndex,
            onPlayAt = onPlayAt,
            onRemove = onRemoveQueue,
            onMove = onMoveQueue,
            onClear = onClearQueue,
            onClose = { showQueue = false },
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            onShuffle = onShuffle,
            onRepeat = onRepeat,
        )
    }
    PlayerMoreSheets(state = state, sheets = more)
}

@Composable
fun ModernCommentBottomSheet(trackId: String, onClose: () -> Unit) {
    val model = remember(trackId) { CommentScreenModel(trackId, "music") }
    LegacyModalBottomSheet(onDismissRequest = onClose, skipPartiallyExpanded = true) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.84f)
                .padding(horizontal = CpSpacing.pageHorizontal),
        ) {
            CommentPane(
                model = model,
                modifier = Modifier.fillMaxSize(),
                sortChipContainerColor = cpNestedSurface(),
            )
        }
    }
}
