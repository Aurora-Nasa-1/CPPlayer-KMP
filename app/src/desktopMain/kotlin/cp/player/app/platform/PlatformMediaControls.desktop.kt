package cp.player.app.platform

import androidx.compose.runtime.Composable
import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.playback.PlaybackUiState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect

private var jmtc: JmtcMediaControls? = null

@Composable
actual fun PlatformMediaControlsEffect(controller: PlaybackController, state: PlaybackUiState) {
    LaunchedEffect(controller) {
        jmtc = JmtcMediaControls.create(controller).also { it.start() }
    }
    LaunchedEffect(state) {
        jmtc?.update(state)
    }
    DisposableEffect(Unit) {
        onDispose {
            jmtc?.stop()
            jmtc = null
        }
    }
}
