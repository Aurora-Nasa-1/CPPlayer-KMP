package cp.player.app.ui.wall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState
import kotlinx.coroutines.delay

@Composable
internal fun PosterLyricTheaterScreen(
    playback: PlaybackUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val s = cpStrings()
    var selectedStyleIndex by rememberSaveable { mutableIntStateOf(0) }
    val style = PosterLyricStyle.fromSetting(selectedStyleIndex)

    val lyricsState = playback.lyrics
    val lyrics = (lyricsState as? LyricsState.Success)?.lines.orEmpty()
    val playing = playback.isPlaying
    val position = remember(playback.positionMs) { mutableStateOf(playback.positionMs) }
    val time = rememberPosterSceneTime(playing)

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var stylePicker by remember { mutableStateOf(false) }
    var interacting by remember { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, stylePicker, playing, interacting) {
        if (controlsVisible && !stylePicker && playing && !interacting) {
            delay(5000)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    interacting = true
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        interacting = false
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { controlsVisible = !controlsVisible }
    ) {
        val currentIndex = remember(lyrics, position.value) {
            if (lyrics.isEmpty()) -1
            else lyrics.indexOfLast { it.time <= position.value }.takeIf { it >= 0 } ?: 0
        }

        PosterLyricScene(
            style = style,
            lyrics = lyrics,
            currentIndex = currentIndex,
            position = position,
            time = time,
            fallbackTitle = playback.currentTrack?.name ?: s.player.lyricsIdle,
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(androidx.compose.animation.core.tween(240)),
            exit = fadeOut(androidx.compose.animation.core.tween(240)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top control bar
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PosterIconButton(s.common.back, onBack) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = s.common.back,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        playback.currentTrack?.name.orEmpty(),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .clickable { stylePicker = true }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (style) {
                                PosterLyricStyle.Orbit -> s.wall.styleOrbit
                                PosterLyricStyle.Clock -> s.wall.styleClock
                                PosterLyricStyle.ColorField -> s.wall.styleColorField
                                PosterLyricStyle.Glow -> s.wall.styleGlow
                            },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Bottom control bar
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    PosterPlayControls(
                        playing = playing,
                        onPrevious = onSkipPrev,
                        onToggle = onPlayPause,
                        onNext = onSkipNext
                    )
                    Box(Modifier.weight(1f)) {
                        PosterPlayerProgress(
                            playback = playback,
                            compact = true,
                            onSeek = onSeek
                        )
                    }
                }
            }
        }

        if (stylePicker) {
            val styleOptions = listOf(
                0 to s.wall.styleOrbit,
                1 to s.wall.styleClock,
                2 to s.wall.styleColorField,
                3 to s.wall.styleGlow
            )
            AlertDialog(
                onDismissRequest = { stylePicker = false },
                title = { Text(s.wall.posterWallLyricsStyle) },
                text = {
                    Column {
                        styleOptions.forEach { (index, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedStyleIndex = index
                                        stylePicker = false
                                        controlsVisible = true
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                RadioButton(
                                    selected = selectedStyleIndex == index,
                                    onClick = {
                                        selectedStyleIndex = index
                                        stylePicker = false
                                        controlsVisible = true
                                    }
                                )
                                Text(
                                    text = label,
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { stylePicker = false }) {
                        Text(s.common.close)
                    }
                }
            )
        }
    }
}
