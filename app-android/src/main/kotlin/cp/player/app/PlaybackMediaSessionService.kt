package cp.player.app

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
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
        // 通知栏那个小图标：media3 默认用它自带的占位图（media3_notification_small_icon），
        // 在状态栏里和本应用没有任何关系。换成自己的单色播放三角。
        // ⚠️ 状态栏图标必须是**白色剪影 + 透明底**，系统会统一着色 —— 带颜色的图会被糊成色块。
        // 资源由 scripts/gen_app_icon.py 生成（drawable-*/ic_stat_playback.png）。
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_stat_playback)
            }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从「最近任务」划掉 app 卡片时不自毁。
     *
     * media3 1.4.1 的默认实现把「暂停中」也视为可停（只看是否正在播放），
     * 会在暂停后划卡片时 stopSelf → 通知消失、进程失去前台资格随即被杀，
     * 恰好抵消 manifest 里 `stopWithTask="false"` 的保活意图。
     * 这里改为：只要引擎里还有可续播的曲目（READY/BUFFERING，含暂停）就保活；
     * 真正空闲（IDLE / 已播完）才停掉自己。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        val keepAlive = p != null && runCatching {
            when (p.playbackState) {
                Player.STATE_READY, Player.STATE_BUFFERING -> true
                else -> false
            }
        }.getOrDefault(false)
        if (keepAlive) return
        stopSelf()
        // 故意不调 super：其默认逻辑与上述意图冲突（见 KDoc）。
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        // SharedMedia3Player is released by the playback controller lifecycle.
        player = null
        super.onDestroy()
    }
}
