package cp.player.app.platform

import androidx.compose.runtime.Composable
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState

@Composable
expect fun PlatformMediaControlsEffect(
    controller: PlaybackController,
    state: PlaybackUiState,
)
