package cp.player.app.platform

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.playback.PlaybackUiState

private var mediaSession: MediaSessionCompat? = null
private var sessionController: PlaybackController? = null

private fun ensureMediaSession(context: Context, controller: PlaybackController): MediaSessionCompat {
    sessionController = controller
    return mediaSession ?: MediaSessionCompat(context.applicationContext, "CPPlayer").also { session ->
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        session.setCallback(object : MediaSessionCompat.Callback() {
            private fun current(): PlaybackController? = sessionController

            override fun onPlay() { current()?.resume() }
            override fun onPause() { current()?.pause() }
            override fun onSkipToNext() { current()?.skipNext() }
            override fun onSkipToPrevious() { current()?.skipPrevious() }
            override fun onSeekTo(pos: Long) { current()?.seekTo(pos.coerceAtLeast(0L)) }
            override fun onStop() { current()?.pause() }
            override fun onMediaButtonEvent(mediaButtonEvent: android.content.Intent): Boolean {
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })
        session.isActive = true
        mediaSession = session
    }
}

private fun releaseMediaSession() {
    sessionController = null
    mediaSession?.run {
        isActive = false
        setCallback(null)
        release()
    }
    mediaSession = null
}

@Composable
actual fun PlatformMediaControlsEffect(controller: PlaybackController, state: PlaybackUiState) {
    val context = ctxOrNull ?: return

    DisposableEffect(controller, context) {
        ensureMediaSession(context, controller)
        onDispose {
            if (sessionController === controller) releaseMediaSession()
        }
    }

    LaunchedEffect(controller, state) {
        val session = ensureMediaSession(context, controller)
        val track = state.currentTrack
        val duration = state.durationMs.coerceAtLeast(track?.durationMs ?: 0L)
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO
        val playback = when {
            state.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            track != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setBufferedPosition(state.positionMs.coerceIn(0L, duration))
                .setState(playback, state.positionMs.coerceAtLeast(0L), 1f)
                .build(),
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track?.id ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.name ?: "CPPlayer")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album.orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build(),
        )
    }
}
