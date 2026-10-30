package cp.player.app.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.component.CpActionButtonGroup
import cp.player.app.ui.component.CpActionGroupItem
import cp.player.app.ui.component.CpContextMenu
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.CpExpressiveArtwork
import cp.player.app.ui.component.CpPlayPauseButton
import cp.player.app.ui.component.CpPlayingEqualizer
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpSplitButton
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpWavyProgress
import cp.player.app.ui.component.rememberActiveProviderSupports
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.resized
import cp.player.core.music.TrackSummary
import cp.player.core.provider.ProviderCapability

/** Real artwork with a static, readable failure state, including failed requests. */
@Composable
internal fun LandingArtwork(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.MusicNote, null,
            Modifier.fillMaxSize(0.3f), tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(url.resized(500), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun DailySelectionFocus(
    songs: List<TrackSummary>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(CpSpacing.pageHorizontal)) {
            val small = maxWidth < 440.dp
            val extraCompact = maxWidth < 280.dp
            val artworkSize = if (compact || small) CpSpacing.musicArtworkCompact else CpSpacing.musicArtwork
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CpSpacing.musicGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CpText(
                            "为你精选",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        CpText(
                            "每日推荐",
                            style = when {
                                extraCompact -> MaterialTheme.typography.headlineSmall
                                small -> MaterialTheme.typography.headlineMedium
                                else -> MaterialTheme.typography.displaySmall
                            },
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 2,
                        )
                        CpText(
                            if (extraCompact) "${songs.size} 首好歌" else "${songs.size} 首 · 每天更新",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    CpExpressiveArtwork(Modifier.size(artworkSize)) {
                        LandingArtwork(songs.firstOrNull()?.coverUrl, Modifier.fillMaxSize())
                    }
                }
                CpSplitButton(
                    onLeadingClick = { songs.firstOrNull()?.let(actions.onPlayDailyTrack) },
                    leadingContent = {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        CpText("播放推荐", fontWeight = FontWeight.SemiBold)
                    },
                    onTrailingClick = actions.onOpenDaily,
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, "查看每日推荐") },
                    modifier = Modifier.width(if (compact) 224.dp else 256.dp).height(CpSpacing.touchTarget),
                    colors = ButtonDefaults.buttonColors(),
                )
            }
        }
    }
}

@Composable
internal fun LandingNowPlaying(
    snapshot: NowPlayingSnapshot,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val track = snapshot.track ?: return
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxSize().padding(CpSpacing.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Equalizer, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                CpText(
                    if (snapshot.isPlaying) "正在播放" else "继续收听",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.onOpenPlayer, modifier = Modifier.size(CpSpacing.touchTarget)) {
                    Icon(Icons.Filled.ChevronRight, "打开播放器")
                }
            }
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(CpSpacing.musicGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CpExpressiveArtwork(
                    Modifier.size(88.dp).clickable(onClick = actions.onOpenPlayer),
                    active = snapshot.isPlaying,
                ) { LandingArtwork(track.coverUrl, Modifier.fillMaxSize()) }
                Column(Modifier.weight(1f).clickable(onClick = actions.onOpenPlayer)) {
                    CpText(track.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    CpText(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CpWavyProgress(
                        if (snapshot.durationMs > 0) snapshot.positionMs.toFloat() / snapshot.durationMs else 0f,
                        waveFlowing = snapshot.isPlaying,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CpText(
                        "${formatTimeMs(snapshot.positionMs)} / ${if (snapshot.durationMs > 0) formatTimeMs(snapshot.durationMs) else "--:--"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CpPlayPauseButton(
                    snapshot.isPlaying, actions.onTogglePlayPause,
                    size = 56.dp,
                )
            }
        }
    }
}

@Composable
internal fun LandingRadioActions(actions: HomeActions, compact: Boolean = false, dailyAvailable: Boolean = true) {
    val local = AppModel.activeProvider() == null
    val recommend = rememberActiveProviderSupports(ProviderCapability.RECOMMEND)
    val items = buildList {
        if (local || recommend) add(CpActionGroupItem(
            if (local) "本地随机" else "私人 FM",
            if (local) Icons.Filled.Shuffle else Icons.Filled.Radio,
            actions.onPlayPersonalFm,
        ))
        add(CpActionGroupItem(
            if (local) "本地曲库" else "心动模式",
            if (local) Icons.Filled.FolderOpen else Icons.Filled.Favorite,
            actions.onOpenIntelligence,
            enabled = local || dailyAvailable,
        ))
        add(CpActionGroupItem("最近播放", Icons.Filled.History, actions.onOpenRecentPlays))
    }
    if (!compact) {
        CpActionButtonGroup(items, Modifier.fillMaxWidth())
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { index, item ->
                Surface(
                    onClick = item.onClick,
                    enabled = item.enabled,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    color = when (index) {
                        0 -> MaterialTheme.colorScheme.secondaryContainer
                        1 -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    Column(
                        Modifier.height(CpSpacing.musicShortcutHeight).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(item.icon, null, Modifier.size(24.dp))
                        CpText(item.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LandingTrackRow(
    track: TrackSummary,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    snapshot: NowPlayingSnapshot,
    modifier: Modifier = Modifier,
    artworkSize: Dp = 48.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val current = snapshot.track?.id == track.id
    val container by animateColorAsState(
        when {
            current -> MaterialTheme.colorScheme.secondaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = CpMotion.effectsFast(), label = "trackHover",
    )
    CpContextMenu(
        items = listOf(
            CpContextMenuItem("播放", Icons.Filled.PlayArrow, onClick = onPlay),
            CpContextMenuItem("更多", Icons.Filled.MoreHoriz, onClick = onOptions),
        ),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().hoverable(interaction),
            onClick = onPlay,
            interactionSource = interaction,
            color = container,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LandingArtwork(track.coverUrl, Modifier.size(artworkSize).clip(MaterialTheme.shapes.small))
                Column(Modifier.weight(1f)) {
                    CpText(track.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    CpText(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (current) CpPlayingEqualizer(
                    isPlaying = snapshot.isPlaying, modifier = Modifier.size(18.dp),
                    barColor = MaterialTheme.colorScheme.primary,
                    scrimColor = androidx.compose.ui.graphics.Color.Transparent,
                )
                IconButton(onClick = onOptions, modifier = Modifier.size(CpSpacing.touchTarget)) {
                    Icon(Icons.Filled.MoreHoriz, "更多", Modifier.size(20.dp))
                }
            }
        }
    }
}
