package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.LyricContent
import cp.player.app.ui.component.PlayerMoreSheets
import cp.player.app.ui.component.PlayerTimeline
import cp.player.app.ui.component.QueueContent
import cp.player.app.ui.component.TrackArtistText
import cp.player.app.ui.component.cpFluidBackground
import cp.player.app.ui.component.rememberActiveProviderSupports
import cp.player.app.ui.component.rememberPlayerMoreSheetState
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.core.playback.PlaybackUiState
import cp.player.core.provider.ProviderCapability
import kotlinx.coroutines.flow.Flow

private enum class LandscapePanel { Lyrics, Queue, Comments }

@Composable
internal fun LandscapePlayerScreen(
    state: PlaybackUiState,
    positionFlow: Flow<Long>,
    initialPositionMs: Long,
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
    onRemoveQueue: (Int) -> Unit,
    onClearQueue: () -> Unit,
    transitionRunning: Boolean = false,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    artistModifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    val s = cpStrings()
    val colors = MaterialTheme.colorScheme
    val navigator = LocalAppNavigator.current
    val sheets = rememberPlayerMoreSheetState()
    val fluid by AppModel.fluidBackgroundFlow.collectAsState()
    val config by AppModel.mobilePlayerCustomConfigFlow.collectAsState()
    val supportsComments = rememberActiveProviderSupports(ProviderCapability.COMMENTS)
    var selection by rememberSaveable { mutableStateOf<LandscapePanel?>(null) }
    val panel = selection.takeUnless { it == LandscapePanel.Comments && !supportsComments }

    val heading: @Composable (Boolean) -> Unit = { large ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CpText(
                    track.name,
                    modifier = titleModifier,
                    style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    emphasized = true,
                    maxLines = 1,
                    marqueeEnabled = state.isPlaying && !transitionRunning,
                )
                TrackArtistText(
                    track = track,
                    modifier = artistModifier,
                    onArtistClick = { navigator.push(AppRoute.UserProfile(it.id, it.name, it.resourceId)) },
                    style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onLike) {
                Icon(
                    if (state.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (state.isFavorite) s.player.unfavoriteTrack else s.player.favoriteTrack,
                    modifier = Modifier.size(CpSpacing.playerFavoriteIcon),
                    tint = if (state.isFavorite) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
    val cover: @Composable (Dp) -> Unit = { size ->
        val shape = MaterialTheme.shapes.large
        Artwork(track.coverUrl, Modifier.size(size).then(artworkModifier).shadow(10.dp, shape).clip(shape), size)
    }
    val transport: @Composable (Float) -> Unit = { scale ->
        PlayerTimeline(state, positionFlow, initialPositionMs, onSeek)
        PlayerTransportRow(
            state = state,
            onTogglePlay = onTogglePlay,
            onSkipNext = onSkipNext,
            onSkipPrev = onSkipPrev,
            onRepeat = onRepeat,
            onShuffle = onShuffle,
            scale = scale,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Box(
        Modifier.fillMaxSize().cpFluidBackground(
            enabled = fluid,
            fallback = Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface)),
            pauseShaderAnimation = transitionRunning,
            coverUrl = track.coverUrl,
            playing = state.isPlaying,
        ).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            val tight = maxHeight < 340.dp
            val bodyHeight = (maxHeight - 62.dp).coerceAtLeast(1.dp)
            val gutter = if (maxWidth < 600.dp) 16.dp else 28.dp
            val closedCover = minOf(bodyHeight - 8.dp, maxWidth * 0.42f, 288.dp).coerceAtLeast(1.dp)
            val closedControls = minOf(312.dp, (maxWidth - closedCover - gutter).coerceAtLeast(1.dp))
            val groupWidth = if (panel == null) closedCover + gutter + closedControls else minOf(maxWidth, 740.dp)

            Column(
                Modifier.widthIn(max = groupWidth).fillMaxWidth().height(bodyHeight + 48.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.KeyboardArrowDown, s.player.collapsePlayer, tint = colors.onSurface)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = colors.surfaceContainerHigh.copy(alpha = 0.62f)) {
                            Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                LandscapePanelButton(Icons.Rounded.Lyrics, s.player.showLyrics, panel == LandscapePanel.Lyrics) {
                                    selection = LandscapePanel.Lyrics.takeUnless { it == panel }
                                }
                                LandscapePanelButton(Icons.AutoMirrored.Rounded.QueueMusic, s.player.showQueue, panel == LandscapePanel.Queue) {
                                    selection = LandscapePanel.Queue.takeUnless { it == panel }
                                }
                                if (supportsComments) {
                                    LandscapePanelButton(Icons.AutoMirrored.Rounded.Comment, s.player.showComments, panel == LandscapePanel.Comments) {
                                        selection = LandscapePanel.Comments.takeUnless { it == panel }
                                    }
                                }
                            }
                        }
                        if (panel == LandscapePanel.Lyrics) {
                            LandscapePanelButton(
                                Icons.Rounded.Translate,
                                if (config.showTranslation) s.player.closeLyricsTranslation else s.player.lyricsTranslation,
                                config.showTranslation,
                            ) { AppModel.updateMobilePlayerCustomConfig { it.copy(showTranslation = !it.showTranslation) } }
                        }
                        IconButton(onClick = { sheets.showMoreMenu = true }) {
                            Icon(Icons.Rounded.MoreHoriz, s.player.more, tint = colors.onSurface)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (panel == null) {
                        cover(closedCover)
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            heading(true)
                            transport((closedControls / 244.dp).coerceIn(0.76f, 1f))
                        }
                    } else {
                        val controlWidth = (groupWidth * 0.40f).coerceIn(166.dp, 292.dp)
                        Column(
                            Modifier.widthIn(max = controlWidth).weight(0.40f, fill = false)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val coverSize = minOf(controlWidth * 0.62f, (bodyHeight - 226.dp).coerceAtLeast(64.dp))
                            if (tight) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    cover(44.dp)
                                    Box(Modifier.weight(1f)) { heading(false) }
                                }
                            } else {
                                Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.CenterStart) {
                                    cover(coverSize)
                                }
                                heading(false)
                            }
                            transport((controlWidth / 244.dp).coerceIn(0.70f, 0.94f))
                        }
                        AnimatedContent(
                            targetState = panel,
                            modifier = Modifier.weight(0.60f).fillMaxHeight().clip(MaterialTheme.shapes.large),
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "landscapePlayerPanel",
                        ) { active ->
                            when (active) {
                                LandscapePanel.Lyrics -> LyricContent(
                                    state = state,
                                    positionFlow = positionFlow,
                                    initialPositionMs = initialPositionMs,
                                    showTranslation = config.showTranslation,
                                    onSeek = onSeek,
                                    lineFontSize = if (tight) 20.sp else 24.sp,
                                    onChangeSource = { navigator.push(AppRoute.LyricsPluginSettings) },
                                )
                                LandscapePanel.Queue -> QueueContent(
                                    queue = state.queue,
                                    currentIndex = state.currentIndex,
                                    onPlayAt = onPlayAt,
                                    onRemove = onRemoveQueue,
                                    onMove = onMove,
                                    onClear = onClearQueue,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                LandscapePanel.Comments -> DesktopCommentContent(track.id)
                            }
                        }
                    }
                }
            }
        }
    }
    PlayerMoreSheets(state, sheets)
}

@Composable
private fun LandscapePanelButton(
    image: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).semantics { selected = active },
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = if (active) colors.onSurface else Color.Transparent,
            contentColor = if (active) colors.surface else colors.onSurface,
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(image, label, Modifier.size(20.dp)) }
        }
    }
}
