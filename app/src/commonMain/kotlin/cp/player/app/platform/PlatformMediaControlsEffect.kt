package cp.player.app.platform

import androidx.compose.runtime.Composable
import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.playback.PlaybackUiState

@Composable
expect fun PlatformMediaControlsEffect(
    controller: PlaybackController,
    state: PlaybackUiState,
)
