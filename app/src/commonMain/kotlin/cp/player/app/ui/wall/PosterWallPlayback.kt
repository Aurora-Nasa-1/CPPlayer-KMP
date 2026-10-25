package cp.player.app.ui.wall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.util.formatTimeMs
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState
import cp.player.app.ui.component.CpText

@Composable
internal fun PosterPlayerLyrics(
    playback: PlaybackUiState,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = cpStrings()
    val lyricsState = playback.lyrics
    val currentPosition = playback.positionMs

    val activeLine = remember(lyricsState, currentPosition) {
        if (lyricsState is LyricsState.Success && lyricsState.lines.isNotEmpty()) {
            val lines = lyricsState.lines
            val idx = lines.indexOfLast { it.time <= currentPosition }
            if (idx >= 0) lines[idx] else lines.first()
        } else null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPlayer),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            if (activeLine != null) {
                CpText(
                    text = activeLine.text,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val trans = activeLine.translation
                if (!trans.isNullOrBlank()) {
                    CpText(
                        text = trans,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                CpText(
                    text = when (lyricsState) {
                        is LyricsState.Loading -> s.player.lyricsLoading
                        is LyricsState.NoLyrics -> s.player.lyricsEmpty
                        else -> s.player.lyricsIdle
                    },
                    color = PosterMuted,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun PosterPlayerProgress(
    playback: PlaybackUiState,
    compact: Boolean,
    onSeek: (Long) -> Unit
) {
    val duration = playback.durationMs.coerceAtLeast(1L)
    val position = playback.positionMs.coerceIn(0L, duration)
    var draggingPosition by remember { mutableFloatStateOf(-1f) }

    val currentFraction = if (draggingPosition >= 0f) draggingPosition else (position.toFloat() / duration)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = currentFraction.coerceIn(0f, 1f),
            onValueChange = { draggingPosition = it },
            onValueChangeFinished = {
                if (draggingPosition >= 0f) {
                    onSeek((draggingPosition * duration).toLong())
                    draggingPosition = -1f
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (!compact) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val displayMs = if (draggingPosition >= 0f) (draggingPosition * duration).toLong() else position
                CpText(
                    formatTimeMs(displayMs),
                    color = PosterMuted,
                    fontSize = 11.sp
                )
                CpText(
                    formatTimeMs(duration),
                    color = PosterMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}
