package cp.player.app

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import cp.player.kmp.playback.SharedMedia3Player

/**
 * Android system media-session host. The shared controller remains the source of
 * truth for in-app playback; this service keeps the process eligible for playback
 * controls while the activity is backgrounded.
 */
class PlaybackMediaSessionService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        (application as? CPPlayerApplication)?.backend
        super.onCreate()
        val exoPlayer = SharedMedia3Player.get(this)
        player = exoPlayer
        // Media3 handles transport commands through the ExoPlayer instance;
        // the app-level MediaSessionCompat bridge mirrors the shared controller.
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        // SharedMedia3Player is released by the playback controller lifecycle.
        player = null
        super.onDestroy()
    }
}
