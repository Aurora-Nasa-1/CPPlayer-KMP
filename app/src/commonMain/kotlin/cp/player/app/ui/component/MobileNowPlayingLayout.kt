package cp.player.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AccessAlarm
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.model.MobilePlayerCustomConfig
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.SeekAvailability
import cp.player.app.ui.util.formatTimeMs
import cp.player.core.music.ArtistSummary
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackUiState
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.delay

/**
 * One mobile player with two focus states. Only the content above the transport
 * changes, so opening lyrics never moves playback or queue controls.
 */
@Composable
internal fun MobileNowPlayingLayout(
    state: PlaybackUiState,
    config: MobilePlayerCustomConfig,
    lyricsExpanded: Boolean,
    supportsComments: Boolean,
    onBack: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleTranslation: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onLike: () -> Unit,
    onArtistClick: ((ArtistSummary) -> Unit)?,
    onQueue: () -> Unit,
    onComments: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    dismissGesture: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    artistModifier: Modifier = Modifier,
    isImmersive: Boolean = false,
    onToggleImmersive: () -> Unit = {},
    onImmersiveChange: (Boolean) -> Unit = {},
    lyricsContent: @Composable (Modifier) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val s = cpStrings()
    var lastInteractionTime by remember { mutableLongStateOf(currentTimeMillis()) }

    LaunchedEffect(
        lyricsExpanded,
        isImmersive,
        lastInteractionTime,
        config.lyricsImmersiveEnabled,
        config.lyricsImmersiveTimeoutSeconds,
    ) {
        if (!lyricsExpanded || !config.lyricsImmersiveEnabled || isImmersive) {
            return@LaunchedEffect
        }
        val timeoutMs = config.lyricsImmersiveTimeoutSeconds.coerceIn(2, 15) * 1000L
        while (true) {
            val elapsed = currentTimeMillis() - lastInteractionTime
            val remaining = timeoutMs - elapsed
            if (remaining <= 0L) {
                onImmersiveChange(true)
                break
            }
            delay(remaining.coerceAtLeast(100L))
        }
    }

    val immersiveInteractionModifier = if (lyricsExpanded && config.lyricsImmersiveEnabled) {
        Modifier.pointerInput(isImmersive) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    lastInteractionTime = currentTimeMillis()
                    if (isImmersive) {
                        onImmersiveChange(false)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    } else Modifier

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        val compact = maxHeight < 700.dp || LocalDensity.current.fontScale > 1.25f
        Column(
            contentModifier.widthIn(max = CpSpacing.playerMaxWidth).fillMaxSize()
                .padding(horizontal = CpSpacing.playerHorizontal)
                .then(immersiveInteractionModifier),
        ) {
            AnimatedVisibility(
                visible = !isImmersive,
                enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
                exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).then(dismissGesture),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerIconButton(
                        Icons.Rounded.KeyboardArrowDown, s.player.collapse, onBack,
                        modifier = Modifier.testTag("player-collapse"),
                    )
                    CpText(
                        state.currentTrack?.album?.takeIf(String::isNotBlank) ?: s.player.nowPlaying,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("player-album"),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        reveal = CpTextReveal.Marquee,
                        textAlign = TextAlign.Center,
                    )
                    PlayerIconButton(Icons.Rounded.MoreHoriz, s.player.more, onMore)
                }
            }
            val track = state.currentTrack
            if (track == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (!state.error.isNullOrBlank()) {
                        CpText(
                            state.error.orEmpty(), color = colors.error,
                            textAlign = TextAlign.Center, maxLines = 4,
                        )
                    } else {
                        CpLoadingIndicator(Modifier.size(32.dp))
                    }
                }
                return@Column
            }
            AnimatedContent(
                targetState = lyricsExpanded,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "mobile-player-focus",
            ) { expanded ->
                if (expanded) {
                    Column(Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = !isImmersive,
                            enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
                            exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlayerArtwork(
                                    track, state.isPlaying, config, onSkipNext, onSkipPrevious,
                                    Modifier.size(52.dp).clickable(
                                        role = Role.Button, onClickLabel = "收起歌词",
                                        onClick = onToggleLyrics,
                                    ),
                                )
                                Spacer(Modifier.width(12.dp))
                                TrackHeading(track, false, onArtistClick, Modifier.weight(1f))
                                PlayerIconButton(
                                    if (isImmersive) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                    if (isImmersive) "退出沉浸" else "沉浸歌词",
                                    onToggleImmersive,
                                    selected = isImmersive,
                                )
                                PlayerIconButton(
                                    Icons.Rounded.Translate, s.player.translation, onToggleTranslation,
                                    selected = config.showTranslation,
                                )
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().lyricEdgeFade()) {
                            lyricsContent(Modifier.fillMaxSize().testTag("player-full-lyrics"))
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        BoxWithConstraints(
                            Modifier.weight(1f).fillMaxWidth().padding(vertical = if (compact) 4.dp else 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Only remaining height goes to artwork; transport is never squeezed.
                            val size = minOf(maxWidth, maxHeight).coerceAtLeast(0.dp)
                            PlayerArtwork(
                                track, state.isPlaying, config, onSkipNext, onSkipPrevious,
                                Modifier.size(size).then(artworkModifier).then(dismissGesture)
                                    .testTag("player-artwork"),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TrackHeading(
                                track = track,
                                large = !compact,
                                onArtistClick = onArtistClick,
                                modifier = Modifier.weight(1f),
                                titleModifier = titleModifier,
                                artistModifier = artistModifier,
                            )
                            Spacer(Modifier.width(8.dp))
                            PlayerIconButton(
                                if (state.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                if (state.isFavorite) s.player.unlike else s.player.like,
                                onLike, selected = state.isFavorite,
                                modifier = Modifier.testTag("player-favorite"),
                            )
                        }
                        InlineLyricsPreview(
                            lyricsState = state.lyrics, positionMs = state.positionMs,
                            isPlaying = state.isPlaying, config = config, trackId = track.id,
                            compact = compact, onClick = onToggleLyrics,
                            modifier = Modifier.padding(vertical = if (compact) 4.dp else 8.dp)
                                .testTag("player-lyric-preview"),
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = !isImmersive,
                enter = fadeIn(CpMotion.effects()) + expandVertically(CpMotion.spatial()),
                exit = fadeOut(CpMotion.effects()) + shrinkVertically(CpMotion.spatial()),
            ) {
                Column(Modifier.fillMaxWidth().testTag("player-transport")) {
                    PlayerTimeline(state, onSeek)
                    Row(
                        Modifier.fillMaxWidth().height(if (compact) 72.dp else 84.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerIconButton(
                            Icons.Rounded.SkipPrevious, s.player.previousTrack, onSkipPrevious, iconSize = 34,
                        )
                        Box(contentAlignment = Alignment.Center) {
                            PlayerIconButton(
                                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                s.player.playOrPause(state.isPlaying),
                                onTogglePlay, iconSize = 48,
                                modifier = Modifier.size(72.dp).testTag("player-play-pause"),
                            )
                            if (state.isBuffering) {
                                CpLoadingIndicator(Modifier.align(Alignment.BottomCenter).size(12.dp))
                            }
                        }
                        PlayerIconButton(
                            Icons.Rounded.SkipNext, s.player.nextTrack, onSkipNext, iconSize = 34,
                        )
                    }
                    state.error?.takeIf(String::isNotBlank)?.let { error ->
                        CpText(
                            error, modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error, textAlign = TextAlign.Center,
                            maxLines = 1, reveal = CpTextReveal.Marquee,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().height(if (compact) 56.dp else 64.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerIconButton(
                            Icons.Rounded.Lyrics,
                            if (lyricsExpanded) "收起歌词" else "展开完整歌词",
                            onToggleLyrics, selected = lyricsExpanded,
                            modifier = Modifier.testTag("player-lyrics-toggle"),
                        )
                        if (supportsComments) {
                            PlayerIconButton(
                                Icons.AutoMirrored.Rounded.Comment, "歌曲评论", onComments,
                                modifier = Modifier.testTag("player-comments"),
                            )
                        } else {
                            Spacer(Modifier.size(CpSpacing.touchTarget))
                        }
                        PlayerIconButton(
                            Icons.AutoMirrored.Rounded.QueueMusic, s.player.queue, onQueue,
                            modifier = Modifier.testTag("player-queue"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackHeading(
    track: TrackSummary,
    large: Boolean,
    onArtistClick: ((ArtistSummary) -> Unit)?,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    artistModifier: Modifier = Modifier,
) {
    Column(modifier) {
        CpText(
            track.name,
            modifier = Modifier.then(titleModifier).fillMaxWidth().testTag("player-track-title"),
            style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, reveal = CpTextReveal.Marquee,
        )
        Spacer(Modifier.height(4.dp))
        TrackArtistText(
            track = track, onArtistClick = onArtistClick,
            modifier = Modifier.then(artistModifier).fillMaxWidth().testTag("player-track-artist"),
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            reveal = CpTextReveal.Marquee,
        )
    }
}

@Composable
private fun PlayerArtwork(
    track: TrackSummary,
    isPlaying: Boolean,
    config: MobilePlayerCustomConfig,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalCpHaptics.current
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var drag by remember(track.id) { mutableFloatStateOf(0f) }
    val scale by animateFloatAsState(
        if (isPlaying) 1f else 0.94f,
        animationSpec = CpMotion.spatialSlow(), label = "mobile-artwork-scale",
    )
    val swipeModifier = if (config.coverSwipeGesture) {
        Modifier.pointerInput(track.id, threshold) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    drag += amount
                },
                onDragCancel = { drag = 0f },
                onDragEnd = {
                    if (drag <= -threshold) {
                        haptics.perform(CpHaptic.Confirm)
                        onNext()
                    } else if (drag >= threshold) {
                        haptics.perform(CpHaptic.Confirm)
                        onPrevious()
                    }
                    drag = 0f
                },
            )
        }
    } else Modifier
    Surface(
        modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = drag.coerceIn(-threshold * 2, threshold * 2) * 0.12f
        }.then(swipeModifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 4.dp,
    ) {
        Box {
            // The parent Surface owns the shape; do not introduce a second corner scale.
            CpCoverPlaceholder(Modifier.fillMaxSize(), corner = 0.dp, animated = false)
            if (!track.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.coverUrl, contentDescription = track.album ?: track.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PlayerTimeline(state: PlaybackUiState, onSeek: (Long) -> Unit) {
    val s = cpStrings()
    val duration = state.durationMs.coerceAtLeast(0L)
    Column(Modifier.fillMaxWidth()) {
        CpMinimalSeekBar(
            positionMs = state.positionMs, durationMs = duration, onSeek = onSeek,
            enabled = SeekAvailability.isSeekable(duration, state.isLocalizing),
            trackKey = state.currentTrack?.id,
            modifier = Modifier.fillMaxWidth().testTag("player-seek"),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CpText(
                formatTimeMs(state.positionMs), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val remaining = state.sleepTimerRemainingMs ?: 0L
            if (remaining > 0L || state.sleepAfterTrack) {
                Row(
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.AccessAlarm, null, Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    CpText(
                        if (remaining > 0L) s.player.sleepRemainingLabel(formatTimeMs(remaining))
                        else s.player.sleepEndsWithTrack,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        reveal = CpTextReveal.Marquee, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val formatQuality = state.formatInfo?.qualityLabel?.takeIf { it.isNotBlank() }
                val sourceQuality = state.sourceQualityLabel?.takeIf { it.isNotBlank() }
                val label = when {
                    sourceQuality != null && formatQuality != null -> "$sourceQuality · $formatQuality"
                    sourceQuality != null -> sourceQuality
                    else -> formatQuality
                }
                if (label != null) {
                    CpText(
                        label, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        textAlign = TextAlign.Center,
                        reveal = CpTextReveal.Marquee,
                    )
                }
            }
            CpText(
                if (duration > 0L) "-${formatTimeMs((duration - state.positionMs).coerceAtLeast(0L))}"
                else SeekAvailability.UNKNOWN_DURATION_LABEL,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SeekAvailability.disabledReason(duration, state.isLocalizing)?.let { reason ->
            CpText(
                reason, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, reveal = CpTextReveal.Marquee,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    iconSize: Int = 23,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = modifier.size(CpSpacing.touchTarget).semantics {
            if (selected != null) this.selected = selected
        },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected == true) colors.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            contentColor = if (selected == true) colors.primary else colors.onSurface,
        ),
    ) {
        Icon(icon, label, Modifier.size(iconSize.dp))
    }
}

private fun Modifier.lyricEdgeFade(): Modifier =
    clip(androidx.compose.ui.graphics.RectangleShape)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.08f to Color.Black,
                    0.9f to Color.Black, 1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
