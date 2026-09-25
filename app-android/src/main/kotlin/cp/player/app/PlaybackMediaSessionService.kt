package cp.player.app

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import cp.player.core.playback.SharedMedia3Player

/**
 * Android system media-session host. The shared controller remains the source of
 * truth for in-app playback; this service keeps the process eligible for playback
 * controls while the activity is backgrounded.
 */
class PlaybackMediaSessionService : MediaSessionService() {
    private var player: ControllerForwardingPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        (application as? CPPlayerApplication)?.backend
        super.onCreate()
        // 会话的 Player 不是裸 ExoPlayer：切歌/seek 必须转交应用控制器。
        // 裸 ExoPlayer 只持有单个 media item，队列在控制器里，
        // 直接用它会导致通知栏/锁屏/耳机切歌无效。
        val sessionPlayer = ControllerForwardingPlayer(SharedMedia3Player.get(this)) {
            AppModel.playback
        }
        player = sessionPlayer
        mediaSession = MediaSession.Builder(this, sessionPlayer).build()
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
