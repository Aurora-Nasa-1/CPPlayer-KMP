package cp.player.app.platform

import androidx.compose.runtime.Composable
import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.playback.PlaybackUiState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect

private var mpris: MprisMediaControls? = null

@Composable
actual fun PlatformMediaControlsEffect(controller: PlaybackController, state: PlaybackUiState) {
    LaunchedEffect(controller) {
        mpris = MprisMediaControls.create(controller).also { it.start() }
    }
    LaunchedEffect(state) { mpris?.update(state) }
    DisposableEffect(Unit) {
        onDispose { mpris?.stop(); mpris = null }
    }
}
