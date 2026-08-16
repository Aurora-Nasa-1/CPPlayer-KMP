package cp.player.app.platform

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.playback.PlaybackUiState

private var mediaSession: MediaSessionCompat? = null

private fun ensureMediaSession(context: android.content.Context, controller: PlaybackController) {
    if (mediaSession != null) return
    mediaSession = MediaSessionCompat(context, "CPPlayer").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = controller.resume()
            override fun onPause() = controller.pause()
            override fun onSkipToNext() = controller.skipNext()
            override fun onSkipToPrevious() = controller.skipPrevious()
            override fun onSeekTo(pos: Long) = controller.seekTo(pos)
            override fun onStop() = controller.pause()
        })
        isActive = true
    }
}

@Composable
actual fun PlatformMediaControlsEffect(controller: PlaybackController, state: PlaybackUiState) {
    val context = ctxOrNull ?: return
    LaunchedEffect(controller, state) {
        ensureMediaSession(context, controller)
        val track = state.currentTrack
        val playback = when {
            state.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(playback, state.positionMs, 1f)
                .build()
        )
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.name ?: "CPPlayer")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                .build()
        )
    }
}
