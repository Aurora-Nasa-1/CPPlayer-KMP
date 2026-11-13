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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.AppModel
import cp.player.app.platform.BackHandler
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.model.CommentScreenModel
import cp.player.app.ui.screen.LyricsPluginSettingsScreen
import cp.player.app.ui.screen.UserProfileScreen
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.pushOrNotify
import cp.player.core.music.ArtistSummary
import cp.player.core.playback.PlaybackUiState
import cp.player.core.provider.ProviderCapability
import kotlinx.coroutines.launch

internal fun modernPlayerFallbackColors(colorScheme: ColorScheme): List<Color> =
    listOf(colorScheme.surfaceContainerHigh, colorScheme.surfaceContainerLow, colorScheme.surface)

/** Mobile player host. Playback, sheets and navigation stay outside the layout. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ModernPlayerScreenContent(
    state: PlaybackUiState,
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
    val navigator = LocalNavigator.current
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
    val isImmersive = lyricsExpanded && config.lyricsImmersiveEnabled && isImmersiveActive && !anySheetOpen

    BackHandler(enabled = isImmersive) {
        isImmersiveActive = false
    }

    BackHandler(enabled = !isImmersive && lyricsExpanded && !showQueue && !showComments) {
        lyricsExpanded = false
    }

    val currentBack by rememberUpdatedState(onBack)
    val dragOffset = remember { Animatable(0f) }
    val dragThreshold = with(LocalDensity.current) { 100.dp.toPx() }
    val resetMotion = CpMotion.spatial<Float>()
    val dismissGesture = Modifier.pointerInput(dragThreshold) {
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
    val onArtist: ((ArtistSummary) -> Unit)? = remember(navigator) {
        navigator?.let { nav ->
            { artist -> nav.pushOrNotify(UserProfileScreen(artist.id, artist.name, artist.resourceId)) }
        }
    }
    val onChangeSource: (() -> Unit)? = remember(navigator) {
        navigator?.let { nav -> { nav.pushOrNotify(LyricsPluginSettingsScreen()) } }
    }

    val currentTrack = state.currentTrack
    val titleModifier = if (currentTrack != null) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState("title-${currentTrack.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
        )
    } else Modifier

    val artistModifier = if (currentTrack != null) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState("artist-${currentTrack.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
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
            .cpFluidBackground(
                enabled = fluidBackground,
                fallback = backgroundBrush,
                pauseShaderAnimation = shouldPauseFluidShaderAfterNavigation(
                    animatedVisibilityScope.transition.isRunning,
                ),
            )
            .sharedBounds(rememberSharedContentState("player-container"), animatedVisibilityScope)
            .graphicsLayer { translationY = dragOffset.value },
    ) {
        MobileNowPlayingLayout(
            state = state,
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
            onRepeat = onRepeat,
            onShuffle = onShuffle,
            onLike = { AppModel.toggleFavoriteOf(state.currentTrack?.id) },
            onArtistClick = onArtist,
            onQueue = { showQueue = true },
            onComments = { showComments = true },
            onMore = { more.showMoreMenu = true },
            modifier = Modifier.fillMaxSize(),
            contentModifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            dismissGesture = dismissGesture,
            artworkModifier = Modifier.sharedBounds(
                rememberSharedContentState("cover-${state.currentTrack?.id}"), animatedVisibilityScope,
            ),
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
        ) { modifier ->
            LyricContent(
                state = state,
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
            onOpenSimilar = {
                showQueue = false
                more.showSimilarSongs = true
            },
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
            CommentPane(model = model, modifier = Modifier.fillMaxSize())
        }
    }
}
